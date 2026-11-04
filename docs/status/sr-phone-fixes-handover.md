# HANDOVER: lane sr-phone-fixes #1 (2026-11-04)

Branch `lane/sr-phone-fixes` (from f9109e72). Decisions: D-SRFIX-01, 01b, 02, 03, 04 in DECISIONS.md.

## Item 1: "The visit could not be saved" (owner's phone, APK 1780)
Root cause (source-confirmed, not run on a worktree of e1f7e5ef): after the SR's own Sales Submit, `CaptureRepository.recordVisitOpen` did `check(isOpen)` -> plain IllegalStateException -> generic `CommitFailed`; no refusal handling existed. On f9109e72 it is a worded refusal (X4a). Fixes: `CommitFailed(failure, typeTag)`, bn+en cards (LOCKED / DAY_SUBMITTED / STORAGE), log tag `AronVisit`, same-uuid resume of an already stored visit (`StoredCallLookup`, `SrDay.heldCall`), resumed blocked visit gets `onBlocked`. A reopen exists only as the admin reopen (POST /v1/day/reopen), hence "ask the office".

## Item 2
(a) No server change. `MediaMetaPayload` required set and `app.media` CHECKs are unchanged since 2026-10-07; the phone always wrote every field. So the five records (business dates 2026-10-21, 10-24 x3, 10-30) failed on a VALUE (DB sqlstate 22/23, reported SCHEMA_INVALID "database refused the record") or an unknown ref. Their client uuids were not available to this lane (only the screenshot). To size a rule: on the dev DB read `app.ingest_registry` (status, outcome_code, detail) and `app.sync_quarantine` for those five uuids, plus the server log line "record refused by the database client_uuid=... sqlstate=..."; then compare bytes (<=307200), width/height (<=4096), blob_path pattern, purpose list, sha256 length.
(b) Done: `RejectedDismissal` (7 days, allow-list of non-money types, closed "Dismissed" card).

## Item 3
`db/seed/06b_dev_smoke_user.sql` (dev only): `smoke1` on daily route `SMK-SR-D` holding only SMOKE-SR-001..003, bound to the dev device 0001 beside sr1001. `slice-smoke.py` default user smoke1; `infra/sql/devseed-smoke-outlet.sql` moves existing smoke outlets. SLICE_PASSWORD is the same seed password (the seed hash goes to every pilot account without one). sr1001 untouched.

## Verification state
Android tests (feature-outlet, feature-dayclose, app-sr, app-amo, ui-screenshots verify, assembleDebug, lintDebug) green. `SeedGulshanBananiTest` not run here (no Docker): CI. Seed verified by hand on Postgres 16. check_infra 44 errors = missing .compiled bicep (env).
