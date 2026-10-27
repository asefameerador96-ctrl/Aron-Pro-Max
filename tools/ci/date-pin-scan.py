#!/usr/bin/env python3
"""Constant-date gate for seed-dependent tests (D-TEST-DATE-PIN-01, D-QA-DATE-PIN-GATE-01).

The dev seed (db/seed/06_dev_gulshan_banani.sql) starts at today's Dhaka date and rolls forward, so a test that loads it
and pins a literal 2026 date passes or fails by the day it runs (22 DataFlowTrace tests failed at Dhaka midnight). This
gate scans test sources
  backend/**/src/test*/**, db/src/test*/**, android/**/src/(test|androidTest)*/**
and fails when a file that uses the dev seed contains a `2026-MM-DD` literal in code (comments, `//` and `/* */`, are\nignored; a `2026-..-..T..Z` timestamp counts).

A file "uses the dev seed" when it mentions one of: 06_dev_gulshan_banani, SeedLoader (loads every db/seed file, 06
included), DataFlowTrace, devProfile (the seed as dev loads it), GUL-SR-, BAN-SR- (route codes that exist only in 06).

Ways to pass, each with a reason:
  - derive the day from the seed clock instead of a literal (the fix);
  - `// date-pin-ok: <reason>` on the line or the line above (a literal that is a fact of the test, not a day of the run);
  - an allow-list row in tools/ci/date-pin-allow.txt: `<literal> | <reason>` (a date the seed itself names, anywhere);
  - a baseline row in tools/ci/date-pin-baseline.txt: `<path> | <literal> | <owner lane>`. The baseline is SHRINK-ONLY
    (the offenders found when the gate was added; each lane deletes its rows with the fix): a literal not in it fails, a
    row nothing matches any more is reported as stale (a notice, not a failure).

Usage: date-pin-scan.py [--list] [repo root, default .]   (--list prints every offender, baselined or not, as a table)
"""
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
LITERAL = re.compile(r"(?<![\w-])2026-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])(?![\d-])")
SEED = re.compile(r"06_dev_gulshan_banani|SeedLoader|DataFlowTrace|devProfile|GUL-SR-|BAN-SR-")
ESCAPE = "date-pin-ok:"
GLOBS = ["backend/*/src/test*/**/*", "db/src/test*/**/*", "android/*/src/test*/**/*", "android/*/src/androidTest*/**/*"]


def owner(path):
    parts = path.parts
    if parts[0] == "android":
        mod = parts[1]
        if mod.startswith("core-") or mod == "dpc":
            return "android-core/geo/dpc/print"
        if mod.startswith("feature-") or mod == "app-sr":
            return "android-sr"
        return {"app-amo": "android-amo", "app-tso": "android-tso"}.get(mod, "android")
    if parts[0] == "backend":
        return "backend-" + parts[1] if len(parts) > 1 else "backend"
    return parts[0]


def code_lines(text):
    """Each line with its comments blanked: `//` and `/* */` outside string literals (quoted, raw triple-quoted, char)."""
    out, in_block, in_raw = [], False, False
    for line in text.splitlines():
        res, i, n, in_str = [], 0, len(line), False
        while i < n:
            two, three = line[i:i + 2], line[i:i + 3]
            if in_block:
                if two == "*/":
                    in_block = False
                    i += 2
                else:
                    i += 1
                continue
            if in_raw:
                if three == '"""':
                    in_raw = False
                    res.append(three)
                    i += 3
                else:
                    res.append(line[i])
                    i += 1
                continue
            c = line[i]
            if in_str:
                res.append(c)
                if c == "\\" and i + 1 < n:
                    res.append(line[i + 1])
                    i += 2
                    continue
                if c == '"':
                    in_str = False
                i += 1
                continue
            if three == '"""':
                in_raw = True
                res.append(three)
                i += 3
            elif c == '"':
                in_str = True
                res.append(c)
                i += 1
            elif c == "'" and i + 2 < n and (line[i + 2] == "'" or (line[i + 1] == "\\" and i + 3 < n and line[i + 3] == "'")):
                j = line.index("'", i + 2)
                res.append(line[i:j + 1])
                i = j + 1
            elif two == "//":
                break
            elif two == "/*":
                in_block = True
                i += 2
            else:
                res.append(c)
                i += 1
        out.append("".join(res))
    return out


def rows(path):
    out = []
    if not path.exists():
        return out
    for raw in path.read_text(encoding="utf-8").splitlines():
        raw = raw.strip()
        if raw and not raw.startswith("#"):
            out.append([c.strip() for c in raw.split("|")])
    return out


def scan(root):
    hits = []
    for g in GLOBS:
        for f in sorted(root.glob(g)):
            if f.suffix not in (".kt", ".java") or not f.is_file():
                continue
            text = f.read_text(encoding="utf-8", errors="replace")
            lines = text.splitlines()
            code = code_lines(text)
            if not any(SEED.search(c) for c in code):
                continue   # a comment that names the seed does not make a test depend on it
            rel = f.relative_to(root)
            for i, line in enumerate(code):
                for m in LITERAL.finditer(line):
                    if ESCAPE in lines[i] or (i > 0 and ESCAPE in lines[i - 1]):
                        continue
                    hits.append((owner(rel), rel.as_posix(), i + 1, m.group(0), lines[i].strip()[:110]))
    return hits


def main():
    args = sys.argv[1:]
    listing = "--list" in args
    rest = [a for a in args if not a.startswith("--")]
    root = Path(rest[0] if rest else ".")
    allow = {r[0] for r in rows(HERE / "date-pin-allow.txt")}
    for r in rows(HERE / "date-pin-allow.txt"):
        if len(r) < 2 or not r[1]:
            print(f"::error file=tools/ci/date-pin-allow.txt::allow-list row without a reason: {r[0]}")
            return 1
    base = {(r[0], r[1]) for r in rows(HERE / "date-pin-baseline.txt") if len(r) >= 2}
    hits = [h for h in scan(root) if h[3] not in allow]
    if listing:
        for own, rel, ln, lit, line in hits:
            print(f"{own}\t{rel}:{ln}\t{lit}\t{'baseline' if (rel, lit) in base else 'NEW'}\t{line}")
    new = [h for h in hits if (h[1], h[3]) not in base]
    seen = {(h[1], h[3]) for h in hits}
    for own, rel, ln, lit, line in new:
        print(f"::error file={rel},line={ln}::constant date {lit} in a seed-dependent test ({own}): derive the day from the "
              f"seed clock (D-TEST-DATE-PIN-01) or add `// date-pin-ok: <reason>`: {line}")
    for rel, lit in sorted(base - seen):
        print(f"::notice file=tools/ci/date-pin-baseline.txt::stale baseline row (fixed): {rel} | {lit}: delete it")
    print(f"date-pin scan: {len(hits)} literal(s) in seed-dependent tests, {len(hits) - len(new)} baselined, {len(new)} new")
    return 1 if new else 0


if __name__ == "__main__":
    sys.exit(main())
