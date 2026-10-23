# db-qa-1 — doc typo: dw.agg_daily_outlet_category does not exist

`docs/evidence/seed-gulshan-banani.md` (expected hops, item 3) and the wave-4 brief name `dw.agg_daily_outlet_category`.
No migration creates it. The category figures live in `dw.agg_daily_route_sku` (+ `dw.dim_product.category_code`,
view `dw.v_daily_sku`), `dw.agg_daily_outlet_sku` and `dw.agg_daily_route_variant` (V0011, V0099); the trace checks all
of them, base units equal the device model (PASS). Please correct the doc (no data is missing). Known-findings line:
`dw.agg_daily_outlet_category exists`.
Also noted for db: seed 06 on the dev profile reproduces locally exactly as the evidence doc says (3 routes x 21 outlets
with the smoke outlet, masks sum 63, Friday no route, Oct targets 24,003.6 sticks/day for the three routes over 25 working days).
