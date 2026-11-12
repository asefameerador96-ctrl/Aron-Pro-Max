# Lane sr-server (SV): SR manual build, server side

Branch `lane/sr-server`, from INT `claude/wonderful-thompson-k6ejnf` at ab78d0d4 (INT has since moved to ce9d92bf7 with contract 1.13.0 and V0158..V0162: NOT yet merged here). **State: train 1 merged onto INT ce9d92bf7; see "Gates" for what is green. A "checked" claim is made only in the report to the lead.** Brief: `docs/requests/sr-manual-lane-brief.md` (lead docs branch). Request file: `docs/requests/contract-1.14-sr-manual.md`. Records: D-SV1-01 to 10 (DECISIONS.md).

## State of train 1

| item | rows | state |
|---|---|---|
| (a) zero-sale reason: `VisitClosePayload.zero_sale_reason_code`, code list `zero_sale_reason` (stock_available, outlet_closed_during_visit, absence_of_owner, insufficient_cash), ingest + code-list re-check, dw | SRM-A-40 | done, tested (SrManualSv1IngestTest) |
| (b) call photo: `VisitClosePayload.call_photo_uuid` + `call_photo_skip_reason_code` (code list `call_photo_skip`), media purpose `outlet_call`, `cfg.sale.call_photo_required`, review read `getCallPhotoReview` | SRM-A-27 | done, tested |
| (c) digest `business_totals`: outlets_visited, sold / stock_issued / qc as lists of {sku_id, qty_base}, promotion_mtk | SRM-B-25 | done, tested |
| (d) outlet requests: `info` cluster_id (same zone), `route_add` keeps cluster, duplicate new-shop request acked `duplicate` | SRM-B-35, 40, 42 | done, tested incl. end-to-end approval |
| (f) edit reasons wrong_sku_qty, promotion_issue (+ wrong_quantity, wrong_price_type seeded), task type poor_brand_visibility, `JourneyCall.status` repeat_sale | SRM-B-15, 49 | done |
| (g) `UpdateCheck.notes_en/bn`; `GET /v1/supervisor/device-otps` (TSO) | SRM-A-06, 09 | done, tested |
| (e) bundle `clusters`, cluster-creation record, AMO verify, portal approval | SRM-B-33, 43, 44 | NOT started (train 2) |
| (h) `outlet_sku_stats` (`ads_hundredths`, base units, phone converts by pack_factor) | SRM-A-29 | NOT started (train 2) |
| survey report `posm-survey` + dw view; late-rows-after-Sales-Submit flag | SRM-B-08, 30 | NOT started (train 2; declared in the request file) |

## Half done / open on this branch

- The last full run (command below) found real misses, now being fixed: docs/24 s9.5 config-key registry (`cfg.sale.call_photo_required`), docs/24 Appendix A operation list (`getTeamDeviceOtps`, `getCallPhotoReview`), `AmoManualContractTest` (pins version 1.12.2 and the journey status enum), `CodeListsTest`, `DataDictionaryTest`, `AdminMasterTest` (code-list counts / dictionary regeneration). Not claimed green.
- INT ce9d92bf7 is merged (contract 1.13.0 under 1.14.0); the lead's later INT f57ce31ed (Android only) and the Loyalty train 2 (V0170, V0171, Wiring.kt, AdminModules tests, dictionary, contract description text) still have to be merged before the final gate run; expect conflicts in those files. The dw view contract is 1.8 (V0175; V0152 took 1.6, V0162 1.7).

## Migrations used

Renumbered at the lead's order (Flyway has no outOfOrder): V0163-V0165, then V0172-V0174, now V0173-V0175 because the Loyalty train 2 carries V0170, V0171 and V0172 (comment-only notes). Local rename only until the lead releases the push. V0173 (visit columns, guards, code lists, `cfg.sale.call_photo_required`), V0174 (VALIDATE), V0175 (dw contract 1.8: `fact_visit.zero_sale_reason_code`, `call_photo_uuid`; `v_visit.zero_sale_reason_code`, `call_capture_taken`). squawk clean.

## Gates run and results

- Green: `python3 tools/slice-contract.py --check`; `tools/ci/money-float-scan.py`; `tools/ci/date-pin-scan.py`; `python3 -m unittest discover` in tools/ci (104 tests); `tools/ci/migrations-check.sh <INT ab78d0d> squawk`; `tools/ci/contract-breaking.sh <INT ab78d0d> oasdiff` (9 enum-value warnings, approved by the request file); Redocly `npx @redocly/cli@2.59.0 lint contract/openapi.yaml --config contract/redocly.yaml` (0 problems); `./gradlew :backend:app:test --tests '*SrManualSv1*'` (10 tests green).
- Last full run: `ARON_TEST_PG_URL=jdbc:postgresql://localhost:5432/postgres?user=aron&password=aron ./gradlew --no-daemon -q --continue :shared:contract:jvmTest :db:test :backend:platform:test :backend:sync:test :backend:masterdata:test :backend:config:test :backend:media:test :backend:analytics:test :backend:app:test` -> NOT green (failures listed above); a first attempt was void (stale Gradle JVMs collided).
- SV2 2026-11-12 on 03065c6d8: green = Python gates, squawk, oasdiff, Redocly, :shared:contract:jvmTest, :db:test, :backend:platform/sync/masterdata/config/media/analytics, ScopeLeakAcrossEndpointsTest (7, call-photos debt line removed, cross-territory test added in SrManualSv1ApiTest), SrManualSv1*. Full :backend:app:test then green too (564 tests, 2 pre-existing skips in GeoRecheckCheckerTest and MoneyIngestOracleTest, not touched by this lane). Counts: contract 115, db 362, platform 98, sync 39, masterdata 261, config 75, media 17, analytics 359, app 564; 0 failures. Android modules not run (not touched). Waiting for Loyalty train 2 on INT before the merge and the final 'checked'.
- Not yet run: the Android modules (none touched), `ScopeLeak` / `RouteInventory` / `ContractConformance` inside `:backend:app:test` (part of the full run, results pending), three-times-green repeat.

## Open questions (none needs the owner for train 1)

1. Late-row flag and survey report: lead asked for them in this contract; shipping them in train 2 under the same version (additive). OK?
2. dw view contract number 1.7 may collide with the Loyalty lane.
3. Task type `poor_brand_visibility` has no Bangla label (part B owner question 4).


## Train 2 handover (for a fresh session; everything below is NOT started unless it says otherwise)

Rules: same brief; contract stays 1.14.0 (additive, same request file; the lead approves it as D-LEAD-CONTRACT-114-01); migrations continue after V0175 (V0170 to V0172 are the Loyalty lane's train 2; the lead gives the next free number); dw view contract continues at 1.9 (1.8 is V0175). Every write idempotent by client UUID with duplicate / out-of-order property tests; supervisor reads stay under server-side scope with tests (the two new reads, `getTeamDeviceOtps` and `getCallPhotoReview`, have role and reach tests in `SrManualSv1ApiTest`; add a second-zone user to prove the negative case when a second TSO exists in the seed).

1. **(e) Clusters.** (i) Bundle section `clusters` (cluster_id, name, route_id, route_name) scoped to the SR's routes server-side (`BundleService`, `BundleDtos`, `BundleDelta`); (ii) cluster-creation record: new record type (`cluster_request`: client_uuid, name, route_id, fix), table + `ingest_registry` / `TypeRules` / `PayloadShapes` entries, widening of the record-type CHECK (V0007 list) NOT VALID + VALIDATE, AMO verification (record or `outlet_request_verification`-like), portal approval (`POST /v1/admin/clusters` exists as `createCluster`; add the approve path), default **live after approval** (part B owner question 3), duplicate names on one route refused with a clear code; (iii) the new-shop proposal may point to a pending cluster by client uuid (`OutletProposal.cluster_request_uuid`). Lane S2 stores cluster requests locally and needs both the section and the record.
2. **(h) `outlet_sku_stats`** bundle section (versioned reference data): outlet x SKU previous-day, ADS, SDLW. State the unit in the schema description: base units (`ads_hundredths` = average daily sales in base units x 100, integer), the phone converts with `pack_factor`. Formulas = part A Q4 default (ADS = achieved / elapsed days over the last 30 days with sales, SDLW = same weekday last week). Size check: 70 outlets x 40 SKUs.
3. **`posm-survey` report key** (SRM-B-08): add to the report enum (openapi.yaml `ReportKey`), a `dw` view of `survey_response` (outlet, date, question, answer, photo flag; no media id in a reuse view: see D-SV1-02), a handler in `ReportHandlers*`, scope tests.
4. **Late-row flag** (SRM-B-30): `app.route_day.late_rows_after_sales_submit` counted in `DayStates` when a memo / due_collection with `committed_at` after `sales_submitted_at` is stored (flag, never refuse: offline-safe), expose as an optional member of the route-day status read and `dw.v_route_day`; test with two phones.
5. **Journey shared rule** (`JourneyTimeline`, S2 asks): the server journey (`getTeamJourney`) must classify `repeat_sale` (second or later sale at the same outlet on the same day) with the same rule the phone uses; the enum value is in the contract, the classifier in `TeamApi` is not changed yet.
6. **Seeds and labels:** `poor_brand_visibility` has no Bangla label (owner question 4); dev seed `04_dev_config_and_codes.sql` still carries older edit codes (harmless: ON CONFLICT).
7. **Half done / known:** nothing half done in train 1 beyond the above; Android lanes still need the `ads_hundredths` unit rule before wiring. `MediaModule` accepts `outlet_call` uploads but the retention / Wi-Fi-first rule for the new purpose is the existing photo rule (no per-purpose override).

## How to run the gates in a lane container (lessons from this session)

- PostgreSQL 16 is installed but not running after a container restart: `pg_ctlcluster 16 main start`. One-time: role `aron` (SUPERUSER CREATEDB, password aron), `ALTER SYSTEM SET max_connections = 600` (the default 100 gives `too many clients already` and floods the db module with millisecond failures), `fsync = off` for speed, restart.
- `export ARON_TEST_PG_URL="jdbc:postgresql://localhost:5432/postgres?user=aron&password=aron"` (local test database only).
- `backend:platform` `DatabaseSmokeTest` migrates the `postgres` database itself: before a run after any migration edit or rename, `drop schema app cascade; drop schema dw cascade; drop table public.flyway_schema_history cascade;` there, else Flyway reports a checksum mismatch.
- Run ONE Gradle at a time (`--no-daemon`), module by module: `:shared:contract:jvmTest`, `:db:test`, `:backend:platform:test`, `:backend:sync:test`, `:backend:masterdata:test`, `:backend:config:test`, `:backend:media:test`, `:backend:analytics:test`, `:backend:app:test` (the app module is about two hours). Two overlapping runs leave hundreds of test databases and connections and every later class fails at once. Kill leftovers with `pkill -9 -f org.gradle` and drop `aron_%` databases before a re-run.
- Python gates need `npm ci` in `web/` (for `gen:contract`) and the pinned binaries from `tools/ci/install-tool.sh oasdiff|squawk <dir>`; delete `tools/ci/__pycache__` before the backend run (`NoSecretsInRepoTest` flags the .pyc files).
- State at c03e44625: Python gates, squawk, oasdiff, Redocly and the 10 `SrManualSv1*` tests were green; the full module run has NOT completed since the last fixes (scope registry, `reports` module mapping for `call-photos`, task-type and code-list-count expectations, renumbering): `:backend:platform:test` and `:shared:contract:jvmTest` were green after them; db, sync, masterdata, config, media, analytics and app were not re-run to the end. Do not report "checked" until they are.
