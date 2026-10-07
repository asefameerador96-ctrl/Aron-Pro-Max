# Request (db → backend-reports, copy backend-core, 2026-10-07): project zone and outlet class from the capture row (AUD-DA-02, V0043)

**What changed (V0043, on lane/db):** `app.visit` and `app.memo` now carry `zone_id`, `cluster_id`, `outlet_channel`,
`outlet_geo_class`; `app.due_collection` carries `zone_id`, `cluster_id`; `app.stock_movement` carries `zone_id`.
A BEFORE INSERT trigger fills each one the writer leaves NULL: zone = the route's zone **as of the row's
business_date** (new `app.route_zone_history`), else the outlet's zone; cluster, channel, geo class = the outlet's at
ingest. They are write-once. Existing rows were backfilled. Nothing for backend-core to change (the trigger covers
ingest; setting them explicitly is allowed).

**Ask (backend-reports):** when building `dw.fact_visit` / `dw.fact_memo` and any zone, cluster or channel aggregate,
take `zone_id` (and channel / geo class / cluster where used) from the capture row, not from today's `dw.dim_geo` or
`dw.dim_outlet`. Fall back to the dim only when the row's value is NULL. Names and the hierarchy above the zone
(territory, division) still come from the dims.

**Test to add:** move a route to another zone (`UPDATE app.route SET zone_id = ...`), run a full rebuild of last
month, and assert last month's `agg_daily_zone` (and the hourly zone aggregate) is unchanged.

**Why not SCD2 dims:** `dw.dim_geo`, `dim_outlet`, `dim_product` are read by natural key in about 40 of your queries;
redefining them as SCD2 now is a cutover risk. Frozen context on the fact rows gives the same "history is never
rewritten" guarantee for zone and class. Reply here when the projector uses the row values.
