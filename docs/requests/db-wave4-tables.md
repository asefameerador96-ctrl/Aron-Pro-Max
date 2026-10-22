# db → backend-authz, backend-reports, web-admin, mdm, android-units: Wave 4 tables (docs/34)

Status: **built on lane/db** (V0087–V0101), 2026-10-22. Each consumer: read your section, reply in this file or in
your own `docs/requests/db-<lane>-*.md` if a shape is wrong; db fixes forward with a new migration.

## backend-authz (docs/34 s1)

- `app.role_def` has `NATIONAL` (ordinal 11) and `BRAND` (12), both web-only (V0087). Role-scoped defaults of the web
  roles (`cfg.auth.password_min_len` 12, `cfg.auth.access_ttl_min` 15) exist for both. `cfg.web.menu_by_role` default:
  NATIONAL = TOP's menu; BRAND = dashboard, the report pages except `by_route_geo_capture`, change_password.
- **TOP is not retired yet** and no user is re-roled (expand only), because the contract `Role` enum, the Kotlin
  `Role`, `ConfigPermissions` (it lists every `role_def` row in `GET /v1/admin/permissions`) and the tests still name
  TOP and do not know NATIONAL/BRAND. **Ask:** when the contract and `Role` carry NATIONAL and BRAND, file
  `docs/requests/db-backend-authz-roles.md` with the contract step you want: (a) `UPDATE app.app_user SET role =
  'NATIONAL' WHERE role = 'TOP'` (+ their role-scoped cfg values), (b) remove TOP from the CHECK and `role_def`
  (refused while any row, cfg value or menu entry names it), (c) anything else (grants in `role_grant_map`).
  Note: `GET /v1/admin/permissions` now returns two more roles (NATIONAL, BRAND); if `RolePermissions.role` is the
  contract enum, conformance tests will flag it — the backend suite was green on this tree (see docs/status/db.md).
- `app.portal_module` (11 codes: masterdata, routes, outlets, products, prices, users, targets, credentials, mdm,
  reports, config) and `app.user_module_grant (user_id, module, access 'r'|'w', granted_by, granted_at)`, PK
  (user_id, module); only ADMIN users (trigger, 23514); revoke = DELETE (api_rw has DELETE); auth_rw can SELECT both
  for the `modules` claim (V0089). A new module = a migration on your request.
- `app.user_brand_scope (user_id, brand_id, granted_by, granted_at)`, PK (user_id, brand_id); only BRAND users and
  only `product_node` level `brand` (trigger, 23514); revoke = DELETE; auth_rw SELECT for the `brands` claim (V0090).
  A BRAND user without rows sees no product data (default-deny is your rule to enforce).
- Re-roling a user away from ADMIN/BRAND leaves its grant rows: delete them in the same transaction (the claims are
  only built for ADMIN/BRAND anyway).

## backend-reports (docs/34 s2, dashboard brief s6)

No shapes had been filed (`docs/requests/db-backend-reports-*.md`, lane/backend-reports) when this was built, so the
tables follow the brief. Your projector (Aggregator) fills every new column; nothing is filled by a trigger.

| Need (brief) | Where |
|---|---|
| House filter, active houses once | `app.house.is_active` (generated from `status`), one active house per (territory, normalised name) (unique index); `dw.dim_geo.house_id/house_name` (+ `dim_geo_version`) **for you to fill** from `app.zone.house_id` (V0091) |
| CPR / Login on normal routes only | `app.route.route_type` normal / manual / dummy_br / dummy_institutional; `dw.dim_geo.route_type` (+ version) **to fill** (V0092) |
| Daily target, "i" pop-up | `app.route_monthly_target` (live row per month × route × SKU, `qty_base`), `dw.v_route_monthly_target` (category, working_days, `daily_qty_base` numeric, unrounded) (V0094/V0095) |
| Working days (Fridays, holidays) | `dw.v_calendar` (is_friday, is_holiday, holiday_name, is_makeup_day, is_working_day), `dw.v_calendar_month` (national holidays only; scoped holidays stay in `app.calendar_holiday`) (V0095) |
| Total Calls, Geo Validation | `dw.fact_visit.completed` and `within_fence` are **generated** (no projector change); `agg_daily_route.completed_calls` **to fill**; `geo_valid_visits` as today |
| Route map time card, distance, FF Geo pin | `agg_daily_route.first_sale_at`, `last_sale_at`, `distance_covered_m` (D-DIST-01), `last_position_at/lat/lng` **to fill** (V0096). The coordinates are `[pii:personal]`: never put them in a `dw.v_*` view |
| Sale Position page | `dw.fact_visit.server_distance_m`, `fix_lat`, `fix_lng`, `force_photo_uuid` **to copy** from `app.visit` (V0098) |
| By Channel STD (cigarette), outlet pop-up sales | `dw.agg_daily_outlet_category (business_date, outlet_id, category_code, route_id, sold_qty_base, memo_count, gross_mtk)` **to fill** (V0096) |
| Breakdown by variant | `dw.agg_daily_route_variant` (memo once per variant) **to fill**; brand and segment exist |
| "last updated hh:mm" | `dw.summary_watermark (summary, last_event_id, refreshed_at)` **to upsert** per summary |
| Final Submit, second submits ignored | `dw.v_final_submit_log` (first submit per zone-day, submits, reopened_now) over `app.final_submit` (V0099) |
| By Channel successful calls | already: `dw.agg_daily_outlet` × `dw.dim_outlet.channel`; `outlet.channel` is NOT NULL with the six channels since V0004 (no backfill needed) |

If you need a different grain (e.g. channel pre-aggregated per route), file the shape and db adds it.

## web-admin

- Targets upload: `app.route_monthly_target_upload (upload_uuid UNIQUE, month, source admin|excel, file_name,
  row_count, result, note, created_by)` and `app.route_monthly_target`. A re-upload of the same month × route × SKU:
  in one transaction set `superseded_by_upload_id` on the live row, then insert the new row (unique live index).
  Rows are never updated otherwise or deleted (42501); the target row's month must equal its upload's month (23514).
- Calendar page: `app.calendar_holiday` as today (kind holiday / makeup_day / emergency_off); `dw.v_calendar*` read it.
- House active flag: write `status` (is_active is generated, an UPDATE of is_active is refused, 428C9).
- SKU pack size fields: `app.sku.base_per_pack`, `entry_unit_default` (V0101).

## mdm (docs/34 s5)

`app.device_command` (V0100): `command_uuid` PK (idempotent create), `type` lock / unlock / message / locate / reboot /
factory_reset, `params` object, `status` flow pending_approval → queued | cancelled; queued → sent | cancelled |
expired; sent → acked | failed | expired; acked → done | failed (trigger, 23514); factory_reset starts
`pending_approval` and needs `approved_by` ≠ `requested_by` before `queued` (CHECK); cancelled ⇔ `cancelled_by`;
request columns and an approval are written once (42501); `expires_at` ≤ 30 days; `sig` (ES256 base64url) for the
DPC; `result` is `[pii:personal]` (a locate answer); no DELETE. Open partial index `(device_id) WHERE status IN
('queued','sent')` for the sync pull. support_ro can read it. Policies, schedules and compliance tables: file the
shapes you need.

## android-units (docs/34 s4.2)

`app.sku.base_per_pack`: cigarettes 20/10, bidi 12/25 (per SKU), lighters 25 (box), matches 1; `entry_unit_default`
pack / pack / pack / dozen (V0101 on existing rows; the seed catalogue matches). `cfg.sale.qty_entry_unit` default
`{"cigarette":"pack","bidi":"pack","lighter":"pack","match":"dozen"}`; new key `cfg.sale.allow_loose_base_units`
(bool, default false, global, both, C2). Prices stay per base unit (`sku_price.per_base_qty` 1): the packet price is
`amount_mtk × base_per_pack`, exact in milli-taka.
