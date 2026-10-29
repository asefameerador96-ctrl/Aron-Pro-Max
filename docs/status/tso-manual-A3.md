# tso-manual A3 android-areas: lane status

Lane A3 of `docs/requests/tso-manual-build-2026-10.md` (s5 A3). Branch `tso-man/A3`, worktree `.claude/worktrees/tso-A3`,
base `95a35b7d` (foundation F1+F2+F3) plus `tso-man/A1-stubs` (5e9c52b0, merged) and `tso-man/A2-gallery` (d0724c5a,
merged in step 2). No push, no pull request. Written 2026-10-29.

## Step 1: Leave, Final Submit, the day lock

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

## Step 2: My Periphery, My Call, My Feedback

Items: PER-02..07, 09, 10, 12..14, 18; MC-01, SP-01..03 (app), VP-01..03, VQ-02, AT-01, AT-02; FB-01..10, FB-13; CR-4;
D-TSO-MAN-34, 35, 36. Started with `git merge --no-ff tso-man/A2-gallery` (926cf155: `GalleryImporter`,
`MediaComponents.gallery` and `importFromGallery`). The gap-analysis item ids are not in the repo; this step covers the
decisions they point to.

### Done

| what | where |
|---|---|
| **core-map** (A3 owns it): `MapPoint.kind` (`MapPointKind` PERSON, OUTLET, SELF; default PERSON, so the AMO callers compile unchanged) and `MapPoint.note` (a line under the row and in the pin bubble); `MapCircle`; `MapFrame.of(points, circle)` frames the circle; `MapSources` words the contract's fix sources (check_in, visit, check_out, breadcrumb) in bn and en, any other text shown as is (the AMO passes its own labels); `LiteMapScreen(interactive = false, circle = null)`: interactive mode is opt-in, a full map (zoom, pan, tap for the bubble; tilt and rotation off; no my-location layer; no toolbar) that fills the screen while live, pins coloured by kind and faded when stale, the circle drawn; offline or without a key it is the same list as lite mode; an outlet row is never greyed and has no "No location yet" line | `android/core-map/src/main/kotlin/.../MapPoint.kt`, `LiteMapScreen.kt`, `res/values{,-bn}/strings.xml` |
| **My Team (screenshot 28)** as its own destination: "My Teams", zone dropdown (first zone chosen), the legend "route: count" (grouped by route), "As of", a Refresh action (one read; nothing polls), the interactive map of the last synced fixes (never live), stale grey-out after `cfg.tso.team_location_max_age_min`, the "Mock GPS today: n" chip from `mock_fixes_today` | `feature-tso/.../periphery/PeripheryScreens.kt` (`TeamScreen`), `PeripheryModel.kt` (`teamGroups`, `legend`, `teamPoints(mockNote)`, `isStale`, `PeripheryConfig`), `PeripheryFlow.kt` (`MyTeamEntry`) |
| **Retailer (screenshot 29)** as its own destination: "Outlets", Zone and Radius dropdowns side by side (radii from `cfg.tso.periphery_radius_options_m`, falling back to 50/100/300), one balanced fix on open, on a zone or radius pick and on the refresh icon; the map with the outlets, the radius circle and the TSO's own pin; pins show the distance rounded to whole metres | `RetailerScreen`, `RetailerState` (`asOfNow`, config radii, zone preselected), `RetailerEntry` |
| **Offline nearby** (D-TSO-MAN-34): `NearbyRule.compute` runs the server's rule on the bundle outlets: active, coordinates confirmed, route set, zone of the route, `Geo.haversineM <= r`, distance rounded to 0.1 m as `distance_m`, nearest first, ties by id, capped with `truncated`. Answer order: a fresh server answer; else the bundle worked out on the phone (banner "No connection: worked out on this phone"); else the last copy of the same view | `PeripheryModel.kt` (`NearbyRule`, `BundleOutlet`, `Stamped.computedOnPhone`), `app-tso` `TsoReadModels.periphery.bundleOutlets` + `TsoBundleReads.nearbyOutlets` |
| Host config reads: `cfg.tso.periphery_radius_options_m`, `cfg.tso.team_location_max_age_min`, `cfg.tso.plan_max_days_ahead`, `cfg.feedback.max_images` from the stored bundle config (registry bounds; defaults when absent) | `app-tso/.../TsoReadModels.kt` (`configRaw`, `configInt`, `periphery.config`, `plan.planMaxDaysAhead`, `feedback.maxImages`) |
| **Set Plan (31, 32, 33)** as its own destination: Select Date (today by default; up to the configured days ahead), Select Zone (the only zone chosen), Select Route (dropdowns), lime Show Outlet ("Choose a route" without one) -> **Select Outlets**: cards "name / code | cluster / owner | phone" with the tick (ring until chosen), the outlets already planned that date ticked (union), "n of m selected" and Select all, lime Set Plan docked -> queued, toast, back to the form with the picks | `mycall/MyCallScreens.kt` (`SetPlanScreen`, `SelectOutletsScreen`, `McDateField`), `PlanState.kt` (`SetPlanState(initialDate = today)`, `maxAheadDays`, `missingRoute`, `backToForm`), `MyCallFlow.kt` (`SetPlanEntry`) |
| **My Visit Plan (34 to 38)** as its own destination: form (today, zone, route, Show Outlet) -> **Visit Plan Outlets**: Pending / Completed tabs without counts, "No Outlets Found", the 5-line card (Code, Owner, Contact, Channel "GT Channel", Cluster) -> **Visit Query**: each seeded question with its "Enter your answer" field (<= 1000 characters, answers optional), "Do you want to delegate the task?" Yes / No radio (No by default), lime Submit -> toast, then Assign Task (Yes) or the outlets (No) -> **Assign Task**: the outlet card (Outlet Name, Cluster, Route), the task type dropdown (labels, never codes), the date (today), Comment, lime Assign Task -> toast, back to the outlets | `VisitPlanScreen`, `VisitPlanOutletsScreen`, `VisitQueryScreen`, `AssignTaskScreen(outlet, onAssigned)`, `VisitPlanEntry` |
| **CR-4**: in both My Call destinations the system back and the on-screen back go to the same place: Select Outlets -> form; Visit Query and Assign Task -> outlets (read again); outlets -> form; form -> out through the navigator. A step restored without its outlet (process death) falls back to the outlets | `MyCallFlow.kt` |
| `PlanOutlet.channel` (online read, cache, bundle); `planDaysAhead` (1..90, default 60) for Set Plan, My Visit Plan and Assign Task; `AssignTaskState(maxAheadDays)`; Visit Query answers cut at 1000 characters as typed | `PlanModel.kt`, `QueryModel.kt`, `TsoBundleReads.kt` |
| **Create New Feedback (43)** as its own destination: "Feedback Category" as a vertical list from the code list (suggestion, feedback), "Enter title", "Descriptions" / "Enter description", Browse Gallery (blue; the system photo picker, no storage permission, no camera), Save (lime) -> queued, toast, back; the next visit starts a fresh form (new uuid). One picture at most (`cfg.feedback.max_images` 0..1; 0 hides Browse Gallery); a second pick replaces the first; a refused picture (no space, too large, unreadable, no picker) is said under the button and the form stays | `feedback/FeedbackScreens.kt` (`FeedbackFormScreen(onSaved)`), `FeedbackModel.kt` (`FeedbackPhotos.fromGallery`, `GalleryPick`, `FeedbackFormState.addFromGallery`, `feedbackMaxImages`), `FeedbackFlow.kt` (`FeedbackNewEntry`) |
| Gallery picture through A2's import: `MediaComponents.gallery.import` stores it compressed and EXIF-free at the pick; Save claims it for the feedback with the record (the existing attach in `feedbackQueue`) | `TsoReadModels.feedbackPhotos(media, resolver).fromGallery`, `AreasHostDeps.feedbackPhotos()` |
| **My feedback** list as its own destination: title, status pill, category and the business date (`business_date`, else the created date), "Not sent yet", lime "Add new feedback" opening FEEDBACK_NEW on top | `FeedbackListScreen(nav)`, `FeedbackListEntry`, `FeedbackItem.businessDate` / `shownDate` |
| **AreasHost**: one entry per drawer row (MY_TEAM, RETAILER, MY_VISIT_PLAN, SET_PLAN, FEEDBACK_NEW, FEEDBACK_LIST), each with the Home button (s8 column Y) | `app-tso/.../AreasHost.kt` |
| Strings: 45 new keys (en and bn) in `feature-tso/src/main/res/values{,-bn}/strings_a3.xml` under `tso_pe_`, `tso_mc_`, `tso_fb_`; core-map `map_source_*` (4, en and bn) | |
| docs/08 sections My Periphery, My Call, My Feedback rewritten from the screenshots and D-TSO-MAN-34..36 | `docs/08-tso-app.md` |

### Tests (new or rewritten)

- `core-map` `MapPiecesTest`: the frame holds the circle (300 m at street level; the four edges at 300 m by haversine), kinds
  default to PERSON, the source words, three pin colours. `LiteMapScreenTest`: localized sources ("Visit", "Check-in",
  "ভিজিট"); interactive mode offline shows the list and composes no map; outlet rows show their note and no "No location yet";
  an unknown source text shows as is.
- `feature-tso` `periphery/PeripheryTest.kt` (PeripheryLogicTest, PeripheryScreenTest): config radii and stale age within
  the bounds; Retailer offers only the configured radii and preselects the first zone; **offline nearby == the server rule**
  (inactive, unconfirmed, routeless, unlocated, other-zone and just-outside outlets dropped; 0.9995 r north and east kept;
  ties by id; every distance = the rounded haversine; cap and `truncated`); rounding (0.1 m half up, whole metres on the
  pins); offline answer worked out from the bundle; a fresh server answer wins, the bundle beats an old copy, the copy is the
  last resort; grouping by route and the legend; stale grey-out after the configured age; the mock chip only for SRs with
  mock fixes (JSON absent or negative = 0); screens: My Team (zone dropdown, mock chip, Refresh reads once more, a11y),
  Retailer (one fix on open, the offline banner and rows, a11y), a radius pick takes one new fix (Bengali digits).
- `feature-tso` `mycall/PlanTest.kt` (new class `PlanTest` next to the existing ones): Set Plan and My Visit Plan default to
  today; the configured days ahead (bounds, DATE_FAR, Assign Task too); Show Outlet without a route reads nothing; a single
  zone is chosen; back to the form keeps the picks; the V0139 questionnaire parses as two text questions; answers capped at
  1000; the outlet channel read and cached.
- `feature-tso` `mycall/MyCallScreenTest.kt` (rewritten): the form on today with the only zone, "Choose a route", the route
  dropdown; Select Outlets card lines, tick, Select all, save; **back paths (CR-4)** in Set Plan and My Visit Plan, system
  back and the on-screen back compared step by step (outlets, Visit Query, Assign Task, form, out); no tab counts and "No
  Outlets Found"; the 5-line card (en and bn digits); the seeded questions with two answer fields, delegate No by default,
  the 1000 cap as typed; Assign Task outlet card and type label; SR Not Set blocked; a11y sweeps.
- `feature-tso` `feedback/FeedbackTest.kt` (new class `FeedbackTest`): categories in code-list order; `max_images` bounds;
  one gallery picture, a replacement drops the first, Save sends it, stored for the form's uuid; a refused picture leaves the
  form and says why; no Browse Gallery at 0; the form's words (en, bn) and the category list; Save goes back and the next
  visit is a fresh form with a new uuid; the business date on the list.
- `ui-screenshots` `TsoAreaScreenshotTests.kt`: 31 new cases (see Goldens); `menuShot` captures a screen with a dropdown open
  (`captureScreenRoboImage`).

## Fix round 1 (independent checker, 2026-10-29)

Seven defects: one high, two medium, four low. All are fixed in A3's files, except the parts that need files A3 does not
own. Those parts are asks 1, 10 and 11.

### Done

| severity | defect | fix | where |
|---|---|---|---|
| high | The Final Submit lock went through `check()` in CaptureRepository's requireOpenRouteDay for every record. Sales Submit after "Submit anyway", leaving a call when the lock arrived mid-visit, and Mark Paid crashed the SR app, again on every retry and after a relaunch. | **D-A3-22.** The lock is per record type: `FinalSubmitLock.LOCKED_TYPES` is B3's `DAY_LOCKED_TYPES`, and day_submit, attendance, print_event and amo_record are never refused. `RouteDayLock.isOpen(db, meta, recordType)` and `requireOpen` apply the lock only when the caller names the type, and throw `RouteDayFinalSubmittedException`. CaptureRepository names no type yet (ask 10), so nothing it does throws for the lock. A3 refuses a new call itself: the SrDay and AmoDay visit-flow committers call `FinalSubmitLock.requireCallOpen`, and VisitFlow shows the refusal as CommitFailed with nothing stored. A call in progress ends on the phone without a close (`SrCallLock.abandon` / `endsOnPhone`, `AmoCallLock.endsOnPhone`, `AmoDay.closeVisit`). A call is not restored on a closed route-day (`FinalSubmitLock.callToRestore`), so no relaunch loop. `SrDay.endCallIfDayFinal()` is ready for SrSaleKit (ask 11). The banner text says "not accepted" instead of "cannot be saved". | `core-database/.../repo/RouteDayLock.kt`, `app-sr/.../SrDay.kt`, `app-amo/.../AmoDay.kt`, `app-sr` and `app-amo` `strings_a3.xml` (en, bn) |
| medium | The SR and AMO lock banners were not drawn. AmoDay.routeLocked had no caller, and the AMO control and joint call screens were not gated. | The AMO control, joint and sale calls cannot start on a closed route: the committer refuses them through the same state as `routeLocked`, which now documents this. A call already open ends on the phone. The banners still need one line each in files A3 does not own: ask 1, now with the exact lines. The row 26 device check waits for that ask. | `AmoDay.kt` |
| medium | Retailer race: a zone or radius pick during the opening fix left the screen on "Loading" with no fix for the new choice. | **D-A3-23.** If find() is called while one is running, the running find takes exactly one more fix for the new choice after it drops the old answer. A pick without its own find leaves the screen on Pick. A second refresh during a run takes no extra fix. | `periphery/PeripheryModel.kt` (`RetailerState.find`, `findOnce`) |
| low | No golden case for screenshot 21. Missing 200 percent cases for 20, 23, 26, 34 and 35, and the bn and 200 percent cases for 32. | Added `leave_s21_en`, `leave_s21_bn`, `leave_s21_200_bn` (dropdown open, `menuShot`), `leave_s20_200_bn`, `fs_s23_200_bn`, `fs_s26_200_en`, `mc_s32_bn`, `mc_s32_200_bn`, `mc_s34_200_bn`, `mc_s35_200_en`. | `ui-screenshots/.../TsoAreaScreenshotTests.kt` |
| low | Final Submit: the system back on the pickers, a failure or the already-submitted state skipped the epoch, so old picks and a stale date came back. | **D-A3-24.** BackHandler is enabled in every phase and goes through the same back as the on-screen arrow. Get Sales Data uses `today()` when tapped. | `finalsubmit/FinalSubmitScreen.kt`, `FinalSubmit.kt`, `FinalSubmitFlow.kt` (KDoc) |
| low | Bengali digits typed into the days field gave 0 days. | **D-A3-25.** `daysFromText` maps Bengali digits to Latin (`LocaleDigits.toAscii`) before it filters. | `leave/LeaveApply.kt` |
| low | A leave the server quarantined showed "Not sent yet" indefinitely. | **D-A3-26.** `LeaveSyncAnswer` with `LeaveItem.answered` and `held`: the outbox state QUARANTINED shows "Held for review: <reason>" with a Warning pill, and REJECTED shows "Not accepted" as before. New strings `tso_leave_held` and `tso_leave_held_reason` (en, bn). | `leave/LeaveModel.kt`, `LeaveScreen.kt`, `app-tso/.../TsoReadModels.kt`, `feature-tso` `strings_a3.xml` |
| | docs/08 Leave, Final Submit, Retailer | updated for the above | `docs/08-tso-app.md` |

### Tests (new or changed)

- `core-database` `RouteDayLockTest`: the locked types are refused and state records stay open
  (`refuses`, `isOpen` with a type, `requireOpen` throws `RouteDayFinalSubmittedException` with route, date and type).
  Captures that name no type never throw for the lock: visit_close mid-visit, due_collection and day_submit are queued,
  and a Sales Submit still closes the route-day with a plain IllegalStateException. Printing stays allowed (the print
  stays the memo's child in the visit family). The sync-answer lock reads through `refuses`.
- `app-sr` `SrDayLockCaptureTest` (new; real database through UserDatabases and the production writers): leaving the call
  after the lock arrived mid-visit ends it on the phone (no close, not restored, a new call refused). Before the lock the
  call closes `abandoned` as before. **Sales Submit** after the lock (SalesSubmitViewModel + RoomDaySubmitWriter) is
  queued with no uncaught exception. **Mark Paid** after the lock (MemoViewModel + RoomDueCollectionWriter) is queued with
  no uncaught exception. Both tests install an uncaught-exception catcher that would record a crash.
- `app-amo` `AmoDayLockCallTest` (new): a call cannot start on the closed route, and one in progress ends on the phone
  without a close and is not restored. Another route of the zone stays open.
- `feature-tso` `PeripheryLogicTest`: a pick during the opening fix takes exactly one more fix and shows the new radius. A
  pick without its own find ends on Pick. A second refresh during a run takes no extra fix.
- `feature-tso` `FinalSubmitLogicTest.getSalesDataAsksForTodayWhenItIsTapped`; new class `FinalSubmitBackTest`: the system
  back on the pickers with a failure leaves through onBack, as the on-screen back does; the system back on the routes
  returns to the pickers.
- `feature-tso` `LeaveModelTest.aQuarantinedApplicationIsHeldForReviewNotNotSentYet`,
  `LeaveScreenTest.aHeldApplicationSaysHeldForReviewWithTheReason`, and Bengali digits in
  `daysTextKeepsDigitsDropsLeadingZerosAndEmptyIsZero`.

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
- **D-A3-04 (superseded by D-A3-22 in fix round 1) The lock is enforced in RouteDayLock.isOpen.** CaptureRepository (not A3's file) checks every route-day capture
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
- **D-A3-11 One entry per drawer row; the hub flows stay as legacy.** AreasHost maps MY_TEAM, RETAILER, MY_VISIT_PLAN,
  SET_PLAN, FEEDBACK_NEW and FEEDBACK_LIST to their own entries (`MyTeamEntry`, `RetailerEntry`, `VisitPlanEntry`,
  `SetPlanEntry`, `FeedbackNewEntry`, `FeedbackListEntry`), as A1's drawer opens them. `PeripheryFlow`, `MyCallFlow`,
  `FeedbackFlow` and the two hubs keep their signatures and behaviour (the hub tiles; the legacy "saved" banner and Done)
  only because A1's `TsoNavigationTest` and `TsoFeedbackFlowTest` (A1's files, also in A1's branch) compose them; they now
  draw the new screens. In this worktree (a1-stubs, tile Home) the tiles still open MY_TEAM, MY_VISIT_PLAN and
  FEEDBACK_LIST, so RETAILER, SET_PLAN and FEEDBACK_NEW are reached through the drawer once A1 is merged first (s6 order).
- **D-A3-12 The interactive map fills the screen while live.** Screenshots 28 and 29 show the map only, so the interactive
  mode shows no list while the map is live (each pin's bubble carries the name, last seen, source and note); offline or
  without a map the list shows as in lite mode. The interactive map holds up to 300 pins (the server's cap); lite mode keeps
  100.
- **D-A3-13 When Retailer takes a fix.** One balanced fix when the screen opens, when the zone or the radius is picked, and
  on the refresh icon; never a stream (constraint 3). "Opens" is the first visit in a run of the app (the state is kept per
  user): a recreated or revisited screen shows its last answer with its "as of" and takes no fix until a pick or refresh.
- **D-A3-14 The gallery picture is claimed at Save, not at the pick.** The form stores the picked picture with A2's
  importer (`MediaComponents.gallery.import`, the core of `importFromGallery`: compressed, EXIF dropped, idempotent by
  uuid) and Save claims it for the feedback with the record (the existing `attach` before the outbox row). With
  `importFromGallery` the picture would be claimed at the pick, so a removed picture or an abandoned form would upload as an
  orphan (`discard` keeps claimed pictures). The picker is launched in feature-tso with `PickVisualMedia` (images only, no
  storage permission) because feature-tso has no core-media dependency and its build file has no A3 line; without an
  activity result registry (previews, tests) Browse Gallery says the phone has no picker instead of crashing.
- **D-A3-15 After a save.** Set Plan: toast, back to the form with the picks (the next Show Outlet ticks the saved outlets).
  Visit Query: toast, then Assign Task (Yes) or the outlets (No). Assign Task: toast, back to the outlets (the outlet is in
  Completed). Feedback: toast and back (D-TSO-MAN-36).
- **D-A3-16 Preselected zones.** Set Plan and My Visit Plan choose the zone when the reach has exactly one; My Team and
  Retailer choose the first zone of the reach (the screenshots open with a zone shown).
- **D-A3-17 Missing card values print "-".** A planned outlet without contact, channel or cluster keeps its five lines
  ("Contact: -"), so the cards line up; Select Outlets drops the empty part instead ("Salam", not "Salam | ").
- **D-A3-18 No preselected task type.** Assign Task opens with "Select Task Type"; screenshot 38's "Irregular Visit" is
  read as the rep's pick. The type is required (as before).
- **D-A3-19 Feedback words.** A category shows `label_bn` when the bundle has one, else English (s7.1 Q5). Browse Gallery has
  no glyph yet: core-ui has no picture icon and docs/32 s4 forbids screen artwork (ask 7).
- **D-A3-20 State kept per destination.** My Team, Retailer, Set Plan and My Visit Plan keep their picks for the run of the
  app (retained per user, like Final Submit); a new feedback form starts fresh after every save. The Visit Query screen no
  longer prints the outlet name above the questions (screenshot 37 has none).
- **D-A3-21 Business date on My feedback.** The card prints `business_date` (contract 1.10.0) and falls back to the date of
  `created_at` for an older row.

- **D-A3-22 The Final Submit lock is per record type and never throws in a screen.** The server refuses only B3's
  `DAY_LOCKED_TYPES` after the lock. Day_submit, attendance, print_event and amo_record are accepted as state, so the phone
  never refuses them (D-TSO-MAN-31). `FinalSubmitLock.LOCKED_TYPES` mirrors that list. `RouteDayLock` applies the lock only
  when the caller names its record type. CaptureRepository (not A3's file) names none yet, so a late Sales Submit, a
  visit_close and a due collection are queued and the server answers them. A3 enforces the lock where it owns the entry
  point:
  - A new call is refused (SR and AMO committers).
  - A call in progress ends on the phone without a close, because the server refuses a visit_close captured after the
    lock.
  - That call is not restored after a relaunch. A reopen brings it back.

  Until ask 10, a sale saved in a call that was already open when the lock arrived, and Mark Paid or a memo edit from the
  memo list, are stored and printed on the phone. The server refuses them `day_final_submitted`, and the banner counts
  them. Before this fix, the same lock-arrives window existed for anything captured before the push landed. The print
  ledger passes no type either, so a reprint after Final Submit (without a Sales Submit) stays in the memo's visit family
  with its route. The server accepts print_event after the lock. Check-out still opens on a final-locked day.
- **D-A3-23 A pick during a running Retailer fix re-runs once.** One balanced fix per pick (D-TSO-MAN-34). The fix that was
  already running is not reused for the new choice: the new choice gets its own fix once the old one ends.
- **D-A3-24 Final Submit: one back in every phase; the date at Get Sales Data.** The system back and the on-screen back are
  the same function in every phase. From the pickers both leave through onBack, so the flow's epoch starts a fresh state
  next time. The sales date is `today()` when Get Sales Data is tapped (Ui.date follows it).
- **D-A3-25 Bengali digits in the typed days field** count as their Latin digits. The field then shows the Latin number
  (D-A3-01: typed fields show the keyboard's Latin digits).
- **D-A3-26 A held leave.** An application the server quarantined shows the Warning pill "Held for review" and the line
  "Held for review: <reason>", worded with the refusal reasons. Once an admin accepts it, the server list carries it and
  the server row replaces the local one.

## Deviations

- The lock banners (`SrDayLockBanner`, `AmoDayLockBanner`) are built and tested but **not yet drawn on the SR and AMO Home**.
  The call site is in `app-sr/.../SrApp.kt` and the AMO home host, which no lane owns in s5. See ask 1. Without them the rep
  sees a refused call as a generic failed commit. Fix round 1 (D-A3-22): the phone refuses new calls and ends a call in
  progress, but the database refuses no capture type until CaptureRepository names its types (ask 10). Until then, the
  server refuses a late memo or collection `day_final_submitted`.
- `FinalSubmitApi.geo()` (per-level `/v1/admin/geo/{level}` reads) is removed; the cascade reads filter-options once.
  `zones()` stays for the flat fallback (A1's TsoNavigationTest fake implements it).

- Step 2: the legacy hub flows stay (D-A3-11); the feedback picture goes through `MediaComponents.gallery.import` plus the
  attach at Save, not `importFromGallery` (D-A3-14); Browse Gallery has no glyph (D-A3-19).
- Step 2: the interactive maps (zoom, pan, tap, the circle) cannot render under Robolectric; the tests cover the offline
  list, the model and the frame. Zoom and tap on a pilot phone remain device checks (s8 rows 28 and 29).

## Asks

1. **Lead (SrApp.kt and the AMO home have no owner; re-raised in fix round 1, the row 26 device check waits for it):** draw
   the lock banner. Since fix round 1 each host needs only one argument, marked `// tso-manual A3`:
   - SR, `SrApp.kt`, the `HomeContent(...)` call: `banner = if (data.downloading)
     stringResource(R.string.sr_bundle_downloading) else srDayLockBannerText(day),`.
   - AMO, `MainActivity.kt`, the `AmoApp(...)` call: `banners = amoDayLockBanners(day),` (the `day` var there is
     `AmoDay?`).

   Both helpers are in A3's files (SrDay.kt, AmoDay.kt). They refresh on each applied push pull while composed and are
   null or empty while the day is open. The Warning-styled composables `SrDayLockBanner` and `AmoDayLockBanner` stay
   available for a host that has a column for them. Either A3 gets these lines in its owned list, or the lead adds them.
2. **Lead / CI (goldens):** record on Linux and look at the goldens listed below; the existing area goldens changed pixels
   on purpose (new layout), including A1's three 200 percent goldens that render A3 screens.
3. **A1:** `TsoLargeTextScreenshotTests.leave_apply_200_bn` now shows the empty-type form with 3 days (LeaveForm no longer
   preselects a type); it compiles unchanged. `TsoNavigationTest` compiles and passes unchanged (see Verify).
4. **B3 (contract already says it):** the preview sends `upload_state`, `visit_kind`, `submit_seq`, `warn_route_ids`; the app
   falls back to `planned`/`had_data` and derives the warn list from `upload_state` until then. The push after the close must
   be `kind = sync_nudge`, `reason = day_final_submitted` (B3 uses Nudger, which sends sync_nudge), so the phone pulls the delta.
5. **B2 / B3:** filter-options must answer 200 for a TSO (s5 B2 rule) and include houses and zones with `house_id` and
   `territory_id` as today.
6. **A1 (step 2):** `TsoNavigationTest` and `TsoFeedbackFlowTest` compose the legacy `PeripheryFlow`, `MyCallFlow` and
   `FeedbackFlow`. Please switch them to the entries of AreasHost (`MyTeamEntry`, `RetailerEntry`, `VisitPlanEntry`,
   `SetPlanEntry`, `FeedbackListEntry`, `FeedbackNewEntry`, all `(..., nav)`); then A3 deletes the legacy flows, the hubs
   and the goldens `tso_my_call_hub_bn` and `tso_periphery_hub_en`. Also: Save of the feedback form now sits under the form
   (screenshot 43); a test that clicks it should `performScrollTo()` first on a small screen.
7. **A1 (core-ui):** a picture glyph (`AronIcons.Image`, Material "image") for Browse Gallery on screenshot 43; A3 adds it to
   the button when it exists.
8. **B3:** `TeamLocation.mock_fixes_today` (the chip), `business_date` on the feedback list, and the bundle config values
   `cfg.tso.periphery_radius_options_m`, `cfg.tso.team_location_max_age_min`, `cfg.tso.plan_max_days_ahead`,
   `cfg.feedback.max_images` (delivery "both") are what the phone reads; absent values fall back to the registry defaults.
9. **B3 / lead (offline Assign Task):** routes read from the bundle carry no SR assignee, so Assign Task offline (no
   cached `/v1/routes`) shows "SR Not Set". If the TSO bundle's route section can carry the route's SR (user id and name),
   A3 reads it; until then Assign Task needs one online routes read per 30 minutes (kept in the cache).

10. **Lead (CaptureRepository is not A3's file; fix round 1, D-A3-22):** let the database refuse the locked types again,
    without a crash. Replace `check(RouteDayLock.isOpen(db, meta)) {...}` in `requireOpenRouteDay(meta)` with
    `RouteDayLock.requireOpen(db, meta, recordType)` and pass the record type per caller:
    - Now: `recordVisitOpen` "visit" and `recordSale` "memo". Their callers already turn a refusal into "commit failed"
      or "save failed".
    - Only after ask 11: `recordDueCollection` "due_collection", `recordVisitSkip` "visit_skip", `recordVisitClose`
      "visit_close".
    - Never: `recordDaySubmit`, `recordAmo`, `recordSurveyResponse`, `recordContentView`. The server accepts them after
      the lock.

    RouteDayLockTest already covers `requireOpen`.
11. **Owners of feature-memo, feature-dayclose and the SR and AMO hosts (fix round 1):** catch
    `RouteDayFinalSubmittedException` where the capture is launched, show the lock text, and gate the actions:
    - `MemoViewModel.markPaid` and memo edit or void: catch the exception, and hide Mark Paid when
      `SrDay.finalLocked()`.
    - `SrSaleKit.closeVisit`: start with `if (day.endCallIfDayFinal()) return`. The call then ends on the phone and no
      close the server would refuse is written.
    - SR picker and AMO `ControlCallHost`: when `day.finalLocked()` or `day.routeLocked(route)` is true, show the lock
      text instead of VisitFlow's generic CommitFailed.
    - Sales Submit needs nothing, because day_submit is accepted after the lock.
12. **A1 (kit, `TsoKit.kt` `TsoDropdownField`):** the open menu should be as wide as its field. In screenshots 21 and 32
    it spans the field and covers the fields below; the a1-stubs body wraps the longest label (scratch recordings
    `tso_leave_s21_en`, `tso_mc_s32_bn`). A3's call sites need no change.

## Goldens needed (record on the Linux runner; none committed from this laptop)

Changed pixels (re-record): `tso_leave_list_bn`, `tso_leave_list_sunlight_en`, `tso_leave_empty_en`, `tso_leave_offline_bn`,
`tso_leave_error_en`, `tso_final_submit_pick_bn`, `tso_final_submit_review_en`, `tso_final_submit_review_sunlight_bn`,
`tso_final_submit_done_en`; A1's file: `tso_leave_apply_200_bn`, `tso_final_submit_pick_200_bn`, `tso_final_submit_review_200_en`.

New: `tso_leave_s19_en`, `tso_leave_s19_bn`, `tso_leave_s19_200_bn`, `tso_leave_refused_en`, `tso_leave_s20_en`,
`tso_leave_s20_bn`, `tso_leave_s20_errors_en`, `tso_leave_s22_en`, `tso_leave_s22_bn`, `tso_leave_s22_200_bn`, `tso_fs_s23_en`,
`tso_fs_s23_bn`, `tso_fs_s24_en`, `tso_fs_s24_bn`, `tso_fs_s24_200_bn`, `tso_fs_s25_en`, `tso_fs_s25_bn`, `tso_fs_s25_200_en`,
`tso_fs_warning_en`, `tso_fs_s26_en`, `tso_fs_s26_bn`.

Step 2, new (31): `tso_pe_s28_en`, `tso_pe_s28_bn`, `tso_pe_s28_200_bn`, `tso_pe_s29_en`, `tso_pe_s29_bn`,
`tso_pe_s29_radius_en`, `tso_pe_s29_200_en`, `tso_mc_s31_en`, `tso_mc_s31_bn`, `tso_mc_s31_200_bn`, `tso_mc_s32_en`,
`tso_mc_s33_en`, `tso_mc_s33_selected_bn`, `tso_mc_s33_200_en`, `tso_mc_s34_en`, `tso_mc_s34_bn`, `tso_mc_s35_pending_en`,
`tso_mc_s35_completed_bn`, `tso_mc_s36_en`, `tso_mc_s36_bn`, `tso_mc_s36_200_bn`, `tso_mc_s37_en`, `tso_mc_s37_bn`,
`tso_mc_s37_200_bn`, `tso_mc_s38_en`, `tso_mc_s38_bn`, `tso_mc_s38_200_en`, `tso_fb_s43_en`, `tso_fb_s43_bn`,
`tso_fb_s43_200_bn`, `tso_fb_s43_filled_en`, `tso_fb_list_en`. (`tso_pe_s29_radius_en` and `tso_mc_s32_en` are whole-screen
captures with the dropdown open, `captureScreenRoboImage`.)

Step 2, changed pixels (re-record): `tso_feedback_list_bn`, `tso_feedback_form_en`, `tso_feedback_empty_en`,
`tso_set_plan_bn` (now the form only), `tso_set_plan_unavailable_en`, `tso_visit_query_bn`; A1's file
`tso_visit_query_3line_bn` (TsoLargeTextScreenshotTests, the Visit Query restyled). Screenshots 27 and 30 are A1's menus.

Fix round 1, new (10): `tso_leave_s20_200_bn`, `tso_leave_s21_en`, `tso_leave_s21_bn`, `tso_leave_s21_200_bn`,
`tso_fs_s23_200_bn`, `tso_fs_s26_200_en`, `tso_mc_s32_bn`, `tso_mc_s32_200_bn`, `tso_mc_s34_200_bn`, `tso_mc_s35_200_en`.
The `tso_leave_s21_*` and `tso_mc_s32_*` cases are whole-screen captures with the dropdown open (`captureScreenRoboImage`).
No existing case changed pixels in this round.

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

Step 2 (2026-10-29, Dhaka 07:20 to 08:48; every run waited for the shared Gradle lock first):

| command | result |
|---|---|
| `gl.sh … :android:core-map:compileDebugKotlin :android:feature-tso:compileDebugKotlin :android:app-tso:compileDebugKotlin --continue` | BUILD SUCCESSFUL in 1m 2s |
| `gl.sh … :android:core-map:testDebugUnitTest :android:feature-tso:testDebugUnitTest --tests '*periphery*' --tests '*mycall*' --tests '*feedback*' --tests '*TsoNavigationTest*' --tests '*TsoFeedbackFlowTest*' :android:ui-screenshots:compileDebugUnitTestKotlin --continue` (run 1) | core-map test sources did not compile (a newline written into a string literal of the new LiteMapScreenTest case; fixed). feature-tso 99 tests, 7 failed: `rememberLauncherForActivityResult` throws "No ActivityResultRegistryOwner" in test hosts whose LocalContext is the wrapped locale context; fixed in the code (the picker and the permission launcher are created only when a registry exists; without one Browse Gallery says "no picker" and Allow offers the settings) |
| same (run 2) | core-map 27/27 (MapPiecesTest 10, LiteMapScreenTest 6, AddressResolverTest 8, OutletLiteMapTest 3); feature-tso 99 tests, 2 failed: the second click on Save (A1's TsoFeedbackFlowTest) and my error-state click missed because Save sits under the form and the saved banner pushed it down; fixed: the banner is under Save, my test scrolls to it |
| `gl.sh … :android:feature-tso:testDebugUnitTest --tests (as above) :android:ui-screenshots:testDebugUnitTest --tests '*TsoAreaScreenshotTests*' -Paron.screenshots.recordTo=<scratchpad>/goldens/A3 --continue` (run 3) | feature-tso 99/99 green; screenshots 66 of 70 written, the 4 Retailer cases failed on the same missing registry (the permission launcher); fixed as above |
| same (run 4) | BUILD SUCCESSFUL in 35s: feature-tso 15 classes, 99 tests, 0 failures (TsoNavigationTest 2, TsoFeedbackFlowTest 2, FeedbackLogicTest 8, FeedbackScreenTest 4, FeedbackTest 9, FeedbackToastTest 1, CheckerFixesTest 3, MyCallScreenTest 12, PlanLogicTest 5, PlanTest 8, QueryAndTaskTest 8, SetPlanStateTest 5, VisitPlanStateTest 3, PeripheryLogicTest 22, PeripheryScreenTest 7); TsoAreaScreenshotTests 70 cases, 70 PNGs written to the scratch dir only (none committed). Looked at s28, s29 (and the radius menu), s31, s32 (route menu), s33 bn, s34 bn, s36, s37 bn, s38, s43 and s43 at 200 percent against the owner's JPGs: same elements in the same order; colours and shapes are still the a1-stubs kit (A1's TsoNight restyles the kit bodies) |
| **Final verify** `gl.sh … :android:feature-tso:testDebugUnitTest :android:core-map:testDebugUnitTest :android:core-database:testDebugUnitTest :android:app-sr:testDebugUnitTest :android:app-amo:testDebugUnitTest :android:feature-tasks:testDebugUnitTest :android:app-tso:assembleDebug :android:app-tso:testDebugUnitTest --continue` | BUILD FAILED in 2m 21s, only on core-database: feature-tso 37 classes, 209 tests, 0 failures; app-tso 4 classes, 23 tests, 0 failures (TsoWriteReplayTest green with the changed read models); app-sr 14 classes, 39 tests, 0 failures; app-amo 5 classes, 31 tests, 0 failures; core-map UP-TO-DATE (27/27 in run 2, inputs unchanged); feature-tasks UP-TO-DATE (19/19, inputs unchanged); app-tso debug APK built. core-database 27 classes, 202 tests, **9 failed**, all Room `MigrationTestHelper` cases (SchemaMigrationTest 6, Migration67Test, Migration78Test, CheckerAmoRecordsTest.version5To6...) with "This driver is configured to open a database named 'migration-34' but 'C:\...\databases\migration-34' was requested": a Windows path in the test helper, listed as a known environment-only failure on this laptop in `docs/status/brand-packshots.md` (line 39). Not A3's: the lane's only core-database change is RouteDayLock.kt and its test (`git diff --stat 95a35b7d HEAD -- android/core-database`), and RouteDayLockTest passed in this run |

Not run in step 2: the ui-screenshots verify mode (goldens are recorded on the Linux runner); the interactive maps on a
phone (device checks); A1's TsoLargeTextScreenshotTests (its sources compile).

Fix round 1 (2026-10-29, Dhaka 10:01 to 12:12). Every run first waited for the shared Gradle lock, which other lanes'
backend runs held for about two hours in total.

| command | result |
|---|---|
| `gl.sh … --continue :android:core-database:testDebugUnitTest --tests '*RouteDayLockTest*' --tests '*RoomPrintLedgerTest*' :android:app-sr:testDebugUnitTest --tests '*SrDayLockCaptureTest*' --tests '*SrDayLockBannerTest*' :android:app-amo:testDebugUnitTest --tests '*AmoDayLockCallTest*' :android:feature-tso:testDebugUnitTest --tests '*periphery*' --tests '*finalsubmit*' --tests '*leave*' :android:app-tso:compileDebugUnitTestKotlin :android:ui-screenshots:compileDebugUnitTestKotlin` (run 1) | BUILD FAILED in 57s, only on `app-sr:compileDebugUnitTestKotlin`: a newline was written into a string literal of the new SrDayLockBannerTest case (fixed). Every other task passed: RouteDayLockTest 9/9, RoomPrintLedgerTest 9/9, AmoDayLockCallTest 1/1; feature-tso PeripheryLogicTest 25/25, PeripheryScreenTest 7/7, FinalSubmitLogicTest 16/16, FinalSubmitScreenTest 6/6, FinalSubmitBackTest 2/2, FinalSubmitCascadeTest 7/7, and all eight leave classes (LeaveModelTest 11, LeaveScreenTest 8, ...). app-tso and ui-screenshots test sources compile |
| **s5 Verify (full)** `gl.sh … --continue :android:feature-tso:testDebugUnitTest :android:core-map:testDebugUnitTest :android:core-database:testDebugUnitTest :android:app-sr:testDebugUnitTest :android:app-amo:testDebugUnitTest :android:feature-tasks:testDebugUnitTest :android:app-tso:testDebugUnitTest :android:ui-screenshots:testDebugUnitTest` | BUILD FAILED in 9m 41s, on two tasks only; both are explained below. **Green:** feature-tso 38 classes, 217 tests, 0 failures; app-sr 15 classes, 45 tests, 0 failures (SrDayLockCaptureTest 4/4 with no uncaught exception, SrDayLockBannerTest 5/5); app-amo 6 classes, 32 tests, 0 failures; feature-tasks 3 classes, 19 tests, 0 failures; app-tso 4 classes, 23 tests, 0 failures; core-map UP-TO-DATE (unchanged this round, 27/27 in step 2). **core-database:** 27 classes, 203 tests, 9 failed (RouteDayLockTest 9/9 green). The 9 are the same Room `MigrationTestHelper` cases as in step 2 (SchemaMigrationTest 6, Migration67Test, Migration78Test, CheckerAmoRecordsTest): "This driver is configured to open a database named 'migration-NN' but 'C:\...' was requested", the Windows-path environment failure. **ui-screenshots (verify mode):** 16 classes, 355 tests, 82 failed; every other class passed. The 82 are all A3 screens: in TsoAreaScreenshotTests (80 cases), 63 have "missing golden" (exactly the new names listed under Goldens: 21 from step 1, 32 from step 2, 10 from this round) and 15 fail the Roborazzi compare (exactly the "changed pixels" lists of steps 1 and 2); `my_call_hub_bn` and `periphery_hub_en` pass. In A1's TsoLargeTextScreenshotTests, the 4 cases that render A3 screens fail the compare (`leave_apply_200_bn`, `final_submit_pick_200_bn`, `final_submit_review_200_en`, `visit_query_three_line_bn`; listed under ask 2 and Goldens) and `home_200_bn` passes. The lead records these goldens on the Linux runner (ask 2) |
| `gl.sh … :android:ui-screenshots:testDebugUnitTest --tests '*TsoAreaScreenshotTests*' -Paron.screenshots.recordTo=<scratchpad>/goldens/A3 --continue` | BUILD SUCCESSFUL in 2m 29s. 80 PNGs were written to the scratch dir only; none committed. I compared `tso_leave_s21_en` and `tso_leave_s21_200_bn` with JPG 21, and `tso_mc_s32_bn` with JPG 32. The elements and their order match: Leave Type with Casual, Sick and Earn (Bangla labels in bn), then Number of days "0", Reason and Apply; the route list opens under Select Route. Differences, all in the kit: the colours are still the a1-stubs look, and the menu is narrower than the field (ask 12) |

## Next step

Step 2 and fix round 1 are done (above). What is left on the lane, in order:

1. The lead or the file owners act on asks 1, 10 and 11 (fix round 1). Once CaptureRepository passes the record type
   (ask 10), rerun `RouteDayLockTest` and `SrDayLockCaptureTest`. `SrDayLockCaptureTest` expects Sales Submit to stay
   queued, because day_submit is never locked. If due_collection is typed before MemoViewModel catches the refusal, Mark
   Paid would fail the uncaught-exception check: keep the order in ask 10.
2. When A1 has switched `TsoNavigationTest` and `TsoFeedbackFlowTest` to the AreasHost entries (ask 6), delete
   `PeripheryFlow`, `MyCallFlow`, `FeedbackFlow`, `PeripheryHub`, `MyCallHub`, the legacy saved-banner path of
   `FeedbackFormScreen` (`onSaved == null`) and the cases `my_call_hub_bn` / `periphery_hub_en`; list their goldens for
   deletion.
3. Put A1's picture glyph on Browse Gallery once `AronIcons.Image` exists (ask 7).
4. Read the route's SR from the bundle for an offline Assign Task if B3 adds it (ask 9).
5. Device checks on a pilot phone against dev:
   - s8 row 26 ("SR phone shows lock"), after ask 1: with the TSO's "Submit anyway", the SR's Sales Submit, Mark Paid
     and leaving an open call do not crash. A new call is refused and the banner shows.
   - Rows 28, 29, 31 to 38 and 43: My Team zoom and tap; the Retailer circle and one fix per pick (also a pick during
     the opening fix); the gallery picker on an Android 9 phone and an Android 11+ phone; a plan and a delegated task
     reaching the SR's bundle with its route.
6. After A1's TsoNight kit is merged, the lead records the goldens on the Linux runner and looks at them against the
   owner's JPGs. The scratch recordings here use the a1-stubs kit, so the colours are not yet the night look.
