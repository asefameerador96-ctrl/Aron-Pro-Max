# Apsis database dump: inventory and topline mapping (2026-11-13)

Source: the owner's sheet "Wings_SFA_DB_Sharing_Status_AKTC.xlsx" (26 tables, one row each) and a screenshot of the dump folder
(`ARON_Half_Yearly_Data`, 26 `.sql` files, 52.5 GB in total; the largest is `live_geo_data_by_outlets.sql` at 31.1 GB).
The source system is Azure Database for MySQL; ours is PostgreSQL. The owner's plan (D-OWNER-APSIS-DUMP-01): the dump is migrated
into the FINAL Azure account when it arrives; for now this list is for mapping only. Nothing of the dump is in the repository or in
the test account, and the lead has not received the dump files. Counts are the vendor's.

## Tables
| # | Apsis table | Rows | From | To | Kind | What it is | Maps to (ours) |
|---|---|---|---|---|---|---|---|
| 1 | trans_sales_by_outlet | 122,210,777 | 2025-12-07 | 2026-09-30 | transactional | by outlet, by SKU, by day: sales and zero sales | app.memo + app.memo_line (history), dw sales facts; zero sales = visit with is_zero_sale |
| 2 | live_geo_data_by_outlets | 89,451,038 | 2025-12-08 | 2026-09-30 | transactional | by outlet, by day: geo data | app.visit + app.geo_fix (history); dw.fact_visit |
| 3 | trans_by_outlet_promotion_data | 38,476,448 | 2025-11-25 | 2026-09-30 | transactional | by outlet, by day, by promotion | deferred (docs/27); archive table for history only |
| 4 | master_ff_attendance_records | 3,264,185 | 2025-11-10 | 2026-09-30 | transactional | field force attendance | app attendance / route_day check-in history |
| 5 | master_outlet_classifications | 1,783,319 | 2025-12-07 | 2026-09-30 | master | outlet category info | outlet.channel, outlet.sub_channel_id (Astha tier), geo_class |
| 6 | trans_section_summary | 1,844,255 | 2025-12-07 | 2026-09-30 | transactional | by route, by day: target outlets, visited, successful calls | dw route-day summary (recomputed from facts; the vendor figure kept as a reconciliation check) |
| 7 | master_sr_target_scope | 1,043,265 | 2026-01-15 | 2026-09-30 | master | by month, by route, by variant target | deferred (docs/27 targets); the minimum target slice of docs/34 |
| 8 | master_outlets | 980,274 | 2025-12-07 | 2026-09-30 | master | outlets basic info | app.outlet |
| 9 | master_outlet_section_mapping | 979,981 | 2025-12-07 | 2026-09-30 | master | outlet and route mapping | outlet.route_id (effective-dated history) |
| 10 | master_geo_config | 747,816 | 2026-02-26 | 2026-09-30 | master | outlets' base coordinates | outlet lat/lng with location basis 'master' |
| 11 | master_clusters | 130,868 | 2025-12-07 | 2026-09-30 | master | cluster info and route mapping | app.cluster (zone + name), cluster to route link |
| 12 | master_ff_route_mapping | 23,850 | 2025-12-07 | 2026-09-30 | master | field force and route mapping | app.route_assignment (primary / cover, valid_from, valid_to) |
| 13 | master_sections | 14,524 | 2025-12-07 | 2026-09-30 | master | routes and service day mapping | route visit kind + visit_days_mask |
| 14 | master_routes | 14,524 | 2025-12-07 | 2026-09-30 | master | routes info | app.route |
| 15 | master_field_forces | 8,078 | 2025-12-07 | 2026-09-30 | master | field force info | app.app_user (SR, AMO, TSO and above), employee_code |
| 16 | master_dep_location_mapping | 4,216 | 2025-12-07 | 2026-09-30 | master | zone mapping with its wing, division and territory | zone.territory_id and the chain above it (effective-dated) |
| 17 | master_sr_targe_configuration | 2,923 | 2026-01-15 | 2026-09-30 | master | target configuration | deferred (docs/27) |
| 18 | master_promotion | 1,154 | 2025-11-25 | 2026-09-30 | master | promotion configuration | deferred (docs/27); Discount portal entities for reference |
| 19 | master_dep | 1,056 | 2025-12-07 | 2026-09-30 | master | zone info ("dep" = distribution point) | app.zone (and app.house where the zone is a house's) |
| 20 | master_locations | 654 | 2025-12-07 | 2026-09-30 | master | wing, division and territory info | app.wing, app.division, app.territory |
| 21 | master_price_list | 308 | 2025-12-07 | 2026-09-30 | master | SKU price info | price list rows (per price group, effective-dated) |
| 22 | master_category | 75 | 2025-12-07 | 2026-09-30 | master | definition of every category | code lists (channel, sub-channel, classification labels) |
| 23 | master_product_classifications | 60 | 2025-12-07 | 2026-09-30 | master | variant, family, segment and category info | product hierarchy (category, brand, variant) |
| 24 | master_sku | 42 | 2025-12-07 | 2026-09-30 | master | SKU info | app.sku (pack_factor, units per rule 5) |
| 25 | master_section_configuration | 11 | 2025-12-07 | 2026-09-30 | master | service day configuration | the visit-day patterns (the eight patterns of the October master data) |
| 26 | master_price_group | 5 | 2025-12-07 | 2026-09-30 | master | price group | price group |

## What the numbers say
- History covers about ten months (December 2025 to September 2026): 122 million sales lines, 89 million geo rows, 38 million promotion rows.
- The master tables hold MORE than the October workbook: 980,274 outlets against 737,159, 14,524 routes against 11,325, 130,868 clusters
  against 104,876, 8,078 field force against 6,954 reps. So the dump keeps inactive or historical rows (and the field force table holds
  AMO, TSO and managers as well as reps): the load needs an "active as of" rule, and history must keep the old rows so past sales still join.
- "Section" in Apsis is a route with its service days; "dep" is the zone (distribution point).
- Promotion and target tables exist in the dump; both programmes stay deferred here (docs/27), so they are archived for history, not rebuilt.

## MySQL to PostgreSQL: how the load will work (plan, not built)
1. Restore the dump into a throwaway MySQL in the final account (or parse the `.sql` files directly), never into the application database.
2. Load each table as text into a PostgreSQL staging schema (`apsis_stage`), one to one, with COPY; no constraints there.
3. Transform set-based into `app` and `dw`: codes as business keys, UTC timestamps plus the Asia/Dhaka business date, money to integer
   milli-taka, quantities to base units via the SKU's pack factor, every imported row tagged with its origin (`data_origin = 'apsis'`)
   and a deterministic UUID derived from the vendor key so a re-run never doubles a row.
4. Reconcile before anything is switched: row counts and sums per table, per day and per route against the vendor's own
   `trans_section_summary`, and against this sheet's counts.
5. The 31 GB geo table and the 122 million sales lines go to partitioned history tables; the phones never download history.
What cannot be decided from a table list: column names, types, keys, encodings (Bangla text, MySQL zero dates, time zone of the
timestamps), and how zero sales and edits are represented. That needs the table definitions (CREATE TABLE statements only, no rows).

## Open with the owner
1. The CREATE TABLE statements of the 26 tables (a schema-only export; small, no personal data) so the column-level mapping can be written now.
2. Whether inactive outlets, routes and field force of the dump are loaded as archived history or left out.
