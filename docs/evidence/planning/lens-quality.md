# Lens: Testing, Phase Gates, CI/CD, Observability and Cutover (the quality system)

Date 2026-10-04. Inputs: CLAUDE.md, PROJECT-CONTEXT.md, README.md, docs/01–13 and 22, db/schema.sql, db/seed, seed-findings.md, and the sibling lenses (data, features, scale, sync, config, security). This lens does not re-derive their findings; it wires them into one gate system and adds what none of them owns: the test pyramid, the re-cut phase plan with entry/exit criteria a non-engineer can verify, the pipelines, the observability catalogue, the pilot/cutover test plan, the support flow, and the traceability format.

ID conventions used here: gates `T-<phase>-<nn>`; this lens owns the **40–49** range (test infrastructure, CI/CD, observability, usability, parity) and the **80–89** range (pilot, cutover, support). Ranges already owned: 01–04 data, 20–35 sync, 51–57 scale, 60–69 config, 70–76 security. Decisions `D-qa-NN`; questions continue from Q31; gaps `G-qa-NN`.

---

## 0. Conclusions (one page)

| # | Conclusion | Consequence |
| --- | --- | --- |
| C1 | **A gate is a triple: a test, an artefact, a named verifier.** docs/12's definitions of done are sentences ("meets battery/size budget"); nobody can tick them. Every gate below names the command or script that runs, the file it leaves in `/docs/evidence/phase-<n>/<T-id>/`, and the role who signs. Phase exit = every *blocking* gate green + a one-page exit report signed by the sponsor's delegate (§2.0). | 206 gates across phases 0–7 (59 defined by this lens in the 40–49 and 80–89 ranges, 147 referenced from the data, sync, scale, config and security lenses), each with an artefact path and a verifier. |
| C2 | **Parity has no oracle today.** The spec describes the current app from manuals and the web UI; no printed memo, screen recording, string list or Apsis report export is stored anywhere (lens-sync G-sync-02 covers the memo). Parity tests (same screens, same memo, same totals, same reports) cannot be written against prose. | A **baseline pack** captured from the current system is a Phase 0 gate (T-0-49) and the entry criterion for Phase 1. Without it, "no retraining" is an opinion (G-qa-01, blocker). |
| C3 | **docs/12 puts the promotion engine in Phase 5, but the pilot in Phase 7 compares printed totals with Apsis.** ~22 promotion groups and DRP offers change the memo total; a pilot memo that differs by one offer is a "discrepancy" on every route every day, drowning the real signal (sync loss). | Promotions/discounts (and 3-decimal rounding, G-feat-46/G-sync-04) move to sub-milestone **2a** as a pilot prerequisite; Phase 5 keeps the programmes (Astha, Diamond League, Superstar, targets). G-qa-02, blocker. |
| C4 | **Three test harnesses carry almost all the risk**: the sync property/fuzz suite (idempotency), the device-lab airplane-mode scripted day (offline + battery), and the reconciliation reports (device vs server vs Apsis). Everything else is ordinary. | They are built in Phase 0–1 (T-0-42, T-0-45, T-1-40, T-1-45) and run on every release candidate, not once. |
| C5 | **8,500 users on day one is a support problem as much as a technical one.** Nothing in docs/ names a helpdesk, a phone line, hours, scripts or what a TSO may fix. A 3–5 % contact rate on a full-fleet wave day is 250–425 calls (ASSUMPTION, §5.7). | Support tiers, what each tier can fix with which tool, staffing arithmetic and a readiness gate (T-7-85) are defined here. G-qa-04, blocker for wave 1. |
| C6 | **Zero-downtime is a discipline, not a feature.** Forward-only migrations with expand/contract, API compatible with schema N−1, app compatible with API N−2, Drift migrations tested N−2→N on every RC, blue/green traffic weights with auto-abort — each is a CI check, not a convention. | §3.6–3.9; gates T-1-46, T-2-44, T-4-47. |
| C7 | **Observability is designed from the sponsor's screen inward.** The sync-health dashboard (login %, submit %, final submit, mismatches, pending photos, mock flags, config reach) is the business's eyes on launch day; the ops dashboards exist to explain what it shows. Device telemetry rides inside the sync batch (R4: zero extra network). | Metric catalogue (§4.4), log schema (§4.2), dashboards (§4.5), reconciliation reports (§4.6), alert routing (§4.7), the pilot's daily sheet (§4.8). |
| C8 | **Traceability is a file in the repo checked by CI**, not a spreadsheet. `plan/rtm.yaml` links R → F → G/cfg/D/Q → T → evidence; `rtm-check` fails the build when a feature has no gate, a gate has no artefact path, or a blocker gap has no closing gate. Tests carry their gate id as a tag so the gate report is generated, never typed. | §6, gate T-0-46. |

Blockers raised by this lens: G-qa-01 (no parity baseline), G-qa-02 (promotion engine phase order), G-qa-03 (no machine-readable Apsis daily data for the parallel run), G-qa-04 (no helpdesk function), G-qa-05 (no fleet device inventory → battery gates unverifiable against the real fleet).

---

## 1. The test pyramid, per component

### 1.1 Principles (each is a CI rule or a review checkbox)

| # | Rule | Enforced by |
| --- | --- | --- |
| P1 | Every test that proves a gate carries the gate id as a tag (`@gate T-1-21` in the test name / Playwright `tag`, Dart `@Tags(['T-1-24'])`). CI emits `gate-report.json` per run. | `rtm-check` (§6.4) |
| P2 | No mocks for the database, the local SQLite, or the contract. API integration tests run against Testcontainers Postgres 16 with the real migrations; app tests run against a real Drift database; clients are generated from the contract. | Code review + lint (`no-pg-mock`) |
| P3 | The clock is injected everywhere (`Clock` interface in api/app/web). The suite runs at three fixed Dhaka instants: 10:00, 16:59:30 and 23:58 (D-qa-10). A test that reads `DateTime.now()`/`new Date()` directly fails lint. | `no-wall-clock` lint |
| P4 | Test data is synthetic and PII-free, produced by `/packages/testkit` (§1.11) from a seed. Apsis sample data never enters CI; it is used only in the import/reconciliation environment (G-qa-08). | Repo policy; gitleaks custom rule for BD phone shapes |
| P5 | Flaky tests are quarantined within one working day (tag `@flaky`, excluded from blocking, listed in the nightly flake report) and fixed or deleted within 10 working days; a quarantined test cannot prove a gate. | Nightly report (§3.2) |
| P6 | PR checks finish in ≤ 15 min wall clock; nightly in ≤ 3 h; the device-lab RC run in ≤ 1 working day plus the 8 h battery protocol. Anything slower moves down the schedule, never gets dropped. | Workflow timeouts |
| P7 | Coverage is a floor, not a target: api ≥ 80 % lines overall, ≥ 95 % on `sync/ingest`, `scope`, `money`, `business_date`, `geo`; app `domain/` ≥ 85 %; web ≥ 70 %. Mutation testing (Stryker) ≥ 70 % on the five api modules above. Dart has no mature mutation tool; property tests substitute (D-qa-04). | CI thresholds |
| P8 | Each gate has one owner (engineering) and one verifier (business or ops, never the owner). | RTM columns |

### 1.2 The pyramid (counts are targets at Phase 7 entry; they are checked, not padded)

| Layer | Component | Tooling | Count (target) | Runtime | Runs on | Blocks |
| --- | --- | --- | --- | --- | --- | --- |
| L0 Static | all | ESLint/TS strict, `dart analyze`, `squawk` (SQL), Bicep lint, custom lints (`no-wall-clock`, `no-pg-mock`, `no-unscoped-query` from lens-security, `no-hardcoded-string` for Flutter, `cfg-literal-registered` from lens-config T-0-63) | n/a | 2–4 min | every PR | merge |
| L1 Contract | `/packages/contract` | Zod schemas → OpenAPI 3.1 → generated TS (`openapi-typescript` + `openapi-fetch`) and Dart (`openapi_generator`, Dart 3 sealed classes) clients; JSON golden fixtures round-tripped in both languages; enum/reason-code drift test; N−2 fixture compatibility | ~250 fixtures, 1 drift test per enum (~30) | 3 min | every PR | merge |
| L2 Unit | api | Vitest; pure functions: money (milli-taka, rounding mode), business date, Haversine, memo arithmetic, config resolver, scope expansion, KPI formulas (`safe_div`) | ~1,500 | 2 min | every PR | merge |
| L2 Unit | app | `flutter test`; domain: outbox invariant, batch builder, memo number, resolver, business date with offsets, print raster layout | ~800 | 3 min | every PR | merge |
| L2 Unit | web | Vitest + Testing Library; KPI components, filters, scope-bounded selectors, number/Bangla formatting | ~400 | 2 min | every PR | merge |
| L3 Integration | api | Vitest + Testcontainers Postgres 16 (+ Azurite, Redis container); every endpoint × happy path × scope-negative × idempotent retry; DQ-01..34 rule tests; RLS on/off | ~450 | 6 min (parallel 4 shards) | every PR | merge |
| L3 Property / fuzz | api sync | `fast-check`; properties from lens-sync §9.1 (T-1-21 idempotent convergence etc.); 10k runs PR, 100k nightly, seeds persisted; corpus replay of anonymised pilot batches (after 7b) | 20 properties | 4 min PR / 90 min nightly | PR + nightly | merge (PR size) |
| L3 Property | app sync | Dart generators (hand-rolled, `test` package) for outbox/ACK/apply purity; shares the JSON corpus with the server via `/packages/testkit/corpus` | 8 properties | 3 min | every PR | merge |
| L3 Widget | app | `flutter test` goldens at 320×640 dp (the reference phones) in `bn` and `en`; every SR/AMO/TSO screen; Bengali font rendering; TSO dark theme | ~300 | 5 min | every PR (app paths) | merge |
| L4 Component e2e | web | Playwright component tests; role personas (SR-less: WM, DMO, TSO, admin bundles) | ~120 | 4 min | PR (web paths) | merge |
| L4 e2e | web + api | Playwright against ephemeral stack (docker compose) on PR smoke (20 flows), full matrix on staging after deploy (~250 flows × 3 personas), visual regression, `axe` a11y | ~250 | 8 min smoke / 40 min full | PR smoke; main full | promote |
| L4 Integration | app | `patrol` on emulator (PR: 6 smoke flows) and on the device lab (RC: ~45 scenarios incl. airplane mode, kill points via `--dart-define=CHAOS_KILL_AT`, permission dialogs, printer fake) | ~45 | 10 min emulator / 3 h lab | PR smoke; RC lab | release |
| L4 Cross-stack | app + api + web | patrol drives a phone, Playwright asserts the tile (T-1-40, T-3-43): "sale on the phone appears on the dashboard" | 6 | 20 min | RC | release |
| L5 Importer | `/api/import` | Synthetic Apsis dump generator (`testkit apsis-dump`: 3 zones, 200 outlets, 2 months, planted defects P-05..P-14), import, reconcile, re-import no-op, quarantine counts | ~60 | 6 min | PR (import paths) + nightly full-size | merge; 7a |
| L5 Load | api | k6 smoke on main (S2 shape, 5 min, 1/50 scale) with thresholds; Azure Load Testing scenarios S1–S9 (lens-scale §6) at the T-4-5x and T-7-5x gates | 9 scenarios | 5 min smoke; 1–10 h scenarios | main; gates | promote; wave |
| L5 Device lab | app | Battery/data protocol (lens-sync §9.5) on primary device per RC; secondary/legacy once per phase; APK size in CI; cold start | 1 protocol | 8 h | RC | release |
| L5 Security | all | CodeQL, Semgrep, gitleaks, Dependabot/OSV, Trivy, PSRule/checkov (lens-security T-0-70..73); ZAP on staging (T-2-74); `/cso` audit before pilot; pen test before wave 1 | per PR / phase | 5 min PR | PR; phase | merge; pilot; wave |
| L6 Human | all | Golden-screen sign-off vs baseline pack; Bangla string review; usability sessions (AMO/TSO); "run without engineering" drill; pilot dress rehearsal | per phase | days | phase exit | phase |

### 1.3 `/packages` contract tests (the one source of truth)

1. **Source of truth**: Zod schemas in `/packages/contract/src` for every request/response, every record type in the batch (`type`, `family_uuid`, `rank`, `client_uuid`, payload), every enum and reason code (`cfg` code tables are *content*, so the contract carries only the *shape* `{code, label_bn, label_en}`), the config key registry types, and `schema_version` (D-qa-02).
2. **Generation**: `pnpm contract:build` emits `openapi.json`, the TS client, and the Dart client into `/app/lib/generated/` (committed, drift-checked: CI regenerates and fails on diff).
3. **Golden fixtures**: `/packages/contract/fixtures/<type>/<case>.json` — at least: minimal, full, unicode Bangla, boundary values (qty = `cfg.sale.max_line_qty_base`, due = 0, `is_zero_sale`), and one *rejected* example per reason code. Both languages parse every fixture and re-serialise byte-identically after canonicalisation.
4. **N−2 compatibility**: fixtures are versioned by `schema_version`; the current server must accept fixtures of `schema_version − 1` and `− 2` (lens-sync T-2-33 is the runtime test; this is the compile-time one).
5. **Enum drift**: the Postgres enum/code table seed, the Zod enum and the Dart enum are compared by a test that reads all three.
6. **Breaking-change gate**: `oasdiff` against the last released `openapi.json`; a breaking change needs a `schema_version` bump and a `BREAKING.md` entry, else the PR fails.

### 1.4 API unit + integration

| Area | What is tested | Oracle |
| --- | --- | --- |
| Ingest `/sync/batch` | every DQ rule (lens-data §6) as a table-driven test; ordering (children first); replay by `batch_uuid` byte-identical; `server_totals` arithmetic; gzip bomb 413; 200-row batch < 150 ms on the CI container | DQ table; stored response |
| Bundle | contents per role; delta `?since=`; `ETag`/304; pre-generated snapshot equals live generation for 50 random users (nightly) | snapshot diff |
| Scope | two-user harness over random scope trees (lens-security T-0-75) on *every* endpoint via route-table introspection: any new endpoint without the harness fails the suite | zero foreign rows |
| Aggregation worker | recompute-by-dirty-key equals a brute-force SQL over `app.*` for random days (lens-data T-1-01..03); late batch touches only its date; supersede cascades | brute-force view |
| Day state | `route_day` transitions table (lens-sync §3.2) as a state-machine test: every (state, event) pair has an expected outcome or a rejection | transition table |
| Config | resolver precedence, effective dating, bounds, as-of re-check (lens-config T-1-6x) | expected values |
| Reports | each docs/09 report query runs against a seeded day and matches a hand-computed fixture; reads only `dw.*` (role grant test, T-4-43) | fixture |
| Auth | lens-security T-0-74, T-1-70 | — |

### 1.5 Flutter tests

| Kind | Scope | Notes |
| --- | --- | --- |
| Unit | `domain/`: outbox, batch builder (family atomicity), ACK apply purity, memo number regex and block logic, business date from corrected time, Haversine, memo arithmetic + rounding corpus, config resolver, string catalogue completeness (every key has `bn` and `en`) | Property tests share the JSON corpus with the server |
| Widget / golden | every screen × {bn, en} × 320×640 dp; TSO dark theme; long Bangla labels do not overflow; the −37,500 % case renders "—" | Goldens reviewed against the baseline pack at T-1-41/T-2-43 |
| Print | memo template → ESC/POS bytes via `PrinterPort` fake → raster decoded → PNG compared with golden (pixel tolerance 0.5 %) for the 7 memo kinds (cash, credit partial, with offer, DRP, zero sale, edited "supersedes", stock memo, summary) | Physical print at T-1-35/T-2-32 (lens-sync) |
| Integration (`patrol`) | emulator smoke on PR: login → bundle → check-in → one sale → sync; device lab on RC: the 45 scenarios = lens-sync §9.2–9.3 kill/airplane/flapping/captive/2G/stale-bundle/config-mid-day/clock-skew/shared-phone + this lens's T-1-40, T-2-40, T-2-47, T-3-40 | Connectivity driven from the host over Wi-Fi ADB (`adb shell cmd connectivity airplane-mode …`, `svc wifi`), throttling via `mitmproxy` 2G profile |
| Upgrade matrix | install N−2 and N−1 release APKs with 200 pending rows + 1 draft sale → upgrade to N → counts intact, sync succeeds (lens-sync T-2-23) — automated on emulator for every RC (T-2-44) | `adb install -r` |
| Accessibility / low literacy | `flutter_test` semantics check: every tappable has a label; icon-only tiles have tooltips; font scale 1.3 does not break the sale screen | G-qa-19 |

### 1.6 Web tests

Component (Vitest + Testing Library) for every KPI tile and filter; Playwright e2e per page × persona {Wing Manager, DMO, TSO, admin bundles} including the **scope negative** ("a WM of wing 3 requesting wing 4's zone gets 403 and sees nothing"); visual regression (Playwright snapshots, 1280 and 390 px); `axe` with zero critical; Excel export compared cell-by-cell to a golden `.xlsx` (values, number formats, sheet names) for the three most-used reports (STD Memo, Data Entry Log, Final Submit Log); Lighthouse performance ≥ 80 and total JS ≤ 350 KB gz on the dashboard (T-4-47).

### 1.7 Migration / importer reconciliation tests

1. `testkit apsis-dump --seed N` produces CSVs in the requested docs/11 shape *with the defects docs/22 found*: zero-volume lines (P-05), duplicate keys (P-06), malformed outlet codes (P-07), outlets missing from the retailer list (P-08), placeholder coordinates (P-09), `12AM`-style timestamps (P-11), `Semi Urban` spelling (P-14).
2. Import → `stg` → `app` with crosswalk; **control totals** (§5.3) computed from the synthetic "Apsis report" the generator also emits; assert equality; assert quarantine counts equal planted defects.
3. Re-import of the same files is a no-op (lens-data T-7-02); import of a delta applies only the delta.
4. Nightly: full-size synthetic (1,051 zones, 460k outlets, 60 days) — runtime and memory recorded; must finish < 4 h on the staging SKU (ASSUMPTION; adjust after the first real dump).

### 1.8 Load tests

Owned by lens-scale §6 (S1–S9, T-4-51..57, T-7-51..56). This lens adds: a **k6 smoke on every main build** (S2 shape, 5 min, 1/50 scale, thresholds ack p95 ≤ 1.5 s, 0 doubles) so regressions are caught weekly, not at the phase gate; and the rule that load tests run against **staging scaled to the prod SKU for the window only**, with the scale-up/down scripted in the workflow (G-qa-09 for budget approval).

### 1.9 Battery / device lab

Reference devices and the measurement protocol are lens-sync §5.1–5.4 and §9.5 (gates: app non-screen drain ≤ 6 %/day, background ≤ 1 %/8 h, GPS ≤ 15 min, wake-locks ≤ 10 min, mobile data ≤ 1 MB/day without photos, APK ≤ 30 MB). This lens defines the **lab** (D-qa-13): a physical rack in the Dhaka office with 2 × primary, 2 × secondary, 2 × legacy devices (ASSUMPTION on models until Q31 answers), one RPP02N and one clone printer, Wi-Fi ADB, a self-hosted GitHub runner, a USB power meter for the 8 h run (phones are not USB-connected during measurement), `mitmproxy` for throttling and the zero-chatter check (T-1-44). Firebase Test Lab is used only for breadth (Android 8–14 smoke on 12 common models), never for budgets.

### 1.10 Security scans

Owned by lens-security §12 (T-0-70..76 per PR; T-2-73/74 before pilot; T-7-70 pen test before wave 1). This lens only places them in the pipelines (§3) and the gate register (§2).

### 1.11 Test data strategy — `/packages/testkit`

| Generator | Produces | Used by |
| --- | --- | --- |
| `fleet --wings 1 --zones 3 --routes 12 --outlets 800 --seed N` | geography, users (every role), devices, routes with visit days, outlets with realistic density (80 % within 55 m of another, P-10), prices from `db/seed`, sales plan, targets, holidays | api integration, Playwright, staging seed |
| `day --date D --fleet F --online-pattern P` | a scripted field day per SR: visits, memos (1.95 lines avg, p99 4, max 40), zero sales 10–16 %, force sales 5 %, dues, edits, QC, photos (placeholder JPEGs ≤ 150 KB), with `client_uuid`s and batches cut by pattern | fuzz corpus, k6, device lab seeding |
| `apsis-dump` | §1.7 | importer tests |
| `personas` | fixed logins per role and scope for e2e (`wm_w03`, `tso_t291`, `admin_cfg_editor`, …) | Playwright, patrol |
| `corpus` | anonymised real batches captured from pilot devices (after 7b; `client_uuid`s re-generated, ids re-mapped, no names/phones) | nightly fuzz replay |

The staging environment carries a **full-size synthetic fleet** (10 wings, 1,051 zones, 11,336 routes, 460k outlets, 9,850 users) regenerated monthly from a fixed seed so that load tests and dashboards are realistic without PII.

### 1.12 Environment matrix

| Env | Purpose | Data | Deploy | Who |
| --- | --- | --- | --- | --- |
| local | engineer loop; `docker compose` (PG16, Azurite, Redis, mailpit) | `testkit fleet` small | manual | engineers |
| CI ephemeral | PR checks | Testcontainers | per job | CI |
| `dev` (Azure, small) | device testing of branches, demos | small synthetic | `workflow_dispatch` any branch | engineers |
| `staging` (Azure, prod-shaped; scaled to prod SKU for load windows) | main auto-deploy, full e2e, soak, load, rehearsals, DR drills | full-size synthetic (+ anonymised corpus) | main | CI; QA |
| `prod` | pilot (real routes, Apsis system of record), waves, fleet | real | manual promote | release manager + business approver |
| device lab | RC validation, battery protocol, printer | synthetic + prod pilot accounts | runner | QA |

### 1.13 Phase-independent definition of "tested" for a feature (CLAUDE.md DoD, made checkable)

A feature `F-*` is **done** when: its RTM row lists ≥ 1 passing gate per applicable column — offline (patrol airplane scenario or `Off=N` justified), idempotency (property or integration test exercising a duplicate upload of its record type), scope (two-user harness case), localisation (string-catalogue completeness + golden in `bn`), battery (no new sensor/network path, confirmed by the zero-chatter check), and — for anything with a number — a reconciliation or fixture oracle.

---

## 2. Phases re-cut with ENTRY, deliverables, gates and EXIT

### 2.0 Rules of the gate system

1. **Gate record** (one row in `plan/gates.yaml`, §6): `id, phase, lens, title, test (command/script/manual protocol), artefact (path under /docs/evidence/), verifier (role), blocking (yes/no), status`.
2. **Blocking** gates stop the phase exit; non-blocking gates are tracked and must close before Phase 7 entry.
3. **Verifier** is never the author. Engineering gates (unit/integration/property) are verified by the tech lead from the CI gate report; business-facing gates (parity, usability, reconciliation, readiness) by the sponsor's delegate (Q34 names the person) using the **one-page script** stored next to the artefact — written so that a non-engineer can run it.
4. **Exit report**: `/docs/evidence/phase-<n>/EXIT.md`: gate table with links, open gaps carried forward (with owner and target sub-milestone), decisions taken (D-ids), measurements (battery, size, p95), and two signatures (tech lead, business delegate).
5. **Entry criteria** are the previous phase's exit plus the external inputs listed (data, devices, people). An entry criterion that is not met is written down with the risk accepted, never silently skipped.
6. Sub-milestones (1a, 2b…) each end with a **demo** the sponsor can watch in ≤ 30 min; the demo script is the artefact.
7. Indicative durations (ASSUMPTION; a tight sprint with daily sponsor access): P0 3 wk · P1 4 wk · P2 8 wk · P3 5 wk · P4 6 wk · P5 5 wk · P6 4 wk · P7 10+ wk (7a 2, 7b 3, 7c–7d 5+, 7e after). P3/P4 can overlap P2 once 2b is exited; P7a starts the day the dump arrives.

### 2.1 Gate ID map

| Range | Lens | Content |
| --- | --- | --- |
| 01–04 | data | schema, seed, idempotent aggregation, control totals |
| 20–35 | sync | outbox, convergence, kill tests, airplane mode, printer, memo numbers |
| 40–49 | **quality (this lens)** | test infrastructure, CI/CD, observability, parity, usability, telemetry |
| 51–57 | scale | fuzz volume, load, chaos, DR, quotas |
| 60–69 | config | registry, bounds, propagation, approvals, revert |
| 70–76 | security | scans, auth, scope leak, PII, pen test |
| 80–89 | **quality (this lens)** | pilot, cutover, support, decommission |

### 2.2 Phase 0 — Foundations (sub-milestones 0a tooling, 0b schema + contract, 0c auth + scope)

**ENTRY**: repo exists; Azure subscription and GitHub org access; decisions D-01..09 (data), D-sync-01..14, D-scale-1..12, D-cfg-1..10, D-sec-01..14 reviewed with Asef and the ones marked "lock early" (docs/13 Q1–Q5) decided; two pilot phones in hand (even before Q31 answers).

**Deliverables**: monorepo; `/packages/contract` v1 with generators; `/db/migrations` M-01..M-45 (lens-data) + runner; `cfg` registry seeded (lens-config §5 Phase 0); auth (lens-security Phase 0); `testkit` v1; pipelines `pr.yml`, `main.yml`, `promote-prod.yml`, `infra.yml`; Bicep for dev/staging/prod skeleton; structured logging + App Insights + Sentry wiring; `rtm.yaml` + `gates.yaml` + `rtm-check`; device lab commissioned; **baseline pack**.

| Gate | Title | Test | Artefact | Verifier | Blocking |
| --- | --- | --- | --- | --- | --- |
| T-0-01..04 | lens-data: schema loads on PG16 Flexible, seed round-trips 7.935, partition job, `safe_div`/CHECK | CI | gate report | tech lead | Y |
| T-0-60..64 | lens-config: registry completeness, bounds, exclusion constraint, code/registry drift, audit immutability | CI | gate report | tech lead | Y |
| T-0-70..76 | lens-security: SAST, deps, secrets, IaC, auth negatives, scope-leak harness, lint + grants | CI | gate report | tech lead | Y |
| **T-0-40** | PR pipeline complete: lint, typecheck, unit, contract, integration (Testcontainers), smoke e2e, security scans, rtm-check; ≤ 15 min; required checks + merge queue configured | open a PR touching all workspaces | CI run URL; branch-protection screenshot | tech lead | Y |
| **T-0-41** | Contract build: Zod → OpenAPI → TS + Dart clients compile; 250 fixtures round-trip in both languages; enum drift test; `oasdiff` gate wired | CI | gate report | tech lead | Y |
| **T-0-42** | Testcontainers harness: fresh PG16 container, all migrations, seed, `testkit fleet` 1-zone in < 10 s; scope harness auto-discovers routes | CI | gate report | tech lead | Y |
| **T-0-43** | Deploy pipeline: main → staging (migrate expand → image → revision → smoke → e2e) and manual promote → prod with 2 approvers, both exercised with the health endpoint; OIDC, no stored cloud secrets | run both workflows | deployment history screenshots; `az containerapp revision list` | ops lead | Y |
| **T-0-44** | Observability baseline: one request produces a pino log line with `request_id` in Log Analytics, an App Insights trace to Postgres, a Sentry event from the app and web with PII scrubbed (T-1-75 redaction test wired); "ops" workbook with 5 tiles | script `obs-smoke.sh` | KQL screenshots; workbook JSON in `/infra/monitor/` | ops lead | Y |
| **T-0-45** | `testkit` v1: `fleet`, `day`, `personas` deterministic by seed; zero PII (gitleaks BD-phone rule passes on output) | CI | gate report | tech lead | Y |
| **T-0-46** | RTM in repo: `plan/rtm.yaml` lists every F-id from lens-features (275) with phase and ≥ 1 planned gate; `gates.yaml` lists every T-id with artefact path; `rtm-check` fails on orphans | CI | rtm-check output | sponsor delegate (reads the summary table) | Y |
| **T-0-47** | Injectable clock in api/app/web; suite passes at 10:00, 16:59:30, 23:58 Dhaka; `no-wall-clock` lint clean | CI (3 jobs) | gate report | tech lead | Y |
| **T-0-48** | Device lab commissioned: 6 reference devices + 2 printers inventoried, Wi-Fi ADB, self-hosted runner online, debug APK installs and launches on all 6 | `device-lab.yml` smoke | inventory sheet with photos; runner log | QA lead | Y |
| **T-0-49** | **Baseline pack** captured from the current system: (a) screen recording of a full SR day and of the AMO/TSO flows on a pilot phone (retailer PII masked), (b) ≥ 25 printed memos (photo + the entered quantities) covering cash, credit/partial, each live promotion group, DRP, zero sale, edit, stock memo, summary, (c) screenshots of all 37 web pages, (d) Excel export of every report for 3 pilot zones for one full month, (e) extracted string glossary (bn/en) from the recordings, (f) the four manuals. Stored in `/docs/baseline/` (private repo path; PII check) | checklist | `/docs/baseline/INDEX.md` | sponsor delegate | Y |

**EXIT (non-engineer check)**: open the staging URL and log in as each of the seven roles with the test personas; each sees only its patch (the delegate tries a wrong zone in the URL and gets "not allowed"); the baseline pack index lists all six items with files; the gate report page shows every Phase 0 gate green.

### 2.3 Phase 1 — The vertical slice (1a offline capture, 1b sync + reconcile, 1c aggregate + tile + config)

**ENTRY**: Phase 0 exit; baseline pack; two pilot SR accounts on staging; RPP02N in the lab; decision on memo numbering (Q5 → D-sync-07) and units (Q8 → D-02).

**1a** — bundle for one SR; app skeleton: login/offline unlock, bundle, home strip, attendance, outlet list, geo check, one sale, print (golden memo), all in airplane mode.
**1b** — outbox → `/sync/batch` → ACK → reconciliation screen; triggers T1/T2/T3/T6 (lens-sync §2.1); batch replay.
**1c** — worker → `agg_daily_*` → one dashboard tile and the first sync-health tile; `cfg.geo.radius_m` editable in a minimal admin table and visible on the phone (lens-config "believable moment").

| Gate | Title | Test | Artefact | Verifier | Blocking |
| --- | --- | --- | --- | --- | --- |
| T-1-01..04 | lens-data: duplicate batch → identical aggregates; reordered children parked; late batch; counts equal | CI | gate report | tech lead | Y |
| T-1-20..35 | lens-sync: outbox invariant, convergence, replay, ACK purity, kill mid-sale/send/ACK, airplane day, flapping, captive Wi-Fi, APK ≤ 30 MB, reconciliation equality, golden memo print | CI + lab | gate report; print photos | tech lead; QA | Y |
| T-1-51, T-1-52 | lens-scale: idempotency fuzz 10k; business-date rollover | CI | gate report | tech lead | Y |
| T-1-60..64 | lens-config: bundle carries config, change reaches the device, resolver p95, as-of re-check, header everywhere | CI + lab | gate report | tech lead | Y |
| T-1-70..76 | lens-security: binding, offline unlock, batch forgery, rate limits, APK config, log redaction, trusted time | CI + lab | gate report | tech lead | Y |
| **T-1-40** | **Airplane-mode vertical slice on the primary device** (patrol): online login → airplane on → check-in → outlet → geo check → one sale → print → airplane off → sync within 60 s → reconciliation equal → Playwright asserts the staging tile shows the sale within 2 min | `device-lab.yml` | video; patrol + Playwright reports | sponsor delegate watches the video and re-does it by hand with the script | Y |
| **T-1-41** | Golden screens vs baseline: home, outlet list, geo check, sale entry, review, print for SR in bn and en at 320×640 compared side by side with the baseline recording frames; differences listed and each marked "accept" or "fix" | review session | `/docs/evidence/phase-1/T-1-41/review.md` with frame pairs | sponsor delegate | Y |
| **T-1-42** | Coverage floors and mutation: api ≥ 80 % (≥ 95 % on the five critical modules), Stryker ≥ 70 % on them; app `domain/` ≥ 85 % | CI | coverage + Stryker reports | tech lead | Y |
| **T-1-43** | Sync-health tile v1 on staging: for the fuzz day's 12 routes shows target/logged-in/uploaded counts equal to the known seed; "as of" timestamp present | Playwright | screenshot + query | ops lead | Y |
| **T-1-44** | Zero-chatter check: 1 h idle foreground + 1 h background with empty outbox on the primary device behind `mitmproxy` → zero requests other than the explicit refresh; 8 h background with 50 pending rows and no network → zero wake-ups attributable to the app beyond the periodic job (≤ 32) | lab protocol | proxy log; Battery Historian | QA lead | Y |
| **T-1-45** | Battery/data protocol run #1 (lens-sync §9.5) on the primary device: all §5.3/§5.4 gate values recorded in `/docs/perf/battery-<v>.md`; any miss has a ticket | lab protocol | the perf file; bugreport | QA lead; sponsor delegate reads the table | Y |
| **T-1-46** | First expand/contract rehearsal: a migration adding a column + index `CONCURRENTLY` applied to staging while k6 S2 smoke runs; zero 5xx, lock waits p95 < 50 ms; the API of the previous release still passes its integration tests against the new schema (N−1 compat job) | `main.yml` + nightly compat | k6 report; compat job | tech lead | Y |
| **T-1-47** | Phase 1 demo executed by a non-engineer from the one-page script (login, airplane, sale, print, sync, tile, change radius to 150 m and see the next visit use it) | live | signed `EXIT.md` | sponsor delegate | Y |

**EXIT**: the sponsor's delegate performs T-1-47 unaided; the perf file shows all battery/data numbers within budget on the primary device; APK ≤ 30 MB; every Phase 1 gate green in the gate report; open gaps carried into Phase 2 are listed with owners.

### 2.4 Phase 2 — The full SR day (2a sale complete, 2b corrections, 2c outlets + media, 2d geo + rails, 2e day close + pilot hardening)

**ENTRY**: Phase 1 exit; promotion catalogue and rules obtained from the business (G-feat-13; Q13) — **if not available, 2a proceeds with the rule engine + the groups visible in the baseline memos and the gap stays open as a pilot blocker**; edit/force reason texts (Q from lens-config); stock return flow decision (G-feat-02).

**2a** — stock load + stock memo, full sale (offers **engine**, review, credit + partial payment, QC, print), **memo rounding rule** (D-01, G-feat-46), zero sale, summary print.
**2b** — due collection, memo edit (geofenced, pre-QC, reasons, supersede), stock return/reconciliation (G-feat-02), "view previous sale data".
**2c** — outlet requests (new/close/info) with photos, media pipeline (compress, queue, SAS upload, Wi-Fi-only, evidence fallback), bounded image cache, AV/KV content, POSM survey, DRP.
**2d** — geo-validation complete: per-scope radius with outlet override, server re-check as-of, plausibility flags, integrity signals, `mock_policy`; config rails (bounds, canary, two-person, revert, anomaly watch).
**2e** — Sales Submit and check-out as outbox events, stale-bundle policy, clock-skew UX, PDA-to-Support, in-app updater, kill switch/min_version, Bangla review, dogfood week, dress rehearsal → **pilot-ready**.

| Gate | Title | Test | Artefact | Verifier | Blocking |
| --- | --- | --- | --- | --- | --- |
| T-2-01..04 | lens-data: edit does not double; overpayment flagged; mock never geo-valid; 10k fuzz reconcile zero diff | CI | gate report | tech lead | Y |
| T-2-20..34 | lens-sync: memo numbers, event ledgers, photo/upgrade/delta kills, 2G catch-up, Wi-Fi-only photos, stale bundle, config mid-day, clock skew, nightly reconcile, printer failure, N−2 payloads, Android 7/8 TLS | CI + lab | gate report | tech lead; QA | Y |
| T-2-51..54 | lens-scale: hot rows, backoff conformance, SAS misuse, TLS on old devices | staging + lab | reports | tech lead | Y |
| T-2-60..69 | lens-config: two-person, reach SLO, offline scheduled apply, kill switch semantics, revert, blast radius, anomaly watch, outlet override, canary, content versioning | CI + lab | gate report | tech lead; sponsor delegate for T-2-61/64 | Y |
| T-2-70..75 | lens-security: edit rules, price recompute, photo pipeline, `/cso` audit, DAST, anti-spoofing server side | CI + staging | reports | security owner | Y (2-73 gates pilot) |
| **T-2-40** | **Full scripted field day** (lens-sync §5.2: 50 outlets, 10 photos, 55 trickle batches, Sales Submit + check-out at 17:00) as a patrol run on all three reference devices under the connectivity pattern; every row synced within 3 min of the final connectivity; reconciliation equal on all three | `device-lab.yml` | 3 reports + videos | QA lead | Y |
| **T-2-41** | **Printed memo parity**: the ≥ 25 baseline memos re-entered in the new app on the lab printer; totals equal to the paisa; layout diff (photo vs photo) approved line by line; stock memo and summary included | lab protocol | side-by-side photo sheet; `parity.md` | sponsor delegate + one SR + one retailer-facing manager | Y |
| **T-2-42** | Rounding + promotion regression corpus in CI: every baseline memo is a fixture (lines in → totals out) for both the Dart and TS arithmetic; both pass; every promotion group has ≥ 3 fixtures | CI | gate report | tech lead | Y |
| **T-2-43** | Localisation complete for SR: `no-hardcoded-string` lint clean; every string key has bn + en; goldens for all SR screens in bn reviewed against the baseline glossary; Bengali font renders conjuncts correctly on the legacy device | CI + review | glossary diff; golden set | native-Bangla reviewer (Q35) | Y |
| **T-2-44** | Upgrade matrix automated: every RC runs N−2→N and N−1→N on emulator with 200 pending rows + draft sale; data intact; sync succeeds; older build refuses to open a newer DB with a clear message | `main.yml` | gate report | tech lead | Y |
| **T-2-45** | Dogfood week: 10 engineering/ops staff run the scripted day daily for 5 trading days on their own low-end phones against staging; crash-free sessions ≥ 99.5 %; zero data-loss tickets; every rejected row explained | Sentry + sync-health | week report | ops lead | Y |
| **T-2-46** | Pilot-hardening drills: PDA-to-Support upload from a lab phone replays into staging and reproduces its counts; `cfg.release.min_version` bump blocks a new-day login but the phone still uploads 20 pending rows; `cfg.ops.kill_switch` auto-expires; updater downloads over Wi-Fi only | lab protocol | drill log | ops lead; sponsor delegate | Y |
| **T-2-47** | Anti-spoof end to end with real spoofing tools: a fake-GPS app and a mock-provider script on the primary device → `mock_location=true`, never geo-valid, visible on the AMO exceptions list within aggregation lag; teleport (two outlets 10 km apart in 2 min) and single-point-route scenarios flagged | lab protocol | report with screenshots | sponsor delegate | Y |
| **T-2-48** | **Pilot dress rehearsal**: 2 pilot SRs + 3 staff run a real beat for one day with both apps (double entry), new app on staging; evening comparison of memo count, STD, totals, dues vs the Apsis app's own summary screen; every difference categorised (§5.2); zero sync-loss category | field day | rehearsal report | sponsor delegate | Y |
| **T-2-49** | Bangla string review signed: 100 % of SR strings reviewed by a native reviewer against the baseline glossary; terms match the current app (বিক্রয় জমা, বাকি, বাকি পরিশোধ, নিরীক্ষণ, …) unless a change is explicitly approved | review | signed glossary | native reviewer; sponsor delegate | Y |

**EXIT ("pilot-ready")**: an SR can run a real beat end to end offline on a reference phone (T-2-40 videos); printed memos match the baseline to the paisa (T-2-41 sheet); the dress rehearsal had zero sync-loss discrepancies (T-2-48); battery/data numbers within budget on all three reference devices (`/docs/perf/`); `/cso` audit has no open high; the sponsor's delegate changed the radius from the GUI and saw the phone pick it up (T-2-61/T-1-61).

### 2.5 Phase 3 — Supervisors (3a AMO, 3b TSO)

**ENTRY**: Phase 2 exit (at least 2a–2d); rubric and questionnaire definitions (lens-config `cfg.rubric.*`, `cfg.survey.tso_visit_query_questions`); decision on reopen roles (Q11); OTP issuance surface (G-feat-11).

| Gate | Title | Test | Artefact | Verifier | Blocking |
| --- | --- | --- | --- | --- | --- |
| T-3-01, 02 | lens-data: Login % unchanged by delta; final submit twice → one row | CI | gate report | tech lead | Y |
| T-3-20..23 | lens-sync: submit kill, shared phone, device replacement, TSO logout guard | lab | gate report | QA | Y |
| T-3-60, 61 | lens-config: bounded TSO permission; reopen rules | CI | gate report | tech lead | Y |
| T-3-70..73 | lens-security: separation of duties, device lifecycle, final submit/reopen audit, exceptions screen scoping | CI | gate report | security owner | Y |
| **T-3-40** | AMO/TSO offline flows (patrol, airplane): control call (distribution/OOS/POSM), joint-call assessment, outlet verification queued offline, AMO sale; TSO visit plan set offline and synced; final submit online with the once-only refusal on retry | lab | reports | QA lead | Y |
| **T-3-41** | SR → AMO → web approval chain end to end: SR new-shop request (phone, offline) → AMO verifies on a second phone → web approver approves (Playwright) → SR bundle delta shows the outlet active; negative: AMO of another zone cannot see it | cross-stack | report | sponsor delegate | Y |
| **T-3-42** | Role-aware single app: the same APK renders SR, AMO and TSO homes (tiles, badges, TSO dark theme) per token role; goldens in bn/en reviewed against the baseline recordings | CI + review | golden set; review notes | sponsor delegate | Y |
| **T-3-43** | TSO final submit drives the numbers: patrol performs a final submit for a zone; Playwright asserts final-submit status, Submit % frozen as of that moment, and the "late data" report receives a row synced afterwards | cross-stack | report | ops lead | Y |
| **T-3-44** | Supervisor usability: 3 AMOs + 2 TSOs (pilot territories) each perform 10 scripted tasks unaided on the app; task success ≥ 90 %; every failure triaged to a ticket or an "accept" | session | usability report | sponsor delegate | N (blocking at Phase 7 entry) |

**EXIT**: an AMO verifies an SR's request and the web approves it (T-3-41 performed live by the delegate); a TSO's final submit closes a zone on the sync-health screen (T-3-43); AMO/TSO reconciliation screens show the nine AMO types; usability findings ticketed.

### 2.6 Phase 4 — Web dashboards & reports (4a sync-health + dashboards, 4b report set, 4c panels + PII, 4d scale proof)

**ENTRY**: Phases 1–3 aggregates in `dw`; KPI decisions D-03, D-05..D-09; Apsis report exports in the baseline pack (for parity); read replica provisioned; staging full-size synthetic fleet.

| Gate | Title | Test | Artefact | Verifier | Blocking |
| --- | --- | --- | --- | --- | --- |
| T-4-01..03 | lens-data: national dashboard p95 < 300 ms from agg; `bi_reader` no PII; every report has a `dw`-only query | CI + staging | gate report | tech lead | Y |
| T-4-51..57 | lens-scale: S1 at 1,000 and 9,850 users, connection exhaustion, aggregation under load, failover chaos, replica lag banner, telemetry volume | Azure Load Testing | run reports | ops lead | Y |
| T-4-60 | lens-config: KPI keys re-colour without release | CI | gate report | tech lead | Y |
| T-4-70..74 | lens-security: RLS performance decision, BI grants, PII exports, web headers/MFA, IDOR sweep | CI + staging | reports | security owner | Y |
| **T-4-40** | Every docs/09 page has Playwright e2e × 3 personas incl. the scope negative; visual snapshots at 1280/390 px; `axe` zero critical; the 37-page parity map in `rtm.yaml` shows each current page → new page | `main.yml` full e2e | report; parity map | sponsor delegate (parity map) | Y |
| **T-4-41** | **Report parity**: each of the 18 reports run for the 3 baseline zones and month; totals compared to the baseline Excel exports; differences explained by a documented KPI decision (D-03 Submit %, D-05 successful call, …) or fixed | script + review | `report-parity.md` with per-report status | sponsor delegate | Y |
| **T-4-42** | Excel export golden diff for STD Memo, Data Entry Log, Final Submit Log: cell values, number formats, sheet names equal to golden; export is a formatting step over the same query (query count = 1) | CI | gate report | tech lead | Y |
| **T-4-43** | Dashboards never touch the transaction log: the `app_web` Postgres role has no SELECT on `app.*` transactional tables; e2e suite passes with that role; `pg_stat_statements` on staging shows zero `app.memo`/`app.visit` reads from the web role during the full e2e | CI + staging | grant snapshot; pg_stat extract | tech lead | Y |
| **T-4-44** | **Sync-health dashboard complete** (§4.5 tile list): every tile has a formula, source view, "as of" stamp, refresh 60 s; verified against a staged fuzz day whose true numbers are known from the seed | Playwright + review | screenshot with seed numbers | sponsor delegate | Y |
| **T-4-45** | Alerts proven by fault injection: each Sev1/Sev2 rule (§4.7) fired once on staging by injecting its condition; reached the on-call phone and the Teams channel; each alert links a runbook page | drill | alert log; runbook index | ops lead | Y |
| **T-4-46** | Reconciliation reports in production shape (§4.6) run nightly on staging for 7 days with zero unexplained differences: device-vs-server, agg-vs-source, and the stale-flag/dead-cfg-key reports | nightly job | 7 report outputs | ops lead | Y |
| **T-4-47** | Web performance budget in CI: Lighthouse perf ≥ 80 on dashboard and daily tracking; JS ≤ 350 KB gz; dashboard p95 ≤ 1 s from the replica under S5 | CI + load | reports | tech lead | Y |

**EXIT**: a Wing Manager persona sees only their wing (delegate tries another wing's URL); the national dashboard loads in well under a second with the full-size synthetic fleet; the 18 reports match the baseline exports or carry an approved explanation; the load-test report at 9,850 users shows bundle p95 ≤ 2 s and 0 lost rows; alerts reached a phone.

### 2.7 Phase 5 — Programmes (5a Astha + Diamond League, 5b Superstar + free samples + remaining promo groups, 5c targets, revisions, tasks, leave)

**ENTRY**: Phase 2a promotion engine live; programme definitions (Q13), Diamond League earning rules (G-feat-20), target split/approval levels (Q12).

| Gate | Title | Test | Artefact | Verifier | Blocking |
| --- | --- | --- | --- | --- | --- |
| T-5-01 | lens-data: target revision re-aggregates only its month/scope | CI | gate report | tech lead | Y |
| T-5-60, 61 | lens-config: effective-dated promo rules; loyalty rate safety | CI | gate report | tech lead | Y |
| T-5-70..72 | lens-security: loyalty server-side, fraud job recall, dues rules | CI | gate report | security owner | Y |
| **T-5-40** | Programme flows offline (patrol): redemption with points deduction, gift photo (one per outlet), Astha tabs with quarter filters, task swipe-resolve; ledger reconciliation vs a reference model after a fuzzed day | lab + CI | reports | QA lead | Y |
| **T-5-41** | Promotion corpus complete: every live promotion group (Q13 list) has ≥ 3 golden memos from the baseline pack or business-provided examples; Discount Report totals equal the corpus sums | CI | gate report | sponsor delegate (corpus coverage table) | Y |
| **T-5-42** | Target revision approval chain e2e: requester ≠ approver per level, `is_final` flag, audit rows; negative target rejected at entry; Astha report renders "—" for the −20 target fixture (the −37,500 % regression) | Playwright + CI | report | sponsor delegate | Y |
| **T-5-43** | Leave (TSO → DMO) and task delegation (AMO/TSO → SR) end to end across phone and web with offline resolve | cross-stack | report | sponsor delegate | N |

**EXIT**: points and gifts reconcile (ledger = redemptions = photos); a promotion applies at sale and prints correctly; a revision routes through its approval levels; the negative-target bug cannot be reproduced.

### 2.8 Phase 6 — Admin / master data (6a CRUD, 6b config console + audit, 6c devices/OTP/releases)

**ENTRY**: lens-features §8c entity list (58 entities); permission bundles (D-sec-05); config console pages P1–P18 (lens-config §4.2).

| Gate | Title | Test | Artefact | Verifier | Blocking |
| --- | --- | --- | --- | --- | --- |
| T-6-01 | lens-data: config change → `acked_pct` reaches 100 % in the test fleet | lab | gate report | tech lead | Y |
| T-6-51 | lens-scale: config bounds/two-person/canary/revert; radius 0 rejected | e2e | report | tech lead | Y |
| T-6-60, 61 | lens-config: dead-key report; rollback-to-version | CI | gate report | tech lead | Y |
| T-6-70..72 | lens-security: console maker-checker, audit immutability, admin bundles | CI | gate report | security owner | Y |
| **T-6-40** | Admin CRUD e2e generated from the entity registry for all 58 entities: create/read/update/soft-delete × permission bundle × audit row present; import/export CSV round-trip for the bulk entities (outlets, routes, assignments, targets) | Playwright (generated) | gate report | tech lead | Y |
| **T-6-41** | **The sponsor changes the radius**: from the console, the delegate sets a zone's radius with a reason, a second approver approves, the lab phone in that zone shows the new value within 15 min, the next visit stores `radius_m_used`, the ack view shows 100 %; then one-click revert | live | screen recording | sponsor delegate | Y |
| **T-6-42** | **"Run without engineering" drill**: a business admin completes 12 operational tasks from a script with no engineer present — add an SR, assign a route with dates, issue a device OTP, replace a device, reset a password, approve an outlet request, set a target, revise a target, change a radius, publish a holiday, set `wave_pct` for a release, read the audit log for one of these — each within its time box | drill | drill log with times | sponsor delegate | Y |
| **T-6-43** | Backup/restore drill with verification: PITR restore of staging to T−1 h into a new server; row counts per table and the audit hash-chain head match the pre-restore snapshot; the app points at the restored server and syncs | drill | restore log | ops lead | Y |

**EXIT**: the drill (T-6-42) completed unaided; every admin write has an audit row the delegate can find in the viewer; a restore has been performed and verified.

### 2.9 Phase 7 — Migration, pilot, cutover (7a shadow import, 7b pilot parallel run, 7c wave 1, 7d waves 2..n, 7e decommission)

**ENTRY**: Phase 2 exit (pilot-ready) for 7b; Phases 3–6 exit for 7c; Apsis dump delivered in the docs/11 shape (Q18); machine-readable Apsis daily data for pilot routes (Q32, G-qa-03); pilot routes and consenting reps chosen (Q33); helpdesk staffed (Q35, G-qa-04); pen test passed (T-7-70); quotas approved (T-7-55); legal/data-residency answered (G-sec-01).

| Gate | Title | Test | Artefact | Verifier | Blocking |
| --- | --- | --- | --- | --- | --- |
| T-7-01, 02 | lens-data: per-zone control totals match; re-import no-op | job | reconciliation report | sponsor delegate | Y (7a) |
| T-7-20 | lens-sync: pilot parallel reconciliation 10 consecutive days | daily job | compare reports | sponsor delegate | Y (7b) |
| T-7-51..56 | lens-scale: load at 1.5× wave, worst case + chaos, kill-switch drill, DR rehearsal, quotas, full-fleet load + soak | Azure Load Testing | run reports | ops lead | Y (7c/7d) |
| T-7-60, 61 | lens-config: wave rollback by flag; launch-day change rate limit | e2e | gate report | tech lead | Y (7c) |
| T-7-70..75 | lens-security: pen test, key rotation, tabletop, DR incl. Key Vault, dump credential handling, auth storm | reports | security owner | Y (7c) |
| **T-7-80** | **Shadow import of the real dump**: importer run on the full dump; row counts per table vs the dump's data dictionary; quarantine ≤ 1 % of rows per table with every reason counted (ASSUMPTION threshold; raise a gap if higher); §5.3 control totals per zone match Apsis reports with zero tolerance on counts and balances | job | `import_reconciliation` report signed | sponsor delegate + finance owner (dues) | Y (7a) |
| **T-7-81** | Opening balances spot-check in the field: for 30 random outlets across 3 pilot zones, the retailer's due and loyalty balance in the new app equal the Apsis app and (where available) the retailer's own record | field | spot-check sheet | pilot lead | Y (7b entry) |
| **T-7-82** | **Pilot parallel run** (§5.2): daily automated comparison per route-day for all pilot routes; pass = 10 consecutive trading days with zero category-C (sync loss) discrepancies, exact memo count and STD, dues exact, value exact to the paisa; every other discrepancy categorised by 09:00 next day | daily job + review | `/docs/evidence/phase-7/T-7-82/day-NN.md` | sponsor delegate | Y (7c) |
| **T-7-83** | **Rollback drill suite** on staging before wave 1, each timed: (1) wave rollback by flag (T-7-60), (2) app rollback to N−1 APK via `app_release`, (3) config revert of a C3 key, (4) API revision rollback via traffic weight, (5) DB PITR (T-6-43), (6) region DR (T-7-54). Each has a runbook page and a measured time | drill | drill log with RTOs | ops lead; sponsor delegate | Y (7c) |
| **T-7-84** | **Day-one readiness checklist** (§5.5) 100 % complete for the wave, signed by the four owners (eng, ops, business, support) at T−1 | checklist | signed checklist | sponsor | Y (each wave) |
| **T-7-85** | **Support readiness**: helpdesk staffed per §5.7 arithmetic, Bangla scripts for the top-20 issues, escalation tested with 20 synthetic tickets end to end within SLA, TSO fix-it toolkit verified on a lab phone, status banner and helpdesk number visible on the locked-out screen (`GET /config/public`) | drill | ticket log; script pack | sponsor delegate | Y (7c) |
| **T-7-86** | **Wave go/no-go**: previous wave's 5-day metrics vs thresholds (login % ≥ 95 % of pre-switch Apsis login % by day 3; submit % ≥ pre-switch; reconciliation mismatch ≤ 0.1 % of route-days; crash-free ≥ 99.5 %; battery complaints ≤ 1 % of wave; open P1 tickets = 0) recorded and signed before the next wave starts | review | go/no-go record | sponsor | Y (each wave) |
| **T-7-87** | Post-wave reconciliation: for 5 trading days after each wave, device totals (telemetry) = server accepted + rejected per route-day; `agg` = `app` sums; final-submit coverage = 100 % of the wave's zones by 21:00 | nightly jobs | reports | ops lead | Y (each wave) |
| **T-7-88** | Decommission criteria: full final reconciliation (all zones, all months imported) passes; the final Apsis delta imported and compared; Apsis credentials rotated/removed (T-7-74); raw dump archived to immutable storage then deleted per policy; retailer-facing memo numbering continuity confirmed (Q5) | review | decommission record | sponsor | Y (7e) |
| **T-7-89** | Retrospective: known-issue register closed or accepted; DECISIONS.md and runbooks updated; the gate report archived | review | retro notes | sponsor delegate | N |

**EXIT per wave**: T-7-84 signed at T−1; T-7-86 signed before the next wave; T-7-87 clean for 5 days. **EXIT Phase 7**: all waves stable; T-7-88 signed.

### 2.10 Requirement → gate coverage (the sponsor's six requirements)

| Requirement | Proven by (blocking gates) |
| --- | --- |
| R1 Data: any dashboard from stored data | T-0-01..04, T-1-01..04, T-2-01..04, T-4-01..03, T-4-43, T-4-46, T-7-80 |
| R2 Features: parity | T-0-49 (baseline), T-1-41, T-2-41, T-2-43, T-2-49, T-3-42, T-4-40, T-4-41, T-5-41, T-6-40, T-0-46 (every F has a gate) |
| R3 Scale: 8,500 simultaneous | T-1-51, T-2-51..54, T-4-51..57, T-7-51..56, T-4-45, T-7-83, T-7-85 |
| R4 Battery / data | T-1-44, T-1-45, T-2-40 (3 devices), lens-sync §5.3/5.4 per RC, T-4-57 |
| R5 Offline + immediate sync | T-1-20..35, T-1-40, T-2-20..34, T-2-40, T-3-40, T-5-40, T-7-82 |
| R6 Admin config with audit reaching the field | T-0-60..64, T-1-60..64, T-2-60..69, T-6-41, T-6-42, T-6-70..72, T-7-60 |
| Process: step-by-step testable | §2.0 rules; one demo per sub-milestone; T-1-47, T-2-48, T-6-42 |

---

## 3. CI/CD, environments, migrations, flags, distribution

### 3.1 Workflows (`/infra/.github/workflows`, copied to `/.github/workflows`)

| Workflow | Trigger | Jobs (in order; parallel where no arrow) | Target time |
| --- | --- | --- | --- |
| `pr.yml` | pull_request (path filters per workspace) | lint+typecheck · contract build + drift + `oasdiff` · api unit · api integration (4 shards, Testcontainers) · api property 10k · app `analyze` + unit + widget goldens + property · app debug APK size (app paths) · web unit + component · e2e smoke (compose stack, 20 flows) · db: `squawk` + apply on fresh PG + apply on staging-schema snapshot · infra: `bicep build` + PSRule + what-if (infra paths) · security: CodeQL, Semgrep, gitleaks, dependency review, OSV (pub) · `rtm-check` · gate-report upload | ≤ 15 min |
| `main.yml` | push to main (merge queue) | build images api/worker/web (SBOM, Trivy, cosign) → push ACR → **migrate staging (expand only)** → deploy revision 0 % → smoke → 100 % → full Playwright e2e → k6 smoke S2 → Flutter release APK per ABI (upload key) → upload `releases/staging/` + `app_release(draft)` → emulator upgrade matrix (N−2, N−1 → N) → gate report | ≤ 60 min |
| `promote-prod.yml` | `workflow_dispatch(release_tag, wave_pct)`; environment `prod` with 2 required reviewers (eng lead + business approver) | preconditions (staging soak ≥ 24 h, all blocking gates for the tag green, change window OK unless `hotfix=true`) → migrate prod (expand) → revision at 10 % → 10-min canary (5xx ≤ 0.5 %, p95 within budget, `reconcile_mismatch` not rising) auto-abort → 50 % → 100 % → smoke → APK publish `releases/prod/` with `rollout_wave`/`wave_pct` → gate report | ≤ 45 min |
| `contract-phase.yml` | manual, ≥ 1 release after the expand | runs the **contract** migrations (drop old columns/tables) after checking no revision older than N is serving traffic | ≤ 10 min |
| `nightly.yml` | 20:00 UTC (02:00 Dhaka) | property 100k · full e2e matrix · schema compat (previous release's api tests vs current migrations) · importer full-size synthetic · k6 S2 1 h · dependency audit · stale-flag + dead-cfg-key reports · flake report · corpus replay (after 7b) | ≤ 3 h |
| `release-app.yml` | tag `app-v*` | build, sign, upload, `app_release` row (draft); release manager sets wave/pct in the console | ≤ 20 min |
| `device-lab.yml` | RC tag or manual; self-hosted runner in the lab | install on reference devices → patrol suite (45) → upgrade matrix on devices → zero-chatter check → (manual) 8 h battery protocol → publish `/docs/perf/` | ≤ 1 working day (+ 8 h) |
| `load-test.yml` | manual (gate) | scale staging to prod SKU → Azure Load Testing scenario → pass/fail → scale down → report | per scenario |
| `infra.yml` | PR what-if; main → staging; manual → prod | Bicep deploy; Azure Policy compliance check; monitor workbooks/alerts as code | ≤ 20 min |

Required checks on `main`: everything in `pr.yml` except path-filtered jobs that did not run; **merge queue** enabled so the integration suite runs on the merged result. Actions pinned by SHA; OIDC federated credentials; no long-lived cloud secrets (lens-security §10).

### 3.2 PR checks — what fails a PR (beyond test failures)

Coverage below floor (§1.1 P7); `oasdiff` breaking change without `schema_version` bump; a `cfg.` literal not in the registry (T-0-63); `rtm-check` orphans; a new endpoint without a scope-harness case; `no-wall-clock`, `no-pg-mock`, `no-hardcoded-string`, `no-unscoped-query` lint hits; APK size +5 % warning / +15 % fail vs last release; a migration that `squawk` flags (non-concurrent index, `NOT NULL` without default on a populated table, type change, rename); a quarantined test used to prove a gate.

### 3.3 main → staging

Deploy is **expand-migrate → deploy → verify**: the migration job (ACA Job, role `app_migrate`, `lock_timeout=5s`, `statement_timeout=15min`) runs before the new revision; the new revision receives 0 % traffic until `/healthz/ready` and the smoke suite pass; then 100 % (staging has no canary period). Failure at any step leaves the previous revision serving; the job posts the failing step to the Teams channel. Staging soak: a release tag must sit on staging ≥ 24 h (≥ 72 h for releases touching `sync/ingest`, migrations with backfills, or the app's Drift schema) before `promote-prod.yml` accepts it.

### 3.4 Manual promote → prod

Environment protection: two reviewers (engineering lead + business approver), change window **outside 07:00–10:00 and 16:30–19:30 Dhaka** and never on a wave's first three days unless `hotfix=true` (which requires a P1 ticket id). Blue/green via ACA traffic weights 10 → 50 → 100 with a 10-minute canary at each step reading App Insights: 5xx ≤ 0.5 %, p95 within SLO, `aron_reconcile_mismatch_routes` not increasing, `aron_sync_rows_total{outcome="rejected"}` rate not increasing; any breach → automatic weight back to the previous revision and a Sev2 alert. Rollback of the API is a traffic weight change (seconds); rollback of a migration is a **forward fix** (never `DROP` what a serving revision reads — the expand/contract rule makes the previous revision compatible by construction).

### 3.5 Environments

See §1.12. Pilot runs on **prod** (real routes, real retailers) while Apsis remains the system of record; the pilot's accounts are flagged `pilot=true` so their data is included in reconciliation but excluded from national rollups until cutover (`cfg.flag.pilot_in_rollups`, default false).

### 3.6 Database migrations — forward-only, expand/contract

| Rule | Detail |
| --- | --- |
| Files | `/db/migrations/NNNN_<name>.sql`, plain SQL, immutable once merged (CI compares checksums with the `schema_migrations` table on every env; a changed shipped file fails the deploy). Runner: `dbmate` (or an equivalent 100-line Node runner); no ORM-generated migrations. Partition creation and retention are **jobs**, not migrations. |
| Expand/contract | Release N: **expand** (add nullable column / new table / new index `CONCURRENTLY` / `CHECK … NOT VALID`); app code N dual-writes old+new and reads old. Release N+1: backfill in batches (≤ 10k rows, `pg_sleep(0.05)` between, resumable by key), `VALIDATE CONSTRAINT`, reads switch to new. Release N+2: **contract** (drop old) via `contract-phase.yml` only after no revision older than N+1 serves traffic. |
| Forbidden | `ALTER TABLE … RENAME`, type changes in place, `NOT NULL` without a constant default on populated tables, non-concurrent index creation, `UPDATE` without a batch bound, dropping anything a serving revision reads, editing a shipped migration. Enums: add values only; business code lists live in `cfg` code tables (lens-config), so enum churn disappears. |
| Locks | `SET lock_timeout = '5s'` in every migration; a lock wait fails fast and the job retries up to 5 times with jitter; DDL that needs an `ACCESS EXCLUSIVE` lock on a hot table is scheduled in the 02:00–03:00 Dhaka window. |
| Compatibility tests | nightly `schema-compat`: the previous release's api integration suite runs against the current migrations (proves N−1 API works on N schema); `squawk` on every PR; a dry run of every migration against a **PITR clone of prod** (once prod has data) measures duration and lock time before promotion. |
| Device (Drift) | same discipline: additive schema versions; `onUpgrade` tested N−2→N on every RC (T-2-44); a newer DB refuses to open in an older build (guard + message) rather than corrupting; pending outbox rows survive any upgrade. |

### 3.7 Feature flags for dark launches (D-qa-08)

Flags are config keys `cfg.flag.<name>` in the lens-config registry (scoped, audited, bounded, propagated like any key) — no third-party flag service, one audit trail. Three kinds:

| Kind | Example | Default | Lifecycle |
| --- | --- | --- | --- |
| `release` (dark launch) | `cfg.flag.memo_edit_v2`, `cfg.flag.pilot_in_rollups` | off | code ships dark; turned on per scope (one zone → territory → all); **removed within two releases after 100 %** (nightly stale-flag report lists flags > 30 days at 100 %) |
| `ops` (switch) | `cfg.ops.kill_switch`, `cfg.ops.read_only_mode`, `cfg.ops.sync_hold_s` | safe | permanent; drilled (T-2-46, T-7-53) |
| `wave` | `cfg.flag.new_app_login_enabled` per scope, `cfg.release.wave_pct` | off | the cutover lever; rollback = flip (T-7-60) |

Rules: a flag that changes the **shape of captured data** is not a flag — it is a `schema_version` bump; flags are evaluated through the cached resolver (never per row in a loop); flags marked `test_both=true` run the api and web suites twice in `nightly.yml` (all on / all off); the device stamps `config_version` on captures, which already records which flags were in force.

### 3.8 App distribution (D-qa-09)

| Step | Mechanism |
| --- | --- |
| Build | `flutter build apk --split-per-abi --obfuscate --split-debug-info` (+ `appbundle` if a Play track is used); version `major.minor.patch+build`; `schema_version` of the contract embedded; symbols uploaded to Sentry |
| Signing | upload key fetched via OIDC from Key Vault in CI; app signing key in Key Vault (or Play App Signing if Play is used); keys never on laptops |
| Channels | `internal` (engineering, 10 devices, every main build), `pilot` (pilot devices, RC builds), `wave` (fleet, by `rollout_wave` + `wave_pct`), `rollback` (previous prod APK always downloadable) — all as blobs under `releases/<channel>/` behind Front Door with caching (lens-scale G-scale-16) |
| Registry | `app_release(version, min_version, apk_url_by_abi, notes_bn/en, rollout_wave, wave_pct, status, published_by, published_at)`; `GET /config/public` serves `min_version`, `latest_version`, `update_url`, `wifi_only` |
| Staged rollout inside a wave | `wave_pct` 1 % → 10 % → 50 % → 100 % over ≥ 3 days, each step gated on crash-free ≥ 99.5 % and sync rejection rate not rising for that version; the device decides eligibility from `hash(device_uuid) mod 100 < wave_pct` so the cohort is stable |
| Updater | in-app check once per day and on Settings; download Wi-Fi-preferred (`cfg.release.update_wifi_only`); `min_version` blocks **new-day login** only — never capture or upload (D-scale-9) |
| Coexistence | package id differs from the Apsis app; both apps share the Bluetooth printer (SPP connect/disconnect per print — lens-sync §6) |
| Server compatibility | the server accepts app versions N, N−1, N−2 (`schema_version`); older versions are listed in `cfg.release.blocked_versions` (upload still allowed) |

### 3.9 Release train

Weekly release train to staging (Tuesday), promotion to prod on Thursday outside the change window, hotfix path any day via `hotfix=true` with a P1 id; app RC every two weeks through the device lab; `BREAKING.md` and `CHANGELOG.md` per release; a release is **not shippable** without its `/docs/perf/battery-<v>.md` (lens-sync D-sync-14) and gate report.

---

## 4. Observability

### 4.1 Principles

No PII anywhere in logs, traces, metrics or crash reports: numeric ids, `client_uuid`, `memo_no`, `device_id`, zone/route ids only; **no names, phones, NID, free text, raw URLs with query strings, tokens, or full-precision coordinates** (logs carry distance and flags, not lat/lng; lens-security §4.3 redaction is the enforcement). One `request_id` (W3C `traceparent`) flows API → worker → logs → App Insights; the device sends its `batch_uuid` which becomes the server span attribute. Device telemetry rides **inside the sync batch** (R4) — never a separate network path. Sampling keeps 100 % of errors, 429/5xx, slow (> 2 s) and every `sync/*` summary, 5 % of other 2xx.

### 4.2 Structured log schema (pino, JSON, one line per event)

| Field | Type | Notes |
| --- | --- | --- |
| `ts`, `level`, `service` (api/worker/web/job), `env`, `version`, `revision` | std | |
| `request_id`, `trace_id`, `span_id` | string | from `traceparent` |
| `route` | string | the **route template** (`POST /sync/batch`), never the raw path |
| `status`, `duration_ms`, `bytes_in`, `bytes_out` | number | |
| `user_id`, `role`, `device_id`, `zone_id`, `territory_id` | number | ids only |
| `batch_uuid`, `rows`, `accepted`, `rejected`, `replayed`, `reason_codes[]` | sync events | per batch; per-row detail only in `sync_rejected` (DB), not logs |
| `config_version`, `schema_version`, `app_version`, `android_sdk` | ints/strings | from headers |
| `event` | enum | the event catalogue: `auth.login`, `auth.refresh`, `bundle.serve`, `bundle.pregen`, `sync.batch`, `sync.row_rejected`, `sync.replay`, `agg.item`, `agg.lag`, `config.change`, `config.ack`, `geo.recheck_mismatch`, `fraud.signal`, `day.sales_submit`, `day.final_submit`, `import.step`, `reconcile.diff`, `alert.fired` |
| `error.code`, `error.message` (allow-listed), `error.stack` (sampled) | | messages are from a fixed table, never interpolated with user data |

Device logs (Flutter `logger` → ring buffer 5 MB) follow the same schema, are included in PDA-to-Support uploads, and never leave the device otherwise. Web logs: server-side only (Next.js route handlers); browser errors to Sentry with the same scrubber.

### 4.3 Application Insights, Sentry, and device telemetry (D-qa-06)

| Signal | Tool | Notes |
| --- | --- | --- |
| API/worker/jobs traces, dependencies (Postgres, Redis, Blob), custom metrics | Azure Monitor OpenTelemetry distro for Node | one workspace per env; daily cap; adaptive sampling as §4.1 |
| App crashes/errors (Flutter), web browser errors | **Sentry** (App Insights has no Flutter SDK) with `beforeSend` scrubber, release = app version, `device_id` as user id, breadcrumbs limited to screen names; batched upload on Wi-Fi/next sync; data residency confirmed with legal (G-sec-01, Q36) | ASSUMPTION: SaaS acceptable; otherwise self-hosted Sentry on ACA |
| Device operational telemetry | one `telemetry` record type in the sync batch (≤ 300 B), per device-day counters since last send: `battery_pct`, `battery_drop_pct_since_last`, `pending_rows`, `pending_photos`, `sync_attempts`, `sync_failures{reason}`, `gps_fixes`, `gps_ms`, `wake_lock_ms`, `rows_captured{type}`, `app_version`, `android_sdk`, `free_storage_mb`, `crash_count`, `clock_offset_ms`, `mock_capable` | stored in `dw.fact_device_day`; no PII; it is operational, not personal (`cfg.telemetry.device_events_enabled`, default true) |
| Web RUM | Playwright synthetic + Lighthouse CI; real-user timing sampled 5 % via Sentry performance | |
| Synthetic probes | Azure Monitor availability tests from Singapore + an **Android 7/8 physical probe** in the lab every 15 min: login (refresh), bundle `If-None-Match`, 12-row batch, `/config/public` | TLS-on-old-devices early warning (lens-scale 4.12) |

### 4.4 Metric catalogue (names as emitted; `aron_` prefix; labels in braces)

| Metric | Type | Labels | Source | SLO / alert |
| --- | --- | --- | --- | --- |
| `aron_http_requests_total` | counter | route, status | api | 5xx ≤ 0.5 % |
| `aron_http_duration_ms` | histogram | route | api | bundle p95 ≤ 2 s; trickle ack p95 ≤ 1.5 s; catch-up p95 ≤ 8 s |
| `aron_http_429_total` | counter | route | api | ≤ 5 % |
| `aron_sync_batches_total` | counter | outcome (accepted/partial/replayed/rejected/413) | api | success ≥ 99.5 % within 3 attempts |
| `aron_sync_rows_total` | counter | type, outcome (accepted/replay/rejected/parked/conflict/flagged), reason_code | api | rejected ≤ 0.5 %; conflict = 0 expected |
| `aron_sync_batch_rows` | histogram | — | api | |
| `aron_sync_retry_attempt` | histogram | — | api (from `X-Retry-Attempt`) | retry ratio |
| `aron_bundle_served_total` | counter | kind (full/delta/304), source (snapshot/live) | api | live share < 5 % in the storm |
| `aron_bundle_pregen_duration_s`, `aron_bundle_pregen_users` | gauge | — | job | ≤ 15 min; = users in scope |
| `aron_agg_lag_s` | gauge | grain | worker | p95 ≤ 60 s |
| `aron_agg_dirty_depth`, `aron_agg_oldest_s` | gauge | — | worker | depth draining; oldest ≤ 5 min |
| `aron_agg_reconcile_diff_total` | counter | grain | nightly | 0 surviving two runs |
| `aron_reconcile_mismatch_routes` | gauge | zone | nightly + submit events | ≤ 0.1 % of route-days |
| `aron_routes_by_state` | gauge | state, zone | route_day | login %, submit %, final-submit % |
| `aron_final_submit_zones` | gauge | territory | final_submit | 100 % by 21:00 |
| `aron_geo_valid_pct`, `aron_force_sale_pct`, `aron_mock_flag_total`, `aron_fraud_signal_total{kind}` | gauge/counter | territory | worker | geo-valid drop > 30 pts alert; force-sale % alert |
| `aron_quarantine_rows` | gauge | reason | sync_rejected | > 100/h alert |
| `aron_photo_pending_gt24h` | gauge | zone | media | 95 % within 24 h |
| `aron_config_version`, `aron_config_ack_pct{version}` | gauge | scope | cfg | ≥ 95 % online devices in 15 min |
| `aron_devices_active`, `aron_devices_by_version`, `aron_devices_by_sdk` | gauge | version/sdk | device + telemetry | version mix for rollout gates |
| `aron_device_battery_drop_pct` (p50/p95), `aron_device_pending_rows` (p95), `aron_device_sync_failures{reason}`, `aron_device_gps_ms` | histogram | app_version | telemetry | drop p95 ≤ budget; pending p95 → 0 after 17:00 |
| `aron_app_crash_free_sessions_pct` | gauge | app_version | Sentry | ≥ 99.5 % |
| `aron_db_connections{pool}`, `aron_db_lock_wait_ms`, `aron_db_replica_lag_s`, `aron_db_storage_pct` | gauge | — | Postgres metrics | lens-scale §5.3 |
| `aron_import_rows{table,outcome}`, `aron_import_control_total_diff{zone,metric}` | gauge | — | importer | 0 diff for counts/balances |
| `aron_parallel_run_diff{route,category}` | gauge | — | pilot compare job | category C = 0 |
| `aron_alerts_fired_total{sev}`, `aron_alert_ack_min` | counter/histogram | — | alert webhook | ack ≤ 15 min Sev1 |

### 4.5 Dashboards

1. **Sync-health (business-facing, F-SYS-026; in-product page, 60 s refresh from rollups)** — per wing → division → territory → zone, with "as of": target routes (planned today, D-09) · logged-in routes and **Login %** (D-08) · uploaded routes · sales-submitted routes and **Submit %** (D-03; "Upload-of-login %" beside it) · final-submitted zones / remaining · last batch age per zone · reconciliation mismatches (route, type, device count, server count; click → detail) · rejected/quarantined rows by reason · photos pending > 24 h · mock-GPS flagged visits today · geo-valid % vs yesterday same hour · force-sale % · app version mix · config version reach (current version, acked %, pending devices by zone) · open P1/P2 tickets count (from the helpdesk tool if integrated, else manual) · banner state.
2. **Ops: traffic & latency** — req/s by route group; p50/p95/p99; 429/5xx; retry histogram; replay hits; replicas; pre-scale state; TLS errors by Android SDK.
3. **Ops: database** — CPU, IOPS, connections (PgBouncer), lock waits, longest txn, replica lag, storage %, autovacuum lag, WAL.
4. **Ops: worker** — dirty depth, oldest item, recompute duration, reconcile diffs, dead items.
5. **Devices** (aggregated) — version/SDK mix, battery drop p50/p95 by version, pending rows p95 by hour, sync failures by reason, GPS ms, crash-free %.
6. **Config reach** (lens-config §3.3) — per version: targeted/acked/pending, pending by zone, likely-offline badge.
7. **Fraud / exceptions** (lens-security §7.4) — signals by kind and territory; review backlog.
8. **Pilot daily** (§4.8) — the parallel-run comparison per route-day with category colours.
9. **Release** — current revision weights, canary metrics, app `wave_pct` cohorts and their crash-free/rejection rates.

Workbooks and alert rules are **code** in `/infra/monitor/` (Bicep + workbook JSON), deployed by `infra.yml`.

### 4.6 Reconciliation reports (all nightly unless stated; results in `dw.*` tables and on the sync-health page)

| Report | Compares | Grain | Tolerance | Action on diff |
| --- | --- | --- | --- | --- |
| Device vs server | each device's last `device_counts` claim (submit event / telemetry) vs `ingest_registry` counts | route-day × type | 0 | row on sync-health; AMO contacts the SR; unexplained after 24 h → ticket |
| Aggregate vs source (lens-data §4.4 step 8) | `SUM/COUNT` from `app.*` vs `dw.agg_*` for the last 7 business dates | zone-day | 0 | re-enqueue (self-heal); alert if it survives two runs |
| Ledger balances | dues: Σ memo due − Σ collections vs `outlet_due_balance`; loyalty: Σ ledger vs balance | outlet | 0 | block further redemption/credit for the outlet; finance review |
| Final-submit coverage | zones final-submitted vs zones with activity | zone-day | 100 % by 21:00 | TSO reminder; Daily Tracking |
| Import control totals (§5.3) | `app` after import vs Apsis reports | zone × metric | 0 for counts/balances; ≤ 0.01 % for STD where Apsis rounds | no cutover until clean |
| Parallel run (§5.2) | new app vs Apsis per pilot route-day | route-day | exact (memo, STD, dues, value) | categorise by 09:00 |
| Stale flags / dead cfg keys / quarantined tests | code vs registry vs test tags | — | lists | weekly cleanup |

### 4.7 Alerting and routing

Thresholds are lens-scale §5.3 plus: crash-free % < 99 % for any app version with > 100 devices (Sev2, pause `wave_pct`); `aron_reconcile_mismatch_routes` > 0.5 % of today's routes at 19:00 (Sev2); `aron_config_ack_pct` < 80 % 30 min after a C3 change (Sev2); synthetic Android-7 probe failing 3× (Sev1); telemetry silence (no `telemetry` records from a zone that was active yesterday, by 10:00) (Sev2); importer step failed (Sev2 during 7a); parallel-run job did not produce today's report by 21:00 (Sev2 during 7b). Routing: Sev1 → on-call engineer phone (Azure Monitor action group → PagerDuty/Opsgenie or SMS) + ops Teams channel + business owner SMS during a wave; Sev2 → Teams + e-mail, phone during business hours 07:00–21:00 Dhaka; Sev3 → Teams digest. Every alert links a runbook page in `/docs/runbooks/`; an alert that fires without a runbook is itself a Sev3 ("undocumented alert"). Alert hygiene: weekly review of fired alerts; an alert that fired > 5× without action is re-tuned or deleted.

### 4.8 What the pilot measures daily (the sheet the pilot lead fills by 09:30 the next morning)

| # | Measure | Source | Target | Owner |
| --- | --- | --- | --- | --- |
| 1 | Routes planned / logged in / uploaded / sales-submitted / final-submitted (pilot zones) | sync-health | 100 % / 100 % / 100 % / 100 % / 100 % by 21:00 | pilot lead |
| 2 | Parallel-run discrepancies by category A–E per route (§5.2) | pilot dashboard | C = 0; others explained | pilot lead + eng |
| 3 | Memo count, STD per SKU, net value, dues collected — new vs Apsis | compare job | exact | pilot lead |
| 4 | Reconciliation mismatches (device vs server) | sync-health | 0 | eng |
| 5 | Rejected / parked / conflict rows by reason | sync-health | rejected explained; conflict 0 | eng |
| 6 | Time from last capture to server receipt (p50/p95 per device) | telemetry + registry | p95 ≤ 3 min while online | eng |
| 7 | Battery drop per device-day (telemetry) and the SR's own reading at 17:00 | telemetry + sheet | within lens-sync §5.3 budget | pilot lead |
| 8 | Mobile data used by the app per device-day | telemetry (`netstats` on 2 lab-managed pilot phones) | ≤ 1 MB without photos; ≤ 3 MB with | pilot lead |
| 9 | Geo-valid %, force-sale %, mock flags, plausibility flags per SR | sync-health | in line with the pilot baseline; every flag reviewed by the AMO | AMO |
| 10 | Photos pending > 24 h | sync-health | 0 | eng |
| 11 | Printer failures, reprints | SR sheet + `print_count` | ≤ 1 per device-day | pilot lead |
| 12 | Crash-free sessions; ANRs | Sentry | ≥ 99.5 % | eng |
| 13 | Support contacts (count, top reasons, time to resolve) | helpdesk | trend down; P1 = 0 | support lead |
| 14 | Config changes made and their reach | config dashboard | 100 % acked by next morning | sponsor delegate |
| 15 | Retailer feedback on the memo (any complaint) | SR sheet | 0 | pilot lead |
| 16 | SR feedback: screens that confused, steps slower than the old app | SR sheet (Bangla) | logged, triaged | pilot lead |
| 17 | Time to complete a sale (first tap on outlet → memo printed), median per SR | activity log | ≤ old app (measured in the baseline recording) | eng |
| 18 | Business-date correctness: rows with `business_date_mismatch` or `clock_skew` | DQ flags | 0 unexplained | eng |
| 19 | App version mix on pilot devices | devices dashboard | 100 % on the RC | eng |
| 20 | Open P1/P2 tickets | tracker | 0 P1 | sponsor delegate |

### 4.9 Telemetry cost control

Log Analytics daily cap per env; adaptive sampling (§4.1); device telemetry ≤ 300 B per device-day (≈ 3 MB/day fleet-wide); Sentry quota per project with spike protection; nightly report of ingestion GB vs budget (lens-scale T-4-57: ≤ 1 GB/day per 1,000 devices).

---

## 5. Pilot and cutover test plan (docs/11 made testable)

### 5.1 Preconditions (Phase 7b entry, checked as gates)

Phase 2 exit (pilot-ready); T-7-80 shadow import clean for the pilot zones; T-7-81 field spot-check; Apsis daily data for the pilot routes available in machine-readable form (Q32) — **if Apsis will not export, the fallback is manual: the pilot lead photographs the Apsis app's end-of-day summary and the printed memos, and a data-entry clerk keys them (G-qa-03 stays a blocker for anything larger than the pilot)**; pilot routes chosen for variety (urban dense, rural, hill; GT and Astha outlets; a wholesale buyer; at least one route with placeholder coordinates P-09) (Q33); pilot SRs briefed on double entry and incentive agreed; both apps installed on the pilot phones; helpdesk line for pilot = the pilot lead's phone; `cfg.flag.pilot_in_rollups=false`.

### 5.2 Parallel-run comparison per day

**Compared per route-day** (job at 20:30 Dhaka, review by 09:00 next day):

| Measure | New app source | Apsis source | Tolerance |
| --- | --- | --- | --- |
| Outlets visited; zero-sale calls | `agg_daily_route` | daily report / app summary | exact |
| Memo count (active, line_count > 0) | `agg_daily_route` | report | exact |
| STD per SKU (base units) | `agg_daily_route_sku` | report (sticks/pieces per P-04) | exact |
| Gross / discount / net value | `agg_daily_route` | report | exact to the paisa (requires 2a engine) |
| Dues collected; outstanding per outlet after the day | ledgers | report / Apsis app | exact |
| Stock issued / returned | stock events | stock memo | exact |
| Login time, upload time, sales-submit time | `route_day` | Data Entry Log | informational |
| Geo-valid %, force-sale count | facts | Apsis geo report if exported | informational (new control is stricter) |

**Discrepancy categories** (every difference gets exactly one): **A** human double-entry omission or typo (SR entered in one app only / different qty) · **B** rule difference (promotion, rounding, unit, KPI definition) → decision or fix · **C** **sync loss or duplication** (row captured on the device but not on the server, or doubled) → P1, blocker · **D** Apsis-side change after the fact (edit, back-office correction) · **E** timing / business-date (row landed on another date). **Pass rule** (D-qa-11): 10 consecutive *trading* days (calendar from `cfg.calendar.*`) with zero C across all pilot routes, exact memo count and STD after A/D/E are explained, and B closed by a recorded decision. A single C resets the count. Weekly: the SRs' time-per-sale and battery readings are compared with the baseline.

### 5.3 Data-dump reconciliation control totals (7a; also the delta before every wave)

| Control total | Grain | Apsis source | Tolerance |
| --- | --- | --- | --- |
| Outlets by status (active / closed-stub P-08) | zone | retailer list + sales history | 0 |
| Outlets with coordinates; with placeholder coordinates (P-09) | zone | retailer list | 0 (placeholders flagged, not "fixed") |
| Routes; route→SR assignments valid on the import date | zone | route list | 0 |
| Users by role | territory | user list | 0 |
| SKUs and price rows by type and validity | — | catalogue | 0 |
| STD per SKU per month (8 months) | zone | monthly STD report | 0; ≤ 0.01 % only where the Apsis report rounds |
| Memo count per month | zone | report | 0 |
| **Outstanding dues** per outlet (sum and count of outlets with dues) | zone | dues report / per-outlet balance | 0 |
| **Loyalty points balance** per outlet | zone | Diamond League report | 0 |
| Targets per route/zone × product × month | zone | target allocation report | 0 |
| Photos linked to records | zone | media manifest | count equal; missing files listed |
| Quarantined rows by reason (P-05..P-14) | table | — | ≤ 1 % per table (ASSUMPTION); each reason explained |

Output: `import_reconciliation(run_id, zone_id, metric, source_value, imported_value, diff, status, note)` and a signed PDF for finance (dues) and sales ops (STD/targets). The importer is re-runnable (T-7-02); a second run must change nothing.

### 5.4 Rollback drills (T-7-83; all on staging before wave 1, repeated quarterly; each with runbook + measured time)

| Drill | Trigger simulated | Steps | Pass |
| --- | --- | --- | --- |
| Wave rollback by flag | wave 2 breaks | `cfg.flag.new_app_login_enabled=false` for the wave scope; banner; old app resumed; pending rows still upload; export of the wave's new-app data for the Apsis side (G-qa-11) | ≤ 5 min to all online devices; 0 rows lost |
| App rollback | bad RC | `app_release` of N marked `blocked_versions`; `latest` = N−1; updater offers N−1; upgrade matrix proves N−1 opens the N DB? **No — downgrade is unsupported**: the drill confirms the guard message and that pending rows still upload from N before the user re-installs N−1 (loss-free path documented) | documented, timed |
| API revision rollback | canary breach | traffic weight to previous revision | ≤ 60 s |
| Config revert | radius typo | one-click revert; ack reach 100 % | ≤ 15 min online devices |
| DB PITR | data corruption bug | restore to T−x into new server; verify (T-6-43); repoint | RTO measured (target ≤ 2 h; ASSUMPTION) |
| Region DR | region outage | lens-scale T-7-54 | RTO ≤ 4 h, RPO ≤ 15 min |

### 5.5 Day-one readiness checklist (per wave; T-7-84; every line has an owner and a tick)

**Data** — final delta import reconciled (§5.3) and signed · opening dues/loyalty spot-check for 10 outlets in the wave · route assignments for the wave's SRs valid for the switch date · holidays/working days for the month entered · targets for the current month present.
**App** — RC on the `wave` channel with `wave_pct` set · `min_version` unchanged · rollback APK downloadable · upgrade matrix green · battery/perf file for the RC published · Bangla strings signed.
**Backend** — load test at 1.5× wave size green (T-7-51/52) · quotas confirmed · pre-scale schedule enabled · bundle pre-gen verified for the wave's users (count + 3 spot checks) · synthetic probes green · change freeze in force.
**Config** — radius values per territory/zone reviewed by the TSOs · `checkout_earliest_time` confirmed · promo rules effective-dated · kill-switch owners named · launch-day change rate limit on.
**Observability** — sync-health on the war-room screen · alerts routed to the on-call phones (test alert fired this morning) · pilot daily sheet template for the wave · telemetry from the wave's devices visible (test device).
**Support** — helpdesk staffed per §5.7 · scripts and known-issue board published · TSOs briefed on their toolkit · OTP bulk issuance done for devices that need binding · escalation tree with phone numbers printed and in the app's locked-out screen.
**People** — war room (two engineers + ops + business owner) · AMOs/TSOs of the wave trained (1 h session, checklist) · SRs informed (Bangla SMS/voice note with the three things that change: new icon, same login, helpdesk number) · go/no-go meeting booked for 21:00 daily.
**Rollback** — drills done this quarter · rollback decision owners named · thresholds (lens-scale §5.4) printed.

### 5.6 Waves and go/no-go

Wave sizes (ASSUMPTION, Q38): pilot 10–20 routes → wave 1 one territory per wing (~10 territories, ~300 routes) → wave 2 one division per wing (~1,000–1,500 routes) → wave 3 half the fleet → wave 4 the rest. Never start a wave in the three days before or after a month end, Eid or a holiday (P-03 calendar), nor on a Thursday (Friday is the off-day; the first full day must have support staffed). Go/no-go (T-7-86) uses the previous wave's five trading days.

### 5.7 Support and escalation flow for 8,500 reps

| Tier | Who | Hours | Can fix (tool) | Cannot (escalate) |
| --- | --- | --- | --- | --- |
| 0 Self | SR | — | re-read the on-screen reason (bn); "Sync" button; printer re-pair; in-app help page; status banner + helpdesk number on the login screen (`GET /config/public`) | anything else |
| 1 AMO | zone AMO (in the field) | selling hours | explain rejected rows (reason text bn); check the SR's reconciliation screen; verify outlet requests; confirm the SR's route assignment for the day in the AMO app; coach on force-sale/photo; request PDA-to-Support | password, OTP, device, config |
| 1.5 TSO | territory TSO | selling hours | issue/re-issue device OTP; mark a device replaced; reset an SR password (temporary, forces change; `cfg.auth.temp_pw_ttl_h`); see zone sync-health; final submit; set a zone radius within bounds (lens-config T-3-60); reopen a day if `cfg.day.reopen_roles` allows | route reassignment across zones, quarantine fixes, releases |
| 2 Helpdesk L1 | ~8–12 agents (Bangla), phone + WhatsApp/Telegram (Q35) | 07:00–21:00 Dhaka every selling day; 24 h during wave day 1–3 | account unlock; OTP bulk; route assignment fix (with TSO confirmation); reading the device's sync status from the admin device page; "fix & accept" of a quarantined row **only** for data-entry-class reasons with a four-eyes rule; known-issue lookup; ticket creation with `device_id`, `route_id`, screenshots | code, config C3, data corrections touching money |
| 3 Engineering on-call L2 | 1 engineer on rota, 1 backup | 24 h during waves; business hours otherwise | config revert; quarantine fixes of other classes; re-generate a user's bundle; re-run aggregation for a date/route; hotfix request | — |
| 3 Release/hotfix L3 | tech lead + release manager | — | hotfix via `promote-prod.yml hotfix=true`; `blocked_versions` | — |

**Arithmetic** (ASSUMPTION; confirm with the first wave): contact rate 3–5 % of switched reps on day 1 (falling to < 1 % by day 5); a 300-route wave → 10–15 contacts; a 1,500-route wave → 45–75; the full-fleet remainder (~4,000 reps) → 120–200 on day 1. An L1 agent handles ~40 contacts/day with scripts → 1–2 agents for wave 1, 2–3 for wave 2, 5–8 for the big waves (staff 8–12 for safety). **SLA**: P1 "SR cannot sell or print" 15 min response / 2 h workaround (the offline design means this should be printer or phone issues, not server); P2 "cannot sync / wrong counts" 1 h / same day; P3 cosmetic next day. **Scripts (Bangla)**, top 20: cannot log in (password/OTP/min_version banner); bundle not downloading; "not within range" (force sale); printer not found; memo totals look wrong (which offer); rejected row reasons (each DQ code); sync pending for hours (Wi-Fi-only photos vs rows); phone date/time wrong banner; check-out before 17:00; Sales Submit with dues; shared phone second user; app update; storage full; permission denied (location/camera/Bluetooth); which radius applies; final submit refused (already done); old app still open; stale bundle banner; battery concern (what to check); where to find help. **Known-issue board** published to AMOs/TSOs daily during waves; **status banner** via `cfg.ops.maintenance_banner`.

### 5.8 Training and communications (brief)

AMOs/TSOs: 1 h hands-on per territory the week before their wave, using the lab's scripted day on their own phones; SRs: no classroom training (the no-retraining mandate) — a 90-second Bangla video of the three visible changes, pushed by the TSO; retailers: nothing (same memo). The baseline-pack recordings double as the "before/after" material.

### 5.9 Decommission (7e)

T-7-88: all waves stable ≥ 10 trading days; final Apsis delta imported and reconciled; Apsis read-only period ended with a last export archived to immutable Blob; credentials in the dump rotated or deleted (T-7-74); DECISIONS.md records the memo-number continuity outcome (Q5); the pilot double-entry data archived; `cfg.flag.pilot_in_rollups=true`.

---

## 6. Requirements traceability

### 6.1 The model

`R (sponsor requirement) → F (feature) → {G (gap), cfg (keys), D (decision), Q (question)} → T (gate) → evidence path`. Every node type lives in one YAML file under `/plan/` in the repo (`rtm.yaml` for F rows, `gates.yaml` for T rows, `gaps.yaml` for G rows; cfg keys come from the registry seed; D/Q from DECISIONS.md and docs/13). The master plan document is **generated** from these files (a script renders the tables), so the plan and the repo cannot drift.

### 6.2 Table formats the master plan carries

**Feature row (`rtm.yaml` → RTM table)**

| F-id | Name | R | Role | Phase (sub) | Spec ref | Off/Idem/Scope/i18n/Battery flags | cfg keys | Gaps | Decisions / Questions | Gates | Evidence | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-SR-012 (example: geo check at outlet open) | Geo check | R2, R4, R5 | SR | 1a | docs/05, docs/06 §4b | Y/Y/Y/Y/single-fix | `cfg.geo.radius_m`, `cfg.geo.fix_timeout_s`, `cfg.geo.max_accuracy_m`, `cfg.geo.no_location_policy` | G-cfg-xx (no-location policy), G-feat-xx | D-cfg-x; Q7 | T-1-40, T-1-61, T-1-63, T-2-47, T-2-67, T-2-75 | `/docs/evidence/phase-1/T-1-40/` … | planned |
| F-SYS-008 | Idempotent batch upload | R1, R3, R5 | all-app | 1b | docs/04, docs/09 | Y/Y/Y/–/batched | `cfg.sync.batch_max_rows`, `cfg.sync.retry_backoff_s` | G-scale-01, G-sync-xx | D-sync-09, D-scale-4 | T-1-20..23, T-1-51, T-2-52, T-4-53 | … | planned |
| F-SYS-026 | Sync-health dashboard | R1, R3, R6 | ops, ADM | 1c (tile), 4a (full) | docs/02 | n/a | `cfg.ops.dashboard_refresh_min_s`, `cfg.sla.*` | — | D-03, D-08, D-09 | T-1-43, T-4-44, T-4-45 | … | planned |
| F-ADM-0xx | Config console: radius | R6 | ADM, TSO | 1c (minimal), 2d, 6b | lens-config §4.3 | n/a | `cfg.geo.radius_m`, `radius_min_m`, `radius_max_m` | G-cfg-xx | D-cfg-x | T-0-61, T-1-61, T-2-60, T-2-67, T-6-41, T-6-70 | … | planned |
| F-SYS-043 | Parallel-run comparison | R2 (cutover) | ADM | 7b | docs/11 | n/a | `cfg.calendar.*` | G-qa-03, G-feat-67 | D-qa-11; Q32 | T-7-82 | … | planned |

**Gate row (`gates.yaml` → gate register)**: `T-id | phase/sub | lens | title | proves (F-ids, G-ids) | test (command / protocol path) | artefact path | verifier role | blocking | status | last run | run URL`.

**Gap row (`gaps.yaml` → gap register)**: `G-id | title | severity | where | owner | target sub-milestone | closing gate(s) | decision/question that closes it | status`.

**Config key row** (from the registry seed; no separate file): `key | class C0–C3 | default | bounds | scope levels | consumed by F-ids | tests: bounds (T-0-61 generic), propagation (T-1-61/T-2-61 generic), specific T-ids`.

### 6.3 Coverage rules (`rtm-check` fails CI when violated)

1. Every F-id in lens-features appears in `rtm.yaml` with a phase and ≥ 1 gate; every F with `Off=Y` has ≥ 1 gate tagged `offline`; every F that writes a device-originated table has ≥ 1 gate tagged `idempotency`; every F with a read endpoint has a `scope` gate; every F with UI has an `i18n` gate.
2. Every T-id in `gates.yaml` has an artefact path and a verifier; every *blocking* gate is referenced by ≥ 1 F or G.
3. Every blocker G has an owner, a target sub-milestone ≤ the phase whose exit it blocks, and a closing gate.
4. Every `cfg.` key in the registry is consumed by ≥ 1 F (else the dead-key report) and every key consumed in code is registered (T-0-63).
5. Every R maps to ≥ 1 F (trivial) and every gate in §2.10 exists.
6. Every automated test tag `T-x-nn` corresponds to a gate; a gate marked `automated` with no tagged test in the last 7 days of runs is reported "unproven".

### 6.4 Mechanism

`pnpm rtm:check` (Node script in `/packages/testkit/rtm`) parses the three YAML files, the registry seed, the test tag inventory (Vitest reporter, Dart `--tags` listing, Playwright `--list`), and the latest `gate-report.json`; it writes `/docs/evidence/RTM.md` and `/docs/evidence/GATES.md` and exits non-zero on any rule in §6.3. The phase exit report (§2.0 item 4) embeds the generated gate table for that phase.

---

## 7. Config keys this lens introduces or constrains (all registered in the lens-config registry; `delivery=bundle` unless noted)

| Key | Class | Default | Bounds | Purpose |
| --- | --- | --- | --- | --- |
| `cfg.telemetry.device_events_enabled` | C1 | true | bool | device operational telemetry in the sync batch (§4.3) |
| `cfg.telemetry.device_max_bytes_per_day` | C1 | 1024 | 256–4096 | cap on telemetry payload per device-day (R4) |
| `cfg.telemetry.server_sample_2xx_pct` | C1 | 5 | 1–100 | server trace sampling; server-only |
| `cfg.telemetry.crash_reporting_enabled` | C1 | true | bool | Sentry on/off per scope (e.g. legal hold) |
| `cfg.flag.pilot_in_rollups` | C2 | false | bool | pilot data excluded from national rollups until cutover |
| `cfg.flag.new_app_login_enabled` | C3 | false | bool per scope | the wave lever (lens-config §1.6 owns it; constrained here by T-7-60/T-7-83) |
| `cfg.release.update_wifi_only` | C1 | true | bool | APK download transport |
| `cfg.sla.reconcile_mismatch_alert_pct` | C1 | 0.5 | 0.1–5 | §4.7 Sev2 threshold |
| `cfg.sla.crash_free_min_pct` | C1 | 99.0 | 95–99.9 | pause `wave_pct` below this |
| `cfg.sla.telemetry_silence_alert_time` | C1 | 10:00 | 08:00–12:00 | zone active yesterday but silent today |
| `cfg.support.contacts` | C1 | (set by business) | list | helpdesk numbers on the locked-out screen (lens-config owns; constrained by T-7-85) |
| `cfg.ops.maintenance_banner` | C2 | null | text bn/en | status banner (lens-config owns) |

---

## 8. Decisions proposed (D-qa-NN; numbers assigned on merge into DECISIONS.md)

| ID | Decision | Why |
| --- | --- | --- |
| D-qa-01 | A gate is test + artefact + named verifier (never the author); phase exit = all blocking gates green + signed exit report; evidence under `/docs/evidence/phase-<n>/<T-id>/` | docs/12 DoDs are not checkable |
| D-qa-02 | Contract source of truth = Zod schemas in `/packages/contract` → OpenAPI 3.1 → generated TS and Dart clients (committed, drift-checked); JSON golden fixtures round-tripped in both languages; `oasdiff` breaking-change gate; server accepts `schema_version` N, N−1, N−2 | CLAUDE.md "one shared source of truth" made mechanical |
| D-qa-03 | API integration tests run on Testcontainers Postgres 16 with the real migrations and seed; no database mocks (`no-pg-mock` lint) | the risk is in SQL and constraints |
| D-qa-04 | Property/fuzz: `fast-check` on the server, hand-rolled Dart generators on the app, one shared JSON corpus; 10k runs per PR, 100k nightly, seeds persisted; Stryker mutation ≥ 70 % on the five critical api modules; no Dart mutation tool (properties substitute) | lens-sync §9.1 properties need a home and a cadence |
| D-qa-05 | Flutter integration via `patrol` (native dialogs, permissions) on emulator for PR smoke and on physical reference devices for RCs; connectivity driven from the host over Wi-Fi ADB; `mitmproxy` for throttling and zero-chatter | airplane-mode scenarios cannot run in plain `integration_test` |
| D-qa-06 | Observability: Azure Monitor OpenTelemetry for api/worker/jobs; **Sentry** for Flutter and web errors (no Flutter SDK for App Insights), PII-scrubbed, residency confirmed; device operational telemetry as a `telemetry` record inside the sync batch; one `request_id` across all | R4 (no extra network) and PII rules |
| D-qa-07 | Migrations: plain forward-only SQL with checksums; expand/contract across three releases; `lock_timeout=5s`; `CONCURRENTLY`; batched backfills; nightly N−1 compat job; dry run on a PITR clone before prod; Drift schema additive with N−2→N upgrade matrix per RC; downgrades unsupported and guarded | zero-downtime by construction |
| D-qa-08 | Feature flags are `cfg.flag.*` keys in the config registry (no third-party service); release flags removed within two releases of 100 %; flags never change captured data shape | one audit trail; R6 |
| D-qa-09 | App distribution via AKTCL's own APK channel (Blob + Front Door) with `app_release` and `wave_pct`; Play tracks optional; package id differs from Apsis; `min_version` blocks new-day login only | shared-ownership phones; docs/11 |
| D-qa-10 | Injectable `Clock` in api/app/web; the suites run at 10:00, 16:59:30 and 23:58 Dhaka; `no-wall-clock` lint | business date, 17:00 gate, midnight rollover |
| D-qa-11 | Pilot pass rule: 10 consecutive trading days with zero sync-loss discrepancies and exact memo/STD/dues/value after explained categories; one sync-loss resets the count | docs/11 "1–2 weeks" made objective |
| D-qa-12 | Every automated test carries its gate id; `gate-report.json` per run; `rtm-check` in CI enforces §6.3 | nothing missed, provably |
| D-qa-13 | Physical device lab in Dhaka (2 × 3 reference classes, 2 printers, Wi-Fi ADB, self-hosted runner); Firebase Test Lab for breadth only | budgets are measured on the phones the fleet uses |
| D-qa-14 | Promotion/discount engine and memo rounding move to sub-milestone 2a (pilot prerequisite); Phase 5 keeps the programmes | printed-total parity in the pilot |
| D-qa-15 | Baseline pack from the current system is a Phase 0 gate and Phase 1 entry criterion | parity needs an oracle |
| D-qa-16 | Pilot runs on prod with `pilot=true` accounts excluded from national rollups by flag; Apsis remains system of record until the wave switch | real retailers, real dues |

---

## 9. Questions for docs/13 (continuing after Q30)

| Q | Question | Blocks |
| --- | --- | --- |
| Q31 | Fleet device inventory: models, RAM, Android versions, share of each (from the MDM or a TSO survey). Needed to fix the three reference devices (lens-sync §5.1 are assumptions). | T-0-48, battery gates' validity (G-qa-05) |
| Q32 | Can Apsis (or AKTCL's own access to the Apsis web) export per-route daily data (memo count, STD per SKU, value, dues) for the pilot routes in machine-readable form during the parallel run? If not, is manual keying acceptable for the pilot? | T-7-82 (G-qa-03) |
| Q33 | Pilot routes: how many, which territories (urban/rural/hill mix), which SRs consent to double entry, what incentive; may a lab-managed phone be carried by two pilot SRs for `netstats` measurement? | 7b entry |
| Q34 | Who is the sponsor's delegate (business verifier) per phase, and who are the four readiness owners (eng, ops, business, support)? | every human-verified gate |
| Q35 | Helpdesk: does a function exist today (for the Apsis app)? Hours, channels (phone/WhatsApp), languages, ticket tool? Who is the native-Bangla string reviewer? | T-2-49, T-7-85 (G-qa-04) |
| Q36 | Is a SaaS error-reporting service (Sentry) acceptable for device crash data (device ids, stack traces, no PII) under AKTCL policy and Bangladesh law (links G-sec-01)? | D-qa-06 |
| Q37 | Who signs "same memo" on behalf of retailers — a retailer panel, the trade marketing team, or the TSOs? | T-2-41 |
| Q38 | Wave order and sizes (by wing? by territory?), blackout dates (month end, Eid, campaigns), and whether a wave may start mid-month given MTD targets | §5.6, T-7-86 |
| Q39 | Acceptable quarantine threshold for the import (1 % per table proposed) and who adjudicates quarantined history (sales ops vs finance for dues)? | T-7-80 |
| Q40 | Target RTO/RPO for PITR and region DR (2 h / 4 h and 15 min proposed, following lens-scale) | T-7-83 |

---

## 10. Gap list (G-qa-NN)

| ID | Title | Severity | Where | Why it matters / what to do |
| --- | --- | --- | --- | --- |
| G-qa-01 | **No parity oracle**: no printed memos, screen recordings, string list or Apsis report exports are captured anywhere; parity gates (T-1-41, T-2-41, T-2-43, T-4-41) have nothing to compare against | blocker | docs/01 "same workflow/same memo"; lens-sync G-sync-02 (memo only) | Capture the baseline pack now (T-0-49) from pilot phones and AKTCL's web login; it is also the training "before/after" material |
| G-qa-02 | **Promotion engine is in Phase 5 but pilot parity needs it**: ~22 groups + DRP offers change every memo total; the pilot (7b) cannot distinguish rule differences from sync loss | blocker | docs/12 Phase 5 vs Phase 7; G-feat-13, G-feat-46 | Move promotions/discounts + rounding to 2a (D-qa-14); obtain the promotion catalogue (Q13) before 2a |
| G-qa-03 | **No machine-readable Apsis daily data for the parallel run**; docs/11 says "compare daily" but names no source or format | blocker (7b) | docs/11 pilot; F-SYS-043 | Ask Apsis/AKTCL for a per-route daily export (Q32); fallback manual keying for the pilot only |
| G-qa-04 | **No helpdesk function, hours, scripts or TSO toolkit** defined for 8,500 reps on wave day | blocker (7c) | docs/11; lens-scale §5.4 names a war room only | §5.7 tiers, arithmetic, scripts; gate T-7-85; Q35 |
| G-qa-05 | **Reference devices are assumptions**; battery/data gates measured on the wrong class of phone prove nothing for the fleet | blocker (for the validity of R4 gates) | lens-sync §5.1; docs/04 | Fleet inventory (Q31) before Phase 1 exit; adjust the lab (T-0-48) |
| G-qa-06 | No business verifier or readiness owners named; docs/12 DoDs have no sign-off | major | docs/12 | Q34; §2.0 rules |
| G-qa-07 | docs/12 Phase 2 and Phase 4 are too large to test as one unit; no sub-milestones or demos | major | docs/12 | §2.4 (2a–2e), §2.6 (4a–4d) |
| G-qa-08 | No test-data strategy; the Apsis sample contains PII and cannot be used in CI; no synthetic generator | major | docs/22; CLAUDE.md DoD "has tests" | `/packages/testkit` (T-0-45); full-size synthetic fleet on staging |
| G-qa-09 | Load-test and prod-SKU staging windows have no budget approval; Azure Load Testing engine quota unknown | major | lens-scale §6, §4.13 | Approve budget per gate window; request quota with T-7-55 |
| G-qa-10 | No CI mechanism for expand/contract compatibility (API N−1 vs schema N; app N−2 vs API N); the rule exists only in prose | major | docs/02 CI/CD; CLAUDE.md migrations | nightly `schema-compat`, `oasdiff`, upgrade matrix (T-1-46, T-2-44) |
| G-qa-11 | Wave rollback leaves new-app data that Apsis never sees; no export format or process (links G-feat-67) | major | docs/11 step 5 | Define an "export wave data for Apsis" job (CSV in the dump's shape) and include it in the rollback drill (T-7-83) |
| G-qa-12 | No error/crash reporting tool chosen; App Insights has no Flutter SDK; residency unknown | major | docs/02 "e.g. Sentry" | D-qa-06; Q36 |
| G-qa-13 | No device telemetry design; without it battery, pending rows and sync failures in the field are invisible, and a naive design would violate R4 | major | docs/02 observability; R4 | `telemetry` record in the sync batch (§4.3), `dw.fact_device_day` |
| G-qa-14 | No reconciliation report for ledgers (dues, loyalty) between transactions and balances; mismatches would surface only as retailer disputes | major | docs/11 "exactly right on day one" | §4.6 ledger report nightly; T-4-46 |
| G-qa-15 | No Bangla localisation review process or reviewer; "Bangla-first" has no acceptance step | major | CLAUDE.md #8 | T-2-43, T-2-49; Q35 |
| G-qa-16 | No pilot route selection criteria, consent or incentive for double entry; pilot SRs do twice the work for 2–3 weeks | major | docs/11 pilot | Q33; §5.1 |
| G-qa-17 | Alerting has thresholds (lens-scale) but no routing, on-call rota, runbook linkage or hygiene rule | major | lens-scale §5.3 | §4.7; T-4-45 |
| G-qa-18 | Restore drills are listed (DR) but without a data-verification step (row counts, audit chain) | minor | lens-scale T-7-54 | T-6-43; §5.4 |
| G-qa-19 | No accessibility / low-literacy UX check for the field apps (icon-only tiles, font scaling, colour-only KPI bands) | minor | docs/06 | §1.5 semantics test; usability sessions T-3-44 |
| G-qa-20 | Tests have no injectable clock requirement; business-date, 17:00 gate and midnight rollover tests would depend on wall time | minor | docs/04 testing | D-qa-10; T-0-47 |
| G-qa-21 | Bluetooth printing cannot be tested in CI; only physical tests exist | minor | docs/06 printing | `PrinterPort` fake + raster golden in CI (§1.5); physical gates stay in the lab |
| G-qa-22 | Wave scheduling has no blackout rules (month end, Eid, Friday off-day) although docs/22 P-03 shows the calendar matters | minor | docs/11 waves | §5.6; Q38 |
| G-qa-23 | docs/12 Phase 7 "pilot route runs a clean parallel day" is one day; a single clean day proves little against intermittent sync loss | minor | docs/12 DoD | D-qa-11: 10 consecutive trading days |
| G-qa-24 | The retailer is never asked whether the memo is "the same"; parity is judged internally | minor | docs/01 "same memo" | Q37; add retailer feedback to the pilot sheet (§4.8 #15) |

---

## 11. Phase mapping (what this lens lands when; summary of §2)

| Phase | Quality deliverables | Gates (this lens) |
| --- | --- | --- |
| 0 | pipelines (`pr`, `main`, `promote-prod`, `infra`), Testcontainers harness, contract generators + fixtures, `testkit` v1, RTM files + `rtm-check`, injectable clock, logging/App Insights/Sentry baseline, device lab, **baseline pack** | T-0-40..49 |
| 1 | airplane-mode cross-stack slice, golden screens vs baseline, coverage/mutation floors, sync-health tile, zero-chatter, battery protocol run #1, first expand/contract rehearsal, non-engineer demo | T-1-40..47 |
| 2 | scripted field day on 3 devices, printed-memo parity, rounding/promo corpus, localisation lint + review, upgrade matrix, dogfood week, hardening drills, anti-spoof lab, dress rehearsal | T-2-40..49 |
| 3 | AMO/TSO offline flows, approval chain e2e, role-aware goldens, final-submit cross-stack, usability | T-3-40..44 |
| 4 | full e2e × personas, report parity, Excel goldens, `dw`-only reads proof, sync-health complete, alert drills, reconciliation reports live, web perf budget | T-4-40..47 |
| 5 | programme flows offline, promo corpus completeness, revision chain, −37,500 % regression, leave/tasks | T-5-40..43 |
| 6 | generated admin CRUD e2e, sponsor radius change, run-without-engineering drill, restore verification | T-6-40..43 |
| 7 | shadow import reconciliation, field spot-check, parallel run, rollback drill suite, readiness checklist, support readiness, go/no-go, post-wave reconciliation, decommission, retro | T-7-80..89 |
