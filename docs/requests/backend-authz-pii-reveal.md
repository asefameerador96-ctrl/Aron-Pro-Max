# backend-authz: PII reveal for admin roles is not built (Q6), later feature

Status: recorded, not built (lead 2026-10-18). Safe default today.

## Today
- The access token's `pii` claim is always false: `TokenSubject.pii` defaults to false and `LoginService` never sets it (backend/auth TokenIssuer.kt, LoginService.kt). So every caller, ADMIN and SUPERADMIN included, sees masked phone numbers and other `pii: true` report columns (`ReportEngine.visibleColumns`).
- `cfg.pii.reauth_min` (re-authenticate before a reveal) is not in the registry and not built.
- This is the safe direction: nobody sees unmasked PII through the API, so nothing leaks; the cost is that ADMIN cannot see a full phone number on the web when docs/24 says it may (ANALYST "no PII unless granted", s8.4).

## Proposed feature (when the lead schedules it)
1. Grant: a permission `pii.reveal` per role in `app.role_grant_map` (the same per-request grant lookup Q5 adds; no token claim, so a revoke takes effect within the cache window).
2. Step-up: `POST /v1/auth/reauth` (password, plus TOTP for MFA roles) returns a short-lived reveal grant (`cfg.pii.reauth_min`, default 15, new registry key, C2) stored server-side per refresh family; the token is not reissued.
3. Reads: report runs and exports set `ctx.pii` only when the role holds `pii.reveal` AND the family has a live reveal grant; each reveal writes `app.audit_log` (who, which report, row count), and the existing PII export budgets (`cfg.ops.pii_*`) keep applying.
4. Contract: additive (new operation, `pii_until` in `GET /v1/me`); web shows a "Reveal" button with the step-up dialog.
5. Tests: no grant -> masked; grant without step-up -> masked; grant + step-up -> unmasked and audited; revoke -> masked within a minute; step-up expired -> masked.
