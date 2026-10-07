# Request from db to backend-reports (2026-10-07): use the AUD-DA-02 history in the projector

Context: audit row AUD-DA-02 (docs/audit/2026-10-07-enterprise-bar-audit.md, DA-02). The db part is on lane/db:
- `V0042`: the capture rows freeze their context. `app.visit` and `app.memo` carry `zone_id`, `cluster_id`,
  `outlet_channel` and `outlet_geo_class`; `app.due_collection` and `app.stock_movement` carry `zone_id` and
  `cluster_id`. A BEFORE INSERT trigger stamps them: the zone of the route on the business date
  (`app.route_zone_history`, `app.route_zone_on(route_id, date)`) and the outlet's placement on that date. They are
  write-once. Ingest needs no change.
- `V0043`: SCD2 version tables `dw.dim_geo_version`, `dim_product_version`, `dim_outlet_version`, `dim_user_version`.
  Triggers keep them in step with your type-1 upserts, so your upserts do not change. A change opens a new version on
  the Asia/Dhaka date of the change. Lookups: `dw.geo_key_on`, `product_key_on`, `outlet_key_on`, `user_key_on`
  `(id, business_date)`. New `dw.dim_user` (user_id, role, designation, home_zone_id, status, pilot; no names).
  `dw.fact_visit` and `fact_memo` gain nullable `geo_key`, `outlet_key` and `user_key`.

## Ask
1. Projector: take `fact_visit.zone_id` and `fact_memo.zone_id` (and the zone of every zone aggregate) from the capture
   row's `zone_id`, not from the current `dim_geo`/`route`. Fall back to the current value only when the row's column
   is NULL (rows from before V0042).
2. Fill `geo_key`, `outlet_key` and `user_key` on the facts with the `*_key_on(id, business_date)` lookups.
3. Upsert `dw.dim_user` from `app.app_user` the same way as the other dimensions.
4. Add the audit's acceptance test (DA-02 point 5): move a route to another zone, run a full rebuild, and assert that last
   month's `agg_daily_zone` is unchanged.

Reply here with `## Answer`; until then the db part stands on its own (the capture rows already keep the old zone).
