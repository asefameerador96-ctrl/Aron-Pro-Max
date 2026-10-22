# Status: lane android-units (2026-10-22, session 1)

## Done (branch `lane/android-units`)
- F-UNITS-001 `shared:rules` `EntryUnits`/`EntrySpec`: entry unit from `base_unit`, `base_per_pack`, `entry_unit_default`; exact packet price; label key (packet/box/dozen); restore for edits. Seed oracle test for every SKU of `db/seed/sku_catalog.csv`.
- F-UNITS-002 Sale: quantity typed, stock column, line amount and review in the entry unit; per-packet price caption; loose sticks refused unless `allowLoose`.
- F-UNITS-003 Stock: issue, row figures, totals panel in the entry unit; movements store `unit_entered`, `pack_factor`, `qty_base`.
- F-UNITS-004 Print: memo, stock slip, day summary print quantity + unit word; category totals in the entry unit; goldens re-recorded once.
- F-UNITS-005 Memo detail, history, day summary, Home KPI strip and sales headline (now packets), edit restore, AMO SR Stock. `UnitsAcceptanceTest` (app-sr): every seeded SKU, 10s and 20s of one brand, lighter box, match dozen, offline commit, stored, printed, aggregated; both bundles.
- F-UNITS-006 screenshot fixtures and Sale/Review/Stock goldens re-recorded once.
- Submit (Bikroy Joma) and dues show no quantities (money only; submit payload stays base): no change needed.

## Not done
- AMO route-category report and TSO dashboards: need entry quantities from the server (docs/requests/android-units-server-entry-quantities.md).
- QC defect counts stay in base units (sticks/pieces) with their label: a defect count is per stick; owner to confirm.
- Discount rows (deferred programme) stay base. Round 2 (lead, F-UNITS-009, Opus re-check pending): Home Issue/Stock tiles per category in the entry unit; Summary, Home KPI and Stock keep base remainders; category totals show the entry count with the base figure in brackets on Review, Summary and paper.

## Opus checker (9d3fee8)
Printed total = stored total = independent oracle for all 42 seeded SKUs in both bundles (quantities 1, 7, 999; both digit styles; StockLoad movements meet the V0007 CHECK). Fixed from its 8 findings: (1) oversold stock crashed Sale (`fromBase` now truncates, negatives stay negative); (2) edit lines that are not whole packets show 0, never a stick figure under the packet label; (3) packet price shown with 3 decimals when not a whole paisa; (4) Stock row shows `+n` remainder; (5) `QtyTooLarge` review problem above 10,000,000 base; (6) a pack of 1 counts in the base unit; (7) print columns 40/30/30 and 30/14/28/28, goldens re-recorded. Not fixed: see the first line above.

## Traps
- Goldens: core-printing `.pbm` and ui-screenshots `.png` changed; other lanes re-recording the same files will conflict (take theirs, re-run `-Paron.updateGoldens=true` / `-Proborazzi.record=true`).
- `PrintSamples` now carries entry units; the on-phone golden test reads the same samples.
- `DECISIONS.md`: D-UNITS-LANE-01..03 (below).
