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
