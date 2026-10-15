# backend-admin lane status

Updated 2026-10-07 (evening). Local lane tests: config 48, masterdata 105, app 24, all green on the pushed state.

## Done (built, checked, pushed to INT)
- **F-API-037** config registry, scoped values, resolution, C0-C3 change workflow, versions, audit (`backend/config`). Opus checker: 9 defects, fixed.
- **F-API-040** `GET /v1/config/delta` (as-of-version resolution, ETag, 304, 410). F-API-065 flags = `cfg.flag.*` through the same path (test).
- **F-API-036 / F-TSO-019 (panel half)** device OTPs (AES-GCM, keyed verifier, reach-scoped, 10/h limit). Opus checker: 5 defects, fixed.
- **F-API-058, 059, 060, 061, 062, 063, 064, 042, 086, F-ADM-012**, update check. Sonnet checker: 6 defects + scope gaps, fixed.
- **F-API-035c, 080, F-ADM-081** products, SKUs, product tree, price preview/publish/decision with the second-approver rail. Opus checker: 5 defects, fixed (shared commit lock with config, numeric overflow, reason trim, per-batch serialisation).
- Built by sub-agents in worktrees, merged, wired, lane tests green; checkers (Sonnet) running: **F-API-035, 035b, 045** (master-data CRUD, outlet-kind bulk), **F-API-020, 020b, 022, 027, 030, 032, 035a** (visit plans, leave, tutorials, PDA upload, feedback, content).

## Built in the second session (contract v1.2 was already on INT, 57f6bd7)
- **F-API-041** `ConfigAckHandler` (config module, in `Wiring.recordHandlers`): unknown version parked `config_version_unknown`; device `config_version_applied` only moves forward; the reach view reads `cfg_ack.acked_config_version`. Test `ConfigAckHandlerTest`.
- **F-API-048 / F-ADM-058** `POST /v1/admin/data-void` (`DataVoidApi.kt`): TSO (own reach), ADMIN, SUPERADMIN. Tombstones 25 route-bearing record tables plus geo_fix by route and date, flips `ingest_registry` to `voided`, reverses dues with one `adjustment` per source record, writes `route_day_void_barrier`, audits with the reason, 409 `ERR_DAY_ALREADY_FINAL_SUBMITTED`, replay by `client_uuid`. `DataVoidBarrierHandler` (all ingest types) refuses late rows captured before the barrier as `voided_by_admin`. Web-entry tables are voided by name once they exist. Test `DataVoidAcceptanceTest` (app module).
- **N-049** `DomainEventProducer` (memo.created, memo.voided, visit.closed, stock.moved, built from the stored row, same savepoint, first store only); `RecordWriter` sets `external_ref = client_uuid` on every stored row (one line in the sync module, backend-core please keep); the data void emits `memo.voided`. `route_day.state_changed` stays with the day state machine (F-SYS-016).
- **F-SYS-013** `RiskSignals.kt`: pure `RiskRules`, `RiskSignalEvaluator.evaluateDay`, `RiskSignalJob` (worker, every 15 min, today and yesterday). Signals: GEO_MOCK, GEO_TELEPORT, GEO_ZERO_JITTER, GEO_ROUTE_SINGLE_POINT, GEO_DEVICE_SERVER_MISMATCH (reads `visit.server_verdict`, filled when backend-core ships F-SYS-012). Not built yet: PERFECT_ACCURACY, SAME_POINT, STALE_FIX, GNSS_*, SHORT_VISIT_GAPS, DEVICE_* (same shape; add to `RiskRules`). Test `RiskSignalsTest`.
- Opus checker on F-API-041/048: 7 defects, all fixed (test `DataVoidCheckerTest`): dues reversal nets per memo (no double reversal, memo_void/supersession untouched); barrier only for app scopes; barrier is `checkEarly` (new `RecordHandler.checkEarly`, called before the parent check in IngestService, so children of a late family are final `voided_by_admin`) under a shared xact advisory lock vs the void's exclusive one; TSO may void `web_entry` only (ADMIN/SUPERADMIN for app memos: docs/24 s8.5, lead may re-rule against docs/21); non-numeric config_version is `schema_invalid`; one void per client_uuid under an advisory lock. Not proven/left: collection on a later day after a void, phone-clock `captured_at` vs trusted time, rows without envelope route_id, tombstone coverage of non-route types, route-day state after void. Checker did not review N-049/F-SYS-013 (no defects reported on them; unreviewed): ask a fresh checker if the lead wants T1 rigour there.

## Second-session round 2 (2026-10-15): contract v1.3 server side and web entry
- Contract v1.3 done: CodeItem.id, TutorialAdmin.asset_id, listFeedback category_code/status filters, user scope node valid_to (exclusive end), survey/rubric/content admin reads that round-trip (option_codes, keys, asset_id, one rubric answer_type enum). Item 2 (POST /outlet-requests) already existed from backend-core. Item 5 (SKU image) waits on db: `docs/requests/backend-admin-sku-image-asset.md`.
- Location history: a row for every basis change (BA-32). F-ADM-037 outlet code rule (BA-30). F-API-049 entry unlocks, F-API-050/052 web route-day and QC entry (BA-31): Opus checker found 6 defects, all fixed with its tests (`WebEntryCheckerTest`, `EntryUnlockCheckerTest`); also a shared lock with Final Submit.
- Not built: F-API-038 (paper backfill through ingest: the server must price the memo and create its visit; large, T1). F-ADM-036 dues adjustments waits on db: `docs/requests/backend-admin-dues-adjustment-table.md` (lead approved the row, Opus checker). Unproven risks of the web-entry check are in the checker report summary: zone-scoped cfg overrides not read, class keys unchecked, overlap policy treated as exclusive_flag, unlock create not idempotent.
- Wall-clock gate: the intended real-clock reads in config and masterdata tests are annotated `wall-clock-ok`.

## HANDOVER (2026-10-15, session 2 recycling at about 610k tokens)
Lane head f5a3e647 is on INT (950ddbff). Tests: masterdata, config, app all green on it.

Done this session (all checked; see the sections above and DECISIONS BA-30..32): config_ack handler, data void (+ checker fixes), domain-event producer + external_ref, risk signals (F-SYS-013), temp-password TTL 24 h, wall-clock annotations, contract v1.3 server side (items 1, 3, 6, 7, 8; item 2 existed), location-history rows, F-ADM-037 code rule, entry unlocks, web route-day and QC entry.

Left:
- **F-ADM-036 dues adjustments** (`/v1/admin/dues-adjustments` list/create/decision): NOT started. Blocked on db: `app.dues_adjustment` does not exist yet (`docs/requests/backend-admin-dues-adjustment-table.md`; check `grep dues_adjustment db/migrations`). Lead approved the row as T1 (money, Opus checker). Shape: create pending idempotent by `client_uuid`; list by `outlet_id`/`status`, reach-scoped; decide: checker is not the maker (409 `ERR_SEPARATION_OF_DUTIES`), approval inserts `app.due_ledger` kind `adjustment` (source = client_uuid, amount signed, `write_off` negative) in the same transaction, audit with the note; roles: finance in docs/15 = ADMIN/SUPERADMIN (TSO may propose? decide from docs/24 s8.5); add lines to `backend/app/src/test/resources/scope-cases.txt`.
- **Contract v1.3 item 5 (SKU image)**: blocked on db (`docs/requests/backend-admin-sku-image-asset.md`); then `image_asset_id` on SkuWrite/SkuPatch (purpose `sku_image`) and `image_url` on Sku.
- **F-API-038 paper backfill** (`POST /v1/admin/data-entry`): T1, large. The server must price a memo from qty-only lines at the business date (price type of the outlet), create the visit (`visit_kind web_entry`) and memo through the ingest path with `source = manual`, memo-number duplicate answered `duplicate_memo_no`, window `cfg.entry.paper_backfill_window_days` (7), roles SUPPORT/ADMIN/SUPERADMIN (docs/24 s8.5). Needs an `IngestService` entry point from backend-core.
- **F-API-051** is a TRIM. **Data-void maker-checker** ruling pending (`docs/requests/backend-admin-data-void-second-person.md`).
- Unproven risks to revisit: zone-scoped `cfg.web.*` overrides not read; web-entry class keys unchecked; overlap policy treated as exclusive_flag; unlock create not idempotent; remaining RiskRules (PERFECT_ACCURACY, SAME_POINT, STALE_FIX, GNSS_*, SHORT_VISIT_GAPS, DEVICE_*).

Next three rows: (1) F-ADM-036 once the table lands; (2) SKU image once the column lands; (3) F-API-038 (agree the ingest entry point with backend-core first).

Traps: `service postgresql start` after any pause (it stops); never two Gradle builds in one worktree (EOFException); checker worktrees live in `.claude/worktrees/` (excluded locally via .git/info/exclude; do not commit); ADMIN logins need MFA in app tests (override `cfg.auth.mfa_required_roles` to `[]` as in DataVoidAcceptanceTest); admin tests use the SYSTEM clock, annotate real-clock reads `// wall-clock-ok:`; push only to lane/backend-admin after merging INT.

## Blocked, with requests filed
- F-API-049, 050, 052, 038 (web half) and the `web_entry` void scope need db tables: `docs/requests/backend-admin-web-entry-tables.md`.
- F-API-051 is a TRIM (docs/27, no Astha quantity path): nothing to build.
- F-ADM-037 needs F-API-055 (outlet requests, backend-core).
- Data-void maker-checker (docs/24 s8.6) is not in the contract: `docs/requests/backend-admin-data-void-second-person.md` (lead).
- Older requests still open: restrictive-dir, price-batch-table, content-tables (V0007 leave trigger), blob-sas, nul-in-text.

## Next rows
1. Fix the checker findings on the rows above.
2. When db lands the web-entry tables: F-API-049, 050, 052, 038, then the `web_entry` void scope.
3. The remaining `RiskRules` of docs/24 s11.4.

## Traps
- Never run two Gradle builds in one worktree at once (test-result files collide); checkers use their own worktree.
- Builders' test helpers clash by name across files (`obj`, `send`, `str`, `items`): suffixes A1/A3 were added.
- Test clocks: two changes at one scope in the same millisecond are bumped by 1 ms.

## Permission matrix mapping (ruling 7; `ConfigPermissions`, test `PermissionMappingTest`)
Stored (`cfg.web.menu_by_role`, seeded `{menu, page, actions}`) to contract (`MenuPermission {menu_id = "menu.page", actions}`):

| Stored action | Contract action(s) | Written back as |
|---|---|---|
| read, view | view | read |
| write | create, edit | (write is only read from the seed) |
| create | create | create |
| edit | edit | edit |
| approve | approve | approve |
| export | export | export |
| submit_void, void | void | void |
| filter, edit_allowed_sections, state, directive, resolve, issue, credentials, final_submit, reopen, publish, write_own, verify, reject, review, unlock, upload, download, take_action | dropped from the contract view (menu-specific verbs, no contract action) | not written by the PUT; a PUT replaces the role's whole list, so these are lost for that role (the editor shows only contract actions) |

A seeded action that is in neither list fails `PermissionMappingTest`.
