# QC six-type entry (Q2): what the app needs from the bundle, and a contract mismatch found on the way (2026-10-17)

Built (android-tso, SR flavour, offline): the six app fault types in two groups (MFC: damaged_crushed, short_outer_pack_stick,
other_mfc; MKT: expired_stock, damaged_in_transit, taste), per-SKU "max / done / remaining" in taka, settlement = quantity x price
at capture through `shared:rules` (the same function as the Review), a stepper per row that can never go past what is left
under the cap, a Yes/No confirm before QC completes, a per-SKU Delete. `QcEntryModel` is the one rule set; `QcEntryModelTest`
holds the property tests; `qc_*` goldens are in `SrStateScreenshotTests`.

## 1. Contract fit: no contract change needed
`QcLinePayload` already carries `fault_type_code`, `fault_group` (MFC or MKT), `qty_base` (1..100000), `unit_price_mtk` and
`settlement_mtk`; the six types are the `applies_to: app` rows of the closed `qc_fault_type` list (db V0027), asserted by a test
that reads that migration.

## 2. Defect fixed: the app was sending values the server would reject
Before this change the app sent `fault_group` = `TFC` (not in the enum MFC or MKT) and `fault_type_code` = `production_fault` or
`transport_fault` (not in the closed list), and allowed a quantity up to 1,000,000 (the contract caps `qty_base` at 100,000).
A memo with QC would have failed server validation. `SaleDraftOps.setQc` now refuses an unknown code, a group that does not
belong to the code, and a quantity above 100,000. Drafts saved by an earlier build with a QC row would fail on reload only if a
rep had a QC row in an unfinished draft; none existed in the field (QC was never wired to the server).

## 3. Need from the bundle (lead / backend): the per-SKU maximum QC
The manual shows "সর্বোচ্চ QC" per SKU (594.50 ৳ for MaxR-10S), but its basis is unknown (MQ-03; `cfg.qc.max_amount_basis` is
`pending`; 594.50 is not 10 x 8.00). The app has the plumbing and NO invented rule: `SaleRoute(qcCapMtkOf = ...)` and
`SaleFlow(qcCapMtkBySku = ...)` take the cap; both default to "no cap", in which case the screen shows only "QC done".
Request: when MQ-03 is answered, the TSO/SR bundle should carry `max_qc_mtk` per SKU (or the basis fields to compute it) and the
SR host (`SrSaleHosts`) should pass it to both parameters. Until then no maximum or remaining line is shown; the screen is
correct either way (golden `qc_bn_no_cap`).
