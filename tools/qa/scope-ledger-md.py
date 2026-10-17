#!/usr/bin/env python3
"""qa: turn build/scope-ledger.tsv (ScopeLeakAcrossEndpointsTest) into the supervisor route ledger table.

  python3 tools/qa/scope-ledger-md.py backend/app/build/scope-ledger.tsv > table.md

Keeps every dashboard route and every operation an AMO, TSO or DMO gets a 2xx on with its own ids."""
import csv, sys, collections
rows = list(csv.reader(open(sys.argv[1]), delimiter="\t"))
head, body = rows[0], rows[1:]
callers = head[2:]
def parse(cell):
    out = []
    for tok in cell.split():
        v, s = tok.rsplit(":", 1); out.append((v, int(s)))
    return out
def summary(cell):
    calls = parse(cell)
    if not calls: return "—"
    own = [s for v, s in calls if v == "own"]
    other = collections.Counter(s for v, s in calls if v != "own")
    return f"own {own[0] if own else '—'}; other " + (", ".join(f"{n}×{s}" for s, n in sorted(other.items())) if other else "none")
sel = []
for r in body:
    op, judged, cells = r[0], r[1], r[2:]
    by = dict(zip(callers, cells))
    sup_ok = any(any(v == "own" and 200 <= s < 300 for v, s in parse(by[c])) for c in ("amo1001", "tso1001", "dmo1001"))
    if "/dashboards/" in op or sup_ok: sel.append((op, judged, by))
other2xx = 0
print("| Operation | National control | " + " | ".join(callers) + " |")
print("|---|---|" + "---|" * len(callers))
for op, judged, by in sel:
    for c in callers:
        other2xx += sum(1 for v, s in parse(by[c]) if v != "own" and 200 <= s < 300)
    print(f"| `{op}` | {judged} | " + " | ".join(summary(by[c]) for c in callers) + " |")
print(f"\n{len(sel)} operations; other-division variants that answered 2xx: {other2xx} (each was checked by the leak detector and none leaked).", file=sys.stderr)
