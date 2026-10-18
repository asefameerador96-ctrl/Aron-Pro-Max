#!/usr/bin/env python3
"""Money-float scan (docs/31 money gate; docs/24 s7.1 "Floating point is never used for money on phone, server or web").

Fails when a money path uses floating point. Scans production sources:
  Kotlin  backend/*/src/main/**, shared/*/src/commonMain/**, android/*/src/main/**
  TS      web/src/** (the generated web/src/contract/ is skipped)
A line is a money line when it names money (mtk, amount, price, money, net_, gross, due, qc, taka, paisa, settlement,
discount; any case, inside identifiers too). In a money file (its path names money: Money.kt, Memo.kt, price-money.ts,
taka.ts, lakh.ts, DuesLedger.kt, ...) every line counts. A money line is an offender when it uses floating point:
  Kotlin  Double, Float, toDouble(, toFloat(, toDoubleOrNull(, toFloatOrNull(, a decimal literal next to * or /
  TS      parseFloat(, toFixed(, Math.round/floor/ceil/trunc(, a division by 10, 100, 1000 or 1eN, a decimal literal
          next to * or /
Comment lines are skipped. A reviewed offender is listed in tools/ci/money-float-allow.txt as
  <path> | <text the line contains> | ok|finding | <reason>
`ok` is a reviewed false positive (a percentage, a font size); `finding` is a real float on money routed to the owning
lane, kept green until it is fixed. The allow-list may only shrink: it may hold at most CEILING entries, and an entry
that matches no line fails the scan (delete it).

Usage: money-float-scan.py [--report] [repo root, default: this checkout]
  exit 1 on an unlisted offender, a stale entry or more than CEILING entries; --report prints and exits 0.
"""
import re
import sys
from pathlib import Path

CEILING = 13  # the allow-list only shrinks: lower this with every removed entry, never raise it without the lead's review
ALLOW = Path(__file__).resolve().parent / "money-float-allow.txt"

MONEY = re.compile(r"mtk|amount|price|money|net_|gross|due|qc|taka|paisa|settlement|discount", re.IGNORECASE)
MONEY_FILE = re.compile(r"money|memo|price|taka|lakh|dues|mtk|amount|settle|discount|qc", re.IGNORECASE)
DEC_ARITH = r"[*/]\s*\d+\.\d+|\d+\.\d+\s*[*/]"
KT_FLOAT = re.compile(r"\bDouble\b|\bFloat\b|\.toDouble(OrNull)?\(|\.toFloat(OrNull)?\(|" + DEC_ARITH)
TS_FLOAT = re.compile(r"parseFloat\(|\.toFixed\(|Math\.(round|floor|ceil|trunc)\(|/\s*(1e\d+|10|100|1000)\b(?!n)|" + DEC_ARITH)
KT_GLOBS = ["backend/*/src/main/**/*.kt", "shared/*/src/commonMain/**/*.kt", "android/*/src/main/**/*.kt"]
TS_GLOBS = ["web/src/**/*.ts", "web/src/**/*.tsx"]
COMMENT = re.compile(r"^\s*(//|\*|/\*)")


def sources(root):
    for globs, rx in ((KT_GLOBS, KT_FLOAT), (TS_GLOBS, TS_FLOAT)):
        for g in globs:
            for f in sorted(root.glob(g)):
                rel = f.relative_to(root).as_posix()
                if f.is_file() and not rel.startswith("web/src/contract/"):
                    yield rel, f, rx


def scan(root):
    hits = []
    for rel, f, rx in sources(root):
        whole = bool(MONEY_FILE.search(Path(rel).name))
        for i, line in enumerate(f.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            if COMMENT.match(line):
                continue
            code = line.split("//")[0] if not rel.startswith("web/") or "://" not in line else line
            if rx.search(code) and (whole or MONEY.search(code)):
                hits.append((rel, i, line.strip()))
    return hits


def load_allow(path):
    entries = []
    for n, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        parts = [p.strip() for p in raw.split("|", 3)]  # the reason may contain "|"
        if len(parts) != 4 or parts[2] not in ("ok", "finding") or not parts[3]:
            raise SystemExit(f"{path.name}:{n}: want '<path> | <text> | ok|finding | <reason>', got: {raw}")
        entries.append({"path": parts[0], "text": parts[1], "kind": parts[2], "reason": parts[3], "line": n, "used": 0})
    return entries


def main(argv):
    report = "--report" in argv
    rest = [a for a in argv if not a.startswith("--")]
    root = Path(rest[0]).resolve() if rest else Path(__file__).resolve().parent.parent.parent
    allow_path = Path(rest[1]) if len(rest) > 1 else ALLOW
    entries = load_allow(allow_path) if allow_path.is_file() else []
    hits = scan(root)
    offenders = []
    for rel, ln, text in hits:
        match = [e for e in entries if e["path"] == rel and e["text"] in text]
        for e in match:
            e["used"] += 1
        if not match:
            offenders.append((rel, ln, text))
    stale = [e for e in entries if not e["used"]]
    level = "warning" if report else "error"
    for rel, ln, text in offenders:
        print(f"::{level} file={rel},line={ln}::floating point on a money path: {text[:160]}")
    for e in stale:
        print(f"::{level} file=tools/ci/{allow_path.name},line={e['line']}::stale allow entry (matches nothing; delete it): {e['path']} | {e['text']}")
    over = len(entries) > CEILING
    if over:
        print(f"::{level} file=tools/ci/{allow_path.name}::{len(entries)} allow entries > CEILING {CEILING}: the list only shrinks")
    findings = [e for e in entries if e["kind"] == "finding"]
    print(f"money-float scan: {len(hits)} float use(s) on money lines, {len(offenders)} unlisted, {len(stale)} stale allow entr(ies), "
          f"{len(entries)}/{CEILING} allowed ({len(findings)} open finding(s))")
    for e in findings:
        print(f"  open finding: {e['path']} | {e['text']} | {e['reason']}")
    return 0 if report or not (offenders or stale or over) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
