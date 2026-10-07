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

## Next
Apply checker findings for the two Sonnet checks; file the `entry_unlock` request; implement the two contract changes once ruled; then the ingest-dependent rows when backend-core lands F-API-006.

## Traps
- Never run two Gradle builds in one worktree at once (test-result files collide); checkers use their own worktree.
- Builders' test helpers clash by name across files (`obj`, `send`, `str`, `items`): suffixes A1/A3 were added.
- Test clocks: two changes at one scope in the same millisecond are bumped by 1 ms.
