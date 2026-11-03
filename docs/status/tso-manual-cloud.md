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
