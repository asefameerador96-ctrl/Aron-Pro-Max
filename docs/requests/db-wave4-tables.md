# db → backend-authz, backend-reports, web-admin, mdm, android-units: Wave 4 tables (docs/34)

Status: **built on lane/db** (V0087–V0103), 2026-10-22, to the requests of backend-authz and backend-reports. Each consumer: read your section, reply in this file or in
your own `docs/requests/db-<lane>-*.md` if a shape is wrong; db fixes forward with a new migration.

## backend-authz (docs/34 s1): your `db-backend-authz-roles.md` (e449edb7), built as written

| Your migration | db | Change from your text |
|---|---|---|
| A (A1–A5) | V0087, V0088 validates | `role_def_role_check` and `app_user_no_top` added NOT VALID, validated in V0088 (squawk); no `IF EXISTS` (Flyway runs once) |
| B `user_module_grant` | V0089 | + index on `granted_by`; api_rw DELETE, auth_rw SELECT grants; dictionary comments |
| C `user_brand_scope` | V0090 | + index on `granted_by`; the same grants |
| D `user_scope` house | V0091, V0092 validates | CHECK NOT VALID then validated |

Also: the seed (`01_geography_people.sql`) gives `admin1001` w on the 15 modules when it has no grant, because a test
database is migrated before it is seeded (V0089's seed then finds no ADMIN). Your `pending/authz-wave4.sql` fixture can
go once this is on INT. **Promote lane/db with or after lane/backend-authz**: V0087 refuses TOP users
(`app_user_no_top`), and INT's tests (e.g. `AuthzAuditTest` inserting `qatop1001` as TOP) need your branch.

## backend-reports (docs/34 s2): your `db-backend-reports-dashboard-summaries.md` (ec0976ed)

| Your item | db | Note |
|---|---|---|
| A1 route class | V0094/V0095 `app.route.route_class`, `dw.dim_geo.route_class` (+ `dim_geo_version`) | as asked |
| A2 house | V0093: `app.house.is_active` (generated from status), one-off dedupe (survivor: active, most zones, lowest id; zones moved; duplicates inactive), unique index on (territory_id, lower(btrim(name))) WHERE active; `dw.dim_geo.house_id/house_name` (+ version) for you to fill | |
| A3 channel | `outlet.channel` NOT NULL with the six channels since V0004; every seed outlet has one | nothing to do |
| A4 product codes | V0098 `dw.dim_product.segment_code/brand_code/variant_code` (+ version), from `product_node.code` (nullable) | |
| A5 working days | V0097 `dw.v_working_day (day, working)` over `dw.v_calendar` (national: Fridays and global holiday/emergency_off off, make-up on); also `dw.v_calendar_month` | |
| **A6 targets** | **not `app.target`**: the lane brief and D-TARGET-MIN-01 name `app.route_monthly_target` (V0096, base units, one live row per month × route × SKU, history by `superseded_by_upload_id`) and `dw.v_route_monthly_target` (category, base unit, working_days, `daily_qty_base` unrounded). `app.target` stays the empty docs/27 hook. No code writes targets yet; web-admin's upload is to write the new table (section below) | please read the view |
| B1–B5, B7 | V0099 as written | B1's index (business_date, route_id) not created: a prefix of the PK (IndexHygieneTest) |
| B6 | V0100 as written | + `server_distance_m` (copy if wanted) and `within_fence` (generated; never in your insert list) |
| final submit | V0101 `dw.v_final_submit_log` (first submit per zone-day, submits, reopened_now) | optional for you |

`route_last_position.lat/lng` and `fact_visit.fix_lat/lng` are `[pii:personal]`: never in a `dw.v_*` view.

## web-admin

- Targets upload: `app.route_monthly_target_upload (upload_uuid UNIQUE, month, source admin|excel, file_name,
  row_count, result, note, created_by)` and `app.route_monthly_target`. A re-upload of the same month × route × SKU:
  in one transaction set `superseded_by_upload_id` on the live row (only a later upload of the same month, else 23514), then insert the new row (unique live index).
  Rows are never updated otherwise or deleted (42501); the target row's month must equal its upload's month (23514).
- Calendar page: `app.calendar_holiday` as today (kind holiday / makeup_day / emergency_off); `dw.v_calendar*` read it.
- House active flag: write `status` (is_active is generated, an UPDATE of is_active is refused, 428C9).
- SKU pack size fields: `app.sku.base_per_pack`, `entry_unit_default` (V0103).

## mdm (docs/34 s5)

`app.device_command` (V0102): `command_uuid` PK (idempotent create), `type` lock / unlock / message / locate / reboot /
factory_reset, `params` object. A new row carries no approval, delivery or outcome. Status flow (trigger, 23514):
pending_approval → queued | cancelled | expired; queued → sent | cancelled | expired; sent → acked | failed | expired;
acked → done | failed. factory_reset starts `pending_approval`; the approval (`approved_by`, `approved_at`) is written
only on the step to `queued`, before `expires_at`, by a SUPERADMIN or an ADMIN with `mdm` = `w` in
`app.user_module_grant` (else 42501), never the requester (CHECK 23514). cancelled ⇔ `cancelled_by`. Request,
approval and delivery columns (`sig`, `sent_at`/`sent_via`, `acked_at`, `completed_at`) are written once (42501);
`expires_at` ≤ 30 days; **expiry is the API's job** (worker_rw has no UPDATE on the table). `result` is
`[pii:personal]` (a locate answer); support_ro reads every column except `result`. No DELETE. Open partial index
`(device_id) WHERE status IN ('queued','sent')` for the sync pull. Policies, schedules and compliance tables: file the
shapes you need.

## android-units (docs/34 s4.2)

`app.sku.base_per_pack`: cigarettes 20/10, bidi 12/25 (per SKU), lighters 25 (box), matches 1; `entry_unit_default`
pack / pack / pack / dozen (V0103 on existing rows; the seed catalogue matches). `cfg.sale.qty_entry_unit` default
`{"cigarette":"pack","bidi":"pack","lighter":"pack","match":"dozen"}`; new key `cfg.sale.allow_loose_base_units`
(bool, default false, global, both, C2). Prices stay per base unit (`sku_price.per_base_qty` 1): the packet price is
`amount_mtk × base_per_pack`, exact in milli-taka.
