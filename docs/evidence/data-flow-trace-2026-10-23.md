# Data-flow trace, 2026-10-23 (lane qa, wave 4, docs/34 s4.4)

One selling day of `sr1001` on the Gulshan/Banani dev geography, from the seed to the dashboard and the reports, with the
number at each hop next to the device's own figure. **Where it ran:** the in-process production wiring on a local
PostgreSQL 16, seeded like the Azure dev database (every `db/seed` file except `04_*`, then `infra/sql/devseed-smoke-outlet.sql`),
test `backend/app/src/test/kotlin/com/aktcl/aron/backend/app/DataFlowTraceTest.kt` (`ApiWorld(devProfile = true)`), INT 384ecee2.
Not loaded: `devseed-app-release.sql`; the harness lifts rate limits and MFA. Run: `ARON_TEST_PG_URL=... ./gradlew :backend:app:test --tests '*DataFlowTraceTest*'`;
the trace below is written to `backend/app/build/data-flow-trace.md` by every run. It is **not** a proof on dev (that is the deploy smoke) and
the "device" is the test's model of the phone, not the Android app.

Business day: Saturday 2026-10-24 (route `GUL-SR-2`, clock 10:30Z = 16:30 Dhaka). The Dhaka date at writing is Friday
2026-10-23, which has no selling route by design. A second division (`LKX`, moved under its own wing) sells a smaller day so scope filters have something to cut.

## Verdict (checked by an Opus checker; its corrections are applied)
- **Proven by equality with an independent model:** every quantity and money figure of the Gulshan day (2 active memos, gross 2,487,000 mtk, paid 1,908,600, due 578,400,
  collected 2,000; sold 160+20 sticks of cigarettes, 75 bidi sticks, 50 lighter pieces, 5 match dozens; one voided memo excluded) is the same in the phone's model, `server_totals`,
  `GET /v1/sync/totals`, the tables, `dw.fact_memo`, `dw.agg_daily_route`, `dw.agg_daily_route_sku`, `dw.v_daily_sku` (base units per category), the national dashboard cards, the route map and the reports.
  Packets become sticks/pieces/dozens with the SKU's `base_per_pack` (20, 10, 25, dozen = 1) and money follows the quantity.
- **Not independent (be exact):** prices and `per_base_qty` come from the bundle (server data); `paid`, `due`, `qty_base` are sent by the device and only checked for internal consistency; every picked SKU has
  `per_base_qty = 1`, amounts are whole taka multiples, no discount or QC: the half-up and `round_adj` branches did not run here. Dashboard money is not asserted (quantities, calls, geo are); report money totals reconcile by hand (info rows).
- **Idempotency proven (sequential):** a resend under a new `batch_uuid` is all `duplicate`; the same `batch_uuid` replays (`replayed: true`, identical acks); children ahead of parents are `rejected parent_missing`
  (retryable) and accepted on resend; rows per table equal the device count (4 visits, 3 memos, 8 lines, 1 collection, 1 void, 4 closes); Sales Submit sent twice is `duplicate`; Final Submit with the same `client_uuid` replays, another gets 409;
  a late sale after Final Submit is accepted once, the day stays `final_submitted`, 4 late rows flagged, the cards count it once; a changed payload under a stored `client_uuid` is `quarantined payload_conflict` and the memo is unchanged; the same content under new uuids is
  `quarantined content_duplicate`; the same `memo_no` under a new uuid is `quarantined memo_no_duplicate` (one row stored).
- **Not proven here:** concurrency (see `SyncFaultScheduleFuzzTest`), batch_uuid reuse with different records, server-side parking retry/expiry (docs/16 DQ-03), the window between a stored memo header and its rejected lines (not read mid-way).
- **Dev (test account):** `/v1/health` 200, build 384ecee2, ready 200; unauthenticated calls 401. Deploy #243: seed Succeeded; the slice smoke FAILED (non-blocking) on Friday at step 2b (see `infra-qa-1`). Seed 06 is applied on dev (inference from that Friday result:
  the bundle had no planned route, which would not hold if Mirpur's daily route were active). Route 22636 is **not confirmed**: inferred `BAN-SR-11` (Thursday), outlet 28201 `SMOKE-SR-003`; the next deploy that prints codes settles it. No dev login was used or needed.

## Findings (open rows of `backend/app/src/test/resources/data-flow-trace-known-findings.txt`; the owner deletes a row with the fix; QA-FLOW-05 and the Friday smoke were fixed by db on lane/db 6ce7e368, train 11)
| Id | Owner | What | Request |
|---|---|---|---|
| QA-FLOW-01 | backend-authz | BRAND user: 403 on every national dashboard operation (NationalDeps built without a brand resolver) | `backend-authz-qa-1.md` |
| QA-FLOW-02 | backend-core | `memo_no_invalid` never raised: another user's name, another date, out-of-block numbers are accepted | `backend-core-qa-1.md` s1 |
| QA-FLOW-03 | backend-reports | `by-outlet` (and `-by-day`) `sold_qty_base` and the totals of `route-std`/`ds-rrs` add sticks, pieces, dozens | `backend-reports-qa-1.md` |
| QA-FLOW-04 | backend-core | question: Final Submit preview lists/warns for a route not planned that day | `backend-core-qa-1.md` s3 |
| QA-FLOW-05 | db | doc typo: `dw.agg_daily_outlet_category` | `db-qa-1.md` |
| QA-FLOW-06 | backend-core | `pack_factor` not compared with `sku.base_per_pack`: a wrong pack size is accepted and books half the quantity | `backend-core-qa-1.md` s2 |
| QA-FLOW-07 | backend-authz | web tokens have no `geo`/`brands`/`modules` claims (scope is resolved server-side; docs/34 s1 says claims) | `backend-authz-qa-1.md` s2 |
Infra: the Friday smoke is red instead of skipped (`infra-qa-1.md`). Scope rules that held: see hop 9.

# Trace (generated; PASS = every listed value equal; FINDING = a listed open defect)

### 1 Seed (dev profile: db/seed without 04_*, plus the smoke outlets)

| Hop | Values | Result |
|---|---|---|
| config version `Dev database overrides` absent (the Gulshan seed applies) | count = 0; expected = 0 | PASS |
| Gulshan/Banani routes, mask, outlets (20 seed + 1 smoke each) | rows = BAN-SR-11:36:21,GUL-SR-1:18:21,GUL-SR-2:9:21; expected = BAN-SR-11:36:21,GUL-SR-1:18:21,GUL-SR-2:9:21 | PASS |
| Friday (bit 6) is no route's day | sum of masks = 63; Sat..Thu = 63 | PASS |
| SKU units by category (count, base_per_pack lo-hi, base unit, entry unit) | bidi: 7, 12-25, stick, pack / cigarette: 31, 10-20, stick, pack / lighter: 2, 25-25, piece, pack / match: 2, 1-1, dozen, dozen | info |
| sr1001 open primaries | routes = BAN-SR-11,GUL-SR-1,GUL-SR-2; expected = BAN-SR-11,GUL-SR-1,GUL-SR-2 | PASS |

### 2 Login and bundle (sr1001, seeded dev phone)

| Hop | Values | Result |
|---|---|---|
| access token claims (names only) | [amr, aud, did, dvu, exp, flv, iat, iss, jti, nbf, perm, pii, role, sub, sv, uname] | info |
| scope claims in the token | role="SR" | info |
| bundle meta | bundle_version=2026-10-24:1 config_version=3 | info |
| routes in the Saturday bundle | planned today = GUL-SR-2; expected = GUL-SR-2 | PASS |
| outlets on the planned route with a pin | with pin = 21; outlets = 21; expected = 21 | PASS |
| plan SKUs with an outlet price today | count = 39; expected >= 39 = 39 | PASS |
| pack sizes in the bundle (category: entry unit x pack) | cigarette: packx10/packx20 / bidi: packx12/packx25 / lighter: packx25 / match: dozenx1 | info |
| memo numbers: bind_ordinal x block + n | ordinal 0, block 500, first number 1 | info |
| first bundle of the day logged the route-day in | route_day.logged_in_at set = true; expected = true | PASS |

### 3 The day on the phone (device truth)

| Hop | Values | Result |
|---|---|---|
| SKUs picked | MaxR-20S (cigarette) id 2: pack x 20 stick, price 9200/1 mtk; MaxR-10S (cigarette) id 1: pack x 10 stick, price 9200/1 mtk; SAB-25s (bidi) id 32: pack x 25 stick, price 880/1 mtk; Aster (lighter) id 39: pack x 25 piece, price 12500/1 mtk; FB (match) id 41: dozen x 1 dozen, price 28000/1 mtk | info |
| GUL2-001 line 1 | MaxR-20S: 3 pack x pack_factor 20 = qty_base 60 stick; gross 552000 mtk | info |
| GUL2-001 line 2 | MaxR-10S: 2 pack x pack_factor 10 = qty_base 20 stick; gross 184000 mtk | info |
| GUL2-001 line 3 | SAB-25s: 1 pack x pack_factor 25 = qty_base 25 stick; gross 22000 mtk | info |
| GUL2-001 line 4 | Aster: 2 pack x pack_factor 25 = qty_base 50 piece; gross 625000 mtk | info |
| GUL2-001 line 5 | FB: 5 dozen x pack_factor 1 = qty_base 5 dozen; gross 140000 mtk | info |
| GUL2-002 line 1 | MaxR-20S: 5 pack x pack_factor 20 = qty_base 100 stick; gross 920000 mtk | info |
| GUL2-002 line 2 | SAB-25s: 2 pack x pack_factor 25 = qty_base 50 stick; gross 44000 mtk | info |
| GUL2-003 line 1 | MaxR-10S: 4 pack x pack_factor 10 = qty_base 40 stick; gross 368000 mtk | info |
| GUL2-001 memo | gross 1523000 paid 1523000 due 0 mtk | info |
| GUL2-002 memo | gross 964000 paid 385600 due 578400 mtk | info |
| GUL2-003 memo | gross 368000 paid 368000 due 0 mtk (voided later) | info |
| GUL2-004 memo | gross 0 paid 0 due 0 mtk | info |
| device model: active memos (voided and zero-sale visits excluded) | memos = 2; expected = 2 | PASS |
| device totals | gross 2487000 mtk, paid 1908600, due 578400 | info |

### 4 Sync upload: out of order, partial, duplicate, replay

| Hop | Values | Result |
|---|---|---|
| due collection | 2000 mtk against GUL2-002 (due 578400) | info |
| device counts per type | {due_collection=1, memo=3, memo_line=8, memo_void=1, visit=4, visit_close=4} | info |
| batch 1 (5 rows, children first): HTTP 200 | {rejected=5} | info |
| batch 1 ack detail | memo:rejected(parent_missing)[sync.outcome.parent_missing] memo_line:rejected(parent_missing)[sync.outcome.parent_missing] memo_line:rejected(parent_missing)[sync.outcome.parent_missing] memo_line:rejected(parent_missing)[sync.outcome.parent_missing] visit_close:rejected(parent_missing)[sync.outcome.parent_missing] | info |
| batch 2 (whole day, shuffled): HTTP 200 | {rejected=10, accepted=11} | info |
| batch 2 ack detail | visit_close:rejected(parent_missing)[sync.outcome.parent_missing] visit:accepted visit:accepted memo:rejected(parent_missing)[sync.outcome.parent_missing] visit:accepted memo_line:rejected(parent_missing)[sync.outcome.parent_missing] due_collection:rejected(parent_missing)[sync.outcome.parent_missing] memo:accepted memo_line:rejected(parent_missing)[sync.outcome.parent_missing] memo_line:rejected(parent_missing)[sync.outcome.parent_missing] memo_void:rejected(parent_missing)[sync.outcome.parent_missing] memo_line:rejected(parent_missing)[sync.outcome.parent_missing] visit_close:accepted memo_line:rejected(parent_missing)[sync.outcome.parent_missing] visit:accepted visit_close:accepted memo_line:rejected(parent_missing)[sync.outcome.parent_missing] visit_close:accepted memo:accepted memo_line:accepted memo_line:accepted | info |
| batch 2 rejected rows are all retryable parent_missing | codes = [parent_missing/true]; expected = [parent_missing/true] | PASS |
| batch 3 (whole day in order): HTTP 200 | {duplicate=11, accepted=10} | info |
| batch 3 leaves no rejected or quarantined row | bad rows = 0; expected = 0 | PASS |
| batch 4 (whole day again, new batch_uuid: a lost-ack resend): every row duplicate | statuses = [duplicate]; expected = [duplicate] | PASS |
| batch 1 resent with its batch_uuid: replayed flag | replayed = true; expected = true | PASS |
| batch 1 replay answers the original acks | same = true; expected = true | PASS |
| batch 2 resent with its batch_uuid: replayed, same acks | replayed+same = true/true; expected = true/true | PASS |
| visits: device vs app.visit | device = 4; server = 4 | PASS |
| memos: device vs app.memo | device = 3; server = 3 | PASS |
| memo lines: device vs app.memo_line | device = 8; server = 8 | PASS |
| due collections: device vs app.due_collection | device = 1; server = 1 | PASS |
| voids: device vs app.memo_void | device = 1; server = 1 | PASS |
| distinct memo_no | distinct = 3; memos = 3 | PASS |
| ingest registry holds each client_uuid once | registry rows = 21; uuids = 21 | PASS |

### 5 Server count: server_totals, GET /v1/sync/totals, tables (device count = server count)

| Hop | Values | Result |
|---|---|---|
| active memos | device = 2; server_totals = 2 | PASS |
| gross mtk | device = 2487000; server_totals = 2487000 | PASS |
| net mtk | device = 2487000; server_totals = 2487000 | PASS |
| paid mtk (sale payments) | device = 1908600; server_totals = 1908600 | PASS |
| due mtk | device = 578400; server_totals = 578400 | PASS |
| due collected mtk | device = 2000; server_totals = 2000 | PASS |
| sold qty (base units) by sku, sticks/pieces/dozens | device = {1=20, 2=160, 32=75, 39=50, 41=5}; server_totals = {1=20, 2=160, 32=75, 39=50, 41=5} | PASS |
| net mtk by category | device = {bidi=66000, cigarette=1656000, lighter=625000, match=140000}; server_totals = {bidi=66000, cigarette=1656000, lighter=625000, match=140000} | PASS |
| accepted due_collection | device = 1; server_totals = 1 | PASS |
| accepted memo | device = 3; server_totals = 3 | PASS |
| accepted memo_line | device = 8; server_totals = 8 | PASS |
| accepted memo_void | device = 1; server_totals = 1 | PASS |
| accepted visit | device = 4; server_totals = 4 | PASS |
| accepted visit_close | device = 4; server_totals = 4 | PASS |
| rejected + quarantined, all types | server = 0; expected = 0 | PASS |
| GET /v1/sync/totals answers 200 | status = 200; expected = 200 | PASS |
| GET /v1/sync/totals money equals the batch answer | same = true; expected = true | PASS |

### 6 Warehouse: dw facts, views and aggregates (base units)

| Hop | Values | Result |
|---|---|---|
| dw.fact_memo active rows | dw = 2; device = 2 | PASS |
| dw.fact_memo voided rows | dw = 1; device = 1 | PASS |
| dw.fact_visit sr_call rows | dw = 4; device = 4 | PASS |
| agg_daily_route.active_memo_count | dw = 2; device = 2 | PASS |
| agg_daily_route.gross_mtk | dw = 2487000; device = 2487000 | PASS |
| agg_daily_route.net_mtk | dw = 2487000; device = 2487000 | PASS |
| agg_daily_route.paid_mtk | dw = 1908600; device = 1908600 | PASS |
| agg_daily_route.due_mtk | dw = 578400; device = 578400 | PASS |
| agg_daily_route.dues_collected_mtk | dw = 2000; device = 2000 | PASS |
| agg_daily_route.successful_calls (outlets with a sale) | dw = 2; device = 2 | PASS |
| agg_daily_route.visits (sr calls) | dw = 4; device = 4 | PASS |
| agg_daily_route.geo_valid_visits | dw = 4; device = 4 | PASS |
| agg_daily_route.target_outlets | dw = 21; bundle outlets = 21 | PASS |
| agg_daily_route (selected) | day_state=synced, logged_in_at=2026-10-24 10:30:00.0, sales_submitted_at=null, final_submitted_at=null, target_outlets=21, visited_outlets=4 | info |
| agg_daily_route_sku.sold_qty_base by sku (base units) | dw = {1=20, 2=160, 32=75, 39=50, 41=5}; device = {1=20, 2=160, 32=75, 39=50, 41=5} | PASS |
| dw.v_daily_sku summed by category (sticks, pieces, dozens) | dw = {bidi=75, cigarette=180, lighter=50, match=5}; device = {bidi=75, cigarette=180, lighter=50, match=5} | PASS |
| category -> base unit | bidi=stick, cigarette=stick, lighter=piece, match=dozen | info |
| dw.agg_daily_outlet_sku base units, checksum only (mixed units) | dw = 310; device = 310 | PASS |
| dw.agg_daily_route_variant base units, checksum only (mixed units) | dw = 310; device = 310 | PASS |
| dw.agg_daily_outlet net_mtk summed over the route (equals gross: no discounts) | dw = 2487000; device = 2487000 | PASS |
| dw.v_daily_route and v_daily_sr agree with the table | v_daily_route.gross = 2487000; v_daily_sr.gross = 2487000; device = 2487000 | PASS |
| dw.agg_daily_outlet_category exists (named in docs/evidence/seed-gulshan-banani.md) | to_regclass = absent; expected = dw.agg_daily_outlet_category | open finding (see the request) |

### 7 Other division (LKX, its own wing) sells on the same Saturday, so a scope filter has something to cut

| Hop | Values | Result |
|---|---|---|
| LKX day uploaded in order | statuses = [accepted]; expected = [accepted] | PASS |
| LKX device truth (base units by category) | {bidi=25, cigarette=40, match=3} | info |
| LKX gross mtk | 474000 | info |

### 8 National dashboard (GET/POST /v1/dashboards/national/*): figures per caller, scope resolved on the server

| Hop | Values | Result |
|---|---|---|
| SUPERADMIN web token claim names | [amr, aud, exp, flv, iat, iss, jti, nbf, perm, pii, role, sub, sv, uname] | info |
| SUPERADMIN sales cards: total_sales per category (base units) | dashboard = {bidi=100, cigarette=220, lighter=50, match=8}; device truth = {bidi=100, cigarette=220, lighter=50, match=8} | PASS |
| SUPERADMIN sales cards: base_unit per category | units = {bidi=stick, cigarette=stick, lighter=piece, match=dozen}; expected = {bidi=stick, cigarette=stick, lighter=piece, match=dozen} | PASS |
| SUPERADMIN sales card detail | cigarette target/day 24003.600 remaining 23783.600 achv_bp 92 routes 3; bidi target/day 3600.960 remaining 3500.960 achv_bp 278 routes 3; lighter target/day 60.000 remaining 10.000 achv_bp 8333 routes 3; match target/day 36.000 remaining 28.000 achv_bp 2222 routes 3; working_days 25; till_time true | info |
| SUPERADMIN CPR target outlets / successful calls | dashboard = 41/4; device truth = 41/4 | PASS |
| SUPERADMIN CPR percent (basis points) | 976 | info |
| SUPERADMIN channel calls total | dashboard = 4; successful calls = 4 | PASS |
| SUPERADMIN channel calls | GT=4, DCC=0, Astha=0, RCC=0, MT=0, HoReCa=0 | info |
| SUPERADMIN channel STD total = cigarette sticks | dashboard = 220; cigarette card = 220 | PASS |
| SUPERADMIN channel STD by channel (stick) | GT=220, DCC=0, Astha=0, RCC=0, MT=0, HoReCa=0 | info |
| SUPERADMIN login/submit | target 2, login 2 (10000 bp), submitted 0 | info |
| SUPERADMIN successful login = routes whose bundle was downloaded | dashboard = 2; device truth = 2 | PASS |
| SUPERADMIN submitted routes (nobody has submitted) | dashboard = 0; expected = 0 | PASS |
| SUPERADMIN final submit | zones 3, service zones 2, submitted 0, remaining 2 | info |
| SUPERADMIN geo-fencing total calls / validated | dashboard = 6/6; device truth = 6/6 | PASS |
| SUPERADMIN FF Geo pins | 2 pins, counts {"red":0,"amber":0,"green":2}, routes without position 0 | info |
| SUPERADMIN freshness | {"date":"2026-10-24","till_time":true,"as_of":"2026-10-24T10:30:00Z","last_updated_at":"2026-10-24T10:30:00Z","tables":[{"table":"agg_daily_route_sku","last_updated_at":"2026-10-24T10:30:00Z"},{"table":"agg_daily_route_variant","last_updated_at":"2026-10-24T10:30:00Z"},{"table":"agg_daily_outlet_sku | info |
| SUPERADMIN filter-options counts | date="2026-10-24", till_time=true, as_of="2026-10-24T10:30:00Z", last_updated_at=null, wings=2, divisions=2, territories=2, houses=3, zones=4, categories=4, segments=6, brands=17, variants=30 | info |
| ADMIN web token claim names | [amr, aud, exp, flv, iat, iss, jti, nbf, perm, pii, role, sub, sv, uname] | info |
| ADMIN sales cards: total_sales per category (base units) | dashboard = {bidi=100, cigarette=220, lighter=50, match=8}; device truth = {bidi=100, cigarette=220, lighter=50, match=8} | PASS |
| ADMIN sales cards: base_unit per category | units = {bidi=stick, cigarette=stick, lighter=piece, match=dozen}; expected = {bidi=stick, cigarette=stick, lighter=piece, match=dozen} | PASS |
| ADMIN sales card detail | cigarette target/day 24003.600 remaining 23783.600 achv_bp 92 routes 3; bidi target/day 3600.960 remaining 3500.960 achv_bp 278 routes 3; lighter target/day 60.000 remaining 10.000 achv_bp 8333 routes 3; match target/day 36.000 remaining 28.000 achv_bp 2222 routes 3; working_days 25; till_time true | info |
| ADMIN CPR target outlets / successful calls | dashboard = 41/4; device truth = 41/4 | PASS |
| ADMIN CPR percent (basis points) | 976 | info |
| ADMIN channel calls total | dashboard = 4; successful calls = 4 | PASS |
| ADMIN channel calls | GT=4, DCC=0, Astha=0, RCC=0, MT=0, HoReCa=0 | info |
| ADMIN channel STD total = cigarette sticks | dashboard = 220; cigarette card = 220 | PASS |
| ADMIN channel STD by channel (stick) | GT=220, DCC=0, Astha=0, RCC=0, MT=0, HoReCa=0 | info |
| ADMIN login/submit | target 2, login 2 (10000 bp), submitted 0 | info |
| ADMIN successful login = routes whose bundle was downloaded | dashboard = 2; device truth = 2 | PASS |
| ADMIN submitted routes (nobody has submitted) | dashboard = 0; expected = 0 | PASS |
| ADMIN final submit | zones 3, service zones 2, submitted 0, remaining 2 | info |
| ADMIN geo-fencing total calls / validated | dashboard = 6/6; device truth = 6/6 | PASS |
| ADMIN FF Geo pins | 2 pins, counts {"red":0,"amber":0,"green":2}, routes without position 0 | info |
| ADMIN freshness | {"date":"2026-10-24","till_time":true,"as_of":"2026-10-24T10:30:00Z","last_updated_at":"2026-10-24T10:30:00Z","tables":[{"table":"agg_daily_route_sku","last_updated_at":"2026-10-24T10:30:00Z"},{"table":"agg_daily_route_variant","last_updated_at":"2026-10-24T10:30:00Z"},{"table":"agg_daily_outlet_sku | info |
| ADMIN filter-options counts | date="2026-10-24", till_time=true, as_of="2026-10-24T10:30:00Z", last_updated_at=null, wings=2, divisions=2, territories=2, houses=3, zones=4, categories=4, segments=6, brands=17, variants=30 | info |
| NATIONAL web token claim names | [amr, aud, exp, flv, iat, iss, jti, nbf, perm, pii, role, sub, sv, uname] | info |
| NATIONAL sales cards: total_sales per category (base units) | dashboard = {bidi=100, cigarette=220, lighter=50, match=8}; device truth = {bidi=100, cigarette=220, lighter=50, match=8} | PASS |
| NATIONAL sales cards: base_unit per category | units = {bidi=stick, cigarette=stick, lighter=piece, match=dozen}; expected = {bidi=stick, cigarette=stick, lighter=piece, match=dozen} | PASS |
| NATIONAL sales card detail | cigarette target/day 24003.600 remaining 23783.600 achv_bp 92 routes 3; bidi target/day 3600.960 remaining 3500.960 achv_bp 278 routes 3; lighter target/day 60.000 remaining 10.000 achv_bp 8333 routes 3; match target/day 36.000 remaining 28.000 achv_bp 2222 routes 3; working_days 25; till_time true | info |
| NATIONAL CPR target outlets / successful calls | dashboard = 41/4; device truth = 41/4 | PASS |
| NATIONAL CPR percent (basis points) | 976 | info |
| NATIONAL channel calls total | dashboard = 4; successful calls = 4 | PASS |
| NATIONAL channel calls | GT=4, DCC=0, Astha=0, RCC=0, MT=0, HoReCa=0 | info |
| NATIONAL channel STD total = cigarette sticks | dashboard = 220; cigarette card = 220 | PASS |
| NATIONAL channel STD by channel (stick) | GT=220, DCC=0, Astha=0, RCC=0, MT=0, HoReCa=0 | info |
| NATIONAL login/submit | target 2, login 2 (10000 bp), submitted 0 | info |
| NATIONAL successful login = routes whose bundle was downloaded | dashboard = 2; device truth = 2 | PASS |
| NATIONAL submitted routes (nobody has submitted) | dashboard = 0; expected = 0 | PASS |
| NATIONAL final submit | zones 3, service zones 2, submitted 0, remaining 2 | info |
| NATIONAL geo-fencing total calls / validated | dashboard = 6/6; device truth = 6/6 | PASS |
| NATIONAL FF Geo pins | 2 pins, counts {"red":0,"amber":0,"green":2}, routes without position 0 | info |
| NATIONAL freshness | {"date":"2026-10-24","till_time":true,"as_of":"2026-10-24T10:30:00Z","last_updated_at":"2026-10-24T10:30:00Z","tables":[{"table":"agg_daily_route_sku","last_updated_at":"2026-10-24T10:30:00Z"},{"table":"agg_daily_route_variant","last_updated_at":"2026-10-24T10:30:00Z"},{"table":"agg_daily_outlet_sku | info |
| NATIONAL filter-options counts | date="2026-10-24", till_time=true, as_of="2026-10-24T10:30:00Z", last_updated_at=null, wings=2, divisions=2, territories=2, houses=3, zones=4, categories=4, segments=6, brands=17, variants=30 | info |
| WM web token claim names | [amr, aud, exp, flv, iat, iss, jti, nbf, perm, pii, role, sub, sv, uname] | info |
| WM sales cards: total_sales per category (base units) | dashboard = {bidi=75, cigarette=180, lighter=50, match=5}; device truth = {bidi=75, cigarette=180, lighter=50, match=5} | PASS |
| WM sales cards: base_unit per category | units = {bidi=stick, cigarette=stick, lighter=piece, match=dozen}; expected = {bidi=stick, cigarette=stick, lighter=piece, match=dozen} | PASS |
| WM sales card detail | cigarette target/day 24003.600 remaining 23823.600 achv_bp 75 routes 3; bidi target/day 3600.960 remaining 3525.960 achv_bp 208 routes 3; lighter target/day 60.000 remaining 10.000 achv_bp 8333 routes 3; match target/day 36.000 remaining 31.000 achv_bp 1389 routes 3; working_days 25; till_time true | info |
| WM CPR target outlets / successful calls | dashboard = 21/2; device truth = 21/2 | PASS |
| WM CPR percent (basis points) | 952 | info |
| WM channel calls total | dashboard = 2; successful calls = 2 | PASS |
| WM channel calls | GT=2, DCC=0, Astha=0, RCC=0, MT=0, HoReCa=0 | info |
| WM channel STD total = cigarette sticks | dashboard = 180; cigarette card = 180 | PASS |
| WM channel STD by channel (stick) | GT=180, DCC=0, Astha=0, RCC=0, MT=0, HoReCa=0 | info |
| WM login/submit | target 1, login 1 (10000 bp), submitted 0 | info |
| WM successful login = routes whose bundle was downloaded | dashboard = 1; device truth = 1 | PASS |
| WM submitted routes (nobody has submitted) | dashboard = 0; expected = 0 | PASS |
| WM final submit | zones 2, service zones 1, submitted 0, remaining 1 | info |
| WM geo-fencing total calls / validated | dashboard = 4/4; device truth = 4/4 | PASS |
| WM FF Geo pins | 1 pins, counts {"red":0,"amber":0,"green":1}, routes without position 0 | info |
| WM freshness | {"date":"2026-10-24","till_time":true,"as_of":"2026-10-24T10:30:00Z","last_updated_at":"2026-10-24T10:30:00Z","tables":[{"table":"agg_daily_route_sku","last_updated_at":"2026-10-24T10:30:00Z"},{"table":"agg_daily_route_variant","last_updated_at":"2026-10-24T10:30:00Z"},{"table":"agg_daily_outlet_sku | info |
| WM filter-options counts | date="2026-10-24", till_time=true, as_of="2026-10-24T10:30:00Z", last_updated_at=null, wings=1, divisions=1, territories=1, houses=2, zones=3, categories=4, segments=6, brands=17, variants=30 | info |
| DMO web token claim names | [amr, aud, exp, flv, iat, iss, jti, nbf, perm, pii, role, sub, sv, uname] | info |
| DMO sales cards: total_sales per category (base units) | dashboard = {bidi=75, cigarette=180, lighter=50, match=5}; device truth = {bidi=75, cigarette=180, lighter=50, match=5} | PASS |
| DMO sales cards: base_unit per category | units = {bidi=stick, cigarette=stick, lighter=piece, match=dozen}; expected = {bidi=stick, cigarette=stick, lighter=piece, match=dozen} | PASS |
| DMO sales card detail | cigarette target/day 24003.600 remaining 23823.600 achv_bp 75 routes 3; bidi target/day 3600.960 remaining 3525.960 achv_bp 208 routes 3; lighter target/day 60.000 remaining 10.000 achv_bp 8333 routes 3; match target/day 36.000 remaining 31.000 achv_bp 1389 routes 3; working_days 25; till_time true | info |
| DMO CPR target outlets / successful calls | dashboard = 21/2; device truth = 21/2 | PASS |
| DMO CPR percent (basis points) | 952 | info |
| DMO channel calls total | dashboard = 2; successful calls = 2 | PASS |
| DMO channel calls | GT=2, DCC=0, Astha=0, RCC=0, MT=0, HoReCa=0 | info |
| DMO channel STD total = cigarette sticks | dashboard = 180; cigarette card = 180 | PASS |
| DMO channel STD by channel (stick) | GT=180, DCC=0, Astha=0, RCC=0, MT=0, HoReCa=0 | info |
| DMO login/submit | target 1, login 1 (10000 bp), submitted 0 | info |
| DMO successful login = routes whose bundle was downloaded | dashboard = 1; device truth = 1 | PASS |
| DMO submitted routes (nobody has submitted) | dashboard = 0; expected = 0 | PASS |
| DMO final submit | zones 2, service zones 1, submitted 0, remaining 1 | info |
| DMO geo-fencing total calls / validated | dashboard = 4/4; device truth = 4/4 | PASS |
| DMO FF Geo pins | 1 pins, counts {"red":0,"amber":0,"green":1}, routes without position 0 | info |
| DMO freshness | {"date":"2026-10-24","till_time":true,"as_of":"2026-10-24T10:30:00Z","last_updated_at":"2026-10-24T10:30:00Z","tables":[{"table":"agg_daily_route_sku","last_updated_at":"2026-10-24T10:30:00Z"},{"table":"agg_daily_route_variant","last_updated_at":"2026-10-24T10:30:00Z"},{"table":"agg_daily_outlet_sku | info |
| DMO filter-options counts | date="2026-10-24", till_time=true, as_of="2026-10-24T10:30:00Z", last_updated_at=null, wings=1, divisions=1, territories=1, houses=2, zones=3, categories=4, segments=6, brands=17, variants=30 | info |
| TSO web token claim names | [amr, aud, exp, flv, iat, iss, jti, nbf, perm, pii, role, sub, sv, uname] | info |
| TSO web token carries the scope claims of docs/34 s1 (geo) | claims geo/brands/modules = false; expected = true | open finding (see the request) |
| TSO sales cards: total_sales per category (base units) | dashboard = {bidi=75, cigarette=180, lighter=50, match=5}; device truth = {bidi=75, cigarette=180, lighter=50, match=5} | PASS |
| TSO sales cards: base_unit per category | units = {bidi=stick, cigarette=stick, lighter=piece, match=dozen}; expected = {bidi=stick, cigarette=stick, lighter=piece, match=dozen} | PASS |
| TSO sales card detail | cigarette target/day 24003.600 remaining 23823.600 achv_bp 75 routes 3; bidi target/day 3600.960 remaining 3525.960 achv_bp 208 routes 3; lighter target/day 60.000 remaining 10.000 achv_bp 8333 routes 3; match target/day 36.000 remaining 31.000 achv_bp 1389 routes 3; working_days 25; till_time true | info |
| TSO CPR target outlets / successful calls | dashboard = 21/2; device truth = 21/2 | PASS |
| TSO CPR percent (basis points) | 952 | info |
| TSO channel calls total | dashboard = 2; successful calls = 2 | PASS |
| TSO channel calls | GT=2, DCC=0, Astha=0, RCC=0, MT=0, HoReCa=0 | info |
| TSO channel STD total = cigarette sticks | dashboard = 180; cigarette card = 180 | PASS |
| TSO channel STD by channel (stick) | GT=180, DCC=0, Astha=0, RCC=0, MT=0, HoReCa=0 | info |
| TSO login/submit | target 1, login 1 (10000 bp), submitted 0 | info |
| TSO successful login = routes whose bundle was downloaded | dashboard = 1; device truth = 1 | PASS |
| TSO submitted routes (nobody has submitted) | dashboard = 0; expected = 0 | PASS |
| TSO final submit | zones 2, service zones 1, submitted 0, remaining 1 | info |
| TSO geo-fencing total calls / validated | dashboard = 4/4; device truth = 4/4 | PASS |
| TSO FF Geo pins | 1 pins, counts {"red":0,"amber":0,"green":1}, routes without position 0 | info |
| TSO freshness | {"date":"2026-10-24","till_time":true,"as_of":"2026-10-24T10:30:00Z","last_updated_at":"2026-10-24T10:30:00Z","tables":[{"table":"agg_daily_route_sku","last_updated_at":"2026-10-24T10:30:00Z"},{"table":"agg_daily_route_variant","last_updated_at":"2026-10-24T10:30:00Z"},{"table":"agg_daily_outlet_sku | info |
| TSO filter-options counts | date="2026-10-24", till_time=true, as_of="2026-10-24T10:30:00Z", last_updated_at=null, wings=1, divisions=1, territories=1, houses=2, zones=3, categories=4, segments=6, brands=17, variants=30 | info |

### 9 Scope rules (server side): widening filters, wrong-level callers, BRAND, field roles

| Hop | Values | Result |
|---|---|---|
| WM asks for the other division's ids (cut to the reach, or refused) | answer (status, units sold) = 200 0; ok = 200 0 | PASS |
| DMO asks for the other division's ids (cut to the reach, or refused) | answer (status, units sold) = 200 0; ok = 200 0 | PASS |
| TSO asks for the other division's ids (cut to the reach, or refused) | answer (status, units sold) = 200 0; ok = 200 0 | PASS |
| WM own figures equal the Gulshan day only | dashboard = {bidi=75, cigarette=180, lighter=50, match=5}; device truth = {bidi=75, cigarette=180, lighter=50, match=5} | PASS |
| WM opens the other division's route-map/7 | status = 404; expected = 404 | PASS |
| WM opens the other division's national/ff-geo/routes/7 | status = 404; expected = 404 | PASS |
| WM opens the other division's sale-position/149 | status = 404; expected = 404 | PASS |
| DMO own figures equal the Gulshan day only | dashboard = {bidi=75, cigarette=180, lighter=50, match=5}; device truth = {bidi=75, cigarette=180, lighter=50, match=5} | PASS |
| DMO opens the other division's route-map/7 | status = 404; expected = 404 | PASS |
| DMO opens the other division's national/ff-geo/routes/7 | status = 404; expected = 404 | PASS |
| DMO opens the other division's sale-position/149 | status = 404; expected = 404 | PASS |
| TSO own figures equal the Gulshan day only | dashboard = {bidi=75, cigarette=180, lighter=50, match=5}; device truth = {bidi=75, cigarette=180, lighter=50, match=5} | PASS |
| TSO opens the other division's route-map/7 | status = 404; expected = 404 | PASS |
| TSO opens the other division's national/ff-geo/routes/7 | status = 404; expected = 404 | PASS |
| TSO opens the other division's sale-position/149 | status = 404; expected = 404 | PASS |
| BRAND qabrand1001 POST national/sales | 403 ERR_FORBIDDEN | info |
| BRAND qabrand1001 POST national/cpr | 403 ERR_FORBIDDEN | info |
| BRAND qabrand1001 POST national/channel-std | 403 ERR_FORBIDDEN | info |
| BRAND qabrand1001 POST national/breakdown | 403 ERR_FORBIDDEN | info |
| BRAND user can read the product cards of its brands (docs/34 s1, level 4) | sales status = 403; expected = 200 | open finding (see the request) |
| SR app token on the dashboard is refused (401 or 403) | status = refused; expected = refused | PASS |
| NATIONAL (read-only level) cannot Final Submit | status = 403; expected = 403 | PASS |
| SR on the web login | status = 403; expected = 403 | PASS |
| SR web login answer | {"type":"urn:aron:problem:err_auth_portal_not_allowed","title":"Auth portal not allowed","status":403,"detail":"this account uses the field app only","instance":"/v1/auth/login","code":"ERR_AUTH_PORTA | info |
| anonymous | status = 401; expected = 401 | PASS |

### 10 Route map and Sale Position pages (GUL-SR-2, GUL2-001)

| Hop | Values | Result |
|---|---|---|
| route map outlets by state | {geo_validated=4, not_called=17} | info |
| route map CPR | {"target_outlets":21,"successful_calls":2,"geo_validated":4,"cpr_pct_bp":952} | info |
| route map target vs achievement | cigarette sales 180 target/day 8001.200 bp 225; bidi sales 75 target/day 1200.320 bp 625; lighter sales 50 target/day 20.000 bp 25000; match sales 5 target/day 12.000 bp 4167 | info |
| route map time card | {"first_sale_time":"2026-10-24T04:34:00Z","last_sale_time":"2026-10-24T05:14:00Z","total_sale_seconds":2400,"average_sale_seconds":1200,"distance_covered_m":195.87} | info |
| route map path points | 5 | info |
| route map: outlets with sales | map = [GUL2-001, GUL2-002]; device truth = [GUL2-001, GUL2-002] | PASS |
| route map GUL2-001 sales (category: base units) | map = {bidi=25, cigarette=80, lighter=50, match=5}; device truth = {bidi=25, cigarette=80, lighter=50, match=5} | PASS |
| sale position GUL2-001 | 200 {"date":"2026-10-24","till_time":true,"as_of":"2026-10-24T10:30:00Z","last_updated_at":"2026-10-24T10:30:00Z","zone":{"id":2,"code":"Z-GUL","name":"Gulshan"},"route":{"id":4,"code":"GUL-SR-2","name":"Gulshan 2 (Sat, Tue)"},"outlet_code":"GUL2-001","radius_m":100,"base":{"lat":23.79192,"lng":90.4079},"header_distance_m":5.4,"sales":[{"memo_uuid":"bc355488-4ee3-420d-9730-72f93782f3e1","kind":"new","lat":23.79196,"lng":90.40793,"at":"2026-10-24T04:34:00Z","distance_m":5.4,"within_fence":true,"statu | info |

### 11 Day records: day_open, attendance, stock issue, Sales Submit (day_submit), TSO Final Submit

| Hop | Values | Result |
|---|---|---|
| morning records accepted (day_open, attendance, stock issue) | statuses = [accepted]; expected = [accepted] | PASS |
| Sales Submit (day_submit) in a batch | day_submit:accepted | info |
| Sales Submit accepted | statuses = [accepted]; expected = [accepted] | PASS |
| Sales Submit sent again with a new batch_uuid | statuses = [duplicate]; expected = [duplicate] | PASS |
| route-day state after Sales Submit | day_state = sales_submitted; expected = sales_submitted | PASS |
| route-day timestamps (logged in, sales submitted, final submitted) | flags = true/true/false; expected = true/true/false | PASS |
| Final Submit preview (routes) | GUL-SR-1: logged_in, had_data false, memos 0, net 0; GUL-SR-2: sales_submitted, had_data true, memos 2, net 2487000 | info |
| Final Submit preview warnings | ["routes_not_sales_submitted"] | info |
| Final Submit preview lists the routes planned (or with data) on the day (question: is the unplanned GUL-SR-1 listing intended? F-TSO-010 says every active route) | routes = [GUL-SR-1, GUL-SR-2]; planned or with data = [GUL-SR-2] | open finding (see the request) |
| TSO Final Submit | status = 200; expected = 200 | PASS |
| Final Submit replayed with the same client_uuid | status = 200; expected = 200 | PASS |
| second Final Submit with another client_uuid | status = 409 ERR_DAY_ALREADY_FINAL_SUBMITTED; expected = 409 ERR_DAY_ALREADY_FINAL_SUBMITTED | PASS |
| route-day after Final Submit | state/final = final_submitted/true; expected = final_submitted/true | PASS |
| Login / Submit card after Sales Submit: submitted routes (national) | dashboard = 1; device truth = 1 | PASS |
| Final Submit card: zones final submitted (national) | dashboard = 1; device truth = 1 | PASS |
| Final Submit card | {"date":"2026-10-24","till_time":true,"as_of":"2026-10-24T10:30:00Z","last_updated_at":"2026-10-24T10:30:00Z","total_zones":3,"service_zones":2,"final_submitted":1,"remaining":1,"submit_pct_bp":5000} | info |
| Final Submit card for the TSO: final submitted / remaining | dashboard = 1/0; device truth = 1/0 | PASS |
| dw.v_final_submit_log rows | zone_id=2,business_date=2026-10-24,first_submitted_at=2026-10-24 10:30:00.0,first_submitted_by=6,first_via=web,submits=1,last_submitted_at=2026-10-24 10:30:00.0,reopened_now=false | info |
| sales cards unchanged by attendance, stock and submit records | dashboard = {bidi=100, cigarette=220, lighter=50, match=8}; device truth = {bidi=100, cigarette=220, lighter=50, match=8} | PASS |
| stock: issued base units by sku in the totals | server = {1=110, 2=260, 32=200, 39=175, 41=10}; device = {1=110, 2=260, 32=200, 39=175, 41=10} | PASS |

### 12 Late sale after Final Submit (accept and flag, never reopens, counted once)

| Hop | Values | Result |
|---|---|---|
| late sale statuses | statuses = [accepted]; expected = [accepted] | PASS |
| late sale again | statuses = [duplicate]; expected = [duplicate] | PASS |
| route-day still final_submitted | state = final_submitted; expected = final_submitted | PASS |
| active memos including the late one | dw = 3; device = 3 | PASS |
| gross mtk including the late one | dw = 2763000; device = 2763000 | PASS |
| late rows after final (flag) | 4 | info |
| late rows flagged | late_rows_after_final > 0 = true; expected = true | PASS |
| national sales cards include the late sale once | dashboard = {bidi=100, cigarette=250, lighter=50, match=8}; device truth = {bidi=100, cigarette=250, lighter=50, match=8} | PASS |

### 13 Reports that exist (GET /v1/reports, POST /v1/reports/{key}/query), SUPERADMIN, the Saturday

| Hop | Values | Result |
|---|---|---|
| reports registered for SUPERADMIN | 32 | info |
| report amo-call (columns_known=true) | HTTP 200, rows 0, totals null | info |
| by-outlet separates units (sold_qty_base would add sticks, pieces and dozens) | unit or category column = false; expected = true | open finding (see the request) |
| report by-outlet (columns_known=true) | HTTP 200, rows 7, totals {"memos":5,"sold_qty_base":408,"net_mtk":3237000,"due_mtk":578400,"dues_collected_mtk":2000} | info |
| report by-outlet-by-day (columns_known=true) | HTTP 200, rows 7, totals {"memos":5,"sold_qty_base":408,"net_mtk":3237000,"due_mtk":578400,"dues_collected_mtk":2000} | info |
| report by-route-geo-capture (columns_known=true) | HTTP 200, rows 9, totals {"outlets":183,"master_location":183,"provisional_location":0,"placeholder_location":0,"no_location":0,"confirmed":183} | info |
| report daily-tracking (columns_known=true) | HTTP 200, rows 2, totals null | info |
| report data-entry-log (columns_known=true) | HTTP 200, rows 2, totals {"uploads":11,"records":115} | info |
| report discount (columns_known=true) | HTTP 200, rows 0, totals {"qty_base":0,"value_mtk":0} | info |
| report ds-rrs (columns_known=true) | HTTP 200, rows 8, totals {"issued_qty_base":755,"sold_qty_base":408,"free_qty_base":0,"returned_qty_base":0,"closing_qty_base":347} | info |
| report dss (columns_known=true) | HTTP 200, rows 3, totals {"target_routes":2,"logged_in_routes":2,"sales_submitted_routes":1,"visits":7,"successful_calls":5,"memos":5,"gross_mtk":3237000,"net_mtk":3237000,"dues_collect | info |
| report final-submit-log (columns_known=true) | HTTP 200, rows 2, totals {"late_rows_after_final":4} | info |
| report final-submit-status (columns_known=true) | HTTP 200, rows 2, totals {"target_routes":2,"not_started":0,"in_progress":1,"sales_submitted":0,"final_submitted":1} | info |
| report free-sample (columns_known=true) | HTTP 200, rows 0, totals {"qty_base":0} | info |
| report geofence-calibration (columns_known=true) | HTTP 200, rows 2, totals null | info |
| report gigo (columns_known=true) | HTTP 200, rows 1, totals null | info |
| report leaderboard (columns_known=true) | HTTP 200, rows 2, totals null | info |
| report memo-number-gaps (columns_known=true) | HTTP 200, rows 0, totals null | info |
| report online-offline (columns_known=true) | HTTP 200, rows 2, totals {"online_memos":0,"offline_memos":5,"manual_memos":0} | info |
| report qc-report (columns_known=true) | HTTP 200, rows 0, totals {"qty_base":0,"settlement_mtk":0} | info |
| report retailer-list (columns_known=true) | HTTP 200, rows 183, totals null | info |
| report route-bsr-cpr (columns_known=true) | HTTP 200, rows 7, totals null | info |
| report route-memo (columns_known=true) | HTTP 200, rows 6, totals {"memos":5,"gross_mtk":3237000,"net_mtk":3237000,"paid_mtk":2658600,"due_mtk":578400} | info |
| report route-qc (columns_known=true) | HTTP 200, rows 0, totals {"lines":0,"qty_base":0,"settlement_mtk":0} | info |
| report route-std (columns_known=true) | HTTP 200, rows 8, totals {"sold_qty_base":408,"free_qty_base":0,"gross_mtk":3237000} | info |
| report sales-summary (columns_known=true) | HTTP 200, rows 11, totals null | info |
| report settlement (columns_known=true) | HTTP 200, rows 0, totals {"lines":0,"qty_base":0,"settlement_mtk":0} | info |
| report sku-list (columns_known=true) | HTTP 200, rows 42, totals null | info |
| report sr-efficiency (columns_known=true) | HTTP 200, rows 6, totals {"target_outlets":41,"visited_outlets":7,"successful_calls":5,"memos":5,"net_mtk":3237000} | info |
| report sr-outlets (columns_known=true) | HTTP 200, rows 183, totals {"outstanding_mtk":576400} | info |
| report std-memo (columns_known=true) | HTTP 200, rows 5, totals {"gross_mtk":3237000,"offer_discount_mtk":0,"drp_discount_mtk":0,"qc_deduction_mtk":0,"net_mtk":3237000,"paid_mtk":2658600,"due_mtk":578400} | info |
| report suspicious-location (columns_known=true) | HTTP 200, rows 2, totals null | info |
| report task-planner (columns_known=true) | HTTP 200, rows 0, totals null | info |
| report tso-top-sheet (columns_known=true) | HTTP 200, rows 2, totals {"target_outlets":41,"visited_outlets":7,"successful_calls":5,"memos":5,"net_mtk":3237000} | info |

### 14 Memo number rule: the server rejects a number that does not match user, date and block (memo_no_invalid, docs/24 s7.5)

| Hop | Values | Result |
|---|---|---|
| memo_no "sr1001-261024-050" (valid in the phone's block) | accepted  | info |
| a number in the phone's block is accepted | status = accepted; expected = accepted | PASS |
| memo_no "lkxsr1001-261024-051" (another user's name) | accepted  | info |
| memo_no another user's name is refused as memo_no_invalid | answer = accepted; expected = rejected memo_no_invalid | open finding (see the request) |
| memo_no "sr1001-261023-052" (another business date) | accepted  | info |
| memo_no another business date is refused as memo_no_invalid | answer = accepted; expected = rejected memo_no_invalid | open finding (see the request) |
| memo_no "sr1001-261024-3000" (sequence outside every block of the user) | accepted  | info |
| memo_no sequence outside every block of the user is refused as memo_no_invalid | answer = accepted; expected = rejected memo_no_invalid | open finding (see the request) |
| memo_no "MEMO-1" (not the format) | rejected schema_invalid | info |
| memo_no not the format is rejected | status = rejected; expected = rejected | PASS |

### 15 Edges: changed payload, same content under a new uuid, same memo_no under a new uuid, wrong pack size

| Hop | Values | Result |
|---|---|---|
| edge base sale accepted | memo = accepted; expected = accepted | PASS |
| same client_uuid, changed payload | quarantined payload_conflict | info |
| a changed payload under a stored client_uuid never overwrites the memo | stored paid_mtk = 184000; original paid_mtk = 184000 | PASS |
| same outlet, lines and minute under new uuids | quarantined content_duplicate | info |
| a second sale with the same content is held, not stored twice | memos at GUL2-011 = 1; expected = 1 | PASS |
| same memo_no under a new client_uuid | quarantined memo_no_duplicate | info |
| the same memo_no is never stored twice | rows with memo_no 060 = 1; expected = 1 | PASS |
| 2 packets of a 20s SKU sent with pack_factor 10 | memo accepted; line accepted | info |
| a pack_factor that differs from the SKU's base_per_pack is refused (docs/34 s4.2: re-checked on the server) | line = accepted; expected = rejected qty_invalid | open finding (see the request) |
