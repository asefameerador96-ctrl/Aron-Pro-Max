# Status: lane qa (wave 2, session 1, started 2026-10-17 18:30Z)

## Done
- **N-014 + AUD-TP-2 (T1)**: the server-side harness in `backend/app/src/test` (all through the production wiring, seeded db):
  - `ContractConformanceTest` + `ContractModel` (contract/openapi.yaml as a JSON Schema 2020-12 oracle, networknt
    1.5.9, test scope): every served operation called as six seeded callers. A run makes 971 calls and validates 897
    bodies. It checks the declared status, the body schema and the ProblemCode, and fails on a route missing from the
    contract. 28 real deviations were found and frozen in a baseline that may only shrink
    (`contract-conformance-baseline.txt`); they are routed in `docs/requests/qa-contract-conformance.md`. Notable: a
    contract bug, `OutletDetail` can never validate because `Outlet` closes itself with `unevaluatedProperties: false`.
  - `ScopeLeakAcrossEndpointsTest` + `ApiWorld` mirror: a second, marked division (D-LKX) loaded from the seed files.
    Every endpoint is probed as SR, AMO, TSO and DMO with own ids, other-division path ids, selector query params and
    body ids. A run makes 908 calls, 288 of them at the other division, and 262 of those are refused. **Zero leaks.**
    The national admin is the positive control.
  - Self-tests that turn the suites red: a seeded wrong body, a seeded problem code, an undeclared status, an unknown
    route and a seeded cross-scope read. The seeded duplicate sale is in N-024.
  - AUD-TP-2 part (2), the deploy probe, already exists: `infra/scripts/slice-smoke.py` from `infra/deploy.sh`. Part
    (3), the web mock, is left alone as the audit says.
- **N-024 (T1)**: `SyncFaultScheduleFuzzTest`. Phones are the SRs of their own mirrored divisions. Random days are
  delivered under the faults reorder, partial, duplicate, drop, truncated gzip, kill mid-batch (an Error thrown out of
  ingest after the n-th stored record: committed prefix, no response) and concurrent twins. The oracle checks: exactly
  the device's rows on the server per table, in count and identity; memo balances; `server_totals` money, sticks by
  SKU and accepted per type equal to the device. Self-tests seed a duplicate sale and a doubled ledger amount. CI
  default is 240 schedules (about 40 s). The acceptance run of 10,000 schedules is recorded below.
- **N-015 (T2)**: `tools/checks/day1-check.ps1` (PASS/FAIL per check). Tested in the cloud with a fake adb and a mock
  API. **DEVICE-PENDING** for the laptop run: D-QA-1 in `device-checks.md`.

## In progress
- (see the CSV for finish times)

## Next three rows
1. N-022 Day 2 check script (airplane-mode sale, one memo on the server): needs F-SR-028 / F-API-006 / F-SYS-046 / F-SR-011 on INT.
2. N-061 security review: widen ScopeLeakAcrossEndpointsTest (sales of the other division in reports and dashboards), secrets and dependency scan, token and device-proof tests.
3. AUD-TP-8 done-rows check (`tools/ci/done-rows-check.py`, warning first).

## Requests filed
- `docs/requests/qa-contract-conformance.md` (lead: contract; backend-core, backend-admin, backend-reports).

## Traps
- Test env vars reach the Test JVM only when forwarded in `backend/app/build.gradle.kts`, and `Test.environment` is
  not an up-to-date input: declare `inputs.property`, or a changed `ARON_SYNC_FUZZ_SCHEDULES` reuses the last result.
  (Fixed for `ARON_FUZZ_*` too.)
- `testApplication {}` (Ktor 3.6) has a one-minute coroutine timeout: long runs use `TestApplication` plus `runBlocking`.
- The existing `SyncConvergenceFuzzTest` hits its own 600/min device limit above about 50 runs (`ARON_FUZZ_RUNS=60`
  fails with 429). This is a harness limit, not a product defect.
- PowerShell: a helper function named `Adb` shadows `adb.exe` (names are case-insensitive) and recurses forever.
- Local: `service postgresql start`; role aron/aron and db aron_test; `ARON_TEST_PG_URL=jdbc:postgresql://localhost:5432/aron_test?user=aron&password=aron`.
