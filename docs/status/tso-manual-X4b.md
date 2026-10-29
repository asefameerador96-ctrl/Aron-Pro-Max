# TSO manual build, lane X4b (integration round: TSO app integration): status

Lane X4b of `docs/requests/tso-manual-build-2026-10.md` s10 (lead rulings R-W-10 and R-W-12) and the cross-lane asks between
A1, A2 and A3 in `docs/status/tso-manual-A1.md`, `-A2.md` and `-A3.md`. Branch `tso-man/X4b`, worktree
`.claude/worktrees/tso-X4b`, base `tso-man/X1` (`f4ba4501d`). 2026-10-29. No push, no pull request. Lane X4a owns
core-database, feature-memo, feature-dayclose, app-sr and app-amo; none of them is touched here.

## Done

| Item | What | Proving test |
|---|---|---|
| 1. R-W-10 (LV-16) | The leave card's "Not accepted:" / "Held for review:" reason is a leave text (bn and en) for the four leave refusal codes of D-B3-15: `content_duplicate` "it overlaps a leave already applied for", `schema_invalid` "the leave type is not recognised; download fresh data", `scope_out_of_reach` "only a TSO may apply for leave", `business_date_out_of_window` "the first day is outside the allowed window". Any other code shows the stored bundle's `reason_texts` entry of that code in the app's language (read offline with the unsent list), else "the server did not accept it". No contract change. | feature-tso `LeaveModelTest.refusalCodesMapToTheirReasonWords`, `theBundleReasonTextsAreReadAndABadSectionGivesNone`, `anAnswerCarriesTheBundleTextOfItsCode`; `LeaveScreenTest.theFourLeaveCodesHaveLeaveTextsInEnglishAndBangla`, `theFourLeaveCodesInBangla`, `anotherCodeShowsTheBundleReasonText`, `anotherCodeShowsTheBundleReasonTextInBangla`, `aRefusedApplicationSaysNotAcceptedWithTheReason`, `aHeldApplicationSaysHeldForReviewWithTheReason` |
| 2. R-W-12 (1) push and inbox | MainActivity reads the tapped notice's open extra in onCreate and onNewIntent through `TsoPushOpen` (app-tso, new): the tickets extra becomes a `TsoOpenRequest` that calls A2's `TicketPush.open` (Ticket Dashboard with the reason as its argument, so it reads online on open, D-A2-30); the inbox extra opens the inbox, whose `support_ticket` row opens TICKET_DETAIL with the uuid (D-A1-28, unchanged). TsoApp applies a request once on the Dashboard (stack emptied first; back returns to the Dashboard). While shown, the Ticket Dashboard, All Tickets and the ticket detail read again on `PushNotice.TicketUpdated` and `InboxUpdated` (`RefreshOnTicketPush`, no timer). | app-tso `TicketPushNavigationTest` (FCM data, the launch intent `PushNotices.openIntent` builds, `TsoPushOpen`, the route in TsoApp: ticket push, inbox push then ticket row, other pushes open nothing, refresh on push only while shown, a failed read keeps listening); feature-tso `TsoShellTest.aPushRequestWhileAScreenIsOpenLandsOnTheDashboardWithItsArgument`, `aTappedPushOpensItsScreenOnTheDashboard`; app-tso `TicketWriteReplayTest.aTicketPushOpensTheTicketDashboardAndASaveOpensAllTickets` (unchanged, green) |
| 3. R-W-12 (2) entries replace the flows | `TsoNavigationTest` and `TsoFeedbackFlowTest` compose `MyTeamEntry`, `RetailerEntry`, `VisitPlanEntry`, `SetPlanEntry`, `FeedbackListEntry`, `FeedbackNewEntry` as AreasHost wires them (every area drawer row opens its real screen with its own title; feedback Save returns to the list and the next form, from the drawer, is fresh with a new uuid). Deleted: `PeripheryFlow`, `MyCallFlow`, `FeedbackFlow`, `PeripheryHub`, `MyCallHub` and what only they used (`TsoMenuGrid`, `TsoMenuTile`, `tsoIcon`, `PeripheryTags.TEAM/RETAILER`, `MyCallTags.SET_PLAN/VISIT_PLAN`, strings `tso_pe_team`, `tso_pe_retailer` in en and bn), the hub screenshot cases `my_call_hub_bn`, `periphery_hub_en` (they were still in TsoAreaScreenshotTests) and the PNGs `tso_my_call_hub_bn.png`, `tso_periphery_hub_en.png`. | feature-tso `TsoNavigationTest` (2), `TsoFeedbackFlowTest` (2) |
| 3a. integration defect found by 3 | A1's Home disc sat over A3's actions: the bottom-bar button (Add new feedback, Set Plan, Submit, Assign) and a form's last button (Save, Show Outlets) were under the disc, so a tap went Home (two A3 feedback tests failed on the merged base for this reason, see Verify). `TsoFrame` now keeps its content above the disc band (`TsoFabBand` 78 dp) and lifts a bottom bar so the disc overlaps only its lower 16 dp, as screenshots 31, 33 and 43 draw it. The Login & Bikroy Joma list no longer adds a second FAB padding. | feature-tso `TsoFrameFabTest` (3); `FeedbackScreenTest.formShowsErrorsThenSavesOffline` and `FeedbackTest.saveGoesBackAndTheNextVisitIsAFreshForm` (failed before, pass now); `MyCallScreenTest` (12) on the 360 x 800 dp field phone |
| 4. R-W-12 (3), (4) core-ui | `AronIcons.Image` (Material "image"); `NightButton` / `TsoGradientButton` take an optional decorative icon in the label's colour; Browse Gallery on the feedback form shows it (screenshot 43). `AronDropdown` (behind `TsoDropdownField`) measures its field and gives the open list the same width, so it spans the field and covers the fields below (screenshots 21 and 32). | core-ui `AronDropdownWidthTest` (short labels, a long label, the glyph) |
| 5. R-W-12 (5), (6), (8) screenshots | `TicketScreenshotTests.night = AronLook.TsoNight`. Drawer goldens of the Tickets group: `tso_drawer_tickets_en` and `tso_drawer_tickets_bn` (TsoScreenshotTests) and `tso_drawer_tickets_200_bn` (TsoLargeTextScreenshotTests, renamed from `tso_drawer_200_bn`, never recorded). Retired: RoleHomeShot `tso_home_bn_light` / `tso_home_bn_sunlight` and the TsoHome cases of ScreenshotTests, with `tso_home_bn_light.png` and `tso_home_bn_sunlight.png`; then `RoleHomes.tsoTiles`, the `RoleHomes.TsoTile` ids (no user left) and the six `rh_tso_*` tile labels (en and bn) in feature-home (`rh_tso_device_otp` stays: it titles the Device OTP screen). Also deleted the orphan PNGs A1 listed as obsolete: `tso_home_parity_bn`, `tso_home_parity_offline_en`, `tso_home_parity_sunlight_bn`, `tso_home_200_bn`, `tso_dashboard_en`, `tso_dashboard_bn`, `tso_dashboard_empty_offline_en`, `tso_dashboard_sunlight_bn`, `tso_dashboard_routes_bn`, `tso_dashboard_routes_empty_en`, `tso_dashboard_zones_en` (no case draws them). | `:android:ui-screenshots:compileDebugUnitTestKotlin`; feature-home `testDebugUnitTest` |
| 6. R-W-12 (7) storage permissions | `ManifestPermissionAuditTest.mergedAppManifestsRequestNoStorage` forbids READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE, READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED and MANAGE_EXTERNAL_STORAGE in the merged manifests of app-sr, app-amo and app-tso (an SDK-capped request counts). | core-system `ManifestPermissionAuditTest` (`mergedAppManifestsRequestNoStorage`, `theStorageRuleSeesAnSdkCappedRequest`) |
| 7. R-W-12 (9) offline Assign Task | The plan source reads the bundle's routes with their SR first: `sr_user_id` / `sr_name` of each route snapshot, which the sync engine keeps raw in `route.<id>` (`ReferenceRepository.routeExtras`, no core-database change). No request at all when the bundle carries them; the online `GET /v1/routes` read (kept 30 minutes) stays only for a bundle without the members (an older server). The bundle routes are taken on the trusted business date (`TsoEnv.businessDate`), not the system clock. | app-tso `TsoAssignTaskOfflineTest` (real database, no network: the SR named, the queued task's `assignee_user_id` is the bundle's SR, a vacant route says SR Not Set and queues nothing; an older bundle tries the online read, then keeps the bundle routes); `TsoBundleReadsTest` (`srOf`, `routesWithSr`) |

### Cross-lane asks between A1, A2 and A3, after the merge

| Ask | State |
|---|---|
| A1 to A3 (1) to (5): the nav frame, entries for RETAILER, SET_PLAN, FEEDBACK_NEW, tests through the drawer | done by A3 before; the tests now compose A3's entries (item 3) |
| A1 to A2: drawer Tickets group, no Home button, inbox row to TICKET_DETAIL, push to TICKET_DASHBOARD, refresh on push | done: A2's entries read the uuid; push through `TicketPush.open`; refresh on `TicketUpdated` / `InboxUpdated` (item 2) |
| A1 to the lead: retire the role-home TSO goldens, then `tsoTiles` | done (item 5) |
| A2 to A1: push via `TicketPush.open` in MainActivity (onCreate, onNewIntent); drawer goldens bn and 200 % | done (items 2, 5) |
| A2 to the lead: `TicketScreenshotTests.night` = TsoNight; storage permissions in ManifestPermissionAuditTest | done (items 5, 6) |
| A3 ask 6 (switch the tests, then delete flows, hubs and their goldens) | done (item 3) |
| A3 ask 7 (`AronIcons.Image` on Browse Gallery), ask 12 (dropdown as wide as its field) | done (item 4) |
| A3 ask 9 (offline Assign Task from the bundle's route SR) | done on the phone (item 7); X2 sends the members (ask 1 below) |
| A3 asks 1, 10, 11 (SR and AMO lock banner, CaptureRepository, memo and day-close callers) | lane X4a (R-W-11), not here |

## Decisions (D-X4b-NN; the lead may move them to DECISIONS.md)

- **D-X4b-01 Exactly the four leave codes.** R-W-10 names four codes; the A3 mapping also sent `payload_conflict`,
  `no_assignment_on_date` and `user_disabled` to leave words ("the same application was already sent", "you cannot apply
  for this date"). They now fall back to the bundle's reason text like every other code. The bundle text is shown in the
  app's language only; a code whose text is missing in that language shows the generic words (no English in a Bangla
  card). The bundle text keeps its own sentence case and full stop after "Not accepted:".
- **D-X4b-02 The push open request is a navigator call.** `TsoApp(openRequest: TsoOpenRequest?)` replaces
  `TsoScreen?` so the host can call A2's `TicketPush.open(nav, extra)` itself (A2's first choice); TsoApp empties the
  stack first, so the screen opens on the Dashboard as A1 had it. `TicketPush.target` also accepts the open extra
  `tickets` that A1's push layer puts on the launch intent (it already accepted the reason `ticket_updated`).
- **D-X4b-03 Ticket screens also re-read on an inbox push.** B1 sends a desk update with push reason `notification`
  today (`Inbox.PUSH_REASON`, R-W-06 description), which the phone reads as `PushNotice.InboxUpdated`; refreshing only on
  `TicketUpdated` would never fire. One read per push, only while a ticket screen is composed; a load already running
  absorbs the push.
- **D-X4b-04 The frame reserves the Home disc band.** Screenshots 31, 33 and 43 draw the content and the bottom action
  above the house, which sits in its own band. `TsoFrame` pads its content by 78 dp (the 64 dp disc and its 14 dp margin)
  when it shows the Home button and has no bottom bar, and lifts a bottom bar to 62 dp so the disc overlaps 16 dp of the
  52 dp button (its centre stays above the disc). A screen inside a TsoFrame needs no `TsoFabPadding` any more; the root
  Dashboard (drawn without TsoFrame) keeps it. MyCallScreenTest now runs on the 360 x 800 dp field phone (as the goldens):
  the default 470 dp Robolectric screen leaves the outlet list no room once the band is reserved.
- **D-X4b-05 Kit drawables kept.** The `aron_ic_tso_*`, `aron_ic_kpi`, `aron_ic_sales_journey`, `aron_ic_task_delegation`
  vector drawables of core-ui were only drawn by `tsoIcon` (deleted); they are generated by `tools/ui-kit/aron_icons.py`
  and documented in the ui-kit status files, so they stay for that tool's owner to retire.
- **D-X4b-06 The open dropdown matches its field in every look.** Only the TSO app uses `AronDropdown` today, so no SR or
  AMO screen changes. A Material menu item is at most 280 dp wide; on a wider field the menu surface still spans the field.
- **D-X4b-07 Orphan goldens deleted.** A1 listed eleven TSO PNGs whose cases are gone; no test draws them, and R-W-15
  re-records every TSO golden after this round, so they are deleted here with the two hub PNGs and the two tile-Home PNGs.
- **D-X4b-08 Bundle SR rule.** The bundle carries the SR when any active route's snapshot has the `sr_user_id` key; then a
  route with a JSON null, or without the key, is vacant. An id without a name, or a non-numeric or zero id, counts as not
  carried. With no route carrying it, the online read runs as before (D-X1-05: the same SR `GET /v1/routes` names).
- **D-X4b-09 One line of the integration branch taken.** X1 is based before `a1b5041b3`, so core-ui's
  HardcodedStringScanTest failed here on the default leave type labels (`LeaveApply.kt:22`). The same one-line change is
  applied here, identical, so the merge sees one change and the lane's verify is green.

## Deviations (files outside the X4b list)

- `android/app-tso/build.gradle.kts`: `testImplementation` of the Compose BOM and `ui-test-junit4` (TicketPushNavigationTest
  composes TsoApp with the test rule; no test manifest, the host activity is registered with Robolectric's package manager
  as app-sr's SrFrameTest does).
- `android/feature-tso/.../TsoDashboardScreens.kt` (A1): the Login & Bikroy Joma list's bottom padding is 16 dp, because the
  frame now keeps it above the Home disc (D-X4b-04).
- `android/feature-tso/src/test/.../mycall/MyCallScreenTest.kt` (A3): 360 x 800 dp and scroll-then-click on Show Outlets and
  the route field (D-X4b-04).
- `android/core-system/src/test/.../ManifestPermissionAuditTest.kt`: named by the brief (item 6).
- `android/feature-tso/.../leave/LeaveApply.kt` line 22: the `// i18n-ignore` comment of the integration branch's `a1b5041b3`,
  byte for byte (D-X4b-09).

## Asks

1. **X2:** send `RouteSnapshot.sr_user_id` / `sr_name` on every TSO bundle route entry and in the delta's `routes_added`
   (X1 ask 2); explicit nulls for a vacant route are clearest, though the phone also reads a missing pair as vacant when
   other routes carry it. Until the server sends them, Assign Task needs the online routes read, as before.
2. **Lead (goldens, R-W-15):** record on the Linux runner the list under "Goldens needed"; nothing here was recorded.
3. **Lead (information):** the frame change (D-X4b-04) moves pixels on every TSO screen with the Home button; it is the fix
   for taps going Home instead of Save, Set Plan, Submit or Assign on a real phone.
4. **Device lab:** tap a `ticket_updated` notification and a `notification` notification on the pilot TSO phone (app in the
   background and closed): the Ticket Dashboard, resp. the inbox, opens on the Dashboard; tap the ticket row. MainActivity's
   two lines are not exercised by a unit test (a Hilt activity); the pieces they call are.

## Verify (this worktree, local laptop through gl.sh; CI not run, nothing pushed)

Final run, 2026-10-29 20:25 to 20:28 (Asia/Dhaka), on `231530724` (the status file added after it):

| command | result |
|---|---|
| `gl.sh <worktree> :android:feature-tso:testDebugUnitTest :android:feature-tickets:testDebugUnitTest :android:app-tso:testDebugUnitTest :android:core-ui:testDebugUnitTest :android:feature-home:testDebugUnitTest :android:core-media:testDebugUnitTest :android:app-tso:assembleDebug :android:ui-screenshots:compileDebugUnitTestKotlin :android:core-system:testDebugUnitTest --tests "com.aktcl.aron.core.system.permission.ManifestPermissionAuditTest" --continue` | BUILD SUCCESSFUL in 1m 8s (600 tasks, 54 executed). feature-tickets, core-media and core-system were up to date from the run just before (20:25 to 20:26, same inputs, all green); that run failed only on the two defects fixed in `7d39f6868` (a string still used) and `231530724` (D-X4b-09) |
| feature-tso | 43 classes, 261 tests, 0 failures (LeaveModelTest 13, LeaveScreenTest 12, TsoShellTest 14, TsoNavigationTest 2, TsoFeedbackFlowTest 2, TsoFrameFabTest 3, MyCallScreenTest 12, FeedbackScreenTest 4, FeedbackTest 9) |
| feature-tickets | 4 classes, 56 tests, 0 failures |
| app-tso | 10 classes, 71 tests, 0 failures (TicketPushNavigationTest 5, TsoAssignTaskOfflineTest 2, TsoBundleReadsTest 4, TicketWriteReplayTest 14, TsoWriteReplayTest 14) |
| core-ui | 24 classes, 159 tests, 0 failures (AronDropdownWidthTest 3; HardcodedStringScanTest green) |
| feature-home | 12 classes, 68 tests, 0 failures |
| core-media | 14 classes, 95 tests, 0 failures |
| core-system ManifestPermissionAuditTest | 7 tests, 0 failures (`mergedAppManifestsRequestNoStorage`, `theStorageRuleSeesAnSdkCappedRequest`; the merged manifests of app-sr, app-amo and app-tso were processed for it, not edited) |
| app-tso debug APK | built (`app-tso-debug.apk`, 29.1 MB) |
| ui-screenshots | `compileDebugUnitTestKotlin` green; the screenshot tests were not run (goldens are recorded on Linux, R-W-15) |
| `python3 tools/ci/money-float-scan.py` | 13/13 allowed, 0 not allowed, 0 stale |
| `python3 tools/ci/date-pin-scan.py` | 106 literals, 106 baselined, 0 new |
| `python3 tools/ci/wallclock-scan.py` | report only: 12 offenders in 4 modules, none in this lane's files (the two switched TSO tests now use a fixed now, `6764f9006`) |

On the way: the merged base already failed `FeedbackScreenTest.formShowsErrorsThenSavesOffline` and
`FeedbackTest.saveGoesBackAndTheNextVisitIsAFreshForm` (run on the committed base with the work in progress stashed: 13
tests, 2 failed; the Home disc took the Save tap), fixed by D-X4b-04. The laptop-only failures named in the brief (Room
MigrationTestHelper paths, SyncConvergenceFuzzTest timing) are in core-database and core-sync, which this lane did not run.

## Goldens needed (record on the Linux runner; none recorded or committed here)

- New name: `tso_drawer_tickets_200_bn` (was `tso_drawer_200_bn`, never recorded); `tso_drawer_tickets_bn` and
  `tso_drawer_tickets_en` (A1's cases, not recorded yet).
- Leave texts (R-W-10): `tso_leave_refused_en`.
- Browse Gallery glyph: `tso_fb_s43_en`, `tso_fb_s43_bn`, `tso_fb_s43_200_bn`, `tso_fb_s43_filled_en`.
- Dropdown as wide as its field (whole-screen captures with the list open): `tso_leave_s21_en`, `tso_leave_s21_bn`,
  `tso_leave_s21_200_bn`, `tso_mc_s32_en`, `tso_mc_s32_bn`, `tso_mc_s32_200_bn`, `tso_pe_s29_radius_en`.
- Frame keeps content above the Home disc (every screen with the Home button): `tso_pe_s28_en`, `tso_pe_s28_bn`,
  `tso_pe_s28_200_bn`, `tso_pe_s29_en`, `tso_pe_s29_bn`, `tso_pe_s29_200_en`, `tso_mc_s31_en`, `tso_mc_s31_bn`,
  `tso_mc_s31_200_bn`, `tso_mc_s33_en`, `tso_mc_s33_selected_bn`, `tso_mc_s33_200_en`, `tso_mc_s34_en`, `tso_mc_s34_bn`,
  `tso_mc_s34_200_bn`, `tso_mc_s35_pending_en`, `tso_mc_s35_completed_bn`, `tso_mc_s35_200_en`, `tso_mc_s36_en`,
  `tso_mc_s36_bn`, `tso_mc_s36_200_bn`, `tso_mc_s37_en`, `tso_mc_s37_bn`, `tso_mc_s37_200_bn`, `tso_mc_s38_en`,
  `tso_mc_s38_bn`, `tso_mc_s38_200_en`, `tso_fb_list_en`, `tso_feedback_list_bn`, `tso_feedback_form_en`,
  `tso_feedback_empty_en`, `tso_set_plan_bn`, `tso_set_plan_unavailable_en`, `tso_visit_query_bn`,
  `tso_visit_query_3line_bn`, `tso_login_status_en`, `tso_login_status_bn`, `tso_login_status_offline_en`,
  `tso_login_status_200_bn` (plus the s32, s43 and s29 radius cases above).
- Tickets in `AronLook.TsoNight` (every non-Sunlight case of TicketScreenshotTests): `tickets_dashboard_en`,
  `tickets_dashboard_bn`, `tickets_dashboard_200_bn`, `tickets_dashboard_empty_bn`, `tickets_dashboard_offline_unsent_en`,
  `tickets_list_en`, `tickets_list_bn`, `tickets_list_200_en`, `tickets_list_no_match_en`, `tickets_list_resolved_en`,
  `tickets_list_resolved_bn`, `tickets_list_resolved_200_bn`, `tickets_details_en`, `tickets_details_bn`,
  `tickets_details_200_en`, `tickets_details_offline_bn`, `tickets_details_open_thread_en`, `tickets_quick_en`,
  `tickets_quick_bn`, `tickets_quick_200_bn`, `tickets_quick_sheet_en`, `tickets_quick_sheet_bn`,
  `tickets_quick_sheet_200_en`, `tickets_create_en`, `tickets_create_bn`, `tickets_create_200_en`, `tickets_issue_types_en`,
  `tickets_issue_types_bn`, `tickets_issue_types_200_bn`.
- Retired (cases and PNGs deleted): `tso_my_call_hub_bn`, `tso_periphery_hub_en`, `tso_home_bn_light`,
  `tso_home_bn_sunlight` (and RoleHomeShot's `rolehome_tso_bn_light` / `_sunlight`, never stored), the orphans
  `tso_home_parity_bn`, `tso_home_parity_offline_en`, `tso_home_parity_sunlight_bn`, `tso_home_200_bn`, `tso_dashboard_en`,
  `tso_dashboard_bn`, `tso_dashboard_empty_offline_en`, `tso_dashboard_sunlight_bn`, `tso_dashboard_routes_bn`,
  `tso_dashboard_routes_empty_en`, `tso_dashboard_zones_en`.

## Not done

- Goldens (R-W-15): none recorded here (Windows laptop).
- MainActivity's onCreate / onNewIntent push lines run only on a device (ask 4).
- The kit drawables of D-X4b-05.

## Next step

The lead merges `tso-man/X4b` after X4a, records the goldens on Linux, and runs the device check of ask 4.
