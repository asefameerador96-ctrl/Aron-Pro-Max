# backend-reports -> db: date `dw.dim_*_version` by the app-side change, not the projector run (session 6, 2026-10-28)

Context: review High 1, lead action row 2, `docs/requests/db-backend-reports-scd2-keys.md`. The Aggregator now attributes every fact and
aggregate AS WAS: zone from the capture row (V0042), product tree / category / user role / zone territory from the V0119 history
(`dw.v_dim_product_history`, `dw.v_dim_user_history`, `app.zone_history`), and fills `fact_visit|fact_memo.geo_key|outlet_key|user_key`
with `dw.geo_key_on|outlet_key_on|user_key_on(id, business_date)` and `dw.dim_user` from `app.app_user`.

## Gap
`dw.dim_geo_version`, `dim_outlet_version` and `dim_user_version` (V0043) are dated by the Asia/Dhaka date on which the projector wrote the
change (`dw.scd2_track`: `d := app.dhaka_date(now())`), not by the date of the app-side change, and the first version is "since ever" with
the attributes of the day V0043 ran. So a key looked up for an old date points at the right ROW only when the projector refreshed the
dimensions on the day of the change. The numbers in the aggregates do not depend on the keys (they use the history directly); the keys are
what a BI join on `geo_key` would use.

## Ask (optional, hours; before the 8,500-user move)
A db function `dw.rekey_dim_versions()` (owner or worker role, idempotent) that rebuilds `dim_geo_version`, `dim_outlet_version` and
`dim_user_version` from `dw.v_dim_geo_history`, `v_dim_outlet_history` and `v_dim_user_history` (one version per history period, with the
history's `valid_from`/`valid_to`), keeping the existing `geo_key` values for versions whose attributes and period are unchanged, and a
trigger/refresh that follows the history tables instead of `dw.scd2_track` from then on. Then one rebuild of the facts (the worker's
range rebuild, candidate 2) re-stamps the keys. `dim_product_version` likewise from `v_dim_product_history`.

Not blocking: the lead may park this until the final-account move.
