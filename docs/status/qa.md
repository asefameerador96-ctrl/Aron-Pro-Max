# Status: lane qa (wave 2, session 1, started 2026-10-17 18:30Z)

## Done (pushed to `lane/qa`)
- **N-014 + AUD-TP-2 (T1)**: the server-side harness in `backend/app/src/test`. Everything runs through the
  production wiring on a seeded db. **Three Opus checker rounds; every confirmed defect is fixed.**
  - `ContractConformanceTest` + `ContractModel` use contract/openapi.yaml as a JSON Schema 2020-12 oracle.
    - Six seeded callers make 970 calls; 902 bodies are validated.
    - 106 of 169 operations get a validated 2xx, including the gzipped `/sync/batch`, `/sync/bundle`, login and `/me`.
    - It checks the declared status, the body schema (one baseline key per normalised error), the ProblemCode, empty
      declared bodies and generated request samples. It fails on a route missing from the contract, proven with a
      real extra route.
    - Shrinking baselines: 96 deviations, 63 operations without a 2xx, 14 unsatisfiable 2xx schemas (a contract
      defect: `allOf` over closed bases). All routed in `docs/requests/qa-contract-conformance.md`.
  - `ScopeLeakAcrossEndpointsTest` + `ApiWorld` mirror: a second, marked division (D-LKX).
    - Both divisions sell different real days through the batch endpoint, then the AggregationWorker runs.
    - Every endpoint is probed as SR, AMO, TSO and DMO: own ids, other-division ids in every `*_id` path, query and
      body field (optional ones too), and selectors.
    - Detection uses three signals: the text marker, other-division ids per kind plus record uuids, and dashboard
      numbers. A supervisor's own answer must equal the national view of its node; an other-division selector must
      be refused or ignored.
    - The positive control runs per read: the national admin sees the other division on 27 of 78 reads. The other 51
      are in `scope-blind-baseline.txt`, which may only shrink.
    - **Zero leaks.** The seeded leak is caught through the real probes on a marker, on ids only and on numbers only,
      including the national view served to a TSO.
  - AUD-TP-2 part (2), the deploy probe, already exists: `infra/scripts/slice-smoke.py`. Part (3) is left alone, as
    the audit says.
- **N-024 (T1)**: `SyncFaultScheduleFuzzTest` with `SyncPhone`. **Two Opus checker rounds; every confirmed defect is
  fixed.**
  - Faults: reorder, partial, ride-along duplicates, whole-batch replay, lost response, truncated gzip, a process kill
    mid-batch (an Error out of ingest after the n-th stored record) and concurrent twins.
  - The oracle has 14 labelled clauses: the day's rows per table (count and identity), and the user's and the phone's
    rows over all dates. It also checks the ingest registry, ledger rows on the phone's outlets on any date, memo
    balances and `server_totals` (money, sticks, accepted, rejected, presence).
  - Every clause is proven by its own seeded fault on its own phone: lost line, identity swap, a second sale on the
    same or another date, the same uuid past a broken trigger, a foreign registry row, a stray ledger row, a doubled
    ledger amount, and tampered totals.
  - CI default is 240 schedules, about 40 s.
  - Acceptance: 10,000 schedules on 8 phones, green (`docs/status/qa-n024-acceptance.txt`). The re-run on the
    strengthened oracle passed too: 10,000 schedules, 3,583 kills, 138,260 rows.
  - Known gap, no seam without a product change: a death after the last family committed but before the response is
    stored.
- **N-015 (T2)**: `tools/checks/day1-check.ps1` prints PASS/FAIL per check. It was tested with a fake adb and a mock
  API, and its /health check passes against dev from the cloud. **DEVICE-PENDING**: the laptop run is D-QA-1 in
  `device-checks.md`.
- **AUD-TP-8 (T1)**: `tools/ci/done-rows-check.py` (warning mode) and its self-test `tools/ci/test_done_rows.py`.
  Today: 351 finished rows, 77 untested. The CI wiring is requested from infra in
  `docs/requests/qa-done-rows-check.md`.
- **N-054 (T1)**: `tools/loadgen/` (generate.sql, replay.py and their self-test).
  - Generating 8,500 SRs, 9,842 users, 11,336 routes and 464,771 outlets took 75 s.
  - A 50-SR replay through the batch endpoint sent 8,187 records; 50 of 50 SRs reconciled and the resend came back
    all duplicate.
  - Two Opus checker rounds; every confirmed defect is fixed.

- **N-022, N-050, N-043, N-042 (check scripts)**: `tools/checks/day2-check.ps1`, `day4-check.ps1`, `day3-check.ps1`
  and `release-install-check.ps1`, each on the shared `AronCheck.psm1`.
  - Each was tested on every path with a fake adb, a fake SDK and a mock API.
  - Checkers: a Sonnet checker (N-022) and an Opus checker plus a re-check (N-042/043/050). The re-check confirmed all 9
    majors fixed; its two minors are fixed too.
  - **DEVICE-PENDING**: D-QA-2..5 in `device-checks.md`.
  - The N-050 radius stamp waits on `qa-visit-radius-read.md`; N-042 `-ViaInAppUpdate` waits on
    `qa-release-publish-route.md`.
- **Executable bits**: tools/ci scripts are 100755 (the `infra/validate.sh` gate).
- Heads sent as `checked`: f5976bf6.

## In progress
- **N-061 (T1)**: report `docs/status/qa-n061-security-review.md`.
  - The first Opus check failed; its findings are fixed.
  - The re-check passes the code and found two wrong report claims, now fixed.
  - The APK half stays partial and open: the CI-built APKs are not scanned yet.
  - Scope harness: 0 leaks. The blind list is down from 48 to 33 lines: 22 global, 8 per-caller and 3 debt.
  - Dependencies: the Gradle OSV scan (`tools/ci/gradle-lockfile.py`) found Jackson 2.22.0 in the backend, 5 High.
    Requests went to backend-core and infra.
  - APK check: `tools/ci/apk-security-check.py`, with a CI request.
  - Secrets: test fixtures only.
- **N-059 (T2), local half**: `RestoreDrillReconciliationTest`. A restore copy plus the s4.8 re-send reconciles to 0
  missing and 0 duplicated; the negative control loses the tail.
  - Checks: the Sonnet check failed and was fixed; the Opus re-check passes.
  - **Not evidenced: the zone-failover half** of the acceptance (kind `failover`, HA promotion). It needs Azure.
  - Open on INT, **fixed on hardening da5f3382** (V0087 `app.mint_server_generation`, `mint-generation.sh`,
    `pitr-restore.md`); it promotes with that branch. The test calls the function once it is on INT.
  - The Azure half needs the owner's phrase in `drill.yml`.

- **N-024 widened to AMO (T1)**: `SupervisorSyncFaultFuzzTest`. The AMO record types under every sync fault; 14
  clauses, each proven by a seeded fault. The Opus checker passed it; 1,000 schedules found no defect.
- **N-055 (T2)**: `tools/checks/day5-check.ps1` (SR request, AMO verify, web approve, TSO Final Submit). The Sonnet
  checker's two majors are fixed. DEVICE-PENDING D-QA-6.
- **Supervisor scope ledger**: `docs/status/qa-scope-ledger-supervisors.md`: 60 routes, 0 leaks. Open question: TSO
  and DMO can read another zone's config through `config/resolve` and `config/values`.
- **Honor device-lab sheet**: `docs/status/qa-device-lab-honor.md`.

## Next rows
1. N-055 Day 5 script (needs F-AMO-022, F-TSO-010), N-063 Day 6 script (needs N-058, N-060), N-066 Day 7 script.
2. N-058 load on Azure: pilot size only in the test account; it is blocked on N-056/N-057 and on sizing.
3. Device rows (N-020, N-060, N-039): DEVICE-PENDING steps.

## Requests filed
- `qa-contract-conformance.md`, `qa-done-rows-check.md`, `qa-visit-radius-read.md`, `qa-apk-security-check.md`,
  `qa-release-publish-route.md`, `qa-backend-jackson-cves.md`, `qa-infra-gradle-osv.md`,
  `qa-restore-mints-generation.md` (all under `docs/requests/`).

## Traps
- `Test.environment` is not an up-to-date input in Gradle. Declare `inputs.property` too, or a changed
  `ARON_SYNC_FUZZ_SCHEDULES` reuses the last result. This is now done for `ARON_FUZZ_*` as well.
- `testApplication {}` (Ktor 3.6) has a one-minute coroutine timeout: long runs use `TestApplication` plus
  `runBlocking`.
- The existing `SyncConvergenceFuzzTest` hits its own 600-per-minute device limit above about 50 runs. That is a
  harness limit, not a product defect.
- Database guards (`client_uuid_once`, `guard_synced_row`) refuse seeded faults; seed them with
  `session_replication_role = replica`.
- Ingest posts `due_ledger` rows without a user_id: attribute them by outlet.
- Dashboards resolve their node through `dw.dim_geo`. Run the AggregationWorker (with refreshDimensions) after
  seeding, or every dashboard is refused for everyone.
- PowerShell: a helper named `Adb` shadows `adb.exe` (names are case-insensitive). In bash, `pkill -f <pattern>`
  kills your own shell when the pattern is in the command line: kill by PID.
- Local setup: `service postgresql start`; role aron/aron; db aron_test;
  `ARON_TEST_PG_URL=jdbc:postgresql://localhost:5432/aron_test?user=aron&password=aron`.
  Migrations by psql need `-1`. `.claude/worktrees/` goes in `.git/info/exclude`.
- A restore copy of a test database: `ALTER DATABASE src WITH ALLOW_CONNECTIONS false`, end its connections, then
  `CREATE DATABASE copy TEMPLATE src`, then allow connections again. Then call `softEvictConnections()` on the
  pools: otherwise their ended connections answer 503 at the next request.
- `infra/validate.sh` fails any `tools/ci/*.py` or `*.sh` that is not 100755 in git: add new scripts with
  `git update-index --chmod=+x`.
