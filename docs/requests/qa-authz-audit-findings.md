# qa → backend-authz: authorisation audit findings (docs/21, docs/24 s8.5), 2026-10-18

Source: `backend/app/src/test/kotlin/com/aktcl/aron/backend/app/AuthzAuditTest.kt` (lane/qa). It makes 1,600 probes
over the admin, config, dues, price, data-entry, enrolment, device-OTP, report, export and dashboard routes, for every
seeded role (SR, AMO, TSO, DMO, WM, TOP, ANALYST, SUPPORT, ADMIN, SUPERADMIN) and a mirrored other division.

It checks:
- role refusals (exactly 401, 403 or 404);
- IDOR with the other division's ids;
- mass-assignment and strict decoding;
- separation of duties (409 plus a DB re-read);
- MFA step-up;
- PII in every 2xx;
- page-size bounds;
- clean success controls for every write.

Known findings sit in `backend/app/src/test/resources/authz-audit-baseline.txt`, which may only shrink. When a fix lands,
the fixing candidate deletes its lines (qa does it in the same candidate).

Owner: backend-authz (session_01LaW5TQ87wr3m8HBjQVjcZG, lane/backend-authz), routed by the lead 2026-10-18.

| Id | Severity | Finding | Baseline lines | Status |
|---|---|---|---|---|
| AZ-1 | low | No route checks the token's `amr`. After `cfg.auth.mfa_required_roles` turns on, a password-only token minted before still writes until it expires (15 min on the web): ADMIN, SUPERADMIN and SUPPORT (dues adjustments, config changes, entry unlocks, enrolment tokens, device OTPs). MFA is enforced only at login and refresh. | `STEPUP …` (3) | routed |
| AZ-2 | medium | SUPPORT runs all 32 business reports (`POST /v1/reports/{key}/query` → 200), against docs/24 s8.5 (SUPPORT: sync health only). `ReportEngine.WEB_ROLES` includes SUPPORT; `DASHBOARD_ROLES` already excludes it. | `ROLE POST /v1/reports/{}/query SUPPORT docs-denied` | routed |
| AZ-3 | low (contract) | Config lists (changes, values, versions, reach pending) and the device-OTP panel cap `limit` at 200 with a default page of 50; the contract's `Limit` is 1..500, default 100. | `LIMIT-CONTRACT …` (8) | routed |
| AZ-4 | medium (scope) | TSO and DMO read the other division's zone config value (values, resolve), its change set (list and by id) and its version by id. Code: `ConfigService.values/changes/change/resolve`, `versionDetail`. | (10) | routed |
| AZ-5 | medium | `CONFIG_READERS` lets DMO, WM, TOP, ANALYST and SUPPORT read all 15 config GET routes; docs/24 s8.5 gives them "—". The lead's ruling: follow docs/24 unless a built screen needs the read. | (75, one per route and role) | routed |
| AZ-6 | medium | SUPPORT lists every user's report exports and opens anyone's export job; a finished job carries a signed read URL (`ReportApi.ADMIN_VIEWERS`, `ExportJobs.kt:142-144`). TSO and DMO see only their own. | (3) | routed |

**Documented deviation (not a finding).** The lead's ruling, 2026-10-18 01:35Z: the TSO reads prices (read only),
reads route assignments, and issues SR device OTPs. These are features of the current Aron (docs/14 phase 3b, the SR
Device OTP panel in 4c, docs/15, docs/19), and observed behaviour wins over a conflicting doc. **The docs/24 s8.5 TSO
row needs updating:** prices R, route assignments R, device OTP issue.
- The audit's role oracle follows docs/15 and docs/19 for these rows.
- Scope must still hold, and is kept as positive controls: a TSO issues OTPs only for SRs in its own reach, and reads
  prices read-only.

**Questions still open** (they never fail the test):
- C2 → C3 for `cfg.auth.mfa_required_roles` and `cfg.price.max_change_pct`: agreed in principle, but it needs a
  registry migration (a final-account item; `docs/requests/backend-authz-c3-keys.md`).
- `/v1/admin/releases*` is built by lead-backend (4e0a3206): qa adds it to the probe set when it is on INT.
- The `pii` claim is never set, so even ADMIN sees masked phones: the safe default, a later feature.
- `editor_permission` is never enforced: backend-authz (Q5).
- DMO and WM are refused enrolment-token reads that docs/24 gives them: stricter, so keep it.
