# lead-ops lane status

Lane `lead-ops #1` (Sonnet), branch `lane/lead-ops` from INT 756cc24c. Brief: harden the migration-incident fixes (Flyway, deploy logs, ACR purge, cost report, diagnose, docs). No Azure credentials; every change is proven by unit tests and, after the push, by the deploy and ops-diagnose job logs.

## Done (push 1; one commit per item, each with its test)

| Item | Commit subject | Proof |
|---|---|---|
| 1 Flyway afterConnect | "Flyway: exempt the migrate sessions through the afterConnect callback ..." | `MigratorIdleTimeoutTest` (3 tests) and `DatabaseSmokeTest` green on local PG16: exemption passes the 5 s migration under a 2 s server timeout, control fails with SQLSTATE 08006, a migration sees `idle_in_transaction_session_timeout = 0`, no `initSql` configured. The WARN's absence is read from the next deploy's migrate console. |
| 2 deploy logs on success, HELD row | "deploy: print the migrate and dblogins console logs on success too ..." | `check_infra` `Workflows`: `test_held_row_is_one_table_row_of_at_most_400_characters` (runs `held_row`), `test_successful_job_logs_are_printed_bounded`. |
| 3 ACR purge unlock | "acr-purge: unlock each tag of a purge candidate ..." | `check_infra` `DeploySafety.test_registry_purge_unlocks_the_tags_deploy_locks` (stub registry refuses the delete until every tag is unlocked; fails without the fix). |
| 4 cost-report scope + warning | "cost-report: usage-details fallback at subscription scope ..." | `tools/ci/test_gates.py` `CostReport` (4 tests, stubbed `az` and `sleep`). |
| 5 diagnose worker log + exclusion | "diagnose: worker app console log query ..." | `check_infra` `Workflows.test_diagnose_reads_the_worker_console_log_and_keeps_statements_out` and the existing read-only test. |
| 6 docs drift | "docs: migrations under the idle timeout and the worker pause ..." | docs/33 part C, docs/24. |
| 7 keepalive proposal | same commit | docs/33 part B (final account only, nothing applied). |

DECISIONS: D-OPS-FLYWAY-AFTERCONNECT-01, D-OPS-DEPLOY-LOGS-01, D-OPS-ACR-UNLOCK-01, D-OPS-COST-SCOPE-01, D-OPS-DIAGNOSE-WORKER-01, D-OPS-DOCS-DRIFT-01, D-OPS-KEEPALIVE-PROPOSAL-01 (7 lines).

## Gates run locally

- `python3 -m unittest infra.tests.check_infra`: every non-compiled test passes; 44 tests need `infra/.compiled` (bicep build, not installable here) and error with FileNotFoundError, as they do without `validate.sh`; CI runs them.
- `python3 tools/ci/test_gates.py`: 50 tests OK (9 skipped for missing binaries).
- Gradle `:backend:platform:test --tests '*MigratorIdleTimeoutTest' --tests '*DatabaseSmokeTest'`: green.

## Open

- The real registry's answer to the unlock (next weekly purge run, or a manual dispatch with dry_run=false) and the test account's acceptance of the subscription-scope `usageDetails` filter (next cost-report run) are NOT proven by unit tests.
- `docs/status/lead-state.md` line 58 still describes the superseded revision-deactivate pause design (PAUSE-01); the lead's file, left alone.
- Evidence run ids: see the section below once the lane run is green.

## Evidence run ids

| Run | Commit | Result |
|---|---|---|
| 42298043704 | 9c551a17 | RED: Infra validation (shellcheck SC2016 in cost-report.sh, fixed in 2f9f2cc8) and Backend app tests (`DataFlowTraceTest` hop12 and everyFindingIsKnown: fails the same way, 2 of 16, on the untouched INT head 756cc24c when run locally after 00:00 Dhaka, so time-dependent or pre-existing, not this lane; owner: qa/backend-core). Shared, db and backend (Flyway tests), Container images, Web, Android, gates: green. |
| 42302644776 | 2f9f2cc8 | green (Infra validation success; other jobs skipped by change detection) |
| 42303324239 | b4ed9675 (merge of INT 7f34a1d2) | green (Android, APK size, gates, analytics success; rest skipped by change detection) |

Checker: Opus PASS on 6 commits (3 should-fixes taken, D-OPS-CHECKER-FIXES-01).
