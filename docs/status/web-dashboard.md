# Web-dashboard lane status

## Done
- N-010 web skeleton (`web/`): generated client + drift test, HttpOnly-cookie BFF, proxy role gate (real 403), role-driven menu, scope badge, bn/en with Bengali digits, hardcoded-text lint in the build, contract-typed mock, Playwright smoke. Contract v1.1 merged, client regenerated, drift test green.

## Decisions
- Sealed (AES-GCM) session cookie `aron_sess` holds the access token server-side-readable only; `aron_rt` holds the refresh token; stateless, so logout cannot revoke a copied cookie (it dies at the next refresh).
- Roles with web access: TSO, DMO, WM, TOP, ANALYST, SUPPORT, ADMIN, SUPERADMIN. SR/AMO get "no web access".
- TypeScript pinned to 5.9 (typescript-eslint and openapi-typescript do not support 7).

## Blocked / open
- Azure deploy of the web app is infra's (image: `node scripts/start-standalone.mjs`).
- Seeded TSO login awaits db seed; mock user `tso334` stands in.

## Requests filed
- web-password-change-flow, web-refresh-cookie-handoff, web-infra-ci-job (docs/requests/).
