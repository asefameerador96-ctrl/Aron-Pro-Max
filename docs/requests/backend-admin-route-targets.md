# Request: route monthly targets (D-TARGET-MIN-01) — web-admin wave 4

Lane: web-admin. For: backend-admin (+ db: table `route_monthly_target`, docs/34 s7). Binding spec: `docs/34` s2.

The page `/admin/route-targets` reads an Excel sheet (route_code, sku_code, month YYYY-MM, quantity_base) **in the browser**, reports shape problems per sheet row,
then sends JSON, so the backend needs no Excel parser. Built and tested against the mock in `web/mock/custom.ts`; the shapes are PROVISIONAL
(`web/src/lib/admin/route-targets.ts`). `web/tests/requests/route-targets-contract.test.ts` is an `it.fails` that turns red when the contract carries them.

## `PUT /v1/admin/route-targets`  (ADMIN, SUPERADMIN write; SUPPORT read via GET; module grant `targets`)
Body: `{ batch_uuid: Uuid, rows: [{route_code, sku_code, month, qty_base}] (1..20000), dry_run: boolean, change_reason?: ChangeReason }`.
- `dry_run: true`: validates, writes nothing, no reason needed. 200 `{ batch_uuid, rows, routes, skus, months[], total_qty_base, replaces, errors[{row, column: route|sku|month|quantity, code}] }`;
  `row` is the 1-based position in `rows`; `replaces` = existing (route, SKU, month) targets the batch would overwrite. Codes: `unknown_route` (unknown or retired), `not_selling_route`
  (route.kind other than `normal`/`manual` has no target), `unknown_sku` (unknown or inactive), `past_month` (month before the current Dhaka month is never rewritten).
- `dry_run: false`: `change_reason` required (10..500); any error refuses the whole batch with 400 `ERR_VALIDATION` (`errors[].pointer` `/rows/<i>`); success 200 `{ batch_uuid, rows, replayed }`.
  Idempotent by `batch_uuid` (same content replays, other content 409). Upserts by (route, SKU, month); quantities are BASE units (sticks, pieces, dozens), integers 0..1e9. Audited (entity `route_target`).

## `GET /v1/admin/route-targets?month=YYYY-MM&limit&cursor`
`{ items: [{route_code, route_name, sku_code, month, qty_base}], next_cursor }`, sorted by route then SKU.

The daily target (monthly quantity over the working days of the calendar, Fridays and holidays excluded) is derived by the dashboard backend, not edited here.
