# Lane android-gates-2 (A-GATE8) — status

Branch `lane/android-gates-2` (from the integration branch 29e8d86f3). Closes the ten follow-ups of `docs/reviews/android-gates-final-pass-2026-11-18.md`. Decisions: D-AGATE-22 onward. Room stays 10.

## HANDOVER (updated after each push)
- Push 1 (94528a88): items 1, 2, 3, 4 (D-AGATE-22). Push 2 (fd6825d4, ci run 44264658755 success, judged pushes 1 and 2 together): items 6, 7, 5 (D-AGATE-23). Push 3: items 10, 8, 9 (D-AGATE-24, with the list of every mutation proof and its result). ALL TEN ITEMS ARE CODED, TESTED AND PROVED; what is left is the lead's check and the device walks named in D-AGATE-24 and the message to the lead.
- Do not push while a lane ci run is open: a push cancels it (ci.yml concurrency).
- Mutation runner: a script that applies one string replacement, runs the named gradle tests, collects failed test names from `*/build/test-results/testDebugUnitTest/*.xml` newer than the start, and restores with `git checkout`; commit first, never mutate a dirty tree.
- Build: `tools/android-sdk.sh`, `echo sdk.dir=/opt/android-sdk > local.properties`, `export ANDROID_HOME=/opt/android-sdk`, run Gradle from the repo ROOT, one process at a time, never edit sources while it runs. Full gate: `./gradlew -q --console=plain --continue testDebugUnitTest :android:app-sr:assembleDebug :android:app-amo:assembleDebug :android:app-tso:assembleDebug lintDebug` (about 20 min warm).
- Gotcha: Room closed mid-test throws a CancellationException; inject a read failure by dropping a table instead (`execSQL("DROP TABLE visit")`).
