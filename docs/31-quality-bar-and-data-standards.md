# 31 — Quality bar and data standards (binding, 2026-10-07)

This is a real system for 8,500 people, not an MVP. The bar below is measurable. Numbers marked with a document come from the long plan and stay binding; numbers marked **lead-set** are new and are confirmed or corrected by measurement on the real phones and the dev environment.

## 1. "Never break, never lag": the gates

| Area | Gate | Source | Enforced by | Proven |
|---|---|---|---|---|
| Selling never waits for the network | a full SR day offline: route, visit, geofence, sale, print, dues; sync later with device count equal to server count | CLAUDE.md, docs/04 | airplane-mode device check; sync property tests | Day 2 check, kill-and-relaunch tests |
| Nothing doubled or lost | duplicate, reordered and partial batches converge to the same server state | docs/04, docs/17 s12 | `N-024` fuzz suite in CI | every push |
| App cold start | at most 2.5 s on the primary phone | docs/20 s2.7 (D-73) | macro measurement in the device check | Day 6, every release candidate |
| Battery | app non-screen drain at most 6 percent of a 5,000 mAh battery over the scripted 8-hour day | docs/20 s2.7 (D-73) | `N-060` protocol on the A06 | Day 6 |
| Data pack | a normal day's sync well under tens of MB including photos; photos at most 200 KB | docs/04 | upload size assertions in tests; Wi-Fi-only photo option | device check |
| APK size | at most 30 MB per ABI (target 22), installed at most 70 MB | docs/20 T-1-33 | CI size gate (warn plus 5 percent, fail plus 15 percent) | every push (infra lane adds the gate) |
| Screen smoothness | lists of 200 outlets and 60 SKUs scroll without dropped-frame bursts; a sale is saved to Room in one transaction under 300 ms on the A06 | **lead-set** | Compose macrobenchmark or the frame-time readout in the device check | Day 6 |
| API speed | SLOs of docs/18 s6.1; interactive reports p95 at most 5 s; dashboards p95 at most 1.5 s | docs/18 | smoke and the dev load script now; full load in the final account | Day 6 (small), final account (full) |
| Safe overload | 429 and 503 with `Retry-After`; phones back off with jitter and keep selling | docs/18 s4 | `N-056` and the sync client tests | Day 6 |
| Security | scope enforced on the server for every endpoint; IDOR sweep with foreign ids; no secret in git or logs | docs/21, CLAUDE.md | `N-061` scope-leak harness; gitleaks; CodeQL | every push, then Day 6 |
| Look and feel | every screen from the shared kit, glass tier B on field phones, all states shown, contrast at least AA | docs/32 | kit review, screenshot tests, owner device check | each kit slice and Day 6 |
| Accessibility and language | Bangla first, every string a resource with a Bangla twin, Bengali digits per locale | CLAUDE.md | `HardcodedStringScanTest` | every push |
| Money correctness | server recomputes every memo equation; printed total equals stored total | docs/24 s7 | `shared` oracle tests and `F-SYS-062` | every push |

A feature is not done unless its row gate is green. A lane that cannot meet a gate says so in its status file the same hour, with the number it measured.

## 2. Engineering standards enforced in CI (infra lane builds the missing ones)

Required on every push to INT and every promotion PR: contract lint plus `oasdiff`; JVM build and tests (shared, db, backend) on PostgreSQL 16; Android debug APKs, unit tests and lint; the hard-coded-string scan; web lint, types, unit tests, build and the smoke e2e; container images built and booted; Bicep validation. **Added this week:** CodeQL (Kotlin, TypeScript), gitleaks, Dependabot, APK size gate, `assembleRelease` for SR (android-core asked), migration lint (`squawk`) and checksum check, SBOM on images.

## 3. Data standards: the database as a product

The data platform design is `docs/16` (principles P1 to P14, naming, money, business date, schemas `app`, `dw`, `cfg`, roles). The build keeps these rules, and the DB lane proves them:

1. **One source of truth per fact**; device-originated rows keyed by client UUID; server surrogate keys separate; every table has `created_at`, `updated_at`, `source`, and a stable `external_ref` so another product can reference a record without knowing our keys (`N-049`).
2. **Business date and trusted time on every transaction** (UTC plus Asia/Dhaka date); one `dw.dim_date` calendar.
3. **Append-only for money and audit**: ledgers, memo versions, the hash-chained audit log; corrections are new rows.
4. **Facts and aggregates are separate from capture tables**; dashboards read `dw` aggregates, never the transaction log; aggregates are rebuildable from facts (dirty-key worker, `docs/16` s8).
5. **Read-only interfaces for other products**: schema `dw` plus the roles `web_ro` and `bi_reader` (replica); views are the contract for BI, with a documented column dictionary. Other verticals (indent, discount and target engines, the BOD dashboard, a lakehouse export) read `dw` views and the domain-event outbox; they never read `app` tables.
6. **Domain events** go through the transactional outbox with versioned payloads, so a later consumer can subscribe without touching the write path.
7. **Reference data is versioned** (prices, offers, config, products) with effective dates; history is never overwritten.
8. **Data dictionary generated from the schema** (`tools/data-dictionary`, DB lane): every table and column has a comment and an owner; CI fails on an undocumented new column.
9. **Retention and PII**: retention jobs, PII read budgets, export log, masking per role; no real personal data in dev.
10. **Migrations**: forward-only, expand/contract, checksum-checked, partitions created by a job.

Review status: the DB lane has built V0001 to V0012 against `docs/16`. An independent audit (workflow of 2026-10-07) checks the built schema against points 1 to 10 and the needs of later verticals; verified gaps become DB-lane rows.

## 4. Operations standards

Observability as code (Application Insights, alert rules, workbooks; budget alert needs the owner's billing policy), health and readiness endpoints, structured logs with request ids, a runbook per failure mode in `docs/18` s7 (the lane keeps `docs/runbooks/` current), backup and point-in-time restore drilled, a status page for the admin portal's sync-health view. Every alert names an owner and an action.
