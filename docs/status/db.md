# db lane status

Updated with every push. Rows of Day 1: N-005, N-006, N-007, N-008 (`python3 tools/my-rows.py db 1 --full`).

## Done

- **N-005 schema v1a** (`db/migrations/V0001`–`V0006`): schemas `app`, `dw`, `stg`; helpers `app.dhaka_date`,
  `app.div_half_up`, `app.touch_master`, `app.deny_mutation`, monthly partition maintenance
  (`app.partition_policy`, `app.ensure_partitions`); geography (wing, division, territory, house, zone, cluster),
  routes (`visit_kind` daily/3f/2f agreeing with `visit_days_mask`), `route_planned`, users (case-insensitive
  username), `user_scope`, `route_assignment` (one primary per route-day by exclusion), credentials and session
  tables, product tree, SKUs, five price types (`sku_price`, no overlaps), sales plans, offers as data, outlets with
  placement and location history, config registry (172 keys of docs/24 s9.5), scoped values, changes, versions,
  calendar, code lists, targets. Tests: `MigrationApplyTest` (empty DB, second run no-op, edited and removed
  migrations refused), `SchemaV1aTest` (16 rules). Checker: two rounds, all confirmed defects fixed.

- **N-006 schema v1b** (`V0007`–`V0009`): every one of the 42 record types of contract v1.1 has its table with the
  shared envelope (client_uuid, family_uuid, business_date + business_date_device, UTC captured_at, clock evidence,
  config/bundle versions, voided_at); partitioned visit/memo/memo_line/geo_fix/domain_event (unique keys include
  business_date, plus `app.client_uuid_once`); immutability guard with write-once columns and state-flow triggers;
  memo arithmetic and line gross as CHECKs; route-day, supervisor-day, QC header, due ledger, indent ledger (Phase 2),
  final submit, submit void, data-void barrier, request trail; programmes, enrolments, gifts, gift assignments, Astha
  targets, loyalty ledger (idempotent on source), content items, risk signals; hash-partitioned ingest registry,
  batch replay, rejected, quarantine, server generation, domain-event outbox; V0009 adds the v1.1 code lists and 54
  config keys. Checker: three rounds, PASS.
- **N-007 schema v1c** (`V0010`, `V0011`): devices (package = flavour), bindings (ordinals 0..3), nonces, status
  reports, rendered policy, app package catalogue for the block list, directives, push tokens, releases
  (maker-checker), enrolment tokens (hash only), device FKs, append-only hash-chained audit log (`chain_seq` under a
  lock, `app.audit_verify()`); dw dimensions (dim_date 2026–2030), partitioned facts, route/route-SKU/route-brand/
  zone/hourly/outlet aggregates; `app.dirty_key` with `app.mark_dirty`. Checker: two rounds, PASS.
- **N-008 seed** (`db/seed/`, `:db:seed`): `sr1001` primary on Daily (127), 3F Sun/Tue/Thu (42), 2F Mon/Thu (36)
  with 60 outlets near Mirpur 10; TSO territory and AMO zone scope; 42 SKUs x 5 prices from the CSV; test accounts
  (password hash only from `ARON_SEED_PASSWORD`); dev phone bound with ordinal 0; dev config overrides in their own
  version; idempotent. Answers `docs/requests/backend-seed-login-accounts.md`. Checker: two rounds, PASS.

- **R5 follow-up** (`V0012`, 2026-10-06): defaults of `cfg.web.menu_by_role` (docs/19 s5.3 mapped to the s8.5 roles,
  deferred programme pages left out) and `cfg.app.home_tiles` (SR: the app's tile order without Loyalty Point, Photo
  Capture and Astha). Test: `ConfigDefaultsTest`.

- **V0013** (2026-10-06): `refresh_family.device_uuid`; phone families must be bound or revoked (answers
  `docs/requests/backend-refresh-family-device-uuid.md`). Checker PASS.
- **Data as a product, docs/31 s3** (2026-10-07; one Opus checker, three rounds: 2 blockers + 6 should-fix, then 1
  blocker, then PASS):
  - `V0014` roles `api_rw`, `worker_rw`, `jobs_rw`, `web_ro`, `bi_reader` (NOLOGIN, NOINHERIT, no special
    attributes), grants generated from `app.db_role_grant` (table and column grants) by `app.apply_db_role_grants()`;
    existing roles are reused without ADMIN when correct, repaired with ADMIN, refused when superuser, BYPASSRLS or a
    member of anything else; global default privileges keep new functions from PUBLIC. `DbRolesTest` (7 tests:
    matrix both ways, each role under SET ROLE, second migrating login, repair path). Infra asked for the logins:
    `docs/requests/db-runtime-roles.md`.
  - `V0015` stable views `dw.v_daily_route`, `v_daily_sr`, `v_daily_outlet`, `v_daily_sku`, `v_collections`,
    `v_attendance`, `v_geo_integrity` (+ `dw.fact_attendance`); every column documented; `business_date` filters
    prune partitions (UNION ALL under one GROUP BY, not FULL JOIN); no coordinates or fix accuracy to web/BI.
  - `V0016` + `tools/data-dictionary/render.sh` + `DataDictionaryTest`: every app/dw table, view and column carries
    `owner | capture | retention | pii`; `docs/data-dictionary.md` (142 relations) is rendered from the catalogue and
    the test fails on a missing comment, a stale file or a table PII class below its columns. CI: already enforced by
    `:db:build`; `docs/requests/db-data-dictionary-ci.md` asks infra to keep it required.
  - `V0017` versioned domain events: `domain_event.payload_version` + catalogue `app.domain_event_type` (7 v1 events
    of docs/24 s12.5, JSON Schema each, published versions fixed); insert trigger refuses an uncatalogued type or
    version, a non-object payload or a missing required key. `docs/data-events.md` rendered by `DataEventsTest`.

- **Enterprise-bar audit rows** (2026-10-07; Opus checker per migration):
  - `V0020` (AUD-DA-03, TP-7, REL-03): roles `auth_rw`, `pii_reader` (outlet contact columns), `support_ro`; `web_ro`
    and `bi_reader` read only `dw.v_*` (+ `dw.v_outlet_masked`); `app.db_role_limit` + `app.apply_login_limits()` write
    the docs/18 timeouts onto login identities; tests with real logins (SHOW, DROP refused, 57014 cancel). Checker PASS.
    Infra and backend asks: `docs/requests/db-runtime-roles.md` (update section).
  - `V0021` (AUD-PERF-01): set-based client_uuid uniqueness. Measured on the docs/22-volume database (7 trading days:
    726k outlets, 2.0 M visits, 1.7 M memos, 2.8 M lines, 25 monthly partitions), 200-row batches of visit + memo +
    2 lines each: **66-79 ms p95 with V0007's per-row trigger, 8-16 ms p95 with V0021** (2.3-2.7 ms with no check);
    checker independently 37.8 -> 8.5 ms. `db/perf/generate.sql`, `db/perf/ingest_batches.sql`. Checker PASS.
  - `V0022`: `tracking_action.created` v1 catalogued (the analytics producer was refused); producer asked to drop the
    free-text note (`docs/requests/db-event-tracking-action-note.md`).
  - `V0027` AUD-DA-04 (system code lists): shipped in step with the one-clause fixture change in backend-masterdata
    (`docs/requests/db-masterdata-code-list-fixture.md`, answered).

- **Session 2 (2026-10-07, after the recycle; Opus checker per batch):**
  - CI run 254 role race: `DbRolesTest` repairs inside a rolled-back transaction; harness migrations and role changes
    under a server-wide advisory lock (`TestPostgres.RoleDdlLock`); backend FreshDb asked (`docs/requests/db-role-ddl-lock.md`).
  - `V0023` back-office tables (admin_asset, tutorial, survey/rubric + immutable versions, print_template,
    support_upload, feedback_status, price_batch with status flow and freeze) and the V0007 leave-decision fix
    (answers `backend-admin-content-tables.md`, `backend-admin-price-batch-table.md`).
  - `V0024` R17: flat `cfg.sync.reconcile_types` (default, stored and pending values), `cfg.bundle.outlet_fields`
    server-only, keys `cfg.print.confirm_after_print`, `cfg.memo.reprint_watermark`,
    `cfg.sale.require_printer_before_sale` (lead ruling in `android-print-integration.md`; SchemaV1aTest allows them).
    backend-core told: `docs/requests/db-backend-core-config-and-device-v12.md`.
  - `V0025`/`V0026` v1.2 device columns `root_hints`, `root_hints_at`, `integrity_unavailable_reason`/`_at` (NULL = unknown).
  - `V0028` restrictive directions (answers `backend-admin-restrictive-dir.md`; ConfigWorkflowTest adapted in step).

## Now (session 2, 2026-10-07)

On lane/db (green locally on db and every backend suite; Opus checker PASS per batch):
- `V0023`-`V0029` (see Done);
- `V0030`: export log, PII read budget, `cfg.pii.*` budget keys (lead ruling, TSO 5000 role value);
- `V0031`: dw device/activity/consent facts (M-123 to M-125);
- `V0032`: `bundle_snapshot`;
- `V0033`: AUD-DA-01 (outbox `tx_id` + horizon, dirty-key dead letter, deprecated versions refused, TRUNCATE guard);
- `V0034`: `cfg.support.public_key_spki`;
- `V0035`: SECURITY DEFINER functions search `pg_temp` last (`ensure_partitions` could be hijacked by a `jobs_rw` temp view).

Next:
1. `entry_unlock` (when backend-admin files it).
2. `backend-reports-db-indexes-and-events.md`: indexes (due_collection, stock_movement, day_exception, dw facts by zone),
   `dw.agg_daily_route_segment`, catalogue rows `due.collected`, `day_exception.decided`, `risk_signal.changed`.
3. AUD-DA-02, DA-05..08, PERF-03/07/08; hot-path EXPLAINs at docs/22 volume.

Query-plan candidates (from the previous session):
- `IngestService.dayStates` filters `(assigned_user_id = :u OR acting_user_id = :u)`; only assigned has an index.
- `BundleService.openMemos` joins memo by client_uuid without business_date (probes every partition); same for the
  "parent in its own table" fallback.
- Check the bundle's `outlet_change_request` EXISTS and `task (assignee_user_id, status)` indexes; add due_ledger rows
  to `db/perf/generate.sql` before EXPLAINing the dues queries.

**Traps:**
- Run Gradle one invocation at a time. Concurrent runs corrupt the test results.
- Before a full run: `rm -rf db/build/test-results`, then recreate the shared `aron_test` DB if a local migration was
  edited (Flyway checksum).
- The local `aron_test` login is not a superuser. Backend tests then hit two problems that CI does not have: the DROP
  DATABASE FORCE race with autovacuum, and `session_replication_role`. For a CI-like run, use a local superuser test
  login (`aron_su`, password set at run time, never committed).
- Always rerun the db and all backend suites AFTER merging INT and BEFORE pushing. Other lanes add outbox producers and
  fixtures that interact with db changes.
- Every new migration needs `SET lock_timeout = '5s';` and must pass squawk:
  `tools/ci/migrations-check.sh origin/<INT> <squawk>`; install squawk with `tools/ci/install-tool.sh squawk <dir>`.
  Squawk rules: no DROP FUNCTION, no DROP DEFAULT, no VALIDATE in the same transaction.
- A new event type needs a catalogue row (`app.domain_event_type`) before its producer ships.
- A new table or column needs `COMMENT ON` with the metadata line, then `tools/data-dictionary/render.sh` (or
  `-Paron.writeDictionary=true`).
- A migration that creates a table calls `SELECT app.apply_db_role_grants();`.

## Lead rulings applied (docs/24 s14a, 2026-10-06)

R1 registry hash partitioning, R2 only the s9.5 keys, R3 scope_id ordinals, R4 `_` in SKU codes: already as built.
R5: V0012. R6 / docs/27: no programme, target or discount tables or migrations will be added; the ones already shipped
(programme, programme_enrolment, gift, gift_assignment, astha_target, loyalty_ledger, redemption, redemption_line,
gift_photo, target_*, offer*) stay as empty hooks and are not edited.

## Next

- Index and query-plan review at docs/22 volume (`db/perf/generate.sql`): ingest measured (V0021); bundle, worker and
  dashboard plans in progress. Then AUD-DA-01 (outbox commit order, dirty-key dead letter), DA-02, DA-05..08, PERF-03/07/08.

- Back-office tables of docs/24 s12.1 that no db row names (`survey`, `survey_question`, `rubric`, `tutorial`,
  `print_template`, `supervisor_target`, `web_entry_*`, `qc_summary_entry`, `entry_unlock`, `dues_adjustment`,
  `price_batch`, `tracking_action`, `report_export_log`, `client_error`): added only when a docs/25 row of another lane
  needs one (`python3 tools/my-rows.py db` lists no db row after Day 1).

## Sponsor go-live checklist (db)

- [ ] AKTCL confirms the authored code lists of the code-list migration (V0027): `void_reason` (Q43), `stock_variance_reason`,
  `outlet_close_reason`, `submit_void_reason`, `edit_reason` (two more live-app reasons, MQ-18), `feedback_category`
  (Q-62), and adds codes through the admin code-list page if needed.
- [ ] AKTCL supplies Bangla labels for every code-list item whose `label_bn` is NULL
  (`SELECT list_key, code, label_en FROM app.code_list_item WHERE label_bn IS NULL AND valid_to IS NULL`).

## Not verified here

- N-005 "applies on the Azure dev database": not reachable from this container; it needs
  `docs/requests/db-azure-btree-gist.md` (infra) first.

## Requests filed

- `docs/requests/db-azure-btree-gist.md` (infra): allow-list `btree_gist`.
- `docs/requests/db-docs19-config-keys.md` (lead): seed the docs/19 keys outside s9.5?
- `docs/requests/db-config-scope-ids.md` (lead): integer `scope_id` for role and geo_class scopes.
- `docs/requests/db-sku-code-spaces.md` (lead): catalogue SKU codes contain spaces; contract pattern does not.
- `docs/requests/db-runtime-roles.md` (infra): runtime logins as members of the V0014 roles.
- `docs/requests/db-data-dictionary-ci.md` (infra): keep `:db:build` (dictionary gate) required in CI.

## Decisions taken (to be copied to DECISIONS.md by the lead; the playbook forbids lanes to edit it)

| Date | Decision | Reason |
|---|---|---|
| 2026-10-05 | Enumerations are `text` + CHECK with the contract's values, not PostgreSQL enum types | adding a value is an ordinary additive migration |
| 2026-10-05 | `cfg_value.scope_id` is 0 for global, the role ordinal for `role`, the geo_class ordinal for `geo_class` | contract `scope_id` is an integer (request filed) |
| 2026-10-05 | No `cfg_version` 0 row: no row means version 0; version 1 (by the disabled `aron.system` user) carries the s9.5 role defaults: `cfg.auth.password_min_len` 12 and `cfg.auth.access_ttl_min` 15 for DMO, WM, TOP, ANALYST, SUPPORT, ADMIN, SUPERADMIN | s9.5 states those defaults per role; ConfigVersion needs a committing user. TSO counts as a field role (8, 60 min) because it captures on the phone |
| 2026-10-05 | `cfg_key.bounds` holds only ConfigBounds members; free-text bounds (time ranges, rules) go to `cfg_key.bounds_rule`; `risk_class` is the lowest class s9.5 names, `risk_rule` keeps the escalation text | ConfigBounds is `additionalProperties: false` |
| 2026-10-05 | `route` daily requires mask 127, 3f three bits, 2f two bits | contract Route says daily = 127 |
| 2026-10-05 | Master rows: `version` always moves forward on UPDATE (trigger) | If-Match must never match a stale version |
| 2026-10-05 | Tables listed in `app.partition_policy` are never the target of a foreign key; children reference parents by `client_uuid` | re-routing default-partition rows detaches the default partition |
| 2026-10-07 | `worker_rw` gets table-level UPDATE on the worker-owned app tables (route_day, visit, media, ...); the guard triggers limit the columns | column grants would have to be kept in step with every new column; the triggers already enforce it |
| 2026-10-07 | `worker_rw` reads every app table except credentials and one-time secrets (`mfa_secret`, `device_otp`, `refresh_token`, `enrolment_token`, `app_user.password_hash`); `push_token` stays readable | the worker sends pushes |
| 2026-10-07 | Code-list migration (V0027) `day_exception_reason` codes follow docs/16 (`dh_out_of_stock`, `sick`) plus docs/19's `other`; channel/geo_class codes are lower case with the canonical value in `attrs.value` | docs/16 owns the data model; the code pattern is lower case (request to the lead: `docs/requests/db-code-list-decisions.md`) |
| 2026-10-07 | `jobs_rw` = `worker_rw` + `ensure_partitions`; no rights on `stg` or job bookkeeping yet | no job table exists; added with the first job that needs one |
| 2026-10-07 | `v_geo_integrity` covers every visit kind of the user; `v_daily_sr` counts SR calls only | integrity is about a person's fixes |
| 2026-10-07 | Domain-event catalogue enforced by a trigger, not a foreign key; `payload_version` nullable (only pre-V0017 rows) | PG16 cannot add a NOT VALID FK to a partitioned table (squawk gate); the outbox is append-only so it cannot be backfilled |
| 2026-10-07 | Event payloads carry ids, codes and amounts only, never names, phones, NIDs or coordinates | keeps the outbox pii: none; consumers read personal fields under their own grants |

## Notes for other lanes

- Backend (outbox producers): only catalogued events (`docs/data-events.md`), `payload_version` explicit (default 1);
  required keys are enforced per event once `enforce_required` is on (V0018; `docs/requests/db-event-payload-v1.md`);
  a new event or a breaking payload change is a db migration, ask through docs/requests.
- Every lane adding a table or column: `COMMENT ON` it in the same migration with the metadata line (V0016 header),
  then `tools/data-dictionary/render.sh`; a migration that creates a table calls `SELECT app.apply_db_role_grants();`.

- Backend: `app.ensure_partitions()` daily; prune `app.ingest_registry` by `received_at` after
  `cfg.retention.ingest_registry_days`; write the registry row first in the ingest transaction (it is the
  concurrency-safe uniqueness point); use `app.mark_dirty(kind, subject, date)` for bundle and aggregate refresh.
- Backend: the domain-event id is not commit order; the projector should read with a small lag window or track gaps.
- Anyone with a local database migrated before 2026-10-05 13:00 UTC: drop and recreate it (V0007 to V0011 changed
  before they were first pushed).

- Backend: run Flyway with the defaults (`validateOnMigrate` on, `cleanDisabled` true). Call
  `SELECT app.ensure_partitions()` daily from the worker and alert on `app.partition_policy.last_error`.
- Backend: master updates need no version arithmetic; `UPDATE ... WHERE id = :id AND version = :ifMatch` and the
  trigger sets `version + 1` and `updated_at`.
- Tests of the db lane need a role with CREATEDB (each test class creates and drops its own database).

| 2026-10-07 | New cfg keys `cfg.print.confirm_after_print`, `cfg.memo.reprint_watermark`, `cfg.sale.require_printer_before_sale`: scope global, delivery device, risk 1, effect B, editor `cfg.edit.field` | lead ruling (android-print-integration.md); `cfg.edit.field` as `cfg.memo.reprint_max` and `cfg.print.template_version` |
| 2026-10-07 | `restrictive_dir` set only where break-glass can compare (numbers, ordered enums); `cfg.release.blocked_version_codes` stays `none` (JSON object) | the backend comparator handles numbers and enum order only |
| 2026-10-07 | V0024 rewrites a not-yet-in-force cfg_value row in place (trigger lifted inside the migration only) | a closed stub would still be listed as scheduled by the config delta; nobody ever resolved the row |
