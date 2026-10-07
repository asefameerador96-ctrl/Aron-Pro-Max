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
- Opus checker (worktree agent) on F-API-041, 048, N-049, F-SYS-013 was running when this was written; apply its findings before calling these rows closed.

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
