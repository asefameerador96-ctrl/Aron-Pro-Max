# Request: entry-unit quantities for AMO reports and TSO dashboards (from android-units)

**To:** backend-reports / backend-core. **Why:** docs/34 s4.2 says AMO and TSO show quantities in the entry unit. Those screens read server aggregates that carry only base quantities per category (`qty_base`, `base_unit`: TSO dashboard `by_category`, AMO route-category report, team performance). A category total in sticks cannot be turned into packets on the phone, because 10s and 20s of one category have different pack sizes.

**Asked:** add `qty_entered` (sum of `memo_line.qty_entered`) and `entry_unit` (`pack` or `dozen`) next to `qty_base` in those aggregates, summed per SKU first; or expose per-SKU rows. Until then the AMO route-category report and the TSO dashboards keep base units (sticks, pieces, dozens), which is what the dashboard shows by the owner's brief. AMO SR Stock (per SKU) is already converted on the phone.

**Also:** `cfg.sale.allow_loose_base_units` is not yet in the cfg registry (V0006); the apps treat it as false (`SaleSku.allowLoose` default). The server check `qty_base = qty_entered x pack_factor` exists as a DB CHECK (V0007) on memo_line and stock_movement; no extra API check was found to be missing.

**Seed (db lane):** lighters are seeded with `base_per_pack` 1 and `entry_unit_default` = base unit; cigarettes/bidi `entry_unit_default` = `stick`. The apps already count packets for those (a base-unit default of a multi-stick pack is never loose) and a box of 25 once `base_per_pack` = 25 and `entry_unit_default` = `pack` arrive in the bundle; `UnitsAcceptanceTest` runs both bundles.
