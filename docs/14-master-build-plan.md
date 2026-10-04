# 14 — Master Build Plan

| What this doc decides |
| --- |
| 1. Phase structure: eight phases and 28 sub-milestones (0a to 7e); each closes on blocking gates, an evidence artefact, a verifier who is not the author and a demo of at most 30 minutes (s2, s3; D-141, D-307). |
| 2. Sponsor requirements: R1 to R6 become acceptance criteria that a non-engineer can check, each with a mechanism, an owning document, a first-working sub-milestone and the gates that prove it (s1; D-61, D-59, D-73, D-87, D-125). |
| 3. Confirmation schedule: 173 MUST-CONFIRM decisions (101 in s4.2, 62 raised by docs 15 to 21 and by the round-2 review in s4.2b, 10 raised by the round-3 review in s4.2c) with owner role, due sub-milestone and the default the build proceeds on (s4; D-308), plus 34 deliberate changes from Apsis for AKTCL sign-off (s5). |
| 4. Top risks: 33 ranked risks RK-01 to RK-33 with owner, mitigation and early-warning gate; the largest are an unknown device fleet, an unseen printed memo, an unknown promotion catalogue, an incomplete Apsis dump and the first wave morning (s6; D-126, D-142, D-143). |
| 5. Changes versus docs/12: promotion engine, rounding, DRP and QC deductions move to 2a; supervisors split into 3a and 3b; web (P4) before programmes (P5); a minimal admin console in 1c; a pilot that must pass 10 consecutive trading days (s2.2; D-33, D-90, D-143, D-145). |

Date: 2026-10-04. Status: binding plan; amended only through DECISIONS.md (D-309). Owner: the doc 14 author. Companion file: `/DECISIONS.md` (543 decisions). The round-2 skeptic review (D-500 to D-553, DECISIONS.md section M3) changed the schedule, the gates and the confirmation schedule below; every change is traced in s7.8. The round-3 review (D-554 to D-601, section M4) added the Board envelope (s2.5b), the 0b day-by-day table (s3.0), ten MUST-CONFIRM rows (s4.2c), the 68-row question table (s4.4), five risks (RK-29 to RK-33), the ready-to-paste precedence text (s9.2b) and the constants registry (s9.4b); every change is traced in s7.9. Documents 15 to 21 own the detail named in s1.1; this document cites them by number and section and does not restate docs/01 to 13 or docs/22.

## 1 Sponsor requirements to mechanism

### 1.1 Purpose and reading order

This plan turns docs/01 to 13, the four user manuals (as reconciled in the manual register: 1,087 inventory items, 31.6 percent fully covered by the spec before this plan), the Apsis data profile (docs/22) and the UI references into a build that can be tested one step at a time. It answers four questions in order: what must be true at launch (s1), in which order it is built and proven (s2, s3), what the business must decide and by when (s4, s5), and what can go wrong (s6, s7, s8).

| Reader | Read first | Then |
| --- | --- | --- |
| CTO or tech lead | s1.3, s2.3, s6 | doc 18 (scale), doc 17 (sync), doc 21 (security) |
| Project manager | s2, s3, s4, s7 | doc 20 s10 (exit scripts) |
| Sponsor and the sponsor's delegate | s1.2, s4, s5, the "Exit check" row of each phase in s3 | doc 20 s10 |
| QA lead | s3 gate rows, s9.3 | doc 20 (gate register, parity oracle) |
| A Claude Code build session | s9, `/DECISIONS.md`, the phase it is working in | the owning doc of the feature (s1.1 table) |

| Document | Owns |
| --- | --- |
| 14 (this) | phases, schedule, confirmation schedule, risks, gap roll-up, DECISIONS.md |
| 15 | every F-id, screen and label parity, rules now stated, message catalogue, glossary, RTM model |
| 16 | schema v2, dw layer, KPI SQL, data-quality rules, importer data design |
| 17 | local store, sync protocol and wire contract, budgets, printing and memo numbers, app lifecycle |
| 18 | load model, Azure topology and SKUs, failure modes, SLOs, launch-day runbook |
| 19 | config model and key registry, admin console, propagation, safety rails |
| 20 | gate register (T-ids), CI/CD, parity oracle, pilot and cutover verification |
| 21 | auth, authorisation, PII, fraud-signal catalogue, audit, supply chain |
| 22 (existing) | what the Apsis data sample says about volume, calendar, outlets and PII |

Proved by: T-0-46 (every F-id has a gate), T-0-49 (baseline pack).

### 1.2 R1 to R6 and the process requirement as acceptance criteria

The sponsor's six launch requirements are the acceptance criteria of the whole programme. Each row says what a non-engineer can check, how the system is built to satisfy it, where the detail lives, when it first works and which blocking gates prove it. Gate ids use the ranges of the plan (doc 20 s3 places each one); named anchors are the ones a business verifier runs by hand.

| Req | Acceptance criterion (checkable) | Mechanism | Owning docs | First works | Blocking gates |
| --- | --- | --- | --- | --- | --- |
| R1 DATA | (a) Every captured field in docs/03 and every field added by the manual register sits in a typed column with provenance (device, app version, captured time, entry source), none only in a JSON blob except the sanctioned list J-1 to J-8 of doc 16 s8.10, each typed or with a lifecycle (D-501). Every OFFLINE or QUEUED capture table has a dw object, or a sponsor-signed exclusion row EX-01 to EX-06; an analyst answers one question per capture type (Q26 to Q41) under a role with no SELECT on app (D-500). (b) The web database role has no SELECT on transactional tables and every dashboard and report is a query on dw tables. (c) A new report is a new view over dw: no change to ingest, no replay of the log. (d) A batch that arrives days late re-aggregates its own business date and equals a from-scratch recompute. (e) Per-zone control totals match after import. | Schema v2 in four schemas (app, cfg, dw, stg, D-24); dirty-key recompute worker, aggregates recomputed never incremented (D-61); dimensions, event facts, daily and month aggregates, snapshots and a restatement log (doc 16 s8); KPI SQL (doc 16 s9); ReportQuery contract; capture map and jsonb lint (`/plan/capture-map.yaml`, rtm-check rule 9, doc 16 s8.9 and s8.10, D-500, D-501) | 16, 15, 18, 20 | 1c (one tile); 4a and 4b (every page and report) | T-0-01..04, T-1-01..04, T-2-01..04, T-4-01..03, T-4-43, T-4-46, T-7-80, T-0-150, T-0-151, T-1-150, T-4-150, T-4-151 |
| R2 FEATURES | (a) Every F-id of doc 15 has at least one gate (rtm-check fails the build otherwise). (b) The 37 current web pages map to new pages and the 41-page union of the TSO manual is covered. (c) Each of the 104 manual-register entries is closed by its owner doc. (d) Printed memo, screens and strings match the baseline pack, signed by the delegate. (e) Every Apsis function that is live but not built, hidden or reserved is a row of the Parity Exceptions Register (doc 15 s6.4, PX-01 to PX-10) with an owner, the sponsor's signature and a date; the usage census (baseline-pack item i) shows no Apsis function without a replacement or a signed retirement before 7b (D-502, D-503). (f) Every manual screen, message, rule and entity row is mapped in `/plan/manual-coverage.yaml` (D-531). | Feature inventory and RTM in the repo (doc 15 s12, doc 20 s8); baseline pack captured from the current system (T-0-49); golden fixtures with arithmetic cross-checks (doc 20 s4); manual-parity gate range T-x-120..139 | 15, 20, 17 | 0a (baseline), 1a (first memo), 2e (pilot-ready) | T-0-46, T-0-49, T-1-41, T-2-41, T-2-43, T-2-49, T-3-42, T-4-40, T-4-41, T-5-41, T-6-40, T-x-120..139, T-0-152, T-0-153, T-7-158 |
| R3 SCALE | At the design point (4.5 lakh calls and 5 lakh visits a day, 8,500 users, 1.5 times the fleet in the full-fleet test): morning refresh storm about 130 requests per second; evening all-offline catch-up proven at 8,000 rows per second (3 times the 2,600 rows per second worst case); 17:00 check-out and submit wave; dashboards p95 at most 1 second during all three. The proof is blocking at 4d: S2, S4 (the 8,000 rows per second ramp and the 1.5 times fleet of 12,750 SRs), S5 with S4, S6, the 12-hour soak S8, S9 and S11, with an S4 smoke at about 2,000 rows per second at 2e (D-504), repeated at 7d; the 17:00:00 spike spreads over 0 to 90 seconds by client jitter (S3b, D-505); storage is sized bottom-up at about 2.8 GB a day and 1.0 TB in year 1 (D-520) on 1,024 GiB disks from 7a with the first grow by month 6 (D-568); the DR replica is the same SKU as the primary (D-522); the first morning of a wave is a small DELTA bundle (about 25 KB a phone) on a pre-bound phone, not a password storm and not "refresh plus 304" (D-557); the wave night is J1 reconciled by 23:00, J2 by 23:30 and the go/no-go at 23:30 (D-557). | Synchronous ingest with a transactional outbox (D-61); pre-generated bundle snapshots (D-71, D-129); Container Apps, PostgreSQL Flexible HA, Managed Redis, Front Door (D-04 to D-07); read replica (D-128); jitter, per-device rate limits and the pre-bind day (D-116, D-126); SLOs (D-135); load tests S1 to S11 (D-134, D-504); bisection ingest with at most 60 savepoints per transaction (D-521) | 18, 17, 16, 20 | 1c (load model rebased, k6 smoke); proven 4d and 7c | T-1-51, T-1-52, T-2-51..54, T-4-51..57, T-7-51..56, SRE gates T-1-53..56, T-2-55..58, T-4-58, T-4-59, T-7-57..59, T-7-83, T-7-85, T-2-155, T-4-155..164, T-4-165 |
| R4 BATTERY | On the primary reference device over the scripted 8-hour day: non-screen drain at most 6 percent of a 5,000 mAh battery; background drain at most 1 percent per 8 hours with pending rows and no network; at most 80 GPS fixes and 15 minutes of GPS; at most 1 MB a day without photos and 3 MB with every photo on mobile (13 photos for the 50-outlet route; photos(n) = round(0.267 n) is the one photo source, D-508); the median route of 64 outlets and the p99 route of 112 are blocking gates scaled by 0.2 + 0.8 n / 50 (6 percent becomes 7.3 and 12.0 percent, 80 fixes becomes 98 and 159, 3 MB becomes 3.7 and 6.0 MB); every battery gate is expressed in mAh AND in percent PER BATTERY-SIZE CLASS from the fleet census (the 300 mAh of the 6 percent budget is 10 percent of a 3,000 mAh phone, such as the Samsung J-series frames of the TSO manual), the smallest-battery census model at 85 percent health joins the 1c battery run and T-2-40, and SH-21 thresholds are per model class (D-564, G-qa-97); the AMO and TSO apps have their own scripted days and gates (AMO 2 MB and TSO 1.5 MB a day, D-506); APK at most 30 MB per ABI for all three flavours; the same numbers are measured on the fleet by the daily telemetry object (p95 of mobile bytes, CPU, engine starts and battery against the role budgets, SH-19 to SH-21) and a canary cohort that regresses by more than 20 percent freezes its own rollout (D-507); zero foreground services and zero alarms; measured on every release candidate. | Budgets as release gates (D-73); one balanced-power fix per event (D-74); compressed photos with Wi-Fi-first upload (D-75); no polling (D-59); device lab and per-release perf file docs/perf/battery-<version>.md; field telemetry and auto-freeze (doc 17 s8.10, D-507); remote brakes cfg.ops.prefetch_enabled, cfg.telemetry.enabled, cfg.geo.radio_env_enabled | 17, 20 | 1a (design), 1c (first measurement) | T-1-35, T-1-44, T-1-45, T-2-40 (three devices), T-2-45, T-2-153, T-3-151, T-3-152, T-3-153, T-7-156 |
| R5 OFFLINE + IMMEDIATE SYNC | (a) A full day in airplane mode works end to end including print. (b) Kill-and-relaunch mid-sale and mid-sync loses nothing and doubles nothing. (c) With the app in the foreground and online, captured rows are acknowledged within 60 seconds without the SR pressing Sync (T-1-40); after connectivity returns with the app in the background they are acknowledged within 3 minutes (T-2-40); across the fleet 95 percent of rows captured with validated connectivity are acknowledged within 60 seconds and 99 percent within 5 minutes, and 90 percent of devices are acknowledged within 3 minutes of reconnecting (SH-22, SH-23, D-509). (d) Device count equals server count on the reconciliation screen. (e) No repeating timer shorter than 60 seconds (the 5-second debounce and the 10-second connectivity check are one-shots), no foreground service, no socket. (f) Photos are the stated exception: Wi-Fi first, a mobile fallback after cfg.media.evidence_mobile_fallback_h (6 hours) and a 24-hour 95-percent SLO, traded against R4; the sponsor signs R5(f) by 2c (D-510). | Local outbox as system of record until ACK; triggers T1 to T8 (D-59); three idempotency layers (D-21) and batch replay (D-62); poison-row isolation (D-65); server-generation re-sync after DR (D-63); OEM and platform condition matrix with a reach-time bound per condition (doc 17 s4.1b, D-511); generation re-sync anchored at the server restore point with a count-and-bucket digest (D-517); held-rows list (SH-24) | 17, 16, 18 | 1b | T-1-20..35, T-1-40, T-2-20..34, T-2-40, T-3-40, T-5-40, T-7-82, T-1-152, T-2-154, T-7-157 |
| R6 ADMIN CONFIG | A business admin changes cfg.geo.radius_m (global, wing, division, territory, house, geo class, zone or outlet: eight levels) in the console with a reason; values outside 20 to 2000 m are refused; critical keys need a second approver; the reach bound is stated in wall-clock terms per cohort (doc 19 s4.1b, D-563): 95 percent of SELLING phones (a batch or bundle request in the last 20 minutes) within 15 minutes, idle phones at their next foreground through a conditional config check that adds no timer, an urgent revert with push OFF at the same 15 minutes and with push ON 95 percent of push-enabled devices within 5 minutes, a silent phone judged by D-431 and D-571 on its rows and refused at its first contact if its build is blocked; the console shows the share of ALL active devices of the scope reached and the unreached ones by reason, and every device has it by its next bundle; the FCM decision D-09 is recorded before the pilot and S9 runs with push on and off; the next visit stores radius_m_used; the audit viewer shows who, when, old and new; one click reverts. The same holds for every operating parameter in the registry (check-out time, min_version, photo size, bands, calendar, loyalty rate). Bounded relief exists for a bad day: an emergency widen lane under break-glass and a time-boxed temporary relief, both auto-expiring (D-527). | cfg schema with scoped, effective-dated, audited values (D-87); risk classes C0 to C3 and rails (D-88); propagation by X-Config-Version, delta pull and ack (D-89); minimal console in 1c, rails in 2d, full console in 6b (D-90); reach measured against all active devices with a tail metric (D-526); authority matrix and Support desk P19 (doc 19 s5.1b, D-540, D-541); emergency lanes (doc 19 s7.6b, D-527) | 19, 17, 21 | 1c (radius, check-out time, min_version) | T-0-60..64, T-1-60..64, T-2-60..69, T-6-41, T-6-42, T-6-70..72, T-7-60 |
| Process: plan first, build phase by phase, test step by step | Every sub-milestone has an entry condition, an exit that can be run by hand, and a demo of at most 30 minutes; a phase is closed by an exit report with two signatures. | Gate = test + artefact + verifier (D-141); sub-milestones 0a to 7e (s2.1); exit scripts (doc 20 s10) | 14, 20 | 0a | T-0-40..49, T-1-47, T-2-48, T-6-42 |

Proved by: the gates in the last column; doc 20 s3 is the register.

### 1.3 Target architecture on one page

```
  FIELD (shared low-end Android, Bangla-first)                     AZURE (Southeast Asia primary, East Asia DR: D-05)
  +--------------------------------------+                         +----------------------------------------------------------+
  | Flutter app, one codebase, flavours  |   HTTPS + gzip          | Front Door (WAF in Log mode on /sync and /media: D-81)     |
  | ARON SR | ARON AMO | ARON TSO (D-01) |                         |   |                                                          |
  |                                      | POST /v1/sync/batch     |   v                                                      |
  | Local SQLite (Drift, SQLCipher)      | ----------------------> | Container Apps (zone-redundant)                          |
  |  = system of record until ACK        | ordered flat records,   |   api: auth, scope, bundle, sync ingest (ONE tx), admin  |
  | outbox + media queue                 | batch_uuid (D-60, D-62) |   worker: outbox -> aggregate, geo re-check, signals     |
  | on-device geofence + mock check      | <---------------------- |   jobs: pre-generate bundles, 00:05 route_day, archive   |
  | Bluetooth ESC/POS print (RPP02N)     | accepted / rejected /   |        |                         |                       |
  | config cache (X-Config-Version)      | server_totals / hold_s  |        v                         v                       |
  +--------------------------------------+                         | PostgreSQL Flexible (HA, PgBouncer)   Azure Managed Redis |
        | GET /v1/sync/bundle (ETag, 304, ?since=)                 |  schemas: app | cfg | dw | stg          (bundle cache,        |
        | photos: direct PUT with 15-min SAS --------------------> |  read replica ---> web reads dw only   replay cache,       |
        v                                                          |                                        rate limits)        |
  Blob Storage (photos RA-GZRS, bundles, APK) <------------------- | Key Vault, managed identities, Azure Monitor, Sentry        |
                                                                   +----------------------------------------------------------+
  AKTCL web users (Next.js, BFF, MFA for admin): dashboards, reports, panels, admin console ---> Front Door ---> web ---> dw (replica)
  GitHub Actions (OIDC) builds, tests, migrates (expand/contract), deploys by traffic weights 10/50/100 (D-14)
```

| Component | Technology (decision) | Responsibility |
| --- | --- | --- |
| Mobile app | Flutter, Drift over SQLite with SQLCipher, WorkManager, geolocator, ESC/POS plugin (D-01, D-67, D-74, D-76) | Offline capture, on-device geofence, print, outbox sync, config cache |
| API | Node.js LTS, TypeScript, NestJS on Fastify, Kysely, PostgreSQL 16 (D-02) | Auth and scope, bundle, synchronous idempotent ingest, admin, reads |
| Worker and jobs | Same code base on Container Apps, KEDA on outbox depth (D-06, D-61, D-71) | Aggregation by dirty (business_date, route) key, geo re-check, plausibility, bundle pre-generation, nightly chain |
| Database | Azure Database for PostgreSQL Flexible Server, zone-redundant HA, PgBouncer, replica (D-06, D-128) | app (transactions, reference), cfg (config), dw (analytics), stg (migration) |
| Cache | Azure Managed Redis (D-07) | Bundle fast path, replay-cache copy, per-device rate limits, config version |
| Object store | Blob Storage, ZRS and RA-GZRS (D-132) | Photos, bundles, APK, audit export |
| Web | Next.js, TypeScript, Tailwind (D-03) | Dashboards, reports, panels, admin console; reads dw only |
| Delivery | GitHub Actions, Bicep, OIDC (D-04, D-14) | Build, test, migrate, deploy, load test, device lab |

Why this shape, tied to the requirements:

1. **Device first (R5, constraint 1).** The phone's database is the system of record until the server's ACK, so the network is never in the sale path; the same outbox serves "immediate when online" (event triggers, no polling) and "fully offline".
2. **One synchronous commit per batch (R3, constraint 2).** The device compares its counts to the server's accepted counts in the same response, so ingest must commit before it answers; everything after the commit is asynchronous and idempotent per dirty key.
3. **Pre-generated bundles and a pre-bind day (R3).** The morning cost is a small delta bundle (about 25 KB, D-557), not 8,500 live scoped queries and not a refresh; the first morning of a wave is moved to the day before.
4. **Aggregates for reads (R1).** Dashboards and reports never scan the log, so a new report needs a view, not a re-derivation of history.
5. **Config as data (R6).** Every operating parameter is a scoped, effective-dated, audited row that reaches the phone through the next natural request, so the radius is a console change and not a release.
6. **Scope and trust on the server (constraints 4 and 5).** Reach comes from the token; geo, prices, totals and points are recomputed on the server; flags go to supervisors.
7. **Budgets are gates (R4).** Size, battery, data and wake-ups are measured on a reference device for every release candidate.

Proved by: T-1-40, T-1-51, T-1-60..64, T-4-43.

### 1.4 Decisions at a glance

`/DECISIONS.md` holds 543 decisions (D-01 to D-269 from the plan skeleton, D-300 to D-312 raised here, D-320 to D-491 raised by docs 15 to 21, D-500 to D-553 raised by the round-2 review, D-554 to D-601 raised by the round-3 review): 57 LOCKED-BY-SPEC, 313 DEFAULT and 173 MUST-CONFIRM (s4). The load-bearing ones are below; a document that disagrees with a row is wrong and raises an amendment (D-309).

| Topic | D-id | Decision in one line | Status |
| --- | --- | --- | --- |
| Stack | D-01, D-02, D-03, D-04 | Flutter (three flavours), NestJS on Fastify with PostgreSQL 16 and Kysely, Next.js, Azure Container Apps with GitHub Actions and Bicep | DEFAULT / LOCKED-BY-SPEC |
| Money and units | D-15, D-16, D-17 | bigint milli-taka; quantities in sticks, pieces, dozens; sticks entered, pack badge derived | DEFAULT / MUST-CONFIRM (2a) |
| Memo | D-18, D-19, D-35 | net = gross - offer discount - DRP discount - QC settlement; one rounding of unrounded lines; new device-composed memo number | MUST-CONFIRM (2a, 1a) |
| Idempotency | D-21, D-62, D-63, D-65 | Three layers, stored batch replay, server-generation re-sync, poison-row isolation | LOCKED-BY-SPEC / DEFAULT |
| Time | D-20, D-28 | Dhaka business date on trusted time; working-day calendar as data | MUST-CONFIRM (1a, 4a) |
| Day state | D-27, D-30, D-44, D-45 | route_day owns the states; login event defined; two named Submit % figures | LOCKED-BY-SPEC / DEFAULT |
| Sync and battery | D-59, D-73, D-74, D-75 | Event-driven sync, budgets as gates, one fix per event, 150 KB photos | LOCKED-BY-SPEC |
| Geofence | D-93, D-95, D-96 | 100 m default within 20 to 2000, no-location policy force sale, mock never valid, warn by default | MUST-CONFIRM (7c, 2d) / LOCKED-BY-SPEC |
| Config | D-87, D-88, D-89, D-90 | Scoped effective-dated audited config, risk classes C0 to C3, delta pull and ack, minimal console in 1c | LOCKED-BY-SPEC / DEFAULT |
| Security | D-101, D-106, D-107, D-109 | ES256 tokens with rotated refresh, scope enforced twice, PII classification, server-side fraud signals | MUST-CONFIRM (0c, 7a entry) / LOCKED-BY-SPEC |
| Scale | D-125, D-126, D-127, D-135 | Load model from docs/22, pre-bind day, RTO 4 h and RPO 15 min, SLOs | LOCKED-BY-SPEC / MUST-CONFIRM (7b, 7c) |
| Test and cutover | D-141, D-142, D-143, D-145, D-147 | Gate triple, baseline pack, promotion engine in 2a, 10-day pilot pass, wave plan | LOCKED-BY-SPEC / MUST-CONFIRM |
| This plan | D-307 to D-311 | Planning basis, default-stands rule, change control, constraint coverage, wave calendar | DEFAULT |
| Round 2 | D-500 to D-553 | Capture-to-dw coverage and typed fields, Parity Exceptions Register and usage census, blocking 4d scale proof, field telemetry for R4 and R5, Apsis delta contract, schedule re-baseline, rollback return path | DEFAULT / MUST-CONFIRM |
| Round 3 | D-554 to D-601 | Plan wired into the repo, the Board envelope, cutover cut rule and late delta, wave-night timeline, gate placement, device-captured facts, BI and lake path, programme parity, APK channel, reach classes, role map and grants, route-day triggers, storage 1 TiB, geo backup at creation, residency at 7a, Front Door Premium, signing outage, working-day windows, replace-device and held binds, replay console, brake lane, price rails, per-wave rollback capacity | DEFAULT / MUST-CONFIRM |

Proved by: T-0-46 (decision ids resolve in rtm-check).

## 2 Phase structure

### 2.1 Phase and sub-milestone table

Codes are the only way documents refer to a sub-milestone (0a, 1b, 2d, 7c). Weeks are ASSUMPTION (D-307): team of 8 to 10 engineers, whole team on P0 to P2, split streams afterwards; they are re-baselined at 1c exit from measured velocity. "Week" is the cumulative week on the critical path with Week 1 starting Sunday 2026-10-11 (the working week is Sunday to Thursday; Friday is the weekly off-day, docs/22 P-03). The gate column lists the T-ids that doc 20 s3 places in the sub-milestone (the skeleton gate families are retired; OI-14-01, OI-20-14). "Confirm" lists the MUST-CONFIRM decisions that must be answered for the sub-milestone to exit (s4 gives the questions and defaults). Entry, demo and exit for each row are in s3.

| Code | Sub-milestone | One-line goal | Weeks | Ends wk | Gates (doc 20 s3 placement) | Confirm at exit |
| --- | --- | --- | --- | --- | --- | --- |
| **P0** | **Foundations** | Everything every later phase stands on exists and is tested; nothing sells yet | 3 | 3 | | |
| 0a | Tooling | Monorepo, CI/CD and IaC skeleton, testkit v1, rtm/gates/gaps files and rtm-check, device lab and reference phones, baseline-pack capture started | 1 | 1 | T-0-40, T-0-42..49, T-0-78, T-0-140, T-0-142, T-0-145, T-0-152, T-0-154, T-0-155, T-0-159..163 | D-156, D-530, D-534, D-554, D-555 |
| 0b | Schema and contract | Schema v2 migrations M-01..M-45, /packages/contract v1, cfg registry seeded, contract and migration tests | 1 | 2 | T-0-01..04, T-0-06..08, T-0-41, T-0-50, T-0-51, T-0-60..64, T-0-144, T-0-150 (report-only), T-0-151 (report-only), T-0-156, T-0-157 | D-501, D-558 |
| 0c | Auth and scope | Login, device bind and OTP model, refresh, ScopeContext and RLS, scope-leak harness, localisation layer with Bengali fonts, string catalogue started | 1 | 3 | T-0-70..77, T-0-79, T-0-120..122, T-0-141, T-0-153, T-0-158 | D-10, D-11, D-12, D-101, D-102, D-103, D-142, D-164, D-502, D-503, D-514, D-560, D-562, D-565 |
| **P1** | **Vertical slice** | One SR, one sale, offline to sync to one dashboard tile, with reconciliation | 4.5 | 7.5 | | |
| 1a | Offline capture | Bundle, route, outlet open, on-device geo check, sale, memo number, print, kill-and-relaunch survival, all on the local outbox | 1.5 | 4.5 | T-1-10, T-1-20, T-1-24, T-1-27, T-1-35, T-1-41, T-1-71, T-1-74, T-1-76, T-1-90, T-1-120..123, T-1-141 | D-20, D-35, D-76, D-158 |
| 1b | Sync and reconcile | Idempotent ingest, batch replay, poison-row isolation, triggers T1..T8, flat wire contract, device count equals server count, property and fuzz tests | 2 | 6.5 | T-1-04..06, T-1-13, T-1-21..23, T-1-25, T-1-26, T-1-28..32, T-1-34, T-1-38, T-1-39, T-1-51..56, T-1-70, T-1-72, T-1-73, T-1-75, T-1-79, T-1-151, T-1-152, T-0-05, T-0-09, T-1-153, T-1-154, T-1-156, T-1-157, T-0-150, T-0-151 (both blocking from this exit) | D-67 |
| 1c | Aggregate, tile, config | Dirty-key aggregation to one dw tile, minimal admin console (radius, check-out time, min_version), battery baseline, load-model rebase | 1 | 7.5 | T-1-01..03, T-1-33, T-1-36, T-1-37, T-1-40, T-1-42..47, T-1-60..65, T-1-77, T-1-78, T-1-100..102, T-1-105, T-1-106, T-1-150, T-0-100 (ratchet), T-1-155, T-1-158 | D-66, D-245, D-523 |
| **P2** | **Full SR day** | The complete SR selling day with promotions, QC, outlets, geo rails and day close; pilot-ready | 9 | 16.5 | | |
| 2a | Sale complete | Full memo model: promotion engine, DRP/slide, QC deduction, rounding, credit and dues, stock, price types, SKU Target and Achievement; golden memos; minimal media queue and SAS upload (F-SYS-010, F-API-007, F-API-057) for the POSM photo | 2.5 | 10 | T-2-05, T-2-07, T-2-20, T-2-36..39, T-2-41, T-2-42, T-2-71, T-2-90, T-2-91, T-2-103, T-2-120..124, T-2-131..133, T-2-136..138, T-2-161 | D-16, D-17, D-18, D-19, D-32, D-33, D-34, D-38, D-58, D-166, D-194, D-248 |
| 2b | Corrections | Edit, supersede, void, reprint, due allocation and receipts, stock return, cash deposit, attendance edge cases | 1 | 11 | T-2-01, T-2-02, T-2-06, T-2-09, T-2-21, T-2-70, T-2-92..94, T-2-101, T-2-102, T-2-125, T-2-143 | D-86, D-200, D-206 |
| 2c | Outlets and media | New, close, info change with cluster and route, photo and location capture, SAS media queue, Wi-Fi-first upload; outlet photos and the 6-hour mobile fallback extend the media path of 2a | 1.5 | 12.5 | T-2-08, T-2-13, T-2-22, T-2-26, T-2-30, T-2-53, T-2-72, T-2-126, T-2-135 | D-510 |
| 2d | Geo and rails | Geo gate with mock invariant, radio environment, force sale, location-change rules, risk signals, config rails, FCM urgent path | 1.5 | 14 | T-2-03, T-2-10..12, T-2-14, T-2-47, T-2-55, T-2-60..69, T-2-75..77, T-2-79, T-2-95, T-2-96, T-2-127, T-2-160, T-1-11, T-1-12, T-2-162, T-2-165, T-2-173, T-2-174 | D-09, D-91, D-94, D-95, D-110, D-519, D-527, D-563, D-571, D-588 |
| 2e | Day close and pilot hardening | Attendance, check-out gate, Sales Submit offline, deposit, updater, stale-bundle policy, budgets at full scope, pilot drill; the day-one tools of D-590 in minimal form; the replace-device wizard, held-binds queue, replay console and ticket store | 2.5 | 16.5 | T-2-04, T-2-23..25, T-2-27..29, T-2-31..34, T-2-40, T-2-43..46, T-2-48, T-2-49, T-2-51, T-2-52, T-2-54, T-2-56..58, T-2-73, T-2-74, T-2-78, T-2-97..100, T-2-128..130, T-2-140, T-2-141, T-2-150, T-2-151, T-2-153, T-2-154, T-2-155, T-2-156, T-2-157, T-2-158, T-2-159, T-2-134, T-2-163, T-2-164, T-2-166..170, T-2-172, T-2-176 | D-13, D-70, D-80, D-115, D-146, D-584, D-589 |
| **P3** | **Supervisors** | AMO and TSO apps and the final-submit loop that closes a zone-day | 5 | 21.5 | | |
| 3a | AMO | Zone-wide bundle, control and joint calls, Team Location, verification, dues, stock, Exceptions, cover, SS designation | 3 | 19.5 | T-3-11, T-3-12, T-3-20..22, T-3-24, T-3-36, T-3-41, T-3-70, T-3-73, T-3-77, T-3-90, T-3-91, T-3-100, T-3-107, T-3-120..124, T-3-151, T-3-153, T-3-156, T-3-160 | D-08, D-37, D-43, D-51, D-52, D-85, D-169, D-172, D-187, D-195 |
| 3b | TSO | Read-snapshot plus queued writes, dashboards, Final Submit with preview, OTP panel, leave, plans, tasks, feedback, Bikroy Joma and Login lists | 2 | 21.5 | T-3-01, T-3-02, T-3-05, T-3-10, T-3-23, T-3-25..28, T-3-40, T-3-42..44, T-3-60, T-3-61, T-3-71, T-3-72, T-3-78, T-3-79, T-3-92, T-3-125..128, T-3-150, T-3-152, T-3-154, T-3-155, T-3-157..159 | D-29, D-45, D-55, D-177, D-198, D-262 |
| **P4** | **Web** | Dashboards and reports from stored data only, sync health, panels, PII controls, scale proof | 6.5 | see 2.3 | | |
| 4a | Sync-health and dashboards | Sync-health, Daily Tracking, Login/Submit Status, per-surface dashboards from dw only | 2 | 23.5 | T-4-01, T-4-43, T-4-44, T-4-47, T-4-58..60, T-4-77, T-4-90, T-4-101, T-4-152, T-4-153, T-4-154 | D-28, D-46, D-185, D-249 |
| 4b | Report set | The 37-page parity map as ReportQuery on dw, Excel and PDF export with sanitiser and watermark | 2 | 29.5 | T-4-03, T-4-40..42, T-4-100, T-4-102, T-4-104, T-4-121, T-4-122, T-4-150, T-4-151, T-4-168 | D-47, D-54, D-191, D-538 |
| 4c | Panels and PII | Web Entry, Final Submit with audited void, outlet approval panel, SR Device OTP panel, role x menu matrix, PII budgets | 1 | 24.5 | T-4-02, T-4-14, T-4-61..65, T-4-70..76, T-4-108, T-4-120, T-4-123..125, T-4-165, T-4-166, T-4-167, T-4-169 | D-40, D-97, D-108, D-114, D-182, D-207 |
| 4d | Scale proof | Load tests S1 to S10 on a production-sized staging window, DR drill, restore drill | 1.5 | 31 | T-4-45, T-4-46, T-4-51..57, T-4-140, T-4-155, T-4-156, T-4-157, T-4-158, T-4-159, T-4-160, T-4-161, T-4-162, T-4-163, T-4-164, T-4-173, T-4-174 | none |
| **P5** | **Programmes** | Astha, Diamond League, Superstar, free samples, remaining promotion groups, targets | 5 | after wk 32 | | |
| 5a | Astha and Diamond League | Tiers, gift choice, ledger, expiry, redemption batches, Outlet Points, Astha Web Entry | 2 | 34 (or earlier, D-306) | T-5-40, T-5-61, T-5-62, T-5-70, T-5-100, T-5-120, T-5-121, T-5-123 | D-41, D-192, D-561 (entry) |
| 5b | Superstar, free samples, promotion groups | Enrolment and slabs, free-sample capture, remaining promotion groups | 1.5 | 35.5 | T-5-41, T-5-60 | none |
| 5c | Targets and revisions | Target sets with variant level and approval chain, grid and Excel upload, revisions (the task chain and the leave chain land whole in 3b, D-532) | 1.5 | 37 | T-5-01, T-5-10, T-5-42, T-5-43, T-5-63, T-5-71..73, T-5-90, T-5-122 | D-31, D-179 |
| **P6** | **Admin and master data** | Every master entity editable with audit; full config console and fleet control | 4 | in P4 to P7 | | |
| 6a | CRUD | 58 entities with audit, future-dating, back-date grants, bulk tools, wholesale marking | 1.5 | 38 | T-6-05, T-6-40, T-6-62, T-6-63, T-6-120, T-6-150 | D-42 |
| 6b | Config console and audit | Full console (all pages, risk classes, approvals, reach view, scheduled values) and audit viewer; a minimal set is a 7b entry condition | 1.5 | 25.5 (minimal), 32 (rest) | T-6-01, T-6-10, T-6-41, T-6-51, T-6-60, T-6-61, T-6-70..73 | D-113 |
| 6c | Devices, OTP, releases | Device and OTP console, release console (min_version, blocked versions, waves, flags), retention and archival jobs | 1 | 32 | T-6-42, T-6-43, T-6-152 | D-23, D-132 |
| **P7** | **Migration, pilot, cutover** | Move 8,500 reps with no lost selling day | 10+ | 42.5+ | | |
| 7a | Shadow import | Apsis dump imported through the staged importer with control totals, quarantine and rollback; history visible in dw; credential plan | 2 | 25.5 (needs the dump by wk 23) | T-7-01, T-7-02, T-7-06, T-7-58, T-7-74, T-7-77, T-7-80, T-7-100, T-7-109, T-7-145, T-7-160 | D-119, D-153, D-569, D-570 and D-05, D-107, D-120 (the legal answers are due at the 7a entry) |
| 7b | Pilot parallel run | 10 to 20 routes run both apps; daily comparison; pass rule 10 consecutive trading days with zero sync loss (D-145); 7b-2 programme pilot after 5a exits | 3 | 28.5 | T-7-20, T-7-81, T-7-82, T-7-90, T-7-91, T-7-140..144, T-7-150, T-7-153, T-7-154, T-7-158, T-7-159, T-7-162..165 | D-126, D-147, D-148, D-152, D-154, D-549, D-592 |
| 7c | Wave 1 | First production wave on a pre-bind day, war room, rollback triggers read after submit settle | 1.5 | 37.5 (starts wk 37) | T-7-10, T-7-51..55, T-7-59..62, T-7-70..73, T-7-75, T-7-76, T-7-83..87, T-7-151, T-7-152, T-7-155, T-7-156, T-7-157, T-7-161, T-7-166, T-7-168 | D-63, D-93, D-127, D-138, D-149 |
| 7d | Waves 2 to n | Territory, division, half, rest; each wave needs the previous stable for 3 trading days | 4+ | 41.5+ | T-7-56, T-7-57, plus T-7-84, T-7-86, T-7-87 repeated per wave (doc 20 s10) | none new |
| 7e | Decommission | Final Apsis delta imported, Apsis read-only or off, archive retained, parallel infrastructure removed | 1 | 42.5+ | T-7-88, T-7-89 | none new |

The sequential sum of the phase weeks is 47 (3 + 4.5 + 9 + 5 + 6.5 + 5 + 4 + 10); the round-2 re-baseline adds 2 weeks to the 45 of the first plan (1b 1.5 to 2, 2e 1.5 to 2.5, 4d 1 to 1.5; D-515); the elapsed plan is shorter only because P0 to P2 use the whole team, the pilot overlaps the tail of P4 and P6, and the programme and CRUD work (5a to 6a) overlaps the wave preparation. Parallel streams do not add capacity: s2.3 shows the arithmetic.

Proved by: the exit report of each sub-milestone (doc 20 s1); T-0-46 keeps this table and gates.yaml in step.

### 2.2 What changes versus docs/12, and why

| docs/12 | This plan | Why | Decision |
| --- | --- | --- | --- |
| Promotion engine in Phase 5 | In 2a, with DRP/slide deduction, QC deduction and rounding | Every pilot memo total depends on them; parity cannot be judged without them | D-33, D-143 |
| Phase 2 and Phase 4 are one unit each | 2a to 2e and 4a to 4d, each with a demo | A unit of 8 or 6 weeks cannot be tested step by step | D-141 |
| Phase 3 supervisors is one unit | 3a AMO and 3b TSO | Different bundles, different offline classes, different gates | D-72, D-82 |
| Pilot is one clean parallel day | 10 consecutive trading days with zero sync loss | One day cannot expose intermittent loss | D-145 |
| Config console in Phase 6 | Minimal console in 1c, rails in 2d, full console in 6b | Radius, check-out time and min_version are launch-critical and R6 must be visible in the first slice | D-90 |
| Idempotent upsert by row uuid only | Three layers plus server-generation re-sync | Retried batches and DR | D-21, D-62, D-63 |
| Phase 1 DoD "meets battery and size budget" | Numeric budgets measured on a reference device per release candidate | A sentence cannot be ticked | D-73 |
| Phase 0 DoD "a seeded DB, green CI, a scoped login" | Plus device lab, contract generators, cfg registry, rtm-check, baseline pack, quota requests | Parity, traceability and launch capacity need Phase 0 inputs | D-142, D-133 |
| Web (Phase 4) after the SR and supervisor apps | Pilot-gating web pages (sync-health, Daily Tracking, Login/Submit Status, Final Submit, outlet approval panel, Web Entry fallback, SR Device OTP panel) land in 4a and 4c and are 7b entry conditions; programme reports stay with their programme in P5 | Final Submit and sync-health gate the pilot | D-143 |
| Phases run strictly in order | P3 to P6 are split streams after 2e; the order of exit is fixed only by the entry conditions of 7b and 7c (s2.3) | Team size and the Apsis dump date decide the real order | D-307 |

Round-2 changes (skeptic review; the decision text is in DECISIONS.md section M3):

| First plan | Now | Why | Decision |
| --- | --- | --- | --- |
| Scale proof at 4d is the load test S1 and the DR and restore drills | 4d runs S2, S4 (the 8,000 rows per second ramp and the 1.5 times fleet), S5 with S4, S6, S8, S9 and S11 as blocking gates; an S4 smoke at about 2,000 rows per second is a 2e gate; the exit script reads the S1 to S11 report; the proof is repeated at 7d | A defect in the ingest or registry design must show before the pilot, when the Drift schema is not yet frozen | D-504 |
| Defaults may drop a live Apsis function silently | Parity Exceptions Register (doc 15 s6.4) with owner, signature and date; usage census in the baseline pack; "no unsigned row" is a 7b entry condition | R2: no current function is missed | D-502, D-503, D-523 |
| Weeks fixed by the skeleton | 1b 2 weeks, 2e 2.5 weeks, 4d 1.5 weeks; calendar-time gates carry `lead_time_h` and `depends_on`; the pilot has a 2-week reserve | A 72-hour ingest soak and a 5-day dogfood week cannot be compressed into 1.5 weeks | D-515, D-529 |
| Gate and demo order not checked | Ordering rule: a gate or a demo step is never earlier than the sub-milestone of any feature it exercises (rtm-check rule 10); minimal Exceptions list in 2d, minimal Outlet Approval Panel leg in 3a, SH-04 to SH-06 in 3b, OTP view by 0c | Demos exercised screens that did not exist yet | D-513 |
| Task chain and leave chain spread over 3a to 5c | The task chain and the leave chain land whole in 3b | A half chain cannot be demonstrated | D-532 |
| F-API-017, F-API-021 and F-API-035 are single rows | Split into read and write rows with their own sub-milestone | An endpoint must not land later than its first caller | D-533 |
| R4 and R5 are proved on lab phones and on the happy path | Field telemetry (SH-19 to SH-24), per-flavour budgets for AMO and TSO, an OEM condition matrix and an immediacy SLO are wave go/no-go measures; a canary that regresses by more than 20 percent freezes its own rollout | "Immediately" and "abruptly" need field evidence | D-506, D-507, D-509, D-511 |
| Phase scope lines list lens ids | Counts and lines are generated from doc 15 (s3 table below); a CI check compares them | Stale counts in docs 14, 15 and 20 | D-516 |

**Changes against docs 03 and 06 to 10 and db/schema.sql (D-534).** The plan documents (14 to 21 and DECISIONS.md) bind and win over docs 01 to 13 and db/schema.sql wherever they differ; docs 01 to 13 stay as the record of the original specification. The differences, by document, are the rows of s5 and the decisions below; each row names the document it changes.

| Document | What the document says | What this plan does | Decision, s5 row |
| --- | --- | --- | --- |
| docs/03 and db/schema.sql | Money in minor units; one flat schema; aggregates maintained as the data arrives | Money is bigint milli-taka (`_mtk`); four schemas (app, cfg, dw, stg) with `client_uuid` and a provenance block on every device table; aggregates recomputed per dirty key, never incremented; typed fields instead of JSON except J-1 to J-8 | D-15, D-24, D-61, D-501; s5 row 14 |
| docs/04 | Budgets in words; photos compressed | Numeric budgets as release gates per route size and per flavour, photos at 150 KB, field telemetry | D-73, D-506, D-508; s5 row 5 |
| docs/05 | Mock-location detection, radius check, photo may update the outlet | A mocked fix is never geo-valid; a photo creates a location-change request instead of moving the outlet; radius is config at eight scope levels | D-96, D-163, D-93; s5 rows 4 and 6 |
| docs/06 | SR screens and flow | Parity kept; reprint carries a duplicate marker, Sale History works offline for 7 days, the memo number is device-composed | D-35, D-83, F-SR-066; s5 rows 15, 25, 26 |
| docs/07 | AMO screens and flow | Parity kept; Outlet badge hidden at zero, team location shown as last seen, same-day cover and day exceptions added | D-169, D-170, D-85; s5 rows 7, 12, 23 |
| docs/08 | TSO screens, logout wipes all data | Logout is refused while anything is unsent; default language per role; confirm before Final Submit | D-69, D-181, D-198; s5 rows 8, 19, 20 |
| docs/09 | Web reads aggregate in the browser; the browser sends scope ids; Delete Section Data | Reads come from dw only; scope is derived on the server; delete is an audited void before Final Submit | D-61, D-106, D-40; s5 rows 1, 2, 9, 30 |
| docs/10 | Submit % defined as submitted over target routes; till-date day counts | Two named figures (Submit % of logged-in, Day-completion %); day counts read from the calendar, never constants (K-13); Total Service Zone derived | D-45, D-58, D-536; s5 row 13 |

Skeleton row "Web after programmes" is read as the pilot-gating rule in the second-last row (docs/12 already has web at Phase 4 and programmes at Phase 5); see OI-14-04.

Proved by: T-0-46 and the exit reports.

### 2.3 Critical path and dependency graph

```
 0a -> 0b -> 0c -> 1a -> 1b -> 1c -> 2a -> 2b -> 2c -> 2d -> 2e ........ (whole team, wk 1 to 16.5, "pilot-ready")
                                                               \
        baseline pack T-0-49 (AKTCL staff) --> 1a entry          +--> 3a -> 3b -> 4a -> 4c -> 6b(min)  (wk 17 to 25.5)
        Apsis dump arrives (by wk 23) + legal answers (D-570) -->  7a (2 wk, data + infra) ----+
        pilot SRs + consent + incentive (Q33) -------------------+                             v
                                                                                              7b pilot (wk 26 to 28.5)
                                  4b, 4d, 6c, 6b(rest) at ~50 percent capacity (wk 26 to 32) --+
                                  5a (2 wk) before wave 1 if the D-306 test says so -----------+
                                                                                               v
          helpdesk, pre-bind plan, quotas (D-133), rollback statement (D-592) ---->   7c wave 1 --> 7d waves --> 7e
          5b, 5c, 6a run during 7c and 7d (programme routes bind only after 5a to 5c: D-306)
```

| Step | Team-weeks | Cumulative week | Calendar (ASSUMPTION) | Note |
| --- | --- | --- | --- | --- |
| P0, P1, P2 (whole team) | 16.5 | 16.5 | Tue 2027-02-02 | pilot-ready; 1.5 weeks later than the first plan because of the D-515 re-baseline (1b +0.5, 2e +1) |
| 3a, 3b, 4a, 4c, 6b minimal | 9 (3 + 2 + 2 + 1 + 1) | 25.5 | Tue 2027-04-06 | Ramadan (about 8 Feb to 9 Mar 2027) and Eid-ul-Fitr (about 9 and 10 Mar) fall inside; about one week of capacity is lost (float, below) |
| 7b pilot, 3 weeks at about 50 percent engineering | 3 | 28.5 | Tue 2027-04-27 | 4b, 4d, 6c, 6b rest need 5 team-weeks and get 1.5 of them |
| Remaining 4b, 4d, 6c, 6b rest | 3.5 | 32 | Thu 2027-05-20 | 7c entry conditions met (s3); Eid-ul-Adha about 16 and 17 May 2027 lies here |
| 5a if the D-306 test requires it | 2 | 34 | Thu 2027-06-03 | |
| Float before the planning date | 2 (with 5a) to 4 (without) | 36 | Thu 2027-06-17 | includes the 2-week pilot reserve (D-529); unreserved float is 0 to 2 weeks (RK-26) |
| 7c wave 1 | 1.5 | starts week 37 | planning date Sunday 2027-06-20, pre-bind day Saturday 2027-06-19 | D-311 blackout rules; Ashura (about 15 June 2027) avoided |

The first plan carried 2.5 to 6.5 weeks of contingency; the re-baseline (D-515) takes 2 weeks of it and the pilot reserve (D-529) takes 2 more, so the float that remains is the number a sponsor should watch. If it is gone, moving the planning date by up to 4 weeks is cheaper than cutting a gate (s2.5).

Calendar dates are astronomical estimates for the moon-dependent holidays; the Bangladesh gazette governs. Fixed national days (26 March, 14 April, 1 May, 16 December, 21 February) are checked against every exit date. The planning date is a target, not a promise: unknown; confirm with the business (D-147, D-311).

External dependencies on the critical path:

| Dependency | Owner | Needed by (last responsible) | If late | Risk |
| --- | --- | --- | --- | --- |
| Named delegate and four readiness owners (D-156) | Sponsor | 0a exit (wk 1) | no gate can be signed | RK-17 |
| Baseline pack, captured by AKTCL staff (T-0-49, D-142, D-312) | Sponsor and sales ops, with a named owner and fallback (D-565) | 0c exit; Phase 1 entry (wk 3) | no parity oracle; 1a starts without golden memos at the risk of rework | RK-02 |
| Physical 58 mm memo samples (D-76, D-158, MQ-57) | Sales ops | 1a exit (wk 4.5) | printed layout guessed; 2a golden fails | RK-02 |
| Promotion catalogue (D-33, Q13) | Trade marketing | 2a entry (wk 7.5) | 2a cannot exit; pilot cannot start | RK-03 |
| Fleet census and two lab phones per reference model (D-11, D-12, Q31) | Ops and IT | 0c exit (wk 3), a hard deadline (D-564) | battery gates on the wrong phones and no battery-size classes; the 0c exit is blocked | RK-01 |
| Dump-request letter issued (D-303) and dump delivered (Q18) | Legal and PM | letter wk 1; dump by end of wk 23 for a 7a that ends before 7b | importer proven only on synthetic data; dues and loyalty unverified | RK-04 |
| Per-route daily Apsis data for the pilot (D-154, Q32) | Sales ops | 7b entry (wk 25.5) | manual keying for the pilot | RK-09 |
| Azure quota requests (D-133) | Infra lead | requested in 0a; granted by 4d | load tests cannot run at production size | RK-24 |
| Legal opinion on residency and PII, location notice (D-05, D-107, D-120) | Legal and HR | 7a entry (D-570) | 7a runs on a pseudonymised or synthetic import and 7b cannot start | RK-05 |
| Helpdesk staffed and pre-bind day plan (D-149, D-126) | Support lead and sales ops | 7c entry | wave 1 deferred | RK-10, RK-06 |
| Apsis delta contract signed and a named delta owner (doc 16 s12.6, D-514) | Sales ops, legal, IT | 0c exit; the delta path must run twice before wave 1 (7a and 7c entry) | every wave night depends on a feed nobody has requested; the wave is deferred | RK-27 |
| Parity Exceptions Register signed and the usage census answered (doc 15 s6.4, D-502, D-503) | Sponsor, sales ops | 0c exit; no unsigned row at 7b entry | R2 cannot be shown to hold; 6a may build a function nobody uses or miss one that is used | RK-16 |
| Rollback return path staffed (D-549) | Sponsor, sales ops, finance | 7b exit | a wave rollback leaves retailer dues in two systems | RK-28 |
| The Board envelope: Apsis contract end and fee, programme cost and cost of delay, confirmed headcount, latest wave-1 date (D-555, s2.5b) | Sponsor | 0a exit | the plan runs on D-307 with the Apsis contract assumed to cover wave 4; nobody can say what a slip costs | RK-30 |
| APK distribution channel and Google developer-verification position (D-10, D-562) | IT, sponsor | 0c exit | the updater is built for a channel that Android 14 to 16 may block; the pre-bind day fails | RK-29 |
| BI path facts: Fabric capacity, VNet data gateway, Power BI licences (D-560) | IT | 0c exit | 4c dashboards are built on a path that is not licensed | RK-24 |
| Wave-1 DR position accepted in writing, with the measured geo-restore RTO (D-569, T-4-174) | Sponsor | 7a exit (server creation); drill before 7c | wave 1 starts on a region-outage position nobody signed | RK-05 |
| Rollback capacity: trained keyers per wave or an Apsis bulk-import path (D-592) | Sponsor, sales ops, finance | 7b exit | the rollback lever of waves 3 and 4 exists on paper only | RK-33 |
| Evidence files committed under /docs/evidence (D-530) and the precedence text applied to CLAUDE.md and the README (D-534) | Sponsor | before kickoff, Sunday 2026-10-11; 0a exit at the latest (D-554, T-0-160) | ids and gates cite files that are not in the repository | RK-02 |

Proved by: T-0-49, T-0-48, T-7-80, T-7-84, T-7-85.

### 2.4 Parallel streams, team shape and effort bands

ASSUMPTION (D-307): 8 to 10 engineers plus a QA lead, a product owner, a native-Bangla reviewer (part time) and the sponsor's delegate. No team, budget or start date is stated in docs/01 to 13 (G-14-02).

| Stream | Engineers | Owns | Peak phases |
| --- | --- | --- | --- |
| App (Flutter) | 3 | Drift store, outbox, geo, print, SR, AMO and TSO flavours, localisation | P1, P2, 3a, 3b |
| API and sync | 2 to 3 | auth, scope, ingest, bundle, config, admin API | P1, P2, P4 |
| Web | 2 | dashboards, reports, panels, console | 4a to 4c, 6b |
| Data and importer | 1 | schema, dw, KPI SQL, importer, reconciliation | P0, 1c, 7a |
| Infra and SRE | 1 | Bicep, pipelines, observability, load tests, runbooks | 0a, 4d, 7c |
| QA and device lab | 1 (QA lead) | gate harnesses, lab, parity oracle, exit scripts | all |
| Product owner, delegate, reviewer | people, not engineers | demos, sign-off, strings | every demo |

Effort bands (ASSUMPTION): person-weeks = weeks (s2.1) x engineers engaged (8 to 10; 7 to 9 in P0 before the app stream starts).

| Phase | Weeks | Person-weeks | Phase | Weeks | Person-weeks |
| --- | --- | --- | --- | --- | --- |
| P0 | 3 | 21 to 27 | P4 | 6.5 | 52 to 65 |
| P1 | 4.5 | 36 to 45 | P5 | 5 | 40 to 50 |
| P2 | 9 | 72 to 90 | P6 | 4 | 32 to 40 |
| P3 | 5 | 40 to 50 | P7 | 10+ | 40 to 70 (operations peak at each wave) |
| | | | **Total** | | **333 to 437** |

Check against the schedule: work before the end of wave 1 is P0 to P2 (129 to 162) plus 3a, 3b, 4a, 4c, 6b minimal (72 to 90) plus 4b, 4d, 6c, 6b rest (40 to 50) plus 7a and pilot support (about 20) = about 261 to 322 person-weeks; at 9 engineers that is 29 to 36 weeks, which agrees with the 32-week critical path to the 7c entry (the first plan had 245 to 302 person-weeks and 30 weeks).

The Board envelope that turns these person-weeks into a cost, and the half-team and compress options, are in s2.5b (D-555).

Proved by: T-0-48 (lab), the exit report velocity table at 1c.

### 2.5 Calendar lead time and the schedule of record (D-515, D-529)

The table of s2.1 is the schedule of record. A sub-milestone cannot end sooner than the calendar time its slowest gate needs after the build is finished: a soak cannot be compressed by adding engineers. Every gate in `gates.yaml` carries `lead_time_h` (the calendar hours the gate needs after its build) and `depends_on` (the gates and features it needs); the calendar critical path of every sub-milestone is computed from them (T-0-155) and a CI check compares it with the Weeks column of s2.1; s2.3 is derived from s2.1 and the same check covers it.

| Sub-milestone | Calendar-bound gates (lead time; ASSUMPTION until T-0-155 computes the path) | Weeks in s2.1 |
| --- | --- | --- |
| 1b | 72-hour ingest soak with fuzz and the poison-batch DB-health run (T-1-151); digest and generation tests (T-1-152) | 2 (was 1.5) |
| 1c | battery and data protocol run 1 on the reference device (8 hours per run, three runs) | 1 |
| 2e | dogfood week: 10 staff, 5 trading days (120 hours) starting on the 2d release candidate; OEM matrix (48 hours); battery runs on three devices (8 hours each); S4 smoke; the `/cso` audit | 2.5 (was 1.5) |
| 3a, 3b | AMO and TSO scripted days and battery runs (8 hours each), per-flavour APK check | 3, 2 |
| 4d | S2, S4, S5 with S4, S6, S9, S11 and the 12-hour soak S8, the DR drill and the restore drill on a production-sized staging window booked with the scale-up (doc 18 s3.4 gives the prescale deploy-time bounds) | 1.5 (was 1) |
| 7b | 10 consecutive trading days with Friday off needs at least 12 calendar days; planned 3 weeks | 3 |
| 7c | pre-bind day, then the wave day, then 5 post-wave days | 1.5 |

Pilot reserve and extension (D-529): the pass rule of 10 consecutive trading days with zero category C resets on every category C. The plan keeps a 2-week reserve inside the float of s2.3, allows at most 2 resets by category C and a total extension of at most 4 weeks; the sponsor's delegate decides each extension; a third reset or a 5-week overrun triggers a re-plan by the sponsor (gate T-7-159). The float is a risk row (RK-26), not a promise.

Proved by: T-0-155 (computed path equals s2.1), T-7-159 (pilot extension rule).

### 2.5b The Board envelope: cost, cost of delay, half team and the compress or extend choice (D-555)

The plan has never said what it costs or what a slip costs, so the Board cannot compare a delay with a cut. Four numbers are the sponsor's to give at 0a (s4.2c, T-0-162); the arithmetic that turns them into a decision is fixed here so that every exit report can print it.

| Item | Statement |
| --- | --- |
| Programme cost | 333 to 437 person-weeks (s2.4) times the confirmed loaded weekly rate per engineer, plus the non-engineer roles (QA lead, product owner, reviewer, helpdesk of 7c), plus Azure at about 1,780 USD a month at the pilot size, 3,690 at wave 1 and 6,630 at full fleet (doc 18 s3.3). The rate is unknown: confirm with finance; it is not assumed here. |
| Cost per week of delay | engineers x weekly rate + the non-engineer roles + Apsis fee per week (the monthly fee x 12 / 52) + Azure at the stage in force x 12 / 52. At 9 engineers the people line dominates; Azure is under 2 percent of it. The exit report of every sub-milestone prints this figure beside the float of s2.3. |
| Latest wave-1 date | Apsis contract end minus the tail that follows the wave-1 date: 1.5 weeks of wave 1, 4 or more weeks of waves 2 to n and 1 week of decommission, about 6.5 weeks, plus the read-only period that D-155 sets. On the planning basis the tail ends about Wednesday 2027-08-04 (week 42.5 of s2.1), so the Apsis contract must run beyond that date by the read-only period; a contract that ends earlier moves the planning date or buys an extension, and the Board must see which (RK-30). |
| Half-team scenario | With 4 to 5 engineers instead of 8 to 10 the work before the end of wave 1 (261 to 322 person-weeks, s2.4) takes about 58 to 72 weeks, not 29 to 36: wave 1 moves from week 37 to about week 66 to 80 (January to April 2028), the P0 to P2 phase from 16.5 weeks to about 29 to 36, and the cost of delay falls per week but rises in total because Apsis and the non-engineer roles run for the whole of the longer period. This scenario is a proportion, not a promise (OI-14-15). |

Compress or extend, in the order the plan prefers:

| Option | Effect | Limit |
| --- | --- | --- |
| Move the planning date by up to 4 weeks | Restores the float without touching a gate | Preferred to every cut (s2.3); needs the Apsis contract to cover it |
| Add up to 2 engineers to the parallel streams only (web, reports, panels, programmes, CRUD) | Saves up to about 2 to 3 of the 32 weeks to the 7c entry | None for P0 to P2, where the whole team is already engaged and sync, schema and contract work does not split (more people do not shorten a soak either, s2.5) |
| Start 5a before the D-306 test says so | Removes the 2-week conditional step from the critical path | Costs the same person-weeks earlier; no gate changes |
| Cut a gate or a feature | Not offered. A gate moves only by an amendment row (doc 20 s3); a feature leaves only by a signed row of the Parity Exceptions Register (doc 15 s6.4) | R1 to R6 |

Proved by: T-0-162 (the four answers), T-0-155 (the calendar path the cost of delay is read against).

## 3 Phase-by-phase plan

Each phase gives: goal and entry; scope as F-id ranges (the lens inventory in docs/12 numbering, 275 features; doc 15 s1 owns the final per-phase counts after the D-143 move and the new pinned ids); deliverables by document; the sub-milestone table (demo of at most 30 minutes, exit that can be checked by hand, blocking gate families, MUST-CONFIRM decisions due); gaps closed; the exit check a non-engineer can run; effort band; and what is explicitly deferred. Every sub-milestone exit needs a test, an evidence artefact under /docs/evidence/phase-N/ and a named verifier who is not the author (D-141). Each exit also records the CLAUDE.md constraints it exercised (s9.4).

### 3.00 Scope counts, generated from doc 15 (D-516)

The F-id lists in the "Scope (F-ids)" rows below are the lens numbering of docs/12 and the ids pinned when this plan was first written. Where they differ from the tables here, the tables win: they are generated from the Ph column of doc 15 (`rtm.yaml`) by the script that regenerates doc 15 s1.3, and a CI check (T-0-152, rtm-check rule 13) fails when the counts of s1.1 and this section differ from doc 15 s1.3. A feature is counted once, under the sub-milestone in which it first works end to end.

| Role | Features | parity | changed | new | Of which in lens-features (275) | Added by this document |
| --- | --- | --- | --- | --- | --- | --- |
| SYS | 97 | 13 | 12 | 72 | 46 | 51 |
| SR | 81 | 42 | 14 | 25 | 50 | 31 |
| AMO | 49 | 31 | 6 | 12 | 36 | 13 |
| TSO | 31 | 19 | 4 | 8 | 20 | 11 |
| WEB | 72 | 51 | 4 | 17 | 47 | 25 |
| ADM | 85 | 11 | 11 | 63 | 37 | 48 |
| API | 94 | 21 | 14 | 59 | 39 | 55 |
| Total | 509 | 188 | 65 | 256 | 275 | 234 |

| docs/12 phase | SYS | SR | AMO | TSO | WEB | ADM | API | Total |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 0 Foundations | 8 | 1 | 0 | 2 | 2 | 2 | 5 | 20 |
| 1 Vertical slice | 30 | 13 | 0 | 0 | 0 | 7 | 16 | 66 |
| 2 Full SR day | 36 | 56 | 0 | 2 | 2 | 30 | 35 | 161 |
| 3 Supervisors | 1 | 4 | 48 | 26 | 3 | 5 | 16 | 103 |
| 4 Web | 4 | 0 | 0 | 0 | 52 | 6 | 11 | 73 |
| 5 Programmes | 1 | 7 | 1 | 1 | 9 | 9 | 4 | 32 |
| 6 Admin and master data | 2 | 0 | 0 | 0 | 3 | 21 | 5 | 31 |
| 7 Migration and cutover | 15 | 0 | 0 | 0 | 1 | 5 | 2 | 23 |
| Total | 97 | 81 | 49 | 31 | 72 | 85 | 94 | 509 |

| Sub-milestone | SYS | SR | AMO | TSO | WEB | ADM | API | Total |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 0c | 8 | 1 | 0 | 2 | 2 | 2 | 5 | 20 |
| 1a | 9 | 12 | 0 | 0 | 0 | 0 | 4 | 25 |
| 1b | 13 | 1 | 0 | 0 | 0 | 0 | 2 | 16 |
| 1c | 8 | 0 | 0 | 0 | 0 | 7 | 10 | 25 |
| 2a | 5 | 24 | 0 | 0 | 0 | 4 | 3 | 36 |
| 2b | 1 | 12 | 0 | 0 | 0 | 0 | 1 | 14 |
| 2c | 2 | 7 | 0 | 0 | 0 | 0 | 0 | 9 |
| 2d | 10 | 2 | 0 | 1 | 1 | 5 | 8 | 27 |
| 2e | 18 | 11 | 0 | 1 | 1 | 21 | 23 | 75 |
| 3a | 1 | 1 | 46 | 0 | 1 | 1 | 8 | 58 |
| 3b | 0 | 3 | 2 | 26 | 2 | 4 | 8 | 45 |
| 4a | 1 | 0 | 0 | 0 | 10 | 1 | 2 | 14 |
| 4b | 1 | 0 | 0 | 0 | 33 | 0 | 4 | 38 |
| 4c | 2 | 0 | 0 | 0 | 9 | 5 | 5 | 21 |
| 5a | 1 | 6 | 1 | 1 | 5 | 3 | 2 | 19 |
| 5b | 0 | 1 | 0 | 0 | 2 | 1 | 0 | 4 |
| 5c | 0 | 0 | 0 | 0 | 2 | 5 | 2 | 9 |
| 6a | 0 | 0 | 0 | 0 | 3 | 14 | 3 | 20 |
| 6b | 0 | 0 | 0 | 0 | 0 | 4 | 0 | 4 |
| 6c | 2 | 0 | 0 | 0 | 0 | 3 | 2 | 7 |
| 7a | 9 | 0 | 0 | 0 | 0 | 3 | 1 | 13 |
| 7b | 6 | 0 | 0 | 0 | 1 | 2 | 1 | 10 |
| Total | 97 | 81 | 49 | 31 | 72 | 85 | 94 | 509 |

Sub-milestones with no row (0a, 0b, 4d, 7c to 7e) own no features of their own: they are tooling, schema, proof and operations work.

Gate-id note: the family column follows the plan skeleton; where this plan names a gate by title (for example the baseline pack, the airplane-mode slice) the id and title come from the quality lens, and doc 20 s3 owns the final placement (OI-14-01).

### 3.0 Phase 0 — Foundations (3 weeks; 0a, 0b, 0c)

| Item | Content |
| --- | --- |
| Goal | Everything every later phase stands on exists and is tested; nothing sells yet |
| Entry | Repo with CLAUDE.md, docs/01 to 13, docs/22 and docs 14 to 21; the manual inventories, the delta register and the six V-* verification files committed read-only under /docs/evidence/manuals/ and /docs/evidence/verification/ (D-530; they are NOT in the repository at the date of this plan, so the sponsor commits them before kickoff and T-0-154 blocks the 0a exit otherwise); the precedence text, the reading list and the money convention applied to CLAUDE.md and the README and the superseded-by banners put on docs 03, 06 to 10 and db/schema.sql (D-534, D-554; the ready-to-paste text is in s9.2b; sponsor action before kickoff on Sunday 2026-10-11; T-0-160); Azure subscription and GitHub organisation access; the DEFAULT decisions D-01 to D-07 and the planning basis D-307 accepted or amended by the sponsor; two pilot phones in hand |
| Scope (F-ids) | F-SYS-001..005, 032, 038; F-SR-002; F-TSO-019; F-WEB-033, 043; F-ADM-022; F-API-001..004, 036 (17 features: login, token and scope, device bind and OTP, change password, first admin and API items) (counts: s3.00, which wins where it differs) |
| Dependencies | Sponsor names the delegate and readiness owners (D-156); the Board envelope rows of s4.2c answered or carrying their default (D-555, T-0-162); AKTCL staff start the baseline pack (D-142, D-312) and nominate its owner and a fallback (D-565); dump-request letter issued (D-303); Azure quota requests filed (D-133); fleet census requested from the TSOs with a hard return date at the 0c exit (D-11, D-12, D-564) |
| Effort band | 21 to 27 person-weeks (s2.4) |

| Document | Lands in Phase 0 |
| --- | --- |
| 14 | this plan, DECISIONS.md, the confirmation schedule, the first-two-weeks list below |
| 15 | F-id inventory loaded into rtm.yaml (T-0-46); message catalogue plan and glossary started |
| 16 | Schema v2 migrations M-01..M-45 on PostgreSQL 16, seed of the 42-SKU catalogue (7.935 round-trips), partition job, safe_div and CHECK constraints |
| 17 | Sync record-type enum and headers in /packages/contract; local-store and SQLCipher decision inputs (D-67) |
| 18 | Bicep skeleton for dev, staging and prod; quota requests; observability baseline; load-model inputs recorded |
| 19 | cfg registry seeded with bounds, exclusion constraint and immutable audit (T-0-60..64) |
| 20 | pr, main and promote-prod pipelines; testkit v1; device lab; gates.yaml and rtm-check; baseline pack (T-0-49) |
| 21 | Argon2id login, tokens, device-key model, ScopeContext, row-level security, scope-leak harness, SAST, dependency, secret and IaC scans (T-0-70..76) |

| Code | Demo (at most 30 minutes; who runs it) | Exit (checkable by hand) | Gates (doc 20 s3 placement) | Confirm |
| --- | --- | --- | --- | --- |
| 0a | Delegate opens a pull request that touches every workspace and watches the pipeline go green in 15 minutes or less; opens the generated GATES.md (the gate report is a generated file, not a product page) and sees the lab phones and printers listed | Pipelines pr, main (to staging) and promote-prod (two approvers) exercised with a health endpoint; Testcontainers harness builds a fresh PostgreSQL 16 with all migrations; testkit generates a one-zone fleet with no personal data; rtm-check fails on an orphan; injectable clock passes at 10:00, 16:59:30 and 23:58 Dhaka; 6 reference devices and 2 printers inventoried; baseline-pack capture started; the evidence files are committed read-only and named in the DECISIONS.md header (T-0-154); rtm-check rules 10 to 16 run over docs 14 to 21 and DECISIONS.md and the findings are fixed (T-0-152, T-0-159); the constants registry exists (T-0-159); CLAUDE.md, the README and the base documents carry the precedence text and banners (T-0-160); the Board envelope rows are answered or carry their default (T-0-162); the generated manual-coverage and F-to-T files exist (T-0-161, T-0-163) | T-0-40, T-0-42..49, T-0-78, T-0-140, T-0-142, T-0-145, T-0-152, T-0-154, T-0-155, T-0-159..163 | D-156, D-530, D-534, D-554, D-555 |
| 0b | Engineer builds the database from migrations in under a minute in front of the delegate; the seed loads; price 7.935 comes back as 7.935; the registry page lists every key with default and bounds | Contract build (Zod to OpenAPI to TypeScript and Dart; 250 fixtures round-trip, T-0-41); schema, seed, partition and CHECK tests; registry completeness, bounds, drift and audit-immutability tests | T-0-01..04, T-0-06..08, T-0-41, T-0-50, T-0-51, T-0-60..64, T-0-144, T-0-150 (report-only), T-0-151 (report-only), T-0-156, T-0-157 | D-501, D-558 |
| 0c | Delegate logs in to staging as each of the seven roles; types another zone's id in the URL and gets "not allowed"; a new phone asks for the OTP | Scope-leak harness green for every endpoint; auth negatives (lockout, uniform errors, token reuse); Bengali fonts render conjuncts; baseline-pack index lists all nine items a to i with files, including the usage census (T-0-49, D-503); the Parity Exceptions Register is signed (T-0-153, D-502); the Apsis delta contract is accepted by the sponsor (D-514); the APK distribution channel is decided with the Google developer-verification position written down (D-10, D-562, RK-29); the fleet census is returned and the lab holds the reference models (D-11, D-12); the role map generator and the positive privilege matrix pass under the runtime logins (T-0-158) | T-0-70..77, T-0-79, T-0-120..122, T-0-141, T-0-153, T-0-158 | D-10, D-11, D-12, D-101, D-102, D-103, D-142, D-164, D-502, D-503, D-514, D-560, D-562, D-565 |

T-0-41 (contract build) sits in the 0a family of the plan skeleton but is run at 0b because /packages/contract v1 is a 0b deliverable (OI-14-01).

Gaps closed (34 register gaps: 8 blocker, 21 major, 5 minor; plus G-14-01, G-14-02, G-14-05 and G-14-08 at 0a). Blockers: G-qa-01 (no parity oracle: baseline pack, 0a), G-data-01 (no idempotency key), G-data-02 (no capture-time context), G-data-03 (paisa cannot hold three decimals), G-data-04 (no unit), G-data-05 (two competing submit-percentage definitions, now Submit % (of logged-in) and Day-completion %), G-cfg-02 (no runtime config store), all 0b; G-feat-11 (OTP issuance surface, 0c). By sub-milestone: 0a 9, 0b 13, 0c 12.

**Exit check a non-engineer can run.** Open the staging address and log in as each of the seven test personas; each sees only its own patch; type a wrong zone in the address and read "not allowed"; open the baseline-pack index and see nine items with files; open the Parity Exceptions Register and see every row with an owner and a signature; open the generated GATES.md and see every Phase 0 gate green or listed pending-oracle with its question; read the two signatures (tech lead, sponsor's delegate) on the exit report.

**Deferred.** Any selling screen; outlets and routes beyond the seed; Managed Redis (needed before wave 1, D-07); WAF tuning; Entra SSO (D-114, by 4c).

#### First two weeks (Phase 0, 0a and 0b), day by day

Assumption: Week 1 starts Sunday 2026-10-11; this week (4 to 8 October) is pre-kickoff. Owners are roles (s9.1). Each line has an output someone can open.

| Day | Task | Owner | Output and acceptance | Id |
| --- | --- | --- | --- | --- |
| Pre-kickoff, Sun 4 to Thu 8 Oct | Sponsor reads s4 and s5, accepts or amends D-01 to D-07 and D-307 | Sponsor, tech lead | Amendment rows in DECISIONS.md or "accepted" in the change log | D-307, D-309 |
| | Commit the evidence files under /docs/evidence/ and apply the text of s9.2b to CLAUDE.md and the README; put the superseded-by banner on docs 03, 06 to 10 and db/schema.sql (these are not files this planning pass may edit) | Sponsor, tech lead | The commit; the precedence sentence is the first thing a new session reads | D-534, D-554, G-qa-86 |
| | Answer or accept the defaults of the Board envelope rows (s4.2c): Apsis contract end and fees, programme cost envelope, headcount, latest wave-1 date | Sponsor | Four signed rows | D-555, T-0-162 |
| | Name the sponsor's delegate and the four readiness owners | Sponsor | Names in s9.1 table | D-156, G-qa-06 |
| | Issue the Apsis dump-request letter through AKTCL legal and open the source-code handover chase | Legal, PM | Dated letter; delivery date tracked in s4 | D-303, G-14-05 |
| | Request Azure subscriptions, resource groups, quotas (ACA 64 cores, 128 for the full fleet; PostgreSQL 48 vCores; Load Testing 10 engines) | Infra lead | Quota tickets filed; alerts at 80 percent planned | D-133, RK-24 |
| | Schedule baseline-pack sessions with AKTCL staff; ask TSOs for the fleet census (models, RAM, Android versions); buy or borrow 2 RPP02N-class printers | Delegate, ops | Calendar entries; census form sent with a return date of the 0c exit; named baseline-pack owner and fallback | D-142, D-11, D-12, D-565 |
| D1, Sun 11 Oct | Kickoff (60 minutes): walk the 28 sub-milestones, owners, the working agreements of s9. Scaffold the monorepo (/api /web /app /db /packages /infra), CODEOWNERS, branch protection | Tech lead | Empty workspaces build; protected main | T-0-40 |
| D2, Mon 12 Oct | pr.yml skeleton (lint, typecheck, unit, gitleaks, CodeQL, dependency review) green; lint stubs no-wall-clock, no-hardcoded-string, no-unscoped-query, no-pg-mock. Testcontainers harness with PostgreSQL 16 and the migration runner; migration 0001 renames the _minor money columns to _mtk (D-15) | API lead, QA lead | A pull request goes green; a fresh database in under 10 seconds | T-0-40, T-0-42 |
| D3, Tue 13 Oct | Bicep for dev: resource group, Container Apps environment, PostgreSQL Flexible (pilot SKU), Blob, Key Vault, Log Analytics; GitHub OIDC federation; what-if on pull request. Injectable clock in api, app and web | Infra lead, all | What-if output attached to a pull request; clock tests pass at the three times | T-0-43, T-0-47 |
| D4, Wed 14 Oct | testkit v1 (fleet, day, personas, seeded and deterministic, no personal data). rtm.yaml, gates.yaml and gaps.yaml loaded from s7 and doc 15; rtm-check job. Commission the device lab: 6 phones, 2 printers, Wi-Fi ADB, self-hosted runner Constants registry /plan/constants.yaml with the first-grow month, storage size, role names and reach bounds, and rtm-check rules 13 to 16 (T-0-159); the F-to-T table generator (T-0-163). | Data lead, QA lead | rtm-check fails on a planted orphan; a debug APK installs on all six phones | T-0-45, T-0-46, T-0-48, T-0-159, T-0-163 |
| D5, Thu 15 Oct | Deploy pipelines main to staging and promote-prod with two approvers, both run against a health endpoint; observability smoke (one request gives a log line, a trace and a Sentry event, PII scrubbed). Baseline-pack session 1 (AKTCL staff record a full SR day and print 25 memos). Demo 0a and exit review Evidence and plan wiring check (T-0-154, T-0-160); the manual-coverage generator runs on the four inventories (T-0-161); the calendar critical path is computed (T-0-155). | Infra lead, QA lead, delegate | Deployment history; KQL screenshot; recording index; signed 0a exit report | T-0-43, T-0-44, T-0-49, T-0-154, T-0-155, T-0-160, T-0-161, T-0-162 |
| D6, Sun 18 Oct | Migrations in the order of the doc 16 s2 map (schemas app, cfg, dw, stg; identity and idempotency tables; geography; products). /packages/contract v1: Zod schemas, envelope, record types from the doc 17 enum, generators to OpenAPI 3.1 and the TypeScript and Dart clients. The schema contract lint skeleton (every device table has `client_uuid` and the provenance block) | Data lead, API lead | Schema applies to a fresh database (T-0-01); both clients compile; the lint runs | T-0-01, T-0-06 |
| D7, Mon 19 Oct | Seed: sku_catalog.csv loaded as round(price x 1000) with a 7.935 round-trip test; golden memo fixtures GF-01 and GF-02 and the `div_half_up` property test. cfg registry: tables, Phase 0 and 1 keys (cfg.geo.radius_m, cfg.day.checkout_earliest_time, cfg.release.min_version) with bounds and exclusion constraint | Data lead, API lead | Test shows 7935 stored and 7.935 returned (T-0-02, T-0-08); registry rejects radius 0 and two open rows for one key (T-0-60..62) | T-0-02, T-0-08, T-0-60..62 |
| D8, Tue 20 Oct | Partition-creation job and default partitions with an alert on rows landing in them; safe_div and target-at-least-zero CHECKs. Contract fixtures round-trip in TypeScript and Dart (250) and the enum-drift test. Roles and immutability: `app.immutability_policy` and the role map skeleton, so `web_ro` cannot read `app` and an UPDATE outside the allow-list raises 42501 on every capture table | Data lead, App lead, API lead | Job output lists 3 months ahead (T-0-03); `safe_div` test (T-0-04); fixture report (T-0-41); the iterating privilege test (T-0-07) | T-0-03, T-0-04, T-0-07, T-0-41 |
| D9, Wed 21 Oct | cfg audit immutability (REVOKE plus trigger) and the code-versus-registry drift test. The 14 dependency rules at the database (doc 19 s2.6b). Flag hygiene report. PgBouncer conformance: no named prepared statements, no LISTEN on 6432, `SET LOCAL` inside transactions. Check quota ticket status; file any missing request | API lead, infra lead | Audit table refuses UPDATE; drift test red on a planted literal (T-0-63, T-0-64); a write that breaks a rule is refused around the API (T-0-157); T-0-144 and T-0-51 reports | T-0-63, T-0-64, T-0-157, T-0-144, T-0-51 |
| D10, Thu 22 Oct | The dw inventory file generated from doc 16 and compared with the document (T-0-156); the nightly job DAG on stub jobs (refuses to run without `import_run.status = reconciled` on a wave night; a killed chunk resumes identically; T-0-50); the capture-to-dw lint and the jsonb lint in REPORT-ONLY mode (T-0-150, T-0-151; blocking from the 1b exit, D-558). Baseline-pack session 2 (screenshots of all 37 web pages; Excel export of every report for 3 pilot zones for one month; AMO and TSO flows). Demo 0b and exit review | Data lead, SRE, QA lead, delegate | Generated file equals the document; job log; lint reports with the two lists; index of captured items; signed 0b exit report | T-0-50, T-0-150, T-0-151, T-0-156, T-0-49 |

All 14 gates of the 0b family have a day and an owner above; T-0-05 and T-0-09 moved to 1b with the ingest registry load and the DQ rules, and the ratchet T-0-100 to 1c (D-558, G-qa-95), so 0b carries 14 gates in 5 days instead of 18. If D9 or D10 slips by a day the 0b exit slips with it and the delegate sees it on the schedule; no gate is dropped to hold the date (the order of reading the plan is s2.5: a gate is moved only by an amendment row, doc 20 s3).

Week 3 (25 to 29 October) is 0c. The first two weeks end with a seeded database, a contract that compiles in two languages, green pipelines and a lab; they do not contain any selling UI.

Proved by: T-0-40..49, T-0-01..04, T-0-60..64, T-0-70..76.

### 3.1 Phase 1 — Vertical slice (4.5 weeks; 1a, 1b, 1c)

| Item | Content |
| --- | --- |
| Goal | One SR, one day, offline to synced to one dashboard tile, with exact reconciliation, inside the battery and size budgets |
| Entry | P0 exit; baseline pack index complete (D-142); two pilot SR accounts on staging; an RPP02N-class printer in the lab; D-20 and D-35 answered or defaults recorded |
| Scope (F-ids) | F-SYS-006..009, 011, 015..019, 023, 025..027, 033..034, 036, 045..046 (19); F-SR-001, 003, 005, 008..011, 013, 016..017, 019, 023, 025, 028, 034, 049 (16); F-WEB-045; F-API-005..006, 010..012, 014, 018, 028 (8); plus pinned F-SR-060 (start-call prompt), F-SYS-047 (server-generation re-sync), F-SYS-048 (poison-row isolation), F-SYS-049 (trusted time), F-SYS-050 (telemetry headers), F-API-040..042 (config delta, ack, public) (counts: s3.00, which wins where it differs) |
| Dependencies | Memo samples (D-76, D-158) for 1a golden prints; the slice sells cigarette SKUs only, so the lighter and match unit question (pieces, boxes or dozens; D-16, due 2a) does not block it (G-14-06) |
| Effort band | 36 to 45 person-weeks (4.5 weeks after the D-515 re-baseline) |

| Document | Lands in Phase 1 |
| --- | --- |
| 15 | parity evidence and message keys for the 1a screens; online and offline class of each F-id |
| 16 | visit, memo, memo_line, geo_fix, attendance_event, ingest_registry, sync_batch v2, route_day; dw base with the dirty-key queue and the first tile |
| 17 | local store, capture rules, triggers T1 to T8, wire contract, reconciliation screen, printing and golden memo, budget protocol |
| 18 | load model rebased on docs/22 (D-125); k6 smoke on every main build; first expand and contract rehearsal |
| 19 | minimal console: radius at global and territory, check-out time, min_version, with bounds, reason, audit and a reach widget (D-90) |
| 20 | airplane-mode patrol, golden screens review, coverage floors and mutation, zero-chatter check, battery protocol run 1 |
| 21 | device binding on shared phones, offline unlock (Argon2id verifier), batch-forgery negative tests, rate limits, log redaction, trusted time |

| Code | Demo | Exit (checkable by hand) | Gates (doc 20 s3 placement) | Confirm |
| --- | --- | --- | --- | --- |
| 1a | On a lab phone in airplane mode the delegate unlocks the app, opens a route and an outlet, passes the geo check, enters a cigarette sale, prints a memo and kills the app mid-sale; the draft is intact on relaunch | Outbox invariant and kill tests pass; golden memo print on the RPP02N; home, outlet list, geo check, sale, review and print screens in Bangla and English compared frame by frame with the baseline recording and each difference marked accept or fix | T-1-10, T-1-20, T-1-24, T-1-27, T-1-35, T-1-41, T-1-71, T-1-74, T-1-76, T-1-90, T-1-120..123, T-1-141 | D-20, D-35, D-76, D-158 |
| 1b | Airplane mode off: rows are acknowledged within 60 seconds with no tap; the reconciliation screen shows device equals server; a duplicate batch changes nothing | Property and fuzz run of 10,000 shuffled, duplicated and partial batches converges to one server state; business-date rollover at 23:55 Dhaka lands on the right date; batch replay returns the stored response; one poison row does not stall the rest | T-1-04..06, T-1-13, T-1-21..23, T-1-25, T-1-26, T-1-28..32, T-1-34, T-1-38, T-1-39, T-1-51..56, T-1-70, T-1-72, T-1-73, T-1-75, T-1-79, T-1-151, T-1-152, T-0-05, T-0-09, T-1-153, T-1-154, T-1-156, T-1-157, T-0-150, T-0-151 (both blocking from this exit) | D-67 |
| 1c | The delegate performs the whole script: login, airplane mode, sale, print, sync, tile; then changes the radius to 150 m in the console and sees the next visit use it (T-1-47) | One dw tile shows the sale; sync-health tile v1 equals the seeded fuzz day; zero-chatter check (one hour idle: zero requests); battery and data protocol run 1 recorded in docs/perf; APK at most 30 MB per ABI; expand and contract rehearsal under k6 load with zero 5xx | T-1-01..03, T-1-33, T-1-36, T-1-37, T-1-40, T-1-42..47, T-1-60..65, T-1-77, T-1-78, T-1-100..102, T-1-105, T-1-106, T-1-150, T-0-100 (ratchet), T-1-155, T-1-158 | D-66, D-245, D-523 |

Gaps closed (47 register gaps: 14 blocker, 26 major, 7 minor; plus G-14-06 at 1a). Blockers: G-data-08 (memo number), G-sync-02 (printed layout) at 1a; G-data-06 (quarantine, conflict, registry), G-fraud-02 (clock method), G-scale-01 (batch replay), G-sre-01 (DR re-sync), G-sre-02 (submit before rows), G-sre-03 (poison row) at 1b; G-analyst-01 (memo-grain fact), G-data-07 (dw layer), G-scale-02 (aggregation strategy), G-qa-05 (reference devices), G-sync-01 (shared-phone binding), G-cfg-01 (admin permission model) at 1c. By sub-milestone: 1a 9, 1b 15, 1c 23.

**Exit check a non-engineer can run.** The delegate performs the Phase 1 demo unaided from the one-page script (login, airplane mode, sale, print, sync, tile, radius change); reads the perf file and sees every battery and data number within budget on the primary phone; sees the APK size; every Phase 1 gate is green on the gate report; open gaps carried into Phase 2 have owners.

**Deferred.** Stock, credit, QC, DRP, offers, edit, zero-sale variants beyond the basic one, outlet requests, photos, AMO and TSO, programmes, Managed Redis, the full console. Constraint coverage of the phase is listed in s9.4.

Proved by: T-1-01..04, T-1-20..35, T-1-40, T-1-41, T-1-47, T-1-51, T-1-52, T-1-60..69.

### 3.2 Phase 2 — Full SR day (9 weeks; 2a, 2b, 2c, 2d, 2e)

| Item | Content |
| --- | --- |
| Goal | The complete SR selling day, including promotions, QC and rounding, outlets, geo rails and day close, so that a pilot memo can match Apsis to the paisa |
| Entry | P1 exit; promotion catalogue and rules from the business (D-33, Q13; an ENTRY condition of 2a, without it 2a cannot exit and the pilot cannot start); stock-return flow decision (G-feat-02); edit-reason texts (D-200) |
| Scope (F-ids) | F-SYS-010, 012..014, 020..022, 024, 028..031, 035, 037 (14); F-SR-004, 006..007, 012, 014..015, 018, 020..022, 024, 026..027, 029..033, 035..040, 048, 050 (26); F-ADM-027, 030; F-API-007..008, 027, 029..031, 034, 037 (8); moved here by D-143: F-ADM-016 (offer and promotion master) and the sale-side promotion, DRP/slide, QC deduction and rounding rules; pinned F-SR-051..054, 056..059, 061, 063..066 (stock return, cash deposit, returns and damaged goods, Sale History, SKU Target and Achievement, visit outcome, memo void, day exception, price compliance, offline day start, device-health line, route picker, reprint with duplicate marker). F-SR-055 (Outlet Points) lands in 5a and F-SR-062 (free sample) in 5b. The sub-milestone placement of the pinned ids is this plan's reading; doc 15 s1 owns it (counts: s3.00, which wins where it differs) |
| Dependencies | 2a needs the promotion catalogue; 2d needs the privacy answer for radio environment (D-110); 2e needs the dress-rehearsal pilot SRs; the distribution-channel answer (D-10, D-562) is due at 0c and 2e proves the install (T-2-164) |
| Effort band | 72 to 90 person-weeks (2a 20 to 25, 2b 8 to 10, 2c 12 to 15, 2d 12 to 15, 2e 20 to 25 after the D-515 re-baseline) |

| Document | Lands in Phase 2 |
| --- | --- |
| 15 | rules now stated for sale, memo, credit and dues, QC, DRP/slide, stock, outlet requests, geofence and force sale; message catalogue for SR strings; Bangla string review |
| 16 | memo v2 with deductions and rounding, qc_entry_line, drp, due_collection and allocation, stock_movement, outlet requests and photos, risk_signal, memo_void, day_exception; DQ rules |
| 17 | commit semantics, family atomicity, media queue and SAS upload, event ledgers, updater, stale-bundle policy, printing for seven memo kinds, upgrade matrix |
| 18 | edge fallback hostname, replay-cache write ordering, push jitter, version-scoped hold, change-freeze windows, support visibility headers |
| 19 | rails: bounds, two-person approval, canary, delayed apply, anomaly watch, kill switches, revert; FCM urgent path |
| 20 | full scripted field day on three devices, memo parity sheet, rounding and promotion corpus, dogfood week, dress rehearsal |
| 21 | mock invariant, radio-environment capture, outlet-location drift rules, fraud signals visible to supervisors, edit-rule and price-recompute tests, /cso audit |

| Code | Demo | Exit (checkable by hand) | Gates (doc 20 s3 placement) | Confirm |
| --- | --- | --- | --- | --- |
| 2a | Delegate re-enters five baseline memos (cash, credit with partial payment, with offer, slide with QC, zero sale) and prints; each total equals the photographed baseline | Printed memo parity: at least 25 baseline memos re-entered, totals equal to the paisa, layout diff approved line by line, stock memo and summary included; rounding and promotion corpus in CI (every group has at least 3 fixtures) | T-2-05, T-2-07, T-2-20, T-2-36..39, T-2-41, T-2-42, T-2-71, T-2-90, T-2-91, T-2-103, T-2-120..124, T-2-131..133, T-2-136..138, T-2-161 | D-16, D-17, D-18, D-19, D-32, D-33, D-34, D-38, D-58, D-166, D-194, D-248 |
| 2b | Edit a memo inside the geofence before QC and see the superseding memo count once; after QC the edit is refused; void with a cancel slip; collect a due (whole memo); stock return | Edit, supersede, void and reprint rules; due collection writes a due_collection row and recomputes the outlet due; attendance edge cases; SR strings complete in Bangla and English | T-2-01, T-2-02, T-2-06, T-2-09, T-2-21, T-2-70, T-2-92..94, T-2-101, T-2-102, T-2-125, T-2-143 | D-86, D-200, D-206 |
| 2c | New-shop request with GEO and photo captured offline; photo at most 150 KB uploads on Wi-Fi; the pending state shows on the SR phone | Outlet new, close and info change with cluster and route; media pipeline (compress, queue, SAS, Wi-Fi-first, evidence fallback after 6 hours); outlet request lifecycle on the SR side | T-2-08, T-2-13, T-2-22, T-2-26, T-2-30, T-2-53, T-2-72, T-2-126, T-2-135 | D-510 |
| 2d | A fake-GPS app and a mock-provider script on the lab phone: the fix is flagged mock, never geo-valid, the SR sees the warning; the delegate loosens and then reverts a radius with a second approver | Geo gate with mock invariant; force sale with reason and photo; location-change rules; config rails proven (two-person, canary, revert, blast-radius preview); anti-spoof end to end with real tools | T-2-03, T-2-10..12, T-2-14, T-2-47, T-2-55, T-2-60..69, T-2-75..77, T-2-79, T-2-95, T-2-96, T-2-127, T-2-160, T-1-11, T-1-12, T-2-162, T-2-165, T-2-173, T-2-174 | D-09, D-91, D-94, D-95, D-110, D-519, D-527, D-563, D-571, D-588 |
| 2e | Dress rehearsal: 2 pilot SRs and 3 staff run a real beat with both apps; the evening comparison shows memo count, STD, value and dues against the Apsis summary screen | Full scripted field day on three devices with every row synced within 3 minutes of the final connectivity; dogfood week (10 staff, 5 trading days, crash-free sessions at least 99.5 percent, zero data-loss tickets); Sales Submit and check-out as outbox events; min_version drill; zero sync-loss discrepancies; Bangla strings signed | T-2-04, T-2-23..25, T-2-27..29, T-2-31..34, T-2-40, T-2-43..46, T-2-48, T-2-49, T-2-51, T-2-52, T-2-54, T-2-56..58, T-2-73, T-2-74, T-2-78, T-2-97..100, T-2-128..130, T-2-140, T-2-141, T-2-150, T-2-151, T-2-153, T-2-154, T-2-155, T-2-156, T-2-157, T-2-158, T-2-159, T-2-134, T-2-163, T-2-164, T-2-166..170, T-2-172, T-2-176 | D-13, D-70, D-80, D-115, D-146, D-584, D-589 |

Gaps closed (96: 8 blocker, 66 major, 22 minor). Blockers: G-feat-13 (promotion engine), G-man-002 (memo total omits slide and QC), G-sync-04 (rounding) at 2a; G-feat-02 (stock return) at 2b; G-cfg-04 (blast radius) at 2d; G-sync-03 (stale bundle), G-scale-03 (bundle path for the morning storm), G-sre-04 (wave first morning) at 2e. By sub-milestone: 2a 22, 2b 13, 2c 12, 2d 20, 2e 29.

**Exit check a non-engineer can run ("pilot-ready").** The gate report shows the S4 smoke at about 2,000 rows per second, the field-telemetry sheet of the dogfood week against the role budgets and the immediacy figures (SH-19 to SH-23). An SR runs a real beat end to end offline on a reference phone (videos of the scripted day); the photographed baseline memos and the new prints are laid side by side and equal to the paisa; the dress rehearsal sheet shows zero sync-loss discrepancies; the perf files show battery and data within budget on all three reference phones; the security audit has no open high finding; the delegate changes the radius in the console and sees a phone pick it up.

**Deferred.** AMO and TSO apps; web reports beyond tile, sync-health v1 and the minimal console; Astha, Diamond League, Superstar, free samples, remaining promotion groups beyond those visible in the baseline memos (5b); target revisions.

Proved by: T-2-01..04, T-2-10..19, T-2-20..34, T-2-36..49, T-2-51..57, T-2-60..75.

### 3.3 Phase 3 — Supervisors (5 weeks; 3a, 3b)

| Item | Content |
| --- | --- |
| Goal | AMO and TSO flavours of the same app and the final-submit loop that closes a zone-day |
| Entry | P2 exit at least through 2d (2e is needed for the pilot); rubric and questionnaire definitions (cfg.rubric.*, cfg.survey.tso_visit_query_questions); the reopen decision (D-55) for 3b |
| Scope (F-ids) | F-AMO-001..017, 020..030, 032..036 (33); F-TSO-001..007, 010..015, 017..018 (15); F-API-009, 016, 019..020, 023..025, 032 (8); pinned F-AMO-037..043 (cover, Exceptions, supervisor day, SS designation, acting-for, DH proxy, Survey), F-TSO-021..026 (Final Submit preview, OTP panel, temporary password, Settings, radius edit or propose, delegation), F-API-039, 043, 044 (counts: s3.00, which wins where it differs) |
| Dependencies | Maps provider answer (D-08) for Team Location and Update Base; whose dues an AMO sees (D-37); Submit % (of logged-in) basis (D-45) |
| Effort band | 40 to 50 person-weeks (3a 24 to 30, 3b 16 to 20) |

| Document | Lands in Phase 3 |
| --- | --- |
| 15 | AMO and TSO F-ids with parity evidence, the online and offline matrix for TSO (read snapshot, queued writes, online-only Final Submit and maps) |
| 16 | supervisor_day, call_assessment, distribution_check, leave, visit plan, feedback, final_submit with route snapshot, dues for AMO |
| 17 | zone-wide AMO bundle with paging, TSO login snapshot and delta, shared-phone and device-replacement flows, TSO logout guard, maps client rules |
| 18 | bundle size caps and paging for a 54-route zone, final-submit load at 17:00 to 19:00 |
| 19 | role x menu data for the supervisors, TSO radius propose mode, cover and exception approval settings |
| 20 | AMO and TSO offline flows in the lab, the SR to AMO to web approval chain, usability session with 3 AMOs and 2 TSOs |
| 21 | separation of duties, device lifecycle, final-submit and reopen audit, Exceptions screen scoping |

| Code | Demo | Exit (checkable by hand) | Gates (doc 20 s3 placement) | Confirm |
| --- | --- | --- | --- | --- |
| 3a | An SR phone raises a new-shop request offline; an AMO phone verifies it; a web approver approves; the SR's next bundle delta shows the outlet active; an AMO of another zone cannot see it | Control call, joint call, Team Location with last-seen age, SR Stock, verification queue, Update Base, AMO sale and Sales Submit with the nine reconciliation types; Exceptions list scoped to the AMO's zone | T-3-11, T-3-12, T-3-20..22, T-3-24, T-3-36, T-3-41, T-3-70, T-3-73, T-3-77, T-3-90, T-3-91, T-3-100, T-3-107, T-3-120..124, T-3-151, T-3-153, T-3-156, T-3-160 | D-08, D-37, D-43, D-51, D-52, D-85, D-169, D-172, D-187, D-195 |
| 3b | A TSO final-submits a zone and the sync-health page shows it; a second attempt is refused with the already-submitted message; a row synced after Final Submit shows in the late-data list | The same APK renders SR, AMO and TSO homes by token role (Bangla and English goldens reviewed); Final Submit preview at Get Sales Data; Bikroy Joma and Login lists; leave, visit plan and Visit Query queued with a sync-state badge; logout refused with pending rows; the task chain (the AMO assigns, the SR lists and resolves offline, the TSO assigns from a visit) and the leave chain (the TSO applies on the phone, the DMO approves on the web) each run end to end (D-532); a Sales Submit void by the TSO and by L1 support with TSO confirmation (D-539); a disabled user's captured rows still upload within the grace (D-551) | T-3-01, T-3-02, T-3-05, T-3-10, T-3-23, T-3-25..28, T-3-40, T-3-42..44, T-3-60, T-3-61, T-3-71, T-3-72, T-3-78, T-3-79, T-3-92, T-3-125..128, T-3-150, T-3-152, T-3-154, T-3-155, T-3-157..159 | D-29, D-45, D-55, D-177, D-198, D-262 |

Gaps closed (58: 0 blocker, 29 major, 29 minor): 3a 34, 3b 24.

**Exit check a non-engineer can run.** The delegate performs the verification chain live (SR request, AMO verify, web approve); a TSO final submit closes a zone on the sync-health screen; the AMO and TSO reconciliation screens show their row sets; the usability session report lists every failed task with a ticket or an "accept" (task success at least 90 percent is a blocking gate at 7b entry, not at this exit).

**Deferred.** 3D maps; a distribution-house login (D-304); final-submit delegation and auto-close stay built-but-off (D-262).

Proved by: T-3-01, T-3-02, T-3-20..28, T-3-40..44, T-3-60, T-3-61, T-3-70..73.

### 3.4 Phase 4 — Web dashboards and reports (6.5 weeks; 4a, 4b, 4c, 4d)

| Item | Content |
| --- | --- |
| Goal | Every dashboard and report is a query on stored dw data, scoped on the server; sync-health is the business's window on launch day |
| Entry | Phases 1 to 3 aggregates in dw; KPI decisions (D-28, D-45 to D-47); Apsis report exports in the baseline pack; read replica provisioned; staging holds a full-size synthetic fleet |
| Scope (F-ids) | F-WEB-001..019, 021, 024..025, 027..028, 031..032, 035..036, 038..042, 044, 047 (35); F-ADM-033, 037; F-API-013, 015, 017, 020 (4); pinned F-WEB-050..056 (Web Entry, web Final Submit, web QC, DS-RRS, AMO Call Report, DSS, Route-wise Memo), F-WEB-057 (Exceptions), F-WEB-058 (dues ageing), F-WEB-059 (distribution-house settlement view). F-WEB-048 and 049 (Astha Web Entry, Loyalty report) land in 5a with their programme (counts: s3.00, which wins where it differs) |
| Dependencies | Sample .xlsx files and the QC PDF (D-191, MQ-47) for 4b; Entra availability (D-114) for 4c; Azure quota granted (D-133) for 4d |
| Effort band | 52 to 65 person-weeks (4a 16 to 20, 4b 16 to 20, 4c 8 to 10, 4d 12 to 15 after the D-515 re-baseline) |

| Document | Lands in Phase 4 |
| --- | --- |
| 15 | the 37-page parity map and the 41-page union, report column sets, labels and aliases |
| 16 | dw dimensions, daily and month aggregates, snapshots and restatement log, KPI SQL, ReportQuery, PII views and bi_reader grants |
| 17 | none new (bundle fields for dashboards already exist) |
| 18 | replica and Redis read path with "as of" stamps, sync-health degraded mode, burn-rate alerts, load tests S1 to S10, DR and restore drills |
| 19 | role x menu matrix as data, back-office tools (Web Entry rules, Sales Plan edit, wholesale marking, SR Device OTP panel, outlet approval) |
| 20 | page e2e with three personas and scope negatives, report parity against the baseline Excel files, alert fault injection, 7 nightly reconciliations |
| 21 | RLS performance budget, IDOR sweep, export watermark and log, PII row budgets and reveal log, web headers and MFA |

| Code | Demo | Exit (checkable by hand) | Gates (doc 20 s3 placement) | Confirm |
| --- | --- | --- | --- | --- |
| 4a | A Wing Manager persona sees only the own wing; the sync-health page shows target, logged-in, uploaded and submitted routes for the seeded day with an "as of" stamp; Daily Tracking buckets 100, 90 to 100, 80 to 90, below 80 | Per-surface dashboards read dw only; Submit % (of logged-in) and Day-completion % both shown with their basis; a non-working day shows dashes, not zeros | T-4-01, T-4-43, T-4-44, T-4-47, T-4-58..60, T-4-77, T-4-90, T-4-101, T-4-152, T-4-153, T-4-154 | D-28, D-46, D-185, D-249 |
| 4b | The delegate runs three baseline-zone reports for a month and compares totals with the baseline Excel exports; Get Excel equals the on-screen query | Every docs/09 and manual report is a ReportQuery on dw with a documented explanation for each difference from the baseline; Excel and PDF export with formula sanitiser and watermark | T-4-03, T-4-40..42, T-4-100, T-4-102, T-4-104, T-4-121, T-4-122, T-4-150, T-4-151, T-4-168 | D-47, D-54, D-191, D-538 |
| 4c | Web Entry and web Final Submit with an audited void; the outlet approval panel; the SR Device OTP panel; an AMO cannot see an SR phone; an export writes a log row | PII budgets and reveal log; role x menu matrix; Final Submit lock rules; the web back-date banner and audited unlock grant | T-4-02, T-4-14, T-4-61..65, T-4-70..76, T-4-108, T-4-120, T-4-123..125, T-4-165, T-4-166, T-4-167, T-4-169 | D-40, D-97, D-108, D-114, D-182, D-207 |
| 4d | The load report S1 to S11 on a production-sized staging window: S1 at 1,000 and at 9,850 users, then S2, S4 (the 8,000 rows per second ramp and the 1.5 times fleet of 12,750 SRs), S5 with S4, S6, the 12-hour soak S8, S9 and S11; a region-failover drill with the cross-region lag shown; a restore drill with row-count and audit-chain verification | Every blocking scale scenario passes (the exit script reads the whole S1 to S11 report, not S1 alone); bundle p95 at most 2 seconds and zero lost rows at 9,850 users and at the 1.5 times fleet; the database role used by the web has no SELECT on transactional tables; alerts reach a phone | T-4-45, T-4-46, T-4-51..57, T-4-140, T-4-155, T-4-156, T-4-157, T-4-158, T-4-159, T-4-160, T-4-161, T-4-162, T-4-163, T-4-164, T-4-173, T-4-174 | none |

Gaps closed (42 register gaps: 2 blocker, 25 major, 15 minor; plus G-14-07 at 4d). Blockers: G-analyst-02 (aggregates current-state only: no target dimension, no as-reported snapshot, no restatement log) at 4a; G-man-086 (Delete Section Data collides with idempotent sync) at 4c. By sub-milestone: 4a 12, 4b 12, 4c 14, 4d 4.

**Exit check a non-engineer can run.** Open the dashboard as the Wing Manager persona and try another wing's address (refused); the national dashboard opens in well under a second on the full-size synthetic fleet; each of the reports matches the baseline Excel file or carries an approved explanation; the S1 to S11 report (not S1 alone) shows the bundle p95 and zero lost rows at 9,850 users and at the 1.5 times fleet, the 17:00:00 spike and the cross-region replica lag within bounds; the delegate receives a test alert on a phone.

**Deferred.** Programme reports (Astha, Campaign Gift Redemption, Superstar) stay with P5; DS-RRS print layout until a sample exists (MQ-45); a self-service BI tool (bi_reader exists for it).

Proved by: T-4-01..03, T-4-14, T-4-40..47, T-4-51..59, T-4-60, T-4-70..76.

### 3.5 Phase 5 — Programmes (5 weeks; 5a, 5b, 5c)

| Item | Content |
| --- | --- |
| Goal | Astha, Diamond League, Superstar, free samples, the remaining promotion groups, targets with approvals, tasks and leave |
| Entry | 2a promotion engine live; which programmes are live and their rules (Q13, D-41); target split and approval levels (D-31) |
| Scope (F-ids) | F-SR-041..047 (7); F-AMO-018..019, 031 (3); F-TSO-008..009, 016, 020 (4); F-WEB-020, 022..023, 026, 029..030, 034, 037, 046 (9); F-ADM-014..015, 017..021, 035..036 (9, F-ADM-016 moved to 2a); F-API-021..022, 026 (3); pinned F-SR-055 (Outlet Points), F-SR-062 (free sample), F-WEB-048 (Astha Web Entry), F-WEB-049 (Loyalty report) (counts: s3.00, which wins where it differs) |
| Dependencies | Earning rules, cash-cap scope and catalogue (D-41); Astha gift save and lock rules (D-192); approver chain (D-31, D-179) |
| Effort band | 40 to 50 person-weeks (5a 16 to 20, 5b 12 to 15, 5c 12 to 15) |

| Document | Lands in Phase 5 |
| --- | --- |
| 15 | Astha, Diamond League and Superstar rules, Outlet Points and the redemption basket flow |
| 16 | target_set and approvals, variant level, Astha tiers, loyalty ledger with source id and expiry, redemption batches and lines, Superstar tables |
| 17 | queued programme flows (redemption, gift photo, task resolve) |
| 19 | effective-dated promotion rules, loyalty rate safety, target and tier keys |
| 20 | programme flows offline, promotion corpus complete, approval chain end to end, the negative-target fixture |
| 21 | loyalty computed on the server only, fraud job recall, dues rules |

| Code | Demo | Exit (checkable by hand) | Gates (doc 20 s3 placement) | Confirm |
| --- | --- | --- | --- | --- |
| 5a | Astha screen with quarter and month filters; a Diamond League redemption offline with points deducted; one gift photo per outlet | Ledger equals redemptions equals photos after a fuzzed day; a replay never double-credits | T-5-40, T-5-61, T-5-62, T-5-70, T-5-100, T-5-120, T-5-121, T-5-123 | D-41, D-192, D-561 (entry) |
| 5b | Superstar enrolment and slabs; a free-sample capture; each remaining promotion group applied at sale | Every live group has at least 3 golden memos; Discount Report totals equal the corpus sums | T-5-41, T-5-60 | none |
| 5c | A target set with approval chain; a negative target refused at entry; the Astha report shows a dash for the -20 fixture | Requester differs from approver per level; the -37,500 percent defect cannot be reproduced | T-5-01, T-5-10, T-5-42, T-5-43, T-5-63, T-5-71..73, T-5-90, T-5-122 | D-31, D-179 |

Gaps closed (18 register gaps: 1 blocker, 9 major, 8 minor; plus G-14-03 at 5a). Blocker: G-feat-20 (Diamond League earning rules absent) at 5a. By sub-milestone: 5a 11, 5b 2, 5c 5.

**Exit check a non-engineer can run.** Points and gifts reconcile on the ledger report; a promotion applies at sale and prints correctly; a target revision routes through its approval levels; the delegate enters the negative-target fixture and sees it refused.

**Deferred.** Suggested order quantity (hook only, D-300); loyalty expiry rule until the business gives it (D-41).

Proved by: T-5-01, T-5-10, T-5-40..43, T-5-60, T-5-61, T-5-70..72.

### 3.6 Phase 6 — Admin and master data (4 weeks; 6a, 6b, 6c)

| Item | Content |
| --- | --- |
| Goal | The business can run the system without engineering: every master entity editable with audit, the full config console, device and release control |
| Entry | The 58-entity list (doc 15 s7); permission bundles (D-91); console pages P1 to P18 (doc 19 s5.2); a minimal 6b subset is a 7b entry condition |
| Scope (F-ids) | F-ADM-001..013, 023..026, 028..029, 034 (20); F-API-035, 038; pinned F-ADM-038..055 (console pages P1 to P18; the minimal set of 1c is a subset), F-ADM-056..079 reserved for back-office tools (counts: s3.00, which wins where it differs) |
| Dependencies | Audit and photo retention answers (D-113, D-23, D-132) |
| Effort band | 32 to 40 person-weeks (6a 12 to 15, 6b 12 to 15, 6c 8 to 10) |

| Code | Demo | Exit (checkable by hand) | Gates (doc 20 s3 placement) | Confirm |
| --- | --- | --- | --- | --- |
| 6a | CRUD for the 58 entities with audit rows; CSV round trip for outlets, routes, assignments and targets; a TSO marks wholesale outlets in bulk | Future-dating rules for prices, targets and the calendar; back-date grants as maker-checker; data_void | T-6-05, T-6-40, T-6-62, T-6-63, T-6-120, T-6-150 | D-42 |
| 6b | The sponsor changes a zone's radius with a reason; a second approver approves; the lab phone in that zone shows it within 15 minutes; the next visit stores radius_m_used; the reach view shows 100 percent; one click reverts | All console pages, risk classes, scheduled values, reach view and the audit viewer; rollback to a version | T-6-01, T-6-10, T-6-41, T-6-51, T-6-60, T-6-61, T-6-70..73 | D-113 |
| 6c | A business admin issues a device OTP, replaces a device, sets wave_pct for a release; a point-in-time restore of staging is verified | Device and OTP console, release console (min_version, blocked versions, waves, flags), retention and archival jobs with manifest | T-6-42, T-6-43, T-6-152 | D-23, D-132 |

Gaps closed (14: 0 blocker, 7 major, 7 minor): 6a 9, 6b 3, 6c 2.

**Exit check a non-engineer can run.** A business admin completes 12 operational tasks from a script with no engineer present (add an SR, assign a route with dates, issue a device OTP, replace a device, reset a password, approve an outlet request, set and revise a target, change a radius, publish a holiday, set wave_pct, read the audit log), each within its time box; every admin write has an audit row the delegate can find; a restore has been performed and verified.

**Deferred.** Wholesale order (bex) and back-margin screens (D-42, Q17).

Proved by: T-6-01, T-6-40..43, T-6-51, T-6-60, T-6-61, T-6-70..72.

### 3.7 Phase 7 — Migration, pilot and cutover (10+ weeks; 7a, 7b, 7c, 7d, 7e)

| Item | Content |
| --- | --- |
| Goal | Move 8,500 reps with no lost selling day; Apsis stays the system of record until a wave switches (D-144) |
| Entry | 7a: the Apsis dump delivered in the doc 16 s12 shape; counsel's answers on residency, PII and the location notice (D-05, D-107, D-120; doc 21 s4.6) on file, because real owner names and phone numbers land at 7a and real employee location at 7b, or the import is pseudonymised or synthetic only and 7b does not start (D-570, T-7-160); the production server created with zone-redundant HA, geo-redundant backup ON from creation and 1,024 GiB (D-568, D-569). 7b: 2a to 2e, 3a, 3b, 4a, 4c, a minimal 6b and the OTP panel exited; baseline pack captured; pilot SR consent and incentive agreed and the consent screen showing the D-120 text; the FCM answer (D-09) recorded or the 15-minute push-off bound accepted (D-563); Front Door Premium with Private Link running with the managed rules in Log mode (D-573, T-7-162); the 10-hour soak of the pilot build passed (T-2-176); the pilot pre-bind readiness drill with the late delta and the straggler sheet (T-7-163, T-7-164); no unsigned row in the Parity Exceptions Register (T-7-158). 7b-2 programme pilot: 5a exited, 3 to 5 programme-tier routes for 10 trading days (T-7-165, D-561). 7c: 7b passed; 4d (with the timed geo-restore drill T-4-174) and 6c exited; D-127, D-149 answered; the Apsis contract end covers the planned waves (D-555); helpdesk staffed; pre-bind plan signed; install success on the pre-bind day at least 95 percent (T-7-166); the per-wave rollback statement signed (T-7-168). Programme routes: P5 exits and 7b-2 passes before any route with programme-tier outlets is bound (D-306, D-561) |
| Scope (F-ids) | F-SYS-039..044 (6); F-ADM-031..032; F-API-033; the importer and reconciliation features of doc 16 s12 and the cutover tooling of doc 20 s7 (counts: s3.00, which wins where it differs) |
| Effort band | 40 to 70 person-weeks (operations peak at each wave) |

| Code | Demo | Exit (checkable by hand) | Gates (doc 20 s3 placement) | Confirm |
| --- | --- | --- | --- | --- |
| 7a | The importer runs on the full dump; the per-zone control table (outlets, month-to-date STD, memo count, outstanding dues, loyalty balances) matches the Apsis reports; a second run changes nothing; an import is rolled back | Row counts per table match the dump dictionary; quarantine at most 1 percent per table with every reason counted; opening balances spot-checked on 30 outlets in 3 zones | T-7-01, T-7-02, T-7-06, T-7-58, T-7-74, T-7-77, T-7-80, T-7-100, T-7-109, T-7-145, T-7-160 | D-119, D-153, D-569, D-570 and D-05, D-107, D-120 (the legal answers are due at the 7a entry) |
| 7b | The 09:30 daily sheet: for each pilot route the memo count, STD, value and dues in both systems, each difference with a category (A double entry, B rule difference, C sync loss, D Apsis-side change, E business date) | Pass rule: 10 consecutive trading days with zero category C, exact memo count, STD, dues and value after A, D and E are explained and B is closed by a recorded decision; rollback drills timed; support readiness proved | T-7-20, T-7-81, T-7-82, T-7-90, T-7-91, T-7-140..144, T-7-150, T-7-153, T-7-154, T-7-158, T-7-159, T-7-162..165 | D-126, D-147, D-148, D-152, D-154, D-549, D-592 |
| 7c | War room on wave day: the small DELTA bundle (about 25 KB a phone, not a password storm and not "refresh plus 304", D-557) on the pre-bound phones, Login % and Submit % (of logged-in) against the pre-switch Apsis figures, final-submit coverage by 21:00 | Day-one checklist signed at T-1 by the four owners; rollback triggers read only after submit settle; five clean post-wave reconciliation days | T-7-10, T-7-51..55, T-7-59..62, T-7-70..73, T-7-75, T-7-76, T-7-83..87, T-7-151, T-7-152, T-7-155, T-7-156, T-7-157, T-7-161, T-7-166, T-7-168 | D-63, D-93, D-127, D-138, D-149 |
| 7d | Next waves (territory, division, half, rest) each after the previous is stable for 3 trading days | Go/no-go record signed before each wave | T-7-56, T-7-57, plus T-7-84, T-7-86, T-7-87 repeated per wave (doc 20 s10) | none new |
| 7e | Apsis off or read-only; final delta reconciled; credentials rotated or removed; raw dump archived and deleted per policy | Decommission record signed (D-155) | T-7-88, T-7-89 | none new |

Gaps closed (20 register gaps: 4 blocker, 9 major, 7 minor; plus G-14-04 at 7b). Blockers: G-qa-03 (no machine-readable Apsis daily data for the parallel run), G-sec-03 (Apsis password hashes in the dump) at 7a; G-qa-04 (no helpdesk) at 7c, G-sec-01 (Bangladesh data law and residency) at the 7a entry (D-570). By sub-milestone: 7a 8, 7b 7, 7c 5.

**Exit check a non-engineer can run.** Per wave: the readiness checklist is signed by engineering, operations, business and support at T-1; on day 3 login % is at least 95 percent of the pre-switch Apsis login %; Submit % (of logged-in) is not below the pre-switch figure; reconciliation mismatch is at most 0.1 percent of route-days after settle; crash-free sessions at least 99.5 percent; battery complaints at most 1 percent of the wave; no open P1 ticket (all from the quality lens, doc 20 s7). Also per wave: field data and battery (SH-19 to SH-21) and immediacy (SH-22, SH-23) within their thresholds (D-507, D-509). For the phase: the Apsis delta path has run twice before wave 1, the Parity Exceptions Register has no unsigned row, the rollback drill shows retailer dues equal in both systems, all waves are stable and the decommission record is signed.

**Deferred.** Nothing is planned past 7e; an item still open at 7e becomes an open item with an owner. s8 gives the cutover summary.

Proved by: T-7-01, T-7-02, T-7-20, T-7-51..61, T-7-70..75, T-7-80..89.

## 4 Business confirmation schedule

### 4.1 How the schedule works

| Rule | Statement |
| --- | --- |
| Default-stands (D-308) | A MUST-CONFIRM decision never stops the build: the build proceeds on the default in the "Proceeds on" column. An unanswered decision stops the exit of the sub-milestone in the "Due" column (or the wave); the delegate records the answer or "default accepted" with the date, and DECISIONS.md is amended. |
| Who answers | Owner roles: SP sponsor (Asef); DEL sponsor's delegate; SOPS sales operations; FIN finance; LEG legal and compliance; IT AKTCL IT and operations; SEC security owner; TM trade marketing; HR; SUP helpdesk and support lead. A role is the person who can answer, not the person who chases. |
| Evidence (D-312) | Questions that only the live Apsis app, a printed sample or an Excel file can answer are answered from captures made by AKTCL staff with AKTCL accounts. No engineer or agent calls, scrapes or drives Apsis's backend or app. |
| Cadence (ASSUMPTION) | A 30-minute confirmation review every Sunday and Wednesday (sponsor or delegate, PM, tech lead) walks the rows due within 3 weeks; a row due within 1 week with no owner is escalated to the sponsor. |
| Count | 173 decisions: 93 from the plan skeleton, 8 raised by doc 14 (D-300 to D-306 and D-311), 62 raised by docs 15 to 21 and by the round-2 review (s4.2b: 50 from docs 15 to 21 plus D-501, D-502, D-503, D-510, D-514, D-519, D-523, D-527, D-530, D-534, D-538 and D-549) and 10 raised by the round-3 review (s4.2c: D-555, D-560, D-561, D-562, D-565, D-569, D-570, D-584, D-589 and D-592). The questions of docs/13 and of the lenses and critics are mapped to them in s4.3. |

### 4.2 MUST-CONFIRM decisions, ordered by due sub-milestone

| Due | Decision | What the business must answer | Owner | Proceeds on (default meanwhile) |
| --- | --- | --- | --- | --- |
| 0a exit | D-156 | Name the sponsor's delegate per phase and the four readiness owners (engineering, ops, business, support); Q34 | SP | Asef acts as delegate until named; no phase can exit without a named verifier |
| 0c exit | D-101 | Token lifetimes and refresh policy (Q4) | SEC + SP | access 60 min, refresh 30 d sliding and 90 d absolute |
| 0c exit | D-102 | Field-role password policy (8 characters plus deny-list proposed; web 12 characters is parity) | SEC + HR | 8 characters with deny-list for field roles; web policy per docs/09 |
| 0c exit | D-103 | OTP lifetime, retries, and whether the AMO app binds with an OTP (MQ-15) | SOPS + SEC | 4 digits, 120 min, 5 attempts; flow built for all three flavours, enforced for SR first (the AMO manual shows no OTP step) |
| 0c exit (Phase 1 entry) | D-142 | Baseline pack from the current system: full SR day recording, at least 25 printed memos, all web pages, one month of Excel exports for 3 zones (captured by AKTCL staff under AKTCL accounts) | SP + SOPS | Phase 0 proceeds; Phase 1 cannot start without T-0-49 |
| 0c exit | D-164 | Device-binding split: 4-digit view-only OTP (parity) and no re-verify for in-place updates (recommended) | SEC + SP | parity OTP form; re-verify off (see D-80) |
| 0c exit | D-11 | Android floor minSdk 26 and 360 x 640 dp baseline; needs the fleet census (Q31), a hard 0c deadline: the census decides the lab, the battery classes and the 0c exit | IT + SOPS | minSdk 26 |
| 0c exit | D-12 | Reference devices for all budgets: Redmi 9A class, Samsung A03 Core class, Android 8.x with 2 GB (fleet census Q31; hard 0c deadline, D-564) | IT + SOPS | lab of 2 of each plus 2 printers (ASSUMPTION) |
| 0c exit | D-10 | Distribution channel: AKTCL signed APK channel, a verified-developer registration, a Managed Google Play private app or a lightweight MDM; the answer fixes the updater design and the Google developer-verification position (MQ-50, D-562, RK-29) | IT | own signed APK channel, with the three package ids registered as a verified developer if the 0c check says so |
| 1a exit | D-20 | Business-date cutoff at 00:00 Dhaka (Q29) | FIN + SOPS | 00:00 Asia/Dhaka |
| 1a exit (before the first golden print) | D-35 | Memo number: new series <username>-<yyMMdd>-<seq3> or continue the Apsis series (Q5, MQ-56) | SOPS + TM | option A, new series with device blocks of 500 |
| 1a exit | D-76 | Physical 58 mm print samples: cash, credit with partial payment, zero sale, edited, reprint, stock slip, day summary (MQ-57, MQ-58) | SOPS | template contract built from the manuals; golden prints wait for samples |
| 1a exit | D-158 | Printed memo layout: the manuals never show the paper (C-02) | SOPS | same as D-76 |
| 1b exit | D-67 | Local database encryption with SQLCipher (security owner sign-off) | SEC | SQLCipher on; fall back to file-based encryption if APK above 30 MB per ABI or the battery gate fails |
| 1c exit | D-66 | Device binding model on shared phones: users per device 3, devices per user 2 | SOPS + SEC | cfg.auth.max_users_per_device 3, max_devices_per_user 2 |
| 1c exit | D-245 | Do all sales in the Apsis export come through SR/AMO apps (direct distributor billing?) (P-01) | SOPS + FIN | size for 4.5 lakh calls a day regardless |
| 2a entry | D-33 | Live promotion catalogue (about 22 groups) and rules (Q13, MQ-05, MQ-06) | TM | none: ENTRY condition of 2a; build the engine from groups visible in baseline memos |
| 2a exit | D-16 | Lighter box size and Match entry unit (MQ-01); Q8 | SOPS | Lighter piece, Match dozen as base units, report unit per surface |
| 2a exit | D-17 | May an SR type loose sticks, or only pack multiples (MQ-02) | SOPS | stepper step = pack size; typed loose sticks accepted and flagged |
| 2a exit | D-18 | Negative net on a zero sale plus QC; basis of the per-SKU maximum QC (MQ-03, MQ-04) | SOPS + FIN | allow negative net as a credit; maximum QC shown as a taka cap from the bundle |
| 2a exit | D-19 | Memo rounding must equal Apsis to the paisa (gate T-2-41) | FIN + DEL | sum unrounded, round once half up to the paisa |
| 2a exit | D-32 | Which outlets are priced at cc or distributor today (Q46) | SOPS | price type is an outlet attribute: retail = outlet list, wholesale = cc |
| 2a exit | D-34 | QC settlement formula and maximum-QC basis (MQ-03) | FIN + SOPS | settlement = defect sticks x price at capture |
| 2a exit | D-38 | Does the current app record a closed-shop or absent-owner outcome (Q50): parity or addition | SOPS | build outcome codes (IMPROVEMENT) |
| 2a exit | D-58 | ADS, TADS, PADS definitions with a non-zero sample (MQ-27) | SOPS | TADS = target / 14, RADS = remaining / 11, ADS = achieved / elapsed selling days, PADS undefined |
| 2a exit | D-166 | Where automatic (non-DRP) discounts render on Review (MQ-06) | SOPS | store offer_discount separately; add no invented line |
| 2a exit | D-194 | Match priced per piece or per dozen (MQ-01) | SOPS | base unit per category, always labelled; price per dozen stored as a rational |
| 2a exit | D-248 | Base units for lighters and matches (P-04, Q8) | SOPS | as D-16 |
| 2b exit | D-86 | Current behaviour when a retailer cancels after print; cancel paper (Q43) | SOPS | memo_void event with a printable cancel slip |
| 2b exit | D-200 | The other two memo edit reasons (MQ-18) | SOPS | first reason 'wrong SKU'; the other two captured from the live app before 2b exit |
| 2b exit | D-206 | Sale History scope: this SR or all SRs for the outlet | SOPS | all memos for the outlet within the user's scope |
| 2d exit | D-09 | FCM push acceptable for urgent config (Q22, Q28) | IT + SP | cfg.ops.push_enabled false in the pilot; urgent changes ride the next request; the answer is RECORDED before the pilot starts (7b entry) and the published urgent bound is 15 minutes with push off (D-563) |
| 2d exit | D-91 | Config approver roster and break-glass on-call holder (Q23) | SP + IT | two named approvers per area; one break-glass account |
| 2d exit | D-94 | May a TSO change a radius or only propose (Q24) | SOPS | propose (change request) |
| 2d exit | D-95 | Behaviour when an outlet has no coordinates and whether the first photo sets the location (Q7, Q25) | SOPS | force sale with reason no_outlet_location; immediate set only for missing or placeholder location |
| 2d exit | D-110 | Radio-environment capture (cell and Wi-Fi identities) privacy sign-off | LEG + HR | capture on in record mode; use gated on sign-off |
| 2e exit | D-13 | Sentry acceptable for device crash data under AKTCL policy and Bangladesh law (Q36) | LEG + IT | Sentry with PII scrubbing; Azure-only fallback |
| 2e exit | D-70 | May reps sell on a bundle up to 2 days old (yesterday's prices) with a banner (G-sync-03) | SOPS + FIN | stale_max_days 2 |
| 2e exit | D-80 | Re-ask the OTP after an in-place app update (MQ-15) | SEC + SOPS | off; parity (on) selectable for the pilot |
| 2e exit | D-115 | Is call audio recorded (Q15, MQ-55) | SOPS + LEG | camera permission only, no RECORD_AUDIO |
| 2e exit | D-146 | Who signs 'same memo' for retailers (Q37) | SP | sponsor's delegate with one SR and one retailer-facing manager |
| 3a exit | D-08 | Map provider, quota and cost; 3D not required (Q42, MQ-51) | SP + FIN | Google Maps SDK 2D lite; MapLibre fallback |
| 3a exit | D-37 | Whose dues an AMO sees (MQ-17) | SOPS | AMO's own credit memos only |
| 3a exit | D-43 | May the AMO reject; what 'বাতিল' does; route of AMO-created outlets (MQ-29, MQ-31) | SOPS | discard without server call; AMO outlets inherit the picked cluster's route |
| 3a exit | D-51 | Meaning of 17 and 15 behind the AMO report targets; TSO capture date (MQ-09, MQ-33) | SOPS | per-surface till-date bases as in K-12 |
| 3a exit | D-52 | One capture of an achievement bar between 25 and 55 percent (colour boundary at 40) | DEL | green from 80, amber from 40, red below 40 |
| 3a exit | D-85 | Who may assign same-day route cover (Q44) | SOPS | AMO assigns for own zone, up to 7 days, audited |
| 3a exit | D-169 | AMO Outlet tile badge definition from a live account | SOPS | pending verification requests in scope, hidden at zero |
| 3a exit | D-172 | AMO verification Cancel semantics (MQ-29) | SOPS | discard with confirm, no server call |
| 3a exit | D-187 | What SS means and which tiles it gets (MQ-19) | SOPS | supervisor-tier user, tiles resolved per user |
| 3a exit | D-195 | Till-date bases per surface (MQ-09, MQ-33) | SOPS | per-surface keys as in D-51 |
| 3a exit | D-304 | Distribution house: own login or AMO proxy confirmation (Q41) | SOPS | AMO proxy confirmation |
| 3b exit | D-29 | Do AMO routes and users count in Target Route, Login % and Not Logged In lists (MQ-38) | SOPS | planned sr-kind routes only |
| 3b exit | D-45 | Web basis of Submit % (of logged-in) on a day with real submits (MQ-34) | SOPS + DEL | logged-in basis labelled 'Submit % (of logged-in)'; Day-completion % beside it |
| 3b exit | D-55 | After Final Submit: what may change and who may reopen (Q11) | SOPS + FIN | late batches accepted and flagged after_final_submit; reopen is an audited admin action |
| 3b exit | D-177 | Submit % (of logged-in) tiles on TSO, AMO and web use logged-in routes (MQ-34) | SOPS | as D-45 |
| 3b exit | D-198 | Is Final Submit time-gated; blocked by 'SR Not Set' routes (MQ-41) | SOPS | no time gate, no block; explicit confirm dialog added |
| 3b exit | D-262 | Final-submit delegation and auto-close at 23:00 (Q45, Q53) | SOPS + FIN | options built and off |
| 3b exit | D-301 | Does the TSO role span product lines beyond tobacco, e.g. Digonto (Q14) | SOPS | four tobacco categories; Digonto off |
| 4a exit | D-28 | Is Saturday a selling day; holiday list (Q27) | SOPS | Friday off; Saturday selling per docs/22 route plan |
| 4a exit | D-46 | Does a free-sample-only memo count as a successful call; is an all-zero outlet-day a zero-sale call (P-05) | SOPS | free-sample-only counts; all-zero outlet-day is a zero-sale call |
| 4a exit | D-185 | Menus and permissions of DMO, WM, WMO, Top, AMO and admin web logins (MQ-48) | SOPS | union of observed menus as data |
| 4a exit | D-249 | All-zero outlet-day equals a zero-sale call (P-05) | SOPS | assumed true; strike rate 84 to 90 percent by month |
| 4a exit | D-302 | DMO, Wing Manager, WMO and Top Management web-only; their menus (Q16, MQ-48) | SOPS | web-only; menus as data |
| 4b exit | D-47 | BSR denominator: total memos or target outlets (Q9) | SOPS | memos containing the brand / total memos; brand reach stored beside it |
| 4b exit | D-54 | Meaning of total_retention_* (Q10) | SOPS | stored as retention_candidate_pct, never labelled retention |
| 4b exit | D-191 | Sample .xlsx files of every Excel report and the QC PDF (MQ-47) | SOPS | column sets from on-screen grids; remaining reports wait for samples |
| 4c exit | D-40 | What Status 'exist' and 'Delete Section Data' remove; does Final Submit lock edits; how often Web Entry is used (MQ-43, MQ-46, Q11) | SOPS | audited void before Final Submit, web-entry rows only |
| 4c exit | D-97 | How the web back-date cut-off is produced (MQ-44) | SOPS | web entry back-date 0 days, per-zone data_entry_date, audited unlock grants |
| 4c exit | D-108 | Does the live Excel contain NID/TIN; PII matrix (Q30) | LEG + SOPS | TSO parity columns with null where blank; every export logged |
| 4c exit | D-114 | Microsoft Entra ID available for SSO | IT | TOTP MFA for admin bundles; SSO when Entra exists |
| 4c exit | D-182 | Delete Section Data replaced by audited void | SOPS | audited void |
| 4c exit | D-207 | TSO sees Address, NID, TIN, Trade Licence columns (parity) or restricted | LEG + SOPS | parity baseline with export log |
| 5a entry | D-41 | Diamond League earning rules, cash-cap scope, gift catalogue, expiry rule (Q13, MQ-20 to MQ-22) | TM | POSM Q1.1 photo +50 points only; build to rules as supplied |
| 5a exit | D-192 | Astha gift choice: save and lock rules, Gift Status meaning (MQ-26) | TM | per-outlet dropdown, lock after hand-over photo |
| 5a exit | D-306 | Which programmes (Astha, Diamond League, Superstar) are live and where (Q13) | TM | 5a becomes a 7c entry condition if programme-tier outlets exceed 10 percent in the wave-1 territories |
| 5c exit | D-31 | Target approval chain, live target while a new set is pending (Q12, MQ-39) | SOPS | one level (WMO); live target stays |
| 5c exit | D-179 | Target set approval levels and statuses | SOPS | configurable list, one level default |
| 5c exit | D-300 | Geo-triggered volume suggestion: inputs and formula (Q6) | SOPS + TM | hook only: field delivered empty, feature off |
| 6a exit | D-42 | Wholesale/C&C effects on price type, target counting, geo gate; unmark rule (Q17, MQ-32) | SOPS | price type cc; no bex or back-margin screens |
| 6b exit | D-113 | Audit retention period (legal) | LEG | 7 years (ASSUMPTION) |
| 6c exit | D-23 | Statutory retention for transactions | LEG + FIN | 13 months hot then archive, 7 years assumed |
| 6c exit | D-132 | Photo retention (Q21) | LEG + SOPS | Hot to Cool 30 d, Cold 90 d, Archive 365 d |
| 7a entry | D-05 | Azure region: Southeast Asia primary, East Asia DR; data localisation (Q19) | LEG + SP | build in Southeast Asia; Phase 0 proceeds; 7a runs on a pseudonymised or synthetic import and 7b is blocked until counsel answers (D-570) |
| 7a entry | D-107 | Legal opinion on PII sensitivity and localisation | LEG | NID/TIN/licence envelope-encrypted; phone, owner masked in BI; real owner names and phone numbers land at 7a, so without an answer the import is pseudonymised (D-570) |
| 7a entry | D-120 | HR and legal text of the employee-location notice | HR + LEG | default Bangla and English notice at first login; the pilot consent screen shows this text at 7b (T-7-160, D-570) |
| 7a exit | D-119 | Password hash algorithm in the Apsis dump (Q18) | SEC + LEG | if absent, TSO-issued temporary passwords on wave day |
| 7a exit | D-153 | Import quarantine threshold and adjudicators (Q39) | SOPS + FIN | at most 1 percent of rows per table, every reason counted |
| 7a exit | D-303 | Apsis dump contents and delivery date (Q18) | SP + LEG | importer built against the synthetic dump generator |
| 7b entry (ask by 2e) | D-305 | Pilot routes, consenting SRs and incentive (Q33) | SP + SOPS | 10 to 20 routes by the composition rule of D-305 |
| 7b exit | D-126 | Is a pre-bind day (T-1 at the distribution house on Wi-Fi) operationally feasible (Q52) | SOPS | plan assumes yes; otherwise wave size shrinks |
| 7b exit | D-147 | Wave sizes and order; wave-1 size 300 routes or 1,000 SRs (Q38, Q51); blackout dates | SP + SOPS | pilot 10 to 20 routes, wave 1 about 300 routes |
| 7b exit | D-148 | How the old app is made read-only per wave (G-feat-67) | SP + IT | flag flip in the new app; Apsis-shape export of new-app data |
| 7b exit | D-152 | During the pilot does the new app print, and what does the retailer keep (Q49) | SOPS | capture_only or print_test_watermark; Apsis memo is the retailer's |
| 7b exit | D-154 | Machine-readable per-route daily Apsis data for the parallel run (Q32) | SOPS + SP | manual keying from photographed summaries (pilot only) |
| 7b exit | D-311 | Wave weekday and blackout dates; Ramadan trading hours (Q38) | SP + SOPS | waves start Sunday to Tuesday, avoid month end, Eid and Ashura |
| 7c exit | D-63 | Tolerance for counts rising again after a failover or PITR (Q54) | SOPS + SP | server-generation re-sync on |
| 7c exit | D-93 | Geofence radius values per territory (Q7) | SOPS | global 100 m; per-zone values only after 2 weeks of pilot fixes per geo class |
| 7c exit | D-127 | RTO 4 h and RPO 15 min for a region outage; PITR RTO 2 h (Q20, Q40) | SP | as stated |
| 7c exit | D-138 | Does the helpdesk L1 get a read-only role for device lookup (Q55) | SUP + IT | L2 engineering runs lookups |
| 7c exit | D-149 | Helpdesk: exists, hours, channels, languages (Q35) | SUP + SP | 8 to 12 Bangla agents, 07:00 to 21:00, 24 h on wave days 1 to 3 |

Proved by: the exit report of each sub-milestone lists the answers received and the defaults accepted (doc 20 s1).

### 4.2b MUST-CONFIRM decisions raised by documents 15 to 21 and by the round-2 review (62), ordered by due sub-milestone

Copied at the editorial merge from the "Decisions raised here" tables of docs 15 to 21 (DECISIONS.md section M2). The owner role of each is the item in the Open items table of the owning document (the "Owner" column of the doc 15 to 21 OI rows); the "Proceeds on" column is the decision text itself, which is the default the build uses. Owner-role assignment per row is open (OI-14-16) except for the twelve rows of the round-2 review, which name the role (the rows marked R2 in the Decision column).

| Due | Decision | What the business must answer | Owner | Proceeds on (default meanwhile) |
| --- | --- | --- | --- | --- |
| 0a exit | D-421 | subscription split | per OI table of doc 18 | Staging is a small twin scaled to the production SKU only inside booked windows by the `load-test` workflow, with an automatic scale-down after 8 h; non-prod in a separate subscription |
| 0a exit | D-530 (R2) | evidence files committed under /docs/evidence | SP | The manual inventories, the delta register and the six V-* verification files are committed read-only under /docs/evidence/manuals/ and /docs/evidence/verification/ as a 0a deliverable; DECISIONS.md and doc 14 name the path; T-0-46 and T-0-120 read them |
| 0a exit | D-534 (R2) | precedence text applied to CLAUDE.md and README; superseded-by banners | SP | A "Plan documents and precedence" section is added to CLAUDE.md and README (docs 14 to 22 and DECISIONS.md bind and win over docs 01 to 13 and db/schema.sql), superseded-by banners go on docs 03, 06 to 10 and db/schema.sql, and doc 14 s2.2 lists every change against docs 03 and 06 to 10; CLAUDE.md, the README, the base docs and schema.sql are outside the files this planning pass may edit, so the text is supplied in ... |
| 0b exit | D-501 (R2) | jsonb list J-1 to J-8 and exclusions EX-01 to EX-06 signed | SP + DEL | R1(a) is amended to "no captured field exists only in a JSON blob except the sanctioned list J-1 to J-8 of doc 16 s8.10, each with a typed form and a lifecycle": survey answers are typed (answer_type with bool, num, option, text), radio-environment features are typed columns, the memo offer versions are rows (bridge_memo_offer_version), promotion rules get typed tables at 2a entry, outlet.extra is typed by 4c; the ... |
| 0c exit | D-338 | reviewer | per OI table of doc 15 | Bangla review and accessibility: an AKTCL reviewer (named by 0c) signs the bn and en catalogue side by side before each release; acceptance is 100 percent keys present, no truncation at 360 x 640 dp and font scale 1.3, tap targets... |
| 0c exit | D-473 | OTP entropy controls | per OI table of doc 21 | OTP entropy controls: one active OTP per user, 3 creations per user per hour, 10 failed attempts per user per 24 h then binding locked until a TSO or `ops_admin` clears it; the panel shows only open OTP values |
| 0c exit | D-502 (R2) | Parity Exceptions Register PX-01 to PX-10 signed | SP + SOPS | A Parity Exceptions Register (doc 15 s6.4) lists every live-but-not-built, hidden or flagged-off Apsis function (PX-01 to PX-10: bex order pages, back-margin commission letters, the web voicerecording page, Sales Journey and KPI home tiles, AMO Survey tile, Joint Call items 4 and 5, Digonto lines for the TSO, the DMO, WM, WMO and Top menus, the geo-triggered volume suggestion, programme outlets in wave 1) with an ... |
| 0c exit | D-503 (R2) | usage census answers (D-42 existence, Q15, Q17, MQ-48, D-342) | SOPS + IT | An Apsis usage census (page and endpoint visit counts from AKTCL's own Apsis admin or audit views, plus distribution-house and DMO/WM interviews) is part of the baseline pack (T-0-49 item i); the answers to D-42 scope, Q17, Q15, MQ-48 and D-342 are pulled forward to 0c (4a at the latest for D-342) |
| 0c exit | D-514 (R2) | Apsis delta contract: owner, content, 20:00 T-1 deadline, fallback | SOPS + LEG + IT | The Apsis delta contract (doc 16 s12.6): per wave and nightly, dues per memo, loyalty ledger, outlet changes, target and assignment changes, same-date control totals and a nightly route-day aggregate feed for unswitched routes, in CSV with a manifest, by 20:00 Dhaka at T-1, from a named delta owner, with an AKTCL-staff extraction fallback and a breach rule (defer the wave); the contract goes into the dump-request ... |
| 1a exit | D-322 | paper marking (UI-SR-29) | per OI table of doc 15 | Reprint: always allowed from the Memo menu; the paper carries a duplicate marker (text from the physical samples; what Apsis prints is unknown) unless the previous print was marked failed_user; cfg.memo.reprint_max default 5 (ASSU... |
| 1a exit | D-344 | printed memo digits from the physical samples | per OI table of doc 15 | Digit script follows the UI language for screens and, by default, the rasterised printed memo; stored values are ASCII; cfg.i18n.digit_script default follow_ui |
| 1a exit | D-346 | One Bangla label per money term on screens and the printed memo | per OI table of doc 15 | One Bangla label per money term on screens and the printed memo: gross, offer discount, DRP/slide discount, QC deduction, net payable; proposed from the manual: মোট (gross), ডিসকাউন্ট, স্লাইড, QC, সর্বমোট (net); the sponsor's dele... |
| 1a exit | D-392 | samples | per OI table of doc 17 | Template contract: 7 memo kinds plus day summary, due receipt, cancel slip and parallel-run test print; template JSON in the bundle with an embedded fallback of each kind; a golden print test per kind; Phase 1a cannot exit on memo... |
| 1a exit | D-393 | Memo number slots | per OI table of doc 17 | Memo number slots: the bind ordinal is a stable device slot (lowest slot neither active nor revoked on the same date; a revoked slot is held until the next business date); blocks 001 to 500, 501 to 999, 1001 to 1500, 1501 to 2000;... |
| 1a exit | D-394 | marker text | per OI table of doc 17 | Print mechanics: `print_job` is a sync record; "পুনর্মুদ্রণ #n" marker unless the previous print was `failed_user`; "ছাপা ঠিক আছে?" after each print; applies D-322 |
| 1c exit | D-395 | Multi-user engine | per OI table of doc 17 | Multi-user engine: one batch in flight device-wide; active user first then oldest pending; one batch per user per rotation; each batch under that user's token; after an SR or AMO logout the refresh token stays in upload-only mode... |
| 1c exit | D-523 (R2) | are bex order pages or back-margin letters used today (existence) | SOPS | D-42 is split: (a) existence: are bex order pages or back-margin commission letters used by any AKTCL role or distribution house today (owner sales operations, due 1c, answered from the usage census D-503); (b) effects (price type, target counting, geo gate, unmarking), due 6a as before; if (a) is yes a reserved feature row and sub-milestone with a schedule delta are added before wave 1 |
| 2a exit | D-320 | any business limit | per OI table of doc 15 | Credit: Apsis shows no credit limit, eligibility rule or maximum age; the new app allows credit on any outlet; cfg.credit.max_due_mtk and cfg.credit.max_days default 0 (off); overdue blocks nothing; the ageing report is the contro... |
| 2a exit | D-341 | Eligibility dots | per OI table of doc 15 | Eligibility dots: legend is admin data (cfg.ui.outlet_badges), per-outlet flags travel in the bundle; all four dots are drawn, filled when eligible and outlined when not (ASSUMPTION); the colour-to-programme mapping is unknown |
| 2a exit | D-342 | Home tiles Sales Journey and KPI are hidden through cfg.app.home_tiles until their content is captured from the live app | per OI table of doc 15 | Home tiles Sales Journey and KPI are hidden through cfg.app.home_tiles until their content is captured from the live app; tile set per user (Loyalty Point and Photo Capture absent on one account) |
| 2a exit | D-343 | Discount lines are stored as (sku, qty, value, kind) so goods-in-kind, per-SKU discount value and quantity-only lines all fit | per OI table of doc 15 | Discount lines are stored as (sku, qty, value, kind) so goods-in-kind, per-SKU discount value and quantity-only lines all fit; the Memo screen discount table lists them; what "SL Match 3 / 0.00" means is unknown |
| 2a exit | D-350 | Line values are exact rationals of price x quantity rounded half-up to a milli-taka | per OI table of doc 16 | Line values are exact rationals of price x quantity rounded half-up to a milli-taka; the memo net is summed from those lines and rounded once to the paisa, half away from zero; the difference is stored as `round_adj_mtk`; `roundin... |
| 2a exit | D-372 | A QC entry saved after the memo is committed is stored with `after_memo_commit` and reported as `qc_late_settlement_mtk`; whether the printed memo is reissued is unknown | per OI table of doc 16 | A QC entry saved after the memo is committed is stored with `after_memo_commit` and reported as `qc_late_settlement_mtk`; whether the printed memo is reissued is unknown |
| 2b exit | D-323 | Returns after QC: defect sticks leave the SR stock as a qc_return movement and go back with the day-end return | per OI table of doc 15 | Returns after QC: defect sticks leave the SR stock as a qc_return movement and go back with the day-end return; QC stays a money deduction; no replacement or credit-note path (none visible in Apsis) |
| 2c exit | D-326 | Selling to a not-yet-approved outlet is allowed the same day (cfg.outlet.sell_before_approval true) with a provisional outlet client_uuid re-linked on approval | per OI table of doc 15 | Selling to a not-yet-approved outlet is allowed the same day (cfg.outlet.sell_before_approval true) with a provisional outlet client_uuid re-linked on approval; a rejected outlet keeps its memos and is flagged for the AMO |
| 2c exit | D-404 | block or allow force sale without a photo | per OI table of doc 17 | Storage: below 500 MB free the image cache is evicted; below 200 MB photo capture is refused and force sale stays allowed with `photo_pending_storage`; `SQLITE_FULL` rolls back and retries after eviction |
| 2c exit | D-510 (R2) | R5 acceptance wording and the photo exception R5(f) | SP | R5 acceptance text is reworded to match the design: (c) 60 s foreground and online, 3 minutes after connectivity returns in the background, (e) no repeating timer under 60 s (the 5 s debounce and the 10 s connectivity validation are one-shots); a new R5(f) states the photo exception: photos are Wi-Fi-first with a mobile fallback after cfg.media.evidence_mobile_fallback_h (6 h) and a 24-hour 95-percent SLO, traded ... |
| 2d exit | D-431 | the business accepts judging an honest offline phone by the value it held | per OI table of doc 19 | The server re-check resolves the value the device knew (its stamped `config_version`) when the stamped value equals what that version contained and the capture is within `cfg.sys.config_accept_window_h` (48) of that version's comm... |
| 2d exit | D-435 | approver roster and break-glass holder (Q23) | per OI table of doc 19 | Permission model: atomic permissions, bundles, grants as C3 requests, approver outside the requester's reporting chain for geo, fraud, auth, PII and day keys, approver cooling 24 h |
| 2d exit | D-478 | Radio environment | per OI table of doc 21 | Radio environment: passive cell identities and salted 8-byte Wi-Fi hashes, no new permission, learned centroids with per-cell radius scaling, 14-day pilot calibration, supervisor-visible only |
| 2d exit | D-519 (R2) | D-431 tolerance window runs from the first change after the stamped version | SOPS + SEC | D-431 is amended: the tolerance window is measured from the first config version AFTER the device's stamped version that changed the key in the chain: a capture is judged under the value the device held when capture_instant - effective_from(first superseding change) <= cfg.sys.config_accept_window_h (48); otherwise under the as-of value with the flag config_stale; the age of the device's own version no longer matters |
| 2d exit | D-527 (R2) | emergency widen lane and temporary relief limits; break-glass holders per shift | SP + SEC | An emergency widen lane under break-glass for geofence and similar rules: bounded by cfg.geo.radius_emergency_max_m (default 250), scope limited to one territory or lower unless two approvers, auto-expires after cfg.geo.emergency_widen_max_hours (6, bound 1 to 12), needs one approver after the fact, exempt from the change freeze, and sets a two-sided anomaly watch; at least two break-glass holders per shift with an ... |
| 2e exit | D-329 | whether the live app blocks selling before check-in (UI-SR-13) | per OI table of doc 15 | Attendance edge cases: check-in without a fix is stored with null location and flagged; a forgotten check-out never blocks Sales Submit and the server closes it as "no check-out" at the end of the business date; a missed check-in... |
| 2e exit | D-383 | Q-UI-08 | per OI table of doc 17 | Sales Submit on the device: enabled online after every row is acknowledged (PARITY), enabled offline with a "N records not yet sent" confirm when `cfg.day.sales_submit_offline_queue` is true (default); dues only warn; `rejected_co... |
| 3a exit | D-324 | Q41 | per OI table of doc 15 | Stock return and counter-confirmation: day-end movements return, damaged, short with counted_qty, variance reason and confirmed_by (nullable); confirmer is the AMO as proxy by default, a dh role (scope node house) if Q41 says so;... |
| 3a exit | D-325 | Q41 | per OI table of doc 15 | Cash deposit: cash_handover event (declared, counted, variance, counted_by, fix) with the same confirmer as D-324; a shortfall is settled outside the system and never blocks |
| 3a exit | D-334 | Price compliance | per OI table of doc 15 | Price compliance: the AMO records the observed shelf price per SKU at a control call; compliant when equal to the outlet list price within a tolerance; counted in the reconciliation row; off for the SR flavour |
| 3a exit | D-348 | The AMO Survey tile is feature-flagged until its screen is captured from the live app | per OI table of doc 15 | The AMO Survey tile is feature-flagged until its screen is captured from the live app; it reuses the SR survey component and earns no points |
| 3a exit | D-349 | Joint call rubric | per OI table of doc 15 | Joint call rubric: three known items plus two disabled placeholders as versioned data; star 1 pre-selected for parity (cfg.rubric.default_rating 1, require_all_rated false); the assessed SR is the route assignee on the date; contr... |
| 3a exit | D-425 | No bulk server-side reverse geocoding | per OI table of doc 18 | No bulk server-side reverse geocoding; on demand only, cached 24 h by coordinates rounded to 3 decimals; Google Maps Platform key restricted, with a billing alert |
| 3b exit | D-337 | SR and AMO leave is not built (only TSO leave appears in any manual) | per OI table of doc 15 | SR and AMO leave is not built (only TSO leave appears in any manual); an absent SR is handled by day exception and cover |
| 3b exit | D-388 | A new business date starts at 00:00 trusted Dhaka time | per OI table of doc 17 | A new business date starts at 00:00 trusted Dhaka time; the family date is fixed at visit open; yesterday's Sales Submit is allowed until `cfg.day.submit_grace_h` (10 h after midnight), then only by audited admin reopen |
| 3b exit | D-489 | employee phone numbers and sender | per OI table of doc 21 | A reset, change or scope revocation surfaces on the victim's phone as a 401 reason with who and when; a password success on a new device raises a banner on the bound device; SMS is optional |
| 4a exit | D-336 | MQ-48 | per OI table of doc 15 | DMO, Wing Manager, WMO and Top Management are web-only; menus and permissions are data; DMO approves TSO leave, WMO approves target sets; Top Management read-only dashboards |
| 4a exit | D-448 | other roles' menus (MQ-48) | per OI table of doc 19 | The menu hides and the server enforces; `cfg.web.menu_by_role` is C3 and seeded with the union of the TSO sidebar and the spec pages, other roles' extra pages granted to DMO, WM, WMO, top and admin only |
| 4a exit | D-481 | Q41 | per OI table of doc 21 | Counter-party confirmation of issue, return and cash (distribution house or AMO as proxy) is a fraud control; FS-17, FS-04 and FS-32 compute against confirmed figures; unconfirmed days age |
| 4b exit | D-538 (R2) | attendance location text: coordinates plus a derived locality hint | SOPS | Attendance stores coordinates only; reports show a locality_hint derived by the worker from the stored fix (nearest cluster name within 500 m, else zone name); a server reverse-geocode provider, quota, cost and a stored address column are added only if the business asks for addresses in GIGO, with the provider named in D-08 |
| 4c exit | D-437 | Web Entry derivations | per OI table of doc 19 | Web Entry derivations: Sale = Issue minus Return, Return at most Issue, class quantities sum to Sale, Successful Call at most the Target Outlet snapshot, replace-with-audit on re-save, explicit Save, app rows win on overlap |
| 4c exit | D-438 | "Delete Section Data" is an audited void with a confirm, a reason of at least 10 characters, default scope web-entry rows, app memos only for `ops_admin` with two-person  | per OI table of doc 19 | "Delete Section Data" is an audited void with a confirm, a reason of at least 10 characters, default scope web-entry rows, app memos only for `ops_admin` with two-person approval, only before Final Submit |
| 4c exit | D-439 | MQ-44 | per OI table of doc 19 | Web entry cut-off: rolling `today - cfg.web.entry_backdate_days` by default, or the zone's Data Entry Date plus one day when `cfg.web.entry_cutoff_source` says so; audited unlock grants of at most 7 days and 24 h, over 7 days or a... |
| 4c exit | D-482 | If counsel finds phone or owner sensitive, or localisation applies, phone and owner move to envelope encryption in two migrations | per OI table of doc 21 | If counsel finds phone or owner sensitive, or localisation applies, phone and owner move to envelope encryption in two migrations; list budgets are per role (TSO 5,000 an hour, others 2,000) |
| 4d exit | D-428 | channel and tool (G-18-09) | per OI table of doc 18 | Alert hygiene: Sev1, Sev2, Sev3 routing; every alert has an owner, a runbook and an auto-resolve; storm profile never changes a Sev1; 90-day pruning; post-incident review in 5 working days |
| 5b exit | D-332 | Superstar | per OI table of doc 15 | Superstar: enrolment by admin per outlet per month, slabs as data, a criteria job sets sales_criteria_met; the rules are unknown |
| 5b exit | D-333 | Free sample | per OI table of doc 15 | Free sample: an is_free memo line at price 0 with kind free_sample, own report; counts as a successful call only if D-46 is confirmed that way |
| 6b exit | D-484 | Audit chain through a serial insert function (Merkle batches if contended) | per OI table of doc 21 | Audit chain through a serial insert function (Merkle batches if contended); the WORM policy stays unlocked in the pilot and is locked before wave 1 |
| 6c exit | D-371 | statutory period | per OI table of doc 16 | Retention classes are rows (`app.retention_policy`); a partition is dropped only after a verified export manifest; hot windows 13, 6, 3, 12 and 25 months by class |
| 7a exit | D-454 | which Apsis reports round | per OI table of doc 20 | Control-total tolerance is 0 for counts, balances and loyalty; STD may differ by at most 0.01 % only where the Apsis report rounds and the rounding is documented per report; dues need FIN's signature |
| 7b exit | D-453 | the feed (Q32) | per OI table of doc 20 | The parallel compare job runs at 20:30 Dhaka per trading day, the report is due 21:00 and reviewed by 09:00; a day with the Apsis feed missing for any pilot route does not count toward the 10 |
| 7b exit | D-486 | A wave cohort's binds are pre-acknowledged by the DMO or `security_admin` on the pre-bind day so D-112 does not hold 1,000 honest binds | per OI table of doc 21 | A wave cohort's binds are pre-acknowledged by the DMO or `security_admin` on the pre-bind day so D-112 does not hold 1,000 honest binds |
| 7b exit | D-549 (R2) | rollback return path: re-key procedure, owner, 3-day window | SP + SOPS + FIN | Wave rollback return path: rollback is (a) re-key of the wave's open dues, stock and credit memos into Apsis through a named, staffed procedure for the first 3 days after a wave, then (b) fix-forward only; Apsis is kept mirrored nightly (read) so dues can be compared; the drill T-7-153 proves retailer dues in Apsis equal Aron after a rollback; the owner is on the war-room roster; same-day sales after a 17:30 ... |
| 7c exit | D-420 | DR is a warm standby in East Asia | per OI table of doc 18 | DR is a warm standby in East Asia: replica, ACR geo-replica, vault with the same signing keys, apps at min 0, a disabled Front Door DR origin whose readiness needs `pg_is_in_recovery() = false`; wave 1 has geo-redundant backup onl... |
| 7c exit | D-441 | radius values (Q7) | per OI table of doc 19 | Radius calibration method: report per geo_class by territory after 14 days and 500 visits, the smallest ladder value reaching 95 percent of honest visits, informational only; first-wave radii come from it |

### 4.2c MUST-CONFIRM decisions raised by the round-3 review (10), ordered by due sub-milestone

The third review (54 gaps, G-qa-86 to G-qa-139) raised ten decisions only the business can answer. Each carries a default so the build never waits (D-308); the sub-milestone in "Due" cannot exit, or the wave cannot start, until the row is answered or its default is accepted in writing by the delegate. They count in the 173 of s1.4 and are indexed in DECISIONS.md section O.4.

| Due | Decision | What the business must answer | Owner | Proceeds on (default meanwhile) |
| --- | --- | --- | --- | --- |
| 0a exit | D-555 (R3) | The Board envelope, four items: (1) the Apsis contract end date and the monthly fee through decommission; (2) the programme cost envelope: person-weeks of s2.4 (333 to 437) times the confirmed loaded rate, with the cost of each week of delay (s2.5b); (3) the confirmed headcount and whether the half-team scenario (s2.5b) is acceptable; (4) the latest wave-1 date the Board accepts | SP | Planning basis D-307 (8 to 10 engineers, planning date Sunday 2027-06-20); the Apsis contract assumed to run to the end of wave 4 at its current fee (RK-30); the cost per week of delay is computed from the rate once given and shown in every exit report; T-0-162 records the answers |
| 0c exit | D-560 (R3) | BI path: whether Fabric capacity F2, the VNet data gateway and Fabric mirroring of the Flexible Server replica are available and licensed, and how many Power BI Pro licences AKTCL holds (doc 16 s13.3, doc 18 s6) | IT | The replica read through a VNet data gateway with 10 Pro licences; the lake query waits; 400 USD a month is an ASSUMPTION priced at 0c |
| 0c exit | D-562 (R3) | APK distribution under Google's developer-verification rule: the channel (own signed APK with a verified-developer registration for the three package ids, a Managed Google Play private app, or a lightweight MDM) and who registers (doc 17 s10.2; moves D-10 from 2e to 0c) | IT + SP | AKTCL's own signed APK channel with the registration done at 0c if Google's current page says it applies; T-2-164 and T-7-166 prove the install on Android 14 to 16 |
| 0c exit | D-565 (R3) | The baseline-pack owner and a named fallback (the pack is captured by AKTCL staff, T-0-49), and the census evidence class: Apsis admin or audit logs, or, if Apsis keeps none, structured interviews with signed attestations plus a 4-week observation log in the pilot zones (T-7-158) | SP + SOPS | The delegate owns the pack with the sales operations manager as fallback; evidence class 2 (interviews and observation) unless Apsis logs exist; Phase 1 cannot start without T-0-49 |
| 2e exit | D-584 (R3) | Whether working-day windows (stale bundle, back-date, unlock counted in working days) with a calendar-day ceiling of 7 are acceptable for the first morning after a break, and which breaks AKTCL declares in `cfg.calendar.break_overrides` (doc 19 s2.6b, OI-19-31) | SOPS | `window_unit` = `working_days`, ceiling 7 calendar days, breaks declared by the delegate a week ahead |
| 2e exit | D-589 (R3) | Price rails: the 15 percent single-change threshold, the 24-hour correction window, per-price-type thresholds (doc 19 s8.2b, OI-19-32) | FIN | 15 percent, 24 hours, one threshold for all price types; a change above it needs `finance_approver` |
| 5a entry | D-561 (R3) | Programme parity at 7b-2: that 3 to 5 programme-tier routes run 10 trading days against Apsis (points, balances, gift status, Astha achievement) before any programme-tier route is bound, and that Apsis programme data per route is available daily (OI-16-41, D-41, D-192) | TM + SOPS | 7b-2 as written in T-7-165; wave 1 excludes programme-tier routes unless 7b-2 has passed |
| 7a entry | D-570 (R3) | Counsel's answers for the 7a rows of doc 21 s4.6: residency of owner names and phone numbers, PII classification, the employee-location notice and the breach-notification duty (D-05, D-107, D-120) | LEG + HR | Pseudonymised or synthetic import only (a PII scan proves it, T-7-160); 7b does not start without the answers |
| 7a exit | D-569 (R3) | The sponsor's written acceptance of the wave-1 DR position: geo-redundant backup from the creation of the 7a server, region outage means a geo-restore into East Asia with the RTO measured by T-4-174 (not the 4 hours of D-127 until measured), RPO about 1 hour for wave 1 (doc 18 s3.1, RB-52) | SP | The measured RTO and RPO are written into RK-05 and the day-one checklist; if the measured RTO exceeds 4 hours the cross-region replica is brought forward at its cost (doc 18 s6) |
| 7b exit | D-592 (R3) | Rollback capacity: whether AKTCL commits trained keyers per wave (about 2 staff per 100 routes for the first day: 8, 38, 68 and 113 people for waves 1 to 4) or obtains an Apsis bulk-import path for dues; how Apsis accepts keyed dues (doc 18 s7.5, OI-18-14, OI-18-18) | SP + SOPS + FIN | Waves above the committed capacity are fix-forward only and say so on the go/no-go sheet (T-7-168); no wave is promised a rollback it cannot staff |

Proved by: T-0-162 (D-555), T-0-49 and T-7-158 (D-565), T-7-160 (D-570), T-4-174 (D-569), T-7-168 (D-592), T-7-165 (D-561), T-2-164 (D-562).

### 4.3 Questions of docs/13 and of the lenses and critics, mapped to decisions

Q1 to Q18 are docs/13. Q19 to Q22 come from the scale lens, Q23 to Q30 from the config lens, Q31 to Q40 from the quality lens, Q41 to Q50 from the field critic; Q51 to Q55 are the SRE critic's, renumbered from its own Q41 to Q45 (they collided with the field critic). New questions raised while writing are numbered Q56 and up by the doc 14 author; none exist yet.

| Q | Topic | Decision (gating status or due) |
| --- | --- | --- |
| Q1 | Mobile framework | D-01 (default) |
| Q2 | Backend stack | D-02 (default) |
| Q3 | Hosting specifics | D-04 (locked), D-06 (default), D-07 (default) |
| Q4 | Auth and token lifetimes | D-101 (0c), D-103 (0c) |
| Q5 | Memo numbering across cutover | D-35 (1a, before the first golden print) |
| Q6 | Geo-triggered volume suggestion | D-300 (5c) |
| Q7 | Geofence radius per territory; no-location behaviour | D-93 (7c), D-95 (2d) |
| Q8 | Units | D-16 (2a), D-17 (2a), D-248 (2a) |
| Q9 | BSR denominator | D-47 (4b) |
| Q10 | Retention meaning | D-54 (4b) |
| Q11 | After final submit | D-55 (3b), D-262 (3b) |
| Q12 | Target split and approval levels | D-31 (5c) |
| Q13 | Programmes live; promotion catalogue | D-33 (2a entry), D-41 (5a entry), D-192 (5a), D-561 (5a entry) |
| Q14 | TSO product scope (Digonto) | D-301 (3b) |
| Q15 | Microphone permission | D-115 (2e) |
| Q16 | Roles beyond the apps | D-302 (4a) |
| Q17 | Wholesale and distributor flows | D-42 (6a) |
| Q18 | Data dump contents | D-303 (7a), D-119 (7a), D-154 (7b) |
| Q19 | Data residency | D-05 (7a entry), D-107 (7a entry), D-570 |
| Q20 | RTO and RPO tolerance in a region outage | D-127 (7c), D-569 (7a) |
| Q21 | Photo retention | D-132 (6c) |
| Q22 | FCM push acceptable | D-09 (2d) |
| Q23 | Approver roster and break-glass holder | D-91 (2d) |
| Q24 | TSO radius authority | D-94 (2d) |
| Q25 | No-location policy and first photo | D-95 (2d) |
| Q26 | Edit reason texts; Astha and Superstar parameters; promotion rule schema | D-200 (2b), D-41 (5a), D-33 (2a entry) |
| Q27 | Saturday selling day; holiday list | D-28 (4a) |
| Q28 | FCM acceptable (restates Q22) | D-09 (2d) |
| Q29 | Business-date cutoff | D-20 (1a) |
| Q30 | PII role x field matrix | D-108 (4c), D-207 (4c) |
| Q31 | Fleet device inventory | D-11 (0c), D-12 (0c), D-564 |
| Q32 | Machine-readable per-route Apsis data for the pilot | D-154 (7b) |
| Q33 | Pilot routes, consent, incentive | D-305 (7b entry) |
| Q34 | Sponsor's delegate and readiness owners | D-156 (0a) |
| Q35 | Helpdesk and native-Bangla string reviewer | D-149 (7c) |
| Q36 | Sentry acceptable | D-13 (2e) |
| Q37 | Who signs 'same memo' for retailers | D-146 (2e) |
| Q38 | Wave order, sizes, blackout dates | D-147 (7b), D-311 (7b) |
| Q39 | Import quarantine threshold and adjudicators | D-153 (7a) |
| Q40 | RTO and RPO for PITR and region DR | D-127 (7c) |
| Q41 | Distribution house login or AMO proxy | D-304 (3a) |
| Q42 | Map tile provider and licence | D-08 (3a) |
| Q43 | Retailer cancels after print | D-86 (2b) |
| Q44 | Same-day route cover | D-85 (3a) |
| Q45 | Final submit for an absent TSO; auto-close | D-262 (3b) |
| Q46 | Outlets priced cc or distributor | D-32 (2a) |
| Q47 | Receipt for a due collection | D-37 (3a) |
| Q48 | Closing an outlet with open dues or points | D-25 (locked) |
| Q49 | Printing during the parallel pilot | D-152 (7b) |
| Q50 | Closed-shop outcome record | D-38 (2a) |
| Q51 | Wave-1 size: 1,000 SRs or about 300 routes | D-147 (7b) |
| Q52 | Pre-bind day feasible | D-126 (7b) |
| Q53 | Auto-close of a zone-day at 23:00 | D-262 (3b) |
| Q54 | Tolerance of counts rising again after DR | D-63 (7c), D-127 (7c) |
| Q55 | L1 read-only Azure role | D-138 (7c) |

### 4.4 The 68 manual-register questions (MQ-01 to MQ-68)

The manual register renamed its own Q-01 to Q-67 as MQ-01 to MQ-67 so they never read as docs/13 numbers; MQ-68 was found in round 3 (the Apsis Stock screen on a second Save, G-qa-115, D-580). Answerers: A AKTCL business or operations; L the sponsor's screenshots or recordings of the live app (D-312); S a physical or Excel sample; X the Apsis dump. A question can name two answerers (A 47, L 31, S 6, X 6 over 68 questions). Every question has a row below with its answerer, the sub-milestone that needs it and the decision that carries its default (D-582: the first draft listed the questions only by range, so a question with no carrier could not be seen). Eight questions have no carrier decision (MQ-07, 13, 35, 36, 40, 42, 65, 66) and three more (MQ-23 to MQ-25) are carried only by the Parity Exceptions Register (D-502): they are built to the observed behaviour, labelled "unknown; confirm", and each is a line in the baseline pack's capture list (item 5 of s4.5) so that AKTCL staff can answer it from the live app. The answers are recorded as amendments of the carrier row (DECISIONS.md how-to-use, rule 6).

| MQ | Question | Who | Needed by | Carried by (the default is in that row) |
| --- | --- | --- | --- | --- |
| MQ-01 | Lighter and match: pieces, boxes or dozens in each app; AMO match price per piece or per dozen | A | 2a | D-16, D-194 |
| MQ-02 | May an SR type loose sticks, or only pack multiples | A, L | 2a | D-17 |
| MQ-03 | Basis of the maximum QC; whether the QC settlement is credited against the memo; negative total on a zero sale plus QC | A, L | 2a | D-18, D-34 |
| MQ-04 | Is the sale-screen footer net of the slide deduction | L | 2a | D-18, D-158 |
| MQ-05 | Slide: packs or sticks, behaviour outside the offer window, one offer per SKU | A | 2a | D-33 |
| MQ-06 | Where the memo discount comes from (columns exist, no entry screen) | A, L | 2a | D-33, D-166 |
| MQ-07 | What the three per-SKU indicators and the red eye-with-slash icon mean | L | 2a | none: unknown; confirm; kept off the screen and listed in the Parity Exceptions Register until a capture answers (D-502) |
| MQ-08 | Distributor price 7.935 against outlet 8: rule or stored data; confirm three-decimal money | A, X | 2a | D-15 |
| MQ-09 | The constant 17 behind the AMO route targets | A, X | 2a | D-51 |
| MQ-10 | Is an outlet photo required on every call or only on Force Sale | L | 1a | D-75, D-163 |
| MQ-11 | What "No" does at the start-call prompt and the print prompt; where AV, KV and the survey sit in the call | L | 1a | D-78, D-160 |
| MQ-12 | What the cancel and delete buttons do on QC entry; the expired-stock threshold glyph | A, L | 2a | D-34 |
| MQ-13 | Check-in: time window, press-and-hold duration, other tiles before check-in | A, L | 2e | none: unknown; confirm; the attendance keys of doc 19 carry the proposed values |
| MQ-14 | Geofence radius per territory; AMO Manual Override reason, daily limit and the "x" button | A | 2d | D-93 |
| MQ-15 | Does the AMO app bind with the TSO OTP; is the OTP asked again after every new version; expiry, retries, lockout | A, L | 0c | D-80, D-103, D-164 |
| MQ-16 | May a later SR collect part of a credit memo; which memo is settled when an outlet has several | A | 3a | D-37, D-161 |
| MQ-17 | Whose dues an AMO sees: only the AMO's own credit memos or any SR's for the retailer | A | 3a | D-37 |
| MQ-18 | The other two sale-edit reasons; why the AMO Edit button is grey | L | 2b | D-200 |
| MQ-19 | What SS stands for and which tiles each designation gets | A | 3a | D-187 |
| MQ-20 | April Diamond League expiry (month end plus 7 days); what "gift requisition" means | A | 5a | D-41 |
| MQ-21 | Is the 199-point cash cap per redemption, per month or per outlet; the remaining gift cards | A, L | 5a | D-41 |
| MQ-22 | POSM +50 points: posted at submit or after upload; monthly cap | A | 5a | D-41 |
| MQ-23 | Joint Call items 4 and 5: titles and guidance text | L, X | 3a | D-502 (PX-06) |
| MQ-24 | Is star 1 a default or sample data; can a Joint Call be saved untouched | L | 3a | D-502 (PX-06) |
| MQ-25 | AMO Survey form: questions, required flags, is it the SR POSM survey | L | 3a | D-502 (PX-05) |
| MQ-26 | Astha gift choice: how saved, can it change and until when; what Gift Status Yes and No mean | A | 5a | D-192 |
| MQ-27 | ADS, TADS, PADS and RADS: exact formulas (TADS fits 500 to 36 but not 900 to 68) | A, L | 2a | D-58 |
| MQ-28 | Is every gift photo mandatory in Campaign Photo Capture (Submit works with one of two photos missing) | L | 5a | D-582 (Q-28 default: Submit only when every slot has a photo) |
| MQ-29 | What "cancel" does on the AMO verification forms; may the AMO reject | A, L | 3a | D-172, D-43 |
| MQ-30 | Closing an outlet that has open dues or unsynced memos: warn, block or allow | A | 3a | D-43 |
| MQ-31 | Which route an AMO-created outlet gets and who assigns it | A | 3a | D-43, D-162 |
| MQ-32 | Wholesale flag: effect on price type, KPI counting and geo gate; how it is removed | A | 3a | D-42 |
| MQ-33 | Till-date basis: calendar days elapsed through yesterday over days in month (TSO 26/30, AMO about 25/30) | A, L | 3a | D-51 |
| MQ-34 | On a day with real submits, is the web Submit % divided by logged-in or by target routes | L | 3b | D-45, D-177 |
| MQ-35 | AMO Live Dashboard total sales 38: quantity or memo count | L | 3b | none: unknown; confirm; the tile is labelled with the unit the evidence supports |
| MQ-36 | CPR: definition and period for the AMO route list | A | 3b | none: unknown; confirm; not built until answered |
| MQ-37 | Do service zones exist; does the TSO Zone picker list them | A | 3b | D-536 |
| MQ-38 | Do AMO routes and users count in Target Route, Total Login and the Not Logged In and Not Uploaded lists | A | 3b | D-29 |
| MQ-39 | Who is the WMO; which screen approves targets; other Target and Product Types; does the live target stay in force while a new set is pending | A, L | 3b | D-31, D-179 |
| MQ-40 | Day target behind the TSO dashboard ring (160.0 total shows 92.5 percent) | A | 3b | none: unknown; confirm; the ring basis is unproven and is labelled |
| MQ-41 | Final Submit: time-gated, blocked by "SR Not Set" routes, does it lock the day, who reopens it | A | 3b | D-198 |
| MQ-42 | TSO By Segment Value Contribution and By Brand Call/Memo Ratio: chart types; is "Maxim - Platinum Series" a brand, variant or series | L | 3b | none: unknown; confirm; a table with the number is the fallback |
| MQ-43 | What Status "exist" and "Delete Section Data" remove: web-entry rows only or app-synced memos too; does Final Submit lock edits | A, L | 4c | D-40 |
| MQ-44 | How the web cut-off date is produced: rolling, configured or the Sales Plan Data Entry Date | A | 4c | D-97, D-439 |
| MQ-45 | DS-RRS: meaning and print layout; where the DSS route link goes | A, S | 4b | D-185 |
| MQ-46 | Web Entry: purpose of Brand Data, the Sale formula, the Classifications list; how often TSOs use Web Entry and Astha Web Entry | A | 4c | D-40, D-437 |
| MQ-47 | Column sets of every Excel report, the QC PDF and print views: sample files | S | 4b | D-191 |
| MQ-48 | Menus and permissions of the DMO, WM, WMO, Top Management, AMO and admin web logins | L | 4a | D-185, D-502, D-503 |
| MQ-49 | How a user resets a web password today; is User ID case-sensitive | A | 0c | D-102 |
| MQ-50 | Distribution channel: side-loaded APK or Play; one APK with flavours | A | 0c | D-10, D-562 |
| MQ-51 | Map provider, quota and cost; is a 3D map required | A | 3a | D-08 |
| MQ-52 | Does Apsis record SR location continuously or per visit or sync; is a last-synced live map acceptable | A, X | 3a | D-170 |
| MQ-53 | Real device fleet: models and Android versions | X | 0c | D-11, D-12 |
| MQ-54 | Is refusing logout while rows are unsynced acceptable, even for the TSO used to a wipe | A | 3b | D-69, D-174 |
| MQ-55 | Is call audio recorded (microphone permission, voicerecording page) | A | 2e | D-115 |
| MQ-56 | Memo numbering across cutover and what the retailer expects printed | A, S | 1a | D-35 |
| MQ-57 | Physical 58 mm samples: cash, credit with partial payment, zero sale, edited, reprint, stock slip, day summary | S | 1a | D-76 |
| MQ-58 | Are the two memo layouts (credit and cash) two templates | A, S | 1a | D-76, D-158 |
| MQ-59 | Printed digits: Bangla or Latin | A, S | 1a | D-76 (the digit script follows the UI language until answered, F-SYS-051) |
| MQ-60 | Which English labels stay English for retraining parity | A | 0c | D-338 (doc 15 s11) |
| MQ-61 | Leave: statuses beyond "DMO approval pending", multi-day applications, any balance | A, L | 3b | D-176, D-197 |
| MQ-62 | Feedback: categories beyond "Suggestion", the My Feedback sub-menu, image limit | L | 3b | D-197 |
| MQ-63 | Task Type options for the TSO, the default due date, the outcome after Submit with delegate No | L | 3b | D-167, D-199 |
| MQ-64 | Is a per-zone submitted badge wanted on the Final Submit "all zones" callout | A | 3b | D-199 |
| MQ-65 | What the SR Summary screen and the red card under the KPI tiles are | L | 2a | none: unknown; confirm; built to the observed layout (doc 15 s3) |
| MQ-66 | Tutorial list and player layout; are the four manuals the content | L | 2a | none: unknown; confirm; empty state only (doc 15 s3) |
| MQ-67 | Are the display glitches Apsis bugs or real data (Sale Data footer 20,260 versus 20,660; Babu Store Wing; STD Memo footer versus rows) | X | 7a | D-211, D-240 |
| MQ-68 | What the Apsis Stock screen does on a second Save: replace the loaded total or add to it (found in round 3, G-qa-115) | A, L | 2a | D-580 (increment-only Save with a correct-total path, F-SR-081, F-AMO-049) |

Ranges by theme (count, blocks): MQ-01 to MQ-09 units and money (9, 2a); MQ-10 to MQ-19 day flow (10, 1a to 3a); MQ-20 to MQ-28 programmes (9, 3a to 5a); MQ-29 to MQ-32 outlets (4, 3a); MQ-33 to MQ-42 targets and KPIs (10, 3a to 3b); MQ-43 to MQ-49 web back office (7, 4a to 4c); MQ-50 to MQ-55 platform (6, 0c to 3a); MQ-56 to MQ-59 printed outputs (4, 1a); MQ-60 strings (1, 0c); MQ-61 to MQ-64 TSO app (4, 3b); MQ-65 to MQ-66 SR app (2, 2a); MQ-67 data quality (1, 7a); MQ-68 stock (1, 2a).

### 4.5 What AKTCL must hand over, and when

| # | Item | Needed for | Who supplies | By | Decision |
| --- | --- | --- | --- | --- | --- |
| 1 | Baseline pack: screen recordings of a full SR day and of the AMO and TSO flows on a pilot phone; at least 25 printed memos with the entered quantities (cash, credit with partial payment, every live promotion group, DRP/slide, zero sale, edit, stock memo, summary); screenshots of all 37 web pages; one month of Excel exports of every report for 3 pilot zones; string glossary; the four manuals | Parity oracle; Phase 1 entry | AKTCL staff with AKTCL accounts (retailer data masked); a NAMED owner and a named fallback, both named by the 0a exit (D-565) | week 3 (0c exit) | D-142, D-312 |
| 2 | Physical 58 mm samples: cash memo, credit memo with partial payment, zero sale, edited, reprint, stock slip, day summary; the Bangla or Latin digit choice on paper | Golden prints | Sales operations | week 4 (1a exit) | D-76, D-158 |
| 3 | Live promotion catalogue (about 22 groups) with rules and validity windows | 2a entry | Trade marketing | week 7.5 | D-33 |
| 4 | Fleet census: device models, RAM, Android versions, share of each | Reference devices, minSdk, battery classes | IT and TSOs | week 3 (0c exit); a hard deadline, because the census decides the lab, the battery classes and the 0c exit (D-564) | D-11, D-12, D-564 |
| 5 | Captures that answer the 31 questions of s4.4 that the live app can answer (answerer L: Joint Call items 4 and 5, the three per-SKU indicators, edit reasons, closed-shop outcome, requested screens, the second Save of the Stock screen) | Per MQ due date | AKTCL staff | as each sub-milestone opens | D-312 |
| 6 | Sample .xlsx file of each Excel report and the QC PDF | 4b report parity | Sales operations | week 21 | D-191 |
| 7 | A day with real submits on the web Login/Submit Status page | Submit % (of logged-in) basis | Sales operations | week 21 (3b exit) | D-45 |
| 8 | Apsis dump in the shape of the dump-request letter, including password-hash algorithm; the delta contract of doc 16 s12.6 (per wave and nightly) with a named delta owner | 7a | Legal and sales operations | week 23 (dump); contract at 0c exit | D-303, D-119, D-514 |
| 9 | Per-route daily Apsis figures for the pilot routes (memo count, STD per SKU, value, dues) | 7b | Sales operations | week 25 | D-154 |
| 10 | Helpdesk facts (exists, hours, channels, languages), staffing, native-Bangla reviewer | Strings and 7c entry | Support lead | week 8 (reviewer), week 32 (helpdesk) | D-149 |
| 11 | Legal opinion on residency and PII, location-notice text, audit and photo retention; the residency, PII and notice rows are due at the 7a entry (D-570), the retention rows by 6c | 7a entry (7b blocked without it); 7c entry | Legal and HR | week 25 (7a entry); retention rows week 32 | D-05, D-107, D-120, D-570, D-113, D-132 |
| 12 | Usage census: page and endpoint visit counts from AKTCL's own Apsis admin or audit views, distribution-house and DMO or WM interviews (baseline-pack item i) | Parity Exceptions Register, 6a scope | AKTCL IT and sales operations | 0c exit | D-502, D-503 |
| 13 | Evidence files and the precedence text: manual inventories, delta register and V-* files committed; CLAUDE.md and README amended with the text of s9.2b | all gates that cite them | Sponsor | before kickoff (Sunday 2026-10-11); 0a exit at the latest | D-530, D-534, D-554 |
| 14 | Support contact log with the real L1 tooling during the pilot, by category (SR, AMO, TSO, retailer dispute) | wave 1 staffing | Support lead | 7b exit | D-553 |
| 15 | The Board envelope: Apsis contract end date and monthly fee, the confirmed rate and headcount, the latest wave-1 date the Board accepts | The programme cost envelope of s2.5b; wave scheduling | Sponsor | 0a exit | D-555 |
| 16 | The APK channel decision and, if Google's rule applies, the verified-developer registration for the three package ids | Updater design; pre-bind day installs | IT and sponsor | 0c exit | D-10, D-562 |
| 17 | BI path facts: Fabric capacity, VNet data gateway, Power BI Pro licence count | 4c dashboards and the lake | IT | 0c exit | D-560 |
| 18 | Rollback capacity: trained keyers per wave or an Apsis bulk-import path for dues | The per-wave rollback statement | Sponsor, sales operations, finance | 7b exit | D-592 |

Proved by: T-0-49 (baseline pack), T-7-80 (dump), T-7-84 and T-7-85 (wave readiness).

## 5 Deliberately changed from Apsis

PARITY FIRST is the sponsor's rule: the pilot reps must see the same screens, labels and printed memo. A change from the observed behaviour exists only where it protects one of the eight CLAUDE.md constraints (DELIBERATE CHANGE), where the manuals show nothing and the change is an addition (IMPROVEMENT, DESIGN ADDITION), or where the spec itself changes (DEFAULT). Each row carries its decision; AKTCL signs it at the sub-milestone shown, and an unsigned change at that exit is raised under D-308. The same list, with fewer columns, is in DECISIONS.md section P.

| # | What differs | Observed (Apsis or spec) | New behaviour | Why | Decision | Flag | Sign-off by |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | Scope is resolved on the server | Browser sends all 1,051 zone ids | Reach derived from role and assignment, carried in the token; the client never sends scope ids | CLAUDE.md 4 | D-106 | DELIBERATE CHANGE | 0c |
| 2 | Reads come from aggregates | Dashboard aggregates 400 KB payloads in the browser | Web reads dw tables only; the web database role has no SELECT on transactional tables | R1; PROJECT-CONTEXT pain point | D-61, D-128, D-190 | DELIBERATE CHANGE | 4a |
| 3 | One codebase, three flavours | Three separate Apsis APKs | One Flutter codebase producing SR, AMO and TSO applicationIds that differ from Apsis so both coexist | least retraining; CLAUDE.md stack | D-01, D-205 | DESIGN CHANGE (invisible to users) | 1a |
| 4 | Anti-spoofing is a supervisor-visible flag | Reps defeat the geofence with fake-GPS apps | A mocked fix is never geo-valid; default policy warn_rep; only the mock warning is shown to the SR; hard block optional | CLAUDE.md 5; docs/05 | D-96, D-109, D-110, D-123 | DELIBERATE CHANGE | 2d |
| 5 | Battery and size budgets are release gates | About 90 MB installed footprint, known battery and data drain | APK at most 30 MB per ABI, 6 percent drain per scripted day, 1 MB per day without photos | CLAUDE.md 3; R4 | D-73 | DELIBERATE CHANGE | 1c |
| 6 | A photo does not silently move the outlet | Manual: 'location information will be updated' when the Force Sale or Manual Override photo is taken | Location-change request plus a provisional device location; immediate update only when the stored location is missing or a placeholder | CLAUDE.md 5; docs/05 | D-163, D-95, D-111 | DELIBERATE CHANGE | 2d |
| 7 | 'Live' team location is the last synced fix | AMO and TSO manuals call it live | 'last seen HH:MM (n min ago)' with source, grey after 120 minutes; no continuous tracking | CLAUDE.md 3 | D-170 | DELIBERATE CHANGE | 3a |
| 8 | TSO logout wipes only a reconciled device | TSO logout wipes all app data with no pending check | Refused with 'N items not yet sent' unless everything is synced; SR and AMO never wipe | CLAUDE.md 1 and 2 | D-69, D-174 | DELIBERATE CHANGE | 3b |
| 9 | 'Delete Section Data' becomes an audited void | Button on the web Final Submit page, scope unknown | Void with reason and tombstones, only before Final Submit, web-entry rows by default | CLAUDE.md 2; docs/03 never hard-delete | D-40, D-182 | DELIBERATE CHANGE | 4c |
| 10 | No OTP re-ask after an in-place update | Manual re-asks the OTP after a new version | Off by default (cfg.auth.reverify_on_new_version false); parity setting kept for the pilot | CLAUDE.md 1; about 8,500 TSO lookups per release | D-80, D-164 | DELIBERATE CHANGE | 2e |
| 11 | Zero target shows a dash | Manual prints 0.00 percent for a zero target | NULL shown as a dash; negative targets rejected at entry | KPI correctness (the -37,500 percent Astha defect) | D-50 | DELIBERATE CHANGE | 3a |
| 12 | AMO Outlet badge hidden at zero | Red dot shown at 0 | Shown only above zero | usability; definition unverified | D-169 | DELIBERATE CHANGE | 3a |
| 13 | Two named submit percentages | docs/10 web: submitted / target routes; apps: / logged-in routes | 'Submit % (of logged-in)' (parity) and 'Day-completion %' stored and returned together | seed finding 7 | D-45, D-177 | DELIBERATE CHANGE (rename of the docs/10 figure) | 3b |
| 14 | Money in milli-taka | CLAUDE.md wording: minor units | bigint _mtk with MONEY_SCALE 1000 because 23 price values on 20 of the 42 SKUs have three decimals | seed finding 1 | D-15 | DELIBERATE CHANGE (amends CLAUDE.md wording) | 0b |
| 15 | Memo number is a new device-composed series | Apsis memo series | <username>-<yyMMdd>-<seq3> with 500-number blocks per bind ordinal; imported Apsis memos keep their numbers | CLAUDE.md 1 (offline numbering) | D-35 | DEFAULT; retailer-facing | 1a |
| 16 | Local database is encrypted | Not observed in the current app | SQLCipher, per-user key wrapped by the Android Keystore | security; shared phones | D-67 | IMPROVEMENT | 1b |
| 17 | Version gate blocks only a new day's login | Updater with an unknown-sources hop; the 'must' is a local migration | min_version blocks a new-day login only, never capture or upload; resumable, hashed download | CLAUDE.md 1 and 2 | D-79 | DESIGN ADDITION | 2e |
| 18 | No microphone permission | Current AMO build requests the microphone | Camera only; no RECORD_AUDIO | privacy; no recording UI exists | D-115 | DELIBERATE CHANGE | 2e |
| 19 | TSO default language and Settings | TSO app is English-only with seven drawer entries and no Settings | Default locale per role (SR bn, AMO bn, TSO en) with a language switch; a Settings entry added | CLAUDE.md 8 | D-181, D-204 | IMPROVEMENT | 3b |
| 20 | Final Submit confirm dialog | No confirm seen before the irreversible submit | Explicit confirm dialog | usability | D-198 | IMPROVEMENT | 3b |
| 21 | Visit outcome codes | Zero sale is the only non-productive record (unconfirmed) | Outcome per visit (sold, zero sale, closed, owner absent, refused, not reached, abandoned) | KPI clarity; Q50 | D-38 | IMPROVEMENT | 2a |
| 22 | Memo void after print | Whether Apsis allows cancelling is unknown | memo_void event with reason, due reversal, stock back and a cancel slip | audit trail; docs/04 immutability | D-86 | IMPROVEMENT | 2b |
| 23 | Day exceptions and route cover | None observed | day_exception approved by the TSO; AMO-assigned same-day cover; acting_for attribution | field reality | D-39, D-85 | IMPROVEMENT | 3a |
| 24 | Due receipts, FIFO ageing and stale-balance marker | None observed | Receipt print, ageing ledger for reporting, marker on the previous-due line | retailer disputes | D-37 | IMPROVEMENT | 2b |
| 25 | Sale History works offline for 7 days | Online-only in the current SR app | Local window of 7 business days plus an online fallback for older dates | CLAUDE.md 1 | D-83, D-206 | DELIBERATE CHANGE | 2b |
| 26 | Reprint carries a duplicate marker | Reprint and Edit share one screen; paper behaviour unknown | Reprint printed as duplicate | double-billing risk (UI-SR-29) | F-SR-066 (doc 15) | IMPROVEMENT | 2b |
| 27 | First-wave radii come from the pilot | Radius per territory unknown in the dump | No radius is calibrated before two weeks of fixes per geo class exist; global default 100 m until then | docs/22 P-10 | D-93, D-254 | DEFAULT | 7c |
| 28 | Parallel-run print is a watermarked test print | Not applicable | Test print says it is not a receipt and carries no previous-due line | no double billing in the pilot | D-152 | DEFAULT | 7b |
| 29 | Apsis credentials are never reused | Dump may carry hashes or tokens | Verify then re-hash at first login, or temporary passwords; never used against a live service | CLAUDE.md guardrail | D-119 | LOCKED-BY-SPEC | 7a |
| 30 | "Delete Section Data" on the web Final Submit page | Delete on the "exist" row with no confirm and an unknown scope (G-man-086) | An audited void with a confirm, a reason of at least 10 characters, default scope web-entry rows, app memos only for ops_admin with two-person approval, only before Final Submit | CLAUDE.md 2: a destructive delete collides with idempotent sync | D-182, D-438 | DELIBERATE CHANGE | 4c |
| 31 | A Sales Submit can be undone | Whether Apsis allows it is unknown | Void within cfg.day.submit_undo_window_min (120 min) by the TSO, or by L1 support with the TSO's confirmation, audited; the day reopens with `submit_seq` kept | field reality: a wrong submit at 17:00 must not need an engineer | D-539 | IMPROVEMENT | 3b |
| 32 | Daily field telemetry | None | A 1 KB `telemetry.day` object: bytes, CPU, wake-lock, engine starts, battery at fixed times; no location, no identity beyond the device | R4 and R5 need field evidence, not lab numbers | D-507 | DESIGN ADDITION | 4a |
| 33 | Paper-memo backfill | None observed | Supervised backfill within cfg.entry.paper_backfill_window_days with entry_source manual and a second person | a dead phone must not lose a sale | D-543 | IMPROVEMENT | 2e |
| 34 | Emergency non-working day | The calendar is fixed data | A same-day emergency_off declaration (monsoon, hartal) by ops_admin, limited to cfg.calendar.emergency_max_days | denominators and alerts must not call a hartal a failure | D-542 | IMPROVEMENT | 2e |

Behaviour that looks like a change but is parity, confirmed against the manual pages: dues warn and never block Sales Submit (D-173); mark-as-paid settles the whole memo (D-37); the memo commits at the first confirm and printing is optional (D-77); the call-start prompt after the geo gate (D-78); the credit checkbox and the QC button are independent (D-203); zero-sale review is printable (D-202); a 4-digit view-only OTP (D-103); cap at 100 on cards and uncapped detail tables (D-50); the TSO, AMO and web tiles show Submit % (of logged-in) (D-45).

Vendor strings are never reproduced (D-244): "Apsis", "Firefly Outlets Reports", "Developed by Apsis Solutions", the manual's example password and slide titles.

Proved by: T-0-46 (each D-id resolves), T-2-41, T-2-49, T-4-41 (parity), and the sign-off lines of the exit reports.

## 6 Risk register

Thirty-three project risks, RK-01 to RK-33 (not the sponsor requirements R1 to R6, the rejected recommendations R-01 to R-34, nor the security residual risks RR-n of doc 21 s11). L is likelihood and I is impact before mitigation (H, M, L). Rows 1 to 15 are the ranked top 15; rows 16 to 33 are tracked at the same review (RK-26 to RK-28 were added by the round-2 review and RK-29 to RK-33 by the round-3 review). Owners are roles (s9.1). The early-warning gate is the first test or event that shows the risk materialising; the register is reviewed at every sub-milestone exit and every Wednesday confirmation review (s4.1).

| ID | Risk | L | I | Owner | Mitigation | Early warning (gate) | Phase |
| --- | --- | --- | --- | --- | --- | --- | --- |
| RK-01 | Device fleet is unknown (models, RAM, Android versions); battery and data budgets are measured on the wrong phones; minSdk 26 may exclude devices (G-qa-05) | H | H | Ops lead, QA lead | Census through the TSOs, returned by the 0c exit as a hard deadline (D-11, D-12, D-564); every battery gate is stated per battery-size class; lab holds 2 each of a Redmi 9A class, a Samsung A03 Core class and an Android 8.x 2 GB phone; budgets re-run on the census top three models; legacy-device TLS and font checks | T-0-48 (lab), T-1-45 (first perf file) | 0a to 1c |
| RK-02 | Printed memo layout and memo-number expectation are unknown: the manuals never show the paper and the retailer sees it (G-sync-02, G-data-08) | H | H | Sales ops, App lead | Physical 58 mm samples by 1a (D-76, D-158); template is data (cfg.print.template_version) with seven memo kinds and golden prints; new device-composed number decided by 1a (D-35); retailer-facing sign-off named (D-146) | T-1-35 (golden print), T-2-41 (memo parity) | 1a, 2a |
| RK-03 | Promotion catalogue unknown: about 22 groups, and automatic discounts exist beyond DRP (home card shows 437.50 total discount) (G-feat-13) | H | H | Trade marketing, API lead | Catalogue is a 2a ENTRY condition (D-33, D-143); offer master is versioned data; every group gets at least 3 golden memos; the baseline pack includes every live group | T-2-42 (corpus), T-5-41 | 2a |
| RK-04 | Apsis dump late, incomplete or aggregate-only: the sample has no SR, memo number, price, discount, dues, loyalty, photos or fixes (docs/22), so dues, loyalty and targets cannot be rebuilt (G-qa-03, G-sec-03) | H | H | Legal, Data lead | Letter issued week 1 (D-303); importer built against the synthetic generator with planted defects P-05 to P-16; path B for aggregate-only months; opening balances re-keyed from Apsis reports as a last resort; sponsor escalation if no delivery date by week 12 | T-7-80 (control totals); no confirmed date by week 12 | 7a |
| RK-05 | Legal position unknown: data residency (no Azure region in Bangladesh), PII, employee-location notice, audit retention (G-sec-01) | M | H | Legal and HR, Sponsor | Ask in week 1; the residency, PII and notice answers are due at the 7a entry, not 7c, because real owner names and phone numbers land at 7a and employee location at 7b (D-570); Phases 0 to 6 in Southeast Asia (D-05); the region-outage RTO is MEASURED by the timed geo-restore drill (T-4-174, RB-52) and written here and in the day-one checklist, not assumed; NID, TIN and licence envelope-encrypted (D-107); notice text (D-120); fallback is to restrict phone and owner fields in BI and exports | T-7-84 checklist line; T-4-174 (measured RTO); T-7-160; opinion not received by week 24 | 7a, 7c |
| RK-06 | First morning of a wave: password login (Argon2id), device bind, full bundle and APK download with no jitter; 30 hashes per 2 GiB replica exhaust memory (G-sre-04) | H | H | Infra lead, Sales ops | Pre-bind day T-1 at the distribution house on Wi-Fi (D-126, Q52); per-replica hash limiter; 2 vCPU and 4 GiB wave-morning revision; pre-scale schedule (D-133); jitter | First-morning storm test (SRE gates T-7-57..59), T-7-84 | 7c |
| RK-07 | A DR or PITR failover loses rows that devices believe are synced (G-sre-01); supervisors see counts rise again | L | H | SRE lead, Sponsor | Server-generation re-sync (D-63); RTO 4 h, RPO 15 min (D-127); DR rehearsal includes the generation change; business tolerance asked (Q54) | T-1-25..34 (re-sync), T-7-54 | 1b, 7c |
| RK-08 | A poison row stalls a device forever, or one server bug 500s every batch (G-sre-03) | M | H | API lead | Per-record savepoints, quarantine and admin retry; client skip-ahead after 5 failures (D-65) | T-1-25..34 | 1b |
| RK-09 | No machine-readable Apsis daily data for the parallel run (Q32): the pilot cannot compare automatically (G-qa-03) | M | M | Sales ops | Ask by 2e; fallback is manual keying from photographed summaries for the pilot only (D-154); daily sheet with categories A to E | T-7-82 day 1 | 7b |
| RK-10 | No helpdesk function for 8,500 reps: at an assumed 3 to 5 percent contact rate a full-fleet wave day is 250 to 425 calls (ASSUMPTION, doc 20 s7) (G-qa-04) | H | H | Support lead | Tiers 0 to 3, 8 to 12 Bangla agents, top-20 scripts, TSO toolkit (OTP, temporary password), support code on blocking screens, device lookup (D-149, D-138) | T-7-85 (support readiness) | 7c |
| RK-11 | Duplicate or lost sales from an idempotency flaw, the single most important correctness rule (CLAUDE.md 2) | L | H | API lead, QA lead | Three layers (D-21); batch replay (D-62); fuzz of 10,000 batches on every PR and 100,000 nightly; kill tests; reconciliation per route-day; tombstones for voids | T-1-51, T-1-25..34, T-7-87 | 1b |
| RK-12 | A false fleet-wide rollback at 17:00 because the submit event outruns its rows (G-sre-02) | M | H | SRE lead | day_submit is the last record of the day; server defers the transition until totals meet the device claim (D-64); triggers read only after settle | T-1-25..34, T-7-86 | 1b, 7c |
| RK-13 | A config mistake locks the fleet (radius 10 m, a min_version typo) (G-cfg-04) | M | H | API lead, Sponsor | Bounds in the database; two-person approval, canary and 10-minute delay for C2 and C3; two-sided anomaly watch; break-glass restrictive-only; change-freeze windows (D-88, D-93, D-100) | T-2-60..69, T-6-41 | 2d |
| RK-14 | Battery or data budget missed on cheap phones (SQLCipher cost, radio-environment capture, photos) | M | H | App lead | Budgets are release gates per candidate (D-73); SQLCipher fallback rule (D-67); passive radio reads only (D-110); per-release perf file | T-1-45, T-2-40 | 1c, 2e |
| RK-15 | The geofence is not a control in dense markets and fake GPS persists: 80 percent of outlets share a 55 m cell (docs/22 P-10) | H | M | Sales ops, Security owner | Radius per zone and outlet with a density view; first radii only from two weeks of pilot fixes (D-93); mock invariant (D-96); radio environment and supervisor Exceptions (D-109, D-110); residual risks written down (D-124) | T-2-47, T-2-10..19 | 2d, 7c |
| RK-16 | Parity gaps surface late: only 31.6 percent of the manual inventory was fully covered before this plan, 63 items were contradicted and 30 of 67 questions need the live app | H | M | Product owner | The 104 register entries each have an owner doc and a closing sub-milestone (s7); MQ schedule (s4.4); captures by AKTCL staff (D-312); manual-parity gates T-x-120..139; dress rehearsal | T-2-48, T-7-82 | 2e, 7b |
| RK-17 | Sponsor's delegate or key people unavailable: every human-verified gate waits on them | M | H | Sponsor | Delegate and a deputy named at 0a (D-156); demos of 30 minutes; one-page scripts; engineering gates still close without the delegate and business gates hold | Any exit report waiting more than 2 working days for a signature | 0a on |
| RK-18 | Schedule slip: weeks are ASSUMPTION; sequential sum is 47 weeks after the D-515 re-baseline; Ramadan and two Eids fall inside; wave blackout rules (D-311) | H | M | PM, Tech lead | Re-baseline at 1c exit (D-307); float of 2 to 4 weeks before the planning date, of which 2 weeks are the pilot reserve, so the unreserved float is 0 to 2 weeks (s2.3, s2.5, D-307 as amended); the cost of each week of delay and the half-team scenario are in s2.5b (D-555); scope changes only through DECISIONS.md; programme and CRUD work overlaps the pilot | 1c exit more than 1 week late | all |
| RK-19 | Wrong business date or clock tampering near midnight and month end (G-fraud-02) | M | M | API lead | Trusted monotonic anchor (D-20); parking rule for rows claiming a closed month (DQ-40); rollover tests | T-1-52, T-0-47 | 1b |
| RK-20 | Bangla rendering and printing: conjuncts on legacy phones, raster printing on the RPP02N, digit script | M | M | App lead, native reviewer | Bundled Noto Sans Bengali; raster printing (D-76); goldens in Bangla; native review of all SR strings; digits follow the UI language (doc 15 s11) | T-1-35, T-2-43, T-2-49 | 1a, 2a |
| RK-21 | Distribution on shared phones: side-load and unknown-sources hop; an updater failure strands phones with pending rows; a bad build has no rollback (G-sre-20) | M | H | Release manager | Two-stage updater with resume and SHA-256 (D-79); additive local schema; rescue release within 24 h; staged wave_pct; N-2 server compatibility | T-2-44 (upgrade matrix), T-2-46 | 2e |
| RK-22 | AKTCL cannot make the Apsis app read-only per wave: reps double-enter and data diverges (G-feat-67) | M | H | Sponsor, IT | Ask under the contract by 7b; wave rollback by flag flip in the new app; export of new-app data in the Apsis dump shape (D-148) | T-7-83 (rollback drills) | 7b |
| RK-23 | Programme outlets are bound before their programmes exist: Astha tiers hold about 76,100 outlets (10.4 percent), so zone exclusion may remove nearly every zone (G-14-03) | H | M | Product owner, PM | Test at the 7b entry review (D-306); 5a before wave 1 if above 10 percent; pilot excludes programme outlets (D-305) | T-5-41; candidate-territory count at 7b entry | 5a, 7c |
| RK-24 | Azure quota lead times (ACA cores, PostgreSQL vCores, Load Testing engines) and unverified costs | M | M | Infra lead, Finance | Requests in 0a (D-133); alert at 80 percent; pricing calculator before any budget (D-06) | T-7-55 (quotas approved); a ticket still open at week 12 | 0a, 4d |
| RK-25 | Credential sharing, supervisor takeover and PII over-exposure on shared phones and web exports | M | M | Security owner | Device proof and attestation (D-104, D-105); takeover hold (D-112); row budgets, watermark and export log (D-121); residual risks recorded (D-124) | T-4-70..76, T-7-70 (pen test) | 4c, 7c |
| RK-26 | Float is almost gone: the D-515 re-baseline took 2 weeks, the pilot reserve (D-529) holds 2 more, so the unreserved float before the wave-1 planning date is 0 to 2 weeks (s2.3, G-qa-38, G-qa-54) | H | M | PM, Sponsor | The schedule of record is s2.1 and s2.3 is derived from it; `lead_time_h` computes the calendar path (T-0-155); a third pilot reset or a 5-week overrun triggers a sponsor re-plan; moving the planning date by up to 4 weeks is preferred to cutting a gate | 1c exit more than 1 week late; the first pilot reset (T-7-159) | all |
| RK-27 | The Apsis delta feed that every wave night depends on is not requested, not owned or fails on a wave night (G-qa-37) | M | H | Sales ops, Legal, IT | Delta contract in the dump-request letter (D-514); a named delta owner and an AKTCL-staff extraction fallback; a breach defers the wave; the path must run successfully twice before wave 1 | T-7-150 to T-7-152; the first nightly feed after 7a | 0c, 7a, 7c |
| RK-28 | A wave rollback strands retailer dues, stock and credit memos in the new system while the reps return to Apsis (G-qa-81) | M | H | Sponsor, Sales ops, Finance | Return path: a named, staffed re-key procedure for 3 days after a wave, then fix-forward only; Apsis mirrored nightly for comparison; owner on the war-room roster (D-549); the capacity of that procedure beyond wave 2 is RK-33 (D-592) | T-7-153 | 7b, 7c |
| RK-29 | APK distribution: a sideloaded 30 MB APK on 8,500 shared phones meets Google's developer-verification rule for installs outside Google Play (announced for first countries from September 2026 and global in 2027), Play Protect warnings and "install unknown apps" friction on Android 14 to 16; wave 1 falls inside that window (G-qa-94) | M | H | IT, Release manager | The channel is decided at 0c (D-10 moves from 2e; D-562): a verified-developer registration for the three package ids, a Managed Google Play private app or a lightweight MDM; the Bangla install guidance is part of the readiness screen; install success at least 95 percent on the pre-bind day is part of every go/no-go | T-2-164, T-7-166 | 0c, 2e, 7c |
| RK-30 | Apsis contract end and the cost of delay are unknown: a slip makes AKTCL pay for two systems, or the vendor can stop service before the last wave (G-qa-87) | M | H | Sponsor, Finance | The Board envelope (s4.2c, D-555): contract end date and fee, the programme cost with a cost per week of delay, the half-team scenario and the latest wave-1 date are answered at 0a or carry the planning-basis default; s2.5b gives the compress or extend choice; the Apsis contract is read at every sub-milestone exit | T-0-162; any exit report that moves the planning date | 0a on |
| RK-31 | Programme outlets (Astha, Diamond League, Superstar; about 76,100 outlets) are bound without parity evidence: points, balances and gifts differ and retailers lose value (G-qa-93) | M | H | Product owner, Trade marketing | 7b-2 programme pilot of 3 to 5 programme-tier routes for 10 trading days against Apsis (T-7-165, D-561); D-41 and D-192 are a 5a ENTRY condition; programme-tier routes bind only after 7b-2 passes | T-7-165; the first programme comparison day | 5a, 7b |
| RK-32 | Key Vault signing is a hard dependency of login and refresh: an outage or a regional restore would stop token minting for 8,500 phones (G-qa-110) | L | H | Security owner, SRE lead | Delegated signing keys: replicas verify and sign with a 24-hour delegation, the root key is non-exportable and in a vault of its own with a DR copy; a restarted replica leaves the pool until it has a delegation; RB-48 (D-574) | T-4-173 (90-minute outage during S2); T-7-73 | 4d, 7c |
| RK-33 | The rollback lever exists on paper beyond wave 2: re-keying dues needs about 8, 38, 68 and 113 people for waves 1 to 4 and it is unknown how Apsis accepts keyed dues (G-qa-131) | H | H | Sponsor, Sales ops, Finance | Per-wave rollback statement (rollback possible, fix-forward only, or split) computed from measured keyer throughput and the committed capacity (D-592, doc 18 s7.5); waves above the capacity are fix-forward only and the go/no-go sheet says so; an Apsis bulk-import path is asked for | T-7-168; T-7-153 | 7b, 7c, 7d |

Proved by: the early-warning gates above; doc 20 s7 holds the readiness checklist that closes RK-06, RK-09, RK-10 and RK-22 per wave.

## 7 Gap roll-up

Sources merged: 299 lens and critic gaps (G-data 32, G-feat 67, G-scale 20, G-sync 20, G-cfg 22, G-sec 23, G-qa 24, G-analyst 16, G-field 23, G-fraud 26, G-sre 26), the 104 manual-register entries G-man-001 to G-man-104, and 9 gaps minted from the data profile: 412 ids. After merging overlaps there are 329 master gaps (82 alias ids folded into a master, 1 withdrawn id, G-feat-37), each with one owner document and one closing sub-milestone. This plan adds 8 of its own (G-14-01 to G-14-08), so the register stands at **337 master gaps: 37 blocker, 195 major, 105 minor**. A gap closes only when its owner document holds the closing text, the decision (D-id) and the gate (T-id) in its Traceability section; doc 20 s8 fails the build if a master id has no closing text by its phase (gaps.yaml). Each of docs 15 to 21 carries its own gap table; this section carries the counts, the blockers and the gaps doc 14 owns.

### 7.1 Counts by owner document and severity

| Owner doc | Blocker | Major | Minor | Total |
| --- | --- | --- | --- | --- |
| 14 | 0 | 6 | 6 | 12 |
| 15 | 3 | 61 | 47 | 111 |
| 16 | 12 | 34 | 19 | 65 |
| 17 | 10 | 18 | 7 | 35 |
| 18 | 2 | 16 | 4 | 22 |
| 19 | 4 | 24 | 8 | 36 |
| 20 | 3 | 8 | 6 | 17 |
| 21 | 3 | 28 | 8 | 39 |
| **all** | **37** | **195** | **105** | **337** |

Doc 14 holds 5 register gaps (G-qa-06, G-qa-07, G-cfg-18, G-fraud-18, G-qa-22) and 7 of its own; doc 20 holds 16 register gaps and G-14-01. The 329 master gaps of the plan skeleton plus these 8 give 337; the per-document tables of docs 15 to 21 keep the 329 and the doc 14 table of s7.5 adds the 8.

### 7.2 Counts by phase, sub-milestone and severity

| Phase | Blocker | Major | Minor | Total | By sub-milestone |
| --- | --- | --- | --- | --- | --- |
| P0 | 8 | 23 | 7 | 38 | 0a 13, 0b 13, 0c 12 |
| P1 | 14 | 26 | 8 | 48 | 1a 10, 1b 15, 1c 23 |
| P2 | 8 | 66 | 22 | 96 | 2a 22, 2b 13, 2c 12, 2d 20, 2e 29 |
| P3 | 0 | 29 | 29 | 58 | 3a 34, 3b 24 |
| P4 | 2 | 25 | 16 | 43 | 4a 12, 4b 12, 4c 14, 4d 5 |
| P5 | 1 | 10 | 8 | 19 | 5a 12, 5b 2, 5c 5 |
| P6 | 0 | 7 | 7 | 14 | 6a 9, 6b 3, 6c 2 |
| P7 | 4 | 9 | 8 | 21 | 7a 8, 7b 8, 7c 5 |

### 7.3 Owner document by phase

| Owner doc | P0 | P1 | P2 | P3 | P4 | P5 | P6 | P7 | Total |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 14 | 7 | 1 | 0 | 0 | 1 | 1 | 0 | 2 | 12 |
| 15 | 3 | 2 | 39 | 42 | 11 | 10 | 3 | 1 | 111 |
| 16 | 10 | 12 | 11 | 10 | 13 | 3 | 1 | 5 | 65 |
| 17 | 0 | 16 | 16 | 3 | 0 | 0 | 0 | 0 | 35 |
| 18 | 2 | 8 | 7 | 0 | 4 | 0 | 0 | 1 | 22 |
| 19 | 4 | 3 | 10 | 0 | 6 | 3 | 10 | 0 | 36 |
| 20 | 5 | 1 | 1 | 0 | 1 | 0 | 0 | 9 | 17 |
| 21 | 7 | 5 | 12 | 3 | 7 | 2 | 0 | 3 | 39 |

### 7.4 The 37 blockers, with owner and closing gate

Blockers are closed first within their sub-milestone. "Gate" is the gate family of the closing sub-milestone (s2.1); doc 20 s3 names the exact test.

| Gap | Blocker (short) | Owner | Closes at | Decision | Gate |
| --- | --- | --- | --- | --- | --- |
| G-qa-01 | No parity oracle: no printed memos, screen recordings, string list or Apsis report exports are captured anywhere; parity gates... | 20 | 0a | D-142 | T-0-40..49 |
| G-cfg-02 | No runtime config store; ~240 parameters hard-coded in prose | 19 | 0b | D-87 | T-0-01..04, T-0-60..64 |
| G-data-01 | No idempotency key; retried batch doubles rows (seed #2) | 16 | 0b | D-21 | T-0-01..04, T-0-60..64 |
| G-data-02 | No capture-time route/assignment/price-list/config context; history re-writes itself when masters change (seed #3) | 16 | 0b | D-20 | T-0-01..04, T-0-60..64 |
| G-data-03 | paisa cannot hold 3-decimal prices (seed #1) | 16 | 0b | D-15 | T-0-01..04, T-0-60..64 |
| G-data-04 | No unit; every volume KPI undefined (seed #8, Q8) | 16 | 0b | D-16, D-17 | T-0-01..04, T-0-60..64 |
| G-data-05 | submit percentage two definitions (seed #7) | 16 | 0b | D-45 | T-0-01..04, T-0-60..64 |
| G-feat-11 | Device OTP issuance surface and launch-day throughput | 21 | 0c | D-103 | T-0-70..76 |
| G-data-08 | No human memo number; offline numbering undefined (Q5) | 17 | 1a | D-35 | T-1-20..24, T-1-35, T-1-41 |
| G-sync-02 | The current printed memo layout (fields, order, Bangla/English mix, widths, totals rounding) is not captured anywhere in the sp... | 17 | 1a | D-142, D-158 | T-1-20..24, T-1-35, T-1-41 |
| G-data-06 | No quarantine, conflict or registry table; rejected rows vanish | 16 | 1b | D-65 | T-1-25..34, T-1-51, T-1-52 |
| G-fraud-02 | Clock-correction conflict: the offset-at-last-sync method is defeated by changing the clock after the last sync; after a reboot... | 17 | 1b | D-20 | T-1-25..34, T-1-51, T-1-52 |
| G-scale-01 | No batch-level idempotency or response replay | 17 | 1b | D-62 | T-1-25..34, T-1-51, T-1-52 |
| G-sre-01 | DR/PITR failover loses rows the devices hold as synced | 17 | 1b | D-63 | T-1-25..34, T-1-51, T-1-52 |
| G-sre-02 | day_submit can precede the rows it closes → Submit % (of logged-in) wrong at 17:00 → false fleet rollback trigger | 17 | 1b | D-64 | T-1-25..34, T-1-51, T-1-52 |
| G-sre-03 | One poison row stalls a device forever | 17 | 1b | D-65 | T-1-25..34, T-1-51, T-1-52 |
| G-analyst-01 | No memo-grain fact (dw.fact_memo) | 16 | 1c | - | T-1-01..04, T-1-60..69 |
| G-cfg-01 | Single admin role; no permission model for ~20 admins with different authority | 19 | 1c | D-91 | T-1-01..04, T-1-60..69 |
| G-data-07 | No dimensions, no event facts, no monthly/balance aggregates, no dirty-queue; "any dashboard anytime" not achievable | 16 | 1c | D-61, D-128 | T-1-01..04, T-1-60..69 |
| G-qa-05 | Reference devices are assumptions; battery/data gates measured on the wrong class of phone prove nothing for the fleet | 17 | 1c | D-12 | T-1-01..04, T-1-60..69 |
| G-scale-02 | Aggregation strategy unspecified; increments would create hot rows and double counts | 16 | 1c | - | T-1-01..04, T-1-60..69 |
| G-sync-01 | Device binding model for shared phones is undefined: users per device, devices per user, what happens to user A's pending rows... | 17 | 1c | D-66 | T-1-01..04, T-1-60..69 |
| G-feat-13 | Offer/promotion rule engine undefined (~22 groups, DRP offers) | 15 | 2a | D-33 | T-2-41, T-2-36..44 |
| G-man-002 | Memo total omits slide deduction, QC settlement amount and per-SKU max-QC | 16 | 2a | D-158 | T-2-41, T-2-36..44 |
| G-sync-04 | Rounding rule from milli-taka prices to the printed paisa totals (per line or per memo; half-up or banker's) has no owner; it d... | 16 | 2a | D-19 | T-2-41, T-2-36..44 |
| G-feat-02 | End-of-day stock return / reconciliation flow absent | 15 | 2b | doc 15 rule decision | T-2-21..24, T-2-43 |
| G-cfg-04 | Blast radius / mis-set protection absent (radius 10 m or min_version typo locks the fleet) | 19 | 2d | D-88 | T-2-10..19, T-2-60..69 |
| G-scale-03 | Bundle generation path not designed for the morning storm | 18 | 2e | - | T-2-31..35, T-2-47..57 |
| G-sre-04 | Wave first-morning storm not modelled: password + bind + full bundle + APK, no jitter; Argon2id memory × concurrency OOMs replicas | 18 | 2e | D-126 | T-2-31..35, T-2-47..57 |
| G-sync-03 | Behaviour when a new business day opens offline (stale bundle) is undefined; without a policy the SR either cannot sell or sell... | 17 | 2e | D-70 | T-2-31..35, T-2-47..57 |
| G-analyst-02 | Aggregates current-state only: no dim_target, no as-reported snapshot, no restatement log | 16 | 4a | - | T-4-41, T-4-51, T-4-52 |
| G-man-086 | Web Final Submit with 'Delete Section Data': a destructive delete the spec forbids, colliding with idempotent sync | 19 | 4c | D-40 | T-4-70..76, T-4-14 |
| G-feat-20 | Diamond League point earning rules absent | 15 | 5a | D-41 | T-5-41, T-5-10 |
| G-qa-03 | No machine-readable Apsis daily data for the parallel run; docs/11 says "compare daily" but names no source or format | 20 | 7a | D-154 | T-7-80..82 |
| G-sec-03 | Whether Apsis password hashes (algorithm, parameters) are in the dump decides between seamless login and a forced reset of 8,50... | 21 | 7a | D-119 | T-7-80..82 |
| G-qa-04 | No helpdesk function, hours, scripts or TSO toolkit defined for 8,500 reps on wave day | 20 | 7c | D-149 | T-7-88..89 |
| G-sec-01 | Bangladesh data-protection and data-localisation obligations are unknown; no Azure region in Bangladesh; PII and employee locat... | 21 | 7a (entry; was 7c) | D-05, D-107, D-570 | T-7-88..89 |

### 7.5 Gaps owned by doc 14 and how this document resolves them

| Gap | Sev | Statement | Resolution in this document | Closes at | Decision or gate |
| --- | --- | --- | --- | --- | --- |
| G-qa-06 | major | No business verifier or readiness owners are named; the docs/12 definitions of done have no sign-off | Every gate has a verifier role who is not the author (D-141); the delegate and the four readiness owners (engineering, operations, business, support) are a MUST-CONFIRM row due at 0a exit (s4.2) and a role table with a "named person" column filled at kickoff (s9.1); the 0a exit report records the names | 0a | D-156, D-141, T-0-46 |
| G-qa-07 | major | docs/12 Phase 2 and Phase 4 are too large to test as one unit; no sub-milestones or demos | 28 sub-milestones (2a to 2e, 4a to 4d and the others), each with entry, demo of at most 30 minutes, checkable exit and gate families (s2.1, s3) | 0a | D-141, s2.1 |
| G-cfg-18 | major | Several defaults are business decisions the spec does not give | Each is a MUST-CONFIRM row with owner, due date and proceed-with default (s4.2): no-location policy (D-95), business-date cutoff (D-20), weekend days (D-28), BSR denominator (D-47), CPR zero-sale rule (D-46, D-249), quantity unit (D-16, D-17), rounding mode (D-19), memo number format (D-35), PII matrix (D-108, D-207) | 0c | D-308, s4.2 |
| G-fraud-18 | minor | Enum and value mismatches with fraud effect between lenses | One value each: cfg.geo.mock_policy has three values (silent_flag, warn_rep, block_sale) default warn_rep (D-96); cfg.geo.max_accuracy_m 100 within 30 to 300 (D-264); offline unlock default 7 days within 1 to 14 (D-265, D-68); redemption over balance is accepted and flagged when the device's balance was sufficient and rejected when it knew it was not (D-266); debounce is 5 s (D-261); retired aliases are listed in doc 19 s3 | 0c | D-96, D-264, D-265, D-266 |
| G-qa-22 | minor | Wave scheduling has no blackout rules although docs/22 P-03 shows the calendar matters | Waves never start within three days of a month end, Eid or a holiday, nor on a Thursday (D-147); they start Sunday to Tuesday so T-1 is a trading day (D-311); the calendar table of s2.3 and s8.2 applies them to the planning date | 7c | D-147, D-311 |
| G-14-01 | minor | Gate-range placement differs between the plan skeleton and the quality lens for 4a, 4b, 7a, 7b and 7c (for example T-7-82 pilot pass sits in the 7a range) | Open item OI-14-01; s3 names gates by family and lens title and defers the placement to doc 20 s3 | 0a | doc 20 s3 places every id; this plan names gates by family |
| G-14-02 | major | No team, budget or start date is stated anywhere; every week and person-week figure is an ASSUMPTION | D-307 states the planning basis and its re-baseline at 1c; the sponsor confirms headcount and start date at kickoff (first-two-weeks list) | 0a | D-156, D-307 |
| G-14-03 | major | Astha tiers hold about 76,100 outlets (10.4 percent); the default of excluding programme zones from wave 1 may exclude nearly every zone | D-306 sets the test at the 7b entry review and the 5a fallback; RK-23 | 5a | D-306 |
| G-14-04 | minor | The pre-bind day T-1 cannot be Friday (weekly off-day): a Saturday wave has no trading-day T-1 | D-311 sets the weekday rule; a Saturday wave needs an approved Friday gathering | 7b | D-311 |
| G-14-05 | major | Apsis dump delivery date and legal route are unknown; no date is committed and the importer depends on it | D-303 issues the letter in week 1 and tracks the date; synthetic generator keeps the importer moving; RK-04 | 0a | D-303 |
| G-14-06 | minor | The plan skeleton lists D-20 as the 1a decision for the lighter and match unit question; that question is D-16 (due 2a) | The 1a slice sells cigarette SKUs only so the unit question (D-16) is due at 2a; the cutoff is the 1a decision (D-20) | 1a | D-16, D-20 |
| G-14-07 | minor | 4b (report set) and 6a are not 7c entry conditions although wave-1 supervisors need reports | Planned: 4b and 6a exit before 7c in the schedule; if 4b slips, wave-1 supervisors use sync-health and Daily Tracking while Apsis reports remain for the unswitched; OI-14-03 | 4d | s2.3, OI-14-03 |
| G-14-08 | minor | Delegate capacity is unknown: 28 demos, 8 exit reports and the human-verified gates need delegate hours | Demos are capped at 30 minutes and scripted (D-141); a deputy delegate is named (RK-17) | 0a | D-156, RK-17 |

### 7.6 The 15 most important manual findings and where each is resolved

The register lists 104 entries (2 blocker, 58 major, 44 minor), none owned by doc 14; each has one owner document and one closing sub-milestone, and several were corrected by verification (statements replaced, not the register text). The table shows where the fifteen findings land.

| # | Finding | Register entries (owner doc, closes at) | Decisions | Resolution |
| --- | --- | --- | --- | --- |
| 1 | Memo total omits the slide and QC deductions | G-man-002 (16, 2a) | D-18, D-158 | net = gross - offer discount - DRP discount - QC settlement; every non-zero component printed; negative net allowed as a credit by default |
| 2 | Web Delete Section Data collides with idempotent sync | G-man-086 (19, 4c) | D-40, D-182 | audited void with tombstones before Final Submit only |
| 3 | Quantities are in sticks; the Match and Lighter unit (piece, box or dozen) differs by screen | G-man-001 (G-data-04; 16, 0b) | D-16, D-17, D-248, D-194 | sticks entered; base unit per category, always labelled; lighter and match MUST-CONFIRM at 2a |
| 4 | Device OTP is server-created, 4 digits, view-only for the TSO | G-man-021 (G-feat-11; 21, 0c) | D-103, D-80, D-164 | encrypted reversible OTP; re-ask after update off by default |
| 5 | A photo silently moves the outlet | G-man-017 (21, 2d) | D-163, D-95, D-111 | location-change request plus provisional location |
| 6 | The two Submit % figures: Submit % (of logged-in) divides by logged-in routes, Day-completion % by target routes | G-man-066 (G-data-05; 16, 0b) | D-45, D-177 | two named figures, parity label per surface |
| 7 | Targets are per variant with approvals; till-date is a calendar pro-rata that differs per surface | G-man-068 (G-feat-24; 15, 5c), G-man-067 (16, 3a) | D-31, D-51, D-195 | target_set header with approval events; per-surface till-date keys |
| 8 | The TSO web portal is a back-office write tool with seven unspecified pages | G-man-085, 086, 087, 089, 099 (19, 4c), 046 (19, 5a), 092 to 094 (15, 4b), 048 (15, 5a) | D-40, D-185, D-190, D-182 | pages F-WEB-048..056, back-office tools in doc 19 s8 |
| 9 | Three SR screens missing: SKU Target and Achievement, Sale History, Outlet Points | G-man-053 (15, 2a), 013 (15, 2b), 040 (15, 5a) | D-58, D-206 | F-SR-056, F-SR-054, F-SR-055 |
| 10 | QC is a money deduction with six app and ten web fault types | G-man-003 (15, 2a), 089 (19, 4c) | D-34, D-159, D-183 | one qc_fault_type table, settlement column |
| 11 | Outlet requests are cluster-first with a web-only reject and approve | G-man-033 (15, 2c), 034 (15, 3a) | D-43, D-162, D-172 | one request queue, cluster and route stored |
| 12 | TSO logout wipes everything, synced or not | G-man-024 (G-sync-16; 17, 2e) | D-69, D-174 | wipe only on a fully reconciled device |
| 13 | No string catalogue and no authored offline, validation or failure text | G-man-101 (15, 0c), 102 (15, 1a) | D-244 | 251-entry catalogue plan, authored error set, five printed guard texts catalogued verbatim |
| 14 | Final Submit needs a read endpoint, "SR Not Set" handling and an audited back-date unlock | G-man-070, 071 (15, 3b), 087 (19, 4c) | D-82, D-198, D-97, D-262 | preview endpoint, no time gate by default, audited grants |
| 15 | "Live" location is not live; no map decision | G-man-058 (15, 3a), 059 (17, 3a) | D-170, D-08 | last synced fix with age; Google Maps 2D lite with MapLibre fallback |

Proved by: gaps.yaml closure check (doc 20 s8); each blocker's gate family above.

### 7.7 Gaps minted by documents 15 to 21 (87)

Each document minted gaps in its block of the skeleton (s5.8) and resolves them in its own table; they are rolled up here as the skeleton requires. They are not part of the 329 master gaps or the 8 gaps of doc 14 in s7.1 to s7.5.

| Owner doc | Blocker | Major | Minor | Total |
| --- | --- | --- | --- | --- |
| 15 | 0 | 2 | 7 | 9 |
| 16 | 0 | 8 | 12 | 20 |
| 17 | 0 | 5 | 10 | 15 |
| 18 | 0 | 7 | 5 | 12 |
| 19 | 0 | 2 | 8 | 10 |
| 20 | 0 | 4 | 4 | 8 |
| 21 | 0 | 6 | 7 | 13 |
| **all** | **0** | **34** | **53** | **87** |

| Gap | Doc | Sev | Phase | Gap in one line |
| --- | --- | --- | --- | --- |
| G-15-01 | 15 | major | 2a | Home tiles Sales Journey and KPI exist in AKTCL screenshots and in no manual or spec (UI-SR-01, UI-SR-02) |
| G-15-02 | 15 | minor | 2a | Outlet eligibility dot legend (colour to programme) and whether a dot hides when not eligible are unknown (UI-SR-22) |
| G-15-03 | 15 | major | 2a | Memo discount table lists quantity and value ("SL Match 3 / 0.00"); three readings fit the evidence (UI-SR-26) |
| G-15-04 | 15 | minor | 2b | What the Apsis printout shows on a reprint, and whether new paper (due receipt, cancel slip) is acceptable to retailers, is unknow... |
| G-15-05 | 15 | minor | 2a | Whether Save on Stock is blocked while the printer is not connected is unknown (UI-SR-17) |
| G-15-06 | 15 | minor | 4a | 13 spec web pages have no evidence in the TSO manual (Task Planner, By-Route Geo Capture, Campaign Gift Redemption, Discount, By O... |
| G-15-07 | 15 | minor | 3b | SR and AMO leave appear in no manual; the absence of a rep has no capture except day exception and cover |
| G-15-08 | 15 | minor | 2a | The per-user rule that hides Loyalty Point and Photo Capture on one SR account (10 versus 12 tiles) is unknown |
| G-15-09 | 15 | minor | 3a | Per-surface status vocabulary: web outlet request shows Pending, Verified, Approved but Rejected is never printed; statuses of tar... |
| G-16-01 | 16 | minor | 0b | `app.dhaka_date()` is IMMUTABLE on the assumption of a fixed +06:00 offset |
| G-16-02 | 16 | major | 2a | Per-line precision of one milli-taka may differ from Apsis by a paisa on a tie |
| G-16-03 | 16 | major | 1c | The lens left route x SKU, outlet and user x SKU daily aggregates unpartitioned (about 340,000, 450,000 and 170,000 rows a day) |
| G-16-04 | 16 | minor | 1b | No place for the policy of a data-quality rule |
| G-16-05 | 16 | major | 1b | The 13-month registry prune of the lens would keep 2.2 M rows a day for a year; replays beyond 48 hours cannot happen |
| G-16-06 | 16 | minor | 2a | QC saved after the memo is committed: is the printed memo reissued? |
| G-16-07 | 16 | minor | 4b | Nothing stops a report from reading `app` |
| G-16-08 | 16 | minor | 4a | "Same time yesterday" needs history by hour; the memo fact is too big to scan per tile |
| G-16-09 | 16 | major | 7a | Pre-cutover months have the 1 October structure, not the structure of their time |
| G-16-10 | 16 | major | 7a | The sales file gives no route or zone for an archived stub, so 277 M sticks cannot roll up |
| G-16-11 | 16 | major | 4c | Web entry and app memos for one route-day could be added together |
| G-16-12 | 16 | minor | 6c | Route x SKU daily history older than 25 months is only in the lake |
| G-16-13 | 16 | minor | 4c | BI sees exact outlet coordinates |
| G-16-14 | 16 | minor | 3a | The AMO reconciliation counts "price compliance" with no screen in any manual; the fields are an assumption |
| G-16-15 | 16 | major | 2a | Match entry unit and Lighter box size decide every volume figure for those categories |
| G-16-16 | 16 | major | 7a | `rollback_import` cannot delete a master a device row already references |
| G-16-17 | 16 | minor | 7a | Imported memos keep Apsis numbers while native ones use the new series: two number spaces in one report |
| G-16-18 | 16 | minor | 5a | Loyalty earning rules, expiry and the 199-point cap scope are unknown |
| G-16-19 | 16 | minor | 5a | Astha tier attribution when an outlet changes tier mid-quarter is not evidenced |
| G-16-20 | 16 | minor | 2b | The credit limit semantics are unknown, so DQ-56 only flags |
| G-17-01 | 17 | major | 1a | D-35's 3-digit `seq` allows only two disjoint 500-blocks, and "ordinal = bindings that date" lets two devices of one user share a ... |
| G-17-02 | 17 | major | 2e | D-64 (submit completes offline) contradicts D-173 and UI-SR-37 (enabled after sync) |
| G-17-03 | 17 | major | 1c | D-73's GPS and drain numbers come from a 50-outlet script; the median planned route has 64 outlets and p99 112; AMO and TSO data g... |
| G-17-04 | 17 | minor | 1c | Plan s3.1 cites T-1-35 for both golden print (1a) and the budget baseline (1c) |
| G-17-05 | 17 | minor | 1b | The planning draft assumed a family of at most 40 rows; a 60-line memo with QC lines is about 175 rows |
| G-17-06 | 17 | major | 1a | The planning draft's visit row is immutable yet carries an outcome known only at close, and its 180 s valve contradicts the 120-mi... |
| G-17-07 | 17 | minor | 1c | The planning draft says an empty periodic run costs under 50 ms; the `workmanager` plugin starts a Dart engine for each run |
| G-17-08 | 17 | minor | 1b | Server-generation re-send of about 3 M rows after a failover is an unthrottled herd |
| G-17-09 | 17 | major | 2e | F-API-031 revokes the refresh token at logout, but SR and AMO logout must keep uploading |
| G-17-10 | 17 | minor | 2e | PDA to Support cannot send the raw DB because it is encrypted under a device-bound key |
| G-17-11 | 17 | minor | 2e | A phone offline beyond `cfg.sync.max_backdate_days` loses its rows to quarantine without warning |
| G-17-12 | 17 | minor | 1c | D-30 says the login event is a full or delta bundle download; after a pre-bind day the morning request is a small delta, not a 304 and not a refresh (D-557) |
| G-17-13 | 17 | minor | 1a | A username containing "-" breaks the `memo_no` split |
| G-17-14 | 17 | minor | 2e | The reconciliation legacy rows (Sale, Stock) add pieces and dozens together; their measure is unknown |
| G-17-15 | 17 | minor | 2e | No stated rule for selling after Sales Submit on the same route-day |
| G-18-01 | 18 | major | 4d | The ingest registry is keyed by random UUID v4: at 3,300 to 8,000 inserts a second its index reads can exceed the IOPS bound |
| G-18-02 | 18 | major | 0a | One subscription would share the vCore and core quota between prod and a staging scaled to prod for tests |
| G-18-03 | 18 | major | 4d | A promoted replica does not inherit HA, backups, PgBouncer or all parameters |
| G-18-04 | 18 | minor | 4a | Azure Load Testing needs about 20 to 30 engines at 500 users each; the quota in D-133 is 10 |
| G-18-05 | 18 | major | 1c | Pre-generation cost per user and for the largest AMO zone are estimates |
| G-18-06 | 18 | major | 2e | With Front Door Standard the ACA ingress is public; locking it by the `X-Azure-FDID` header is unverified and service tags in ACA ... |
| G-18-07 | 18 | major | 2e | A KEDA `cron` pre-scale rule must coexist with multiple-revision traffic weights |
| G-18-08 | 18 | minor | 4a | Calendar-aware alert baselines need the working-day calendar as data in the dw date dimension |
| G-18-09 | 18 | major | 4d | The alert channel and on-call tool (SMS, voice, push) are not chosen |
| G-18-10 | 18 | minor | 2e | Front Door default platform 429 limits are not published in the sources read |
| G-18-11 | 18 | minor | 4d | Replica behaviour after a primary HA failover is not stated |
| G-18-12 | 18 | minor | 3a | Bulk reverse geocoding would be a cost trap outside Azure |
| G-19-01 | 19 | minor | 0a | `btree_gist` may not be on the Azure Database for PostgreSQL Flexible Server allow-list; the exclusion constraint falls back to a ... |
| G-19-02 | 19 | minor | 0b | The registry holds 649 keys (600 before the round-3 pass, 565 before round 2) and 101 with a C3 tier (90, then 78 before); D-88 and the skeleton counted 245 and 34 from the lens alone |
| G-19-03 | 19 | minor | 1b | Doc 17 s6.7 lists the delta shape without `bounds`, `dir`, `effective_to` and the scheduled list |
| G-19-04 | 19 | major | 2d | The what-if and density tools need `server_distance_m`, `device_distance_m`, accuracy and `location_confirmed` on the visit fact a... |
| G-19-05 | 19 | minor | 2d | Values of the lens-security fraud thresholds (for example `credit_share_x`, `edits_per_month`) are not in this registry |
| G-19-06 | 19 | major | 4c | Web Entry derivations are inferred, not shown (Sale = Issue minus Return; "Brand Data"; how often the page is used) |
| G-19-07 | 19 | minor | 4c | The meanings of the Sales Plan "Data Entry Date" and "PDA" column and of the "exist" status are unknown |
| G-19-08 | 19 | minor | 2d | A pilot of 10 to 20 routes is below the anomaly watch sample; the watch widens to a parent scope |
| G-19-09 | 19 | minor | 4c | "WMO" is a designation in the manuals and not in the role enum |
| G-19-10 | 19 | minor | 1c | New admin endpoints and console sub-tools need F-API and F-ADM ids |
| G-20-01 | 20 | major | 0a | The baseline pack and evidence repository hold retailer phones, owner names and rep identities in recordings, memo photos and expo... |
| G-20-02 | 20 | minor | 1c | The lens probes TLS on "Android 7/8" while D-11 sets minSdk 26 (Android 8.0); the legacy class is ambiguous |
| G-20-03 | 20 | major | 2a | Several manual behaviours have no oracle (MQ-01 to MQ-07, Q57, Joint Call items); a gate cannot be green against an unknown |
| G-20-04 | 20 | major | 2e | Whether trigger T4 fires on MIUI, Realme and Vivo phones after an OEM kill is unmeasured; "immediate sync" (R5) is proved only on ... |
| G-20-05 | 20 | minor | 2e | 91 human-signed gates (67 before the round-2 pass) depend on the availability of a few named verifiers |
| G-20-06 | 20 | major | 7a | The Apsis monthly reports may include sales not made in the apps (D-245), so STD control totals against them could fail for a legi... |
| G-20-07 | 20 | minor | 1c | The baseline pack is static; if the live Apsis app changes mid-project the oracle ages (the live app is already newer than the man... |
| G-20-08 | 20 | minor | 1a | The lab has 2 printer models; the fleet's printer models are unknown, and clone printers differ in density and Bangla raster legib... |
| G-21-01 | 21 | major | 3b | D-112 would hold every first bind on a wave day (TSO-issued temporary passwords plus OTPs) |
| G-21-02 | 21 | major | 3b | A revoked phone's pending rows have no defined path (doc 19 has the grace key, nothing defines the behaviour) |
| G-21-03 | 21 | major | 2d | Visits validated against a provisional location would credit K-09 and make moving a pin profitable |
| G-21-04 | 21 | major | 0c | Write-path scope judged on current reach would quarantine honest rows after a transfer or cover change |
| G-21-05 | 21 | major | 1b | Password spraying and a new-device login on a bound user's account are invisible to the victim |
| G-21-06 | 21 | major | 0c | A 4-digit OTP with 5 attempts per code has no per-user budget |
| G-21-07 | 21 | minor | 5c | The exact content fingerprint is evaded by a 1 s time shift |
| G-21-08 | 21 | minor | 2d | Radio rules can false-positive on rural macro cells |
| G-21-09 | 21 | minor | 2e | Key custody for the support bundle was unspecified |
| G-21-10 | 21 | minor | 1c | Audit chain contention, WORM lock irreversibility and security-event volume |
| G-21-11 | 21 | minor | 4c | One list budget for every role would block a TSO browsing a 2,500-outlet territory |
| G-21-12 | 21 | minor | 7a | Imported usernames may violate the memo-number format (hyphens, case) |
| G-21-13 | 21 | minor | 1b | Redis failure behaviour for revocation was unstated |

Proved by: T-0-46 (`gaps.yaml` lists every id above with an owner and a closing gate).

### 7.8 Round-2 gaps (G-qa-25 to G-qa-85): the skeptic review and where each is closed

Three skeptics reviewed documents 14 to 21 against R1 to R6 and the process requirement and raised 61 gaps. Skeptic gap n is recorded as G-qa-(24+n); each is closed by a decision of DECISIONS.md section M3 (D-500 to D-553) and by the owning documents. The gaps are not part of the 329 master gaps, the 8 of doc 14 or the 87 of s7.7. Three of them (skeptic gaps 39, 40 and 42) are small corrections made in place and carry no decision. The unlisted severity is that of the skeptic's review; the blockers are the ones that stop an exit (gaps 13 and 17 are named blockers in the review and are closed by D-514 and D-517).

| Gap | Skeptic gap | Subject | Decision | Owner documents | Closing gate |
| --- | --- | --- | --- | --- | --- |
| G-qa-25, G-qa-40 | 1, 16 | Captured fields with no dw object; no analyst question per capture type | D-500 | 16, 15, 20 | T-0-150, T-4-150, T-4-151 |
| G-qa-26 | 2 | R1(a) breached by JSON blobs | D-501 | 16, 15, 20 | T-0-151 |
| G-qa-27, G-qa-47 | 3, 23 | R2: live functions silently dropped; no register | D-502 | 15, 14, 20 | T-0-153, T-7-158 |
| G-qa-28 | 4 | Scale proof thin and late | D-504 | 18, 20, 14 | T-2-155, T-4-155..164 |
| G-qa-29 | 5 | 17:00:00 spike | D-505 | 17, 18 | T-4-155 |
| G-qa-30 | 6 | AMO and TSO budgets are assumptions | D-506 | 17, 20 | T-3-151..153 |
| G-qa-31 | 7 | R4 proved only on lab phones | D-507 | 17, 18, 19, 20 | T-2-153, T-7-155, T-7-156 |
| G-qa-32 | 8 | Budgets for one route size, photos counted twice | D-508 | 17, 20 | T-1-36, T-2-40 |
| G-qa-33 | 9 | R5 "immediately" has no SLO | D-509 | 17, 18, 20 | T-2-154, T-7-157 |
| G-qa-34 | 10 | R5 acceptance wording versus design | D-510 | 14, 17 | R5(f) signature by 2c |
| G-qa-35 | 11 | geo_class shadowing | D-512 | 19, 20 | T-2-65 |
| G-qa-36 | 12 | Demo and gate ordering | D-513 | 14, 20, 15 | T-0-152, T-3-156 |
| G-qa-37 | 13 | Apsis delta contract (blocker) | D-514 | 16, 14, 20 | T-7-150..152 |
| G-qa-38 | 14 | Calendar lead times | D-515 | 14, 20 | T-0-155 |
| G-qa-39, G-qa-59, G-qa-69 | 15, 35, 45 | Stale counts, generated scope lines | D-516 | 14, 15, 19, 20 | T-0-46, T-0-152 |
| G-qa-41 | 17 | Re-sync after restore (blocker) | D-517 | 17, 16, 18 | T-1-152, T-7-54 |
| G-qa-42 | 18 | Refresh-expiry herd | D-518 | 21, 18 | T-4-162 |
| G-qa-43 | 19 | D-431 tolerance window | D-519 | 19, 20 | T-1-63 |
| G-qa-44 | 20 | Storage sizing | D-520 | 16, 18 | T-1-150, T-2-150 |
| G-qa-45 | 21 | Savepoint per record | D-521 | 16, 17, 18 | T-1-151, T-4-163 |
| G-qa-46 | 22 | DR replica SKU | D-522 | 18 | T-4-164, T-7-52 |
| G-qa-48 | 24 | OEM and platform conditions | D-511 | 17, 20 | T-2-140 |
| G-qa-49 | 25 | Immutability of capture tables | D-524 | 16, 20 | T-0-07 |
| G-qa-50 | 26 | Registry and key reconciliation | D-525 | 16, 19 | T-6-60 |
| G-qa-51 | 27 | Config reach measured on online devices only | D-526 | 19, 20 | T-2-61, T-6-01, T-6-41 |
| G-qa-52, G-qa-77 | 28, 53 | Emergency widen and temporary relief | D-527 | 19, 21 | T-2-160, T-6-10 |
| G-qa-53 | 29 | Aggregation worker sizing | D-528 | 16, 18 | T-4-54 |
| G-qa-54 | 30 | Pilot reserve and extension | D-529 | 14, 20 | T-7-159 |
| G-qa-55 | 31 | Cited evidence not in the repository | D-530 | 14, 20 | T-0-154 |
| G-qa-56 | 32 | Manual coverage unmapped | D-531 | 15, 20 | T-0-152 |
| G-qa-57 | 33 | Task and leave chains split across phases | D-532 | 15, 20 | T-3-155 |
| G-qa-58 | 34 | API phase later than its callers | D-533 | 15, 20 | T-0-152 |
| G-qa-60 | 36 | Precedence of plan documents over docs 01 to 13 | D-534 | 14 | text in s9.2; applied to CLAUDE.md and README by the owner |
| G-qa-61 | 37 | bex and back-margin existence | D-523 | 15, 14 | T-7-158 |
| G-qa-62 | 38 | Gate-to-feature mapping | D-535 | 20, 15 | T-0-46 |
| G-qa-63, G-qa-64, G-qa-66 | 39, 40, 42 | Small corrections in place: the force-sale reason list (F-SR-018), a retired config key cited by F-TSO-007, the till-date day counts (K-13) | none | 15, 16 | T-2-77, T-3-25..28, T-2-124 |
| G-qa-65 | 41 | Total Service Zone and the Final Submit ring | D-536 | 15, 16 | T-4-41 |
| G-qa-67 | 43 | Astha exclusion from Web Entry; Save button | D-537 | 15, 16, 19 | T-4-46 |
| G-qa-68 | 44 | Attendance address | D-538 | 15, 16 | T-4-40 |
| G-qa-70 | 46 | Sales Submit void | D-539 | 15, 16, 17, 19 | T-3-150 |
| G-qa-71 | 47 | Authority matrix | D-540 | 19, 21 | T-2-157 |
| G-qa-72 | 48 | Support desk page and role | D-541 | 19, 15 | T-2-157, T-2-158 |
| G-qa-73 | 49 | Emergency non-working day | D-542 | 16, 19 | T-4-154 |
| G-qa-74 | 50 | Paper-memo recovery | D-543 | 16, 19 | T-2-151 |
| G-qa-75 | 51 | One default date and the reason chip | D-544 | 16, 15 | T-4-41 |
| G-qa-76 | 52 | Location-request volume | D-545 | 16, 18 | T-4-152 |
| G-qa-78 | 54 | Config ceilings against budgets | D-546 | 19, 20 | T-2-156 |
| G-qa-79 | 55 | Invariants across keys | D-547 | 19 | T-0-61, T-2-69 |
| G-qa-80 | 56 | Mixed estate | D-548 | 16, 15 | T-4-153, T-7-152 |
| G-qa-81 | 57 | Rollback return path | D-549 | 18, 14 | T-7-153 |
| G-qa-82 | 58 | Operations drill and runbooks | D-550 | 18, 20 | T-6-42, T-7-85 |
| G-qa-83 | 59 | User administration and disabled-user upload | D-551 | 19, 21 | T-2-159, T-3-154 |
| G-qa-84 | 60 | Alert and paging parameters | D-552 | 18 | T-7-55 |
| G-qa-85 | 61 | Support staffing from a measured rate | D-553 | 18, 20 | T-7-154 |

Gap 36 (G-qa-60) is closed only in part by this pass: CLAUDE.md, the README, docs 01 to 13 and db/schema.sql are outside the files this planning pass may edit, so s9.2 supplies the text and D-534 stays MUST-CONFIRM until the owner applies it.

Proved by: T-0-46 (`gaps.yaml` lists every id above).

### 7.9 Round-3 gaps (G-qa-86 to G-qa-139): the third skeptic review and where each is closed

A third review of documents 14 to 21 raised 54 gaps (one blocker: G-qa-122, Apsis residual selling and catch-up). Review gap n is recorded as G-qa-(85+n); each is closed by a decision of DECISIONS.md section M4 (D-554 to D-601) and by the owning documents, whose Traceability sections carry the same ids. The gaps are not part of the 329 master gaps, the 8 of doc 14, the 87 of s7.7 or the 61 of s7.8: the register of s7.1 to s7.5 stays at 337 and the three later rounds are tracked as 87, 61 and 54 (539 gap ids in all). The severity is the reviewer's and is not re-graded here. Ten of the decisions are MUST-CONFIRM and are in s4.2c.

| Gap | Review gap | Subject | Decision | Owner documents | Closing gate |
| --- | --- | --- | --- | --- | --- |
| G-qa-86 | 1 | Build brief and evidence not wired in: CLAUDE.md, README and the base documents still say the old things; /docs/evidence and /plan files do not exist | D-554 | 14, 20 | T-0-154, T-0-160 |
| G-qa-87 | 2 | No Board envelope: programme cost, cost of delay, headcount, Apsis contract end | D-555 | 14, 20 | T-0-162 |
| G-qa-88 | 3 | Apsis rows written after the T-1 20:00 delta cut are missed | D-556 | 16, 20 | T-7-163 |
| G-qa-89 | 4 | Wave-night schedule: J2 at 22:00 before the Apsis day is complete | D-557 | 18, 17, 14 | T-7-161 |
| G-qa-90 | 5 | Gate placement differs between docs 14, 18 and 20 | D-558 | 20, 18, 14 | T-0-152 (rule 10), T-0-156 |
| G-qa-91 | 6 | Device-captured record types with no dw object (device_integrity, activity_log, consent_accept, config_ack) | D-559 | 16 | T-0-150, T-4-168 |
| G-qa-92 | 7 | BI and archive path: lake query engine and network path not designed | D-560 | 16, 18 | T-4-165, T-4-169 |
| G-qa-93 | 8 | Programme parity: no pilot for programme outlets | D-561 | 15, 16, 20 | T-7-165 |
| G-qa-94 | 9 | APK distribution risk under Google developer verification | D-562 | 17, 18, 14 | T-2-164, T-7-166 |
| G-qa-95 | 10 | Phase 0b overloaded with 18 gates in 5 days | D-558 | 20, 14 | T-0-05, T-0-09 (moved), T-0-150, T-0-151 (report-only) |
| G-qa-96 | 11 | R6 reach stated three ways | D-563 | 19, 14 | T-2-165, T-2-174 |
| G-qa-97 | 12 | Battery budgets not stated per battery-size class | D-564 | 17, 20, 14 | T-1-45, T-2-40 |
| G-qa-98 | 13 | Baseline pack has no named owner or fallback | D-565 | 20, 14 | T-0-49, T-7-158 |
| G-qa-99 | 14 | Stale figures across the documents | D-601 | 14 to 21 | T-0-159 |
| G-qa-100 | 15 | dw.enqueue runs under the wrong role | D-566 | 16 | T-1-153, T-1-154 |
| G-qa-101 | 16 | Two role maps (the GRANT script and the immutability policy) | D-566 | 16, 20 | T-0-158, T-0-07 |
| G-qa-102 | 17 | route_day written by paths that enqueue no dirty key | D-567 | 16 | T-1-155, T-3-158 |
| G-qa-103 | 18 | Storage sized three ways | D-568 | 18, 16 | T-0-159 |
| G-qa-104 | 19 | Geo backup only at creation; hydration of the restored disk | D-569 | 18 | T-4-174 |
| G-qa-105 | 20 | Residency, PII and notice answered too late (7c) | D-570 | 21, 14 | T-7-160 |
| G-qa-106 | 21 | Config reach with push off and an undefined "online" | D-563 | 19 | T-2-174, T-4-161 |
| G-qa-107 | 22 | Device-supplied config_version stamp can regress | D-571 | 19, 21 | T-2-162 |
| G-qa-108 | 23 | Re-sync after a restore beyond the back-date window | D-572 | 16, 17 | T-1-156 |
| G-qa-109 | 24 | Front Door tier: managed WAF and Private Link need Premium | D-573 | 18, 21 | T-7-162 |
| G-qa-110 | 25 | Key Vault signing is a hard dependency of login | D-574 | 21, 18 | T-4-173 |
| G-qa-111 | 26 | Web audience under-counted (AMO, TSO and HQ readers) | D-575 | 18, 20 | T-4-158 |
| G-qa-112 | 27 | Constants repeated and drifting across documents | D-576 | 20, 14, 16 | T-0-159 |
| G-qa-113 | 28 | Route-day void does not stop rows already on phones | D-577 | 16, 19 | T-4-166 |
| G-qa-114 | 29 | DSS ignores web-entered data | D-578 | 16, 15 | T-4-167 |
| G-qa-115 | 30 | day_closed reject code against a final-submitted zone-day | D-579 | 17, 16, 15 | T-3-159 |
| G-qa-116 | 31 | Stock re-save doubles stock | D-580 | 15, 17, 16 | T-2-161, T-3-160 |
| G-qa-117 | 32 | No F-id to T-id traceability | D-581 | 15, 20 | T-0-163 |
| G-qa-118 | 33 | Row-level coverage of the manual inventories not proved | D-554 | 15, 20 | T-0-161 |
| G-qa-119 | 34 | MQ table and the Q-28 default | D-582 | 14, 15 | T-5-123 |
| G-qa-120 | 35 | Doc 15 row ordering and phase placement | D-583 | 15 | T-0-152 |
| G-qa-121 | 36 | Naming drift (outlet_class_history, seven versus eight scope levels) | D-583 | 15, 19 | T-0-152 |
| G-qa-122 | 37 | BLOCKER. Apsis residual selling and catch-up: reps keep selling in Apsis after binding, and Apsis rows arrive after the delta | D-556 | 16, 14, 20 | T-7-163, T-7-164 |
| G-qa-123 | 38 | Windows counted in calendar days across breaks | D-584 | 17, 18, 19 | T-2-163 |
| G-qa-124 | 39 | Policy unbind with pending rows | D-585 | 17, 21 | T-3-157 |
| G-qa-125 | 40 | Held binds and replacing a device | D-586 | 19, 21 | T-2-167 |
| G-qa-126 | 41 | No replay tool for rows lost to a restore | D-587 | 19, 18 | T-2-168 |
| G-qa-127 | 42 | Brake keys frozen inside the change freeze | D-588 | 19 | T-2-173 |
| G-qa-128 | 43 | No price rails: a 79.35 for 7.935 | D-589 | 19 | T-2-166 |
| G-qa-129 | 44 | Day-one tools land late | D-590 | 19, 15 | T-2-172 |
| G-qa-130 | 45 | Dependency rules between keys incomplete | D-591 | 19 | T-0-157 |
| G-qa-131 | 46 | Rollback capacity beyond wave 2 | D-592 | 18, 14 | T-7-153, T-7-168 |
| G-qa-132 | 47 | L1 cannot reset a password | D-593 | 19 | T-2-158 |
| G-qa-133 | 48 | No server-to-device directive channel | D-594 | 17, 19 | T-2-169 |
| G-qa-134 | 49 | No revert for a bulk batch | D-595 | 19 | T-6-150 |
| G-qa-135 | 50 | Alert rows missing for cfg.sla keys and rollback triggers | D-596 | 18 | T-4-45 |
| G-qa-136 | 51 | Safe-range holes in cfg bounds | D-597 | 19 | T-0-61 |
| G-qa-137 | 52 | No device_model scope | D-598 | 19 | T-2-156 |
| G-qa-138 | 53 | Ticket store and backfill | D-599 | 19 | T-2-170 |
| G-qa-139 | 54 | Legal hold on archive and drop jobs | D-600 | 16, 21 | T-6-152 |

Closed only in part by this pass: gaps 1 and 33 (G-qa-86, G-qa-118). CLAUDE.md, the README, docs 03 and 06 to 10, db/schema.sql and the /docs/evidence and /plan files are outside the files this planning pass may edit, so s9.2b supplies the ready-to-paste text, T-0-160 and T-0-161 prove it, and OI-14-18 and OI-14-19 stay open until the sponsor applies it. Gap 32 (G-qa-117) is closed by a rule, the explicit rows of doc 15 s12.6 for the known holes and every new feature, and the generator T-0-163 (the full 509-row table is generated at 0a, OI-15-25). Gap 10 (G-qa-95) is closed by moving tasks, not weeks: T-0-05 and T-0-09 to 1b, T-0-100 to 1c and the two lints report-only at 0b, with a day-by-day table in s3.0. Gap 46 (G-qa-131) is closed by an honest per-wave statement and a capacity cap; whether the schedule or the budget changes is the sponsor's choice in s2.5b.

**Stale-count note (gap 14, G-qa-99, D-601).** Figures that earlier rounds left behind are replaced where they stand and are checked by T-0-159: the counts of this document (543 decisions; 57 LOCKED-BY-SPEC, 313 DEFAULT and 173 MUST-CONFIRM; 33 risks; 509 features; 649 config keys of which 101 have a C3 tier, doc 19 s3.2.11), the 1,024 GiB storage and the first grow by month 6, Premium Front Door from the pilot, J2 triggered by J1 and the 23:30 go or no-go, the cost table of doc 18 s3.3 and D-307 (the critical path and the float of 0 to 2 weeks, s2.3). The old values appear only in the change log of DECISIONS.md.

Proved by: T-0-46 (`gaps.yaml` lists every id above), T-0-159 (the constants and counts).

## 8 Cutover summary

Detail lives in doc 18 s7 (launch-day operating model) and doc 20 s7 (pilot, cutover and support verification); this section is the one-page view for the sponsor. The rule behind all of it: no wave proceeds until the previous one is clean, every wave has a rollback lever that works while captured rows keep uploading, and Apsis is not switched off until the last wave is stable (docs/11, D-144, D-155).

### 8.1 Pilot (7b): design and pass rule

| Item | Design |
| --- | --- |
| Composition (D-305, ASSUMPTION) | 10 to 20 routes in at least 3 zones covering urban, rural and hill geo classes; one route with a wholesale outlet and one credit-heavy route; no programme-tier outlets before 5a; consenting SRs with an agreed incentive |
| System of record | Apsis, until a wave switches (D-144); pilot accounts are flagged pilot=true and kept out of national rollups (cfg.flag.pilot_in_rollups false) but included in reconciliation |
| What the retailer holds | Apsis memo. The new app runs in capture_only or print_test_watermark mode (D-152); the test print says it is not a receipt and has no previous-due line; due collections flagged parallel are excluded from balances |
| Daily comparison | By 09:30 next day, per route-day: memo count, STD per SKU, value, dues, geo %, Submit % (of logged-in) in both systems; each difference gets a category (D-145) |
| Categories | A double entry; B rule difference (closed by a recorded decision); C sync loss; D Apsis-side change; E business date |
| Pass rule (D-145) | 10 consecutive trading days (per the working-day calendar) with zero category C and exact memo count, STD, dues and value after A, D and E are explained and B is closed; one category C resets the count. Non-trading days neither count nor reset |
| Inputs | Per-route daily Apsis figures in machine-readable form, else manual keying for the pilot only (D-154); opening balances spot-checked on 30 outlets in 3 zones before day 1 |
| Planned duration | 3 weeks (a 10-trading-day run needs at least 12 calendar days with Friday off) |

### 8.2 Waves (7c, 7d): sizes and calendar

Wave sizes after the pilot are unknown; confirm with the business (D-147, Q38, Q51). The ladder below is arithmetic on the fleet figures (11,336 routes, 291 territories, 50 divisions, 10 wings, 8,500 SRs, 1.33 routes per SR) and is an ASSUMPTION.

| Wave | Scope | Routes (approx.) | SRs (approx.) | Share of fleet |
| --- | --- | --- | --- | --- |
| Pilot | 10 to 20 routes | 10 to 20 | 8 to 15 | 0.1 to 0.2 percent |
| 1 | One territory per wing (10 territories at 39 routes each) | 390 | 290 | 3.4 percent |
| 2 | One division per wing (10 divisions) | 2,270 cumulative | 1,700 cumulative | 20 percent |
| 3 | Half the fleet | 5,670 cumulative | 4,250 cumulative | 50 percent |
| 4 | The rest | 11,336 | 8,500 | 100 percent |

If Q51 settles on wave 1 = 1,000 SRs (11.8 percent), the first wave is about 3.5 times larger and the load model of doc 18 s1 (1,150 new users) applies. Rules (D-147, D-311): never within three days of a month end, Eid or a public holiday, nor on a Thursday; start Sunday to Tuesday so the pre-bind day T-1 is a trading day; each wave starts only when the previous one has been stable for 3 trading days (7d); no wave starts before the 7c entry conditions of s3.7 hold. Planning date for wave 1: Sunday 2027-06-20 (ASSUMPTION, s2.3). Until the last wave the estate is mixed: dim_geo carries the wave of each route, KPI denominators and alerts default to the switched routes with a coverage banner, a nightly Apsis route-day feed keeps the national totals complete in the all-routes view, and the leaderboard shows "partial" below 80 percent switched (D-548, doc 16 s9.10).

### 8.3 Wave-day timeline (Dhaka time; doc 18 s7 holds the runbook)

| When | What | Decision |
| --- | --- | --- |
| T-1 14:00 | J2w wave pre-snapshot of the wave's users, so the pre-bind pre-fetch has a snapshot to fetch (no usual 22:00 snapshot exists while the SRs are still at the distribution house) | D-557, T-7-161 |
| T-1 | Pre-bind day: the wave's SRs gather at the distribution house on Wi-Fi, install, bind with the OTP, log in once, fetch and VERIFY the bundle for D, and test-print; TSOs pre-issue OTPs per zone (audited); day one on the new app is a DELTA of about 25 KB, not a password storm | D-126, D-103, D-557 |
| T-1 19:00 | Readiness read from the screen: 100 percent bound, logged in, bundle verified, test memo printed, install success at least 95 percent (`cfg.cutover.install_success_pct`); every absent SR listed and the route held on Apsis. The Apsis completion line: every wave SR has uploaded and every wave zone has its Apsis Final Submit (`cfg.cutover.apsis_complete_by_time`); a go/no-go condition | D-562, D-556, T-7-166 |
| T-1 20:00 to 23:00 | Final Apsis delta and control totals by 20:00 (AKTCL-staff fallback until 21:00); J1 imports; the clerk reconciles; J1 `reconciled` by 23:00 | D-514, D-71, D-557 |
| T-1 J1 `reconciled` to 23:30 | J2 builds the final snapshots of D, TRIGGERED BY J1, not by the clock; deadline 23:30 | D-557 |
| T-1 23:30 | Go or no-go: J1 reconciled, J2 done, probes green, bind list complete, every unbound SR's route held, install success and the Apsis completion line met, the per-wave rollback statement signed; otherwise the wave is deferred | D-557, D-592, T-7-168 |
| 00:05 | Create route_day rows for the planned routes so denominators exist from midnight | D-71 |
| 02:15 to 04:30 | Re-aggregation and refresh of snapshots for users dirtied since; coverage check (at least 99 percent valid for the date, else bundle_hold serves yesterday's snapshot plus delta and pages level 2) | D-71, D-129 |
| 06:00 to 06:20 | J1b: the late Apsis delta DL-1b reaches phones as a bundle delta before the first sale, with "balance as of <time>"; the straggler sheet opens with a named clerk; `apsis_residual` (DL-6) is watched | D-556, T-7-163, T-7-164 |
| 06:15 | Pre-scale: api 8 replicas, worker 3, web 4 (a 2 vCPU and 4 GiB wave-morning revision) | D-133, D-126 |
| 07:00 | War room opens: sync-health, login %, support queue, config reach | D-149 |
| 16:45 to 21:00 | Pre-scale again; 17:00 check-out and Sales Submit wave; submit settle (server totals at least the device counts, 30-minute timeout); rollback triggers are read only after settle; final-submit coverage of the wave's zones by 21:00 | D-64, D-133 |
| T+1 09:30 | Daily sheet, defects triaged, go or hold for the next day | doc 20 s7 |

### 8.4 Rollback levers

| Lever | Scope | Speed | Decision |
| --- | --- | --- | --- |
| Wave flag flip: cfg.flag.new_app_login_enabled false at wave scope (pushed); captured rows still upload; new-app data exportable in the Apsis dump shape | one wave | minutes after the next request or push | D-148, T-7-60 |
| Config revert of a C3 key; urgent change by FCM only if enabled | any scope | minutes | D-88, D-89, D-09 |
| API revision rollback by traffic weight | all users | seconds | D-14 |
| App: roll-forward rescue release within 24 hours; the previous local schema opens for one release; downgrade unsupported | app version | hours | D-79 |
| Schema: forward fix only (expand and contract) | database | per release | D-14 |
| Import run rollback | one import run | per run | doc 16 s12 |
| Point-in-time restore (RTO 2 h) and region DR through the cross-region replica from wave 2 (RTO 4 h, RPO 15 min) with the server-generation re-sync | database or region | hours | D-127, D-63 |
| Region outage before the cross-region replica exists (wave 1): geo-restore of the geo-redundant backup into East Asia, RTO MEASURED by T-4-174 (not assumed), RPO about 1 hour, wait for disk hydration before enabling HA | region | hours, measured | D-569, RB-52 |
| Re-key of dues into Apsis (data return) | one wave, first 3 trading days | hours of staffed work; limited by the committed keyer capacity | D-549, D-592 |

The one trigger fixed by this plan: reconciliation mismatch above 2 percent of routes, read after submit settle (D-64). Other triggers and their owners are in doc 18 s7.

**Return path after a rollback (D-549, MUST-CONFIRM by 7b) and its capacity (D-592, MUST-CONFIRM by 7b).** Flipping the wave flag returns the reps to Apsis but not the data: the wave's open dues, stock and credit memos exist only in the new system. Rollback therefore includes a named, staffed procedure that re-keys them into Apsis for the first 3 days after a wave, and after that the answer is fix-forward only; Apsis is kept mirrored nightly (read) so dues can be compared; same-day sales after a 17:30 rollback follow the same procedure; the owner is on the war-room roster. The drill T-7-153 proves retailer dues in Apsis equal Aron after a rollback. The re-key needs about 2 staff per 100 routes on the first day: about 8 people for wave 1 (390 routes), about 38 more for wave 2, about 68 for wave 3 and about 113 for wave 4, and how Apsis accepts keyed dues is unknown (OI-18-14). So the lever is stated per wave and signed with the go/no-go sheet: "rollback possible" inside AKTCL's committed capacity, "fix-forward only" above it (the 2 percent trigger then means stop the next wave, hold and fix forward), or "split" into sub-waves no larger than the capacity; an Apsis bulk-import path removes the cap (doc 18 s7.5, T-7-168, RK-33).

### 8.5 Go or no-go before each next wave (doc 20 s7)

| Measure | Threshold |
| --- | --- |
| Login % on day 3 | at least 95 percent of the pre-switch Apsis login % for the same routes |
| Submit % (of logged-in) | not below the pre-switch figure |
| Reconciliation mismatch after settle | at most 0.1 percent of route-days |
| Crash-free sessions | at least 99.5 percent |
| Battery complaints | at most 1 percent of the wave |
| Field data and battery (SH-19 to SH-21, D-507) | p95 mobile bytes per device-day at or below 1.25 times the route-size gate; no regression above 20 percent on CPU, engine starts or battery drop against the previous release; p10 of the 17:00 battery at or above cfg.telemetry.bat17_floor_pct |
| Immediacy (SH-22, SH-23, D-509) | 95 percent of online-captured rows acknowledged within 60 s and 99 percent within 5 min; 90 percent of devices within 3 min of reconnecting; no device holding rows beyond 24 h |
| Open P1 tickets | zero |
| Install success on the pre-bind day (D-562) | at least `cfg.cutover.install_success_pct` (95) of the wave's phones with the signed APK installed and launched |
| Apsis residual and stragglers (D-556) | `apsis_residual` at zero for the wave's switched routes; every straggler on the sheet has a named owner; the Apsis completion line (19:00) was met |
| Per-wave rollback statement (D-592) | "rollback possible", "fix-forward only" or "split", signed with the go/no-go sheet |
| Final-submit coverage of the wave's zones | 100 percent by 21:00 on each of 5 post-wave days |

### 8.6 Decommission (7e)

Only after all waves are stable for 10 trading days, the final Apsis delta is imported and reconciled, Apsis credentials are rotated or removed, the raw dump is archived to immutable storage and then deleted per policy (30 days after reconciliation), and memo-number continuity is recorded (D-155, D-35). Proved by: T-7-88.

Proved by: T-7-82 (pilot pass), T-7-83 (rollback drills), T-7-84 (readiness), T-7-85 (support), T-7-86 (go or no-go), T-7-87 (post-wave reconciliation), T-7-88 (decommission).

## 9 Working agreements

### 9.1 Roles

The team shape is an ASSUMPTION (D-307). The "Named person" column is filled at kickoff; the four readiness owners of D-156 are engineering, operations, business and support. A person verifying a gate is never its author (D-141).

| Role | Responsibility | Verifies | Named person |
| --- | --- | --- | --- |
| Sponsor (Asef) | Answers or delegates MUST-CONFIRM rows; signs phase exits and the changes of s5 | exit reports (second signature may be the delegate) | Asef |
| Sponsor's delegate (business verifier) | Runs every demo and one-page exit script; owns the business gates | T-0-49, T-1-41, T-1-47, T-2-41, T-2-48, T-4-41, T-6-42, T-7-82 | to be named at 0a |
| Plan owner (doc 14 author, product owner) | Keeps DECISIONS.md, s4 schedule, s7 counts; runs the confirmation review | none (never the verifier of a gate it authored) | to be named |
| Tech lead (engineering readiness owner) | Engineering gates from the CI gate report; second signature on exit reports | T-0-40..47, T-1-42 (coverage and mutation), CI gate report | to be named |
| App, API and sync, Web, Data and importer, Infra and SRE leads | Own their stream's deliverables (s2.4) | peer review of other streams | to be named |
| QA lead | Device lab, patrol and load harnesses, parity oracle, exit scripts | lab gates, T-2-40, T-2-45 | to be named |
| Security owner | Auth, scope, PII, fraud signals, scans, pen test | T-0-70..76, T-2-70..75, T-4-70..76, T-7-70 | to be named |
| Ops lead (operations readiness owner) | Environments, alerts, runbooks, wave days | T-0-43, T-0-44, T-4-45, T-7-83 | to be named |
| Support lead (support readiness owner) | Helpdesk, scripts, TSO toolkit | T-7-85 | to be named |
| Release manager | App channel, wave_pct, min_version | T-2-44, T-2-46 | to be named |
| Sales operations, finance, trade marketing, legal and HR, IT | Answer the s4 rows in their area | none | to be named |
| Native-Bangla reviewer (part time) | Reviews all strings against the baseline glossary | T-2-43, T-2-49 | to be named |

### 9.2 How Claude Code sessions and engineers work

| # | Rule |
| --- | --- |
| 1 | Every session reads CLAUDE.md, the rows of `/DECISIONS.md` for the area, this section and the owning document of the feature before it writes code; CLAUDE.md stays loaded for the whole session |
| 2 | Work stays inside one sub-milestone whose entry condition holds; Phase 1 (the vertical slice) is finished before anything widens |
| 3 | Parity first (the manual's behaviour wins) unless it breaks one of the eight constraints; then the constraint wins and a D-id records the change (s5) |
| 4 | Every assumption, deviation or business answer is logged in DECISIONS.md with date and reason before the code that depends on it merges; when a spec is ambiguous, build the smallest correct version and log the question (CLAUDE.md "How to work"); only the plan owner edits a status (D-309) |
| 5 | Code, tests and commits cite F-ids, G-ids, D-ids and T-ids exactly; each test carries its gate id as a tag so the gate report is generated, not typed |
| 6 | Evidence of a gate is a file under /docs/evidence/phase-N/<T-id>/ with a named verifier; a gate without an artefact is not green |
| 7 | Migrations are forward-only, plain SQL, checksummed, expand then contract over three releases, with lock_timeout 5 s; a shipped migration is never edited (CLAUDE.md, D-02, D-14) |
| 8 | No secrets in the repository; Key Vault and OIDC only; CI data is synthetic and free of personal data and the Apsis sample never enters CI (D-151) |
| 9 | Strings only through the localisation layer with Bangla and English; no hard-coded text (lint) |
| 10 | The session ends by listing what it left open as open items with an owner and a sub-milestone |
| 11 | Plan documents and precedence (D-534): docs 14 to 21 and DECISIONS.md bind and win over docs 01 to 13 and db/schema.sql wherever they differ; docs 01 to 13 stay as the record of the original specification; when a session finds a difference it follows the plan document and logs the difference in DECISIONS.md. The text for the owner to add to CLAUDE.md and README, and as a "superseded by" banner on docs 03, 06 to 10 and db/schema.sql, is: "This file and docs/01 to docs/13 are the original brief. The build is governed by docs/14 to docs/21 and /DECISIONS.md; where they differ from this file, from docs/03, docs/06 to docs/10 or from db/schema.sql, the plan documents win and the difference is listed in docs/14 s2.2." The list of changes against docs 03 and 06 to 10 is in s2.2. The ready-to-paste text for CLAUDE.md, the README and the banners is in s9.2b (D-554) |
| 12 | Evidence the plan cites lives in the repository: the manual inventories, the delta register and the six V-* verification files are under /docs/evidence/manuals/ and /docs/evidence/verification/, read-only (D-530, T-0-154); a gate, decision or gap that cites a file that is not there fails rtm-check; at the date of this plan the files are NOT yet committed, so the sponsor commits them before kickoff (s9.2b, T-0-154) |
| 13 | A gate or a demo step is placed at or after the sub-milestone of every feature it exercises (rtm-check rule 10, D-513) |
| 14 | Repeated constants (the first-grow month, the storage size, role names, reach bounds, counts, the bounding box) are read from `/plan/constants.yaml`, never retyped; rtm-check rules 13 and 14 fail a conflicting value in docs 14 to 21 or DECISIONS.md (D-576, T-0-159) |
| 15 | Every F-id names the gate that tests its behaviour in the generated F-to-T table (doc 15 s12.6); a gate cited by more than 12 features, or one whose text names none of the feature's behaviours, fails rtm-check rule 15 (D-581, T-0-163) |

### 9.2b Making the plan binding: ready-to-paste text, and the sponsor's action before kickoff (D-554, G-qa-86)

The plan documents are binding only if the file a new session reads first points to them. CLAUDE.md, the README, docs 03 and 06 to 10, db/schema.sql and the `/docs/evidence` and `/plan` files are outside the files this planning pass may edit, so the owner of CLAUDE.md (the sponsor) applies the text below before kickoff on Sunday 2026-10-11. T-0-160 checks that it was done and fails the 0a exit if not; OI-14-18 and OI-14-19 stay open until then.

| Step | Where | Text or action |
| --- | --- | --- |
| A | CLAUDE.md, a new section after "Mission" | "## Plan documents and precedence. This file and docs/01 to docs/13 are the original brief. The build is governed by docs/14 to docs/21 and /DECISIONS.md. Where they differ from this file, from docs/03, docs/06 to docs/10 or from db/schema.sql, the plan documents win, and the difference is listed in docs/14 s2.2. Read DECISIONS.md and the owning document before you build a feature; a decision there is binding, and a MUST-CONFIRM row has a default you build now and an owner who answers it." |
| B | CLAUDE.md, Conventions, the money line | Replace "Money in integer minor units; quantities in the SKU's own unit" with "Money in integer milli-taka (bigint, column suffix _mtk, 1 Tk = 1,000 mtk, because distributor prices such as 7.935 have three decimals; D-15); quantities in the SKU's own unit" |
| C | CLAUDE.md, "How to work", item 1 | Replace "Build in the phase order in `docs/12`" with "Build in the phase and sub-milestone order of docs/14 s2.1 (0a to 7e); docs/12 is superseded. Finish the Phase 1 vertical slice before widening" |
| D | CLAUDE.md, "How to work", item 2 | Replace with "DECISIONS.md at the repo root is the decision log: read the rows for your area before you build, and add a row (assumption, date, reason) before the code that depends on it merges. Flag anything in docs/13 you had to decide yourself" |
| E | README.md | A short "Plan and precedence" paragraph with the text of step A and the reading order of docs/14 s1.1 (14 master plan, 15 features, 16 data, 17 sync, 18 scale and Azure, 19 admin and config, 20 tests, 21 security) |
| F | docs/03, docs/06 to docs/10 and db/schema.sql | A first-line banner: "Superseded in part by docs/14 to docs/21 and /DECISIONS.md. Where this file differs, the plan documents win (docs/14 s2.2 lists the differences)." In db/schema.sql the banner is a SQL comment and the file is replaced by migrations M-01 to M-45 (D-15: `_minor` columns become `_mtk`) |
| G | /docs/evidence/manuals/ and /docs/evidence/verification/ | Commit read-only the manual inventories, the delta register and the six V-* verification files, so that every id, gate and gap that cites them resolves (D-530, T-0-154) |
| H | /plan/ | Commit the files that rtm-check reads: rtm.yaml, gates.yaml, gaps.yaml, key-aliases.yaml, constants.yaml, capture-map.yaml, dw-landing.yaml and manual-coverage.yaml; the generated ones are produced at 0a (T-0-46, T-0-159, T-0-161, T-0-163) |
| I | DECISIONS.md header | The sentence that says the evidence files "are committed" is corrected to say they are not yet committed and names the step G action (done in this pass, D-554) |

Proved by: T-0-160 (wiring), T-0-154 (evidence), T-0-159 (constants).

### 9.3 Definition of done made checkable

CLAUDE.md's definition of done, one row each, with the check that fails the build or blocks the exit.

| Definition of done (CLAUDE.md) | Check |
| --- | --- |
| Works offline where the spec says so and survives kill-and-relaunch mid-operation | Each F-id carries an offline class (OFFLINE, QUEUED, HYBRID, CACHED, ONLINE-ONLY); OFFLINE and QUEUED features have a patrol test in airplane mode and a kill test (T-1-20..24, T-2-20..34) |
| Sync is idempotent and reconciles (device count equals server count) | Every new record type is added to the property and fuzz generator (rtm-check) and to the reconciliation counts; T-1-25..34, T-1-51 |
| Scope is enforced server-side; a user cannot read outside their assignment | The scope-leak harness discovers every endpoint; a new endpoint without a scope case fails the pull request (T-0-70..76); lint no-unscoped-query |
| Has tests, including the offline and sync path | Coverage floors: API at least 80 percent (at least 95 percent on the five critical modules), mutation score at least 70 percent on them, app domain layer at least 85 percent (T-1-42) |
| Meets the battery and size budget of docs/04 | Perf file docs/perf/battery-<version>.md for every release candidate; APK size warning at +5 percent and failure at +15 percent against the last release (T-1-45, D-73) |
| Bangla and English strings in the localisation layer | no-hardcoded-string lint; bn and en key completeness check; Bangla goldens (T-2-43, T-2-49) |
| Added by this plan | The feature's F-id has a gate; its decisions exist in DECISIONS.md; its gap rows have closing text (T-0-46) |

### 9.4 The eight constraints in every phase (D-310)

Every phase from Phase 1 has at least one blocking gate for each CLAUDE.md constraint. The standing gates re-run on every release candidate; the last column adds what is new in each phase. Placement of exact ids is doc 20's (s3); the families below follow the plan.

| # | Constraint | Standing gate (every release candidate) | Added in P1 to P7 |
| --- | --- | --- | --- |
| 1 | Offline-first | Airplane-mode slice and scripted day (T-1-40, T-2-40) | P3 T-3-40 (AMO and TSO flows); P5 T-5-40 (programme flows); P7 T-7-82 (pilot parallel run) |
| 2 | Idempotent sync | Fuzz of 10,000 batches per pull request, 100,000 nightly (T-1-51) | P1 T-1-151 and T-1-152 (poison batch with the database healthy, digest after a restore); P2 T-2-01..04; P4 T-4-46 (nightly reconciliation); P7 T-7-87 (post-wave reconciliation) |
| 3 | Lightweight and battery-friendly | Perf file per candidate and zero-chatter check (T-1-44, T-1-45) | P2 T-2-40 (three devices), T-2-153 (field telemetry); P3 T-3-151 to T-3-153 (AMO and TSO budgets, APK per flavour); P7 battery complaints in T-7-86, T-7-156 |
| 4 | Server-side scope | Scope-leak harness on every pull request (T-0-70..76) | P3 T-3-70..73; P4 T-4-70..74 (RLS, IDOR sweep); P6 T-6-70..72 |
| 5 | Geo-validation and anti-spoofing | On-device geo check and mock invariant (T-1-20..24, T-2-10..19) | P2 T-2-47 (real spoofing tools); P3 T-3-73 (Exceptions scoping); P7 geo % in the daily sheet (T-7-82) |
| 6 | No-hiccup cutover | Golden screens and memo parity against the baseline (T-1-41, T-2-41) | P2 T-2-48 (dress rehearsal); P4 T-4-41 (report parity); P5 T-5-41 (promotion corpus); P7 T-7-80, T-7-82, T-7-83 |
| 7 | Dhaka business date | Clock-injected suite at 10:00, 16:59:30, 23:58 and rollover (T-0-47, T-1-52) | P2 T-2-20..34 (clock-skew family); P4 T-4-01..03 (aggregates by business date); P7 T-7-87 |
| 8 | Bilingual | no-hardcoded-string lint and Bangla goldens (T-0-40, T-1-41) | P2 T-2-43, T-2-49; P3 T-3-42; P7 T-7-85 (Bangla support scripts) |

### 9.4b The constants registry: one owner for each repeated value (D-576, G-qa-112, G-qa-99)

Each value below is repeated in several documents and drifted in round 3 (storage in three sizes, the Front Door tier in two, the reach bound in three forms, the key count in four). The owner document states it once; every other document cites it. At 0a the values move into `/plan/constants.yaml` and rtm-check rules 13 and 14 fail a conflicting value in docs 14 to 21 or DECISIONS.md (doc 20 s8.2, T-0-159); until then this table is the register (OI-20-26).

| Constant | Value | Owner (single source) | Decision |
| --- | --- | --- | --- |
| Money scale | bigint milli-taka, MONEY_SCALE 1000, columns `_mtk` | doc 16 s2 | D-15 |
| Week 1 and wave-1 planning date | Sunday 2026-10-11; Sunday 2027-06-20 | doc 14 s2.1, s2.3 | D-307 |
| Sub-milestones; phases | 28 (0a to 7e); 8 phases (0 to 7) | doc 14 s2.1 | D-515 |
| Decisions | 543: 57 LOCKED-BY-SPEC, 313 DEFAULT, 173 MUST-CONFIRM | DECISIONS.md header | D-554 to D-601 |
| Features; gates; risks | 509 features (doc 15 s12.3b); 497 gate ids (doc 20 s3, generated `GATES.md` wins); 33 risks (s6) | doc 15, doc 20, doc 14 | D-576, D-601 |
| Config keys | 649, of which 101 have a C3 tier | doc 19 s3.2.11 | D-88, D-576 |
| Primary and replica storage | Premium SSD v2, 1,024 GiB from 7a; 2,048 GiB at the first grow; the first grow is planned by month 6 | doc 18 s3.1, s8.11 of doc 16 | D-568, D-131 |
| Server tiers | pilot D2ds_v5 with zone-redundant HA; wave 1 D4ds_v5; full fleet D8ds_v5 | doc 18 s3.1 | D-06, D-569 |
| Backup and DR position | geo-redundant backup ON from the creation of the 7a server; wave 1 relies on a geo-restore with the RTO measured by T-4-174; the cross-region replica from wave 2 | doc 18 s3.1, s5.4 | D-569 |
| Front Door | Premium (managed WAF rules, Private Link to the origin) from the pilot; managed rules in Log mode through the pilot | doc 18 s2.4 | D-573 |
| Azure monthly cost | about 1,780 (pilot), 3,690 (wave 1), 6,630 (full fleet) USD, unverified list prices | doc 18 s3.3 | D-06, D-576 |
| Web audience | about 1,500 concurrent readers at 17:00 to 19:00; about 117,500 requests a day | doc 18 s1.3b | D-575 |
| Wave night | J1 reconciled by 23:00; J2 triggered by J1, done by 23:30; go or no-go 23:30; J2w 14:00 on the eve; J1b 06:00; Apsis completion line 19:00; final delta files 20:00 | doc 18 s4.7, s7.3 | D-557, D-556 |
| Install success | at least `cfg.cutover.install_success_pct` (95) | doc 19 s3.2.11 | D-562 |
| Config reach | selling phones 95 percent within 15 minutes (push on or off, ordinary or urgent for push-off); push-enabled devices 95 percent within 5 minutes; idle phones at the next foreground; online window 20 minutes | doc 19 s4.1b | D-563 |
| Windows in working days | stale bundle 2 (calendar ceiling 7); back-date 7; re-sync late 14 days; registry retention 45 days with the dependency rules of s2.6b | doc 19 s3.2, s2.6b | D-584, D-591, D-572 |
| Role map | one map `app.role_grant_map`, generated grants, the pooled logins `app_api`, `app_auth`, `app_worker`, `app_jobs`, `app_web`, `app_export`, `bi_reader`, `app_migrator` | doc 16 s13.4b | D-566 |
| Country bounding box | `cfg.geo.country_bbox` (one key for DQ-22 and `geo_out_of_country`) | doc 19 s3.2.11 | D-576 |
| App budgets | non-screen drain at most 6 percent of 5,000 mAh a day (stated per battery-size class), 1 MB a day without photos, 3 MB with them, APK at most 30 MB per ABI | doc 17 s8.1 and s8.2b | D-73, D-564 |
| MQ questions | 68 (MQ-01 to MQ-68) | s4.4 | D-582 |
| Gap ids | 337 master gaps plus 87, 61 and 54 of the three later rounds | s7 | D-601 |

Proved by: T-0-159 (constants and counts), T-0-152 (rule 10 placement).

### 9.5 Skills per stage (gstack and the vendored Android and Azure skills)

CLAUDE.md names the gstack sprint and the vendored skills; this plan fixes where each is used. Web browsing is only through `/browse`; `mcp__claude-in-chrome__*` tools are never used.

| Stage | Skill | Use in this programme |
| --- | --- | --- |
| Think | /office-hours, /spec | Turn a sub-milestone brief into a precise spec before the build session starts |
| Plan | /autoplan, /plan-eng-review, /plan-design-review, /plan-devex-review | Each sub-milestone plan (eng review for 1b and 4d, design review for the web pages and the console, devex review for the admin API) |
| Design | /design-consultation, /design-html, /design-review, /diagram | Web dashboards and console (4a to 4c, 6b); sequence and state diagrams in docs 17 to 19 |
| Build | Claude Code with CLAUDE.md; android-permissions-security, android-intent-security, play-policy-insights (Flutter Android host); azure-prepare, azure-enterprise-infra-planner, azure-validate (infra) | Android host project and Azure IaC |
| Review | /review on every pull request; /cso at 2d, 4c and before 7c; /codex as second opinion on the sync engine and the ingest transaction; /investigate for defects | T-2-70..75 audit, T-7-70 |
| Test | /qa and /qa-only for web and API; /browse for any web page check; /benchmark for the web performance budget; android-profiler and r8-analyzer for battery and APK size | T-4-47, T-1-45, T-1-35 |
| Ship | /ship, /land-and-deploy, /canary, /setup-deploy, /document-release; azure-deploy, azure-validate | blue and green traffic weights 10, 50, 100 (D-14); wave days |
| Reflect | /retro at every phase exit; /learn | exit report lessons; re-baseline at 1c (D-307) |
| Safety | /careful, /freeze, /guard | around migrations, production config and the importer |

The Jetpack Compose and Kotlin-architecture skills apply only if docs/13 Q1 chose native Kotlin; Flutter is the default (D-01).

### 9.6 Branch and release rules

| Rule | Statement |
| --- | --- |
| Branching | Trunk-based: short-lived branches named <sub-milestone>/<id>-<slug>; no direct push to main; merge queue so the integration suite runs on the merged result |
| Pull request | Must cite the F-ids, G-ids, D-ids and T-ids it touches; the PR template carries the definition of done of s9.3; required checks are everything in the pr workflow (lint, typecheck, contract drift and oasdiff, unit, integration, property, goldens, db squawk, infra what-if, CodeQL, Semgrep, gitleaks, dependency review, rtm-check) in 15 minutes or less |
| Main to staging | Expand-migrate, deploy at 0 percent, smoke, 100 percent, full e2e, k6 smoke; staging soak 24 hours (72 hours for ingest, backfill or the Drift schema) before a tag is promotable (D-14) |
| Production | Manual promote with two approvers (engineering lead and a business approver); deployments outside 07:00 to 10:00 and 16:30 to 19:30 Dhaka and never in a wave's first three days unless a P1 hotfix; traffic weights 10, 50, 100 with a 10-minute canary and automatic abort; config freeze windows 07:00 to 09:30 and 16:30 to 19:30 (D-100) |
| Migrations | Forward-only, expand then contract; the contract phase runs only after no revision older than N serves traffic; a bad migration is fixed forward |
| App releases | Tag app-v*; per-ABI signed APKs; upload key apart from the signing key; independent reproducible rebuild before publish; publish is a human action (D-122); staged wave_pct; every candidate ships with its perf file and gate report |
| Release train | Weekly to staging (Tuesday); production on Thursday outside the change window; app candidate every two weeks through the device lab (doc 20 s5) |

### 9.7 Guardrails (stated once, respected by every document)

1. No engineer or agent reaches, calls, scrapes or reverse-engineers Apsis's running backend or app; the new system defines its own API (D-312).
2. Credentials or tokens found in the Apsis dump are data to migrate or rotate and are never used against a live service (D-119).
3. No secrets in the repository; Key Vault and OIDC only (D-122).
4. No fabricated measurement: a figure that was not measured is an ASSUMPTION or "unknown; confirm with the business". Azure costs are unverified list prices (D-06).
5. Vendor strings, artwork and the manual's example password are never reproduced (D-244).

### 9.8 Exit report

Every phase and sub-milestone ends with `/docs/evidence/phase-N/EXIT.md`: the gate table with links, open gaps carried forward (owner, target sub-milestone), decisions taken (D-ids), the answers received and defaults accepted (s4), measurements (battery, size, p95), the constraints exercised (s9.4) and two signatures (tech lead and the sponsor's delegate).

Proved by: T-0-46 (rtm-check), T-0-40 (PR pipeline), T-0-43 (deploy pipeline), T-1-42 (coverage floors).

## Open items

Items this document could not resolve alone, and disagreements with the plan skeleton (the document follows the skeleton in every case). Every MUST-CONFIRM decision it applies is in s4.2 by D-id (101 rows), and the 62 raised by docs 15 to 21 and by the round-2 review are in s4.2b and the 10 raised by the round-3 review in s4.2c; documents 15 to 21 carry their own OI-<doc>-nn tables, which the merge copies here as pointers (OI-14-11).

| OI | Item | Why open | Owner role | Needed by | Proceeds on meanwhile |
| --- | --- | --- | --- | --- | --- |
| OI-14-01 | Gate-family placement differs between the skeleton and the quality lens for 4a, 4b, 7a, 7b and 7c (for example the skeleton puts T-7-80..82 in 7a, while the pilot-pass gate T-7-82 is the 7b exit in the lens; T-7-88 is decommission in the lens and a 7c gate in the skeleton) (G-14-01) | Two sources, one id space | doc 20 author | 0a | s3 shows the skeleton family column and names gates by title; doc 20 s3 is authoritative Resolved at the editorial merge: s2.1 and the s3 phase tables list doc 20 placements. |
| OI-14-02 | Skeleton row 1a lists D-20 for the lighter and match unit question; D-20 is the business-date cutoff and that question is D-16 (due 2a) (G-14-06) | Skeleton wording | plan owner | 1a | The slice sells cigarette SKUs only; cutoff is the 1a decision, units the 2a decision |
| OI-14-03 | 4b (report set) and 6a are not 7c entry conditions in the skeleton although wave-1 supervisors need reports (G-14-07) | Entry table of the skeleton | plan owner, sponsor | 4d | Schedule has 4b exiting before 7c; if it slips, sync-health and Daily Tracking serve wave-1 supervisors and Apsis reports remain for the unswitched |
| OI-14-04 | Skeleton s3.2 row "Web after programmes" does not match docs/12, where web is Phase 4 and programmes Phase 5 | Skeleton wording | plan owner | 0a | Read as the pilot-gating rule: sync-health, Daily Tracking, Final Submit, outlet approval, Web Entry fallback and OTP panel land in 4a and 4c (s2.2) |
| OI-14-05 | No team, budget or start date is stated; weeks and person-weeks are ASSUMPTION (G-14-02) | Not in docs/01 to 13 | Sponsor | 0a | D-307: 8 to 10 engineers; Week 1 starts Sunday 2026-10-11; the Board envelope (programme cost, cost per week of delay, headcount, latest wave-1 date, Apsis contract end) is asked at 0a (D-555, s2.5b, s4.2c) |
| OI-14-06 | Programme-tier outlets are 10.4 percent of outlets: zone exclusion for wave 1 may not be workable (G-14-03, D-306) | docs/22 P-14 arithmetic | Sales ops, trade marketing | 5a | Pilot avoids programme outlets; test at 7b entry; 5a before wave 1 if above 10 percent |
| OI-14-07 | Pre-bind day cannot fall on Friday; weekday rule and Ramadan trading hours (G-14-04, D-311) | Calendar | Sales ops | 7b | Waves start Sunday to Tuesday |
| OI-14-08 | Apsis dump delivery date and route (G-14-05, D-303) | Legal chase | Legal, sponsor | 0a (letter), 7a (data) | Importer on the synthetic generator |
| OI-14-09 | The assignment asked for the top 15 risks; the skeleton binds 25 | Different counts | plan owner | 0a | 25 delivered; rows 1 to 15 are the ranked top |
| OI-14-10 | The skeleton outline has no H2 for the one-page architecture, the decisions summary, the requirement matrix or the first-two-weeks list | Outline | plan owner | 0a | Placed in s1.3, s1.4, Traceability and s3.0 |
| OI-14-11 | Documents 15 to 21 did not exist when this document was written; section references follow the skeleton outlines (for example doc 16 s9, doc 17 s4, doc 18 s7, doc 19 s5.2, doc 20 s3, s7 and s10, doc 21 s11) | Parallel writing | plan owner | merge | Re-check every reference and copy the OI tables at merge (skeleton s9.4 checklist) Resolved at the editorial merge: every "doc NN sX" reference in docs 14 to 21 was checked against the headings of the target document (220 cross-document references, none dangling); the OI tables of docs 15 to 21 stay in those documents and are not copied here. |
| OI-14-12 | Dates of moon-dependent holidays (Ramadan, both Eids, Ashura) are astronomical estimates; the Bangladesh gazette governs; the calendar table of s2.3 is a planning aid | Estimates | Sales ops | 7b | Re-check the planning date when the 2027 gazette is published |
| OI-14-13 | The skeleton table for D-208 to D-244 has no status column; doc 14 classified them (D-219 LOCKED-BY-SPEC, the rest DEFAULT) | Skeleton gap | plan owner | 0a | DECISIONS.md change log |
| OI-14-14 | Q41 to Q55 and the lens gaps that cite "D-qa-nn", "D-sync-nn" and similar lens decision ids are mapped to D-ids by the skeleton; a lens id that is not mapped is not binding | Lens ids | plan owner | merge | Skeleton D-ids only |
| OI-14-15 | Phase weeks use whole-team staffing for P0 to P2 and split streams later; a smaller team lengthens elapsed time roughly in proportion (2.4) | Team size | Sponsor | 0a | s2.4 |
| OI-14-16 | The owner role of each of the 50 docs 15 to 21 MUST-CONFIRM decisions of s4.2b is a pointer to the Open items table of the owning document, not yet a named role from the s4.1 list | Raised at the editorial merge; the owning documents name roles in prose | doc 14 author | 0a | the default in the "Proceeds on" column |
| OI-14-17 | The schedule is re-baselined by D-515 (1b 2 weeks, 2e 2.5, 4d 1.5) and the unreserved float before the planning date is 0 to 2 weeks once the 2-week pilot reserve is held (s2.3, s2.5, RK-26); weeks stay ASSUMPTION until the 1c velocity re-baseline | The first plan's contingency was 2.5 to 6.5 weeks | Sponsor, PM | 1c exit | Planning date Sunday 2027-06-20; D-307 is amended to say so (critical path 32 weeks to the 7c entry, wave 1 starts in week 37, planning float 2 to 4 weeks of which 2 are the pilot reserve, unreserved 0 to 2 weeks; the first draft said 31.5 weeks and 2.5 to 6.5 weeks of contingency); a sponsor decision to move it by up to 4 weeks is preferred to cutting a gate |
| OI-14-18 | CLAUDE.md, the README, docs 01 to 13 and db/schema.sql are outside the files this planning pass may edit; the precedence text (s9.2 rule 11) and the superseded-by banners are not yet applied (D-534, G-qa-60) | A planning pass does not edit the project brief or the base documents | Sponsor (owner of CLAUDE.md) | 0a exit | Plan documents are read as binding by every session through s9.2 and DECISIONS.md; the ready-to-paste text, the banners and the sponsor's action before kickoff are in s9.2b (D-554, T-0-160) |
| OI-14-19 | The evidence files cited by ids, gates and decisions (manual inventories, delta register, six V-* files) are not yet under /docs/evidence (D-530, G-qa-55) | They were produced outside the repository | Sponsor, tech lead | 0a exit | T-0-154 blocks the 0a exit; gates that read them stay pending-oracle; the DECISIONS.md header now says the files are not yet committed (D-554) |
| OI-14-20 | Parity Exceptions Register, usage census and the Apsis delta contract need signatures and owners by 0c (D-502, D-503, D-514) | Only AKTCL can sign | Sponsor, sales operations, legal, IT | 0c exit | Register rows ship as built-but-hidden; 7a and 7c do not start without the delta contract |
| OI-14-21 | Severity of the 61 round-2 gaps is the skeptic's and is not re-graded here; s7.1 to s7.5 counts are unchanged (s7.8, s7.9) | The skeptic review gave closing decisions, not a new severity table | doc 14 author | 0a | s7.8 lists the gaps with closing gates; counts of s7.1 stay at 337 plus the 87 and the 61 |
| OI-14-22 | The Board envelope: Apsis contract end and monthly fee, the loaded weekly rate, confirmed headcount and the latest wave-1 date are unknown; s2.5b fixes the arithmetic only (G-qa-87, D-555) | Only the sponsor and finance can state them | Sponsor, finance | 0a exit | The planning basis D-307; the Apsis contract assumed to cover wave 4 (RK-30); no figure is invented |
| OI-14-23 | Severity of the 54 round-3 gaps is the reviewer's and is not re-graded; the one blocker is G-qa-122 (s7.9) | The review gave closing decisions, not a severity table | doc 14 author | 0a | s7.1 to s7.5 stay at 337 |
| OI-14-24 | Eight manual questions have no carrier decision (MQ-07, 13, 35, 36, 40, 42, 65, 66) and three are carried only by the Parity Exceptions Register (s4.4) | The manuals do not show the behaviour | Product owner | per MQ date | Built to the observed behaviour and labelled "unknown; confirm"; item 5 of s4.5 collects the captures |
| OI-14-25 | D-10, D-11 and D-12 move from 2e and 1c to the 0c exit, so the APK channel, the Android floor and the fleet census are due in week 3, two to five weeks earlier than the first plan (G-qa-94, G-qa-97) | The Google verification rule and the battery classes need them first | IT, sales operations | 0c exit | The defaults of s4.2 stand; the 0c exit is blocked without an answer or a written acceptance of the default |
| OI-14-26 | The half-team scenario of s2.5b is a proportion of person-weeks, not a re-plan | Team size is unconfirmed | PM, sponsor | 0a | Re-baseline at 1c from measured velocity |
| OI-14-27 | Counts and repeated constants are kept by hand in this document until `/plan/constants.yaml` exists (T-0-159) | A tool to build at 0a | Tech lead | 0a | s9.4b is the register |

## Traceability

### R to documents, features, config keys and gates

Features are the pinned and reserved ids of the plan (doc 15 s12 holds the full RTM: R to F to G, cfg, D, T and evidence); config keys are the registry names of doc 19 s3; gates are families and anchors (doc 20 s3 places each).

| Req | Documents | Features | Config keys | Gates |
| --- | --- | --- | --- | --- |
| R1 DATA | 16, 15, 18, 20 | F-WEB-053..056, F-WEB-058, F-SYS-049, F-SYS-086, F-SYS-094, F-SYS-096; every report and tile in F-WEB-001..047 | cfg.agg.poll_interval_s, cfg.kpi.* (bands, bar_bands, tilldate_basis.<surface>, submit_pct_denominator, target_route_kinds, dues_buckets), cfg.calendar.*, cfg.retention.*, cfg.sync.max_savepoints_per_tx, cfg.geo.country_bbox, cfg.sla.dss_stale_alert_s | T-0-01..04, T-1-01..04, T-2-01..04, T-4-01..03, T-4-43, T-4-46, T-7-80, T-0-150, T-0-151, T-1-150, T-4-150, T-4-151, T-0-156, T-0-158, T-1-155, T-4-166, T-4-167, T-4-168, T-4-169 |
| R2 FEATURES | 15, 20, 17 | F-SR-051..066, F-AMO-037..043, F-TSO-021..026, F-WEB-048..057, F-API-039..046, F-SR-081, F-AMO-049, F-ADM-078..085, F-SYS-097; the 509 inventory ids | cfg.print.template_version, cfg.i18n.digit_script, cfg.app.default_locale, cfg.web.menu_by_role, cfg.tso.dashboard_tiles, cfg.tso.product_scope, cfg.memo.rounding_mode, cfg.memo.allow_negative_net, cfg.sale.max_lines_per_memo | T-0-46, T-0-49, T-1-41, T-2-41, T-2-43, T-2-49, T-3-42, T-4-40, T-4-41, T-5-41, T-6-40, T-x-120..139, T-0-153, T-7-158, T-0-161, T-0-163, T-2-161, T-3-160, T-5-123 |
| R3 SCALE | 18, 17, 16, 20 | F-SYS-047, F-SYS-048, F-SYS-050, F-SYS-087, F-SYS-088 | cfg.api.rl.*, cfg.bundle.regen_max_per_s, cfg.ops.prescale_schedule, cfg.ops.sync_hold_by_version, cfg.auth.hash_concurrency_per_replica, cfg.sync.login_jitter_s, cfg.sync.resync_window_h, cfg.sync.checkout_upload_jitter_max_s, cfg.sync.digest_days, cfg.cutover.*, cfg.sec.dek_cache_min, cfg.auth.upload_access_ttl_min | T-1-51, T-1-52, T-2-51..54, T-4-51..57, T-7-51..56, SRE gates T-1-53..56, T-2-55..58, T-4-58, T-4-59, T-7-57..59, T-7-83, T-7-85, T-2-155, T-4-155..164, T-7-150..152, T-7-161..168, T-4-173, T-4-174, T-4-158 |
| R4 BATTERY | 17, 20 | F-SR-064, F-SYS-050 | cfg.media.photo_max_kb, cfg.media.evidence_mobile_fallback_h, cfg.sync.debounce_s, cfg.geo.require_precise, cfg.telemetry.device_max_bytes_per_day, cfg.app.local_history_days, cfg.telemetry.enabled, cfg.telemetry.bat17_floor_pct, cfg.ops.prefetch_enabled, cfg.release.auto_freeze_regression_pct | T-1-35, T-1-44, T-1-45, T-2-40, T-2-45, T-2-153, T-3-151..153, T-7-156 (each stated per battery-size class, D-564) |
| R5 OFFLINE + IMMEDIATE SYNC | 17, 16, 18 | F-SR-060, F-SR-063, F-SYS-047..049, F-SYS-089, F-SYS-090, F-SYS-095 | cfg.sync.debounce_s, cfg.sync.family_skip_after, cfg.sync.max_backdate_days, cfg.day.submit_settle_timeout_min, cfg.bundle.stale_max_days, cfg.app.logout_block_when_pending, cfg.auth.offline_unlock_max_days, cfg.sync.resync_safety_margin_h, cfg.sla.pending_rows_alert_h, cfg.sync.resync_late_max_days, cfg.calendar.window_unit, cfg.calendar.break_overrides | T-1-20..35, T-1-40, T-2-20..34, T-2-40, T-3-40, T-5-40, T-7-82, T-1-152, T-2-154, T-7-157, T-1-156, T-2-163, T-2-169, T-3-157, T-3-159 |
| R6 ADMIN CONFIG | 19, 17, 21 | F-ADM-038..055 and F-ADM-072..077 (console pages P1 to P19; P19 is the Support desk), F-API-040..042, F-TSO-025, F-ADM-078..085, F-SYS-091, F-SYS-092 | cfg.geo.radius_m (with radius_min_m and radius_max_m), cfg.geo.mock_policy, cfg.geo.max_accuracy_m, cfg.geo.no_location_policy, cfg.geo.tso_radius_mode, cfg.day.checkout_earliest_time, cfg.release.min_version, cfg.release.blocked_versions, cfg.sys.change_freeze_windows, cfg.flag.*, cfg.sec.fraud.*, cfg.sla.config_reach_urgent_min, cfg.sla.config_reach_tail_h, cfg.geo.radius_emergency_max_m, cfg.sys.temporary_relief_max_h, cfg.sla.online_window_min, cfg.sla.config_reach_selling_min, cfg.sla.config_reach_selling_pct, cfg.sys.brake_lane_keys, cfg.sys.device_model_scoped_keys, cfg.price.max_change_pct | T-0-60..64, T-1-60..64, T-2-60..69, T-6-41, T-6-42, T-6-70..72, T-7-60, T-2-156..160, T-0-157, T-2-165..174, T-6-150, T-6-152 |
| Process (plan first, phase by phase, testable) | 14, 20 | none (gate system) | none | T-0-40..49, T-1-47, T-2-48, T-6-42, T-0-152, T-0-154, T-0-155, T-0-159, T-0-160, T-0-162 |

### Ids used in this document and where they are handled

| Family | Used | Handled in |
| --- | --- | --- |
| D-ids | D-01 to D-601 as cited; the 173 MUST-CONFIRM rows (s4.2, s4.2b and s4.2c) | s1.4, s4.2, s4.2b, s4.2c, s5; all rows in DECISIONS.md |
| G-ids | the 54 round-3 gaps G-qa-86 to G-qa-139 (s7.9); the 61 round-2 gaps G-qa-25 to G-qa-85 (s7.8); the 87 gaps minted by docs 15 to 21 (s7.7); the 37 blockers (s7.4); G-qa-06, G-qa-07, G-cfg-18, G-fraud-18, G-qa-22 and G-14-01 to G-14-08 (s7.5); counts for all 337 (s7.1 to s7.3); the 15 manual findings (s7.6) | s7 |
| T-ids | families and anchors per sub-milestone | s2.1, s3, s9.4; doc 20 s3 |
| RK-ids | RK-01 to RK-33 | s6 |
| Q and MQ | Q1 to Q55; MQ-01 to MQ-68 | s4.3, s4.4; DECISIONS.md section Q |
| F-ids | ranges per phase and pinned ids | s3, Traceability above; doc 15 |
| R-ids (rejected recommendations) | R-01 to R-34 | DECISIONS.md section R |
| Board envelope and constants | the Board envelope s2.5b (D-555); the constants registry s9.4b (D-576); the ready-to-paste precedence text s9.2b (D-554) | s2.5b, s9.2b, s9.4b; T-0-159, T-0-160, T-0-162 |

Constraint rows: CLAUDE.md constraints 1 to 8 are traced to gates per phase in s9.4 (D-310). Process requirement: s2, s3 and s9.3; sponsor requirements R1 to R6: s1.2 and the matrix above.

