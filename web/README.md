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
| `npx tsx scripts/calm-glass-shots.ts <outDir> [baseUrl]` | screenshot matrix of login, dashboard, admin and config (1366x768 and 390x844, light and dark) against a running server and mock, for look-and-fix rounds |

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
src/styles/          tokens.css (Calm Glass variables: themes, tiers, density, type, motion) · glass.css (Tailwind mapping, glass utilities)
src/lib/             i18n (bn/en catalogues, Bengali digits, Dhaka dates) · auth (roles, sealing, session) · api client · menu · ui/theme (pre-paint stamp)
src/components/      shell, sidebar, theme menu, connection chip, scope badge, locale switch, login form · kit/ (buttons, chips, stat and hero cards, meter, banner, empty state, icons) · admin/ (kit, crud engine)
src/app/             (dashboards)/ · login/ · admin/ · api/bff/
mock/server.ts       contract-typed mock API for development and tests
```

## Design language: Calm Glass

The portal follows `docs/design/web-glass.md` (binding values in `docs/design/tokens.md`). The rules that matter when adding a page:

- **Colours only through tokens.** Use the Tailwind utilities of `src/styles/glass.css` (`text-primary`, `text-secondary`, `text-accent`
  for links and labels, `bg-accent-graphic` for icons and meters, `bg-success-container text-success-on-container` and the warning /
  danger / offline pairs for status, `bg-raised`, `border-input`, `rounded-chip|card|sheet`, `shadow-card|sheet|dialog`). No raw hex,
  no `slate-*`, no `bg-white`, no `dark:` on a colour in new code: the ESLint rule `colors/no-raw-colors`
  (`eslint-rules/no-raw-colors.mjs`) fails `npm run lint` for the Calm Glass files listed in `eslint.config.mjs`; add a page to
  that list when it is restyled. The overrides in `globals.css` keep the untouched pages on the tokens for one release.
- **Controls:** heights follow `--ctl-h` (`min-h-(--ctl-h)`, `size-(--ctl-h)` for round icon buttons): 40 px desk, 48 px on a
  coarse pointer (tokens.css `@media (pointer: coarse)`, asserted by `e2e/calm-glass.spec.ts`). Never a fixed `h-10`.
- **Money:** cards, hero figures and ranked bars print two decimals from integer milli-taka (`formatTakaDisplay`, half up);
  report cells keep three (`formatTaka`, docs/24 s12.3). The taka sign is 0.8 em with a 14 px floor in the Bengali face (`.taka`).
- **Surfaces:** `glass-card` (cards; never blurred), `glass-data` (tables, charts, key rows: near-opaque), `glass-bar` (top bar and
  stuck table headers: blur in tier A only), `glass-sheet` / `glass-panel` / `glass-dialog` / `glass-pop`.
- **Type roles:** `text-hero`, `text-display`, `text-numeral`, `text-title`, `text-heading`, `text-body`, `text-body-strong`,
  `text-caption`, `text-label`, `text-table`; Bangla line heights follow `:lang(bn)` automatically.
- **Kit first:** `src/components/kit/` (`Button`, `ButtonLink`, `StatusChip`, `StatCard`, `HeroCard`, `Meter`, `Skeleton`,
  `EmptyState`, `Banner`, `SegmentedLinks`, `RankedBars`, `Icon`) and `admin/kit` (`PageHeading`, `Card`, `Stat`, `DataTable`).
  Icons are inline SVG path data (`kit/icons.tsx`); there is no icon library.
- **Theme, tier and density** are stamped on `<html>` before first paint by the inline script in `app/layout.tsx` (nonce from the
  proxy): `data-theme` light | dark | sunlight ("High contrast"), `data-glass` full | lite | off, `data-density` comfortable |
  regular | compact, `data-nav` rail. The user's choice lives in `localStorage` (`aron.theme`, `aron.density`, `aron.glass`,
  `aron.nav`); the default is light, tier A where `backdrop-filter` exists and no reduced-transparency or more-contrast setting is on.
- **Tests:** `tests/css-contrast.test.ts` and `tests/contrast-tokens.test.ts` recompute the contrast ledger (raw and glare) from
  `tokens.css` for all three themes; `tests/theme-script.test.ts` runs the pre-paint script against a fake document.
