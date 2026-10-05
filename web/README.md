# Aron web (dashboards and admin portal)

Next.js 16 (App Router) · TypeScript strict · Tailwind 4 · Node 22. One app, two route groups:
`(dashboards)` for the web roles and `/admin` for `SUPPORT`, `ADMIN`, `SUPERADMIN` (everyone else gets HTTP 403).
Binding rules: `docs/24-build-spec.md` s6.5 (BFF auth), s8 (roles), `docs/26-lane-playbook.md`.

## Commands

| Command | What |
|---|---|
| `npm ci` | install (CI) |
| `npm run gen:contract` | regenerate `src/contract/openapi.d.ts` from `../contract/openapi.yaml` (never edit it by hand) |
| `npm run lint` | ESLint, including **`local/no-hardcoded-text`** (user-visible text must come from the catalogue) |
| `npm run typecheck` | `tsc --noEmit` (a contract rename that touches code here fails this) |
| `npm test` | Vitest: contract drift, i18n parity, lint rule, roles/menu, sealing, BFF + CRUD handlers against the mock |
| `npm run build` | runs `lint` first (`prebuild`), then `next build` (standalone output) |
| `npm run test:e2e` | Playwright smoke tests against the mock (needs a prior `npm run build`) |
| `npm run mock` | the mock API on `http://127.0.0.1:4010` |
| `npm run dev` / `npm start` | dev server / the standalone production server (`scripts/start-standalone.mjs`) |
| `bash scripts/ci.sh <install\|generate\|lint\|typecheck\|test\|build\|e2e\|all>` | the steps the CI workflow calls |

`npm run dev` against the mock: `npm run mock` in one terminal, then
`ARON_API_BASE_URL=http://127.0.0.1:4010 ARON_COOKIE_INSECURE=1 npm run dev`.
Mock users: `tso334/tso-pass-1` (TSO), `wm1/wm-pass-1`, `analyst1/analyst-pass-1`, `admin1/admin-pass-1` and
`support1/support-pass-1` (TOTP code `123456`), `sr334001/sr-pass-1` (no web access), `locked1`, `pwchange1`.

## Environment

| Variable | Meaning |
|---|---|
| `ARON_API_BASE_URL` | origin of the API (paths already start with `/v1`); default is the mock |
| `ARON_SESSION_SECRET` | 32+ characters sealing the session cookies; **required in production** (the server throws without it) |
| `ARON_COOKIE_INSECURE=1` | drops `Secure` from cookies for plain-http localhost; never in production |
| `NEXT_PUBLIC_MAPS_WEB_KEY` | Google Maps key (GitHub secret `MAPS_WEB_KEY`, build time). Empty = maps show a placeholder |
| `PW_CHROMIUM_PATH` | optional path to a pre-installed Chromium for Playwright (sandbox only) |

Nothing here needs a secret to build or test.

## How auth works (BFF)

The browser talks only to Next.js. Route handlers under `/api/bff/*` call the API server-side:

- `aron_rt` (refresh token) and `aron_sess` (access token + user + scope, **AES-GCM sealed**) are `HttpOnly`,
  `SameSite=Strict`, `Secure`. No script can read them; no BFF response body contains a token; nothing goes to
  `localStorage`.
- Login: password, then for `ADMIN`/`SUPERADMIN`/`SUPPORT` a TOTP step (`aron_mfa` holds the 5-minute `mfa_token`,
  sealed, so even that token stays server-side). The BFF refuses a session for an MFA role that skipped the step.
- `src/proxy.ts` gates routes (login redirect, **real 403**), pages and handlers repeat the same policy
  (`src/lib/auth/roles.ts: accessFor`). Scope is shown from the server (`ScopeBadge`); the client never sends scope ids.
- An expiring access token is refreshed through `/api/bff/session/refresh` (pages) or inline (BFF handlers).

## Add an entity to the admin portal (one short file)

The CRUD generator (`src/components/admin/crud/`) turns **one metadata object** into the list page with filters, the
create page, the edit page with audit history, and the audited BFF writes. Every write demands a **reason** (10 to 500
characters) in the form and again in the BFF.

1. Make sure the contract has the operations: list (`GET`), create (`POST`), update (`PATCH` with `If-Match` and a
   `change_reason`). Missing pieces: file a request in `docs/requests/`.
2. Copy `src/app/admin/_entities/clusters.ts` to `src/app/admin/_entities/<thing>.ts` and edit it:
   ```ts
   export const things = defineEntity<Thing, ThingWrite, ThingPatch>({   // schema types from @/contract/types
     slug: "things", labelKey: "entity.things", singularKey: "entity.things.singular",
     api: { collection: "/v1/admin/things", item: "/v1/admin/things/{id}" /*, get: "/v1/admin/things/{id}" */ },
     auditEntity: "thing", idField: "id",
     fields: [{ name: "name", labelKey: "entity.field.name", kind: "text", required: true, maxLength: 120, column: true }, ...],
     filters: [{ param: "q", kind: "search", labelKey: "common.search" }],
     readRoles: ADMIN_PORTAL_ROLES, writeRoles: ["ADMIN", "SUPERADMIN"],
     reasonOnUpdate: "change_reason", reasonOnCreate: null /* or "change_reason" once the Write schema has it */,
   });
   ```
   Field `name`s are type-checked against the generated schemas; `mode` is `rw` (default), `create-only`,
   `update-only` or `readonly`; `kind` is `text`, `int`, `enum`, `timestamp`.
3. Add it to `ENTITIES` in `src/app/admin/_entities/registry.ts`. The menu entry and `/admin/<slug>` pages appear.
4. Add the labels (`entity.things`, `entity.things.singular`, `entity.field.*`) to **both** `messages-en.ts` and
   `messages-bn.ts`; `tests/i18n.test.ts` and `tests/crud-meta.test.ts` fail if one is missing.
5. Extend `mock/server.ts` if the entity should work in the Playwright smoke run.

Entities that need more than fields (custom screens such as config changes) are normal pages under `src/app/admin/`
that reuse the kit in `src/components/admin/kit/` (`Field`, `ReasonField`, `DataTable`, `FilterBar`).

## Layout

```
src/contract/        openapi.d.ts (generated) + types.ts (aliases the app imports)
src/lib/             i18n (bn/en catalogues, Bengali digits, Dhaka dates) · auth (roles, sealing, session) · api client · menu
src/components/      shell, scope badge, locale switch, login form · admin/ (kit, crud engine)
src/app/             (dashboards)/ · login/ · admin/ · api/bff/
mock/server.ts       contract-typed mock API for development and tests
```
