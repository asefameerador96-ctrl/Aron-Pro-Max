## qa #3 (2026-10-25): CI time and hang, then the constant-date gate
- Slice 1 (D-QA-CI-HANG-01): `:backend:analytics:test` in its own job `jvm-analytics`, step/job timeouts, `TEST-CLASS START/END` logging in the root build, 5 m JUnit timeout on analytics. Local facts: analytics suite 38 m 35 s with 1 fork, 36 m 43 s with 3 forks (4 cores, CPU-bound on per-class Flyway migrations: 11-13 s for a one-test class); slowest class NationalDashboardTest 263 s. Idea for db/platform owners: migrated template database for `FreshDb`.
- Slice 2 (D-QA-DATE-PIN-GATE-01): follows after slice 1's lane run.

## Wave 4, session 2g: cover day, emergency off day, parking (built locally, pushed after "train 17 landed"), 2026-10-23
- `DataFlowTraceCoverHolidayTest` (cover day Monday, emergency off day Tuesday, shared `DataFlowTraceScenario`), `DataFlowTraceParkingTest`; `DataFlowTraceSupport` has a movable clock (`clockShift`) and `prepareSrDay(username, phone, routeCode)`. No finding; two observations are in the evidence doc (a sale on a called-off day is in the sales cards but in no CPR/Login figure; a parentless child after the TTL is quarantined `business_date_out_of_window`, not a final reject as DQ-03 words it).
- The handover above stays valid; strike items (2) and (3) of "Open for qa #3" once this is pushed.

# Status: lane qa (wave 4, session 2 -> 3 handover, 2026-10-23)

## Handover from qa #2
- **State:** the data-flow trace has an empty known-findings list (`data-flow-trace-known-findings.txt` holds its header only); any new difference fails `DataFlowTraceTest` by name. Heads declared and checked: 6fa6e5a8 -> e6650974 (train 13.1) -> 0adfc165 (Sunday/Monday) -> 8101f53b (slices 2 and 3, evidence doc) -> the BRAND figure check (this push).
- **Proven (all in `backend/app/src/test`, evidence in `docs/evidence/data-flow-trace-2026-10-23.md`):**
  - `DataFlowTraceTest` (Saturday, GUL-SR-2, 15 hops, incl. BRAND figures equal the brand's slice in base units across both divisions);
  - `DataFlowTraceOtherDaysTest` (Sunday GUL-SR-1, Monday BAN-SR-11);
  - `DataFlowTraceMoneyTargetsTest` (per_base_qty 1/2/3, exact .5 line, memo net half-up to the paisa, `round_adj`; monthly target over Oct, Nov doubled, Dec lighters removed, month boundary);
  - `DataFlowTraceConcurrencyTest` (8 twins under 8 batch_uuids, 8 sends under one batch_uuid, batch_uuid reuse 409, changed copy quarantined).
  The support takes the business date (`DataFlowTraceSupport(day)`); a new day or route is one subclass of `DataFlowTraceOtherDay`.
- **Local setup in a fresh container:** `service postgresql start`; `ALTER ROLE postgres PASSWORD 'pg'`; `ARON_TEST_PG_URL=jdbc:postgresql://localhost:5432/postgres?user=postgres&password=pg`; the trace classes take about 1.5 min, the full `:backend:app:test` about 20 min.
- **Also proven (session 2g, pushed after train 17):** `DataFlowTraceCoverDayTest` (stand-in SR on BAN-SR-11, acting user, Successful Login through his check-in), `DataFlowTraceHolidayTest` (division emergency off day: route not planned, sale stored and in the sales cards, no CPR/Login row; division scope leaves the national working days, a global holiday lowers them by one), `DataFlowTraceParkingTest` (parent_missing parked with `parked_until`, resend attempts, convergence, the sync-window quarantine after the TTL, no half-stored memo when a line is refused for good). Support: movable clock `clockShift`, `prepareSrDay(username, phone, routeCode)`, shared `DataFlowTraceScenario`.
- **Observations and the lead's rulings (2026-10-23):** (1) a sale on a called-off day is in the sales cards but in no CPR/Login figure: intended, the planned-only oracle (D-REPORTS-LOGIN-01); no request, one line in the owner report. (2) After the 7-day TTL a parentless child resent is `quarantined business_date_out_of_window` by the sync window, not a final `parent_missing` reject as docs/16 DQ-03 words it: lead-owned docs fix, nothing is lost, no request.
- **Open for qa #3:** (1) the dev-side rerun on a selling day needs a workflow job that carries the smoke device key (never a lane; the lead files it as a lead/infra item); (2) a final-submit/Sales Submit on the cover and holiday days, the stock issue and day_submit money block on days other than Saturday; (3) re-merge INT on request and keep the known-findings list empty (a row is added only with a request doc to the owning lane).
- **Lessons:** the trace's setup is part of the proof: the BRAND 403 of the first run was my setup (a grant insert that matched no row), now guarded by a `check`; a stale expectation after an intended product change (login needs a check-in, preview warnings only for due routes) is fixed in the test with the ruling cited, never by loosening.

# Status: lane qa (wave 4, session 2: data-flow trace)

## Wave 4, session 2f: BRAND figures in the trace (built locally, pushed after "train 16 landed"), 2026-10-23
- Hop 9 of `DataFlowTraceTest` now grants the BRAND user the brand of the first sold SKU and checks that its sales cards equal that brand's slice in base units across both divisions ({cigarette=220} against the national day with five categories of sales), that the channel STD total equals the brand's cigarette sticks, and that filter-options list its own brand only. The check first asserts that the brand's slice is a strict part of the day, so the filter has something to cut. No finding.
- Candidate 8101f53b (run #1459) is checked by the lead for train 16.

## Wave 4, session 2e: slice 3, concurrency and batch_uuid reuse (built locally, pushed after slice 2 rides train 15), 2026-10-23
- `DataFlowTraceConcurrencyTest` (33 PASS rows): (1) eight concurrent twins under eight batch_uuids: each record accepted exactly once across the answers, the rest duplicate, rows per table, server_totals and the ingest registry equal the device; (2) eight concurrent sends under one batch_uuid: no 5xx, every answer 200, nothing stored twice (observed: none is flagged `replayed` while the first is in flight, each walks the records and answers duplicate); (3) a batch_uuid reused for another record set: 409 `ERR_SYNC_BATCH_UUID_REUSED`, nothing of the second set stored, the extra memo accepted under a new batch_uuid; (4) a changed copy of a stored line: quarantined `payload_conflict`, no figure changes (server_totals and dw route-day stay equal to the device).
- Open after the three slices: Friday-style exceptions (a holiday or emergency day in the trace), a cover day (another SR on the route), dev-side runs only with secrets through the workflow.

## Wave 4, session 2d: slice 2, rounding branches and monthly targets (built locally, pushed after the lead reports train 15), 2026-10-23
- `DataFlowTraceMoneyTargetsTest`. (1) Three SKUs repriced per 2, 1 and 3 base units (an exact .5 line in a lighter box), four memos whose unrounded sum rounds down (-3), up (+2, +3) and on the .5 tie (+5) to the paisa; every figure from the phone equals stored memos (net, round_adj, gross, paid, due), stored lines, server_totals and the dw route-day and outlet nets. (2) The sales cards' daily target over October, November (every target doubled), December (lighter targets removed) and the boundary day: month total over the month's working days, target routes per category, null where no target. No finding. `Memo` in the support now carries net and round_adj (Saturday memos are multiples of 10, so unchanged).
- Next: slice 3 (concurrency and batch_uuid reuse at trace level).

## Wave 4, session 2c: slice 1, the trace on Sunday and Monday, 2026-10-23
- `DataFlowTraceOtherDaysTest` (Sunday 2026-10-25 on GUL-SR-1, Monday 2026-10-26 on BAN-SR-11): the same dev-profile world with a clock at the date. `DataFlowTraceSupport` now takes the business date as a constructor parameter (default Saturday); the memo number follows it (`yyMMdd`).
- Each day: the bundle plans only that day's route; the first bundle logs every bundled route-day in (planned or not; D-BC-PREVIEW-01 relies on it); a sale in packets (cigarette 20s and 10s, bidi, lighter, match), a credit memo with a partial due collection, a voided memo and a zero-sale visit; upload shuffled, in order, again and replayed; row counts, `server_totals` money and sold base units by SKU, dw route-day figures, `v_daily_sku` by category and the national sales cards for SUPERADMIN all equal the device. 29 PASS rows per day, no finding.
- Next slices: (2) multi-month target reading and the half-up / `round_adj` branches (a SKU with per_base_qty > 1); (3) concurrency and batch_uuid reuse at trace level.

## Wave 4, session 2b: re-merge of INT 604f9560 (train 13), 2026-10-23
- Hop 11 follows D-BC-PREVIEW-01 (backend-core 24b08bd6): an unplanned route without data is listed but not due, so the preview after the Sales Submit has no warnings; a new check before the Sales Submit proves a due, unsubmitted route (GUL-SR-2) raises `routes_not_sales_submitted`.
- The trace proved the remaining rows on this head (geo claim, memo_no_invalid x3, pack_factor), so `data-flow-trace-known-findings.txt` is empty (header only). Any new difference now fails the test by name.
- `DataFlowTraceTest` and the full `:backend:app:test` are green locally.

## Wave 4, session 2: re-merge of INT 52bdd477 (train 12), 2026-10-23
- Merged INT; `ApiWorld.kt` keeps backend-admin's version plus the devProfile hooks. `DataFlowTraceTest` and the full `:backend:app:test` are green locally.
- Known-findings list shrunk to what is still open: deleted QA-FLOW-01 (BRAND; the hop-9 403 was a trace setup bug, the BRAND grant insert matched no brand row because `product_node.code` is null; the setup now checks the insert, and the BRAND card now answers 200; proof of the scoping stays `NationalScopeLeakTest`), QA-FLOW-03 (by-outlet now has one `sold_<category>_base` column each, no `sold_qty_base`) and QA-FLOW-04 (hop 11 now asserts the preview warnings `routes_not_sales_submitted`, both active routes listed).
- Login/Submit: since train 12 a successful login needs a check-in as well as a download (brief s3.4, db V0108), so hop 8 expects 0 before the attendance and a new check after hop 11 expects 1 for the TSO.
- Still open (waiting for owners): QA-FLOW-02 and QA-FLOW-06 (backend-core 24b08bd6, train 13 or 14), QA-FLOW-07 (geo claim, backend-authz 45fc83d5, train 13).

## Wave 4 handover (2026-10-23)
- **Done:** `DataFlowTraceTest` (15 ordered hops; the open defects are rows of the shrink-only list `data-flow-trace-known-findings.txt` (each cites its request; the owning lane deletes its row with the fix; the lead accepted this pattern), `DataFlowTraceSupport` (shared world), `ApiWorld(devProfile = true)` (seed as dev loads it),
  evidence `docs/evidence/data-flow-trace-2026-10-23.md`, requests `backend-core-qa-1`, `backend-authz-qa-1`, `backend-reports-qa-1`, `db-qa-1`, `infra-qa-1`.
  Opus checker (T1 money and idempotency) ran; its label corrections and its missing edge cases (changed payload, content duplicate, memo_no duplicate, wrong pack size) are in. Local run: `:backend:app:test --tests '*DataFlowTraceTest*'` green; the rest of the lane suites were not re-run locally (the only product-test change is an optional `ApiWorld` constructor parameter): CI decides, so this head is **not** declared a candidate.
- **Successor start (lead, 2026-10-22 22:36Z):** `6fa6e5a8` (lane run #1417 green) holds INT 384ecee2 only, two promotions behind (trains 10, 11); it overlaps backend-admin 7b44110d in `ApiWorld.kt`. After the lead reports train 12 landed (about 00:30Z 2026-10-23): merge INT into lane/qa, resolve `ApiWorld.kt` (keep backend-admin's dev-profile fixture changes and the `devProfile` parameter / trace hooks), run `DataFlowTraceTest`, delete from `data-flow-trace-known-findings.txt` only rows whose fix is on the merged head and proven by the trace (BRAND hop 9: fixed by train 10, see `backend-authz-qa-1-answer.md` on lane/backend-authz 6a62f4ac; by-outlet units: backend-reports 9be08ac4 + db V0109, train 12/13; memo_no_invalid and pack_factor: backend-core #14, in progress), run the full `:backend:app:test`, push once, declare "candidate <sha>" when the lane CI is green on every job. Do not push before train 12 lands.
- **Local run facts:** full `:backend:app:test` takes 20 min locally, 12 min in CI; CI "Shared, db and backend" took 40 min.

- **Local setup in a fresh container:** `service postgresql start`; `ALTER ROLE postgres PASSWORD 'pg'`; `ARON_TEST_PG_URL=jdbc:postgresql://localhost:5432/postgres?user=postgres&password=pg`; first Gradle sync takes about 6 minutes (online, not `--offline`).
- **Open for the next qa session:** extend the trace to Sunday (GUL-SR-1) and Monday (BAN-SR-11) and to a multi-month target reading; half-up and `round_adj` branches (a SKU with `per_base_qty` > 1, a discount); concurrency and batch_uuid reuse at trace level; rerun against dev on a Saturday with the deploy's secrets only if the lead provides them through the workflow (qa never reads them); the BRAND level once backend-authz wires it; route 22636's code from the next deploy that prints codes.

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
- **Supervisor scope ledger**: `docs/status/qa-scope-ledger-supervisors.md`: 60 routes, 0 leaks. The TSO/DMO cross-zone
  config read is closed (lead-backend 8a13fe8; D-AUTHZ-Q2 then made config reads ADMIN/SUPERADMIN only).
- **Money gates (docs/31, T1)**: oracle property tests, `MoneyIngestOracleTest`, the 200-amount format corpus (web,
  shared, backend, TSO) and the no-float scan (CEILING 17). QA-MNY-01/03/04/05(lane)/06/08(lane) fixed; 02 and 07 open
  (`docs/requests/qa-money-gate-findings.md`).
- **Authorisation audit (T1)**: `AuthzAuditTest`, 1,646 probes. AZ-1..AZ-7 all fixed (backend-authz 31ede218,
  lead-backend f13adb3): 0 findings, 6 open questions; the oracle follows docs/24 s8.5 plus the lead's rulings
  (D-AMO-REPORT-02, D-AUTHZ-Q2) (`docs/requests/qa-authz-audit-findings.md`).
- **Day-close race (T1)**: `DayCloseRaceTest` is strict by default since lead-backend fixed KD-1 and KD-2 (f13adb3).
- **Date bomb**: every test config row starts at `ApiWorld.CFG_FROM` / switches at `CFG_SWITCH`, not the wall clock
  (33 test files; the dev seed's device overrides fixed by lead-backend dbfdbfca), so the suite keeps passing after
  the API test clock date (2027-01-03).
- **Honor device-lab sheet**: `docs/status/qa-device-lab-honor.md`.

## Handover (qa session 1, 2026-10-18 morning)
**On INT** (104f28c1, with backend-authz 31ede218; its calendar allowlist c081c78c is in this push): qa 7ca3c5ea, i.e. the money gates, the authorisation audit
(releases, AMO reports, AZ-7 probes), the date-bomb fix, the strict day-close race and the next_cursor probe fix.
**The last qa push** carries DeviceGate/BindDeviceChecker on `CFG_SWITCH` (the dbfdbfca seed fix is on INT), the audit
oracle on D-AUTHZ-Q2 with the TSO entry-classes and SUPPORT calendar positive controls, AZ-1..AZ-6 marked fixed, and
these status rows.

**Ratchets (all shrink-only):**
| Ratchet | Where | Now |
|---|---|---|
| Authorisation findings | `backend/app/src/test/resources/authz-audit-baseline.txt` | 0 (comments only) |
| Scope-blind reads | `backend/app/src/test/resources/scope-blind-baseline.txt` | 35 lines (23 global, 8 per-caller, 3 debt, 1 national-only) |
| Money float allow-list | `tools/ci/money-float-allow.txt`, CEILING in `tools/ci/money-float-scan.py` | 17 / 17, 0 open findings |
| Web money format | `web/tests/money-format-corpus.test.ts` KNOWN_CEILING | 0 |
| TSO compact format | `TsoCompactCorpusTest` knownCeiling (android-tso owns it) | 0 |
| Day-close race | `DayCloseRaceTest`, `ARON_DAYCLOSE_TOLERATE` | `none` by default (strict) |

**Open findings and questions:**
- Money: QA-MNY-02 (QC settlement vs qty x price, flag only, waits on D-34/MQ-03; the fix needs `price_per_qty` in the
  contract), QA-MNY-07 (`LocaleDigits.formatInteger` lakh grouping, android-core, no owner free). QA-MNY-05 and 08 are
  fixed on lane/android-tso; on INT check `MoneyGateReviewTest`'s `@Ignore` (remove it and add the four oracle
  boundaries in `docs/requests/qa-money-gate-findings.md`).
- Authorisation: 6 open questions, none failing: C2 -> C3 for two keys (final account), the `pii` claim never set,
  `editor_permission` (D-AUTHZ-Q5 follow-up), and DMO/WM refused enrolment-token reads (stricter, kept). Several
  reach refusals answer 403 ERR_FORBIDDEN, not ERR_OUT_OF_SCOPE (contract note).
- N-061: the CI scan of the three release APKs and the Maps/Firebase key restrictions (owner console step), the
  Jackson 2.22.3 pin (backend-core) and the Gradle OSV CI step (infra).
- Contract conformance: the shrinking baselines in `docs/requests/qa-contract-conformance.md`.

**Next for qa:** N-063 Day 6 script when N-058/N-060 produce report files; the replay-after-state-change cases
(feedback, leave, visit plans, Idempotency-Key config changes, price batch, dues adjustment); the device rows
(DEVICE-PENDING).

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
