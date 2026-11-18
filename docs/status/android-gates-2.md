# Lane android-gates-2 (A-GATE8) — status

Branch `lane/android-gates-2` (from the integration branch 29e8d86f3). Closes the ten follow-ups of `docs/reviews/android-gates-final-pass-2026-11-18.md`. Decisions: D-AGATE-22 onward. Room stays 10.

## HANDOVER (updated after each push)
- Push 1 (94528a88): items 1, 2, 3, 4 (D-AGATE-22). Push 2: items 6, 7, 5 (D-AGATE-23). Next: 10, 8, then 9 (race tests and the mutation proofs).
- Build: `tools/android-sdk.sh`, `echo sdk.dir=/opt/android-sdk > local.properties`, `export ANDROID_HOME=/opt/android-sdk`, run Gradle from the repo ROOT, one process at a time, never edit sources while it runs. Full gate: `./gradlew -q --console=plain --continue testDebugUnitTest :android:app-sr:assembleDebug :android:app-amo:assembleDebug :android:app-tso:assembleDebug lintDebug` (about 20 min warm).
- Gotcha: Room closed mid-test throws a CancellationException; inject a read failure by dropping a table instead (`execSQL("DROP TABLE visit")`).
