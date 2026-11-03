# TSO manual build: cloud status (lane tso-manual #1)

## HANDOVER (tso-manual #1 to its successor, 2026-11-03 22:05 UTC)
**State.** lane/tso-manual head 86236433bb6740cae5276c083ecd124b0ecdb043 (contains INT train 66 = 3421ad4b). The lead's all-areas gate run is on that sha; do NOT push lane/tso-manual until the lead says. Work goes to lane/tso-manual-wip-* branches. Note: a push to lane/tso-manual while a dispatched run is going auto-cancels that run (workflow concurrency); one run per head.
**Landed in batch 1** (one commit each, see "Batch 1" below): TSO-look harness fix and 84 re-recorded goldens; UI fixes for conformance rows 35, 26, 25, 33, 43; 31-tile Quick Ticket test, By Channel toggle test, login bn/200 bn and 7 tall goldens; owner-named TSO APK copy in ci.yml (tools/ci/tso-apk-copy.sh + test); VisitPlansTest date fixes; 'As of' only when kept; D-TSO-MAN-49 defaults (accept_and_flag, auto close off) and D-TSO-MAN-50 (white button labels on darkened fills, 4.5:1 group). Opus checkers: PASS on the day-lock defaults diff (9e0c2423), on the token/contrast diff (8f686a13); the UI/CI diff failed once and was fixed. The last commits after 8f686a13 (comments, routeCard contrast test) are covered only by the lead's gate checker.
**Follow-ups (lead's decisions).** (a) TsoBottomBar (TsoKit.kt:338, used by ChangePasswordScreen) draws AronPrimaryButton: white label on the cyan bar ramp 3BB8D8-78A8F2 about 2.3:1; route it to NightButton Cyan (a successor task, not train 67). (b) Batch 2 after landing: the bright-blue bottom glow on Final Submit, Target Status and login-status pages, and cosmetic rows 8 (title wraps at 360 dp), 14 (ticket-details button on the chips' row), 16 (sheet position), 30 (Set Plan icon glyph), 41 (shrinking % column). (c) NOT started, not mine: X2 (server members for the app: sr_user_id/sr_name on the bundle, monthly_target_qty, can_submit, till-date bp) and X4a (SR/AMO day lock).
**How goldens are recorded (container, Linux).** `export ANDROID_HOME=/opt/android-sdk` (install with tools/android-sdk.sh), then `./gradlew :android:ui-screenshots:testDebugUnitTest -Proborazzi.record=true` (about 70 s); record mode rewrites every PNG, so `git status` and keep only the intended changes (tso_*, tickets_*); never keep a laptop-recorded PNG (the 21 laptop ones failed verify on the CI runner; container recordings match the runner: 394 of 415 passed there). Verify with `./gradlew :android:ui-screenshots:cleanTestDebugUnitTest :android:ui-screenshots:testDebugUnitTest`. `bootstrapRecord` stays false. Harness rule: every TSO golden test wraps screens in `AronTheme(language, dark = !sunlight, sunlight = sunlight, tier = GlassTier.B, reducedMotion = true, tso = true)` (TsoAreaScreenshotTests.shot/menuShot now do; the 200% class uses dark = true); a tall golden is a method-level `@Config(qualifiers = "w360dp-h1400dp")`.
**Container notes.** Local Postgres for backend tests: `pg_ctlcluster 16 main start`, role/db aron_test, trust rule for aron_test in /etc/postgresql/16/main/pg_hba.conf, `ARON_TEST_PG_URL=jdbc:postgresql://localhost:5432/aron_test?user=aron_test`; Maven Central sometimes answers 429 for version ranges (retry after a minute); gradle commands over about 9 minutes must run in the background. Full local Android job (assemble 3 APKs, testDebugUnitTest, lintDebug) takes about 9 minutes.
**Residual owner-visible differences** are listed under "Remaining owner-visible differences" below (fills darker than the screenshots, no bottom glow, cosmetic rows).

## Batch 2 (tso-manual #2, lane/tso-manual-wip-batch2)
Items in the lead's order, one commit each (golden PNGs ride in the commit that changed them or in the "goldens: re-recorded" commit):
1. Tickets harness: `AronTheme(dark = !sunlight, tso = true)` like TsoAreaScreenshotTests, manual LocalAronLook layering removed. Recording in the container left 36 of 37 `tickets_*` PNGs byte-identical (the old layering already drew the same pixels); only `tickets_dashboard_sunlight_en` changed.
2. Map fallback list (rows 28, 29): ink and muted ink from `LocalAronLook` when the look has the TSO roles (`night != null`); the default and Sunlight looks pass `Color.Unspecified` and inherit the content colour as before. The shared `LiteMapScreen` is also drawn by the AMO Team Location screen: the first version of this change did change the AMO list (black to 111111 / 505050), the lead's checker caught it, and it is now gated; golden `map_fallback_list_default_en` (MapFallbackListScreenshotTests) pins the default look and verifies against the pre-batch screen.
3. Page glows: `NightPageGlow` (`nightPage(n, glow)`), tokens `glowBottom` 3D9FFF (dome squashed to rise 72 dp, inside the 78 dp Home band, so no content scrolls over it: checker finding, TsoGlowRiseTest) and `glowTop` 0A3056 (title 7:1, red 4.5:1 asserted); bottom dome on Final Submit, Target Status and Details, login status; top wash on Leave and Leave Apply; Sunlight draws none. `AronNightTopBar(onPage = true)` inside a TsoFrame so the glow is not cut at the bar. TokenContrastTest `tsoTopGlowKeepsInkReadable` (white 7:1, muted 4.5:1 on the top glow).
4. Row 19 leave card: teal-to-blue gradient (126985 to 16507F), teal edge, solid orange badge B45C00 with white bold label (4.7:1), day disc that grows with the font (the 200 % Bangla clipping). Test `tsoLeaveCardInkAndOrangeBadgeReachFourPointFive`.
5. Row 25 route cards 3F7AAD (white 4.56:1), no 0.55 alpha on not-due routes, D-TSO-MAN-51.
6. Small items: info-dialog scrim 0.35, dropdown list near-black navy over its field, helper line outside the glowing field (no dark patch), glowing Date field on Leave Apply, Assign Task info card navy with an aqua edge, bottom bar clear of the Home disc (rows 33, 38: `TsoBarOverFab` = band + 4 dp, no overlap). Row 43 bottom-bar notch: not done (optional).
7. Row 34 behavioural test `MyCallScreenTest.myVisitPlanShowsTodayAndTheZoneAndNeedsARouteBeforeShowOutlet`.
8. `TsoBottomBar` primary is `NightButton` Cyan.
9. Rows 8 (titles shrink 22 to 16 sp to fit one line), 30 (crossed-out storefront for Set Plan), 41 (one figure size per column, % column wider). Rows 14 and 16 are not changed (below).

### Mismatches vs owner screenshots (after batch 2)
- Row 14: Submit Review & Close wraps under the pills at 360 dp: the pills (about 190 dp) plus the button (about 200 dp) exceed the 328 dp row; the owner's phone is about 410 dp wide. The flow row wraps by design (never squeezes). Accepted.
- Row 16: our Quick Report sheet is taller (extra optional username field, D-TSO-MAN-20/21), so it starts higher than the owner's. Accepted.
- Row 19: card gradient and orange badge follow the owner's; the orange is darker than the owner's FF9800 (white label at 4.7:1, D-TSO-MAN-50).
- Rows 23 to 26, 39 to 42: the bottom dome is the owner's bright blue but only 72 dp high (the owner's is about 155 dp): a taller dome would put scrolling white text on 2.76:1. Rows 19 to 22: the top wash is darker than the owner's (0A3056 vs about 0E3F70) so the gradient title holds 7:1.
- Row 25: card blue 3F7AAD is a little darker than the owner's 4682B4 (white-label contrast).
- Row 21: our list is the field's width (the owner's runs edge to edge) and covers the field it opened from.
- Rows 28, 29: a stale person row stays dimmed (STALE_ALPHA); live rows are white.
- Row 30: the Set Plan glyph is our storefront with a slash (approximation of the owner's icon).
- Row 41: the "Remaining" header hyphenates (Re-main-ing); the owner's wraps "Remainin / g".
- Row 43: bottom-bar notch not drawn.

## Batch 3 (tso-manual #2, lane/tso-manual-wip-batch3, based on c3f5df90)
1. Row 102: `TsoGlow.of(screen)` is the one table the app-tso hosts and the screenshot frames read; the login-status, Target Status and Target Details goldens now draw the bottom dome (TsoGlowTest).
2. Row 28: a stale or no-fix person row in the TSO map fallback list is drawn in the muted ink (B8C2E5, 10:1 on the page) instead of 0.5 alpha (the "Mock GPS today" line was 3.4:1); SR/AMO keep the alpha; `map_fallback_list_default_en` is unchanged.
3. Row 34 and Set Plan pickers: glowing Date field with the calendar icon at the start, white Select Route placeholder (same ink as a chosen value; Set Plan's pickers share the component), 4.5:1 asserted.
4. Row 38: glowing Date field, plain Comment box (page colour, quiet outline, no glow), the "Assigned to" line removed (the success banner "Task assigned to <SR>" carries the same name; string removed from both languages).
5. Leave list: 'As of' only for a kept copy (LeaveListState); the two tests that expected it on a fresh read now assert none there and the Bangla test uses a kept copy.
6. Row 21 panel over the Date field's border line: not changed (not a one-line offset: the panel is three rows tall).

## Task 1: goldens recorded on Linux (2026-11-03)
- Recorded with `./gradlew :android:ui-screenshots:testDebugUnitTest -Proborazzi.record=true` in the lane container (Robolectric, Linux, Maven mirror).
- 140 new PNGs committed in `android/ui-screenshots/src/test/screenshots/` (the 161 failing tests share names, some tests write several variants; 161 failures in run #1734 were all "missing golden"). The 21 existing goldens that record mode rewrote were reverted (they already verified in CI; no churn).
- Verify mode after the commit: 415 tests, 0 failures.
- `bootstrapRecord` stays `false`.

## Golden vs owner screenshot comparison (sample, by eye)
- tickets_dashboard_en vs 11-ticket-dashboard.jpg: matches (tiles, resolution card, colours).
- tso_fs_s24_en vs 24-get-sales-data.jpg: MISMATCH. Golden shows white dropdown fields and dark (unreadable) field labels (Wing, Division, Territory, House, Zone) on the navy page; the owner's screen has dark navy fields with a purple glow border, white bold labels, white button text "Get Sales Data" and a blue bottom glow. Needs a UI fix lane (not changed here; golden records current behaviour).

## Mismatches vs owner screenshots
- golden tso_fs_s24_en vs 24-get-sales-data.jpg: white dropdown fields and unreadable dark labels on navy; owner's: dark navy fields with purple glow, white bold labels, white "Get Sales Data" text, blue bottom glow.

## Task 1b: VisitPlansTest (2026-11-03)
Root cause: not the INT merge. VisitPlansTest posted plans dated 2026-11-01..05; the API refuses a plan date before today (Dhaka) as `in_past`, so the test went stale on 2026-11-02 (CI run #1738 on 2026-11-03 saw 400; the call_assessment "null" uuid is the follow-on). Fix: dates are today+N in the test; assertions unchanged.

## Task 2 (D-TSO-MAN-49)
V0134 defaults: late policy accept_and_flag, auto final submit false; Opus checker PASS on 9e0c2423 (cleanups applied: job fallback false, DayStates comment).

## Batch 1 (lead conformance ledger, 2026-11-03 21:20 UTC)
Done, one commit each: (1) TsoAreaScreenshotTests wraps screens in the TSO look; 84 goldens re-recorded on Linux (the 21 laptop-recorded goldens failed verify on the CI runner in gate run 41717952377, so they were re-recorded too); (2) UI fixes: row 35 tabs as two pills, row 26 TSO info dialog (navy panel, centred text, filled blue OK), row 25 steel-blue route cards (night look; Sunlight keeps the white outline card), row 33 aqua edge on the chosen outlet card, row 43 category rows navy with a teal-edged chosen row, blue button glow; (3) tests: TicketAnnexATilesTest (31 tiles, Annex A order, REPORT_DIFFERENT), TsoChannelToggleTest, goldens tso_login_bn, tso_login_200_bn and tall goldens for rows 9, 11, 15, 17, 18, 25, 39; (4) ci.yml signed dev step makes aron_tso_app_<dd_mm_yyyy>_v<code>.apk and rewrites SHA256SUMS after release-manifest.py (tools/ci/tso-apk-copy.sh, self-test tools/ci/test_tso_apk_copy.py); (5) VisitPlansTest randomised dates today+10..15; (6) "As of" only for a kept copy (dashboard, Target Status, login status; the inbox keeps it); (7) §8 proof references, stale comments, AutoFinalSubmitDefaultOffTest.

## Remaining owner-visible differences (recorded, not fixed)
- Button labels (D-TSO-MAN-50, lead ruling): white bold labels as in the owner's screenshots, on the screenshot hues darkened only until the label holds 4.5:1 (cyan 297F95-5577AB, lime 687E10-478430, Cancel 008837, Log Out A86702-A5680B). Residual difference: the fills are slightly darker than the owner's brighter lime and cyan. Sunlight unchanged. Flip back with 'navy labels'.
- The bright-blue bottom glow of the Final Submit, Target and login-status pages is not drawn (only the Home disc halo); not done.
- Row 37 delegate-question and radio label colours: the re-recorded golden shows white ink; no change needed after the harness fix (checked by eye).
- Row 14 ticket-details button wrapping, row 8 title wrapping at 360 dp, row 30 Set Plan icon glyph, row 41 shrinking % column, row 16 sheet position: cosmetic, not done.
- Success dialog is drawn edge to edge in the Robolectric capture (a window-capture effect); on a device the platform dialog margins apply (inferred).
- Browse Gallery is a flat blue with a glow; the owner's is a light-to-deep blue gradient (a gradient would drop white-label contrast below 7:1).
