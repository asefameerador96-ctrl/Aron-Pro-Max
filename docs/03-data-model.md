# 03 — Data Model

Field names below match what the current API returns, so the Apsis dump maps on with little translation. Six layers: reference, outlet, field transactions, sync/log, targets, and aggregates (aggregates are in `docs/10`). The canonical DDL is in `db/schema.sql`; this doc is the explanation.

## Conventions

- Surrogate PKs are `bigint` identity unless noted. Natural codes (`outlet_code`, `zone_code`) are unique columns, not PKs.
- Device-originated rows carry a `client_uuid uuid UNIQUE` — the idempotency key for sync.
- `created_at timestamptz`, and on transactions a `business_date date` (Asia/Dhaka).
- Soft-delete with `status` where the business needs history; never hard-delete transactions.

## Reference and master data

| Table | Key | Holds | Links |
| --- | --- | --- | --- |
| `product_category` / `product_segment` / `product_brand` / `product_variant` / `sku` | id, parent_id | Product hierarchy; `sales_enable`, `sort`, image | each to parent |
| `sku_price` | sku_id + price_type + valid_from | Five price types: `outlet`, `cc`, `distributor`, `reporting`, `nto`; effective-dated | sku |
| `sales_plan` | zone_id + sku_id | Which SKUs are sellable in a zone | zone, sku |
| `wing` / `division` / `territory` / `house` / `zone` | id, code, parent_id | Geography; `zone` = distribution point (`dep_id`, codes from 5001) | up the chain |
| `route` | id (from 10001), code | Section name, `visit_days` (e.g. Sun/Tue/Thu or Daily), zone_id | zone |
| `route_assignment` | route_id + user_id + valid_from | SR (and SS) on a route; effective dates for alternate-day cover | route, user |
| `cluster` | id | `cluster_type` (e.g. Transit Hub), `cluster_name`, zone_id | zone |
| `channel` / `sub_channel` / `geo_classification` | id | Outlet classifications (GT/DCC/Astha/RCC/MT/HoReCa; Astha tiers; Urban/Rural…) | referenced by outlet |
| `app_user` | id | username (`sr334001`, `amo5001`), role, name, phone, status | assignments, scope |
| `user_scope` | user_id + node | The geography node(s) a user may see | user, geography |
| `device` | id | Bound phone per user, bound via TSO OTP; last-seen | user |
| `territory_geo_config` | territory_id | `radius_m` — the geofence radius for the territory | territory |
| `offer` / `promotion` | id | Promotion definitions, date ranges, rules (see `docs/10`) | sku/brand |

Roles enum: `sr`, `amo`, `tso`, `dmo`, `wm`, `top`, `admin`.

## Outlet book

| Table | Key | Holds | Links |
| --- | --- | --- | --- |
| `outlet` | id, `outlet_code` | name, owner, contact, address, NID, TIN, trade licence, `latitude`, `longitude`, channel, sub_channel, geo_classification, status, timestamps | route, cluster, zone |
| `outlet_change_request` | id | type (`new`/`close`/`info`), proposed JSON, `requested_by` (SR), `verified_by` (AMO), `approved_by` (web), status | outlet, user |
| `outlet_photo` | id | blob URL, GPS, captured_at, purpose (`capture`/`base_update`/`info`) | outlet |

PII note: NID, TIN, trade licence and phone are sensitive. Expose only to roles that need them (see `docs/09`).

## Field transactions (created offline in the apps)

| Table | Key | Holds | Links |
| --- | --- | --- | --- |
| `attendance` | user_id + business_date | check-in/out time + GPS | user |
| `stock_issue` | user_id + business_date + sku_id | issued and returned quantities | user, sku |
| `visit` | `client_uuid` | user, outlet, started_at, GPS, `geo_validated`, `photo_validated`, `force_reason`, `is_zero_sale`, business_date | outlet, user |
| `memo` | `client_uuid` | visit_id, printed_at, gross, discount, net, `is_credit`, `paid_amount`, `due_amount`, `edit_reason`, business_date | visit, outlet |
| `memo_line` | memo_id + sku_id | quantity, unit_price, discount, offer_id | memo, sku |
| `qc_entry` | visit_id + sku_id | production-fault and transport-fault quantities | visit, sku |
| `survey_response` | visit_id + question_id | POSM answers + photo | visit |
| `drp_collection` | visit_id | empty packs/slides collected, offer given | visit |
| `due_collection` | `client_uuid` | amount collected against a prior credit memo | outlet, memo |
| `loyalty_ledger` | id | points earned/spent, reason, time (Diamond League) | outlet |
| `redemption` | `client_uuid` | gift or cash chosen, points deducted, photo | outlet |
| `gift_photo` | id | Astha / campaign gift hand-over photo | outlet |
| `task` | id | type (OOS/General/Irregular Visit), outlet, due date, status, resolved_at | user, outlet |
| `call_assessment` | `client_uuid` | AMO joint-call star ratings; TSO retailer questionnaire | user, outlet |
| `distribution_check` | `client_uuid` | AMO control-call: per-brand present/OOS, POSM | outlet, brand |

`visit` is the parent of most of a call's records (survey, DRP, memo, QC), so its `client_uuid` is created first and referenced by the rest in the same offline batch.

## Sync and logs

| Table | Key | Holds | Feeds |
| --- | --- | --- | --- |
| `sync_batch` | id | device, user, uploaded_at, per-type counts | device-vs-server reconciliation |
| `route_log` | route_id + business_date | download/upload first & last time, counts, source | login % / submit % |
| `final_submit` | zone_id + business_date | who submitted, when (one per zone/day) | final-submit status |
| `activity_log` | id | user, screen/action, time | audit |

## Targets

| Table | Key | Holds | Links |
| --- | --- | --- | --- |
| `target` | scope + product + month | STD target (can be fractional) and memo target, by route or zone, per category/brand/SKU | route/zone, product |
| `target_revision` | id | requested change, config, date range, approval level, approver, final flag | target, user |

## Relationships, in one line

`outlet` belongs to a `route` → `zone` → `house`/`territory` → `division` → `wing`; `visit` belongs to an `outlet` + `app_user`; `memo` belongs to a `visit`; `target` is scoped to a route or zone × a product level; aggregates (`docs/10`) are keyed by `business_date` × scope × product.

## Enums (create as Postgres enums or lookup tables)

- `role`: sr, amo, tso, dmo, wm, top, admin
- `price_type`: outlet, cc, distributor, reporting, nto
- `channel`: GT, DCC, Astha, RCC, MT, HoReCa
- `geo_class`: Hill, Urban, SemiUrban, Rural
- `outlet_request_type`: new, close, info
- `request_status`: pending, verified, approved, rejected
- `task_type`: oos, general, irregular_visit
- `force_reason`: internet_problem, location_change
- `day_state`: not_started, logged_in, in_field, synced, sales_submitted, final_submitted
