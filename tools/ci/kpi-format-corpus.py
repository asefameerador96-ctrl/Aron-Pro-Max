#!/usr/bin/env python3
"""KPI format corpus (D-TSO-MAN-10; build contract docs/requests/tso-manual-build-2026-10.md s4). Owner: lane F3.

Generates contract/fixtures/kpi-format-corpus.json: 300 seeded cases plus every fixture of the contract's s4.2 to s4.4,
with the integers and the en and bn text every KPI formatter must produce. The expectations are computed here with
Python integers and fractions.Fraction only: an independent oracle (it rounds exact rationals half up; the Kotlin and
TypeScript code use the (2*s*n + d) div (2*d) integer form), sharing no code with either. Before writing or checking, the
generator asserts that its oracle reproduces every number the contract states (the s4 tables), so a wrong oracle fails
here and never reaches the file.

Sections (each entry carries `note`: "seeded", or the contract fixture it pins):
  percent        n, d -> whole, tenths, bp (null when d = 0), whole_capped (Target card: min(100, whole)), text of each
                 (whole 0 dp, tenths 1 dp, bp 2 dp; whole part grouped with ","; "—" when d = 0)
  qty            n (null allowed), d -> text: one decimal half up; from 1,000.0 the value in K with one decimal, rounded
                 once from n, whole part grouped ("1,234.6 K"); "—" for null n or d = 0
  sales_card     s4.2 Sales: a, t, w -> total_sales Qty(a), total_target Qty(t, w), ring tenths(a*w, t) (null when t or
                 w is 0 or null)
  tilldate       t, e, n -> round_half_up(t * clamp(e, 0, n) / n), null when n = 0
  target_card    s4.3 cards: item targets, achievement, e, n -> monthly and till-date target (sum of item rows),
                 capped whole %, band, text
  details        s4.3 details rows: a, t -> bp (uncapped), 2 dp text, remaining max(t - a, 0)
  remaining      t, a -> max(t - a, 0)
  band           p, lo, hi -> red (p < lo), amber (p < hi), green
  working_days   month, weekend (ISO), node path, calendar marks (date, level, scope_id, kind as app.calendar_holiday)
                 [, day] -> month_days, elapsed_days ([1st..day]); per date the most specific mark on the path decides; a
                 working day is a make-up day or neither weekend nor off
  ticket_counts  counts by status -> total, open, resolved % and open % (whole, 0 when total = 0) as text
  ticket_age     now_ms, captured_ms -> en / bn age text (<1 min just now, <1 h minutes, <24 h hours, else days; floor)

Readers: shared/rules KpiFormatCorpusTest (Kotlin, the phone and the server) and web/tests/kpi-format-corpus.test.ts
(lane W1). Every integer in the file is a safe JavaScript integer, and so is every intermediate of the integer forms
(20000 * n + d <= 6.3e15 for the largest percent case), so the web can use plain numbers.

Usage: kpi-format-corpus.py [--write]   (default: --check, exit 1 when the committed file is not what this generates)
"""
import calendar
import datetime
import json
import math
import random
import sys
from fractions import Fraction
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
OUT = ROOT / "contract" / "fixtures" / "kpi-format-corpus.json"
SEED = 20261028
SEEDED = {"percent": 90, "qty": 90, "tilldate": 40, "working_days": 30, "ticket_age": 30, "remaining": 10, "band": 10}
BN = "০১২৩৪৫৬৭৮৯"
DASH = "—"
MAX_SAFE = 9_007_199_254_740_991
LEVELS = ["global", "wing", "division", "territory", "zone"]
OFF_KINDS = {"holiday", "emergency_off"}
AGE_BN = {"just": "এইমাত্র", "m": "মিনিট আগে", "h": "ঘণ্টা আগে", "d": "দিন আগে"}


# ---------------------------------------------------------------- oracle (Fraction arithmetic, rounded once)

def half_up(x):
    """A non-negative rational rounded half up."""
    assert x >= 0
    return math.floor(x + Fraction(1, 2))


def group(digits):
    out = []
    while len(digits) > 3:
        out.insert(0, digits[-3:])
        digits = digits[:-3]
    out.insert(0, digits)
    return ",".join(out)


def bn(s):
    return "".join(BN[int(c)] if c.isdigit() else c for c in s)


def both(s):
    return {"en": s, "bn": bn(s)}


def pct(n, d, scale):
    return None if d == 0 else half_up(Fraction(scale * n, d))


def pct_text(v, decimals):
    if v is None:
        return DASH
    whole, frac = divmod(v, 10 ** decimals)
    return group(str(whole)) + ("." + str(frac).rjust(decimals, "0") if decimals else "") + "%"


def qty_text(n, d=1):
    if n is None or d == 0:
        return DASH
    t = half_up(Fraction(10 * n, d))
    if t < 10_000:
        return f"{t // 10}.{t % 10}"
    k = half_up(Fraction(n, 100 * d))  # tenths of a thousand, from n itself
    return f"{group(str(k // 10))}.{k % 10} K"


def till_date(t, e, n):
    if n == 0:
        return None
    return half_up(Fraction(t * min(max(e, 0), n), n))


def remaining(t, a):
    return max(t - a, 0)


def band(p, lo, hi):
    return "red" if p < lo else "amber" if p < hi else "green"


def card_pct(a, t):
    w = pct(a, t, 100)
    return None if w is None else min(100, w)


def covers(node, level, scope_id):
    return level == "global" or node.get(level + "_id") == scope_id


def node_calendar(node, marks):
    """Per date, the most specific mark on the node's path decides; at equal level an off day wins."""
    best = {}
    for m in marks:
        if not covers(node, m["level"], m["scope_id"]):
            continue
        lvl = LEVELS.index(m["level"])
        off = m["kind"] in OFF_KINDS
        cur = best.get(m["date"])
        if cur is None or lvl > cur[0] or (lvl == cur[0] and off):
            best[m["date"]] = (lvl, off)
    return {d for d, (_, off) in best.items() if off}, {d for d, (_, off) in best.items() if not off}


def working(date, weekend, off, makeup):
    iso = date.isoformat()
    return iso in makeup or (date.isoweekday() not in weekend and iso not in off)


def working_days(month, weekend, node, marks, day=None):
    y, m = map(int, month.split("-"))
    off, makeup = node_calendar(node, marks)
    dates = [datetime.date(y, m, i) for i in range(1, calendar.monthrange(y, m)[1] + 1)]
    month_days = sum(1 for d in dates if working(d, weekend, off, makeup))
    elapsed = None
    if day is not None:
        last = datetime.date.fromisoformat(day)
        elapsed = sum(1 for d in dates if d <= last and working(d, weekend, off, makeup))
    return month_days, elapsed


def age(now_ms, captured_ms):
    ms = max(now_ms - captured_ms, 0)
    if ms < 60_000:
        return {"en": "just now", "bn": AGE_BN["just"]}
    for unit, size in (("d", 86_400_000), ("h", 3_600_000), ("m", 60_000)):
        if ms >= size:
            n = str(ms // size)
            return {"en": f"{n}{unit} ago", "bn": f"{bn(n)} {AGE_BN[unit]}"}
    raise AssertionError("unreachable")


# ---------------------------------------------------------------- entries

def percent_entry(n, d, note):
    whole, tenths, bp = pct(n, d, 100), pct(n, d, 1_000), pct(n, d, 10_000)
    return {
        "n": n, "d": d, "whole": whole, "tenths": tenths, "bp": bp, "whole_capped": card_pct(n, d),
        "text": {"whole": both(pct_text(whole, 0)), "tenths": both(pct_text(tenths, 1)), "bp": both(pct_text(bp, 2))},
        "note": note,
    }


def qty_entry(n, d, note):
    return {"n": n, "d": d, "text": both(qty_text(n, d)), "note": note}


def sales_entry(a, t, w, note):
    ring = None if t is None or a is None or w == 0 else pct(a * w, t, 1_000)
    return {"a": a, "t": t, "w": w, "total_sales": both(qty_text(a)), "total_target": both(qty_text(t, w)),
            "ring_tenths": ring, "ring": both(pct_text(ring, 1)), "note": note}


def tilldate_entry(t, e, n, note):
    return {"t": t, "e": e, "n": n, "value": till_date(t, e, n), "note": note}


def target_card_entry(label, items, achieved, e, n, lo, hi, note):
    monthly = sum(items)
    till = sum(till_date(t, e, n) for t in items)
    mp, tp = card_pct(achieved, monthly), card_pct(achieved, till)
    return {
        "label": label, "items": items, "achieved": achieved, "e": e, "n": n, "lo": lo, "hi": hi,
        "monthly_target": monthly, "tilldate_target": till, "monthly_pct": mp, "tilldate_pct": tp,
        "monthly_band": band(mp, lo, hi), "tilldate_band": band(tp, lo, hi),
        "monthly_text": both(pct_text(mp, 0)), "tilldate_text": both(pct_text(tp, 0)), "note": note,
    }


def details_entry(a, t, note):
    bp = pct(a, t, 10_000)
    return {"a": a, "t": t, "bp": bp, "text": both(pct_text(bp, 2)), "remaining": remaining(t, a), "note": note}


def working_entry(month, weekend, node, marks, day, note):
    md, el = working_days(month, weekend, node, marks, day)
    return {"month": month, "weekend": weekend, "node": node, "marks": marks, "day": day, "month_days": md,
            "elapsed_days": el, "note": note}


def counts_entry(new, in_progress, resolved, closed, cancelled, note):
    total = new + in_progress + resolved + closed + cancelled
    op = new + in_progress
    rp = 0 if total == 0 else pct(resolved, total, 100)
    opp = 0 if total == 0 else pct(op, total, 100)
    return {"new": new, "in_progress": in_progress, "resolved": resolved, "closed": closed, "cancelled": cancelled,
            "total": total, "open": op, "resolved_pct": rp, "open_pct": opp,
            "resolved_text": both(pct_text(rp, 0)), "open_text": both(pct_text(opp, 0)), "note": note}


def age_entry(now_ms, captured_ms, note):
    return {"now_ms": now_ms, "captured_ms": captured_ms, "text": age(now_ms, captured_ms), "note": note}


def mark(date, level, scope_id, kind):
    return {"date": date, "level": level, "scope_id": scope_id, "kind": kind}


# ---------------------------------------------------------------- the contract's fixtures (s4.2 to s4.4)

ZONE = {"wing_id": 1, "division_id": 2, "territory_id": 3, "zone_id": 4}
TERRITORY = {"wing_id": 1, "division_id": 2, "territory_id": 3, "zone_id": None}
GLOBAL = {"wing_id": None, "division_id": None, "territory_id": None, "zone_id": None}
NOW = 1_793_000_000_000  # an instant in November 2026; ages are differences, the instant itself does not matter
CIG_ITEMS = [1000, 1000, 10000, 10000, 5000, 6000, 2999, 5000]


def fixtures():
    """Every s4 fixture as corpus entries, plus the contract's stated value, which the oracle must reproduce."""
    f = {k: [] for k in ("percent", "qty", "sales_card", "tilldate", "target_card", "details", "remaining", "band",
                         "working_days", "ticket_counts", "ticket_age")}
    checks = []  # (description, oracle value, contract value)

    def sales(a, t, w, note, sales_txt, target_txt, ring_txt):
        e = sales_entry(a, t, w, note)
        f["sales_card"].append(e)
        checks.extend([(note + " total sales", e["total_sales"]["en"], sales_txt),
                       (note + " total target", e["total_target"]["en"], target_txt),
                       (note + " ring", e["ring"]["en"], ring_txt)])

    sales(40, 42000, 25, "s4.2 Sales Cigarette (screenshot 6)", "40.0", "1.7 K", "2.4%")
    sales(2991, 26000, 25, "s4.2 Sales Bidi (screenshot 6)", "3.0 K", "1.0 K", "287.6%")
    sales(0, 100, 25, "s4.2 Sales Lighter (screenshot 6)", "0.0", "4.0", "0.0%")
    sales(None, 175, 25, "s4.2 Sales Match total target (screenshot 6)", DASH, "7.0", DASH)
    sales(40, None, 25, "s4.2 Sales target null", "40.0", DASH, DASH)

    def percent(n, d, note, kind, expected):
        e = percent_entry(n, d, note)
        f["percent"].append(e)
        checks.append((note, e[kind] if isinstance(expected, int) else e["text"][kind]["en"], expected))

    percent(4, 4, "s4.2 Geo Fencing 4/4", "tenths", "100.0%")
    percent(5, 5, "s4.2 By Channel STD one channel", "bp", "100.00%")
    percent(3, 119, "s4.2 CPR 3/119", "tenths", "2.5%")
    percent(29, 1186, "s4.2 CPR 29/1186 (double rounding: never 2.5)", "tenths", "2.4%")
    percent(2, 3, "s4.2 Brand CPR 2/3", "whole", "67%")
    percent(1, 3, "s4.2 Brand CPR 1/3", "whole", "33%")
    percent(2, 5, "s4.2 Login Status 2/5", "whole", "40%")
    percent(1, 5, "s4.2 Bikroy Joma 1/5", "whole", "20%")
    percent(750, 1000, "s4.2 Segment 750/1000 mtk", "bp", 7500)
    percent(18, 723, "s4.4 Resolved 18/723", "whole", "2%")
    percent(0, 723, "s4.4 Open 0/723", "whole", "0%")
    percent(7, 0, "s4.1 d = 0 prints a dash", "tenths", DASH)
    percent(310_000_000_000, 1, "s4.1 Long bound: A*W = 10^10 * 31", "bp", 3_100_000_000_000_000)
    checks.append(("s4.2 CPR 29/1186 bp", f["percent"][3]["bp"], 245))  # rounding 245 bp to tenths would give 2.5

    def qty(n, d, note, expected, locale="en"):
        e = qty_entry(n, d, note)
        f["qty"].append(e)
        checks.append((note, e["text"][locale], expected))

    qty(999, 1, "s4.2 K boundary 999", "999.0")
    qty(19999, 20, "s4.2 K boundary 19999/20 (999.95 rounds to 1,000.0)", "1.0 K")
    qty(1234567, 1, "s4.2 K boundary 1234567", "1,234.6 K")
    qty(40, 1, "s4.2 K boundary bn 40", "৪০.০", "bn")
    qty(None, 1, "s4.2 Total Target null", DASH)
    qty(175, 25, "s4.2 Match total target", "7.0")
    qty(175, 0, "s4.1 d = 0 prints a dash", DASH)
    qty(1234567, 1, "s4.2 K boundary bn 1234567", "১,২৩৪.৬ K", "bn")

    for t, v in ((1000, 806), (10000, 8065), (5000, 4032), (6000, 4839), (2999, 2419), (100, 81), (170, 137),
                 (11000, 8871)):
        e = tilldate_entry(t, 25, 31, f"s4.3 till-date item {t} (25/31, calendar basis)")
        f["tilldate"].append(e)
        checks.append((e["note"], e["value"], v))
    for t, e_, n, v, note in ((40999, 20, 25, 32799, "s4.3 working basis 20/25 (holiday 21st)"),
                              (40999, 21, 26, 33115, "s4.3 working basis 21/26 (no holiday)")):
        e = tilldate_entry(t, e_, n, note)
        f["tilldate"].append(e)
        checks.append((note, e["value"], v))
    f["tilldate"].append(tilldate_entry(1000, 40, 31, "s4.1 e above n is clamped to n"))
    f["tilldate"].append(tilldate_entry(1000, -3, 31, "s4.1 e below 0 is clamped to 0"))
    f["tilldate"].append(tilldate_entry(1000, 0, 0, "s4.1 n = 0 gives null"))

    for label, items, a, mt, tt, mp, tp, mb, tb in (
            ("Cigarette", CIG_ITEMS, 1030, 40999, 33064, 3, 3, "red", "red"),
            ("Bidi", [11000], 50, 11000, 8871, 0, 1, "red", "red"),
            ("Lighter", [100], 85, 100, 81, 85, 100, "green", "green"),
            ("Match", [170], 72, 170, 137, 42, 53, "amber", "amber")):
        e = target_card_entry(label, items, a, 25, 31, 40, 80, f"s4.3 Target card {label} (screenshots 39, 40)")
        f["target_card"].append(e)
        checks.extend([(e["note"] + " monthly T", e["monthly_target"], mt), (e["note"] + " till-date T", e["tilldate_target"], tt),
                       (e["note"] + " monthly %", e["monthly_pct"], mp), (e["note"] + " till-date %", e["tilldate_pct"], tp),
                       (e["note"] + " monthly band", e["monthly_band"], mb), (e["note"] + " till-date band", e["tilldate_band"], tb)])
    checks.append(("s4.3 achieved Cigarette", sum([350, 330, 20, 200, 30, 30, 40, 30]), 1030))
    checks.append(("s4.3 working-basis card sum 21/26 (sum of item rows)", sum(till_date(t, 21, 26) for t in CIG_ITEMS), 33114))

    for a, t, txt, rem in ((350, 1000, "35.00%", 650), (20, 10000, "0.20%", 9980), (40, 2999, "1.33%", 2959),
                           (350, 806, "43.42%", 456), (20, 8065, "0.25%", 8045), (85, 81, "104.94%", 0)):
        e = details_entry(a, t, f"s4.3 details {a}/{t} (screenshots 41, 42)")
        f["details"].append(e)
        checks.extend([(e["note"], e["text"]["en"], txt), (e["note"] + " remaining", e["remaining"], rem)])
    for t, a, v in ((1000, 350, 650), (2999, 40, 2959), (81, 85, 0)):
        f["remaining"].append({"t": t, "a": a, "value": remaining(t, a), "note": f"s4.3 remaining {t} - {a}"})
        checks.append((f"s4.3 remaining {t} - {a}", remaining(t, a), v))
    for p, v in ((0, "red"), (1, "red"), (3, "red"), (42, "amber"), (53, "amber"), (85, "green"), (100, "green"),
                 (39, "red"), (40, "amber"), (79, "amber"), (80, "green")):
        f["band"].append({"p": p, "lo": 40, "hi": 80, "band": band(p, 40, 80), "note": f"s4.3 band {p} on [40,80]"})
        checks.append((f"s4.3 band {p}", band(p, 40, 80), v))

    hol21 = mark("2026-10-21", "global", 0, "holiday")
    zone12 = mark("2026-10-12", "zone", 4, "holiday")
    fri30 = mark("2026-10-30", "global", 0, "makeup_day")
    for month, node, marks, day, md, el, note in (
            ("2026-10", GLOBAL, [], "2026-10-25", 26, 21, "s4.2 Working days Oct 2026: 31 - 5 Fridays"),
            ("2026-10", GLOBAL, [hol21], "2026-10-25", 25, 20, "s4.2 Working days global holiday 2026-10-21"),
            ("2026-10", ZONE, [hol21, zone12], "2026-10-25", 24, 19, "s4.2 Working days zone holiday 2026-10-12: zone"),
            ("2026-10", TERRITORY, [hol21, zone12], "2026-10-25", 25, 20, "s4.2 Working days zone holiday 2026-10-12: territory"),
            ("2026-10", GLOBAL, [hol21, fri30], "2026-10-25", 26, 20, "s4.2 Working days Friday 2026-10-30 declared selling"),
            ("2026-10", ZONE, [mark("2026-10-21", "global", 0, "holiday"), mark("2026-10-21", "zone", 4, "makeup_day")],
             "2026-10-31", 26, 26, "s4.1 a zone make-up day overrides a global holiday on the zone's path"),
            ("2026-10", ZONE, [mark("2026-10-30", "global", 0, "makeup_day"), mark("2026-10-30", "zone", 4, "emergency_off")],
             "2026-10-31", 26, 26, "s4.1 a zone emergency off overrides a global make-up day"),
            ("2026-10", GLOBAL, [mark("2026-10-23", "global", 0, "holiday")], "2026-10-23", 26, 19,
             "s4.1 a holiday on a Friday is not subtracted twice; elapsed excludes an off day")):
        e = working_entry(month, [5], node, marks, day, note)
        f["working_days"].append(e)
        checks.extend([(note + " month", e["month_days"], md), (note + " elapsed", e["elapsed_days"], el)])

    e = counts_entry(0, 0, 18, 705, 0, "s4.4 Ticket Dashboard 723 / 0 / 0 / 18 (screenshot 11)")
    f["ticket_counts"].append(e)
    checks.extend([(e["note"] + " total", e["total"], 723), (e["note"] + " resolved %", e["resolved_text"]["en"], "2%"),
                   (e["note"] + " open %", e["open_text"]["en"], "0%")])
    e = counts_entry(0, 0, 0, 0, 0, "s4.4 no tickets: 0%")
    f["ticket_counts"].append(e)
    checks.extend([(e["note"] + " resolved", e["resolved_text"]["en"], "0%"), (e["note"] + " open", e["open_text"]["en"], "0%")])
    f["ticket_counts"].append(counts_entry(3, 2, 4, 1, 1, "s4.4 open = new + in_progress, total = every status"))

    for delta, txt, note in ((4 * 86_400_000, "4d ago", "s4.4 age 4 d"), (23 * 3_600_000 + 59 * 60_000, "23h ago", "s4.4 age 23 h 59 m"),
                             (59_000, "just now", "s4.4 age 59 s"), (-5_000, "just now", "s4.4 capture after now (skew)"),
                             (60_000, "1m ago", "s4.4 age exactly 1 min"), (3_600_000, "1h ago", "s4.4 age exactly 1 h"),
                             (86_400_000, "1d ago", "s4.4 age exactly 24 h")):
        e = age_entry(NOW, NOW - delta, note)
        f["ticket_age"].append(e)
        checks.append((note, e["text"]["en"], txt))
    checks.append(("s4.4 age bn 4 d", f["ticket_age"][0]["text"]["bn"], "৪ দিন আগে"))
    return f, checks


# ---------------------------------------------------------------- seeded cases

def seeded(rnd):
    s = {k: [] for k in SEEDED}
    while len(s["percent"]) < SEEDED["percent"]:
        kind = rnd.choice("sssshhhhuuulllz")
        if kind == "s":  # a share: part of a whole
            d = rnd.randrange(1, 10 ** rnd.randrange(1, 8)); n = rnd.randrange(0, d + 1)
        elif kind == "h":  # an exact half at one precision: d = 2*s*j, n = j*(2k+1) makes s*n/d = k + 1/2 (rounds up)
            sc, j = rnd.choice([100, 1_000, 10_000]), rnd.randrange(1, 60)
            d, n = 2 * sc * j, j * (2 * rnd.randrange(0, 300) + 1)
        elif kind == "u":  # above 100% (uncapped rings and details)
            d = rnd.randrange(1, 10_000); n = rnd.randrange(d, 50 * d)
        elif kind == "l":  # large: up to the contract's bound A*W = 3.1e11
            n = rnd.randrange(0, 310_000_000_001); d = rnd.randrange(1, 10 ** rnd.randrange(1, 12))
        else:  # d = 0
            n, d = rnd.randrange(0, 1000), 0
        s["percent"].append(percent_entry(n, d, "seeded"))
    while len(s["qty"]) < SEEDED["qty"]:
        kind = rnd.randrange(4)
        d = 1 if rnd.randrange(3) else rnd.randrange(0, 32)
        if kind == 0 and d > 0:  # around the K boundary: 999.0 .. 1,001.0 times d
            n = rnd.randrange(999 * d, 1001 * d + 1)
        elif kind == 1:  # an exact half of a K tenth (n / 100 = x.5 with d = 1)
            n = rnd.randrange(10, 10 ** rnd.randrange(3, 12)) * 100 + 50
        else:
            n = rnd.randrange(0, 10 ** rnd.randrange(1, 14))
        s["qty"].append(qty_entry(n, d, "seeded"))
    while len(s["tilldate"]) < SEEDED["tilldate"]:
        n = rnd.choice([28, 29, 30, 31, rnd.randrange(0, 32)])
        s["tilldate"].append(tilldate_entry(rnd.randrange(0, 10 ** rnd.randrange(1, 11)), rnd.randrange(-2, n + 3), n, "seeded"))
    while len(s["working_days"]) < SEEDED["working_days"]:
        y, m = rnd.randrange(2025, 2030), rnd.randrange(1, 13)
        last = calendar.monthrange(y, m)[1]
        weekend = sorted(rnd.sample(range(1, 8), rnd.choice([0, 1, 1, 1, 2])))
        node = rnd.choice([GLOBAL, TERRITORY, ZONE])
        used = set()
        marks = []
        for _ in range(rnd.randrange(0, 7)):
            date = datetime.date(y, m, rnd.randrange(1, last + 1)).isoformat()
            level = rnd.choice(LEVELS)
            scope_id = 0 if level == "global" else rnd.choice([ZONE[level + "_id"], ZONE[level + "_id"] + 10])
            if (date, level, scope_id) in used:  # one live declaration per date and scope (calendar_holiday_one)
                continue
            used.add((date, level, scope_id))
            marks.append(mark(date, level, scope_id, rnd.choice(["holiday", "holiday", "emergency_off", "makeup_day"])))
        marks.sort(key=lambda x: (x["date"], LEVELS.index(x["level"]), x["scope_id"]))
        day = datetime.date(y, m, rnd.randrange(1, last + 1)).isoformat()
        s["working_days"].append(working_entry(f"{y:04d}-{m:02d}", weekend, node, marks, day, "seeded"))
    for _ in range(SEEDED["ticket_age"]):
        size = rnd.choice([1_000, 60_000, 3_600_000, 86_400_000, 30 * 86_400_000])
        delta = rnd.randrange(-size // 10, 3 * size)  # a few captures after now (skew)
        s["ticket_age"].append(age_entry(NOW, NOW - delta, "seeded"))
    for _ in range(SEEDED["remaining"]):
        t = rnd.randrange(0, 100_000); a = rnd.randrange(0, 2 * t + 2)
        s["remaining"].append({"t": t, "a": a, "value": remaining(t, a), "note": "seeded"})
    for _ in range(SEEDED["band"]):
        lo = rnd.randrange(0, 100); hi = rnd.randrange(lo, 101); p = rnd.randrange(0, 151)
        s["band"].append({"p": p, "lo": lo, "hi": hi, "band": band(p, lo, hi), "note": "seeded"})
    return s


def safe(o):
    """Every integer in the corpus is a safe JavaScript integer."""
    if isinstance(o, bool) or o is None or isinstance(o, str):
        return True
    if isinstance(o, int):
        return -MAX_SAFE <= o <= MAX_SAFE
    if isinstance(o, list):
        return all(safe(x) for x in o)
    return all(safe(v) for v in o.values())


def build():
    fx, checks = fixtures()
    bad = [f"{what}: oracle {got!r}, contract {want!r}" for what, got, want in checks if got != want]
    if bad:
        raise SystemExit("kpi-format-corpus: the oracle disagrees with the contract's s4 fixtures:\n  " + "\n  ".join(bad))
    sd = seeded(random.Random(SEED))
    sections = {k: fx[k] + sd.get(k, []) for k in fx}
    total_seeded = sum(len(v) for v in sd.values())
    assert total_seeded == 300, total_seeded
    corpus = {
        "_doc": "KPI format corpus (D-TSO-MAN-10, build contract s4); generated by tools/ci/kpi-format-corpus.py --write, "
                "never edited by hand. Integers in, text out (en and bn); one rounding per figure, half up; '—' (U+2014) "
                "for a missing figure. Readers: shared/rules KpiFormatCorpusTest and web/tests/kpi-format-corpus.test.ts.",
        "seed": SEED,
        "dash": DASH,
        "k_suffix": " K",
        "counts": {"seeded": total_seeded, "fixtures": sum(len(v) for v in fx.values()), "fixture_checks": len(checks)},
        **sections,
    }
    assert safe(corpus), "an integer of the corpus is not a safe JavaScript integer"
    return corpus


def render():
    return json.dumps(build(), ensure_ascii=False, indent=1) + "\n"


def main():
    for stream in (sys.stdout, sys.stderr):  # Bengali text in a message must not crash a cp1252 console
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    text = render()
    rel = OUT.relative_to(ROOT).as_posix()
    if "--write" in sys.argv[1:]:
        OUT.parent.mkdir(parents=True, exist_ok=True)
        OUT.write_bytes(text.encode("utf-8"))
        print(f"wrote {rel}")
        return 0
    # read_text: universal newlines, so a CRLF checkout (core.autocrlf) compares equal; the file is written with LF.
    if not OUT.is_file() or OUT.read_text(encoding="utf-8") != text:
        print(f"::error file={rel}::KPI format corpus is stale or missing; run python tools/ci/kpi-format-corpus.py --write")
        return 1
    print(f"KPI format corpus: {rel} up to date (300 seeded cases plus the s4 fixtures; oracle reproduces the contract)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
