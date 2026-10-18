# backend-authz lane status

## HANDOVER (session 1, 2026-10-18) — read this first
- Lane: backend-authz (Opus), branch `lane/backend-authz` (from INT 0a228647). Push only there; merge INT before every push; tell the lead (session_01MbUQSxrP7AB9tbyANjUTPS) `ready <sha>` once that sha's own CI run is ALL green. Integrator: session_01E5Npz6awntNxGPuFaaGjRF. lead-backend (money/sync/idempotency, owns ConfigScope.kt and the price replay-first): session_014aSx5HgZg1V3hp2ydEvQC4. qa (AuthzAuditTest baseline): session_01EBKfNb2MyCc7ddsrfjcmcP. web-dashboard: session_01DFSVGQoFFF3whoQaJgMPpN.
- Local tests: `service postgresql start`; role `aron`/`aron` (superuser) and db `aron_test`; `export ARON_TEST_PG_URL='jdbc:postgresql://localhost:5432/aron_test?user=aron&password=aron'` in the same shell as gradle. Never put a `//` comment in the middle of a one-line JWT builder chain (it swallowed `.expirationTime` once).
- Module test tokens (analytics TestTokens, config ConfigTestSupport, masterdata TestTokens/AdminProductsHarness) give ADMIN, SUPERADMIN and SUPPORT amr [pwd, mfa] since AZ-1; a test of a password-only admin token must build its own.
- Queue order (lead): AZ-2, Q1 rest, Q2, AZ-3, AZ-1, Q5; report-only Q3/Q4/Q6/Q8. Q1/AZ-3/Q2 and Q5 build on lead-backend's unpromoted commits (8a571fa1, faae41f7, 45755543): merge the sha lead-backend calls checked before editing ConfigApi/ConfigScope/AdminPrices.

## Items
- AZ-2 (SUPPORT ran all 32 reports): DONE d39db691. REPORT_ROLES (was WEB_ROLES) drops SUPPORT; SUPPORT also cannot open others' export jobs (download URL); sync health (/v1/dashboards/sync-health) unchanged. Test SupportReportAccessTest (32 keys). web-dashboard told (catalog REPORT_ROLES and menu still show SUPPORT the reports). Opus checker: running.
- AZ-1 (MFA only at login/refresh): DONE 68522d11. AuthGuard: web API token, role in cfg.auth.mfa_required_roles, amr without mfa -> 401 ERR_AUTH_MFA_INVALID on writes and on /v1/admin/config*, /v1/admin/enrolment-tokens* reads; /v1/auth/* and plain reads open; phones exempt. Test MfaPerRequestTest (old token after the flag turns on). Opus checker: running.
- Q1 rest (config changes/versions/reach pending scope): waiting on lead-backend's checked sha.
- Q2 (CONFIG_READERS too wide): after Q1.
- AZ-3 (config list limits 1..500, default 100): after Q1 (same file).
- Q5 (finance.backdate never grantable): investigated. Grants live per role in `app.role_grant_map` (V0002, no rows, no API); the token `perm` claim is always empty. Plan: the guard adds the role's grants from a cached lookup (JdbiUserStore, 10 s like the scope version) to the principal, so a grant or revoke (a row in role_grant_map) takes effect within seconds; waits on lead-backend 45755543 (price replay-first) reaching this branch.
- Q3/Q4 (one ADMIN can loosen MFA roles / max_change_pct alone): reported, docs/requests/backend-authz-c3-keys.md (option B, value-triggered C3 in RiskClassifier, needs no migration).
- Q6 (pii claim never set): reported, docs/requests/backend-authz-pii-reveal.md (safe default today).
- Q8 (enrolment-token reads refused to DMO/WM): stricter than docs/24, kept (lead).
