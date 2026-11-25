# Android gates 3 (lane android-gates-3, A-GATE9)

Branch `lane/android-gates-3` from the integration branch (train 140, 85abe7817). Android only: no Room schema change, no contract change, no server change. Decisions D-AGATE-32..34 at the end of `DECISIONS.md` (D-AGATE-30 and 31 were already taken by the previous lane, so the numbering starts at 32).

## State
1. DONE: the writer discovery in `EveryWriterIsGatedTest` also finds a DAO write outside a transaction and outside the outbox (D-AGATE-32).
2. DONE: `AmoRecordWriter.saveSurveyLocked` asks the gate first, before the replay branch (D-AGATE-33).
3. ALREADY DONE by the previous lane (D-AGATE-30, landed in train 128): `SrLockFixture` and `AmoLockFixture` have a `clockMs` constructor parameter with a fixed default and anchor the trusted clock with it. Verified, nothing to change: no `currentTimeMillis`, `Instant.now` or `LocalDate.now` in either fixture. The default is 05:00 Dhaka of 2026-10-06 (2026-10-05T23:00:00Z, constant `FIXTURE_NOON_MS`, the name is historical), NOT noon: the trusted clock refuses to go back, and a first try with noon failed `SrLatePolicyTest`, `AmoLatePolicyTest` and `SrCheckOutSubmitTest` (D-AGATE-30). The brief said noon; I kept the value that passes and did not rename the constant (it is used by tests of both apps).
4. DONE: one summary line per gate test class in the CI log of the Android job (D-AGATE-34).

## What the extended discovery found (direct DAO writers, no transaction, no outbox)
The DAO write methods are derived at test time from `core-database/.../dao/*.kt` (about 70 names: `@Insert`, `@Update`, `@Delete`, `@Upsert`, and `@Query` that starts with INSERT, UPDATE, DELETE or REPLACE; the outbox DAO's bare `insert` stays on its own pattern). A call `.name(`, `execSQL(` or `compileStatement(` in a main source file outside the DAO package marks the file. New files found, all on `systemWriters` with a reason (none writes a field record):

| File | Why not gated |
|---|---|
| app-amo RoomCallTargetsStore | call-targets cache in sync_meta |
| app-tso TicketsHost | drops mirror keys the server now answers; the ticket save is `TicketDevice.save` (gated) |
| core-database RouteDayLock | the server's void of a submit cycle in sync_meta |
| core-database LocalPurge | retention purge of acknowledged rows (raw SQL) |
| core-database Migration9To10 | schema migration (raw SQL) |
| core-sync BundleDownloader, ResumeConfigCheck, SyncRules, SyncWork | sync_meta bookkeeping |
| feature-attendance DayCloseFailed | failed-close counters in sync_meta |
| feature-dayclose RoomDayAdapters | marks a refused outbox row unseen |

Inside files that are already gated, per function (named in `gatedByCaller` with a reason): `SrDay.dismissRolloverNotice`, `AmoDay.dismissRolloverNotice` (delete the phone's own rollover marks), `TsoReadModels.cachePut` and the two `refresh` overrides (read cache, mirror rows). Inside `CaptureRepository` (function level, no transaction): `addPhoneMark`, `clearPhoneMarks` (phone records) and the private `writeDaySubmit` (called only inside the gated transactions of `recordDaySubmit` and `recordCheckOut`) are named in `repositoryDirectWritersNotGated`. No DAO write in an operational file was found ungated: the sweep proves it by a test that fails when one is added.

## KNOWN, RECORDED (what is still not covered)
- The discovery is a name heuristic: a write through a name not derived from the DAO sources (a new DAO in another package, a Room `@RawQuery`, reflection) is not seen; `execSQL` and `compileStatement` are. A same-named method of another class marks its file (false positive, named on a list).
- A file already on `gatedFiles` is checked per function for the app files and for `CaptureRepository`; the other gated files (`ProgrammeStore`, `TsoOutbox`) are checked for the gate in the file only, as before.
- The five gate classes in the CI summary are my reading of "the five gate test classes": `EveryWriterIsGatedTest`, `ActionGateWriteTest`, `SrLatePolicyTest`, `AmoLatePolicyTest`, `SrKitLockTest` (list at the top of `tools/ci/android-gate-test-summary.py`). If the lead meant others, change that list.

## WALL-CLOCK TESTS
Residual of D-AGATE-28 is closed by D-AGATE-30 (fixtures pinned). No assertion changed in this lane.

## RECIPE
- Build: `tools/android-sdk.sh`, `echo sdk.dir=/opt/android-sdk > local.properties`, `export ANDROID_HOME=/opt/android-sdk`, Gradle from the repo ROOT, one process at a time, never edit sources while it runs.
- Writer sweep: `./gradlew :android:core-database:testDebugUnitTest --tests '*EveryWriterIsGatedTest*'`. Mutation proofs: add a file under any `src/main` that calls a DAO write (for example `dao.insertFix(r)`) and the test names it; add an ungated function with a DAO write to `CaptureRepository` and it names the function; delete the first line of `saveSurveyLocked` and both the sweep and `aReplayedSurveySaveWithAClosedGateClaimsNoPhoto` fail.
- Summary line locally after a run: `python3 tools/ci/android-gate-test-summary.py android`.
- Device walk (nothing new on screen): with Developer options on, open an AMO call with a survey whose answers are stored (Save, kill, reopen) and press Save again: the block screen, no photo upload queued.
