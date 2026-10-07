#!/usr/bin/env python3
"""Wall-clock reads in test sources (lead item 2026-10-07, after ConfigToolsTest went red depending on the hour CI ran).

A test that reads the real clock passes or fails by time of day. Scans Kotlin and Java test sources
  backend/**/src/test*/**, android/**/src/test*/**, shared/**/src/*Test*/**, db/**/src/test*/**
for LocalDate.now( LocalDateTime.now( LocalTime.now( Instant.now( OffsetDateTime.now( ZonedDateTime.now(
System.currentTimeMillis( Clock.systemUTC Clock.systemDefaultZone Clock.System.now( (kotlinx), and reports each one
with its module and owner lane. An intended read carries `// wall-clock-ok: <reason>` on the same line or the line
above (for example measuring elapsed time, which does not depend on the date).

Mode: REPORT (default; exit 0, warnings) until Day 5 (2026-10-09), then --blocking (exit 1 on any offender).
Usage: wallclock-scan.py [--blocking] [--markdown FILE] [repo root, default .]
"""
import re
import sys
from pathlib import Path

PATTERN = re.compile(r"\b(LocalDate|LocalDateTime|LocalTime|Instant|OffsetDateTime|ZonedDateTime)\.now\(|"
                     r"System\.currentTimeMillis\(|Clock\.system(UTC|DefaultZone)\b|Clock\.System\.now\(")
ESCAPE = "wall-clock-ok:"
GLOBS = ["backend/*/src/test*/**/*", "android/*/src/test*/**/*", "shared/*/src/*Test*/**/*", "db/src/test*/**/*"]


def owner(path):
    parts = path.parts
    top = parts[0]
    if top == "android":
        mod = parts[1]
        if mod.startswith("core-") or mod == "dpc":
            return "android-core/geo/dpc/print"
        if mod.startswith("feature-") or mod == "app-sr":
            return "android-sr"
        return {"app-amo": "android-amo", "app-tso": "android-tso"}.get(mod, "android")
    return top  # backend, shared, db


def module(path):
    parts = path.parts
    return "/".join(parts[:2]) if parts[0] in ("backend", "android", "shared") else parts[0]


def scan(root):
    hits = []
    for g in GLOBS:
        for f in sorted(root.glob(g)):
            if f.suffix not in (".kt", ".java") or not f.is_file():
                continue
            lines = f.read_text(encoding="utf-8", errors="replace").splitlines()
            for i, line in enumerate(lines):
                if PATTERN.search(line) and ESCAPE not in line and not (i > 0 and ESCAPE in lines[i - 1]):
                    rel = f.relative_to(root)
                    hits.append((owner(rel), module(rel), f"{rel}:{i + 1}", line.strip()[:120]))
    return hits


def main():
    args = [a for a in sys.argv[1:]]
    blocking = "--blocking" in args
    md = args[args.index("--markdown") + 1] if "--markdown" in args else None
    rest = [a for a in args if not a.startswith("--") and a != md]
    root = Path(rest[0] if rest else ".")
    hits = scan(root)
    level = "error" if blocking else "warning"
    for own, mod, where, line in hits:
        file, _, ln = where.rpartition(":")
        print(f"::{level} file={file},line={ln}::wall-clock read in a test ({own}): {line}")
    counts = {}
    for own, mod, _, _ in hits:
        counts[(own, mod)] = counts.get((own, mod), 0) + 1
    print(f"wall-clock scan: {len(hits)} offender(s) in {len(counts)} module(s) ({'blocking' if blocking else 'report only'})")
    if md:
        with open(md, "w", encoding="utf-8") as out:
            out.write("| Owner lane | Module | Reads |\n|---|---|---|\n")
            for (own, mod), n in sorted(counts.items()):
                out.write(f"| {own} | `{mod}` | {n} |\n")
    return 1 if (blocking and hits) else 0


if __name__ == "__main__":
    sys.exit(main())
