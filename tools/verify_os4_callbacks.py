#!/usr/bin/env python3
"""Check compiled OS4 callback dispatch, including Kotlin lexical-shadowing regressions.

Run after :app:compileReleaseKotlin:
    python tools/verify_os4_callbacks.py --javap PATH_TO_JDK/bin/javap

Reads bytecode rather than source names. In 1.0.2, Method variables named bind/lock
captured the implicit Playback calls; javap shows Method.invoke(Boolean/String, []).
That code compiles successfully, so an APK signature/contract check cannot catch it.
This check does not execute Xposed or verify Android rendering.
"""
import argparse
from collections import Counter
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
OWNER = 'com/txf/fluidwall/xposed/HookOs4Wallpaper'
PLAYBACK = OWNER + '$Playback'


def check_dispatch(classes, javap):
    directory = classes / 'com/txf/fluidwall/xposed'
    files = sorted(directory.glob('HookOs4Wallpaper*.class'))
    if not files:
        raise ValueError(f'No compiled OS4 callbacks under {classes}; compile Kotlin first')
    counts = Counter()
    failures = []
    inspected = 0
    for file in files:
        if file.stem.startswith('HookOs4Wallpaper$Playback'):
            continue  # Playback.requestFrame intentionally reflects the host's refresh method.
        name = file.relative_to(classes).with_suffix('').as_posix().replace('/', '.')
        result = subprocess.run([str(javap), '-p', '-c', '-s', '-classpath', str(classes), name],
                                capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=30)
        if result.returncode:
            raise ValueError(f'javap failed for {name}: {result.stderr.strip()}')
        for method in re.split(r'(?m)^  (?=\S)', result.stdout):
            descriptor = re.search(r'descriptor: (\([^\n]+)', method)
            if not descriptor or f'L{PLAYBACK};' not in descriptor.group(1).split(')')[0]:
                continue
            inspected += 1
            title = method.splitlines()[0].strip()
            if re.search(r'// Method java/lang/reflect/Method\.invoke:', method):
                failures.append(f'{name}.{title}: dispatches via Method.invoke instead of Playback')
            for target in re.findall(r'// Method ' + re.escape(PLAYBACK) + r'\.([^\s]+)', method):
                counts[target] += 1
    required = {
        'bind:(Z)V': 1,
        'lock:(Ljava/lang/String;)V': 2,  # keyguard Hook and screen-off receiver
        'unlock:(Ljava/lang/String;)V': 2,  # engine Hook and user-present receiver
        'reload:()V': 1,
        'surfaceLost:()V': 1,
    }
    for target, minimum in required.items():
        if counts[target] < minimum:
            failures.append(f'{target}: found {counts[target]} direct calls, expected at least {minimum}')
    if failures:
        raise ValueError('\n'.join(failures))
    print(f'PASS: {inspected} Playback callback methods contain no reflective dispatch')
    print('PASS: bind, keyguard, screen-off, unlock, reload and surface-loss call Playback directly')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--classes', type=Path, default=ROOT / 'app/build/intermediates/built_in_kotlinc/release/compileReleaseKotlin/classes')
    parser.add_argument('--javap', type=Path)
    args = parser.parse_args()
    javap = args.javap
    if javap is None and os.environ.get('JAVA_HOME'):
        javap = Path(os.environ['JAVA_HOME']) / 'bin' / ('javap.exe' if os.name == 'nt' else 'javap')
    if javap is None:
        found = shutil.which('javap')
        javap = Path(found) if found else None
    try:
        if javap is None or not javap.is_file():
            raise ValueError('Set --javap or JAVA_HOME to an installed JDK')
        check_dispatch(args.classes.resolve(), javap.resolve())
        return 0
    except (ValueError, OSError, subprocess.TimeoutExpired) as error:
        print(f'FAIL: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
