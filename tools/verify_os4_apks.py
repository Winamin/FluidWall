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
RENDERER = PREFIX + "opengl/ImageWallpaperRenderer;"
ANIM = PREFIX + "opengl/AnimImageWallpaperRenderer;"
DESKTOP_RENDERER = PREFIX + "container/openGL/DesktopAnimImageWallpaperRenderer;"
HANDLER = "Landroid/os/Handler;"
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


def object_getter(source: Method, expected_type: str) -> str:
    require(len(source.instructions) == 2, f"Expected a direct object getter: {source.target}")
    get = re.fullmatch(r"iget-object (v\d+), (v\d+), (\S+) // field@[0-9a-fA-F]+",
                       source.instructions[0])
    require(get is not None, f"Expected iget-object in {source.target}")
    require(get.group(2) == f"v{source.registers - source.ins}",
            f"Getter does not read its own instance: {source.target}")
    require(get.group(3).endswith(":" + expected_type), f"Unexpected getter field type: {source.target}")
    require(source.instructions[1] == "return-object " + get.group(1),
            f"Getter does not return the loaded field: {source.target}")
    return get.group(3)


def check_wallpaper(classes: dict[str, DexClass]) -> None:
    for child, parent in ((DESKTOP, ENGINE), (DESKTOP_RENDERER, ANIM), (ANIM, RENDERER),
                          (SERVICE[:-1] + "$UniversalEngine;", "Landroid/service/wallpaper/WallpaperService$Engine;")):
        require(child in classes and classes[child].parent == parent,
                f"Changed inheritance: expected {child} extends {parent}")

    constructor = method(classes, DESKTOP, "<init>", f"({SERVICE})V")
    method(classes, DESKTOP, "F", f"({SERVICE[:-1]}$UniversalEngine;)V")
    handler_getter = method(classes, ENGINE, "G", f"(){HANDLER}")
    method(classes, ENGINE, "q", "()V")
    renderer_getter = method(classes, DESKTOP, "P", f"(){RENDERER}")
    for name, signature in (("hideKeyguardWallpaper", "(ZI)V"),
                            ("hideKeyguardWallpaper", "()V"),
                            ("showWallpaperUnlockAnim", "()V"),
                            ("showKeyguardWallpaper", "(ZI)V")):
        method(classes, DESKTOP, name, signature)
    renderer_constructor = method(classes, DESKTOP_RENDERER, "<init>", f"({SERVICE}{HANDLER})V")
    method(classes, DESKTOP_RENDERER, "finish", "()V")
    method(classes, DESKTOP_RENDERER, "finish", "()V")
    for name in ("onDrawFrame", "finish", "onSurfaceCreated"):
        method(classes, RENDERER, name, "()V")
    refresh = method(classes, ANIM, "refresh", "(Z)V")
    print("PASS: exact hook signatures and class inheritance")

    renderer_field = object_getter(renderer_getter, DESKTOP_RENDERER)
    handler_field = object_getter(handler_getter, HANDLER)
    renderer_calls = [(index, registers) for index, target, registers in calls(constructor)
                      if target == renderer_constructor.target]
    require(len(renderer_calls) == 1, "Expected one desktop renderer construction")
    index, registers = renderer_calls[0]
    require(len(registers) == 3, "Unexpected renderer constructor register layout")
    instance, service, handler = registers
    instructions = constructor.instructions
    require(index >= 3, "Truncated renderer constructor instructions")
    require(instructions[index - 3].startswith(f"new-instance {instance}, {DESKTOP_RENDERER} //"),
            "Renderer constructor does not operate on the new desktop renderer")
    require(re.fullmatch(rf"iget-object {service}, v\d+, \S+:{re.escape(SERVICE)} // field@[0-9a-fA-F]+",
                         instructions[index - 2]) is not None,
            "Renderer constructor no longer receives the engine's wallpaper service")
    require(re.fullmatch(rf"iget-object {handler}, v\d+, {re.escape(handler_field)} // field@[0-9a-fA-F]+",
                         instructions[index - 1]) is not None,
            "Renderer constructor Handler differs from engine.G()")
    this_register = f"v{constructor.registers - constructor.ins}"
    require(re.fullmatch(rf"iput-object {instance}, {this_register}, {re.escape(renderer_field)} // field@[0-9a-fA-F]+",
                         instructions[index + 1]) is not None,
            "engine.P() does not return the desktop renderer created by the engine")
    print(f"PASS: engine.P() returns the constructed desktop renderer ({renderer_field})")
    print("PASS: engine.G() returns the same GL Handler passed to the renderer")

    require(("onDrawFrame", "()V") not in classes[DESKTOP_RENDERER].methods,
            "Desktop renderer now overrides onDrawFrame; review dispatch")
    inherited_draw = method(classes, ANIM, "onDrawFrame", "()V")
    call_index(inherited_draw, f"{RENDERER}.onDrawFrame:()V")
    require(len(inherited_draw.instructions) == 2 and inherited_draw.instructions[-1] == "return-void",
            "Anim renderer onDrawFrame now does more than delegate; review after-hook placement")
    render = method(classes, ENGINE, "M", "(Z)V")
    prepare = call_index(render, f"{ENGINE}.V:()V")
    draw = call_index(render, f"{RENDERER}.onDrawFrame:()V")
    swaps = []
    for index, target, _ in calls(render):
        match = re.fullmatch(r"(L[^;]+;)\.([^:]+):(\(.*)", target)
        if match and match.group(1) in classes:
            candidate = classes[match.group(1)].methods.get((match.group(2), match.group(3)))
            if candidate and any(called == "Landroid/opengl/EGL14;.eglSwapBuffers:(Landroid/opengl/EGLDisplay;Landroid/opengl/EGLSurface;)Z"
                                 for _, called, _ in calls(candidate)):
                swaps.append(index)
    require(len(swaps) == 1 and prepare < draw < swaps[0],
            "Cannot prove prepare -> renderer.onDrawFrame -> EGL swap instruction order")
    require(not any(re.match(r"(?:if-|goto|return|throw|.*switch)", instruction)
                    for instruction in render.instructions[draw + 1:swaps[0]]),
            "Control flow changed between renderer.onDrawFrame and EGL swap")
    print("PASS: inherited onDrawFrame runs after EGL preparation and before eglSwapBuffers")

    callback = f"{ANIM[:-1]}$CallBack;.onRefresh:(Z)V"
    call_index(refresh, callback)
    on_refresh = method(classes, DESKTOP[:-1] + "$1;", "onRefresh", "(Z)V")
    call_index(on_refresh, f"{ENGINE}.U:(Z)V")
    request = method(classes, ENGINE, "U", "(Z)V")
    thread_utils = PREFIX + "utils/ThreadUtils;"
    call_index(request, f"{thread_utils}.b:({HANDLER}Ljava/lang/Runnable;)V")
    thread_class = classes.get(thread_utils)
    dispatch = thread_class.methods.get(("b", f"({HANDLER}Ljava/lang/Runnable;)V")) if thread_class else None
    require(dispatch is not None, "Missing ThreadUtils.b Handler dispatcher")
    for target in ("Landroid/os/Looper;.myLooper:()Landroid/os/Looper;",
                   "Ljava/lang/Runnable;.run:()V",
                   "Landroid/os/Handler;.post:(Ljava/lang/Runnable;)Z"):
        call_index(dispatch, target)
    print("PASS: refresh callback reaches GL dispatcher (same-thread run is synchronous)")


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
