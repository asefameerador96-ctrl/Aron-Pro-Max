# 20 — Test Strategy, Phase Gates, CI/CD and Cutover Verification

Date 2026-10-04. Owner: QA lead with the tech lead. Inputs: CLAUDE.md, docs/01 to 13, docs/22, docs/ui-reference, the four manual inventories and the manual delta register, the six verification files, and the decisions D-01 to D-269 as amended in doc 14.

**What this doc decides**

> 1. A gate is a test, an evidence artefact under `/docs/evidence/phase-N/<T-id>/` and a named verifier who is never the author; a phase exits when every blocking gate is green and the exit report carries two signatures (D-141, s1).
> 2. The gate register (s3) places every T-id from every lens and critic, resolves id collisions and adds the manual-parity gates (T-x-120 to T-x-139) and the doc 20 additions (T-x-140 and up); a gate with no known oracle is `pending-oracle`, not green (D-450, D-451).
> 3. Parity has an oracle: the baseline pack (T-0-49) plus golden fixtures whose arithmetic is re-checked before use (D-142, s4), and the pilot passes only after ten consecutive trading days with zero sync loss (D-145, s7).
> 4. CI/CD is GitHub Actions with blue/green traffic weights 10, 50, 100 and auto-abort, forward-only migrations in three releases (expand, backfill, contract), flags as config, and app waves staged by `wave_pct` (D-14, D-99, D-456, s5).
> 5. Cutover is verified by zero-tolerance control totals, timed rollback drills, a signed per-wave readiness checklist, go/no-go thresholds and a tiered support flow with its arithmetic (D-149, D-153, D-454, D-455, s7).

How to read this document: sections 1 to 3 define the system and the register; 4 to 6 define the oracles and the pipelines; 7 is the cutover; 8 to 10 are the mechanics and scripts a non-engineer can run. Every number without a source is marked ASSUMPTION. `unknown; confirm with the business` marks what only AKTCL can answer, with the proceed-with default. Ids not defined in doc 14 are minted only in the blocks reserved for this document (D-450 to D-469, G-20-01 to G-20-40).

## 1 Gate system

### 1.1 Definition (D-141)

A **gate** is a claim that is proved by three things at once: a **test** (a command, a script or a human protocol with a script), an **artefact** (a file that survives the run) and a **verifier** (a named role that is not the author of the work). Prose definitions of done (docs/12) are not gates.

| Field | Rule |
| --- | --- |
| `id` | `T-<phase digit>-<nn>`, unique across the whole register. The phase digit is the phase in which the gate must be green (D-450). |
| `phase`, `sub` | the sub-milestone code (0a to 7e) whose exit the gate blocks. A gate is placed where its feature exits, not where its test is first written; an automated gate runs on every PR from the day its test exists. |
| `lens` | DAT data, FRD fraud, SYN sync, QUA quality, SCA scale, SRE reliability, CFG config, SEC security, PIL pilot and cutover (range 80 to 89), FLD field, ANA analyst, MAN manual parity, D20 doc 20 additions. |
| `check` | what is proved, with the numbers; every check names the decisions and gaps it closes. |
| `artefact` | the file left in `/docs/evidence/phase-N/<T-id>/`; `G` means the entry in that run's `gate-report.json` plus the CI run URL. |
| `verifier` | role code (s1.3). The author and the verifier are different people; for a human gate the verifier runs the one-page script stored next to the artefact. |
| `run` | cadence: PR every pull request; N nightly; RC every release candidate; PH once at the sub-milestone or phase exit; W every wave; D every pilot day; Q quarterly drill. |
| `blocking` | Y blocks the phase exit; N is tracked and must close before 7b entry; P blocks 7b entry; W1 blocks wave 1 (7c); WV blocks every wave. |
| `status` | `planned`, `automated-green`, `human-pending`, `signed`, `pending-oracle` (D-451), `waived` (a waiver names the risk accepted, the accepting role and an expiry; it is never silent), `unproven` (a gate marked automated with no tagged test in the last 7 days of runs). |

### 1.2 Phase exit and sub-milestone demos

1. **Exit report** `/docs/evidence/phase-N/EXIT.md`: the generated gate table for the phase with links, open gaps carried forward (owner, target sub-milestone), decisions taken (D-ids), measurements (battery, size, p95), and two signatures: the tech lead and the sponsor's delegate (D-141).
2. **Entry criteria** are the previous phase's exit plus the external inputs named in doc 14 s2.3. An entry criterion that is not met is written down with the risk accepted; it is never skipped silently.
3. **Demo**: every sub-milestone ends with a demo of at most 30 minutes that a non-engineer can run from a script. The script is the artefact (`demo.md`); the sponsor's delegate performs it unaided at least once per phase (s10).
4. **Independence**: engineering gates (unit, integration, property, load) are verified by the tech lead from the generated gate report; business-facing gates (parity, usability, reconciliation, readiness) by the sponsor's delegate, the pilot lead, the finance owner or the native-Bangla reviewer, using the stored script.
5. **Evidence integrity (D-452)**: each evidence directory carries `MANIFEST.json` (sha256 per file, commit, run URL, verifier, date). Frames, photos and recordings that show retailer or rep data are masked before storage; the baseline pack lives under a private path with an access list (T-0-140, G-20-01).

### 1.3 Verifier codes

Named persons are assigned by the sponsor (D-156, MUST-CONFIRM by 0a; Q34). Until then the role code is the contract.

| Code | Role | Signs |
| --- | --- | --- |
| TL | tech lead | engineering gates from the generated report |
| REL | release manager | reproducible-APK rebuild, app releases |
| QA | QA lead | device-lab, patrol, protocol gates |
| OPS | ops lead | pipelines, observability, drills, reconciliation reports |
| SEC | security owner | security gates and the `/cso` audit |
| SD | sponsor's delegate (business verifier, one per phase) | parity, usability, readiness, exit reports |
| PL | pilot lead | pilot comparison, spot checks, daily sheet |
| FIN | finance owner | dues and opening-balance control totals |
| NB | native-Bangla reviewer (Q35) | string catalogue, glossary, goldens in bn |
| SPN | sponsor | wave readiness, go/no-go, decommission |
| SUP | support lead | helpdesk readiness, scripts |
| RM | retailer-facing manager (trade marketing or TSO, Q37) | "same memo" co-signature |

Human sign-off load is a risk (G-20-05): s3.12 counts the human-signed gates per phase.

Proved by: T-0-46, T-0-140, T-0-142.

## 2 Test pyramid and tooling

### 2.1 Principles (each is a CI rule or a review checkbox)

| # | Rule | Enforced by |
| --- | --- | --- |
| P1 | Every test that proves a gate carries the gate id as a tag (`@gate T-1-21` in the test name, Playwright `tag`, Dart `@Tags(['T-1-24'])`); CI emits `gate-report.json` per run. | `rtm-check` (s8) |
| P2 | No mocks for the database, the local SQLite database or the contract. API integration runs on Testcontainers PostgreSQL 16 with the real migrations; app tests run on a real Drift database; clients are generated from the contract (D-02, D-150). The only fakes allowed are hardware and third parties: `PrinterPort`, FCM, map tiles, the clock. Apsis is never called (CLAUDE.md guardrail). | lint `no-pg-mock`, review |
| P3 | The clock is injected everywhere (`Clock` interface in api, app, web). The suite runs at three fixed Dhaka instants: 10:00, 16:59:30 and 23:58. A direct `DateTime.now()` or `new Date()` fails lint (G-qa-20). | lint `no-wall-clock`, T-0-47 |
| P4 | Test data is synthetic and PII-free from `/packages/testkit` (s9); the Apsis sample never enters CI (D-151). | gitleaks Bangladesh-phone rule, repo policy, T-0-45, T-0-140 |
| P5 | Flaky tests are quarantined within one working day (tag `@flaky`, excluded from blocking, listed in the nightly flake report) and fixed or deleted within 10 working days; a quarantined test cannot prove a gate. | nightly report |
| P6 | PR checks finish in 15 minutes or less; nightly in 3 hours or less; the device-lab RC run in one working day plus the 8-hour battery protocol. Anything slower moves down the schedule; it is never dropped. | workflow timeouts |
| P7 | Coverage is a floor, not a target (ASSUMPTION: planning targets): api 80 % lines overall and 95 % on `sync/ingest`, `scope`, `money`, `business_date`, `geo`; app `domain/` 85 %; web 70 %. Stryker mutation score 70 % on the five api modules above. Dart has no mature mutation tool, so property tests substitute. | CI thresholds, T-1-42 |
| P8 | Each gate has one owner (engineering) and one verifier (business or ops, never the owner). | `gates.yaml` columns |
| P9 | Money is integer milli-taka (D-15): a lint fails any float operation on a `_mtk` value; quantities always carry their unit (sticks, pieces, dozens, boxes). | lint `no-float-money`, T-2-37, T-2-38 |
| P10 | A test whose oracle is unknown is written against the proceed-with default and marked `pending-oracle` (D-451); it is never deleted and never counted green. | `gates.yaml` status |

### 2.2 The pyramid by component

Counts are planning targets at 7b entry (ASSUMPTION); `rtm-check` verifies coverage against the F-ids of doc 15, so a count is never padded to reach a number.

| Layer | Component | Tooling | Target count | Runtime | Runs on | Blocks |
| --- | --- | --- | --- | --- | --- | --- |
| L0 static | all | ESLint and strict TypeScript, `dart analyze`, `squawk` (SQL), Bicep lint, custom lints (`no-wall-clock`, `no-pg-mock`, `no-unscoped-query`, `no-hardcoded-string` for Flutter, `no-float-money`, `cfg-literal-registered`) | n/a | 2 to 4 min | every PR | merge |
| L1 contract | `/packages/contract` | Zod to OpenAPI 3.1 to generated TypeScript (`openapi-typescript`, `openapi-fetch`) and Dart clients; golden JSON fixtures round-tripped in both languages; one enum-drift test per enum; N-1 and N-2 fixtures; `oasdiff` | about 250 fixtures, about 30 drift tests | 3 min | every PR | merge |
| L2 unit | api | Vitest: money, rounding mode, business date, Haversine, memo arithmetic, config resolver, scope expansion, KPI formulas with `safe_div` | about 1,500 | 2 min | every PR | merge |
| L2 unit | app | `flutter test`: outbox invariant, batch builder, memo number, resolver, business date with offsets, print raster layout | about 800 | 3 min | every PR | merge |
| L2 unit | web | Vitest and Testing Library: KPI components, filters, scope-bounded selectors, Bangla and Latin number formatting | about 400 | 2 min | every PR | merge |
| L3 integration | api | Vitest and Testcontainers (PostgreSQL 16, Azurite, Redis) connecting AS THE RUNTIME LOGINS through PgBouncer in transaction mode, never as a superuser (D-566; T-0-158, T-1-153): every endpoint times happy path, scope-negative and idempotent retry; DQ rules of doc 16 s7; RLS on and off | about 450 | 6 min (4 shards) | every PR | merge |
| L3 property and fuzz | api sync | `fast-check`: the 20 properties of s2.4; 10,000 runs per PR, 100,000 nightly, seeds persisted; corpus replay of anonymised pilot batches after 7b | 20 properties | 4 min PR, 90 min nightly | PR, nightly | merge |
| L3 property | app sync | hand-rolled Dart generators for outbox, ACK and apply purity; shares the JSON corpus in `/packages/testkit/corpus` with the server | 8 properties | 3 min | every PR | merge |
| L3 widget | app | `flutter test` goldens at 320 x 640 dp in bn and en for every SR, AMO and TSO screen; TSO dark theme; Bengali conjuncts; long labels do not overflow; semantics check (every tappable labelled; font scale 1.3 does not break the sale screen) | about 300 (146 screens in the manual inventory times 2 locales) | 5 min | PR (app paths) | merge |
| L4 component | web | Playwright component tests with role personas (WM, DMO, TSO, admin bundles) | about 120 | 4 min | PR (web paths) | merge |
| L4 e2e | web and api | Playwright on an ephemeral compose stack (20 smoke flows on PR; full matrix of about 250 flows times 3 personas on staging); visual regression at 1280 and 390 px; `axe` | about 250 | 8 min smoke, 40 min full | PR smoke, main full | promote |
| L4 integration | app | `patrol` on emulator (6 smoke flows on PR) and on the device lab (about 45 scenarios on RC: airplane mode, kill points through `--dart-define=CHAOS_KILL_AT`, permission dialogs, printer fake) | about 45 | 10 min emulator, 3 h lab | PR smoke, RC lab | release |
| L4 cross-stack | app, api, web | patrol drives a phone, Playwright asserts the tile (T-1-40, T-3-43) | 6 | 20 min | RC | release |
| L5 importer | `/api/import` | synthetic dump generator with planted defects (s9), import, reconcile, re-import is a no-op, quarantine counts equal planted defects | about 60 | 6 min | PR (import paths), nightly full size | merge, 7a |
| L5 load | api | k6 smoke on every main build (S2 shape, 5 min, 1/50 scale); Azure Load Testing with Locust device simulator for S1 to S10 (doc 18 s8) at the T-4-5x and T-7-5x gates | 10 scenarios | 5 min smoke, 1 to 10 h scenarios | main, gates | promote, wave |
| L5 device lab | app | battery and data protocol of s2.7 on the primary device per RC; secondary and legacy once per phase; APK size in CI; cold start | 1 protocol | 8 h | RC | release |
| L5 security | all | cadence of s2.8 | per PR and per phase | 5 min PR | PR, phase | merge, pilot, wave |
| L6 human | all | golden-screen sign-off against the baseline pack; Bangla string review; usability sessions (AMO, TSO); run-without-engineering drill; pilot dress rehearsal | per phase | days | phase exit | phase |

### 2.3 Contract tests (D-150)

1. **One source**: Zod schemas in `/packages/contract/src` for every request and response, every sync record type (`type`, `family_uuid`, `rank`, `client_uuid`, payload), every enum and reason code, the config key registry types and `schema_version`. Business code lists are `cfg` content, so the contract carries only the shape `{code, label_bn, label_en}`.
2. **Generation**: `pnpm contract:build` emits `openapi.json`, the TypeScript client and the Dart client into `/app/lib/generated/`; the output is committed and drift-checked (CI regenerates and fails on any diff).
3. **Golden fixtures** under `/packages/contract/fixtures/<type>/<case>.json`: minimal, full, Bangla text, boundary values (`qty_base` at `cfg.sale.max_line_qty_base`, due 0, zero sale), and one rejected example per reason code. Both languages parse every fixture and re-serialise it byte-identically after canonicalisation.
4. **Compatibility**: the server accepts `schema_version` N, N-1 and N-2; fixtures are versioned (compile-time test here; runtime test T-2-33).
5. **Enum drift**: the PostgreSQL code-table seed, the Zod enum and the Dart enum are compared by one test that reads all three.
6. **Breaking-change gate**: `oasdiff` against the last released `openapi.json`; a breaking change needs a `schema_version` bump and a `BREAKING.md` entry or the PR fails.

### 2.4 Sync-engine properties and kill tests (doc 17 s12 owns the plan; the cadence is here)

| Gate (register row in s3; this table adds the generator and oracle) | Property or kill point | Generator or hook | Oracle |
| --- | --- | --- | --- |
| T-1-20 | outbox invariant: domain rows equal outbox rows; payload hash equals stored row hash, also after random kills | random day of 1 to 300 captures | equality after every operation |
| T-1-21 | idempotent convergence: any partition into batches with 0 to 50 % duplicates, 0 to 20 % children before parents, 0 to 20 % truncated tails and `batch_uuid` replay equals one clean batch | 10,000 PR, 100,000 nightly | row-by-row equality of `app.*` and `dw.agg_*` for touched dates |
| T-1-22 | replay returns a byte-identical response within 24 h; a different row set under the same `batch_uuid` returns 409; the stored response is committed in the ingest transaction before any cache write | crash injection between commit and cache | stored response |
| T-1-23 | ACK application is pure: never leaves `syncing`, never flips `synced` to `pending`, device count equals accepted plus rejected plus conflict | random ACKs including unknown `client_uuid` | invariants |
| T-1-25, T-1-26, T-1-27 | family atomicity; backoff bounded, jittered, handed to WorkManager after 5 failures; business date around midnight Dhaka with offsets up to 48 h | simulated streams | reference model |
| T-2-20, T-2-21 | memo numbers never collide (bind-ordinal slot up to 3, 500 per block); edits, dues and stock as events never double a memo count, due balance or stock balance | random supersede chains | ledger equality with a reference model |
| T-1-24, T-1-28, T-1-29, T-2-22, T-2-23, T-2-24, T-3-20 | kill between memo commit and first printer byte; mid-send; mid-ACK; mid-photo; during Drift migration with 200 pending rows; during delta apply; during Sales Submit | `adb shell am force-stop` and the debug-only chaos hook | exactly one copy or none; nothing lost, nothing doubled |

### 2.5 Flutter, web and API specifics

| Area | What is tested | Oracle |
| --- | --- | --- |
| Ingest `/sync/batch` | every DQ rule as a table-driven test; children-first ordering; replay byte-identical; `server_totals` arithmetic; gzip bomb returns 413; a 200-row batch under 150 ms on the CI container | DQ table, stored response |
| Bundle | contents per role; delta `?since=`; ETag and 304; pre-generated snapshot equals live generation for 50 random users nightly | snapshot diff |
| Scope | two-user harness over random scope trees on every endpoint through route-table introspection: a new endpoint without a harness case fails the suite (T-0-75) | zero foreign rows |
| Aggregation worker | recompute by dirty key equals a brute-force SQL over `app.*` for random days; a late batch touches only its date; supersede cascades | brute-force view |
| Day state | transition table as a state-machine test: every (state, event) pair has an outcome or a rejection | transition table |
| Reports | each report is a `dw`-only query matching a hand-computed fixture; the `app_web` role has no SELECT on `app.*` transactional tables (T-4-43) | fixture |
| Print | memo template to ESC/POS bytes through the `PrinterPort` fake, rasterised and compared with a golden PNG at 0.5 % pixel tolerance for the 7 memo kinds plus the day summary (T-1-141); physical print at T-1-35 and T-2-41 | golden PNG, photo |
| Upgrade matrix | install N-2 and N-1 APKs with 200 pending rows and one draft sale, upgrade to N, counts intact, sync succeeds (T-2-44); a build older than N-1 refuses a newer database with a clear message, while N-1 opens it for one release (additive schema, D-79) | `adb install -r` |
| Web | every page times persona including the scope negative (a WM of wing 3 asking for wing 4 gets 403 and sees nothing); Excel export compared cell by cell to a golden `.xlsx` for STD Memo, Data Entry Log and Final Submit Log; Lighthouse performance 80 or more and dashboard JavaScript 350 KB gz or less (T-4-47) | golden file |

### 2.6 Connectivity control in tests

Connectivity is driven from the host over Wi-Fi ADB (`adb shell cmd connectivity airplane-mode`, `svc wifi`); USB would charge the phone and corrupt the battery numbers. Throttling uses `mitmproxy` with a 2G profile (50 kbps, 400 ms) and doubles as the zero-chatter recorder (T-1-44). Captive-portal Wi-Fi is simulated by a network that answers 200 to the validation HEAD with an HTML body (T-1-32, D-59).

### 2.7 Device lab and battery and data measurement protocol (D-12, D-73)

**Lab (ASSUMPTION until the fleet census, Q31).** A rack in the Dhaka office with 2 phones of each reference class: primary Redmi 9A class (2 GB, Android 10 or 11, MIUI), secondary Samsung A03 Core class (2 GB, Android 11 Go), legacy Android 8.x with 2 GB or less; 2 printers (an RPP02N and one clone); Wi-Fi ADB; a self-hosted GitHub runner that only runs `device-lab` jobs for protected tags (D-122); `mitmproxy`. Firebase Test Lab is used for breadth only (Android 8 to 14 smoke on about 12 common models), never for budgets. Budgets are valid only for the reference class; the census may change the class (OI-20-03).

**Scripted field day** (the measurement workload and the integration test script): 50 outlets on one route; 13 photos (5 force sales, 5 outlet captures, 3 survey; photos(n) = round(0.267 n), doc 17 s8.2, D-508); 2 attendance fixes; 60 GPS fixes (50 opens, 5 refreshes, 5 force-sale fixes); 50 memos printed, 1 stock memo, 1 day summary; 55 trickle batches with connectivity toggled on a fixed pattern (online 60 % of the time); 1 full bundle and 3 deltas; Sales Submit and check-out at 17:00 Dhaka; 90 minutes screen-on at 50 % brightness; the printer connected during Stock and at each print. Duration 8 hours; a 2-hour variant times 4 is allowed between RCs, but the gate numbers come from the 8-hour run.

**Protocol (per RC, primary device; once per phase on the secondary and legacy devices):**

1. Fresh install of the RC; printer paired; `adb shell dumpsys batterystats --reset` and `dumpsys netstats --reset`; unplug; start the script (patrol) with the connectivity pattern driven from the host.
2. After 8 hours `adb bugreport` into Battery Historian; read for the app uid: estimated power (screen excluded), wake-lock total and longest, GPS time and fixes, mobile-radio active time, job executions, alarms, foreground-service time. `dumpsys netstats detail` gives mobile and Wi-Fi bytes per uid.
3. Background variant: 50 pending rows, airplane mode, app backgrounded 8 hours; then enable the network and require a sync within 3 minutes.
4. Zero-chatter check (T-1-44): 1 hour idle foreground and 1 hour background with an empty outbox behind `mitmproxy`: zero requests other than the explicit refresh.
5. APK size in CI per ABI against the gate and the previous release (plus 5 % warns, plus 15 % fails); cold start with `adb shell am start -W`.
6. Record the numbers in `/docs/perf/battery-<version>.md`; a build is not shippable without it; a regression above 20 % against the previous release blocks the release.
7a. THREE FLAVOURS (D-506, G-qa-30): the SR script above is the SR oracle only; the AMO and TSO flavours run their own scripted days (doc 17 s8.2b: a 54-route-zone AMO day with 8 supervisory calls, Team Location, Update Base and a Wi-Fi pre-fetch; a TSO day with a login snapshot, dashboards, a Final Submit and two map opens), and `/docs/perf/battery-<version>.md` has an SR, an AMO and a TSO section per release candidate; the AMO and TSO gates are blocking from 3a and 3b (T-3-151 to T-3-153).
7b. CEILINGS (D-546): the protocol is also run with each budget-linked key of doc 19 s2.6 at its ceiling (T-2-156).
7c. ROUTE SIZES (D-508, G-qa-32): the budgets apply to the 50-outlet script AND the median (64) and p99 (112) routes, and the median and p99 values are BLOCKING (the Board-facing row shows all three).
7. Baseline comparison (IMPROVEMENT, ASSUMPTION that the Apsis app is installed on the primary device): run the same script on the Apsis app once in Phase 1 and record its numbers beside ours, because docs/11 says cutover must not make phones worse.

**Budgets (pass criteria).** D-73 values are decisions; rows marked L are lens-sync additions (our numbers, ASSUMPTION).

| Metric | Gate | Source |
| --- | --- | --- |
| App non-screen drain over the scripted 8-hour day | at most 6 % of a 5,000 mAh battery (300 mAh) | D-73 |
| Background drain, 8 hours, 50 pending rows, no network | at most 1 % | D-73 |
| Background drain, 8 hours idle with network and an empty outbox | at most 0.5 % | L |
| Whole-device level at 17:00 from 100 % at 08:00 | at least 60 % | L |
| GPS | at most 80 fixes and 15 minutes per day; no position stream; no background location | D-73, D-74 |
| Wake locks | at most 10 minutes per day and 90 seconds each | D-73 |
| Mobile-radio active time attributable to the app | at most 20 minutes per day | L |
| WorkManager executions | at most 100 per day | L |
| Foreground services and alarms | 0 and 0 | D-73 |
| Mobile data per day | at most 1 MB without photos; at most 3 MB with the photos of the 50-outlet script (13) all on mobile, scaled to 3.7 MB for the median route (17 photos) and 6.0 MB for the p99 route (30 photos): the photo count has one source, 17.6 a day at the average route (D-508, G-qa-32); AMO at most 2 MB and TSO at most 1.5 MB without photos | D-73, D-506, D-508 |
| APK per ABI; installed size | at most 30 MB (target 22); at most 70 MB | D-73 |
| Cold start | at most 2.5 s on the primary device | D-73 |
| Photo compression CPU | at most 1.5 s per photo (T-2-30) | L |

The installed-size baseline of the current apps is 92 to 101 MB and the APK files are 74 to 80 MB (D-224); docs/04's "about 90 MB" is the installed footprint.

### 2.8 Security testing cadence (D-457; mechanisms in doc 21 s9 and s10)

| Cadence | Test | Tool | Gate | Owner |
| --- | --- | --- | --- | --- |
| every PR | SAST; dependency review and OSV on `pubspec.lock`; secret scan with push protection; IaC scan; lint `no-unscoped-query`; APK config check | CodeQL, Semgrep, Dependabot, OSV-Scanner, gitleaks, PSRule and checkov | T-0-70 to T-0-73, T-0-76, T-1-74 | TL |
| every main build | image scan, SBOM and cosign signature; auth negative suite; scope-leak harness; log-redaction injection | Trivy, Syft, cosign | T-0-74, T-0-75, T-1-75 | TL |
| nightly | dependency audit; scope-leak property test; stale-flag and dead-key reports | OSV, `fast-check` | T-0-75, T-0-144 | TL |
| every release to staging | authenticated DAST baseline | OWASP ZAP | T-2-74 | SEC |
| per phase | IDOR sweep of every endpoint with foreign ids; RLS performance (at most plus 10 % p95); BI-role grants | scripted | T-4-70, T-4-74, T-4-71 | SEC |
| before the pilot (2e) | gstack `/cso` audit of api, web, app, infra; adversary lab with real spoofing tools | `/cso`, lab | T-2-73, T-2-10 | SEC |
| before wave 1 (7c) | independent external penetration test on staging at prod SKU with anonymised data; reproducible-APK rebuild; wave-day auth storm; Apsis-dump credential handling | external firm | T-7-70, T-7-10, T-7-75, T-7-74 | SEC |
| per release | independent reproducible rebuild of the release tag; release publish is a human action | CI plus a second runner | T-7-10 | REL (release manager) |
| quarterly | key-rotation drill, incident tabletop, DR restore including Key Vault, access review | drills | T-7-71, T-7-72, T-7-73 | SEC |

### 2.9 gstack skills in the test loop (CLAUDE.md tooling)

`/review` on every PR; `/qa` and `/qa-only` for browser and API flows on staging; `/benchmark` for the web performance budget (T-4-47); `/cso` before the pilot (T-2-73); `/canary` after every prod promotion; `/test-audit` monthly to find duplicate or low-value tests and the code they keep alive; `/investigate` for any category C discrepancy. A skill run is an input to a gate; it does not replace the verifier.

Proved by: T-0-40, T-0-41, T-0-42, T-0-45, T-0-47, T-1-20, T-1-21, T-1-42, T-1-44, T-1-45, T-2-40.

## 3 Gate register

### 3.1 Range map, collision rules and aliases (D-450)

| Range | Lens | Content | Defined in |
| --- | --- | --- | --- |
| 01 to 09 | DAT | schema, seed, idempotent aggregation, control totals (01 to 04 used; 05 to 09 reserved for doc 16) | lens-data, doc 16 |
| 10 to 19 | FRD | geo spoofing, clock, signatures, outlet drift, risk signals, PII budgets, rails direction, APK provenance | fraud critic, doc 21 |
| 20 to 39 | SYN | outbox, convergence, kill tests, airplane mode, printer, memo numbers, budgets (20 to 35 used); 36 to 39 are the 2a engine gates owned here | lens-sync, doc 17 |
| 40 to 49 | QUA | test infrastructure, CI/CD, observability, parity, usability | this doc |
| 50 to 59 | SCA, SRE | scale (51 to 57 as minted by the scale lens) and the SRE additions in the slots the scale lens left free (50, 51, 53 to 59) | lens-scale, SRE critic, doc 18 |
| 60 to 69 | CFG | registry, bounds, propagation, approvals, revert | lens-config, doc 19 |
| 70 to 79 | SEC | scans, auth, scope leak, PII, pen test | lens-security, doc 21 |
| 80 to 89 | PIL | pilot, cutover, support, decommission | this doc |
| 90 to 99 | FLD | field-reality gates | field critic |
| 100 to 109 | ANA | analyst gates, renumbered (104 to 109 free) | analyst critic, doc 16 |
| 120 to 139 | MAN | manual-parity gates derived from the register, allocated here | this doc |
| 140 to 199 | D20 | additions raised while writing this document | this doc |

Collision and alias record (the lower-numbered source range keeps the id; the other is recorded here):

| Event | Resolution |
| --- | --- |
| Analyst gates T-x-90 to T-x-93 collided with the field critic's 90 to 99 | the analyst's originals move to 100 to 103 (the field critic keeps every T-x-90 to 99 id): analyst T-0-90 is now T-0-100; T-1-90, 91, 92 are now T-1-100, 101, 102; T-2-90 to 93 are now T-2-100 to 103; T-3-90 is now T-3-100; T-4-90, 91, 92 are now T-4-100, 101, 102; T-5-90 is now T-5-100; T-7-90 is now T-7-100 |
| Analyst T-1-27 and sync T-1-27 | the same business-date property; one gate, kept as sync T-1-27 |
| Data T-1-01 and T-1-02, sync T-1-21 and scale T-1-51 | the same idempotent-convergence property seen from three lenses; three ids kept for traceability, T-1-21 is the property run, T-1-51 the staging and k6 run, T-1-01 and T-1-02 the aggregate assertions of the same run |
| Sync T-2-34 and scale T-2-54 | the same TLS-chain test on old Android; two ids, one run, both signed from it |
| Data T-4-02 and security T-4-71 | the same `bi_reader` grant test; one run |
| Pilot T-7-20 (sync) and T-7-82 | the same 10-day parallel run; one run, both ids signed |
| SRE critic reused the free slots of the 50 range | kept as minted; no overlap with scale's used ids (verified in `gates.yaml` by the duplicate-id check) |
| Lens-quality decision numbering | replaced by the canonical D numbers (D-141 and following) |

Placement rule: ranges are firm, the sub-milestone is placed by where the feature exits. Skeleton listings that differ from this placement are recorded in OI-20-14.

### 3.2 Column legend

Lens, verifier and run codes are defined in s1. Artefact `G` is the gate-report entry; other names are files in the evidence directory. Blocking codes: Y, N, P (blocks 7b entry), W1 (blocks wave 1), WV (every wave). References such as G-man-nnn are register entries (doc 14 s7 gives the master id where an entry was merged).

### 3.3 Phase 0: Foundations

**0a Tooling**

| ID | Lens | Check | Artefact | Verifier | Run | Blk |
| --- | --- | --- | --- | --- | --- | --- |
| T-0-40 | QUA | PR pipeline complete (lint, typecheck, unit, contract, integration, smoke e2e, scans, `rtm-check`) in 15 minutes or less; required checks and merge queue configured; a PR touching all workspaces passes | run URL; branch-protection screenshot | TL | PH | Y |
| T-0-42 | QUA | Testcontainers harness: fresh PostgreSQL 16, every migration present, seed, `testkit fleet` 1 zone in under 10 s; the scope harness auto-discovers routes | G | TL | PR | Y |
| T-0-43 | QUA | Deploy pipelines: main to staging (migrate expand, image, revision at 0 %, smoke, 100 %, e2e) and manual promote to prod with 2 approvers, both exercised against the health endpoint; OIDC, no stored cloud secret (D-04, D-14) | deployment history; `az containerapp revision list` | OPS | PH | Y |
| T-0-44 | QUA | Observability baseline: one request gives a pino line with `request_id` in Log Analytics, an App Insights trace to PostgreSQL, a Sentry event from app and web with PII scrubbed; the ops workbook has 5 tiles (D-13) | KQL screenshots; workbook JSON in `/infra/monitor/` | OPS | PH | Y |
| T-0-45 | QUA | `testkit` v1 (`fleet`, `day`, `personas`) is deterministic by seed and PII-free (gitleaks Bangladesh-phone rule passes on the output; G-qa-08) | G | TL | PR | Y |
| T-0-46 | QUA | RTM in the repo: `rtm.yaml` lists every F-id of doc 15 with a phase and one planned gate or more; `gates.yaml` lists every T-id with an artefact path; `gaps.yaml` lists every master gap; `rtm-check` fails on orphans and on rules 9 to 13 of s8.2; the generator reads `/docs/evidence/manuals/` and `/docs/evidence/verification/` (D-530); the F-id counts of doc 14 s1.1 and s3 equal doc 15 s1.3 and the scope rows of doc 14 s3 are generated from `rtm.yaml` (D-516) | rtm-check output; generated `RTM.md`, `GATES.md` | SD | PR | Y |
| T-0-47 | QUA | Injectable clock in api, app and web; the suites pass at 10:00, 16:59:30 and 23:58 Dhaka; `no-wall-clock` lint clean (G-qa-20) | G (3 jobs) | TL | PR | Y |
| T-0-48 | QUA | Device lab commissioned (s2.7): reference devices and 2 printers inventoried, Wi-Fi ADB, runner online, a debug APK installs and launches on all (G-man-026: census of the fleet is Q31) | inventory sheet with photos; runner log | QA | PH | Y |
| T-0-49 | QUA | Baseline pack captured (s4.1): recordings, at least 25 printed memos, 37 or more web page captures, one month of Excel exports for 3 pilot zones, string glossary, the four manuals, and the Apsis USAGE CENSUS of item i of s4.1 (page and endpoint visit counts from AKTCL's own Apsis admin or audit views, plus distribution-house and DMO or WM interviews; D-503) (G-qa-01, D-142) | `/docs/baseline/INDEX.md` | SD | PH | Y |
| T-0-140 | D20 | Evidence and baseline stores: private path with an access list; every frame, photo and memo scan masked (retailer phone, owner name, NID); `MANIFEST.json` check; the Apsis sample never reachable from CI (D-151, D-452; G-20-01) | access list; mask log | SEC | PH | Y |
| T-0-142 | D20 | Sponsor's delegate per phase and the four readiness owners (engineering, ops, business, support) named in writing (D-156; G-qa-06) | signed roster | SPN | PH | Y |
| T-0-145 | D20 | Compatibility mechanisms live: `squawk` on migrations, shipped-migration checksum check, nightly `schema-compat` (previous release's api suite against current migrations), `oasdiff`; one deliberate-violation PR per rule fails (G-qa-10) | the 5 failing PR runs | TL | PR | Y |

**0b Schema and contract**

| ID | Lens | Check | Artefact | Verifier | Run | Blk |
| --- | --- | --- | --- | --- | --- | --- |
| T-0-01 | DAT | The schema loads clean on PostgreSQL 16 Flexible Server with every migration M-01 to M-45 (the 47 tables of `db/schema.sql` renamed per D-15) | G | TL | PR | Y |
| T-0-02 | DAT | Seed prices round-trip exactly: 7.935 Tk is stored as 7,935 mtk and read back as 7.935 (23 price values on 20 of the 42 SKUs have three decimals; D-15) | G | TL | PR | Y |
| T-0-03 | DAT | The partition job creates the next 3 months; rows landing in the default partition raise the alert (D-23, D-131) | G | TL | N | Y |
| T-0-04 | DAT | `safe_div` and the CHECKs reject a target of -20 and a zero denominator; a percentage with a zero or null denominator is NULL and renders as a dash (D-28, D-50) | G | TL | PR | Y |
| T-0-41 | QUA | Contract build: Zod to OpenAPI to TypeScript and Dart clients compile; about 250 fixtures round-trip in both languages; enum-drift test; `oasdiff` wired (D-150) | G | TL | PR | Y |
| T-0-50 | SRE | Nightly job DAG: pre-generation refuses to run without `import_run.status = reconciled` on a wave night; a job killed mid-chunk and resumed gives identical output; a snapshot with `valid_for_business_date` other than today fails the job and alerts; coverage % is emitted (D-71; G-sre-08) | G | OPS | N | Y |
| T-0-51 | SRE | PgBouncer conformance: no named prepared statements, no LISTEN on port 6432, `SET LOCAL` only inside transactions; NestJS shutdown drains within 30 s (D-137). The persisted-response-before-cache ordering needs ingest and is asserted at T-1-22 and T-1-53 (ASSUMPTION: split of the SRE gate) | G | TL | PR | Y |
| T-0-60 | CFG | Registry completeness: every key introduced in phase 1 or earlier exists in `config_item` with default, bounds, risk class and scope levels (D-87, D-88) | G | TL | PR | Y |
| T-0-61 | CFG | Bounds are enforced in the database: inserting `cfg.geo.radius_m` of 10 or 5000 fails even around the API (bounds 20 to 2000, D-93) | G | TL | PR | Y |
| T-0-62 | CFG | Exclusion constraint: two open rows for one key and scope cannot coexist; close-and-insert in one transaction succeeds | G | TL | PR | Y |
| T-0-63 | CFG | Code and registry drift: CI grep of `cfg.` literals against the seed gives zero unregistered keys and no retired alias | G | TL | PR | Y |
| T-0-64 | CFG | Audit immutability: UPDATE and DELETE on the config audit table are denied to the API role; every write path produces exactly one audit row | G | TL | PR | Y |
| T-0-100 | ANA | `dw` landing RATCHET (D-558, G-qa-90): `/plan/dw-landing.yaml` (generated from the "Lands" columns of doc 16 s2.3, s8.2 and s8.9.1) is compared with the database at EVERY sub-milestone exit from 1c to 5a, and exactly the objects whose landing sub-milestone is at or before the current one must exist (65 objects by 5a; 0b has only `dim_date` and the operations tables) with `source` and `fidelity` columns where history can be imported; the README states the trusted-timestamp rule and the `kind` filters (D-26). The first draft required all 65 objects at the 0b exit, when doc 16 lands them from 1c to 5a, so the first phase exit could not be signed; the 0b-time check is the inventory gate T-0-156 | G | TL | PR | Y |
| T-0-144 | D20 | Flag hygiene: every flag is a `cfg.flag` key; release flags carry `remove_by`; the stale-flag report runs (flags at 100 % for more than 30 days are listed; D-99, D-459) | G | TL | N | N |

**0c Auth, scope, localisation**

| ID | Lens | Check | Artefact | Verifier | Run | Blk |
| --- | --- | --- | --- | --- | --- | --- |
| T-0-70 | SEC | SAST on `/api`, `/web`, `/packages`: no high or critical finding | G | TL | PR | Y |
| T-0-71 | SEC | Dependency scanning (Dependabot, `npm audit`, OSV on `pubspec.lock`): no critical; highs triaged within 7 days | G | TL | PR | Y |
| T-0-72 | SEC | Secret scanning (push protection and gitleaks on history): no finding | G | TL | PR | Y |
| T-0-73 | SEC | IaC scan (PSRule for Azure, checkov) on `/infra`: no high; Azure Policy assignments present in staging and prod | G | TL | PR | Y |
| T-0-74 | SEC | Auth negative suite: expired, garbage, `alg=none`, wrong `kid` and `aud` tokens; refresh rotation; reuse inside and outside the 60 s grace; lockout; uniform error timing within 20 ms (D-101, D-102) | G | SEC | PR | Y |
| T-0-75 | SEC | Scope-leak harness on every endpoint and a property test over random scope trees: user B never sees user A's rows, and with `scoped()` stubbed out RLS alone also returns 0 rows (D-106) | G | SEC | PR | Y |
| T-0-76 | SEC | `no-unscoped-query` lint clean; grants snapshot test: `api_rw` cannot UPDATE audit tables | G | SEC | PR | Y |
| T-0-120 | MAN | String catalogue seeded: all 225 app-owned manual entries (251 inventoried; the 26 Android-owned dialogs are excluded; SR, AMO, TSO and Web counts as in the inventories read from `/docs/evidence/manuals/`, D-530, G-qa-69) exist in `/packages/i18n` with bn and en under keys `<area>.<screen>.<element>`; the vendor-string deny-list (Apsis, "Firefly Outlets Reports", "Developed by Apsis Solutions", "Login page" slide titles, the example password) finds nothing (G-man-101, D-244) | G; coverage table | NB | PR | Y |
| T-0-121 | MAN | Formatting profile as tests: digits follow the UI language (`cfg.i18n.digit_script`), Western grouping, two decimals with the taka sign, ISO dates in lists, Bengali-digit mobile numbers normalised to ASCII; the printed-digit choice is unknown (MQ-59) and tested in both scripts (G-man-103, G-man-037) | G | TL | PR | Y |
| T-0-122 | MAN | Glossary and label formats: the 14 on-screen terms (OHS, OOS, SOQ, POSM, STD or STT, CPR, BSR, DSS, DS-RRS, GIGO, WMO, PDA, FF, Bikroy Joma) live in one glossary file; the label templates "name (code-phone-cluster)" and SR "name (sub-channel)" render from fixtures (G-man-104, G-man-037, D-162) | G; glossary | NB | PR | Y |
| T-0-141 | D20 | The Apsis dump-request letter is issued with the memo-level fields of docs/22 (SR, route, memo id and number, time, price, discount, paid and due, geo fix with mock flag, force-sale flag, photos with record ids), the password-hash algorithm (D-119) and per-route daily data (D-154), AND the DELTA CONTRACT of doc 16 s12.6 (dues per memo, loyalty ledger, outlet changes, target and assignment changes, same-date control totals, the nightly route-day feed for unswitched routes; CSV with a manifest; by 20:00 Dhaka at T-1; a named delta owner; the AKTCL-staff fallback; the breach rule) (D-514, G-qa-37); Apsis's acknowledgement is recorded (Q18) | letter; acknowledgement | SD | PH | Y |

Phase 0 exit signs T-0-49 and T-0-142 explicitly: without them parity has no oracle and no gate has a named verifier.

Proved by: all gates above.

### 3.4 Phase 1: The vertical slice

**1a Offline capture**

| ID | Lens | Check | Artefact | Verifier | Run | Blk |
| --- | --- | --- | --- | --- | --- | --- |
| T-1-20 | SYN | Outbox invariant under random captures and kill points (s2.4) | G | TL | PR | Y |
| T-1-24 | SYN | Kill between memo commit and the first printer byte, during the transaction and during `sale_draft` autosave: exactly one memo or none, draft resume offered, memo number not duplicated, outbox equals domain | kill-test report | QA | RC | Y |
| T-1-27 | SYN | Business date, DEVICE half (1a): for corrected timestamps around midnight Dhaka and offsets up to 48 h the device computes the right `business_date` from trusted time and a 23:55 sale keeps its date after 00:00. The SERVER half (agreement with the server, and a 23:55 sale synced at 00:10 re-aggregating only its own date) is T-1-157 at 1b and T-1-158 at 1c, because the server rollover and the re-aggregation land there (D-558, G-qa-90; also the analyst's T-1-27; D-20) | G | TL | PR | Y |
| T-1-35 | SYN | Golden memo print on an RPP02N and a clone printer; photographs diffed against the captured current memo (G-sync-02; D-76) | print photos | QA | RC | Y |
| T-1-141 | D20 | `PrinterPort` fake rasterises the 7 memo kinds and the day summary and compares with golden PNGs at 0.5 % tolerance in CI (G-qa-21); physical gates stay T-1-35 and T-2-41 | G; PNG diffs | TL | PR | Y |
| T-1-41 | QUA | Golden screens and memo parity of the slice against the baseline: home, outlet list, geo check, sale entry, review, print for SR in bn and en at 320 x 640 dp, side by side with baseline frames, and the slice's memo against its baseline memo (the full corpus is T-2-41); each difference marked accept or fix | `review.md` with frame pairs | SD | PH | Y |
| T-1-10 | FRD | Monotonic time, DEVICE half (1a): device clock set back 2 days after the last sync, 10 captures: the device keeps trusted time and stamps `time_untrusted` after a reboot; the SERVER half (rows land on the real business date with `clock_skew_flag` after the sync) is T-1-157 at 1b (D-558). The server side of the following also lands in 1b: with a reboot in between: `time_untrusted` and the DQ-40 month-close rule parks rows claimed in a closed month (D-20; G-fraud-02) | G | SEC | PR | Y |
| T-1-76 | SEC | Trusted time, DEVICE half (1a): device clock set -1 day and +1 day: the device stamps the correct `business_date` from its anchor; the SERVER half (records land on the correct `business_date`, `clock_skew_flag` set, the clock signal raised, F-SYS-049) is T-1-157 at 1b (D-558) | G | SEC | PR | Y |
| T-1-71 | SEC | Offline unlock on a 2 GB Android 8 device: Argon2id verifier in 1.0 s or less; works in airplane mode; 10 failures give a doubling cool-down; an expired window needs an online login; an online password change invalidates the old verifier (D-68) | G; device log | SEC | RC | Y |
| T-1-74 | SEC | APK config check in CI: `allowBackup=false`, `debuggable=false`, cleartext off, exported-component list, no secret strings, obfuscation on, per-ABI splits | G | SEC | PR | Y |
| T-1-90 | FLD | Mixed-script outlet list sorts identically on device and server; a Bengali-digit phone matches `phone_hash` (D-118) | G | TL | PR | Y |
| T-1-120 | MAN | Login and permission chrome: build version string from one source on login, drawer and Settings (D-208); launcher labels "ARON SR", "ARON AMO", "ARON TSO"; location denied blocks Sale and Attendance with a Bangla rationale and a Settings deep link; Bluetooth denied only disables printing; no RECORD_AUDIO (D-115); cached session re-opens offline (G-man-020; D-74) | patrol report | QA | RC | Y |
| T-1-121 | MAN | Authored message set: every class listed in the register (wrong credentials, session expired, no network, GPS fail, permission denied, printer not connected, save and upload failure, partial sync, required-field errors, empty lists, "needs internet", "N items not yet sent", "no SR on this route", map location off) has bn and en text and a trigger test; the five printed guard texts (AMO out-of-range, sync-before-submit advisory, unpaid-dues confirm, TSO already-submitted alert, web back-date banner) match the baseline pack exactly (G-man-102) | G; string diff | NB | PR | Y |
| T-1-122 | MAN | Commit semantics: the draft is written at "এগিয়ে যান" and survives a kill (resume at Review); Print shows "আপনি কি নিশ্চিত? বিক্রয় জমা হবে"; yes commits (sync_state pending) before and regardless of printing; the second dialog asks print yes or no and no leaves `printed_at` null; QC and Print are independent in any order; a zero sale prints (G-man-006; D-77, D-202, D-203) | patrol; goldens | QA | RC | Y |
| T-1-123 | MAN | Printer UX: the red slashed printer icon on Stock, Review, Memo and Summary means not connected (D-220); auto-reconnect; Print enabled only when connected and saved; "ছাপা ঠিক আছে?" after printing, and a reprint after "না" carries no duplicate marker (G-man-023; D-76; G-field-20) | lab report | QA | RC | Y |

**1b Sync and reconcile**

| ID | Lens | Check | Artefact | Verifier | Run | Blk |
| --- | --- | --- | --- | --- | --- | --- |
| T-1-04 | DAT | Device counts equal accepted counts for every record type | G | TL | PR | Y |
| T-1-21 | SYN | Idempotent convergence under duplication, reordering, truncation and replay (s2.4); a re-sent `client_uuid` with a mutated payload gives `conflict` and no state change; a regenerated uuid is caught by the content fingerprint (D-21); 10,000 runs on PR, 100,000 nightly | G; seeds | TL | PR | Y |
| T-1-22 | SYN | Batch replay byte-identical; 409 for a different row set; response persisted in the ingest transaction before any cache write (D-62) | G | TL | PR | Y |
| T-1-23 | SYN | ACK application is pure (s2.4) | G | TL | PR | Y |
| T-1-25 | SYN | Family atomicity: the batch builder never splits a visit and its children; overshoot is at most one family | G | TL | PR | Y |
| T-1-26 | SYN | Backoff: bounded, jittered, handed to WorkManager after 5 failures, no retry on 4xx, `Retry-After` honoured | G | TL | PR | Y |
| T-1-28 | SYN | Kill mid-batch send: rows back to pending within 120 s; the resend reuses `batch_uuid`; the server holds one copy | kill-test report | QA | RC | Y |
| T-1-29 | SYN | Kill mid-ACK application: all rows of the batch flip or none; the resend answers `replayed: true` | kill-test report | QA | RC | Y |
| T-1-30 | SYN | Full scripted day (s2.7) in airplane mode from 08:00, network at 17:05; variants: permission revoked at 12:00, reboot at 13:00, MIUI force-stop at 14:00: all rows synced within 3 minutes of connectivity (trigger T4 fires); counts match; K-01 shows the offline start; Sales Submit accepted; nothing lost or doubled | lab report; video | QA | RC | Y |
| T-1-31 | SYN | Flapping network (30 s on, 90 s off for 2 hours): no duplicate rows, at most 1 POST per 5 s, wake-lock total within budget | lab report | QA | RC | Y |
| T-1-32 | SYN | Captive Wi-Fi counts as offline (validation HEAD fails); no error-toast storm; trigger T4 re-arms (D-59) | lab report | QA | RC | Y |
| T-1-34 | SYN | The reconciliation screen's per-type device counts equal `server_totals` (accepted plus rejected plus conflict) after T-1-30; one injected `unknown_sku` row shows 1 rejected and Sales Submit stays allowed | lab report | QA | RC | Y |
| T-1-51 | SCA | Idempotency fuzz on staging with k6: 10,000 random duplicate, reordered, partial batches converge to one server state; replay body identical | k6 report | TL | PR | Y |
| T-1-52 | SCA | Business-date rollover: sales at 23:59 and 00:01 Dhaka land correctly; a late batch re-aggregates yesterday | G | TL | PR | Y |
| T-1-53 | SRE | Poison-row isolation: a throwing record gives 200 with `rejected(server_error)` plus a quarantine row; a 10,000-row fuzz with 1 % throwing rows accepts all others; a stuck family uploads the rest of the day within 2 cycles and is surfaced; a failing batch is bisected and a transaction never holds more than 60 savepoints (D-65, D-521; G-sre-03) | G | TL | PR | Y |
| T-1-54 | SRE | Submit settle: `day_submit` one batch ahead of 100 rows gives state `submit_pending_rows` and no mismatch flag; after the rows, `sales_submitted`; rows that never arrive are flagged after 30 minutes; K-02 correct throughout (D-64; G-sre-02) | G | TL | PR | Y |
| T-1-55 | SRE | Token expiry wave and clock skew: 1,000 simulated devices issued tokens in one second refresh spread over 8 minutes or more; a device +3 h has no refresh loop; the grace accepts a 30 s-expired token on `/sync/batch` (D-101; G-sre-06) | G | TL | PR | Y |
| T-1-56 | SRE | Server-generation re-sync: restore staging to T-1 h and change the generation; the generation carries `restore_point_utc`; 100 simulated devices resend every synced row acked at or after the restore point minus 6 h REGARDLESS OF THEIR OWN CLOCK; server state equals pre-restore for those rows, zero duplicates, reconciliation equal. Added cases (D-517, G-qa-41): devices OFFLINE for 72 h across the generation flip, devices that acked within the last minute before the restore point, and a PITR distance above 24 h; after every device reconnects ZERO missing UUIDs, including in the `lost interval` dashboards; a deliberately dropped uuid is found by the digest of T-1-152 (D-63; F-SYS-047, F-SYS-080; G-sre-01) | G; restore log | OPS | PR | Y |
| T-1-70 | SEC | Device binding: OTP TTL and attempts; bind without a password-authenticated token refused; `X-Device-Proof` from another key refused; a refresh from a second device with a copied token refused and the family revoked (D-103, D-104) | G | SEC | PR | Y |
| T-1-72 | SEC | Batch forgery: another user's `user_id` in the payload ignored; an outlet outside reach quarantined; `business_date` 8 days back quarantined; another user's `client_uuid` gives a conflict and a security event; a gzip bomb at 100:1 gives 413 within 50 ms (D-116) | G | SEC | PR | Y |
| T-1-73 | SEC | Rate-limit conformance per device key with k6; 500 devices behind one IP never trip the WAF; 429 only per device | k6 report | SEC | PR | Y |
| T-1-75 | SEC | Log redaction: phone, NID, password and token injected into every request path; none appears in App Insights or pino output | G | SEC | PR | Y |
| T-1-13 | FRD | Lockout without denial of service: 10 wrong passwords across 500 usernames from 5 IPs; the bound device of each user then logs in with the right password and `X-Device-Proof`; an unbound device is locked 15 minutes; the fleet alert fires (D-102; G-fraud-12) | G | SEC | PR | Y |
| T-0-05 | DAT | Registry and partition shape (moved from 0b to 1b with the ingest registry load, D-558, G-qa-95): 64 hash partitions on the ingest registry, monthly partitions three months ahead, no foreign key between partitioned event tables, `app.check_orphans()` empty, 2,600 registry inserts per second on the reference server without p95 drift; `archive_candidates` honours each retention class (D-23) | G | TL | PR | Y |
| T-0-09 | DAT | Config data contract (moved from 0b to 1b with the DQ rules, D-558, G-qa-95): every key read by ingest or `dw` has a registry row with bounds; the effective-dated lookup returns the value in force at a timestamp; `cfg.dq_rule` holds all 69 rules | G | TL | PR | Y |

**1c Aggregate, tile, config**

| ID | Lens | Check | Artefact | Verifier | Run | Blk |
| --- | --- | --- | --- | --- | --- | --- |
| T-1-01 | DAT | A duplicate batch gives identical `agg_*` rows | G | TL | PR | Y |
| T-1-02 | DAT | Reordered children are parked, then accepted | G | TL | PR | Y |
| T-1-03 | DAT | A late batch for D-2 changes only D-2 rows and nothing on D | G | TL | PR | Y |
| T-1-33 | SYN | APK at most 30 MB per ABI (target 22), installed at most 70 MB, cold start at most 2.5 s on the primary device (D-73; F-SYS-036) | G; size report | TL | PR | Y |
| T-1-40 | QUA | Airplane-mode vertical slice on the primary device (patrol): online login, airplane on, check-in, outlet, geo check, one sale, print, airplane off, sync within 60 s, reconciliation equal; Playwright asserts the staging tile shows the sale within 2 minutes | video; patrol and Playwright reports | SD | PH | Y |
| T-1-42 | QUA | Coverage floors and mutation (P7): api 80 % and 95 % on the five critical modules, Stryker 70 % on them, app `domain/` 85 % | coverage and Stryker reports | TL | PR | Y |
| T-1-43 | QUA | Sync-health tile v1 on staging: for the fuzz day's 12 routes shows target, logged-in and uploaded counts equal to the seed, "as of" present, and both K-02 ("Submit % (of logged-in)") and K-03 ("Day-completion %") returned with their basis (D-45; G-data-05) | screenshot; query | OPS | PH | Y |
| T-1-44 | QUA | Zero-chatter check (s2.7): no request other than the explicit refresh; 8 hours background with 50 pending rows and no network gives at most 32 WorkManager runs | proxy log; Battery Historian | QA | RC | Y |
| T-1-45 | QUA | Battery and data protocol run 1 (s2.7) on the primary device, with the Apsis app on the same script for comparison; every budget value recorded; any miss has a ticket | `/docs/perf/battery-<v>.md`; bugreport | QA | RC | Y |
| T-1-46 | QUA | First expand/contract rehearsal: a migration adding a column and an index CONCURRENTLY applied to staging during a k6 S2 smoke gives zero 5xx and lock waits under 50 ms p95; the previous release's api tests pass against the new schema | k6 report; compat job | TL | PH | Y |
| T-1-47 | QUA | Phase 1 demo run by a non-engineer from the script: login, airplane, sale, print, sync, tile, change the radius to 150 m and see the next visit use it | signed `EXIT.md` | SD | PH | Y |
| T-1-60 | CFG | The bundle carries `config.version`, the resolved radius with provenance and the `scheduled` list | G | TL | PR | Y |
| T-1-61 | CFG | A radius set to 150 m reaches the device within one trickle sync (a selling phone; an idle one at its next foreground, doc 19 s4.1b): the next visit stores `radius_m_used` 150 and `config_version`; a visit captured before the change keeps 100 | lab report | QA | RC | Y |
| T-1-62 | CFG | Resolver performance: all keys of a 12-pair chain under 2 ms p95, one key under 0.3 ms, L0 hit rate above 99 % in the storm test | G | TL | N | Y |
| T-1-63 | CFG | As-of re-check: a visit back-dated to before a radius change is re-checked with the old radius (D-87); D-431 as amended by D-519: a visit stamped with a 30-day-old version whose value changed 2 hours earlier is accepted under the old value and one whose value changed 5 days earlier is judged under the new value, for a radius TIGHTENED and a radius LOOSENED, with two changes after the stamped version starting the clock at the first (G-qa-43) | G | TL | PR | Y |
| T-1-64 | CFG | 100 % of API responses carry `X-Config-Version`; `GET /config/public` works unauthenticated and is cached | G | TL | PR | Y |
| T-1-100 | ANA | Memo fact round trip: a printed memo appears in `fact_memo` with `printed_at`, `time_to_print_s`, `outstanding_before_mtk`; an edit gives a second row with `supersedes_memo_id` | G | TL | PR | Y |
| T-1-101 | ANA | Restatement log: a D-2 batch changes `agg_daily_route_sku` for D-2 and writes exactly the changed rows to `agg_restatement_log` with `reason = late_batch` | G | TL | PR | Y |
| T-1-102 | ANA | Hour basis: a sale at 23:55 Dhaka on a phone 40 minutes fast lands on D with `hour_of_day` 23 and no `business_date_mismatch`; on a phone 40 minutes slow it also lands on D | G | TL | PR | Y |

Phase 1 exit also requires the load-model rebase (D-125) in the Locust simulator (doc 18 s8) and D-245 (whether every sale in the Apsis export comes through the apps) answered or carried as OI-20-15.

Proved by: all gates above.

### 3.5 Phase 2: The full SR day

**2a Sale complete** (entry: the promotion catalogue of Q13 and the physical memo samples of Q57; without them 2a cannot exit, D-33)

| ID | Lens | Check | Artefact | Verifier | Run | Blk |
| --- | --- | --- | --- | --- | --- | --- |
| T-2-20 | SYN | Memo numbers: for random (user, date, bind-ordinal slot up to 3, up to 1,500 memos including overflow, random rollbacks) no collision and the format `<username>-<yyMMdd>-<seq>` holds with three digits for ordinals 0 and 1 and four digits from ordinal 2 and on overflow (D-393); the server rejects a foreign prefix, a wrong date part and a `seq` outside the slot's range; a held slot is never reissued on the same date (D-35) | G | TL | PR | Y |
| T-2-36 | D20 | Offer-engine differential: for every promotion group of the Q13 catalogue the device (Dart) engine and the server recompute (TypeScript) agree on 3 golden baskets or more per group and on 10,000 random baskets; validity windows follow the business date (D-33, D-143) | G | TL | PR | Y |
| T-2-37 | D20 | Memo arithmetic property: net = gross - offer_discount - drp_discount - qc_deduction; totals are summed from unrounded lines and rounded once; `round_adj_mtk` reproduces the printed total; Dart equals TypeScript on 100,000 random memos including the zero-sale-plus-QC negative-net case (D-18, D-19) | G | TL | PR | Y |
| T-2-38 | D20 | Quantity model: `qty_entered`, `unit_entered`, `pack_factor`, `qty_base` round-trip for cigarette, bidi, lighter and match; no cross-category STD sum; the Match price per dozen is held as a rational (28.00 per dozen, never 2.33 per piece); report-unit conversion (Lighter in pieces on the web, boxes on the TSO app) (D-16, D-17, D-49) | G | TL | PR | Y |
| T-2-39 | D20 | Stock and price lists: stock memo Issue, Stock and Return (issued minus sold) per SKU and per category; the outlet's price type picks the outlet or cc list; a wholesale line of 10,000 sticks or more raises the anomaly flag and is accepted, never rejected (D-32, D-42, D-260) | G | TL | PR | Y |
| T-2-41 | QUA | Printed-memo parity: the 25 or more baseline memos re-entered in the new app and printed on the lab printer; totals equal to the paisa; layout photo against photo approved line by line; stock memo and day summary included (D-158, D-146) | side-by-side photo sheet; `parity.md` | SD with one SR and RM | PH | Y |
| T-2-42 | QUA | Regression corpus in CI: every baseline memo is a fixture (lines in, totals out) for both arithmetic engines, and both pass; every promotion group has 3 fixtures or more | G | TL | PR | Y |
| T-2-71 | SEC | Price recompute: a tampered `unit_price` or discount is accepted with `price_mismatch` (a printed memo is never rejected); inconsistent totals (net not equal to gross minus discount) are rejected (D-117) | G | SEC | PR | Y |
| T-2-90 | FLD | A wholesale-outlet memo takes prices from the cc list and passes DQ-13 (D-32) | G | TL | PR | Y |
| T-2-91 | FLD | The zero-sale dialog records one outcome code; the Non-visit and No-sale strip matches the seed (D-38, D-56) | G | TL | PR | Y |
| T-2-103 | ANA | Price and offer provenance: a price change at 11:00 gives lines before and after the right `list_price_mtk`; a memo on a stale bundle is `bundle_stale = true` with `price_mismatch` where applicable; `fact_memo_offer` sums equal memo discount minus line discounts | G | TL | PR | Y |
| T-2-120 | MAN | Memo money fixtures GF-01, GF-02, GF-05 to GF-07 of s4.3 (AMO 116.42 and 289.50; SR 360.50, 280.50, 161.50; slide 80.00; QC 80.00; max-QC cap display; home card discount 437.50 beside DRP discount 0.00) on both engines; the credit label shows 61.50, never 61 (D-213) (G-man-002, G-man-004, G-man-009) | G | TL | PR | Y |
| T-2-121 | MAN | Units parity: stock badge 6,500 gives 650 and 6,000 gives 300 as a read-only derived value; cigarette price per stick; every Match cell shows a unit label; the sale card shows three read-only unlabelled slots (G-man-001, G-man-005, G-man-007; D-17) | G; goldens | SD | RC | Y |
| T-2-122 | MAN | QC flow: six app fault types in two groups through picker, entry, summary and confirm; max-QC cap with done and remaining; expired-stock threshold 4 months (`cfg.qc.expired_stock_months`); settlement 10 sticks x 8.00 = 80.00; the 11-code table maps the web 5+5 and app 3+3 labels (G-man-003; D-34) | patrol; goldens | SD | RC | Y |
| T-2-123 | MAN | Call start: after the geo gate the prompt "আপনি কি কল শুরু করতে চান?" appears; "না" returns to the list and the outlet is not counted visited; AV, KV, survey and sale appear in that order and only after yes; the visit row exists from outlet open (G-man-008, G-man-043; D-78) | patrol | QA | RC | Y |
| T-2-124 | MAN | SKU Target and Achievement: TADS = target / 14 (500 gives 36, 900 gives 64), RADS = remaining / 11 (500 gives 45, 900 gives 82), card uncapped, ADS = achieved / elapsed selling days, PADS shown as a dash until defined; home strip definitions of D-56; the negative-target regression renders a dash (G-man-053, G-man-054; D-58) | G | SD | PR | Y |

**2b Corrections**

| ID | Lens | Check | Artefact | Verifier | Run | Blk |
| --- | --- | --- | --- | --- | --- | --- |
| T-2-01 | DAT | An edit does not double the memo count (K-06): the superseding memo counts once | G | TL | PR | Y |
| T-2-02 | DAT | An overpayment is flagged, not lost | G | TL | PR | Y |
| T-2-21 | SYN | Edits, dues and stock as events never double a memo count, due balance or stock balance (s2.4) | G | TL | PR | Y |
| T-2-70 | SEC | Edit rules on the server: edit after QC, after Sales Submit, outside the geofence, or at chain depth 4 is rejected with the right reason; a valid edit supersedes correctly (D-86, D-201) | G | SEC | PR | Y |
| T-2-92 | FLD | Void after print: stock back, due reversed, cancel slip printed, memo count unchanged; the void-after-collection signal fires (D-86) | G; print photo | QA | RC | Y |
| T-2-93 | FLD | The due receipt prints and the allocation matches the ledger (D-37) | G | QA | RC | Y |
| T-2-94 | FLD | A cash hand-over variance appears on the settlement view | G | TL | PR | Y |
| T-2-101 | ANA | Dues ageing: fuzzed collections with and without `against_memo_id` give `agg_memo_due_open` buckets equal to a reference FIFO model; opening balances are aged from `age_basis_date`; "as of D" is reproducible (K-18) | G | TL | PR | Y |
| T-2-102 | ANA | Every DQ rule fired in the ingest fuzz has a `fact_dq_flag` row; counts per flag equal `sync_rejected` plus flagged rows | G | TL | PR | Y |
| T-2-143 | D20 | Ledger reconciliation on a fuzzed day of 10,000 batches: sum of memo due minus sum of collections equals `outlet_due_balance` and the loyalty ledger equals the balance, zero difference; a planted difference blocks further credit or redemption for that outlet and lists it (G-qa-14) | reconcile report | FIN | N | Y |
| T-2-125 | MAN | Corrections parity: mark-as-paid settles the whole memo with no amount field and writes a `due_collection`; the credit dialog requires an amount below the grand total; edit is blocked at outlet level after any QC and offers three reasons (the first "ভুল SKU নির্বাচিত।"); Sale History footer equals the sum of its rows (the manual's 20,260 versus 20,660 is not copied), local window plus online fallback banner (G-man-009, G-man-010, G-man-012, G-man-013; D-37, D-201, D-206, D-211) | patrol | QA | RC | Y |

**2c Outlets and media**

| ID | Lens | Check | Artefact | Verifier | Run | Blk |
| --- | --- | --- | --- | --- | --- | --- |
| T-2-22 | SYN | Kill mid-compression and mid-upload: no orphan file, queue state consistent, the blob is absent or complete | kill-test report | QA | RC | Y |
| T-2-26 | SYN | Wi-Fi-only photos: 13 photos on mobile all day and Wi-Fi at 19:00 give zero photo bytes on mobile until the evidence fallback (6 h) for the 5 force-sale photos; the rest upload on Wi-Fi (D-75) | lab report | QA | RC | Y |
| T-2-30 | SYN | Photo pipeline CPU at most 1.5 s per photo on the primary device; EXIF stripped; 150 KB at 1024 px or less | G; lab report | QA | RC | Y |
| T-2-53 | SCA | SAS misuse: wrong path, expired or read attempts are rejected; a photo failure never blocks sale sync | G | SEC | PR | Y |
| T-2-72 | SEC | Photo pipeline: EXIF stripped, sha256 mismatch invalid, the same photo across outlets raises the duplicate signal, SAS write-only for 15 minutes and path-pinned (a PUT elsewhere returns 403) (D-75) | G | SEC | PR | Y |
| T-2-13 | FRD | Injection: outlet names from the OWASP CSV-injection corpus, `\x1B@` and U+202E give text cells in xlsx, unchanged printer output and a badge on the approval panel (D-118) | G | SEC | PR | Y |
| T-2-126 | MAN | Outlet forms: new, close and info pick a Cluster and the route is derived on the server; picker label by role; GEO and photo required for an info change; a request shows pending; a Force Sale photo does not move the stored outlet (a location-change request is raised instead) (G-man-016, G-man-019, G-man-033; D-43, D-162, D-163) | patrol | QA | RC | Y |

**2d Geo and rails**

| ID | Lens | Check | Artefact | Verifier | Run | Blk |
| --- | --- | --- | --- | --- | --- | --- |
| T-2-03 | DAT | A mocked fix is never `geo_validated` (D-48 invariant) | G | TL | PR | Y |
| T-2-10 | FRD | Adversary lab on the primary device against staging with real tools: a Play-Store fake-GPS app, a root hook with a joystick walk, a replayed fix through SQLite, a curl-forged batch, content replay with new uuids, clock back-dating, force sale from home, an outlet location moved to the SR's house: each rejected or on the minimal Exceptions list of 2d (the web view over F-API-034; the AMO app list is T-3-156) within aggregation lag; the joystick walk raises `radio_mismatch` beyond 5 km from the serving cell | report with screenshots | SD | PH | P |
| T-2-11 | FRD | SDR spoof where a HackRF is available, otherwise realistic injected fixes: `gps_time_anomaly` or `radio_mismatch` fires; 3 co-located devices raise `co_located_users` | report | SEC | PH | N |
| T-2-12 | FRD | Fix freshness: no `getLastKnownLocation` path (code review and runtime assert); a fix older than 65 s used for a verdict gives `stale_fix` (D-74) | G | SEC | PR | Y |
| T-2-14 | FRD | A blocked build N at 12:00: rows captured at 11:00 upload, rows captured at 13:00 on N go to `blocked_version_capture` quarantine (D-130) | G | SEC | PR | Y |
| T-2-47 | QUA | Anti-spoof end to end with real spoofing tools on the primary device: `mock_location = true`, never geo-valid, visible on the minimal Exceptions list (the web view F-WEB-057 minimal over the endpoint F-API-034, both in 2d: the AMO app list F-AMO-038 first works in 3a and is re-checked there by T-3-156) within aggregation lag; a teleport (two outlets 10 km apart in 2 minutes) and a single-point route are flagged | report with screenshots | SD | PH | Y |
| T-2-75 | SEC | Anti-spoofing server side: a mocked fix is never `geo_validated_server`; integrity signals stored; `cfg.geo.mock_policy` transitions (silent_flag, warn_rep, block_sale) behave (D-96) | G | SEC | PR | Y |
| T-2-60 | CFG | Two-person approval: the requester cannot approve a C3 request (database constraint and UI); a second approver can; the audit shows both | G | TL | PR | Y |
| T-2-61 | CFG | Reach SLO measured on ALL active devices of the scope (online or not, with the tail metric and the unreached count by reason), not only on the survivors: after a global C1 change at least 95 % of the SELLING cohort (a batch or bundle request in the last 20 minutes) hold the version within 15 minutes of the commit, idle devices at their next foreground through the resume check, and the share of ALL active devices reached at 24 h is reported with the unreached by reason; an urgent revert reaches 95 % of the selling cohort within 15 minutes with push OFF and 95 % of push-enabled devices within 5 minutes with push ON (doc 19 s4.1b; D-526, D-563, G-qa-51, G-qa-96); 100 % of devices that pull a bundle the next morning are on the new version | lab report | SD | PH | Y |
| T-2-62 | CFG | Offline scheduled apply: a device in airplane mode holding a scheduled check-out change enables check-out at the new time with no network; a skewed clock falls back to the restrictive value and flags the day | lab report | QA | RC | Y |
| T-2-63 | CFG | Kill-switch semantics: `block_login` at zone scope refuses a new-day login with a banner while pending rows still upload and local data stays intact; the switch auto-expires (D-130) | G; lab | QA | RC | Y |
| T-2-64 | CFG | Revert: one click produces a new version, pushes it and the device resolves the previous value; nothing is deleted; both versions are in history | G | SD | PH | Y |
| T-2-65 | CFG | Blast-radius preview counts equal the devices targeted at commit (difference 0) for global, wing, territory and zone scopes, and `outlets_changed` and `outlets_shadowed_by_geo_class` equal a brute-force resolve before and after, with a territory edit under geo_class rows showing its shadowed outlets and an edit that changes none refused (D-512, G-qa-35) | G | TL | PR | Y |
| T-2-66 | CFG | Anomaly watch: a synthetic 15-point drop of geo-valid % in the changed scope raises the alert naming the version within 15 minutes; a synthetic rise after a loosening also raises it (D-94) | G | OPS | PR | Y |
| T-2-67 | CFG | Outlet override precedence: an outlet at 25 m inside a zone at 150 m inside a territory at 100 m resolves 25, 150 and 100 with correct provenance (D-87, D-93) | G | TL | PR | Y |
| T-2-68 | CFG | Canary enforcement: a global C3 radius request without a prior sub-scope canary is refused unless break-glass; break-glass creates a review item and an alert and may only restore or restrict (D-88); the emergency-widen lane (doc 19 s7.6b) widens one territory to at most 250 m inside a freeze window, expires after 6 h and needs one approver afterwards, a 300 m request in the lane is refused, and temporary relief needs two approvers before it applies (D-527, G-qa-52, G-qa-77) | G | SEC | PR | Y |
| T-2-69 | CFG | Content versioning: editing a reason code bumps `config_version`; the device shows the new label at its next request; historical memos keep the old code | G | TL | PR | Y |
| T-2-55 | SRE | Push jitter: an FCM data message to 1,000 simulated devices spreads `GET /config/delta` over 100 s or more for an ordinary key and 20 s or less for a kill switch; no 429 at minimum replicas (D-09; G-sre-07) | G | OPS | PH | Y |
| T-2-95 | FLD | Radius resolves by geo class with correct provenance (D-87) | G | TL | PR | Y |
| T-2-96 | FLD | The first outlet after 60 minutes idle gets a fix within 10 s on the primary device because acquisition starts on entering the outlet list (D-74) | lab report | QA | RC | Y |
| T-2-127 | MAN | Photo and location rules: the sentence "location information will be updated" maps to a location-change request; a provisional device location is flagged; an immediate update only for a missing or placeholder location (`location_confirmed` false) (G-man-017; D-95, D-163) | patrol | SD | RC | Y |
| T-1-11 | FRD | Record signature (placed at 2d with F-SYS-072; the first draft had it at 1b, before the feature exists, D-558): editing `lat` or `lng` in the local SQLite (debug hook) and syncing gives `sig_invalid` quarantine; an honest batch verifies; signing costs 5 ms or less per record on the primary device (D-104) | G | SEC | RC | Y |
| T-1-12 | FRD | Attestation at bind (placed at 2d with F-SYS-031, D-558): a genuine release build gives `trust_level` normal; a repacked debug-signed build and an emulator give low; all three still sync (D-105) | G | SEC | RC | Y |

**2e Day close and pilot hardening**

| ID | Lens | Check | Artefact | Verifier | Run | Blk |
| --- | --- | --- | --- | --- | --- | --- |
| T-2-04 | DAT | The reconcile job finds zero difference after a fuzz of 10,000 batches | G | TL | N | Y |
| T-2-23 | SYN | Kill during Drift migration with 200 pending rows: the migration completes or rolls back; pending rows are intact and uploadable; N-1 can open the database for one release (D-79) | kill-test report | QA | RC | Y |
| T-2-24 | SYN | Kill during delta apply: `ref_*` is old or new, never mixed; `ref_meta` consistent | kill-test report | QA | RC | Y |
| T-2-25 | SYN | 2G throttle with a 200-row catch-up completes (halving or timeout and resume) in 10 minutes or less | lab report | QA | RC | Y |
| T-2-27 | SYN | Stale bundle: fetched on D, airplane mode, device date D+1: sells with a banner and `day_open{online:false}`; D+2 is read-only; D+1 rows are flagged `stale_price` when a price changed (D-70) | lab report | QA | RC | Y |
| T-2-28 | SYN | Config change mid-day (radius 100 to 60 m): the next outlet open uses 60 m; `radius_m_used` 60 and `config_version` stored; `config_ack` received; a visit captured before keeps 100 | lab report | QA | RC | Y |
| T-2-29 | SYN | Clock skew of -3 h and +40 min: corrected business date and 17:00 gate, rows flagged, banner shown, no rejection | lab report | QA | RC | Y |
| T-2-31 | SYN | Nightly device-versus-server reconciliation gives zero unexplained differences over a 10,000-batch fuzz day | reconcile report | OPS | N | Y |
| T-2-32 | SYN | Printer off mid-print: the sale is intact, reprint works, `print_count` correct | lab report | QA | RC | Y |
| T-2-33 | SYN | Fixture payloads of the two previous releases are accepted by the new server (D-150) | G | TL | PR | Y |
| T-2-34 | SYN | TLS chain on the legacy device class and the clock-skew message (also T-2-54: one run, two ids; Android 7.0 only if the census requires it, OI-20-13) | lab report | QA | RC | Y |
| T-2-40 | QUA | Full scripted field day (s2.7) as a patrol run on the three reference devices under the connectivity pattern: every row synced within 3 minutes of the final connectivity; reconciliation equal on all three | 3 reports and videos | QA | RC | Y |
| T-2-43 | QUA | Localisation complete for SR: `no-hardcoded-string` lint clean; every key has bn and en; goldens for all SR screens in bn reviewed against the baseline glossary; Bengali conjuncts render on the legacy device; semantics check at font scale 1.3 | glossary diff; golden set | NB | PH | Y |
| T-2-44 | QUA | Upgrade matrix on every RC: N-2 to N and N-1 to N on emulator with 200 pending rows and a draft sale: data intact, sync succeeds; a build older than N-1 refuses a newer database with a clear message and N-1 opens it for one release (D-79) | G | TL | RC | Y |
| T-2-45 | QUA | Dogfood week: 10 engineering and ops staff run the scripted day for 5 trading days on their own low-end phones against staging; crash-free sessions 99.5 % or more; zero data-loss tickets; every rejected row explained | week report | OPS | PH | Y |
| T-2-46 | QUA | Pilot-hardening drills: PDA to Support from a lab phone replays into staging and reproduces its counts; a `min_version` bump blocks a new-day login while 20 pending rows still upload; a kill switch auto-expires; the updater downloads on Wi-Fi only | drill log | OPS | PH | Y |
| T-2-48 | QUA | Pilot dress rehearsal: 2 pilot SRs and 3 staff run a real beat with both apps; evening comparison of memo count, STD, totals and dues against the Apsis app's own summary screen; every difference categorised A to E (s7.3); zero category C | rehearsal report | SD | PH | Y |
| T-2-49 | QUA | Bangla string review: 100 % of SR strings reviewed against the baseline glossary; terms match the current app (বিক্রয় জমা, বাকি, বাকি পরিশোধ, নিরীক্ষণ) unless a change is explicitly approved | signed glossary | NB | PH | Y |
| T-2-51 | SCA | Hot-row test: 500 memos per second into one zone give no deadlocks and aggregates equal raw sums | k6 report | TL | PH | Y |
| T-2-52 | SCA | Client backoff conformance under simulated 503 and 429 storms: attempts at most 5, jitter present, no retry on 4xx | G | TL | PR | Y |
| T-2-54 | SCA | Same run as T-2-34 | lab report | QA | RC | Y |
| T-2-56 | SRE | Version-scoped upload hold: build X looping at 1 request per second per device (500 devices, one NAT IP) is stopped at the edge within 60 s; other versions unaffected; the hold auto-expires (D-130; G-sre-11) | G | OPS | PH | Y |
| T-2-57 | SRE | Hostname and certificate failover: a broken certificate on the custom domain makes devices switch to the Front Door default host within 3 failures; a WAF HTML 403 in Prevention is treated as transient; the certificate-expiry probe fires at 14 days (D-81; G-sre-12) | G; drill log | OPS | PH | Y |
| T-2-58 | SRE | Support visibility: a failing-sync device is found by an L1 persona in 2 minutes; the device page shows last request, status, `X-Last-Sync-Error` and pending claim; "Send diagnostics" lands while `/sync/batch` returns 500 (D-138; G-sre-18) | G | SUP | PH | Y |
| T-2-73 | SEC | gstack `/cso` audit of api, web, app and infra before the pilot: no high; mediums have owners and dates | audit report | SEC | PH | P |
| T-2-74 | SEC | DAST: OWASP ZAP baseline and authenticated scan against staging: no high | ZAP report | SEC | RC | Y |
| T-2-97 | FLD | A rain-day exception removes the route from K-01, K-02 and Daily Tracking denominators and buckets it "exception" (D-39) | G | TL | PR | Y |
| T-2-98 | FLD | Shared-phone wrong-user drill: the identity confirmation appears, `acting_for_user_id` is stored, KPIs attribute to the acting user (D-66, D-85) | lab report | QA | RC | Y |
| T-2-99 | FLD | Parallel mode (`cfg.flag.parallel_run_mode`): the test print carries "পরীক্ষামূলক - এটি রসিদ নয়" and no previous-due line; parallel `due_collection` rows are excluded from balances (D-152; G-field-12) | G; print photo | QA | RC | Y |
| T-2-100 | ANA | User-day aggregate: a regular SR and a substitute on one route on one day give two `agg_daily_user` rows whose successful calls sum to the route's; an AMO control-call sale lands in `amo_successful_calls` only (D-26) | G | TL | PR | Y |
| T-2-128 | MAN | Attendance UX: address with Refresh online and coordinates offline; press-and-hold confirm (`cfg.app.hold_to_confirm_ms`); four states; check-out enabled from 17:00 Dhaka inclusive on corrected time; other tiles not gated by check-in by default (G-man-029; D-209) | patrol | QA | RC | Y |
| T-2-129 | MAN | Sales Deposit: rows config-driven per role and version (SR five, AMO 8 or 9); the Server column is the last `server_totals`, blank with a timestamp before the first sync; Sync enables Sales Submit; the unpaid-dues dialog shows a dynamic count and only warns; `submitted_with_dues` recorded (G-man-030, G-man-031; D-173, D-222) | patrol; goldens | QA | RC | Y |
| T-2-130 | MAN | Updater and logout: unknown-sources guidance with the `canRequestPackageInstalls` check, percent download, SHA-256, n/m migration progress that preserves pending rows, no install while a batch syncs; SR and AMO logout keeps the database; PDA to Support queues offline (G-man-022, G-man-024, G-man-025; D-69, D-79) | lab report | QA | RC | Y |
| T-2-140 | D20 | OEM background-restriction matrix: on the three lab devices plus one Realme and one Vivo class phone, with the guidance screen completed and not completed, record whether trigger T4 (one-off with network) fires after connectivity returns with the app backgrounded and after a user force-stop; PASS CRITERIA (D-511, G-qa-48, G-qa-33): the reach-time bounds of doc 17 s4.1b per OEM class and condition (A foreground 60 s, B backgrounded P95 3 minutes, C battery saver 15 minutes, D force-stopped: the next foreground launch plus 5 seconds with the first launch draining the outbox, E reboot, F restricted bucket), measured on the CENSUS top-10 models (not only the five lab phones; re-run when the census changes the list); the gate FAILS when a cell exceeds its bound and no mitigation (guidance screen, native Worker) brings it back inside, a model that fails is blocked from the wave or flagged `oem_blocked` with guidance, and the server-side held-rows list (doc 17 s4.1b, SH-24) must show a device that holds rows beyond 4 h; the result also sets the content of `cfg.app.oem_guidance` (R5 proof outside the lab happy path; G-20-04) | matrix sheet | QA | PH | Y |
| T-2-141 | D20 | Coexistence on one pilot phone: both apps installed with distinct package ids and separate storage; the printer stays paired and prints alternately from both without re-pairing (the RFCOMM socket is released after `cfg.print.disconnect_idle_s`); the Apsis app is not touched by the install, update or removal of ours (F-SYS-044; G-sec-23) | lab report | QA | PH | P |

**Phase 2 exit ("pilot-ready")**: an SR runs a real beat end to end offline on a reference phone (T-2-40 videos); printed memos match the baseline to the paisa (T-2-41); the dress rehearsal has zero category C (T-2-48); battery and data are within budget on all three devices (`/docs/perf/`); `/cso` has no open high; the sponsor's delegate changed the radius from the console and saw the phone pick it up (T-1-61, T-2-61).

Proved by: all gates above.

### 3.6 Phase 3: Supervisors

**3a AMO**

| ID | Lens | Check | Artefact | Verifier | Run | Blk |
| --- | --- | --- | --- | --- | --- | --- |
| T-3-41 | QUA | SR to AMO to web approval chain end to end: an SR new-shop request on a phone (offline), the AMO verifies on a second phone, the web approver approves in the minimal Outlet Approval Panel of 3a (F-WEB-032 minimal, approve and reject; badges and bulk limits are 4c, D-513) (Playwright), the SR's bundle delta shows the outlet active; negative: an AMO of another zone cannot see it | report | SD | PH | Y |
| T-3-11 | FRD | Onsite verification: an AMO verifying a new-outlet request from 3 km away is accepted but flagged `remote_verification` and routed to the TSO; from 40 m it is clean; a 450 m move of a confirmed outlet needs the TSO, of a placeholder-pin outlet only the AMO (D-111) | G | SEC | PR | Y |
| T-3-12 | FRD | Dismissal resample: an AMO dismisses 50 seeded signals, 5 plus or minus 2 are re-queued to the TSO; a dismissal rate at 3 times the peers raises a signal (D-109) | G | SEC | PR | Y |
| T-3-20 | SYN | Kill during Sales Submit (event appended, app killed): `submitted_local` persists, the event uploads, the server `route_day` becomes `sales_submitted` once | kill-test report | QA | RC | Y |
| T-3-21 | SYN | Shared phone: user A captures 20 rows offline and logs out; user B logs in online; A's rows upload under A's token within one sync cycle; B's bundle correct; no counter or bundle contamination (D-66) | lab report | QA | RC | Y |
| T-3-22 | SYN | Device replaced mid-day (new OTP): `bind_ordinal` 1, memo numbers 501 and up, `day_states` show the route in the field, the old device's PDA upload replays idempotently | lab report | QA | RC | Y |
| T-3-70 | SEC | Separation of duties: an AMO cannot verify outside the zone or their own requests; approver is never the requester | G | SEC | PR | Y |
| T-3-73 | SEC | Exceptions screen: seeded FS-01, 02, 03, 08, 09 scenarios appear to the right AMO and TSO only; review actions sync idempotently (F-AMO-038) | G | SEC | PR | Y |
| T-3-90 | FLD | Same-day cover: the substitute's delta contains the route within one sync cycle and his rows pass DQ-06 and DQ-07 (D-85) | lab report | QA | RC | Y |
| T-3-91 | FLD | Re-attribution moves a route-day in `dw` without touching `app` rows, audited | G | TL | PR | Y |
| T-3-100 | ANA | Supervisor history: an AMO moved between zones on the 15th is attributed days 1 to 14 and 15 to 31 to different users; `dim_supervisor_assignment` has two rows | G | TL | PR | Y |
| T-3-120 | MAN | AMO screens: home tiles with the badge on Outlet only and shown when above zero; Control Call flow versus the Joint Call screen (D-221); SR Perf. Assessment with OOS a subset of distributed and POSM tri-state; Joint Call assesses the selected SR, default stars [unknown; confirm: items 4 and 5, MQ-23, MQ-24]; AMO Survey; SS designation menu variants; Update Base limits (100 m, move limit, no mocked fix) (G-man-015, G-man-018, G-man-044, G-man-050, G-man-051, G-man-052, G-man-055, G-man-056, G-man-057; D-95, D-169) | patrol; goldens | SD | RC | Y |
| T-3-121 | MAN | AMO reads: Team Performance, Live Dashboard with explicit Filter, SR Stock with the SR phone shown as 11 asterisks and retailer phones visible, STD Memo Report, Sales Summary Up To Now (monthly x 15/17: Marise 100,000 gives 88,235.29), cards printed at 100 or less and uncapped detail with 2 decimals, bar bands green from 80, amber from 40, red below 40 (placeholder) (G-man-060, G-man-061, G-man-062, G-man-063, G-man-064, G-man-067; D-50, D-51, D-52, D-171) | G; goldens | SD | PR | Y |
| T-3-122 | MAN | Team Location and maps: last synced fix with "last seen HH:MM (n min ago)" and its source, greyed after `cfg.tso.team_location_max_age_min` (120), list fallback offline; no map SDK in the SR flavour; the map loads only when its screen opens; the key is restricted by package name and signing SHA (G-man-058, G-man-059; D-08, D-170) | lab; APK inspection | SD | RC | Y |
| T-3-123 | MAN | AMO bundle: a 54-route zone (3,500 to 11,000 outlets) downloads paged within the 2 MB gzip cap and the geo gate runs offline for any route of the zone (G-man-065; D-72) | lab report | QA | RC | Y |
| T-3-124 | MAN | AMO day state and requests: `supervisor_day` separate from `route_day` (D-27); dues scope is the AMO's own credit memos (D-37); verification queue: Save verifies, "বাতিল" discards with no server call, Reject and Approve only on the web, cluster and route both carried (G-man-011, G-man-032, G-man-034, G-man-035; D-172, D-230) | patrol | QA | RC | Y |

**3b TSO**

| ID | Lens | Check | Artefact | Verifier | Run | Blk |
| --- | --- | --- | --- | --- | --- | --- |
| T-3-01 | DAT | K-01 (Login %) is unchanged by a delta refresh (D-30) | G | TL | PR | Y |
| T-3-02 | DAT | Final Submit twice gives one row; the second attempt is refused with the already-submitted message (D-55) | G | TL | PR | Y |
| T-3-10 | FRD | Supervisor takeover chain: a TSO resets an SR password and issues an OTP within 1 hour; a bind from a device previously bound to the TSO is held, the DMO and security_admin are alerted, the SR is notified (D-112) | G | SEC | PR | Y |
| T-3-23 | SYN | TSO logout with 5 pending rows is refused with the count; allowed after PDA to Support or after sync (D-69) | lab report | QA | RC | Y |
| T-3-40 | QUA | AMO and TSO offline flows (patrol, airplane): control call (distribution, OOS, POSM), joint-call assessment, outlet verification queued offline, AMO sale; TSO visit plan set offline and synced; Final Submit online with the once-only refusal on retry | reports | QA | RC | Y |
| T-3-42 | QUA | Role-aware single codebase: the same build renders SR, AMO and TSO homes (tiles, badges, TSO dark theme) per token role through the three flavours; goldens in bn and en reviewed against the baseline recordings (D-01) | golden set; notes | SD | PH | Y |
| T-3-43 | QUA | TSO Final Submit drives the numbers: patrol performs a Final Submit; Playwright asserts on the minimal sync-health of 3b (SH-04 to SH-06, F-WEB-047 minimal: K-15 and the zone shown closed; the full page is 4a, D-513) K-15, K-02 frozen as of that moment, and that a row synced afterwards appears in the late-data report flagged `after_final_submit` (D-55) | report | OPS | PH | Y |
| T-3-44 | QUA | Supervisor usability: 3 AMOs and 2 TSOs from pilot territories each perform 10 scripted tasks unaided; task success 90 % or more; every failure becomes a ticket or an accepted note | usability report | SD | PH | N |
| T-3-60 | CFG | Bounded TSO permission: a TSO sets a radius for a zone of the territory within bounds, not for a neighbouring territory, not beyond bounds; audit records the scope; default mode is propose (D-94) | G | TL | PR | Y |
| T-3-61 | CFG | Reopen rules: a role outside `cfg.day.final_submit_delegate_roles` and the reopen roles cannot reopen; a reopen beyond the window needs two persons; late rows after final land per policy (D-55); a submit void by the TSO, by L1 with TSO confirmation inside `cfg.day.submit_undo_window_min` and by ops_admin before Final Submit unlocks the phone, is refused for L1 without confirmation and for a final-submitted zone until reopened (D-539, G-qa-70) | G | TL | PR | Y |
| T-3-71 | SEC | Device lifecycle: suspend blocks sync not capture; revoke wipes that user's data at next contact only; reactivate works; all audited (F-TSO-022 OTP panel, view-only, which first works in 0c per doc 19 s5.2 B7; D-513) | G | SEC | PR | Y |
| T-3-72 | SEC | Final Submit once per zone per day; reopen needs ops_admin and a reason; the audit chain verifies | G | SEC | PR | Y |
| T-3-92 | FLD | TSO on approved leave: an acting TSO submits (`cfg.day.final_submit_delegate_roles`); auto-close, if enabled, writes `kind = auto`; late rows still accepted and flagged (D-262) | G | TL | PR | Y |
| T-3-125 | MAN | TSO chrome: exactly seven drawer entries, logout is the header icon, default locale by role (`cfg.app.default_locale`: sr bn, amo bn, tso en) with a language switch and a Settings entry as IMPROVEMENT; greeting, Home button, back button, pickers and date formats (G-man-027, G-man-028, G-man-084; D-181, D-204) | goldens | SD | PR | Y |
| T-3-126 | MAN | TSO online-only matrix: Final Submit and maps are disabled offline with "needs internet"; the already-submitted alert fires at "Get Sales Data" through the preview endpoint (F-TSO-021); "SR Not Set" routes are listed and non-blocking; five-level scope-bounded cascade; Leave, Set Plan, Visit Query, Assign Task and Feedback queue with a sync-state badge (G-man-069, G-man-070, G-man-071, G-man-077; D-82, D-198, D-235) | patrol | QA | RC | Y |
| T-3-127 | MAN | TSO dashboards: Login 1 of 4 gives 25 % (K-01); K-02 and K-03 both returned with their basis; list rows per (user, route) while counters are per route; till-date 26/30 per-item ceil fixture; chart names By Segment Value Contribution and By Brand Call/Memo Ratio; the ring's day-target basis is unknown (MQ-40) (G-man-066, G-man-067, G-man-072, G-man-073, G-man-076, G-man-100; D-44, D-45, D-51, D-236) | G; goldens | SD | PR | Y |
| T-3-128 | MAN | TSO plans and forms: Visit Query is two free-text questions plus a built-in "delegate task" defaulting to No; Leave is one date plus a typed whole number of days of at least 1 and imported rows are never recomputed; Feedback category list is Suggestion; no invented caps (30 outlets, 30 leave days); Set Plan card shows the cluster; Pending and Completed rule (G-man-074, G-man-075, G-man-078 to G-man-083; D-175, D-176, D-197, D-199) | patrol | SD | RC | Y |

**Phase 3 exit**: an AMO verifies an SR's request and the web approves it (T-3-41, performed live by the delegate); a TSO's Final Submit closes a zone on the sync-health screen (T-3-43); the AMO and TSO reconciliation screens show the configured row sets; usability findings are ticketed.

Proved by: all gates above.

### 3.7 Phase 4: Web dashboards and reports

**4a Sync-health and dashboards**

| ID | Lens | Check | Artefact | Verifier | Run | Blk |
| --- | --- | --- | --- | --- | --- | --- |
| T-4-01 | DAT | National dashboard p95 under 300 ms from aggregates | G | TL | PR | Y |
| T-4-43 | QUA | Dashboards never touch the transaction log: the `app_web` role has no SELECT on `app.*` transactional tables; the e2e suite passes with that role; `pg_stat_statements` on staging shows zero `app.memo` or `app.visit` reads from the web role during the full e2e (D-128, D-190) | grant snapshot; pg_stat extract | TL | PR | Y |
| T-4-44 | QUA | Sync-health complete (s6.7): every tile has a formula, a source view, an "as of" stamp and a 60 s refresh, and matches a staged fuzz day whose true numbers are known from the seed | screenshot with seed numbers | SD | PH | Y |
| T-4-47 | QUA | Web performance budget in CI: Lighthouse 80 or more on dashboard and Daily Tracking; JavaScript 350 KB gz or less; dashboard p95 1 s or less from the replica under S5 | reports | TL | PR | Y |
| T-4-58 | SRE | Sync-health under load: S3 plus S5 with 1,500 viewers at 60 s give page p95 1.5 s or less, cache hit ratio 90 % or more, replica lag 30 s or less; degraded mode engages above 60 s lag (D-128; G-sre-16) | Azure Load Testing report | OPS | PH | Y |
| T-4-59 | SRE | Aggregation staleness: a planted throwing route goes dead after 5 tries, raises the staleness alert within 5 minutes, shows on sync-health and re-drives; a worker killed mid-claim is reclaimed after `claim_timeout_s` (D-140; G-sre-17) | G | OPS | PR | Y |
| T-4-60 | CFG | Changing `cfg.kpi.bands` re-colours the dashboard without a release; `cfg.kpi.submit_pct_denominator` switches the labelled tile while K-02 and K-03 stay available under their own names | G | TL | PR | Y |
| T-4-90 | FLD | Date rule: Friday shows the non-working banner; Tuesday 08:30 shows yesterday's sales beside today's login (D-28) | Playwright | SD | PR | Y |
| T-4-101 | ANA | On a configured holiday `target_outlets` is 0, K-01 renders a dash and `is_planned` is false; a route-day override re-plans only that route (D-28) | G | TL | PR | Y |

**4b Report set**

| ID | Lens | Check | Artefact | Verifier | Run | Blk |
| --- | --- | --- | --- | --- | --- | --- |
| T-4-03 | DAT | Every report has a `dw`-only query running under the `bi_reader` role | G | TL | PR | Y |
| T-4-40 | QUA | Every page of the 41-page union has Playwright e2e times 3 personas including the scope negative; visual snapshots at 1280 and 390 px; `axe` with zero critical; the parity map of the 37 spec pages in `rtm.yaml` shows each current page to its new page | report; parity map | SD | PH | Y |
| T-4-41 | QUA | Report parity: each of the 18 spec reports plus DSS, DS-RRS, Route-wise Memo and the Loyalty report run for the 3 baseline zones and month; totals compared with the baseline Excel exports; each difference is explained by a documented KPI decision (D-45, D-46, D-249) or fixed | `report-parity.md` | SD | PH | Y |
| T-4-42 | QUA | Excel export golden diff for STD Memo, Data Entry Log and Final Submit Log: values, number formats and sheet names equal to golden; the export is a formatting step over the same query (query count 1) | G | TL | PR | Y |
| T-4-100 | ANA | As-reported snapshot: the national value for month M as of date d equals `snap_month_zone_product` summed; a target revision on d+8 changes the live row and leaves the snapshot intact; the restatement log names the revision | G | TL | PR | Y |
| T-4-102 | ANA | All 25 analyst questions are `dw`-only queries stored under `/db/dw/questions/` that run under `bi_reader` against the staged fuzz day and return the fixture answer | G | TL | PR | Y |
| T-4-121 | MAN | Report vocabulary and columns: filter vocabulary and the labels Get Data, Get Excel, Get PDF, Print are consistent; column sets equal the sample `.xlsx` for 7 or more on-screen grids; 11 reports are Excel-only (not 13); Data Entry Log MIN is the first and MAX the last event time (G-man-038, G-man-095, G-man-097; D-191, D-238, D-240) | Playwright; xlsx diff | SD | PR | Y |
| T-4-122 | MAN | Report existence: DSS, DS-RRS (the print view shows discount data), Route-wise Memo, AMO Call Report (Supervisory Module) and the Loyalty Diamond League report are each a ReportQuery on `dw`; one name per report with display aliases as data (G-man-092, G-man-093, G-man-094, G-man-098; D-241) | Playwright | SD | PR | Y |

**4c Panels and PII**

| ID | Lens | Check | Artefact | Verifier | Run | Blk |
| --- | --- | --- | --- | --- | --- | --- |
| T-4-02 | DAT | `bi_reader` cannot select `dim_outlet_pii` (same run as T-4-71) | G | SEC | PR | Y |
| T-4-14 | FRD | PII budgets: a WM persona paging `GET /outlets?reveal=1` gets 429 after 2,000 rows per hour; an export of 6,000 PII rows needs approval and carries the watermark sheet and is attributable from a 10-row fragment; export cell sanitiser against formula prefixes (D-121; G-fraud-13) | G | SEC | PR | Y |
| T-4-70 | SEC | RLS performance: p95 of `/sync/batch` and `/sync/bundle` with RLS on is at most 10 % above off; otherwise the fallback decision is recorded (D-106) | G | SEC | PH | Y |
| T-4-71 | SEC | `bi_reader` cannot select `dim_outlet_pii`; `v_outlet_masked` masks; the replica is reachable only through a private endpoint | G | SEC | PR | Y |
| T-4-72 | SEC | PII exports need the `pii` claim and re-authentication; every export is logged with `includes_pii` and visible in the viewer (D-108) | G | SEC | PR | Y |
| T-4-73 | SEC | Web headers (Mozilla Observatory A or better), CSRF on `/auth/refresh` from a foreign origin refused, idle timeout, MFA enforced for the admin bundles (D-114) | G | SEC | PR | Y |
| T-4-74 | SEC | IDOR sweep: every GET and PATCH by id with foreign ids and every foreign key in write bodies returns 403 or 404 and zero rows | G | SEC | PR | Y |
| T-4-120 | MAN | The role x menu x action matrix is data (`cfg.web.menu_by_role`): the 41-page union renders per role; DMO, WM, WMO and Top menus match the captures [unknown; confirm: MQ-48] (G-man-096, G-man-099; D-185, D-243) | Playwright | SD | PR | Y |
| T-4-123 | MAN | Web back office: Web Entry per route-day with a client uuid per submission; Final Submit page with date picker and the cut-off banner; "Delete Section Data" is an audited void whose tombstone rejects a later phone upload as `voided_by_admin`; web QC market and warehouse entries stay a separate source (G-man-085, G-man-086, G-man-087, G-man-089; D-40, D-97, D-182) | Playwright; sync test | SD | PR | Y |
| T-4-124 | MAN | Web session: login failure text; no self-service reset (reset through the TSO or support); Remember me off by default; password at least 12 characters with mixed case and a digit, not one of the last 10, not within 24 hours of the last change (G-man-091; D-102) | Playwright | SEC | PR | Y |
| T-4-125 | MAN | Outlet Approval Panel: one Outlet Type filter, Verify, Reject and Approve with the Approve confirm; the TSO's Browse Retailer shows Address, NID, TIN and Trade Licence columns (null where blank or the placeholder 123) and each export is in `report_export_log` (G-man-039, G-man-062; D-108, D-186, D-207) | Playwright | SEC | PR | Y |

**4d Scale proof**

| ID | Lens | Check | Artefact | Verifier | Run | Blk |
| --- | --- | --- | --- | --- | --- | --- |
| T-4-51 | SCA | S1 at 1,000 users (wave-1 size) with 5-minute concentration: bundle p95 2 s or less, 0 5xx | Azure Load Testing | OPS | PH | Y |
| T-4-52 | SCA | S1 at 9,850 users; decide the Redis fast path (D-07) | Azure Load Testing | OPS | PH | Y |
| T-4-53 | SCA | Connection exhaustion: scale api to 30 replicas under S3; PgBouncer waits 0 | Azure Load Testing; pg metrics | OPS | PH | Y |
| T-4-54 | SCA | Aggregation under S3 plus S5: lag p95 60 s or less and longest transaction under 1 s; the coalescing window is measured, not assumed; ASSERT the p95 milliseconds per key (it replaces the 15 ms placeholder of doc 18 s4.5 and sets the worker replica maximum) and CATALOG BLOAT (`pg_class` and `pg_attribute` row counts flat over the run: the recompute uses no temp table) (D-528, G-qa-53) | Azure Load Testing | OPS | PH | Y |
| T-4-55 | SCA | S7 failover chaos: error window 120 s or less, no lost rows, devices resume | Azure Load Testing | OPS | PH | Y |
| T-4-56 | SCA | Replica lag and the stale banner (D-128) | Azure Load Testing | OPS | PH | Y |
| T-4-57 | SCA | Telemetry volume per 1,000 devices 1 GB per day or less (D-136) | ingestion report | OPS | PH | Y |
| T-4-45 | QUA | Alerts proven by fault injection (s6.6): each Sev1 and Sev2 rule fired once on staging by injecting its condition, reached the on-call phone and the Teams channel, and links a runbook | alert log; runbook index | OPS | PH | Y |
| T-4-46 | QUA | Reconciliation reports (s6.4) in production shape run nightly on staging for 7 days with zero unexplained differences | 7 report outputs | OPS | N | Y |
| T-4-140 | D20 | Load-test budget approved per window and quotas confirmed (ACA cores 64 or more, PostgreSQL 48 vCores or more, Azure Load Testing 10 engines or more); the workflow scales staging to the prod SKU for the window only and back to the pilot SKU within 1 hour, verified by a cost alert (D-133, D-134; G-qa-09) | approval record; quota screenshots | OPS | PH | Y |

**Phase 4 exit**: a Wing Manager persona sees only their wing (the delegate tries another wing's URL); the national dashboard loads in well under a second on the full-size synthetic fleet; the 22 reports match the baseline exports or carry an approved explanation; the S1 report at 9,850 users shows bundle p95 2 s or less and 0 lost rows; alerts reached a phone.

Proved by: all gates above.

### 3.8 Phase 5: Programmes

| ID | Sub | Lens | Check | Artefact | Verifier | Run | Blk |
| --- | --- | --- | --- | --- | --- | --- | --- |
| T-5-40 | 5a | QUA | Programme flows offline (patrol): redemption with points deduction, one gift photo per outlet, Astha tabs with quarter filters, task swipe-resolve; ledger reconciled with a reference model after a fuzzed day | reports | QA | RC | Y |
| T-5-61 | 5a | CFG | Loyalty rate safety: `cfg.loyalty.cash_rate_mtk_per_point` is C3; redemptions store the rate used; a revert leaves past redemptions unchanged | G | TL | PR | Y |
| T-5-70 | 5a | SEC | Loyalty: a redemption over the server balance is rejected or accepted-and-flagged per D-266; points are never accepted from the client; an adjustment above the threshold needs an approver | G | SEC | PR | Y |
| T-5-100 | 5a | ANA | Programme attribution: an outlet whose tier changes mid-quarter appears once under the configured attribution; superseding a point-earning memo reverses and re-earns; a frozen quarter snapshot is unchanged by a late sync | G | TL | PR | Y |
| T-5-120 | 5a | MAN | Points and redemption: Outlet Points with expiring points, expiry date and as-of date from the bundle; the POSM Q1.1 photo earns +50 points computed on the server (not for an AMO survey); a redemption is a basket of lines under one confirm; cash 2 Tk per point up to 199 points; Tornado fan costs 400 points (D-219) (G-man-040, G-man-041, G-man-042; D-41) | patrol; sync test | SD | RC | Y |
| T-5-121 | 5a | MAN | Astha: one component with role switches (SR tabs, AMO route selector, the "All Brand" memo-target row per app); zero month chips means the whole quarter; Gift Choice Panel and Report; Astha Web Entry outlet x SKU grid; the Diamond League report (G-man-045, G-man-046, G-man-047, G-man-048; D-168, D-192, D-231) | Playwright; patrol | SD | RC | Y |
| T-5-41 | 5b | QUA | Promotion corpus complete: every live promotion group of the Q13 list has 3 golden memos or more from the baseline pack or business examples; Discount Report totals equal the corpus sums (D-33) | G; coverage table | SD | PR | Y |
| T-5-60 | 5b | CFG | A promotion effective tomorrow is in today's bundle as `scheduled`, applies to tomorrow's memos offline and never to today's; a late sync of today's memo uses today's rule | G | TL | PR | Y |
| T-5-01 | 5c | DAT | A target revision re-aggregates only its month and scope | G | TL | PR | Y |
| T-5-42 | 5c | QUA | Target revision approval chain end to end: requester differs from approver per level, `is_final`, audit rows; a negative target is rejected at entry; the Astha report renders a dash for the -20 fixture (the -37,500 % regression) | report | SD | PR | Y |
| T-5-43 | 5c | QUA | REGRESSION run, after the 5c target features, of the chains proved at 3b by T-3-155 (D-532): leave (TSO to DMO) and task delegation (AMO and TSO to SR) end to end across phone and web with offline resolve, plus the target-linked task types | report | SD | RC | N |
| T-5-71 | 5c | SEC | Fraud job on a synthetic dataset with 50 planted schemes across FS-01 to FS-19: recall 90 % or more, false positives 5 % or less per clean SR-day | G | SEC | N | Y |
| T-5-72 | 5c | SEC | Dues: a collection above the outstanding amount or against a foreign memo is rejected; the ageing report is correct | G | SEC | PR | Y |
| T-5-10 | 5c | FRD | Fraud recall extended with planted FS-20 to FS-29 scenarios: recall 90 % or more, false positives 5 % or less; Data Entry above 20 memos without TSO confirmation is refused | G | SEC | N | Y |
| T-5-90 | 5c | FLD | The missing-M+1-targets alert fires on the 26th and the app renders a dash | G | TL | PR | Y |
| T-5-122 | 5c | MAN | Targets and tasks: target set header (name, product type variant, target type stt, start, end, "WMO approval pending"), manual route x variant grid and Excel upload, target at least 0 at entry; task labels চলমান and সম্পন্ন, resolved tasks stay on the SR list (G-man-049, G-man-068, G-man-090; D-31, D-167, D-179, D-189) | Playwright | SD | PR | Y |

**Phase 5 exit**: points and gifts reconcile (ledger equals redemptions equals photos); a promotion applies at sale and prints correctly; a revision routes through its approval levels; the negative-target bug cannot be reproduced.

Proved by: all gates above.

### 3.9 Phase 6: Admin and master data

| ID | Sub | Lens | Check | Artefact | Verifier | Run | Blk |
| --- | --- | --- | --- | --- | --- | --- | --- |
| T-6-40 | 6a | QUA | Admin CRUD e2e generated from the entity registry for all 58 entities: create, read, update, soft-delete times permission bundle, with an audit row present; CSV import and export round trip for outlets, routes, assignments and targets | Playwright (generated) | TL | PR | Y |
| T-6-120 | 6a | MAN | Wholesale marking by a TSO is idempotent (`batchUuid`) with audit per outlet and unmark behind `cfg.outlet.wholesale_unmark_allowed`; Sales Plan carries the zone contact fields and Data Entry Date (G-man-036, G-man-088; D-42, D-97) | Playwright | SD | PR | Y |
| T-6-41 | 6b | QUA | The sponsor changes the radius: the delegate sets a zone's radius with a reason from the console, a second approver approves, the lab phone in that zone, selling, shows the new value within 15 minutes and an idle lab phone shows it at its next foreground (doc 19 s4.1b), the next visit stores `radius_m_used`, the acknowledgement view shows 100 %, then a one-click revert | screen recording | SD | PH | Y |
| T-6-01 | 6b | DAT | A config change reaches `acked_pct` 100 % in the test fleet | lab report | TL | PR | Y |
| T-6-10 | 6b | FRD | Rails direction: a TSO raising a zone radius 100 to 180 needs C3; break-glass setting 500 at territory is refused (`restrictive_only`); break-glass revert allowed; a fraud threshold set by `cfg.field` is forbidden and by `cfg.sec` allowed with the dead-signal watch; a back-dated price row by `master_data` is refused and by `finance_admin` with an approver accepted with the restated-memo count; a target edit after month start routes to the revision workflow; BRAKE LANE (D-588, doc 19 s7.6c): at 08:00 inside the freeze a build is added to `blocked_versions` and `cfg.promo.engine_enabled` is switched off by a break-glass holder and both apply at once and are reviewed (T-2-173), while `wave_pct` can be lowered but not raised (D-94, D-98, D-109) | G | SEC | PR | Y |
| T-6-51 | 6b | SCA | Config bounds, two-person, canary scope and revert in the admin e2e; radius 0 is rejected | Playwright | TL | PR | Y |
| T-6-60 | 6b | CFG | Dead-key report lists a registry key with no code reader; a deprecated key is hidden by default | G | TL | N | Y |
| T-6-61 | 6b | CFG | Rollback to version N reproduces the resolved snapshot of N for 10 random chains exactly | G | TL | PR | Y |
| T-6-70 | 6b | SEC | Console: out-of-bounds radius rejected; a critical key needs a different approver; change audited; `config_version` bumps; acknowledgement reach reported | G | SEC | PR | Y |
| T-6-71 | 6b | SEC | Audit immutability: UPDATE and DELETE fail for every role; the hash chain verifies; the daily export lands in the immutable container and cannot be deleted (D-113) | G | SEC | PR | Y |
| T-6-72 | 6b | SEC | Admin bundles: a `config_editor` cannot write users; break-glass use alerts within 5 minutes (D-91) | G | SEC | PR | Y |
| T-6-42 | 6c | QUA | Run-without-engineering drill: NAMED PERSONAS complete the tasks from a script with no engineer present, each persona holding only its own bundle of doc 19 s5.1b (D-91 forbids one persona with every permission, D-550, G-qa-82): security_admin adds an SR; master_data assigns a route with dates and publishes a holiday; ops_admin or L2 issues a device OTP and replaces a device; the TSO approves an outlet request and enters a target; an approver approves a target revision; config_editor with a config_approver changes a radius; release_mgr sets `wave_pct`; audit.view reads the audit log for one of these. The drill ALSO covers the launch-day actions the first list omitted: undo a Sales Submit (TSO and L1 with confirmation), reopen or on-behalf-submit a zone, correct an outlet location (AMO proposes, TSO approves), revert or roll back a config (editor, and break-glass), declare an emergency non-working day, assign cover or an exception and bulk-mark absent, disable or offboard an SR, accept a quarantined row, and decode a support code, each within its time box | drill log with times | SD | PH | Y |
| T-6-43 | 6c | QUA | PITR restore of staging to T-1 h into a new server with verification: per-table row counts and the audit hash-chain head equal the pre-restore snapshot; the app points at the restored server and syncs (D-127) | restore log | OPS | PH | Y |

**Phase 6 exit**: the T-6-42 drill completes unaided; every admin write has an audit row the delegate can find in the viewer; a restore has been performed and verified.

Proved by: all gates above.

### 3.10 Phase 7: Migration, pilot, cutover

| ID | Sub | Lens | Check | Artefact | Verifier | Run | Blk |
| --- | --- | --- | --- | --- | --- | --- | --- |
| T-7-01 | 7a | DAT | Per-zone control totals match the dump (s7.4) | reconciliation report | SD | PH | Y |
| T-7-02 | 7a | DAT | Re-importing the same dump is a no-op | G | TL | PH | Y |
| T-7-80 | 7a | PIL | Shadow import of the real dump: row counts per table against the dump's data dictionary; quarantine at most 1 % of rows per table with every reason counted (D-153); control totals of s7.4 with zero tolerance on counts and balances | `import_reconciliation` report signed | SD with FIN | PH | Y |
| T-7-58 | 7a | SRE | Import rollback drill: a planted-bad delta on staging, `rollback_import(run_id)` returns control totals to the pre-import values; referenced rows are quarantined and listed (G-sre-21) | drill log | OPS | Q | Y |
| T-7-74 | 7a | SEC | Apsis dump handling: a credential-discovery report is produced; any live credential is rotated; the raw dump is deleted 30 days after reconciliation; PII only in `app` and `dim_outlet_pii` (D-119, D-155) | report | SEC | PH | Y |
| T-7-100 | 7a | ANA | Migrated history provenance: months loaded by path B carry `source = migration_aggregate` and `fidelity = 2`; a route-level report over them is refused and a wing-level year-on-year renders with the hatched marker | G | TL | PR | Y |
| T-7-145 | 7a | D20 | Daily Apsis data for pilot routes: a machine-readable feed of memo count, STD per SKU, value and dues for the last 5 trading days of the dump arrives and the compare job runs on it end to end; if Apsis cannot export, the manual-keying fallback is rehearsed with a clerk on the same 5 days and its error rate recorded (D-154; G-qa-03) | compare dry-run report | PL | PH | Y |
| T-7-81 | 7b | PIL | Opening balances spot check in the field: for 30 random outlets across 3 pilot zones, INCLUDING outlets whose dues or loyalty changed in Apsis after the T-1 20:00 cut (stragglers, D-556), the retailer's due and loyalty balance in the new app equal the Apsis app and, where available, the retailer's own record | spot-check sheet | PL | PH | P |
| T-7-82 | 7b | PIL | Pilot parallel run (s7.3): a daily automated comparison per route-day for all pilot routes; pass is 10 consecutive trading days (per `cfg.calendar.*`) with zero category C, exact memo count and STD after A, D and E are explained, and B closed by a recorded decision; every other discrepancy categorised by 09:00 next day (D-145; G-qa-23; also T-7-20) | `/docs/evidence/phase-7/T-7-82/day-NN.md` | SD | D | W1 |
| T-7-20 | 7b | SYN | Same run as T-7-82: new-app counts against the Apsis daily report per route (F-SYS-043) equal for 10 consecutive trading days | compare reports | SD | D | W1 |
| T-7-90 | 7b | FLD | Opening-balance provenance: for 30 outlets the old-system balance shown on the device equals the import reconciliation (G-field-22) | spot-check sheet | PL | PH | P |
| T-7-91 | 7b | FLD | A radius calibration report per geo class exists from pilot fixes before any wave radius is set (D-93) | report | SD | PH | W1 |
| T-7-140 | 7b | D20 | Pilot device hygiene: pilot phones hold only their pilot routes' bundles; storage is encrypted per D-67; no migrated Apsis PII appears in logs, telemetry or PDA uploads; at pilot end a reconciled logout wipes the new app's data and the Apsis app is left untouched (D-458; G-sec-23) | checklist; log grep | SEC | PH | P |
| T-7-141 | 7b | D20 | Pilot readiness: routes chosen for variety (urban dense, rural, hill; GT outlets; Astha, Diamond League or Superstar outlets only if 5a to 5c have exited; one wholesale buyer; one route with placeholder coordinates), consent and incentive for double entry signed, briefing card names which memo the retailer keeps (G-qa-16, G-field-12; D-152) | signed checklist; briefing card | SD | PH | P |
| T-7-142 | 7b | D20 | Retailer "same memo" check: at least 30 retailers (ASSUMPTION: 3 per pilot route on a 10-route pilot) shown the new memo beside the old; every complaint recorded; none unexplained (D-146; G-qa-24) | feedback sheet | RM | PH | W1 |
| T-7-143 | 7b | D20 | Wave-data export for the Apsis side: the export job writes the wave's new-app data in the dump's shape (one CSV per table with a data dictionary); re-importing it into a scratch database reproduces the control totals (D-148; G-qa-11) | export; reconcile report | OPS | PH | W1 |
| T-7-144 | 7b | D20 | Old-app retirement per wave is documented by the business and rehearsed on the pilot: after the switch time an SR cannot create new Apsis sales [unknown; confirm: how, since AKTCL cannot set the app read-only itself; if no mechanism exists the wave go/no-go line is "old app disabled or uninstalled on every phone of the wave, ticked by the TSO on the readiness screen", and the DL-6 `apsis_residual` alert of T-7-164 detects what slips through, D-556]; evidence is an Apsis-side screenshot and the SR's confirmation (D-148; G-feat-67) | procedure; screenshot | SD | PH | W1 |
| T-7-10 | 7c | FRD | Reproducible APK: an independent rebuild of the release tag matches the CI digest per ABI; `app_release` publish is refused on a mismatch; the self-hosted runner refuses a `pull_request` job (D-122) | rebuild diff | SEC | RC | W1 |
| T-7-51 | 7c | SCA | S1, S2, S3 and S5 at 1.5 times the wave size on prod-SKU staging | Azure Load Testing | OPS | PH | W1 |
| T-7-52 | 7c | SCA | S4 worst case plus S7 chaos, with a CROSS-REGION REPLICA LAG gate: at most 5 minutes at the end of the 17:00 wave, the WAL rate against the replay rate recorded in MB/s (D-522, G-qa-46) | Azure Load Testing | OPS | PH | W1 |
| T-7-53 | 7c | SCA | Kill-switch drill: block a version; capture continues, upload continues, login is refused (D-130) | lab report | OPS | Q | W1 |
| T-7-54 | 7c | SCA | DR rehearsal: promote the East Asia replica, redeploy by Bicep, point Front Door, force a new server generation carrying its restore point and watch devices re-send, INCLUDING devices that stayed offline 72 h across the flip, devices that acked in the last minute before the restore point and a PITR distance above 24 h, with zero missing UUIDs afterwards (D-517); measure RTO (target 4 h) and RPO (15 minutes) (D-127) | drill log | OPS | Q | W1 |
| T-7-55 | 7c | SCA | Quota checklist signed off (D-133) | signed list | OPS | PH | W1 |
| T-7-59 | 7c | SRE | Launch-day game day: the timeline of doc 18 s7 injected on prod-SKU staging with the 1.5x fleet simulator (pre-generation failure at 04:00, deploy at 09:00, radius change and revert at 11:00, failover at 13:00, retry loop at 10:30, WAF 403 at 14:30, telemetry cap at 15:00, the 17:00 wave, region-outage tabletop at 18:30): every detection fires within its stated time, every runbook is executed, RTO per incident recorded, no sync loss | game-day report | OPS | PH | W1 |
| T-7-60 | 7c | CFG | Wave rollback by flag: `cfg.flag.new_app_login_enabled = false` for a wave blocks new-day login for that wave only, pushes within 5 minutes to online devices, and all pending rows still upload | G; lab | OPS | Q | W1 |
| T-7-61 | 7c | CFG | Launch-day change rate limit: the 6th applied C3 version within an hour is refused with a clear message (D-100) | G | TL | PR | W1 |
| T-7-70 | 7c | SEC | External penetration test (API, web, Android app including local storage, infra) by an independent firm on staging at prod SKU with anonymised data: no open high or critical before wave 1; retest of fixes | report | SEC | PH | W1 |
| T-7-71 | 7c | SEC | Key-rotation drill: JWT key rollover with no field logouts; DEK re-wrap; pepper version bump | drill log | SEC | Q | W1 |
| T-7-72 | 7c | SEC | Incident tabletop (lost device, admin credential leak, spoofing ring) executed against staging; audit evidence exported | tabletop record | SEC | Q | W1 |
| T-7-73 | 7c | SEC | DR restore includes Key Vault soft-delete recovery and audit-chain continuity across the restore | drill log | SEC | Q | W1 |
| T-7-75 | 7c | SEC | Wave-day auth storm: 1,000 binds and 9,850 refreshes in 60 minutes on prod-SKU staging with the OTP panel bulk issue: no legitimate lockout, refresh p95 under 300 ms | Azure Load Testing | SEC | PH | W1 |
| T-7-83 | 7c | PIL | Rollback drill suite on staging before wave 1, each timed with a runbook (s7.5) | drill log with times | OPS and SD | Q | W1 |
| T-7-84 | 7c | PIL | Day-one readiness checklist (s7.6) 100 % complete for the wave and signed by the four owners at T-1 | signed checklist | SPN | W | WV |
| T-7-85 | 7c | PIL | Support readiness: helpdesk staffed per s7.8, Bangla scripts and the support runbooks RB-17 to RB-54 of doc 18 s7.6 for the top 20 issues and the launch-day actions, EACH RUN WITH THE REAL L1 PERSONA and the tool and permission of doc 19 s5.1b (a script that needs a permission the persona lacks fails, T-2-158);  escalation tested with 20 synthetic tickets inside SLA, the TSO toolkit verified on a lab phone, status banner and helpdesk number visible on the locked-out screen (`GET /config/public`) (D-149; G-qa-04) | ticket log; script pack | SUP | PH | W1 |
| T-7-86 | 7c | PIL | Wave go/no-go: the previous wave's 5 trading days against the thresholds of s7.7, recorded and signed before the next wave starts | go/no-go record | SPN | W | WV |
| T-7-87 | 7c | PIL | Post-wave reconciliation for 5 trading days: device totals from telemetry equal server accepted plus rejected per route-day; `agg` equals `app`; final-submit coverage 100 % of the wave's zones by 21:00 | nightly reports | OPS | W | WV |
| T-7-56 | 7d | SCA | S1 to S5 at 1.5 times the full fleet (12,750 SRs) plus the S8 soak | Azure Load Testing | OPS | PH | WV |
| T-7-57 | 7d | SRE | First-morning storm S1': 7,350 password logins, binds, full bundles and APK through Front Door in 20 minutes on prod-SKU staging: replica memory under 80 %, 0 OOM restarts, login p95 3 s or less; with the pre-bind-day procedure the same population is refresh plus 304 at 2 s or less (D-126; G-sre-04) | Azure Load Testing | OPS | PH | WV |
| T-7-88 | 7e | PIL | Decommission criteria (s7.9, D-155): all waves stable for 10 trading days, final Apsis delta imported and reconciled, Apsis credentials rotated or removed, the raw dump archived to immutable storage then deleted per policy, memo-number continuity recorded; NO UNSIGNED ITEM in the Parity Exceptions Register of doc 15 s6.4 and NO Apsis function in the usage census without a replacement or a SIGNED RETIREMENT (evidence class 1 or the interview-and-observation class 2 of T-7-158, D-565) (D-502, D-503, G-qa-27, G-qa-47); the final decommission delta ran through the delta contract of doc 16 s12.6 | decommission record | SPN | PH | Y |
| T-7-89 | 7e | PIL | Retrospective: known-issue register closed or accepted; DECISIONS.md and runbooks updated; the gate report archived | retro notes | SD | PH | N |

**Exit per wave**: T-7-84 signed at T-1, T-7-86 signed before the next wave, T-7-87 clean for 5 days. **Exit Phase 7**: all waves stable and T-7-88 signed.

Proved by: all gates above.

### 3.11 Register supplement: ids minted by documents 16, 17, 19 and 21, and reserved ranges completed here

The ids below were minted by other documents in the blocks the ranges reserve (doc 16 for 05 to 09 and 104 to 109, doc 17 for 36 to 39, doc 19 for the back-office pages, doc 21 for 70 to 79 beyond T-x-76: the last 21 rows, added at the editorial merge from doc 21 s10.2 and doc 19 s12.5, OI-21-14) or are cited by docs 14, 15 and 18 as ranges that this document must define (T-3-24 to T-3-28). Lens code is the minting document's lens. They are part of the register and of the counts in s3.12.

| ID | Sub | Lens | Check | Artefact | Verifier | Run | Blk |
| --- | --- | --- | --- | --- | --- | --- | --- |
| T-0-06 | 0b | DAT | Schema contract lint: every device-originated table has `client_uuid`, the provenance block and a row in doc 16 s6; every flag emitted by `/api` is a `cfg.dq_rule` row; no enum for a business list; every migration number is inside its reserved block; the same lint calls the capture-to-dw coverage check of T-0-150 (every OFFLINE or QUEUED app table has a dw object or a signed exclusion row) and the jsonb check of T-0-151 (D-500, D-501, G-qa-40) | G | TL | PR | Y |
| T-0-07 | 0b | DAT | Roles and immutability: `web_ro` cannot read `app`; `api_rw` cannot DELETE a transactional row; an UPDATE outside the allow-list raises 42501 on EVERY capture table (the test iterates over `app.immutability_policy`, not a hand-written list: it updates every non-listed column and deletes a row of each table, D-524, G-qa-49); `bi_reader` cannot read `dim_outlet_pii` | G | SEC | PR | Y |
| T-0-08 | 0b | DAT | Money at SQL level: golden memo fixtures GF-01 and GF-02, the 7.935 round trip, and a property test of `app.div_half_up` and `app.round_to_paisa` (D-15, D-19) | G | TL | PR | Y |
| T-1-05 | 1b | DAT | Worker enrichment grants: `worker_rw` updates only allow-listed columns; replaying an enrichment changes nothing | G | TL | PR | Y |
| T-1-06 | 1b | DAT | Memo arithmetic at ingest: DQ-14, DQ-15 and DQ-53 on 10,000 fuzzed memos; `round_adj_mtk` within 5 mtk; the printed total reproducible from stored columns | G | TL | PR | Y |
| T-1-37 | 1c | SYN | Trigger inventory and manifest lint (doc 17): zero foreground services and alarms in `dumpsys`; no `Timer.periodic` under 60 s; no `setInexactRepeating` under 15 minutes and no geofence callback; WorkManager jobs registered only while rows or photos are pending (T5); `allowBackup` false with extraction rules; no background-location permission (D-59, D-67, D-73) | G; dumpsys output | QA | RC | Y |
| T-1-38 | 1b | SYN | Time anchor: a reboot between anchor and capture falls back with `time_untrusted`; wall clock set back 24 h in one boot and set forward past 17:00 leave the business date and the gate unchanged with a banner; a changed device zone has no effect (D-20) | lab report | QA | RC | Y |
| T-1-39 | 1b | SYN | Contract conformance: the record-type enum and reason codes in `/packages/contract` match the outbox CHECK, the server registry and the Dart client; Zod and Dart round-trip fixtures; an unknown type or key is rejected; Bengali digits are normalised (D-118, D-150) | G | TL | PR | Y |
| T-1-36 | 1c | SYN | Battery and data baseline (doc 17): the standard scripted day on the primary device with the s2.7 protocol, also measuring the engine-start cost of a WorkManager run (planning estimate 300 ms and 30 to 40 MB, ASSUMPTION), the SQLCipher cost, the tail-energy assumption and the heavy-day slope, and giving the SR bundle size and rows per SR-day that replace the planning figures (about 25 KB gz median, 60 KB p95; delta 20 KB gz or less) in the load simulator of doc 18 s8; re-run per RC; one run with T-1-45, which reads the same numbers against the budgets | `/docs/perf/battery-<v>.md` | QA | RC | Y |
| T-1-65 | 1c | CFG | Minimal console (D-90): radius at global and territory, check-out time and `min_version` set from the GUI with bounds, a reason and an audit row; the reach widget shows targeted, acknowledged and pending devices | G; screenshot | TL | PR | Y |
| T-1-105 | 1c | ANA | Dirty queue: coalescing, `gen` and `claimed_gen` (a re-enqueue during a run releases the claim), claim timeout after a worker deploy, dead letter after 5 attempts raises the alert, a rebuild never starves live work (D-140) | G | TL | PR | Y |
| T-1-106 | 1c | ANA | KPI SQL fixtures for K-01 to K-18 including every dash case (zero, negative and null denominators) | G | TL | PR | Y |
| T-2-05 | 2a | DAT | Offer, DRP and QC decomposition: `memo_offer` components sum to the memo deductions; DQ-52, DQ-55 and DQ-45 fire on their fixtures | G | TL | PR | Y |
| T-2-07 | 2a | DAT | Quantity handling: `qty_base` for every fixture (pack, stick, dozen, box), report-unit conversion and the pack-factor mismatch flag | G | TL | PR | Y |
| T-2-06 | 2b | DAT | Dues ledger: signs, supersede and void reversal, FIFO allocation equal to a reference model, opening bucket | G | TL | PR | Y |
| T-2-09 | 2b | DAT | Void and tombstone: a voided `client_uuid` uploaded late is rejected `voided_by_admin`; a memo void reverses dues and stock as events (D-22, D-86) | G | TL | PR | Y |
| T-2-08 | 2c | DAT | Outlet data: a merge keeps history, closure never deletes, location history and request lifecycle rows exist, the photo owner is nullable (D-25) | G | TL | PR | Y |
| T-3-24 | 3a | D20 | AMO offline day in airplane mode on a zone-wide bundle: control call (distribution, OOS, POSM), joint-call assessment, outlet verification queued, AMO sale, Sales Submit with the AMO's configured reconciliation rows; a kill during the day loses nothing (T-3-40 repeats this and adds the TSO flows) | lab report | QA | RC | Y |
| T-3-36 | 3a | SYN | Kill during a paged bundle download: the old bundle stays intact, the download resumes from the last page, the swap is atomic, total at most 2 MB gz for a 54-route zone (D-72) | kill-test report | QA | RC | Y |
| T-3-107 | 3a | ANA | Calendar and till-date: the April 2026 working-day fixtures (14 elapsed, 11 remaining, 25 working days), a scoped holiday, a make-up day, a route-day override, and the per-surface till-date fixtures (D-28, D-51) | G | TL | PR | Y |
| T-3-05 | 3b | DAT | Supervisor captures: idempotent upsert under duplicate and out-of-order fuzz for tasks, assessments, distribution checks, price compliance, leave, visit plans and feedback | G | TL | PR | Y |
| T-3-25 | 3b | D20 | Final Submit preview and once-only rule: the preview endpoint is scope-checked; the alert fires at "Get Sales Data"; the same `client_uuid` returns the first success; another uuid gets 409 with the same Bangla text; the web page uses the same rule (F-API-039; D-82, D-262) | G; patrol | QA | RC | Y |
| T-3-26 | 3b | D20 | TSO queued writes (the task and leave chains land WHOLE in 3b, D-532: F-API-026, F-API-022 and the SR list and resolve are 3b): Leave, Set Plan, Visit Query, Assign Task and Feedback queue offline with a client uuid and a sync-state badge; a retry never doubles; the feedback image uses the media queue (D-82) | patrol | QA | RC | Y |
| T-3-27 | 3b | D20 | TSO read snapshot: the login snapshot and pickers stay under 1 MB gz for a territory of about 2,500 outlets; every aggregate screen shows "as of hh:mm"; no polling timer; an offline snapshot is labelled stale (D-72, D-84) | G; patrol | QA | RC | Y |
| T-3-28 | 3b | D20 | A zone that cannot Final Submit (TSO absent, null house, API down, mis-click): the picker tolerates a null house; an on-behalf submit carries a reason and the actor; auto-close stays off by default; the 21:00 coverage list names the remaining zones (D-262; doc 18 failure mode FM-27) | G; patrol | QA | RC | Y |
| T-4-104 | 4b | ANA | Report registry: every report of doc 16 s9 runs as a `dw`-only query (the CHECK holds), `min_fidelity` excludes migrated aggregates from route, SR and BSR reports, the effective-source view picks one source per route-day | G | TL | PR | Y |
| T-4-61 | 4c | CFG | Web Entry: route-day aggregates with a client uuid per submission; web rows and app rows for the same route-day are mutually exclusive by default (flagged if both exist, never added); back-date cut-off by `cfg.web.entry_backdate_days` (D-40, D-97) | Playwright | TL | PR | Y |
| T-4-62 | 4c | CFG | Web Final Submit with the audited void: "Delete Section Data" voids web-entry rows only before Final Submit, with reason, scope and tombstones; a late phone upload is rejected `voided_by_admin`; the page carries the app's messages and the DSS advisory (D-40, D-182, D-184) | Playwright | TL | PR | Y |
| T-4-64 | 4c | CFG | QC entry and reports: market and warehouse entries are a separate source never added to app QC; the two QC reports in Excel and PDF; the 11-code fault table (D-34, D-183) | Playwright | TL | PR | Y |
| T-4-65 | 4c | CFG | Permission bundles: the role x menu x action matrix is enforced on every `/admin` and back-office endpoint; a TSO writes only inside the own scope; maker-checker for critical changes (D-91) | G; Playwright | SEC | PR | Y |
| T-4-108 | 4c | DAT | BI: `bi_reader` grants, the masked view, `bi_user_scope` row-level security and the replica-lag alert (doc 16 says 120 s; D-128 and doc 18 say 60 s, OI-20-20) | G | SEC | PR | Y |
| T-5-62 | 5a | CFG | Astha Web Entry (outlet x SKU grid) and the Astha Gift Choice Panel: route-scoped per-outlet dropdown, save and lock rules as decided, the report filtered by Gift Status (D-192) | Playwright | TL | PR | Y |
| T-5-63 | 5c | CFG | Set Target and approval list: manual route x variant grid and Excel upload with a stored file, target at least 0, header with approval status; approver differs from requester; whether the live target stays in force while a new set is pending is unknown (MQ-39) (D-31, D-189) | Playwright | TL | PR | Y |
| T-6-05 | 6a | DAT | Masters with KPI effect are future-dated only: a past-dated price or target edit is refused or goes through approval with a blast-radius preview and an audit row (D-98) | G | TL | PR | Y |
| T-6-62 | 6a | CFG | Master-data pages: create, edit and soft-delete with audit under the permission bundles; the pilot subset (users, routes, assignments, outlets) AND the day-one tools F-ADM-005, 006, 036, 070 and 071 in minimal form are usable from 2e (D-590, T-2-172); back-date unlock grants and bulk operations are audited | Playwright | TL | PR | Y |
| T-6-63 | 6a | CFG | Sales Plan and wholesale marking write paths: the TSO acts inside own zones, audit per outlet, idempotent `batchUuid`, changes reach phones at the next bundle; an offline phone keeps the old plan and the server flags `sku_not_in_plan` instead of rejecting | Playwright; lab | TL | PR | Y |
| T-7-06 | 7a | DAT | Archived stubs: one per outlet code, never in target outlets, history and dues preserved, rolled up to the Unmapped member (D-25, D-252) | G | TL | PR | Y |
| T-7-109 | 7a | ANA | Importer: the normalisation functions, deterministic uuid, re-import is a no-op, `rollback_import`, control-total difference report, quarantine at most 1 % | G | TL | PR | Y |
| T-7-62 | 7c | CFG | Release console and waves: wave membership, `cfg.release.wave_pct`, `blocked_versions` and `min_version` set from the console with reasons and audit; a wave rollback flag flipped at wave scope from the console | Playwright; lab | OPS | PH | W1 |
| T-0-77 | 0c | SEC | Bump `scope_version` for a user; send a request with the old token; upload a batch of yesterday's rows for a route the user lost this morning. Pass: 401 `scope_changed`, refresh returns new claims; the as-of rows are accepted, rows outside the as-of reach are parked `scope_out_of_reach` and never dropped (G-cfg-12, G-21-04; minted by doc 21 s10.2) | G | SEC | PR | Y |
| T-0-78 | 0a | SEC | CI policy lint on every workflow: every third-party action pinned by SHA, no `pull_request_target` checkout, no stored cloud secret, least-privilege `permissions`, CODEOWNERS present for the sensitive paths, branch protection read through the API. Pass: Zero violations (G-sec-19; minted by doc 21 s10.2) | G | SEC | PR | Y |
| T-0-79 | 0c | SEC | Reset and OTP flows: temporary password shown once and expiring in 24 h, `must_change` enforced, victim device shows the reset notice, no self-service reset on the web, user id case-insensitive; OTP stored as AES-GCM ciphertext and absent from logs; lockout counters survive a Redis flush. Pass: All hold (G-feat-34, G-man-091, G-man-021; minted by doc 21 s10.2) | G | SEC | PR | Y |
| T-1-77 | 1c | SEC | UPDATE, DELETE and TRUNCATE on `app.audit_log`, `cfg.config_change_audit`, `app.security_event`, `app.report_export_log` fail for every role; chain verifies; a 1,000-row burst keeps the chain intact; refresh successes are not logged; failure deduplication holds. Pass: All hold; contention measured and recorded (G-sec-11, G-21-10; minted by doc 21 s10.2) | G | SEC | PR | Y |
| T-1-78 | 1c | SEC | Two users on one bound phone: after A logs out, the upload grant uploads A's rows while B works; the upload grant is refused on `/sync/bundle`, reads, bind and day events; A's full refresh token is unreadable without A's unlock; kill-and-relaunch mid-session resumes without the password; `logout scope=upload` after the last ack. Pass: All hold (G-fraud-19, OI-17-09; minted by doc 21 s10.2) | G | SEC | RC | Y |
| T-1-79 | 1b | SEC | Stop Redis in staging: `/sync/batch`, `/auth/refresh`, `/day/*` continue; `/admin/*`, a PII report and `/auth/bind-device` return 503; no sale is blocked on the device. Pass: All hold (D-491, G-21-13; minted by doc 21 s10.2) | G | SEC | PH | Y |
| T-2-76 | 2d | SEC | Radio environment: capture on Android 8, 10 and 12 reference devices with no extra permission; no scan started; battery within D-73; BSSIDs stored only as salted hashes; Rule A and B fire on an injected 8 km offset and do not fire across a rural cell with a learned 12 km radius; capture is off when `cfg.geo.radio_env_enabled` is false. Pass: All hold (G-fraud-01, D-478; minted by doc 21 s10.2) | G | SEC | RC | Y |
| T-2-77 | 2d | SEC | Confirmed outlet, force sale photo: request raised, call proceeds, provisional point used; the visit is not in the K-09 numerator; approval re-evaluates it; rejection leaves it not valid; placeholder-pin outlet sets the location at once. Pass: All hold (G-man-017, G-fraud-06, G-21-03; minted by doc 21 s10.2) | G | SEC | PR | Y |
| T-2-78 | 2e | SEC | PDA to Support bundle: ciphertext in Blob, no plaintext phone anywhere in storage; decrypt only through `/support/decrypt` with MFA and a ticket; every decrypt audited; an unknown key id refuses to send. Pass: All hold (G-sec-14, OI-17-11; minted by doc 21 s10.2) | G | SEC | RC | Y |
| T-2-79 | 2d | SEC | SR-surface lint: the SR bundle and responses carry no `risk_signal` and no FLAG-policy code; `cfg.sync.reason_texts` holds REJECT codes only; manifest permission allow-list holds (no `RECORD_AUDIO`, no `READ_PHONE_STATE`). Pass: Zero violations (G-fraud-26, G-feat-28; minted by doc 21 s10.2) | G | SEC | PR | Y |
| T-3-77 | 3a | SEC | New-outlet request with a matching `phone_hash`, a similar name within 100 m and a staff phone: duplicate badge shown on the AMO screen and the approval panel; approval blocked until "merge" or "not a duplicate" plus a note. Pass: All hold (G-sec-18; minted by doc 21 s10.2) | G | SEC | PR | Y |
| T-3-78 | 3b | SEC | OTP panel: view-only; a TSO sees only own-territory SRs; value shown only for open OTPs; one aggregated `otp_view` row per page view; 5 wrong attempts expire the OTP; the 10-per-day failed cap locks binding until a TSO clears it with an audit row. Pass: All hold (G-man-021, D-473; minted by doc 21 s10.2) | G | SEC | PR | Y |
| T-3-79 | 3b | SEC | Wave cohort: 1,000 binds with TSO-issued temporary passwords and OTPs across 35 TSOs are not held when the DMO has acknowledged the cohort; a user outside the cohort, a replacement of a bound device and a device previously bound to the actor are held. Pass: All hold (G-21-01, D-486; minted by doc 21 s10.2) | G | SEC | PH | Y |
| T-4-63 | 4c | CFG | Back-date window and unlock: an entry before the earliest date is refused with the banner; an active grant allows it once within its range and expiry; a grant over 7 days needs `finance_approver`; every use is logged (D-97, D-439; minted by doc 19 s12.5) | Playwright | TL | PR | Y |
| T-4-75 | 4c | SEC | Role-by-subject matrix of s4.2 through every serialiser: an AMO payload never contains an SR phone (not a masked string), a DMO list masks retailer phones, the TSO baseline shows NID and TIN columns with null for the placeholder, `master_data` reads NID decrypted and the read is logged. Pass: All hold (G-feat-59, G-man-039, G-man-062, G-sec-04; minted by doc 21 s10.2) | G | SEC | PR | Y |
| T-4-76 | 4c | SEC | Adding a PII column to the SR bundle (`cfg.bundle.outlet_fields`) is refused without the C3 approval and the `pii` mapping; the bundle field allow-list test passes. Pass: All hold (G-sec-04; minted by doc 21 s10.2) | G | SEC | PR | Y |
| T-4-77 | 4a | SEC | Planted stock and cash variances against a confirmed issue and a counted cash figure raise FS-17 and FS-32; an unconfirmed day ages on the report; a collection without a fix on a month-end memo raises FS-33. Pass: Recall 90 percent or more, false positives 5 percent or fewer per clean SR-day (G-fraud-07, D-481; minted by doc 21 s10.2) | G | SEC | N | Y |
| T-5-73 | 5c | SEC | Planted FS-30 (a memo repeated with a 1 s time shift) and FS-31 (a Platinum tier proposal for a low-sales outlet) scenarios. Pass: Recall 90 percent or more; the Astha tier is not effective until the TSO approves (G-fraud-05, G-fraud-23; minted by doc 21 s10.2) | G | SEC | N | Y |
| T-6-73 | 6b | SEC | A `config_approver` grant made 2 h ago approves a C3 change: refused (`ERR_CFG_COOLING`); an adjustment total above 200,000 Tk in a day needs the approver whatever its size. Pass: All hold (FS-29; minted by doc 21 s10.2) | G | SEC | PR | Y |
| T-7-76 | 7c | SEC | Evidence file check: counsel's written opinion on localisation, sensitivity of phone and owner, location monitoring and breach duty is on record; D-05, D-107, D-120 answered; the WORM policy locked after the retention answer. Pass: File present, decisions closed (G-sec-01, G-sec-22; minted by doc 21 s10.2) | G | SEC | PH | W1 |
| T-7-77 | 7a | SEC | Credential paths on 20 pilot users: the verifiable-hash path verifies then re-hashes and deletes the staging hash; the temporary-password path forces a change; a weak hash is never an active credential; usernames violating the memo-role regex are quarantined with a rename map. Pass: All hold (G-sec-03, D-119, D-488; minted by doc 21 s10.2) | G | SEC | PH | Y |

Proved by: all gates above.

### 3.12 Register counts (generated by `rtm-check`; counted from s3.3 to s3.11 on 2026-10-04)

| Phase | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | Total |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Gates | 51 | 74 | 109 | 51 | 64 | 19 | 17 | 55 | 440 |
| Human-signed (SD, NB, PL, FIN, SPN, SUP, RM) | 10 | 4 | 18 | 12 | 12 | 6 | 3 | 26 | 91 |

| Range | 01-09 | 10-19 | 20-39 | 40-49 | 50-59 | 60-69 | 70-79 | 80-89 | 90-99 | 100-109 | 120-139 | 140-199 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Gates | 36 | 16 | 50 | 49 | 35 | 40 | 61 | 10 | 18 | 20 | 37 | 68 |

Blocking mix: Y 394, W1 27, P 7, N 5, WV 7 (the 52 gates of s3.14 add Y 47, W1 3, WV 2). Ids 05 to 09 and 104 to 109 are the ones doc 16 minted (s3.11); an id minted later is added to `gates.yaml` with its owner and the build fails on a duplicate.

Round 3 (s3.15) adds 57 gate ids: Phase 0 +8, Phase 1 +6, Phase 2 +22, Phase 3 +4, Phase 4 +6, Phase 5 +1, Phase 6 +2, Phase 7 +8; the totals above are regenerated by `rtm-check` (rule 13) from `gates.yaml` and are not recounted by hand here. T-0-05 and T-0-09 moved from 0b to 1b and T-1-11 and T-1-12 from 1b to 2d without changing any count (D-558). Fourteen of the 57 are human-signed (SD, SPN, SUP, and SD with FIN), which raises the human load of Phase 0 by 4, Phase 2 by 4, Phase 4 by 1 and Phase 7 by 5.

### 3.13 Human sign-off load (G-20-05)

Human-signed gates number 91 (s3.12, after the 24 added in s3.14); the sponsor's delegate alone signs or co-signs about 70 of them. Phase 2 has 18 human gates and Phase 7 has 26. Each has a one-page script of 30 minutes or less, so Phase 2 costs about 7 person-hours of signing plus the demos (ASSUMPTION: 30 minutes per gate). The risk is availability, not volume: D-156 names a deputy per phase, and a gate whose verifier is unavailable for 5 working days is escalated to the sponsor, never self-signed.

Proved by: T-0-46, T-0-142.

### 3.14 Round-2 gate supplement: ids minted by the skeptic gap resolution (D-500 to D-553)

Fifty-two gates minted in the free numbers 150 to 199 of the D20 range (T-x-150 and above; a phase digit can repeat a number because the id is the pair, s3.1). Columns as in s3.11. Gates whose text changed in place (T-0-06, T-0-07, T-0-46, T-0-49, T-0-100, T-0-120, T-0-141, T-1-53, T-1-56, T-1-63, T-2-10, T-2-47, T-2-61, T-2-65, T-2-68, T-2-140, T-3-26, T-3-41, T-3-43, T-3-61, T-3-71, T-4-54, T-5-43, T-6-42, T-7-52, T-7-54, T-7-85, T-7-88) are listed by the decision in their text, not repeated here. The T-5-43 chain test is aliased: its first proof is T-3-155 (D-532).

| ID | Ph | Lens | Check | Artefact | Verifier | Run | Blk |
| --- | --- | --- | --- | --- | --- | --- | --- |
| T-0-150 | 0b | DAT | Capture-to-dw coverage lint (rtm-check rule 9, D-500): over the migrated DDL AND the full record-type enum of doc 17 s2.3 (D-559: `device_integrity`, `activity_log`, `consent_accept` and `config_ack` are record types, not only tables of doc 16 s5.1) every OFFLINE or QUEUED app capture table and record type is MAPPED to a dw object named in `/plan/dw-landing.yaml` (the object itself lands from 1c to 5a, the ratchet of T-0-100 checks that) or has a signed exclusion row EX-nn; REPORT-ONLY at the 0b exit, BLOCKING from the 1b exit (D-558); every device-captured column maps to a dw column or matches the technical-column pattern EX-06; an unsigned exclusion, a mapped column that does not exist and a new column without a map row each fail the build | `/plan/capture-map.yaml`; lint output | TL | PR | Y |
| T-0-151 | 0b | DAT | jsonb lint (D-501; report-only at 0b, blocking from 1b, D-558): any jsonb column in the `app` or `dw` schema outside the sanctioned list J-1 to J-8 of doc 16 s8.10 fails the build; the sponsor's signature on the list is recorded | lint output; signed list | SD | PR | Y |
| T-0-152 | 0a | QUA | rtm-check rules 10 to 13 of s8.2 (D-513, D-531, D-533, D-516): every gate and every demo step is at or after the sub-milestone of every F-id and named screen it exercises; every manual screen, message, rule and entity row is mapped in `/plan/manual-coverage.yaml`; a feature's phase is at or after the phase of every API it lists; the counts of doc 14 s1.1 and s3 equal doc 15 s1.3. The rules are run over docs 14 and 20 now, the findings list is COMMITTED at `/plan/findings-r3.md` and the findings fixed before 0a exits (rule 10 found, in the round-3 pass: T-0-100 at 0b needing 1c to 5a objects, T-1-27, T-1-10 and T-1-76 at 1a needing 1b and 1c server logic, T-1-11 and T-1-12 at 1b before F-SYS-072 and F-SYS-031, T-4-160 against the pilot, and doc 18 s8.3 against doc 20; all re-placed, D-558) | rtm-check output | SD | PR | Y |
| T-0-153 | 0c | QUA | Parity Exceptions Register and usage census (D-502, D-503): doc 15 s6.4 lists every live-but-not-built, hidden or flagged-off Apsis function with an owner, the sponsor's signature and a date; the census of baseline-pack item i exists; the answers to the D-42 existence question, Q17, Q15, MQ-48 and D-342 are recorded | register; census table | SPN | PH | Y |
| T-0-154 | 0a | QUA | Evidence files committed (D-530): the four manual inventories, the delta register and the six V-* verification files are in `/docs/evidence/manuals/` and `/docs/evidence/verification/`, read-only, named in the DECISIONS.md header and in the doc 14 s3.0 Entry list; T-0-46 and T-0-120 read them | repository listing | SD | PR | Y |
| T-0-155 | 0a | QUA | Calendar lead time (D-515, D-529): `gates.yaml` carries `lead_time_h` and `depends_on`; the calendar critical path per sub-milestone is computed (24 h and 72 h soaks, 8-hour battery runs, the OEM matrix, the dogfood week, the dress rehearsal, the `/cso` audit) and equals the weeks of the doc 14 s2.1 table, and s2.3 is derived from it | computed path | TL | PR | Y |
| T-1-150 | 1c | DAT | Row widths measured on the 1c synthetic load (`pg_total_relation_size` per row of every table); the table of doc 16 s8.11 and the sizing of doc 18 s1.9 are rewritten from the numbers (D-520) | measured table | OPS | PH | Y |
| T-1-151 | 1b | SRE | Version-wide poison row (S11, D-521): every batch of 8,500 simulated devices carries one poison record for 30 minutes; savepoints per transaction at most 60; `pg_stat_slru` subtransaction counters and subxact overflow flat; ack p95 inside its SLO; every good row accepted | Azure Load Testing; pg metrics | OPS | PH | Y |
| T-1-152 | 1b | SYN | Digest check (D-517): after a restore that drops one acked uuid per type, `POST /sync/digest` names the differing bucket and a targeted re-send repairs it; a clean device reports a clean digest and its purge hold lifts | G | TL | PR | Y |
| T-2-150 | 2e | DAT | Row widths re-measured on the 2e release candidate; the storage table, the alert thresholds (60 percent, projected-full within 90 days) and the first-grow month are recomputed (D-520) | measured table | OPS | PH | Y |
| T-2-151 | 2e | FLD | Supervised paper-memo backfill (D-543, F-ADM-075): support keys 5 printed credit memos of a dead phone, the zone TSO approves, dues and outlet STD are restored at memo level, a double keying creates nothing twice, a later upload of the same `memo_no` leaves the device row and marks the manual row superseded (DQ-70), the same person cannot enter and approve | walk-through; G | SD | PH | Y |
| T-2-153 | 2e | SYN | Field telemetry (D-507): the daily telemetry object stays at most 1 KB, carries app-uid mobile and Wi-Fi bytes, CPU ms, wake-lock ms, engine starts and the 08:00, 12:00 and 17:00 battery levels; `fact_device_day` is populated and SH-19 to SH-24 compute on a 200-device simulated fleet | G | OPS | PR | Y |
| T-2-154 | 2e | SYN | Immediacy SLO computation (D-509): from `sync_latency_s` and `connectivity_regained_at` the 60 s, 5 min and 3 min shares are computed for a scripted fleet and equal the scripted truth; the check-out jitter is excluded from the clock | G | OPS | PR | Y |
| T-2-155 | 2e | SCA | S4 smoke at about 2,000 rows/s on the 2e release candidate (D-504): catch-up ack p95 within 8 s, 0 lost rows, ingest-registry read IOPS inside the 60 percent bound; a failure here blocks the Drift schema freeze | Azure Load Testing | OPS | PH | Y |
| T-2-156 | 2e | QUA | R4 at config extremes (D-546, doc 19 s2.6): the doc 17 s8.8 protocol is run with each budget-linked key at its ceiling; a ceiling that breaks a gate fails the registry; the budget-impact line shown in the preview equals the measurement | `/docs/perf/ceilings-<v>.md` | QA | RC | Y |
| T-2-157 | 2e | CFG | Support desk P19 (D-541): a support code decodes to the right error, build, config version and device CRC; search by username, phone, employee code, memo number, outlet code and batch uuid finds the device and the last 50 requests for an L1 persona in under 2 minutes; every search is audited and PII is masked | G; timed run | SUP | PH | Y |
| T-2-158 | 2e | CFG | Persona authority (D-540): every script of s7.8 is run with the persona that owns it (L1 can reset a temporary password with TSO confirmation or from the pre-approved wave-day list, D-593); an action the persona lacks in doc 19 s5.1b fails; a TSO confirmation turns an L1 proposal into an applied OTP, submit void or assignment | G | SUP | PH | Y |
| T-2-159 | 2e | CFG | User wizard and maker-checker (D-551): a TSO creates a user in own territory and cannot apply without a DMO or master_data checker; the checker is never the maker; a disabled user's phone uploads its earlier rows as `parked_user_disabled`; bulk mark absent works for 60 routes and refuses 61 | G | SD | PH | Y |
| T-2-160 | 2d | FRD | Emergency lanes (D-527): the emergency-widen lane inside a freeze, its auto-expiry after 6 h, a refused 300 m request in the lane, temporary relief with two approvers, and an audited hand-over between two break-glass holders | G | SEC | PR | Y |
| T-3-150 | 3b | CFG | Submit void (D-539, F-SR-080, F-API-069): a TSO voids a route-day's Sales Submit at 10:00, the phone unlocks capture on its next response, sells, and submits again under a new uuid with `submit_seq` 2; the voided uuid replays as `voided`; K-02 and the day state fall back and recover; refused after Final Submit until the zone is reopened | patrol; G | SD | PH | Y |
| T-3-151 | 3a | QUA | AMO flavour budgets (D-506, doc 17 s8.2b): the scripted AMO day on the primary device; drain, whole-device level, GPS, mobile data without photos and tiles (2 MB), wake locks and cold start inside the s8.2b gates; blocking from 3a exit; the first RC sets the baseline x 1.2 | `/docs/perf/battery-<v>.md` AMO section | QA | RC | Y |
| T-3-152 | 3b | QUA | TSO flavour budgets (D-506, doc 17 s8.2b): the scripted TSO day on the primary device within the s8.2b gates (1.5 MB data, 4 percent drain); blocking from 3b exit | perf file TSO section | QA | RC | Y |
| T-3-153 | 3a | QUA | APK per ABI for each flavour with the Maps SDK at most 30 MB, installed at most 70 MB, cold start at most 2.5 s (D-506, D-508); the SR flavour has no maps SDK; growth above 5 percent warns and above 15 percent fails | CI size report | TL | PR | Y |
| T-3-154 | 3b | SEC | Disabled-user upload (D-551): after `user_disabled` the phone stops capturing, keeps the upload grant for `cfg.user.dismissal_upload_grace_h`, uploads its earlier rows as `parked_user_disabled`, and the TSO accepts them; nothing is counted or lost silently | G | SEC | PR | Y |
| T-3-155 | 3b | QUA | Task and leave chains end to end, whole in 3b (D-532): AMO assign, TSO assign, SR list and resolve offline, F-API-026 and the task events; leave apply on the TSO phone, DMO approval on the web, F-API-022; a TSO in the 3b demo creates a task the SR can see and resolve | report | SD | PH | Y |
| T-3-156 | 3a | QUA | The Exceptions list on the AMO app (F-AMO-038): the mock flag and teleport of T-2-47 appear on the AMO's own list within aggregation lag | report | SD | PH | Y |
| T-4-150 | 4b | QUA | R1 acceptance drill (D-500): an analyst delivers THREE UNPLANNED reports chosen on the day (for example POSM compliance by wing, QC fault mix by brand, TSO visit-plan completion) from `dw` only, with no change to ingest, the worker or the migrations beyond a view; the web and BI roles hold no SELECT on `app` | three report outputs; role grant snapshot | SD | PH | Y |
| T-4-151 | 4b | ANA | Capture-type analyst questions Q26 to Q41 of doc 16 s15 are answered by SQL under the `bi_reader` role (no SELECT on `app`); a question that needs `app` fails | query results | TL | PR | Y |
| T-4-152 | 4a | SCA | Location-request volume (D-545): 19,600 requests a day loaded into the queue model; one open request per outlet; the 72 h escalation and 14-day lapse clocks; the bulk approve of a consistent 3-visit group; the AMO queue length measured | G | OPS | PH | Y |
| T-4-153 | 4a | QUA | Switched-route scope (D-548): with 3.4 percent of routes on Aron the default KPIs divide by switched routes only, the coverage banner reads the true share, the all-routes view adds the `apsis_parallel` feed with the label, and the Sev1 login alert does not fire for unswitched routes | report | SD | PH | Y |
| T-4-154 | 4a | QUA | Emergency non-working day (D-542): a zone declaration at 08:00 sets `planned` false for route-days not yet logged in, keeps logged-in routes counting, suppresses SH-01 and Sev1 for the scope, reaches a phone by delta and is audited; a retroactive declaration needs a second approver | G; report | SD | PH | Y |
| T-4-155 | 4d | SCA | S3b, the 17:00:00 spike (D-505): 5,100 users submit within 60 s with the S5 readers, once without and once with the 0 to 90 s client jitter; ack p95 at most 8 s without and 3 s with the jitter, 503 at most 5 percent, 0 lost rows, every settle correct, dashboard p95 at most 1 s | Azure Load Testing | OPS | PH | Y |
| T-4-156 | 4d | SCA | S2 trickle at the fleet shape (D-504): 5,100 users, 5 percent duplicates, 2 percent children before parents, 1 percent regenerated uuids; ack p95 at most 1.5 s, server rows equal distinct `client_uuid`s, aggregation lag p95 at most 60 s | Azure Load Testing | OPS | PH | Y |
| T-4-157 | 4d | SCA | S4 including the ramp to 8,000 rows/s and the 1.5 x fleet of 12,750 users (D-504): CPU at most 80 percent, data IOPS at most 60 percent of provisioned, ack p95 at most 8 s at 3,333 rows/s and 12 s at 8,000, 0 lost rows, `cl_waiting` 0, ingest-registry read IOPS inside the bound (G-18-01) | Azure Load Testing | OPS | PH | Y |
| T-4-158 | 4d | SCA | S5 under S4: 1,500 dashboard readers at 60 s and 50 exports during the worst-case catch-up; dashboard p95 at most 1 s, sync-health p95 at most 1.5 s, replica lag at most 30 s | Azure Load Testing | OPS | PH | Y |
| T-4-159 | 4d | SCA | S6 photo burst: 5 SAS calls and 50 PUT per second for 10 minutes; 0 API bytes proxied; SAS p95 at most 300 ms; Storage 503 count 0 | Azure Load Testing | OPS | PH | Y |
| T-4-160 | 4d | SCA | S8 soak of 10 hours at the S2 shape at PRODUCTION size (the repeat of the 2e soak T-2-176; the pilot's entry is T-2-176, not this gate, because 4d ends at week 31 and the pilot starts at week 26, D-558): memory growth at most 10 percent an hour, the outbox drains, connection counts flat, dead-tuple ratio below 20 percent, subtransaction counters flat | Azure Load Testing | OPS | PH | Y |
| T-4-161 | 4d | SCA | S9 config propagation measured on ALL active devices of the scope with the tail metric (D-526): 95 percent within 2 trickle cycles, deltas spread over at least 100 s (ordinary) and at most 20 s (urgent) | Azure Load Testing | OPS | PH | Y |
| T-4-162 | 4d | SCA | S10 with the absolute-expiry herd (D-518): 4,250 families expire on one simulated day; login p95 at most 3 s, `auth` memory below 80 percent, no lockout, offline unlock still works for 7 days after expiry; the per-family jitter spreads a cohort over 30 days | Azure Load Testing | OPS | PH | Y |
| T-4-163 | 4d | SCA | S11 version-wide poison row at fleet size with the S3 shape (D-521): see T-1-151 at 8,500 devices with the production SKU | Azure Load Testing | OPS | PH | Y |
| T-4-164 | 4d | SCA | DR replica lag during S4, S8 and S3b (D-522): the cross-region replica replays within 5 minutes at the end of each run; WAL MB/s against replay MB/s recorded | Azure Load Testing; pg metrics | OPS | PH | Y |
| T-4-165 | 4c | ANA | Self-service BI (D-500, scheduled at 4c): an analyst opens Power BI on the replica with `bi_reader`, builds a report from `dw` with no engineer, refresh windows exclude 17:30 to 21:00 Dhaka, row-level security follows `bi_user_scope`; T-4-108 proves the grants | analyst walk-through | SD | PH | Y |
| T-7-150 | 7b | PIL | Delta rehearsal on the pilot scope (D-514): the delta contract files for the pilot routes arrive by 20:00 (Apsis or the staff fallback), import and reconcile with zero difference on counts, dues and points; the rehearsal INJECTS stragglers: an Apsis upload at 21:30 on T-1 and another at 07:00 on T, and asserts that both reach the phone through the DL-1b late delta before the first sale or sit on the straggler sheet with a named owner (D-556, T-7-163) | import_reconciliation report | SD with FIN | PH | Y |
| T-7-151 | 7c | PIL | Delta rehearsal for the wave-1 scope one week before T-1 (D-514): a second successful run of the whole delta path including the injected stragglers of T-7-150 and the DL-1b run at 06:00; a 7a and 7c entry condition | import_reconciliation report | SD with FIN | PH | W1 |
| T-7-152 | 7c | PIL | Nightly Apsis route-day feed for unswitched routes (DL-6, D-548): loaded for 3 nights as `apsis_parallel`, national totals complete in the all-routes view, the coverage banner true | report | SD | PH | W1 |
| T-7-153 | 7b | PIL | Rollback return-path drill (D-549): roll a pilot wave back on staging after 2 days of data; retailer dues in the Apsis-shaped export equal Aron to the paisa; the re-key procedure is staffed and timed and the keyer THROUGHPUT is measured (memos and collections per hour per trained keyer), from which the re-key capacity of each wave is computed against 2 staff per 100 routes; the per-wave statement of doc 18 s7.5 (rollback possible, fix-forward only, or split) is written and signed (T-7-168); from day 4 the procedure says fix-forward only | drill log | SPN | PH | Y |
| T-7-154 | 7b | PIL | Pilot contact log (D-553): every contact is logged by category (SR, AMO, TSO, retailer dispute) with the real L1 tooling; the measured rate replaces the 3 to 5 percent assumption and wave 1 is staffed for the measured rate and a 15 to 25 percent day-1 stress case | contact log | SUP | PH | Y |
| T-7-155 | 7c | SRE | Auto-freeze drill (D-507): a synthetic canary regression of 25 percent on CPU or battery drop freezes `wave_pct` and raises a Sev2; the slider is disabled; only a named release_mgr clears it with a reason; the remote brakes switch off prefetch, telemetry and radio_env for a cohort and take effect on the next pull | drill log | OPS | PH | W1 |
| T-7-156 | 7c | PIL | Field R4 go/no-go (D-507): SH-19 to SH-21 over the previous wave's 5 trading days are inside the thresholds of s7.7 and are recorded in the go/no-go sheet | go/no-go sheet | SPN | PH | WV |
| T-7-157 | 7c | PIL | Immediacy go/no-go (D-509): SH-22 and SH-23 over the previous wave's 5 trading days are inside the thresholds of s7.7 and the pilot sheet line 6 | go/no-go sheet | SPN | PH | WV |
| T-7-158 | 7b | PIL | 7b entry condition (D-502): no unsigned item in the Parity Exceptions Register and no Apsis function in the usage census without a replacement or a signed retirement; the census evidence class may be (1) Apsis admin or audit logs, or, if Apsis keeps none (D-565), (2) structured interviews with SIGNED attestations by the function owners plus a 4-week observation log from the pilot zones; mirrored in T-7-88 | register; census | SPN | PH | Y |
| T-7-159 | 7b | PIL | Pilot extension rule (D-529): resets by category C are counted; at most 2 resets and an extension of at most 4 weeks, the sponsor's delegate decides; a third reset or a 5-week overrun triggers a re-plan by the sponsor | pilot tally | SD | PH | Y |

The sub-milestone of each gate is at or after the sub-milestone of every feature it exercises (rtm-check rule 10, D-513): T-3-156 is 3a because F-AMO-038 first works in 3a; T-3-41 uses the minimal F-WEB-032 of 3a; T-3-43 uses the minimal sync-health of 3b; T-2-47 and T-2-10 use the minimal Exceptions list of 2d.

### 3.15 Round-3 gate supplement: ids minted by the third skeptic pass (D-554 to D-601)

Fifty-seven gates minted in the free numbers of each phase (the extensions of existing gates are listed by decision below): T-0-156 to T-0-163, T-1-153 to T-1-158, T-2-131 to T-2-138 (manual-parity range) and T-2-161 to T-2-176, T-3-157 to T-3-160, T-4-166 to T-4-174, T-5-123, T-6-150 and T-6-152, T-7-160 to T-7-168. Columns as in s3.11. Gates whose text changed in place (T-0-100, T-0-150, T-0-151, T-0-152, T-1-10, T-1-27, T-1-56, T-1-61, T-1-76, T-2-20, T-2-61, T-2-158, T-4-160, T-6-10, T-6-41, T-6-62, T-7-81, T-7-85, T-7-88, T-7-144, T-7-150, T-7-151, T-7-153, T-7-158) are listed by the decision in their text. T-1-11 and T-1-12 moved to 2d; T-0-05 and T-0-09 moved to 1b (alias record: the ids are kept, the placement changed, D-558).

| ID | Ph | Lens | Check | Artefact | Verifier | Run | Blk |
| --- | --- | --- | --- | --- | --- | --- | --- |
| T-0-156 | 0b | DAT | dw inventory (D-558): `/plan/dw-landing.yaml` is generated from the "Lands" columns of doc 16 s2.3, s8.2 and s8.9.1 and equals the document; every one of the 65 objects has a landing sub-milestone; the objects landing at 0b (`dim_date`, the operations tables) exist | generated file; diff | TL | PR | Y |
| T-0-157 | 0b | CFG | Dependency rules at the database (D-591, doc 19 s2.6b): each of the 14 rules rejects a violating write that bypasses the API, including `ingest_registry_days` 30 with `max_backdate_days` 30 and `config_accept_window_h` 24 with `stale_max_days` 3; the edit drawer lists them | G | TL | PR | Y |
| T-0-158 | 0c | SEC | ROLE MAP and POSITIVE privilege matrix (D-566, doc 16 s13.4b): the GRANT script is generated from `app.role_grant_map` and `app.immutability_policy`; for every row of the map and every capture table `has_table_privilege`, `has_column_privilege`, `has_function_privilege`, `has_schema_privilege` and `pg_has_role` equal the expected value (a MISSING privilege fails as well as an EXTRA one); no login is a superuser or holds BYPASSRLS beyond `app_worker`, `app_jobs` and `app_migrator`; docs 18, 20 and 21 define no role of their own | `/plan/roles.yaml`; test output | SEC | PR | Y |
| T-0-159 | 0a | QUA | Constants registry and drift (D-576, rtm-check rules 13 and 14): `/plan/constants.yaml` holds each repeated value once (first-grow month 6, full-fleet storage 1,024 GiB, Premium Front Door from the pilot, role names, `cfg.geo.country_bbox`, reach bounds); a planted conflicting value in any of docs 14 to 21 or DECISIONS.md fails; the generated week counts, float and F-id counts equal the text; one storage figure in docs 14, 16, 18 and DECISIONS.md | rtm-check output | SD | PR | Y |
| T-0-160 | 0a | QUA | Plan wired into the repo (D-554, G-qa-86): CLAUDE.md and the README carry the precedence text of doc 14 s9.2b and name docs 14 to 21, DECISIONS.md and the money convention of D-15; docs 03, 06 to 10 and `db/schema.sql` carry the superseded-by banner; `/docs/evidence/` and the `/plan/*.yaml` files exist and are committed; the DECISIONS.md header sentence is true; docs 14 to 21 are committed | repository listing; commit | SD | PH | Y |
| T-0-161 | 0a | QUA | `/plan/manual-coverage.yaml` is GENERATED by script from the four inventories (D-554): 192 screens, 416 rules, 115 entity rows and 225 messages, each with an F-id, a cfg key or `table.column`, or a signed "not built, because X"; a row with none fails | generated file; generator log | SD | PR | Y |
| T-0-162 | 0a | QUA | The Board envelope (D-555): the MUST-CONFIRM rows of doc 14 s4.2c are answered or carry their proceed-with default: Apsis contract end and fees, the programme cost envelope with a cost per week of delay, the confirmed headcount with the half-team scenario, the latest wave-1 date the Board accepts | signed rows | SPN | PH | Y |
| T-0-163 | 0a | QUA | The F-id to T-id table (D-581): `rtm.yaml` carries a `covers` field per feature; the generated table of doc 15 s12.6 lists one or more NAMED gates per feature with a one-line assertion; rtm-check rule 15 fails a feature whose cited gate names none of its behaviours and a gate named by more than 12 features | generated table; rtm-check output | QA | PR | Y |
| T-1-153 | 1b | SEC | The Phase 1 slice under the REAL runtime logins through PgBouncer in transaction mode, in CI and on staging (D-566): `/sync/batch` with its enqueue, the settle timer, the dw worker, the partition and archive jobs, auth refresh and an export; the gate fails if `session_user` is a superuser or holds BYPASSRLS beyond the documented logins | G | SEC | PR | Y |
| T-1-154 | 1b | DAT | Enqueue under `api_rw` (D-566, G-qa-100): a batch under `app_api` leaves a dirty row, the worker claims it under `app_worker`, `dw.enqueue` is SECURITY DEFINER with a pinned search_path and no EXECUTE for PUBLIC; the worker UPDATE grants equal `app.immutability_policy` for `visit.sig_status` and `location_request_id` | G | TL | PR | Y |
| T-1-155 | 1c | DAT | Route-day writers (D-567, G-qa-102): a bundle-only login with NO batch lights the Login % tile within 60 s; a coverage test that every endpoint and trigger that writes `route_day`, the web-entry tables or `data_void` produces a dirty row; the settle timer enqueues | G | TL | PR | Y |
| T-1-156 | 1b | SYN | Re-sync after a PITR with old rows (D-572, G-qa-108): acked rows older than the 7-day window re-sent by a phone that stayed offline 4 days after the flip; zero rows quarantined, all accepted and flagged `resync_late`; a row beyond `cfg.sync.resync_late_max_days` is quarantined | G | TL | PR | Y |
| T-1-157 | 1b | SYN | Server half of the time gates (D-558): rows from a clock set back 2 days or +/- 1 day land on the real `business_date` with `clock_skew_flag`, the clock signal is raised and a month-closed claim is parked (DQ-40); the device halves are T-1-10, T-1-27 and T-1-76 at 1a | G | SEC | PR | Y |
| T-1-158 | 1c | DAT | Re-aggregation of the right date (D-558): a 23:55 sale synced at 00:10 re-aggregates only its own business date and the result equals a from-scratch recompute | G | TL | PR | Y |
| T-2-131 | 2a | MAN | AV and KV (F-SR-020): AV pre-downloaded on Wi-Fi plays in the order AV, KV, survey, sale; each view writes a `content_view` event offline; a missing item is skipped and never blocks the sale (D-554, G-qa-117) | lab report | QA | RC | Y |
| T-2-132 | 2a | MAN | POSM survey (F-SR-021): Q1 required, Q1.1 photo only when Q1 is yes, the confirm dialog, +50 points posted once by the server even when the upload is replayed, none for an AMO survey (D-41) | lab report | QA | RC | Y |
| T-2-133 | 2a | MAN | Visit outcomes and hidden tiles (F-SR-057, F-SR-067, F-SR-068, F-SR-075): every outcome code writes its record, a skip needs no fix and no geo gate, three closed outcomes raise the AMO task, the Sales Journey and KPI tiles are hidden by default, eligibility dots follow the bundle flags (D-38, D-342) | lab report | QA | RC | Y |
| T-2-134 | 2e | MAN | Tutorial (F-SR-048, F-API-027): the list from the backend, the empty-state string, the list cached, playback online only, no background download | lab report | QA | RC | Y |
| T-2-135 | 2c | MAN | Outlet form validation (F-SR-037 to F-SR-039): the 11-digit mobile rule against the stored vectors of `cfg.outlet.mobile_regex_test_vectors`; closure refused while dues are open when `cfg.outlet.close_block_if_dues` is true; the confirm dialog of closure and its absence in new and info change | lab report | QA | RC | Y |
| T-2-136 | 2a | MAN | Home strip, money cards, SKU Target and Achievement, Summary and current stock tracker (F-SR-010, F-SR-069, F-SR-056, F-SR-036, F-SR-050) equal the dw KPI definitions on the golden day | G | TL | PR | Y |
| T-2-137 | 2a | MAN | Start-call prompt, route picker for an SR with several routes, multi-visit policy and visit kind, and the suggestion hook delivered empty and off (F-SR-060, F-SR-065, F-SYS-078, F-SYS-035) | lab report | QA | RC | Y |
| T-2-138 | 2a | MAN | Definitions published by the business (F-ADM-016, F-ADM-020, F-ADM-061, F-API-035a): a promotion, survey, rubric or content item edited in P8 reaches a phone on the next delta with bn and en text and the simulator equals the engine on the fixture | G | TL | PR | Y |
| T-2-161 | 2a | SYN | Stock re-save (D-580, F-SR-014, F-SR-081): the screen shows the loaded total read-only; a Save posts the increment as a new movement; a same-values re-save inside `cfg.stock.resave_guard_window_min` and a double tap post nothing; "correct total" posts a signed `adjustment`; stock, the issue KPI, the Summary return quantity and the stock warning are not doubled | G | TL | PR | Y |
| T-2-162 | 2d | FRD | Stamp regress and the no-grace tightening (D-571, doc 19 s2.3b) as in doc 19 s12.5 | G | SEC | PR | Y |
| T-2-163 | 2e | SYN | First morning after a break (D-584): a 5-day and a 9-day gap with the last working evening pre-fetching the first working day after the break; an offline rep starts on that snapshot, rows left unsynced on the last working day are not quarantined, `calendar.break_overrides` is honoured, and `ingest_registry_days` cannot be set below the rule | lab report | QA | RC | Y |
| T-2-164 | 2e | SEC | APK install and update (D-562, RK-29): the signed APK installs and updates on current Android 14, 15 and 16 devices with Play Protect on, through the Bangla guidance flow, on the channel decided at 0c | lab report | QA | RC | Y |
| T-2-165 | 2d | CFG | Radius widened while an SR stands out of range (D-563): the console widens the radius, the SR taps Refresh GPS, the resume check applies the new value and the visit is geo-valid with the new `radius_m_used`; offline the visit stays out of range | lab report | QA | RC | Y |
| T-2-166 | 2e | CFG | Price rails (D-589, doc 19 s8.2b): a 79.35 typed for 7.935 is flagged red and needs `finance_approver`; above 100 percent is refused; a same-day correction by two roles takes effect at once and flags the memos made at the wrong price without rewriting them; a break-glass restore of the previous `list_version` works at 08:00 | G | SEC | PR | Y |
| T-2-167 | 2e | CFG | Replace-device wizard and held binds (D-586, D-585): a replacement with a pre-approving checker binds without being held; an unapproved one sits in the queue with its age, is released in one click by a delegate after 15 minutes, and the alert fires at 45; the displaced phone's pending rows arrive as `revoked_device` | G | SD | PH | Y |
| T-2-168 | 2e | CFG | Replay console (D-587): dry run, second approval, apply; applying twice equals once; `replay_excess` is flagged and never applied; over-window rows need the third approver; support cannot approve its own replay | G | SD | PH | Y |
| T-2-169 | 2e | CFG | Device directive (D-594): a directive queued for a silent phone is delivered in the next response or a 401 body, acted on at the next foreground contact, acknowledged, and expires unused after `cfg.support.directive_ttl_h` | G | TL | PR | Y |
| T-2-170 | 2e | CFG | Ticket store (D-599): the SLA timers of 15 minutes and 2 hours, the contact log by category and the daily known-issue board work for an L1 persona | walk-through | SUP | PH | Y |
| T-2-172 | 2e | CFG | Day-one tools (D-590): the L2 and TSO personas use the minimal F-ADM-005, 006, 036, 070 and 071 in 2e (a price revision, a disputed due, a reactivated outlet, an SR transfer, a pilot zone's sales plan) with no engineer | walk-through | SD | PH | Y |
| T-2-173 | 2d | FRD | Brake lane (D-588, doc 19 s7.6c): at 08:00 inside the freeze a holder adds a build to `blocked_versions` and switches off the promo engine; both apply at once and are reviewed in 24 h; a loosening is still refused | G | SEC | PR | Y |
| T-2-174 | 2d | CFG | Reach classes and the silent phone (D-563, doc 19 s4.1b): as in doc 19 s12.5 | lab report | QA | RC | Y |
| T-2-176 | 2e | SCA | 10-hour soak on the 2e release candidate at the S2 shape on staging (D-558): memory growth at most 10 percent an hour, the outbox drains, connection counts flat; the pilot's entry gate (the production-size repeat is T-4-160) | Azure Load Testing | OPS | PH | Y |
| T-3-157 | 3b | SEC | Policy unbind (D-585): a third device is bound while the oldest holds 20 unsent rows; all 20 arrive as `revoked_device` and the bind screen showed the count | G | SEC | PR | Y |
| T-3-158 | 3b | DAT | Submit void and the tile (D-567): a TSO voids a route-day's Sales Submit and Submit % changes with no further batch | G | TL | PR | Y |
| T-3-159 | 3b | SYN | Late row for a final-submitted zone-day (D-579): the row is ACCEPTED, aggregated and flagged `after_final_submit`, appears in the Final Submit Log, and no `day_closed` code exists in the contract | G | TL | PR | Y |
| T-3-160 | 3a | MAN | The AMO stock screen (F-AMO-029, F-AMO-049): the same re-save and correct-total rule as T-2-161 | lab report | QA | RC | Y |
| T-4-166 | 4c | DAT | Route-day void barrier (D-577): a phone holding 12 unsent rows of the route-day when a TSO voids it uploads them and all 12 are rejected `voided_by_admin` and aggregated nowhere; rows captured after `voided_at` are accepted; the void modal showed the pending-row count | G | TL | PR | Y |
| T-4-167 | 4c | ANA | DSS includes web entry (D-578): a Web Entry save for a route-day appears in the DSS totals and drill-down within 60 s and DSS equals the Web Entry totals for it; the "data as of" stamp shows and the staleness alert fires at 120 s | G | TL | PR | Y |
| T-4-168 | 4b | ANA | The analyst questions Q42 to Q44 of doc 16 s15 (devices with low trust by model, screens used, consent by policy version) are answered under `bi_reader` with no SELECT on `app` | query results | TL | PR | Y |
| T-4-169 | 4c | ANA | Lake query (D-560): a 3-year range is answered through the Fabric lakehouse for the archived part and the replica for the hot part, with the same schema; the BI path (VNet data gateway) is exercised from Power BI | analyst walk-through | SD | PH | Y |
| T-4-173 | 4d | SCA | Key Vault outage for 90 minutes during S2 (D-574, S12): minting, refresh and uploads continue; a restarted replica leaves the pool and verification still works; the first failed `sign` alerts | Azure Load Testing | OPS | PH | Y |
| T-4-174 | 4d | SRE | Timed geo-restore into East Asia (D-569, S13): every step timed including disk hydration; the measured RTO is written into RK-05 and the day-one checklist; the sponsor accepts the wave-1 DR position in writing | drill log | OPS | PH | Y |
| T-5-123 | 5a | MAN | Gift photo Submit (D-582): Submit needs every capture slot; "Photo already captured." hides Submit; a partial campaign submit, if MQ-28 allows it, flags the missing slot and raises a task | lab report | QA | RC | Y |
| T-6-150 | 6a | CFG | Revert a bulk batch (D-595): apply 5,000 assignment changes, revert the batch, assert the affected columns equal the starting state; a wholesale mark can be reverted; rows edited since are listed and skipped | G | TL | PR | Y |
| T-6-152 | 6c | SEC | Retention hold (D-600): a hold on a user and a date range makes the archive and drop jobs skip the covering partitions and blobs and report them on P16; lifting it releases them; every step is audited | G | SEC | PR | Y |
| T-7-160 | 7a | SEC | Residency, PII and notice (D-570): counsel's answers to the 7a rows of doc 21 s4.6 are on file, or the import is proved pseudonymised or synthetic by a PII scan and 7b is blocked; the pilot consent screen shows the D-120 text | file; PII scan | SEC | PH | Y |
| T-7-161 | 7c | SRE | Wave-night timeline replay (D-557, S14): the J1 to J2 to J6 to J1b chain with J1 finishing at 22:55; J2 finishes by 23:30; the go or no-go holds at 23:30; the first-morning delta p95 is under 2 s | timeline log | OPS | PH | W1 |
| T-7-162 | 7b | SEC | Front Door Premium (D-573): the managed rules in Log mode through the pilot on the real payload corpus, no false positive left in enforcement on `/sync/batch`; a direct request to the Container Apps origin is refused (Private Link, or the service tag and header fallback) | log review; test | SEC | PH | Y |
| T-7-163 | 7b | PIL | Stragglers and the late delta (D-556, doc 16 s12.6): an Apsis upload at 21:30 on T-1 and one at 07:00 on T reach the phone through DL-1b before the first sale or sit on the straggler sheet with a named owner; the Apsis completion line (19:00) is a go/no-go condition | rehearsal report | SD with FIN | PH | Y |
| T-7-164 | 7b | PIL | Apsis residual (D-556): an Apsis memo on an `on_aron` route raises the alert and SH-26 with the threshold of zero; a route with an unbound SR stays `held` | report | SD | PH | Y |
| T-7-165 | 7b | PIL | Programme pilot 7b-2 (D-561): 3 to 5 programme-tier routes for 10 trading days with a daily comparison of points earned, redeemed and balance, gift status and Astha achievement against Apsis; category C resets; the pass is the condition for binding any programme-tier route | pilot tally | SD | PH | Y |
| T-7-166 | 7c | PIL | Install success on the pre-bind day (D-562): at or above `cfg.cutover.install_success_pct` (95); part of every wave go/no-go | readiness report | SPN | PH | WV |
| T-7-168 | 7c | PIL | Per-wave rollback statement (D-592): for each wave "rollback possible", "fix-forward only" or "split", computed from the measured keyer throughput and the committed capacity, signed with the go/no-go sheet | signed statement | SPN | PH | WV |

## 4 Parity oracle and golden fixtures

### 4.1 The baseline pack (T-0-49; D-142; closes G-qa-01 in 0a and feeds Phase 1 entry)

Without the pack, "same workflow, same memo, same totals" is an opinion. The pack is captured from AKTCL's own pilot phones and AKTCL's own web login (no contact with Apsis's running backend, CLAUDE.md guardrail) and stored under `/docs/baseline/` with `INDEX.md`.

| Item | Content | Acceptance | Feeds |
| --- | --- | --- | --- |
| a | Screen recordings of a full SR day (login to check-out) and of the AMO and TSO flows on a pilot phone, with the installed app version string visible (the live app is newer than the manuals) | every screen of the manual inventory (SR 60, AMO 62, TSO 24) appears at least once; retailer data masked | T-1-41, T-2-43, T-3-42 |
| b | At least 25 printed memos, each a photo plus the entered quantities: cash, credit with partial payment, each live promotion group, DRP, zero sale, edit, stock memo, day summary (physical 58 mm samples, MQ-57, MQ-58) | each kind of s4.2 present; photo legible at the line level | T-1-35, T-2-41 |
| c | Screenshots of every web page of the TSO login (the 41-page union) and of any other role's menu AKTCL can provide (MQ-48) | 41 pages indexed against the 37-page spec map | T-4-40, T-4-120 |
| d | One full month of Excel exports of every report for 3 pilot zones, plus the QC PDF and print views (MQ-47) | the column sets and totals of each report readable | T-4-41, T-4-121 |
| e | The extracted Bangla and English string glossary from a and c | every distinct label and message once, with its screen | T-0-120, T-2-49 |
| f | The four user manuals | filed | all |
| g | The five unknowns only the live app can settle, captured on purpose: Joint Call items 4 and 5, the three per-SKU indicators, the basis of the maximum QC amount, whether a photo is taken on an in-range call, the printed layouts (MQ-23, MQ-07, MQ-03, MQ-10, MQ-57) | each has a frame or is listed as still unknown | pending-oracle list (s4.6) |
| h | For the pilot routes, and later for each wave, the Apsis Data Entry Log and end-of-day summary screens for the 5 trading days before the switch (the pre-switch login and submit baseline for T-7-86) | screens or file per route-day | T-7-82, T-7-86 |
| i | The Apsis USAGE CENSUS (D-503, G-qa-27): page and endpoint visit counts from AKTCL's own Apsis admin or audit views for the last 90 days (including the bex order pages, back-margin commission letters, the voicerecording page, the Sales Journey and KPI tiles and every menu of the DMO, WM, WMO and top roles), plus interviews with five distribution houses, the DMOs and the wing managers about the functions they use | a table of every Apsis page and function with a visit count and a named owner, and a flag for each one without a replacement in doc 15 | T-0-153, T-7-88, T-7-158 |

The pack therefore has nine items, a to i (doc 14 s3.0 said six and an earlier count said eight).

Ownership, capacity and fallback (D-565, G-qa-98). The critical path (Phase 1 entry, week 3) depends on AKTCL staff producing the nine items: three app recordings, 25 or more printed memos with quantities, 41 web page captures, a month of Excel exports for 3 zones, a string glossary and a 90-day usage census plus interviews at 5 distribution houses. The first draft named no AKTCL owner, hours or backup and assumed that audit or visit-count views exist. D-156 now names the AKTCL BASELINE-PACK OWNER (a sales-operations data owner, with a named deputy) and a day budget of 3 staff for 5 working days, tracked on the 0a board, with the delegate escalating a slip at 3 working days. FALLBACK when Apsis keeps no usage logs (item i): structured interviews with SIGNED attestations by the function owners, plus a 4-week observation log from the pilot zones (what the pilot SRs, AMOs and TSOs actually open), accepted as the evidence class that T-7-158 and T-7-88 read; the answer "no logs" is recorded at 0c (OI-16-42). Rules: the pack is re-captured on any change of the installed Apsis app version (G-20-07); any frame with retailer or rep identity is masked before it leaves the phone; the pack is read-only after the T-0-49 sign-off, and additions are new dated folders.

### 4.2 Memo corpus

The corpus is two sets. **Printed set** (T-1-35, T-2-41): at least 25 physical memos for layout and totals. **Review-screen set** (T-2-42, T-5-41): lines in, totals out, taken from baseline recordings and business examples, at least 3 per promotion group (about 22 groups, Q13), so about 66 or more fixtures; no print is needed for these.

| Kind | Minimum in the printed set | Notes |
| --- | --- | --- |
| cash memo | 3 | one with an automatic offer discount |
| credit with partial payment | 3 | amount strictly below the grand total (D-37) |
| each live promotion group | 1 per group, 3 fixtures per group in the review set | validity window (for example the slide offer valid 2025-11 to 2025-12-30) |
| DRP or slide deduction | 2 | the deduction shown as its own line (D-18) |
| QC with deduction | 2 | settlement and max-QC cap |
| zero sale | 1 | prints; consumes a memo number (D-36) |
| edited memo | 1 | prints the supersedes line (D-76) |
| stock memo | 1 | the hand-over slip to the distributor (default: Save works offline and `stock_slip_printed` is false; `cfg.stock.require_printed_slip`) |
| day summary | 1 | per SKU, per category, grand total, Return column |

Arithmetic rule (D-19): the printed total comes from summing unrounded line values and rounding once to the paisa; per-line display rounding is never stored; `round_adj_mtk` makes the printed total reproducible. Both engines (Dart, TypeScript) must match every fixture.

### 4.3 Golden fixtures (every value is re-checked by arithmetic before use; prices here are fixtures, never catalogue prices)

Rule: a number copied from a manual page becomes a fixture only after it reproduces from the other printed numbers on the same page. Digit misreads have happened: in the manual font a Bengali 4 looks like a Latin 8, 8 like a loop, 7 like 9 (TADS 68 was 64; Black Diamond 218,768 is 218,700; Avon 27,800 was wrong).

| ID | Fixture | Value to reproduce | Arithmetic check performed | Source and decision |
| --- | --- | --- | --- | --- |
| GF-01 | AMO memo and summary | total 116.42 (AMO p28, p43); summary 289.50 (p29, p46); FB 1 is 2.33 per piece on one screen and 28.00 per dozen on another | lines are taken from the pack and summed unrounded then rounded once (116.41 results from rounding per line); 28.00 / 12 = 2.3333 | V-money; D-16, D-19 |
| GF-02 | SR memo with slide and QC | gross 360.50, slide 80.00, net 280.50, quantity total 65 unchanged; SR p35 161.50; QC 10 sticks x 8.00 = 80.00; slide offer of 10 empty MaxR-10S packets gives 1 reward pack of 10 sticks x 8.00 = 80.00 | 360.50 - 80.00 = 280.50; 10 x 8.00 = 80.00 | V-money; D-18, D-33, D-34 |
| GF-03 | Stock badge | 6,500 sticks gives badge 650 (pack size 10); 6,000 gives 300 (pack size 20); category totals Cigarette 12,500, Bidi 2,400, Lighter 550, Match 400 | 6,500 / 10 = 650; 6,000 / 20 = 300 | V-money; D-17 |
| GF-04 | Lighter and match quantity basis | Lighter (Aster) 1 = 1 piece at 12.50; Match SL 12 = 19.00; Match entry unit unknown (12 pieces on SR p34, 1 dozen on p35) | n/a | V-money; D-16; MQ-01 |
| GF-05 | Dues | partial payment accepted only below the grand total; the label shows 61.50 (the manual truncates to 61: not reproduced); mark-as-paid settles the whole memo | n/a | V-money; D-37, D-213 |
| GF-06 | Max-QC | cap 594.50 taka, basis unknown (not 10 x 8.00); display only | n/a | V-money; D-34; MQ-03 |
| GF-07 | Home card | total discount 437.50 with DRP discount 0.00 shown separately | 35 x 12.50 = 437.50 | V-money, UI-SR-05; D-18 |
| GF-08 | SR day summary | Match 141.00; Lighter 4,687.50; gross 4,828.50; discount 437.50; QC 0.00; net 4,391.00; Return = issued minus sold (Lighter 400 - 375 = 25; Match 400 - 6 = 394); visited 6 of 60; strike rate 10 %; Non-visit 54 | 3 x 28.00 + 3 x 19.00 = 141.00; 375 x 12.50 = 4,687.50; 141.00 + 4,687.50 = 4,828.50; 4,828.50 - 437.50 - 0.00 = 4,391.00; 6 / 60 = 10 %; 60 - 6 = 54 | UI-SR-04, UI-SR-30; D-56 |
| GF-09 | SR target screen | target 500 gives TADS 36 and RADS 45; target 900 gives TADS 64 and RADS 82 (elapsed 14, remaining 11, achieved 0, round half up) | 500 / 14 = 35.71; 500 / 11 = 45.45; 900 / 14 = 64.29; 900 / 11 = 81.82 | V-targets; D-58 |
| GF-10 | Percent display | 38,960 against 3,944 prints 100 on a card (987.83 capped); 1,500 against 118 prints 1271.19 in a detail table; 136 against 0 prints remaining 136 and a dash (the manual prints 0.00) | 38,960 / 3,944 = 9.878; 1,500 / 118 = 12.7119 | V-targets; D-50 |
| GF-11 | TSO till-date | monthly 3,944, 1,425, 526, 510 give 3,420, 1,236, 456, 442 at 26/30 with a ceiling per item then summed | category monthly x 26/30 alone gives 3,418.13, 1,235.00, 455.87, 442.00, so the first two printed values need the item rows from the pack | V-targets; D-51 |
| GF-12 | AMO team till-date | monthly 2,972,900 gives about 2,475,477 | ratio 0.83268 against 25/30 = 0.83333; tolerance 0.1 % | V-targets; D-51 |
| GF-13 | AMO report till-date | Marise 100,000 gives 88,235.29 at 15/17 | 100,000 x 15 / 17 = 88,235.29 | V-targets; D-51 |
| GF-14 | Login and CPR | Login 1 of 4 routes = 25 % on TSO and web; CPR 6 / 60 = 10 % | exact | V-day, UI-SR-09; D-44, D-46 |
| GF-15 | Submit and Day-completion | on the sample day every submit bar is 0 %, so the web basis is unproven; the fixture asserts K-02 and K-03 on a synthetic day with real submits | synthetic | D-45; MQ-34 |
| GF-16 | PII | an AMO sees an SR phone as 11 asterisks; retailer phones stay visible to field roles | n/a | V-outlets; D-108 |
| GF-17 | Memo number | `sr334001-261004-017`; a device bound a second time that day starts at 501; the server keeps it verbatim | regex `^[a-z]+[0-9]+-[0-9]{6}-[0-9]{3,4}$` (three digits for bind ordinals 0 and 1, four from ordinal 2 and on overflow, D-393) | D-35 |
| GF-18 | Defects not reproduced | Sale History footer 20,260 versus rows 20,660; the AMO 12:53 PM check-out sample; the 2719 % value; TADS 68; the 61.50 truncation; "Babu Store" with Wing Dhaka versus Gaibandha goes to the import validation report | the test asserts the new app's value, not the manual's | I-04, I-06, I-08, I-18, I-21 |
| GF-19 | Data-profile defects | the planted defects of s9.3 (zero-volume lines, 68 duplicate keys, text outlet codes, 175,031 absent outlets, shared coordinates, placeholder NID) | counts per defect | docs/22; D-245 to D-260 |

### 4.4 Screen goldens

Process: for each screen the pack frame and the new app's golden are placed side by side at the same size; the reviewer marks each difference `accept` (a recorded decision or an IMPROVEMENT) or `fix`. Rules: goldens exist for bn and en at 320 x 640 dp (about 292 app goldens: 146 inventory screens times 2 locales; state variants add to the count), the TSO in its dark theme, web at 1280 and 390 px; Bengali digits follow `cfg.i18n.digit_script` and the golden is taken in both scripts until MQ-59 is answered; no vendor strings or artwork are copied (D-244; AKTCL's ownership of artwork is to be confirmed, so icons are redrawn); text overflow and Bengali conjunct rendering are asserted on the legacy device.

### 4.5 String glossary and message catalogue

1. The catalogue (doc 15 s11) holds the 251 manual entries (SR 99, AMO 90, TSO 24, Web 38; about 225 app-owned) plus the authored error and offline set; T-0-120 proves the seeding, T-1-121 the authored set, T-2-49 the Bangla review.
2. The five guard texts printed in the manuals are parity strings and are compared byte for byte; typos in the manual are corrected only through a glossary decision that names the screen.
3. Terms whose English form stays in the Bangla UI (Report, Sub-Channel, Geo Classification, Wing, Division, Territory, Zone, Assigned Tasks) follow the answer to MQ-60; until then the baseline recording is the oracle.
4. TSO default locale is English with a language switch (D-181); SR and AMO default to Bangla.

### 4.6 Report parity

For each report the pack holds the Excel file for the 3 zones and month. The comparison is: same column set and order, same totals, same row count for the same filters; a difference is closed by a decision (for example K-02 versus K-03, D-45; the dash for a zero target, D-50) or by a fix. The 11 Excel-only reports are compared by file; the on-screen grids by screenshot plus export. `Get Excel` is a formatting step over the same query (T-4-42). A report with no sample file is `pending-oracle` and uses the column set of the manual capture (MQ-47).

### 4.7 Oracles that are unknown (pending-oracle, D-451)

A gate that depends on an unknown is written against the proceed-with default, marked `pending-oracle`, and is not counted green for the phase it blocks until the owner supplies the oracle.

| Unknown | Gates affected | Tested meanwhile against | Supplied by | Needed by |
| --- | --- | --- | --- | --- |
| Promotion catalogue and rules (Q13, MQ-05, MQ-06) | T-2-36, T-2-42, T-5-41 | groups visible in the baseline memos | business | 2a entry |
| Match and lighter entry unit (MQ-01) | T-2-38, T-2-121 | piece for lighter, dozen for match, labels always shown | business | 2a |
| Loose sticks and stepper (MQ-02); negative net and max-QC basis (MQ-03, MQ-04) | T-2-120, T-2-122 | pack-multiple stepper; `cfg.memo.allow_negative_net` true | business | 2a |
| Printed layouts of the seven memo kinds (Q57, MQ-57, MQ-58) | T-1-35, T-2-41 | the on-screen memo layouts | sample memos | 1a |
| Printed digit script (MQ-59) | T-0-121, T-2-41 | both scripts | business | 1a |
| Who signs "same memo" (Q37) | T-2-41, T-7-142 | the sponsor's delegate with an SR and a manager | business | 2e |
| Rounding must equal Apsis to the paisa (D-19) | T-2-37, T-2-41 | half-up on the paisa | pack memos | 2a |
| Joint Call items 4 and 5 (MQ-23, MQ-24) | T-3-120 | items 1 to 3 plus placeholders | live app capture | 3a |
| Whose dues an AMO sees (MQ-17) | T-3-124 | the AMO's own credit memos | business | 3a |
| Till-date meaning of 17 and 15, TSO capture date (MQ-09, MQ-33) | T-3-121, T-3-127 | the verified fixtures GF-11 to GF-13 | business | 3a |
| Web basis of K-02, "Submit % (of logged-in)" (MQ-34) | T-3-127, T-4-44 | both K-02 and K-03 returned | a day with real submits | 3b |
| TSO ring day-target basis (MQ-40) | T-3-127 | unspecified; not asserted | business | 3b |
| Menus of DMO, WM, WMO, Top (MQ-48) | T-4-120 | the TSO union | live logins | 4a |
| Sample `.xlsx` of every report (MQ-47) | T-4-41, T-4-121 | the on-screen column sets | business | 4b |
| Programme earning rules and catalogue (Q13, MQ-20 to MQ-22) | T-5-120, T-5-41 | the seed rule POSM +50, gifts as documented | business | 5a |

Proved by: T-0-49, T-1-41, T-2-41, T-2-42, T-2-120 to T-2-124, T-3-127, T-4-41.

## 5 CI/CD, environments and release

### 5.1 Workflows (D-14; `/infra/.github/workflows`, mirrored to `/.github/workflows`)

| Workflow | Trigger | Jobs | Target time |
| --- | --- | --- | --- |
| `pr.yml` | pull request, path filters per workspace | lint and typecheck; contract build, drift check and `oasdiff`; api unit; api integration (4 shards, Testcontainers); api property 10,000 runs; app `analyze`, unit, widget goldens, property; app debug APK size (app paths); web unit and component; e2e smoke (compose stack, 20 flows); db `squawk`, apply on a fresh database and on a staging-schema snapshot; infra `bicep build`, PSRule and what-if (infra paths); security CodeQL, Semgrep, gitleaks, dependency review, OSV; `rtm-check`; gate-report upload | 15 min |
| `main.yml` | push to main through the merge queue | build api, worker and web images (SBOM, Trivy, cosign) and push to ACR; migrate staging (expand only); deploy the revision at 0 %; smoke; 100 %; full Playwright e2e; k6 S2 smoke; Flutter release APKs per ABI; upload to `releases/staging/` and create a draft `app_release`; emulator upgrade matrix; gate report | 60 min |
| `promote-prod.yml` | `workflow_dispatch(release_tag, wave_pct)`; environment `prod` with 2 required reviewers (engineering lead and business approver) | preconditions (staging soak met, every blocking gate for the tag green, change window open unless `hotfix = true`); migrate prod (expand); revision at 10 % with a 10-minute canary (5xx at most 0.5 %, p95 within SLO, `aron_reconcile_mismatch_routes` and the rejected-row rate not rising) with auto-abort; 50 %; 100 %; smoke; APK publish to `releases/prod/` with `rollout_wave` and `wave_pct`; gate report | 45 min |
| `contract-phase.yml` | manual, at least one release after the expand | runs the contract migrations after checking that no revision older than N+1 serves traffic | 10 min |
| `nightly.yml` | 20:00 UTC (02:00 Dhaka) | property 100,000 runs; full e2e matrix; schema-compat; full-size synthetic importer run; k6 S2 for 1 hour; dependency audit; stale-flag and dead-key reports; flake report; test-both flag runs; corpus replay (after 7b) | 3 h |
| `release-app.yml` | tag `app-v*` | build, sign, upload, draft `app_release`; the release manager sets wave and percentage in the console | 20 min |
| `device-lab.yml` | RC tag or manual; self-hosted runner in the lab, protected tags only (D-122) | install on reference devices; patrol suite (about 45); upgrade matrix on devices; zero-chatter check; (manual) 8-hour battery protocol; publish `/docs/perf/` | one working day plus 8 h |
| `load-test.yml` | manual (gate) | scale staging to the prod SKU, run the Azure Load Testing scenario with pass criteria, scale down, report | per scenario |
| `infra.yml` | PR what-if; main to staging; manual to prod | Bicep deploy; Azure Policy compliance; monitor workbooks and alert rules as code | 20 min |

Required checks on `main` are everything in `pr.yml` except path-filtered jobs that did not run; the merge queue runs the integration suite on the merged result. Actions are pinned by SHA; cloud access is OIDC only (D-122).

### 5.2 What fails a PR beyond failing tests

Coverage below the floor (P7); `oasdiff` breaking change without a `schema_version` bump; a `cfg.` literal not in the registry or a retired alias (T-0-63); `rtm-check` orphans; a new endpoint without a scope-harness case; any lint hit (`no-wall-clock`, `no-pg-mock`, `no-hardcoded-string`, `no-unscoped-query`, `no-float-money`); APK size more than 5 % above the last release (warn) or 15 % (fail); a migration flagged by `squawk` (non-concurrent index, NOT NULL without a default on a populated table, type change, rename); a quarantined test cited as the proof of a gate; a duplicate T-id.

### 5.3 main to staging and the staging soak

The deploy order is expand-migrate, deploy, verify. The migration job (an ACA job under role `app_migrate`, `lock_timeout` 5 s, `statement_timeout` 15 min) runs before the new revision; the revision receives 0 % traffic until `/healthz/ready` and the smoke suite pass; then 100 % (staging has no canary period). A failure at any step leaves the previous revision serving and posts the failing step to the Teams channel. A release tag must sit on staging for 24 hours before `promote-prod.yml` accepts it, and 72 hours for releases that touch `sync/ingest`, a migration with a backfill, or the Drift schema (D-14).

### 5.4 Promote to prod

Environment protection requires two reviewers. The change window excludes the freeze windows of `cfg.sys.change_freeze_windows` (07:00 to 09:30 and 16:30 to 19:30 Dhaka, D-100) and the first three days of every wave unless `hotfix = true`, which needs a P1 ticket id. Blue/green runs through ACA traffic weights 10, 50, 100 with a 10-minute canary at each step; any breach moves the weight back automatically and raises a Sev2 alert. Rollback of the API is a weight change (seconds); rollback of a migration is a forward fix, never a DROP of something a serving revision reads.

### 5.5 Environments

| Env | Purpose | Data | Deploy | Who |
| --- | --- | --- | --- | --- |
| local | engineer loop, `docker compose` (PostgreSQL 16, Azurite, Redis, mailpit) | `testkit fleet` small | manual | engineers |
| CI ephemeral | PR checks | Testcontainers | per job | CI |
| `dev` (Azure, small) | device testing of branches, demos | small synthetic | `workflow_dispatch`, any branch | engineers |
| `staging` (Azure, prod-shaped; scaled to the prod SKU for load windows only) | main auto-deploy, full e2e, soak, load, rehearsals, DR drills | full-size synthetic fleet (s9) plus the anonymised corpus after 7b | main | CI, QA |
| `prod` | pilot (real routes, Apsis the system of record), waves, fleet | real | manual promote | release manager and business approver |
| device lab | RC validation, battery protocol, printer | synthetic plus pilot accounts | runner | QA |

Pilot accounts are flagged `pilot = true`; their data counts in reconciliation but is excluded from national rollups until cutover by `cfg.flag.pilot_in_rollups` (default false, D-144). SKUs follow D-06 (pilot D2ds_v5 without HA, wave 1 D4ds_v5 zone-redundant, full fleet D8ds_v5); doc 18 s9 owns the infrastructure matrix.

### 5.6 Database migrations: forward-only, expand/contract over three releases (D-02, D-14)

| Rule | Detail |
| --- | --- |
| Files | `/db/migrations/NNNN_<name>.sql`, plain SQL, immutable once merged; CI compares checksums with `schema_migrations` on every environment and a changed shipped file fails the deploy. Partition creation and archival are jobs, not migrations. |
| Release N (expand) | add a nullable column, a new table, an index CONCURRENTLY or a CHECK NOT VALID; code N dual-writes old and new and reads old. |
| Release N+1 (backfill and switch) | batched backfill (at most 10,000 rows, `pg_sleep(0.05)` between, resumable by key), VALIDATE CONSTRAINT, reads switch to new. |
| Release N+2 (contract) | drop the old, through `contract-phase.yml` only after no revision older than N+1 serves traffic. |
| Forbidden | RENAME, in-place type change, NOT NULL without a constant default on a populated table, non-concurrent index creation, UPDATE without a batch bound, dropping anything a serving revision reads, editing a shipped migration. Enums take new values only; business code lists live in `cfg` code tables. |
| Locks | `SET lock_timeout = '5s'` in every migration; a lock wait fails fast and the job retries up to 5 times with jitter; DDL that needs ACCESS EXCLUSIVE on a hot table runs in the 01:00 to 02:00 Dhaka maintenance window (D-71). |
| Compatibility | nightly `schema-compat` runs the previous release's api suite against the current migrations (proves API N-1 on schema N); `squawk` on every PR; before promotion each migration is dry-run on a PITR clone of prod (once prod has data) to measure duration and lock time. |
| Device (Drift) | additive schema versions only; `onUpgrade` tested N-2 to N on every RC (T-2-44); a build older than N-1 refuses a newer database, N-1 opens it for one release (D-79); pending outbox rows survive every upgrade. |
| Gates | T-0-145 (mechanisms), T-1-46 (first rehearsal), T-2-44 (device), T-2-33 (server accepts N-2). |

### 5.7 Feature flags (D-99, D-459)

Flags are config keys: scoped, audited, bounded, propagated like every key; there is no third-party flag service. Key spelling follows the decisions that name them (`cfg.flag.pilot_in_rollups`, `cfg.flag.new_app_login_enabled`, `cfg.flag.parallel_run_mode`); doc 19 owns the final key names (OI-20-12).

| Kind | Examples | Default | Lifecycle | Proof |
| --- | --- | --- | --- | --- |
| release (dark launch) | a new memo-edit flow; `cfg.flag.pilot_in_rollups` | off | code ships dark; turned on per scope (zone, territory, all); removed within two releases of 100 % (the nightly stale-flag report lists flags at 100 % for more than 30 days) | T-0-144 |
| ops (switch) | kill switch, read-only mode, `cfg.ops.sync_hold_by_version`, `cfg.ops.maintenance_banner` | safe | permanent; every switch has a mandatory duration (D-130); drilled | T-2-46, T-2-63, T-7-53 |
| wave | `cfg.flag.new_app_login_enabled` per scope, `cfg.flag.parallel_run_mode` | off | the cutover lever; rollback is a flip | T-7-60 |

Rules: a flag that changes the shape of captured data is not a flag, it is a `schema_version` bump; flags are evaluated through the cached resolver, never per row; a flag marked `test_both` runs the api and web suites twice in `nightly.yml` (all on, all off); the device stamps `config_version` on every capture, which records the flags in force.

### 5.8 App distribution and staged rollout (D-10, D-79, D-130, D-456)

| Step | Mechanism |
| --- | --- |
| Build | `flutter build apk --split-per-abi --obfuscate --split-debug-info` (an `appbundle` too if a Play track is chosen); one codebase, three flavours with distinct applicationIds (D-01); version `major.minor.patch+build`; the contract's `schema_version` embedded; symbols uploaded to Sentry. |
| Signing | the upload key is fetched through OIDC from Key Vault in CI, separate from the app signing key; keys never on laptops or runners; the release publish is a human action after an independent reproducible rebuild (D-122). |
| Channels | `internal` (engineering, 10 devices, every main build), `pilot` (pilot devices, RC builds), `wave` (fleet, by `rollout_wave` and `wave_pct`), `rollback` (the previous prod APK always downloadable); blobs under `releases/<channel>/` behind Front Door. |
| Registry | `app_release(version, min_version, apk_url_by_abi, notes_bn, notes_en, rollout_wave, wave_pct, status, published_by, published_at)`; `GET /config/public` serves `min_version`, `latest_version`, `update_url`, `wifi_only`. |
| Staged rollout inside a wave | `cfg.release.wave_pct` 1 %, 10 %, 50 %, 100 % over at least 3 trading days; each step requires crash-free sessions of 99.5 % or more for that version (below `cfg.sla.crash_free_min_pct`, default 99.0, the percentage is paused automatically), a rejection rate not above the fleet's, and no category C; the cohort is `hash(device_uuid) mod 100 < wave_pct`, so it is stable. |
| Updater | in-app check once a day and on Settings; download Wi-Fi-preferred (`cfg.release.update_wifi_only`); `cfg.release.min_version` blocks a new-day login only; `cfg.release.blocked_versions` stops new captures only; neither ever blocks upload of captured rows. |
| Rollback of a bad build | downgrade is unsupported; the rescue is a roll-forward release within 24 hours; the drill proves the guard message and that pending rows still upload from N before a reinstall (D-79; T-7-83). |
| Coexistence | package ids differ from the Apsis apps; both share the Bluetooth printer through system pairing (T-2-141). |
| Server compatibility | the server accepts app versions N, N-1 and N-2 (`schema_version`); older versions go to `cfg.release.blocked_versions` and may still upload. |

### 5.9 Release train

Weekly release to staging on Tuesday; promotion to prod on Thursday outside the freeze windows; the hotfix path any day through `hotfix = true` with a P1 id; an app RC every two weeks through the device lab; `BREAKING.md` and `CHANGELOG.md` per release. A release is not shippable without its `/docs/perf/battery-<version>.md` and its gate report (D-73). The quarterly drills of s7.5 are scheduled on the train, never in a wave's first three days.

Proved by: T-0-40, T-0-43, T-0-145, T-1-46, T-2-44, T-2-46, T-7-60, T-7-83.

## 6 Observability verification and reconciliation reports

Design belongs to doc 18 s6 (SLOs, dashboards, alerts, sampling) and doc 17 (device telemetry); this section defines what is verified and the reports that prove the data is right.

### 6.1 Log schema and the no-PII rule (D-13, D-136)

No PII appears in logs, traces, metrics or crash reports: ids, `client_uuid`, `memo_no`, hashed `device_id`, zone and route ids only; never names, phones, NID, free text, raw URLs with query strings, tokens or full-precision coordinates (logs carry distance and flags). One `request_id` (W3C `traceparent`) flows api, worker, logs and App Insights; the device's `batch_uuid` becomes a server span attribute. Sampling keeps 100 % of errors, 429, 5xx, requests slower than 2 s and every `sync/*` summary, and 5 % of other 2xx (`cfg.telemetry.success_sample_pct`).

| Field | Notes |
| --- | --- |
| `ts`, `level`, `service` (api, worker, web, job), `env`, `version`, `revision` | standard |
| `request_id`, `trace_id`, `span_id` | from `traceparent` |
| `route` | the route template (`POST /sync/batch`), never the raw path |
| `status`, `duration_ms`, `bytes_in`, `bytes_out` | numbers |
| `user_id` (hashed), `role`, `device_id` (hashed), `zone_id`, `territory_id`, `business_date` | ids only |
| `batch_uuid`, `rows`, `accepted`, `rejected`, `replayed`, `reason_codes[]` | per batch; per-row detail lives in `sync_rejected`, not in logs |
| `config_version`, `schema_version`, `app_version`, `android_sdk` | from headers |
| `event` | catalogue: `auth.login`, `auth.refresh`, `bundle.serve`, `bundle.pregen`, `sync.batch`, `sync.row_rejected`, `sync.replay`, `agg.item`, `agg.lag`, `config.change`, `config.ack`, `geo.recheck_mismatch`, `fraud.signal`, `day.sales_submit`, `day.final_submit`, `import.step`, `reconcile.diff`, `alert.fired` |
| `error.code`, `error.message` (from a fixed table, never interpolated with user data), `error.stack` (sampled) | |

Verified by: T-0-44 (one request traced end to end), T-1-75 (injection of phone, NID, password and token finds nothing), T-4-57 (telemetry volume).

### 6.2 Device telemetry rides inside the sync batch

One `telemetry` record per device-day (typically 300 bytes, capped by `cfg.telemetry.device_max_bytes_per_day`, default 1,024, D-136), never a separate network path (R4): `battery_pct`, `battery_drop_pct_since_last`, `pending_rows`, `pending_photos`, `sync_attempts`, `sync_failures{reason}`, `gps_fixes`, `gps_ms`, `wake_lock_ms`, `rows_captured{type}`, `app_version`, `android_sdk`, `free_storage_mb`, `crash_count`, `clock_offset_ms`, `mock_capable`. It is operational, not personal, lands in `dw.fact_device_day`, and is the source of the pilot sheet's battery and pending-row lines. Crash reporting uses Sentry with a `beforeSend` scrubber, `device_id` as the user id and screen-name breadcrumbs only; its data residency is open (D-13, Q36, OI-20-11).

### 6.3 Metrics asserted by gates

| Metric (prefix `aron_`) | Meaning | Asserted by |
| --- | --- | --- |
| `sync_batches_total{outcome}`, `sync_rows_total{type,outcome,reason_code}` | accepted, replayed, rejected, parked, conflict, flagged | T-1-21, T-1-53, T-4-45 |
| `http_duration_ms{route}` | bundle p95 2 s or less; trickle ack p95 1.5 s; catch-up p95 8 s | T-4-51, T-4-53, T-7-51 |
| `agg_lag_s`, `agg_dirty_depth`, `agg_oldest_s` | aggregation lag p95 60 s; oldest item 5 minutes | T-4-54, T-4-59 |
| `reconcile_mismatch_routes` | at most 0.1 % of route-days after settle | T-1-54, T-7-87 |
| `routes_by_state{state,zone}`, `final_submit_zones` | K-01, K-02, K-03, K-15 | T-1-43, T-4-44 |
| `bundle_served_total{kind,source}` | live share under 5 % in the storm | T-4-51 |
| `config_ack_pct{version}` | 95 % of online devices within 15 minutes | T-2-61 |
| `device_battery_drop_pct`, `device_pending_rows`, `device_sync_failures{reason}` | pending p95 goes to 0 after 17:00 | T-1-45, T-7-87 |
| `app_crash_free_sessions_pct{app_version}` | 99.5 % or more | T-2-45, T-7-86 |
| `import_control_total_diff{zone,metric}` | 0 for counts and balances | T-7-80 |
| `parallel_run_diff{route,category}` | category C 0 | T-7-82 |
| `geo_valid_pct`, `force_sale_pct`, `mock_flag_total`, `fraud_signal_total{kind}` | K-09 and companions | T-2-66, T-2-47 |

### 6.4 Nightly reconciliation reports (results in `dw.*` tables and on the sync-health page)

| Report | Compares | Grain | Tolerance | Action on a difference | Gate |
| --- | --- | --- | --- | --- | --- |
| Device versus server | each device's last `device_counts` claim (submit event or telemetry) against `ingest_registry` counts | route-day x type | 0 | row on sync-health; the AMO contacts the SR; unexplained after 24 h becomes a ticket | T-2-31, T-7-87 |
| Aggregate versus source | SUM and COUNT from `app.*` against `dw.agg_*` for the last 7 business dates | zone-day | 0 | re-enqueue (self-heal); alert if it survives two runs | T-4-46 |
| Ledger balances | dues: sum of memo due minus collections against `outlet_due_balance`; loyalty: ledger sum against balance | outlet | 0 | block further credit or redemption for the outlet; finance review | T-2-143 |
| Final-submit coverage | zones final-submitted against zones with activity | zone-day | 100 % by 21:00 | TSO reminder; Daily Tracking | T-7-87 |
| Import control totals (s7.4) | `app` after import against the Apsis reports | zone x metric | 0 for counts and balances | no cutover until clean | T-7-80 |
| Parallel run (s7.3) | new app against Apsis per pilot route-day | route-day | exact | categorise by 09:00 | T-7-82 |
| Hygiene | stale flags, dead cfg keys, quarantined tests | repo | lists | weekly cleanup | T-0-144, T-6-60 |

### 6.5 The pilot daily sheet (the pilot lead fills it by 09:30 the next morning; D-460)

| # | Measure | Source | Target | Owner |
| --- | --- | --- | --- | --- |
| 1 | Routes planned, logged in, uploaded, sales-submitted and final-submitted (pilot zones) | sync-health | 100 % each by 21:00 | PL |
| 2 | Parallel-run discrepancies by category A to E per route | pilot dashboard | C = 0; others explained | PL, engineering |
| 3 | Memo count, STD per SKU, net value and dues collected, new against Apsis | compare job | exact | PL |
| 4 | Reconciliation mismatches (device versus server) | sync-health | 0 | engineering |
| 5 | Rejected, parked and conflict rows by reason | sync-health | rejected explained; conflict 0 | engineering |
| 6 | Time from last capture to server receipt (p50, p95 per device) | telemetry and registry | p95 within 3 minutes while online | engineering |
| 7 | Battery drop per device-day and the SR's own reading at 17:00 | telemetry and sheet | within the s2.7 budget | PL |
| 8 | Mobile data used by the app per device-day | `netstats` on 2 lab-managed pilot phones (Q33) | at most 1 MB without photos, 3 MB with | PL |
| 9 | Geo-valid %, force-sale %, mock flags, plausibility flags per SR | sync-health | in line with the pilot baseline; every flag reviewed by the AMO | AMO |
| 10 | Photos pending more than 24 hours | sync-health | 0 | engineering |
| 11 | Printer failures, reprints, printer model per device | SR sheet and `print_count` | at most 1 per device-day | PL |
| 12 | Crash-free sessions and ANRs | Sentry | 99.5 % or more | engineering |
| 13 | Support contacts (count, top reasons, time to resolve) | helpdesk | falling; P1 = 0 | SUP |
| 14 | Config changes made and their reach | config dashboard | 100 % acknowledged by next morning | SD |
| 15 | Retailer feedback on the memo, any complaint | SR sheet | 0 unexplained | PL |
| 16 | SR feedback: screens that confused, steps slower than the old app | SR sheet (Bangla) | logged and triaged | PL |
| 17 | Median time to complete a sale (first tap on the outlet to memo printed) | activity log | at most the old app's (measured in the baseline recording) | engineering |
| 18 | Rows with `business_date_mismatch` or `clock_skew` | DQ flags | 0 unexplained | engineering |
| 19 | App version mix on pilot devices | devices dashboard | 100 % on the RC | engineering |
| 20 | Open P1 and P2 tickets | tracker | 0 P1 | SD |
| 21 | Immediacy: share of rows ACKed within 60 s and 5 min when captured online, within 3 min of `connectivity_regained_at` when captured offline, devices holding rows beyond 4 h (D-509, D-511) | SH-22, SH-23, SH-24 | 95 and 99 percent; 90 percent of devices; 0 devices | engineering |
| 22 | Field battery and data from telemetry on ALL pilot devices (not only the 2 lab-managed phones): p95 mobile bytes, CPU ms, engine starts, whole-device level at 17:00 (D-507) | SH-19 to SH-21 | inside the s2.7 budgets by route size | engineering |
| 23 | Every support contact by category (SR, AMO, TSO, retailer dispute) with its tool and time to resolve (D-553) | helpdesk log | measured rate recorded for the wave-1 staffing | SUP |

### 6.6 Alert fault injection (T-4-45; thresholds are in doc 18 s6, injection recipes are owned here)

Each Sev1 and Sev2 rule is fired once on staging by injecting its condition and must reach the on-call phone and the Teams channel and link a runbook in `/docs/runbooks/`. An alert that fires without a runbook is itself a Sev3 ("undocumented alert"). Weekly alert review: an alert that fired more than 5 times without action is re-tuned or deleted. Baselines are calendar-aware (Friday and holidays, D-135).

| Sev | Alert | Injection on staging |
| --- | --- | --- |
| 1 | API availability (5xx above 2 % for 5 minutes, or Front Door origin unhealthy) | fault-inject 503 on the origin; kill replicas |
| 1 | DB primary unavailable or HA failing over | forced failover (S7) |
| 1 | K-01 at 09:00 Dhaka below 70 % of the calendar-aware baseline for any wing | disable pre-generation for one wing |
| 1 | sync success below 97 % for 1 h; crash rate above 2 % for the wave's build (the rollback triggers, D-596) | simulators fail batches; synthetic crashes |
| 1 | Apsis residual (SH-26): a switched route receives an Apsis memo | load a DL-6 row of `apsis_residual` |
| 1 | Key Vault delegation under 3 h or the first failed `sign` | block the vault endpoint on staging |
| 1 | an urgent config change below 95 % of the selling cohort after 15 minutes (push off) or of push-enabled devices after 5 | simulators ignore the delta |
| 1 | synthetic legacy-device probe failing 3 times | break the certificate chain on a test host |
| 2 | bundle p95 above 3 s for 10 minutes; 429 above 5 % for 5 minutes | load at S1 with replicas pinned low |
| 2 | outbox age above 5 minutes; aggregation staleness above 5 minutes | pause the worker; plant a throwing route (T-4-59) |
| 2 | replica lag above 60 s | pause WAL replay on the replica |
| 2 | geo-valid % of a territory down more than 30 points against yesterday's same hour | set a test territory radius to 20 m |
| 2 | quarantine above 100 per hour, or rejected above 0.5 % of rows | feed rows with an out-of-scope outlet |
| 2 | crash-free below 99 % for a version with more than 100 devices (pauses `wave_pct`) | synthetic crashes from simulators |
| 2 | `reconcile_mismatch_routes` above 0.5 % of today's routes at 19:00 | withhold rows from simulated devices |
| 2 | config acknowledgement below 80 % 30 minutes after a C3 change | simulators ignore the delta |
| 2 | Submit % at 21:00, force-sale share, mock-GPS share, quarantine backlog, crash-free below `cfg.sla.crash_free_min_pct`, DSS stamp older than 120 s, a bind held for more than 45 minutes (the `cfg.sla` keys of doc 19 that had no routed alert, D-596) | lower the thresholds on a test scope; hold a bind; stop the DSS refresh |
| 2 | telemetry silence from a zone active yesterday by 10:00 | switch simulators off |
| 2 | importer step failed (7a); parallel-run report absent by 21:00 (7b) | fail a step; skip the job |
| 2 | certificate expiry within 14 days (T-2-57) | short-lived test certificate |
| 3 | storage above 70 %; ACA cores above 80 % of quota; ingestion above budget; pre-generation failed or running past 04:30 | lower the thresholds on a test workspace |

Routing: Sev1 goes to the on-call engineer's phone (Azure Monitor action group to a paging service or SMS) and the ops Teams channel, plus the business owner by SMS during a wave; Sev2 to Teams and e-mail, phone during 07:00 to 21:00 Dhaka; Sev3 to a Teams digest.

### 6.7 Sync-health tile verification (T-1-43, T-4-44)

Every tile is checked against a staged fuzz day whose true numbers are known from the seed, with the "as of" stamp visible and a 60-second refresh.

| Tile | Definition | Oracle |
| --- | --- | --- |
| Target routes, logged-in routes, Login % | K-01 | seed counts |
| Submit % (of logged-in) and Day-completion % shown side by side with their basis | K-02, K-03 | seed counts; both always returned (D-45) |
| Final-submitted zones and remaining list | K-15 | seed |
| `submit_pending_rows` shown distinctly from not submitted | D-64 | planted late rows |
| Last batch age per zone; photos pending more than 24 hours | doc 16 s8 | planted |
| Reconciliation mismatches (route, type, device count, server count) | s6.4 | planted gaps |
| Rejected and quarantined rows by reason | `sync_rejected` | planted |
| Mock-GPS flagged visits today; geo-valid % against yesterday's same hour; force-sale % | K-09 and companions | planted |
| App version mix; config version reach (acknowledged %, pending devices by zone) | `dw.fact_device_day`, config reach view | simulators |
| Open P1 and P2 ticket count; maintenance banner state | helpdesk or manual | manual |

Proved by: T-0-44, T-1-43, T-1-75, T-2-31, T-4-44, T-4-45, T-4-46, T-4-57, T-7-87.

## 7 Pilot, cutover and support verification

docs/11 made testable. Apsis stays the system of record until a wave switches (D-144); nothing here contacts Apsis's running backend or app: the Apsis side of every comparison is data AKTCL exports (D-154) or reads off its own screens.

### 7.1 Entry conditions (checked as gates)

| Entry | Condition | Gates |
| --- | --- | --- |
| 7a | the dump delivered in the shape of docs/11 plus the memo-level fields of docs/22 (Q18); the importer built and green on synthetic data (s9); the RESIDENCY, PII and NOTICE answers of doc 21 s4.6 (D-05, D-107, D-120) on file, or the import is pseudonymised or synthetic only and 7b does not start (D-570, G-qa-105: real owner names and phone numbers land at 7a and real employee location at 7b, so these answers cannot wait for 7c); the APK distribution channel decided (D-10, D-562); the production server created with HA and geo-redundant backup on and 1,024 GiB (D-568, D-569) | T-0-141, T-7-145, T-7-160 |
| 7b | 2a to 2e, 3a, 3b, 4a, 4c (Final Submit and Web Entry fallback), minimal 6b and the device OTP panel exited; the 10-hour soak T-2-176 passed (D-558); the FCM decision D-09 recorded and S9 run with push on and off (D-563); Front Door Premium running with the managed rules in Log mode (D-573); the pilot consent screen shows the D-120 text (D-570); baseline pack captured; shadow import clean for the pilot zones; field spot check; per-route daily Apsis data available or the manual fallback rehearsed; pilot SRs consented and incentive agreed; pilot routes chosen; `cfg.flag.pilot_in_rollups = false` | T-0-49, T-7-80, T-7-81, T-7-90, T-7-141, T-7-145, T-2-73, T-2-141, T-2-176, T-4-161, T-7-162 |
| 7c | 7b passed (including the programme pilot of the row below for any wave that contains programme outlets); 4d and 6c exited; D-127 and D-149 answered (D-05, D-107 and D-120 moved to the 7a entry, D-570); the timed geo-restore drill T-4-174 done, its RTO written into RK-05 and the day-one checklist and the wave-1 DR position accepted by the sponsor in writing (D-569); the per-wave rollback statement signed (T-7-168); helpdesk staffed; pre-bind-day plan signed; pen test passed; quotas approved; rollback suite and game day done | T-7-82, T-7-70, T-7-55, T-7-83, T-7-59, T-7-85, T-4-174, T-7-168 |
| Programme outlets (7b-2, D-561, G-qa-93) | D-41 (earning rules, cash-cap scope, catalogue, expiry), D-192 and D-332 are a 5a ENTRY condition, as D-33 is for 2a; the pilot and wave 1 exclude programme zones until 5a to 5c exit; then a PROGRAMME PILOT (7b-2) runs 3 to 5 programme-tier routes for 10 trading days with a daily comparison of points earned, redeemed and balance, gift status and Astha achievement against Apsis under the same pass rule (category C resets); that pass is a condition for binding any programme-tier route; PX-10 is resolved with real zone-by-zone counts from the dump, not an assumption | T-5-40, T-5-41, T-7-165 |

### 7.2 Pilot design (D-144, D-147, D-152)

1. Size: 10 to 20 routes (D-147); about 8 to 15 SRs at 1.33 routes per SR (11,336 routes over 8,500 SRs; ASSUMPTION), plus their AMOs and TSOs.
2. Selection criteria (T-7-141): urban dense, rural and hill; GT outlets; one wholesale buyer; at least one route with placeholder coordinates (docs/22 P-09); programme outlets only if P5 has exited; SRs who consent to double entry for about 2 to 3 weeks and an incentive agreed (G-qa-16; unknown; confirm with the business, Q33).
3. Both apps run on each pilot phone with different package ids; the new app is flagged `pilot = true`.
4. Print behaviour (D-152): `cfg.flag.parallel_run_mode` is `off`, `capture_only` or `print_test_watermark`. In test mode the print carries "পরীক্ষামূলক - এটি রসিদ নয়" and no previous-due line; new-app `due_collection` rows marked parallel are excluded from balances; the SR's briefing card says which memo the retailer keeps (G-field-12). Which mode the pilot uses is unknown; confirm with the business (Q49); the default is `print_test_watermark`.
5. Two lab-managed phones carry `netstats` measurement for the data line of the sheet (Q33).
6. The helpdesk for the pilot is the pilot lead's phone.

### 7.3 Parallel-run comparison per trading day

The compare job runs at 20:30 Dhaka per trading day (days per `cfg.calendar.*`), the report is due by 21:00 (a missing report by 21:00 is a Sev2), and review is complete by 09:00 the next day (D-453). Source on the Apsis side is the per-route daily feed of D-154; if Apsis cannot export, the pilot lead photographs the Apsis app's end-of-day summary and printed memos and a clerk keys them (fallback for the pilot only; unknown; confirm with the business, Q32).

| Measure | New-app source | Apsis source | Tolerance |
| --- | --- | --- | --- |
| Outlets visited; zero-sale calls | `agg_daily_route` | daily report or app summary | exact |
| Memo count (active, `line_count` above 0, K-06) | `agg_daily_route` | report | exact |
| STD per SKU in base quantity (sticks, pieces, dozens) | `agg_daily_route_sku` | report | exact |
| Gross, discount and net value (`net_mtk`) | `agg_daily_route` | report | exact to the paisa (needs 2a) |
| Dues collected; outstanding per outlet after the day | ledgers | report or Apsis app | exact |
| Stock issued and returned | stock events | stock memo | exact |
| Login time, upload time, Sales Submit time | `route_day` | Data Entry Log | informational |
| Geo-valid %, force-sale count | facts | Apsis geo report if exported | informational (the new control is stricter) |

**Discrepancy categories** (every difference gets exactly one; D-145): **A** human double-entry omission or typo (the SR entered in one app only or a different quantity); **B** rule difference (promotion, rounding, unit, KPI definition) resolved by a recorded decision or a fix; **C** sync loss or duplication (captured on the device but not on the server, or doubled): P1, blocker; **D** an Apsis-side change after the fact (edit, back-office correction); **E** timing or business date (the row landed on another date).

**Triage rule (separates A from C):** pull the device ledger of the route-day (telemetry `device_counts`, or a PDA to Support export when counts disagree). Row absent on the device: A. Row on the device but absent, different or doubled on the server: C. Row on both with different values: B when a rule explains it, otherwise A. Apsis audit shows a later edit: D. Date or clock flag: E. A category C is investigated with `/investigate`, the `ingest_registry` and `sync_rejected`, and its root cause recorded before the count resumes.

**Pass rule (D-145):** 10 consecutive trading days with zero category C across all pilot routes, exact memo count, STD, dues and value after A, D and E are explained and B is closed by a recorded decision. One category C resets the count; a non-trading day neither counts nor resets. A trading day on which the Apsis feed is missing for any pilot route does not count until its data is keyed (D-453). Weekly, the SRs' time-per-sale and battery readings are compared with the baseline (pilot sheet lines 7 and 17).

### 7.4 Data-dump reconciliation control totals (7a; the delta before every wave repeats it)

| Control total | Grain | Apsis source | Tolerance |
| --- | --- | --- | --- |
| Outlets by status (active; archived stubs of docs/22 P-08) | zone | retailer list and sales history | 0 |
| Outlets with coordinates; with placeholder coordinates (P-09) | zone | retailer list | 0 (placeholders are flagged, not fixed) |
| Routes; route-to-SR assignments valid on the import date | zone | route list | 0 |
| Users by role | territory | user list | 0 |
| SKUs and price rows by type and validity | all | catalogue | 0 |
| STD per SKU per month (8 months) | zone | monthly STD report | 0, or at most 0.01 % only where the Apsis report rounds and the rounding is documented per report (D-454) |
| Memo count per month | zone | report | 0 |
| **Outstanding dues** per outlet (sum and count of outlets with dues) | zone | dues report or per-outlet balance | 0 |
| **Loyalty points balance** per outlet | zone | Diamond League report | 0 |
| Targets per route or zone x product x month | zone | target allocation report | 0 |
| Photos linked to records | zone | media manifest | counts equal; missing files listed |
| Quarantined rows by reason (P-05 to P-14) | table | n/a | at most 1 % of rows per table with every reason counted and explained; a higher rate raises a gap (D-153) |

Output: `import_reconciliation(run_id, zone_id, metric, source_value, imported_value, diff, status, note)` and a signed PDF for finance (dues, FIN) and sales operations (STD and targets). The importer is re-runnable (T-7-02); a second run changes nothing. A delta import for a wave night must be reconciled by 23:00 on T-1, J2 is triggered by it and must finish by 23:30, and the go or no-go is read at 23:30, or the wave is deferred (D-71, D-557); rows that Apsis receives after the 20:00 cut are picked up by the late delta DL-1b at 06:00 on day T and the straggler sheet (D-556, doc 16 s12.6). Caveat (G-20-06): the Apsis monthly reports may include sales made outside the apps (docs/22 P-01, D-245); until that is answered, history reconciles to the dump's own rows and the monthly STD comparison is informational.

### 7.5 Rollback drills (T-7-83; all on staging before wave 1 and quarterly after, each with a runbook and a measured time)

| Drill | Trigger simulated | Steps | Pass | Gate |
| --- | --- | --- | --- | --- |
| Wave rollback by flag | a wave breaks | `cfg.flag.new_app_login_enabled = false` for the wave scope; banner; old app resumed; pending rows still upload; wave data exported for the Apsis side | 95 percent of push-enabled devices in 5 minutes, 95 percent of selling phones in 15 minutes with push off, idle phones at the next foreground (doc 19 s4.1b, D-563); 0 rows lost | T-7-60, T-7-143 |
| Rollback return path (D-549, G-qa-81) | a wave rolls back after 2 days of data | the wave's open dues, credit memos and stock are re-keyed into Apsis by the named, staffed procedure (days 1 to 3), from day 4 fix-forward only; retailer dues in the Apsis-shaped export compared with Aron; keyer throughput measured and the per-wave capacity statement of doc 18 s7.5 written (waves above the committed capacity are fix-forward only, D-592) | dues equal to the paisa; procedure timed; owner on the war-room roster; capacity per wave computed | T-7-153, T-7-168 |
| App rollback | a bad RC | mark N in `blocked_versions`; publish a roll-forward rescue release within 24 hours; show the downgrade guard message and that pending rows still upload from N | documented and timed | T-2-46, T-7-83 |
| API revision rollback | canary breach | traffic weight to the previous revision | 60 s or less | T-7-83 |
| Config revert | a radius typo | one-click revert; acknowledgement reach | 15 minutes to the selling cohort (doc 19 s4.1b) | T-2-64 |
| DB PITR | a data-corruption bug | restore to T-x into a new server, verify counts and the audit-chain head, repoint, force a new server generation | RTO measured (target 2 h, D-127) | T-6-43, T-1-56 |
| Region DR | region outage | promote the East Asia replica, redeploy by Bicep, Front Door secondary origin, devices re-send 24 hours of rows | RTO 4 h, RPO 15 min (D-127) | T-7-54 |
| Geo-restore into East Asia (the wave-1 path, D-569, RB-52) | region outage before the cross-region replica exists | restore the geo-redundant backup of staging into East Asia, wait for disk hydration before HA, scaling or a replica, apps and keys from the DR copies, new generation, smoke script, origin switch | every step timed; the measured RTO replaces "RTO hours: unknown" in RK-05 and the day-one checklist | T-4-174 |
| Key Vault outage (D-574, RB-48) | `kv-sign` unreachable for 90 minutes | S12 during S2 load | minting, refresh and uploads continue; the first failed `sign` alerts | T-4-173 |
| Silent phone and push on or off (D-563, RB-49) | a phone offline beyond the reach bound after an urgent revert | S9 with push on and with push off; a phone silent for 6 hours reconnects | selling cohort within 15 minutes (push off) or 5 minutes (push on); the silent phone applies the version on its first request and its rows were judged by D-431 and D-571 meanwhile | T-2-174, T-4-161 |
| Import rollback | a bad delta | `rollback_import(run_id)` | control totals back to the pre-import values | T-7-58 |
| Version-scoped hold | an upload loop | `cfg.ops.sync_hold_by_version` at the edge; rows stay on the devices | stopped within 60 s; auto-expires | T-2-56, T-7-53 |
| Key rotation | a leaked key | JWT rollover, DEK re-wrap | no field logouts | T-7-71 |

### 7.6 Day-one readiness checklist (T-7-84; per wave; every line has an owner and a tick; signed at T-1 by the four owners)

| Group | Line | Owner | Evidence |
| --- | --- | --- | --- |
| Data | Every wave SR has completed the Apsis end-of-day upload and Sales Submit and the Apsis TSO Final Submit is done for 100 % of the wave's zones by 19:00 at T-1 (`cfg.cutover.apsis_complete_by_time`); a GO/NO-GO condition (D-556, G-qa-88) | TSOs, SPN | Apsis Final Submit log |
| Data | Final delta import reconciled and signed by 23:00 at T-1, J2 done by 23:30 and the go or no-go read at 23:30, otherwise the wave is deferred (D-557) | OPS, FIN | signed `import_reconciliation` |
| Data | Late delta DL-1b scheduled for 06:00 on day T and T+1 to T+3, a named clerk and the 3-day straggler sheet open, bundle delta with "balance as of" marker verified on a lab phone (D-556) | OPS, FIN | job output; the sheet |
| Data | `apsis_residual` live for the wave, or "old app disabled or uninstalled on every phone of the wave" ticked by the TSO; the bind-at-the-distribution-house list complete and every unbound SR's route `held` (D-556, RB-44) | OPS, TSOs | SH-26; readiness ticks |
| Data | Opening dues and loyalty spot check for 10 outlets of the wave, at least 3 of them outlets whose Apsis rows landed after the T-1 20:00 cut (D-556) | PL | sheet |
| Data | Route assignments valid for the switch date for every SR of the wave; working-day calendar and holidays of the month entered; targets for the month present | business | reports |
| App | RC on the `wave` channel with `wave_pct` set; `min_version` unchanged; rollback APK downloadable | REL | `app_release` row; URL check |
| App | Upgrade matrix green; the RC's `/docs/perf/` file published; Bangla strings signed | QA, NB | gate report |
| App | **Pre-bind day done**: 100 % of the wave's devices installed (install success at or above `cfg.cutover.install_success_pct`, 95, with Play Protect on, D-562), bound, logged in once, the bundle for D FETCHED AND VERIFIED from the 14:00 pre-snapshot, and a test memo printed by 19:00 on T-1; day one is a small delta, not "refresh plus 304" (D-126, D-557; feasibility is Q52) | TSOs, SUP | bind report |
| Backend | Load test at 1.5 times the wave green (T-7-51, T-7-52); quotas confirmed; pre-scale schedule enabled (`cfg.ops.prescale_schedule`) | OPS | reports |
| Backend | Pre-generation verified at 04:30: count equals the wave's users plus the existing, coverage 99 % or more, `valid_for_business_date` equals D; three bundles spot-checked; otherwise `bundle_hold` and L2 paged (D-71) | OPS | job output on the war-room screen |
| Backend | Synthetic probes green; certificate expiry more than 14 days; the Front Door fallback host probed from a lab phone; change freeze armed | OPS | probe log |
| Backend | The measured geo-restore RTO and the sponsor's written acceptance of the wave-1 DR position are on the sheet; the config reach bounds measured with push on and off are printed (D-569, D-563) | OPS, SPN | T-4-174, T-4-161 |
| Rollback | The per-wave statement "rollback possible / fix-forward only / split" is signed with the committed re-key capacity (D-592) | SPN, FIN | statement |
| Config | Radius values per territory and zone reviewed by their TSOs against the calibration report (T-7-91); `cfg.day.checkout_earliest_time` confirmed; promotion rules effective-dated | SD | review record |
| Config | Kill-switch owners named; launch-day change-rate limit on (T-7-61) | OPS | roster |
| Observability | Sync-health on the war-room screen; a test alert fired this morning and reached the on-call phones; the pilot-sheet template for the wave; a test device's telemetry visible | OPS | screenshots |
| Support | Helpdesk staffed per s7.8; scripts and known-issue board published; TSOs briefed on their toolkit; OTP bulk issuance done where needed; escalation tree printed and set in `cfg.support.contacts` for the locked-out screen | SUP | roster; script pack |
| People | War room (two engineers, ops, the business owner); AMOs and TSOs trained (1 hour with the checklist); SRs informed by Bangla SMS or voice note (new icon, same login, helpdesk number; or the temporary-password path if the dump's hashes cannot be verified, D-119); go/no-go meeting booked for 21:00 daily | SPN, SUP | attendance; message log |
| Rollback | Drills done this quarter; rollback decision owners named; thresholds of s7.7 printed; old-app retirement procedure ready (T-7-144) | SPN | signed list |

The hour-by-hour wave-day timeline (00:05 route_day creation, 04:30 coverage check, 06:15 pre-scale, the storm watch, the 17:00 wave, 21:00 day close) is owned by doc 18 s7; this checklist is the verification form for it.

### 7.7 Waves and go/no-go (T-7-86; D-147)

Wave order (D-147; sizes unknown, confirm with the business, Q38, Q51): pilot 10 to 20 routes, then wave 1 one territory per wing (about 300 to 390 routes) unless Q51 says 1,000 SRs, wave 2 one division per wing, wave 3 half the fleet, wave 4 the rest. No wave starts within three days of a month end, Eid or a holiday (calendar) or on a Thursday (Friday is the off-day and the first full day needs support staffed). Each wave needs the previous one stable for 3 trading days, and starts Sunday to Tuesday so that the pre-bind day T-1 is a trading day (doc 14 s8.2).

| Go/no-go measure (the previous wave's 5 trading days; ASSUMPTION thresholds) | Threshold |
| --- | --- |
| K-01 against the wave's pre-switch Apsis login % (Data Entry Log of the 5 trading days before the switch, pack item h) | 95 % or more of it by day 3 |
| K-02 ("Submit % (of logged-in)") against the pre-switch figure | not lower |
| Reconciliation mismatch (device versus server after settle) | 0.1 % of route-days or less |
| Sync success within 3 attempts (D-135) | 99.5 % or more |
| Crash-free sessions of the wave's app version | 99.5 % or more |
| Battery complaints | 1 % of the wave or less |
| Field data and battery (SH-19 to SH-21, D-507, G-qa-31): p95 mobile bytes per device-day at or below 1.25 x the route-size gate; no regression above 20 percent against the previous release on CPU, engine starts or battery drop; p10 of the 17:00 battery level at or above `cfg.telemetry.bat17_floor_pct` | all three |
| Immediacy (SH-22, SH-23, D-509, G-qa-33): 95 percent of online-captured rows ACKed within 60 s and 99 percent within 5 min; 90 percent of devices ACKed within 3 min of reconnecting; no device holding rows beyond 24 h | all four |
| Open P1 tickets | 0 |
| Install success on the pre-bind day (D-562) | `cfg.cutover.install_success_pct` (95) or more |
| Apsis residual selling (SH-26, D-556) | zero unexplained `apsis_residual` rows after day 1 |

**Pilot reserve and extension (D-529, G-qa-54).** The pass rule of 10 consecutive trading days with zero category C resets on every category C; the plan keeps a 2-week pilot reserve inside the contingency of doc 14 s2.3, allows at most 2 resets and an extension of at most 4 weeks, the sponsor's delegate decides each extension, and a third reset or a 5-week overrun triggers a re-plan by the sponsor (gate T-7-159). **Rollback triggers (pre-agreed, read after submit settle, D-64):** reconciliation mismatch above 2 % of routes; sync success below 97 % for 1 hour; crash rate above 2 % of sessions for the wave's app version; any data-corruption class bug. Action: `cfg.flag.new_app_login_enabled = false` at wave scope; captured rows keep uploading; the wave's data is exported for the Apsis side (D-148).

### 7.8 Support and escalation flow (D-149; T-7-85)

| Tier | Who | Hours | Can fix (tool) | Escalates |
| --- | --- | --- | --- | --- |
| 0 Self | SR | n/a | read the on-screen reason (bn); Sync button; printer re-pair; in-app help page; status banner and helpdesk number on the login screen (`GET /config/public`) | anything else |
| 1 AMO | zone AMO | selling hours | explain rejected rows (reason text bn); check the SR's reconciliation screen; verify outlet requests; confirm the SR's route assignment; coach on force sale; request PDA to Support | password, OTP, device, config |
| 1.5 TSO | territory TSO | selling hours | view and re-issue the device OTP; mark a device replaced; reset an SR password (temporary, forces change); zone sync-health; Final Submit; zone radius within bounds (T-3-60); reopen a day where the role is allowed | route reassignment across zones, quarantine fixes, releases |
| 2 Helpdesk L1 | 8 to 12 Bangla agents (D-149); phone and messaging | 07:00 to 21:00 Dhaka every selling day; wave days 1 to 3 add night cover by forwarded phone and on-call | account unlock; OTP bulk; route-assignment fix with TSO confirmation; read a device's sync status on the admin device page (read-only L1 role, Q55); "fix and accept" of a quarantined row only for data-entry reasons with a four-eyes rule; known-issue lookup; ticket creation with `device_id`, `route_id`, screenshots and the support code | code, C3 config, data corrections touching money |
| 3 Engineering on-call | 1 engineer and 1 backup | 24 h during waves, business hours otherwise | config revert; other quarantine fixes; regenerate a user's bundle; re-run aggregation for a date or route; hotfix request | n/a |
| 3 Release | tech lead and release manager | on call | hotfix through `promote-prod.yml hotfix = true`; `blocked_versions` | n/a |

**Support code (G-field-15):** every blocking screen shows a short code the SR can read aloud: error code, build number, config version, a 3-digit CRC of `device_uuid` (not its last 4 characters: doc 17 s10.7 is authoritative, D-541), bind state, free storage, battery; the Support desk page P19 of doc 19 s5.2 decodes it and searches by username, phone, employee code, memo, outlet and batch (T-2-157). **SLA (ASSUMPTION except P1):** P1 "SR cannot sell or print" 15 minutes to respond and 2 hours to a workaround (D-149); P2 "cannot sync or wrong counts" 1 hour and the same day; P3 cosmetic the next day. **Known-issue board:** published to AMOs and TSOs daily during waves; **status banner** through `cfg.ops.maintenance_banner`.

**Staffing arithmetic (ASSUMPTION: 3 to 5 % of switched reps contact support on day 1 and under 1 % by day 5; 60 % of contacts fall in the 6 peak hours; an agent handles 6 contacts per hour with scripts, about 40 per day; coverage of 07:00 to 21:00 needs 1.75 shifts):**

| Wave (new reps switched in that wave) | Routes | SRs (routes / 1.33) | Day-1 contacts | Peak per hour | Concurrent agents | Rostered headcount |
| --- | --- | --- | --- | --- | --- | --- |
| Wave 1 (one territory per wing) | about 390 (D-147 says about 300; doc 14 s8.2 computes 10 territories at 39 routes) | about 290 | 9 to 15 | about 1.5 | 1 | 2 |
| Wave 1 if Q51 says 1,000 SRs | about 1,330 | 1,000 | 30 to 50 | 5 | 1 | 2 |
| Wave 2 (one division per wing; 2,270 routes cumulative) | about 1,880 | about 1,410 | 42 to 71 | 7 | 2 | 4 |
| Wave 3 (half the fleet cumulative) | about 3,400 | about 2,550 | 77 to 128 | 13 | 3 | 6 |
| Wave 4 (the rest) | about 5,670 | about 4,250 | 128 to 213 | 21 | 4 | 7 |
| A single full-fleet day (not planned) | 11,336 | 8,500 | 255 to 425 | 43 | 8 | 14 |

The 3 to 5 percent contact rate is an ASSUMPTION with no source, and it ignores AMO and TSO contacts, retailer disputes and the load that needs a human (location-request escalations, absence exceptions, OTP and bind help): the pilot therefore LOGS every contact by category (SR, AMO, TSO, retailer dispute) with the real L1 tooling (T-7-154), and wave 1 is staffed for the MEASURED rate and a 15 to 25 percent day-1 stress case, with a named L2 on call for each master-data, security and ops_admin action of doc 19 s5.1b (D-553, G-qa-85). The pool of 8 to 12 agents (D-149) holds for waves of up to half the fleet; a single full-fleet day would exceed it, which is a further reason for waves. The measured contact rate of each wave replaces the assumption before the next wave is staffed (D-455). The Bangla scripts cover the top 20 issues: cannot log in (password, OTP, `min_version` banner); bundle not downloading; "not within range" (force sale); printer not found; memo totals look wrong (which offer); rejected-row reasons (each DQ code); sync pending for hours (Wi-Fi-only photos versus rows); phone date and time wrong; check-out before 17:00; Sales Submit with dues; shared phone second user; app update; storage full; permission denied (location, camera, Bluetooth); which radius applies; Final Submit refused (already done); the old app still open; stale-bundle banner; battery concern; where to find help.

### 7.9 Training, communications and decommission

AMOs and TSOs: one hour hands-on per territory the week before their wave, using the lab's scripted day on their own phones. SRs: no classroom (the no-retraining mandate): a 90-second Bangla video of the three visible changes pushed by the TSO. Retailers: nothing, the memo is the same (T-2-41, T-7-142). The baseline recordings double as before-and-after material.

**Decommission (7e, T-7-88, D-155):** all waves stable for 10 trading days; the final Apsis delta imported and reconciled; Apsis credentials in the dump rotated or removed (T-7-74); the raw dump archived to immutable storage and deleted 30 days after reconciliation; the memo-number continuity outcome recorded in DECISIONS.md (Q5); the pilot double-entry data archived; `cfg.flag.pilot_in_rollups = true`; the parallel-run infrastructure removed.

Proved by: T-7-01, T-7-02, T-7-80 to T-7-89, T-7-140 to T-7-145, T-7-58, T-7-60, T-7-83.

## 8 Traceability mechanism

### 8.1 Files (all under `/plan/`, checked by CI; the tables of doc 14 and this document's register are generated from them)

| File | One row per | Key fields |
| --- | --- | --- |
| `rtm.yaml` | F-id of doc 15 | `id, name, R[], role, sub, spec_ref, flags{offline, idempotent, scope, i18n, battery}, cfg[], gaps[], decisions[], questions[], gates[], evidence, status` |
| `gates.yaml` | T-id | `id, sub, lens, check, proves{F[], G[]}, test, artefact, verifier, run, blocking, status, aliases[], last_run, run_url, lead_time_h, depends_on[]` (D-515: `lead_time_h` is the calendar time the gate needs after its build, for example 72 for an ingest soak, 120 for the dogfood week, 8 for a battery run, 48 for the OEM matrix; `depends_on` lists the gates and F-ids it needs; the calendar critical path per sub-milestone is computed from them by T-0-155 and printed in doc 14 s2.5) |
| `capture-map.yaml`, `manual-coverage.yaml` | app capture column; manual screen, message, rule or entity row | see s8.2 rules 9 and 11 (D-500, D-531) |
| `gaps.yaml` | master G-id and every G-20-nn | `id, title, severity, owner_doc, phase, closing_gates[], closing_text_ref, decision, status` |

Config keys come from the registry seed (doc 19) and decisions from DECISIONS.md; neither is duplicated.

```yaml
# gates.yaml entry (shape only)
- id: T-2-41
  sub: 2a
  lens: QUA
  check: "printed-memo parity against the baseline corpus"
  proves: { F: [F-SR-066], G: [G-sync-02, G-qa-01] }
  artefact: docs/evidence/phase-2/T-2-41/parity.md
  verifier: SD
  run: PH
  blocking: Y
  status: planned   # planned | automated-green | human-pending | signed | pending-oracle | waived | unproven
  aliases: []
  lead_time_h: 0
  depends_on: []
```

### 8.2 `rtm-check` rules (the build fails when any is violated)

1. Every F-id of doc 15 appears in `rtm.yaml` with a sub-milestone and one gate or more; an F with `offline: Y` has a gate tagged `offline`; an F that writes a device-originated table has a gate tagged `idempotency`; an F with a read endpoint has a `scope` gate; an F with UI has an `i18n` gate; a battery-relevant F has the zero-chatter or budget gate.
2. Every T-id has an artefact path, a verifier different from the owner, a run cadence and a blocking code; every blocking gate is referenced by one F or one G or more; the duplicate-id check passes across all lens ranges.
3. Every master gap has an owner, a closing sub-milestone and a closing gate; a blocker's closing sub-milestone is not later than the phase whose exit it blocks; a gap with no closing text in its owner document by its phase fails the build (doc 14 s7).
4. Every `cfg.` key in the registry is consumed by one F or more (else the dead-key report); every key used in code is registered (T-0-63); no retired alias appears.
5. Every R1 to R6 maps to gates in the Traceability table and every gate named there exists.
6. Every automated test tag `T-x-nn` corresponds to a gate; a gate marked automated with no tagged test in the last 7 days of runs is `unproven`.
7. A `pending-oracle` gate lists its open question; the phase exit report prints the count and fails the exit when a blocking gate is still `pending-oracle` (D-451).
9. CAPTURE MAP (D-500, G-qa-25, G-qa-40): every device-captured column of every OFFLINE or QUEUED app table maps to a dw column in `/plan/capture-map.yaml` or to a signed exclusion row (doc 16 s8.9.2); an unsigned exclusion, a new capture column without a map row or a mapped column that does not exist fails. This is the lint of T-0-150, and it is what makes "any report, any time, without touching the log" decay-proof.
10. ORDERING (D-513, G-qa-36): the sub-milestone of every gate and of every demo step (s10.1) is at or after the `Ph` of every F-id and named screen it exercises; a gate or a demo that needs a later deliverable fails the build. The rule is run over docs 14 and 20 before 0a exits (T-0-152).
11. MANUAL COVERAGE (D-531, G-qa-56): `/plan/manual-coverage.yaml` has one row per manual screen (192), message (225 app-owned of 251), rule (416) and entity or field row (SR-E 39, AMO-E 22, TSO-E 29, Web-E 25), each mapped to an F-id, a cfg key, a doc 16 table.column or an explicit "not built, because X"; an unmapped row fails.
12. API ORDER (D-533, G-qa-58): a feature's phase is at or after the phase of every API it lists, so read and write halves of F-API-017, F-API-021 and F-API-035 carry their own sub-milestones.
13. COUNTS (D-516, G-qa-39, G-qa-69; extended D-576, G-qa-99): the F-id, gate, key and gap counts AND the week counts and key numbers printed in docs 14, 15, 18, 19 and 20 and in DECISIONS.md (the schedule weeks, the float, the storage figure, the first-grow month, the Premium Front Door date, the F-id counts) equal the numbers generated from `rtm.yaml`, `gates.yaml`, the registry seed and `/plan/constants.yaml`.
14. CONSTANTS (D-576, G-qa-112): every value that appears in more than one document (the first-grow month, the storage size, the Premium Front Door date, the role names, the Bangladesh bounding box, the config reach bounds) has ONE owner and one key in `/plan/constants.yaml` (cfg keys where they exist); the other documents cite the key, and a registered constant that appears with a different value anywhere in docs 14 to 21 or DECISIONS.md fails the build (T-0-159).
15. GATE BEHAVIOUR (D-581, G-qa-117): a feature may cite only gates whose text names one of the behaviours in its `covers` field; a gate named by more than 12 features fails; every feature has an explicit F-id to T-id row (doc 15 s12.6, T-0-163).
16. PRECEDENCE AND EVIDENCE (D-554, G-qa-86): CLAUDE.md and the README carry the precedence section of doc 14 s9.2b, docs 03, 06 to 10 and `db/schema.sql` carry the superseded-by banner, `/docs/evidence/` and `/plan/*.yaml` exist, and the DECISIONS.md header sentence is true (T-0-160).
8. Lints on the documents and code: an unqualified Submit-percent label (the labels are K-02 "Submit % (of logged-in)" and K-03 "Day-completion %"), the retention KPI name used on a tile before Q10 is answered (the field is `retention_candidate_pct`), a cross-category STD sum, and the plural of "unit" used without naming the unit (say sticks, pieces, dozens, boxes) all fail; a decision id cited but absent from DECISIONS.md fails; the vendor-string deny-list of T-0-120 fails the string catalogue.

### 8.3 Gate tags and the generated outputs

A test names its gate (`@gate T-1-21`); CI emits `gate-report.json` per run (gate id, test count, pass count, run URL, commit). `pnpm rtm:check` writes `/docs/evidence/RTM.md` and `/docs/evidence/GATES.md` and the per-phase `EXIT.md` embeds the generated gate table. The register of s3 is the reviewed source of the first `gates.yaml`; after T-0-46 the YAML is authoritative and this document is regenerated from it.

Proved by: T-0-46, T-0-63, T-0-142.

## 9 Test data strategy

### 9.1 `/packages/testkit` generators (D-151; every output is synthetic and PII-free)

| Generator | Produces | Used by |
| --- | --- | --- |
| `fleet --wings 1 --zones 3 --routes 12 --outlets 800 --seed N` | geography, users of every role, devices, routes with visit days, outlets with realistic density (80 % within 55 m of another, P-10), prices from `db/seed` stored in milli-taka, sales plan, targets, holidays | api integration, Playwright, staging seed |
| `day --date D --fleet F --online-pattern P` | a scripted field day per SR: visits, memos (2.5 lines per call at design, 1.95 observed; p99 4; maximum 40), zero sales 10 to 16 %, force sales 5 %, dues, edits, QC, photos (placeholder JPEGs of 150 KB or less), `client_uuid`s and batches cut by the pattern | fuzz corpus, k6, Locust, device-lab seeding |
| `apsis-dump --seed N` | CSVs in the docs/11 shape with planted defects (s9.3) and the synthetic "Apsis report" the control totals compare against | importer tests |
| `personas` | fixed logins per role and scope for e2e (`wm_w03`, `tso_t291`, `admin_cfg_editor`) | Playwright, patrol |
| `corpus` | anonymised real batches from pilot devices (after 7b): `client_uuid`s regenerated, ids remapped, no names, phones or free text, coordinates jittered | nightly fuzz replay |

### 9.2 Synthetic fleet on staging

Staging carries a full-size synthetic fleet regenerated monthly from a fixed seed: 10 wings, 50 divisions, 291 territories, 1,051 zones, 11,336 routes, about 460,000 outlets on a day's plan and 9,850 users (8,500 SRs plus AMOs, TSOs and supervisors; ASSUMPTION for the non-SR count). The Locust device simulator uses the design values of D-125 (350 rows per SR-day, 2.5 lines per call, 5 % duplicated batches, 2 % children before parents, 1 % resent under a new `batch_uuid`) so the load tests are shaped like the real day.

### 9.3 Planted defects in the synthetic dump (docs/22; the importer's assertions)

The CI generator is small (3 zones, 200 outlets, 2 months), so planted counts are scaled and fixed (ASSUMPTION: scaled up where the real rate would round to zero). Each defect has an expected outcome and the test asserts it exactly.

| P | Planted defect (real rate in the sample) | Expected importer outcome |
| --- | --- | --- |
| P-01, P-02 | volumes per day growing 53 % over 3 months; lines per outlet-day rising from 1.26 to 1.95 | load-model checks, not importer; the `day` generator reproduces the ramp |
| P-03 | Friday off, an Eid break, two single-day holidays, one traded Friday | `cfg.calendar` rows created; no trading-day target on non-working days |
| P-04 | every positive volume a multiple of the pack size | `qty_base` in sticks; a non-multiple is flagged, not rejected |
| P-05 | zero-volume lines (8.7 % of rows; one SKU 100 % zero); all-zero outlet-days 10 to 16 % | zero lines excluded from STD; an all-zero outlet-day imported as a zero-sale call (ASSUMPTION, D-249) |
| P-06 | duplicate (date, outlet, SKU) keys with different values (68 in 37.3 M rows; 5 planted) | both kept as separate visits or memos per D-250; none overwritten |
| P-07 | outlet codes: 7-digit numbers (94 %), 8 to 13 characters with letters, stray leading `:` `*` `।`, 1 to 2 digits | normalised with a crosswalk; unparseable codes quarantined with a reason |
| P-08 | outlets in the sales file but missing from the active list (175,031 against 734,789, 23.8 %; 48 planted) | archived stubs created with history, dues and loyalty; never deleted |
| P-09 | outlets sharing identical points (4.7 %; 9 planted on 3 points) and outlets without coordinates (0.15 %; 1 planted) | `location_confirmed = false`; no-location policy applies |
| P-10 | 80 % of outlets within 55 m of another | the density view and radius what-if use it; no importer effect |
| P-11 | `Created At` as `2025-12-19 01AM` (hour precision, no timezone), `Updated At` blank for 62 % | explicit parser, Dhaka assumed; blanks stay null |
| P-12 | NID placeholder `123` (80 %), blank (20 %); TIN and licence empty; address filled for 13 outlets | placeholder treated as null; PII columns nullable and classified |
| P-13 | route kinds in the name: Daily 3,242, 3F 6,083 (two alternating groups), 2F 2,009 (three groups) | `visit_kind` and `visit_days` parsed from the name and stored separately from it |
| P-14 | geo class blank for 19 %, "Semi Urban" spelling, nine sub-channels | maps to `SemiUrban`; null allowed; Astha tiers as sub-channels |
| P-15 | `sku_id` 1 to 43 with gaps at 19, 20, 40; two catalogue SKUs not sold | the export's `sku_id` is the crosswalk key |
| P-16 | lines of 10,000 sticks or more (0.07 % of lines; 10 planted) | accepted with the anomaly flag; no hard cap |

Quarantine assertion: quarantined rows by reason equal the planted unparseable codes exactly and stay under the 1 % threshold of D-153; a re-import of the same files is a no-op; a delta import applies only the delta; the nightly full-size synthetic run (1,051 zones, 460,000 outlets, 60 days) finishes in 4 hours or less on the staging SKU (ASSUMPTION; adjusted after the first real dump).

Proved by: T-0-45, T-0-140, T-7-02, T-7-80, T-7-100.

## 10 Phase exit checklists (the script a non-engineer runs)

Each script is stored as `demo.md` beside the phase's `EXIT.md`. The sponsor's delegate performs it unaided; any step that needs an engineer is a finding.

### 10.1 Demo per sub-milestone (at most 30 minutes each)

| Sub | Demo |
| --- | --- |
| 0a | open a PR that breaks a rule and watch the check fail; open the generated `GATES.md` (the gate report; it is a generated file, not a product page, so it has no F-id and no owner, D-558); open `/docs/baseline/INDEX.md` |
| 0b | create the database from scratch; show the seed price 7.935 round-trip; try to save a radius of 10 and be refused |
| 0c | log in as each role; try a wrong zone and be refused; show the 225-string catalogue count (251 inventoried); a new phone asks for the OTP and the TSO reads it on the OTP panel; open the Parity Exceptions Register and the usage census |
| 1a | airplane mode: check-in, geo check, one sale, print on the Bluetooth printer; kill the app mid-sale and relaunch |
| 1b | upload the same batch twice and show one copy; show the device and server counts equal |
| 1c | the sale appears on the dashboard tile; change the radius to 150 m and see the next visit use it |
| 2a | enter three baseline memos and compare totals and print with the paper |
| 2b | edit a memo, void a memo, mark a due paid, print the receipt |
| 2c | capture a new shop with a photo offline; show the photo uploading on Wi-Fi only |
| 2d | run a fake-GPS app and show the flag on the minimal web Exceptions list (the AMO's own app list is demoed in 3a, F-AMO-038); change a zone radius through the console |
| 2e | the scripted day on three phones; the updater; PDA to Support; the dress-rehearsal sheet |
| 3a | an SR's new-shop request verified on the AMO phone and approved in the minimal Outlet Approval Panel on the web; the mock flag of 2d on the AMO's own Exceptions list |
| 3b | a TSO Final Submit; the second attempt refused; the minimal sync-health shows the zone closed; leave, visit plan and a task queued offline and the task visible to the SR; a Sales Submit voided and the day continued |
| 4a to 4d | the sync-health screen with seed numbers; a report next to its baseline Excel; a Wing Manager persona; the S1 to S11 and S3b load report including the 8,000 rows/s ramp and the 17:00:00 spike; an alert reaching a phone; the R1 drill with three unplanned reports |
| 5a to 5c | a redemption that reconciles; a promotion applied and printed; a target revision through its approvals |
| 6a to 6c | the 12-task run-without-engineering drill; the audit viewer; a restore log |
| 7a | the import reconciliation report with zero difference; a re-import changing nothing |
| 7b | a pilot daily sheet and the 10-day tally |
| 7c, 7d | the wave-day war room: sync-health, go/no-go record |
| 7e | the decommission record |

### 10.2 Exit scripts

**Phase 0 (3 weeks)**

| Step | Do | Expect | Gate |
| --- | --- | --- | --- |
| 1 | Open the staging URL and log in as each of the seven roles with the test personas | each role sees only its patch | T-0-75 |
| 2 | Edit the URL to another zone | "not allowed", nothing shown | T-0-75 |
| 3 | Open the baseline index | items a to i (nine) each list their files, including the usage census | T-0-49, T-0-153 |
| 4 | Open the generated `GATES.md` | every Phase 0 gate is green or listed pending-oracle with its question; rtm-check rules 9 to 16 pass | T-0-46, T-0-152, T-0-159, T-0-160 |
| 5 | Try to save a radius of 10 m | refused | T-0-61 |
| 6 | Read the roster of delegates and owners and the dump-request letter with Apsis's acknowledgement | present | T-0-142, T-0-141 |

**Phase 1 (4.5 weeks)**

| Step | Do | Expect | Gate |
| --- | --- | --- | --- |
| 1 | Log in online, switch on airplane mode, check in, open an outlet, pass the geo check, sell, print | the memo prints; nothing needs a network | T-1-40 |
| 2 | Kill the app mid-sale and reopen | resume at Review; one memo, not two | T-1-24 |
| 3 | Switch airplane mode off | within 60 s the reconciliation screen shows equal counts | T-1-34 |
| 4 | Open the dashboard | the tile shows the sale within 2 minutes | T-1-40 |
| 5 | Resend the same batch | no second sale | T-1-22 |
| 6 | Change the radius to 150 m in the console | the next visit stores 150 | T-1-61 |
| 7 | Read the perf file | every budget within limit on the primary device; APK at most 30 MB | T-1-45, T-1-33 |

**Phase 2 (9 weeks)**

| Step | Do | Expect | Gate |
| --- | --- | --- | --- |
| 1 | Run a beat on a reference phone offline | all rows sync within 3 minutes of connectivity | T-2-40 |
| 2 | Re-enter 3 baseline memos and print | totals equal to the paisa; layout approved against paper | T-2-41 |
| 3 | Run a fake-GPS app | never geo-valid; on the minimal web Exceptions list (the AMO app list arrives in 3a, T-3-156) | T-2-47 |
| 4 | Change a zone radius through the console | a selling phone shows it within 15 minutes, an idle one at its next foreground | T-2-61 |
| 5 | Run the PDA to Support and the `min_version` drills | counts reproduced; pending rows still upload | T-2-46 |
| 6 | Read the dress-rehearsal sheet | zero category C | T-2-48 |
| 7 | Read the `/cso` result | no open high | T-2-73 |

**Phase 3 (5 weeks)**

| Step | Do | Expect | Gate |
| --- | --- | --- | --- |
| 1 | Capture a new shop (SR), verify it (AMO second phone), approve it (minimal web panel of 3a) | the SR's delta shows it active; another zone's AMO cannot see it | T-3-41 |
| 1b | Create a task as the TSO and an AMO; resolve it as the SR offline; apply for leave on the TSO phone and approve it as the DMO on the web | the SR sees and resolves the task; the DMO approves | T-3-155 |
| 2 | Final Submit a zone, then try again | closed once; the second attempt refused; the sync-health screen shows the zone closed | T-3-43, T-3-02 |
| 3 | Read the usability report | 90 % success; failures ticketed | T-3-44 |
| 4 | Read the AMO and TSO perf sections | battery, data, APK and cold start inside the s8.2b gates | T-3-151, T-3-152, T-3-153 |

**Phase 4 (6.5 weeks)**

| Step | Do | Expect | Gate |
| --- | --- | --- | --- |
| 1 | Open the national dashboard on the full-size synthetic fleet | well under a second | T-4-47 |
| 2 | Log in as a Wing Manager and try another wing | nothing shown | T-4-74 |
| 3 | Compare 5 reports with their baseline Excel | equal or an approved explanation | T-4-41 |
| 4 | Read the S1 report at 9,850 users | bundle p95 2 s or less; 0 lost rows | T-4-52 |
| 5 | Trigger an alert | the phone and Teams channel receive it | T-4-45 |
| 6 | Read the 4d scale report (S1 to S11 and S3b: the 8,000 rows/s ramp and the 1.5 x fleet, S2, S5 with S4, S6, the 10-hour soak, S9, S10 with the absolute-expiry herd, S11, the 17:00:00 spike, DR replica lag) | every blocking scale gate green; the S1 report alone does not close the phase | T-4-155 to T-4-164 |
| 7 | The analyst delivers three reports of the delegate's choosing from dw only | three outputs, no change to ingest or the worker | T-4-150, T-4-151 |

**Phase 5 (5 weeks)**

| Step | Do | Expect | Gate |
| --- | --- | --- | --- |
| 1 | Redeem points for a gift | ledger, redemption and photo reconcile | T-5-40 |
| 2 | Sell under a promotion | applied and printed correctly | T-5-41 |
| 3 | Revise a target through its approvals | each level a different person; audit rows present | T-5-42 |
| 4 | Enter a negative target | refused; the report renders a dash | T-5-42 |

**Phase 6 (4 weeks)**

| Step | Do | Expect | Gate |
| --- | --- | --- | --- |
| 1 | Complete the 12 admin tasks of the drill without an engineer | each within its time box | T-6-42 |
| 2 | Find the audit row of three of them | present | T-6-42 |
| 3 | Read the restore log | counts and audit-chain head equal | T-6-43 |

**Phase 7 (10 or more weeks)**

| Sub | Do | Expect | Gate |
| --- | --- | --- | --- |
| 7a | Read the import reconciliation | zero difference on counts and balances; quarantine at most 1 % with reasons; a re-import changes nothing | T-7-80, T-7-02 |
| 7b | Read the 10-day tally, the delta rehearsal, the rollback return-path drill and the parity register | 10 consecutive trading days with zero category C; the delta path ran; dues equal after a rollback; no unsigned item | T-7-82, T-7-150, T-7-153, T-7-158 |
| 7c | Sign the readiness checklist at T-1; watch the war room on the day; sign go/no-go that night | thresholds of s7.7 met | T-7-84, T-7-86 |
| 7d | Repeat per wave | each wave stable 3 trading days before the next | T-7-84, T-7-86, T-7-87 |
| 7e | Read the decommission record | all criteria ticked | T-7-88 |

Proved by: T-0-46, T-1-47, T-2-48, T-6-42, T-7-84, T-7-88.

## 11 Gaps owned

### 11.1 The 16 master gaps assigned to this document (3 blockers, 8 major, 5 minor), each resolved here

| Gap | Sev | Phase | Resolution (where) | Closing gates | Decision |
| --- | --- | --- | --- | --- | --- |
| G-qa-01 No parity oracle | blocker | 0a | The baseline pack of s4.1 (recordings, 25 or more printed memos, web captures, Excel exports, string glossary, manuals) is a Phase 0 gate and the Phase 1 entry criterion; golden fixtures and screen goldens derive from it (s4) | T-0-49, T-1-41, T-2-41, T-4-41 | D-142 |
| G-qa-03 No machine-readable Apsis daily data for the parallel run | blocker | 7a | The per-route daily feed is requested in the dump letter and dry-run on 5 historical days; the manual-keying fallback is rehearsed; days without the feed do not count toward the 10 (s7.3) | T-0-141, T-7-145, T-7-82 | D-154, D-453 |
| G-qa-04 No helpdesk, hours, scripts or TSO toolkit | blocker | 7c | Tiers 0 to 3, SLA, scripts for the top 20 issues, support code, known-issue board, staffing arithmetic recomputed per wave (s7.8) | T-7-85, T-2-58 | D-149, D-455 |
| G-qa-08 No test-data strategy | major | 0a | `/packages/testkit` generators, synthetic full-size staging fleet, the Apsis sample never in CI, planted-defect dump generator (s9) | T-0-45, T-0-140, T-7-100 | D-151 |
| G-qa-10 No CI mechanism for expand/contract compatibility | major | 0a | `squawk`, shipped-migration checksums, nightly `schema-compat`, `oasdiff`, the N-2 upgrade matrix, three-release discipline (s5.6) | T-0-145, T-1-46, T-2-44, T-2-33 | D-14, D-150 |
| G-qa-14 No reconciliation report for the dues and loyalty ledgers | major | 2b | Nightly ledger-balance report with block-on-difference (s6.4) | T-2-143, T-4-46 | D-37, D-41 |
| G-qa-09 Load-test windows and Azure Load Testing quota have no budget approval | major | 4d | Approval record per window, quota checklist, scripted scale-up and scale-down with a cost alert (s2.2, s7.1) | T-4-140, T-7-55 | D-133, D-134 |
| G-feat-67 The old Apsis app cannot be set read-only by AKTCL | major | 7b | The business documents and rehearses a retirement procedure; the wave lever is `cfg.flag.new_app_login_enabled`; unknown; confirm with the business how the old app is stopped | T-7-144, T-7-143 | D-148 |
| G-field-12 Parallel-run pilot prints two memos | major | 7b | `cfg.flag.parallel_run_mode` with the test-print watermark, no previous-due line, parallel dues excluded, the briefing card (s7.2) | T-2-99, T-7-141 | D-152 |
| G-qa-11 Wave rollback leaves new-app data Apsis never sees | major | 7b | The wave-data export job in the dump's shape, proved by re-import into a scratch database and in the rollback drill (s7.5) | T-7-143, T-7-83 | D-148 |
| G-qa-16 No pilot route criteria, consent or incentive | major | 7b | Selection criteria, consent and incentive checklist (s7.2); unknown; confirm with the business (Q33) | T-7-141 | D-147 |
| G-qa-20 Tests have no injectable clock | minor | 0a | `Clock` interface, three fixed Dhaka instants, `no-wall-clock` lint (s2.1 P3) | T-0-47 | D-20 |
| G-qa-21 Bluetooth printing cannot be tested in CI | minor | 1a | `PrinterPort` fake with raster goldens in CI; physical gates stay in the lab (s2.5) | T-1-141, T-1-35 | D-76 |
| G-qa-23 One clean parallel day proves little | minor | 7b | Ten consecutive trading days with zero sync loss (s7.3) | T-7-82 | D-145 |
| G-qa-24 The retailer is never asked whether the memo is "the same" | minor | 7b | Retailer check and the pilot sheet line 15 (s6.5, s7.2) | T-7-142, T-2-41 | D-146 |
| G-sec-23 Pilot devices hold Apsis-migrated PII beside the old app | minor | 7b | Pilot bundles limited to pilot routes, per-user encryption, no PII in logs or PDA uploads, wipe at pilot end, coexistence test (s7.2) | T-7-140, T-2-141 | D-458 |

### 11.2 New gaps raised while writing (minted in the block G-20-01 to G-20-40; doc 14 adds owner and phase to its register)

| ID | Sev | Phase | Gap | Proposed owner and handling | Gate |
| --- | --- | --- | --- | --- | --- |
| G-20-01 | major | 0a | The baseline pack and evidence repository hold retailer phones, owner names and rep identities in recordings, memo photos and exports; nothing stores or masks them | doc 20 owns: private path, access list, masking before storage, manifest (D-452) | T-0-140 |
| G-20-02 | minor | 1c | The lens probes TLS on "Android 7/8" while D-11 sets minSdk 26 (Android 8.0); the legacy class is ambiguous | doc 17 with D-11: legacy device is Android 8.x unless the census (Q31) shows Android 7 and D-11 is amended | T-2-34 |
| G-20-03 | major | 2a | Several manual behaviours have no oracle (MQ-01 to MQ-07, Q57, Joint Call items); a gate cannot be green against an unknown | doc 20: `pending-oracle` state (D-451) with the list of s4.7 | s4.7, T-0-46 |
| G-20-04 | major | 2e | Whether trigger T4 fires on MIUI, Realme and Vivo phones after an OEM kill is unmeasured; "immediate sync" (R5) is proved only on the lab happy path | doc 17 and doc 20: the OEM matrix sets `cfg.app.oem_guidance` | T-2-140 |
| G-20-05 | minor | 2e | 91 human-signed gates (67 before the round-2 pass) depend on the availability of a few named verifiers | doc 14: deputy per phase, escalation after 5 working days (s3.13) | T-0-142 |
| G-20-06 | major | 7a | The Apsis monthly reports may include sales not made in the apps (D-245), so STD control totals against them could fail for a legitimate reason | doc 16 and doc 20: reconcile history to the dump's own rows; monthly STD comparison informational until D-245 is answered | T-7-80 |
| G-20-07 | minor | 1c | The baseline pack is static; if the live Apsis app changes mid-project the oracle ages (the live app is already newer than the manuals) | doc 20: re-capture on any Apsis version change; version string on every capture | T-0-49 |
| G-20-08 | minor | 1a | The lab has 2 printer models; the fleet's printer models are unknown, and clone printers differ in density and Bangla raster legibility | doc 17: printer model per device in the pilot sheet (line 11) and a printer census | T-1-35, T-7-82 |

### 11.2b Round-2 gaps closed in this document (skeptic review; the text of every decision is in DECISIONS.md section M3)

| Gap | Decision | Where | Gates |
| --- | --- | --- | --- |
| G-qa-25, G-qa-40 | D-500 | s8.2 rule 9, s3.14 | T-0-150, T-4-150, T-4-151 |
| G-qa-26 | D-501 | s3.14 | T-0-151 |
| G-qa-27, G-qa-47, G-qa-61 | D-502, D-503, D-523 | s4.1 item i, T-7-88 | T-0-153, T-7-158 |
| G-qa-28 | D-504 | s3.14 (4d gates), s10.2 Phase 4 | T-2-155, T-4-155 to T-4-164 |
| G-qa-29 | D-505 | s3.14 | T-4-155 |
| G-qa-30 | D-506 | s2.7 (7a), s3.14 | T-3-151 to T-3-153 |
| G-qa-31, G-qa-33 | D-507, D-509 | s6.5 lines 21 and 22, s7.7 | T-2-153, T-2-154, T-7-155 to T-7-157 |
| G-qa-32 | D-508 | s2.7 | T-1-36, T-2-40 |
| G-qa-35 | D-512 | T-2-65 | T-2-65 |
| G-qa-36 | D-513 | s8.2 rule 10, s10 | T-0-152, T-3-156 |
| G-qa-37 | D-514 | T-0-141, s3.14 | T-7-150, T-7-151 |
| G-qa-38 | D-515 | s8.1 (`lead_time_h`) | T-0-155 |
| G-qa-39, G-qa-59, G-qa-69 | D-516 | s8.2 rule 13, T-0-46, T-0-120 | T-0-46, T-0-152 |
| G-qa-41 | D-517 | T-1-56, T-7-54 | T-1-56, T-1-152, T-7-54 |
| G-qa-42 | D-518 | s3.14 | T-4-162 |
| G-qa-43 | D-519 | T-1-63 | T-1-63 |
| G-qa-44 | D-520 | s3.14 | T-1-150, T-2-150 |
| G-qa-45 | D-521 | T-1-53 | T-1-151, T-4-163 |
| G-qa-46 | D-522 | T-7-52 | T-4-164, T-7-52 |
| G-qa-48 | D-511 | T-2-140 | T-2-140 |
| G-qa-49 | D-524 | T-0-07 | T-0-07 |
| G-qa-51 | D-526 | T-2-61 | T-2-61, T-6-01, T-6-41 |
| G-qa-52, G-qa-77 | D-527 | T-2-68 | T-2-160, T-6-10 |
| G-qa-53 | D-528 | T-4-54 | T-4-54 |
| G-qa-54 | D-529 | s7.7 | T-7-159 |
| G-qa-55 | D-530 | T-0-46, T-0-120 | T-0-154 |
| G-qa-56 | D-531 | s8.2 rule 11 | T-0-152 |
| G-qa-57 | D-532 | T-3-26, T-5-43 | T-3-155 |
| G-qa-58 | D-533 | s8.2 rule 12 | T-0-152 |
| G-qa-62 | D-535 | s3 "proves F" column | T-0-46 |
| G-qa-70 | D-539 | T-3-61 | T-3-150 |
| G-qa-71, G-qa-72, G-qa-83 | D-540, D-541, D-551 | s7.8, T-6-42, T-7-85 | T-2-157, T-2-158, T-2-159, T-3-154 |
| G-qa-73 | D-542 | s3.14 | T-4-154 |
| G-qa-74 | D-543 | s3.14 | T-2-151 |
| G-qa-76 | D-545 | s3.14 | T-4-152 |
| G-qa-78 | D-546 | s2.7 (7b) | T-2-156 |
| G-qa-80 | D-548 | s3.14 | T-4-153, T-7-152 |
| G-qa-81 | D-549 | s7.5 | T-7-153 |
| G-qa-82, G-qa-85 | D-550, D-553 | T-6-42, T-7-85, s7.8 | T-6-42, T-7-85, T-7-154 |

Gate-to-feature mapping (D-535, G-qa-62): the register of s3 gains a `proves F` column generated from `gates.yaml` (`proves.F`), and doc 15's Gates column lists individual T-ids instead of a sub-milestone family, so rtm-check rule 1 can no longer be satisfied by a security gate that never exercises the feature (the 22 4c rows had pointed at `T-4-14, 70..76`).

### 11.2c Round-3 gaps closed in this document (skeptic review, D-554 to D-601)

| Gap | Decision | Where | Gate |
| --- | --- | --- | --- |
| G-qa-86, G-qa-118 | D-554 | s8.2 rule 16, s3.15 | T-0-160, T-0-161 |
| G-qa-87 | D-555 | s3.15 | T-0-162 |
| G-qa-88, G-qa-122 | D-556 | s7.4, s7.6, s7.7, T-7-150, T-7-151 | T-7-163, T-7-164 |
| G-qa-89 | D-557 | s7.4, s7.6 | T-7-161 |
| G-qa-90, G-qa-95 | D-558 | s3.3 (T-0-100, T-0-150 to T-0-152), s3.4, s3.5, s10 | T-0-156, T-1-157, T-1-158, T-2-176 |
| G-qa-93 | D-561 | s7.1 | T-7-165 |
| G-qa-94 | D-562 | s7.1, s7.6, s7.7 | T-2-164, T-7-166 |
| G-qa-96, G-qa-106 | D-563 | T-2-61, T-1-61, T-6-41, s7.5 | T-2-165, T-2-174, T-4-161 |
| G-qa-98 | D-565 | s4.1, T-7-158, T-7-88 | T-7-158 |
| G-qa-100, G-qa-101 | D-566 | s3.15 | T-0-158, T-1-153, T-1-154 |
| G-qa-102 | D-567 | s3.15 | T-1-155, T-3-158 |
| G-qa-104 | D-569 | s7.1, s7.5, s7.6 | T-4-174 |
| G-qa-105 | D-570 | s7.1 | T-7-160 |
| G-qa-107 | D-571 | s3.15 | T-2-162 |
| G-qa-108 | D-572 | s3.15 | T-1-156 |
| G-qa-109, G-qa-110 | D-573, D-574 | s7.1, s7.5 | T-7-162, T-4-173 |
| G-qa-111 | D-575 | s3.15 (S5 audience in doc 18) | T-4-158 |
| G-qa-112 | D-576 | s8.2 rules 13 and 14 | T-0-159 |
| G-qa-113 to G-qa-117 | D-577 to D-581 | s3.15, doc 15 s12.6 | T-4-166, T-4-167, T-3-159, T-2-161, T-0-163 |
| G-qa-119 | D-582 | doc 14 s4.4 | T-5-123 |
| G-qa-123 to G-qa-127 | D-584 to D-588 | s3.15 | T-2-163, T-3-157, T-2-167, T-2-168, T-2-173 |
| G-qa-128, G-qa-129 | D-589, D-590 | s3.15, T-6-62 | T-2-166, T-2-172 |
| G-qa-130 | D-591 | s3.15 | T-0-157 |
| G-qa-131 | D-592 | s7.5, T-7-153 | T-7-168 |
| G-qa-132 to G-qa-139 | D-593 to D-600 | s3.15, T-2-158, s6.6 | T-2-169, T-2-170, T-6-150, T-6-152 |

### 11.3 Decisions raised here (block D-450 to D-469; copied to DECISIONS.md by doc 14)

| ID | Decision | Status | Why | Docs |
| --- | --- | --- | --- | --- |
| D-450 | T-ids are unique across all lens ranges; the phase digit is the phase in which the gate must be green; the sub-milestone is where the feature exits; analyst gates move to 100 to 103 and manual-parity gates occupy 120 to 139 per phase; doc 20 additions start at 140 | DEFAULT | five sources minted overlapping ranges | 14, 20 |
| D-451 | A gate whose oracle is unknown is `pending-oracle`: it is written against the proceed-with default, never deleted, and does not count as green for the phase it blocks | DEFAULT | prevents a silently green gate on a guess | 14, 20 |
| D-452 | Every evidence directory carries `MANIFEST.json` (sha256 per file, commit, run URL, verifier, date); frames, photos and memos showing retailer or rep data are masked before storage; the baseline pack is in a private path with an access list | DEFAULT | P-12 shows phone and owner are real PII | 20, 21 |
| D-453 | The parallel compare job runs at 20:30 Dhaka per trading day, the report is due 21:00 and reviewed by 09:00; a day with the Apsis feed missing for any pilot route does not count toward the 10 | DEFAULT; MUST-CONFIRM (by 7b): the feed (Q32) | the pass rule must be computable | 20 |
| D-454 | Control-total tolerance is 0 for counts, balances and loyalty; STD may differ by at most 0.01 % only where the Apsis report rounds and the rounding is documented per report; dues need FIN's signature | DEFAULT; MUST-CONFIRM (by 7a): which Apsis reports round | docs/11 "must match" | 20 |
| D-455 | Support staffing is recomputed before each wave from the previous wave's measured contact rate; the arithmetic is peak-hour contacts divided by 6 per agent-hour, times 1.75 for 07:00 to 21:00 coverage; the 8 to 12 agent pool is the cap | DEFAULT (ASSUMPTION inputs) | D-149 gives the pool, not the rule | 20 |
| D-456 | Inside a wave an app version rolls out at `wave_pct` 1 %, 10 %, 50 %, 100 % over 3 or more trading days; each step needs crash-free 99.5 % or more, a rejection rate not above the fleet's and no category C; below `cfg.sla.crash_free_min_pct` the percentage pauses | DEFAULT | a bad build must reach few devices first | 17, 20 |
| D-457 | Security testing follows the cadence of s2.8, including `/cso` before the pilot and an independent penetration test before wave 1 | DEFAULT | CLAUDE.md "security officer" stage | 20, 21 |
| D-458 | Pilot phones carry only pilot-route bundles; both apps keep separate storage; at pilot end a reconciled logout wipes the new app's data; no tool of ours touches the Apsis app | DEFAULT | G-sec-23; guardrail | 20, 21 |
| D-459 | Flags marked `test_both` run the api and web suites all-on and all-off nightly; release flags older than 30 days at 100 % are listed for removal | DEFAULT | D-99 made checkable | 19, 20 |
| D-460 | The pilot daily sheet of s6.5 (20 measures, filled by 09:30) is the single input to the daily pilot review and, with the wave's 5-day figures, to the go/no-go | DEFAULT | one source of numbers | 20 |

### 11.4 Register entries G-man-001 to G-man-104: the gate that verifies each

No manual-register entry is owned by this document (each has an owner in docs 15, 16, 17, 19 or 21); each is verified by a gate here. Where an entry was merged, the master id is in brackets once.

| Entries | Gate |
| --- | --- |
| 001 (G-data-04), 038 | T-2-121, T-2-38 for 001; T-4-121 for 038 |
| 002, 004, 009 | T-2-120, T-2-37, T-2-36 |
| 003 | T-2-122 |
| 005, 007 | T-2-121, T-2-39 |
| 006 | T-1-122 |
| 008, 043 | T-2-123 |
| 010, 012, 013 | T-2-125 |
| 011, 032, 034, 035 | T-3-124 |
| 014 (G-sync-02), 023 | T-1-35, T-1-141, T-2-41 for 014; T-1-123 for 023 |
| 015, 018, 044, 050, 051, 052, 055, 056, 057 | T-3-120 |
| 016, 019, 033 | T-2-126 |
| 017 | T-2-127 |
| 020 | T-1-120 |
| 021 (G-feat-11) | T-1-70, T-3-71, T-7-75 |
| 022 (G-sync-15), 024 (G-sync-16), 025 | T-2-130, T-2-23, T-3-23 |
| 026 (G-qa-05) | T-0-48 |
| 027, 028, 084 | T-3-125 |
| 029 | T-2-128 |
| 030, 031 | T-2-129, T-1-34 |
| 036, 088 | T-6-120 |
| 037, 103, 104 | T-0-121, T-0-122 |
| 039, 062 (G-feat-59) | T-4-125 for 039; T-3-121, T-4-125 for 062 |
| 040, 041, 042 | T-5-120 |
| 045, 046, 047 (G-feat-21), 048 | T-5-121 |
| 049, 068 (G-feat-24), 090 | T-5-122 |
| 053, 054 | T-2-124 |
| 058, 059 | T-3-122 |
| 060, 061, 063, 064, 067 | T-3-121 (067 also T-3-127) |
| 065 | T-3-123 |
| 066 (G-data-05), 072, 073, 076, 100 | T-3-127, T-1-43 for 066 |
| 069, 070, 071, 077 | T-3-126 |
| 074 (G-feat-25), 075 (G-feat-25), 078, 079, 080, 081, 082, 083 | T-3-128 |
| 085, 086, 087, 089 | T-4-123 |
| 091 (G-feat-34) | T-4-124 |
| 092, 093, 094, 098 | T-4-122 |
| 095, 097 | T-4-121 |
| 096, 099 | T-4-120 |
| 101 | T-0-120 |
| 102 | T-1-121 |

Counts: 104 entries, 37 manual-parity gates (T-0-120 to T-6-120), every entry mapped.

Proved by: T-0-46 (`gaps.yaml` closure check), T-0-142, T-7-141, T-7-143, T-7-144, T-7-145.

## Open items

| ID | Item | Why open | Owner role | Needed by | What proceeds meanwhile |
| --- | --- | --- | --- | --- | --- |
| OI-20-01 | Sponsor's delegate per phase and the four readiness owners (D-156; Q34) | named persons are not known | sponsor | 0a | the roles of s1.3 are the contract; the tech lead signs engineering gates; no human gate is signed until its verifier is named |
| OI-20-02 | Baseline-pack access: a pilot phone, the web login, 25 or more printed memos, sample `.xlsx` files, the five live-app unknowns (D-142; Q57, MQ-47, MQ-57) | only AKTCL can supply them | sponsor's delegate | 0c (Phase 1 entry) | capture starts in 0a; gates depending on a missing item are `pending-oracle` (s4.7) |
| OI-20-03 | Fleet census and reference devices (D-11, D-12; Q31) | the budgets are valid only for the reference class | operations | 0c | the lab of D-12; battery and data gates are labelled provisional |
| OI-20-04 | Who signs "same memo" for retailers (D-146; Q37) | unknown; confirm with the business | sponsor | 2e | the sponsor's delegate with one SR and one retailer-facing manager |
| OI-20-05 | Per-route daily Apsis data in machine-readable form (D-154; Q32) | unknown; confirm with the business | business | 7b | manual keying from photographed summaries, pilot only |
| OI-20-06 | Quarantine threshold and adjudicator (D-153; Q39) | unknown; confirm with the business | sales operations, finance | 7a | 1 % per table; sales operations adjudicates history, finance adjudicates dues |
| OI-20-07 | Wave sizes, order and pre-bind-day feasibility (D-147, D-126; Q38, Q51, Q52) | unknown; confirm with the business | operations | 7b | wave 1 of about 300 routes; pre-bind day assumed feasible |
| OI-20-08 | How the old app is made read-only per wave (D-148; G-feat-67) | AKTCL cannot set it itself | business | 7b | a documented SR instruction plus monitoring Apsis activity after the switch time (T-7-144) |
| OI-20-09 | Parallel-run print mode (D-152; Q49) | unknown; confirm with the business | business | 7b | `print_test_watermark` |
| OI-20-10 | Helpdesk existence, hours, channels and the L1 read-only access (D-149, D-138; Q35, Q55) | unknown; confirm with the business | support | 7c | 8 to 12 Bangla agents provisioned by AKTCL; read-only L1 workbook role |
| OI-20-11 | Sentry data residency (D-13; Q36) | legal answer | security | 2e | Sentry with the scrubber; self-hosted on ACA as the fallback |
| OI-20-12 | Key spelling and registration: the decisions use `cfg.flag.pilot_in_rollups`, `cfg.flag.new_app_login_enabled`, `cfg.flag.parallel_run_mode` (doc 19 registers these spellings); `cfg.sla.crash_free_min_pct` (C1, default 99.0, bounds 95 to 99.9) is used here and is not in doc 19's registry | a proposed key needs registration | doc 19 | 0b | this document uses the decision spellings and treats the crash-free key as proposed Resolved at the editorial merge: `cfg.sla.crash_free_min_pct` is registered in doc 19 s3.2.6 (C1, 99.0, bounds 95 to 99.9). |
| OI-20-13 | Android 7 versus minSdk 26 (D-11; G-20-02) | fleet census | operations | 0c | legacy lab device is Android 8.x |
| OI-20-14 | Placement differences from the planning skeleton: T-1-35 is the print gate (the budgets baseline is T-1-45); T-7-82 and T-7-20 are the 7b run (the skeleton lists them in 7a); T-7-83 to T-7-87 are wave gates (7c) and T-7-88 is decommission (7e); T-2-43 is 2e (the skeleton lists 2b); T-0-41, T-4-41, T-5-41, T-6-41 and T-6-43 follow their deliverables; the change window is D-100's 07:00 to 09:30 (the lens said 10:00) | the skeleton's gate families were soft | doc 14 | merge | this register Resolved at the editorial merge: doc 14 s2.1 and the s3 phase tables now list the gates of this register per sub-milestone; the skeleton families are retired. |
| OI-20-15 | Whether every sale in the Apsis export comes through the apps (D-245; G-20-06) | unknown; confirm with the business | business | 1c | history reconciles to the dump's rows |
| OI-20-16 | Numbers marked ASSUMPTION: coverage floors, layer counts, contact rate 3 to 5 %, 6 contacts per agent-hour, 30 retailers, P2 and P3 SLA, go/no-go thresholds, human-gate minutes | no measurement exists | tech lead | after wave 1 | the stated values |
| OI-20-17 | Penetration-test vendor and window (T-7-70); load-test window budgets (T-4-140; G-qa-09) | budget decisions | sponsor | 7c; 4d | internal `/cso` and k6 only until approved |
| OI-20-18 | MUST-CONFIRM decisions whose gates here are `pending-oracle` until answered: D-16, D-17, D-18, D-19, D-33, D-35, D-37, D-38, D-51, D-52, D-58, D-76, D-86, D-95, D-146, D-153, D-154, D-156, D-185, D-191, D-198, D-207, D-262 (owners and dates in doc 14 s4; oracles in s4.7) | answers arrive per module | per doc 14 s4 | per doc 14 s4 | the proceed-with default of each decision |
| OI-20-19 | The register runs to 497 gates and the document exceeds its length budget; the generated `GATES.md` becomes authoritative after T-0-46 | the register is the deliverable | doc 14 | 0a | this document |
| OI-20-20 | Replica-lag alert threshold: doc 16 (T-4-108) says 120 s; D-128 and doc 18 say 60 s for degraded mode and the alert | two numbers for one alert | doc 16, doc 18 | 4a | 60 s (D-128 wins) Resolved at the editorial merge: 60 s everywhere (doc 16 T-4-108 text and s13 changed; D-128, D-427). |
| OI-20-21 | Gate ranges cited by docs 14, 15 and 18 that do not match a minted gate: T-2-25 to T-2-30 (named media SAS in doc 15; T-2-25 is the 2G catch-up and the SAS gates are T-2-53 and T-2-72), T-3-25 to T-3-28 (defined in s3.11), T-4-10 to T-4-13 (not minted; only T-4-14 exists in that block) | families were cited before ids were placed | doc 14, doc 15 | merge | this register Resolved at the editorial merge: docs 14 and 15 now cite T-4-14, T-5-10, T-6-51, T-2-26, T-2-30, T-2-53 and T-2-72 in place of the unminted ranges; T-3-25 to T-3-28 are defined in s3.11. |
| OI-20-22 | The numeric AMO and TSO gates of doc 17 s8.2b are ASSUMPTION until the 3a and 3b baselines (D-506) | no AMO or TSO device measurement exists | QA lead | 3a, 3b | the s8.2b values; the first RC sets the baseline x 1.2 |
| OI-20-23 | Sponsor signatures required by this pass: the jsonb list J-1 to J-8 and exclusions EX-01 to EX-06 (D-501, 0b), the R5(f) photo exception (D-510, 2c), the Parity Exceptions Register (D-502, 0c) | decisions only the sponsor can sign | sponsor | 0b, 0c, 2c | the lists as written; the exits are blocked unsigned |
| OI-20-24 | The T-5-43 id keeps its phase digit 5 while its first proof moved to T-3-155 (D-532) | gate ids carry the phase of the pass | doc 20 author | merge | T-5-43 becomes the 5c regression run; alias recorded in `gates.yaml` |
| OI-20-25 | The fifty-seven gates of s3.15 have verifiers and run cadences proposed here; the verifier roster per phase (D-156) must name people for the 14 human-signed ones | Only AKTCL can name people | sponsor | 0a | the delegate signs |
| OI-20-26 | The constants registry `/plan/constants.yaml` and rtm-check rules 13 to 16 are specified here and built at 0a (T-0-159, T-0-160, T-0-163); until they exist the owner of each repeated value is named in doc 14 s9.4 | a tool to build | tech lead | 0a | doc 14 s9.4 |

## Traceability

### Requirements and process

| Requirement | Mechanism here | Gates |
| --- | --- | --- |
| R1 Data: any dashboard or report from stored data | dw-only reads, aggregate-versus-source and ledger reconciliation, import control totals (s6.4, s7.4) | T-0-01 to T-0-04, T-1-01 to T-1-04, T-2-01 to T-2-04, T-2-143, T-4-01, T-4-03, T-4-43, T-4-46, T-4-102, T-7-80 |
| R2 Features: parity | baseline pack, goldens, 37 manual-parity gates, report parity (s4) | T-0-49, T-1-41, T-2-41, T-2-43, T-2-49, T-3-42, T-4-40, T-4-41, T-5-41, T-6-40, T-0-46, T-x-120 to T-x-130 |
| R3 Scale: 8,500 users at once | load gates, game day, first-morning storm, readiness and support (s7) | T-1-51, T-2-51 to T-2-54, T-4-51 to T-4-57, T-4-45, T-7-51 to T-7-57, T-7-59, T-7-83, T-7-85 |
| R4 Battery and data | protocol, budgets, zero-chatter, per-RC perf file (s2.7) | T-1-33, T-1-44, T-1-45, T-2-40, T-4-57 |
| R5 Offline plus immediate sync | airplane, flapping, kill, OEM matrix, parallel run (s2.4, s7.3) | T-1-20 to T-1-35, T-1-40, T-2-20 to T-2-34, T-2-40, T-2-140, T-3-40, T-5-40, T-7-82 |
| R6 Admin config with audit reaching the field | config gates and the sponsor's radius change (s3.4 to s3.9) | T-0-60 to T-0-64, T-1-60 to T-1-64, T-2-60 to T-2-69, T-6-41, T-6-42, T-6-70 to T-6-72, T-7-60 |
| Process: plan first, then phase by phase, each testable | gate system, exit reports, demos, scripts (s1, s10) | T-1-47, T-2-48, T-6-42 |

CLAUDE.md constraints by phase (each appears in at least one gate per phase from 1):

| Constraint | P1 | P2 | P3 | P4 | P5 | P6 | P7 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 Offline-first | T-1-30 | T-2-40 | T-3-40 | T-4-55 | T-5-40 | T-6-43 | T-7-82 |
| 2 Idempotent sync | T-1-21 | T-2-21 | T-3-02 | T-4-123 | T-5-01 | T-6-01 | T-7-02 |
| 3 Battery and data | T-1-45 | T-2-40 | T-3-122 | T-4-57 | per-RC perf file (s5.9) | per-RC perf file | T-7-82 (sheet lines 7, 8) |
| 4 Server-side scope | T-1-72 | T-2-70 | T-3-70 | T-4-74 | T-5-72 | T-6-72 | T-7-70 |
| 5 Geo and anti-spoofing | T-1-11 | T-2-47 | T-3-11 | T-4-44 | T-5-71 | T-6-10 | T-7-91 |
| 6 No-hiccup cutover | T-1-46 | T-2-48 | T-3-41 | T-4-41 | T-5-41 | T-6-42 | T-7-82 to T-7-88 |
| 7 Dhaka business date | T-1-27 | T-2-29 | T-3-43 | T-4-101 | T-5-100 | T-6-120 | T-7-87 |
| 8 Bilingual | T-1-121 | T-2-43 | T-3-125 | T-4-120 | T-5-122 | T-0-120 on every PR | T-7-85 |

### Ids used and where they are handled

| Type | Ids | Handled in |
| --- | --- | --- |
| T-ids | every id of s3.3 to s3.11 (497 gates; 388 before the round-3 pass); ranges and aliases in s3.1 | s3, s10; cited from s2, s4 to s9 |
| F-ids | F-SYS-017 (business date), F-SYS-020 (updater), F-SYS-021 (PDA to Support), F-SYS-026 (sync-health), F-SYS-030 (photo pipeline), F-SYS-036 (APK budget), F-SYS-043 (parallel-run comparison), F-SYS-044 (coexistence), F-SYS-047 (server-generation re-sync), F-SYS-048 (poison-row isolation), F-SYS-049 (trusted time), F-SYS-050 (telemetry and support visibility), F-SR-054, F-SR-056, F-SR-058, F-SR-066, F-AMO-038, F-TSO-021, F-TSO-022, F-API-039 | s3 rows, s6, s8 |
| G-ids | the 16 owned gaps (s11.1) and G-20-01 to G-20-08 (s11.2); cited for context: G-data-04, G-data-05, G-feat-11, G-feat-21, G-feat-24, G-feat-25, G-feat-34, G-feat-59, G-field-15, G-field-20, G-field-22, G-fraud-02, G-fraud-12, G-fraud-13, G-qa-05, G-qa-06, G-sre-01, G-sre-02, G-sre-03, G-sre-04, G-sre-06, G-sre-07, G-sre-08, G-sre-11, G-sre-12, G-sre-16, G-sre-17, G-sre-18, G-sre-21, G-sync-02, G-sync-15, G-sync-16; and every G-man entry 001 to 104 (s11.4) | s11, s3 |
| D-ids | D-01, D-02, D-04 to D-23, D-25 to D-28, D-30 to D-46, D-48 to D-52, D-55, D-56, D-58, D-59, D-62 to D-79, D-81, D-82, D-84 to D-88, D-90, D-91, D-93 to D-109, D-111 to D-122, D-125 to D-128, D-130, D-131, D-133 to D-138, D-140 to D-156, D-158, D-162, D-163, D-167 to D-173, D-175, D-176, D-179, D-181 to D-186, D-189 to D-192, D-197 to D-199, D-201 to D-204, D-206 to D-209, D-211, D-213, D-219 to D-222, D-224, D-230, D-231, D-235, D-236, D-238, D-240, D-241, D-243 to D-245, D-249, D-250, D-252, D-260, D-262, D-266, D-269; raised here D-450 to D-460 (s11.3) | each decision is applied in the gate rows that cite it (s3), in s2, s5 and s7 as stated, and listed with status in doc 14 s4 where it is MUST-CONFIRM |
| cfg keys | `cfg.app.default_locale`, `cfg.app.hold_to_confirm_ms`, `cfg.app.oem_guidance`, `cfg.day.checkout_earliest_time`, `cfg.day.final_submit_delegate_roles`, `cfg.flag.new_app_login_enabled`, `cfg.flag.parallel_run_mode`, `cfg.flag.pilot_in_rollups`, `cfg.geo.mock_policy`, `cfg.geo.radius_m`, `cfg.i18n.digit_script`, `cfg.kpi.bands`, `cfg.kpi.submit_pct_denominator`, `cfg.loyalty.cash_rate_mtk_per_point`, `cfg.memo.allow_negative_net`, `cfg.ops.maintenance_banner`, `cfg.ops.prescale_schedule`, `cfg.ops.sync_hold_by_version`, `cfg.outlet.wholesale_unmark_allowed`, `cfg.print.disconnect_idle_s`, `cfg.qc.expired_stock_months`, `cfg.release.blocked_versions`, `cfg.release.min_version`, `cfg.release.update_wifi_only`, `cfg.release.wave_pct`, `cfg.sale.max_line_qty_base`, `cfg.sla.crash_free_min_pct`, `cfg.stock.require_printed_slip`, `cfg.support.contacts`, `cfg.sys.change_freeze_windows`, `cfg.telemetry.device_max_bytes_per_day`, `cfg.telemetry.success_sample_pct`, `cfg.tso.team_location_max_age_min`, `cfg.web.entry_backdate_days`, `cfg.web.menu_by_role`, `cfg.calendar.*` | s2, s3, s5, s6, s7 |
| Q and MQ | Q5, Q10, Q13, Q18, Q31, Q32, Q33, Q34, Q35, Q36, Q37, Q38, Q39, Q49, Q51, Q52, Q55, Q57; MQ-01, MQ-02, MQ-03, MQ-04, MQ-05, MQ-06, MQ-07, MQ-09, MQ-10, MQ-17, MQ-20, MQ-22, MQ-23, MQ-24, MQ-33, MQ-34, MQ-39, MQ-40, MQ-47, MQ-48, MQ-57, MQ-58, MQ-59, MQ-60 | s4.7, Open items |

### Added at the editorial merge

**Master gaps owned by this document (16):** G-qa-01, G-qa-03, G-qa-04, G-qa-08, G-qa-10, G-qa-14, G-qa-09 (G-scale-17, G-cfg-17), G-feat-67, G-field-12, G-qa-11, G-qa-16, G-qa-20, G-qa-21, G-qa-23, G-qa-24, G-sec-23.

**Decisions that DECISIONS.md assigns to this document and that it applies without an inline citation:** D-303, D-305, D-306, D-308, D-309, D-310, D-311, D-312. Most are "See D-nn" aliases of a decision cited above or decisions raised by doc 14; the substance was not re-verified row by row.

### Added by the round-2 gap resolution (D-500 to D-553)

| Kind | Ids | Handled in |
| --- | --- | --- |
| Gaps | every row of s11.2b (G-qa-25 to G-qa-85 except the document-owned ones named there) | s11.2b |
| Decisions | D-500 to D-553 as listed in s11.2b | s3.14, s8.2, s11.2b |
| Gates | the 52 gates of s3.14 (T-0-150 to T-0-155, T-1-150 to T-1-152, T-2-150 to T-2-160 except T-2-152, T-3-150 to T-3-156, T-4-150 to T-4-165, T-7-150 to T-7-159) and the in-place changes named there | s3.14 |
| Rules | rtm-check rules 9 to 13 | s8.2 |

### Added by the round-3 gap resolution (D-554 to D-601)

| Kind | Ids | Handled in |
| --- | --- | --- |
| Gaps | G-qa-86 to G-qa-139 (those listed in s11.2c) | s11.2c |
| Decisions | D-554 to D-601 (those listed in s11.2c) | s11.2c |
| Gates | the 57 of s3.15 and the in-place changes named there; T-0-05 and T-0-09 moved to 1b, T-1-11 and T-1-12 moved to 2d | s3.15 |
| Rules | rtm-check rules 13 (extended), 14, 15 and 16 | s8.2 |
| Scenarios | S12, S13, S14 (doc 18) | s7.5, s3.15 |
