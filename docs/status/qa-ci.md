# Lane qa-ci #1 status (2026-11-06)

Branch `lane/qa-ci`. Decisions D-QA-CI-01..08 in DECISIONS.md.

## Done
1. Robolectric offline (tools/ci/robolectric-prefetch.sh, ci.yml cache + step, build.gradle.kts). Run 42297680755: Android job green; log shows the four jars fetched once with SHA check and no other android-all download.
2. Computed-date audit: AdminEnv.workingDay; docs/31 s2.1.
3. SrDayLockHostsTest awaits settled state (TestWork.awaitSettled).
4. APK baselines per app (SR armeabi-v7a 4.76 MB; run shows +0.1 %).
5. Money-gate self-test no longer leaks ::error (0 matches in the gates job log).
6. validate.sh covers infra/scripts/*.py; slice-smoke.py 100755.
7. SppConnectorTest on virtual time (9/9 locally, also under CPU load).
8. Partial: DataFlowTraceTest comment.

## Open
- Item 8: planned_today hop for SMK-SR-D on the trace day; SeedGulshanBananiTest plan SKU price validity dates (needs a trace-test run with Postgres).
- Checker note: awaitSettled does not see composition-scope coroutines; the 30 s dialog poll still guards.
- Robolectric cache is saved only on job success; first run downloads (retried).

## Evidence
Lane run 42297680755 (sha 5f4eae4e): all 12 jobs green. Sonnet checker: PASS on SppConnectorTest, SrDayLockHostsTest, CI wiring.

## Update 2026-11-11 (qa-ci #1)
- Also done since: D-QA-CI-09 (DataFlowTrace late policy via ApiWorld extraConfig, Saturday failure), D-QA-CI-10 (AdminMasterTest holiday window), D-QA-CI-11/12 (app-amo host classes on the Looper-backed Main, fixture await idles the main looper, AmoLockFixtureCloseTest/AmoDayLockHostsTest moved, printTapPrintsNothing awaits settled work), D-QA-CI-13 (item 8: planned_today hop for SMK-SR-D, plan-SKU price validity in SeedGulshanBananiTest).
- Evidence runs: 42297680755 (items 1-7), 42889544065 (D-QA-CI-10), 42936665241 (D-QA-CI-11), 42940428527 (D-QA-CI-12, pending at writing).
- Dated note: db SeedGulshanBananiTest holiday count (2 per December in a month-start+3 window) breaks from 2027-10-01; its working_days 26 check is skipped from 2027-01-01 by design. Revisit 2027-09.
- Local tooling: the Android SDK installs with tools/android-sdk.sh (ANDROID_HOME=/opt/android-sdk plus local.properties sdk.dir, git-ignored); Postgres 16 runs locally (service postgresql start; role aron_test, trust on 127.0.0.1) so backend, db and Android tests can be run before a push. Full backend:app takes about 27 min: run it with nohup and watch the log, not a foreground call.
- Open: none from the lead's list.

## HANDOVER (qa-ci #1 to qa-ci #2, 2026-11-11)
State: all items from the brief are done; the last lane head was 6fd21503 (train 87, CI run 42946082024 watched by the lead). No open items. Push only when the lead asks (one push per CI run, merge INT first, DECISIONS lines in the bold form `- **D-QA-CI-nn (date, qa-ci).** text`, next number D-QA-CI-16).

Harness patterns to reuse (never a sleep, never a retry, never a loosened assertion):
- **MainLooperImmediate** (`android/app-amo/src/test/.../MainLooperImmediate.kt`, D-AMO-CALLS-16): the test `Dispatchers.Main` for Compose + Room host tests. Eager on the main thread, posted to the main looper from any other thread, so Main work never resumes on a Room IO thread (CalledFromWrongThreadException) and no query leaks past `db.close()`. A blocking poll on the main thread must idle the looper (`shadowOf(Looper.getMainLooper()).idle()`), as `AmoLockFixture.await` and `TestWork.waitUntil` do (D-QA-CI-11).
- **TestWork.awaitSettled()** (SR and AMO `TestWork.kt`): view-model and day coroutines done, Room executor idle, main looper idled each poll; the number is only a hang guard. Use it before asserting that something did NOT happen (D-QA-CI-12) or before reading UI state the host reaches after a Room read.
- **awaitStockLoaded / awaitRefusalDialog** (AmoRefusalHostsTest, SrRefusalHostsTest, SrDayLockHostsTest): `f.work.awaitSettled()`, `compose.waitForIdle()`, then `compose.waitUntil` the node exists. `waitForIdle()` does not wait for Room's executor, which is how a tap right after `show()` fails under load (D-QA-CI-14). Proof technique: a temporary `Thread.sleep(1200)` on the IO thread in the product read, test fails unfixed and passes fixed, product file reverted before commit.
- **Frozen world clock and DbServerConfig** (D-QA-CI-09): DbServerConfig keeps its first snapshot for 30 s of the world clock, which the data-flow trace freezes. Put cfg overrides in `ApiWorld(extraConfig = ...)` so they exist before the wiring is built, never after.
- **Dates**: calendar tests use `AdminEnv.workingDay(offset, span)` (D-QA-CI-02) and never count rows over a window that can hold the seeded Victory Day 2026-12-16 (D-QA-CI-10); assert on the rows the test declared. SppConnectorTest runs on virtual time with an injected dispatcher (D-QA-CI-07).
- **Dated expiry**: db SeedGulshanBananiTest's December holiday count breaks from 2027-10-01 (expects 2 per December in a month-start+3 window; the window then reaches a December with no seeded rows) and its working_days 26 check is skipped from 2027-01-01 by design. Revisit 2027-09.

Local recipes (container, no repo change needed):
- Android SDK: `ANDROID_HOME=/opt/android-sdk tools/android-sdk.sh`, then `echo sdk.dir=/opt/android-sdk > local.properties` (git-ignored). Android unit tests: `./gradlew --no-build-cache :android:app-amo:cleanTestDebugUnitTest :android:app-amo:testDebugUnitTest --tests '*X'` (without `--no-build-cache` the clean test is restored from cache in 1 s and proves nothing).
- PostgreSQL: `service postgresql start`; role `aron_test` (superuser), database `aron_test`, `trust` on 127.0.0.1 in pg_hba.conf; `export ARON_TEST_PG_URL="jdbc:postgresql://localhost:5432/aron_test?user=aron_test"`. The full `:backend:app:test` takes about 27 min: run it with nohup and watch the log file (a foreground call is killed at 10 min); a Monitor `pgrep` guard on the Gradle wrapper is unreliable, use a grep on `BUILD (SUCCESSFUL|FAILED)` in the log.
- Stray `__pycache__` folders from running tools/ci Python tests trip NoSecretsInRepoTest: delete them before a backend run.
- Robolectric in CI is offline (tools/ci/robolectric-prefetch.sh + ROBOLECTRIC_OFFLINE_DIR, D-QA-CI-01): a Robolectric bump or a test at a new API level needs the jar table in that script updated (test_gates.RobolectricPrefetch fails otherwise).
- Reading CI: `curl https://api.github.com/repos/asefameerador96-ctrl/Aron-Pro-Max/actions/runs?branch=lane/qa-ci&per_page=1`, `.../runs/<id>/jobs?per_page=50`, full log `curl -L .../actions/jobs/<job_id>/logs` to a scratch file, grep.
