# Status: lane android-units (2026-10-22, session 1)

## HANDOVER (lane done for now; restart when backend-reports entry-unit aggregates or db's pack-size seed land)
**State:** everything is on `lane/android-units` (345d6dc plus this file). INT merged (b4421ac). Local Gradle works (`echo sdk.dir=/opt/android-sdk > local.properties`; Maven Central answered fine, no mirror needed).
**CI #1313 (345d6dc) failed two jobs:** (1) `HardcodedStringScanTest` flagged my `"Total"` fallback literal in PrintModels.kt (fixed in F-UNITS-013; I had not run core-ui tests locally: run `:android:core-ui:testDebugUnitTest` before pushing); (2) the gitleaks gate reported "leaks found: 1" over 1880 commits; a local scan of my 20 commits since 9d3fee8 finds none, and INT run #1309 (b4421ac) also failed, so it is probably not from this lane (lead to check the redacted finding).
**Done:** F-UNITS-001..012 (below). Money check by three Opus passes: printed = stored = oracle for every seeded SKU, both bundles.
**Next when restarted:** (1) AMO route-category report and TSO dashboards read the new `qty_entered`/`entry_unit` aggregates (docs/requests/android-units-server-entry-quantities.md; feature-amo ReportScreens/StockCorrection, feature-tso TsoDashboard*); (2) after db's pack-size seed (lighter `base_per_pack` 25, `entry_unit_default` pack, cfg.sale.allow_loose_base_units registered) drop the D-UNITS-LANE-01 bridge only if db confirms, and wire `allowLoose` from config; (3) owner confirmation: bidi pack sizes, QC defect counts in sticks.
**Traps:** goldens (core-printing .pbm via `-Paron.updateGoldens=true`, ui-screenshots png via `-Proborazzi.record=true`) are re-recorded; conflicts: take theirs and re-record. Each push cancels the running CI run. `PrintSamples` is shared with the on-phone golden test. Cosmetic leftovers listed under Not done.

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
- Discount rows (deferred programme) stay base. Round 2 (lead, F-UNITS-009/010; first Opus re-check FAILED 4 blockers, all fixed in F-UNITS-010: print quantity cells no longer wrap, Home tile remainders on their own small line, mixed-unit categories get one line per unit on Home, Summary, Review and paper, bracket block `category_base` instead of a bracket row; second and third Opus passes failed only on cosmetic/dock clipping, fixed in F-UNITS-011/012; money check passed all three times: printed = stored = oracle for every seeded SKU; the F-UNITS-012 dock fix is covered by SummaryDockUnitsTest but not re-checked by Opus. Known cosmetic leftovers: English "Total Cigarette" label wraps on paper; dock value cells with 5+ digits clip (pre-existing); stock slip prints the raw category code (pre-existing)): Home Issue/Stock tiles per category in the entry unit; Summary, Home KPI and Stock keep base remainders; category totals show the entry count with the base figure in brackets on Review, Summary and paper.

## Opus checker (9d3fee8)
Printed total = stored total = independent oracle for all 42 seeded SKUs in both bundles (quantities 1, 7, 999; both digit styles; StockLoad movements meet the V0007 CHECK). Fixed from its 8 findings: (1) oversold stock crashed Sale (`fromBase` now truncates, negatives stay negative); (2) edit lines that are not whole packets show 0, never a stick figure under the packet label; (3) packet price shown with 3 decimals when not a whole paisa; (4) Stock row shows `+n` remainder; (5) `QtyTooLarge` review problem above 10,000,000 base; (6) a pack of 1 counts in the base unit; (7) print columns 40/30/30 and 30/14/28/28, goldens re-recorded. Not fixed: see the first line above.

## Traps
- Goldens: core-printing `.pbm` and ui-screenshots `.png` changed; other lanes re-recording the same files will conflict (take theirs, re-run `-Paron.updateGoldens=true` / `-Proborazzi.record=true`).
- `PrintSamples` now carries entry units; the on-phone golden test reads the same samples.
- `DECISIONS.md`: D-UNITS-LANE-01..03 (below).
