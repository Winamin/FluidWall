#!/usr/bin/env python3
"""Check FluidWall's OS4 hook contract against original APKs, without a device.

Requires Python 3.10+ and Android SDK build-tools (aapt and dexdump). Examples:
    python tools/verify_os4_apks.py
    python tools/verify_os4_apks.py --sdk /path/to/android-sdk --apk-dir /path/to/apks
    python tools/verify_os4_apks.py --aapt /path/to/aapt --dexdump /path/to/dexdump

The default APK directory and local.properties are relative to this repository,
not the working directory. No JADX output, downloads, ADB, or third-party Python
packages are used. A missing or changed contract returns a nonzero exit status.
Passing confirms static APK structure and instructions, not on-device visibility.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass, field
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[1]
PREFIX = "Lcom/miui/miwallpaper/"
SERVICE = PREFIX + "wallpaperservice/UniversalWallpaper;"
ENGINE = PREFIX + "wallpaperservice/impl/ImageEngineImpl;"
DESKTOP = PREFIX + "wallpaperservice/impl/desktop/DesktopImageEngineImpl;"
UNIVERSAL = SERVICE[:-1] + "$UniversalEngine;"
ENGINE_SERVICE = PREFIX + "wallpaperservice/service/EngineService;"
SURFACE_CONTROL = "Landroid/view/SurfaceControl;"
SURFACE_HOLDER = "Landroid/view/SurfaceHolder;"
DEX_NAME = re.compile(r"classes(?:\d+)?\.dex\Z")


class ContractError(Exception):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ContractError(message)


def run(tool: Path, *args: str, cwd: Path) -> str:
    result = subprocess.run(
        [str(tool), *args], cwd=cwd, capture_output=True,
        encoding="utf-8", errors="replace", timeout=180,
    )
    require(result.returncode == 0,
            f"{tool.name} exited {result.returncode}: {result.stderr.strip()[:1200]}")
    return result.stdout


def sdk_candidates(explicit: Path | None) -> list[Path]:
    if explicit is not None:
        return [explicit]
    candidates = [Path(value) for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT")
                  if (value := os.environ.get(key))]
    properties = ROOT / "local.properties"
    if properties.exists():
        match = re.search(r"^sdk\.dir\s*=\s*(.+)$", properties.read_text(encoding="utf-8"), re.M)
        if match:
            # Java properties escape drive colons and Windows backslashes.
            value = re.sub(r"\\(.)", r"\1", match.group(1).strip())
            candidates.append(Path(value))
    return candidates


def find_tool(name: str, explicit: Path | None, sdks: list[Path]) -> Path:
    if explicit is not None:
        require(explicit.is_file(), f"Tool does not exist: {explicit}")
        return explicit.resolve()
    filename = name + (".exe" if os.name == "nt" else "")
    for sdk in sdks:
        build_tools = sdk / "build-tools"
        if not build_tools.is_dir():
            continue
        versions = sorted(build_tools.iterdir(), key=lambda path: (
            tuple(int(n) for n in re.findall(r"\d+", path.name)), path.name), reverse=True)
        for version in versions:
            candidate = version / filename
            if candidate.is_file():
                return candidate.resolve()
    if located := shutil.which(filename):
        return Path(located).resolve()
    raise ContractError(f"Cannot find {filename}; set --sdk, --{name}, or ANDROID_HOME")


@dataclass
class Element:
    name: str
    attrs: dict[str, str] = field(default_factory=dict)
    children: list[Element] = field(default_factory=list)

    def children_named(self, name: str) -> list[Element]:
        return [child for child in self.children if child.name == name]


def parse_manifest(text: str) -> Element:
    stack: list[tuple[int, Element]] = []
    root = None
    for line in text.splitlines():
        if match := re.match(r"(\s*)E: (\S+)", line):
            indent = len(match.group(1))
            element = Element(match.group(2))
            while stack and stack[-1][0] >= indent:
                stack.pop()
            if stack:
                stack[-1][1].children.append(element)
            else:
                require(root is None, "Unexpected multiple manifest roots in aapt output")
                root = element
            stack.append((indent, element))
        elif match := re.match(r'\s*A: ([^=(]+)(?:\([^)]*\))?=(.*)', line):
            require(bool(stack), "Manifest attribute without an element")
            value = match.group(2)
            if quoted := re.match(r'"([^"\\]*(?:\\.[^"\\]*)*)"', value):
                value = quoted.group(1)
            stack[-1][1].attrs[match.group(1)] = value
    require(root is not None and root.name == "manifest", "aapt did not decode a manifest")
    return root


def boolean_attr(element: Element, name: str, default: bool | None = None) -> bool | None:
    value = element.attrs.get(name)
    if value is None:
        return default
    match = re.fullmatch(r"\(type 0x12\)0x([0-9a-fA-F]+)", value)
    require(match is not None, f"Unexpected boolean encoding for {name}: {value}")
    return int(match.group(1), 16) != 0


@dataclass
class Apk:
    path: Path
    manifest: Element
    entries: set[str]


def inspect_apks(directory: Path, aapt: Path, temp: Path) -> dict[str, Apk]:
    require(directory.is_dir(), f"APK directory does not exist: {directory}")
    packages: dict[str, Apk] = {}
    paths = sorted(directory.glob("*.apk"))
    require(bool(paths), f"No APKs in {directory}")
    for index, path in enumerate(paths):
        # Relative ASCII names also work with SDK tools that cannot open Unicode paths.
        copied = temp / f"input-{index}.apk"
        shutil.copyfile(path, copied)
        manifest = parse_manifest(run(aapt, "dump", "xmltree", copied.name,
                                      "AndroidManifest.xml", cwd=temp))
        package = manifest.attrs.get("package")
        require(bool(package), f"Missing package name in {path.name}")
        with zipfile.ZipFile(path) as archive:
            entries = set(archive.namelist())
        require(package not in packages, f"Duplicate package {package} in APK directory")
        packages[package] = Apk(path.resolve(), manifest, entries)
    return packages


@dataclass
class Method:
    owner: str
    name: str
    signature: str
    access: int
    registers: int
    ins: int
    instructions: list[str]

    @property
    def target(self) -> str:
        return f"{self.owner}.{self.name}:{self.signature}"


@dataclass
class DexClass:
    parent: str
    methods: dict[tuple[str, str], Method]


def parse_dexdump(text: str) -> dict[str, DexClass]:
    classes = {}
    for block in re.split(r"(?m)^Class #\d+\s+-\s*$", text)[1:]:
        owner = re.search(r"Class descriptor\s+: '([^']+)'", block)
        parent = re.search(r"Superclass\s+: '([^']+)'", block)
        require(owner is not None and parent is not None, "Unrecognized dexdump class header")
        methods = {}
        for member in re.split(r"(?m)^    #\d+\s+: \(in [^\r\n]+\)\s*$", block)[1:]:
            name = re.search(r"^      name\s+: '([^']+)'", member, re.M)
            signature = re.search(r"^      type\s+: '([^']+)'", member, re.M)
            require(name is not None and signature is not None, "Unrecognized dexdump member header")
            if not signature.group(1).startswith("("):
                continue  # Fields use the same member layout.
            access = re.search(r"^      access\s+: 0x([0-9a-fA-F]+)", member, re.M)
            registers = re.search(r"^      registers\s+: (\d+)", member, re.M)
            ins = re.search(r"^      ins\s+: (\d+)", member, re.M)
            require(access is not None, "Missing method access flags")
            instructions = re.findall(r"^[0-9a-fA-F]+:.*?\|[0-9a-fA-F]+: (.+)$", member, re.M)
            method = Method(owner.group(1), name.group(1), signature.group(1),
                            int(access.group(1), 16), int(registers.group(1)) if registers else 0,
                            int(ins.group(1)) if ins else 0, instructions)
            key = (method.name, method.signature)
            require(key not in methods, f"Duplicate method: {method.target}")
            methods[key] = method
        require(owner.group(1) not in classes, f"Duplicate DEX class: {owner.group(1)}")
        classes[owner.group(1)] = DexClass(parent.group(1), methods)
    require(bool(classes), "dexdump returned no class definitions")
    return classes


def load_dex(apk: Apk, dexdump: Path, temp: Path) -> dict[str, DexClass]:
    names = sorted(name for name in apk.entries if DEX_NAME.fullmatch(name))
    require(bool(names), "Wallpaper APK has no root classes*.dex")
    classes: dict[str, DexClass] = {}
    with zipfile.ZipFile(apk.path) as archive:
        for index, name in enumerate(names):
            data = archive.read(name)
            require(data.startswith(b"dex\n"), f"{name} is not a standard DEX file")
            dex = temp / f"wallpaper-{index}.dex"
            dex.write_bytes(data)
            parsed = parse_dexdump(run(dexdump, "-d", "-n", dex.name, cwd=temp))
            require(not (classes.keys() & parsed.keys()), "Duplicate class definitions across DEX files")
            classes.update(parsed)
    return classes


def method(classes: dict[str, DexClass], owner: str, name: str, signature: str,
           *, public: bool = True) -> Method:
    require(owner in classes, f"Missing DEX class: {owner}")
    result = classes[owner].methods.get((name, signature))
    require(result is not None, f"Missing declared method: {owner}.{name}:{signature}")
    require(not (result.access & (0x8 | 0x100 | 0x400)),
            f"Expected a concrete instance method: {result.target}")
    require(not public or bool(result.access & 1), f"Expected public method: {result.target}")
    require(bool(result.instructions), f"Missing disassembly: {result.target}")
    return result


def calls(method: Method) -> list[tuple[int, str, list[str]]]:
    result = []
    for index, instruction in enumerate(method.instructions):
        match = re.match(r"invoke-\S+ \{([^}]*)\}, (\S+) // method@", instruction)
        if match:
            registers = [register.strip() for register in match.group(1).split(",")]
            result.append((index, match.group(2), registers))
    return result


def call_index(source: Method, target: str) -> int:
    indexes = [index for index, called, _ in calls(source) if called == target]
    require(len(indexes) == 1, f"Expected exactly one call to {target} in {source.target}")
    return indexes[0]


def synchronized_object_getter(source: Method, expected_type: str) -> tuple[str, str]:
    """Return the object field and monitor field proved by a synchronized getter."""
    instructions = source.instructions
    require(len(instructions) == 8, f"Expected a synchronized object getter: {source.target}")
    this_register = f"v{source.registers - source.ins}"
    lock = re.fullmatch(rf"iget-object (v\d+), {this_register}, (\S+:Ljava/lang/Object;) // field@[0-9a-fA-F]+",
                        instructions[0])
    get = re.fullmatch(rf"iget-object (v\d+), {this_register}, (\S+:{re.escape(expected_type)}) // field@[0-9a-fA-F]+",
                       instructions[2])
    exception = re.fullmatch(r"move-exception (v\d+)", instructions[5])
    require(lock is not None and get is not None and exception is not None,
            f"Getter no longer reads its own monitor and object fields: {source.target}")
    monitor = lock.group(1)
    require(instructions[1] == f"monitor-enter {monitor}"
            and instructions[3] == f"monitor-exit {monitor}"
            and instructions[4] == f"return-object {get.group(1)}"
            and instructions[6] == f"monitor-exit {monitor}"
            and instructions[7] == f"throw {exception.group(1)}",
            f"Changed synchronized getter control flow: {source.target}")
    return get.group(2), lock.group(2)


def check_wallpaper(classes: dict[str, DexClass]) -> None:
    for child, parent in ((DESKTOP, ENGINE),
                          (UNIVERSAL, "Landroid/service/wallpaper/WallpaperService$Engine;")):
        require(child in classes and classes[child].parent == parent,
                f"Changed inheritance: expected {child} extends {parent}")

    method(classes, DESKTOP, "<init>", f"({SERVICE})V")
    bind = method(classes, DESKTOP, "F", f"({UNIVERSAL})V")
    finish = method(classes, DESKTOP, "L", "()V")
    for name, signature in (("hideKeyguardWallpaper", "(ZI)V"),
                            ("hideKeyguardWallpaper", "()V"),
                            ("showWallpaperUnlockAnim", "()V"),
                            ("showKeyguardWallpaper", "(ZI)V")):
        method(classes, DESKTOP, name, signature)
    getter = method(classes, UNIVERSAL, "c", f"(){SURFACE_CONTROL}")
    created = method(classes, UNIVERSAL, "onSurfaceCreated", f"({SURFACE_HOLDER})V")
    changed = method(classes, UNIVERSAL, "onSurfaceChanged", f"({SURFACE_HOLDER}III)V")
    destroyed = method(classes, UNIVERSAL, "onSurfaceDestroyed", f"({SURFACE_HOLDER})V")
    visibility = method(classes, UNIVERSAL, "onVisibilityChanged", "(Z)V")
    print("PASS: exact View/Surface hook signatures and wallpaper engine inheritance")

    this_register = f"v{bind.registers - bind.ins}"
    argument_register = f"v{bind.registers - bind.ins + 1}"
    require(re.fullmatch(rf"iput-object {argument_register}, {this_register}, \S+:{re.escape(UNIVERSAL)} // field@[0-9a-fA-F]+",
                         bind.instructions[0]) is not None,
            "DesktopImageEngineImpl.F() no longer stores its UniversalEngine argument")
    call_index(finish, f"{ENGINE}.L:()V")
    print("PASS: F() binds the UniversalEngine and declared L() delegates to engine teardown")

    surface_field, monitor_field = synchronized_object_getter(getter, SURFACE_CONTROL)
    print(f"PASS: UniversalEngine.c() returns its synchronized SurfaceControl cache ({surface_field})")

    instructions = created.instructions
    this_register = f"v{created.registers - created.ins}"
    names = [(index, match.group(1)) for index, instruction in enumerate(instructions)
             if (match := re.fullmatch(r'const-string(?:/jumbo)? (v\d+), "mSurfaceControl" // string@[0-9a-fA-F]+',
                                       instruction))]
    require(len(names) == 1, "Expected one mSurfaceControl reflection in onSurfaceCreated")
    index, name_register = names[0]
    require(index >= 2 and index + 5 < len(instructions), "Truncated SurfaceControl cache assignment")
    reflection = f"{PREFIX}utils/ReflectUtil;.c:({UNIVERSAL}Ljava/lang/String;)Ljava/lang/Object;"
    require((index + 1, reflection, [this_register, name_register]) in calls(created),
            "mSurfaceControl is no longer read by ReflectUtil.c from this UniversalEngine")
    result = re.fullmatch(r"move-result-object (v\d+)", instructions[index + 2])
    require(result is not None, "Missing reflected SurfaceControl result")
    result_register = result.group(1)
    require(re.fullmatch(rf"check-cast {result_register}, {re.escape(SURFACE_CONTROL)} // type@[0-9a-fA-F]+",
                         instructions[index + 3]) is not None
            and re.fullmatch(rf"iput-object {result_register}, {this_register}, {re.escape(surface_field)} // field@[0-9a-fA-F]+",
                             instructions[index + 4]) is not None,
            "Reflected mSurfaceControl is not cached in the field returned by c()")
    lock = re.fullmatch(rf"iget-object (v\d+), {this_register}, {re.escape(monitor_field)} // field@[0-9a-fA-F]+",
                        instructions[index - 2])
    require(lock is not None and instructions[index - 1] == f"monitor-enter {lock.group(1)}"
            and instructions[index + 5] == f"monitor-exit {lock.group(1)}",
            "SurfaceControl cache write no longer uses the getter's monitor")
    initialize = call_index(created, f"{ENGINE_SERVICE}.x:({SURFACE_HOLDER})V")
    require(initialize < index, "SurfaceControl cache timing changed relative to engine surface creation")
    print("PASS: onSurfaceCreated caches reflected mSurfaceControl after engine initialization")

    instructions = destroyed.instructions
    this_register = f"v{destroyed.registers - destroyed.ins}"
    clears = [(index, match.group(1)) for index, instruction in enumerate(instructions)
              if (match := re.fullmatch(rf"iput-object (v\d+), {this_register}, {re.escape(surface_field)} // field@[0-9a-fA-F]+",
                                        instruction))]
    require(len(clears) == 1, "Expected one SurfaceControl cache write in onSurfaceDestroyed")
    index, null_register = clears[0]
    # A conservative direct-register check: its last operation must define integer zero,
    # which is the DEX null value for iput-object; invocations cannot mutate caller registers.
    operations = [instruction for instruction in instructions[:index]
                  if re.match(rf"\S+ {null_register}(?:,|$)", instruction)]
    require(bool(operations) and re.fullmatch(rf"const(?:/4|/16)? {null_register}, #int 0 // #0",
                                            operations[-1]) is not None,
            "onSurfaceDestroyed does not clear the SurfaceControl cache to null")
    require(index >= 2 and index + 1 < len(instructions), "Truncated SurfaceControl cache clear")
    lock = re.fullmatch(rf"iget-object (v\d+), {this_register}, {re.escape(monitor_field)} // field@[0-9a-fA-F]+",
                        instructions[index - 2])
    require(lock is not None and instructions[index - 1] == f"monitor-enter {lock.group(1)}"
            and instructions[index + 1] == f"monitor-exit {lock.group(1)}",
            "SurfaceControl cache clear no longer uses the getter's monitor")
    # q() is lifecycle evidence, not a hook dependency of the View-based implementation.
    notify = call_index(destroyed, f"{ENGINE_SERVICE}.q:()V")
    require(index < notify, "Engine surface-loss notification precedes the SurfaceControl cache clear")
    print("PASS: onSurfaceDestroyed clears the same cache before EngineService.q() notification")

    for source, target, argument_offsets in (
            (changed, f"{ENGINE_SERVICE}.A:({SURFACE_HOLDER}II)V", (1, 3, 4)),
            (visibility, f"{ENGINE_SERVICE}.g:(Z)V", (1,))):
        index = call_index(source, target)
        registers = next(registers for call, _, registers in calls(source) if call == index)
        incoming = source.registers - source.ins
        require(registers[1:] == [f"v{incoming + offset}" for offset in argument_offsets],
                f"Lifecycle callback no longer forwards its original arguments: {source.target}")
    print("PASS: surface dimensions and visibility are forwarded to the engine unchanged")


def check_launcher(apk: Apk) -> None:
    applications = apk.manifest.children_named("application")
    require(len(applications) == 1, "Launcher manifest must contain exactly one application")
    application = applications[0]
    require(boolean_attr(application, "android:hasCode", True) is False,
            "Launcher no longer declares android:hasCode=false")
    require(not any(DEX_NAME.fullmatch(name) for name in apk.entries),
            "Native launcher unexpectedly contains root DEX files")
    libraries = application.children_named("uses-library")
    runtimes = [library for library in libraries
                if library.attrs.get("android:name") == "hyperos.rustruntime.v5"]
    require(len(runtimes) == 1 and boolean_attr(runtimes[0], "android:required", True) is True,
            "Launcher must require hyperos.rustruntime.v5")
    metadata = {element.attrs.get("android:name"): element
                for element in application.children_named("meta-data")}
    require("hyperos_package" in metadata
            and boolean_attr(metadata["hyperos_package"], "android:value") is True,
            "Missing native HyperOS package marker")
    library = metadata.get("hyperos_app_lib_name")
    require(library is not None and library.attrs.get("android:value") == "libapp_launcher.so",
            "Launcher native app library metadata changed")
    require("lib/arm64-v8a/libapp_launcher.so" in apk.entries,
            "Missing arm64 launcher native library")
    print("PASS: launcher hasCode=false, no DEX, required Rust runtime v5 and native app library")


def main() -> int:
    # SDK output is decoded as UTF-8 above; keep Unicode APK paths readable in pipes too.
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apk-dir", type=Path, default=ROOT / "apks")
    parser.add_argument("--sdk", type=Path)
    parser.add_argument("--aapt", type=Path)
    parser.add_argument("--dexdump", type=Path)
    args = parser.parse_args()
    try:
        sdks = sdk_candidates(args.sdk)
        aapt = find_tool("aapt", args.aapt, sdks)
        dexdump = find_tool("dexdump", args.dexdump, sdks)
        print(f"aapt: {aapt}\ndexdump: {dexdump}", flush=True)
        with tempfile.TemporaryDirectory(prefix="fluidwall-os4-") as directory:
            temp = Path(directory)
            packages = inspect_apks(args.apk_dir.resolve(), aapt, temp)
            for package in ("com.miui.miwallpaper", "com.miui.home"):
                require(package in packages, f"No {package} APK in {args.apk_dir}")
                apk = packages[package]
                digest = hashlib.sha256(apk.path.read_bytes()).hexdigest()
                print(f"APK: {package}\n  {apk.path}\n  SHA-256: {digest}", flush=True)
            check_launcher(packages["com.miui.home"])
            check_wallpaper(load_dex(packages["com.miui.miwallpaper"], dexdump, temp))
        print("PASS: OS4 static APK contract. Device rendering remains unverified.")
        return 0
    except (ContractError, OSError, zipfile.BadZipFile, subprocess.TimeoutExpired) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
