# backend-core lane status (handoff for a fresh session)

Updated 2026-10-07 by the Day 1 backend session (context ~600k, recycled per docs/29 s5). Earlier history: `docs/status/backend.md` and `docs/status/backend.csv` (keep appending to `backend.csv`; it is this lane's time log).

## Done (pushed to INT, checker findings fixed, acceptance on the seed)
N-009, F-SYS-001, F-API-001, F-SYS-002, F-API-002, F-SYS-005, N-017 (planner part). 130 backend tests on PostgreSQL 16, incl. `backend/app/src/test/.../SeededAcceptanceTest.kt` (sr1001 login/refresh on the seeded dev phone, tso1001 territory-only outlets, seeded Sunday/Friday plan, target outlets 40).

## In progress
Nothing half-done. The working tree is clean.

## Next three rows (critical path, in this order)
1. **F-API-005 `GET /v1/sync/bundle`** (L, T1). Call `RoutePlanner.routesFor(userId, date)` (platform `DayPlan.kt`; implementation `masterdata/SqlRoutePlanner`; `targetOutlets` is already 0 for unplanned routes). Freeze `target_outlets` at the first bundle of the day (route_day, V0007 line ~294). `targets` and `offers` arrays EMPTY (docs/27). ETag = bundle_version, 304 on If-None-Match, gzip. Reach: `SqlReachResolver` (masterdata) through the `ReachResolver` interface in platform. The bundle belongs to module `sync`; masterdata now belongs to backend-admin, so call it only through platform interfaces (append-only wiring in `app/Wiring.kt`).
2. **F-API-006 `POST /v1/sync/batch`** (L, T1). Tables in V0008: `ingest_registry` (hash-partitioned on client_uuid, PK client_uuid, ruling R1 docs/24 s14a), `sync_batch`, `sync_rejected`, `sync_quarantine`, `server_generation`, `domain_event`. docs/24 s3.3 (payload_sha256 via the RFC 8785 canonicaliser in shared:contract), s4.4 to s4.6, s4.12. Guard config: `authenticated(guard) { audiences = setOf(API, UPLOAD); expiredGraceS = 60; checkScopeVersion = false for UPLOAD }`. Body must be gzip (415 otherwise), 1 MiB compressed / 8 MiB decompressed (`receiveStrict` has gzip + caps; raise maxBytes for this route). X-Device-Proof over the gzip body bytes (DeviceProof.kt has the verifier; add the batch proof string). Inflight cap 64 per replica -> 503 Retry-After 5..60.
3. **F-SYS-014 quarantine of invalid references** (M, T1), then F-SYS-055/048/062/012/013 per `python3 tools/my-rows.py backend-core --todo`.
Also replace `NIL_GENERATION` in `app/Wiring.kt` with a read of `app.server_generation` once F-API-006 lands.

## Where things are
- Platform (`backend/platform`): problems (`ApiProblem`, status from `ProblemCode`), `installAronPlatform` (request id, markers, Front Door gate, StatusPages), `receiveStrict` (strict JSON, duplicate members, gzip, caps), `RateLimiter`, `AuthGuard`/`authenticated(...)`, `AccessTokenVerifier`, `JwtKeys`, `DeviceProof`, `Database`/`Migrator`, `DbServerConfig` (global cfg reads, config_version), `Reach`/`ReachResolver`, `DayPlan`/`RoutePlanner`, testFixtures `FreshDb.create()` (fresh migrated DB per test class).
- Auth (`backend/auth`): `LoginService`, `RefreshService`, `TokenIssuer`, `PasswordHasher`/`HashLimiter`, JDBI stores (`JdbiUserStore`, `JdbiRefreshStore`, `JdbiLockoutStore`, `JdbiDeviceStore`).
- Decisions: `DECISIONS.md`, section "Build-session decisions: backend lane".

## Traps found
- **Maven Central answers HTTP 429** to this container. Using a mirror init script was refused by the session's safety classifier: do NOT add one. Retry with growing backoff (60 s, 120 s, ...); never use `--refresh-dependencies`.
- **Set `ARON_TEST_PG_URL` in the same shell command** as gradle (shell state does not persist): `export ARON_TEST_PG_URL='jdbc:postgresql://localhost:5432/aron_test?user=aron&password=aron' && ./gradlew ...`. After a container restart: `service postgresql start`. Role `aron`/`aron` is superuser (create it if missing).
- **Push only with `&&` after a green build.** Once a push followed a failed build through `;`; the code was green, but never again.
- `refresh_family.device_id` has an FK to `app.device` (V0010): tests that create refresh families need real device rows (`AuthFixture.addDevice/bind`).
- The seed's dev phone (`00000000-0000-4000-8000-000000000001`) has a placeholder key: no proofs possible; allowed only while `cfg.device.require_enrolled` is false.
- The geography snapshot is cached for 60 s: use `GeoRepository.geoCovering(routeIds, zoneIds)` when live rows may reference a newer route or zone.
- No session state on connections (api pool behind PgBouncer): no `SET`, no session advisory locks in api code.
- Reviewer worktrees under `.claude/worktrees/` are excluded locally (`.git/info/exclude`); never commit them.
- Requests open: `docs/requests/backend-refresh-family-device-uuid.md` (db). Phones without a device row get no refresh grant until it lands.
