# TSO manual build: cloud status (lane tso-manual #1)

## HANDOVER
(none yet; task 1 in progress, 2026-11-03)

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
- Button labels: Login, Apply, Get Sales Data, Submit, Show Outlet, Set Plan, Save carry a NAVY label on the lime/cyan fills where the owner's screenshots show white. Deliberate (sponsor rule 14, outdoor legibility, TokenContrastTest 7:1; white on these fills is about 2:1). Awaiting the lead's ruling; unchanged.
- The bright-blue bottom glow of the Final Submit, Target and login-status pages is not drawn (only the Home disc halo); not done.
- Row 37 delegate-question and radio label colours: the re-recorded golden shows white ink; no change needed after the harness fix (checked by eye).
- Row 14 ticket-details button wrapping, row 8 title wrapping at 360 dp, row 30 Set Plan icon glyph, row 41 shrinking % column, row 16 sheet position: cosmetic, not done.
- Success dialog is drawn edge to edge in the Robolectric capture (a window-capture effect); on a device the platform dialog margins apply (inferred).
- Browse Gallery is a flat blue with a glow; the owner's is a light-to-deep blue gradient (a gradient would drop white-label contrast below 7:1).
