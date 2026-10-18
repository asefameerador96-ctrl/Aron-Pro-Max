#!/usr/bin/env python3
"""Money-float scan (docs/31 money gate; docs/24 s7.1 "Floating point is never used for money on phone, server or web").

Fails when a money path uses floating point. Scans production sources:
  Kotlin  backend/*/src/main/**, shared/*/src/commonMain/**, android/*/src/main/**
  TS      web/src/** (the generated web/src/contract/ is skipped)
A line is a money line when it names money (mtk, amount, price, money, net_, gross, due, qc, taka, paisa, settlement,
discount, paid, total, cash, collect; any case, inside identifiers too). In a money file (its name names money:
Money.kt, Memo.kt, price-money.ts, taka.ts, lakh.ts, DuesLedger.kt, ...) every line counts. A money line is an
offender when it uses floating point:
  Kotlin  Double, Float, toDouble(, toFloat(, toDoubleOrNull(, toFloatOrNull(, an exponent literal (1e3, 1.5E6),
          a decimal literal next to * or /
  TS      parseFloat(, toFixed(, Math.round/floor/ceil/trunc(, a division by 10, 100, 1000, 1_000 (any power of ten,
          underscores allowed) or 1eN (not a BigInt literal such as 1000n), a decimal literal next to * or /
Skipped: whole-line comments (`//`, a `/*` line, a block-comment continuation `*` followed by a space, `/` or the end of
the line) and the part of a line after a `//` that is outside a string literal (so "http://..." is still code).

A reviewed offender is listed in tools/ci/money-float-allow.txt as
  <path> | <exact offending fragment> | ok|finding[ xN] | <reason>
The fragment must cover every float use on its line and must occur on exactly ONE scanned float line of that file (xN:
exactly N byte-identical lines, for a line repeated verbatim): an entry that matches no line (stale) or another number of
lines (a new float line that copies an allowed fragment) fails, and a float
use on an allowed line that the fragment does not cover is an offender. `ok` is a reviewed false positive (a percentage,
a font size); `finding` is a real float on money routed to the owning lane, kept green until it is fixed. The list
only shrinks: it may hold at most CEILING entries.

Usage: money-float-scan.py [--report] [repo root, default: this checkout] [allow file]
  exit 1 on an offender, a stale or ambiguous entry, or more than CEILING entries; --report prints and exits 0.
"""
import re
import sys
from pathlib import Path

CEILING = 26  # the allow-list only shrinks: lower this with every removed entry, never raise it without the lead's review
ALLOW = Path(__file__).resolve().parent / "money-float-allow.txt"

MONEY = re.compile(r"mtk|amount|price|money|net_|gross|due|qc|taka|paisa|settlement|discount|paid|total|cash|collect", re.IGNORECASE)
MONEY_FILE = re.compile(r"money|memo|price|taka|lakh|dues|mtk|amount|settle|discount|qc", re.IGNORECASE)
DEC_ARITH = r"[*/]\s*\d+\.\d+|\d+\.\d+\s*[*/]"
KT_FLOAT = re.compile(r"\bDouble\b|\bFloat\b|\.toDouble(OrNull)?\(|\.toFloat(OrNull)?\(|\b\d+(\.\d+)?[eE][+-]?\d+\b|" + DEC_ARITH)
TS_FLOAT = re.compile(r"parseFloat\(|\.toFixed\(|Math\.(round|floor|ceil|trunc)\(|/\s*(1e\d+|10+|1(_?000)+)\b(?!n)|" + DEC_ARITH)
KT_GLOBS = ["backend/*/src/main/**/*.kt", "shared/*/src/commonMain/**/*.kt", "android/*/src/main/**/*.kt"]
TS_GLOBS = ["web/src/**/*.ts", "web/src/**/*.tsx"]
COMMENT = re.compile(r"^\s*(//|\*(\s|/|$))")
BLOCK_OPEN = re.compile(r"^\s*/\*")


def is_comment(line):
    if COMMENT.match(line):
        return True
    # A `/*` line is a comment unless code follows its closing `*/`.
    return bool(BLOCK_OPEN.match(line)) and ("*/" not in line or not line.split("*/", 1)[1].strip())


def code_part(line):
    """The line up to a `//` comment that is outside a string literal ("...", '...', `...`)."""
    quote = None
    i = 0
    while i < len(line):
        c = line[i]
        if quote:
            if c == "\\":
                i += 2
                continue
            if c == quote:
                quote = None
        elif c in "\"'`":
            quote = c
        elif line.startswith("//", i):
            return line[:i]
        i += 1
    return line


def sources(root):
    for globs, rx in ((KT_GLOBS, KT_FLOAT), (TS_GLOBS, TS_FLOAT)):
        for g in globs:
            for f in sorted(root.glob(g)):
                rel = f.relative_to(root).as_posix()
                if f.is_file() and not rel.startswith("web/src/contract/"):
                    yield rel, f, rx


def scan(root):
    """(path, line number, line, [float match spans]) of every money line that uses floating point."""
    hits = []
    for rel, f, rx in sources(root):
        whole = bool(MONEY_FILE.search(Path(rel).name))
        for i, line in enumerate(f.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            if is_comment(line):
                continue
            code = code_part(line)
            spans = [m.span() for m in rx.finditer(code)]
            if spans and (whole or MONEY.search(code)):
                hits.append((rel, i, line, spans))
    return hits


def load_allow(path):
    entries = []
    for n, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        parts = [p.strip() for p in raw.split("|", 3)]  # the reason may contain "|"
        kind = re.fullmatch(r"(ok|finding)(?: x([2-9]))?", parts[2]) if len(parts) == 4 else None
        if len(parts) != 4 or not parts[1] or not kind or not parts[3]:
            raise SystemExit(f"{path.name}:{n}: want '<path> | <fragment> | ok|finding[ xN] | <reason>', got: {raw}")
        entries.append({"path": parts[0], "text": parts[1], "kind": kind.group(1), "count": int(kind.group(2) or 1),
                        "reason": parts[3], "line": n, "lines": []})
    return entries


def covered(line, spans, texts):
    """True when every float span lies inside an occurrence of one of [texts] on [line]."""
    occ = []
    for t in texts:
        start = line.find(t)
        while start >= 0:
            occ.append((start, start + len(t)))
            start = line.find(t, start + 1)
    return all(any(a <= s and e <= b for a, b in occ) for s, e in spans)


def main(argv):
    report = "--report" in argv
    rest = [a for a in argv if not a.startswith("--")]
    root = Path(rest[0]).resolve() if rest else Path(__file__).resolve().parent.parent.parent
    allow_path = Path(rest[1]) if len(rest) > 1 else ALLOW
    entries = load_allow(allow_path) if allow_path.is_file() else []
    hits = scan(root)
    offenders = []
    for rel, ln, text, spans in hits:
        match = [e for e in entries if e["path"] == rel and e["text"] in text]
        for e in match:
            e["lines"].append(ln)
        if not covered(text, spans, [e["text"] for e in match]):
            offenders.append((rel, ln, text))
    stale = [e for e in entries if not e["lines"]]
    ambiguous = [e for e in entries if e["lines"] and len(e["lines"]) != e["count"]]
    level = "warning" if report else "error"
    name = f"tools/ci/{allow_path.name}"
    for rel, ln, text in offenders:
        print(f"::{level} file={rel},line={ln}::floating point on a money path: {text.strip()[:160]}")
    for e in stale:
        print(f"::{level} file={name},line={e['line']}::stale allow entry (matches no float line; delete it): {e['path']} | {e['text']}")
    for e in ambiguous:
        print(f"::{level} file={name},line={e['line']}::allow entry matches {len(e['lines'])} lines {e['lines']}, want exactly {e['count']}: {e['path']} | {e['text']}")
    over = len(entries) > CEILING
    if over:
        print(f"::{level} file={name}::{len(entries)} allow entries > CEILING {CEILING}: the list only shrinks")
    findings = [e for e in entries if e["kind"] == "finding"]
    print(f"money-float scan: {len(hits)} money line(s) with floating point, {len(offenders)} not allowed, {len(stale)} stale and "
          f"{len(ambiguous)} ambiguous allow entr(ies), {len(entries)}/{CEILING} allowed ({len(findings)} open finding(s))")
    for e in findings:
        print(f"  open finding: {e['path']} | {e['text']} | {e['reason']}")
    return 0 if report or not (offenders or stale or ambiguous or over) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
