# Notes for lanes that are ON HOLD (lead, 2026-10-07; usage constraint)

Lanes on hold are not woken (docs/status/lane-sessions.md, throttle list). The lead appends here what each must know on release and sends it in one message then.

## web-dashboard (and web-admin, web-config)
- backend-core (change-password with `aron-pwchange`, R15 and R18): **the BFF must forward the `Cookie: aron_rt` header on `POST /v1/auth/change-password`**, otherwise all of that user's web sessions are revoked. The statuses 403, 409 and 503 are coming in contract v1.4 (`docs/requests/backend-core-change-password-statuses.md`); handle them in the form with plain Bangla and English messages.
- Contract v1.3 is on `lane/lead-contract-v1-3` (promoted to INT by the integrator): remove your REQUEST stubs for acting scope `valid_to` (exclusive end), `POST /v1/outlet-requests`, `CodeItem.id`, SKU image, definition reads, tutorial `asset_id`, feedback filters (docs/requests/contract-v1.3-queue.md).
- Design: `docs/design/tokens.md` (refined v1) and `web-glass.md` are final for now; the web must apply section 11 of tokens.md (web-glass.md was written from v0 colours and must adopt the tokens.md values), then re-run its contrast unit test.

## backend-admin
- Contract v1.3 server work (R20, docs/requests/contract-v1.3-queue.md); the web-admin temp-password TTL fix (cfg.auth.temp_password_ttl_h default 24) if not done; wall-clock test offenders in masterdata (6) and config (4) by 2026-10-08 evening (the gate blocks on 2026-10-09).

## backend-reports
- Wall-clock test offenders in analytics (2) by 2026-10-08 evening; db tables for export and device facts are with db.

## android-sys
- Done. Low-priority leftover: the desk tool that opens "ARONSUP1" support files (docs/requests/android-sys-support-key.md).

## laptop operator (before the first daily gate pull request INT to main)
- infra folded the "Contract lint" job into "Repository gates" (2026-10-07 08:48 UTC). `main`'s live protection still lists "Contract lint" as a required check, which will never report again and would block the gate PR. Pull INT in C:\Users\User\Aron and re-run `tools/github-governance.ps1` (create-only, idempotent; the protection step replaces the required list with the new one: 7 checks). Needs the owner's usual approval prompt. Do it before the lead opens the first gate PR.
