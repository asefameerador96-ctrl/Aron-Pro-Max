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
