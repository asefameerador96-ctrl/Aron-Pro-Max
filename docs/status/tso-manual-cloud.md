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
