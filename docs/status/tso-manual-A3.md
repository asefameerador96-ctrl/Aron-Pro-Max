# tso-manual A3 android-areas: lane status

Lane A3 of `docs/requests/tso-manual-build-2026-10.md` (s5 A3). Branch `tso-man/A3`, worktree `.claude/worktrees/tso-A3`,
base `95a35b7d` (foundation F1+F2+F3) plus `tso-man/A1-stubs` (5e9c52b0, merged). No push, no pull request. Written
2026-10-29.

## Step 1: Leave, Final Submit, the day lock (this step)

Items: LV-02..07, 09..12, 14, 16, 23, 24; FS-02, 03, 05..09, 12, 19; CR-3, CR-5; D-TSO-MAN-27, 28 (app), 31 (SR and AMO
lock), 32, 33; XA-01 (SR card label). The gap-analysis item ids are not in the repo; this step covers the decisions they
point to, listed below.

### Done

| what | where |
|---|---|
| Merge of `tso-man/A1-stubs`; the 17 area cases moved unchanged from A1's TsoScreenshotTests into the new TsoAreaScreenshotTests (golden names kept; 12 cases stay with A1) | `android/ui-screenshots/.../TsoAreaScreenshotTests.kt` (first commit) |
| **Leave list (screenshot 19, D-TSO-MAN-33)**: ascending by from_date (ties by uuid); card = orange status pill top right, calendar icon + "from to to", "Reason: ...", day count in a circle ("Day"/"Days", one TalkBack phrase), no leave type; "Not sent yet" for an outbox row; "Not accepted: <reason>" (red line, Danger pill) for a row the server refused on sync; lime "Apply For Leave" docked at the bottom; no Home button | `feature-tso/.../leave/LeaveScreen.kt`, `LeaveModel.kt` |
| **Leave form (screenshots 20 to 22)**: "Leave Type" dropdown with nothing chosen ("Select leave Type"), types from the bundle code list `leave_type` (fallback Casual, Sick, Earn); "Date" field empty until picked ("Select Date", calendar icon, long form "October 26, 2026", picker opens on the business date's month, limited by `cfg.leave.allow_past_days` / `allow_ahead_days`); typed "Number of days" starting at "0" (leading zeros dropped, "Last day: ..." under it); "Reason"; lime Apply under the form; per-field error lines | `leave/LeaveApplyScreen.kt`, `leave/LeaveApply.kt` (`LeaveTypes`, `LeaveWindow`, `daysFromText`, `LeaveError.DATE_MISSING`) |
| After Apply: queued on the phone (one uuid per form, double tap and retry safe), then back to the list with a toast; the new row shows "Not sent yet" | `leave/LeaveFlow.kt` |
| Host: `leaveTypes()` from the stored `code_lists`, `window()` from the stored config, refusal code on unsent rows from the outbox (`state = rejected`, `last_code`) | `app-tso/.../TsoReadModels.kt` |
| **Final Submit pickers (screenshots 23, 24, D-TSO-MAN-32)**: Wing, Division, Territory, House, Zone dropdowns from one read of `GET /v1/dashboards/national/filter-options` (reach from the token, no ids sent; cached in the user's database so the pickers open offline); a level with one option is preselected down the tree; "No house" option for a territory's zones without a house; Get Sales Data (lime) enabled only with the zone; the flat zone list stays as the fallback when the options cannot be read | `finalsubmit/FinalSubmit.kt` (`FsGeo.parseFilterOptions`, `FsGeo.children`, `FinalSubmitState`), `FinalSubmitScreen.kt`, `TsoReadModels.finalSubmit` |
| **Routes (screenshot 25, D-TSO-MAN-27)**: Sales Date box (green date), "Routes", cards "<route name> <kind>" (Daily, 3F, 2F, AMO), "FF: <full name>" / "FF: SR Not Set", amber "Not uploaded" chip on `upload_state = not_uploaded`, not-due routes greyed with "not due"; memo/net line and red dot dropped; lime Submit docked | `FinalSubmitScreen.kt`, `FsRoute.kindLabel/notUploaded/notDue` |
| **Submit**: due routes not uploaded (`warn_route_ids`, else `upload_state`) give the warning that names them ("Submit anyway" / "Cancel", never blocks); nothing due = submit at once, no confirm; success dialog "Success" / "Final Submit Done Successfully..." / OK over the greyed routes; OK returns to the pickers with the zone cleared and the upper levels kept (CR-5); back from the routes returns to the pickers | `FinalSubmitState.submit/confirmSubmit/cancelConfirm/nextZone` |
| **D-TSO-MAN-28 / CR-3**: client uuid = StableIds.v4 of `aron.tso.final-submit:<user>:<zone>:<date>:<submit_seq>` (seq from the preview); 409 `ERR_DAY_FINAL_SUBMIT_REOPENED` reads the preview again and shows "This zone was reopened..." over the routes; the next submit uses the new seq | `FsIds.clientUuid`, `FsSubmitOutcome.Reopened`, `TsoReadModels.finalSubmit.submit` |
| **Day lock (D-TSO-MAN-31)**: `FinalSubmitLock` (public, in RouteDayLock.kt): a route-day is final when its `RouteDayState` for the date says `final_submitted` (or has `final_submitted_at`) in the held route section (bundle, then every delta; the push `day_final_submitted` is a sync_nudge that pulls the delta) or in the last sync answer's `day_states`; `RouteDayLock.isOpen` returns false for it, so every capture that checks the route-day (visit, sale, memo edit, close, collection, skip, AMO record, survey, content view) is refused in the database itself; a print still goes through (the ledger puts it in its own family without a route); `finalRoutes` and `metLock` (held `after_final_submit`, refused `day_final_submitted` counts of the day) | `core-database/.../repo/RouteDayLock.kt` |
| **SR**: `SrDay.dayLock` (refreshed by `reload()` and by the banner on every applied push pull), `finalLocked()`, check-out opens on a final-locked day as after a Sales Submit, `SrDayLockBanner(day)` / `SrDayLockBannerContent(lock)` (title, body "... Printing still works.", held and refused counts; bn and en) | `app-sr/.../SrDay.kt`, `app-sr/src/main/res/values{,-bn}/strings_a3.xml` |
| **AMO**: `AmoDay.dayLock` (closed zone routes and acks, refreshed by `refreshZone()` and by the banner), `routeLocked(routeId)`, `AmoDayLockBanner` / `AmoDayLockBannerContent` (bn and en) | `app-amo/.../AmoDay.kt`, `app-amo/src/main/res/values{,-bn}/strings_a3.xml` |
| **SR Task card label (D-TSO-MAN-35, XA-01)**: the red tag shows the `task_type` label (Bangla when given), not the code; `TaskTypeLabels` parses the bundle `code_lists`; `TaskContent(typeLabels = ...)` defaults to the labels the SR day holds (SrDay.reload holds them); the code shows only when the bundle has no label for it | `feature-tasks/.../TaskScreen.kt`, `SrDay.reload` |
| Strings: new keys in `feature-tso/src/main/res/values{,-bn}/strings_a3.xml`; existing A3-prefix keys edited: `tso_leave_days_label` ("Number of days"), bn `tso_leave_type`, `tso_leave_from`, `tso_leave_days_label` | |
| docs/08 Leave and Final Submit sections (A3's sections) | `docs/08-tso-app.md` |

### Tests (new or rewritten)

- `feature-tso` `leave/LeaveTest.kt`: LeaveModelTest (to_date in calendar days incl. Friday, ascending order, merge, refusal
  codes), LeaveScreenTest (card layout, ascending on screen, refused row, Bangla digits, empty/error/offline, a11y),
  LeaveApplyScreenTest (empty form as screenshot 20, bundle types and choice, typed days and last day, field errors),
  LeaveFlowTest (Apply queues once, toast, back to the list, row "Not sent yet"; the date picked in the calendar).
- `feature-tso` `finalsubmit/FinalSubmitTest.kt`: seq uuid, no confirm when nothing is due, warning names routes and never
  blocks, older server without warn ids, submit_seq in the uuid, 409 REOPENED re-preview and new uuid, OK clears the zone
  (next zone), already submitted, offline retry keeps the uuid, kind labels, 1.10.0 JSON; screen tests for the five levels and
  Get enabled only with the zone, the routes / chip / warning / success / next zone, the Bangla alert, not due, reopened notice.
- `feature-tso` `finalsubmit/FinalSubmitCascadeTest.kt`: single options preselected, single zone preselected, children per
  level incl. "No house", upper change clears below, ids outside the options ignored, unreadable options fall back, JSON.
- `core-database` `RouteDayLockTest.kt` (new): section lock refuses visit, sale, close and collection; print allowed in its own
  family without a route; sync-answer lock (lost push); same route and date only; final_submitted_at alone; broken sources;
  reopen unlocks when both sources move on; finalRoutes; metLock counts.
- `feature-tasks` `TaskTypeLabelTest.kt` (new): code list parse, label not code (en, bn), held labels as the default, fallback.
- `app-sr` `SrDayLockBannerTest.kt` (new): banner words en and bn with counts; nothing when open.
- `ui-screenshots` `TsoAreaScreenshotTests.kt`: 21 new cases for screenshots 19 to 26 (see Goldens).

## Decisions

- **D-A3-01 Typed days start at "0".** Screenshot 20 shows "0" as the field's value; the field keeps the typed text, drops
  leading zeros ("01" -> 1) and an emptied field stays empty (0 days, refused with the days message). The typed value shows
  the keyboard's Latin digits in Bangla too (an input field, as the other typed fields); the hint and every printed figure
  use Bengali digits.
- **D-A3-02 Refused leave.** A leave the server refused on sync (outbox `rejected`) shows a Danger pill "Not accepted" and the
  line "Not accepted: <reason>", the reason worded from the RecordOutcomeCode (window, duplicate, not allowed, incomplete,
  other). The mirror row stays (it never reaches the server list), so the TSO sees it until logout.
- **D-A3-03 Lock sources.** The Final Submit lock is read from both the held route section (bundle and delta) and the last sync
  answer's `day_states`, either one locks; a reopen unlocks once both have moved on (next sync answer and next delta, the
  foreground delta comes within 30 minutes). A lost push still locks at the next sync.
- **D-A3-04 The lock is enforced in RouteDayLock.isOpen.** CaptureRepository (not A3's file) checks every route-day capture
  through it, so the refusal is in one place and cannot be bypassed by a screen. Consequence: a Sales Submit (`day_submit`)
  after the lock is refused on the phone, although the server would accept it as state only; the SR's check-out therefore
  opens on a final-locked day (SrDay attendance gate) so no rep is stuck. The AMO's supervisor-day submit and attendance are
  routeless and stay open.
- **D-A3-05 Task type labels through the day object.** `TaskTypeLabels` is compose state filled by `SrDay.reload()` from the
  user's bundle (reference data only), and `TaskContent` reads it by default, so the SR card shows labels without an edit to
  SrApp.kt (no owner in s5). An explicit `typeLabels` parameter exists for any other caller.
- **D-A3-06 Final Submit warnings banner dropped.** The server's warning codes are no longer a banner over the routes
  (screenshot 25 has none); the warning dialog names the not-uploaded routes instead. `warnings` is still parsed.
- **D-A3-07 OK clears only the zone.** After success the zone pick is cleared even when it is the house's only zone (the zone
  just submitted is never picked again for the TSO); Wing to House stay.
- **D-A3-08 Back from the routes returns to the pickers** (system back and the on-screen back alike); back from the pickers
  (also with a failure line on them) leaves the screen. Back while the success dialog shows equals OK. A recreated screen
  keeps its picks, routes and phase (the filter options are read once per screen state).
- **D-A3-09 Houseless zones.** FilterNode zones may have `house_id` null; the House level then offers "No house"
  (`FsGeo.NO_HOUSE` = 0, never a server id) whose zones are the territory's zones without a house.
- **D-A3-10 Kind labels in Bangla.** Daily = "দৈনিক", AMO = "এএমও"; 3F and 2F stay Latin (codes on the field cards).

## Deviations

- The lock banners (`SrDayLockBanner`, `AmoDayLockBanner`) are built and tested but **not yet drawn on the SR and AMO Home**:
  the call site is in `app-sr/.../SrApp.kt` and the AMO home host, which no lane owns in s5. See ask 1. Capture is locked
  regardless (database), so the safety part of D-TSO-MAN-31 holds without the banner.
- `FinalSubmitApi.geo()` (per-level `/v1/admin/geo/{level}` reads) is removed; the cascade reads filter-options once.
  `zones()` stays for the flat fallback (A1's TsoNavigationTest fake implements it).

## Asks

1. **Lead (SrApp.kt and the AMO home have no owner):** draw the lock banner. SR: in `SrApp.kt` Home, put
   `SrDayLockBanner(day)` at the top of the Home content (or pass its text as `HomeContent(banner = ...)`); AMO: the same with
   `AmoDayLockBanner(day)` on the AMO home. One line each, marked `// tso-manual A3`. Either A3 gets the line in its owned
   list, or the lead adds it.
2. **Lead / CI (goldens):** record on Linux and look at the goldens listed below; the existing area goldens changed pixels
   on purpose (new layout), including A1's three 200 percent goldens that render A3 screens.
3. **A1:** `TsoLargeTextScreenshotTests.leave_apply_200_bn` now shows the empty-type form with 3 days (LeaveForm no longer
   preselects a type); it compiles unchanged. `TsoNavigationTest` compiles and passes unchanged (see Verify).
4. **B3 (contract already says it):** the preview sends `upload_state`, `visit_kind`, `submit_seq`, `warn_route_ids`; the app
   falls back to `planned`/`had_data` and derives the warn list from `upload_state` until then. The push after the close must
   be `kind = sync_nudge`, `reason = day_final_submitted` (B3 uses Nudger, which sends sync_nudge), so the phone pulls the delta.
5. **B2 / B3:** filter-options must answer 200 for a TSO (s5 B2 rule) and include houses and zones with `house_id` and
   `territory_id` as today.

## Goldens needed (record on the Linux runner; none committed from this laptop)

Changed pixels (re-record): `tso_leave_list_bn`, `tso_leave_list_sunlight_en`, `tso_leave_empty_en`, `tso_leave_offline_bn`,
`tso_leave_error_en`, `tso_final_submit_pick_bn`, `tso_final_submit_review_en`, `tso_final_submit_review_sunlight_bn`,
`tso_final_submit_done_en`; A1's file: `tso_leave_apply_200_bn`, `tso_final_submit_pick_200_bn`, `tso_final_submit_review_200_en`.

New: `tso_leave_s19_en`, `tso_leave_s19_bn`, `tso_leave_s19_200_bn`, `tso_leave_refused_en`, `tso_leave_s20_en`,
`tso_leave_s20_bn`, `tso_leave_s20_errors_en`, `tso_leave_s22_en`, `tso_leave_s22_bn`, `tso_leave_s22_200_bn`, `tso_fs_s23_en`,
`tso_fs_s23_bn`, `tso_fs_s24_en`, `tso_fs_s24_bn`, `tso_fs_s24_200_bn`, `tso_fs_s25_en`, `tso_fs_s25_bn`, `tso_fs_s25_200_en`,
`tso_fs_warning_en`, `tso_fs_s26_en`, `tso_fs_s26_bn`.

## Verify (this worktree, local laptop through gl.sh; CI not run, nothing pushed)

| command | result |
|---|---|
| `gl.sh … --continue :android:core-database:testDebugUnitTest --tests '*RouteDayLockTest*' --tests '*RoomPrintLedgerTest*' --tests '*DayRecordsTest*' :android:feature-tasks:testDebugUnitTest :android:feature-tso:testDebugUnitTest --tests … :android:app-sr:testDebugUnitTest --tests '*SrDayLockBannerTest*' --tests '*SrHomeSubmitGateTest*' :android:app-amo:testDebugUnitTest :android:app-tso:testDebugUnitTest :android:ui-screenshots:compileDebugUnitTestKotlin` (run 1) | feature-tso test sources did not compile (two missing imports in LeaveTest; fixed). Every other task passed: core-database RouteDayLockTest 8/8, RoomPrintLedgerTest 9/9, DayRecordsTest 10/10; feature-tasks 19/19 (TaskTypeLabelTest 5, TaskScreenTest 7, TaskBoardTest 7); app-sr SrDayLockBannerTest 3/3, SrHomeSubmitGateTest 1/1; app-amo 31/31 (all 5 classes); app-tso 23/23 (all 4 classes); ui-screenshots test sources compile |
| `gl.sh … :android:feature-tso:testDebugUnitTest` (run 2, full) | 157 tests, 6 failed: test-side only (two buttons below the fold clicked without scrolling, three text matchers that found two nodes, the date picker's day cell reads its full date). Fixed in the tests |
| `gl.sh … :android:feature-tso:testDebugUnitTest --tests '*leave*' --tests '*finalsubmit*' --tests '*TsoNavigationTest*' --tests '*TsoAppTest*'` (run 3) | BUILD SUCCESSFUL; 56 tests, 0 failures (A1's TsoNavigationTest 2/2 and TsoAppTest 4/4 unchanged and green) |
| `gl.sh … :android:feature-tso:testDebugUnitTest` (run 4, full, after restoring the LeaveJson/LeaveChecker/LeaveApplyState classes the rewrite had dropped, plus LeaveApplyOptionsTest) | BUILD SUCCESSFUL; 35 classes, 173 tests, 0 failures, 0 errors |
| `gl.sh … :android:ui-screenshots:testDebugUnitTest --tests '*TsoAreaScreenshotTests*' -Paron.screenshots.recordTo=<scratchpad>/goldens/A3` | BUILD SUCCESSFUL; 38 PNGs written to the scratch dir only (none committed). Looked at s19, s20 bn, s22, s23, s24, s25, s26, the warning and the refused row against the owner's JPGs: same structure and order of elements; colours are still the current kit (A1's TsoNight step restyles the kit bodies without changing the signatures) |

Not run: `:android:core-map:testDebugUnitTest` (core-map is step 2), the full core-database and app-sr suites (only the
targeted classes above), the ui-screenshots verify mode (goldens are recorded on the Linux runner).

## Next step

A3 step 2 (s5 A3 second half): My Periphery (PER-02..07, 09, 10, 12..14, 18; D-TSO-MAN-34; core-map interactive opt-in,
MapPoint kind, radius circle), My Call (MC-01, SP-01..03, VP-01..03, VQ-02, AT-01, AT-02; D-TSO-MAN-35; CR-4 back paths),
My Feedback (FB-01..10, FB-13; D-TSO-MAN-36; A2's GalleryImport), split the hubs into RETAILER, SET_PLAN and FEEDBACK_NEW in
AreasHost once A1's drawer lands, the docs/08 sections for them, and the goldens for screenshots 27 to 38 and 43. Before
that: answer ask 1 (banner call sites) when the lead rules.
