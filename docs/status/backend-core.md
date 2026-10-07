# backend-core lane status (handoff for a fresh session)

Updated 2026-10-07 (session 3 of the lane). Earlier history: `docs/status/backend.md`; time log `docs/status/backend.csv`.

## Done this session (pushed to INT)
- **F-API-005 `GET /v1/sync/bundle`** (`backend/sync/BundleService.kt`, `ScopedConfig.kt`, `ReasonTexts.kt`): Opus checker 5 findings, 4 fixed; growing `snapshot_seq` waits on `docs/requests/backend-bundle-snapshot-table.md` (code ready, test assumption-guarded).
- **F-API-006 `POST /v1/sync/batch`** (`IngestService.kt`, `RecordWriter.kt`, `TypeRules.kt`, generated `PayloadShapes.kt`, `Jcs.kt`): Opus checker 8 findings, all fixed (`BatchCheckerTest`).
- **F-SYS-055, F-SYS-048, F-SYS-014, F-SYS-062**: Opus checker (combined) 8 findings, all fixed (`IngestRulesCheckerTest`). Open, not a defect of these rows: a worker must turn parked rows final after `cfg.sync.parked_ttl_days` (nothing reads `parked_until` yet); the quarantine review endpoints (`listQuarantine`, `resolveQuarantine`) do not exist yet.
- **F-SYS-060, N-036, F-SYS-078 checked** (Opus, 2026-10-07, `DuesVisitsCheckerTest`): 9 findings. Fixed: a late collection after its memo's void or edit now gives back part of the closure (`adjustment`, note `late_collection_reopens_closure`) so every upload order ends at the in-order balance; an edit of a non-active memo is `edit_not_allowed`; a collection posts to its memo's outlet, never the payload's; ageing cancels voids and edits against their own memo, and only collections and adjustments pay FIFO; the memo's ledger rows are read and closed under a per-memo transaction lock. Visit-kind policy: an SR phone books `sr_call` only, AMO also `amo_control_call` and `amo_joint_call`, TSO also `tso_visit`; `web_entry` never comes from a phone (others are quarantined `scope_out_of_reach`). Routed: breadcrumb partitioning (`docs/requests/backend-core-breadcrumb-partitioning.md`, db).
- Platform fix: Ktor Compression now compresses responses only (request bodies were decoded twice).
- **Session 3 (2026-10-07):**
  - F-SYS-060 re-checked by a second Opus checker (`DuesRecheckTest`): every upload order of {memo, 2 collections, void} and of {A, collection A, edit B, collection B, void B} ends at the in-order balance. Two findings, both fixed: the late-collection adjustment is now dated on the closure's business date, so as-of rollups do not depend on upload order; a collection captured after the void or edit gives nothing back (it is real cash).
  - **RecordHandler registry** (see the lead items below); `config_ack` was refused by the database before (fixed).
  - Platform: U+0000 in a JSON body is answered 400 `invalid_character` (`receiveStrict`; the batch still refuses it per record); SQLSTATE 22021 or 22P05 on any path is answered 400. The migrate role uses Flyway `connectRetries` 10 at 15 s and a 30 s pool timeout (api and worker keep 5 s).
  - `GET /v1/me` returns `menus` (R10).
  - **F-API-004, F-SYS-004 change-password**, with the web `password_change_token` (`aron-pwchange`, R15/R18): policy by role, 24 h minimum age, no reuse of the current password, Argon2id, revokes the full grants except the calling phone's own, upload grants survive. "Last 10" waits for `docs/requests/backend-core-password-history.md` (db); its test is assumption-guarded. Opus checker: 4 findings, fixed: a phone client serves only its own field role (an ADMIN could skip TOTP through app_sr; now 403 ERR_FORBIDDEN); a web BFF that forwards its aron_rt keeps its own family; the deny-list matches deny words inside the password. Contract status codes: `docs/requests/backend-core-change-password-statuses.md`. **web-dashboard:** the BFF must forward `Cookie: aron_rt` on change-password, or every one of the user's web sessions ends.
  - **F-API-031 logout** (`LogoutTest`; Opus checker: nothing reproducible, `LogoutCheckerTest`). Notes: access tokens stay valid until expiry (stateless); a web logout revokes only the family whose aron_rt the BFF forwards; docs/21 allows logout in bind_required, docs/24 does not (code follows docs/24).
  - **F-SYS-056 route-day planning job** (`RouteDayPlanningJob`, worker, every 5 min, from 00:05 Dhaka; `RouteDayPlanningTest`) and **F-SYS-016 day state machine** (`DayStates`, hooked into ingest; `DayStateTest`). The settle timeout runs in the same worker tick. Combined Opus checker running. Found and fixed: the generated payload shapes refused every `day_submit` that had counts (a map member was treated as an object with no members).
  - **F-SYS-003, F-API-003 bind-device**: at `bind_required` the login creates one OTP per live window (sealed with `OtpCipher`, `otp_sha256` = `OtpCipher.mac`); `POST /v1/auth/bind-device` needs the bind token, X-Device-Proof (`bind`, hex sha256(otp), bucket), is single use, expires after 120 min, and refuses everything after 5 wrong tries; it binds with the next free ordinal (0 to 3) and continues the login. Opus checker: 2 findings, both fixed: a fifth phone answers 409 `ERR_DEVICE_LIMIT_REACHED` (docs/24 s7.5; android-core should map it, the app maps only `ERR_AUTH_BIND_LOCKED` today), and is refused before the OTP is spent. OTP creation now takes the same user-row lock as the TSO panel. Docs note for the lead: an OTP locked after 5 tries stays locked (hidden on the panel) until it expires or the TSO re-issues it; docs/24 says it "expires".
  - Not mine, red on INT: backend-admin's `ConfigToolsTest.whatIfCountsVisitsWhoseVerdictWouldChange` (expected 0, was -4).

## Decisions taken (this session)
- Change-password revokes every full-grant family of the user except the calling phone's own (the contract says "other"; a web caller has no family id in the token, so all web families go and the BFF logs in again).
- A phone with a temporary password keeps the 10-minute API token for change-password (R15 covers `client: web` only).
- The bind OTP is created at the first `bind_required` login and reused while it is live; a new one only after expiry, consumption or a TSO re-issue. Wrong tries count per OTP (5), so a TSO re-issue resets them.
- A collection captured after its memo's void or edit is booked against the outlet; only one captured before gives back the closure.
- Over-collection (a collection above the memo's open balance) is booked as is, because the SR really took the cash: the memo goes below zero and the outlet shows a credit in the ageing (`Ageing.credit`). The spec sets no policy (2026-10-07).
- AMO and TSO phones may also send `sr_call` (an own sale call, e.g. while covering a route); the AMO path has no seeded device test yet.
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
