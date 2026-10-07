# backend-admin lane status

Updated 2026-10-07 (evening). Local lane tests: config 48, masterdata 105, app 24, all green on the pushed state.

## Done (built, checked, pushed to INT)
- **F-API-037** config registry, scoped values, resolution, C0-C3 change workflow, versions, audit (`backend/config`). Opus checker: 9 defects, fixed.
- **F-API-040** `GET /v1/config/delta` (as-of-version resolution, ETag, 304, 410). F-API-065 flags = `cfg.flag.*` through the same path (test).
- **F-API-036 / F-TSO-019 (panel half)** device OTPs (AES-GCM, keyed verifier, reach-scoped, 10/h limit). Opus checker: 5 defects, fixed.
- **F-API-058, 059, 060, 061, 062, 063, 064, 042, 086, F-ADM-012**, update check. Sonnet checker: 6 defects + scope gaps, fixed.
- **F-API-035c, 080, F-ADM-081** products, SKUs, product tree, price preview/publish/decision with the second-approver rail. Opus checker: 5 defects, fixed (shared commit lock with config, numeric overflow, reason trim, per-batch serialisation).
- Built by sub-agents in worktrees, merged, wired, lane tests green; checkers (Sonnet) running: **F-API-035, 035b, 045** (master-data CRUD, outlet-kind bulk), **F-API-020, 020b, 022, 027, 030, 032, 035a** (visit plans, leave, tutorials, PDA upload, feedback, content).

## Blocked, with requests filed
- `backend-admin-contract-gaps.md` (F-API-041 needs the sync RecordHandler; F-API-083 no contract path; TSO OTP issue vs s8.5; permission shape), `backend-admin-restrictive-dir.md`, `backend-admin-price-batch-table.md`, `backend-admin-content-tables.md` (incl. a V0007 leave trigger defect), `backend-admin-blob-sas.md`, `backend-admin-contract-changes.md` (me.menus, DeviceOtp columns; waiting for the lead's ruling).
- F-API-049 needs an `entry_unlock` table (db request still to file); F-API-048, 050, 051, 052, 038, N-049, F-ADM-058, F-SYS-013 need sync ingest (F-API-006, F-SYS-012, F-SYS-059) from backend-core.
- F-ADM-037 needs F-API-055 (outlet requests, backend-core).

## In progress / owed (contract v1.2, ruled by the lead, not yet on INT)
- `DeviceOtp` gets `employee_code`, `zone_code`, `zone_name` (nullable): one extra join in `DeviceOtps.kt` list and `issueOtp` result.
- `GET /v1/admin/routes?include=assignees` returns `Route.assignees [{user_id, full_name, role, username}]` with ONE query (AdminRoutes.kt list), for web-dashboard F-WEB-010.
- backend-core calls `ConfigPermissions.menusForRole` for `me.menus`.

## Next three rows
1. The two v1.2 items above (after `git pull`, commit starts "Contract v1.2").
2. F-API-049 entry-unlock (needs the db `entry_unlock` table request: not yet filed) and then F-API-048 data void, F-API-050/051/052, F-API-038, N-049 once backend-core lands F-API-006 / F-SYS-059.
3. F-API-041 config_ack handler once the RecordHandler registry exists (routed to backend-core by the lead).

## Next (older note)
Apply checker findings for the two Sonnet checks; file the `entry_unlock` request; implement the two contract changes once ruled; then the ingest-dependent rows when backend-core lands F-API-006.

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
