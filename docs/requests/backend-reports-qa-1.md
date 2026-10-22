# backend-reports-qa-1 — report totals add sticks, pieces and dozens (docs/16 P8, D-49)

From lane qa (`DataFlowTraceTest` hop 13; dump of every report in `backend/app/build/reports-dump.json` after a run).
Opus checker: CONFIRMED. Request: `POST /v1/reports/by-outlet/query` `{"period":{"date":"2026-10-24"},"output":{"format":"json","page_size":100}}`.
Response (redacted): rows `{"outlet_code":"GUL2-001","sold_qty_base":160,"net_mtk":1523000,...}`, totals
`{"memos":5,"sold_qty_base":408,...}`. GUL2-001 sold 60+20+25 sticks, 50 lighters (pieces) and 5 match dozens: 160 is a
unit-less sum; 408 mixes the two divisions' sticks, pieces and dozens. `OUTLET_COLS` (`ReportHandlersA.kt:166-170`) has one
`sold_qty_base` column with no unit or category. Same pattern: `by-outlet-by-day` (shares the handler) and the totals rows
of `route-std` (408) and `ds-rrs` (755 / 408 / 347). docs/16 P8: aggregates sum `qty_base` only inside one SKU or one
category; DECISIONS D-49: no cross-category total STD. Suggested: add a category column (rows per outlet and category) or
drop the cross-category total. `route-std`/`ds-rrs` rows are per SKU and fine; only their totals mix.
Failing test: hop 13 `by-outlet separates units`.
Everything else reconciled: route-memo, std-memo, dss, sr-efficiency, tso-top-sheet money (gross 3,237,000, paid 2,658,600,
due 578,400, outstanding 576,400 mtk across both divisions) equals the device model; final-submit-log shows 4 late rows after
Final Submit; memo-number-gaps lists 0 gaps for numbers 1..4 (the void is explained).

## Attached test (add it with the fix)
`docs/requests/attachments/ReportUnitTotalsTest.kt.txt`: both checks fail today. Drop the `.txt`, add under `backend/app/src/test/kotlin/com/aktcl/aron/backend/app/`; extends `DataFlowTraceSupport`.
