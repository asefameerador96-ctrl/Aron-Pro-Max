#!/usr/bin/env python3
"""List the backlog rows of one lane, optionally for one day.

    python3 tools/my-rows.py backend            # every BUILD row of the lane, by day
    python3 tools/my-rows.py backend 1          # only Day 1
    python3 tools/my-rows.py backend 1 --full   # include the full acceptance tests

Source: docs/25-build-backlog.csv (read-only for lanes).
"""
import csv
import os
import sys
import textwrap

path = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'docs', '25-build-backlog.csv')
args = [a for a in sys.argv[1:] if not a.startswith('--')]
full = '--full' in sys.argv
if not args:
    sys.exit(__doc__)
lane = args[0]
day = args[1] if len(args) > 1 else None

rows = [r for r in csv.DictReader(open(path, encoding='utf-8'))
        if r['decision'] == 'BUILD' and r['lane'] == lane and (day is None or r['day'] == day)]
rows.sort(key=lambda r: (int(r['day']), r['id']))
hours = {'S': 2, 'M': 4, 'L': 8}
print(f"{len(rows)} rows, {sum(hours[r['size']] for r in rows)} band-hours (S=2, M=4, L=8)\n")
for r in rows:
    print(f"[Day {r['day']}] {r['id']}  size {r['size']}  needs: {r['dependencies'] or '-'}")
    print('  ' + r['name'])
    test = r['acceptance_test'] if full else r['acceptance_test'][:220]
    print(textwrap.indent(textwrap.fill('ACCEPT: ' + test, 110), '  '))
    if r.get('unknown_assumed'):
        print('  ASSUMED (sponsor to confirm): ' + r['unknown_assumed'])
    print()
