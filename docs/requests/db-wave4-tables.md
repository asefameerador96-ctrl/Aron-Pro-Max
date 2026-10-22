# db → backend-authz, backend-reports, web-admin, mdm, android-units: Wave 4 tables (docs/34)

Status: **built on lane/db** (V0087–V0104), 2026-10-22, to the requests of backend-authz, backend-reports, backend-admin and mdm. Each consumer: read your section, reply in this file or in
your own `docs/requests/db-<lane>-*.md` if a shape is wrong; db fixes forward with a new migration.

## backend-authz (docs/34 s1): your `db-backend-authz-roles.md` (e449edb7), built as written

| Your migration | db | Change from your text |
|---|---|---|
| A (A1–A4 except the user re-role) | V0087, V0088 validates | `role_def_role_check` NOT VALID, validated in V0088 (squawk); no `IF EXISTS` (Flyway runs once). **Shipped as V0105/V0106** (session 8, after backend-authz 8ed44597 reached INT): `UPDATE app_user TOP → NATIONAL` (scope_version + 1) and A5 `app_user_no_top` (validated in V0106) |
| B `user_module_grant` | V0089 | + index on `granted_by`; api_rw DELETE, auth_rw SELECT grants; dictionary comments |
| C `user_brand_scope` | V0090 | + index on `granted_by`; the same grants |
| D `user_scope` house | V0091, V0092 validates | CHECK NOT VALID then validated |

Also: the seed (`01_geography_people.sql`) gives `admin1001` w on the 15 modules when it has no grant, because a test
database is migrated before it is seeded (V0089's seed then finds no ADMIN). Your `pending/authz-wave4.sql` fixture can
go once this is on INT. On the combined tree (lane/db + your e449edb7 + backend-reports) masterdata tests also answered 403 for admins the
tests create without grants (RoutesInReachTest, OutletRequestsTest, AdminContentTest): once your probe sees
`user_module_grant`, test-created ADMIN users need grants in those fixtures. Your `PendingAuthzSchemaTest` expects the
TOP re-role, which is now held.

## backend-reports (docs/34 s2): your `db-backend-reports-dashboard-summaries.md` (ec0976ed)

| Your item | db | Note |
|---|---|---|
| A1 route class | V0094/V0095 `app.route.route_class`, `dw.dim_geo.route_class` (+ `dim_geo_version`) | as asked |
| A2 house | V0093: `app.house.is_active` (generated from status), one-off dedupe (survivor: active, most zones, lowest id; zones moved; duplicates inactive), no unique index (lead ruling: duplicates are real; the admin merge endpoint de-duplicates, the filter lists DISTINCT active houses); `dw.dim_geo.house_id/house_name` (+ version) for you to fill | |
| A3 channel | `outlet.channel` NOT NULL with the six channels since V0004; every seed outlet has one | nothing to do |
| A4 product codes | V0098 `dw.dim_product.segment_code/brand_code/variant_code` (+ version), from `product_node.code` (nullable) | |
| A5 working days | V0097 `dw.v_working_day (day, working)` over `dw.v_calendar` (national: Fridays and global holiday/emergency_off off, make-up on); also `dw.v_calendar_month` | |
| **A6 targets** | **not `app.target`**: `app.route_monthly_target (route_id, sku_id, month, qty_base, batch_uuid, …)` PK (route_id, sku_id, month), backend-admin's DDL (V0096), and `dw.v_route_monthly_target` (category, base unit, working_days, `daily_qty_base` unrounded). `app.target` stays the empty docs/27 hook | please read the view |
| B1–B5, B7 | V0099 as written | B1's index (business_date, route_id) not created: a prefix of the PK (IndexHygieneTest) |
| B6 | V0100 as written | + `server_distance_m` (copy if wanted) and `within_fence` (generated; never in your insert list) |
| final submit | V0101 `dw.v_final_submit_log` (first submit per zone-day, submits, reopened_now) | optional for you |

`route_last_position.lat/lng` and `fact_visit.fix_lat/lng` are `[pii:personal]`: never in a `dw.v_*` view.

## web-admin and backend-admin

- Targets: backend-admin's `docs/requests/backend-admin-route-target-tables.md` DDL as written (V0096):
  `app.route_monthly_target` PK (route_id, sku_id, month), rewritten in place by a later upload; `app.route_target_batch`
  the idempotency record. backend-admin can delete `proposed_route_monthly_target.sql` once this is on INT.
- `route.kind` stays `sr`/`amo` (V0002; every bundle and report reads it). The new class is a separate column,
  `app.route.route_class` normal / manual / dummy_br / dummy_institutional (V0094). So the target API's rule "kind sr,
  normal or manual" becomes `kind = 'sr' AND route_class IN ('normal', 'manual')` (refuse dummy routes).
- Calendar page: `app.calendar_holiday` as today (kind holiday / makeup_day / emergency_off); `dw.v_calendar*` and
  `dw.v_working_day` read it.
- House active flag: write `status` (is_active is generated; an UPDATE of is_active is refused, 428C9).
- SKU pack size fields: `app.sku.base_per_pack`, `entry_unit_default` (V0104).

## mdm (docs/34 s5)

Your `docs/requests/db-mdm-device-command.md` DDL as written (V0102, validation of the app.device constraints in V0103;
the FK, version CHECK and pair CHECK on the existing app.device are added NOT VALID, squawk). Added by db: a guard
trigger (a new command carries no approval, delivery or result, a factory reset starts `pending_approval` and every
other type `queued`; on the approval step `approved_at` is set to now() by the server); support_ro reads commands
without `result`; `dw.v_device_command` (no sig, no result). No DELETE for anyone. Answer: `db-mdm-device-command-answer.md`.

## android-units (docs/34 s4.2)

`app.sku.base_per_pack`: cigarettes 20/10, bidi 12/25 (per SKU), lighters 25 (box), matches 1; `entry_unit_default`
pack / pack / pack / dozen (V0104 on existing rows; the seed catalogue matches). `cfg.sale.qty_entry_unit` default
`{"cigarette":"pack","bidi":"pack","lighter":"pack","match":"dozen"}`; new key `cfg.sale.allow_loose_base_units`
(bool, default false, global, both, C2). Prices stay per base unit (`sku_price.per_base_qty` 1): the packet price is
`amount_mtk × base_per_pack`, exact in milli-taka.
