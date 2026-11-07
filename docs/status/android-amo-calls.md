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

## HANDOVER (A2 #1, 2026-11-07; A2 #2 continues from here)
**Branch/build.** `lane/android-amo-calls`, first push head 7605ac51 (CI run 42340700755). Push only that branch, merge INT before every push, one push per CI run. Build traps: `tools/android-sdk.sh` + `echo sdk.dir=/opt/android-sdk > local.properties`; never run two Gradle commands at once (contention made feature-tasks/feature-tickets/core-sync fail once; they were green when run alone); goldens only on Linux with `-Proborazzi.record=true --tests ...`; golden names must not collide with `AmoCoreScreenshotTests` (mine are `amo_cc_tiles_*`, `amo_control_call_pick_*`).
**Covered rows (local tests + goldens):** 17-24, 26-32, 42-44, 65-66 price row (see Done above, D-AMO-CALLS-01..05). Opus checker FAILED the first version on two high Sales-path findings (dead end when out of range; sticky non-standard state), both fixed (`checked` retailer id, `callOpen` from the session, Out range with Manual Override then the reason dialog); NOT re-checked after the fix: ask a checker to re-read `ControlCallHost.PickStep`.
**Open rows / items:** 45 Summary (title, empty "কিছু পাওয়া যায়নি", Print) unchanged and unchecked against note 45; 66 due warning + success dialogs unchecked; 24/25 Sale and review parity not compared line by line (shared SR screens: packshot badge, discount % and stock icons); memo detail "রুট:" and dashed red due box unchecked (42-43); side-by-sides still missing for 24, 25, 42, 43, 45, 65, 66 (present: 17, 18, 20, 23 partial, 26, 27, 31).
**Low checker findings left:** MemoFind may undercount/double count in edge cases (as-of today; null as-of; edited earlier memo); hidden Price row can still block Submit if a price record is mismatched while the tile is off (keep the row when its counts are non-zero); `saveAssessment` does not itself require POSM (host does); a no-location outlet forces `no_outlet_location` over the chosen reason (shared SR rule).
**Wiring list for F1/F2 (when the lead says the foundation train landed):** (1) `RecordMapping.visit`: add top-level `force_sale_reason` = `geo_force_reason_code` when `geo_action == force_sale` and the code is internet_problem/location_change (contract class `VisitPayload`, lane amo-foundation); extend `AmoRecordWriterReplayTest.theForceSaleReasonIsOnTheVisit...` to assert the new field. (2) Rubric v2 comes in the bundle `rubrics` section with `section`, `section_bn`, `guidance_en`, `guidance_bn` (already parsed by `JointCall.rubric`); check the real key names against the contract fixture. (3) Previous-sale-data client: `AmoApi.salesByDate` + `PreviousSales.parseServer` already read the F2 shape (`qty_report` is a Decimal3 STRING, parsed as BigDecimal); replace the fixtures with the contract fixture and test 403/404 (falls back to local memos). (4) `cfg.amo.price_compliance_tile` and `cfg.amo.joint_call_default_star` are read through `reference.config`; confirm the keys arrive in the bundle config.
**Context at handover:** about 440k by my own estimate (get_session reports 0 tokens).
