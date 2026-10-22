# backend-reports request to the db lane: `dw.agg_daily_outlet.sold_qty_base` adds units (docs/16 P8, D-49)

Found by the Opus checker on qa finding backend-reports-qa-1. `dw.agg_daily_outlet.sold_qty_base` is `sum(memo_line.qty_base)` over every SKU of the outlet-day, i.e. sticks + pieces + dozens
in one number, and it is exposed by the stable view `dw.v_daily_outlet` (V0015) with the dictionary comment "Quantity sold in base units". No API reads it any more
(the last reader, `by_channel[].qty_base`, is null now), but it is the reusable `dw` surface of owner rule 12 and a BI reader would add units.

Ask (forward migration, your call which): either drop the column from the view and the table, or keep it and change the dictionary comment to "mixed base units, do not aggregate;
use dw.agg_daily_outlet_sku (per SKU) or dw.agg_daily_route_sku joined to dw.dim_product for any quantity". I do not read the column; nothing in backend-reports needs it.
