#!/usr/bin/env python3
"""Per-class summary of the Android gate test classes (D-AGATE-32), read from the JUnit XML that Gradle already writes.

One line per class, from the log of the Android job alone:

    GATE-TEST-SUMMARY <class> module=<module> tests=<n> failed=<n> skipped=<n>

A class with no result file prints `GATE-TEST-SUMMARY <class> NOT-RUN` and a `::warning::` annotation (a class renamed or not run in this job); an unparsable result file prints `UNREADABLE` (also a warning). Results of all files of a class are summed. Report only: this step never
fails the job (the Gradle step is the gate) and changes no gate or budget. It reads the result files, not the console, so a build-cache hit
(which prints no test lines) still shows what the cached run did. Usage: android-gate-test-summary.py [android-dir] [--write-step-summary]
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

# The five classes that pin the Developer options gate, the late policy and the day lock (docs/status/android-gates-3.md). Change this list only with that file.
CLASSES = [
    "com.aktcl.aron.core.database.EveryWriterIsGatedTest",
    "com.aktcl.aron.core.database.ActionGateWriteTest",
    "com.aktcl.aron.sr.SrLatePolicyTest",
    "com.aktcl.aron.amo.AmoLatePolicyTest",
    "com.aktcl.aron.sr.SrKitLockTest",
]


def summarise(android_dir):
    """One row per class: (class, module, tests, failed, skipped, status) with status NOT-RUN, UNREADABLE or OK.
    Sums over ALL result files of the class (several modules or test task variants), so one file never hides another."""
    rows = []
    for cls in CLASSES:
        files = sorted(glob.glob(os.path.join(android_dir, "*", "build", "test-results", "*", "TEST-%s.xml" % cls)))
        if not files:
            rows.append((cls, None, 0, 0, 0, "NOT-RUN"))
            continue
        modules, tests, failed, skipped, status = [], 0, 0, 0, "OK"
        for f in files:
            modules.append(f.replace(os.sep, "/").split("/")[-5])
            try:
                suite = ET.parse(f).getroot()
                tests += int(suite.get("tests", 0))
                failed += int(suite.get("failures", 0)) + int(suite.get("errors", 0))
                skipped += int(suite.get("skipped", 0))
            except (ET.ParseError, OSError, ValueError):
                status = "UNREADABLE"
        rows.append((cls, ",".join(sorted(set(modules))), tests, failed, skipped, status))
    return rows


def main(argv):
    args = [a for a in argv[1:] if not a.startswith("--")]
    android_dir = args[0] if args else "android"
    rows = summarise(android_dir)
    lines = []
    for cls, module, tests, failed, skipped, status in rows:
        short = cls.rsplit(".", 1)[1]
        if status == "NOT-RUN":
            lines.append("GATE-TEST-SUMMARY %s NOT-RUN" % short)
            print("::warning title=Gate test class not run::%s has no result file in this job (renamed, moved or not run)" % short)
        elif status == "UNREADABLE":
            lines.append("GATE-TEST-SUMMARY %s module=%s UNREADABLE" % (short, module))
            print("::warning title=Gate test result unreadable::%s: a result file could not be parsed" % short)
        else:
            lines.append("GATE-TEST-SUMMARY %s module=%s tests=%d failed=%d skipped=%d" % (short, module, tests, failed, skipped))
    print("\n".join(lines))
    step = os.environ.get("GITHUB_STEP_SUMMARY")
    if "--write-step-summary" in argv and step:
        with open(step, "a", encoding="utf-8") as f:
            f.write("### Android gate test classes\n\n| class | module | tests | failed | skipped | status |\n|---|---|---|---|---|---|\n")
            for cls, module, tests, failed, skipped, status in rows:
                f.write("| %s | %s | %s | %s | %s | %s |\n" % (cls.rsplit(".", 1)[1], module or "-", tests, failed, skipped, status))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
