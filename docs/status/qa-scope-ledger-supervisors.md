# Scope ledger: dashboard, AMO and TSO routes (qa, 2026-10-17)

Every route a supervisor (amo1001, tso1001, dmo1001) gets a 2xx on with its own ids, plus every dashboard route,
against the endpoint-wide scope harness (`ScopeLeakAcrossEndpointsTest`, N-014/N-061). Generated from
`backend/app/build/scope-ledger.tsv` by `tools/qa/scope-ledger-md.py`, from the local run on lane/qa after 5b8b1826,
with the report-geo probe added.

How to read a cell: `own S` is the status of the caller's own call. `other N×S` lists the calls that named the other
division: its ids in the path or in a required query, a selector, the body (report queries included, through
`geo.zone`, `geo.territory` and `geo.division`), or coordinates. **National control**: `judged` means the national
admin sees the other division on this read, so a leak would show. `blind` is on the baseline list with its reason.
`write` marks a write or a read sent by POST.

**Result: 60 operations, 0 leaks.** 32 other-division calls answered 2xx. Each went through the leak detector
(marker, other ids, record uuids, and the numbers rule for aggregates), and none leaked:
- **List reads** whose other-division selector is filtered down to the caller's reach (nothing of the other division
  came back): `memos` (outlet_id, route_id), `route-assignments` (route_id, user_id), `outlet-requests`
  (territory_id, zone_id), `risk-signals` (user_id), `report-exports` (user_id; own exports only) and
  `outlets/nearby` at the other division's pins.
- **Configuration reads** that answer for another zone: `admin/config/resolve` (other-path) and `admin/config/values`
  (other `scope_id`), for TSO and DMO. Configuration is on the blind list as global by design: radius, thresholds,
  windows, and no field data. **Question for the lead:** should a TSO see another zone's resolved configuration? If
  not, it is a scope defect for the config owner.
- No dashboard answered 2xx to another division's selector.

Whole run: 1,012 calls, 392 naming the other division, 103 of them refused for scope (out of scope or 404). The
national control judges 45 of 78 reads.

**Not covered by this ledger** (from the N-061 review's known gaps):
- `media/{}/read-url` and `admin/config/reach/{}` are debt on the blind list.
- Report queries: only the sampled report key is probed, not every report.
- Geo: only the division level.
- No positive own check on the uuid write paths.

| Operation | National control | sr1001 | amo1001 | tso1001 | dmo1001 |
|---|---|---|---|---|---|
| `GET /v1/admin/calendar/holidays` | blind | own 403; other none | own 403; other none | own 200; other none | own 200; other none |
| `GET /v1/admin/clusters` | judged | own 403; other 1×403 | own 403; other 1×403 | own 200; other 1×403 | own 200; other 1×403 |
| `GET /v1/admin/code-lists` | blind | own 403; other none | own 403; other none | own 200; other none | own 200; other none |
| `GET /v1/admin/config/changes` | blind | own 403; other none | own 403; other none | own 200; other none | own 200; other none |
| `GET /v1/admin/config/keys` | blind | own 403; other none | own 403; other none | own 200; other none | own 200; other none |
| `GET /v1/admin/config/resolve` | blind | own 403; other 1×403 | own 403; other 1×403 | own 200; other 1×200 | own 200; other 1×200 |
| `GET /v1/admin/config/values` | blind | own 403; other 1×403 | own 403; other 1×403 | own 200; other 1×200 | own 200; other 1×200 |
| `GET /v1/admin/config/versions` | blind | own 403; other none | own 403; other none | own 200; other none | own 200; other none |
| `GET /v1/admin/device-otps` | judged | own 403; other 1×403 | own 403; other 1×403 | own 200; other 1×403 | own 200; other 1×403 |
| `GET /v1/admin/devices` | judged | own 403; other 1×403 | own 403; other 1×403 | own 200; other 1×403 | own 200; other 1×403 |
| `GET /v1/admin/devices/{}` | judged | own 403; other 1×403 | own 403; other 1×403 | own 200; other 1×404 | own 200; other 1×404 |
| `GET /v1/admin/devices/{}/directives` | judged | own 403; other 1×403 | own 403; other 1×403 | own 200; other 1×404 | own 200; other 1×404 |
| `GET /v1/admin/devices/{}/status-history` | judged | own 403; other 1×403 | own 403; other 1×403 | own 200; other 1×404 | own 200; other 1×404 |
| `GET /v1/admin/geo/{}` | judged | own 403; other none | own 403; other none | own 200; other none | own 200; other none |
| `GET /v1/admin/geo/{}/{}` | judged | own 403; other 1×403 | own 403; other 1×403 | own 200; other 1×404 | own 200; other 1×404 |
| `GET /v1/admin/outlets` | judged | own 403; other 3×403 | own 403; other 3×403 | own 200; other 3×403 | own 200; other 3×403 |
| `GET /v1/admin/outlets/{}` | judged | own 403; other 1×403 | own 403; other 1×403 | own 200; other 1×404 | own 200; other 1×404 |
| `GET /v1/admin/prices` | blind | own 403; other none | own 403; other none | own 200; other none | own 200; other none |
| `GET /v1/admin/product-nodes/{}` | blind | own 403; other none | own 403; other none | own 200; other none | own 200; other none |
| `GET /v1/admin/route-assignments` | judged | own 403; other 3×403 | own 403; other 3×403 | own 200; other 2×200, 1×403 | own 200; other 2×200, 1×403 |
| `GET /v1/admin/routes` | judged | own 403; other 2×403 | own 403; other 2×403 | own 200; other 2×403 | own 200; other 2×403 |
| `GET /v1/admin/routes/{}` | judged | own 403; other 1×403 | own 403; other 1×403 | own 200; other 1×404 | own 200; other 1×404 |
| `GET /v1/admin/skus` | blind | own 403; other none | own 403; other none | own 200; other none | own 200; other none |
| `GET /v1/admin/users` | judged | own 403; other 1×403 | own 403; other 1×403 | own 200; other 1×403 | own 200; other 1×403 |
| `GET /v1/admin/users/{}` | judged | own 403; other 1×403 | own 403; other 1×403 | own 200; other 1×404 | own 200; other 1×404 |
| `GET /v1/admin/users/{}/scope` | judged | own 403; other 1×403 | own 403; other 1×403 | own 200; other 1×404 | own 200; other 1×404 |
| `GET /v1/app/home` | judged | own 200; other none | own 200; other none | own 200; other none | own 200; other none |
| `GET /v1/app/update-check` | blind | own 200; other none | own 200; other none | own 200; other none | own 200; other none |
| `GET /v1/config/delta` | blind | own 200; other none | own 200; other none | own 200; other none | own 200; other none |
| `GET /v1/dashboards/daily-tracking` | judged | own 403; other 1×403 | own 403; other 1×403 | own 200; other 1×403 | own 200; other 1×403 |
| `GET /v1/dashboards/geo-validation` | judged | own 403; other 1×403 | own 403; other 1×403 | own 200; other 1×403 | own 200; other 1×403 |
| `GET /v1/dashboards/login-submit` | judged | own 403; other 1×403 | own 403; other 1×403 | own 200; other 1×403 | own 200; other 1×403 |
| `GET /v1/dashboards/summary` | judged | own 403; other 1×403 | own 403; other 1×403 | own 200; other 1×403 | own 200; other 1×403 |
| `GET /v1/dashboards/sync-health` | judged | own 403; other 1×403 | own 403; other 1×403 | own 200; other 1×403 | own 200; other 1×403 |
| `GET /v1/day/final-submit/preview` | judged | own 403; other 1×403 | own 403; other 1×403 | own 200; other 1×403 | own 200; other 1×403 |
| `GET /v1/feedback` | judged | own 403; other none | own 403; other none | own 200; other none | own 200; other none |
| `GET /v1/leave` | judged | own 403; other none | own 403; other none | own 200; other none | own 200; other none |
| `GET /v1/me` | blind | own 200; other none | own 200; other none | own 200; other none | own 200; other none |
| `GET /v1/memos` | judged | own 200; other 2×200 | own 200; other 2×200 | own 200; other 2×200 | own 200; other 2×200 |
| `GET /v1/outlet-requests` | judged | own 200; other 2×200 | own 200; other 1×200, 1×403 | own 200; other 1×200, 1×403 | own 200; other 1×200, 1×403 |
| `GET /v1/outlets` | judged | own 200; other 3×403 | own 200; other 3×403 | own 200; other 3×403 | own 200; other 3×403 |
| `GET /v1/outlets/nearby` | judged | own 200; other 1×200, 1×403 | own 200; other 1×200, 1×403 | own 200; other 1×200, 1×403 | own 200; other 1×200, 1×403 |
| `GET /v1/report-exports` | blind | own 200; other 1×200 | own 200; other 1×200 | own 200; other 1×200 | own 200; other 1×200 |
| `GET /v1/reports` | blind | own 200; other none | own 200; other none | own 200; other none | own 200; other none |
| `GET /v1/risk-signals` | judged | own 403; other 2×403 | own 200; other 1×200, 1×403 | own 200; other 1×200, 1×403 | own 200; other 1×200, 1×403 |
| `GET /v1/routes` | judged | own 200; other 2×403 | own 200; other 2×403 | own 200; other 2×403 | own 200; other 2×403 |
| `GET /v1/sync/generation` | blind | own 200; other none | own 200; other none | own 200; other none | own 200; other none |
| `GET /v1/sync/totals` | blind | own 200; other none | own 200; other none | own 200; other none | own 200; other none |
| `GET /v1/tasks` | judged | own 200; other 1×403 | own 200; other 1×403 | own 200; other 1×403 | own 200; other 1×403 |
| `GET /v1/team/locations` | judged | own 403; other 1×403 | own 200; other 1×403 | own 200; other 1×403 | own 200; other 1×403 |
| `GET /v1/team/stock` | judged | own 403; other 1×403 | own 200; other 1×403 | own 200; other 1×403 | own 200; other 1×403 |
| `GET /v1/tutorials` | blind | own 200; other none | own 200; other none | own 200; other none | own 200; other none |
| `GET /v1/visit-plans` | judged | own 403; other none | own 403; other none | own 200; other none | own 200; other none |
| `GET /v1/web-entry/route-day` | judged | own 403; other 1×403 | own 403; other 1×403 | own 200; other 1×403 | own 403; other 1×403 |
| `POST /v1/admin/data-void` | write | own 403; other 1×403 | own 403; other 1×403 | own 200; other 1×403 | own 403; other 1×403 |
| `POST /v1/admin/users/{}/credentials` | write | own 403; other 1×403 | own 403; other 1×403 | own 200; other 1×404 | own 403; other 1×403 |
| `POST /v1/dashboards/daily-tracking/actions` | write | own 403; other 1×403 | own 403; other 1×403 | own 409; other 1×403 | own 409; other 1×403 |
| `POST /v1/day/final-submit` | write | own 403; other 1×403 | own 403; other 1×403 | own 200; other 1×409 | own 403; other 1×403 |
| `POST /v1/reports/{}/query` | write | own 403; other 1×403 | own 200; other 1×403 | own 200; other 1×403 | own 200; other 1×403 |
| `POST /v1/risk-signals/{}/review` | write | own 403; other none | own 200; other none | own 409; other none | own 409; other none |
