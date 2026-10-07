# web-config lane status

## Done (pushed to the integration branch, independent checker run, defects fixed)
Day 3: F-ADM-033, 034, 053, 022, F-TSO-022. Day 4: F-ADM-013, 038, 040, 041, 042, 043, 044, 009, 048, 078, 027, 049, 030, 051, 050, 039, N-045, N-046.
Day 5: F-ADM-023, 029, 052, 064, 078, F-WEB-051. Day 6: F-ADM-024, 025, 036, 043, 051, 055, 057, 060, 067, F-WEB-050, 052, 060, 063.
F-ADM-046: the master-data hub, the generated entities and the code-list pages come from the web-admin lane; I added the calendar link (`app/admin/master-links.ts`). Prices and Sales Plan belong to web-admin's open rows (F-ADM-005, F-ADM-006).

## Overlaps with other lanes (flagged to the lead)
- web-admin also built a calendar CRUD, a code-list editor (`/admin/code-lists/[key]`) and the hub. Mine: `/admin/calendar` (weekend days + holidays), `/admin/code-lists` (reason tables) and `/admin/qc-faults`. Both work; pick one editor later.
- The 04:34 rebalance moved 12 of my rows to web-dashboard after they were built; web-dashboard was told not to rebuild them.

## How it is built (read before touching)
- All admin writes go through one whitelist: `web/src/lib/admin/ops.ts` (method, path, roles, reason member, If-Match, forbidden body values) and the BFF handler `lib/admin/op-server.ts` (`POST /api/bff/admin-op`). Per-operation consistency rules: `lib/admin/op-rules.ts`. The browser never names a path.
- Forms: `components/admin/kit/op-form.tsx` (typed fields, reason), `op-inline.tsx` (row action with reason), `config/config-set-form.tsx` (one config key at one scope, typed against registry bounds, `lib/admin/config.ts`).
- Pages are thin server components; views are pure components rendered in tests with `tests/helpers/render.tsx`; API stubs in tests come from `tests/helpers/harness.ts` (mock plus per-test stubs).
- Strings: `lib/i18n/messages-config.ts` holds [English, Bangla] pairs (parity by construction).
- Menu: `app/admin/config-menu.ts`.

## Decisions
- Weekend days, release minimum/blocked versions, app-block lists and every other setting use `config.change` (registry decides applied, scheduled or pending approval).
- P5 "schedule": the contract has no schedule decision; a scheduled change is made at request time with an effective date (future-dated keys). Documented on the page.
- Release publish is SUPERADMIN only (docs/24 s8.5); the proxy refuses `status: published` for ADMIN.
- Sync health (P13) reads `/v1/dashboards/sync-health`, the same endpoint as the ops dashboard; F-WEB-045 does not exist yet in `web/(dashboards)`.
- ANALYST can read audit per docs/24 s8.5 but stays outside the admin portal gate (earlier lane's decision); revisit with the lead.
- Adoption by version aggregates up to 5,000 devices (10 pages of 500); a fleet-size account needs an API aggregate (request when the final account is sized).
- Commits end with the neutral `Co-Authored-By: Claude` trailer.

## Requests filed
- `docs/requests/web-config-device-otp-columns.md` (employee code and zone name on DeviceOtp).

## Later changes (2026-10-07)
- Design direction (docs/32): status colours, radii, pill buttons and field sizes use the Calm Glass tokens (`var(--success|warning|danger)`, `--radius-*`), the geofence page has a glass side panel over the map, `app/admin/loading.tsx` and `error.tsx` give every admin page a loading and an error state, table cells wrap long text. docs/design v1 replaces token values only.
- Maps: the radius map loads through `requestMaps` in `components/map-panel.tsx` (BFF cap, no client key); the N-047 checker test is un-skipped and passes.
- Dedupe with web-admin: docs/requests/web-config-web-admin-dedupe.md (web-admin's code-list editor stays, my editor and `/admin/qc-faults` deleted, my calendar stays).
- F-ADM-036 dues adjustment: DEPENDENCY-WAIT. The page, proxy and tests are done against the contract; the outlet balance changes only when backend-core ships the dues ledger (F-SYS-060) and the dues-adjustment routes.

## Open items (not web-config's to fix)
- F-ADM-064: the menu follows the matrix for ADMIN and SUPERADMIN only; other roles need their own menus from the API (docs/requests/web-config-menu-matrix.md).
- F-ADM-036 dues: no backend route or ledger exists yet.
- Day control lists read report column names (docs/requests/web-config-day-control-columns.md).
- Web Entry class split is built (sub-channel ids; names need docs/requests/web-config-entry-class-labels.md).
- No ops dashboard (F-WEB-045) existed when P13 was built; both read `/v1/dashboards/sync-health`.

## Device-pending
- N-045: scan the QR on a factory-reset phone (owner's phone).
- F-ADM-039 map: needs `NEXT_PUBLIC_MAPS_WEB_KEY` (GitHub secret) to show the real map; N-047 (web-dashboard) will replace the loader in `radius-map.tsx`.
