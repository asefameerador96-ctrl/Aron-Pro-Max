#!/usr/bin/env python3
"""AUD-TP-8: a machine link between a finished backlog row, its test and the status CSVs (cheap version).

For every row id logged as finished in docs/status/*.csv (a non-empty finished_utc) that is a row of
docs/25-build-backlog.csv:
  untested     no tracked test source names the id
  skipped      a test source that names the id also carries @Ignore, @Disabled, test.skip, it.skip, describe.skip,
               xit( or @Test(enabled = false)
  placeholder  every test source that names the id has no assertion at all (assert, expect(, verify, check,
               fail(, should, Assertions)
Test sources are tracked files (git ls-files) under */src/*[tT]est*/, web/tests/, web/e2e/, infra/tests/ and
tools/**/test_*.py. A row that has no automated test on purpose (a written report, a device run, a docs row) is
listed in tools/ci/done-rows-exempt.txt as `<row id>  <reason of ten characters or more>`.

Warning mode by default (exit 0, findings printed and, under GitHub Actions, written to the step summary);
`--gate` exits 1 on any finding. Owner: qa (AUD-TP-8); wired in ci.yml by infra.
"""
import csv
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
ROW_ID = re.compile(r"\b(?:F-[A-Z]+-\d{3}|N-\d{3}|AUD-[A-Z]+-\d+)\b")
TEST_PATH = re.compile(r"(^|/)src/[^/]*[tT]est[^/]*/|^web/tests/|^web/e2e/|^infra/tests/|^tools/.*/test_[^/]*\.py$")
SKIP = re.compile(r"@Ignore\b|@Disabled\b|\btest\.skip\(|\bit\.skip\(|\bdescribe\.skip\(|\bxit\(|@Test\s*\(\s*enabled\s*=\s*false")
ASSERTION = re.compile(r"\bassert|\bexpect\(|\bverify\b|\bcheck\(|\bfail\(|\bshould|Assertions\.|toMatchSnapshot|toHaveScreenshot")
SOURCE_EXT = (".kt", ".kts", ".java", ".ts", ".tsx", ".js", ".mjs", ".py", ".sh")


def backlog_ids(root: Path) -> set:
    with open(root / "docs/25-build-backlog.csv", newline="", encoding="utf-8") as f:
        return {r[0].strip() for r in csv.reader(f) if r and ROW_ID.fullmatch(r[0].strip())}


def finished_ids(root: Path) -> dict:
    """Row id -> the status CSVs that log it finished."""
    out = {}
    for path in sorted((root / "docs/status").glob("*.csv")):
        with open(path, newline="", encoding="utf-8") as f:
            for r in csv.DictReader(f):
                if not (r.get("finished_utc") or "").strip():
                    continue
                for rid in ROW_ID.findall(r.get("row_id") or ""):
                    out.setdefault(rid, []).append(path.name)
    return out


def test_sources(root: Path) -> dict:
    """Tracked test source path -> text."""
    files = subprocess.run(["git", "ls-files"], cwd=root, capture_output=True, text=True, check=True).stdout.splitlines()
    out = {}
    for p in files:
        if TEST_PATH.search(p) and p.endswith(SOURCE_EXT):
            try:
                out[p] = (root / p).read_text(encoding="utf-8", errors="replace")
            except OSError:
                pass
    return out


def exemptions(root: Path) -> tuple:
    path = root / "tools/ci/done-rows-exempt.txt"
    ok, bad = {}, []
    if path.exists():
        for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            line = line.split("#", 1)[0].strip()
            if not line:
                continue
            parts = line.split(None, 1)
            if len(parts) < 2 or len(parts[1].strip()) < 10 or not ROW_ID.fullmatch(parts[0]):
                bad.append(f"done-rows-exempt.txt:{n}: expected `<row id>  <reason of ten characters or more>`")
            else:
                ok[parts[0]] = parts[1].strip()
    return ok, bad


def check(root: Path) -> list:
    backlog = backlog_ids(root)
    finished = {k: v for k, v in finished_ids(root).items() if k in backlog}
    sources = test_sources(root)
    exempt, findings = exemptions(root)
    by_id = {rid: [] for rid in finished}
    for path, text in sources.items():
        for rid in set(ROW_ID.findall(text)) & by_id.keys():
            by_id[rid].append(path)
    for rid in sorted(finished):
        files = by_id[rid]
        if rid in exempt:
            continue
        where = ", ".join(sorted(set(finished[rid])))
        if not files:
            findings.append(f"untested     {rid}  (finished in {where}): no test source names it")
            continue
        skipped = [p for p in files if SKIP.search(sources[p])]
        for p in skipped:
            findings.append(f"skipped      {rid}  {p}: carries a skip or disable marker")
        if all(not ASSERTION.search(sources[p]) for p in files):
            findings.append(f"placeholder  {rid}  {', '.join(files[:3])}: no assertion in any test that names it")
    for rid in sorted(set(exempt) - set(finished)):
        findings.append(f"stale-exempt {rid}: exempted but not logged finished (remove the line)")
    return findings


def main(argv) -> int:
    gate = "--gate" in argv
    root = Path(next((a.split("=", 1)[1] for a in argv if a.startswith("--root=")), ROOT))
    findings = check(root)
    finished = len([k for k in finished_ids(root) if k in backlog_ids(root)])
    head = f"done-rows-check: {finished} finished rows, {len(findings)} findings ({'gate' if gate else 'warning'} mode)"
    print(head)
    for f in findings:
        print("  " + f)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as s:
            s.write(f"### {head}\n\n" + "".join(f"- `{f}`\n" for f in findings) + "\n")
    return 1 if (gate and findings) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
