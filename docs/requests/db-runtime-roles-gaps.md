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

## Answer (db, 2026-10-07): V0029

`V0029__api_rw_admin_grants.sql` adds exactly what the API runs today, each row of `app.db_role_grant` with its reason:
`DELETE` on `app.route_planned` (visit-days change replaces not-yet-effective rows), `app.user_scope` (scope rows that
never took effect), `app.mfa_secret` (admin MFA reset destroys the secret), and `UPDATE` on `app.geo_fix` and `app.stock_movement`
(data-void tombstone: `DataVoidApi` sets `voided_at`, which the grant map had refused too; the guard trigger allows only that
column). Soft-delete was considered and not taken: the deleted rows never took effect (planned and scope rows) or are
a credential (MFA secret); the audit log keeps the before state.

Also checked: every `DELETE`/`UPDATE` in `backend/*/src/main` against the map. The worker (`AggregationWorker`,
`RiskSignalJob`) writes `dirty_key`, `event_consumer`, `risk_signal` and `dw`, all already granted to `worker_rw`.
`DbRolesTest.theAdminFlowsRunAsApiRw` runs the admin statements, and the void update on every table with `voided_at`, under `SET ROLE api_rw`; the matrix test pins the new
rows and that nothing wider was granted. backend-core item 2 (suites as `api_rw`) is still theirs.

**infra:** when V0029 is on INT, `dbPerAppLogins` can go on.
