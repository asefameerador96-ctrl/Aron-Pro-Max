# Plan skeleton: the binding structure for documents 14 to 21 and DECISIONS.md

Date: 2026-10-04. Author: lead architect. Status: BINDING for the eight parallel writers. Where a writer's source material (a lens, a critic, a verification, the manual register) disagrees with this file, THIS FILE WINS and the writer notes the disagreement in the document's "Open items" section.

Inputs read in full: CLAUDE.md, PROJECT-CONTEXT.md, README.md, docs/01 to 13, docs/22, docs/ui-reference/README.md and sr/home.md, db/schema.sql, db/seed/README.md and the CSV header, plan/seed-findings.md, the seven lens files, the four critic files, the six verification files, and the manual register (sections 0, 1, 2.1 to 2.8, 3, 4, 5, 2.7 in full).

## Contents

| # | Section | Use |
| --- | --- | --- |
| 0 | Order of authority, how to use this file | all writers |
| 1 | Final document set and section outlines | each writer: your outline |
| 2 | Canonical decisions D-01 to D-269 (and D-300 to D-499 reserved) | use verbatim; Q registry; rejected recommendations |
| 3 | Phase structure (phases 0 to 7 with sub-milestones) | reference exactly |
| 4 | Canonical KPI formulas and the two Submit % names | reference exactly |
| 5 | Naming conventions and reserved id ranges (F, G, D, T, Q, M, cfg, tables) | do not collide |
| 6 | Load model inputs and data-profile findings P-01 to P-16 folded in | docs 16, 18 |
| 7 | Consolidated gap register (every G-* and G-man-*, one owner each) | each writer copies its owned rows |
| 8 | Writing rules | all writers |
| 9 | Hand-off notes: parallel-writing risks and how they are closed | all writers |

## 0. Order of authority and how to use this file

Order of authority when two sources disagree (highest first):

1. CLAUDE.md non-negotiable constraints 1 to 8 (offline-first, idempotent sync, battery, server-side scope, geo plus anti-spoofing, no-hiccup cutover, Dhaka business date, bilingual).
2. docs/01 to 13 as they stand, except where a decision below changes them deliberately (each such change is a D-id with the reason, and doc 14 lists them in the "deliberately changed" table).
3. This file (decisions D-xx, KPI formulas, id ranges, gap assignments).
4. Verification files (they override the manual register wherever they say PARTLY or REFUTED).
5. The manual register, the four manual inventories, docs/22, docs/ui-reference.
6. Lens files and critic files (they carry the depth; they pre-date the manuals).

Rule for conflicts between the manuals and the spec (set by the sponsor): PARITY FIRST. The manual's observed behaviour wins unless it breaks one of the eight constraints, in which case the constraint wins and the change is a deliberate decision with a reason.

Markers writers must use: `ASSUMPTION` (our call, say why), `unknown; confirm with the business` (only AKTCL can answer; give the proceed-with default), `PARITY` (reproduces observed behaviour), `IMPROVEMENT` (not observed; deliberate addition), `DELIBERATE CHANGE` (differs from observed behaviour; has a D-id).

How to use: read section 1 for your outline, section 5 for the ids you may mint, section 7 for the gaps you own, and use section 2, 3 and 4 verbatim wherever those topics appear. Do not restate docs/01 to 13; cite them.

## 1. Final document set and section outlines

Files (all under /home/user/Aron-Pro-Max):

| # | File | Title | Writer owns |
| --- | --- | --- | --- |
| 14 | docs/14-master-build-plan.md | 14 - Master Build Plan | phases, schedule, requirement map, confirmation schedule, risk register, gap roll-up; also writes /home/user/Aron-Pro-Max/DECISIONS.md |
| 15 | docs/15-feature-inventory.md | 15 - Feature Inventory and Traceability | every F-id, screen/label parity, rules now stated, localisation catalogue, RTM model |
| 16 | docs/16-data-platform.md | 16 - Data Platform: Schema v2, Facts, Dimensions, Aggregation | tables, DQ rules, dw layer, KPI queries, importer data design |
| 17 | docs/17-sync-engine.md | 17 - Offline-First Sync Engine, Battery and Data Budgets | device store, sync protocol, bundle/delta, budgets, printing, app lifecycle |
| 18 | docs/18-scale-reliability-azure.md | 18 - Scale, Reliability and Azure Architecture | load model, Azure design and SKUs, failure modes, SLOs, runbooks |
| 19 | docs/19-admin-config.md | 19 - Central Admin Dashboard and Runtime Configuration | config model, key registry, admin pages, back-office write tools, rails |
| 20 | docs/20-test-strategy.md | 20 - Test Strategy, Phase Gates, CI/CD and Cutover Verification | gate register (T-ids), pyramid, CI/CD, parity oracle, pilot/cutover tests |
| 21 | docs/21-security-privacy.md | 21 - Security, Privacy and Anti-Abuse | auth, authz, PII, hardening, fraud catalogue, audit, supply chain |
| - | DECISIONS.md | Decisions log | written by the doc 14 author from section 2 of this file |

Every document, in this order: title line; "What this doc decides" box (exactly 5 lines); numbered H2 sections as below; "Open items"; "Traceability". Each H2 below lists what it must contain and which input files feed it. "L-xxx" means lens-xxx.md, "C-xxx" means critic-xxx.md, "V-xxx" means verification-xxx.md, "REG" means the manual register, "MAN" means the four manual inventories.

### 1.1 docs/14-master-build-plan.md

| Heading | Must contain | Feeds |
| --- | --- | --- |
| What this doc decides (box) | five lines: phase structure, sponsor-requirement mechanisms, confirmation schedule, top risks, what changes versus docs/12 | this file s2, s3 |
| 1 Sponsor requirements to mechanism | table R1..R6 plus the process requirement: mechanism, owning doc, phase it first works, the gates that prove it (copy L-quality s2.10 and extend with manual and critic gates) | L-quality s2.10, all lenses s0 |
| 2 Phase structure | 2.1 phase and sub-milestone table (goal, entry, exit, demo, indicative weeks) exactly as this file s3; 2.2 what changes versus docs/12 and why (promotion engine to 2a, supervisors split, web before programmes, pilot gates); 2.3 critical path and dependency graph (data profile, baseline pack, Apsis dump, Q confirmations); 2.4 parallel streams (app, api, web, data, infra, QA) and team shape (ASSUMPTION: 8 to 10 engineers) | L-quality s2, docs/12, C-sre s0 |
| 3 Phase-by-phase plan | one H3 per phase 0..7: goal; deliverables by document (14..21); sub-milestone demos; blocking gates by id; MUST-CONFIRM decisions due; gaps closed (blockers first); exit check a non-engineer can run | L-quality s2.2 to 2.9, sections 3 and 7 here |
| 4 Business confirmation schedule | every MUST-CONFIRM decision and Q with owner role, the phase it blocks, what proceeds on the default meanwhile (generated from s2 of this file) | s2 here, REG s5 |
| 5 Deliberately changed from Apsis | the table of DELIBERATE CHANGE decisions (D-ids) for AKTCL sign-off: scope, aggregates, flavours, anti-spoof, budgets plus those from the manuals (photo moves outlet, live location, logout wipe, QC/print order if applicable, Delete Section Data, OTP re-verify, till-date bases) | docs/13, s2 here |
| 6 Risk register | top 25 risks (likelihood, impact, owner, mitigation, early-warning gate): fleet/device unknown, memo layout unknown, promo catalogue unknown, Apsis dump content, legal/residency, wave first morning, DR re-sync, poison row, parallel-run oracle, helpdesk | C-sre, C-field, C-fraud, L-security s15 |
| 7 Gap roll-up | counts by owner doc x phase x severity (from s7 here), the list of blockers with owner and closing gate; pointer: each doc 15..21 carries its own gap table | s7 here |
| 8 Cutover summary | pilot design, parallel-run pass rule, waves, rollback; points to docs 18 and 20 for detail | L-quality s5, L-scale s5.4, docs/11 |
| 9 Working agreements | how Claude Code sessions use CLAUDE.md, DECISIONS.md, the gate register; Definition of Done (CLAUDE.md) made checkable; gstack skill per stage; branch and release rules | CLAUDE.md, L-quality s3 |
| Open items / Traceability | collected open items from all docs; R to doc map | all |

### 1.2 docs/15-feature-inventory.md

| Heading | Must contain | Feeds |
| --- | --- | --- |
| What this doc decides (box) | 5 lines: the F-id universe, parity rules, new features from manuals, localisation catalogue, RTM model | |
| 1 Scope, id scheme and counts | F-ROLE-nnn scheme, ranges (s5), counts per role and per phase after additions, coverage statement (manual inventory 31.6% fully covered; 84.5% at least partly) and how this doc closes the gap | L-features s9, REG s4 |
| 2 System features (F-SYS) | table as L-features s1 plus new F-SYS (s5.2 pins): columns id, name, roles, what it does, offline class (OFFLINE/QUEUED/HYBRID/CACHED/ONLINE-ONLY), writes/reads (doc 16 table names), cfg keys, gaps, decisions, phase | L-features s1, REG s2.6 |
| 3 SR app (F-SR) | all SR screens and flows in day order; each with parity evidence (manual page), rules now stated, messages pointer; includes F-SR-051..066; home tile grid incl. Sales Journey and KPI (UI-SR-01/02, unknown; confirm) | L-features s2, REG s1, manual-sr.md, V-money, V-device, V-outlets, V-targets, C-field |
| 4 AMO app (F-AMO) | same format; zone-wide bundle, supervisor_day, SS designation, per-user tile resolution, Exceptions, cover assignment, DH confirmation proxy | L-features s3, REG, manual-amo.md, C-field |
| 5 TSO app (F-TSO) | same; online-only matrix, read model, final submit preview and rules, leave, plans, feedback, chrome | L-features s4, REG G-man-069..084, manual-tso.md |
| 6 Web (F-WEB) | the 41-page union with the 37-page spec parity map; reports with ReportQuery params (pointer to doc 16); new pages (Web Entry, Astha Web Entry, web Final Submit, DSS, DS-RRS, Route-wise Memo, Loyalty report, QC pages, Exceptions, dues ageing, DH settlement); role x menu x action matrix pointer to doc 19 | L-features s5, REG G-man-092..099, V-web, manual-web.md |
| 7 Admin and master data (F-ADM) | entity CRUD list (58 entities), back-office write tools by role (pointer to doc 19 for behaviour) | L-features s6, s8c |
| 8 API catalogue (F-API) | endpoint table: method, path, caller, scope rule, idempotency, online/offline, phase; includes new endpoints (final-submit preview, config delta/public/ack, cover, day exceptions, outlet-kind bulk, support ping) | L-features s7, docs/09 |
| 9 Rules now stated | per area (sale, memo, credit and dues, QC, DRP/slide, stock, outlet requests, tasks, leave, final submit, Astha/DL/Superstar, targets, attendance, geofence/force sale): the rule, PARITY or IMPROVEMENT, decision id, cfg keys | L-features s8a/8b, REG s2.3, V-* |
| 10 Online and offline behaviour matrix | every surface classed OFFLINE/LOCAL/QUEUED/HYBRID/CACHED/ONLINE-FIRST/ONLINE-ONLY with the requirement (register s2.6 extended) | REG s2.6 |
| 11 Localisation, glossary and message catalogue | glossary (OHS, SOQ, STD/STT, CPR, BSR, DSS, DS-RRS, GIGO, WMO, PDA, FF, Bikroy Joma, Astha tiers, HLP, C&C), terminology table (route/section, cluster/zone, house), label-format templates, number/date/time/money profile (digits follow UI language, Western grouping, 2 dp with ৳, ISO dates in lists), message catalogue plan (251 manual rows, about 225 app-owned; authored error set), per-role default locale | REG G-man-037, 101..104, MAN message registers |
| 12 Requirement and decision traceability model | RTM columns, how F links to R, G, cfg, D, T, evidence; rtm.yaml shape; counts | L-quality s6 |
| Open items / Traceability | | |

### 1.3 docs/16-data-platform.md

| Heading | Must contain | Feeds |
| --- | --- | --- |
| What this doc decides (box) | 5 lines: money and units, idempotency model, schema v2 and migration map, dw layer and restatement, KPI query definitions | |
| 1 Principles and conventions | P1..P14 (L-data s3.1) restated as review checkboxes; naming (s5); money milli-taka; qty model; business date; provenance block; SCD blocks | L-data s3 |
| 2 Schema overview and migration map | schemas app, cfg, dw, stg; migration number ranges (s5.6); table-to-migration map; what changes in db/schema.sql (renames, drops) | L-data s3.3, REG s2.8 |
| 3 Reference and master data | geography (zone dep_id/dep_name/email/address/pda_contact/data_entry_date), route (kind, name vs label, visit days), products with variant level and status, prices (5 types, 3 decimals), sales plan, calendars, users (employee_code, ss designation), assignments incl. cover, clusters, classifications (9 sub-channels), channel table, reason codes as config tables, offers, QC fault taxonomy | L-data M-02..M-04, M-14, M-16..M-20, REG s2.8 |
| 4 Outlet book | outlet v2 (kind, price_type, status active/closed/merged/archived, location_confirmed, thumbnail, mobile check), history tables, requests with cluster plus route, location change requests, photos, merge model | L-data M-15/M-16, V-outlets, C-analyst G-12, docs/22 P-08/09 |
| 5 Field transaction tables | visit v2, geo_fix (incl. radio_env), memo v2 (deductions, rounding, due snapshot), memo_line, qc_entry_line, drp, survey, due_collection and allocation, stock_movement, attendance_event, redemption batch/lines, loyalty ledger, task, assessments, distribution check, price compliance, leave, visit plan, feedback, web entry tables, void/tombstones, final_submit and route snapshot, route_day, supervisor_day | L-data M-05..M-12, M-21..M-36, REG s2.8, C-field |
| 6 Sync, integrity and audit tables | ingest_registry (content_fp), sync_batch v2, sync_rejected, sync_conflict, media_object, bundle_download, reconcile_snapshot, audit_log, security_event, risk_signal pointer to doc 21 | L-data M-30, M-31, M-43 |
| 7 Data-quality rules | DQ-01..DQ-43 table with policy REJECT/PARK/FLAG/CLAMP, flag code, owner; new rules from critics | L-data s6, C-fraud s4.2 |
| 8 Analytics layer (dw) | dims, event facts (fact_memo added), daily and month aggregates, user-day, due ageing, price/offer provenance, snapshots and restatement log, supervisor history, source/fidelity columns, fact_device_day; worker mechanics (dirty queue, recompute, 5 s daytime poll, staleness, rebuild, reconcile) | L-data s4, C-analyst s2, C-sre G-17 |
| 9 KPI definitions and report data sources | the canonical KPI table (s4 of this file) expanded to SQL-level definitions on dw; every docs/09 report and every new report mapped to a dw-only query; ReportQuery schema; STD/CPR/BSR/till-date per surface; zero-target handling | L-data s4.5, REG G-man-053/060/064/067/092..097 |
| 10 Targets and programmes data | target_set/approval, variant level, Astha tiers, Diamond League ledger/expiry/redemption batch, Superstar, promotions | L-data M-21..M-24, REG G-man-040..048, 068 |
| 11 Calendar, day state and planning data | working-day calendar, route-day planning, day-state entity table, login event, offline start, exceptions, covers | L-data M-32, D-08/09, C-analyst G-10, C-field G-02/06 |
| 12 Importer and migration data design | Apsis dump request (memo-level fields), staging, crosswalk (outlet code normalisation, sku_id), archived stubs, timestamp parser, quarantine, opening balances, path B for aggregate-only months, import_run and rollback, control totals | L-data M-42, docs/22, C-analyst G-09, C-sre G-21 |
| 13 Retention, archival, PII classification, BI access | retention classes, archival jobs, PII fields and masking, bi_reader grants, read replica | L-data s5 |
| 14 Gaps owned / Open items / Traceability | s7 rows owned by 16 | |

### 1.4 docs/17-sync-engine.md

| Heading | Must contain | Feeds |
| --- | --- | --- |
| What this doc decides (box) | 5 lines: outbox model, triggers (R5 without polling), wire contract and recovery protocols, budgets as release gates, printing and memo numbers | |
| 1 Principles | device is system of record until ACK; three idempotency layers; no polling; budgets are gates | L-sync s0 |
| 2 Local store | per-user SQLite via Drift plus device file; WAL+FULL; SQLCipher decision (D-xx); outbox, media queue, ref cache, session tables; footprint; purge rules | L-sync s1, L-security s9 |
| 3 Capture rules | commit points (draft at Proceed, print is the commit, print optional, QC independent), call-start prompt, family atomicity, kill-and-relaunch, abandoned visits, visit outcome/skip, void, acting_for, identity confirmation | L-sync s8.4, REG G-man-006/008, C-field |
| 4 Sync engine | triggers T1..T8, debounce 5 s plus family hold, batch builder, flat ordered wire contract with envelope (batch_uuid, server_totals, day_states, hold_s, X-Server-Generation, X-Batch-Attempt, X-Pending-Rows), response handling per HTTP outcome and edge responses, backoff, poison-row isolation and skip-ahead, submit settle, server-generation re-sync, media upload via SAS | L-sync s2, C-sre G-01/02/03/10/14, L-scale s4.5 |
| 5 State machines | per record, per route-day mirror (entity ownership), per device session, time and clock (trusted anchor primary), midnight | L-sync s3, D-20 |
| 6 Bundle and config propagation | bundle contents per role (SR, AMO zone-wide, TSO snapshot), size budgets and paging, pre-generation DAG consumption, delta protocol, stale-bundle policy, pre-fetch, config pull/ack/scheduled | L-sync s4, L-config s3, C-sre s3.4 |
| 7 Offline session, device binding, logout | client side of auth: offline unlock verifier, shared phones, multi-user engine, logout rules (refuse when pending), OTP screens, 401/403 handling | L-sync s3.3, L-security s2, REG G-man-020/021/024 |
| 8 Battery, data and size budgets | numeric gates, reference devices, scripted day, measurement protocol, per-release perf file | L-sync s5, s9.5 |
| 9 Printing and memo numbering | RPP02N SPP, PrinterPort, template contract (7 memo kinds, golden print), Bangla raster, print flow and failure paths, confirm-after-print, memo_no format and bind-ordinal blocks, reprint rules | L-sync s6/s7, REG G-man-014/023, C-field G-20 |
| 10 App lifecycle | flavours, updater (two stages, unknown-sources, SHA-256, resume, finish-offline-day), distribution channel, permissions gating matrix, PDA to Support, maps client rules, app chrome per role | REG G-man-020/022/025/059, L-sync s8 |
| 11 Edge-case catalogue | 8.1..8.20 of L-sync plus field and SRE additions | L-sync s8, C-field, C-sre |
| 12 Sync test plan | property, kill, airplane, flapping, reconciliation, printer, upgrade gates mapped to doc 20 ids | L-sync s9 |
| 13 Gaps owned / Open items / Traceability | | |

### 1.5 docs/18-scale-reliability-azure.md

| Heading | Must contain | Feeds |
| --- | --- | --- |
| What this doc decides (box) | 5 lines: re-based load model, Azure topology and SKUs, ingest/aggregation capacity design, reliability and DR protocol, launch-day operating model | |
| 1 Load model | canonical inputs (s6 of this file), per-SR day, storms (morning, trickle, 17:00 wave, worst case), wave first morning and pre-bind day, bundle, photos, dashboards, write amplification, peak RPS table, storage growth | L-scale s1, C-sre s1, docs/22 |
| 2 Azure architecture | diagram, region (SEA, DR East Asia, MUST-CONFIRM), Front Door, ACA workload profiles, PostgreSQL Flexible Server, Redis (Managed Redis), Blob (ZRS, RA-GZRS for evidence), Key Vault, MI, networking/private endpoints, hostnames, Bicep layout | L-scale s2, C-sre G-12/13 |
| 3 Capacity, quotas and cost | SKU ladder pilot/wave 1/full, quota requests and lead times, cost order of magnitude (unverified), reservation plan | L-scale s3 |
| 4 Ingest, aggregation and bundle capacity design | synchronous ingest, pooling and timeouts, outbox worker, recompute coalescing, hot-row avoidance, partition operations, pre-generation job DAG with deadlines, route_day 00:05 job, bundle serving | L-scale s2.8, C-sre G-08/09/14/15/17, L-data s4.4 |
| 5 Reliability | failure modes (L-scale s4 plus SRE incidents), DR and PITR with server generation re-sync, rollback matrix per change type, change freeze, edge fallback hostname, version-scoped hold, replay-cache write ordering | L-scale s4, C-sre s2, s3.2 |
| 6 SLOs, observability and alerting | SLIs measurable (fixes), dashboards, alerts with routing and runbooks, telemetry cost control, burn-rate alerts, calendar-aware baselines | L-scale s5, L-quality s4, C-sre s3.1 |
| 7 Launch-day operating model | runbook per wave (T-1, 00:05, 04:30, 06:15, 07:00..), pre-scale schedule, war room, rollback triggers read after submit settle, support visibility | L-scale s5.4, C-sre s8 |
| 8 Load-test plan | S1..S10 rebased, gates (T-1-5x etc.), tooling, staging at prod SKU windows | L-scale s6, C-sre s6 |
| 9 Environments and IaC | env matrix, Bicep structure, policy, secrets, OIDC; pointers to doc 20 for pipelines | L-scale s2.10, L-quality s3 |
| 10 Gaps owned / Open items / Traceability | | |

### 1.6 docs/19-admin-config.md

| Heading | Must contain | Feeds |
| --- | --- | --- |
| What this doc decides (box) | 5 lines: config model and precedence, risk classes and rails, propagation to field, admin pages and permission bundles, geofence management | |
| 1 Principles and scope | config vs master data vs content vs code; R6 requirements; what is not config | L-config s0, s1.11 |
| 2 Config data model | tables, scope levels (outlet > route > zone > geo_class > house > territory > division > wing > wave > role > global), resolution query, effective dating, versions, snapshots, caches, as-of rule | L-config s2, C-field G-11, C-sre G-15 |
| 3 Key registry | canonical keys by area (s5.7 of this file for naming), kind S/T/O, type, default, bounds, scope, editor, effect, risk class; columns "parity default" and "recommended" where the manual differs; alias retirement table; counts; manual-derived keys (REG s2.5) and critic keys merged | L-config s1, REG s2.5, C-fraud s4.4, C-field s11.1, C-sre s5 |
| 4 Propagation to the field | X-Config-Version, delta pull, ack, scheduled values, FCM urgent with jitter, kill switches never block upload, /config/public, reach view | L-config s3, C-sre G-07 |
| 5 Admin dashboard | 5.1 access model and permission bundles; 5.2 page map P1..P18 plus the TSO web back-office pages (Web Entry, Astha Web Entry, web Final Submit with audited void, QC entry and reports, Sales Plan edit, wholesale marking, SR Device OTP panel, Set Target and approval list, gift choice panel, outlet approval panel); 5.3 role x menu x action matrix as data | L-config s4, REG G-man-085..099, V-web |
| 6 Geofence management | radius by scope incl. geo_class and outlet override, map and density view, what-if on stored fixes, calibration plan, TSO bounded edit mode, location-unconfirmed handling, update-base limits | L-config s4.3, C-field G-11, docs/22 P-09/10, REG G-man-017/018 |
| 7 Change workflow and safety rails | request lifecycle, approvals, canary, delayed apply, two-sided anomaly watch, break-glass restrictive-only, change-rate limit, change-freeze windows, revert and rollback-to-version, fraud thresholds owned by cfg.sec | L-config s4.7/4.9, C-fraud G-09/10, C-sre G-22 |
| 8 Master-data and back-office tools | CRUD behaviour for the 58 entities with audit; future-dated rules for price/targets/calendar; back-date unlock grants; data_void; QC and Web Entry rules; bulk operations | L-features s8c, C-fraud G-11, REG G-man-086/087/088/089/090 |
| 9 Releases, waves and flags | release console, min_version/blocked_versions/sync hold by version, wave membership, flags, pilot_in_rollups | L-config s4.6/4.8, L-quality s3.7/3.8 |
| 10 Admin API | endpoints (L-config s4.10 plus new) | L-config s4.10 |
| 11 Phase plan for admin | what lands in 1c, 2d, 3, 4, 5, 6, 7 | L-config s5 |
| 12 Gaps owned / Open items / Traceability | | |

### 1.7 docs/20-test-strategy.md

| Heading | Must contain | Feeds |
| --- | --- | --- |
| What this doc decides (box) | 5 lines: gate = test + artefact + verifier, the gate register, parity oracle, CI/CD and zero-downtime rules, pilot/cutover verification | |
| 1 Gate system | definition, record shape, blocking rules, exit report, verifier never the author, demo per sub-milestone | L-quality s2.0 |
| 2 Test pyramid and tooling | layers L0..L6 per component with counts, tools, runtime, cadence; coverage floors and mutation; clock injection; no-mock rule | L-quality s1 |
| 3 Gate register | the master table of every T-id by phase and sub-milestone: id, lens/owner, title, test, artefact, verifier, blocking; includes ranges 01..09 data, 10..19 fraud, 20..39 sync, 40..49 quality, 50..59 scale and SRE, 60..69 config, 70..79 security, 80..89 pilot/cutover, 90..99 field, 100..109 analyst (renumbered), 120..139 manual parity; collision rules | all lenses and critics |
| 4 Parity oracle and golden fixtures | baseline pack (T-0-49) contents, memo corpus, fixtures taken from the manuals with their arithmetic cross-checks (list in s9.3 here), screen goldens, string glossary, report parity | L-quality s2.2, V-money, V-targets, REG |
| 5 CI/CD, environments and release | workflows, PR checks, main to staging, promote to prod, migrations expand/contract, flags, app distribution, release train, environments | L-quality s3 |
| 6 Observability verification and reconciliation reports | log schema, metrics catalogue pointer, nightly reconciliation reports, pilot daily sheet, alert fault-injection | L-quality s4 |
| 7 Pilot, cutover and support verification | preconditions, parallel-run comparison and pass rule, control totals, rollback drills, readiness checklist, waves and go/no-go, support tiers and arithmetic, training, decommission | L-quality s5, C-field, C-sre s8 |
| 8 Traceability mechanism | rtm.yaml, gates.yaml, gaps.yaml, rtm-check rules, generated RTM.md and GATES.md | L-quality s6 |
| 9 Test data strategy | testkit generators, synthetic fleet, anonymised corpus, apsis-dump generator with planted defects P-05..P-16 | L-quality s1.11 |
| 10 Phase exit checklists | per phase: the non-engineer script | L-quality s2 |
| 11 Gaps owned / Open items / Traceability | | |

### 1.8 docs/21-security-privacy.md

| Heading | Must contain | Feeds |
| --- | --- | --- |
| What this doc decides (box) | 5 lines: auth model, scope enforcement, PII handling, fraud-signal catalogue and spoofing defence, audit and supply chain | |
| 1 Threat model | actors (SR, SR+, AMO, TSO, ADM, SUP, EXT, CI), assets and classes | L-security s1, C-fraud s1 |
| 2 Authentication | tokens (ES256 60 min, rotated refresh 30 d sliding 90 d absolute, reuse detection), device key and proof on refresh, bind and batch, passwords (Argon2id), lockout without DoS, OTP model (server-created, TSO views, 4 digits default, encrypted reversible, TTL, attempts), re-verify on version switch, offline unlock verifier, web (BFF, MFA), first login at cutover (Apsis hashes) | L-security s2, REG G-man-021, C-fraud G-12/19 |
| 3 Authorization | scope resolution, ScopeContext and RLS, RBAC permission bundles, maker-checker, IDOR | L-security s3 |
| 4 PII and privacy | inventory, role x subject matrix (SR phone hidden from AMO; retailer phone visible to field roles; NID/TIN envelope encrypted), protection options and the decision, logs/telemetry redaction, list budgets and watermarked exports, legal position (unknown; confirm), consent, retention, breach runbooks | L-security s4, REG G-man-039/062, docs/22 P-12, C-fraud G-13 |
| 5 Transport, storage and platform | TLS and no pinning, secrets and keys, media/SAS path, trusted time anchor, per-record signatures, Keystore attestation, local DB encryption decision, mobile hardening | L-security s5/s9, C-fraud G-02/03/04 |
| 6 API hardening | rate limits per device/user, payload caps, replay rules, validation from /packages, injection (spreadsheet, ESC/POS, bidi), error envelope | L-security s6, C-fraud G-12/14 |
| 7 Anti-abuse | geo spoofing controls (mock invariant, radio environment, fix freshness/shape, co-location), outlet-location drift, fraud signal catalogue FS-01..FS-29 with thresholds and surfaces, SR-visible vs supervisor-visible flags, Exceptions screen, dismissal review | L-security s7, C-fraud s2/s4, docs/05 |
| 8 Audit logging | what is logged, immutability (REVOKE, trigger, hash chain, WORM export), PII-read log | L-security s8 |
| 9 Supply chain and CI security | repo, secrets, OIDC, SAST/deps/IaC, signing, reproducible APK, runner isolation | L-security s10, C-fraud G-16 |
| 10 Security test plan | gates by phase mapped to doc 20 ids (70..79 and 10..19) | L-security s12, C-fraud s5 |
| 11 Residual risks accepted | R-1..R-7 plus new | C-fraud s7 |
| 12 Gaps owned / Open items / Traceability | | |

## 2. Canonical decisions

Writers use these verbatim. A decision is changed only by the doc 14 author amending the row (never silently inside another document).

Status legend:

| Status | Meaning | What a writer does |
| --- | --- | --- |
| LOCKED-BY-SPEC | CLAUDE.md or docs/01 to 13 already decide it, or hard evidence (data profile, manuals) closes it | state it, do not reopen |
| DEFAULT | we proceed with this unless AKTCL objects; changing it is an amendment | build to it; list under "deliberately changed" if it differs from the current Apsis behaviour |
| MUST-CONFIRM (by N) | the default below lets the build proceed, but phase or sub-milestone N cannot exit (or a wave cannot start) until the business answers | write "unknown; confirm with the business", give the proceed-with default, name the blocked gate |

Columns: ID, decision (binding text), status, why/source, documents that apply it. Ranges: D-01 to D-14 platform; D-15 to D-43 data; D-44 to D-58 KPI; D-59 to D-86 sync, device, app; D-87 to D-100 config and admin; D-101 to D-124 security, privacy, fraud; D-125 to D-140 scale and Azure; D-141 to D-156 test, release, cutover; D-157 to D-207 the 51 manual-versus-spec rows C-01..C-51 (D = 156 + k); D-208 to D-244 the 37 inside-manual conflicts I-01..I-37 (D = 207 + k); D-245 to D-260 data-profile findings P-01..P-16 (D = 244 + k); D-261 to D-269 cross-lens conflict resolutions. D-300 to D-499 are reserved for decisions raised while writing (s5.3).

### 2.1 Platform, stack and process (D-01 to D-14)

| ID | Decision | Status | Why / source | Docs |
| --- | --- | --- | --- | --- |
| D-01 | Mobile app is Flutter (Dart), ONE codebase producing three Android flavours (SR, AMO, TSO) with distinct applicationIds and launcher labels "ARON SR", "ARON AMO", "ARON TSO"; role-aware UI resolved from the token; the applicationIds differ from the Apsis apps so both coexist on a pilot phone | DEFAULT | docs/13 Q1; C-49; three separate Apsis APKs today | 17, 20 |
| D-02 | API is Node.js (current LTS) + TypeScript + NestJS on Fastify; PostgreSQL 16; data access with Kysely (typed SQL); plain-SQL forward-only migrations with checksums (dbmate-style runner); no ORM-generated migrations | DEFAULT | docs/13 Q2; D-qa-07 | 16, 20 |
| D-03 | Web is Next.js + TypeScript + Tailwind reading aggregates only; scope derived on the server | LOCKED-BY-SPEC | CLAUDE.md stack and constraint 4 | 16, 19, 21 |
| D-04 | Hosting is Azure with GitHub Actions (OIDC, no stored cloud secrets) and Bicep IaC in /infra; API, worker, web and jobs run on Azure Container Apps (workload-profiles environment, zone-redundant), not App Service | LOCKED-BY-SPEC (Azure, Actions); DEFAULT (Container Apps) | docs/13 Q3; Board decision | 18, 20 |
| D-05 | Region: Azure Southeast Asia (3 AZs) primary, East Asia DR; no Azure region exists in Bangladesh; dev and staging use the same region | MUST-CONFIRM (by 7c; Phase 0 proceeds) | G-sec-01 data localisation unknown; Q19; RTO/RPO Q20 | 18, 21 |
| D-06 | SKU ladder: pilot GP D2ds_v5 no HA; wave 1 GP D4ds_v5 zone-redundant HA; full fleet GP D8ds_v5 zone-redundant HA; SSD v2 128/256/512 GiB; built-in PgBouncer (transaction mode, port 6432); PITR 7/35/35 days plus geo-redundant backup at full fleet; in-region read replica from wave 1; ACA api min 1/2/3 and max 3/10/30 replicas, worker scaled by KEDA on outbox depth; Front Door Standard until wave 1, Premium with Private Link origin at full fleet. List prices are unverified; regenerate with the Azure Pricing Calculator before budgeting | DEFAULT | L-scale s2.5, s3 | 18 |
| D-07 | Cache is Azure Managed Redis (Balanced, HA), not Azure Cache for Redis (retirement path). Required before wave 1 (bundle fast path, replay-cache copy, per-device rate limits, config version); Phases 1 to 3 may run without it | DEFAULT | L-scale s2.7 | 18 |
| D-08 | Maps (AMO Team Location and Update Base; TSO My Team and Retailer): Google Maps SDK (the current provider per the manuals), 2D only, lite mode, loaded only when its screen opens, no map on dashboards, key in Key Vault restricted by package name and signing SHA with a billing alert, bounded tile cache (cfg.map.tile_cache_mb); no map SDK in the SR flavour; MapLibre/OSM is the fallback if cost or the offline rule blocks Google; attendance address is online-only with coordinates as the offline fallback | MUST-CONFIRM (by 3a: provider, quota, cost; 3D not required) | V-outlets G-man-059; MQ-51; Q42 | 17, 18 |
| D-09 | Push: FCM data messages only for urgent config (kill switch, min_version, blocked versions, C3 reverts), payload carries pull_after_s jitter (120 s ordinary, 20 s urgent); cfg.ops.push_enabled is false in the pilot; no FCM-triggered uploads; if FCM is refused, urgent changes ride the next request | MUST-CONFIRM (by 2d) | Q22, Q28; G-sre-07 | 17, 19 |
| D-10 | App distribution is AKTCL's own signed APK channel (Blob behind Front Door, per-ABI split APKs, app_release registry with wave_pct, SHA-256 verified before install); a Play track is optional and a Play-distributed build cannot self-install | MUST-CONFIRM (by 2e) | MQ-50; G-man-022; D-qa-09 | 17, 20 |
| D-11 | Android floor: minSdk 26 (Android 8.0); baseline layout 360 x 640 dp; hardware back key handled; no gesture-only navigation | MUST-CONFIRM (by 1c exit; fleet census Q31) | G-qa-05; G-man-026; MQ-53 | 17, 20 |
| D-12 | Reference devices for every budget gate: primary Redmi 9A class (2 GB, Android 10/11), secondary Samsung A03 Core class, legacy Android 8.x with 2 GB; a Dhaka device lab with 2 of each and 2 printers (ASSUMPTION until the census) | MUST-CONFIRM (by 1c) | L-sync s5.1; D-qa-13 | 17, 20 |
| D-13 | Observability: Azure Monitor OpenTelemetry for api, worker and jobs; Sentry for Flutter and web (PII-scrubbed, device id and stack only); device operational telemetry rides inside the sync batch (no extra network); one request_id everywhere | MUST-CONFIRM (by 2e: Sentry residency) | D-qa-06; Q36 | 18, 20 |
| D-14 | CI/CD: GitHub Actions workflows pr, main, promote-prod (two approvers), contract-phase, nightly, release-app, device-lab, load-test, infra; blue/green by ACA traffic weights 10, 50, 100 with auto-abort; migrations expand/contract over three releases; staging soak 24 h (72 h for ingest, backfill or Drift-schema releases) | DEFAULT | L-quality s3 | 20 |

### 2.2 Data model, money, units, day state (D-15 to D-43)

| ID | Decision | Status | Why / source | Docs |
| --- | --- | --- | --- | --- |
| D-15 | Money is bigint milli-taka (column suffix _mtk, constant MONEY_SCALE = 1000); db/schema.sql _minor columns are renamed in migration 0001; the seed CSV loads as round(price x 1000); 23 of 42 seed prices have three decimals (distributor 7.935) so paisa cannot hold them; no floats anywhere | DEFAULT (changes CLAUDE.md "minor units" from paisa to milli-taka) | seed finding 1; V-money; REG s0.3 | 16 |
| D-16 | Quantity model on every quantity-bearing row: qty_entered + unit_entered + pack_factor + qty_base; base units: stick (cigarette, bidi), piece (lighter), dozen (match); report_unit and report_factor per SKU (Lighter: Pcs on web, Box on the TSO app; Match: Dozen); STD sums qty_base only inside one SKU or one category, never across categories | LOCKED-BY-SPEC for cigarette and bidi (sticks: docs/22 P-04 and manual stock badge 6,500 sticks = 650 packs); MUST-CONFIRM (by 2a) for lighter box size and match entry unit | V-money G-man-001; MQ-01 | 16, 17 |
| D-17 | Cigarette and bidi quantity is entered, stored and displayed in sticks; the stepper step is the pack size (loose sticks allowed or not is MUST-CONFIRM); the purple pack badge is read-only qty / pack_size; prices are per stick; Match price per dozen is stored as a rational (28.00 per dozen), never as 2.33 per piece; every Match quantity cell shows a unit label. Replaces the plan assumption "SRs enter packs" | DEFAULT; MUST-CONFIRM (by 2a): stepper and loose sticks (MQ-02) | V-money G-man-001 | 15, 16, 17 |
| D-18 | Memo net = gross - offer_discount - drp_discount (slide) - qc_deduction. Quantity totals are not reduced by slide or QC; the slide reward unit is a memo_line with is_free and offer_id so volume and stock include it (confirm). The CHECK is amended; both printed and on-screen lines show every non-zero component. Zero sale plus QC can make the net negative: cfg.memo.allow_negative_net default true (stored as a credit) | DEFAULT; MUST-CONFIRM (by 2a): negative-net rule and the max-QC basis (MQ-03, MQ-04) | V-money G-man-002 | 15, 16, 17 |
| D-19 | Rounding: totals are summed from unrounded line values and rounded once (AMO fixtures 116.42 and 289.50; SR fixtures 360.50 and 161.50); per-line display rounding is never stored; round_adj_mtk stored so the printed total is reproducible; cfg.memo.rounding_mode default half_up_paisa, future-dated only | MUST-CONFIRM (by 2a: must equal Apsis to the paisa; gate T-2-41) | V-money G-man-001; G-sync-04; G-feat-46 | 16, 17, 20 |
| D-20 | Every transaction stores UTC timestamps plus business_date (Asia/Dhaka, cutoff 00:00). Trusted capture time: monotonic anchor (server_time + elapsedRealtime + boot_id) is primary, the clock offset at last contact is the fallback, raw device time last; business_date and business_date_server derive from the trusted time; rows claiming a closed month on untrusted time are parked (DQ-40) | LOCKED-BY-SPEC (Dhaka business date); DEFAULT (anchor method, resolves G-fraud-02); MUST-CONFIRM (by 1a): 00:00 cutoff (Q29) | CLAUDE.md 7; D-sec-09; D-sync-13 | 16, 17, 21 |
| D-21 | Idempotency has three layers: row client_uuid (UUID v4 generated on device) in a global ingest_registry with payload hash, batch_uuid replay with the stored response (D-62), and content fingerprint for replay with regenerated uuids; children carry the parent's client_uuid; UUID v7 is NOT adopted (deviation would need confirmation, G-sre-26) | LOCKED-BY-SPEC | CLAUDE.md 2 | 16, 17 |
| D-22 | Synced transactional rows are immutable except server enrichment columns; every correction is a new row or event (supersedes, against, memo_void, due_collection, admin data_void); a voided client_uuid stays in ingest_registry as a tombstone so a late upload is rejected (voided_by_admin) and never resurrects a number | LOCKED-BY-SPEC | docs/03, docs/04; V-day G-man-086 | 16, 17 |
| D-23 | Monthly RANGE partitions by business_date on visit, memo, memo_line, geo_fix, attendance_event, due_collection, activity_log, sync_rejected and event-grain facts from Phase 1; partitions created 3 months ahead by a job; retention classes as cfg.retention.* (transactions hot 13 months then archive; geo_fix 6 months; photos Hot, Cool, Cold, Archive) | DEFAULT; MUST-CONFIRM (by 6c): statutory retention (7 years assumed) | L-data s5.1; G-data-32 | 16, 18 |
| D-24 | Postgres schemas: app (transactions and reference), cfg (config), dw (analytics), stg (migration staging). Migration number ranges are in s5.6 | DEFAULT | L-data s3.3 | 16 |
| D-25 | Outlets are never deleted: status active, closed, merged, archived; archived stubs hold history, dues and loyalty for the 175,031 outlets in the sales sample that are missing from the active list; outlet_code is text (never a number); location_confirmed flag; closing an outlet with open dues warns (cfg.outlet.close_block_if_dues = warn) | LOCKED-BY-SPEC (never delete); DEFAULT (rest) | docs/22 P-07, P-08; G-man-033 | 16, 15 |
| D-26 | Visit kinds: sr_call, amo_control_call, amo_joint_call, tso_visit, web_entry. Route-level KPIs (STD, memo count, geo %, CPR) include every active memo or visit on the route regardless of seller; AMO control-call sales are counted separately (amo_successful_calls) and never inflate the SR strike rate; user-level KPIs (SR Efficiency, GIGO, risk score) use coalesce(acting_for_user_id, user_id) | DEFAULT | G-field-16; G-analyst-03; F-AMO-036 | 16 |
| D-27 | Day-state ownership: route_day (route x business_date) owns not_started, logged_in, in_field, synced, sales_submitted; the user owns attendance; the zone owns final_submit and it is projected onto every route_day of the zone; AMO and TSO have supervisor_day (user x date), separate from route_day; an SR on two routes has two route_day rows flipped by one bundle | LOCKED-BY-SPEC (resolves seed finding 10) | D-sync-02; V-day G-man-032 | 16, 17 |
| D-28 | Working-day calendar is data, not code: cfg.calendar.weekend_days default Friday, cfg.calendar.holidays (date, scope, selling_day), make-up days, route-day overrides; planned(route, d) = visit_days matches the weekday AND the date is a working day, XOR any override; on a non-working day target_outlets = 0 and every percentage is NULL shown as a dash, never 0 | DEFAULT; MUST-CONFIRM (by 4a): whether Saturday is a selling day (Q27) | docs/22 P-03; G-feat-09; G-analyst-10 | 16, 19 |
| D-29 | Route kind: sr or amo; target routes for Login %, Submit % and Final Submit counts are the planned sr-kind routes (cfg.kpi.target_route_kinds = [sr]); AMO-kind routes are excluded; "SR Not Set" is a normal state of an AMO route; list rows are (assigned user, planned route) pairs, so an AMO assigned to an sr-kind route appears; route name and visit-day label are stored separately and never keyed on name | DEFAULT; MUST-CONFIRM (by 3b): whether AMO routes count (MQ-38) | V-web G-man-100; V-day | 16 |
| D-30 | Login event = the first bundle_download for that route's user whose request falls on the Dhaka business date D (full or delta; a pre-fetch of D+1 on the evening of D does not count) OR a day_open event captured offline on a stale bundle (flagged offline_start); later deltas never change logged_in_at | LOCKED-BY-SPEC (seed finding 9 resolved) | D-08; G-sync-07; G-analyst-16 | 16, 17 |
| D-31 | Targets: product_level gains variant (SKU rolls up to variant, brand, category); every monthly target SET is a header (target_set: name, territory, product_type variant, target_type stt, start_date, end_date, status, source, submitted_by) with approval events; default approval is one level, role WMO (configurable list; the level order is not evidenced); entry is manual route x variant or Excel upload, no automatic split (cfg.target.split_method manual); target >= 0 at entry; zero target displays a dash | DEFAULT; MUST-CONFIRM (by 5c): approver chain, whether the live target stays while a new set is pending (MQ-39, Q12) | V-targets G-man-068; G-man-090 | 16, 19 |
| D-32 | Price type is an outlet attribute resolved on the server and delivered in the bundle (retail outlets use outlet; wholesale outlets use cc); the SR never chooses; the bundle carries both lists; DQ-13 compares against the outlet's resolved list | DEFAULT; MUST-CONFIRM (by 2a): which outlets are priced cc or distributor today (Q46) | G-field-08; docs/22 P-16 | 16, 15 |
| D-33 | Offer/promotion engine and DRP discount move to sub-milestone 2a (pilot prerequisite). Offer master: bn and en text, valid_from/valid_to, qualifying brand or SKU set, ratio, reward SKU, scope; auto-applied discounts (offer_discount) and the DRP/slide deduction are separate memo components; fixture: 10 empty MaxR-10S packets give 1 reward pack (10 sticks x 8.00 = 80.00) shown as a deduction. Validity window behaviour and whether the footer cart total is net of slide are unknown | DEFAULT; MUST-CONFIRM (by 2a entry): the live promotion catalogue (about 22 groups) and rules (Q13, MQ-05, MQ-06) | V-money G-man-004; D-qa-14; G-qa-02 | 15, 16, 20 |
| D-34 | QC: qc_fault_type code table (union of 6 app and 10 web labels with an overlap map; 11 codes; stable group codes MFC and MKT; applies_to app/web), qc_entry_line (client_uuid, fault_type_code, qty_sticks); settlement_mtk = defect sticks x price at capture (inferred from one example 10 x 8.00 = 80.00); max_qc is a taka cap per SKU with done and remaining shown (basis unknown, 594.50 is not 10 x 8.00); expired-stock threshold 4 months (cfg.qc.expired_stock_months); web Market and Warehouse QC entries are a separate source column, never added to app QC | DEFAULT; MUST-CONFIRM (by 2a): settlement formula and cap basis (MQ-03) | V-money G-man-003, G-man-089 | 15, 16 |
| D-35 | Memo number: option A, a new series printed as <username>-<yyMMdd>-<seq3> composed on the device in the same transaction as the memo insert, with disjoint 500-number blocks per device-bind ordinal; server keeps it verbatim (UNIQUE per business_date); memo_serial is an optional server column so Apsis-series continuity can be switched on later; imported Apsis memos keep their original number | DEFAULT; MUST-CONFIRM (by 1a, before the first golden print): retailer expectation (Q5, MQ-56) | docs/13 Q5; D-sync-07 | 16, 17 |
| D-36 | Zero sale creates a memo row with line_count = 0 (a printable zero record; the retailer may receive it); it consumes a memo number; it counts as a visit and as a no-sale, is excluded from memo count and successful calls; the zero-sale confirm dialog asks one outcome reason (D-38) | DEFAULT | V-money C-46; G-feat-38 | 15, 16 |
| D-37 | Dues: mark-as-paid settles the WHOLE memo (PARITY); every action still writes a due_collection row (client_uuid, against_memo_id, amount = remaining); partial later collection is an off-by-default enhancement (cfg.credit.allow_partial_collection); ageing allocation is FIFO for reporting regardless of the retailer's choice; an AMO's due label and dues count cover the AMO's own credit memos only; due receipt print and stale-balance marker added (IMPROVEMENT) | DEFAULT; MUST-CONFIRM (by 3a): whose dues an AMO sees (MQ-17) | V-money G-man-010; G-man-011; G-field-09 | 15, 16 |
| D-38 | Every visit has an outcome code (sold, zero_sale_stock_ok, closed, owner_absent, refused, competitor_exclusive, not_reached, abandoned); a skip record (no fix, no geo gate) marks an outlet not reached; abandoned visits are excluded from visited and CPR (cfg.kpi.count_abandoned_visits false); whether the current app records a closed-shop outcome is unknown | DEFAULT (IMPROVEMENT); MUST-CONFIRM (by 2a): parity or addition (Q50) | G-field-03; G-sync-08 | 15, 16, 17 |
| D-39 | Day exceptions (rain, hartal, market closed, DH out of stock, breakdown, sick) are an offline-capable event (day_exception) raised by the SR for the own route or by the AMO for the zone, approved by the TSO in app (cfg.day.exception_requires_approval true); an approved exception removes the route from Login %, Submit % and Daily Tracking denominators and labels it "exception" | DEFAULT (IMPROVEMENT) | G-field-02 | 15, 16, 19 |
| D-40 | Web back-office data: Web Entry (route-day aggregates), Astha Web Entry (outlet x SKU), web QC entries and web Final Submit are separate sources with their own tables; web rows and app rows for the same route-day are mutually exclusive by default (flagged if both exist, never added); each submission carries a client uuid; "Delete Section Data" is an audited void (reason, scope, tombstones) allowed only before Final Submit, default scope web-entry rows; back-date cut-off cfg.web.entry_backdate_days (0) with per-zone date and audited unlock grants | DEFAULT; MUST-CONFIRM (by 4c): what Delete and Status "exist" remove, whether Final Submit locks edits (MQ-43, Q11), how often Web Entry is used (MQ-46) | V-day G-man-086/087; V-web G-man-085 | 16, 19 |
| D-41 | Loyalty and programmes: ledger rows carry source_type and source_id so a replay never double-credits; points are computed on the server, never accepted from the client; expiry is a nightly ledger row (cfg.loyalty.expiry_days; April league expires 2026-05-07, rule unknown); a redemption is a batch (client_uuid) of lines; seed earning rule: POSM survey Q1.1 photo gives +50 points (not for an AMO survey); full earning rules, catalogue and cap scope are required before Phase 5 | DEFAULT; MUST-CONFIRM (by 5a): earning rules, cash-cap scope, catalogue (Q13, MQ-20..22) | V-targets G-man-040/042; G-feat-20 | 15, 16 |
| D-42 | Wholesale/C&C: outlet_kind (retail, wholesale) separate from the channel enum, marked in bulk by a TSO (idempotent batchUuid, audit per outlet, unmark behind cfg.outlet.wholesale_unmark_allowed); effect on price type (D-32), target-outlet counting and geo gate is defined before building; quantity validation is a soft ceiling plus anomaly flag, never a hard cap (26,683 lines are 10,000 sticks or more) | DEFAULT; MUST-CONFIRM (by 6a): effects, bex/back-margin screens out of scope unless Q17 says otherwise | docs/22 P-16; G-man-036; Q17 | 15, 16, 19 |
| D-43 | Outlet request lifecycle: pending, verified (AMO app Save or web Verify), approved or rejected (web only); the three types (new, close, info) plus base_update/location correction share one queue; requests store both route_id and cluster_id; SR and AMO forms pick a Cluster; GEO and photo are required for new shop and info change; approving a closure sets status closed; approving a new outlet assigns the code; rejected state is shown to the SR (IMPROVEMENT) | DEFAULT; MUST-CONFIRM (by 3a): whether AMO may reject and what 'বাতিল' does (MQ-29), route of AMO-created outlets (MQ-31) | V-outlets G-man-033/034 | 15, 16 |

### 2.3 KPI definitions (D-44 to D-58)

| ID | Decision | Status | Why / source | Docs |
| --- | --- | --- | --- | --- |
| D-44 | Login % = logged_in_routes / target_routes (target routes per D-29, planned on a working day); verified 1/4 = 25% on TSO and web | LOCKED-BY-SPEC | V-day G-man-066 | 16, 15 |
| D-45 | Two Submit % KPIs with distinct names: "Submit % (of logged-in)" code submit_pct_of_logged_in = sales_submitted_routes / logged_in_routes (the figure shown under TSO "Bikroy Joma Status", AMO live tile and web "Login/Submit Status": PARITY); "Day-completion %" code day_completion_pct = sales_submitted_routes / target_routes (the docs/10 web definition, kept as the secondary figure on Daily Tracking and sync-health). cfg.kpi.submit_pct_denominator selects which one the label "Submit %" shows on a tile (default logged_in_routes) and the API always returns both with their basis; the web basis is unproven until a day with real submits | DEFAULT; MUST-CONFIRM (by 3b): web basis (MQ-34) | V-day G-man-066; seed finding 7 | 16, 15 |
| D-46 | CPR = strike rate = successful_calls / target_outlets. Successful call = a visit (kind sr_call) with at least one active memo having net_mtk > 0 or qty_base > 0 (free-sample-only counts); zero-sale visits and abandoned visits are not successful; target_outlets per D-57 | DEFAULT; MUST-CONFIRM (by 4a): whether a free-sample-only memo counts, whether an all-zero outlet-day is a zero-sale call (docs/22 P-05) | UI-SR-09 (6/60 = 10%); L-data D-05 | 16 |
| D-47 | BSR primary = memos containing the brand / total active memos; secondary named "brand reach" = outlets that bought the brand / target outlets; both stored; the leaderboard shows the primary | DEFAULT; MUST-CONFIRM (by 4b): denominator (Q9) | docs/13 Q9; L-data s4.5 | 16 |
| D-48 | Geo-validation % = server_geo_valid calls / (sr_call visits - abandoned); force sales are photo-valid and excluded from the numerator; a mocked fix is never geo-valid (invariant); also expose photo_valid %, mock %, suspicious %, geo_mismatch % | LOCKED-BY-SPEC | docs/05; L-data s4.5 | 16, 21 |
| D-49 | STD (API STT) = sum of qty_base in the SKU's base unit; shown in report_unit where it differs (Lighter: Pcs web, Box TSO app; Match: Dozen); there is no cross-category "total STD": cross-category totals are shown in value (net_mtk); zero-volume lines are not sales | LOCKED-BY-SPEC | docs/22 P-04/P-05; V-money | 16, 15 |
| D-50 | Achievement display: summary cards and bars on AMO and TSO cap the printed percent at 100; detail tables are uncapped with 2 decimals (1271.19% prints as is); the SR home card is uncapped; remaining = max(target - achievement, 0); target 0 or less shows a dash (DELIBERATE CHANGE from observed 0%); targets are >= 0 at entry and a negative one is flagged at read; no global 1000 cap on detail tables; tapping a category bar opens that category only (AMO) | LOCKED-BY-SPEC (PARITY); DELIBERATE CHANGE for the dash | V-targets G-man-060 | 15, 16 |
| D-51 | Till-date target basis is a per-surface key, not one default: TSO Target Status = calendar days elapsed / days in month with item targets rounded up then summed (verified 26/30); AMO Team Performance = calendar pro-rata (about 25/30, rounding unknown); AMO Sales Summary Up To Now = monthly target x 15/17 (route-scheduled days, meaning of 17 and 15 unknown); SR ADS screen = working-day split (14 elapsed, 11 remaining). Keys cfg.kpi.tilldate_basis.<surface> and cfg.kpi.tilldate_rounding.<surface> | DEFAULT; MUST-CONFIRM (by 3a): meaning of 17 and 15 and the TSO capture date (MQ-09, MQ-33) | V-targets G-man-067/064 | 16, 15, 19 |
| D-52 | Colour bands are two different things: the 4-band achievement set (>=100, 90-100, 80-90, <80) for KPI tables (cfg.kpi.bands) and the observed bar colours on AMO and TSO cards (cfg.kpi.bar_bands): green from 80, amber from 40, red below 40 (green observed at 84 and 89 percent; amber at 56 to 70; red at 23 and below; the 40 boundary is a placeholder) | DEFAULT; MUST-CONFIRM (by 3a): one capture between 25 and 55 percent | V-targets G-man-060 | 15, 19 |
| D-53 | Memo count = active memos with line_count > 0 (superseded, void and zero memos excluded; the superseding memo counts once); value KPIs use net_mtk; Memo and STD for AMO control calls follow D-26 | LOCKED-BY-SPEC | L-data D-06 | 16 |
| D-54 | Retention (docs/10 total_retention_*) is stored as retention_candidate_pct (outlets that bought the category in both M-1 and M / outlets that bought in M-1) under a non-committal name until Q10 is answered; never labelled "retention" on a tile until confirmed | MUST-CONFIRM (by 4b) | docs/13 Q10 | 16 |
| D-55 | Final-submit status = zones final-submitted / zones having target routes, plus the list of remaining zones; once per zone per day by primary key; late batches for a closed zone-day are accepted, aggregated into their business date and flagged after_final_submit; reopening is an audited admin action | DEFAULT; MUST-CONFIRM (by 3b): who may reopen, what may change (Q11) | docs/13 Q11; G-feat-17 | 15, 16 |
| D-56 | SR home strip definitions: Outlets visited x/y (visited counts opened visits, not abandoned), Strike rate per D-46, Issue = stock received today in each SKU's own unit (shown per category), Current stock = opening + issues - sold - returned computed locally, Non-visit = target outlets - visited, No-sale = zero-sale calls; the Issue and Current stock tiles show a per-category split (UI-SR-10: bare totals mix units) | DEFAULT | UI-SR-09/10; G-feat-38 | 15, 16 |
| D-57 | Target outlets (day) = active outlets on routes planned that day per D-28, plus any outlet actually visited (shown as unplanned_visits so CPR cannot exceed 100% for an unplanned visit); the route plan for the day is fixed at bundle time | LOCKED-BY-SPEC | L-data D-09; UI-SR-09 | 16 |
| D-58 | SR SKU Target and Achievement screen: columns Target, Achieved, Remaining, Achievement %, ADS, TADS, PADS, RADS per brand or variant row (title keeps "SKU List"); parity fixtures: TADS = target / 14 and RADS = remaining / 11 rounded (500 gives 36 and 45; 900 gives 64 and 82; the register's 68 is a misread); ADS = achieved / elapsed selling days, PADS undefined; percentage not capped; computed locally from bundle target plus local sales | DEFAULT; MUST-CONFIRM (by 2a): ADS, TADS, PADS definitions with a non-zero sample (MQ-27) | V-targets G-man-053 | 15, 16 |

### 2.4 Sync, offline, battery, device and app (D-59 to D-86)

| ID | Decision | Status | Why / source | Docs |
| --- | --- | --- | --- | --- |
| D-59 | Immediate sync (R5) is event-driven, never polled: T1 debounce 5 s after the last local write with a family hold (a visit and its children leave together; hold max 180 s); T2 app foreground; T3 validated connectivity regained (HEAD check, captive Wi-Fi counts as offline); T4 WorkManager one-off with NetworkType.CONNECTED enqueued whenever a send failed for lack of network; T5 WorkManager periodic 15 min (registered only while rows or photos are pending); T6 manual Sync; T7 Sales Submit; T8 Wi-Fi connected for the media queue. No foreground service, no socket, no AlarmManager, no timer shorter than 60 s | LOCKED-BY-SPEC (R5, R4) | L-sync s2; D-sync-10 | 17 |
| D-60 | POST /sync/batch carries one ordered flat records[] list (type, family_uuid, rank, client_uuid, payload) with envelope fields batch_uuid, device_uuid, schema_version, device counts per type and date; the response carries accepted, rejected (reason_code, retryable), conflicts, parked, server_totals, day_states, hold_s, X-Config-Version, X-Server-Generation; requests carry X-Batch-Attempt, X-Pending-Rows and X-Last-Sync-Error. This replaces the per-type arrays in docs/09 (the new system defines its own API) | DEFAULT | L-sync s2.4; D-sync-09; C-sre G-18/19 | 17, 15 |
| D-61 | Ingest is synchronous into PostgreSQL and returns authoritative accepted counts (no queue in front of the database); everything after commit (aggregation, geo re-check, plausibility, photo linking) runs from a transactional outbox worker, idempotent per dirty (business_date, route) key; aggregates are recomputed, never incremented | DEFAULT | D-scale-1/2 | 17, 18 |
| D-62 | Batch-level idempotency: device-generated batch_uuid; the response is persisted in the ingest transaction (table sync_batch.response) and Redis is only a cache of it; a repeat within 24 h replays the stored response; replay is keyed by (device_id, batch_uuid) so another device never receives it; the same batch_uuid with a different row set returns 409 | LOCKED-BY-SPEC (CLAUDE.md 2) | D-scale-4; G-scale-01; G-sre-14 | 17, 18 |
| D-63 | Server generation: every response carries X-Server-Generation (minted at DR promotion or PITR restore); when it changes the device flips outbox rows acked within cfg.sync.resync_window_h (24) back to pending and re-sends; registry dedupe absorbs it. This is what makes DR and PITR usable | DEFAULT; MUST-CONFIRM (by 7c): business tolerance for counts rising again after failover (Q54) | D-sre-01; G-sre-01 | 17, 18 |
| D-64 | Sales Submit, check-out and day_open are outbox events (they complete offline); day_submit is always the LAST record of the day's outbox sequence; the server defers the sales_submitted transition until its totals are at least the device counts claimed in the event (cfg.day.submit_settle_timeout_min 30) and shows submit_pending_rows meanwhile; submit_count_mismatch is evaluated only after settle; the rollback trigger "mismatch above 2% of routes" reads it after settle | DEFAULT | D-sync-08; D-sre-02; G-sre-02 | 17, 18, 16 |
| D-65 | Poison-row isolation: ingest uses per-record savepoints; a record that throws becomes rejected(server_error) plus a quarantine row, never a 500; the client counts a family_fail_count and skips ahead after cfg.sync.family_skip_after (5) so the rest of the day uploads; sync_rejected has admin retry, fix-and-accept, discard | DEFAULT | D-sre-03; G-sre-03 | 17, 16 |
| D-66 | Shared phones: one SQLite file per user plus one device file; user A's pending rows upload under A's own stored refresh token while B works; cfg.auth.max_users_per_device 3 and max_devices_per_user 2; first capture of a business date on a device with more than one bound user asks "Are you <name>?" and records acting_for_user_id when the route's assignee differs; re-attribution is an aggregation-time event, never an UPDATE | DEFAULT; MUST-CONFIRM (by 1c): the binding model (G-sync-01, G-sec-02) | D-sync-01; D-field-02 | 17, 21 |
| D-67 | Local database is encrypted with SQLCipher using a per-user 256-bit key wrapped by the Android Keystore (not password-derived, so the engine can flush another user's rows); if the APK exceeds 30 MB per ABI or the battery gate fails, fall back to Android file-based encryption only and record a new decision | DEFAULT; MUST-CONFIRM (by 1b): security owner sign-off (G-sync-13) | L-security s9 vs L-sync D-sync-01 | 17, 21 |
| D-68 | Offline unlock is an Argon2id verifier (m=19 MiB, t=2, p=1) written at each online password login, valid while the refresh token is unexpired and the last online authentication is within cfg.auth.offline_unlock_max_days (default 7, range 1 to 14), 10 failed attempts with doubling cool-down; not PBKDF2 | DEFAULT | G-sec-07; fraud X5 | 17, 21 |
| D-69 | Logout: SR and AMO end the session, keep the local database and the engine keeps uploading; TSO "Log Out" wipes all local data ONLY on a fully reconciled device (no row with sync_state other than synced, empty media queue); otherwise it is refused with "N items not yet sent" plus Sync now and Cancel (PDA to Support also clears the guard); cfg.app.logout_block_when_pending true; dialog wording in a bn/en pair with the grammar fixed | LOCKED-BY-SPEC (CLAUDE.md 1 and 2 beat docs/08) | V-device G-man-024; D-sync-03 | 17, 15 |
| D-70 | Stale bundle: with no signal at day open the app sells on a cached bundle up to cfg.bundle.stale_max_days (2) old with a banner, flags rows bundle_stale, appends a day_open event so Login % stays truthful; older than that it shows memos and dues read-only until online (check-in still allowed) | DEFAULT; MUST-CONFIRM (by 2e): business accepts selling on yesterday's prices (G-sync-03) | L-sync s4.5; D-sync-12 | 17 |
| D-71 | Nightly job chain with preconditions and deadlines: wave nights delta import reconciled by 23:00 or the wave is deferred; 00:05 create route_day for D (planned routes) so denominators exist from midnight; 01:00 to 02:00 maintenance window; 02:15 re-aggregation; 22:00 generate D+1 bundle snapshots for Wi-Fi pre-fetch; 03:30 refresh snapshots for users dirtied since; 04:30 coverage check (at least 99%, valid_for_business_date equals D, else bundle_hold serves yesterday's snapshot plus delta and pages L2) | DEFAULT | D-sre-06; G-sre-08/09 | 18, 17 |
| D-72 | Bundles per role: SR = all assigned routes for the date; AMO = zone-wide outlet list for every route of the zone (3,500 to 11,000 outlets for a 54-route zone), pending verification requests, SR list, tasks, paged by section above 2,000 rows with a 2 MB gzip hard cap; TSO = login snapshot (GET /app/home) plus territory pickers (about 2,500 outlets, under 1 MB gzip) delta-refreshed; every aggregate screen shows "as of hh:mm" | DEFAULT | V (G-man-065, 069); docs/22 P-13 | 17, 18 |
| D-73 | Budgets are release gates (measured per RC on the primary device, recorded in /docs/perf/battery-<version>.md): app non-screen drain at most 6 percent of a 5,000 mAh battery over the scripted 8-hour day; background drain at most 1 percent per 8 h with pending rows and no network; GPS at most 80 fixes and 15 min per day; wake locks at most 10 min per day and 90 s each; mobile data at most 1 MB per day without photos and 3 MB with 10 photos; APK at most 30 MB per ABI (target 22); installed at most 70 MB; cold start at most 2.5 s; foreground services 0; alarms 0 | LOCKED-BY-SPEC (docs/04 hard rules, numbers are ours) | L-sync s5 | 17, 20 |
| D-74 | Location: one fused balanced-power fix per event (outlet open, attendance in and out, force sale, outlet capture); acquisition starts on entering the outlet list and a fix up to 60 s old and 30 m away may be reused; never getLastKnownLocation for a verdict; no position stream, no background location; Precise location required (cfg.geo.require_precise); denied location blocks Sale and Attendance with a Bangla rationale and a Settings deep link; Bluetooth denied only disables printing | LOCKED-BY-SPEC (CLAUDE.md 3) | V-device G-man-020; G-field-13 | 17, 21 |
| D-75 | Media: capture camera-only for evidence photos (force sale, outlet capture, base update); compress on device to at most 150 KB with long edge 1024 px and JPEG quality 70 (down to 40, then 800 px); EXIF stripped; SHA-256 and perceptual hash stored; queue with Wi-Fi-only default and evidence fallback to mobile after cfg.media.evidence_mobile_fallback_h (6); direct upload by user-delegation SAS pinned to photos/{business_date}/{device_uuid}/{client_uuid}.jpg, write-only, 15 min; the record syncs first, the photo follows; photo failure never blocks a sale | LOCKED-BY-SPEC (docs/04); DEFAULT (numbers) | L-sync s5.5; L-scale s2.6 | 17, 21 |
| D-76 | Printing: RPP02N-class 58 mm ESC/POS over Bluetooth Classic SPP behind a PrinterPort interface; Bangla rendered as raster from the bundled Noto Sans Bengali; template is data (cfg.print.template_version) with 7 memo kinds (cash, credit with partial payment, with offer, DRP/slide, zero sale, edited with "supersedes", stock memo) plus day summary, each with a golden print test; printer icon on Stock, Review, Memo, Summary (red slashed = not connected); auto-reconnect; Print enabled only when connected AND saved; "ছাপা ঠিক আছে?" confirmation after print | DEFAULT; MUST-CONFIRM (by 1a): physical 58 mm samples (Q57, MQ-57) | L-sync s6; V-money G-man-005/014 | 17, 20 |
| D-77 | Commit semantics: the draft memo and visit are written to the local database when the SR taps "এগিয়ে যান" so kill-and-relaunch resumes at Review; tapping Print shows "আপনি কি নিশ্চিত? বিক্রয় জমা হবে"; yes commits the immutable record (sync_state pending) before and regardless of printing; the second dialog asks whether to print, no leaves printed_at null (reprint from Memo); QC and Print are independent buttons in any order; cfg.sale.require_printer_before_sale false | LOCKED-BY-SPEC (PARITY) | V-money G-man-006; C-47 | 17, 15 |
| D-78 | Call start: after the geo gate the explicit prompt "আপনি কি কল শুরু করতে চান?" appears (cfg.sale.call_start_prompt true); the visit row is created at outlet open with opened_at, call_started_at is separate, "না" returns to the list and is not counted as visited; AV, KV, survey and sale begin only after yes | LOCKED-BY-SPEC (PARITY) | V-money G-man-008 | 17, 15 |
| D-79 | App update is two stages of one flow: online check, Bangla "Install unknown apps" guidance with a canRequestPackageInstalls check, resumable download with percent and SHA-256, OS install, then an on-device migration with determinate n/m progress that preserves pending rows; cfg.release.min_version blocks a NEW DAY's login only (an open offline day finishes) and never capture or upload; blocked_versions stops new captures only; downgrade is unsupported, rollback is a roll-forward rescue release within 24 h, the local schema is additive and N-1 can open it for one release; PDA to Support export before any reinstall | DEFAULT; the forced gate is a design addition (not observed) | V-device G-man-022; D-sre-12 | 17, 20 |
| D-80 | Device-binding re-verify: the OTP is required for an unknown device; asking again after an in-place update is OFF by default (cfg.auth.reverify_on_new_version false) because it needs about 8,500 TSO lookups per release and breaks an offline day; parity (true) stays available for the pilot | DEFAULT (DELIBERATE CHANGE); MUST-CONFIRM (by 2e) with MQ-15 | V-device G-man-021 | 21, 17 |
| D-81 | Edge responses: a response is an API response only if content-type is application/json and the envelope field success is present; anything else (WAF HTML 403, 413 at the edge, 502 to 504) is a transport failure (backoff, same batch_uuid), never a terminal state; managed WAF rules run in Log mode on /sync and /media for one real wave and body inspection is excluded for those paths; two hostnames are baked in (custom domain, then the Front Door default endpoint after cfg.net.fallback_after_failures 3) | DEFAULT | D-sre-07; G-sre-10/12 | 17, 18 |
| D-82 | TSO app is a read-snapshot plus queued-writes client: Leave, Set Plan, Visit Query, Assign Task, Feedback are queued with a client uuid and a sync-state badge; Final Submit and maps are online-only ("needs internet" when offline); GET /day/final-submit/preview (scope-checked: salesDate, alreadySubmitted, submittedAt, routes with FF name or null) feeds the alert at "Get Sales Data" | DEFAULT | V-day G-man-070; G-man-069 | 17, 15 |
| D-83 | Local history window cfg.app.local_history_days = 7 (Sale History and reprints) with an online fallback GET /memos?outlet=&date= for older dates and an offline banner; purge is by business_date age, never of non-synced or rejected rows, never "on final submit" | DEFAULT | G-feat-65; G-sync-17; V-targets G-man-013 | 17 |
| D-84 | No notification mechanism that polls: badges (tasks, pending requests) come from the bundle delta and sync responses; FCM only under D-09 | DEFAULT | G-feat-35 | 17 |
| D-85 | Visit and capture identity: visits and memos carry acting_for_user_id; an AMO selling for a dead-phone SR and an SS covering are recorded as the acting user with the route's assignee referenced; same-day cover is created by an AMO action "Assign cover for today" (route, user in zone, dates up to 7 days) which bumps scope_version so the substitute's next delta carries the route | DEFAULT (IMPROVEMENT); MUST-CONFIRM (by 3a): who may assign cover (Q44) | G-field-05/06 | 15, 17, 21 |
| D-86 | Memo void after print is an event (memo_void: reason, fix, retailer_ack) allowed under the edit rules (same business date, inside the geofence, before QC at that outlet and before Sales Submit, chain depth at most 3); effects: memo status void, due reversal in the due ledger, stock back; a printable cancel slip; whether Apsis allows cancelling at all is unknown | DEFAULT (IMPROVEMENT); MUST-CONFIRM (by 2b): current behaviour and cancel paper (Q43) | G-field-04; G-fraud-17 | 15, 16, 17 |

### 2.5 Config and admin (D-87 to D-100)

| ID | Decision | Status | Why / source | Docs |
| --- | --- | --- | --- | --- |
| D-87 | All operating parameters are rows in cfg.config_value (scoped, effective-dated, audited); resolution precedence most specific wins: outlet, route, zone, geo_class, house, territory, division, wing, wave, role, global; a geography beats a role; territory_geo_config becomes a compatibility view; every captured row stores config_version and the resolved values it used (radius_m_used) and the server re-checks as of capture time | LOCKED-BY-SPEC (R6) | D-cfg-1/4; G-field-11 adds geo_class | 19, 16 |
| D-88 | Four risk classes C0 to C3 (34 keys C3): C0 content; C1 operational single editor with reason; C2 sensitive 10-minute delayed apply with cancel; C3 critical two-person approval, mandatory canary at scope above territory, anomaly watch armed; class may be raised from the GUI, never lowered; adding or removing a key is a migration, changing a value is config | DEFAULT | D-cfg-5/7 | 19 |
| D-89 | Propagation: X-Config-Version on every response, delta pull GET /config/delta on the next natural request, ack carried on the next sync or bundle request, scheduled values stored on the device for offline application (cfg.sys.schedule_horizon_days), restrictive fallback when the clock is suspect; kill switches never block upload or wipe data; unauthenticated GET /config/public (Front Door cached 60 s) serves min_version, banner and helpdesk number | LOCKED-BY-SPEC (R5, R6) | L-config s3; D-scale-9 | 19, 17 |
| D-90 | A minimal admin console ships in Phase 1c (radius at global and territory, check-out time, min_version) with bounds, reason, audit and a reach widget so the sponsor sees the radius change reach a phone; the full console follows 2d and 6b | DEFAULT | D-cfg-8 | 19 |
| D-91 | Admin authority is a permission table bounded by geography, not one admin role: bundles master_data, security_admin, config_editor, config_approver, finance_admin, finance_approver, release_mgr, importer, pii_officer, ops_admin, plus roles sso as needed; maker-checker for critical config and finance adjustments; one break-glass account with an audited flow | DEFAULT; MUST-CONFIRM (by 2d): the approver roster and on-call break-glass holder (Q23) | D-sec-05; G-cfg-01 | 19, 21 |
| D-92 | Config-key names follow s5.7 of this file (unified register names win over per-manual and per-lens aliases); where the manual behaviour differs from the recommended one the registry holds both a parity default and a recommended value | LOCKED-BY-SPEC | REG s0.3; L-config s1.0 | 19 |
| D-93 | Geofence radius: global default 100 m, bounds 20 to 2000 (critical keys radius_min_m and radius_max_m), overrides at wing, division, territory, geo_class, zone and outlet; an outlet override above 3 x the resolved zone value needs an approver; no radius is calibrated until at least two weeks of fixes per geo class exist (pilot), so the first wave radii come from the calibration report | DEFAULT; MUST-CONFIRM (by 7c): radius values per territory (Q7) | docs/05; G-field-11; docs/22 P-10 | 19 |
| D-94 | TSO radius edit mode defaults to propose (a change request) rather than direct edit within bounds (cfg.geo.tso_radius_mode); any increase above cfg.geo.radius_increase_escalation_m (150) is C3; the anomaly watch is two-sided (a rise in geo-valid % after a loosening raises the alert) | DEFAULT; MUST-CONFIRM (by 2d): TSO authority (Q24) | G-cfg-03; G-fraud-09 | 19, 21 |
| D-95 | No-location policy default force_sale_required (an outlet without coordinates opens as a force sale with reason no_outlet_location); the first capture sets the location immediately only when the stored location is missing or a placeholder (location_confirmed false), otherwise a location-change request is raised and the device uses a provisional location flagged until approved; Update Base needs the AMO within cfg.geo.update_base_max_distance_m (100) of the chosen point, a move limit and no mocked fix | DEFAULT (DELIBERATE CHANGE from the observed immediate overwrite); MUST-CONFIRM (by 2d): Q25 | V-outlets G-man-017/018; docs/22 P-09 | 19, 15, 21 |
| D-96 | cfg.geo.mock_policy has one enum: silent_flag, warn_rep, block_sale, default warn_rep; "a mocked fix is never geo-valid" is an invariant independent of the policy; hard block of selling stays off by default; of all fraud flags only the mock warning is visible to the SR | LOCKED-BY-SPEC (docs/05 "report, do not just block") | G-fraud-18; D-fraud-06 | 19, 21 |
| D-97 | Back-date windows are separate keys: app sync cfg.sync.max_backdate_days 7 (server), web entry cfg.web.entry_backdate_days 0 with a per-zone data_entry_date and audited unlock grants (entry_unlock_grant with reason and expiry); the Sales Plan "Data Entry Date" may be the lever (unconfirmed) | DEFAULT; MUST-CONFIRM (by 4c): how the web cut-off is produced (MQ-44) | V-day G-man-087 | 19, 16 |
| D-98 | Master data with KPI effect is future-dated only: prices, targets after the month starts, calendar changes for past dates; back-dating is a finance_approver maker-checker action with a blast-radius preview of restated rows; a trigger blocks UPDATE and DELETE of synced transactional rows | DEFAULT | D-fraud-08; G-fraud-11 | 19, 16 |
| D-99 | Feature flags are cfg.flag.* keys (release, ops, wave kinds); release flags are removed within two releases of 100 percent; flags never change the shape of captured data (that is a schema_version bump) | DEFAULT | D-qa-08 | 19, 20 |
| D-100 | Change-freeze windows cfg.sys.change_freeze_windows (07:00 to 09:30 and 16:30 to 19:30 Dhaka) block C2 and C3 config changes and bulk master-data operations (break-glass excepted, reviewed); at most 5 applied C3 versions per hour fleet-wide; scope_version bumps coalesce per user per 5 min; bundle regeneration capped at cfg.bundle.regen_max_per_s 20 | DEFAULT | G-sre-22 | 19, 18 |

### 2.6 Security, privacy and fraud (D-101 to D-124)

| ID | Decision | Status | Why / source | Docs |
| --- | --- | --- | --- | --- |
| D-101 | Tokens: access JWT ES256 with kid, 60 min TTL (web 15 min) with jitter plus or minus 10 min and refresh piggybacked under 5 min to expiry, 60 s grace on /sync/batch, expiry judged on corrected time; refresh token opaque 256-bit, stored hashed, rotated each use with reuse detection (60 s grace replays the stored response), sliding 30 days and absolute 90 days since the last password login; claims carry role, top scope nodes, scope_version; the server expands reach itself | DEFAULT; MUST-CONFIRM (by 0c): lifetimes (Q4) | D-sec-01; D-sre-04; G-sre-06 | 21, 17 |
| D-102 | Passwords: Argon2id on the server; web policy at least 12 characters, mixed case and a digit, not one of the last 10, not within 24 h of the last change (docs/09, PARITY); field roles at least 8 characters with a deny-list (proposed because 8,500 reps type on shared phones); lockout 10 attempts per username in 15 min with doubling cool-down keyed by (username, device) so a bound device with proof bypasses it (no denial of service); uniform errors; temporary password for resets (24 h, forces change); no self-service reset on the web today (PARITY, reset via TSO or support); web "Remember me" cfg.auth.web_remember_me_days default 0 | LOCKED-BY-SPEC (web policy); MUST-CONFIRM (by 0c): field-role policy (D-sec-13) | docs/09; V-web G-man-091; G-fraud-12 | 21 |
| D-103 | Device-binding OTP: created by the server when an unknown device attempts login; the TSO sees it in the SR Device OTP panel (view only, superset of the 8 SR-manual columns, scoped Wing to Zone filters, search, refresh) and may re-issue as an admin improvement; default 4 digits (parity), TTL cfg.auth.otp_ttl_min 120, 5 attempts then expired; stored encrypted and reversible (AES-GCM, key wrapped by Key Vault), NOT hashed, so the TSO can read it; online-only; binds user to device, needs a password-authenticated bind_required token; a bind from an unseen device model is flagged; bulk pre-issue per zone for wave days is audited | DEFAULT; the "server creates it" part is inference; MUST-CONFIRM (by 0c): TTL, retries, whether the AMO app binds (MQ-15) | V-device G-man-021; G-feat-11 | 21, 19 |
| D-104 | Device proof: at bind the app registers an EC P-256 key held in Android Keystore (StrongBox when present); X-Device-Proof signs refresh, bind and batch requests; every device-originated record carries an ES256 signature over canonical payload, client_uuid, captured_elapsed_ms and boot_id: record mode in the pilot, enforce before wave 1; devices without a hardware key are accepted with trust_level low | DEFAULT | D-fraud-02/03; G-fraud-03 | 21, 17 |
| D-105 | Keystore attestation chain and attestationApplicationId are verified at bind: genuine AKTCL-signed build gives trust_level normal, a repack or emulator gives low with weighted scoring; never a hard block alone | DEFAULT | G-fraud-04 | 21 |
| D-106 | Scope is enforced twice: a request-scoped ScopeContext and scoped() repository helper (lint rule no-unscoped-query) as the primary control and PostgreSQL row-level security (SET LOCAL app.user_id) on transactional and PII tables as defence in depth, with a 10 percent p95 budget (T-4-70); the client never sends scope ids; every foreign key in a write body is resolved through ScopeContext | LOCKED-BY-SPEC (CLAUDE.md 4) | D-sec-04 | 21, 16 |
| D-107 | PII classification: NID, TIN and trade licence are envelope-encrypted (AES-256-GCM, DEK wrapped in Key Vault, never searched, never in bundles or dw.dim_outlet); phone, owner name and address stay plaintext in app.outlet behind grants, a masked BI view, redaction in logs, list row budgets and an export log, pending legal review; docs/22 P-12 shows phone and owner are the real exposure and the NID placeholder 123 is treated as null | DEFAULT; MUST-CONFIRM (by 7c): legal opinion on sensitivity and localisation (G-sec-01, G-sec-04) | D-sec-06; docs/22 P-12 | 21, 16 |
| D-108 | Role x subject PII matrix: the server returns no SR phone to an AMO view (11 asterisks parity); retailer phones stay visible on route-scoped field screens (label "name (code-phone-cluster)"); TSO web parity baseline shows Address, NID, TIN and Trade Licence columns (null where blank or the placeholder) with every Excel export logged in report_export_log; restriction possible through cfg.pii.field_roles | DEFAULT; MUST-CONFIRM (by 4c): whether the live Excel contains NID/TIN (ask for a real file) | V-outlets G-man-062; V-web G-man-039 | 21, 15 |
| D-109 | Fraud model: server-side signals only (catalogue FS-01 to FS-29 in doc 21), stored in risk_signal and surfaced on AMO and TSO Exceptions and the web Exceptions report; nothing is auto-reversed or auto-deleted; supervisor review actions (reviewed, dismissed, confirmed) are idempotent events; AMO dismissals are re-sampled to the TSO; fraud thresholds (cfg.fraud.*) are owned by cfg.sec at class C2 or higher with floors and ceilings and a dead-signal watch | DEFAULT | D-sec-08; G-fraud-10/20 | 21, 19 |
| D-110 | Geo-spoof defence beyond mock flags: collect passive radio environment at fix time (serving and neighbour cell identities, visible Wi-Fi BSSID hashes when a recent system scan exists) into geo_fix.radio_env; rules for cell/position mismatch, GNSS time anomaly, stale or synthetic fix shape, co-located users; battery cost zero (passive reads on an existing fix); privacy class same as the fix | DEFAULT; MUST-CONFIRM (by 2d): privacy sign-off (G-fraud-01) | G-fraud-01/25 | 21, 16 |
| D-111 | Outlet-location change controls: a move above 300 m on a confirmed outlet requires TSO approval; verification of a location change needs the AMO's own fix within the radius of the proposed point or the request is flagged remote_verification; a proposed point within 500 m of the requester's check-in centroid is flagged moved_to_home; placeholder-pin outlets are corrected by the AMO alone | DEFAULT | G-fraud-06; DQ-37 | 21, 19 |
| D-112 | Supervisor takeover chain: a password reset plus OTP issue for the same user by the same actor within 24 h, or a bind of a device previously bound to the actor, holds the bind pending a second person and alerts DMO and security_admin; the SR is notified of a reset | DEFAULT | G-fraud-08 | 21 |
| D-113 | Audit: append-only app.audit_log, cfg.config_change_audit, security_event and report_export_log (REVOKE plus trigger plus hash chain), exported daily to an immutable (WORM) Blob container with 7-year retention (ASSUMPTION), PII-read and export events logged; no shared admin accounts | DEFAULT; MUST-CONFIRM (by 6b): retention (legal) | D-sec-10 | 21 |
| D-114 | Web authentication: access token in memory and refresh in an HttpOnly SameSite=Strict cookie (BFF) with Origin checks; TOTP MFA mandatory for the admin bundles (cfg.auth.mfa_required_roles default admin; recommended admin, top, wm); Microsoft Entra ID SSO for DMO, WM, top and admin if AKTCL has it | DEFAULT; MUST-CONFIRM (by 4c): Entra availability (G-sec-10) | D-sec-11 | 21 |
| D-115 | Microphone: the apps request camera only and NOT RECORD_AUDIO (the Audio prompt in the manual is a camera-plugin side effect and no recording UI exists); if call recording is a real feature it needs a consent design first | DEFAULT; MUST-CONFIRM (by 2e): Q15, MQ-55 | V-device G-man-020; D-sec-14 | 21, 17 |
| D-116 | API limits: per-device and per-user rate limits in Redis (cfg.api.rl.*; WAF per-IP thresholds only as a high backstop because carrier-grade NAT puts thousands of SRs behind one IP); batch body at most 1 MiB compressed, 8 MiB decompressed with a 20:1 ratio guard, at most 500 rows, memo lines at most 60; unknown JSON keys rejected; error envelope without stack or SQL | DEFAULT | G-scale-04/G-sec-15 | 21, 18 |
| D-117 | Server recomputes what it can: geo verdict, prices (price_mismatch flag, never reject a printed memo), totals (reject on arithmetic), points, business date; qty multiple-of-pack and large quantity are flags; coordinates outside the Bangladesh bounding box are flagged | LOCKED-BY-SPEC (CLAUDE.md 5, docs/05) | L-security s6.4 | 21, 16 |
| D-118 | Injection and rendering safety: export cell sanitiser against spreadsheet formulas, device- and server-side stripping of control and bidi/format characters in names reaching the printer, approval panel and dedupe, NFC normalisation and Bengali digit normalisation (G-field-10) at input and ingest | DEFAULT | G-fraud-14 | 21, 16 |
| D-119 | Apsis credentials: treated as data to migrate or rotate, never reused against a live service; if the dump carries verifiable password hashes (algorithm and parameters known) the importer verifies then re-hashes at first login, otherwise wave day uses TSO-issued temporary passwords per SR (forced change); same usernames either way | MUST-CONFIRM (by 7a): hash algorithm in the dump (Q18) | CLAUDE.md guardrail; G-sec-03 | 21, 16 |
| D-120 | Employee-location notice: an in-app Bangla and English notice at first login states that location is recorded only at check-in and out, outlet open, force sale and outlet capture; acceptance stored (user_consent); raw fixes are rounded to 3 decimals in dw after 730 days | DEFAULT; MUST-CONFIRM (by 7c): HR and legal text (G-sec-22) | L-security s4.1 | 21 |
| D-121 | PII read budgets: list endpoints mask personal columns by default with a logged reveal, hourly and daily row budgets per user, exports above a threshold need approval and carry a watermark sheet | DEFAULT | D-fraud-10; G-fraud-13 | 21 |
| D-122 | Supply chain: actions pinned by SHA, OIDC only, CodeQL, Semgrep, gitleaks, Dependabot and OSV, Trivy, cosign-signed images, SBOM; APK upload key separate from the signing key; independent reproducible rebuild of the release tag before publish; release publish is a human action; the lab self-hosted runner only runs protected-tag device-lab jobs | DEFAULT | G-fraud-16; L-security s10 | 21, 20 |
| D-123 | The SR is never shown fraud flags other than the mock warning; FLAG-policy codes never appear in cfg.sync.reason_texts; rejection texts shown to the SR cover only REJECT-policy codes | LOCKED-BY-SPEC (docs/05) | D-fraud-06; G-fraud-26 | 21, 17 |
| D-124 | Residual risks accepted and written down (rooted hook with consistent radio, credential sharing, SDR beside the real tower, zone AMO bundle on a rooted phone, two colluding approvers, break-glass for at most 4 h, retailer-side disputes); compensating signals are the due ledger, retailer SMS receipts (optional, off by default), joint calls | DEFAULT | C-fraud s7 | 21 |

### 2.7 Scale, reliability and Azure operations (D-125 to D-140)

| ID | Decision | Status | Why / source | Docs |
| --- | --- | --- | --- | --- |
| D-125 | Load model inputs are the data-profile values, not docs/01: peak 346,772 successful calls on one day, design point 4.5 lakh calls and 5 lakh visits per day, about 2.0 lines per call on average (design 2.5; p99 4, max 40), 11,336 routes, about 280 rows per SR-day, 2.2 million business rows per day; full table in s6 | LOCKED-BY-SPEC (docs/22) | P-01/P-02; C-sre s1.1 | 18 |
| D-126 | Pre-bind day: every wave has a day T-1 in which the TSOs gather the wave's SRs at the distribution house on Wi-Fi to install, bind, log in once, pre-fetch tomorrow's bundle and test-print, so day one is refresh plus 304; a per-replica password-hash concurrency limiter (cfg.auth.hash_concurrency_per_replica 4, 503 plus Retry-After) and a 2 vCPU / 4 GiB wave-morning api revision protect against the Argon2id memory storm | DEFAULT; MUST-CONFIRM (by 7b): operationally feasible (Q52) | G-sre-04; D-sre-05 | 18, 20 |
| D-127 | DR posture: RTO 4 h and RPO 15 min for a region outage (cross-region async replica plus Bicep redeploy), PITR RTO 2 h, both made safe by the server-generation re-sync (D-63); a Front Door secondary origin group in the DR region; Key Vault soft-delete recovery in the drill | MUST-CONFIRM (by 7c): tolerance (Q20, Q40, Q54) | D-scale-5; D-sre-01 | 18 |
| D-128 | Read path: dashboards, reports, Excel and BI read the in-region replica with an "as of" stamp; live-state tiles (route_day, final-submit validation, device page) read the primary; Redis response cache 30 s keyed by scope hash; sync-health has a degraded mode (zone level, 120 s refresh) when replica lag exceeds 60 s | DEFAULT | D-sre-08 | 18, 16 |
| D-129 | Per-user bundle snapshots are pre-generated by the job DAG (D-71), served with ETag/304 and live ?since deltas; invalidation by scope on assignment, outlet, price, offer, target or config change (debounced 60 s, capped); a pre-generation failure falls back to yesterday's snapshot plus delta (bundle_hold), not to live generation | DEFAULT | D-scale-3; G-sre-08 | 18, 17 |
| D-130 | Kill-switch semantics: min_version blocks a new day's login; blocked_versions blocks new captures; neither ever blocks upload of captured rows or wipes data; the single exception is the version-scoped sync hold (cfg.ops.sync_hold_by_version, enforced at the Front Door, auto-expiring, rows stay on the device) for an upload loop; every operational switch has a mandatory duration | DEFAULT | D-scale-9; D-sre-11 | 18, 19 |
| D-131 | Storage and partitions: SSD v2 has no autogrow (alert at 70 percent, first grow at month 12 on the re-based growth of about 650 MB per day); partitions created by a job with a default partition and an alert on rows landing in it; archive job writes a manifest and drops only after verified upload | DEFAULT | G-scale-08; C-sre s1.1 | 18, 16 |
| D-132 | Blob: ZRS for bundles, apk and content; read-access geo-zone-redundant (RA-GZRS) for photos and audit-export so evidence stays readable in a region outage; lifecycle Hot to Cool 30 d to Cold 90 d to Archive 365 d; reference photos exempt; one user-delegation key cached per replica (refresh at 6 days or on 403) | DEFAULT; MUST-CONFIRM (by 6c): photo retention (Q21) | G-sre-13; L-scale s2.6 | 18, 21 |
| D-133 | Pre-scale schedule is data (cfg.ops.prescale_schedule per wing, default 06:15 and 16:45 to 8 api replicas, worker 3, web 4); quotas are requested in Phase 0 (ACA cores at least 64, 128 for the full fleet; PostgreSQL at least 48 vCores; Azure Load Testing at least 10 engines) with alerts at 80 percent | DEFAULT | G-scale-11; G-sre-25 | 18 |
| D-134 | Load testing is Azure Load Testing with a Locust device simulator on staging scaled to the production SKU only for the test window, plus a k6 smoke on every main build; scenarios S1 to S10 rebased on D-125; the full-fleet gate is 1.5 times the fleet | DEFAULT | D-scale-12; G-sre-05 | 18, 20 |
| D-135 | SLOs (07:00 to 21:00 Dhaka): auth/sync/day availability 99.9 percent; bundle p95 at most 2 s server time; trickle ack p95 at most 1.5 s; catch-up (200 rows) p95 at most 8 s; sync success at least 99.5 percent within 3 attempts; reconciliation mismatch at most 0.1 percent of route-days after settle; aggregation lag p95 at most 60 s; dashboards p95 at most 1 s; SLIs measured at the edge as well as the API; burn-rate alerts and calendar-aware baselines (Friday, holidays) | DEFAULT | L-scale s5.1; G-sre-19 | 18 |
| D-136 | Telemetry: server-side adaptive sampling (100 percent of errors, 429, 5xx, slow above 2 s; 5 percent of 2xx); device telemetry only inside the sync batch with cfg.telemetry.device_max_bytes_per_day 1024; sync summaries in a cap-exempt workspace table; the daily cap sized three times for wave days with an 80 percent alert | DEFAULT | G-scale-13; G-sre-24 | 18 |
| D-137 | PgBouncer transaction-mode rules: no named prepared statements, no LISTEN on 6432, SET LOCAL only inside transactions; config cache invalidation uses Redis pub/sub with a 30 s poll fallback; graceful shutdown drains 30 s | DEFAULT | G-sre-15 | 18, 19 |
| D-138 | Support visibility: the server records per-device observed state from request headers (X-Pending-Rows, X-Last-Sync-Error) at most once per 10 min, POST /support/ping on demand, a device-lookup workbook with an L1 read-only role; support code on every blocking screen | DEFAULT; MUST-CONFIRM (by 7c): L1 access model (Q55) | G-sre-18; G-field-15 | 18, 20 |
| D-139 | ACA api sizing and probes: 1 vCPU / 2 GiB, HTTP rule concurrentRequests 30; startup, liveness (process only) and readiness (DB and Redis, 2 s) probes; statement_timeout per DB role (api 15 s, worker 10 min, web 60 s); lock_timeout 3 s for api; pool 8 per api replica and 4 per worker or web | DEFAULT | L-scale s2.4/2.5 | 18 |
| D-140 | Worker poll interval cfg.agg.poll_interval_s: 5 s between 06:00 and 23:00 Dhaka, 60 s otherwise; claim batch 500; claim timeout 300 s; dead items surface on sync-health with re-drive | DEFAULT | G-sre-17; cross-lens X7 | 16, 18 |

### 2.8 Test, release and cutover (D-141 to D-156)

| ID | Decision | Status | Why / source | Docs |
| --- | --- | --- | --- | --- |
| D-141 | A gate is a test, an artefact under /docs/evidence/phase-<n>/<T-id>/ and a named verifier who is never the author; a phase exits when all blocking gates are green and the exit report carries two signatures (tech lead, sponsor's delegate); each sub-milestone ends with a demo of at most 30 minutes | LOCKED-BY-SPEC (sponsor's process requirement) | D-qa-01 | 20, 14 |
| D-142 | The baseline pack from the current system (full SR day recording, at least 25 printed memos with entered quantities incl. every live promotion group, all web pages, month of Excel exports for 3 pilot zones, string glossary, the four manuals) is a Phase 0 gate (T-0-49) and the Phase 1 entry criterion; without it parity has no oracle | MUST-CONFIRM (by 0c) | G-qa-01; D-qa-15 | 20, 14 |
| D-143 | Promotion engine, DRP/slide deduction, QC deduction and memo rounding move from Phase 5 into sub-milestone 2a; Phase 5 keeps the programmes (Astha, Diamond League, Superstar, targets, tasks, leave) | LOCKED-BY-SPEC (pilot parity) | D-qa-14; G-qa-02 | 14, 15, 16 |
| D-144 | The pilot runs on production with accounts flagged pilot=true excluded from national rollups (cfg.flag.pilot_in_rollups false); Apsis stays the system of record until a wave switches | DEFAULT | D-qa-16 | 20, 18 |
| D-145 | Pilot pass rule: ten consecutive trading days (cfg.calendar) with zero sync-loss discrepancies (category C), exact memo count, STD, dues and value after categories A (double-entry), D (Apsis-side change) and E (business date) are explained and B (rule difference) closed by a recorded decision; one category C resets the count | DEFAULT | D-qa-11 | 20 |
| D-146 | "Same memo" is signed against the baseline memo corpus by the sponsor's delegate with one SR and one retailer-facing manager; retailer feedback is a column on the pilot daily sheet | MUST-CONFIRM (by 2e): who signs for retailers (Q37) | G-qa-24 | 20 |
| D-147 | Wave plan: pilot 10 to 20 routes, then waves by territory then division then half then rest; never within three days of a month end, Eid or a holiday (calendar) or on a Thursday; wave 1 is one territory per wing (about 300 routes) unless Q51 says 1,000 SRs | MUST-CONFIRM (by 7b): wave sizes and order (Q38, Q51) | L-quality s5.6; C-sre Q41 | 14, 20 |
| D-148 | Wave rollback is a flag flip (cfg.flag.new_app_login_enabled false at wave scope, pushed) with captured rows still uploading; new-app data captured during the wave is exportable in the Apsis dump shape (export job) because AKTCL cannot set the Apsis app read-only itself | DEFAULT; MUST-CONFIRM (by 7b): how the old app is made read-only per wave (G-feat-67) | G-qa-11; G-feat-67 | 20, 19 |
| D-149 | Support: tiers 0 self, 1 AMO, 1.5 TSO (OTP, temporary password, final submit), 2 helpdesk (8 to 12 Bangla agents, 07:00 to 21:00, 24 h on wave days 1 to 3), 3 engineering on-call; SLA P1 15 min response and 2 h workaround; top-20 Bangla scripts; staffing arithmetic as L-quality s5.7 | MUST-CONFIRM (by 7c): helpdesk exists, hours, channels (Q35) | G-qa-04 | 20 |
| D-150 | Contract: Zod schemas in /packages/contract are the single source for API, web and app; generated OpenAPI 3.1, TypeScript and Dart clients are committed and drift-checked; golden fixtures round-trip in both languages; oasdiff breaking-change gate; the server accepts schema_version N, N-1, N-2 | LOCKED-BY-SPEC (CLAUDE.md "one shared source of truth") | D-qa-02 | 20, 16 |
| D-151 | CI test data is synthetic and PII-free from /packages/testkit; the Apsis sample never enters CI; staging carries a full-size synthetic fleet; anonymised pilot batches (after 7b) form the fuzz corpus | LOCKED-BY-SPEC | G-qa-08 | 20 |
| D-152 | Parallel-run print behaviour: cfg.flag.parallel_run_mode values off, capture_only, print_test_watermark; the test print carries "পরীক্ষামূলক - এটি রসিদ নয়" and no previous-due line; new-app due collections flagged parallel are excluded from balances; the SR briefing card names which memo the retailer keeps | MUST-CONFIRM (by 7b): Q49 | G-field-12 | 20, 17 |
| D-153 | Import quarantine threshold: at most 1 percent of rows per table with every reason counted and explained; a higher rate raises a gap; adjudication by sales ops (history) and finance (dues) | MUST-CONFIRM (by 7a): Q39 | L-quality s5.3 | 20, 16 |
| D-154 | The parallel run needs per-route daily Apsis data in a machine-readable form (memo count, STD per SKU, value, dues); fallback for the pilot only is manual keying from photographed summaries | MUST-CONFIRM (by 7b): Q32 | G-qa-03 | 20 |
| D-155 | Decommission only after all waves are stable for 10 trading days, the final Apsis delta is imported and reconciled, Apsis credentials are rotated or removed, the raw dump is archived to immutable storage then deleted per policy (30 days after reconciliation), and memo-number continuity is recorded | DEFAULT | docs/11; T-7-88 | 20, 14 |
| D-156 | The sponsor's delegate per phase and the four readiness owners (engineering, ops, business, support) are named before Phase 0 exits | MUST-CONFIRM (by 0a): Q34 | G-qa-06 | 14, 20 |

### 2.9 Manual versus spec: the 51 contradiction rows (D-157 to D-207, D = 156 + k for C-k)

Rule applied: PARITY FIRST; a constraint wins only where it is broken, and that is recorded as DELIBERATE CHANGE. "Verification" means the corrected statement from the verification files replaces the register's text. Status follows s2 legend.

| D-id | C-id | Winner | Decision | Status | Docs |
| --- | --- | --- | --- | --- | --- |
| D-157 | C-01 | Manual | Quantity entered and stored in sticks (cigarette, bidi); pack badge is derived. See D-16, D-17. Plan M-02 "packs" is refuted | LOCKED-BY-SPEC | 15, 16 |
| D-158 | C-02 | Manual | Memo net = gross - offer discount - DRP discount - QC settlement; quantity totals unchanged (D-18). The printed paper layout is never shown in any manual, so the template comes from physical samples | DEFAULT; MUST-CONFIRM (by 1a): samples | 16, 17 |
| D-159 | C-03 | Manual | One qc_fault_type table: 6 app types and the web labels, 11 codes, applies_to app/web, stable group codes MFC and MKT; the app group "পরিবহন ত্রুটি" is MKT in the English summary. See D-34 | DEFAULT | 15, 16 |
| D-160 | C-04 | Manual | Explicit call-start prompt after the geo gate; visit created at outlet open, call_started_at separate; "না" is not counted visited. See D-78 | LOCKED-BY-SPEC | 15, 17 |
| D-161 | C-05 | Manual | Mark-as-paid settles the whole memo; partial collection is an off-by-default enhancement; a due_collection row is always written. See D-37 | LOCKED-BY-SPEC (PARITY) | 15, 16 |
| D-162 | C-06 | Manual | Outlet new, close and info forms pick a Cluster; route is derived server-side; both route_id and cluster_id stored; AMO verification forms carry both a Route and a Cluster dropdown (verification); picker label differs by role (SR "name (sub-channel)", AMO "name-code-phone-cluster") | LOCKED-BY-SPEC | 15, 16 |
| D-163 | C-07 | Spec | Force Sale and Manual Override photos do not silently move the outlet: they raise a location-change request, the call proceeds (photo_validated true, geo_validated false) and the device keeps a provisional flagged location; immediate update only for a missing or placeholder location. Verification: the manual says "location information will be updated" but not that master coordinates are overwritten, and shows no approval; docs/05 and docs/07 also disagree. DELIBERATE CHANGE. See D-95 | DEFAULT | 15, 19, 21 |
| D-164 | C-08 | Split | Manual wins on a 4-digit OTP and a view-only TSO panel; recommendation wins on no re-verify for in-place updates (cfg.auth.reverify_on_new_version false; parity true available). "Server creates the OTP at login" is an inference; docs saying "TSO provided" are not flatly contradicted. See D-80, D-103 | DEFAULT; MUST-CONFIRM (by 0c) | 21 |
| D-165 | C-09 | Manual | The TSO must be able to read the code: stored encrypted and reversible, never a one-way hash (lens M-41 code_hash is replaced). View-only is on the page; "server-created" is not | DEFAULT | 21, 16 |
| D-166 | C-10 | Reopened | Verification: auto-applied (non-DRP) discounts DO exist (SR home card: total discount 437.50 beside DRP discount 0.00). Keep the offer engine, store offer_discount and drp_discount separately, do not invent a discount line on Review; where auto discounts render is unknown | MUST-CONFIRM (by 2a): MQ-06 | 15, 16 |
| D-167 | C-11 | Manual | Task status labels চলমান (ongoing) and সম্পন্ন (completed) map to the status enum; resolved tasks stay on the SR list; badge = open task count (confirm) | DEFAULT | 15 |
| D-168 | C-12 | Manual | Astha memo target is one "All Brand" row at route level; at outlet level it is per brand in the SR app and one "All Brand" row in the AMO app (verification: the shapes differ per APP, not per route versus outlet): one table component with show_remaining and memo-target mode per role | LOCKED-BY-SPEC | 15, 16 |
| D-169 | C-13 | Manual | No Task Delegation badge on the AMO home (it belongs to the SR app); the Outlet tile badge = pending SR verification requests in the AMO's scope, shown only when above zero (DELIBERATE CHANGE for the zero dot); the home value (8 or 0) does not equal the hub badges (2+2+2), so the definition stays unknown | DEFAULT; MUST-CONFIRM (by 3a): live account | 15 |
| D-170 | C-14 | Spec | Team Location shows the last synced fix with "last seen HH:MM (n min ago)" and its source (check-in, visit, sync), greyed after cfg.tso.team_location_max_age_min (120, AMO too), list fallback offline; no continuous tracking (CLAUDE.md 3). DELIBERATE CHANGE for AKTCL sign-off | DEFAULT | 15, 17, 18 |
| D-171 | C-15 | Manual | The AMO SR list hides SR phones (server returns none); retailer phones stay visible on field screens. See D-108 | LOCKED-BY-SPEC | 15, 21 |
| D-172 | C-16 | Web manual | Pending, then Verify (AMO app Save or web Verify), then Reject or Approve on the web only, all three types; AMO "বাতিল" discards the form with no server call (inference; confirm); the docs/07 "Cancel = reject" has no support. See D-43 | DEFAULT; MUST-CONFIRM (by 3a): MQ-29 | 15 |
| D-173 | C-17 | Warn side | Sales Submit is enabled after sync; outstanding dues only WARN (dialog with a dynamic count of retailers on the route with due above 0); never block. The two manual sentences describe the same flow (verification); the real contradiction is docs/04 "dues cleared", amended here | LOCKED-BY-SPEC | 15, 17 |
| D-174 | C-18 | CLAUDE.md | TSO logout wipes only a reconciled device and is refused with the pending count otherwise; SR and AMO never wipe unsynced data. DELIBERATE CHANGE. See D-69 | LOCKED-BY-SPEC | 15, 17 |
| D-175 | C-19 | Retracted | Verification: the Select Outlets second line is probably the cluster, so the card shows name, code, cluster, owner, phone (address as fallback); there is no contradiction. The 30-outlet cap is invented: default none, configurable | DEFAULT | 15 |
| D-176 | C-20 | Manual | Leave: one from_date plus typed integer days of at least 1 are authoritative; to_date derived for new rows; imported rows are never recomputed or rejected; no 30-day cap | DEFAULT | 15, 16 |
| D-177 | C-21 | Manual | The TSO, AMO and web Submit % tiles use logged-in routes; the plan figure is kept as the renamed Day-completion %. See D-45; web basis unproven | DEFAULT; MUST-CONFIRM (by 3b): MQ-34 | 15, 16 |
| D-178 | C-22 | Manual | Targets are per variant: product_level gains variant. See D-31 | LOCKED-BY-SPEC | 16 |
| D-179 | C-23 | Manual | Every monthly target set is a submission needing approval (WMO pending seen); level order and statuses other than "WMO approval pending" are not evidenced. See D-31 | DEFAULT; MUST-CONFIRM (by 5c) | 16, 19 |
| D-180 | C-24 | docs/03 | zone gets dep_id and dep_name (schema.sql lacked them); "Dep Name" is the zone name in the sample | LOCKED-BY-SPEC | 16 |
| D-181 | C-25 | Manual (default) | Per-role default locale (cfg.app.default_locale: sr bn, amo bn, tso en); bilingual catalogues ship for all; TSO gets a language switch as an IMPROVEMENT; CLAUDE.md "as today" is false for the TSO (verification) | DEFAULT | 15, 17 |
| D-182 | C-26 | Spec | Web "Delete Section Data" is an audited void with reason and ingest tombstones, only before Final Submit, default scope web-entry rows. See D-22, D-40 | DEFAULT; MUST-CONFIRM (by 4c) | 16, 19 |
| D-183 | C-27 | Manual | QC reasons are one code table with applies_to; web 5+5 and app 3+3 map into it. See D-34 | DEFAULT | 16 |
| D-184 | C-28 | Manual | Web Final Submit page (selectable date) and the TSO app use one server rule; the web page carries the app's messages and the DSS advisory | DEFAULT | 15, 19 |
| D-185 | C-29 | Manual (union) | The TSO web inventory is 15 menu items and 41 pages; the real inventory is the union across roles; the menu is data. The register's explanations "two roles" and "the build changed" are rejected (verification: the sidebar is clipped in screenshots) | DEFAULT; MUST-CONFIRM (by 4a): other roles' menus (MQ-48) | 15, 19 |
| D-186 | C-30 | Manual | The Outlet Approval Panel serves New, Close and Info through one Outlet Type filter with Verify, Reject, Approve and an Approve confirm dialog | LOCKED-BY-SPEC | 15, 19 |
| D-187 | C-31 | Manual | Route kind sr/amo; SS appears as a supervisor-tier user in the AMO build (meaning unknown); correct F-ADM-003 and M-14 wording; never key on route name. See D-29 | MUST-CONFIRM (by 3a): MQ-19 | 15, 16 |
| D-188 | C-32 | Both | The web dashboard loads for today without a button press AND offers a date-range Filter | LOCKED-BY-SPEC | 15 |
| D-189 | C-33 | Manual | TSO enters targets route by route or by Excel upload (route x variant); no automatic split is evidenced; formula split optional (cfg.target.split_method manual) | DEFAULT | 19, 16 |
| D-190 | C-34 | Manual | QC, Sales Plan, Data Entry, Supervisory, Wholesale, OTP and Set Target are TSO-operated pages; the TSO has write rights within own scope | LOCKED-BY-SPEC | 19 |
| D-191 | C-35 | Spec (corrected) | Reports render on screen first; the count of Excel-only reports is 11 by the register's own list (not 13) and two of them show Get Data; the same column sets as today's Excel files are required, so sample .xlsx files are requested | MUST-CONFIRM (by 4b): MQ-47 | 15, 16 |
| D-192 | C-36 | Manual | Astha gift choice is the web Astha Gift Choice Panel (route-scoped, per-outlet dropdown) plus a report filtered by Gift Status; answers Q13's "TSO portal"; save and lock rules unknown | MUST-CONFIRM (by 5a): MQ-26 | 15, 19 |
| D-193 | C-37 | Both | Report units differ by surface (Lighter Pcs on web, Box on TSO; Match Dozen) via report_unit; entry unit is separate. See D-16 | DEFAULT | 16 |
| D-194 | C-38 | Manual evidence | Match is priced per piece on some screens and per dozen on others, even inside the SR manual (12 pieces versus 1 dozen); never infer a unit from a number; fix a base unit per category and label it | MUST-CONFIRM (by 2a): MQ-01 | 15, 16 |
| D-195 | C-39 | Manual-derived | Till-date target bases are per surface (TSO 26/30, AMO team about 25/30, AMO report 15/17, SR ADS 14 and 11); the register's single calendar default is rejected. See D-51 | DEFAULT; MUST-CONFIRM (by 3a) | 16 |
| D-196 | C-40 | Manual | Cap 100 on cards and bars, uncapped detail to 2 decimals, SR card uncapped; remove the plan's global 1000 cap for detail tables. See D-50 | LOCKED-BY-SPEC | 15, 16 |
| D-197 | C-41 | Manual | No invented limits: no visit-plan outlet cap, no leave day cap, feedback categories only "Suggestion" (config keeps the list) | DEFAULT | 15, 19 |
| D-198 | C-42 | Manual (by silence) | No Final Submit time gate is applied (cfg.day.final_submit_earliest_time none); the 14:26:05 sample row is a Not Done row so it proves nothing either way (verification); an explicit confirm dialog before the irreversible submit is an IMPROVEMENT | MUST-CONFIRM (by 3b): Q-41/MQ-41 | 15, 19 |
| D-199 | C-43 | Manual | Visit Query = two free-text questions (Bangla labels as printed) plus a built-in "delegate task" radio defaulting to No; answers optional, 500-character limit (ASSUMPTION) | DEFAULT | 15 |
| D-200 | C-44 | Manual | First edit reason is "ভুল SKU নির্বাচিত।" (wrong_sku); the other two are captured from the live app; wrong_outlet is dropped because the outlet is read-only on edit | MUST-CONFIRM (by 2b): MQ-18 | 15, 19 |
| D-201 | C-45 | Spec | Edit is blocked at OUTLET level once any QC was done there (docs/06 already says so and the manual agrees); the plan's per-memo DQ-16 and F-SR-033 narrowing is corrected; day scope of the lock unconfirmed | DEFAULT | 15, 16 |
| D-202 | C-46 | Manual | A zero-sale review is printable; a zero sale writes a memo row with line_count 0. See D-36 | LOCKED-BY-SPEC | 15, 16 |
| D-203 | C-47 | Manual | The credit checkbox and the "প্রোডাক্ট QC" button are independent, in any order; QC is optional | LOCKED-BY-SPEC | 15, 17 |
| D-204 | C-48 | Both | The current TSO drawer has exactly seven entries, logout is the header icon, no Settings today; a Settings entry (language, version and update, send data file, change password) is added as an IMPROVEMENT; mark F-SYS-019/020/021 "SR and AMO; TSO improvement" and F-SYS-004 "web; apps improvement" | DEFAULT | 15, 17 |
| D-205 | C-49 | Flavours of one codebase | See D-01; keeps install and training parity | DEFAULT | 17 |
| D-206 | C-50 | Manual | SR Sale History is a feature (F-SR-054): per outlet per date SKU quantity and value, reachable before and after the geo gate, writes nothing; local window plus online fallback; the footer is the sum of the rows (the observed 20,260 versus 20,660 is a display bug); scope this SR versus all SRs for the outlet unknown, default all memos for the outlet within the user's scope | DEFAULT; MUST-CONFIRM (by 2b) | 15, 17 |
| D-207 | C-51 | Decision needed | Parity baseline: the TSO sees Address, NID, TIN and Trade Licence columns (blank or placeholder shown as null); exports are logged; restriction by cfg.pii.field_roles is a recorded change. See D-108 | MUST-CONFIRM (by 4c) | 21, 15 |

### 2.10 Inside-manual conflicts: the 37 build decisions (D-208 to D-244, D = 207 + k for I-k)

| D-id | I-id | Decision (the register's build decision unless marked) | Docs |
| --- | --- | --- | --- |
| D-208 | I-01 | Show the real installed build version on login, drawer and Settings from one source; the post-update splash shows the pre-update number only as a processing label | 17 |
| D-209 | I-02 | Check-out enabled when corrected Dhaka time is 17:00 or later (inclusive); the tile text "after 5" and the note "from 5 pm" mean the same | 15, 17 |
| D-210 | I-03 | The "এগিয়ে যান" button is on the sale footer (not after picking the shop); no separate shop-select button | 15 |
| D-211 | I-04 | Sale History and AMO Sale Data totals are computed exactly from rows; the observed 400 discrepancy (20,260 versus 20,660) is not copied; footer equals the sum of rows (test) | 15, 16 |
| D-212 | I-05 | One credit label "বাকি" (localised); "ক্রেডিট" retired | 15 |
| D-213 | I-06 | Dues are shown to the paisa everywhere including the checkbox label (verification: the label truncates 61.50 to 61 and 48.50 to 48) | 15 |
| D-214 | I-07 | Same field with per-screen labels: "রুট" on review and "সেকশন" only where a physical memo sample prints it | 15, 17 |
| D-215 | I-08 | Source-data inconsistency (Babu Store Wing "Dhaka" versus "Gaibandha") goes to the import validation report as an outlet geography mismatch | 16 |
| D-216 | I-09 | Deposit enabled after sync; dues warn only. See D-173 | 15 |
| D-217 | I-10 | One label for slide/DRP: "স্লাইড সংগ্রহ" with the English alias "Collect DRP Discount" | 15 |
| D-218 | I-11 | "পূর্বের সেল ডাটা দেখুন" and "Sale History" are one screen with one label | 15 |
| D-219 | I-12 | Tornado fan costs 400 points (PDF checked; docs/06 and docs/10 already say 400) | 15 |
| D-220 | I-13 | The red slashed icon on Review is the printer-not-connected indicator (verification: a printer, not an eye) | 15, 17 |
| D-221 | I-14 | AMO Control Call real flow is p25 to 27; the p21 slide titled Control Call shows the Joint Call screen | 15 |
| D-222 | I-15 | Reconciliation rows are config-driven per role and app version; the Server column is the last server_totals, blank with a timestamp before the first sync; AMO may show 8 or 9 rows | 15, 17 |
| D-223 | I-16 | Update is two stages of one flow (APK download then local migration with n/m progress). See D-79 | 17 |
| D-224 | I-17 | Say which size the baseline is: installed size (92 to 101 MB) versus APK file (74 to 80 MB); docs/04's "about 90 MB" is the installed footprint | 17 |
| D-225 | I-18 | The 12:53 PM check-out sample (another account, ss344002, 2025-11-01) is unexplained, not a "test capture" (verification); the 17:00 rule stays; cfg.day.checkout_earliest_time can differ per role scope | 15 |
| D-226 | I-19 | Terminology table keeps cluster and zone separate ("Savar Metro" appears as both in the manual) | 15 |
| D-227 | I-20 | Key on sku code; the seed catalogue carries both ARIS-O-20s and ARIS-A-20s | 16 |
| D-228 | I-21 | Display glitches in the manuals (footer sums, bidi 6,500 versus 7,300) are not golden values; golden tests recompute and cross-check every printed percentage; Platinum Series 3,780 and 107.1 percent is correct (not a glitch) | 20 |
| D-229 | I-22 | AMO Outlet badge = pending SR verification requests in scope (working definition, unverified) | 15 |
| D-230 | I-23 | The cancel icon on verification forms means discard with a confirm; no server call | 15 |
| D-231 | I-24 | Astha memo target with month chips: zero chips means the whole quarter; achievement filters by the chosen months; whether the target recomputes is unknown (behaviour not proven by any capture) | 15, 16 |
| D-232 | I-25 | Callout "ARON SR" in the TSO manual is a copy-paste; the app is ARON TSO | 17 |
| D-233 | I-26 | Screen titles win over callout wording: "By Channel STD" and "CPR" (the callout is web wording) | 15 |
| D-234 | I-27 | Leave days are typed, not derived; card renders "<from> to <to>" and "n Day(s)" | 15 |
| D-235 | I-28 | The already-submitted check fires at "Get Sales Data" through the preview endpoint. See D-82 | 15, 17 |
| D-236 | I-29 | Dashboard counts are per route, list rows are per (user, route); the UI says so | 15, 16 |
| D-237 | I-30 | outlet_code is text; "DHK-344-011" and "2689479" both occur | 16 |
| D-238 | I-31 | One export label "Get Excel" | 15 |
| D-239 | I-32 | Single-date screens are built as single-date (captions say range); a range is an improvement | 15 |
| D-240 | I-33 | Data Entry Log MIN is the first and MAX the last event time; do not copy the swapped sample (MAX 16:57:25 earlier than MIN 17:35:50) | 16 |
| D-241 | I-34 | One name per report with display aliases stored as data (BSR and CPR has three strings, Route-wise STD two) | 15 |
| D-242 | I-35 | Store route name and visit-day label separately; never key on route name (names repeat) | 16 |
| D-243 | I-36 | Menu differences between screenshots are sidebar clipping; use the union; no role or build explanation. See D-185 | 15, 19 |
| D-244 | I-37 | Never reproduce vendor strings ("Firefly Outlets Reports", "Apsis", "Developed by Apsis Solutions", "Login page" slide titles, the example password "PhuTysYsd623gB") | 15, 17 |

### 2.11 Data-profile findings folded in (D-245 to D-260, D = 244 + k for P-k)

Source: docs/22 (partial Apsis export: 37.3 M sales rows, 734,789 outlets). Each finding is a decision with an owner; section 6.2 shows what each changes in the load model and importer.

| D-id | P-id | Decision | Status | Docs |
| --- | --- | --- | --- | --- |
| D-245 | P-01 | Size for the real volume: peak 346,772 successful calls per day (25 Jun), June average 306k; design 4.5 lakh calls and 5 lakh visits per day with growth headroom (rows per day grew 53 percent from May to July). The export may include sales not made in the apps (direct distributor billing) | DEFAULT; MUST-CONFIRM (by 1c): whether all sales in the export come through SR/AMO apps | 18, 14 |
| D-246 | P-02 | Memos are getting longer: 1.95 SKU lines per outlet-day in July (1.26 in May), p99 4, max 40; design 2.5 average; memo_line rows, print length and bundle size grow faster than visits; a memo supports at least 60 lines (cfg.sale.max_lines_per_memo) | DEFAULT | 16, 17, 18 |
| D-247 | P-03 | Friday is the weekly off-day (one Friday, 22 May, traded just before Eid); Eid break 27 to 31 May and single holidays 13 and 19 June show no trading: the working-day calendar is an admin-editable data set (default Friday off, holidays, make-up days). See D-28 | DEFAULT | 16, 19 |
| D-248 | P-04 | Volume is stored in sticks and pieces (every positive volume is an exact multiple of the pack size); resolves Q8 for cigarettes and bidi; lighter and match stay MUST-CONFIRM. See D-16 | LOCKED-BY-SPEC; MUST-CONFIRM (by 2a) lighter, match | 16 |
| D-249 | P-05 | Zero-volume lines (8.7 percent of rows; MaxR-10S is 100 percent zero) are not sales: STD and KPIs exclude them while zero-sale calls count as visits; an all-zero outlet-day is treated as a zero-sale call (ASSUMPTION, strike rate 84 to 90 percent by month), to confirm against memo-level data | DEFAULT; MUST-CONFIRM (by 4a) | 16 |
| D-250 | P-06 | 68 duplicate (date, outlet, SKU) keys with differing values show an outlet can be visited twice in a day (two routes, two SRs) or a memo edited: no design may assume one memo per outlet-day; multi-visit policy cfg.day.multi_visit_same_outlet_policy default allow_after_zero_sale (DQ-42) | DEFAULT | 16, 17 |
| D-251 | P-07 | Outlet codes are text (693k are 7 digits; others 8 to 13 characters, some with letters, stray leading symbols or 1 to 2 digits): the importer normalises, keeps a crosswalk and quarantines what it cannot parse; letters probably mark field-created outlets (ASSUMPTION) | DEFAULT | 16 |
| D-252 | P-08 | 175,031 outlets (2.6 M rows, 277 M sticks) are in the sales file but not in the 1 October retailer list: create archived outlet stubs; closure is a status, never a delete. See D-25 | LOCKED-BY-SPEC | 16 |
| D-253 | P-09 | 34,454 outlets sit on 11,222 identical points and 1,093 have no coordinates: flag location_confirmed = false (location unconfirmed); the first visit routes through the force-sale or Update Base correction path rather than a punitive geo-fail; cfg.geo.no_location_policy applies. See D-95 | DEFAULT | 16, 19, 21 |
| D-254 | P-10 | A 100 m geofence mostly proves "in this market" (80 percent of outlets share a 55 m cell, densest cell 383): radius is adjustable per zone and per outlet with a density view in the admin panel, geo-validation is one signal among several (photo, visit sequence, supervisor flags, radio environment), and exact nearest-neighbour distances are computed with PostGIS on the full dump | DEFAULT | 19, 21, 16 |
| D-255 | P-11 | Outlet "Created At" is hour-precision 12-hour text without timezone ("2025-12-19 01AM"): explicit parser, Asia/Dhaka assumed; "Updated At" is partial; app-created outlet requests are a trickle (approval queue well under 500 per day) | DEFAULT | 16 |
| D-256 | P-12 | PII reality: phone and owner name are filled for 100 percent; NID is the placeholder 123 for 589k and blank for 145k; TIN and trade licence are empty; address filled for 13: keep NID, TIN and licence columns nullable and PII-classified but build nothing that depends on them. See D-107 | LOCKED-BY-SPEC | 21, 16 |
| D-257 | P-13 | Hierarchy matches the spec (10 wings, 50 divisions, 291 territories, 1,051 zones) with 11,336 routes (median 64 outlets, p99 112, max 214; 4 to 54 routes per zone): one SR covers more than one route, so the day plan is driven by visit days and assignment history, never "one SR = one route"; bundle per SR about 65 to 110 outlets per route-day | LOCKED-BY-SPEC | 16, 17, 18 |
| D-258 | P-14 | geo_class allows null (19 percent blank); import maps "Semi Urban" to SemiUrban; sub-channels are GT, Gold, Platinum, RCC, Diamond, DCC, Silver, MT, HoReCa (nine); Astha tiers are sub-channels of channel Astha; cluster type is 99.7 percent Transit Hub | LOCKED-BY-SPEC | 16 |
| D-259 | P-15 | All 40 SKUs sold match the seed sku_code exactly; the export's sku_id (1 to 43 with gaps at 19, 20, 40) is the importer's SKU crosswalk key; SupSty-10S and SupSty-20S were not sold in the sample | LOCKED-BY-SPEC | 16 |
| D-260 | P-16 | Wholesale/C&C buyers exist (26,683 lines of 10,000 sticks or more from 21,092 outlets): quantity validation is a configurable soft ceiling with an anomaly flag, not a hard cap; the largest lines are 10^5 to 6.5 x 10^5 sticks. See D-42 | DEFAULT | 16, 15 |

### 2.12 Cross-lens conflict resolutions (D-261 to D-269)

| D-id | Conflict | Resolution | Status | Docs |
| --- | --- | --- | --- | --- |
| D-261 | Debounce 10 s (scale) versus 5 s (sync) | 5 s with the family hold; load unchanged (one batch per visit) | DEFAULT | 17, 18 |
| D-262 | Final submit transport: online-only (lens-sync) versus outbox (critic-field) | POST /day/final-submit stays online-only in the app (the TSO manual shows a server round trip), retry-safe by client_uuid and once-only by primary key; delegation to an acting TSO or DMO (cfg.day.final_submit_delegate_roles) and auto-close (cfg.day.final_submit_autoclose_time, default null) exist as options, off until confirmed | DEFAULT; MUST-CONFIRM (by 3b): Q45, Q53 | 15, 17, 19 |
| D-263 | Worker poll 20 s (data) versus 5 s (config) | 5 s daytime, 60 s night. See D-140 | DEFAULT | 16, 18 |
| D-264 | cfg.geo.max_accuracy_m 150 (config) versus 100 (sync, data) | 100 default, bounds 30 to 300; the distance check does not subtract accuracy (cfg.geo.accuracy_tolerant false) | DEFAULT | 19, 21 |
| D-265 | Offline unlock 3 days (config) versus 14 (security) | cfg.auth.offline_unlock_max_days default 7, range 1 to 14; cfg.auth.offline_session_max_days is retired. See D-68 | DEFAULT | 19, 21 |
| D-266 | Redemption over balance: reject versus accept-and-flag | Accept and flag (points_overdrawn, outlet blocked from further redemption) when the balance in the device's bundle was sufficient at capture; reject insufficient_points when the device knew it was insufficient | DEFAULT | 15, 21 |
| D-267 | cfg.fraud.* editable by cfg.field | Moved to cfg.sec at class C2 or higher with floors and ceilings. See D-109 | DEFAULT | 19, 21 |
| D-268 | Web reads replica (scale) versus primary (data) | See D-128 | DEFAULT | 18 |
| D-269 | Gate and question numbering collisions between critics | Resolved in s5.4 (T-id ranges) and s5.5 (Q renumbering); doc 20 owns the final gate register | LOCKED-BY-SPEC | 20, 14 |

### 2.13 Register recommendations REJECTED or corrected, and why

| R-id | Register recommendation | Rejected or changed to | Reason |
| --- | --- | --- | --- |
| R-01 | G-man-001: "stepper step = pack size", "loose sticks may be typed", "price list changed since the seed", golden test "to the paisa" from displayed lines | Stepper and loose sticks kept as MUST-CONFIRM; the price-list claim dropped (MaxR-10S 8.00 matches no seed price type; the manual numbers are fixtures only); totals summed from unrounded lines (116.41 versus 116.42) | V-money: only the values 10, 20, 12, 25 are visible; arithmetic proves the rounding rule |
| R-02 | G-man-002 / C-02: ".. AMO 'ডিসকাউন্ট এবং অন্যান্য (-)' is probably discounts plus QC" and a single deduction subset | Store all components, print every non-zero one; max-QC is a taka cap with unknown basis | V-money: SR and AMO screens show different subsets; the reading is inference |
| R-03 | G-man-004: fixture "100 sticks of empty packs" | 10 empty packets entered; offer text means 100 sticks' worth | V-money: the entered value is 10 |
| R-04 | G-man-005: "percent equals stock percentage" and "eye-with-slash" icon | Percent meaning unknown (0 percent new, 100 percent on edit re-entry); the icon is a printer-with-slash | V-money zoom checks |
| R-05 | G-man-012 / C-45: "plan DQ-16 locks per memo versus the spec" | docs/06 already says outlet-level; the plan's narrowing is the defect | V-money |
| R-06 | G-man-016: severity major, phase P2, 50 GB per day photo risk | Minor, phase 3; camera appears only inside the Force Sale chain; no every-call photo is evidenced | V-outlets |
| R-07 | G-man-017: the photo "overwrites the stored outlet location with no approval" as an observed fact | Observed fact is only the sentence "location information will be updated"; the integrity decision (approval plus provisional location) stands | V-outlets |
| R-08 | G-man-021: "the SERVER creates the OTP; Manual wins on who creates it" and "list only SRs with an open request" | Server creation is an inference kept as DEFAULT; the panel lists every SR in the selected zone as the manual does (open-request filter is an improvement); 4-digit length kept | V-device |
| R-09 | G-man-022: "forced update gate", "SHA-256", "resume" as observed, and "version contradiction" | Observed: unknown-sources hop, percent download, installer, n/m processing; forced gate, SHA-256, resume and Wi-Fi-only are design additions; versions are consistent (splash shows the pre-update version) | V-device |
| R-10 | G-man-027 / C-48: TSO Settings "contradiction" | docs/08 already matches the manual; the conflict is only with plan drafts; Settings added as an improvement; no mobile app has change password (web only) | V-device |
| R-11 | G-man-029: "AMO shows English where the SR shows Bangla", "12:53 PM probably a test capture", clock source and check-in gate as findings | Both languages appear on the AMO; cause of 12:53 unknown; clock source, hold duration and check-in gate are unsupported | V-device |
| R-12 | G-man-030 / C-17: warn versus block "contradiction" | Same flow described twice; real conflict is inside the spec (docs/04 "dues cleared"); warn wins | V-day |
| R-13 | G-man-031: "sale row = memo count" | Quantity total or line count (1 outlet shows 68, 118, 25); measure unknown; "অফলাইন" is an authored string, not manual text | V-day |
| R-14 | G-man-033: "GEO and photo mandatory on new shop" as manual fact | Written as "must" only for info change; mandatory on new shop is an improvement | V-outlets |
| R-15 | G-man-034: AMO Save = Verify and Cancel = discard stated as the manual | Inferences; the web lifecycle is the evidence; implement discard-without-call, ask MQ-29 | V-outlets |
| R-16 | G-man-056: Outlet badge = sum of the three hub badges | Not supported (home reads 8 or 0 while hubs read 2+2+2); definition unknown | V-outlets |
| R-17 | G-man-059: "map provider undecided" | The manuals show Google Maps; parity baseline is Google, MapLibre is a fallback that must be argued | V-outlets |
| R-18 | G-man-060: bands red below 50, yellow 50 to 90, green 90 and above | Observed green at 84 and 89 percent contradicts it; green from 80, amber from 40, red below 40 (placeholder) | V-targets |
| R-19 | G-man-064: "17" as the constant, nine of eleven rows round | Ten of ten rows fit monthly x 15/17; Black Diamond is 218,700 not 218,768 | V-targets |
| R-20 | G-man-066 / D-03 of the lens: target routes canonical for all Submit % tiles | Logged-in basis is parity on TSO, AMO, web; target-route figure is renamed Day-completion % | V-day; docs/07, docs/08 already say logged-in |
| R-21 | G-man-067: calendar days as the single default for every till-date figure | Per-surface bases (TSO 26/30 ceil, AMO team about 25/30, AMO report 15/17, SR ADS 14 and 11) | V-targets |
| R-22 | G-man-068: "WMO is level 1; fix the lens default" and seven statuses | Only "WMO approval pending" is evidenced; levels and statuses stay configurable | V-targets |
| R-23 | G-man-053: TADS = target / selling days "does not fit 900 to 68" | TADS is 64 (Bengali 4 misread as 8); target / 14 fits both rows | V-targets |
| R-24 | G-man-070: "409 on a second attempt" as spec text | 409 is a plan proposal; docs/09 has only POST | V-day |
| R-25 | G-man-071: "no time gate" as evidence; read-only Sales Date as stated | Not evidenced (the 14:26:05 row is Not Done); default none by silence, marked unknown | V-day |
| R-26 | G-man-079 / C-19: address on the Select Outlets card | The second line is probably the cluster; contradiction retracted | V-outlets |
| R-27 | G-man-085: "Successful Call per sub-channel" | Successful Call is one route-level input; only class columns are per sub-channel | V-web |
| R-28 | G-man-099: "menus belong to different roles" and "the build changed" | Sidebar clipping in screenshots; union of items; no role or build explanation | V-web |
| R-29 | C-35: "13 reports are Excel-only" | 11 by the register's own list; two show Get Data | V-web |
| R-30 | G-man-102: "no error, offline or validation text exists" | Five guard or failure texts ARE printed (AMO out-of-range, sync-before-submit advisory, unpaid-dues confirm, TSO already-submitted alert, web back-date banner): catalogue them verbatim | V-web |
| R-31 | G-man-062: drop the mixed-unit grand total | Parity total optional behind a flag; per-category totals are the improvement | V-outlets |
| R-32 | G-man-018 / G-man-017: Update Base and Force Sale overwrite | See R-07; Update Base gets distance, move, mock and monthly caps | V-outlets |
| R-33 | Lens M-02 "SRs enter packs", lens-config final_submit_earliest_time 17:00, visit_plan_max_outlets 30, leave max 30 days, feedback categories Complaint and Bug | Replaced by sticks, none, none, none, Suggestion only | REG s2.5 |
| R-34 | Lens-sync PBKDF2 offline verifier, lens-security 6-digit hashed OTP, lens-config offline_session_max_days 3 | Argon2id, 4-digit reversible OTP, unlock default 7 days | D-68, D-103, D-265 |

### 2.14 Question registry (default answers and who confirms)

docs/13 Q1 to Q18 (every one has a default):

| Q | Topic | Decision | Status |
| --- | --- | --- | --- |
| Q1 | Mobile framework | D-01 Flutter, three flavours | DEFAULT |
| Q2 | Backend stack | D-02 Node/NestJS/PostgreSQL | DEFAULT |
| Q3 | Hosting specifics | D-04, D-06, D-07 Azure Container Apps, PostgreSQL Flexible, Blob, Actions | LOCKED-BY-SPEC (Azure, Actions), DEFAULT (rest) |
| Q4 | Auth and token lifetimes | D-101, D-103 | MUST-CONFIRM (by 0c) |
| Q5 | Memo numbering across cutover | D-35 option A new series | MUST-CONFIRM (by 1a) |
| Q6 | Geo-triggered volume suggestion | Hook only: outlet_suggestion field delivered empty, cfg.sale.suggested_qty_enabled false; formula reconstructed from migrated history or provided by the business | DEFAULT; MUST-CONFIRM (by 5c), blocks only the suggestion feature |
| Q7 | Geofence radius per territory and no-location behaviour | D-93, D-95 | MUST-CONFIRM (by 7c) |
| Q8 | Units | D-16, D-17, D-248 | LOCKED-BY-SPEC (cigarette, bidi); MUST-CONFIRM (by 2a) lighter, match |
| Q9 | BSR denominator | D-47 | MUST-CONFIRM (by 4b) |
| Q10 | Retention meaning | D-54 | MUST-CONFIRM (by 4b) |
| Q11 | After final submit | D-55, D-262 | MUST-CONFIRM (by 3b) |
| Q12 | Target split and approval levels | D-31 | MUST-CONFIRM (by 5c) |
| Q13 | Programmes live and promotion catalogue | D-33, D-41, D-192 | MUST-CONFIRM (promotion catalogue by 2a entry; programmes by 5a) |
| Q14 | TSO product scope (Digonto) | TSO dashboard tiles are config (cfg.tso.dashboard_tiles, cfg.tso.product_scope); default tobacco categories | DEFAULT; MUST-CONFIRM (by 3b) |
| Q15 | Microphone permission | D-115 camera only | DEFAULT; MUST-CONFIRM (by 2e) |
| Q16 | Roles beyond the apps | DMO, WM, WMO, Top are web-only; menus and permissions are data (cfg.web.menu_by_role); DMO approves TSO leave; WMO approves target sets | DEFAULT; MUST-CONFIRM (by 4a): menus (MQ-48) |
| Q17 | Wholesale and distributor flows | D-42; no bex or back-margin screens in scope | DEFAULT; MUST-CONFIRM (by 6a) |
| Q18 | Data dump contents | Memo-level rows, geo fixes with mock flag, photos with record ids, password hash algorithm (D-119), per-route daily data (D-154) requested in a dump-request letter issued in Phase 0 | MUST-CONFIRM (by 7a) |

"Things we deliberately changed from Apsis" (docs/13), each a decision for sign-off:

| Change | Decision | Status |
| --- | --- | --- |
| Scope resolved server-side | D-106 | LOCKED-BY-SPEC |
| Reads from aggregates | D-61, D-128, D-190 (web reads dw only) | LOCKED-BY-SPEC |
| One role-aware codebase | D-01 (three flavours of one codebase) | DEFAULT |
| Anti-spoofing as a supervisor-visible flag, optional hard block | D-96, D-109, D-110 | LOCKED-BY-SPEC |
| Battery and size budgets enforced | D-73 | LOCKED-BY-SPEC |

New questions: Q19 to Q22 (scale lens), Q23 to Q30 (config lens), Q31 to Q40 (quality lens), Q41 to Q50 (field critic) keep their numbers and are listed in doc 14 s4. The SRE critic's questions, which collided with Q41 to Q45, are renumbered Q51 (wave-1 size: 1,000 SRs or about 300 routes), Q52 (pre-bind day feasible), Q53 (auto-close of a zone-day by system at 23:00), Q54 (tolerance of counts rising again after DR), Q55 (L1 read-only Azure role). The manual register's 67 questions are renamed MQ-01 to MQ-67 (hyphen kept to distinguish them from docs/13 numbers); doc 14 s4 groups them: MQ-01..09 units and money (blocks 2a), MQ-10..19 day flow (1a to 3a), MQ-20..28 programmes (3a to 5a), MQ-29..32 outlets (3a), MQ-33..42 targets and KPIs (3a to 3b), MQ-43..49 web back office (4a to 4c), MQ-50..55 platform (0c to 2e), MQ-56..59 printed outputs (1a), MQ-60 strings (0c), MQ-61..64 TSO app (3b), MQ-65..66 SR app (2a), MQ-67 data quality (7a).


## 3. Phase structure (phases 0 to 7 with sub-milestones)

Writers reference sub-milestones by these codes only (0a, 1b, 2d, 7c and so on). Indicative weeks are ASSUMPTION (team of 8 to 10 engineers, streams: app, api, web, data, infra, QA; doc 14 s2.4 states the team shape). Phases P3 to P6 run as parallel streams once 2e exits; the sequential sum is about 45 weeks and the planned elapsed time to wave 1 is about 34 to 38 weeks (doc 14 owns the critical path). Gate families name the T-id ranges of s5.4; doc 20 s3 places every individual T-id.

Exit rule for every sub-milestone (D-141): a test, an evidence artefact under /docs/evidence/phase-N/ and a named verifier who is not the author, plus a demo a non-engineer can run (doc 20 s10).

### 3.1 Phase and sub-milestone table

| Code | Sub-milestone | One-line goal | Weeks | Gate families | MUST-CONFIRM decisions due at exit (from s2) |
| --- | --- | --- | --- | --- | --- |
| P0 | Foundations | Everything every later phase stands on exists and is tested; nothing sells yet | 3 | | |
| 0a | Tooling | Monorepo, CI/CD skeleton, IaC skeleton, testkit v1, rtm/gates/gaps files and rtm-check, device lab and reference phones, baseline-pack capture started | 1 | T-0-40..49 (T-0-49 baseline pack), 01..04 | D-156 (sponsor delegate and readiness owners) |
| 0b | Schema and contract | Schema v2 migrations M-01..M-45 and the /packages/contract v1 (Zod, enums, generators); cfg registry seeded; contract and migration tests | 1 | T-0-01..04, 60..64 | none (money, units, idempotency are LOCKED or DEFAULT) |
| 0c | Auth and scope | Login, device bind and OTP model, refresh, ScopeContext and RLS, scope-leak harness, localisation layer with Bengali fonts, the string and glossary catalogue started | 1 | T-0-70..76 | D-101, D-102, D-103 (token lifetimes, password policy, OTP form), D-142 (baseline pack), D-164 |
| P1 | Vertical slice | One SR, one sale, offline to sync to one dashboard tile, with reconciliation | 4 | | |
| 1a | Offline capture | Bundle load, route, outlet open, geo check on device, sale, memo number, print, kill-and-relaunch survival, all on the local outbox | 1.5 | T-1-20..24, T-1-35 (print), T-1-41 (parity of memo) | D-20 (lighter and match units), D-35 (memo number), D-76 (printer), D-158 (printed layout samples) |
| 1b | Sync and reconcile | Idempotent ingest, batch replay, poison-row isolation, outbox triggers T1..T8, flat wire contract, reconciliation (device count equals server count), property and fuzz tests | 1.5 | T-1-25..34, 51..52 | D-67 (SQLCipher) |
| 1c | Aggregate, tile, config | Dirty-key aggregation to one dw tile, the minimal admin console (radius, check-out time, min_version), reference-device budget baseline, load-model rebase | 1 | T-1-01..04, 60..69 (minimal), 35 (budgets baseline) | D-11 (Android floor), D-12 (reference devices), D-66 (binding model), D-245 (are all export sales app sales) |
| P2 | Full SR day | The complete SR selling day, including the promotion engine, QC, outlets, geo rails and day close, pilot-ready | 8 | | |
| 2a | Sale complete | Full memo model: promotion engine, DRP/slide, QC deduction, rounding, credit and dues, stock, price types, SKU Target and Achievement screen; golden memo fixtures | 2.5 | T-2-41 (memo parity), 36..39, 40..44 | D-16, D-17, D-18, D-19, D-32, D-33, D-34, D-38, D-58, D-166, D-194, D-248 |
| 2b | Corrections | Edit, supersede, void and reprint rules, dues allocation and receipts, stock return and cash deposit, attendance edge cases | 1 | T-2-21..24, T-2-43 | D-86, D-200, D-206 |
| 2c | Outlets and media | New outlet, close, info change with cluster and route, photo and location capture, SAS media queue, photo compression and upload on Wi-Fi, outlet request lifecycle on the SR side | 1.5 | T-2-25..30, 70..73 | none |
| 2d | Geo and rails | Geo gate with mock invariant, radio-environment capture, force sale, location-change rules, risk signals, config rails (bounds, two-person, canary, anomaly watch, kill switches), FCM urgent path | 1.5 | T-2-10..19, 60..69 | D-09 (FCM), D-91 (admin authority), D-94 (TSO radius edit), D-95 (no-location policy), D-110 (radio environment privacy) |
| 2e | Day close and pilot hardening | Attendance, check-out gate, Sales Submit offline, sales deposit, updater, stale-bundle policy, budgets at full scope, pilot readiness drill | 1.5 | T-2-31..35, 53..57, 47..49 | D-10 (distribution channel), D-13 (telemetry), D-70 (stale bundle), D-80 (OTP re-verify), D-115 (camera only), D-146 (who signs "same memo") |
| P3 | Supervisors | AMO and TSO apps and the final-submit loop that closes a zone-day | 5 | | |
| 3a | AMO | AMO flavour: zone-wide bundle, control and joint calls, Team Location, verification, dues, stock, Exceptions, cover assignment, SS designation | 3 | T-3-41, T-3-20..24 | D-08 (maps), D-37 (whose dues), D-43 (request lifecycle), D-51, D-52 (till-date bases, bands), D-85 (acting for), D-169, D-172, D-187, D-195 |
| 3b | TSO | TSO flavour: read-snapshot plus queued writes, dashboards, Final Submit with preview, OTP panel, leave, plans, tasks, feedback, Bikroy Joma and Login lists | 2 | T-3-42, T-3-25..28 | D-29, D-45, D-55 (route kind, Submit % basis, reopen), D-177, D-198, D-262 |
| P4 | Web | Dashboards and reports from stored data only, sync health, panels, PII controls, scale proof | 6 | | |
| 4a | Sync-health and dashboards | Sync-health page, Daily Tracking, Login/Submit Status, per-surface dashboards from dw only | 2 | T-4-41, 51..52 | D-28 (Saturday), D-46 (CPR edge cases), D-185, D-249 |
| 4b | Report set | The 37-page parity map: every docs/09 and manual report as a ReportQuery on dw, Excel and PDF export with sanitiser and watermark | 2 | T-4-42..46 | D-47, D-54, D-191 |
| 4c | Panels and PII | Web Entry, Final Submit with audited void, outlet approval panel, SR Device OTP panel, role x menu matrix, PII budgets and reveal log | 1 | T-4-70..76, 10..14 | D-40, D-97, D-108, D-114, D-182, D-207 |
| 4d | Scale proof | Full load tests S1..S10 on a prod-sized staging window, DR drill, restore drill with data verification | 1 | T-4-53..57 | none |
| P5 | Programmes | Astha, Diamond League, Superstar, free samples, remaining promotion groups, targets, tasks, leave | 5 | | |
| 5a | Astha and Diamond League | Astha tiers and gift choice, Diamond League ledger, expiry, redemption batches, Outlet Points, Astha Web Entry | 2 | T-5-41, T-5-10..12 | D-41, D-192 |
| 5b | Superstar, free samples, promo groups | Superstar enrolment and slabs, free-sample capture, remaining promotion groups | 1.5 | T-5-42 | none |
| 5c | Targets, revisions, tasks, leave | Target sets with variant level and approval chain, manual grid and Excel upload, revisions, Task Delegation, leave approval | 1.5 | T-5-43 | D-31, D-179 |
| P6 | Admin and master data | Every master entity editable with audit; the full config console and fleet control | 4 | | |
| 6a | CRUD | The 58 entities with audit, future-dating rules, back-date grants, bulk tools, wholesale marking | 1.5 | T-6-41 | D-42 |
| 6b | Config console and audit | Full config console (all admin pages, risk classes, approvals, reach view, scheduled values) and the audit viewer | 1.5 | T-6-60..69 | D-113 (audit retention) |
| 6c | Devices, OTP, releases | Device and OTP console, release console (min_version, blocked versions, waves, flags), retention and archival jobs | 1 | T-6-43, 51..57 | D-23 (statutory retention), D-132 (photo retention) |
| P7 | Migration, pilot, cutover | Move 8,500 reps with no lost selling day | 10+ | | |
| 7a | Shadow import | Apsis dump imported through the staged importer with control totals, quarantine and rollback; history visible in dw; credential plan | 2 | T-7-80..82, T-0-04 | D-119 (credentials in the dump), D-153 (quarantine threshold) |
| 7b | Pilot parallel run | 10 to 20 routes run both apps; daily comparison; pass rule of 10 trading days with zero sync loss (D-145) | 3 | T-7-83..87 | D-126 (pre-bind day), D-147 (wave plan), D-148 (rollback), D-152 (parallel print), D-154 (daily Apsis data) |
| 7c | Wave 1 | First production wave on a pre-bind day, war room, rollback triggers read after submit settle | 1.5 | T-7-88..89 | D-05 (region), D-63 (generation re-sync tolerance), D-93 (radius values), D-107 (PII legal), D-120 (location notice), D-127 (DR), D-138 (support visibility), D-149 (helpdesk) |
| 7d | Waves 2 to n | Territory, division, half, rest; each wave needs the previous stable for 3 trading days | 4+ | T-7-85..89 | none new |
| 7e | Decommission | Final Apsis delta imported, Apsis read-only or off, archive retained, the parallel infrastructure removed | 1 | T-7-89 | none new |

Entry conditions that cross phases (doc 14 s2.3 owns the graph; these are binding):

| Condition | Rule |
| --- | --- |
| 7b entry | 2a to 2e, 3a, 3b, 4a, 4c (Final Submit and Web Entry fallback), 6b minimal and the device OTP panel are exited; baseline pack (T-0-49) captured; pilot SR consent and incentive agreed (G-qa-16) |
| 7c entry | 7b passed; 4d exited; 6c exited; D-05, D-107, D-120, D-127, D-149 answered; helpdesk staffed; pre-bind day plan signed |
| Programme outlets | P5 exits before any route that has Astha, Diamond League or Superstar outlets is bound; default (ASSUMPTION): wave 1 excludes zones with programme outlets until 5a to 5c exit (MUST-CONFIRM by 5a through D-41 and D-192) |
| Promotion catalogue | Q13 catalogue is a 2a ENTRY condition (D-33, D-143); without it 2a cannot exit and the pilot cannot start |
| Mid-phase scope | Features moved earlier than docs/12 are: promotion engine, rounding, DRP and QC deductions (to 2a); supervisors split into 3a and 3b; web before programmes; pilot gates (s2.2 of doc 14) |

### 3.2 Differences from docs/12 (written once, doc 14 s2.2 expands)

| docs/12 | Here | Why | Decision |
| --- | --- | --- | --- |
| Promotion engine in Phase 5 | In 2a | Every pilot memo total depends on it; parity cannot be judged otherwise | D-33, D-143 |
| Phase 2 and Phase 4 each one unit | Split into 2a to 2e and 4a to 4d with demos | Untestable as single units | D-141 |
| Phase 7 pilot is one clean parallel day | 10 consecutive trading days, zero sync loss | One day proves nothing against intermittent loss | D-145 |
| Config console in Phase 6 | Minimal console in 1c, rails in 2d, full console 6b | Radius and min_version are launch-critical | D-90 |
| Idempotent upsert only by row uuid | Three layers plus server-generation re-sync | DR and retried batches | D-21, D-62, D-63 |
| Web after programmes | Web (P4) before programmes (P5) | Final Submit and sync-health gate the pilot | D-143 |

## 4. Canonical KPI formulas and the two Submit % names

Every document uses these names and formulas verbatim. Source decisions are in s2.3; SQL-level definitions are doc 16 s9. Day-level rollups key off the Dhaka business date. A percentage with a zero or null denominator is NULL and shown as a dash (D-28, D-50), never 0.

| KPI id | Canonical name (UI label in quotes) | Code (API and dw field) | Formula | Basis and notes | Decision |
| --- | --- | --- | --- | --- | --- |
| K-01 | Login % | login_pct | logged_in_routes / target_routes | target_routes = planned sr-kind routes on a working day; logged-in per the login event (first bundle download that Dhaka day, or an offline day_open flagged offline_start) | D-44, D-29, D-30 |
| K-02 | "Submit % (of logged-in)" | submit_pct_of_logged_in | sales_submitted_routes / logged_in_routes | PARITY: TSO "Bikroy Joma Status", AMO live tile, web "Login/Submit Status"; a route counts as submitted only after the settle rule (server totals at or above device counts, or 30 min timeout) | D-45, D-64 |
| K-03 | "Day-completion %" | day_completion_pct | sales_submitted_routes / target_routes | The docs/10 definition kept as secondary on Daily Tracking and sync-health; never labelled "Submit %" without the qualifier. cfg.kpi.submit_pct_denominator picks which one a tile captioned "Submit %" shows (default logged_in_routes); the API always returns both with their basis | D-45 |
| K-04 | CPR ("Strike rate") | cpr_pct | successful_calls / target_outlets | successful call = sr_call visit with at least one active memo of net_mtk > 0 or qty_base > 0; zero-sale and abandoned visits excluded | D-46, D-57 |
| K-05 | Target outlets (day) | target_outlets | active outlets on routes planned that day, plus visited unplanned outlets shown as unplanned_visits | plan fixed at bundle time; 0 on a non-working day | D-57, D-28 |
| K-06 | Memo count | memo_count | active memos with line_count > 0 | superseded, void and zero memos excluded; the superseding memo counts once | D-53 |
| K-07 | STD (API STT) | std_qty | sum of qty_base in the SKU's base unit | no cross-category total; values in net_mtk; Lighter report unit differs by surface | D-49 |
| K-08 | Net sales value | net_mtk | gross - offer_discount - drp_discount - qc_deduction | totals from unrounded line values, rounded once to the paisa | D-18, D-19 |
| K-09 | Geo-validation % | geo_valid_pct | server_geo_valid calls / (sr_call visits - abandoned) | force sales photo-valid and not in the numerator; mocked fix never valid; companions photo_valid %, mock %, suspicious %, geo_mismatch % | D-48 |
| K-10 | BSR | bsr_pct | memos containing the brand / total active memos | secondary "brand reach" = outlets that bought the brand / target outlets; leaderboard uses the primary | D-47 |
| K-11 | Achievement % | achievement_pct | achieved / target | card and bar: printed value capped at 100 on AMO and TSO; detail tables uncapped, 2 decimals; SR home card uncapped; target <= 0 shows a dash; remaining = max(target - achieved, 0) | D-50 |
| K-12 | Till-date target | tilldate_target | per-surface basis: TSO Target Status = ceil(item target x elapsed calendar days / days in month) summed (26/30); AMO Team Performance about 25/30; AMO Sales Summary Up To Now = monthly x 15/17; SR ADS = working-day split (14 elapsed, 11 remaining) | keys cfg.kpi.tilldate_basis.<surface> and cfg.kpi.tilldate_rounding.<surface>; one global basis is REJECTED (R-rows in s2.13) | D-51 |
| K-13 | ADS, TADS, PADS, RADS | ads, tads, pads, rads | ADS = achieved / elapsed selling days; TADS = target / 14; RADS = remaining / 11 (rounded); PADS undefined until a non-zero sample is captured | local computation from bundle target plus local sales | D-58 |
| K-14 | Visited / Non-visit / No-sale | visited, non_visit, no_sale | visited = opened, non-abandoned visits; non_visit = target_outlets - visited; no_sale = zero-sale calls | SR home strip | D-56 |
| K-15 | Final-submit status | final_submit_status | zones final-submitted / zones having target routes, with the list of remaining zones | once per zone per day; late batches flagged after_final_submit | D-55 |
| K-16 | Retention candidate | retention_candidate_pct | outlets that bought the category in M-1 and M / outlets that bought in M-1 | never labelled "retention" on a tile until Q10 is answered | D-54 |
| K-17 | User-level efficiency (SR Efficiency, GIGO, risk score) | sr_efficiency, gigo | computed on coalesce(acting_for_user_id, user_id) | route-level KPIs include every active memo or visit regardless of seller; AMO control-call sales counted as amo_successful_calls and never inflate K-04 | D-26 |
| K-18 | Overdue / dues ageing | dues_aged_* | outstanding due by memo date bucket (0-7, 8-30, 31-60, 61+ days; ASSUMPTION buckets, cfg.kpi.dues_buckets) from the dues allocation ledger | opening balances land in an "opening" bucket, not aged | D-37 |

Colour bands (D-52): two sets, not one. cfg.kpi.bands is the 4-band achievement set (>=100, 90 to 100, 80 to 90, <80) for KPI tables; cfg.kpi.bar_bands is the bar colour set on AMO and TSO cards (green from 80, amber from 40, red below 40; the 40 boundary is a placeholder pending one capture between 25 and 55 percent).

Names that must NOT appear in any document without the qualifier: bare "Submit %" (always "Submit % (of logged-in)" or "Day-completion %"), bare "retention", bare "STD total" across categories, bare "units" (say sticks, pieces, dozens, boxes).

## 5. Naming conventions and reserved id ranges

Rule of the section: a writer mints only ids inside the block reserved for its document. An id outside the block is a collision and the doc 14 author rejects it at merge.

### 5.1 Database and code naming

| Item | Convention |
| --- | --- |
| Schemas | app (transactions and reference), cfg (config), dw (analytics), stg (migration staging) (D-24) |
| Tables | singular snake_case nouns (memo, memo_line, route_day); join tables as a_b; dw tables prefixed dim_, fact_, agg_ (daily or month grain in the name: agg_daily_route, agg_month_zone_product), snap_, bridge_, v_ for views; stg tables prefixed stg_ |
| Keys | surrogate id bigint identity; device-originated rows also carry client_uuid uuid (v4) and are registered in app.ingest_registry; foreign keys named <entity>_id |
| Columns | money suffix _mtk (bigint milli-taka); quantities qty_entered, unit_entered, pack_factor, qty_base; timestamps suffix _at (timestamptz UTC); business_date date (Asia/Dhaka); booleans is_ or has_ prefix; percent columns suffix _pct (numeric, 0 to 100 scale); never an enum type for a business code, always a code table in cfg (G-cfg-10) |
| Standard column blocks | @PROV (client_uuid, device_id, app_version, captured_at, captured_elapsed_ms, boot_id, business_date, entry_source), @AUDIT (created_at, updated_at, updated_by), @SCD (valid_from, valid_to, is_current) as defined in L-data s3.2 |
| Migrations | /db/migrations/NNNN_<snake_name>.sql forward-only, expand then contract; migration ids M-nn in this file map to the file number (M-07 is 0007_*); never edited after shipping |
| API | REST, base path /v1; JSON snake_case; error envelope { code, message, details, request_id } with stable ERR_<AREA>_<NAME> codes; custom headers X-Config-Version, X-Server-Generation, X-Batch-Attempt, X-Pending-Rows, X-Device-Proof, X-App-Version, X-Device-Id (exact spelling) |
| Sync record types | one canonical enum owned by doc 17 and generated from /packages/contract; doc 16 table names follow the type names; no doc invents a second spelling |
| Config keys | cfg.<area>.<name> lower snake case, units in the name suffix (_s, _min, _m, _kb, _mtk, _pct, _days, _h) (s5.7) |
| Feature flags | cfg.flag.<kind>.<name> with kind release, ops, wave (D-99) |
| Metrics and logs | aron_ prefix metrics; pino JSON logs with request_id, user_id hash, device_id hash, business_date |
| Localisation | message keys <area>.<screen>.<element> in /packages/i18n with bn and en; no hardcoded strings (CLAUDE.md DoD) |
| File and doc names | docs/NN-kebab-title.md exactly as s1; DECISIONS.md at the repo root |

### 5.2 F-id pins and reserved blocks

Only the doc 15 writer mints F-ids. Other writers cite existing ids and, if a feature is missing, list it under "Open items" as "needs F-id" (doc 15 assigns from its block and the doc 14 merge adds it to s7). Current maxima: F-SR-050, F-AMO-036, F-TSO-020, F-WEB-047, F-ADM-037, F-SYS-046, F-API-038 (F-API-020b exists).

| Range | Pinned meaning | Decision or gap |
| --- | --- | --- |
| F-SR-051 | End-of-day stock return and reconciliation | G-feat-02 |
| F-SR-052 | Cash deposit and distributor settlement | G-feat-03 |
| F-SR-053 | Returns and damaged goods after QC | G-feat-05 |
| F-SR-054 | Sale History (SR and AMO share the logic) | G-man-013, D-206 |
| F-SR-055 | Outlet Points (expiring points, expiry date, as-of date) | G-man-040 |
| F-SR-056 | SKU-wise Target and Achievement (ADS, TADS, PADS, RADS) | G-man-053, D-58 |
| F-SR-057 | Visit outcome and skip record | D-38 |
| F-SR-058 | Memo void after print (event, reason, retailer ack) | D-86 |
| F-SR-059 | Field-side day exception capture | D-39 |
| F-SR-060 | Start-call confirmation prompt | D-78 |
| F-SR-061 | Price compliance capture | G-feat-01 |
| F-SR-062 | Free sample capture | G-feat-23 |
| F-SR-063 | Offline day start and stale-bundle banner | D-70, D-30 |
| F-SR-064 | Device-health line on Home | G-field-15 |
| F-SR-065 | Route picker for an SR with several routes | G-feat-40 |
| F-SR-066 | Memo reprint with duplicate marker | G-feat-04, G-field-20 |
| F-SR-067 to F-SR-099 | Reserved for doc 15 (manual-derived and critic-derived SR items) | |
| F-AMO-037 | Same-day cover assignment | D-85, G-field-06 |
| F-AMO-038 | Exceptions screen (fraud signals visible to the supervisor) | D-109 |
| F-AMO-039 | Supervisor day state (supervisor_day) | G-man-032, D-27 |
| F-AMO-040 | SS designation and per-user menu variants | G-man-055 |
| F-AMO-041 | Acting-for sale for a dead-phone SR | D-85 |
| F-AMO-042 | Distribution-house counter-confirmation proxy | G-field-01 |
| F-AMO-043 | AMO Survey screen | G-man-044 |
| F-AMO-044 to F-AMO-069 | Reserved for doc 15 | |
| F-TSO-021 | Final Submit preview and read endpoint | G-man-070 |
| F-TSO-022 | SR Device OTP panel (view only) | D-103 |
| F-TSO-023 | Temporary password reset and unlock | D-102 |
| F-TSO-024 | TSO Settings (language, version, update, PDA to Support, password) | G-man-027 |
| F-TSO-025 | Radius edit or propose | D-94 |
| F-TSO-026 | Final-submit delegation and auto-close | G-field-07, D-262 |
| F-TSO-027 to F-TSO-049 | Reserved for doc 15 | |
| F-WEB-048 to F-WEB-056 | Pinned by the manual register: 048 Astha Web Entry, 049 Loyalty (Diamond League) Report, 050 Web Entry, 051 web Final Submit, 052 web QC, 053 DS-RRS Report, 054 AMO Call Report (Supervisory Module), 055 DSS Report, 056 Route-wise Memo Report | G-man-046, 048, 085, 086, 089, 093, 098, 092, 094 |
| F-WEB-057 | Exceptions page (risk signals, dismissal review) | D-109 |
| F-WEB-058 | Dues ageing report | K-18 |
| F-WEB-059 | Distribution-house settlement view | G-field-01 |
| F-WEB-060 to F-WEB-089 | Reserved for doc 15 | |
| F-ADM-038 to F-ADM-055 | Config console pages P1 to P18 of doc 19 s5.2 (F-ADM-(037+n) is page Pn) | R6 |
| F-ADM-056 to F-ADM-079 | Reserved for doc 15 and doc 19 back-office tools (Web Entry rules, QC rules, bulk tools, back-date grants) | |
| F-SYS-047 | Server-generation re-sync | D-63 |
| F-SYS-048 | Poison-row isolation and skip-ahead | D-65 |
| F-SYS-049 | Trusted time anchor | D-20 |
| F-SYS-050 | Device telemetry headers and support visibility | D-138 |
| F-SYS-051 to F-SYS-089 | Reserved for doc 15 | |
| F-API-039 to F-API-046 | 039 final-submit preview, 040 GET /config/delta, 041 POST /config/ack, 042 GET /config/public, 043 POST /day/cover, 044 GET /day/exceptions, 045 POST /outlets/outlet-kind/bulk, 046 POST /support/ping | s1.2 |
| F-API-047 to F-API-079 | Reserved for doc 15 | |

Collision resolution on record: the manual deltas proposed F-SR-051 to F-SR-053 for items that lens-features had already assigned to stock return, cash deposit and returns/damaged goods. The lens-features meaning stays; manual-derived SR items start at F-SR-054. A document that finds F-SR-051 to 053 used for any other meaning is wrong.

### 5.3 D-id blocks

| Range | Owner | Use |
| --- | --- | --- |
| D-01 to D-269 | this file | canonical; amended only by the doc 14 author |
| D-300 to D-319 | doc 14 | master-plan decisions raised during writing (DECISIONS.md cross-cutting) |
| D-320 to D-349 | doc 15 | feature and rule decisions |
| D-350 to D-379 | doc 16 | data platform decisions |
| D-380 to D-409 | doc 17 | sync engine decisions |
| D-410 to D-429 | doc 18 | scale and Azure decisions |
| D-430 to D-449 | doc 19 | admin and config decisions |
| D-450 to D-469 | doc 20 | test and cutover decisions |
| D-470 to D-499 | doc 21 | security and privacy decisions |

Every new D-id states status (LOCKED-BY-SPEC, DEFAULT, MUST-CONFIRM by phase), why, and the documents that apply it; the doc 14 author copies it into DECISIONS.md with date and reason and adds it to s4 of doc 14 when MUST-CONFIRM.

### 5.4 T-id (gate) ranges

Form: T-<phase digit>-<nn>, the phase digit being the phase in which the gate must pass. The range, not the phase, tells the lens.

| Range | Owner lens or critic | Content |
| --- | --- | --- |
| 01 to 09 | data | schema, seed, idempotent aggregation, control totals (01..04 used; 05..09 free for doc 16) |
| 10 to 19 | fraud critic | geo spoofing, outlet drift, risk signals, PII budgets (10..14 used) |
| 20 to 39 | sync | outbox, convergence, kill tests, airplane mode, printer, memo numbers, budgets (20..35 used; 36..39 for doc 17 additions and doc 20 2a gates) |
| 40 to 49 | quality | test infrastructure, CI/CD, observability, parity, usability, telemetry (T-0-49 baseline pack) |
| 50 to 59 | scale and SRE | 51..57 scale; 50, 58, 59 SRE additions |
| 60 to 69 | config | registry, bounds, propagation, approvals, revert |
| 70 to 79 | security | scans, auth, scope leak, PII, pen test (70..76 used) |
| 80 to 89 | quality | pilot, cutover, support, decommission |
| 90 to 99 | field critic | field-reality gates |
| 100 to 109 | analyst critic | RENUMBERED: the analyst's T-x-90..93 become T-x-100..103 (collision with the field critic); 104..109 free for doc 16 and 20 |
| 120 to 139 | manual parity | register-derived gates (golden fixtures, strings, screens, reports) allocated by doc 20 |
| 140 to 199 | doc 20 | additions raised during writing |

Collision rule: a gate id is unique across the whole register; if two sources used one id, the lower-numbered source range keeps it and the other moves into the free block above, with the old id recorded in the gate's "aliases" cell.

### 5.5 Question ids

| Namespace | Meaning |
| --- | --- |
| Q1 to Q18 | docs/13 |
| Q19 to Q22 | scale lens; Q23 to Q30 config lens; Q31 to Q40 quality lens; Q41 to Q50 field critic |
| Q51 to Q55 | SRE critic (renumbered from its own Q41 to Q45, which collided with the field critic) |
| MQ-01 to MQ-67 | manual register questions (register's Q-01 to Q-67; hyphen kept so they never read as docs/13 numbers) |
| Q56 and up | new questions raised while writing; a writer writes "Q-NEW (doc N, short title)" and the doc 14 author assigns the next number |

### 5.6 Migration and table-range reservations

| Range | Content | Owner |
| --- | --- | --- |
| M-01 to M-45 | lens-data Phase 0 list (M-37..M-39 are reserved for partition function and agg_dirty; M-45 runtime config contract) | doc 16 |
| M-46 to M-51 | analyst app additions (supervisor assignment history, dues allocation, offer version set, visit outcome and app_version, outlet merge, printed due snapshot) | doc 16 |
| M-52 to M-59 | app additions raised by the field critic, SRE, fraud critic and manuals (day exception, visit outcome codes, memo void event, acting_for, risk_signal, config ack and wave membership) | doc 16 |
| M-60 to M-69 | dw base (dims, event facts, daily and month aggregates, dirty queue, run and reconcile tables) from lens-data s4 | doc 16 |
| M-70 to M-76 | analyst dw additions: fact_memo, dim_target and snapshots, agg_daily_user, price history facts, DQ flags, offer facts, outlet merge facts (RENUMBERED from the analyst's M-60 to M-66) | doc 16 |
| M-77 to M-99 | further dw | doc 16 |
| M-100 to M-129 | manual-derived schema additions (the register's S-nn blocks), mapped one to one by doc 16 | doc 16 |
| M-130 to M-149 | stg and importer | doc 16 |

DQ rule ids: DQ-01 to DQ-34 lens-data; DQ-35 to DQ-43 critics; DQ-44 to DQ-69 reserved for doc 16 (manual-derived). Fraud signals: FS-01 to FS-29 catalogue; FS-30 to FS-39 reserved for doc 21. Residual risks in doc 21 are RR-1.. (not R-1 to avoid the sponsor requirements R1 to R6 and the rejected-recommendation ids R-01 to R-34); doc 14 risks RK-01 to RK-25; open items OI-<doc>-nn.

### 5.7 Config keys: canonical areas and aliases to retire

Canonical key names are those of the manual register s2.5 where it names a key, then lens-config s1; D-92 makes this binding. Area prefixes and the owning section of doc 19 s3:

| Prefix | Content | Prefix | Content |
| --- | --- | --- | --- |
| cfg.geo | radius, accuracy, mock policy, no-location, update base, integrity weights | cfg.auth | tokens, OTP, passwords, binding, offline unlock, web session |
| cfg.sync | triggers, batch, retry, clock, back-date, settle, re-sync, reconcile types | cfg.bundle | bundle size, pre-gen, stale policy, delta |
| cfg.day | day cycle, check-out time, submit, final submit, exceptions | cfg.app | hold-to-confirm, tiles, labels, locale, history days |
| cfg.sale, cfg.memo, cfg.credit, cfg.stock, cfg.price, cfg.promo, cfg.drp, cfg.qc | selling rules | cfg.print | printer, pairing, template |
| cfg.media | photo size, quality, upload policy | cfg.release, cfg.ops, cfg.flag | versions, kill switches, pre-scale, flags, waves |
| cfg.kpi | bands, till-date bases, denominators, dues buckets | cfg.calendar | weekend days, holidays, overrides |
| cfg.target, cfg.astha, cfg.loyalty, cfg.superstar | programmes and targets | cfg.outlet, cfg.route, cfg.visit | outlet and route rules |
| cfg.tso, cfg.web, cfg.report, cfg.dashboard | role surfaces, menus, reports | cfg.survey, cfg.rubric, cfg.content, cfg.task, cfg.leave, cfg.feedback | content and lists |
| cfg.pii, cfg.sec (including cfg.sec.fraud.*) | PII, limits, fraud thresholds | cfg.api, cfg.agg, cfg.telemetry, cfg.retention, cfg.sla, cfg.support, cfg.sys | platform |
| cfg.map | provider, tiles, geocoding | cfg.i18n | digit script, locale, formats |

Aliases to retire (the right-hand name is the only one allowed after merge):

| Retire | Use |
| --- | --- |
| cfg.geo.max_speed_mps | cfg.geo.max_speed_kmh |
| cfg.geo.min_accuracy_m | cfg.geo.max_accuracy_m (default 100, D-264) |
| cfg.media.max_bytes | cfg.media.photo_max_kb |
| cfg.sync.retry_backoff | cfg.sync.retry_backoff_s |
| cfg.sync.max_clock_skew_s | cfg.sync.max_clock_skew_min |
| cfg.auth.pw_history, pw_min_len, pw_min_age_h | cfg.auth.password_history_depth, password_min_len, password_min_age_h |
| cfg.auth.offline_session_max_days | cfg.auth.offline_unlock_max_days (D-265) |
| cfg.loyalty.cash_per_point | cfg.loyalty.cash_rate_mtk_per_point |
| cfg.periphery.radius_options_m, cfg.tso.periphery_radius_options | cfg.tso.periphery_radius_options_m |
| cfg.app.hold_ms | cfg.app.hold_to_confirm_ms |
| cfg.i18n.numeral_system | cfg.i18n.digit_script |
| cfg.maps.provider | cfg.map.provider |
| cfg.app.reconcile_counters | cfg.sync.reconcile_types |
| cfg.print.pin_hint | cfg.print.pairing_pins |
| cfg.fraud.* | cfg.sec.fraud.* (D-267) |
| cfg.sync.debounce_s 10 | cfg.sync.debounce_s 5 (D-261) |

Every key row in doc 19 s3 has: key, kind (S setting, T time-shaped or scheduled, O operational switch), type, parity default, recommended value where different, bounds, scope levels, editor bundle, effect (B immediate on next bundle or request, S next session, R next release), risk class C0 to C3, risk if mis-set, owning D-id, gap id.

### 5.8 Per-document new-gap blocks

A gap a writer discovers while writing is minted as G-<doc number>-nn (G-15-01, G-16-01 and so on) in the block below; the doc 14 author copies it into s7 of doc 14 with an owner and phase. Existing lens and critic gap ids are never renumbered.

| Doc | Block | Doc | Block |
| --- | --- | --- | --- |
| 14 | G-14-01 to G-14-40 | 18 | G-18-01 to G-18-40 |
| 15 | G-15-01 to G-15-60 | 19 | G-19-01 to G-19-40 |
| 16 | G-16-01 to G-16-60 | 20 | G-20-01 to G-20-40 |
| 17 | G-17-01 to G-17-60 | 21 | G-21-01 to G-21-40 |

New gaps already minted by this file (from the data profile): G-data-33 to G-data-37, G-cfg-23, G-feat-68, G-feat-69, G-sync-21 (s6.2).

## 6. Load model inputs and data-profile findings P-01 to P-16

### 6.1 Canonical load-model inputs (docs 16 and 18 use exactly these; doc 18 s1 derives every rate)

Source: docs/22 (partial Apsis export, 37.3 M sales rows) re-based by the SRE critic; these replace the docs/01 and lens-scale figures of 1.2 lakh calls, 3 lakh visits and 5 lines per memo. "Measured" is the data profile; "design" is what we size for and test at (D-125, D-245, D-246).

| Input | Measured (docs/22) | Design value | Note |
| --- | --- | --- | --- |
| Fleet | 8,500 reps; 10 wings, 50 divisions, 291 territories, 1,051 zones, 11,336 routes | same, plus growth headroom | median 64 outlets per route, p99 112, max 214; 4 to 54 routes per zone; one SR covers several routes (D-257) |
| Successful calls per day | average 275,000 to 306,000; peak 346,772 (25 Jun) | 450,000 | CPR strike rate 84 to 90 percent by month under the all-zero assumption (D-249) |
| Visits opened per day | about 400,000 | 500,000 | includes all-zero outlet-days at 10 to 16 percent |
| SKU lines per call | 1.95 in July (1.26 in May); p99 4; max 40 | 2.5 average; memo supports 60 lines | cfg.sale.max_lines_per_memo = 60 |
| memo_line rows per day | about 700,000 | about 1.1 M | |
| Business rows per day | about 2.2 M | about 3.0 M (ASSUMPTION, doc 18 s1 derives) | rows per day grew 53 percent May to July |
| Rows per SR-day | about 280 (47 visits, 41 memos, 82 lines, QC, survey, DRP, stock, events) | 350 | catch-up of a full offline day is two batches of 200 rows (about 12 KB gz) |
| Evening worst case (all offline, 30-minute window) | about 2,600 rows/s peak, about 1,300 sustained, about 17,000 batches (9.4/s) | prove 8,000 rows/s (3x) on the fleet SKU or move to the next SKU | doc 18 s4, T-4-51..57 |
| Trickle batches per day (60 percent online) | about 240,000, peak about 17/s | same | |
| Aggregate row-writes per day if written per memo | about 24.5 M | not allowed: recompute by dirty key per (route, window) (D-61, D-129) | coalescing window is measured by T-4-54 |
| Photos per day | about 130,000, about 20 GB | 150,000 | 150 KB each (D-75); about 18 PUT/s in the evening Wi-Fi wave |
| Storage growth | about 650 MB per day, about 240 GB per year raw plus indexes | doc 18 s1 | SSD v2 has no autogrow: alert at 70 percent, first grow at month 12 (D-131) |
| Bundle per SR | 65 to 110 outlets per route-day, several routes | paged | AMO with 54 routes is about 3,500 outlets, 1.6 MB raw, 350 KB gz; cap 2 MB; paging from day one (G-sync-21) |
| Calendar | Friday off (one traded Friday, 22 May); Eid break 27 to 31 May; single holidays 13 and 19 June | calendar-aware baselines | D-28; "vs yesterday" alerts break on the Friday off-day |
| Login storm (steady morning) | n/a | about 130 req/s, 5 to 20 minutes | refresh-token exchange, no hash, jitter (cfg.sync.login_jitter_s) |
| First day of a wave | n/a | 1,150 new users in wave 1 and 7,350 new plus 1,150 veterans on a full-fleet day with no pre-bind day | password login with Argon2id (about 64 MiB each, bounded concurrency), bind, full bundle, 35 MB APK (about 257 GB on the full-fleet day); a correctness and support day in wave 1 and the real storm on the full-fleet day; removed by the pre-bind day (D-126) |

### 6.2 What each data-profile finding changes (P-01 to P-16; decision D = 244 + k)

| P | Finding in one line | Changes | Decision | Owner doc | Gap | Phase |
| --- | --- | --- | --- | --- | --- | --- |
| P-01 | About 350k successful calls a day, peak 346,772; the export may include sales not made in the apps | load model, aggregates, evidence of app share | D-245 | 18, 14 | G-data-33 (new), G-sre-05, G-data-37 (new) | 1c |
| P-02 | 1.95 lines per call and rising, max 40 | memo_line volume, print length, bundle size, 60-line memo | D-246 | 16, 17, 18 | G-feat-69 (new) | 2a |
| P-03 | Friday off, Eid break, single holidays | working-day calendar as data, calendar-aware baselines | D-247, D-28 | 16, 19 | G-feat-09 | 4a |
| P-04 | Volume is in sticks and pieces, exact pack multiples | resolves Q8 for cigarettes and bidi; lighter and match stay open | D-248 | 16 | G-data-04 | 0b |
| P-05 | 8.7 percent zero-volume lines; all-zero outlet-days | zero lines are not sales; all-zero outlet-day is a zero-sale call (ASSUMPTION) | D-249 | 16 | G-data-36 (new), G-field-03 | 4a |
| P-06 | 68 duplicate (date, outlet, SKU) keys | no design assumes one memo per outlet-day; multi-visit policy | D-250 | 16, 17 | G-feat-08, G-data-36 | 2a |
| P-07 | Outlet codes are text, with letters and stray symbols | importer normalisation, crosswalk, quarantine | D-251 | 16 | G-data-35 (new) | 7a |
| P-08 | 175,031 outlets in sales but not in the 1 October list | archived stub outlets; closure is a status | D-252 | 16 | G-data-34 (new) | 7a |
| P-09 | 34,454 outlets share 11,222 points; 1,093 without coordinates | location_confirmed flag; first visit routes through force-sale or Update Base | D-253, D-95 | 16, 19, 21 | G-cfg-23 (new), G-fraud-06 | 2d |
| P-10 | A 100 m geofence proves the market, not the shop | per-zone and per-outlet radius, density view, geo as one signal among several, PostGIS on the dump | D-254 | 19, 21, 16 | G-cfg-23 | 2d |
| P-11 | Created At is hour-precision 12-hour text without timezone | explicit parser, Dhaka assumed | D-255 | 16 | G-data-35 | 7a |
| P-12 | Phone and owner filled 100 percent; NID is placeholder 123 or blank; TIN and licence empty | PII columns nullable and classified; build nothing that depends on them | D-256, D-107 | 21, 16 | G-sec-04, G-data-23 | 4c |
| P-13 | Hierarchy matches spec; 11,336 routes; an SR covers several routes | day plan by visit days and assignment history, bundle paging | D-257 | 16, 17, 18 | G-sync-21 (new), G-feat-40 | 1c |
| P-14 | geo_class null 19 percent; nine sub-channels; clusters 99.7 percent Transit Hub | import mapping, nullable geo_class | D-258 | 16 | G-data-35 | 7a |
| P-15 | All 40 SKUs match the seed sku_code; sku_id 1 to 43 with gaps | importer SKU crosswalk key | D-259 | 16 | G-data-19 | 7a |
| P-16 | Wholesale and C&C buyers with 10,000-stick lines | soft quantity ceiling plus anomaly flag, not a hard cap | D-260, D-42 | 16, 15 | G-feat-68 (new), G-field-08 | 2a |

Test consequences: doc 20 s9 plants P-05 to P-16 as defects in the synthetic apsis-dump generator, and the load tests S1 to S10 are scripted from s6.1 (doc 18 s8).

## 7. Consolidated gap register

Sources merged: G-data (32), G-feat (67), G-scale (20), G-sync (20), G-cfg (22), G-sec (23), G-qa (24), G-analyst (16), G-field (23), G-fraud (26), G-sre (26) = 299 lens and critic gaps; the 104 manual-register entries G-man-001 to G-man-104; and 9 new gaps minted from the data profile (G-data-33 to G-data-37, G-cfg-23, G-feat-68, G-feat-69, G-sync-21). That is 412 ids. After merging overlaps there are 329 master gaps, 82 alias ids folded into a master, and 1 withdrawn id (G-feat-37). Every master has exactly one owner doc and one closing phase. Phase codes are the sub-milestones of s3.

Owner rules used (a writer who disagrees amends through doc 14, never locally): back-office write tools go to doc 19; missing features, rules and screens to doc 15 (including the localisation catalogue and glossary); schema, KPI and dw to doc 16; sync, device and app lifecycle (including maps, printing, memo numbering) to doc 17; Azure, scale and SRE to doc 18; auth, PII and fraud to doc 21; test, cutover, parity oracle and support to doc 20; cross-cutting business confirmations and wave calendar to doc 14.

### 7.1 Counts by owner document and severity (feeds doc 14 s7)

| Owner doc | Blocker | Major | Minor | Total |
| --- | --- | --- | --- | --- |
| 14 | 0 | 3 | 2 | 5 |
| 15 | 3 | 61 | 47 | 111 |
| 16 | 12 | 34 | 19 | 65 |
| 17 | 10 | 18 | 7 | 35 |
| 18 | 2 | 16 | 4 | 22 |
| 19 | 4 | 24 | 8 | 36 |
| 20 | 3 | 8 | 5 | 16 |
| 21 | 3 | 28 | 8 | 39 |
| all | 37 | 192 | 100 | 329 |

Counts by closing sub-milestone: 0a 9; 0b 13; 0c 12; 1a 9; 1b 15; 1c 23; 2a 22; 2b 13; 2c 12; 2d 20; 2e 29; 3a 34; 3b 24; 4a 12; 4b 12; 4c 14; 4d 4; 5a 11; 5b 2; 5c 5; 6a 9; 6b 3; 6c 2; 7a 8; 7b 7; 7c 5.

### 7.2 The register

Columns: master id (the surviving id), aliases merged into it (these ids are retired: any reference to an alias means the master), title, severity (the highest of the merged members; verification may lower it), owner doc (writes the closing text in its own gap table), phase (the sub-milestone by which the gap must be closed; it is a gate, not a start date), other docs touched, deciding D-id where one exists, and for register rows the verification result (C confirmed, P partly confirmed and corrected in s7.3, blank not verified). Rows are ordered blockers first, then major, then minor; inside a severity by phase then owner.

| Master id | Aliases merged | Title | Sev | Doc | Phase | Also | Decision | V |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| G-qa-01 | - | No parity oracle: no printed memos, screen recordings, string list or Apsis report exports are captured... | blocker | 20 | 0a | - | D-142 |  |
| G-data-01 | G-data-11 | No idempotency key; retried batch doubles rows (seed #2) | blocker | 16 | 0b | - | D-21 |  |
| G-data-02 | - | No capture-time route/assignment/price-list/config context; history re-writes itself when masters change (seed... | blocker | 16 | 0b | - | D-20 |  |
| G-data-03 | - | paisa cannot hold 3-decimal prices (seed #1) | blocker | 16 | 0b | - | D-15 |  |
| G-data-04 | G-man-001 | No unit; every volume KPI undefined (seed #8, Q8) | blocker | 16 | 0b | - | D-16, D-17 |  |
| G-data-05 | G-man-066 | Submit % two definitions (seed #7) | blocker | 16 | 0b | 15 | D-45 |  |
| G-cfg-02 | - | No runtime config store; ~240 parameters hard-coded in prose | blocker | 19 | 0b | - | D-87 |  |
| G-feat-11 | G-man-021 | Device OTP issuance surface and launch-day throughput | blocker | 21 | 0c | 15 | D-103 |  |
| G-data-08 | G-scale-18 | No human memo number; offline numbering undefined (Q5) | blocker | 17 | 1a | 18 | D-35 |  |
| G-sync-02 | G-man-014, G-feat-60 | The current printed memo layout (fields, order, Bangla/English mix, widths, totals rounding) is not captured... | blocker | 17 | 1a | 15 | D-142, D-158 |  |
| G-data-06 | - | No quarantine, conflict or registry table; rejected rows vanish | blocker | 16 | 1b | - | D-65 |  |
| G-fraud-02 | G-sec-12, G-cfg-20 | Clock-correction conflict: the offset-at-last-sync method is defeated by changing the clock after the last... | blocker | 17 | 1b | 19, 21 | D-20 |  |
| G-scale-01 | G-sync-05, G-sre-14 | No batch-level idempotency or response replay | blocker | 17 | 1b | 18 | D-62 |  |
| G-sre-01 | - | DR/PITR failover loses rows the devices hold as synced | blocker | 17 | 1b | - | D-63 |  |
| G-sre-02 | G-sync-09 | day_submit can precede the rows it closes → Submit % wrong at 17:00 → false fleet rollback trigger | blocker | 17 | 1b | - | D-64 |  |
| G-sre-03 | - | One poison row stalls a device forever | blocker | 17 | 1b | - | D-65 |  |
| G-analyst-01 | - | No memo-grain fact (dw.fact_memo) | blocker | 16 | 1c | - | - |  |
| G-data-07 | - | No dimensions, no event facts, no monthly/balance aggregates, no dirty-queue; "any dashboard anytime" not... | blocker | 16 | 1c | - | D-61, D-128 |  |
| G-scale-02 | - | Aggregation strategy unspecified; increments would create hot rows and double counts | blocker | 16 | 1c | - | - |  |
| G-qa-05 | G-sync-12, G-man-026 | Reference devices are assumptions; battery/data gates measured on the wrong class of phone prove nothing for... | blocker | 17 | 1c | 15 | D-12 |  |
| G-sync-01 | G-sec-02, G-feat-41 | Device binding model for shared phones is undefined: users per device, devices per user, what happens to user... | blocker | 17 | 1c | 15, 21 | D-66 |  |
| G-cfg-01 | - | Single admin role; no permission model for ~20 admins with different authority | blocker | 19 | 1c | - | D-91 |  |
| G-feat-13 | G-qa-02, G-man-004 | Offer/promotion rule engine undefined (~22 groups, DRP offers) | blocker | 15 | 2a | 20 | D-33 |  |
| G-man-002 | - | Memo total omits slide deduction, QC settlement amount and per-SKU max-QC | blocker | 16 | 2a | - | D-158 | P |
| G-sync-04 | G-feat-46 | Rounding rule from milli-taka prices to the printed paisa totals (per line or per memo; half-up or banker's)... | blocker | 16 | 2a | 15 | D-19 |  |
| G-feat-02 | G-field-21 | End-of-day stock return / reconciliation flow absent | blocker | 15 | 2b | - | D-300 range (doc 15) |  |
| G-cfg-04 | G-scale-15 | Blast radius / mis-set protection absent (radius 10 m or min_version typo locks the fleet) | blocker | 19 | 2d | 18 | D-88 |  |
| G-sync-03 | G-scale-05 | Behaviour when a new business day opens offline (stale bundle) is undefined; without a policy the SR either... | blocker | 17 | 2e | 18 | D-70 |  |
| G-scale-03 | - | Bundle generation path not designed for the morning storm | blocker | 18 | 2e | - | - |  |
| G-sre-04 | G-scale-06 | Wave first-morning storm not modelled: password + bind + full bundle + APK, no jitter; Argon2id memory ×... | blocker | 18 | 2e | - | D-126 |  |
| G-analyst-02 | - | Aggregates current-state only: no dim_target, no as-reported snapshot, no restatement log | blocker | 16 | 4a | - | - |  |
| G-man-086 | - | Web Final Submit with 'Delete Section Data': a destructive delete the spec forbids, colliding with idempotent... | blocker | 19 | 4c | - | D-40 | P |
| G-feat-20 | G-man-041 | Diamond League point earning rules absent | blocker | 15 | 5a | - | D-41 |  |
| G-qa-03 | - | No machine-readable Apsis daily data for the parallel run; docs/11 says "compare daily" but names no source or... | blocker | 20 | 7a | - | D-154 |  |
| G-sec-03 | G-feat-66 | Whether Apsis password hashes (algorithm, parameters) are in the dump decides between seamless login and a... | blocker | 21 | 7a | 15 | D-119 |  |
| G-qa-04 | - | No helpdesk function, hours, scripts or TSO toolkit defined for 8,500 reps on wave day | blocker | 20 | 7c | - | D-149 |  |
| G-sec-01 | - | Bangladesh data-protection and data-localisation obligations are unknown; no Azure region in Bangladesh; PII... | blocker | 21 | 7c | - | D-05, D-107 |  |
| G-qa-06 | - | No business verifier or readiness owners named; docs/12 DoDs have no sign-off | major | 14 | 0a | - | - |  |
| G-qa-07 | - | docs/12 Phase 2 and Phase 4 are too large to test as one unit; no sub-milestones or demos | major | 14 | 0a | - | - |  |
| G-scale-11 | - | Quotas not pre-requested; defaults unknown | major | 18 | 0a | - | - |  |
| G-sre-12 | - | Single hostname/cert/DNS zone; no cert-expiry monitoring; CA chain vs Android 7 unverified | major | 18 | 0a | - | - |  |
| G-qa-08 | - | No test-data strategy; the Apsis sample contains PII and cannot be used in CI; no synthetic generator | major | 20 | 0a | - | - |  |
| G-qa-10 | - | No CI mechanism for expand/contract compatibility (API N−1 vs schema N; app N−2 vs API N); the rule exists only... | major | 20 | 0a | - | - |  |
| G-sec-19 | G-fraud-16 | No supply-chain controls (action pinning, OIDC, image signing, SBOM, APK key separation) | major | 21 | 0a | - | - |  |
| G-cfg-10 | - | Reason codes (force sale, edit, QC fault, DRP kinds, task types, leave types, feedback categories) are Postgres... | major | 16 | 0b | - | - |  |
| G-data-13 | - | No entry_source/offline flag on memo; report impossible | major | 16 | 0b | - | - |  |
| G-data-24 | G-scale-08 | Unpartitioned transaction tables at 44 M visits/yr; UNIQUE(client_uuid) cannot be global once partitioned | major | 16 | 0b | 18 | - |  |
| G-field-10 | - | Bangla as data: Bengali digits in phone/qty/amount fields never normalised (phone_hash dedupe fails), no... | major | 16 | 0b | - | - |  |
| G-data-20 | - | No config tables; radius only per territory, no zone/outlet override, no effective dating, no audit, no ack | major | 19 | 0b | - | - |  |
| G-cfg-18 | - | Several defaults are business decisions the spec does not give (no-location policy, cutoff time, weekend days... | major | 14 | 0c | - | - |  |
| G-man-101 | - | No verbatim string catalogue: 251 message entries across the four manuals (SR 99, AMO 90, TSO 24, Web 38) | major | 15 | 0c | - | - | C |
| G-man-103 | - | Number, date, time and money formatting profile | major | 15 | 0c | - | - |  |
| G-data-16 | - | No password hash/history, OTP, refresh tokens | major | 16 | 0c | - | - |  |
| G-cfg-12 | - | A user_scope/role change does not reach an already-issued token (token carries scope) | major | 21 | 0c | - | - |  |
| G-feat-34 | G-sec-20, G-man-091 | Password reset / forgot / lockout flow absent | major | 21 | 0c | 15 | - |  |
| G-sec-05 | - | Token lifetimes, rotation, revocation, device proof and offline login were unspecified | major | 21 | 0c | - | - |  |
| G-sec-06 | - | No enforcement pattern or defence in depth for scope; a single unscoped query would reproduce the current... | major | 21 | 0c | - | - |  |
| G-sre-06 | - | Hourly access-token expiry wave; device-clock-driven refresh loops | major | 21 | 0c | - | - |  |
| G-man-102 | - | No error, offline, validation or failure text exists in any manual - author the set | major | 15 | 1a | - | - | P |
| G-man-006 | - | Sale commit point: Print is the commit; printing optional; QC and Print independent; draft persisted at Proceed | major | 17 | 1a | - | - |  |
| G-man-020 | - | Permission gating, offline re-login, version string and branding on login | major | 17 | 1a | - | - |  |
| G-man-023 | G-feat-43 | Printer pairing, connection and print-button rules | major | 17 | 1a | 15 | - |  |
| G-sre-10 | - | Edge 4xx/5xx without the API envelope misread as terminal (WAF 403 halts a device's sync) | major | 17 | 1b | - | - |  |
| G-scale-09 | - | Per-request hot writes: device.last_seen_at, route_log | major | 18 | 1b | - | - |  |
| G-scale-10 | - | No timeouts, pool sizes, concurrency limits or 429 semantics defined | major | 18 | 1b | - | - |  |
| G-fraud-03 | G-sync-13 | Local store and batch content are not tamper-evident; a lifted access token forges batches for 60 min... | major | 21 | 1b | 17 | D-67 |  |
| G-fraud-05 | - | Replay of a batch with regenerated client_uuids is not detected; the same-outlet policy's server rule is... | major | 21 | 1b | - | - |  |
| G-sec-07 | - | Proposed PBKDF2-HMAC-SHA256 at 100k iterations for the offline verifier is below the OWASP floor (600k) and... | major | 21 | 1b | - | - |  |
| G-data-10 | - | No entity for the route-day state machine; route_log cannot hold states | major | 16 | 1c | - | - |  |
| G-data-22 | - | Successful call, memo count under edits/zero-sale, target outlets, till-date target, login event undefined | major | 16 | 1c | - | - |  |
| G-data-33 | - | Export may contain sales not made in the apps (direct distributor billing); provenance of each imported sale... | major | 16 | 1c | - | D-245 |  |
| G-scale-07 | - | activity_log volume unbounded (~0.4 M rows/day) | major | 16 | 1c | - | - |  |
| G-sre-17 | - | Aggregation: dead items leave tiles stale with no alert; stale claims after a worker deploy; lag is a gauge | major | 16 | 1c | - | - |  |
| G-sync-07 | - | An offline day start on a cached bundle produces no login event → Login % undercounts exactly the reps with the... | major | 16 | 1c | - | - |  |
| G-sync-21 | - | Bundle size and paging for SRs with several routes a day (65 to 110 outlets per route-day) and AMOs with up to... | major | 17 | 1c | - | D-257 |  |
| G-sre-05 | - | Load model and S1–S9 predate docs/22 (memos ~3×, lines/memo 1.95, rows/SR-day ~280, 11,336 routes) | major | 18 | 1c | - | - |  |
| G-sre-08 | G-scale-19 | Nightly job chain has no orchestration, preconditions, resumability or date assertion; maintenance window... | major | 18 | 1c | - | - |  |
| G-sre-09 | - | route_day rows are created by the bundle pre-gen job | major | 18 | 1c | - | - |  |
| G-sre-15 | - | LISTEN cfg_changed does not work through PgBouncer transaction pooling | major | 18 | 1c | - | - |  |
| G-sre-19 | - | SLO measurability holes (§3.1) | major | 18 | 1c | - | - |  |
| G-cfg-03 | - | Who may edit the geofence radius, and at what scope, is unspecified | major | 19 | 1c | - | D-93, D-94 |  |
| G-cfg-05 | - | Mid-day config changes and late syncs: which value applies to a captured row is undefined | major | 19 | 1c | - | - |  |
| G-fraud-19 | - | On a shared, rooted phone user B can use user A's refresh token with the shared device key; the engine's need... | major | 21 | 1c | - | - |  |
| G-sec-11 | G-cfg-15, G-feat-50 | Audit is a client-asserted activity_log; no immutable server-side audit, no PII export log, no security event... | major | 21 | 1c | 15, 19 | - |  |
| G-feat-08 | - | Multiple visits to one outlet per day | major | 15 | 2a | - | - |  |
| G-feat-14 | - | Credit sale eligibility and limits | major | 15 | 2a | - | - |  |
| G-feat-40 | - | SR with several routes on one day (route picker, header) | major | 15 | 2a | - | - |  |
| G-feat-44 | - | Stock insufficiency validation at sale | major | 15 | 2a | - | - |  |
| G-field-03 | G-sync-08, G-feat-38, G-data-31 | No visit outcome / no-sale reason and no "skip" record: shut shop, owner absent, refused, stock sufficient... | major | 15 | 2a | 16, 17 | D-38 |  |
| G-field-08 | G-feat-27, G-feat-33 | Price-type resolution per outlet is undefined: C&C/wholesale buyers exist (P-16); sku_price has five types; the... | major | 15 | 2a | - | D-32 |  |
| G-man-003 | - | QC in the app: six fault types in two groups; picker, entry, summary and submit screens | major | 15 | 2a | - | - | C |
| G-man-005 | - | Sale card extras: three unlabelled indicators, pack badge, red eye-with-slash icon | major | 15 | 2a | - | - | P |
| G-man-007 | - | Stock screen: Issue vs Stock columns, category totals panel, Save/Print enabling, whose stock | major | 15 | 2a | - | - |  |
| G-man-008 | - | Start-call confirmation before AV/KV, survey and sale ('আপনি কি কল শুরু করতে চান?') | major | 15 | 2a | - | - |  |
| G-man-053 | - | SR SKU-wise Target and Achievement screen with ADS, TADS, PADS, RADS | major | 15 | 2a | - | - | P |
| G-analyst-06 | - | Price history / list price / reporting value absent from dw | major | 16 | 2a | - | - |  |
| G-analyst-08 | - | Offer scope and memo-level offers absent from dw | major | 16 | 2a | - | - |  |
| G-feat-52 | - | Price change mid-day: which price a memo uses | major | 16 | 2a | - | - |  |
| G-feat-03 | - | Cash deposit / distributor settlement absent | major | 15 | 2b | - | - |  |
| G-feat-05 | - | Returns / damaged goods path after QC undefined | major | 15 | 2b | - | - |  |
| G-feat-16 | G-field-04 | Edit after print: void/reprint semantics | major | 15 | 2b | - | D-86 |  |
| G-fraud-17 | G-sec-16 | The memo edit window - the main sales-manipulation lever - is self-contradictory across keys and undefined as a... | major | 15 | 2b | 21 | - |  |
| G-man-010 | - | Mark-as-paid settles the whole memo only; spec says full or partial | major | 15 | 2b | - | - | C |
| G-man-012 | - | Sale edit: reasons list, outlet-level QC lock, AMO's greyed Edit button | major | 15 | 2b | - | - | P |
| G-man-013 | - | Sale History (previous sales of an outlet by date) for SR and AMO; online-only in the current build | major | 15 | 2b | - | - | C |
| G-feat-45 | G-analyst-05, G-field-09 | Dues allocation, aging and adjustments | major | 16 | 2b | 15 | D-37 |  |
| G-qa-14 | - | No reconciliation report for ledgers (dues, loyalty) between transactions and balances; mismatches would... | major | 20 | 2b | - | - |  |
| G-feat-42 | - | Selling to a newly captured outlet before approval | major | 15 | 2c | - | - |  |
| G-man-033 | G-feat-61 | Outlet new / close / info-change forms select Cluster, not Route (SR and AMO) | major | 15 | 2c | - | - | C |
| G-data-18 | - | outlet_photo.outlet_id NOT NULL cannot store a photo for a not-yet-approved outlet | major | 16 | 2c | - | - |  |
| G-scale-20 | - | Photo linkage lifecycle undefined (record acked before photo exists) | major | 17 | 2c | - | - |  |
| G-sync-10 | - | Evidence photos (force sale, outlet location) on Wi-Fi-only policy may never upload for SIM-less phones or... | major | 17 | 2c | - | - |  |
| G-sre-13 | - | Blob ZRS makes evidence unreadable in a region outage; SAS issuance may fetch a user-delegation key per call | major | 18 | 2c | - | - |  |
| G-sec-13 | - | Multipart upload through the API with no path pinning, size check or content verification; photo evidence not... | major | 21 | 2c | - | - |  |
| G-feat-39 | - | Outlet without saved location; location-update approval path | major | 15 | 2d | - | - |  |
| G-data-09 | - | Single geo_validated; no integrity/plausibility storage (seed #4) | major | 16 | 2d | - | - |  |
| G-feat-35 | - | No notification mechanism to apps | major | 17 | 2d | - | - |  |
| G-sync-11 | - | Permission revoked mid-day (location/camera) has no defined path; force-sale reasons are a fixed enum | major | 17 | 2d | - | - |  |
| G-cfg-06 | - | Offline devices cannot learn of a scheduled change (e.g. check-out time) | major | 19 | 2d | - | - |  |
| G-cfg-08 | - | Urgent changes (kill switch, revert) have no path faster than the next device request | major | 19 | 2d | - | - |  |
| G-cfg-13 | - | Outlet-level radius override can blow up bundle size if delivered per outlet | major | 19 | 2d | - | - |  |
| G-cfg-16 | - | Operational switches with no expiry are a launch-day hazard (left on overnight) | major | 19 | 2d | - | - |  |
| G-cfg-23 | G-field-11 | Geofence density and calibration: 34,454 outlets on 11,222 identical points, 1,093 without coordinates; a 100 m... | major | 19 | 2d | - | D-93, D-254 |  |
| G-fraud-09 | G-fraud-10 | Scope owners can loosen controls within bounds (2 km radius) unobserved; the anomaly watch is one-sided... | major | 19 | 2d | 21 | D-88, D-267 |  |
| G-sre-07 | - | FCM push fan-out becomes a GET /config/delta storm | major | 19 | 2d | - | - |  |
| G-sre-22 | - | No change-freeze windows; bulk admin ops during storms; scope_version bulk bumps; snapshot regeneration... | major | 19 | 2d | - | - |  |
| G-fraud-01 | G-sync-18, G-fraud-25 | No radio-environment corroboration of GPS fixes: rooted/hooked mock, SDR spoofing and realistic joystick walks... | major | 21 | 2d | 17 | D-110 |  |
| G-fraud-06 | - | Outlet location drift: force-sale / manual-override / update-base moves have no distance rule; AMO may verify... | major | 21 | 2d | - | - |  |
| G-man-017 | - | Photo capture (Force Sale, Manual Override) overwrites the outlet location immediately | major | 21 | 2d | - | - | P |
| G-scale-04 | G-sec-15 | Per-IP rate limiting is harmful behind carrier-grade NAT | major | 21 | 2d | - | - |  |
| G-sec-08 | - | Fraud controls beyond GPS (fake sales, edits, ghost outlets, dues, loyalty, clock) are absent from the spec | major | 21 | 2d | - | - |  |
| G-feat-18 | - | Attendance edge cases (missed check-in, forgotten check-out, device clock) | major | 15 | 2e | - | - |  |
| G-field-02 | G-feat-10 | No field-side day exception: rain/flood, hartal, market closed (haat day), DH out of stock, breakdown... | major | 15 | 2e | - | D-39 |  |
| G-man-030 | - | Sales Deposit dues rule: the manuals say both 'warn' and 'block' | major | 15 | 2e | - | - | P |
| G-man-031 | - | Sales Deposit reconciliation: row set, what each count measures, online/offline/failure states | major | 15 | 2e | - | - | P |
| G-qa-15 | - | No Bangla localisation review process or reviewer; "Bangla-first" has no acceptance step | major | 15 | 2e | - | - |  |
| G-scale-16 | - | APK and thumbnail distribution on wave day via the API would saturate it | major | 17 | 2e | - | - |  |
| G-sre-11 | - | A non-compliant build's upload loop cannot be stopped (D-scale-9 forbids blocking upload) | major | 17 | 2e | - | - |  |
| G-sre-20 | - | App rollback with pending rows is data-loss-prone | major | 17 | 2e | - | - |  |
| G-sync-06 | G-feat-65 | Sales Submit and check-out are specified as online calls; a Wi-Fi-only SR cannot close the day in the field | major | 17 | 2e | 15 | - |  |
| G-sync-15 | G-man-022 | Updater must not install while a batch is in flight and must handle the per-ABI choice; no spec for rollback of... | major | 17 | 2e | 15 | D-79 |  |
| G-sync-16 | G-man-024 | Wipe with pending rows loses data | major | 17 | 2e | 15 | - |  |
| G-qa-12 | - | No error/crash reporting tool chosen; App Insights has no Flutter SDK; residency unknown | major | 18 | 2e | - | - |  |
| G-qa-13 | G-sre-18 | No device telemetry design; without it battery, pending rows and sync failures in the field are invisible, and... | major | 18 | 2e | - | - |  |
| G-scale-13 | - | Telemetry cost and PII exposure at fleet scale | major | 18 | 2e | - | - |  |
| G-cfg-22 | - | Wave membership and feature flags for the parallel pilot have no data model | major | 19 | 2e | - | - |  |
| G-feat-28 | G-sec-09 | Microphone / voice recording | major | 21 | 2e | - | - |  |
| G-fraud-04 | G-sec-21 | Repackaged or downgraded clients are indistinguishable: the in-app signature check is removable; attestation is... | major | 21 | 2e | - | D-105 |  |
| G-fraud-12 | - | Wave-day lockout denial-of-service against predictable usernames; refresh-based login does not protect... | major | 21 | 2e | - | - |  |
| G-sec-14 | - | No redaction mechanism specified; PDA-to-Support uploads a whole local DB of retailer phones to support with no... | major | 21 | 2e | - | - |  |
| G-feat-01 | G-data-12 | Price compliance capture has no screen, fields or rules | major | 15 | 3a | 16 | - |  |
| G-field-01 | - | Distribution house is not an actor: stock issue, stock return and cash hand-over are self-declared by the SR... | major | 15 | 3a | - | - |  |
| G-field-05 | - | Wrong-user capture on a shared phone and no acting_for: SR B sells under A's session; AMO sells for a... | major | 15 | 3a | - | D-85 |  |
| G-field-06 | G-feat-07 | Same-day cover cannot start: no AMO/TSO-app action creates a route_assignment(kind=cover); the substitute's... | major | 15 | 3a | - | D-85 |  |
| G-man-011 | - | Whose dues an AMO sees and settles | major | 15 | 3a | - | - |  |
| G-man-015 | - | AMO dashboard 'Sale' tile vs Control Call > Sale: one geo-gated path | major | 15 | 3a | - | - |  |
| G-man-018 | - | Update Base: no distance limits, no success message, no mock-GPS check | major | 15 | 3a | - | - |  |
| G-man-032 | - | AMO day state: the AMO owns no route, so no route_day | major | 15 | 3a | - | - |  |
| G-man-034 | - | Outlet request lifecycle: verify in app or web, reject only on web, approve on web; all three request types | major | 15 | 3a | - | - | P |
| G-man-051 | G-feat-56 | Joint Call 5-step rubric: items 4 and 5 are not in the manual | major | 15 | 3a | - | - |  |
| G-man-052 | - | Joint Call: which SR is assessed, default star rating, untouched save | major | 15 | 3a | - | - |  |
| G-man-055 | - | SS designation and per-user menu variants in the AMO app | major | 15 | 3a | - | - |  |
| G-man-058 | G-feat-36 | Team Location is 'live' in the manuals, last-synced fix in the spec (AMO and TSO) | major | 15 | 3a | - | - | C |
| G-analyst-03 | G-field-16 | No agg_daily_user; acting_user_id single-valued; visit kind filter undefined | major | 16 | 3a | 15 | - |  |
| G-analyst-04 | - | Supervisor (AMO/TSO/DMO) assignment has no history | major | 16 | 3a | - | - |  |
| G-man-067 | - | Till-date target is a calendar-day pro-rata; the plan defaults to working days | major | 16 | 3a | - | D-51 | P |
| G-man-059 | G-field-18 | Map and geocoding provider is undecided (AMO Team Location/Update Base, TSO My Team/Retailer, Attendance... | major | 17 | 3a | 15 | - | P |
| G-man-065 | - | AMO bundle scope: any route of the zone, offline | major | 17 | 3a | - | - |  |
| G-feat-17 | - | After final submit: reopen path and late syncs | major | 15 | 3b | - | - |  |
| G-feat-25 | G-man-074, G-man-075 | Leave approval by DMO has no UI; SR/AMO leave absent | major | 15 | 3b | - | - |  |
| G-field-07 | G-sre-23 | Final submit has no delegation and no auto-close: TSO on leave or offline at 19:00 leaves the zone open... | major | 15 | 3b | 18 | - |  |
| G-man-070 | - | Final Submit needs a read endpoint: the duplicate check fires at 'Get Sales Data', before Submit | major | 15 | 3b | - | - | C |
| G-man-071 | G-feat-64 | Final Submit rules: Sales Date, 'SR Not Set' routes, no confirm before an irreversible action, no time gate | major | 15 | 3b | - | - | P |
| G-man-072 | - | Login & Bikroy Joma lists: row unit, 'Not Uploaded' is a superset, Dep Name, route-kind suffix | major | 15 | 3b | - | - |  |
| G-man-073 | - | TSO dashboard sales tiles: day-target basis for the achievement ring, units and number format | major | 15 | 3b | - | - |  |
| G-data-17 | - | Leave, visit plan, feedback, final-submit route snapshot/reopen trail missing (Q11) | major | 16 | 3b | - | - |  |
| G-man-100 | - | Route kind (SR vs AMO), 'SR Not Set', target-route definition and who is assigned to a route | major | 16 | 3b | - | - | P |
| G-man-069 | - | TSO has no offline model; docs/04 defines only the SR bundle | major | 17 | 3b | - | - |  |
| G-fraud-08 | - | Supervisor takeover chain (password reset + OTP + bind by one TSO) is undetected unless the victim notices | major | 21 | 3b | - | - |  |
| G-feat-29 | - | Daily Tracking "take action" undefined | major | 15 | 4a | - | - |  |
| G-analyst-07 | - | DQ and plausibility flags not in dw beyond geo | major | 16 | 4a | - | - |  |
| G-feat-09 | G-data-30, G-analyst-10 | Holiday / non-selling-day calendar absent | major | 16 | 4a | - | D-28 |  |
| G-sre-16 | - | Web read path conflict; no response cache; sync-health has no SLO or degraded mode | major | 18 | 4a | - | - |  |
| G-fraud-07 | - | Fabricated cash sales, skimmed collections, under-reported issue and pocketed cash-back are invisible because... | major | 21 | 4a | - | - |  |
| G-feat-53 | - | Column sets of the 11 download-only reports unknown | major | 15 | 4b | - | - |  |
| G-man-092 | - | DSS Report (Sales Summary): the pre-Final-Submit check report is not in the spec or plan | major | 15 | 4b | - | - | C |
| G-man-093 | - | DS-RRS Report with a Print view that shows discount data before Final Submit | major | 15 | 4b | - | - | C |
| G-man-098 | G-feat-31 | Supervisory Module = AMO Call Report (contents were unknown in the spec) | major | 15 | 4b | - | - |  |
| G-data-21 | - | Free Sample, Discount (promotion groups), By-Route Geo Capture, Data Entry Log, Campaign Gift Redemption have... | major | 16 | 4b | - | - |  |
| G-man-095 | - | Report filter vocabulary and output behaviours are unspecified (every report depends on them) | major | 16 | 4b | - | - | P |
| G-fraud-14 | - | Spreadsheet formula injection in xlsx/CSV exports; ESC/POS control bytes and Unicode direction/format... | major | 21 | 4b | - | - |  |
| G-feat-26 | - | DMO / WM / Top Management views unspecified | major | 15 | 4c | - | - |  |
| G-data-23 | - | NID/TIN/phone unprotected in schema; no export log; no masked BI view | major | 16 | 4c | - | - |  |
| G-man-085 | G-feat-30 | Web Entry: route-day aggregate sales entry (Issue / Return / Sale / Memos / Successful Call per sub-channel)... | major | 19 | 4c | - | - | P |
| G-man-087 | - | Back-date cut-off for web data entry with a 'call support' override | major | 19 | 4c | - | - | C |
| G-man-089 | G-feat-32 | Web QC: market and warehouse entry, two QC reports (Excel and PDF), and a 10-reason fault taxonomy | major | 19 | 4c | 15 | - | C |
| G-man-099 | - | Role x menu x action matrix: the TSO web portal is wider and writes more than '37 pages, 13 menus' | major | 19 | 4c | - | - | P |
| G-feat-59 | G-man-039, G-man-062 | PII role × field matrix | major | 21 | 4c | 15 | D-108 |  |
| G-fraud-13 | - | Paginated list endpoints with personal columns have no row budget or masking default; exports have no volume... | major | 21 | 4c | - | - |  |
| G-sec-04 | - | Phone/owner name protection level (plaintext with grants vs encrypted) is undecided and depends on G-sec-01... | major | 21 | 4c | - | D-107, D-108 |  |
| G-sec-10 | - | Web roles (DMO/WM/Top/admin) have no MFA or SSO design; admin is a single undifferentiated role | major | 21 | 4c | - | - |  |
| G-qa-17 | G-sre-24 | Alerting has thresholds (lens-scale) but no routing, on-call rota, runbook linkage or hygiene rule | major | 18 | 4d | - | - |  |
| G-scale-12 | - | DR posture undefined (RTO/RPO, paired region, redeploy) | major | 18 | 4d | - | - |  |
| G-qa-09 | G-scale-17, G-cfg-17 | Load-test and prod-SKU staging windows have no budget approval; Azure Load Testing engine quota unknown | major | 20 | 4d | 18, 19 | - |  |
| G-feat-21 | G-man-047 | Astha gift choice surface ("TSO portal") undefined | major | 15 | 5a | - | - |  |
| G-man-040 | - | Outlet Points screen: expiring points, expiry date, as-of date | major | 15 | 5a | - | - | C |
| G-data-14 | - | Astha per-outlet targets, Superstar, gift assignment, gift catalog, earn rules, offers all missing | major | 16 | 5a | - | - |  |
| G-man-046 | - | Astha Web Entry: outlet x SKU quantity grid for Astha-channel outlets | major | 19 | 5a | - | - | C |
| G-feat-22 | - | Superstar enrolment/slab/criteria rules | major | 15 | 5b | - | - |  |
| G-feat-23 | - | Free sample capture screen missing | major | 15 | 5b | - | - |  |
| G-feat-24 | G-man-068 | Target split formula and approval levels | major | 15 | 5c | - | - |  |
| G-data-15 | - | target_revision stores no values; no target uniqueness; no version history; negative targets allowed | major | 16 | 5c | - | - |  |
| G-man-090 | - | Target entry: manual route-wise grid, Excel sample download and upload, stored file | major | 19 | 5c | - | - |  |
| G-feat-06 | - | SR transfer between routes mid-month | major | 15 | 6a | - | - |  |
| G-cfg-11 | - | Content the business must change without a release has no versioning path (survey questions, rubric, gift... | major | 19 | 6a | - | - |  |
| G-feat-12 | - | Survey questions, AV/KV and campaign content management absent | major | 19 | 6a | - | - |  |
| G-fraud-11 | G-fraud-21 | Retroactive master-data changes rewrite KPI history through the front door (back-dated prices, target edits... | major | 19 | 6a | 21 | - |  |
| G-man-036 | - | Retailer Wholesale Outlet: bulk marking flow is only a page name in the spec | major | 19 | 6a | - | - |  |
| G-cfg-07 | - | No delivery visibility: admin cannot see which devices run which config | major | 19 | 6b | - | - |  |
| G-feat-63 | - | SR onboarding / offboarding runbook features | major | 19 | 6c | - | - |  |
| G-analyst-09 | - | Migrated history: no provenance/fidelity; aggregate-only dump has no landing path | major | 16 | 7a | - | - |  |
| G-data-19 | G-sre-21 | No crosswalk/opening-balance/import-run tables; re-import not idempotent | major | 16 | 7a | 18 | - |  |
| G-data-34 | - | 175,031 sold-to outlets are absent from the 1 October retailer list: archived-stub rule, closure is a status... | major | 16 | 7a | - | D-252 |  |
| G-data-35 | - | Outlet code and timestamp normalisation: text codes, hour-precision 12-hour Created At, placeholder NID and... | major | 16 | 7a | - | D-251 |  |
| G-data-36 | - | Duplicate (date, outlet, SKU) keys with differing values and 8.7 percent zero-volume lines in the export... | major | 16 | 7a | - | D-249, D-250 |  |
| G-feat-67 | - | Old Apsis app cannot be set read-only by AKTCL | major | 20 | 7b | - | - |  |
| G-field-12 | - | Parallel-run pilot prints two memos: nothing defines print behaviour in parallel_run_mode, how the retailer... | major | 20 | 7b | - | - |  |
| G-qa-11 | - | Wave rollback leaves new-app data that Apsis never sees; no export format or process (links G-feat-67) | major | 20 | 7b | - | - |  |
| G-qa-16 | - | No pilot route selection criteria, consent or incentive for double entry; pilot SRs do twice the work for 2–3... | major | 20 | 7b | - | - |  |
| G-qa-20 | - | Tests have no injectable clock requirement; business-date, 17:00 gate and midnight rollover tests would depend... | minor | 20 | 0a | - | - |  |
| G-cfg-09 | - | Risk classification of keys is not in the spec | minor | 19 | 0b | - | - |  |
| G-cfg-21 | - | JSON Schema validation inside Postgres may be unavailable on Flexible Server | minor | 19 | 0b | - | - |  |
| G-fraud-18 | - | Enum and value mismatches with fraud effect: mock_policy enum/default, max_accuracy_m 100 vs 150, offline... | minor | 14 | 0c | - | - |  |
| G-man-104 | - | Glossary of terms used on screen (OHS, OOS, SOQ, POSM, STD/STT, CPR, BSR, DSS, DS-RRS, GIGO, WMO, PDA, FF... | minor | 15 | 0c | - | - |  |
| G-man-037 | - | Retailer / route label formats and mobile-number normalisation | minor | 15 | 1a | - | - |  |
| G-field-20 | - | Half-printed memo on a clone printer is followed by a "duplicate"-marked reprint | minor | 17 | 1a | - | - |  |
| G-qa-21 | - | Bluetooth printing cannot be tested in CI; only physical tests exist | minor | 20 | 1a | - | - |  |
| G-analyst-14 | - | One trusted timestamp; hour_of_day/business_date_server basis | minor | 16 | 1b | - | - |  |
| G-sre-26 | - | ingest_registry keyed by random UUID v4 is the hottest index at 2,600 inserts/s | minor | 16 | 1b | - | - |  |
| G-sync-14 | - | Debounce default 10 s vs 5 s | minor | 17 | 1b | - | - |  |
| G-data-37 | - | Growth and seasonality in the load model: rows per day +53 percent May to July, Eid break, Friday off (P-01... | minor | 18 | 1c | - | D-247, D-125 |  |
| G-feat-68 | - | Wholesale and C&C buyers: soft quantity ceiling and anomaly flag instead of a hard cap (P-16) | minor | 15 | 2a | - | D-260 |  |
| G-feat-69 | - | Longer memos: 1.95 lines per call on average, max 40, a memo must support 60 lines; print length and screen... | minor | 15 | 2a | - | D-246 |  |
| G-man-043 | - | AV, KV and survey are assigned per outlet and shown in a fixed order (AV, KV, survey, sale) | minor | 15 | 2a | - | - |  |
| G-man-054 | - | SR dashboard: hidden content below the KPI tiles, route label format, Summary tile has no manual page | minor | 15 | 2a | - | - |  |
| G-analyst-15 | - | Printed due snapshot not stored on memo | minor | 16 | 2a | - | - |  |
| G-feat-04 | - | Memo reprint rules undefined | minor | 15 | 2b | - | - |  |
| G-feat-15 | - | The 3 sale-edit reasons are unnamed | minor | 15 | 2b | - | - |  |
| G-man-009 | - | Credit-sale partial-payment dialog: validation, rounding and label | minor | 15 | 2b | - | - | C |
| G-man-016 | - | Is an outlet photo required on every call, or only on Force Sale? | minor | 15 | 2c | - | - | P |
| G-man-019 | - | Photo + GEO capture: requiredness and feedback (new shop, info change, verification) | minor | 15 | 2c | - | - |  |
| G-data-25 | - | ended_at, gps_retry_count, suggestion snapshot, force photo link missing | minor | 16 | 2c | - | - |  |
| G-sync-20 | - | No cap on local photo queue / image cache growth on 16-GB phones | minor | 17 | 2c | - | - |  |
| G-fraud-15 | - | SAS URL (sig=) patterns absent from the redaction list; camera-only capture for evidence photos implicit, not... | minor | 21 | 2c | - | - |  |
| G-field-13 | - | Cold GPS at the first outlet becomes a force sale and an FS-09 count | minor | 17 | 2d | - | - |  |
| G-fraud-26 | - | Showing fraud flags to the SR teaches evasion; the boundary between "warn the rep" and "tell the supervisor" is... | minor | 21 | 2d | - | - |  |
| G-man-029 | - | Attendance UX: address + Refresh, press-and-hold sheet, four states, check-in gate, clock source | minor | 15 | 2e | - | - | P |
| G-qa-19 | - | No accessibility / low-literacy UX check for the field apps (icon-only tiles, font scaling, colour-only KPI... | minor | 15 | 2e | - | - |  |
| G-data-27 | - | PDA-to-Support upload, in-app update manifest, tutorial list have no tables | minor | 16 | 2e | - | - |  |
| G-field-15 | - | Pre-login SR cannot be diagnosed; no device-health line on Home | minor | 17 | 2e | - | - |  |
| G-man-025 | - | PDA to Support (send data file): confirmation, progress, offline queue, file naming | minor | 17 | 2e | - | - |  |
| G-sync-17 | - | Purge rule conflicts with AMO "View previous sale data" and with reprints of yesterday's memo | minor | 17 | 2e | - | - |  |
| G-scale-14 | - | TLS trust / device clock on old Android untested | minor | 18 | 2e | - | - |  |
| G-feat-57 | - | Control-call / joint-call targets origin | minor | 15 | 3a | - | - |  |
| G-field-23 | - | On-the-spot verification during a joint call is impossible (request not yet on the server) | minor | 15 | 3a | - | - |  |
| G-man-035 | - | Verification lists and forms: fields, mandatory flags, option lists, what the AMO sees | minor | 15 | 3a | - | - |  |
| G-man-044 | - | AMO Survey screen is not documented | minor | 15 | 3a | - | - |  |
| G-man-050 | - | SR Performance Assessment: OOS must be a subset of distributed; POSM tri-state; return behaviour | minor | 15 | 3a | - | - |  |
| G-man-056 | - | AMO dashboard badge sits on Outlet only, not on Task Delegation | minor | 15 | 3a | - | - | C |
| G-man-057 | - | AMO dashboard header date, KPI tile definitions, collapse chevron | minor | 15 | 3a | - | - |  |
| G-man-061 | - | AMO Live Dashboard: explicit Filter press, and what 'মোট বিক্রয়' counts | minor | 15 | 3a | - | - |  |
| G-man-063 | - | AMO STD Memo Report: columns, date rules, scrolling | minor | 15 | 3a | - | - |  |
| G-analyst-12 | - | Outlet merge model; phone_hash_pepper_version | minor | 16 | 3a | - | - |  |
| G-data-26 | - | distribution_check shape repeats POSM per brand; no visit link; assessments lack rubric definitions | minor | 16 | 3a | - | - |  |
| G-feat-55 | - | Outlet code assignment rule; closure/info approvals on panel | minor | 16 | 3a | - | - |  |
| G-man-060 | - | Target/achievement display rules: card cap, detail uncapped, colour bands, remaining clamp, category drill-down | minor | 16 | 3a | - | D-50, D-52 | P |
| G-man-064 | - | AMO Sales Summary Up To Now: CPR definition and zero-target percentage | minor | 16 | 3a | - | - | P |
| G-fraud-20 | - | AMO dismissals of exceptions carry no consequence or review | minor | 21 | 3a | - | - |  |
| G-sec-18 | - | No duplicate-outlet detection at verification/approval | minor | 21 | 3a | - | - |  |
| G-feat-48 | - | Feedback triage sink | minor | 15 | 3b | - | - |  |
| G-feat-49 | - | Visit plan completion rule and geo gate for Visit Query | minor | 15 | 3b | - | - |  |
| G-man-027 | - | TSO app has no Settings (language, version, update, PDA to Support, password) | minor | 15 | 3b | - | - | P |
| G-man-028 | - | TSO UI is English-first; CLAUDE.md says Bangla-first | minor | 15 | 3b | - | - | C |
| G-man-076 | - | TSO dashboard charts: metric naming, drill-down glyph, empty charts, brand label | minor | 15 | 3b | - | - |  |
| G-man-077 | - | Final Submit pickers: five-level cascade, scope-bounded options, 'all zones' callout | minor | 15 | 3b | - | - |  |
| G-man-078 | - | TSO map screens: radius unit and default, centre, markers, density, empty states | minor | 15 | 3b | - | - |  |
| G-man-079 | - | Set Plan: outlet card shows address (not cluster), invented 30-outlet cap, repeat Set Plan | minor | 15 | 3b | - | - | P |
| G-man-080 | - | Visit plan lifecycle: Pending/Completed rule, Completed card, edit and delete | minor | 15 | 3b | - | - |  |
| G-man-081 | - | Visit Query: free-text answers, Bangla labels, delegate default No, outcome of 'No' | minor | 15 | 3b | - | - |  |
| G-man-082 | - | TSO Assign Task: assignee when 'SR Not Set', task-type list, due date | minor | 15 | 3b | - | - |  |
| G-man-083 | - | TSO Feedback: categories, the 'My Feedback' sub-menu, image rules | minor | 15 | 3b | - | - |  |
| G-man-084 | - | TSO app chrome: greeting, Home FAB, drawer subtitles, back button, pickers, date formats | minor | 15 | 3b | - | - |  |
| G-man-096 | - | Web dashboard: tiles, charts and date-range filter beyond the spec's list | minor | 15 | 4a | - | - |  |
| G-analyst-11 | - | fact_visit.outcome, app_version on facts, fact_config_change.is_revert_of | minor | 16 | 4a | - | - |  |
| G-analyst-16 | - | Login provenance (offline_start, stale bundle); device-day telemetry in dw inventory | minor | 16 | 4a | - | - |  |
| G-field-14 | - | Memo sequence gaps are not reported: a free detector of lost rows and "clear data" days | minor | 16 | 4a | - | - |  |
| G-field-19 | - | Dashboard date semantics: default date, "same time yesterday" comparator, non-working-day banner | minor | 16 | 4a | - | - |  |
| G-fraud-22 | - | No signal for deliberate offline days or stale-price selling | minor | 21 | 4a | - | - |  |
| G-man-094 | - | Route-wise reports: Memo Report missing; STD and BSR & CPR carry three names each | minor | 15 | 4b | - | - | P |
| G-man-097 | - | Data Entry Log and Final Submit Log: column semantics (MIN/MAX/COUNT, Done/Not Done) | minor | 15 | 4b | - | - |  |
| G-data-28 | - | Retention KPI (Q10), BSR denominator (Q9), TSO product scope (Q14) unresolved; candidate definitions shipped... | minor | 16 | 4b | - | - |  |
| G-feat-58 | - | Online/Offline Sales report definition | minor | 16 | 4b | - | - |  |
| G-man-038 | - | Master-data field differences: product status, role-visible prices, vendor strings, sort semantics | minor | 16 | 4b | - | - |  |
| G-data-29 | - | "business"/"additional detail" outlet sections, QC page, Supervisory Module, Daily-Tracking "take action"... | minor | 15 | 4c | - | - |  |
| G-feat-54 | - | Retailer web edit field lists; bypass of verification | minor | 15 | 4c | - | - |  |
| G-fraud-24 | - | Data Entry and support replays lack four-eyes thresholds, source tagging and share signals | minor | 19 | 4c | - | - |  |
| G-qa-18 | - | Restore drills are listed (DR) but without a data-verification step (row counts, audit chain) | minor | 18 | 4d | - | - |  |
| G-man-042 | - | Gift redemption: scope of the 199-point cash cap, full catalogue, stepper and dialog rules | minor | 15 | 5a | - | - | P |
| G-man-045 | - | Astha views: route vs outlet table shapes, month chips ignored by the memo target, empty state | minor | 15 | 5a | - | - | P |
| G-man-048 | - | Loyalty Program - Diamond League Report (outlet-wise points) is not a listed report | minor | 15 | 5a | - | - | C |
| G-analyst-13 | - | Programme tier attribution; loyalty reversal on supersede; programme freeze | minor | 16 | 5a | - | - |  |
| G-fraud-23 | - | Astha tier assigned at AMO verification with no approval or sales check → gifts to friendly outlets | minor | 21 | 5a | - | - |  |
| G-sec-17 | G-sync-19 | Points accepted from the client would allow fabrication; redemption balance race across devices unhandled | minor | 21 | 5a | 17 | D-266 |  |
| G-feat-19 | G-man-049 | Task lifecycle beyond pending/completed | minor | 15 | 5c | - | - |  |
| G-field-17 | - | Month-end readiness: targets for M+1, Astha quarter, program periods, route_day rows for the 1st; no alert, no... | minor | 19 | 5c | - | - |  |
| G-feat-51 | - | Sales-plan change effect on a device holding stock | minor | 15 | 6a | - | - |  |
| G-feat-62 | - | Outlet reopen / reactivation | minor | 15 | 6a | - | - |  |
| G-feat-47 | - | Tutorial / manual content management | minor | 19 | 6a | - | - |  |
| G-man-088 | - | Sales Plan is TSO-operated and carries zone contact fields and a Data Entry Date the schema lacks | minor | 19 | 6a | - | - |  |
| G-cfg-14 | - | Price-list / promo-set publication has no fleet-reach visibility | minor | 19 | 6b | - | - |  |
| G-cfg-19 | - | Dead/unused keys will accumulate | minor | 19 | 6b | - | - |  |
| G-data-32 | - | Statutory retention for sales records/photos in Bangladesh unknown | minor | 16 | 6c | - | - |  |
| G-field-22 | - | Opening-balance provenance is not visible at the shop on cutover week | minor | 15 | 7a | - | - |  |
| G-qa-23 | - | docs/12 Phase 7 "pilot route runs a clean parallel day" is one day; a single clean day proves little against... | minor | 20 | 7b | - | - |  |
| G-qa-24 | - | The retailer is never asked whether the memo is "the same"; parity is judged internally | minor | 20 | 7b | - | - |  |
| G-sec-23 | - | During the parallel run the new app also holds Apsis-migrated PII on pilot devices; coexistence with the old... | minor | 20 | 7b | - | - |  |
| G-qa-22 | - | Wave scheduling has no blackout rules (month end, Eid, Friday off-day) although docs/22 P-03 shows the calendar... | minor | 14 | 7c | - | - |  |
| G-sre-25 | - | Pre-scale schedule (07:30/16:45) is later than the real login window; web not pre-scaled | minor | 18 | 7c | - | - |  |
| G-sec-22 | - | Employee location monitoring has no notice/consent or retention rule | minor | 21 | 7c | - | - |  |

Withdrawn: G-feat-37 (withdrawn: a reserved id, never a gap; the login event is decided by D-30).

### 7.3 Register entries corrected by verification (these statements replace the register text)

Everything not listed here was CONFIRMED by the verification files, or was not verified and keeps the register statement. A writer who reads the register must read this table first.

| Register id | Corrected statement |
| --- | --- |
| G-man-001 | Cigarette and bidi: entered and stored in sticks (proven, stock badge 6,500 to 650). Lighter 1 = 1 piece at 12.50. Match unit conflicts inside the SR manual itself (12 pieces p34 versus 1 dozen p35): MUST-CONFIRM. "Price list changed since the seed" and "stepper = pack size" are unproven. Price per dozen is a rational, never 2.33 per piece. |
| G-man-002 | Net = gross - offer discount - DRP (slide) discount - QC settlement; quantity totals unchanged. Max-QC is a taka cap (594.50, basis unknown). SR and AMO screens show different deduction subsets, so store every component and print the non-zero ones. A zero-sale plus QC memo can go negative: needs cfg.memo.allow_negative_net. The printed paper layout is never shown. |
| G-man-004 | The sample entered 10 empty packets (not 100); the offer text means 100 sticks worth, valid Nov to Dec 2025. Automatic discounts exist (home card total discount 437.50, DRP discount 0.00 shown separately), so C-10 is reopened. Footer net of slide is inferred. |
| G-man-005 | The red slashed icon is the printer status, not an eye; the percent shown is not a stock percent; keep three read-only unlabelled slots; the pack badge is derived. |
| G-man-012 | docs/06 already states the outlet-level QC lock; only the plan (F-SR-033, DQ-16) narrowed it to per memo. The plan is the document to fix. The AMO Edit button is grey on every screenshot. |
| G-man-016 | Camera is used only inside the Force Sale chain; the in-range path is unobserved; the 50 GB per day claim is unsupported. Severity lowered to minor. |
| G-man-017 | The manual says "location information updated" on photo capture; it does not prove master coordinates are overwritten and shows no approval UI; docs/05 and docs/07 disagree internally. Constraint wins: location_change_request plus provisional device fix; immediate update only for a missing or placeholder location (D-111, D-95). |
| G-man-021 | Four digits, a view-only TSO panel and a re-ask after a new version are printed; "the server creates the code" is inference (docs say "provided by the TSO"). The TSO must read the code, so it is stored encrypted and reversible, not hashed. Sample create times suggest a long-lived per-user code. TTL unknown. |
| G-man-022 | The forced update gate is not observed (the splash "must" is a mandatory local migration); SHA-256, resume and Wi-Fi-only are design additions. APK files are 74 to 80 MB and installed size 92 to 101 MB (docs/04 about 90 MB is installed size). Three separate packages today. Side-load versus Play is unasked: new question. |
| G-man-027 | docs/08 already lists the 7 Settings entries the manual shows; only plan F-SYS-019 to 021 conflict. No mobile change-password exists in any app (web Credentials only). |
| G-man-029 | The AMO shows both the Bangla and English done-state; the 12:53 PM sample belongs to another account (cause unknown, not a test capture); hold duration, clock source and check-in gate are unsupported. |
| G-man-030 | A Yes/No warning dialog with the count of retailers on the route with due above 0 (the p73 sentence is the same flow). The real contradiction is inside the spec (docs/04 "dues cleared" versus docs/06 and docs/07 "warns"). Record submitted_with_dues. |
| G-man-031 | The "sale" row is a quantity total or a line count, not a memo count (one outlet shows 68, 118 and 25): the measure is MUST-CONFIRM. The server column comes only from the last server_totals. |
| G-man-034 | Web lifecycle Pending-Verify, Verified, Reject or Approve (Approve confirm text evidenced, the rest authored); the web panel can Verify too; AMO Save = Verify and Cancel = discard are inference: implement Cancel as discard without a server call. |
| G-man-039 | The columns exist but the cells are blank and the export content is unseen; the PII baseline is therefore unproven (docs/22 P-12 shows phone and owner name filled, NID placeholder). |
| G-man-042 | One confirm redeems a basket of lines (redemption batch plus lines); the catalogue shows at least 6 items, the rest are not in the PDF; the scope of the 199-point cash cap is unknown. |
| G-man-045 | Astha view shapes differ between SR and AMO (outlet view); build one component with role switches; the memo-target behaviour of the month chips is unknown. |
| G-man-053 | TADS = target / 14 (500 gives 36; 900 gives 64, the register read 68) and RADS = remaining / 11; rows are brand-labelled; the 2719 percent value is untrustworthy (home sample later shows 1 percent). |
| G-man-059 | The current provider is Google Maps (callouts p33, p65); TSO My Teams is 3D, the others flat. Default Google Maps SDK parity with the key in Key Vault and restrictions, MapLibre fallback, 2D lite, reverse geocoding on the server at sync (D-08). |
| G-man-060 | Observed bands: green from about 80 (84 and 89 green), amber 56 to 70, red 23 and below; proposed green >= 80, amber >= 40, red < 40 (placeholder, confirm). Card cap 100 on AMO and TSO, detail uncapped, SR card uncapped; remove the global 1000 cap. |
| G-man-064 | Printed target = monthly x 15/17 (10 of 10 rows are integers; Marise 100,000 gives 88,235.29); CPR is a percent; a zero target prints 0 percent in the manual and a dash here (DELIBERATE CHANGE, D-50). |
| G-man-066 | docs/07 and docs/08 already say "of logged-in"; the clash is with the docs/10 web column and the plan; the web basis is unprovable (all submit 0 percent); captions differ by surface; Login % = logins / target routes confirmed (1/4 = 25 percent). Parity: logged-in basis on TSO, AMO, web; target-route basis kept as the renamed secondary (D-45). |
| G-man-067 | There is no single till-date basis: TSO 26/30 ceil per item, AMO team about 25/30 (not exact), AMO report monthly x 15/17, SR ADS 14 elapsed plus 11 remaining. Per-surface keys cfg.kpi.tilldate_basis.<surface> (D-51). |
| G-man-068 | Variant product level and a target set header (name, product type, target type, start, end, approval status "WMO approval pending") are evidenced; WMO as level 1 and the other statuses are not; docs/08 already mentions variant items. |
| G-man-071 | "SR Not Set" routes do not block Final Submit and no confirm appears; the read-only date is inferred; a time gate is NOT evidenced (14:26:05 is a Not Done row): default none, marked unknown. All SR Not Set rows were AMO routes. |
| G-man-079 | The place line on the Set Plan card is probably the cluster (C-19 retracted); the cap of 30 outlets is invented. |
| G-man-085 | Successful Call is a route-level number, not per sub-channel. |
| G-man-086 | Delete Section Data appears only on the "exist" row, with no confirm, scope unknown; docs/09 lists Data Entry Log and Final Submit Log by name. The blocker stays as a design risk because the control destroys data (D-40: audited void instead). |
| G-man-094 | Memo Report is missing and STD and BSR & CPR carry several names each: partly confirmed; one name per report with display aliases (I-34). |
| G-man-095 | The Excel-only report count is 11 not 13; about 8 on-screen grids give column sets; the rest need captures. |
| G-man-099 | 15 items and 41 pages confirmed; the "different roles" and "build changed" explanations are unsupported (the sidebar is clipped). |
| G-man-100 | No route-kind field is printed; AMO users also appear on SR routes; SS is undefined. |
| G-man-102 | Five guard texts ARE printed in the manuals (parity strings); only the offline, validation and failure texts must be authored. |

### 7.4 Index of the 104 manual-register entries (G-man-001 to G-man-104): each has exactly one owner and phase

A row whose master id differs from its own id was merged (its owner and phase are the master's). Phase is the closing sub-milestone.

| Entry | Master | Doc | Phase | Sev | V | Entry | Master | Doc | Phase | Sev | V |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 001 | G-data-04 | 16 | 0b | blo | P | 053 | = | 15 | 2a | maj | P |
| 002 | = | 16 | 2a | blo | P | 054 | = | 15 | 2a | min |  |
| 003 | = | 15 | 2a | maj | C | 055 | = | 15 | 3a | maj |  |
| 004 | G-feat-13 | 15 | 2a | blo | P | 056 | = | 15 | 3a | min | C |
| 005 | = | 15 | 2a | maj | P | 057 | = | 15 | 3a | min |  |
| 006 | = | 17 | 1a | maj |  | 058 | = | 15 | 3a | maj | C |
| 007 | = | 15 | 2a | maj |  | 059 | = | 17 | 3a | maj | P |
| 008 | = | 15 | 2a | maj |  | 060 | = | 16 | 3a | min | P |
| 009 | = | 15 | 2b | min | C | 061 | = | 15 | 3a | min |  |
| 010 | = | 15 | 2b | maj | C | 062 | G-feat-59 | 21 | 4c | maj | C |
| 011 | = | 15 | 3a | maj |  | 063 | = | 15 | 3a | min |  |
| 012 | = | 15 | 2b | maj | P | 064 | = | 16 | 3a | min | P |
| 013 | = | 15 | 2b | maj | C | 065 | = | 17 | 3a | maj |  |
| 014 | G-sync-02 | 17 | 1a | blo |  | 066 | G-data-05 | 16 | 0b | blo | P |
| 015 | = | 15 | 3a | maj |  | 067 | = | 16 | 3a | maj | P |
| 016 | = | 15 | 2c | min | P | 068 | G-feat-24 | 15 | 5c | maj | P |
| 017 | = | 21 | 2d | maj | P | 069 | = | 17 | 3b | maj |  |
| 018 | = | 15 | 3a | maj |  | 070 | = | 15 | 3b | maj | C |
| 019 | = | 15 | 2c | min |  | 071 | = | 15 | 3b | maj | P |
| 020 | = | 17 | 1a | maj |  | 072 | = | 15 | 3b | maj |  |
| 021 | G-feat-11 | 21 | 0c | blo | P | 073 | = | 15 | 3b | maj |  |
| 022 | G-sync-15 | 17 | 2e | maj | P | 074 | G-feat-25 | 15 | 3b | maj |  |
| 023 | = | 17 | 1a | maj |  | 075 | G-feat-25 | 15 | 3b | maj |  |
| 024 | G-sync-16 | 17 | 2e | maj | C | 076 | = | 15 | 3b | min |  |
| 025 | = | 17 | 2e | min |  | 077 | = | 15 | 3b | min |  |
| 026 | G-qa-05 | 17 | 1c | blo |  | 078 | = | 15 | 3b | min |  |
| 027 | = | 15 | 3b | min | P | 079 | = | 15 | 3b | min | P |
| 028 | = | 15 | 3b | min | C | 080 | = | 15 | 3b | min |  |
| 029 | = | 15 | 2e | min | P | 081 | = | 15 | 3b | min |  |
| 030 | = | 15 | 2e | maj | P | 082 | = | 15 | 3b | min |  |
| 031 | = | 15 | 2e | maj | P | 083 | = | 15 | 3b | min |  |
| 032 | = | 15 | 3a | maj |  | 084 | = | 15 | 3b | min |  |
| 033 | = | 15 | 2c | maj | C | 085 | = | 19 | 4c | maj | P |
| 034 | = | 15 | 3a | maj | P | 086 | = | 19 | 4c | blo | P |
| 035 | = | 15 | 3a | min |  | 087 | = | 19 | 4c | maj | C |
| 036 | = | 19 | 6a | maj |  | 088 | = | 19 | 6a | min |  |
| 037 | = | 15 | 1a | min |  | 089 | = | 19 | 4c | maj | C |
| 038 | = | 16 | 4b | min |  | 090 | = | 19 | 5c | maj |  |
| 039 | G-feat-59 | 21 | 4c | maj | P | 091 | G-feat-34 | 21 | 0c | maj |  |
| 040 | = | 15 | 5a | maj | C | 092 | = | 15 | 4b | maj | C |
| 041 | G-feat-20 | 15 | 5a | blo |  | 093 | = | 15 | 4b | maj | C |
| 042 | = | 15 | 5a | min | P | 094 | = | 15 | 4b | min | P |
| 043 | = | 15 | 2a | min |  | 095 | = | 16 | 4b | maj | P |
| 044 | = | 15 | 3a | min |  | 096 | = | 15 | 4a | min |  |
| 045 | = | 15 | 5a | min | P | 097 | = | 15 | 4b | min |  |
| 046 | = | 19 | 5a | maj | C | 098 | = | 15 | 4b | maj |  |
| 047 | G-feat-21 | 15 | 5a | maj |  | 099 | = | 19 | 4c | maj | P |
| 048 | = | 15 | 5a | min | C | 100 | = | 16 | 3b | maj | P |
| 049 | G-feat-19 | 15 | 5c | min |  | 101 | = | 15 | 0c | maj | C |
| 050 | = | 15 | 3a | min |  | 102 | = | 15 | 1a | maj | P |
| 051 | = | 15 | 3a | maj |  | 103 | = | 15 | 0c | maj |  |
| 052 | = | 15 | 3a | maj |  | 104 | = | 15 | 0c | min |  |

### 7.5 What this register rejects and the rules for using it

1. Register recommendations that are REJECTED or corrected are listed with reasons in s2.13 (R-01 to R-34); this table never overrides them.
2. An alias id is retired. A document that cites an alias writes the master id and, once, the alias in brackets.
3. A gap closes only when the owner doc contains the closing text, the decision (D-id) and the gate (T-id) in its "Traceability" section; doc 20 s8 fails the build if a master id has no closing text by its phase (gaps.yaml).
4. Severity counts in doc 14 s7 are computed from this table, so a writer who changes an owner, phase or severity amends this file through the doc 14 author, never silently.
5. Gaps raised while writing use the s5.8 blocks and are added here by the doc 14 author with one owner and phase.

## 8. Writing rules (all writers, no exceptions)

| # | Rule | How it is checked |
| --- | --- | --- |
| 1 | Tables over prose. Prose only for a decision's reason (one or two sentences) and for the "what this doc decides" box. Lists of more than three parallel items are tables | doc 14 merge review |
| 2 | Every document starts with its title line, then the "What this doc decides" box of EXACTLY five lines (one decision per line, each naming a D-id or section), then the numbered H2 sections of s1 in order, then "Open items", then "Traceability" (last two H2s, unnumbered) | rtm-check style review |
| 3 | "Open items": a table OI-<doc>-nn, item, why open, owner role, needed by (sub-milestone), what proceeds on the default meanwhile. Every MUST-CONFIRM decision the doc applies appears here by D-id. Where the document disagrees with this file, the disagreement is an open item and the document still follows this file | |
| 4 | "Traceability": a table mapping each F-id, G-id, D-id, T-id and cfg key the document uses to the section that handles it; plus a requirement row for R1 to R6 and the process requirement. A cited id that this file does not define is an error | |
| 5 | Cross-reference by document number and section ("doc 16 s9", "doc 17 s4.3"), never by file name, never by "above" or "below". Use s-numbers of THIS file only inside this file | |
| 6 | Do not restate docs/01 to 13 or docs/22: cite them ("docs/04 budgets") and state only what is new or changed. If a statement in docs/01 to 13 is wrong, say so once with the D-id that changes it | |
| 7 | Use the exact names of this file: KPI names (s4), sub-milestone codes (s3), D-ids (s2), config keys (s5.7), id blocks (s5). Never invent a second spelling | grep-based lint in doc 20 s8 |
| 8 | Mark every non-obvious statement: PARITY, IMPROVEMENT, DELIBERATE CHANGE (with D-id), ASSUMPTION (with why), or "unknown; confirm with the business" (with the proceed-with default). A number without a source or an ASSUMPTION marker is not allowed | |
| 9 | Parity first: where a manual shows behaviour, build that behaviour unless it breaks one of CLAUDE.md constraints 1 to 8; then the constraint wins and the change is a DELIBERATE CHANGE row with a D-id. Never reproduce vendor strings (D-244) | |
| 10 | Verification beats register: wherever s7.3 corrects a register statement, use the corrected statement | |
| 11 | Every table, key, endpoint and screen the document introduces states its phase (sub-milestone), whether it works OFFLINE, QUEUED, HYBRID, CACHED or ONLINE-ONLY, and the gate (T-id) that proves it | |
| 12 | Money is milli-taka (_mtk); quantities are in the SKU's own unit, always with the unit named (sticks, pieces, dozens, boxes); times are UTC with the Dhaka business date; Dhaka times written as "07:00 Dhaka". Never "units", never an unlabelled "STD total" | |
| 13 | Numbers: Western digits in documents; Bangla strings are quoted in Bengali script only where parity-critical (button and message text from the manuals) and carry their key from the message catalogue plan (doc 15 s11) | |
| 14 | No emojis, no marketing language, no "should be considered": say "must", "default is", "is". Short sentences | |
| 15 | Code and DDL appear only where the exact text is load-bearing (wire contract JSON, a constraint, a SQL KPI definition, a state table). Never pad with boilerplate code | |
| 16 | Each doc ends each major section with the gate line "Proved by: T-ids" so doc 20 can build the register | |
| 17 | A writer does not edit another doc's territory: a need is raised as an open item addressed to the owning doc (s9.1 shows who defines what) | |
| 18 | Do not write outside the assigned file. Doc 14's author additionally writes /home/user/Aron-Pro-Max/DECISIONS.md from s2 (every row: id, date 2026-10-04, decision, reason, status, owner doc; plus the MUST-CONFIRM schedule and the "deliberately changed" table) | |
| 19 | Length is a budget, not a target (ASSUMPTION): doc 14 about 40 to 70 KB; doc 15 about 120 to 200 KB (it carries the F-id tables); doc 16 about 80 to 130 KB; doc 17 about 70 to 110 KB; doc 18 about 60 to 100 KB; doc 19 about 80 to 140 KB (it carries the 245-key registry); doc 20 about 60 to 100 KB; doc 21 about 60 to 100 KB | |
| 20 | Guardrails restated once in doc 14 s9 and respected by all: no contact with Apsis's running backend or app; credentials in the dump are data to migrate or rotate; no secrets in the repo; no fabricated measurements (a figure that was not measured is an ASSUMPTION or "unknown") | |

## 9. Hand-off notes

### 9.1 Who defines, who consumes (a consumer cites; it does not redefine)

| Item | Defined in | Consumed by |
| --- | --- | --- |
| Sync record-type enum and wire contract (records[], envelope, headers) | 17 s4 and /packages/contract | 16 (tables), 18 (capacity), 20 (property tests), 21 (validation) |
| Table and column definitions, DQ rules, dw layer, KPI SQL | 16 | 15, 17, 18, 19, 20 |
| F-ids and screen parity, message catalogue, glossary, online/offline matrix | 15 | all |
| Config key registry, risk classes, rails, admin pages, permission bundles | 19 | 15, 17, 18, 20, 21 (fraud thresholds live in cfg.sec.fraud.* owned by 19 for mechanics and 21 for values) |
| Auth tokens, OTP model, device proof, PII matrix, audit, FS signals | 21 | 15, 16, 17, 19 |
| Budgets (battery, data, size), triggers, bundle and delta, printing, memo number, updater, maps client | 17 | 15, 18, 20 |
| Load model use, SKUs, SLOs, DR, job DAG, launch-day runbook | 18 | 16, 17, 20 |
| Gate register (T-ids), parity oracle, CI/CD, cutover tests, support arithmetic | 20 | all (they list "Proved by") |
| Phases, schedule, confirmation schedule, risks (RK-nn), DECISIONS.md | 14 | all |
| Importer data design | 16 s12 | 18 (operations), 20 (verification), 21 (credentials) |

### 9.2 Parallel-writing risks and how they are closed

| Risk | Closure |
| --- | --- |
| Two docs define the same number differently (debounce, accuracy, offline unlock) | s2.12 and s5.7 give one value; a writer who finds a different number in a lens uses this file |
| Id collisions (F, G, D, T, Q, M, FS, DQ, R) | s5 blocks; R-nn is rejected recommendations, RR-n residual risks, RK-nn project risks, R1 to R6 sponsor requirements |
| Doc N cites a decision doc M has not written yet | the decision exists in s2 now; docs cite the D-id, not the doc M section, unless the section is in s1 |
| A MUST-CONFIRM decision silently becomes LOCKED | status words are copied verbatim; only the doc 14 author changes status |
| "Submit %" ambiguity | s4: always the qualified name; both figures returned |
| Wire contract drift between 16 and 17 | the record-type enum is generated from /packages/contract; doc 16 lists tables per record type, doc 17 owns the types |
| Register statements that verification corrected | s7.3 is binding |
| Gap closed twice or never | s7 has one owner; the owner's "Traceability" must list its master ids; doc 14 s7 recounts |
| A document silently changes a phase | phases are s3 only; a feature needing a different phase is an open item |
| Photo, location and PII features drift against constraints | D-75, D-74, D-107, D-108, D-110, D-120 are the single statements; docs cite them |
| Over-claiming coverage (the manual inventory is 31.6 percent fully covered, 84.5 percent at least partly) | doc 15 s1 states the coverage figure as an upper bound and the 20 percent plan-only items from register s0.3 |
| The pilot parity cannot be judged | D-33, D-142, D-143: promotion engine and baseline pack are entry conditions for 2a and 7b |

### 9.3 Golden fixtures (doc 20 s4 copies this list; every value is cross-checked by arithmetic before use)

Rule: a number copied from a manual page is a fixture only after its arithmetic reproduces from the other printed numbers on that page (digit misreads happened: TADS 68, Black Diamond 218,768, Avon 27,800 were wrong). Prices in fixtures are fixtures, never catalogue prices (the manual's MaxR-10S 8.00 matches neither seed price 9.20 nor 7.935).

| Fixture | Value to reproduce | Source and decision |
| --- | --- | --- |
| AMO memo and summary | AMO p28/p43 total 116.42; p29/p46 summary 289.50; FB 1 = 2.33 per piece on one screen and 28.00 per dozen on another (same price, two units) | V-money, D-16, D-19 |
| SR memo with slide and QC | gross 360.50, slide 80.00, net 280.50, quantity total 65; SR p35 161.50; QC settlement 10 sticks x 8.00 = 80.00; slide offer 100 sticks of empty MaxR packs gives 1 pack of 10 sticks x 8.00 = 80.00 (valid 2025-11 to 2025-12-30) | V-money, D-18, D-34 |
| Stock badge | 6,500 sticks shows pack badge 650 (pack size 10) | V-money, D-17 |
| Lighter and match units | Lighter (Aster) 1 = 1 piece at 12.50; SL 12 = 19.00; Match unit MUST-CONFIRM | V-money, D-16 |
| Dues | partial payment accepted only if amount is below the grand total; label 61.50 truncated to 61 in the manual (do not reproduce the truncation: I-06); mark-as-paid settles the whole memo | V-money, D-37, D-213 |
| Max-QC | cap 594.50 (taka; basis unknown) | V-money, D-34 |
| Home card discount | total discount 437.50 with DRP discount 0.00 shown separately | V-money, D-18 |
| SR target screen | target 500 gives TADS 36 and RADS 45; target 900 gives TADS 64 and RADS 82 (elapsed 14, remaining 11, achieved 0, round half up) | V-targets, D-58 |
| Percent display | 38960/3944 prints 100 on a card (cap); 1500/118 prints 1271.19 in a detail table; 136/0 prints remaining 136 and a dash here (manual prints 0.00) | V-targets, D-50 |
| Till-date | TSO monthly 3944, 1425, 526, 510 gives 3420, 1236, 456, 442 at 26/30 with ceil per item; AMO team monthly 2,972,900 gives about 2,475,477 (within 0.1 percent); AMO report Marise 100,000 gives 88,235.29 at 15/17 | V-targets, D-51 |
| Login and CPR | Login 1 of 4 routes = 25 percent (TSO and web); CPR 6/60 = 10 percent (UI-SR-09) | V-day, D-44, D-46 |
| Submit % | all-zero on the sample day: the web basis cannot be proven; the fixture asserts both figures K-02 and K-03 on a synthetic day | D-45 |
| PII | AMO sees an SR phone as 11 asterisks; retailer phones visible to field roles | V-outlets, D-108 |
| Memo number | <username>-<yyMMdd>-<seq3> composed on the device, bind-ordinal blocks of 500 | D-35 |
| Manual defects NOT to reproduce | Sale History footer 20,260 versus rows 20,660; the AMO 12:53 PM sample; the 2719 percent value; the register's TADS 68 | I-21, I-18, V-targets |
| Data-profile generator | planted defects P-05 to P-16 (zero-volume lines, 68 duplicate keys, text outlet codes, 175,031 absent outlets, shared coordinates, placeholder NID) | docs/22, D-245 to D-260 |

### 9.4 Merge checklist for the doc 14 author (run before the documents are declared final)

| Check | Expected |
| --- | --- |
| Decisions in DECISIONS.md | 269 (D-01 to D-269) plus any D-300 to D-499 raised, none duplicated |
| MUST-CONFIRM decisions in the confirmation schedule (doc 14 s4) | 93 from s2 (list in the hand-back) plus the questions of s2.14 |
| Master gaps in doc 14 s7 | 329 (37 blockers, 192 major, 100 minor) plus new G-<doc>-nn; per-owner counts equal s7.1 |
| Manual entries G-man-001 to 104 | 104 rows in s7.4, each resolved by its owner |
| Rejected register recommendations | R-01 to R-34 present in DECISIONS.md under "rejected" |
| F-id pins | s5.2 honoured; no F-SR-051 to 053 meaning other than s5.2 |
| Config keys | no retired alias of s5.7 appears; two Submit % names only |
| Phase codes | only 0a to 7e of s3 appear |
| Requirement coverage | R1 to R6 and the process requirement each map to a mechanism, a doc, a phase and gates (doc 14 s1) |
| Constraints | each of CLAUDE.md constraints 1 to 8 appears in at least one gate per phase from 1 |

### 9.5 Confidence of this file (what a writer should treat as soft)

| Item | Confidence | Reason |
| --- | --- | --- |
| Decisions D-01 to D-269 | firm, except those marked MUST-CONFIRM | each states its source; parity-first rule applied |
| Phase weeks and team shape | ASSUMPTION | no team or budget is stated anywhere |
| T-id placement inside a sub-milestone | soft | doc 20 s3 places the individual gates; ranges (s5.4) are firm |
| Owner doc and phase of minor gaps | soft | a writer may propose a change through doc 14 |
| Reference devices, fleet census | unknown; confirm with the business | G-qa-05, D-12 |
| Azure SKUs and cost | ASSUMPTION, unverified | no pricing was fetched; doc 18 states orders of magnitude only |
