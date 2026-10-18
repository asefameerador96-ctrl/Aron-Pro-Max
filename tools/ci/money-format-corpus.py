#!/usr/bin/env python3
"""Money format corpus (docs/31 money gate, F-SYS-051, docs/24 s7.1 and s7.6). Owner: qa lane.

Generates contract/fixtures/money-format-corpus.json: 200 seeded milli-taka amounts (0, negatives, 1 paisa, half-paisa
values, lakh and crore boundaries, crore scale, the largest safe JavaScript integer) with the strings every money
formatter must produce. The expected strings are computed here with Python integers only (an
independent oracle: no shared code with the Kotlin or the TypeScript formatters):

  money2      F-SYS-051 display: taka, 2 decimals rounded half away from zero on the integer mtk, Western 3-digit
              grouping, the taka sign after the amount ("1,234.50 ৳"); Bengali digits in bn
  amount2     money2 without the sign (what the web's formatTakaDisplay returns; the hero card adds " ৳")
  money3      report cells and exact amounts: 3 decimals, nothing rounded, Western grouping ("1,234.500")
  plain3      the price editor's plain form: 3 decimals, no grouping, ASCII ("1234.500")
  lakh_crore  dashboard compact figure: mtk to whole taka half away from zero, then from 1 lakh up the value in lakh or
              crore with 2 decimals half up ("3.62 Cr", "65.07 Lakh"; the whole part Western-grouped from 1,000 crore up,
              F-SYS-051), below a lakh the grouped whole taka

The two implementations that read the file are backend/sync MoneyFormatCorpusTest (shared:rules Formats/Money) and
web/tests/money-format-corpus.test.ts (web/src/lib formatters). A formatter that disagrees with the file is a finding.

Usage: money-format-corpus.py [--write]   (default: --check, exit 1 when the committed file is not what this generates)
"""
import json
import random
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
OUT = ROOT / "contract" / "fixtures" / "money-format-corpus.json"
SEED = 20261018
SIZE = 200
TAKA = "৳"
BN = "০১২৩৪৫৬৭৮৯"
WORDS = {"en": {"lakh": "Lakh", "crore": "Cr"}, "bn": {"lakh": "লাখ", "crore": "কোটি"}}  # web i18n unit.lakh / unit.crore
MAX_SAFE = 9_007_199_254_740_991


def half_away(n, d):
    """Integer n / d rounded half away from zero (d > 0), integers only."""
    q, r = divmod(abs(n), d)
    if 2 * r >= d:
        q += 1
    return -q if n < 0 else q


def group(digits):
    out = []
    while len(digits) > 3:
        out.insert(0, digits[-3:])
        digits = digits[:-3]
    out.insert(0, digits)
    return ",".join(out)


def bn(s):
    return "".join(BN[int(c)] if c.isdigit() else c for c in s)


def fixed(scaled, decimals, grouping=True):
    """A signed integer count of 10^-decimals taka as text."""
    neg = scaled < 0
    digits = str(abs(scaled)).rjust(decimals + 1, "0")
    whole, frac = digits[:-decimals], digits[-decimals:]
    return ("-" if neg else "") + (group(whole) if grouping else whole) + "." + frac


def lakh_crore(mtk, loc):
    taka = half_away(mtk, 1000)
    sign = "-" if taka < 0 else ""
    a = abs(taka)
    if a >= 10_000_000:
        txt = fixed(half_away(a, 100_000), 2) + " " + WORDS[loc]["crore"]  # crore with 2 decimals = a / 10^5 hundredths
    elif a >= 100_000:
        txt = fixed(half_away(a, 1_000), 2) + " " + WORDS[loc]["lakh"]
    else:
        txt = group(str(a))
    txt = sign + txt
    return bn(txt) if loc == "bn" else txt


def expected(mtk):
    a2 = fixed(half_away(mtk, 10), 2)
    m3 = fixed(mtk, 3)
    return {
        "money2": {"en": f"{a2} {TAKA}", "bn": f"{bn(a2)} {TAKA}"},
        "amount2": {"en": a2, "bn": bn(a2)},
        "money3": {"en": m3, "bn": bn(m3)},
        "plain3": fixed(mtk, 3, grouping=False),
        "lakh_crore": {"en": lakh_crore(mtk, "en"), "bn": lakh_crore(mtk, "bn")},
    }


def amounts():
    special = [
        (0, "zero"), (1, "1 mtk"), (4, "below half a paisa"), (5, "exactly half a paisa (rounds up)"),
        (-5, "negative half paisa (rounds away from zero)"), (-4, "negative below half a paisa shows 0.00"),
        (9, "just under 1 paisa"), (10, "1 paisa"), (-10, "minus 1 paisa"), (15, "1.5 paisa"), (25, "2.5 paisa"),
        (995, "0.995 taka rounds to 1.00"), (999, "0.999 taka"), (1000, "1 taka"), (1005, "1.005 taka half"),
        (-1005, "negative 1.005 taka half"), (7_935, "7.935 Tk a stick (docs/24 s7.1)"),
        (158_700, "20 sticks x 7,935 mtk (docs/24 s7.4)"), (116_416, "116,416 mtk (docs/24 s7.4 fixture)"),
        (116_420, "116.42 AMO fixture (D-19)"), (289_500, "289.50 AMO fixture (D-19)"), (360_500, "360.50 SR fixture (D-19)"),
        (161_500, "161.50 SR fixture (D-19)"), (1_164_200, "1,164.20 (docs/24 s7.1 display example)"),
        (999_995, "999.995 rounds to 1,000.00"), (-15_000, "QC credit -15.00 (D-18 negative net, zero gross)"),
        (99_999_499, "99,999.499 Tk: below a lakh after taka rounding"), (99_999_500, "99,999.5 Tk rounds to 1 lakh taka"),
        (100_000_000, "1 lakh taka"), (100_004_999, "1 lakh taka and 4.999"), (6_507_000_000, "65.07 lakh"),
        (9_999_499_999, "99.99 lakh boundary"), (9_999_999_499, "just below a crore taka"),
        (9_999_999_500, "rounds to exactly 1 crore taka"), (10_000_000_000, "1 crore taka"),
        (36_100_000_000, "3.61 crore (web lakh.ts example)"), (36_150_000_000, "3.615 crore: half up to 3.62"),
        (36_149_999_999, "just below 3.615 crore"), (-36_150_000_000, "negative 3.615 crore"),
        (28_067_000, "28,067 taka below a lakh"), (1_234_567_890, "1,234,567.890 grouping"),
        (12_345_678_901_234, "crore-of-crore scale"), (1_000_000_000_000_000, "10^15 mtk"),
        (MAX_SAFE, "largest safe JavaScript integer"), (-MAX_SAFE, "smallest safe JavaScript integer"),
        (123_456_789_005, "large with half paisa"), (-1, "minus 1 mtk"), (-999, "minus 0.999 taka"),
        (100, "10 paisa"), (12_500, "12.5 taka"),
    ]
    out = [{"mtk": m, "note": n} for m, n in special]
    seen = {m for m, _ in special}
    rnd = random.Random(SEED)
    while len(out) < SIZE:
        scale = rnd.choice([10, 1_000, 100_000, 10_000_000, 1_000_000_000, 100_000_000_000, 10_000_000_000_000])
        m = rnd.randrange(0, scale)
        kind = rnd.randrange(4)
        if kind == 0:
            m = m - m % 10 + 5  # exact half paisa
        elif kind == 1:
            m -= m % 10  # whole paisa
        if rnd.randrange(5) == 0:
            m = -m  # credits and negative nets are shown
        if m in seen:
            continue
        seen.add(m)
        out.append({"mtk": m, "note": "seeded"})
    for o in out:
        o["expect"] = expected(o["mtk"])
    return out


def build():
    return {
        "_doc": "Money format corpus; generated by tools/ci/money-format-corpus.py --write, never edited by hand. "
                "money2 = F-SYS-051 display (2 decimals half away from zero, Western grouping, sign after); amount2 = "
                "money2 without the sign; money3 = 3 decimals exact; plain3 = ungrouped ASCII 3 decimals; lakh_crore = "
                "dashboard compact figure (whole taka half away from zero, then lakh/crore 2 decimals half up). mtk is "
                "an integer count of milli-taka (1 Tk = 1,000 mtk).",
        "seed": SEED,
        "taka_sign": TAKA,
        "lakh_crore_words": WORDS,
        "amounts": amounts(),
    }


def render():
    return json.dumps(build(), ensure_ascii=False, indent=1) + "\n"


def main():
    text = render()
    if "--write" in sys.argv[1:]:
        OUT.parent.mkdir(parents=True, exist_ok=True)
        OUT.write_text(text, encoding="utf-8")
        print(f"wrote {OUT.relative_to(ROOT)} ({SIZE} amounts)")
        return 0
    if not OUT.is_file() or OUT.read_text(encoding="utf-8") != text:
        print(f"::error file={OUT.relative_to(ROOT)}::money format corpus is stale or missing; run tools/ci/money-format-corpus.py --write")
        return 1
    print(f"money format corpus: {OUT.relative_to(ROOT)} up to date ({SIZE} amounts)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
