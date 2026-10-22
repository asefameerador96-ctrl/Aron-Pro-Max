# Answer to backend-reports-qa-1: quantities of different units are never added (docs/16 P8, D-49)

Request: `docs/requests/backend-reports-qa-1.md` on lane/qa d2e5df6e (hop 13 of the data-flow trace). Confirmed; fixed in lane/backend-reports with
`ReportUnitsTest` (a day with 20 sticks, 50 lighter pieces and 5 match dozens at one outlet; 75 and 130 appear nowhere) and an Opus checker round (T1).

## What changed (report columns are data in the registry, so no contract change and no version bump)

| Report | Before | Now |
|---|---|---|
| `by-outlet`, `by-outlet-by-day` | one `sold_qty_base` per outlet (sticks + pieces + dozens) and a total | `sold_cigarette_base` (unit stick), `sold_bidi_base` (stick), `sold_lighter_base` (piece), `sold_match_base` (dozen); one row per outlet (and day), money columns unchanged; each category total is in its own unit |
| `route-std` | totals row summed `sold_qty_base`, `free_qty_base`, `gross_mtk` | totals row has `gross_mtk` only; rows stay per route and SKU with their `base_unit` |
| `ds-rrs` | totals row summed issued, sold, free, returned, closing | no totals row (every column is a quantity); rows stay per zone and SKU with `base_unit` |
| `qc-report`, `settlement` (QC batch) | totals summed `qty_base` across SKUs | totals keep `lines` and `settlement_mtk`; no quantity total |
| `route-qc` | rows per route and QC type added `qty_base` across SKUs, and the total did too | rows per route, QC type and category with `category_code` and `base_unit`; totals `lines` and `settlement_mtk` |
| `GET /v1/dashboards/summary` `by_channel[].qty_base` | summed `agg_daily_outlet.sold_qty_base` across categories | `null` (the field is nullable in the contract); the channel STD in sticks, cigarette only, is the national dashboard's `channel-std` card (brief s4.1) |

Unchanged and correct: the dashboard `by_category`, the national dashboard cards (per category), and `by_segment` / `by_brand` quantities (a segment or a brand sits under one category).

## Contract
`ReportColumn.unit` is a free string and `ChannelVolume.qty_base` is already nullable, so there is no schema change, no enum value and nothing for `contract-v1.7-queue.md`.
The web report pages read the columns from the registry; `by_channel[].qty_base` is not used by the web (checked in `web/src`).
