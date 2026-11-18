# Lane android-gates-2 (A-GATE8) — status

Branch `lane/android-gates-2` (from the integration branch 29e8d86f3). Closes the ten follow-ups of `docs/reviews/android-gates-final-pass-2026-11-18.md`. Decisions: D-AGATE-22 onward. Room stays 10.

## KNOWN, RECORDED (D-AGATE-27)
- `AmoRecordWriter.decideRequest` and `commitOutletRequest` now ask the Developer options gate BEFORE the already-decided check, the fix read and the photo claim. `ActionGate.requireOpen()` throws `GateClosedException`, which is a CancellationException: the coroutine ends quietly and `ActionGate.onClosed` puts the block screen up at once (read from `ActionGate.kt`; not walked on a device). It is not a generic "save failed". Device walk: with Developer options on, press the verify button of an outlet request: expect the block screen, no error text.
- `AmoRecordWriter.saveSurveyLocked`: its REPLAY branch (answers already stored, only the photos are claimed again) claims photos without asking the gate. Pre-existing and harmless for data (nothing new is written, the claim is idempotent); recorded here, not changed in this round.
- The writer discovery scan (`EveryWriterIsGatedTest`) is a heuristic: it finds files that use `withTransaction` or an `outbox...insert`; a direct DAO insert without a transaction and without the outbox is NOT discovered.

## WALL-CLOCK TESTS (D-AGATE-28)
- FIXED: `SrLatePolicyTest` and `AmoLatePolicyTest`, `theCallGuardsReadThePolicyAtTrustedTimeNotAtTheWallClock`: the phone's own clock is now pinned to 2026-10-06T06:00:00Z (noon Dhaka), trusted time ten minutes behind it, and the fixture's check-in is repeated for that date. Same assertions. They failed when CI ran between 18:00 and 18:10 UTC (00:00 to 00:10 Dhaka) because `System.currentTimeMillis()` and trusted time then sat on different business dates.
- SEARCHED (app-sr, app-amo, app-tso, feature-* and core-* tests for `System.currentTimeMillis`, `LocalDate.now`, `Instant.now`, `Clock.system`, `LocalDateTime.now`, a wall time plus or minus an offset): no other test derives a business date from the wall clock with an offset. The TSO tests pin their date (`2026-10-17` and similar). `RefusedRecordsTest` and `wait` helpers use the wall clock only for a 10 s timeout.
- FIXED (D-AGATE-29): `SrKitLockTest` now pins the day's clock to noon Dhaka of a fixed date in `setUp` (the failure at ~18:00Z was the fixture taking its date from the real clock). `SrLockFixture` and `AmoLockFixture` themselves still do (other classes are not pinned).
- RESIDUAL (not changed): `SrLockFixture` and `AmoLockFixture` take their business date from the REAL clock at construction (the trusted clock falls back to it until a test anchors it), and their fix source stamps `System.currentTimeMillis()`. A test that is running at the very instant of Dhaka midnight (18:00:00Z) can therefore see the date change under it; a window of seconds. `SrKitLockTest.markPaidIsHiddenOnAFinalDayAndAReprintStillGoesToThePrintLedger` failed once at about 18:00Z in a full run and passed alone and ten minutes later; this is the likely cause, not proven. If it recurs, pin the fixtures' clock at construction (a constructor parameter), which touches every test that uses them.

## DEVICE WALK: steps a person can follow on a phone (SR and AMO apps; labels as shown in Bangla)
Use a test phone with a test login (an SR for the SR steps, an AMO for the AMO steps). Do each part with the network OFF first (airplane mode), then ON. Say "OK" only if the result is exactly as written.

**Part A. Rule 1: no check-in, no work**
1. Open the app, do NOT check in. Tap a work tile (Stock, Sale or a call). Expect: a page titled **আগে হাজিরা দিন** and a button **হাজিরায় যান**; nothing can be saved.
2. Tap **হাজিরায় যান**, then **চেক ইন** (hold to confirm). Expect: **চেক ইন সম্পন্ন হয়েছে** with the time. Go back to the same work tile: it now opens.
3. (AMO) Open Control Call, then press the phone's Back. Expect: you return, no crash.

**Part B. Rule 2: no sale beyond stock**
4. Open Stock and enter, say, 2 dozen of one item; save. Open Sale for an outlet and try to enter 3 dozen of that item. Expect: the quantity stops at 2 dozen (the screen will not take more).
5. Save the 2-dozen sale. Expect: the memo is saved. Try a second sale of the same item. Expect: the screen says what is held (stock line with the figure), nothing is saved.

**Part C. Rule 3: check-out submits the day**
6. Make one sale, do NOT press Sales Submit. After 17:00 (or when the phone shows the check-out time reached) open Attendance, hold **চেক আউট**. Expect: **চেক আউট সম্পন্ন হয়েছে** with the time you pressed; no "Sales Submit first" message.
7. Open the Sales Submit screen. Expect: the day shows as submitted. Turn the network ON and wait a minute. Expect: the sync chip goes to "synced".
8. With a call still open, try **চেক আউট**. Expect: a message that **একটি কল এখনো খোলা আছে** and no check-out is written; close the call, press again, it works.

**Part D. Forgotten day (the phone closes it)**
9. Check in, make a sale, do NOT check out. Close the app. Next day (or move the phone clock past midnight, Dhaka time) open the app. Expect: a notice that your day **...তারিখের দিনটি খোলা ছিল** and the phone closed it for you; Attendance then lets you check in for the new day.
10. Leave a call open past midnight and open the app after 04:00. Expect: a notice that the call was ended by the phone; a saved sale in it is untouched.
11. Earlier call still open before 04:00: open Stock. Expect: **আগে গতকালের কলটি শেষ করুন** and a button **খোলা কলটি বন্ধ করুন**; tap it, the call closes and the page goes away. Try a Save for that call instead: expect the text **আগের দিনের একটি কল এখনও খোলা** (not the check-in text).

**Part E. Lock-out screen (needs a test build or a helper to make the close fail; ask the lead for the test switch)**
12. With the close forced to fail, open the app 3 times a minute or more apart. Expect: a full page **এই ফোনে দিনটি বন্ধ করা যায়নি** with a line **কোড: E-CLOSE-DAY/...** and a button **আবার চেষ্টা করুন**. Three quick opens within a few seconds must NOT show it.
13. On that page: tap anywhere else (empty parts, where the app was). Expect: nothing happens. Press the phone's Back button. Expect: nothing happens, the page stays. Turn TalkBack on and swipe: expect it reads only this page (title, code, button), never the app underneath.
14. Remove the cause, tap **আবার চেষ্টা করুন**. Expect: the page goes, the day is closed, check-in works. Write down the code if it did not.

**Part F. Midnight edge**
15. At 23:59, start **চেক আউট** (hold). If your phone is slow to find a location, wait past 00:00. Expect: the check-out is YOURS (no "closed by the phone" notice), and the time shown is the time you pressed, not 00:00.

**Part G. Texts and replay**
16. With the AMO: end a call whose day was already submitted from the gate's **খোলা কলটি বন্ধ করুন**. Expect: the words **এই কলের দিনের বিক্রি আগেই জমা দেওয়া হয়েছে**.
17. Sale screen: press Save, immediately press Back/kill the app, reopen, change a quantity, press Save again. Expect: the saved memo comes back, with the message **সংরক্ষিত মেমো দেখানো হচ্ছে** saying the later change was not saved.
18. Second phone close: after step 9 on a phone that already had a check-out sent from another phone for that day, sync. Expect: no refusal shown to the rep; the next day's sync summary stays clean.

## HANDOVER (updated after each push)
- Push 1 (94528a88): items 1, 2, 3, 4 (D-AGATE-22). Push 2 (fd6825d4, ci run 44264658755 success, judged pushes 1 and 2 together): items 6, 7, 5 (D-AGATE-23). Push 3: items 10, 8, 9 (D-AGATE-24, with the list of every mutation proof and its result). ALL TEN ITEMS ARE CODED, TESTED AND PROVED; what is left is the lead's check and the device walks named in D-AGATE-24 and the message to the lead.
- Do not push while a lane ci run is open: a push cancels it (ci.yml concurrency).
- Mutation runner: a script that applies one string replacement, runs the named gradle tests, collects failed test names from `*/build/test-results/testDebugUnitTest/*.xml` newer than the start, and restores with `git checkout`; commit first, never mutate a dirty tree.
- Build: `tools/android-sdk.sh`, `echo sdk.dir=/opt/android-sdk > local.properties`, `export ANDROID_HOME=/opt/android-sdk`, run Gradle from the repo ROOT, one process at a time, never edit sources while it runs. Full gate: `./gradlew -q --console=plain --continue testDebugUnitTest :android:app-sr:assembleDebug :android:app-amo:assembleDebug :android:app-tso:assembleDebug lintDebug` (about 20 min warm).
- Gotcha: Room closed mid-test throws a CancellationException; inject a read failure by dropping a table instead (`execSQL("DROP TABLE visit")`).

## SOURCE-SCANNING TESTS AND THE GRADLE CACHE (D-AGATE-29)
Tests that read `.kt`/`.xml` source files at run time declare those files as inputs of their module's unit test tasks (`tasks.withType<Test>().configureEach { inputs.files(...) }` at the end of the build file; `core-ui` already did): `core-database` (EveryWriterIsGatedTest: core-database, core-sync, app-sr, app-amo, app-tso main sources), `app-sr` (NoAlwaysOnMapTest, ProgrammeStoreTest, SrPrinterAssistTest: app-sr, feature-sale, feature-attendance main), `app-amo` (AmoNavigationCoverageTest, MainActivityEffectKeysTest: app-amo, feature-amo main). Proof: gate moved below the photo claim in SrDay, run WITHOUT --rerun and WITHOUT --no-build-cache (the repo has org.gradle.caching=true): the test failed; reverted: it passed. Other tests that read files (core-geo, core-map, core-media, core-printing, app-tso icons) read their own module's resources or assets, which are already task inputs; not examined line by line.

## PHOTO-CLAIM SWEEP (D-AGATE-31)

Every `claimPhoto(` / `attach(` call in app-sr, app-amo, app-tso that runs before a repository write, with the gate position. Pinned by `EveryWriterIsGatedTest.everyPhotoClaimComesAfterTheGateOrIsNamed` (function-level: the gate must precede the claim, or the function is named in `claimsGatedElsewhere` with a reason) and `theSrOutletRequestCommitterGatesBeforeItsClaims`.

| Call site | Gate position |
|---|---|
| SrDay.kt visit commit (geo-force photo) | gate first, before the uuid is used (visit-commit test) |
| SrDay.kt `commitAll` (outlet requests) | **fixed this round**: gate is the first line, before the fix read and every claim |
| SrDay.kt `saveSurvey` | claim runs after the gated repository transaction (named) |
| SrDay.kt `claimPhotoIo` | helper; callers are gated (named) |
| AmoDay.kt visit commit | gate first (AMO claim-order test) |
| AmoDay.kt `claimPhoto`, `memoHeader` | helper / wiring lambda; callers gated (named) |
| AmoRecordWriter `decideRequest`, `commitOutletRequest` | gate first |
| AmoRecordWriter `saveSurveyLocked` | claim after the gated transaction; the replay branch has no gate (known gap, KNOWN RECORDED) |
| ProgrammeStore `saveGiftPhoto` | gate at the top of the function |
| TicketsHost.kt `TicketDevice.save` | **fixed this round**: gate before the held-check and the claim loop |
| TsoReadModels.kt feedback queue | **fixed this round**: gate before the `attach(` claim |
| TicketsHost.kt `refreshConfig` | wiring lambda only (named) |

Mutation proofs (default Gradle caching, no `--rerun`): removing the gate in SrDay `commitAll`, `TicketDevice.save` or the feedback enqueue fails `everyPhotoClaimComesAfterTheGateOrIsNamed` (the first also fails the SR-specific test); using the geo-force photo uuid before the gate in the AMO visit commit fails the visit-commit test.
