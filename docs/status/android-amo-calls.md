# Status: lane android-amo-calls (A2), started 2026-11-06

Branch `lane/android-amo-calls` (from INT 7f34a1d2, which carries A1). Push only that branch; merge INT before every push; one push per CI run.

## Done (local, tests green; see DECISIONS D-AMO-CALLS-01..05)
- Rows 17-20, 21-22, 29-30: shared picker (`CallPick.kt`, `ControlCallHost.PickStep`): route and retailer drop-downs, LIVE indicator (not a button), alphabet bar of the letters present, range warning + Manual Override + Refresh, retailer panel (green dot, brown badge, code descending), route panel. Tests `CallPickTest`.
- Rows 23: force-sale reason dialog on the Sales tile, reason through the Manual Override photo step, stored on the visit; replay test extended (`AmoRecordWriterReplayTest`).
- Rows 24, 26: "বিক্রয় ইতিহাস" button, previous sale data with the date steps, F2 shape fixture, offline = local memos (`PreviousSalesTest`).
- Rows 25, 24 (review, due checkbox, partial payment, Product QC, Print): the shared SR review and credit prompt already carry the vendor texts and the 0 <= paid < total rule (`ReviewScreen`, `CreditPrompt`, `PaidInput`); not changed (SR goldens untouched).
- Rows 27-28, 31-32: Perf Assessment and Joint Call v2 screens, saved dialog, POSM required, default star config (`AssessmentScreensTest`, `JointCallV2Test`).
- Rows 42-44: Memo route/retailer find with the outstanding due (`MemoFindTest`); detail dialogs are the shared memo ones.
- Rows 65-66: Sales Submit row "মূল্য সম্মতি" hidden unless `cfg.amo.price_compliance_tile` (`AmoReconciliationPriceRowTest`); Price compliance tile behind the same flag.
- Goldens: `AmoCallsScreenshotTests` (bn/en/200 %/Sunlight for pick, menu, panels, reason, saved, Perf Assessment, Joint Call v2, previous sales) and the re-recorded `AmoCoreScreenshotTests`.

## Open (not done in this pass; honest list)
- Side-by-side PNGs `docs/evidence/ui-parity/amo-*.png` (17, 18, 20, 23, 24, 25, 27, 31, 42, 43, 45, 65, 66): not composed yet.
- Row 45 Summary ("বিক্রয় সারসংক্ষেপ" title, empty state "কিছু পাওয়া যায়নি", Print): the shared summary screen was not changed; check the texts against note 45.
- Row 24 Sale screen parity: the discount % and stock icons and the entered-quantity badge are the shared `PackCard` as the SR has it; not compared line by line with note 23/24.
- Row 66 due warning dialog and success dialog: not changed (shared Sales Submit); check against note 66.
- Memo detail "রুট:" label and the dashed red due box: shared memo detail, not checked against notes 42-43.
- Real F1/F2 clients: `force_sale_reason` top-level mapping and the real sales-by-date client wait for the foundation train; the app reads the endpoint through `AmoApi.salesByDate` now and falls back as designed.
- No emulator or phone here: every acceptance is DEVICE-PENDING.
