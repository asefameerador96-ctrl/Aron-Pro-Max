# Lane sr-server (SV, then SV2): SR manual build, server side

Branch `lane/sr-server`. Brief: `docs/requests/sr-manual-lane-brief.md` (lead docs branch). Request file: `docs/requests/contract-1.14-sr-manual.md`. Records: D-SV1-01 to 13 (DECISIONS.md). A "checked" claim is made only in the report to the lead, for a named sha.

## State (SV2, 2026-11-12)

- **Base:** INT 4739d5d1a (train 107: Loyalty routes, contract 1.13.1, V0170 to V0172) is merged. Contract 1.14.0 on top of 1.13.1; my migrations V0173, V0174, V0175; dw view contract 1.8.
- **Gates (SV2, on fbbc71d2f, the merged head):** all green. Python gates (slice-contract, money-float, date-pin, tools/ci unittest 104, migrations-check + squawk, contract-breaking + oasdiff, Redocly) and Gradle one module at a time: contract 115, db 364, platform 99, sync 39, masterdata 285, config 75, media 17, analytics 359, app 568 (2 pre-existing skips: GeoRecheckCheckerTest, MoneyIngestOracleTest); 0 failures. Not run: Android modules (untouched), a three-times-green repeat. The lead's Opus review of fe023e1d2 (FAIL) is answered by D-SV1-13.
- **Train 1 items:** (a) zero-sale reason, (b) call photo, (c) digest business totals, (d) outlet requests, (f) edit reasons / task type / repeat_sale status, (g) update notes and TSO device OTPs: done; the review fixes of D-SV1-13 changed (a), (b), (c), (d), (g).
- **Train 1 not started (train 2):** (e) clusters section and cluster-creation record, (h) `outlet_sku_stats`, `posm-survey`, late-row flag, journey classifier.

## Review fixes (D-SV1-13): where they are

1. Unknown or retired optional labels are stored and flagged (`app.visit.reason_code_unlisted`), never rejected: `IngestService` 5c removed, `RecordWriter.closeVisit`, V0173 column + write-once guard.
2. `info` request with a gone or other-zone cluster: kept, cluster dropped (`RecordHandler.rewrite`), flag event `flag_cluster_dropped`. Look-alike new shop: stored, `flag_possible_duplicate`; `sameAs` removed. Events are server events (`via = job`); `OutletRequest.flags` and `possible_duplicate_of` read them.
3. Device OTP reads: `Cache-Control: no-store`; paging by raw rows; tests for another territory and for consumed / expired / other-day codes.
4. `cfg.sale.call_photo_required` ships false.
5. Records: contract changelog, request file, seed, dictionary, comments, this file.
6. Call-photo review: media joined by the visit's own user; reach = zones or routes like `MediaRead`; row member `reason_code_unlisted`. Digest: `call_declined` is not a visit made; contract text states exactly what is counted and does not claim the phone's rules.

## Migrations used

V0173 (visit columns incl. `reason_code_unlisted`, guards, `outlet_request_event.event` flag kinds, code lists, `cfg.sale.call_photo_required` = false), V0174 (VALIDATE), V0175 (dw view contract 1.8: `fact_visit.zero_sale_reason_code`, `call_photo_uuid`; `v_visit.zero_sale_reason_code`, `call_capture_taken`). History: V0163 to V0165, then V0172 to V0174, renamed at the lead's orders because Flyway here has no outOfOrder.

## Open questions

1. Task type `poor_brand_visibility` has no Bangla label (part B owner question 4).
2. `reason_code_unlisted` is not in the dw views yet (train 2 may add it with the next dw contract number).


## Train 2 handover (for a fresh session; everything below is NOT started unless it says otherwise)

Rules: same brief; contract stays 1.14.0 (additive, same request file; D-LEAD-CONTRACT-114-01 is requested, the lead approves it); migrations continue after V0175 (V0170 to V0172 are the Loyalty lane's train 2; the lead gives the next free number); dw view contract continues at 1.9 (1.8 is V0175). Every write idempotent by client UUID with duplicate / out-of-order property tests; supervisor reads stay under server-side scope with tests (the two new reads, `getTeamDeviceOtps` and `getCallPhotoReview`, have role and reach tests in `SrManualSv1ApiTest`; add a second-zone user to prove the negative case when a second TSO exists in the seed).

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
- State history (superseded by the State section above): at c03e44625: Python gates, squawk, oasdiff, Redocly and the 10 `SrManualSv1*` tests were green; the full module run has NOT completed since the last fixes (scope registry, `reports` module mapping for `call-photos`, task-type and code-list-count expectations, renumbering): `:backend:platform:test` and `:shared:contract:jvmTest` were green after them; db, sync, masterdata, config, media, analytics and app were not re-run to the end. Do not report "checked" until they are.
