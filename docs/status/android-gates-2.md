# Lane android-gates-2 (A-GATE8) — status

Branch `lane/android-gates-2` (from the integration branch 29e8d86f3). Closes the ten follow-ups of `docs/reviews/android-gates-final-pass-2026-11-18.md`. Decisions: D-AGATE-22 onward. Room stays 10.

## DEVICE-WALK-ONLY ITEMS (nothing below can be proved by a unit test; D-AGATE-24 and -25 name them)
1. The lock-out screen on a phone: the look, the Bangla text, that a tap anywhere on it reaches nothing underneath, that system Back does nothing while it shows, that TalkBack reads only that screen (the screens below are hidden with `clearAndSetSemantics`), and that Try again really recovers after the cause is gone.
2. A real forgotten-day close whose location read crosses midnight, and a rep check-out pressed at 23:59:45 on a slow fix: the rep's press wins, no phone close, the screen shows the press time.
3. A real 04:00 race of a Save and the phone's end of the call (the unit tests hold Room's executor; a phone has real timing).
4. A second phone close answered `attendance_duplicate` by the dev server, then the next daily digest staying clean (no resend of the attendance bucket).
5. The new dialog texts in Bangla on a phone: earlier call open (`review_problem_earlier_call_open`), the AMO ended-call text (`amo_gate_day_submitted`), and the replay text (`review_later_*`).
6. The Home banner / sale screen after a Save that landed unheard and then was edited (the replay dialog).

## HANDOVER (updated after each push)
- Push 1 (94528a88): items 1, 2, 3, 4 (D-AGATE-22). Push 2 (fd6825d4, ci run 44264658755 success, judged pushes 1 and 2 together): items 6, 7, 5 (D-AGATE-23). Push 3: items 10, 8, 9 (D-AGATE-24, with the list of every mutation proof and its result). ALL TEN ITEMS ARE CODED, TESTED AND PROVED; what is left is the lead's check and the device walks named in D-AGATE-24 and the message to the lead.
- Do not push while a lane ci run is open: a push cancels it (ci.yml concurrency).
- Mutation runner: a script that applies one string replacement, runs the named gradle tests, collects failed test names from `*/build/test-results/testDebugUnitTest/*.xml` newer than the start, and restores with `git checkout`; commit first, never mutate a dirty tree.
- Build: `tools/android-sdk.sh`, `echo sdk.dir=/opt/android-sdk > local.properties`, `export ANDROID_HOME=/opt/android-sdk`, run Gradle from the repo ROOT, one process at a time, never edit sources while it runs. Full gate: `./gradlew -q --console=plain --continue testDebugUnitTest :android:app-sr:assembleDebug :android:app-amo:assembleDebug :android:app-tso:assembleDebug lintDebug` (about 20 min warm).
- Gotcha: Room closed mid-test throws a CancellationException; inject a read failure by dropping a table instead (`execSQL("DROP TABLE visit")`).
