#!/usr/bin/env python3
"""Parse AKTCL's "Geofencing Radius Information - September 2026" PDF (research department) into a CSV.

The PDF lists every territory (Sl, Territory, Division, Wing, Automation Code, Current Geofencing Radius = 100 m) and
marks exactly one of the "Proposed Geofencing Radius" columns (20, 30, 40, 50, 80, 100 Meter) with a 1. Plain text
extraction loses the column of that mark, so this reads word positions (pymupdf) and assigns the mark to the nearest
column header. Division subtotal rows (code 21..76), wing total rows (code 1..10) and the national line carry the six
column counts; every one of them is checked against the parsed rows, so a mis-assigned mark fails the run.

Output columns: wing, division, territory, territory_code, current_radius_m, proposed_radius_m, wing_code, division_code
(the last two come from the subtotal rows; "Automation Code" = the territory code of the retailer list).

    python -P tools/data/geofence_radius_to_csv.py IN.pdf OUT.csv [--expect 51,108,71,34,5,22]

No PII: the PDF holds only territory names and codes.
"""
from __future__ import annotations

import argparse
import csv
import re
import sys

RADII = (20, 30, 40, 50, 80, 100)
CODE_RE = re.compile(r"^1[0-9]{3}$")          # territory automation codes are 1001..1316
INT_RE = re.compile(r"^[0-9]+$")


def lines_of(page):
    """Words grouped into rows by their top y (a new row starts when the gap exceeds 3 pt)."""
    words = sorted(page.get_text("words"), key=lambda w: (w[1], w[0]))
    rows, cur, last_y = [], [], None
    for w in words:
        if last_y is not None and w[1] - last_y > 3:
            rows.append(sorted(cur, key=lambda x: x[0])); cur = []
        cur.append(w); last_y = w[1]
    if cur:
        rows.append(sorted(cur, key=lambda x: x[0]))
    return rows


def header_columns(page):
    """x-centre of each proposed-radius column from the header words 20/30/40/50/80/100 (one row, above the table;
    the same row also carries Sl./Territory/Division/Wing, so the words are picked by text and position)."""
    cand = [w for w in page.get_text("words") if w[1] < 120 and w[0] > 360 and w[4] in {str(r) for r in RADII}]
    centres = {int(w[4]): (w[0] + w[2]) / 2 for w in cand}
    if sorted(centres) != list(RADII):
        raise SystemExit("header row with the six radius columns not found")
    return centres


def nearest_radius(x, centres):
    return min(centres, key=lambda r: abs(centres[r] - x))


def text_between(row, x_from, x_to):
    return " ".join(w[4] for w in row if x_from <= w[0] < x_to).strip()


def parse(pdf_path):
    import pymupdf

    doc = pymupdf.open(pdf_path)
    territories = []            # dicts in page order
    pending = []                # territory rows since the last division subtotal
    pending_div_groups = []     # division subtotal groups since the last wing total
    division_codes, wing_codes = {}, {}
    for page in doc:
        rows = lines_of(page)
        centres = header_columns(page)
        first_col = min(centres.values()) - 20
        for row in rows:
            code_words = [w for w in row if 270 <= w[0] < 300 and INT_RE.match(w[4])]
            if not code_words:
                continue
            code = code_words[0][4]
            marks = [w for w in row if w[0] >= first_col and INT_RE.match(w[4])]
            sl = [w for w in row if w[0] < 35 and INT_RE.match(w[4])]
            if sl and CODE_RE.match(code) and len(marks) == 1 and marks[0][4] == "1":
                current = [w[4] for w in row if 330 <= w[0] < 360 and INT_RE.match(w[4])]
                t = {
                    "wing": text_between(row, 182, 268), "division": text_between(row, 112, 182),
                    "territory": text_between(row, 35, 112), "territory_code": code,
                    "current_radius_m": int(current[0]) if current else None,
                    "proposed_radius_m": nearest_radius((marks[0][0] + marks[0][2]) / 2, centres),
                }
                territories.append(t); pending.append(t)
            elif not sl and len(marks) == 6 and page.number < 6:
                counts = [int(w[4]) for w in marks]
                code_n = int(code)
                if code_n >= 21:                       # division subtotal
                    check(pending, counts, f"division {code}")
                    for t in pending:
                        t["division_code"] = code
                    for t in pending:
                        division_codes.setdefault(t["division"], code)
                    pending_div_groups.append(pending); pending = []
                else:                                  # wing total
                    group = [t for g in pending_div_groups for t in g]
                    check(group, counts, f"wing {code}")
                    for t in group:
                        t["wing_code"] = code
                        wing_codes.setdefault(t["wing"], code)
                    pending_div_groups = []
    if pending or pending_div_groups:
        raise SystemExit("territory rows left without a division subtotal or wing total")
    # The division printed on a territory row disagrees with its subtotal group on a few rows (a research-sheet label);
    # the subtotal code is what the national summary counts, so its canonical name wins and the difference is reported.
    div_names, wing_names = summary_names(doc)
    for t in territories:
        canon = div_names.get(t["division_code"], (t["division"], t["wing"]))[0]
        if norm(canon) != norm(t["division"]):
            print(f"note: territory {t['territory']} ({t['territory_code']}) is printed under division "
                  f"'{t['division']}' but counted in subtotal {t['division_code']} = {canon}; using {canon}", file=sys.stderr)
        t["division"] = canon
        t["wing"] = wing_names.get(t["wing_code"], t["wing"])
    return territories, division_codes, wing_codes


def norm(s):
    return re.sub(r"[^a-z0-9]", "", s.lower())


def summary_names(doc):
    """The last page (national summary): the canonical division name per division code and wing name per wing code."""
    div, wing = {}, {}
    for page in doc:
        if page.number != len(doc) - 1:                # only the summary page; the others carry rotated wing labels
            continue
        for row in lines_of(page):
            code = [w[4] for w in row if 270 <= w[0] < 300 and INT_RE.match(w[4])]
            counts = [w for w in row if w[0] >= 370 and INT_RE.match(w[4])]
            if not code or len(counts) != 6 or len(code[0]) > 2:
                continue
            name = text_between(row, 35, 112); wing_name = text_between(row, 182, 268)
            if not name or name == "National":
                continue
            n = int(code[0])
            if n >= 21:
                div.setdefault(code[0], (name, wing_name))
            elif n >= 1:
                wing.setdefault(code[0], name)
    return div, wing


def counts_of(rows):
    return [sum(1 for t in rows if t["proposed_radius_m"] == r) for r in RADII]


def check(rows, counts, what):
    got = counts_of(rows)
    if got != counts:
        raise SystemExit(f"{what}: parsed marks {got} differ from the PDF subtotal {counts}")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("pdf"); ap.add_argument("out")
    ap.add_argument("--expect", default="51,108,71,34,5,22", help="national counts for 20,30,40,50,80,100 m")
    a = ap.parse_args(argv)
    territories, division_codes, wing_codes = parse(a.pdf)
    expect = [int(x) for x in a.expect.split(",")]
    got = counts_of(territories)
    if got != expect:
        raise SystemExit(f"national counts {got} differ from the expected {expect}")
    codes = [t["territory_code"] for t in territories]
    if len(set(codes)) != len(codes):
        dup = sorted({c for c in codes if codes.count(c) > 1})
        raise SystemExit(f"duplicate territory codes: {dup}")
    with open(a.out, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["wing", "division", "territory", "territory_code", "current_radius_m",
                                          "proposed_radius_m", "wing_code", "division_code"], lineterminator="\n")
        w.writeheader()
        for t in territories:
            w.writerow(t)
    print(f"{len(territories)} territories, {len(division_codes)} divisions, {len(wing_codes)} wings; "
          f"counts 20/30/40/50/80/100 = {'/'.join(map(str, got))}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
