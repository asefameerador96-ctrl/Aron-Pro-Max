#!/usr/bin/env python3
"""List the backlog rows of one lane or sub-lane, optionally for one day.

    python3 tools/my-rows.py backend-core        # every BUILD row of the sub-lane, by day
    python3 tools/my-rows.py backend-core 1      # only Day 1
    python3 tools/my-rows.py backend-core --full # include the full acceptance tests
    python3 tools/my-rows.py backend-core --todo # hide rows already in docs/status/*.csv

A name matches the backlog `lane` column (for example `backend`, all sub-lanes) or the `sublane` column
(`backend-core`, `backend-reports`, `backend-admin`, `android-sr-a`, `android-sr-b`, `android-geo-dpc`,
`web-admin`, `web-config`, ...). Each row shows its model tier (docs/29): T1 = top-tier builder and checker,
T2 = standard tier. A lane may promote a T2 row to T1, never demote.
Source: docs/25-build-backlog.csv (read-only for lanes).
"""
import csv
import glob
import os
import sys
import textwrap

root = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
path = os.path.join(root, 'docs', '25-build-backlog.csv')
args = [a for a in sys.argv[1:] if not a.startswith('--')]
full = '--full' in sys.argv
todo = '--todo' in sys.argv
if not args:
    sys.exit(__doc__)
lane = args[0]
day = args[1] if len(args) > 1 else None

done = set()
if todo:
    for f in glob.glob(os.path.join(root, 'docs', 'status', '*.csv')):
        for r in csv.DictReader(open(f, encoding='utf-8')):
            rid = (r.get('row_id') or '').strip()
            if rid and 'recheck' not in rid:
                done.add(rid)

rows = [r for r in csv.DictReader(open(path, encoding='utf-8'))
        if r['decision'] == 'BUILD' and lane in (r['lane'], r.get('sublane', '')) and (day is None or r['day'] == day)
        and r['id'] not in done]
rows.sort(key=lambda r: (int(r['day']), r['id']))
hours = {'S': 2, 'M': 4, 'L': 8}
print(f"{len(rows)} rows, {sum(hours[r['size']] for r in rows)} band-hours (S=2, M=4, L=8)\n")
for r in rows:
    print(f"[Day {r['day']}] {r['id']}  size {r['size']}  tier {r.get('model_tier') or '-'}  needs: {r['dependencies'] or '-'}")
    print('  ' + r['name'])
    test = r['acceptance_test'] if full else r['acceptance_test'][:220]
    print(textwrap.indent(textwrap.fill('ACCEPT: ' + test, 110), '  '))
    if r.get('unknown_assumed'):
        print('  ASSUMED (sponsor to confirm): ' + r['unknown_assumed'])
    if r.get('scope_note'):
        print('  NOTE: ' + r['scope_note'][:300])
    print()
