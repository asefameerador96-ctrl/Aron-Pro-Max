# Backend lane status

Updated 2026-10-05, end of Day 1 rows.

## Done (built, checker findings fixed, pushed)
- **N-009** Ktor API skeleton: RFC 9457 problems (stable codes; unknown routes, 405, 406, 413, 415, Ktor client errors, 500), request id, marker headers, GET/HEAD `/v1/health`, `/v1/health/ready`, Hikari + JDBI, Flyway runner (`ARON_ROLE=migrate`), roles api/worker/migrate, settings from env / Key Vault references / `*_FILE` (secrets and URL credentials redacted), per-device/per-user rate limiter (429 + Retry-After + RateLimit-*), ES256 keys, token verifier, route guard, device proofs, repository secret scan.
- **F-SYS-001 / F-API-001** login: Argon2id under a per-replica limiter (503 + Retry-After under 200 parallel logins), uniform credential error with dummy hash, per-username and per-device limits (never IP), shared `auth_lockout` (username, device, Front-Door IP class), device state checks, bind / MFA / temporary-password outcomes, ES256 access token with s8.2 claims and TTL jitter, refresh + upload grants, scope summary; web refresh token as the `aron_rt` cookie.
- **F-SYS-002 / F-API-002** refresh: rotation on use, SHA-256 hashes only, 60 s grace replay returns the same token, reuse after 60 s revokes the family, device binding and proof, refusals never consume the token, sliding 30 d inside 90 ± 15 d, upload grant survives user disable.
- **F-SYS-005** scope: reach from role, effective-dated `user_scope` and `route_assignment`; selectors only narrow (403 outside); client scope ids ignored; `GET /v1/admin/outlets` reach-filtered, PII-gated, keyset paging; scope-leak harness of 200 randomised queries with zero leaks in memory, over SQL and over HTTP.
- **N-017** day plan: Daily / 3F / 2F from `visit_days_mask` and effective `route_planned`, working calendar (weekend, scoped holidays, make-up days), planned flag and target outlets per route (`SqlRoutePlanner`, interface `RoutePlanner` for the bundle).

All of it runs on real PostgreSQL 16 (126 backend tests).

## Open, with the reason
- Acceptance on the **seeded** SR/TSO waits for the db lane's N-008 seed (request filed). Tests seed their own data meanwhile.
- **Device tables** (N-007, V0010) not landed: production wiring uses no device store; phones log in only where `cfg.device.require_enrolled = false` and get no refresh grant until bound (request filed for `refresh_family.device_uuid`).
- **N-017 bundle clause** (bundle carries planned routes and target outlets, frozen at first fetch) lands with F-API-005 on Day 2.
- **Health through Front Door / Container Apps** needs the infra lane's container image and deploy (no Dockerfile in the repo yet; the image must run `backend:app` with ARON_ROLE, attach the App Insights agent, and set ARON_FRONT_DOOR_ID).
- Server generation header sends the nil UUID until the sync schema (N-006) has `server_generation`.

## Requests filed
- `docs/requests/backend-seed-login-accounts.md` (db): login-ready seed accounts (hashes from an env var, must_change_password false, SR with Daily/3F/2F routes, TSO, device binding after V0010).
- `docs/requests/backend-refresh-family-device-uuid.md` (db): `refresh_family.device_uuid` so unenrolled phones keep a bound refresh grant.
- Answer to `docs/requests/web-refresh-cookie-handoff.md` (web): the BFF cookie hand-off is the intended one; implemented (DECISIONS.md).
