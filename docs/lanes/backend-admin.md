# Lane brief: backend-admin

Session model: **Sonnet** (docs/29 s3). Owns: backend modules `config` and `masterdata`.

Read `docs/lanes/README.md` first.

- Scope: `/admin/*` CRUD for master data (users, scope, routes, assignments, outlets, classes, products, prices, product tree), configuration service (`GET/PUT /admin/config`, snapshot, delta, ack, what-if, blast radius, reach, requests, versions), flags and permissions, price publish and correct, outlet-kind bulk, web-entry endpoints (no Astha quantities: docs/27), data void and entry unlock, support and feedback endpoints, leave and visit plans, app update check.
- Spec: `docs/19` and `docs/24` s9 (config registry, scope chain, bounds, maker-checker), `docs/24` s14 and s14a decisions.
- Every write is audited (append-only audit log from backend-core: coordinate through the `platform` interfaces, do not edit that module); every admin write needs a reason and `If-Match` version.
- The web lanes build against your endpoints through the contract. Keep error codes exactly as the contract names them. Seed config keys: only the 172 of s9.5 (ruling R2).
- T1 rows: config resolution and snapshot/delta (F-API-037), data void, price-change rails, device OTP: Opus checker.
