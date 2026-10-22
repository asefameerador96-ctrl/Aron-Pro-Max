# Lane brief: backend-admin

Session model: **Sonnet** (docs/29 s3). Owns: backend modules `config` and `masterdata`.

Read `docs/lanes/README.md` first.

- Scope: `/admin/*` CRUD for master data (users, scope, routes, assignments, outlets, classes, products, prices, product tree), configuration service (`GET/PUT /admin/config`, snapshot, delta, ack, what-if, blast radius, reach, requests, versions), flags and permissions, price publish and correct, outlet-kind bulk, web-entry endpoints (no Astha quantities: docs/27), data void and entry unlock, support and feedback endpoints, leave and visit plans, app update check.
- Spec: `docs/19` and `docs/24` s9 (config registry, scope chain, bounds, maker-checker), `docs/24` s14 and s14a decisions.
- Every write is audited (append-only audit log from backend-core: coordinate through the `platform` interfaces, do not edit that module); every admin write needs a reason and `If-Match` version.
- The web lanes build against your endpoints through the contract. Keep error codes exactly as the contract names them. Seed config keys: only the 172 of s9.5 (ruling R2).
- T1 rows: config resolution and snapshot/delta (F-API-037), data void, price-change rails, device OTP: Opus checker.

## Wave 4 (owner directive 2026-10-22, docs/34 s1, s2 D-TARGET-MIN-01, s4.2; session 4 started 09:31 UTC)

Admin APIs web-admin needs, slices first, each audited with reason and `If-Match`: route monthly target
upload (`preview` and `commit`, base units, validation report, idempotent re-upload; T1 Opus checker),
calendar/holidays CRUD and working days per month, house `is_active` and de-duplication (merge with reason),
outlet channel mandatory (GT, DCC, Astha, RCC, MT, HoReCa), SKU `base_per_pack` / `entry_unit_default` / pack
label in the product API and the bundle. The user-access grants API belongs to backend-authz (web-admin's
request shapes are the start). Requests arrive as `docs/requests/backend-admin-<name>.md` on
lane/web-admin.
