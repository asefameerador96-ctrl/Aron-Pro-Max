# backend-core lane status (handoff for a fresh session)

Updated 2026-10-07 (session 2 of the lane). Earlier history: `docs/status/backend.md`; time log `docs/status/backend.csv`.

## Done this session (pushed to INT)
- **F-API-005 `GET /v1/sync/bundle`** (`backend/sync/BundleService.kt`, `ScopedConfig.kt`, `ReasonTexts.kt`): Opus checker 5 findings, 4 fixed; growing `snapshot_seq` waits on `docs/requests/backend-bundle-snapshot-table.md` (code ready, test assumption-guarded).
- **F-API-006 `POST /v1/sync/batch`** (`IngestService.kt`, `RecordWriter.kt`, `TypeRules.kt`, generated `PayloadShapes.kt`, `Jcs.kt`): Opus checker 8 findings, all fixed (`BatchCheckerTest`).
- Built, Opus checker running (combined): **F-SYS-055, F-SYS-048, F-SYS-014, F-SYS-062**. Built, checker pending: **F-SYS-060** (`DuesLedger.kt`), **N-036** (`TeamLocations.kt`), **F-SYS-078**.
- Platform fix: Ktor Compression now compresses responses only (request bodies were decoded twice).

## Decisions taken (this session)
- `bundle_version` seq = content digest until `app.bundle_snapshot` exists; ETag correct either way.
- Bundle `supervisor` is null (AMO zone-wide bundle is F-AMO-044, backend-reports); `templates` empty (no table; phone uses embedded default); `my_outlet_requests`/`device_policy_version` not sent yet.
- F-SYS-014 "parked" read as "kept in sync_rejected": the contract makes `unknown_sku` a final rejection.
- F-SYS-062 "rejected" read as the contract's quarantine `arithmetic_mismatch` (s7.4); split families are flagged on the memo.
- `net_by_category_mtk` = sum of active memo line gross per SKU category (no spec formula; phone must match).
- Target outlets are frozen by the first non-pre-fetch bundle (checker reading of s12.4/s4.9).
- Parents must belong to the uploader, except dues against a memo in reach, an assigned task, an outlet request in reach.

## Requests filed
`backend-jcs-canonicaliser.md` (shared), `backend-config-value-shape.md` (lead), `backend-bundle-snapshot-table.md` (db), `backend-core-assignment-ended-at.md` (backend-admin).

## Next rows
F-SYS-056 route-day planning job, F-API-031 logout, F-SYS-016 day state machine (in_field/synced from ingest), F-API-026 tasks, N-037 push, then Day 3 rows (`python3 tools/my-rows.py backend-core --todo`).

## Traps found
- Maven 429: `/tmp`-style retry loop around gradle (sleep 60..480 s) works; it can take 20+ min.
- Fixed clock in tests: login limiter (10/15 min per user) and device limiter never reset; cache one token per test class.
- Each test sale family needs a distinct `captured_at` (content fingerprint).
- INT moves every few minutes: merge, test only if backend/db/shared changed, push, loop.
- **Maven Central answers HTTP 429** to this container. Using a mirror init script was refused by the session's safety classifier: do NOT add one. Retry with growing backoff (60 s, 120 s, ...); never use `--refresh-dependencies`.
- **Set `ARON_TEST_PG_URL` in the same shell command** as gradle (shell state does not persist): `export ARON_TEST_PG_URL='jdbc:postgresql://localhost:5432/aron_test?user=aron&password=aron' && ./gradlew ...`. After a container restart: `service postgresql start`. Role `aron`/`aron` is superuser (create it if missing).
- **Push only with `&&` after a green build.** Once a push followed a failed build through `;`; the code was green, but never again.
- `refresh_family.device_id` has an FK to `app.device` (V0010): tests that create refresh families need real device rows (`AuthFixture.addDevice/bind`).
- The seed's dev phone (`00000000-0000-4000-8000-000000000001`) has a placeholder key: no proofs possible; allowed only while `cfg.device.require_enrolled` is false.
- The geography snapshot is cached for 60 s: use `GeoRepository.geoCovering(routeIds, zoneIds)` when live rows may reference a newer route or zone.
- No session state on connections (api pool behind PgBouncer): no `SET`, no session advisory locks in api code.
- Reviewer worktrees under `.claude/worktrees/` are excluded locally (`.git/info/exclude`); never commit them.
- Requests open: `docs/requests/backend-refresh-family-device-uuid.md` (db). Phones without a device row get no refresh grant until it lands.
