#!/usr/bin/env python3
"""N-061: turn `gradle dependencies --configuration <c>` output into a gradle.lockfile that osv-scanner reads, so the
backend image and the release APKs get the same OSV gate (tools/ci/osv-gate.py) as web/package-lock.json.
Owner: qa. The repository has no Gradle lockfile or verification metadata yet (ci.yml: "joins it when it exists").

  ./gradlew -q :backend:app:dependencies --configuration runtimeClasspath > backend.txt
  ./gradlew -q :android:app-sr:dependencies --configuration releaseRuntimeClasspath > sr.txt
  python3 tools/ci/gradle-lockfile.py out/gradle.lockfile backend.txt sr.txt
  osv-scanner scan source --lockfile out/gradle.lockfile --format json > osv.json
  python3 tools/ci/osv-gate.py osv.json tools/ci/osv-allow.txt

Only resolved modules count: a constraint line "(c)" and an unresolved "(n)" or FAILED line are skipped; "a:b:1 -> 2"
and "a:b -> 2" take the resolved version 2;
a rich version "a:b:{prefer 1} -> 2" takes 2 (or 1 when nothing resolved it). Project dependencies are not modules and are skipped.
"""
import re
import sys
from pathlib import Path

LINE = re.compile(r"[+\\]--- ([\w.\-]+):([\w.\-]+)(?::(\{[^}]*\}|[^\s:]+))?(?: -> ([^\s]+))?(.*)$")


def modules(text: str) -> set:
    out = set()
    for line in text.splitlines():
        m = LINE.search(line)
        if not m:
            continue
        group, artifact, requested, resolved, rest = m.groups()
        if "(c)" in rest or "(n)" in rest or "FAILED" in rest:
            continue
        if requested and requested.startswith("{"):  # a rich version: {strictly 1.2} or {prefer 1.2}
            requested = (requested.strip("{}").split() or [None])[-1]
        version = resolved or requested
        if version:
            out.add(f"{group}:{artifact}:{version}")
    return out


def main(argv) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2
    found = set()
    for path in argv[1:]:
        mods = modules(Path(path).read_text(encoding="utf-8"))
        if not mods:
            print(f"gradle-lockfile: no resolved module in {path} (did the dependencies task fail?)")
            return 2
        found |= mods
    out = Path(argv[0])
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("".join(f"{m}=runtimeClasspath\n" for m in sorted(found)), encoding="utf-8")
    print(f"gradle-lockfile: {len(found)} modules from {len(argv) - 1} configuration(s) -> {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
