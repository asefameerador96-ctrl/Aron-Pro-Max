# backend-core lane status (handoff for a fresh session)

Updated 2026-10-07 (session 2 of the lane). Earlier history: `docs/status/backend.md`; time log `docs/status/backend.csv`.

## Done this session (pushed to INT)
- **F-API-005 `GET /v1/sync/bundle`** (`backend/sync/BundleService.kt`, `ScopedConfig.kt`, `ReasonTexts.kt`): Opus checker 5 findings, 4 fixed; growing `snapshot_seq` waits on `docs/requests/backend-bundle-snapshot-table.md` (code ready, test assumption-guarded).
- **F-API-006 `POST /v1/sync/batch`** (`IngestService.kt`, `RecordWriter.kt`, `TypeRules.kt`, generated `PayloadShapes.kt`, `Jcs.kt`): Opus checker 8 findings, all fixed (`BatchCheckerTest`).
- **F-SYS-055, F-SYS-048, F-SYS-014, F-SYS-062**: Opus checker (combined) 8 findings, all fixed (`IngestRulesCheckerTest`). Open, not a defect of these rows: a worker must turn parked rows final after `cfg.sync.parked_ttl_days` (nothing reads `parked_until` yet); the quarantine review endpoints (`listQuarantine`, `resolveQuarantine`) do not exist yet.
- Built and pushed, **Opus checker NOT run yet**: **F-SYS-060** (`DuesLedger.kt`: ledger hooks, memo void tombstone, FIFO ageing), **N-036** (breadcrumb ingest; the team-location read is backend-reports' `GET /v1/team/locations`, my duplicate was removed), **F-SYS-078** (multi-visit, visit kinds). First job of the next session: one combined Opus checker for these three.
- Platform fix: Ktor Compression now compresses responses only (request bodies were decoded twice).

## Decisions taken (this session)
- `bundle_version` seq = content digest until `app.bundle_snapshot` exists; ETag correct either way.
- Bundle `supervisor` is null (AMO zone-wide bundle is F-AMO-044, backend-reports); `templates` empty (no table; phone uses embedded default); `my_outlet_requests`/`device_policy_version` not sent yet.
- F-SYS-014 "parked" read as "kept in sync_rejected": the contract makes `unknown_sku` a final rejection.
- F-SYS-062 "rejected" read as the contract's quarantine `arithmetic_mismatch` (s7.4); split families are flagged on the memo.
- `net_by_category_mtk` = sum of active memo line gross per SKU category (no spec formula; phone must match).
- Target outlets are frozen by the first non-pre-fetch bundle (checker reading of s12.4/s4.9).
- Parents must belong to the uploader, except dues against a memo in reach, an assigned task, an outlet request in reach.

## Lead items queued for the next session (2026-10-07, in this order)
- Maven: the owner approved the Google Central mirror, now in `settings.gradle.kts` on INT (pull); no init scripts. The retry loop in this session was before it landed.
- Audit (docs/audit/2026-10-07-enterprise-bar-audit.md, `my-rows.py --todo`): **AUD-TP-1** idempotent-sync fuzz/property harness on POST /v1/sync/batch (duplicate, reordered, partial, concurrent batches; the checker tests BatchCheckerTest/IngestRulesCheckerTest are a start); **AUD-REL-01/02** DB outage must not fail liveness, DB errors map to retryable 503 not 500 (note: IngestService maps a non-data DB error per record to `server_error` retryable already; the request-level path still gives 500). Then AUD-SEC-02, PERF-02, SEC-01/03/07/08, REL-07/08, TP-3 by day.
- `docs/requests/backend-admin-nul-in-text.md`: reject U+0000 at the platform layer (400). The sync batch already makes it a per-record schema_invalid.
- `GET /v1/me` `menus` via `ConfigPermissions.menusForRole(role)` (contract v1.2).
- Web password change: `password_change_token` (aud `aron-pwchange`, 10 min, only POST /v1/auth/change-password, which answers 200 LoginResponse, refresh via Set-Cookie `aron_rt`) — docs/24 s14a R15, R18.
- Device record: store `root_hints` and `play_integrity_unavailable` once db adds the columns (request file to db needed); missing = NULL = integrity unknown, never clean or failed; root_hints only weighted evidence for DEVICE_INTEGRITY_FAIL.
- requestHash: lower-case hex SHA-256 of nonce || device_uuid. OTP bind compares `OtpCipher.mac(otp, user_id)`.
- `docs/requests/backend-migrate-connect-retries.md`: Flyway connectRetries and a longer timeout for the migrate pool only.
- `cfg.sync.reconcile_types` is being reshaped by db (flat object `ROLE.row` -> record types) and `cfg.bundle.outlet_fields` becomes server delivery: when it lands, re-check `ScopedConfig.fitsContract` keeps both (the request backend-config-value-shape is answered: contract not widened).
- **ANNOUNCED 2026-10-07: the `RecordHandler` registry is on INT** (ingest extension point, F-API-006 family). Interface `backend/platform/.../platform/RecordHandler.kt` (`RecordHandler { types; check(h, rec): RecordRefusal?; afterStored(h, rec, serverId) }`, `IngestRecord`, `RecordRefusal`); register one line in `Wiring.recordHandlers(db, clock)` (append-only). `check` runs before the generic write (a refusal's outcome code decides quarantine/park/final); `afterStored` runs once on first store, in the record's savepoint (throwing = retryable `server_error`). Test: `RecordHandlerTest`. Also fixed: `config_ack` was refused by the database (payload `config_version` overwrote the envelope stamp; now stored as `acked_config_version`). Unblocks backend-admin's config_ack handler and the rows waiting on it.
- When F-SYS-059 lands, replace backend-admin's AuditWriter with the platform audit writer.

## Requests filed
`backend-jcs-canonicaliser.md` (shared), `backend-config-value-shape.md` (lead), `backend-bundle-snapshot-table.md` (db), `backend-core-assignment-ended-at.md` (backend-admin).

## Next rows (after the checker above)
F-SYS-056 route-day planning job, F-API-031 logout, F-SYS-016 day state machine (in_field/synced from ingest), F-API-026 tasks, N-037 push, then Day 3 rows (`python3 tools/my-rows.py backend-core --todo`).

## Traps found
- Maven 429: `/tmp`-style retry loop around gradle (sleep 60..480 s) works; it can take 20+ min.
- Fixed clock in tests: login limiter (10/15 min per user) and device limiter never reset; cache one token per test class.
- Each test sale family needs a distinct `captured_at` (content fingerprint).
- Content fingerprint: test sale families need a distinct captured_at AND a distinct committed_at minute.
- `PayloadShapes.kt` is generated (`python3 backend/sync/tools/gen_payload_shapes.py`); `JcsTest` fails when the contract moves: regenerate.
- backend-reports' `DailyTrackingTest` was red on INT (domain_event type `tracking_action.created` missing in the db registry): not this lane's.
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
