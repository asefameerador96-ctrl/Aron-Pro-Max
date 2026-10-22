# Request: outlet import with the channel mandatory — web-admin wave 4

Lane: web-admin. For: backend-admin (+ db: `outlet.channel` NOT NULL, docs/34 s2). Binding spec: `docs/34` s2 ("Astha channel vs Astha programme").

There is no outlet import in the contract. The page `/admin/outlet-import` reads an Excel sheet in the browser (like the route targets), reports problems per sheet row,
previews with the server and commits. Built against the mock (`web/mock/custom.ts`); shapes PROVISIONAL (`web/src/lib/admin/outlet-import.ts`).
`web/tests/requests/outlet-import-contract.test.ts` is an `it.fails` that turns red when the contract gains the operation.

## `POST /v1/admin/outlets/import`  (ADMIN, SUPERADMIN; module grant `outlets`)
Body: `{ batch_uuid: Uuid, rows: [OutletImportRow] (1..5000), dry_run: boolean, change_reason?: ChangeReason }`, with
`OutletImportRow = { code: string|null, name, owner_name, zone_code, cluster (cluster NAME inside the zone), channel: GT|DCC|Astha|RCC|MT|HoReCa, outlet_kind: retail|wholesale, route_code: string|null, lat: number|null, lng: number|null, contact_number: string|null, address: string|null }`.
- `channel` is **required on every row**: missing or outside the six values is 400 `required` / `invalid` at `/rows/<i>/channel` (also on `dry_run`).
- `dry_run: true`: writes nothing, no reason needed. 200 `{ batch_uuid, rows, creates, updates, by_channel: {GT: n, ...}, errors[{row (1-based in rows), column, code}] }`.
  Codes: `unknown_channel`, `unknown_zone` (unknown or inactive zone code), `unknown_cluster` (no cluster of that name in the zone), `unknown_route` (unknown, or route of another zone), `code_taken` (outlet code belongs to an outlet of another zone), `required`, `invalid`.
- `dry_run: false`: `change_reason` required (10..500); any error refuses the whole batch (400, pointers `/rows/<i>/<column>`); success 200 `{ batch_uuid, rows, replayed }`; idempotent by `batch_uuid` (same content replays, other content 409);
  a row whose `code` matches an outlet of the same zone UPDATES it (never clears its status), otherwise creates one (code generated when null). Audited (entity `outlet_import`).
