# Backend lane status

Updated 2026-10-05.

## Done
- **N-009** Ktor API skeleton: RFC 9457 problems with stable codes (also for unknown routes, 405, 413, 415, Ktor client errors and 500s), request id, marker headers, GET/HEAD `/v1/health`, `/v1/health/ready`, Hikari pools + JDBI, Flyway runner (`ARON_ROLE=migrate`), roles api/worker/migrate in one `main`, settings from env / Key Vault references / `*_FILE` with secrets redacted, per-device/per-user rate limiter (429 + Retry-After + RateLimit-*), ES256 key loading, access-token verifier and route guard, device-proof verifier, repository secret scan. Checker: 7 defects confirmed and fixed (its tests are in the suite).

## In progress
- F-SYS-001 / F-API-001 login and F-SYS-002 / F-API-002 refresh: logic and tests done against store interfaces (Argon2id + limiter, ES256 tokens with TTL jitter, lockout, rate limits, refresh rotation with 60 s grace and reuse revocation, device binding and proofs). Remaining: JDBI stores against the db lane's tables.
- F-SYS-005 scope: reach calculator, narrowing filter and the 200-randomised-query scope-leak harness (in-memory) done. Remaining: SQL reach repository + the TSO-token test against seeded data.
- N-017 day plan: rules and tests done (Daily, 3F, 2F, weekend, scoped holidays and make-up days, target outlets). Remaining: data access and the bundle part.

## Blocked / waiting
- Login and refresh persistence need `refresh_family` / `refresh_token` / device tables (N-005 has identity; device tables are N-007). Seeded SR/TSO (N-008) not landed yet.
- "GET /health answers from Azure through Front Door" needs the container image and deploy (infra lane).

## Requests filed
- none yet
