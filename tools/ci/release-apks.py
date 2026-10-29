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
       release-apks.py --tso-copy-name <versionCode> [<yyyy-mm-dd>]   (tso-manual A1, D-TSO-MAN-42)
  prints aron_tso_app_<dd_mm_yyyy>_v<versionCode>.apk, the name of the extra TSO copy the release adds next to the existing
  names (the updater keeps reading those); the date is the build date in Asia/Dhaka (UTC+6, no DST), today by default.
"""
import datetime
import re
import sys
from pathlib import Path

ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64", "x86")


DHAKA = datetime.timezone(datetime.timedelta(hours=6))


def tso_copy_name(version_code, build_date):
    """aron_tso_app_<dd_mm_yyyy>_v<versionCode>.apk (D-TSO-MAN-42, screenshot 1)."""
    code = int(version_code)
    if code <= 0:
        raise ValueError(f"versionCode must be positive: {version_code}")
    return f"aron_tso_app_{build_date.strftime('%d_%m_%Y')}_v{code}.apk"


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--tso-copy-name":
        if len(sys.argv) not in (3, 4):
            print("::error::usage: release-apks.py --tso-copy-name <versionCode> [<yyyy-mm-dd>]", file=sys.stderr)
            return 2
        day = datetime.date.fromisoformat(sys.argv[3]) if len(sys.argv) == 4 else datetime.datetime.now(DHAKA).date()
        try:
            print(tso_copy_name(sys.argv[2], day))
        except ValueError as e:
            print(f"::error::{e}", file=sys.stderr)
            return 2
        return 0
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
