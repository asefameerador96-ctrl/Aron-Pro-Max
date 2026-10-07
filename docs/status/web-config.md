# web-config lane status

## Done (pushed to the integration branch, checker run)
Day 3: F-ADM-033, F-ADM-034, F-ADM-053, F-ADM-022, F-TSO-022. Day 4: F-ADM-013, 038, 040, 041, 042, 043, 044, 009, 048, 078, 027, 049, 030, 051, 050.
Built, checker running: F-ADM-039, N-045, N-046.

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

## Next three rows
F-ADM-023, F-ADM-029, F-ADM-042-done, then F-ADM-046 (master data CRUD, L), F-ADM-052, F-ADM-064, F-ADM-078-done, F-WEB-051, Day 6 rows.

## Device-pending
- N-045: scan the QR on a factory-reset phone (owner's phone).
- F-ADM-039 map: needs `NEXT_PUBLIC_MAPS_WEB_KEY` (GitHub secret) to show the real map; N-047 (web-dashboard) will replace the loader in `radius-map.tsx`.
