#!/usr/bin/env python3
"""APK size gate (F-SYS-036, docs/31 s1, docs/20 T-1-33). Owner: infra lane.

For each APK and each ABI it can run on, measures:
  download  bytes a phone of that ABI gets: the APK minus the native libraries of the OTHER ABIs (what a per-ABI
            split or an ABI-filtered build would ship); for an APK without native code every ABI gets the same size;
  installed an estimate of the on-device footprint: the APK plus the uncompressed size of that ABI's native libraries
            (extracted at install when extractNativeLibs is on) plus the uncompressed size of classes*.dex (the
            runtime keeps an optimised copy). An estimate, not a device measurement; the device check confirms it.
  universal the APK file itself, when it carries several ABIs (what ships until the build splits per ABI).
Then compares with tools/ci/apk-size-baseline.json:
  download above 30 MB, or installed above 70 MB          -> FAIL (absolute budget)
  download more than 15 % above the baseline              -> FAIL
  download more than  5 % above the baseline              -> WARNING
An APK or ABI with no baseline entry is checked against the absolute budget only and printed as a baseline to add.
Usage: tools/ci/apk-size-gate.py [--write-baseline] <name>=<path.apk> ...
"""
import json
import os
import sys
import zipfile

MB = 1024 * 1024
ABS_DOWNLOAD_MB, ABS_INSTALLED_MB = 30, 70
WARN_PCT, FAIL_PCT = 5, 15
ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
BASELINE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "apk-size-baseline.json")


def measure(path):
    total = os.path.getsize(path)
    with zipfile.ZipFile(path) as z:
        infos = z.infolist()
    lib = {a: [i for i in infos if i.filename.startswith(f"lib/{a}/")] for a in ABIS}
    present = [a for a in ABIS if lib[a]] or ["any"]
    dex = sum(i.file_size for i in infos if i.filename.startswith("classes") and i.filename.endswith(".dex"))
    out = {}
    for abi in present:
        # Data plus the zip local header (30 bytes + name) and central directory entry (46 bytes + name) per file.
        others = sum(i.compress_size + 76 + 2 * len(i.filename) for a in ABIS if a != abi for i in lib.get(a, []))
        own_native = sum(i.file_size for i in lib.get(abi, []))
        download = total - others
        out[abi] = {"download": download, "installed": download + own_native + dex}
    if len(present) > 1:
        # The file as built (a universal APK installs on every ABI); the same budgets apply to it.
        out["universal"] = {"download": total,
                            "installed": total + max(sum(i.file_size for i in lib[a]) for a in present) + dex}
    return out


def main(argv):
    write = "--write-baseline" in argv
    pairs = [a for a in argv if a != "--write-baseline"]
    if not pairs:
        sys.exit(__doc__)
    baseline = json.load(open(BASELINE)) if os.path.exists(BASELINE) else {}
    failed, measured = False, {}
    summary = ["| APK | ABI | download | installed (est.) | baseline | change |", "|---|---|---|---|---|---|"]
    for pair in pairs:
        name, path = pair.split("=", 1)
        measured[name] = measure(path)
        for abi, m in measured[name].items():
            d, inst = m["download"], m["installed"]
            base = baseline.get(name, {}).get(abi)
            change = ""
            if d > ABS_DOWNLOAD_MB * MB:
                print(f"::error::{name} {abi}: {d / MB:.2f} MB download is above the {ABS_DOWNLOAD_MB} MB budget"); failed = True
            if inst > ABS_INSTALLED_MB * MB:
                print(f"::error::{name} {abi}: {inst / MB:.2f} MB installed (estimate) is above the {ABS_INSTALLED_MB} MB budget"); failed = True
            if base:
                pct = (d - base) * 100.0 / base
                change = f"{pct:+.1f} %"
                if pct > FAIL_PCT:
                    print(f"::error::{name} {abi}: {d / MB:.2f} MB is {pct:.1f} % above the baseline {base / MB:.2f} MB (fail above +{FAIL_PCT} %)"); failed = True
                elif pct > WARN_PCT:
                    print(f"::warning::{name} {abi}: {d / MB:.2f} MB is {pct:.1f} % above the baseline {base / MB:.2f} MB (warn above +{WARN_PCT} %)")
            else:
                print(f"::notice::{name} {abi}: no baseline yet; measured {d} bytes (add it to tools/ci/apk-size-baseline.json)")
            summary.append(f"| {name} | {abi} | {d / MB:.2f} MB | {inst / MB:.2f} MB | "
                           f"{f'{base / MB:.2f} MB' if base else 'none'} | {change} |")
    print("\n".join(summary))
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as f:
            f.write("### APK sizes (F-SYS-036)\n" + "\n".join(summary) + "\n")
    if write:
        new = {n: {abi: m["download"] for abi, m in v.items()} for n, v in measured.items()}
        with open(BASELINE, "w") as f:
            json.dump({**baseline, **new}, f, indent=2, sort_keys=True)
            f.write("\n")
        print(f"baseline written to {BASELINE}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
