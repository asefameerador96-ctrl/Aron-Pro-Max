#!/usr/bin/env python3
"""Lists the release APKs of the three apps, ready for the size gate and the signing loops (F-SYS-036, AUD-DG-03,
docs/requests/android-core-abi-splits.md). Works for both build layouts, so android-core can turn ABI splits on
without breaking CI:
  one APK per app        app-sr-release-unsigned.apk                       -> variant "universal"
  ABI splits per app     app-sr-arm64-v8a-release-unsigned.apk, ...,
                         app-sr-universal-release-unsigned.apk             -> variants "arm64-v8a", ..., "universal"
Every app must have exactly one universal APK (the device-owner provisioning file, docs/24 s10); an unexpected file
name fails, so a new layout is a reviewed change here.

Prints one tab-separated line per APK: <app> <variant> <gate name> <file stem for the signed copy> <path>
  gate name  sr-release (universal; keeps the existing baseline) or sr-release-arm64-v8a
  file stem  aron-sr (universal) or aron-sr-arm64-v8a; the caller appends the version and ".apk"
Usage: release-apks.py [repo root, default .]
"""
import re
import sys
from pathlib import Path

ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64", "x86")


def main():
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    errors, lines = [], []
    for app in ("sr", "amo", "tso"):
        folder = root / "android" / f"app-{app}" / "build" / "outputs" / "apk" / "release"
        files = sorted(folder.glob("*.apk"))
        pattern = re.compile(rf"^app-{app}(?:-({'|'.join(map(re.escape, ABIS))}|universal))?-release(?:-unsigned)?\.apk$")
        variants = {}
        for f in files:
            m = pattern.match(f.name)
            if not m:
                errors.append(f"{app}: unexpected release output {f.name}")
                continue
            v = m.group(1) or "universal"
            if v in variants:
                errors.append(f"{app}: two {v} APKs ({variants[v].name}, {f.name})")
            variants[v] = f
        if "universal" not in variants:
            errors.append(f"{app}: no universal release APK in {folder} (found {[f.name for f in files]})")
            continue
        for v in ["universal"] + [a for a in ABIS if a in variants]:
            gate = f"{app}-release" if v == "universal" else f"{app}-release-{v}"
            stem = f"aron-{app}" if v == "universal" else f"aron-{app}-{v}"
            lines.append("\t".join((app, v, gate, stem, str(variants[v]))))
    for e in errors:
        print(f"::error::{e}", file=sys.stderr)
    if errors:
        return 1
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main())
