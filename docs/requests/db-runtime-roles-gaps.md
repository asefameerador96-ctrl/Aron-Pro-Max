# Request (infra → db, backend-core): grant gaps found before switching the apps to their own logins

From: infra lane, 2026-10-07. Answers `docs/requests/db-runtime-roles.md` (incl. the V0020 update).

## What infra has done (pushed)

- `infra/sql/runtime-logins.sql`, run by the `dblogins` Container Apps job after every migration: `app_api` (api_rw,
  plus pii_reader and support_ro WITH INHERIT FALSE, SET TRUE), `app_worker` (worker_rw), `app_jobs` (jobs_rw); exact
  memberships, never superuser/CREATEROLE/BYPASSRLS; then `SELECT app.apply_login_limits()` (items 1 and 2 of the update).
  `app_auth`, `app_web` and a BI login are not created: auth has no own pool, the web BFF does not read the database,
  and there is no replica or BI consumer yet. Say when one is needed.
- Passwords in Key Vault, URLs derived from the admin URLs. The migrate job keeps the admin login.
- The switch (`dbPerAppLogins` in `infra/params/*.apps.bicepparam`) is **off**: the apps still use the admin login.

## Why it is off: api_rw lacks DELETE that live admin flows use (independent checker, confirmed on a migrated db)

`has_table_privilege('api_rw', ..., 'DELETE')` is false for these, true only for `app.auth_lockout`:

| Code | Statement | Effect as app_api |
|---|---|---|
| `backend/masterdata/.../AdminRoutes.kt:91` | `DELETE FROM app.route_planned` | visit-days change with `effective_from` → 500 |
| `backend/masterdata/.../AdminUsers.kt:164` | `DELETE FROM app.mfa_secret` | admin MFA reset → 500 |
| `backend/masterdata/.../AdminUsers.kt:267, 271` | `DELETE FROM app.user_scope` | scope edits → 500 |

## Ask

1. **db:** a forward migration adding the DELETE rows to `app.db_role_grant` for `api_rw` on those three tables (or tell
   backend to soft-close rows instead), re-applied by `app.apply_db_role_grants()`.
2. **backend-core:** item 4 of the update (suites run as `api_rw`), at least for the admin masterdata, auth, ingest and
   scope paths, so the next missing grant fails a test instead of dev.
3. Tell infra when both land; infra then sets `dbPerAppLogins = true` and watches the first deploy.

## Also noted (infra, follow-up)

The api, worker, migrate and web identities can read every secret in the vault (`kvRead` at vault scope), so per-app
logins do not yet isolate a compromised api from the admin URL. Per-secret role assignments are an infra follow-up.
