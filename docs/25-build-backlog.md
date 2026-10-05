# 25 — Build backlog: seven days, hard cap ten

**Status: v1, 2026-10-05.** Written from `docs/15` under the schedule of `docs/23` (binding). The same rows are in `docs/25-build-backlog.csv` (UTF-8, one row per feature, no line breaks inside a cell). The rule of the sponsor: everything the current Aron does plus the sponsor's additions; no feature is lost silently, so every one of the 509 features of `docs/15` has exactly one decision here, BUILD or DROP, and every DROP has a reason in section 2 for the sponsor to review.

## 0. How the backlog is made

- **Rows.** Every F-id of `docs/15` appears once. N-001 and up are the items `docs/15` does not have: the sponsor's additions of `docs/23` (native Kotlin apps, device-owner policy and enrolment, scheduled app blocking, GNSS and Play Integrity layers, anti-spoofing tests, low-power breadcrumbs, push, Bangla bitmap printing, Google Maps, the route kinds), what the screenshots show that `docs/15` misses (the Cluster tile), and the scaffolding the day plan needs (contract, schema, skeletons, infrastructure, CI, tests, the daily ten-minute checks, load, failover and battery runs, the release candidate).
- **Decision.** All 253 features with status parity or changed are BUILD. Of the 256 with status new, a row is BUILD when the sponsor's list needs it, when the system cannot be operated without it (config, devices and enrolment, audit, sync health, users, releases, master data), or when a manual or screenshot shows the current app has it; otherwise it is DROP with a one-line reason. When in doubt it is BUILD.
- **Size and hours.** S is under 2 agent-hours, M under 4, L under 8. All totals below count a row at the top of its band (S 2, M 4, L 8), so they are the safe upper estimate; a session is 8 agent-hours a day.
- **Day.** The day a row must be done. Rows on the path of a day's ten-minute check stay on their theme day. Other rows keep their theme day (`theme_day` in the CSV) unless the lane was full, in which case they move later (never beyond Day 6, and never beyond Day 5 for the SR, AMO and TSO apps, so the apps are final before the Day 6 battery and load runs), or, in the backend, web, AMO and TSO lanes, one day earlier. Day 7 carries only the release candidate, the field-test script and fixes. Days 8 to 10 carry nothing: they are the reserve.
- **Dependencies** are build prerequisites (code, data, contract), not the order a user walks through a flow, so screens of one flow can be built in parallel against fixtures. A dependency is always done on an earlier or the same day, and no chain of same-day dependencies is longer than 16 hours (one working day of strictly sequential work, about ten hours at typical task speed; the evening check scripts are exempt).
- **CSV columns.** id, name, decision, role, lane, size, day, theme_day, dependencies (semicolon-separated ids), acceptance_test, source, docs15_status (parity, changed, new, or n/a for N rows), unknown_assumed, scope_note, drop_reason.
- **Source** is one of: parity, changed (docs/15 status), new-required (needed to run the system), sponsor (docs/23), screenshot, manual.

## 1. Summary

### 1.1 Decisions by role

| Role | BUILD (docs/15) | BUILD (N rows) | BUILD total | DROP | Rows |
| --- | --- | --- | --- | --- | --- |
| SR | 70 | 8 | 78 | 11 | 89 |
| AMO | 42 | 0 | 42 | 7 | 49 |
| TSO | 28 | 0 | 28 | 3 | 31 |
| WEB | 64 | 4 | 68 | 8 | 76 |
| ADM | 60 | 2 | 62 | 25 | 87 |
| API | 71 | 0 | 71 | 23 | 94 |
| SYS | 78 | 52 | 130 | 19 | 149 |
| Total | 413 | 66 | 479 | 96 | 575 |

Of the 509 features: parity 188 BUILD, 0 DROP; changed 65 BUILD, 0 DROP; new 160 BUILD and 96 DROP. The N rows are all BUILD.

DROP rows have no lane, size or day; their reasons are in section 2.

BUILD rows by source: parity 185, changed 65, new-required 88, sponsor 129, screenshot 7, manual 5.

### 1.2 BUILD rows by lane and by day

| Lane | Rows | S | M | L | Agent-hours |
| --- | --- | --- | --- | --- | --- |
| shared | 7 | 2 | 3 | 2 | 32 |
| db | 4 | 0 | 2 | 2 | 24 |
| backend | 130 | 65 | 53 | 12 | 438 |
| android-core | 39 | 19 | 18 | 2 | 126 |
| android-geo | 5 | 1 | 3 | 1 | 22 |
| android-dpc | 4 | 0 | 1 | 3 | 28 |
| android-print | 8 | 4 | 2 | 2 | 32 |
| android-sr | 66 | 29 | 32 | 5 | 226 |
| android-amo | 41 | 25 | 16 | 0 | 114 |
| android-tso | 23 | 14 | 9 | 0 | 64 |
| web-dashboard | 56 | 47 | 8 | 1 | 134 |
| web-admin | 72 | 32 | 35 | 5 | 244 |
| infra | 6 | 1 | 3 | 2 | 30 |
| qa | 18 | 8 | 5 | 5 | 76 |
| Total | 479 | 247 | 190 | 42 | 1590 |

| Day | BUILD rows | Agent-hours |
| --- | --- | --- |
| 1 | 26 | 116 |
| 2 | 51 | 196 |
| 3 | 73 | 286 |
| 4 | 102 | 334 |
| 5 | 124 | 356 |
| 6 | 100 | 292 |
| 7 | 3 | 10 |
| Total | 479 | 1590 |

Rows by lane and day:

| Lane | D1 | D2 | D3 | D4 | D5 | D6 | D7 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| shared | 4 | 3 | 0 | 0 | 0 | 0 | 0 |
| db | 4 | 0 | 0 | 0 | 0 | 0 | 0 |
| backend | 7 | 18 | 22 | 24 | 29 | 30 | 0 |
| android-core | 5 | 8 | 9 | 3 | 5 | 9 | 0 |
| android-geo | 0 | 1 | 4 | 0 | 0 | 0 | 0 |
| android-dpc | 0 | 0 | 3 | 1 | 0 | 0 | 0 |
| android-print | 0 | 4 | 4 | 0 | 0 | 0 | 0 |
| android-sr | 0 | 15 | 21 | 15 | 14 | 1 | 0 |
| android-amo | 0 | 0 | 0 | 20 | 18 | 3 | 0 |
| android-tso | 0 | 0 | 0 | 6 | 17 | 0 | 0 |
| web-dashboard | 1 | 0 | 0 | 13 | 17 | 25 | 0 |
| web-admin | 1 | 0 | 6 | 18 | 22 | 25 | 0 |
| infra | 2 | 0 | 1 | 0 | 0 | 2 | 1 |
| qa | 2 | 2 | 3 | 2 | 2 | 5 | 2 |

### 1.3 Agent-hours by lane and day against capacity

Capacity at one session per lane (the model of `docs/23`) is 14 lanes x 8 = 112 agent-hours a day. Sessions needed is the hours divided by 8, rounded up. Both tables are checked by the script at the end of this page.

Agent-hours by lane and day:

| Lane | D1 | D2 | D3 | D4 | D5 | D6 | D7 | Total |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| shared | 22 | 10 | 0 | 0 | 0 | 0 | 0 | 32 |
| db | 24 | 0 | 0 | 0 | 0 | 0 | 0 | 24 |
| backend | 20 | 64 | 100 | 86 | 80 | 88 | 0 | 438 |
| android-core | 20 | 32 | 28 | 8 | 14 | 24 | 0 | 126 |
| android-geo | 0 | 4 | 18 | 0 | 0 | 0 | 0 | 22 |
| android-dpc | 0 | 0 | 24 | 4 | 0 | 0 | 0 | 28 |
| android-print | 0 | 24 | 8 | 0 | 0 | 0 | 0 | 32 |
| android-sr | 0 | 56 | 72 | 48 | 48 | 2 | 0 | 226 |
| android-amo | 0 | 0 | 0 | 56 | 52 | 6 | 0 | 114 |
| android-tso | 0 | 0 | 0 | 16 | 48 | 0 | 0 | 64 |
| web-dashboard | 4 | 0 | 0 | 40 | 36 | 54 | 0 | 134 |
| web-admin | 4 | 0 | 16 | 72 | 72 | 80 | 0 | 244 |
| infra | 16 | 0 | 2 | 0 | 0 | 8 | 4 | 30 |
| qa | 6 | 6 | 18 | 4 | 6 | 30 | 6 | 76 |
| Total | 116 | 196 | 286 | 334 | 356 | 292 | 10 | 1590 |
| Capacity, one session per lane | 112 | 112 | 112 | 112 | 112 | 112 | 112 | 784 |

Sessions needed by lane and day:

| Lane | D1 | D2 | D3 | D4 | D5 | D6 | D7 | Peak |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| shared | 3 | 2 | 0 | 0 | 0 | 0 | 0 | 3 |
| db | 3 | 0 | 0 | 0 | 0 | 0 | 0 | 3 |
| backend | 3 | 8 | 13 | 11 | 10 | 11 | 0 | 13 |
| android-core | 3 | 4 | 4 | 1 | 2 | 3 | 0 | 4 |
| android-geo | 0 | 1 | 3 | 0 | 0 | 0 | 0 | 3 |
| android-dpc | 0 | 0 | 3 | 1 | 0 | 0 | 0 | 3 |
| android-print | 0 | 3 | 1 | 0 | 0 | 0 | 0 | 3 |
| android-sr | 0 | 7 | 9 | 6 | 6 | 1 | 0 | 9 |
| android-amo | 0 | 0 | 0 | 7 | 7 | 1 | 0 | 7 |
| android-tso | 0 | 0 | 0 | 2 | 6 | 0 | 0 | 6 |
| web-dashboard | 1 | 0 | 0 | 5 | 5 | 7 | 0 | 7 |
| web-admin | 1 | 0 | 2 | 9 | 9 | 10 | 0 | 10 |
| infra | 2 | 0 | 1 | 0 | 0 | 1 | 1 | 2 |
| qa | 1 | 1 | 3 | 1 | 1 | 4 | 1 | 4 |
| Total | 17 | 26 | 39 | 43 | 46 | 38 | 2 | 46 |
| Baseline, one per lane | 14 | 14 | 14 | 14 | 14 | 14 | 14 | 14 |

**It does not fit one session per lane, and I am saying so plainly.** The plan holds 1590 agent-hours (at band ceilings). One session per lane gives 112 a day, 672 for Days 1 to 6, so the baseline covers 42 percent of the work, and 35 of the 51 lane-days that carry work are over 8 hours. Levelled across Days 3 to 6 the plan still needs about 38 to 46 sessions running at once on those days (Day 1: 17, Day 2: 26). The lanes that need the most extra sessions are:

- **backend**: 438 agent-hours, peak 13 sessions on Day 3 (baseline 1). Split into sub-lanes by module (sync and ingest, config and admin, reports, auth and devices, push) so parallel sessions do not edit the same files.
- **web-admin**: 244 agent-hours, peak 10 sessions on Day 6 (baseline 1). Split by page group (master data, config console, device and release pages, web entry panels).
- **android-sr**: 226 agent-hours, peak 9 sessions on Day 3 (baseline 1). Split by screen group (sale loop, memo and dues, outlet requests, programmes); the SR app is final by Day 5 so the Day 6 battery run measures it.
- **web-dashboard**: 134 agent-hours, peak 7 sessions on Day 6 (baseline 1). Report pages are small and template-driven; the dashboard home is the one large row.
- **android-core**: 126 agent-hours, peak 4 sessions on Day 2 (baseline 1). Database, outbox and sync stay with one owner; screens elsewhere only read them.
- **android-amo**: 114 agent-hours, peak 7 sessions on Day 4 (baseline 1). AMO screens reuse the SR components; start from Day 4 once the sale loop exists.
- **qa**: 76 agent-hours, peak 4 sessions on Day 6 (baseline 1). 

How the average number of parallel sessions turns into days, for 1590 agent-hours (the longest dependency chain, section 1.4, is a floor of about 4 days whatever the staffing):

| Average sessions in parallel | Days of work |
| --- | --- |
| 14 | 14.2 |
| 20 | 9.9 |
| 24 | 8.3 |
| 28 | 7.1 |
| 32 | 6.2 |
| 40 | 5.0 |

The hard cap of ten days therefore needs at least 20 sessions on average and no failed check, and seven days needs about 28 with no slack.

If tasks average 60 percent of their band ceiling the total falls to about 954 agent-hours, roughly 24 sessions a day over Days 2 to 6, still about 1.4 times the baseline. The levelled day counts above are the ones to staff; the cut ladder in 1.8 shows what can be removed if the sessions cannot be had. Forty or more parallel sessions will also run into the Claude usage limits that `docs/23` names as risk 2, and the heaviest days are 3 to 5.

### 1.4 Critical path

The longest chain of dependencies, 52 agent-hours end to end, runs from N-002 (the contract) to F-WEB-055 (DSS Report (Sales Summary)). Anything that slips on it slips the whole plan:

| Step | Row | Name | Lane | Size | Day |
| --- | --- | --- | --- | --- | --- |
| 1 | N-002 | API contract package (OpenAPI 3.1 and JSON schemas) | shared | L | 1 |
| 2 | N-009 | Ktor API skeleton | backend | M | 1 |
| 3 | F-SYS-005 | Server-side scope resolution | backend | M | 1 |
| 4 | F-API-006 | POST /sync/batch | backend | L | 2 |
| 5 | F-SYS-015 | Aggregation into fact and aggregate tables | backend | L | 3 |
| 6 | F-API-017 | GET /reports/<name>?filters&format=json/xlsx/pdf/print (the full ReportQuery registry; th… | backend | L | 3 |
| 7 | N-051 | Report handlers batch B (Data Entry Log, Final Submit Log, GIGO, DSS, DS-RRS, Top Sheet,… | backend | L | 5 |
| 8 | F-WEB-055 | DSS Report (Sales Summary) | web-dashboard | M | 6 |

The longest chain into each day's ten-minute check, which is what the sponsor sees each evening:

- **Day 2 check** (26 agent-hours): N-001 → F-SYS-018 → N-018 → F-SR-028 → N-022.
- **Day 3 check** (38 agent-hours): N-002 → N-009 → F-SYS-005 → F-API-006 → F-SYS-012 → N-039 → N-043.
- **Day 4 check** (38 agent-hours): N-005 → N-007 → F-API-037 → F-ADM-012 → F-ADM-039 → N-050.
- **Day 5 check** (40 agent-hours): N-002 → N-009 → F-SYS-005 → F-API-006 → F-API-057 → F-SYS-010 → F-SR-037 → F-AMO-022 → N-055.
- **Day 6 failover drill** (48 agent-hours): N-002 → N-009 → F-SYS-005 → F-API-006 → F-SYS-015 → F-API-014 → N-056 → N-059.

Each of these chains is held on its day: the rows on them never move (priority 1).

### 1.5 The ten riskiest features

| # | Rows | Why it is risky | What reduces the risk |
| --- | --- | --- | --- |
| 1 | N-018, N-019, F-SR-028 | Bangla printing as a bitmap on a 58 mm printer: Bengali conjunct shaping, 384-dot raster and flow control on a cheap MP-58N, and no sample of the paper layout exists (Q-UI-09). | Goldens on JVM first, a physical print on Day 2 morning, the sponsor's photos of a memo, a stock slip and a day summary before Day 2. |
| 2 | F-SYS-008, F-API-006, N-024 | Idempotent sync is the single most important correctness rule: a retried, duplicated or partial upload must never create a second sale. Everything else reads these rows. | Property and fuzz suite on Day 3 with 10,000 random schedules; the same suite re-run on Days 5 and 6. |
| 3 | N-029, N-030, N-031 | Device-owner provisioning on three phone models (Galaxy A06, A07, Honor X5c Plus): QR provisioning, policy support and factory-reset enrolment differ by maker, and the Honor may lack Google services. | Enrol all three phones on Day 3 morning; the developer-verification check (N-042) and the capability flags in the device record show what each phone supports. |
| 4 | N-032 | Scheduled blocking must work offline, survive reboot and force-stop and not be undone by Samsung or Honor battery savers; it rests on the device-owner suspend call. | Applied from a cached list on the phone; Day 3 test in airplane mode and after reboot; battery-saver exemption is part of the policy. |
| 5 | N-039, N-025, N-026, N-027 | The promise "software spoofing is closed" must be proved with at least five mock-location apps and one cloning tool on three phones; GNSS raw measurements and Play Integrity are not available on every cheap phone. | Layered signals (mock flag, GNSS consistency, integrity, server plausibility); report each tool before and after enrolment; RF-level simulators stay a statistical flag only. |
| 6 | F-SR-017, N-021 | The offline geofence gate must be fast and honest on cheap phones indoors while taking one fix per event: at most 80 fixes and 6 percent drain in an 8-hour day. | One balanced-power fix with a 60 s reuse window, accuracy and mock flag on every fix, battery trace on Day 6. |
| 7 | F-SYS-015, F-SYS-086, F-API-014 | Dashboards read stored aggregates built by a dirty-key worker; a wrong key means a wrong tile, and late batches must re-aggregate their own business date. | Control totals from a seeded day, run-twice-equals-once test, 60 s latency check, late-batch test. |
| 8 | N-058, N-059, N-056, N-054 | One shot at the scale proof on Day 6: 8,500 users, 1.5 times fleet, a 3 times burst, an instance killed and a database failover, inside a subscription whose quotas are shared with live services. | Regional quota snapshot first, data generator on Day 5, admission control and autoscaling built before the test, test size capped to what the quota allows. |
| 9 | F-SR-024, F-SR-025, F-SR-027, F-SYS-045 | Memo money must match the printed paper to the paisa: offer catalogue unknown (only one offer is evidenced), QC deduction rule unknown, 3-decimal prices, rounding once. | Shared Kotlin money module with golden vectors from the screenshots (4,391.00), one fixture offer, sponsor answers on the UNKNOWN list. |
| 10 | N-060, N-037, N-038 | The 8-hour battery and data budget with device policy, breadcrumbs and push on three phones, and push delivery under maker battery savers. | Telemetry (F-SYS-081) from Day 3, scripted 8-hour run on Day 6, push is only a nudge so a missed push never loses a task. |

### 1.6 Behaviours that are UNKNOWN and need the sponsor

Each row is built with the default shown; the sponsor's answer replaces the default. "Answer by" is the day the row is built.

| Row | What is unknown, and the default assumed | Answer by |
| --- | --- | --- |
| F-SR-011 Attendance check-in | Whether Stock and Sale are blocked before check-in is UNKNOWN (Q-UI-04); assumed: the check-in message is a prompt, not a block (cfg.day.require_checkin_before_sale off). | Day 2 |
| F-SR-014 Stock load (issue by SKU) | Whether Save is blocked while the printer is not connected is UNKNOWN (Q-UI-03); assumed: Save always works offline, the slip is flagged not printed, Sales Submit warns. | Day 2 |
| F-SR-023 Sale: SKU quantity entry | Meaning of the purple pack badge and the three unlabelled indicators is UNKNOWN (Q-UI-11); assumed: packs equivalent (quantity over pack size) and the indicators hidden. | Day 2 |
| F-SR-024 Offers auto-apply | Offer catalogue and where discounts render are UNKNOWN (UI-SR-26, Q-UI-01); assumed: money discount per SKU stored as (sku, qty, value, kind) and printed as the current slip prints; only the one known offer is seeded. | Day 2 |
| N-020 Print goldens and a physical print test on the MP-58N, comp… | Printed layouts are not shown in any manual (Q-UI-09); the sponsor must supply photos of a printed memo, stock slip and day summary before Day 2; default: layout from the on-screen memo. | Day 2 |
| F-SR-027 Product QC | Maximum-QC basis and deduction rule are UNKNOWN (MQ-03, MQ-04); assumed: defect sticks x price at capture, cap per SKU in taka. | Day 3 |
| F-SR-031 Memo reprint | What the Apsis printout shows on a reprint is UNKNOWN (UI-SR-29); assumed: a duplicate marker line on the paper. | Day 3 |
| F-SR-035 Sales Submit (বিক্রয় জমা) | Exact Sales Submit enabling rule and handling of unpaid dues are UNKNOWN (Q-UI-08); assumed: enabled when every record is acknowledged and the counts match; dues only warn. | Day 3 |
| F-SR-068 KPI tile | KPI tile behaviour UNKNOWN (UI-SR-02); assumed: target versus achievement by category fed by the KPI registry. | Day 3 |
| F-SR-075 Outlet eligibility dots | Dot colours and their programmes UNKNOWN (UI-SR-22); assumed: red, green and magenta are promotion groups 1 to 3 and the emblem is Astha, legend editable in the portal. | Day 3 |
| F-SR-033 Sale edit | Edit reasons 2 and 3 are UNKNOWN (G-man-012); assumed: wrong SKU quantity and wrong price type alongside the known wrong-SKU reason. | Day 4 |
| F-SR-036 Summary and summary print | Whether the Return column is acknowledged by the distributor is UNKNOWN (Q-UI-10); assumed: printout only, closing stock stored per SKU per day. | Day 4 |
| F-SR-067 Sales Journey tile | Sales Journey tile behaviour UNKNOWN (UI-SR-01); assumed: route-progress view of today's planned outlets. | Day 4 |
| F-AMO-040 SS designation and per-user menu variants | Meaning of the SS designation is UNKNOWN (MQ-19); assumed: a supervisor-tier user shown the reduced 13-tile set. | Day 4 |
| N-040 Outlet menu fourth tile Cluster | Cluster tile behaviour UNKNOWN (UI-SR-40); assumed: an outlet change request of type cluster, verified by the AMO then approved on the web, route unchanged, cluster history kept. | Day 4 |
| N-046 App-block list page in the admin portal | Which apps to block is a sponsor decision (docs/23 Q1); default: Facebook, Instagram, TikTok, YouTube, Snapchat and games blocked; WhatsApp and Messenger allowed; phone, SMS, Maps and camera always allowed. | Day 4 |
| F-AMO-011 Joint Call assessment | Joint Call rubric items 4 and 5 are UNKNOWN (G-man-051); assumed: three known items plus two disabled placeholders, star 1 preselected. | Day 5 |
| F-AMO-035 Price compliance check | Price compliance fields are UNKNOWN (G-feat-01); assumed: observed price per SKU compared with the outlet list within a tolerance. | Day 5 |
| N-044 Cluster request handling on the server | Cluster behaviour UNKNOWN (UI-SR-40); see the Cluster tile row. | Day 5 |
| F-AMO-043 AMO Survey screen | AMO Survey screen is UNKNOWN (G-man-044); assumed: the SR POSM survey component without points, behind a flag. | Day 6 |
| F-WEB-053 DS-RRS Report | DS-RRS layout and meaning are UNKNOWN (G-man-093); assumed: a route-day delivery and settlement statement built from the memo and discount facts. | Day 6 |

Also needed from the sponsor, from `docs/23`: photos of a printed memo, a stock slip and a day summary before Day 2; the list of apps to block (default above); the Bangla reviewer who signs the string catalogue.

### 1.7 Rows that are not on their theme day

The theme day is the day `docs/23` gives that kind of work. The levelling moved a row only where a lane was full and the row is not on the path of a ten-minute check; the SR, AMO and TSO apps never move past Day 5. The CSV column `theme_day` lists each one.

| Lane | Later than theme | Hours | Earlier than theme | Hours |
| --- | --- | --- | --- | --- |
| backend | 31 | 84 | 20 | 54 |
| android-core | 3 | 6 | 0 | 0 |
| android-dpc | 1 | 4 | 0 | 0 |
| android-print | 1 | 2 | 0 | 0 |
| android-sr | 17 | 54 | 0 | 0 |
| android-amo | 0 | 0 | 20 | 56 |
| android-tso | 0 | 0 | 6 | 16 |
| web-dashboard | 22 | 48 | 3 | 6 |
| web-admin | 22 | 74 | 4 | 10 |
| qa | 1 | 2 | 0 | 0 |

98 rows are later than their theme day and 53 are earlier; 328 of 479 BUILD rows are on their theme day.

### 1.8 Cut ladder, if the sessions cannot be had

Nothing here is applied; every row stays BUILD. This is the order in which I would cut, with the agent-hours each step frees. The cuts remove rows that are parity but sit outside what the sponsor named, or that are not needed on the first day of selling.

| Step | Cut | Rows | Agent-hours freed | Cumulative hours left | Sessions a day left (Days 2 to 6) |
| --- | --- | --- | --- | --- | --- |
| 1 | Programme screens and reports (Astha, Diamond League, loyalty points, gifts, Superstar): parity, but the programme portals are Phase 2 | 17 | 54 | 1536 | 35 |
| 2 | Target portal: Set Target, revisions, Excel upload and the two target reports (Phase 2 target portal) | 7 | 24 | 1512 | 35 |
| 3 | Reports that only the spec names (no manual page) and Daily Tracking take-action | 13 | 30 | 1482 | 34 |
| 4 | Second wave of the config console and its rails | 15 | 42 | 1440 | 33 |
| 5 | Disaster-recovery refinements, window logic and the memo-gap trio | 11 | 32 | 1408 | 32 |
| 6 | SR extras that are improvements | 8 | 18 | 1390 | 32 |
| 7 | Wholesale marking, unlock grants, outlet reactivation, transfers and the supervisor target table | 9 | 22 | 1368 | 31 |

The seven cuts free 222 agent-hours (14 percent) and still leave about 31 sessions a day on Days 2 to 6, so they do not close the gap to the baseline of 14. The gap closes only with more sessions, more days (the reserve, up to Day 10) or both; the table of days against sessions in 1.3 is the trade. For scale: rows that no ten-minute check and no later row depends on (priority 3) are 330 agent-hours.

### 1.9 Where I used judgment and the sponsor should look

- **Parity rows in the Phase 2 area are BUILD.** The rule says every parity and changed row is built, but `docs/23` puts the discount, Astha and target portals in Phase 2. I built the field screens and web reports the current app has (Astha, Diamond League, gift photo, targets) and dropped only the setup portals and tools that were new; the programme data is seeded by script until the Phase 2 portals exist. Cut ladder steps 1 and 2 are the way back if this is too much for the week.
- **Reduced scope inside a BUILD row** (shown in `scope_note`): F-ADM-078 replace-device wizard has no checker approval step; F-ADM-081 price-change rails keep the preview and the percent guard but not the correction lane or the break-glass restore.
- **Sales Journey, KPI and Cluster tiles** are built with the assumed behaviour in section 1.6, not hidden.
- **Offers.** F-ADM-016 (offer CRUD) is BUILD because the screenshots show live discounts; the rule builder and simulator (F-ADM-061) are Phase 2 and dropped.
- **Rows most likely to be asked back from the DROP list:** F-SR-058 (void a printed memo), F-SR-070 (due receipt), F-SR-051 and F-SR-052 (stock return and cash settlement, owner unanswered, Q41), F-ADM-072 (support desk) and F-SR-059 (day exception). Restoring all 96 would add roughly 290 agent-hours (about 3 hours a row on average, a rough figure).

## 2. Dropped list for the sponsor to review

96 rows, all with status new in `docs/15`; none of the 253 parity or changed rows is here. Each reason is one line.

| Category | Rows |
| --- | --- |
| Apsis migration and cutover | 26 |
| Phase 2 portals (discount, Astha, target, programme finance) | 7 |
| Support-desk tools | 14 |
| Process rails, registers and compliance | 10 |
| Reserved pages with no manual or screenshot evidence | 3 |
| Day exceptions, cover, submit void and distributor settlement (workflows in no manual or screenshot) | 23 |
| Improvements and analysis in no manual or screenshot | 13 |
| Total | 96 |

DROP by role: SR 11, AMO 7, TSO 3, WEB 8, ADM 25, API 23, SYS 19.

### 2.1 Apsis migration and cutover

| Row | Name | Reason |
| --- | --- | --- |
| F-WEB-069 | Coverage banner and scope toggle | Exists only to compare Apsis and Aron during a cutover. |
| F-ADM-031 | Rollout wave and rollback console | Cutover waves, outside this build; staged rollout by version stays in F-ADM-027. |
| F-ADM-032 | Import and reconciliation console | Apsis migration, outside this build. |
| F-ADM-054 | Config page P17: Import and reconciliation console | Apsis migration, outside this build. |
| F-ADM-068 | Wave temporary-password batch | Apsis cutover work, outside this build. |
| F-ADM-069 | Bulk OTP pre-issue per zone | Wave launch tool, outside this build; single OTP issue (F-ADM-022) stays. |
| F-API-033 | /admin/migration/* | Apsis migration and cutover, outside this build. |
| F-API-067 | POST /admin/binds/cohort-ack (path proposed, ASSUMPTION) | Wave cohort acknowledgement of the takeover rule, outside this build. |
| F-API-085 | POST /admin/migration/apsis-feed | Apsis migration, outside this build. |
| F-SYS-038 | Apsis dump importer and ID crosswalk | Apsis migration, outside this build (docs/23 s1). |
| F-SYS-039 | Post-import reconciliation report | Apsis migration, outside this build. |
| F-SYS-040 | Delta re-import before a wave | Cutover work, outside this build. |
| F-SYS-041 | Wave rollout control | Cutover waves; staged rollout by version stays in F-ADM-027. |
| F-SYS-042 | Wave rollback | Cutover work, outside this build. |
| F-SYS-043 | Parallel-run comparison report | Cutover work, outside this build. |
| F-SYS-065 | Apsis-shape export for rollback | Cutover work, outside this build. |
| F-SYS-066 | Credential migration at cutover | Apsis migration, outside this build. |
| F-SYS-068 | Opening-balance provenance | No import in this build; any opening dues are loaded as ordinary due adjustments (F-ADM-036). |
| F-SYS-076 | Importer outlet normalisation and archived stubs | Apsis migration, outside this build. |
| F-SYS-077 | Parallel-run print and capture mode | Cutover work, outside this build. |
| F-SYS-082 | Apsis delta import and the nightly route-day feed | Apsis migration, outside this build. |
| F-SYS-083 | Switched-route dimension and coverage | Exists only to compare Apsis and Aron during a cutover. |
| F-SYS-085 | Wave rollback return path | Cutover work, outside this build. |
| F-SYS-087 | Late delta DL-1b and DL-2b | Cutover work, outside this build. |
| F-SYS-088 | Apsis residual detection | Cutover work, outside this build. |
| F-SYS-097 | Wave straggler reconciliation sheet | Cutover process register, outside this build. |

### 2.2 Phase 2 portals (discount, Astha, target, programme finance)

| Row | Name | Reason |
| --- | --- | --- |
| F-ADM-017 | Diamond League setup | A Phase 2 programme portal (docs/23 s1); the SR redemption screens (F-SR-043) read seeded data meanwhile. |
| F-ADM-018 | Astha program setup | A Phase 2 portal (docs/23 s1); the Astha screens read seeded data meanwhile. |
| F-ADM-019 | Superstar setup | A Phase 2 programme portal with unknown rules (D-332). |
| F-ADM-035 | Loyalty balance adjustment | Exists to make Apsis day-one balances exact (migration); programme finance tools are Phase 2. |
| F-ADM-045 | Config page P8: Programme setup | Phase 2 discount and programme portals. |
| F-ADM-047 | Config page P10: Targets | A page wrapper of Set Target, target revision and Excel upload (F-ADM-014, F-ADM-015, F-ADM-059), which are built; target portal is Phase 2. |
| F-ADM-061 | Promotion test-a-memo simulator | A Phase 2 discount portal tool. |

### 2.3 Support-desk tools

| Row | Name | Reason |
| --- | --- | --- |
| F-SR-077 | Support code on blocking screens | Support-desk tool whose decoder page is a support function. |
| F-ADM-072 | Config page P19: Support desk | Support-desk tool. |
| F-ADM-075 | Supervised paper-memo backfill | Support recovery tool; manual keying is covered by Data Entry (F-ADM-024). |
| F-ADM-080 | Replay console (P20) | Support-desk tool for decrypted device bundles. |
| F-ADM-084 | Support ticket store (P19) | Support-desk tool. |
| F-ADM-085 | Device directive button (P19) | Support-desk tool. |
| F-API-046 | POST /support/ping | Support-desk tool. |
| F-API-066 | POST /support/decrypt | Support-desk tool for decrypted device bundles. |
| F-API-071 | GET /support/search, POST /support/decode | Support-desk tool. |
| F-API-073 | POST /entries/paper-memo, POST /entries/paper-memo/{id}/approve | Support recovery tool; manual keying is covered by F-API-038. |
| F-API-077 | POST /admin/support/replay/dry-run, apply, approve | Support-desk replay console. |
| F-API-079 | POST /admin/devices/{id}/directive, GET /admin/devices/{id}/directives | Support-desk directive channel. |
| F-API-084 | POST /support/tickets, PATCH /support/tickets/{id}, GET /support/tickets | Support-desk tool. |
| F-SYS-095 | Device directive channel | Support-desk tool; PDA to Support (F-SYS-021) and the portal device list cover the day-one need. |

### 2.4 Process rails, registers and compliance

| Row | Name | Reason |
| --- | --- | --- |
| F-ADM-077 | Emergency-widen lane and temporary relief | Break-glass process rail; a normal radius edit (F-ADM-012) reaches phones at their next sync. |
| F-ADM-079 | Held-binds queue (P11, P15 tile) | Two-person takeover-rule process queue with SLA timers, not in any manual or screenshot. |
| F-ADM-082 | Revert a bulk batch (P9) | Process rail; a bulk change is undone by applying the inverse change. |
| F-ADM-083 | Retention-hold console (P16 tab) | Compliance process; no data is dropped in this build. |
| F-API-068 | POST /admin/binds/{id}/release (path proposed, ASSUMPTION) | Held-binds queue, outside this build. |
| F-API-075 | POST /admin/config/emergency-widen, POST /admin/config/temporary-relief | Break-glass process rail. |
| F-API-078 | GET /admin/binds/held | Held-binds queue, outside this build. |
| F-API-081 | POST /admin/bulk/{batchUuid}/revert | Process rail; a bulk change is undone by applying the inverse change. |
| F-API-082 | POST /admin/retention-holds, GET and DELETE /admin/retention-holds/{id} | Compliance process; no data is dropped in this build. |
| F-SYS-093 | Retention hold | Compliance process register; this build never drops a partition (F-SYS-063), so nothing needs holding yet. |

### 2.5 Reserved pages with no manual or screenshot evidence

| Row | Name | Reason |
| --- | --- | --- |
| F-WEB-070 | Wholesale order (bex) pages | Reserved and hidden in every menu, no manual or screenshot (PX-01); wholesale marking stays in F-ADM-056. |
| F-WEB-071 | Back-margin commission letters | Reserved and hidden in every menu, no manual or screenshot (PX-02). |
| F-WEB-072 | Voice recording page | Reserved and hidden in every menu, no manual or screenshot (PX-03); no microphone permission in any app. |

### 2.6 Day exceptions, cover, submit void and distributor settlement (workflows in no manual or screenshot)

| Row | Name | Reason |
| --- | --- | --- |
| F-SR-051 | End-of-day stock return and reconciliation | New workflow with an unanswered owner (Q41); the Summary Return column and printout (F-SR-036) stay. |
| F-SR-052 | Cash deposit and distributor settlement | New workflow with an unanswered owner (Q41); not in any manual or screenshot. |
| F-SR-059 | Field-side day exception | Approval workflow not in any manual or screenshot; holidays stay in the working-day calendar (F-ADM-033). |
| F-SR-080 | Day reopen after a submit void | Submit void is a round-3 addition not in any manual; a sale after Sales Submit is accepted and flagged (D-64). |
| F-AMO-037 | Same-day cover assignment | Cover workflow not in any manual or screenshot; route assignment with dates (F-ADM-003) covers a substitute. |
| F-AMO-041 | Acting-for sale | Cover workflow not in any manual or screenshot. |
| F-AMO-042 | Distribution-house confirmation proxy | Part of the distributor settlement workflow, owner unanswered (Q41). |
| F-AMO-045 | Zone day exception | Day-exception approval workflow not in any manual or screenshot. |
| F-AMO-047 | Bulk mark absent | Day-exception workflow not in any manual or screenshot. |
| F-TSO-030 | Confirm or perform a submit void | Part of the submit-void family, not in any manual or screenshot. |
| F-WEB-059 | Distribution-house settlement view | Part of the distributor settlement workflow, owner unanswered (Q41). |
| F-WEB-065 | Day exceptions report | Part of the day-exception family, not in any manual or screenshot. |
| F-ADM-062 | Interim web cover assignment | Cover workflow not in any manual or screenshot; route assignment (F-ADM-003) covers it. |
| F-ADM-063 | Re-attribution action | Part of the acting-for and cover workflow, not in any manual or screenshot. |
| F-ADM-073 | Emergency non-working-day declaration | Part of the day-exception family; the working-day calendar (F-ADM-033) covers holidays. |
| F-ADM-074 | Submit void and day-control actions (P15) | Part of the submit-void family, not in any manual or screenshot. |
| F-API-043 | POST /day/cover | Cover workflow not in any manual or screenshot. |
| F-API-044 | GET /day/exceptions | Day-exception workflow not in any manual or screenshot. |
| F-API-047 | POST /day/exceptions | Day-exception workflow not in any manual or screenshot. |
| F-API-056 | POST /admin/attribution | Part of the acting-for and cover workflow, not in any manual or screenshot. |
| F-API-069 | POST /day/submit-void | Part of the submit-void family, not in any manual or screenshot. |
| F-API-072 | POST /calendar/emergency | Part of the day-exception family, not in any manual or screenshot. |
| F-API-076 | POST /day/mark-absent/bulk | Day-exception workflow not in any manual or screenshot. |

### 2.7 Improvements and analysis in no manual or screenshot

| Row | Name | Reason |
| --- | --- | --- |
| F-SR-058 | Memo void after print | Improvement, Apsis cancel is unknown (Q43); corrections stay covered by Sale edit (F-SR-033). |
| F-SR-061 | Price compliance capture | Disabled in the SR flavour by default; the shared component is built once for the AMO (F-AMO-035). |
| F-SR-062 | Free sample capture | Not in any manual or screenshot (G-feat-23); the Free Sample report (F-WEB-026) still reads the is_free lines that DRP rewards create. |
| F-SR-070 | Due receipt print | Improvement, Apsis prints no receipt for a collection (Q47); not in any manual or screenshot. |
| F-SR-071 | Due dispute capture | Dispute workflow with AMO task and finance queue; process feature not seen in any manual or screenshot. |
| F-SR-078 | Sell to a not-yet-approved outlet | Improvement not seen in any manual or screenshot; the current app sells only after approval. |
| F-AMO-046 | Joint-call QR verification | Improvement not in any manual or screenshot. |
| F-AMO-048 | Approve consistent location proposals | Convenience over the outlet approval panel (F-WEB-032). |
| F-TSO-026 | Final-submit delegation and auto-close | Off until confirmed (Q45, Q53); not in any manual or screenshot. |
| F-TSO-031 | Approve consistent location proposals | Convenience over the outlet approval panel (F-WEB-032). |
| F-WEB-058 | Dues ageing report | Not in any manual or screenshot; By Outlet Report (F-WEB-019) already carries dues and the ledger (F-SYS-060) keeps the buckets. |
| F-WEB-066 | Visit outcome report | Analysis report not in any manual or screenshot; outcomes are still captured (F-SR-057). |
| F-API-074 | POST /outlet-requests/bulk-approve | Convenience over the approval endpoint (F-API-055). |

## 3. Backlog by day

Rows are sorted by lane, then id. "Needs" lists the dependency ids. A name followed by *(theme day n)* is a row that was moved off its theme day (section 1.7). A day is done only when its ten-minute check passes.

### Day 1 — Contract, schema, skeletons and infrastructure

Ten-minute check: install the skeleton app on the Galaxy A06; log in; the API answers from Azure. 26 rows, 116 agent-hours, 17 sessions.

| Id | Name | Role | Lane | Size | Needs | Acceptance test | Source |
| --- | --- | --- | --- | --- | --- | --- | --- |
| F-SYS-017 | Business-date stamping | SYS | shared | S | N-003 | A sale stamped 23:55 Dhaka stores its UTC time and that day's business_date and one stamped 00:05 stores the next day's, identically in the shared module and on the server | parity |
| N-002 | API contract package (OpenAPI 3.1 and JSON schemas): envelope, ERR_ codes, enums, auth, sync record types, bundle shape; generated Kotlin and TypeScript clients | SYS | shared | L | - | Contract lint passes; the Kotlin and TypeScript clients regenerate in CI and a deliberate field rename breaks both builds | sponsor |
| N-003 | Shared rules v1 (Kotlin Multiplatform): integer milli-taka money, quantity units (sticks, pieces, dozens), memo line and totals, discount lines (sku, qty, value, kind), QC deduction, net formula | SYS | shared | L | - | Golden vectors pass on JVM and on the server: 141.00 + 4,687.50 - 437.50 - 0.00 = 4,391.00; FB 3 x 28.00 = 84.00; totals are summed unrounded and rounded once | sponsor |
| N-004 | Shared geofence maths (Kotlin Multiplatform): Haversine, radius resolution order (outlet, zone, geo class, house, territory, division, wing, global), accuracy and mock rules | SYS | shared | M | - | Haversine matches 20 reference pairs within 0.5 m; a mocked fix never returns valid; the radius resolves from the most specific scope that has a value | sponsor |
| N-005 | Schema v1a (forward-only SQL): identity, geography, product, SKU and price types, outlet, route with visit_kind and visit_days, assignment, user scope, config tables | SYS | db | L | - | Migrations apply on an empty PostgreSQL 16 and on the Azure dev database; a second run is a no-op; a checksum guard refuses an edited shipped migration | sponsor |
| N-006 | Schema v1b: field transactions (visit, geo_fix, attendance, memo, memo_line, stock_movement, QC, DRP, due ledger, task, outlet request, media, ingest_registry, sync_batch, outbox), each with client_uuid, UTC time and business_date | SYS | db | L | - | Inserting the same client_uuid twice into any device-originated table violates the unique key; every table has a timestamptz column and a Dhaka business_date column | sponsor |
| N-007 | Schema v1c: device, enrolment token, policy, app-block list, app release, append-only audit log, notification token, aggregate (dw) tables, dirty-key table | SYS | db | M | N-005 | audit_log rejects UPDATE and DELETE; dw tables and the dirty-key table exist; all migrations apply in order on a clean database | sponsor |
| N-008 | Seed data and test accounts: geography, about 40 SKUs with five price types, routes of kinds Daily, 3F and 2F, SR, AMO, TSO and admin users, 60 outlets with coordinates near the test route | SYS | db | M | N-005, N-006 | After seeding, the seeded SR has three routes (Daily, 3F, 2F) with 60 outlets and the seeded TSO's scope covers them; re-running the seed changes nothing | sponsor |
| F-API-001 | POST /auth/login | API | backend | S | F-SYS-001 | POST /auth/login rate-limits per username and device (not per IP), returns scope claims and scope_version, and under 200 parallel logins the hash limiter answers 503 with Retry-After | changed |
| F-API-002 | POST /auth/refresh | API | backend | S | F-SYS-001 | POST /auth/refresh rotates the opaque refresh token with reuse detection and a 60 s grace replay; a used refresh token replayed after 60 s revokes the grant | changed |
| F-SYS-001 | Username and password login | SYS | backend | S | N-009, N-008 | POST /auth/login for the seeded SR returns an ES256 access token and a rotated refresh token carrying role and scope claims; a wrong password returns the uniform error and repeated failures lock the (username, device) pair | parity |
| F-SYS-002 | Token refresh | SYS | backend | S | F-SYS-001 | A refresh token rotates on use; a replay of a used token within 60 s returns the same result and after that revokes the grant; the phone keeps reading local data while a refresh fails (tested in airplane mode) | parity |
| F-SYS-005 | Server-side scope resolution | SYS | backend | M | N-005, N-009 | A seeded TSO token returns only outlets of its territory, a request carrying scope ids from the client ignores them, and the scope-leak harness finds zero cross-scope rows over 200 randomised queries | parity |
| N-009 | Ktor API skeleton: modules, error envelope, request id, health, Postgres pool, migration runner, Key Vault settings, OpenTelemetry | SYS | backend | M | N-002, N-005 | GET /health answers from Azure Container Apps through Front Door; an unknown route returns the ERR_ envelope with a request id; no secret is in the repository | sponsor |
| N-017 | Route kinds and day plan: Daily, 3F (three days a week) and 2F (two days a week) as route.visit_kind and visit_days; today's route resolved from the business date and the working calendar; bundle carries the planned routes and the target-outlet count | SR | backend | M | N-008 | On a seeded Sunday the Daily and the Sun/Tue/Thu 3F routes are planned and the Mon/Thu 2F route is not; on Friday (weekend) none is planned; the target-outlet count equals the planned routes' outlets | sponsor |
| F-SYS-018 | Localisation layer and bundled fonts | SYS | android-core | M | N-001 | Switching to Bangla shows Bengali digits and the bundled Bengali font on every skeleton screen and a lint test fails the build on any hardcoded user-visible string | parity |
| F-SYS-033 | Role-aware single codebase, three flavours | SYS | android-core | S | N-001 | The SR, AMO and TSO APKs install side by side with launcher labels ARON SR, ARON AMO and ARON TSO and the package ids com.aktcl.aron.sr, .amo and .tso | changed |
| F-SYS-044 | Side-by-side coexistence | SYS | android-core | S | N-001 | The three apps run side by side on one phone with a test copy of a fourth package id and the same paired printer prints from each | sponsor |
| N-001 | Native Kotlin Android build: Gradle multi-module and three apps (SR, AMO, TSO) | SYS | android-core | L | - | Gradle assembles the three debug APKs (com.aktcl.aron.sr, .amo, .tso); the SR APK installs on the Galaxy A06 and shows the login screen and a home placeholder; login against the Azure API is proven by the Day 1 check | sponsor |
| N-016 | Room database for the SR day: entities, DAOs and repositories for visit, geo_fix, attendance, memo, memo_line, stock_movement, QC, outlet, route and SKU, generated from schema v1b and the contract | SYS | android-core | M | N-002, N-006 | A migration test creates every table with a unique client_uuid; inserting the same client_uuid twice is rejected and a seeded SR day loads from a fixture in under a second | sponsor |
| N-010 | Next.js and Tailwind web skeleton: generated client, login shell, scope-aware layout, bn and en strings, menu driven from data | SYS | web-dashboard | M | N-002 | The web app deploys to Azure, shows the login page, logs in the seeded TSO and renders an empty scoped dashboard shell in Bangla and English | sponsor |
| N-011 | Admin portal skeleton: role-gated route group, table and form kit with a mandatory reason field, generic CRUD page generator | SYS | web-admin | M | N-010 | A generated CRUD page for one seeded table lists, edits and writes an audit row with the reason; a non-admin role gets 403 | sponsor |
| N-012 | Azure infrastructure as code for rg-aron-dev: Front Door and WAF, Container Apps (API and worker), zone-redundant PostgreSQL with pooling, point-in-time restore and geo-redundant backup, Blob, Key Vault, App Insights, budget alert; subscription, region and names as parameters | SYS | infra | L | - | One command deploys the whole stack to rg-aron-dev; the deploy identity has rights only on that group (a test deployment to another group is denied); a budget alert exists | sponsor |
| N-013 | CI/CD on GitHub Actions: build the three APKs, the web app and the API, run all tests, deploy to rg-aron-dev with a restricted identity | SYS | infra | L | N-012 | A push to main builds three APKs and uploads them as artifacts, runs the unit and contract tests, and deploys the API and web app to rg-aron-dev within 15 minutes | sponsor |
| N-014 | Test harness: contract tests (Kotlin and TypeScript), scope-leak harness, sync property and fuzz harness skeleton, Android instrumented tests on the emulator and on a phone via adb | SYS | qa | M | N-002, N-009 | The harness runs in CI and fails on a seeded contract mismatch, a seeded cross-scope read and a seeded duplicate sale | sponsor |
| N-015 | Day 1 ten-minute check script: adb devices, install the skeleton, log in, API answers from Azure | SYS | qa | S | N-001, F-SYS-001, N-012 | Running the script on the sponsor's laptop prints PASS for: phone listed, APK installed, login succeeded, /health answered from Azure | sponsor |

### Day 2 — SR selling loop

Ten-minute check: airplane mode: check in, sell, print; network on; the sale appears once on the server. 51 rows, 196 agent-hours, 26 sessions.

| Id | Name | Role | Lane | Size | Needs | Acceptance test | Source |
| --- | --- | --- | --- | --- | --- | --- | --- |
| F-SYS-045 | Price snapshot on memo | SYS | shared | M | N-003 | Each memo line stores unit_price_mtk and the price type; 12 sticks at 7.935 are summed unrounded and rounded once with round_adj_mtk stored; a later price change leaves old memos unchanged | parity |
| F-SYS-051 | Number, date, time and money formatting profile | SYS | shared | S | N-003 | One formatter set renders 1,234.50 followed by the taka sign with Bengali digits in Bangla and Latin digits in English, Western grouping, on the phone and on the web | changed |
| F-SYS-070 | Text normalisation and Bangla collation | SYS | shared | M | N-003 | Bengali digits convert to Western at input and ingest (flagged when changed), phones normalise to 11 digits and the server name_sort_key orders 200 mixed Bangla and Latin outlet names identically in SQLite and PostgreSQL | new-required |
| F-API-005 | GET /sync/bundle?since= | API | backend | L | N-005, N-008, F-SYS-005, N-017 | GET /sync/bundle for the seeded SR returns the planned routes, outlets with flags, products, both price lists, sales plan, targets, offers, geo config, config snapshot and business date, gzipped with ETag, 304 on repeat and paged above 2,000 rows | changed |
| F-API-006 | POST /sync/batch | API | backend | L | N-006, N-002, F-SYS-005 | POST /sync/batch upserts every record by client_uuid, replays a repeated batch_uuid, returns accepted, rejected, parked, server_totals and day_states, accepts at most 500 rows and answers 429 with Retry-After on a storm | changed |
| F-API-007 | POST /media/upload (multipart fallback) *(theme day 3)* | API | backend | S | F-API-006 | POST /media/upload accepts a small non-evidence file idempotently by (client_uuid, purpose); the same request replayed changes nothing and the response matches the contract | changed |
| F-API-021a | GET /targets/*, achievement and Astha reads *(theme day 3)* | API | backend | S | F-SYS-005, N-008 | GET /targets/* returns targets, achievement and till-date bases from seeded data with at least 0 and a dash on zero | new-required |
| F-API-025 | GET /memos?outlet=&date= *(theme day 3)* | API | backend | S | F-SYS-005 | GET /memos?outlet=&date= returns the memos of an outlet beyond the local window within the caller's scope; a caller outside the scope gets no rows (scope-leak harness) and the body equals the seeded data | parity |
| F-API-026 | GET and POST /tasks, POST /tasks/:uuid/resolve *(theme day 3)* | API | backend | M | F-API-006, F-SYS-005 | GET and POST /tasks and POST /tasks/:uuid/resolve assign, list and resolve tasks idempotently, also through the batch | parity |
| F-API-031 | POST /auth/logout *(theme day 3)* | API | backend | S | F-SYS-002 | POST /auth/logout revokes the full refresh grant at once and with scope=upload also the upload-only grant after the last ACK | parity |
| F-SYS-014 | Quarantine of invalid references | SYS | backend | M | F-API-006 | A memo line for an unknown SKU is parked rather than dropped, the batch response lists it under parked, and the other rows of the batch are accepted | sponsor |
| F-SYS-025 | Route log (Data Entry Log source) *(theme day 3)* | SYS | backend | S | F-API-005, F-API-006 | The first and last download and upload time and counts per route per day are stored in Dhaka time with MIN as first and MAX as last | parity |
| F-SYS-035 | Suggested order quantity hook *(theme day 3)* | SYS | backend | S | F-API-005 | The bundle carries a per-outlet suggested_qty field that is empty by default and the sale screen shows nothing when it is empty | manual |
| F-SYS-048 | Poison-row isolation and skip-ahead | SYS | backend | M | F-API-006 | One malformed record in a batch of 100 becomes rejected(server_error) with a quarantine row, the other 99 are accepted, and the client skips that record family after 3 failures | sponsor |
| F-SYS-055 | Batch replay and content fingerprint | SYS | backend | M | F-API-006 | Repeating a batch_uuid returns the stored response, the same batch_uuid with a different row set returns 409 and a replay with regenerated uuids is caught by the content fingerprint | sponsor |
| F-SYS-056 | Route-day planning job *(theme day 3)* | SYS | backend | M | N-017 | At 00:05 Dhaka a route_day exists for every route planned for that date by visit kind and calendar, with target outlets fixed at bundle time | new-required |
| F-SYS-060 | Dues ledger, allocation and ageing *(theme day 3)* | SYS | backend | M | F-API-006 | A credit memo, a collection and a void each add a ledger row, and FIFO allocation gives ageing buckets 0-7, 8-30, 31-60 and 61+ that sum to the outlet balance | new-required |
| F-SYS-062 | Server recompute checks *(theme day 3)* | SYS | backend | M | F-API-006, N-003 | A batch whose memo total disagrees with its lines is rejected, a price mismatch is flagged but never rejects a printed memo, and coordinates outside Bangladesh are flagged | new-required |
| F-SYS-078 | Multi-visit and visit-kind policy *(theme day 3)* | SYS | backend | S | F-API-006 | A second visit to one outlet on one day is accepted after a zero sale, the visit kinds sr_call, amo_control_call, amo_joint_call, tso_visit and web_entry are stored, and route KPIs include every active memo | new-required |
| N-036 | Breadcrumb ingest and storage in a partitioned table, feeding the team-location read *(theme day 3)* | SYS | backend | S | N-006, F-API-006 | Breadcrumb points in a batch are stored once by client_uuid; the last point per user is returned by the team-location read with its age | sponsor |
| N-037 | Push service (Firebase Cloud Messaging): device tokens, task-assigned push, data-only nudge, Bangla text, cfg.notify switches *(theme day 3)* | SYS | backend | M | N-007, F-API-026 | An AMO assigning a task sends one push to the SR's registered phone within 30 seconds; the push carries no task data, the task itself arrives with the next sync | sponsor |
| F-SYS-006 | Reference bundle download (the login event) | SYS | android-core | M | F-API-005, N-016 | After one download in the seeded SR's session, airplane mode still loads routes, outlets, SKUs, prices, config and business date from Room; killing the app mid-download resumes without duplicates; the first bundle of the Dhaka day records the logged-in event | changed |
| F-SYS-008 | Idempotent batch upload | SYS | android-core | L | N-002, N-001, N-003, N-016 | Every capture writes its row and an outbox record in one Room transaction; uploading the same batch twice, in any order, or after a kill mid-send leaves exactly one memo and one set of lines on the server (end to end in the Day 2 check) | sponsor |
| F-SYS-011 | Constrained background sync | SYS | android-core | M | N-001, N-016 | WorkManager runs one expedited job after a failed send and a 15-minute periodic job registered only while rows are pending; a lint test finds no foreground service, alarm or timer under 60 s | sponsor |
| F-SYS-023 | Runtime permission flow | SYS | android-core | M | N-001 | Denying location blocks Attendance and Sale with the Bangla rationale and a Settings deep link, denying Bluetooth disables only printing, and the manifest requests no microphone permission | changed |
| F-SYS-027 | Memo numbering | SYS | android-core | S | F-SYS-008 | Memo numbers follow <username>-<yyMMdd>-<seq3> composed in the memo insert transaction from disjoint 500-number blocks per device; a failed save burns a number and the server keeps the number verbatim, unique per business date | changed |
| F-SYS-046 | Immediate-sync trigger on connectivity regained | SYS | android-core | M | F-SYS-011 | Regaining connectivity flushes pending rows within 60 s without pressing Sync (5 s debounce), and a lint test finds no polling timer | sponsor |
| F-SYS-049 | Trusted time anchor | SYS | android-core | S | N-001 | With the phone clock set two hours wrong, the business date and the 17:00 check-out gate follow corrected time (server time plus elapsed realtime) | sponsor |
| N-023 | Shared Compose UI kit: theme, Bengali typography, tile grid, stepper, press-and-hold button, dialogs, empty, error and offline-banner states | SYS | android-core | M | N-001 | A kit gallery screen renders every component in Bangla and English at font scale 1.3 on a 360 x 640 dp screen with no truncation and 48 dp touch targets | sponsor |
| N-021 | Location fix manager: one on-demand balanced-power fused fix, reuse of a fix up to 60 s and 30 m old, timeout, accuracy, provider, elapsed realtime, mock flag stamped on every fix; no background location updates | SR | android-geo | M | N-001, N-004 | Opening an outlet takes one fix (never a stream): a battery trace over a scripted 60-outlet day shows at most 80 fixes and the mock flag is stored with every fix | sponsor |
| F-SR-013 | Bluetooth printer pairing and state | SR | android-print | M | N-019 | Pairing the MP-58N from the printer icon shows green connected with the banner, red slashed when off, auto-reconnects, and Print is enabled only when connected and saved | parity |
| F-SR-028 | Print memo | SR | android-print | M | N-018, N-019, N-016, F-SYS-045 | Print shows 'sure? the sale will be saved', Yes commits the immutable memo before and regardless of printing, then 'print this sale?' and the Bangla memo prints once; No leaves printed_at empty and the memo reprintable | parity |
| N-018 | Bangla memo renderer: Bengali text shaped with the bundled font and rasterised to a 1-bit bitmap at 384 dots for 58 mm; memo, stock slip, day summary and cancel slip as versioned templates; Bengali or Latin digits | SR | android-print | L | N-003, F-SYS-018 | The renderer produces the memo bitmap for the seeded sale with correct Bengali conjuncts and digits and matches the stored golden image pixel for pixel on JVM and on the Galaxy A06 | sponsor |
| N-019 | Bluetooth printing on the MP-58N: SPP connect, ESC/POS raster in flow-controlled chunks, reconnect, paper-out and disconnect states (same path for RPP02N-class printers) | SR | android-print | L | N-001 | A 40-line Bangla memo prints on the MP-58N without cut-off or garbage; switching the printer off mid-print shows the failed state and a retry prints the whole memo once | sponsor |
| F-SR-001 | Install, login, first bundle | SR | android-sr | M | F-SYS-006, F-SYS-001, N-023 | The SR logs in with username and password, the first bundle downloads resumably, the version shows from one source on the login screen and in Settings, and the footer carries AKTCL branding instead of the vendor credit | parity |
| F-SR-003 | Permissions onboarding and gating | SR | android-sr | S | F-SYS-023 | First run asks for precise location, camera and Bluetooth with Bangla rationale and never asks for the microphone; checked on the Galaxy A06 against the seeded day, in Bangla and in English, and the state survives a kill and relaunch | changed |
| F-SR-008 | Home header | SR | android-sr | S | N-023, N-017 | The header shows 'SR - name (username)' and '<route name (visit days)>, ISO date' and a route label for Daily, 3F or 2F | parity |
| F-SR-009 | Home tile grid | SR | android-sr | S | N-023, N-016 | Home shows the screenshot's tile grid in order, the tiles resolved per user (Loyalty Point and Photo Capture hidden for an account without them) and a task badge equal to the open task count | parity |
| F-SR-010 | Home KPI strip | SR | android-sr | M | F-SR-009, F-SR-050 | In airplane mode the KPI strip shows outlets visited x/y, strike rate (6 of 60 gives 10 percent), Issue and Current stock per category with units, non-visit and no-sale equal to values computed from Room for the seeded day, in Bengali digits | changed |
| F-SR-011 | Attendance check-in | SR | android-sr | M | N-021, F-SYS-008, F-SYS-049, N-023 | In airplane mode check-in stores one fix, the time and the mock flag, shows the check-in message and disables the button; the address shows coordinates offline and a text address online; check-out stays disabled before 17:00 Dhaka | parity |
| F-SR-014 | Stock load (issue by SKU) | SR | android-sr | M | N-016, F-SYS-045, N-023 | The Stock screen takes a stepped or typed Issue per SKU, shows today's loaded total read-only, a Save posts only the entered increment and works offline, a same-values re-save within the guard window is refused, and the category totals show 400 and 400 for the seeded load | changed |
| F-SR-016 | Route outlet list with alphabet filter | SR | android-sr | M | N-016, N-023, F-SYS-070 | The Sale picker lists today's outlets as name (code-phone-cluster) with case-insensitive Bangla and Latin filter chips, 11-digit phones and closed outlets hidden | changed |
| F-SR-017 | Open visit and geo check | SR | android-sr | L | N-016, N-021, N-004, F-SYS-008 | Selecting an outlet opens the visit row at once and takes one fix; inside the radius the sale continues, outside it shows the Bangla out-of-range message with Force Sale and Refresh, a mocked fix is never valid, and everything works in airplane mode | parity |
| F-SR-019 | Refresh GPS fix | SR | android-sr | S | N-021 | Refresh GPS re-reads one fix and re-evaluates, capped by the configured limit per outlet; checked on the Galaxy A06 against the seeded day, in Bangla and in English, and the state survives a kill and relaunch | parity |
| F-SR-023 | Sale: SKU quantity entry | SR | android-sr | L | F-SYS-045, N-023, N-016 | The sale screen takes quantities in sticks for cigarettes and bidi, pieces for lighters and dozens for matches with the unit shown, a pack badge equal to quantity over pack size, a running total, a stock warning that allows the sale and a 60-line limit | changed |
| F-SR-024 | Offers auto-apply | SR | android-sr | M | N-003, N-008 | The seeded offer applies automatically on Review and the Home card (437.50 for 35 lighters at 12.50), the DRP reward stays a separate deduction, and discount lines are stored as (sku, qty, value, kind) | parity |
| F-SR-025 | Review (নিরীক্ষণ) | SR | android-sr | M | F-SR-023, F-SR-024 | Review shows lines with category subtotals, every non-zero component and net = gross - offer discount - DRP discount - QC; credit and Product QC work in any order and the draft survives a kill and relaunch | changed |
| F-SR-050 | Current stock tracker | SR | android-sr | S | F-SR-014 | Current stock per SKU equals issued minus sold minus returned after every sale, computed locally, and feeds the KPI strip, the Summary return and the stock warning | new-required |
| F-SR-060 | Start-call confirmation prompt | SR | android-sr | S | N-016, N-023 | After the geo gate the prompt 'start the call?' appears; No returns to the list without counting a visit and Yes starts the call | parity |
| N-020 | Print goldens and a physical print test on the MP-58N, compared with the sponsor's photographed sample | SR | qa | M | N-018, N-019 | A memo, a stock slip and a day summary printed on the MP-58N match the sponsor's sample layout (columns, Bangla labels, totals) in a side-by-side photo review | sponsor |
| N-022 | Day 2 ten-minute check script: airplane mode, check in, sell, print, then network on and the sale appears once on the server | SYS | qa | S | F-SR-028, F-API-006, F-SYS-046, F-SR-011 | The script run on the Galaxy A06 in airplane mode ends with one printed memo and, after the network is enabled, exactly one memo on the server for the seeded SR | sponsor |

### Day 3 — Rest of the SR day, geo integrity, device policy

Ten-minute check: the spoofing apps are refused; check-in suspends the chosen apps; check-out releases them. 73 rows, 286 agent-hours, 39 sessions.

| Id | Name | Role | Lane | Size | Needs | Acceptance test | Source |
| --- | --- | --- | --- | --- | --- | --- | --- |
| F-ADM-012 | Geofence configuration | ADM | backend | L | F-API-037, N-004, N-005 | The radius resolves at global, wing, division, territory, geo class, zone and outlet with effective dating and bounds 20 to 2,000 m, an outlet override above 3 x the zone value needs an approver, and the value reaches the bundle and delta | changed |
| F-API-008 | POST /day/sales-submit | API | backend | M | F-API-006, F-SYS-016 | POST /day/sales-submit is idempotent, returns the dues warning and delays sales_submitted until the server totals reach the device counts or the 30 minute timeout | changed |
| F-API-010 | GET /outlets *(theme day 4)* | API | backend | S | F-SYS-005 | GET /outlets is scoped, paginated with updated_since and PII-gated; a caller outside the scope gets no rows (scope-leak harness) and the body equals the seeded data | parity |
| F-API-017 | GET /reports/<name>?filters&format=json/xlsx/pdf/print (the full ReportQuery registry; the early read halves are F-API-017a and F-API-017b, D-533) | API | backend | L | F-SYS-005, F-SYS-015 | The ReportQuery registry serves each report as json, xlsx, pdf or print from one query with a logged export and a sanitiser, and rejects scope ids sent by the client | changed |
| F-API-029 | GET /app/update-check?version=&role=&abi= | API | backend | S | N-007 | GET /app/update-check returns the latest release, min version, APK URL and SHA-256 for the role and ABI; a phone on the latest version gets no update and one below min_version is told to update | changed |
| F-API-035 | /admin/* CRUD of all 58 entities (6a; the early halves are F-API-035a, 035b, 035c by entity group, D-533) | API | backend | L | N-005, N-011, F-SYS-059 | The generic /admin/* CRUD framework serves geography, clusters, QC fault types and the other master entities with optimistic concurrency and an audit row for every write | changed |
| F-API-036 | POST /admin/device-otp and GET /admin/device-otp | API | backend | S | F-SYS-003 | POST and GET /admin/device-otp view, re-issue and pre-issue OTPs stored AES-GCM encrypted and scoped; the same request replayed changes nothing and the response matches the contract | changed |
| F-API-037 | GET and PUT /admin/config, GET /config/snapshot | API | backend | L | N-005, N-007 | GET and PUT /admin/config and GET /config/snapshot store typed scoped values with effective date, reason and risk class and stamp a version | changed |
| F-API-040 | GET /config/delta | API | backend | S | F-API-037 | GET /config/delta returns config changes since a version with an ETag; a phone at the current version gets 304 and one two versions behind gets both changes | sponsor |
| F-API-041 | POST /config/ack | API | backend | S | F-API-037 | POST /config/ack records the applied config version carried on the next sync; the acknowledgement appears in the reach view for that device | sponsor |
| F-API-057 | POST /media/sas | API | backend | S | F-API-006 | POST /media/sas returns write-only user-delegation SAS URLs for up to 10 photos, pinned to photos/{business_date}/{device}/{client_uuid}.jpg, valid 15 minutes | new-required |
| F-SYS-003 | Device binding by OTP | SYS | backend | M | F-SYS-001, N-007 | A login from an unknown device returns bind_required and a 4-digit OTP that is single-use, expires after 120 minutes, locks after 5 wrong tries, and is readable only by a TSO whose scope contains the SR | changed |
| F-SYS-012 | Server geo re-check on ingest | SYS | backend | M | F-API-006, N-004 | A visit uploaded with a mocked fix is stored geo_validated_server=false; with a 100 m radius a fix 80 m away is valid and one 130 m away is not, using the radius resolved for the visit's business date | sponsor |
| F-SYS-013 | Anti-spoofing plausibility flags | SYS | backend | L | F-SYS-012, N-006 | Replayed fixtures raise risk signals for teleport speed above the limit, zero jitter, one coordinate across a route and phone-versus-server disagreement; only the mock warning is visible to the SR | sponsor |
| F-SYS-015 | Aggregation into fact and aggregate tables | SYS | backend | L | N-007, F-API-006 | After ingest of the seeded day the dirty-key worker updates agg_daily_route within 60 s; running it twice gives identical numbers; a late batch re-aggregates its own business date | sponsor |
| F-SYS-016 | Day state machine | SYS | backend | M | F-API-006 | route_day moves not_started, logged_in, in_field, synced, sales_submitted from events, an SR on two routes has two route_day rows, and an AMO submit writes supervisor_day without touching an SR route | changed |
| F-SYS-050 | Device telemetry headers and support visibility | SYS | backend | S | F-API-006 | X-Pending-Rows, X-Last-Sync-Error and X-App-Version are recorded at most once per 10 minutes per device and a daily device object under 1 KB is accepted | new-required |
| F-SYS-057 | Risk signal store and review events | SYS | backend | M | F-SYS-013 | Server signals are stored in risk_signal, a review (dismiss or confirm) is an idempotent event and nothing is auto-reversed | sponsor |
| F-SYS-059 | Append-only audit log | SYS | backend | M | N-007 | UPDATE or DELETE on audit_log is rejected by the database and every admin write adds a hash-chained row with actor, before and after | new-required |
| N-027 | Server verification of Play Integrity verdicts and key attestation chains; enrolment gate cfg.device.require_enrolled on attendance and sales ingest | SYS | backend | L | N-026, F-API-006 | With the gate on, a batch from a phone that is not enrolled or fails integrity has its attendance and sales parked with a reason and a supervisor flag; with the gate off they are accepted and flagged | sponsor |
| N-031 | Enrolment and policy service: single-use expiring enrolment tokens, device registry, policy delivery, compliance reports | SYS | backend | M | N-007, N-009 | A token works once and expires; the device record shows model, Android version, policy version and last compliance report; revoking a device blocks sync but keeps local capture; with the gate on, a phone wiped outside the process cannot log in until it is enrolled again | sponsor |
| N-033 | App-block list service: versioned list delivered through config to enrolled phones | SYS | backend | S | N-007, F-API-037 | Changing the list in the database is delivered to an enrolled phone at its next sync and the phone confirms the list version | sponsor |
| F-SYS-007 | Bundle delta refresh | SYS | android-core | S | F-SYS-006 | A ?since= refresh applies only changed rows, never raises a login event, and a price change applies to new memos only | sponsor |
| F-SYS-009 | Device-versus-server reconciliation | SYS | android-core | S | F-SYS-008, F-API-006 | The per-type counts on the device equal server_totals after sync; before the first sync the Server column is blank with a timestamp; a mismatch shows a reason text | parity |
| F-SYS-010 | Media queue and photo upload | SYS | android-core | M | F-API-057, F-SYS-030 | A 150 KB photo queued offline uploads by write-only SAS on Wi-Fi first, a slow photo never delays the record sync, and a kill-and-relaunch resumes the upload | sponsor |
| F-SYS-019 | Language toggle | SYS | android-core | S | F-SYS-018 | The Settings language switch (en or Bangla) applies instantly without a confirmation and persists after a relaunch; checked on the Galaxy A06 against the seeded day, in Bangla and in English, and the state survives a kill and relaunch | parity |
| F-SYS-022 | Logout | SYS | android-core | S | F-SR-001 | SR logout keeps the local database and the engine keeps uploading; TSO logout with N unsent items is refused with 'N items not yet sent' (Sync now, Cancel) and wipes only a fully reconciled device | changed |
| F-SYS-030 | Photo capture and compression pipeline | SYS | android-core | M | F-SYS-023 | A captured photo is at most 150 KB with a 1024 px long edge, EXIF stripped, SHA-256 stored and stamped with the fix (lat, lng, accuracy, mock flag); one retake is allowed and the camera is released after capture | parity |
| F-SYS-052 | Offline unlock and shared-phone user switching | SYS | android-core | M | F-SYS-001 | After one online login the user unlocks offline within 7 days, 10 wrong attempts impose a doubling cool-down, and user B on the same phone has a separate database while A's rows upload under A's token | sponsor |
| F-SYS-072 | Device proof and record signatures | SYS | android-core | M | F-SYS-001, N-031 | Each batch carries X-Device-Proof signed by the Keystore key registered at bind; a bad signature is flagged in record mode and rejected in enforce mode | sponsor |
| N-038 | Push on the phone: notification channel, Bangla text, tap opens Tasks, nudge triggers one sync, no polling | SYS | android-core | M | N-037, N-001 | A task push shows the Bangla notification on the Galaxy A06 (also with the screen off and with the app force-stopped by Samsung battery saver exempted); tapping opens the task list showing the task | sponsor |
| F-SYS-031 | Integrity signals at login | SYS | android-geo | S | N-026 | Developer options, USB debugging and root hints are read at login and with each batch and stored on the device record; they never block a sale alone | sponsor |
| N-025 | GNSS consistency capture: satellite count, signal strength and raw measurements attached to every fix where the phone supports them, with a capability flag per model | SYS | android-geo | L | N-021 | A fix taken on the Galaxy A06, A07 and Honor X5c Plus carries satellite count and signal strength (or an explicit not-supported flag) and the data is in the next batch | sponsor |
| N-026 | Play Integrity token request and hardware key attestation on the phone, tied to the device-owner enrolment | SYS | android-geo | M | N-001 | At login and enrolment the phone sends a Play Integrity token and an attestation chain; a phone without Google services sends an explicit unavailable marker instead of failing | sponsor |
| N-035 | Low-power breadcrumbs: optional, admin-set (off by default), batched, passive or low-power fused fixes with a minimum displacement, uploaded in batches under a battery cap | SYS | android-geo | M | N-021, F-SYS-011 | With breadcrumbs on at 10 minutes, an 8-hour scripted day adds at most 48 points and under 1 percent battery on the Galaxy A06; with the setting off no extra fix is ever taken | sponsor |
| N-029 | Device-owner policy core: developer options and USB debugging off, unknown sources off, Aron not uninstallable, location permission granted and pinned, approved app list only, factory reset blocked, battery-optimisation exemption for Aron | SYS | android-dpc | L | N-001 | On an enrolled Galaxy A06 the developer-options switch cannot be turned on, Aron cannot be uninstalled, location permission cannot be revoked and a factory reset from Settings is refused | sponsor |
| N-030 | Enrolment by QR: device-owner provisioning extras (server URL, one-time token), the handshake that registers the device, fetches the policy and reports compliance | SYS | android-dpc | L | N-031, N-029 | A factory-reset Galaxy A06 scans the QR from the portal, installs Aron, becomes device owner, registers and shows as enrolled and compliant in the portal; a reused token is refused | sponsor |
| N-032 | Scheduled app blocking: the configured apps are suspended at check-in and released at check-out, applied on the phone from a cached list so it works offline, and survives reboot and force-stop | SYS | android-dpc | L | N-029, F-SR-011 | On an enrolled phone check-in suspends Facebook, Instagram, TikTok, YouTube, Snapchat and games while phone, SMS, Maps and camera stay available, in airplane mode and after a reboot; check-out releases them | sponsor |
| F-SR-015 | Print stock memo *(theme day 2)* | SR | android-print | S | N-018, N-019, F-SR-014 | The stock slip prints per SKU with category totals and stock_slip_printed turns true; Save is never blocked by the printer | parity |
| F-SR-031 | Memo reprint | SR | android-print | S | F-SR-030 | Reprint prints a committed memo with the duplicate marker, increments the reprint count and refuses beyond the configured limit | changed |
| F-SR-066 | Reprint duplicate marker and edited-memo number | SR | android-print | S | F-SR-031 | A reprint prints the duplicate marker, and an edited memo prints 'supersedes <memo no>' and has its own number from the same series | new-required |
| F-SR-073 | Print confirmation "ছাপা ঠিক আছে?" | SR | android-print | S | F-SR-028 | After each print one tap confirms the paper is readable; No marks the job failed_user and the next print has no duplicate marker and does not count toward the reprint limit | new-required |
| F-SR-005 | Settings: language | SR | android-sr | S | F-SYS-019 | The language chosen in Settings persists across relaunch; checked on the Galaxy A06 against the seeded day, in Bangla and in English, and the state survives a kill and relaunch | parity |
| F-SR-018 | Force Sale | SR | android-sr | M | F-SR-017, F-SYS-010, F-SR-079 | Out of range, one reason (internet problem or location change) and an outlet photo let the sale proceed with photo_validated true and geo_validated false; the photo raises a location-change request and a denied location permission blocks the sale | changed |
| F-SR-022 | Slide (DRP) empty-pack collection | SR | android-sr | M | F-SR-024, F-SR-023 | Slide lists only SKUs with an active offer, takes empty packets by stepper or manual dialog with shortcut steps, and 10 empty MaxR-10S packets give one reward pack shown as an 80.00 deduction with the quantity total unchanged | parity |
| F-SR-027 | Product QC | SR | android-sr | L | F-SR-025 | Product QC takes production and transport faults per SKU, saves the deduction as defect sticks times price at capture, subtracts it from the net and locks edits at that outlet once completed | parity |
| F-SR-030 | Memo menu | SR | android-sr | M | F-SR-028 | The Memo menu lists visited outlets with the memo total and memo number or time, shows the items table, discount table (SKU, quantity, value), total discount, total QC and grand total, and offers Print, Edit and Mark paid | parity |
| F-SR-032 | Due collection (বাকি পরিশোধ) | SR | android-sr | M | F-SR-030, F-SYS-060 | The Memo menu shows the outlet due in red, Mark paid asks to confirm and settles the whole memo writing a due_collection row, and the success text shows | parity |
| F-SR-034 | Sync | SR | android-sr | S | F-SYS-009 | The Sales Submit sync screen shows Online status and device-versus-server counts for outlet, sale, stock, QC and promotion and a Sync data button that retries | changed |
| F-SR-035 | Sales Submit (বিক্রয় জমা) | SR | android-sr | M | F-SR-034, F-API-008 | Sales Submit is enabled after sync, warns without blocking when retailers still owe, queues offline as the last event of the day and shows the success message once the server settles | changed |
| F-SR-037 | Outlet request: new shop | SR | android-sr | M | F-SYS-010, F-SR-079 | New shop takes cluster, name, owner, 11-digit mobile and GEO and photo, saves offline with no confirmation and shows the success text and the pending state | changed |
| F-SR-039 | Outlet request: info change | SR | android-sr | M | F-SR-079, F-SYS-010 | Information change edits name, owner and mobile, requires the GEO and photo capture, confirms and queues the request offline | parity |
| F-SR-046 | Task list and badge | SR | android-sr | S | F-API-026 | Tasks assigned by an AMO or TSO appear with type, outlet, text, completion date and status (ongoing or completed), resolved tasks stay and the badge equals the open count; the empty state reads 'your AMO has not assigned any task' with a synced-at time | parity |
| F-SR-047 | Task resolve (swipe) | SR | android-sr | S | F-SR-046 | Swiping a task and tapping Resolve sets it completed offline and queues it with a client uuid; checked on the Galaxy A06 against the seeded day, in Bangla and in English, and the state survives a kill and relaunch | parity |
| F-SR-054 | Sale History | SR | android-sr | M | F-API-025, F-SR-017 | Sale History shows an outlet's sales by date from the local 7-day window, falls back to GET /memos with an offline banner, and the footer equals the exact sum of the rows | parity |
| F-SR-056 | SKU-wise Target and Achievement | SR | android-sr | M | F-API-005, N-003 | SKU Details shows Target, Achieved, Remaining, percent, ADS, TADS, PADS and RADS from local data; with the April 2026 fixture (14 elapsed, 11 remaining) a target of 500 gives TADS 36 and RADS 45, 900 gives 64 and 82, and a zero target shows a dash | parity |
| F-SR-057 | Visit outcome and skip record | SR | android-sr | M | F-SR-017 | Every visit ends with an outcome (sold, zero sale, closed, owner absent, refused, competitor exclusive, not reached, abandoned); a skip needs no fix; abandoned visits are excluded from visited; three consecutive closed outcomes raise a task to the AMO | new-required |
| F-SR-065 | Route picker for an SR with several routes | SR | android-sr | S | N-017, F-SR-016 | With two routes planned for the date the SR picks the route, one bundle serves both and the header names the route in use | new-required |
| F-SR-068 | KPI tile | SR | android-sr | M | F-SR-056, F-SR-010 | The KPI tile opens target versus achievement by category (assumed) from the same values as the Home strip and the SKU target screen | screenshot |
| F-SR-075 | Outlet eligibility dots | SR | android-sr | S | F-SR-016 | Four dots are drawn before each outlet from the bundle flags and the legend comes from cfg.ui.outlet_badges; a dot is filled when eligible and outlined when not | parity |
| F-SR-076 | Outlet missing from my route request | SR | android-sr | S | F-SR-039 | A request of type 'add this existing outlet to my route or cluster' queues offline and is verified by the AMO; checked on the Galaxy A06 against the seeded day, in Bangla and in English, and the state survives a kill and relaunch | new-required |
| F-SR-079 | GEO and photo capture component | SR | android-sr | M | F-SYS-030, N-021 | The shared capture screen takes one fix at the shutter with the mock flag, shows the thumbnail and 'GEO captured +/- n m', allows one retake and compresses the photo | changed |
| F-SR-081 | Stock "correct total" | SR | android-sr | S | F-SR-014 | The Stock correct-total path takes the right total and a reason and posts a signed adjustment movement, never an overwrite | new-required |
| F-TSO-022 | SR Device OTP panel (web, view only) | TSO | web-admin | M | F-API-036, N-011 | The SR Device OTP panel filters Wing to Zone, lists every SR of the zone with the 8 manual columns and shows the OTP only to the TSO of that scope; 'No Data' shows for an empty zone | changed |
| F-ADM-001 | Geography CRUD *(theme day 4)* | ADM | web-admin | S | F-API-035, N-011 | Wing, division, territory, house, zone and cluster pages create and edit records with unique codes, the zone's extra fields and soft-delete by status, each write audited with a reason | parity |
| F-ADM-022 | SR Device OTP administration | ADM | web-admin | S | F-TSO-022 | The device OTP administration re-issues an OTP, searches and filters by scope and shows the OTP log, with one active OTP per user | changed |
| F-ADM-033 | Working-day calendar *(theme day 4)* | ADM | web-admin | S | F-API-037 | The working-day calendar sets weekend days (default Friday) and holidays with selling-day flags and drives today's route, Login percent and till-date bases | new-required |
| F-ADM-034 | Admin audit log viewer *(theme day 4)* | ADM | web-admin | M | F-SYS-059 | The audit viewer lists config, master-data and permission changes by actor, key, scope and date with export; a change made in the portal appears in the viewer within a minute with actor, before and after | new-required |
| F-ADM-053 | Config page P16: Audit viewer *(theme day 4)* | ADM | web-admin | S | F-ADM-034 | The audit page in the portal shows the same audit viewer; it shows the same rows as the audit viewer for the same filter | new-required |
| F-SYS-036 | APK size budget | SYS | infra | S | N-013 | CI fails any release APK above 30 MB per ABI or 70 MB installed; the check fails the pipeline on a deliberate violation | new-required |
| N-024 | Sync property and fuzz suite: duplicated, reordered, partial, truncated and killed-mid-batch uploads never create a second sale or double a number; device count equals server count | SYS | qa | L | N-014, F-API-006, F-SYS-008 | 10,000 random batch schedules (duplicate, reorder, drop, kill) over a seeded day always end with exactly the device's rows on the server and every total equal | sponsor |
| N-039 | Anti-spoofing acceptance tests: at least five well-known mock-location apps and one app-cloning tool tried on the Galaxy A06, A07 and Honor X5c Plus, before and after enrolment, each result reported | SYS | qa | L | N-029, N-025, F-SYS-012 | A written report lists each tool and phone before and after enrolment; after enrolment each spoofing app cannot be installed or switched on, and where one runs the visit is refused and flagged | sponsor |
| N-043 | Day 3 ten-minute check script: the spoofing apps are refused, check-in suspends the chosen apps, check-out releases them | SYS | qa | S | N-039, N-032 | The script run on an enrolled phone prints PASS for refused spoofing apps, suspended apps after check-in and released apps after check-out | sponsor |

### Day 4 — Web dashboards and admin portal

Ten-minute check: change the radius in the portal; the phone obeys at its next sync. 102 rows, 334 agent-hours, 43 sessions.

| Id | Name | Role | Lane | Size | Needs | Acceptance test | Source |
| --- | --- | --- | --- | --- | --- | --- | --- |
| F-AMO-039 | Supervisor day state *(theme day 5)* | AMO | backend | S | F-SYS-016 | An AMO Sales Submit writes supervisor_day for the user and date and does not flip any SR route_day; the same request replayed changes nothing and the response matches the contract | new-required |
| F-AMO-044 | Zone-wide AMO bundle | AMO | backend | M | F-API-005 | The AMO bundle for a 54-route zone (3,500 to 11,000 outlets) downloads in pages of at most 2,000 rows each under 2 MB gzip and a delta returns only changed rows | parity |
| F-TSO-019 | Device OTP issuance (as actor) *(theme day 3)* | TSO | backend | S | F-SYS-003, F-API-036 | An OTP created at an SR's login attempt is stored encrypted and is visible only to the TSO whose scope contains that SR | changed |
| F-API-003 | POST /auth/bind-device *(theme day 3)* | API | backend | S | F-SYS-003 | POST /auth/bind-device with {deviceUuid, otp} and the device public key binds once and refuses the sixth wrong OTP; the OTP cannot be used twice | changed |
| F-API-014 | GET /dashboard/national?date= | API | backend | M | F-SYS-015, F-SYS-005 | GET /dashboard/national returns scoped rollups with a date-range filter in under one second, cached 30 s per scope hash, with an as-of stamp | parity |
| F-API-019 | GET /outlets/nearby?lat&lng&radius *(theme day 5)* | API | backend | S | F-SYS-005 | GET /outlets/nearby returns outlets within 50, 100 or 300 m of a point from a geo index with a marker cap; a caller outside the scope gets no rows (scope-leak harness) and the body equals the seeded data | parity |
| F-API-022 | GET and POST /leave, POST /leave/:id/decision *(theme day 5)* | API | backend | S | F-SYS-005 | GET and POST /leave and POST /leave/:id/decision apply (idempotent by client uuid), list and decide; a caller outside the scope gets no rows (scope-leak harness) and the body equals the seeded data | parity |
| F-API-028 | GET /ops/sync-health, GET and POST /ops/quarantine | API | backend | M | F-SYS-015, F-SYS-014 | GET /ops/sync-health and GET and POST /ops/quarantine return the health figures and apply quarantine actions; a quarantine action on a seeded parked row changes its state and is audited | new-required |
| F-API-032 | POST /feedback, GET /admin/feedback *(theme day 5)* | API | backend | S | F-SYS-005 | POST /feedback and GET /admin/feedback store feedback with an optional image link; the same request replayed changes nothing and the response matches the contract | parity |
| F-API-034 | GET /reports/suspicious-locations | API | backend | S | F-SYS-057 | GET /reports/suspicious-locations returns flagged visits with review events; a caller outside the scope gets no rows (scope-leak harness) and the body equals the seeded data | sponsor |
| F-API-035b | /admin/* CRUD: users, scope, routes, assignments, outlets, classifications, approvals (pilot minimum) | API | backend | L | F-API-035 | /admin/* CRUD for users, scope, routes, assignments, outlets, classifications and the pilot-minimum approvals audits every write | new-required |
| F-API-035c | /admin/* CRUD: products, prices, product tree | API | backend | M | F-API-035 | /admin/* CRUD for products, prices and the product tree audits every write; the same request replayed changes nothing and the response matches the contract | new-required |
| F-API-048 | POST /admin/data-void *(theme day 5)* | API | backend | M | F-API-006, F-SYS-059 | POST /admin/data-void records an audited void with reason and scope, tombstones client uuids and refuses after Final Submit | new-required |
| F-API-055 | POST /outlet-requests/:uuid/verify, /reject, /approve | API | backend | M | F-API-006, F-SYS-005 | POST /outlet-requests/:uuid/verify, /reject and /approve are idempotent by event uuid; approving a closure sets the outlet closed | new-required |
| F-API-058 | GET /admin/config/whatif?key=&scope=&value=&days= | API | backend | M | F-API-037 | GET /admin/config/whatif re-evaluates stored fixes under a candidate radius and reports how many visits would change verdict | sponsor |
| F-API-063 | GET /admin/config/reach/{version} and GET .../reach/{version}/pending?zone= | API | backend | S | F-API-041 | GET /admin/config/reach/{version} and .../pending list targeted, applied and pending devices and their lag; the counts equal the acknowledgements stored for that version | sponsor |
| F-API-080 | POST /admin/price/preview, publish, correct | API | backend | M | F-API-035c | POST /admin/price/preview and publish return the affected SKUs, price types, outlets and devices and publish idempotently by batchUuid | new-required |
| F-API-083 | GET /config/check | API | backend | S | F-API-040 | GET /config/check answers 304 or the delta inline for If-None-Match on the config version at most once per gap; a call inside the minimum gap is answered 304 from the cache | sponsor |
| F-SYS-026 | Sync-health dashboard | SYS | backend | M | F-SYS-015, F-SYS-050 | The ops endpoint returns login %, submit % (of logged-in), final-submit by zone, trickle latency, quarantine count and config ack % for the seeded day equal to hand-computed values | new-required |
| F-SYS-054 | Release gating and kill switches | SYS | backend | M | F-ADM-027, F-SYS-053 | min_version blocks a new day's login but not upload, blocked_versions blocks new captures only, and neither wipes data | new-required |
| F-SYS-086 | Route-day and web-entry dirty-key triggers *(theme day 3)* | SYS | backend | M | F-SYS-015 | A change to route_day, final-submit, route-assignment or data-void enqueues its dirty key so the dashboard tile updates within 60 s | sponsor |
| N-028 | Server GNSS-consistency rules: satellite count and signal strength checked against the claimed position and speed; scored into risk_signal *(theme day 3)* | SYS | backend | M | N-025, F-SYS-013 | A fix claiming an open-sky position with zero satellites, and a fix whose signal strength is identical across 20 visits, each raise a risk signal; a normal fix raises none | sponsor |
| N-048 | Report handlers batch A (STD Memo, SR Efficiency, Route-wise STD, Route-wise Memo, CPR and BSR, By Outlet, By Outlet By Day, Online/Offline, Task Planner, By-Route Geo Capture) | WEB | backend | L | F-API-017, F-SYS-015 | Each handler returns the seeded day's rows equal to hand-computed control totals, scoped to the caller, in json and xlsx | sponsor |
| N-052 | Report handlers batch C (QC reports, Target reports, Astha, Campaign Gift, Diamond League, Superstar, Memo-number gaps) *(theme day 5)* | WEB | backend | M | F-API-017, F-SYS-015 | Each handler returns the seeded data equal to hand-computed control totals, scoped to the caller, in json and xlsx | sponsor |
| F-SYS-053 | Config propagation to the field | SYS | android-core | M | F-API-040, F-API-041 | A radius changed in the portal reaches the phone through X-Config-Version and the delta on its next request, is acknowledged on the next sync, and is stamped on the next visit as radius_m_used | sponsor |
| F-SYS-075 | Employee-location notice and consent *(theme day 3)* | SYS | android-core | S | F-SYS-001 | The first login shows the Bangla and English location notice, the acceptance is stored and uploaded once, and no sale starts before acceptance when the setting requires it | new-required |
| F-SYS-092 | Resume config check | SYS | android-core | S | F-API-083 | On resume (online, last contact older than the gap) one conditional GET returns 304 or the delta, at most the daily cap, and there is no timer | sponsor |
| N-034 | Managed app update on enrolled phones: silent install through a package-installer session with SHA-256 check, replacing the unknown-sources path *(theme day 3)* | SYS | android-dpc | M | N-029, F-API-029 | A newer SR build published in the portal installs on an enrolled phone without the unknown-sources prompt, keeps pending rows and the device-owner status, and a wrong checksum is refused | sponsor |
| F-SR-002 | Device OTP prompt *(theme day 3)* | SR | android-sr | S | F-SYS-003 | On an unbound phone four OTP boxes and Verify appear; wrong, expired and too-many-attempts texts show in Bangla and English and a correct OTP binds the phone | changed |
| F-SR-007 | Settings: Logout *(theme day 3)* | SR | android-sr | S | F-SYS-022 | Settings Logout asks for confirmation and an SR logout keeps the local database; checked on the Galaxy A06 against the seeded day, in Bangla and in English, and the state survives a kill and relaunch | parity |
| F-SR-012 | Attendance check-out *(theme day 3)* | SR | android-sr | S | F-SR-011 | Check-out is enabled from 17:00 corrected Dhaka time, press-and-hold shows the day-complete message and queues an outbox event | parity |
| F-SR-026 | Credit (বাকি) with partial payment *(theme day 3)* | SR | android-sr | M | F-SR-025, F-SYS-060 | The credit checkbox asks for the paid amount (at least 0 and below the total, two decimals), shows the due to the paisa and the memo records is_credit, paid and due | parity |
| F-SR-029 | Zero sale *(theme day 3)* | SR | android-sr | S | F-SR-025, F-SR-057 | Proceed with no SKU asks to sell zero, writes a memo row with line_count 0 that consumes a number, counts as a visit and a no-sale and is printable | parity |
| F-SR-033 | Sale edit *(theme day 3)* | SR | android-sr | L | F-SR-030, F-SR-027 | Edit works only inside the outlet geofence and before QC, asks one of three reasons, reopens the sale pre-filled with the outlet read-only, and the new memo supersedes the old one and adjusts the due; the server re-checks the geofence | parity |
| F-SR-036 | Summary and summary print *(theme day 3)* | SR | android-sr | M | F-SR-028 | Summary shows per-SKU memo count, quantity, value, discount, discounted value and return, per-category totals and the grand total (4,391.00 for the seeded day) and prints as the day-summary slip | parity |
| F-SR-038 | Outlet request: permanently closed *(theme day 3)* | SR | android-sr | S | F-SYS-008 | Permanently closed shows read-only fields, asks 'this shop will be permanently closed', queues the request and warns about open dues | parity |
| F-SR-040 | Own-request status *(theme day 3)* | SR | android-sr | S | F-SR-037 | The SR's own requests show pending, verified, approved or rejected and a rejected one shows its reason; checked on the Galaxy A06 against the seeded day, in Bangla and in English, and the state survives a kill and relaunch | new-required |
| F-SR-053 | Returns and damaged goods after QC *(theme day 3)* | SR | android-sr | S | F-SR-027, F-SR-050 | Faulty sticks collected at QC leave the SR's stock as a qc_return movement so Current stock and the Summary Return column stay correct | new-required |
| F-SR-063 | Offline day start and stale-bundle banner *(theme day 3)* | SR | android-sr | M | F-SYS-006 | With no signal at day open a 1-day-old bundle sells with a banner and flags rows bundle_stale, a 3-day-old bundle shows memos and dues read-only, and check-in still works in both cases | new-required |
| F-SR-067 | Sales Journey tile *(theme day 3)* | SR | android-sr | M | F-SR-016, F-SR-057 | The Sales Journey tile opens a route-progress view (assumed) listing today's planned outlets with visited, not-visited and sold status and counts equal to the KPI strip | screenshot |
| F-SR-069 | Home money summary cards *(theme day 3)* | SR | android-sr | M | F-SR-025, N-003 | The two Home money cards show per-category value, gross, discount, QC and net, and total discount, DRP discount, total QC, grand total and total taka; the seeded day gives 4,828.50, 437.50 and 4,391.00 with one Bangla label per term | screenshot |
| F-SR-072 | Identity confirmation on a shared phone *(theme day 3)* | SR | android-sr | S | F-SYS-052 | The first capture of a business date on a phone with two bound users asks 'are you <name> (<code>)?' and a different assignee is stored as acting_for_user_id | new-required |
| N-040 | Outlet menu fourth tile Cluster: change-cluster outlet request from the SR app *(theme day 3)* | SR | android-sr | M | F-SR-039, F-SR-079 | The Outlet menu shows four tiles in the screenshot order; Cluster lets the SR pick an outlet and a new cluster with a reason and queues an outlet_change_request of type cluster offline | screenshot |
| F-AMO-001 | Home header and tile grid *(theme day 5)* | AMO | android-amo | M | F-AMO-044, F-SYS-006 | The AMO home shows the header, the tile set resolved for the user (17 for a full AMO, 13 for an SS user) and a badge on Outlet only when pending SR verification requests are above zero | changed |
| F-AMO-003 | Attendance (press-and-hold) *(theme day 5)* | AMO | android-amo | S | F-SR-011 | AMO check-in and check-out behave as the SR's (address, hold-to-confirm, four states, check-out from 17:00, coordinates offline) | parity |
| F-AMO-005 | Manual Override (outlet photo) *(theme day 5)* | AMO | android-amo | S | F-SR-018 | Manual Override takes an outlet photo, raises a location-change request, lets the call proceed and is capped per day | changed |
| F-AMO-013 | View previous sale data (date picker) *(theme day 5)* | AMO | android-amo | S | F-SR-054 | View previous sale data shows an outlet and date aggregate from the local window with a GET /memos fallback and an exact footer total | parity |
| F-AMO-014 | Memo menu: Print, Edit, Mark paid *(theme day 5)* | AMO | android-amo | M | F-SR-030, F-SR-032 | The AMO Memo menu prints, edits and marks paid as the SR's does with the credit-memo and cash-memo layouts, and the due label covers the AMO's own credit memos only | parity |
| F-AMO-015 | Summary and print *(theme day 5)* | AMO | android-amo | S | F-SR-036 | The AMO Summary shows the whole-day table per SKU plus Total, Discount and Grand total and prints; checked on the Galaxy A06 against the seeded zone, in Bangla and in English | parity |
| F-AMO-018 | Task Delegation: assigned list *(theme day 5)* | AMO | android-amo | S | F-API-026 | The Assigned Tasks tab lists outlet, text, status and completion date with resolutions; checked on the Galaxy A06 against the seeded zone, in Bangla and in English | parity |
| F-AMO-019 | Task Delegation: assign (+) *(theme day 5)* | AMO | android-amo | M | F-API-026, N-037 | Assign Task picks route or section and outlet, shows the read-only outlet card, takes task type, date and description, saves with the success toast, and the assigned SR gets the task and a push | parity |
| F-AMO-023 | Verify: outlet closure *(theme day 5)* | AMO | android-amo | S | F-API-055, F-SR-038 | Closure verification shows the read-only request with balance and points and Save marks it verified; checked on the Galaxy A06 against the seeded zone, in Bangla and in English | parity |
| F-AMO-024 | Verify: info change *(theme day 5)* | AMO | android-amo | M | F-API-055, F-SR-039 | Info-change verification shows the old versus new fields and Save marks the request verified; checked on the Galaxy A06 against the seeded zone, in Bangla and in English | parity |
| F-AMO-025 | AMO own: New shop *(theme day 5)* | AMO | android-amo | S | F-SR-037 | The AMO's own New shop request goes to the web approver without an AMO verification step; checked on the Galaxy A06 against the seeded zone, in Bangla and in English | parity |
| F-AMO-026 | AMO own: Permanent close *(theme day 5)* | AMO | android-amo | S | F-SR-038 | The AMO's own permanent-close request confirms, queues and warns about open dues; checked on the Galaxy A06 against the seeded zone, in Bangla and in English | parity |
| F-AMO-027 | AMO own: Info change *(theme day 5)* | AMO | android-amo | S | F-SR-039 | The AMO's own info-change request edits the fields, captures GEO and photo, confirms and queues; checked on the Galaxy A06 against the seeded zone, in Bangla and in English | parity |
| F-AMO-028 | Update Base (location recalibration) *(theme day 5)* | AMO | android-amo | M | N-053, F-SR-079 | Update Base confirms, takes a photo, lets the AMO pick the exact point on the map and routes the change through approval with the 100 m, move-limit and no-mock guards; offline it falls back to the current fix | changed |
| F-AMO-029 | AMO stock load and stock memo *(theme day 5)* | AMO | android-amo | S | F-SR-014 | The AMO stock screen has Issue and Stock columns, category totals and Save and Print with the same re-save rule as the SR | parity |
| F-AMO-030 | AMO Sales Submit (extended reconciliation) *(theme day 5)* | AMO | android-amo | M | F-SR-035, F-AMO-039 | The AMO Sales Submit shows the reconciliation rows (outlet, sale, stock, QC, promotion, distribution and OOS, price compliance, joint call, survey), warns about unpaid dues and writes supervisor_day | changed |
| F-AMO-038 | Exceptions screen *(theme day 5)* | AMO | android-amo | M | F-SYS-057 | The Exceptions screen lists today's fraud and anomaly signals for the zone (geo, mock, teleport) and review, dismiss and confirm work offline as idempotent events | sponsor |
| F-AMO-040 | SS designation and per-user menu variants *(theme day 5)* | AMO | android-amo | S | F-AMO-001 | Team Performance and SR Stock appear only when the AMO has subordinate SRs in scope and Astha only when Astha outlets are in scope | parity |
| F-AMO-049 | AMO stock "correct total" *(theme day 5)* | AMO | android-amo | S | F-SR-081 | The AMO stock screen has the same correct-total path as the SR's; checked on the Galaxy A06 against the seeded zone, in Bangla and in English | new-required |
| N-053 | Google Maps SDK component for the AMO and TSO apps: lite mode, loaded only when its screen opens, bounded tile cache, list fallback offline *(theme day 5)* | SYS | android-amo | M | N-001 | The map screen opens only from its tile, uses lite mode, and with the network off falls back to the last cached list with the age of each fix | sponsor |
| F-TSO-001 | Login (dark theme) and logout *(theme day 5)* | TSO | android-tso | S | F-SYS-001 | The TSO app shows the dark navy login and a Log Out dialog that wipes local data only on a fully reconciled device; checked on the Galaxy A06 against the seeded zone, in Bangla and in English | changed |
| F-TSO-008 | Leave: list *(theme day 5)* | TSO | android-tso | S | F-API-022 | Leave list cards show dates, reason, days and a status badge naming the approver role; checked on the Galaxy A06 against the seeded zone, in Bangla and in English | parity |
| F-TSO-012 | My Periphery: Retailer radius map *(theme day 5)* | TSO | android-tso | M | N-053, F-API-019 | The Retailer radius map takes zone and radius (50, 100 or 300 m) around one fix and shows capped, clustered markers; phone numbers only for roles allowed PII | parity |
| F-TSO-018 | My Feedback *(theme day 5)* | TSO | android-tso | S | F-API-032, F-SYS-010 | My Feedback takes category, title, description and one compressed image and queues offline with success and failure toasts | parity |
| F-TSO-028 | TSO app chrome (parity) *(theme day 5)* | TSO | android-tso | M | F-TSO-001 | The TSO chrome has the hamburger, greeting, logout icon, blue Home button, seven drawer entries with subtitles and the date formats of the manual | parity |
| F-TSO-029 | My Feedback list *(theme day 5)* | TSO | android-tso | S | F-TSO-018 | My Feedback list shows the TSO's own feedback with title, category, date and status; checked on the Galaxy A06 against the seeded zone, in Bangla and in English | manual |
| F-WEB-001 | Dashboard (home) | WEB | web-dashboard | L | F-API-014, N-047, F-WEB-041 | The Dashboard loads for a seeded TSO scope without a button press and shows the sales tiles, live strike rate (1 of 43 is 2.3 percent), channel tiles, final-submit status, login and submit status, geo fencing and the FF location map, each with its business date and an as-of stamp; p95 under one second from aggregates | changed |
| F-WEB-002 | Browse Retailer (list) | WEB | web-dashboard | M | F-API-010, F-WEB-041 | Browse Retailer returns the seeded outlets for the five geo filters with the manual's columns, masks PII columns by role and logs every Excel export | parity |
| F-WEB-005 | Products: Segment | WEB | web-dashboard | S | F-API-035c | Browse Segment lists category, segment, status and sort read-only; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | parity |
| F-WEB-007 | Products: Variant | WEB | web-dashboard | S | F-API-035c | Browse Variant lists category, segment, brand, variant, status and sort; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | parity |
| F-WEB-008 | Products: SKU | WEB | web-dashboard | S | F-API-035c | Browse SKU shows prices to three decimals (7.935) and only the price types the role may see, with Excel export; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | parity |
| F-WEB-015 | Report: Route-wise STD *(theme day 5)* | WEB | web-dashboard | S | N-048 | Route-wise STD shows route columns then one column per selected SKU for a date range; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | parity |
| F-WEB-041 | Standard scope filter | WEB | web-dashboard | S | F-SYS-005, N-010 | The five-level scope filter is defaulted and bounded by the token, and the client never sends scope ids; a TSO of another territory cannot select this one | parity |
| F-WEB-043 | Web login and session | WEB | web-dashboard | M | F-SYS-001, N-010 | Web login takes a case-insensitive User ID and password with show or hide and Remember me off by default, has no forgot-password link, keeps the access token in memory and requires a second step for admin roles | parity |
| F-WEB-044 | Suspicious-location report | WEB | web-dashboard | S | F-API-034 | The suspicious-location report lists flagged visits and routes with a pattern over time and mock counts; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | sponsor |
| F-WEB-045 | Sync-health dashboard (ops) | WEB | web-dashboard | M | F-SYS-026, F-API-028 | The sync-health dashboard shows login %, submit %, final-submit by zone, trickle latency, quarantine backlog, config ack % and pending photos with drill from zone to route to device | new-required |
| F-WEB-057 | Exceptions page | WEB | web-dashboard | S | F-SYS-057, F-API-034 | The Exceptions page lists risk signals with dismissal review and re-sampling and mirrors the AMO screen; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | sponsor |
| F-WEB-061 | QC Report (Market and Warehouse) *(theme day 5)* | WEB | web-dashboard | S | N-052 | The QC Report shows Market and Warehouse sources separately with Get Excel and Download PDF; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | parity |
| N-047 | Google Maps in the web: loader, map component, key restrictions and a daily cost guard, used by the dashboard FF Geo Location tile, the geofence page and team location | WEB | web-dashboard | M | N-010 | The map component renders seeded outlet pins and fixes in the dashboard, loads only on pages that need it, and the cost guard stops requests above the daily cap | sponsor |
| F-ADM-002 | Route CRUD | ADM | web-admin | M | F-API-035b, N-017 | Route CRUD sets code, name, zone, kind (sr or amo), visit_kind (daily, 3f or 2f) and visit_days, keeps the route name apart from the day label, and a change reaches the next bundle | changed |
| F-ADM-003 | Route assignment (SR, SS, cover) | ADM | web-admin | M | F-API-035b | Route assignment gives an SR a route with valid_from and valid_to, kind primary or cover, rejects overlapping SRs on one route and day and re-attributes nothing historic | changed |
| F-ADM-006 | Sales Plan (zone x SKU) | ADM | web-admin | M | F-API-035b | The Sales Plan page edits a zone's enabled SKUs with the picker tree, saves one zone atomically with audit, can apply to all zones of a territory and an offline phone keeps the old plan with the server flagging sku_not_in_plan | changed |
| F-ADM-007 | User CRUD | ADM | web-admin | M | F-API-035b, F-SYS-059 | User CRUD creates and disables users with role, employee code and designation, issues a temporary password with forced change and disabling revokes the device while pending rows still upload | parity |
| F-ADM-009 | Device management | ADM | web-admin | M | N-045 | Device management lists devices per user with last seen, app version, config version, integrity trust level and printer model, and revoking blocks sync but not local capture | parity |
| F-ADM-013 | Operating-parameter console | ADM | web-admin | L | F-API-037, N-011 | The operating-parameter console lists keys by area with typed validation, a risk class, a mandatory reason, an audit row and a reach widget, and a saved change is visible through the config endpoint | sponsor |
| F-ADM-027 | App release management | ADM | web-admin | M | F-API-029 | App release management uploads an APK per ABI with checksum and size gate, publishes it and sets latest, min_version and blocked versions with adoption by version | new-required |
| F-ADM-030 | Sync quarantine review | ADM | web-admin | M | F-API-028 | The quarantine review lists parked rows by reason with the payload PII-masked and offers re-map, accept, discard and return to device | new-required |
| F-ADM-038 | Config page P1: Config home | ADM | web-admin | M | F-ADM-013 | Config home shows the current config version, pending requests, recent changes and the reach widget; the version and the pending count equal the config service at the same moment | sponsor |
| F-ADM-039 | Config page P2: Geofence radius management (map) | ADM | web-admin | L | F-ADM-012, N-047 | The geofence page edits the radius at each scope level on a map with density view and what-if on stored fixes, and a saved value reaches the phone at its next sync | sponsor |
| F-ADM-040 | Config page P3: Rules and thresholds console | ADM | web-admin | S | F-ADM-013 | The rules and thresholds console edits check-out time, min_version and the other keys by area with typed validation | sponsor |
| F-ADM-041 | Config page P4: Operational switches | ADM | web-admin | S | F-ADM-013 | Operational switches set a kill switch, read-only, sync hold or banner, each with a mandatory duration and an audit row | sponsor |
| F-ADM-044 | Config page P7: Reach and pending devices | ADM | web-admin | S | F-API-063 | Reach shows devices per config version, the acknowledged share and the pending list per zone; the acknowledged share equals the acknowledgements stored for that version | sponsor |
| F-ADM-048 | Config page P11: Device management | ADM | web-admin | S | F-ADM-009, F-API-036 | Device management page shows bound users, last sync, versions, integrity, revoke and the OTP issue and log; revoking a device blocks its next sync and keeps its local data | sponsor |
| F-ADM-049 | Config page P12: Release management | ADM | web-admin | S | F-ADM-027 | The release page shows APK upload, publish, min_version, blocked versions and an adoption chart; publishing a release makes it visible to the update check for the right role and ABI | new-required |
| F-ADM-050 | Config page P13: Sync health | ADM | web-admin | S | F-WEB-045 | The Sync Health page in the portal shows the same figures as the ops dashboard; the figures are identical to the sync-health dashboard at the same moment | new-required |
| N-045 | Enrolment QR page in the admin portal: QR generator (per zone or batch, token expiry) and the enrolled-device list with compliance state | ADM | web-admin | L | N-031, N-011 | The portal produces a QR that enrols a factory-reset phone; the device list shows model, policy version and compliance for it within a minute | sponsor |
| N-046 | App-block list page in the admin portal: package names, groups, allow-list and the default categories | ADM | web-admin | M | N-033, N-011 | Adding a package to the block list in the portal makes the enrolled phone suspend it at its next check-in; the default list matches docs/23 question 1 | sponsor |
| N-042 | Developer-verification check: a release-signed APK installs and updates on an enrolled phone without Google Play *(theme day 3)* | SYS | qa | S | N-029 | A release-signed SR APK installs and upgrades on the enrolled Galaxy A06, A07 and Honor X5c Plus and the result is logged against Google's September 2026 rule | sponsor |
| N-050 | Day 4 ten-minute check script: change the radius in the portal and the phone obeys at its next sync | SYS | qa | S | F-ADM-039, F-SYS-053 | The script changes the radius from 100 m to 50 m in the portal and the next visit on the phone is stamped with 50 m and judged by it | sponsor |

### Day 5 — AMO and TSO apps

Ten-minute check: an AMO verifies an outlet request; a TSO closes a zone-day. 124 rows, 356 agent-hours, 46 sessions.

| Id | Name | Role | Lane | Size | Needs | Acceptance test | Source |
| --- | --- | --- | --- | --- | --- | --- | --- |
| F-WEB-042 | PII gating per role | WEB | backend | M | F-API-010 | SR phones are hidden from the AMO, retailer phones are visible only on route-scoped field screens and NID, TIN and licence columns are envelope-encrypted and role-gated | parity |
| F-ADM-037 | Outlet code assignment rule | ADM | backend | S | F-API-055 | Approving a new outlet request generates the next outlet code from the sequence rule and an approved outlet never changes code | new-required |
| F-API-004 | POST /auth/change-password *(theme day 4)* | API | backend | S | F-SYS-004 | POST /auth/change-password applies the policy and the 24 hour minimum age and records the history of 10; the same password submitted again is refused as one of the last 10 | parity |
| F-API-009 | POST /day/final-submit | API | backend | M | F-SYS-016, F-API-039 | POST /day/final-submit is allowed once per zone and day by primary key, the same client_uuid returns the first success, another uuid gets 409 with the same Bangla text, and later batches are accepted and flagged after_final_submit | parity |
| F-API-011 | GET /routes *(theme day 4)* | API | backend | S | F-SYS-005 | GET /routes returns the scoped routes and assignments; a caller outside the scope gets no rows (scope-leak harness) and the body equals the seeded data | parity |
| F-API-012 | GET /targets *(theme day 4)* | API | backend | S | F-API-021a | GET /targets returns scoped targets at variant, brand, category or SKU level; a caller outside the scope gets no rows (scope-leak harness) and the body equals the seeded data | parity |
| F-API-015 | GET /dashboard/daily-tracking?date= and POST .../action | API | backend | M | F-SYS-015 | GET /dashboard/daily-tracking returns buckets with the exception bucket and POST .../action stores a take-action note | parity |
| F-API-016 | GET /dashboard/live?zone= | API | backend | M | F-SYS-015 | GET /dashboard/live returns zone and territory tiles from the same aggregates as the web with an as-of stamp; a caller outside the scope gets no rows (scope-leak harness) and the body equals the seeded data | parity |
| F-API-017b | GET /reports/std-memo, /reports/sales-summary (AMO app reports) | API | backend | S | F-API-017 | GET /reports/std-memo and /reports/sales-summary return the two AMO report reads for the AMO's scope; a caller outside the scope gets no rows (scope-leak harness) and the body equals the seeded data | new-required |
| F-API-018 | GET /app/home?role=&date= | API | backend | M | F-SYS-015, F-SYS-005 | GET /app/home returns the KPI strip and tiles scoped to the SR, zone or territory and the SR's local strip equals it after sync | parity |
| F-API-020 | GET and POST /visit-plans | API | backend | S | F-SYS-005 | GET and POST /visit-plans set and list plans as a union by outlet, idempotent by client uuid; a caller outside the scope gets no rows (scope-leak harness) and the body equals the seeded data | parity |
| F-API-020b | GET /routes/:id/assignments *(theme day 4)* | API | backend | S | F-SYS-005 | GET /routes/:id/assignments returns the SR or AMO assigned and shows SR Not Set as normal for AMO routes; a caller outside the scope gets no rows (scope-leak harness) and the body equals the seeded data | parity |
| F-API-023 | GET /team/locations?zone= | API | backend | S | N-036 | GET /team/locations returns the last synced fix per SR in scope with its age and source; a caller outside the scope gets no rows (scope-leak harness) and the body equals the seeded data | changed |
| F-API-024 | GET /team/stock?zone=&date= | API | backend | S | F-SYS-005 | GET /team/stock returns an SR's lifted stock for a date without the SR's phone number; a caller outside the scope gets no rows (scope-leak harness) and the body equals the seeded data | parity |
| F-API-027 | GET /tutorials | API | backend | S | F-SYS-005 | GET /tutorials returns the video and manual list; a caller outside the scope gets no rows (scope-leak harness) and the body equals the seeded data | parity |
| F-API-030 | POST /support/pda-upload | API | backend | S | F-SYS-005 | POST /support/pda-upload returns a SAS for a size-capped file in Blob; the same request replayed changes nothing and the response matches the contract | parity |
| F-API-035a | /admin/* CRUD: offers, surveys, rubrics, content, programme definitions *(theme day 4)* | API | backend | M | F-API-035 | /admin/* CRUD for offers, surveys, rubrics, content and programme definitions audits every write and the next bundle carries the change | new-required |
| F-API-039 | GET /day/final-submit/preview?zoneId= | API | backend | S | F-SYS-016 | GET /day/final-submit/preview returns salesDate, alreadySubmitted, submittedAt and routes with the FF name or null within the caller's scope | new-required |
| F-API-042 | GET /config/public *(theme day 4)* | API | backend | S | F-API-037 | GET /config/public is cached by Front Door for 60 s and returns min_version, banner and the helpdesk number; the response is served from the Front Door cache for 60 s and carries no user data | sponsor |
| F-API-059 | GET /admin/config/blast-radius?scope_type=&scope_id= *(theme day 4)* | API | backend | S | F-API-037 | GET /admin/config/blast-radius returns the zones, routes, outlets, users and devices a change would touch and its counts equal the devices targeted at commit | sponsor |
| F-API-061 | POST /admin/config/requests, GET .../requests?status=, POST .../{id}/approve, reject, cancel, adopt, break-glass *(theme day 4)* | API | backend | M | F-API-037 | POST /admin/config/requests and the approve, reject, cancel and break-glass actions run the change-request workflow with every transition audited | sponsor |
| F-API-064 | GET and POST /admin/permissions *(theme day 4)* | API | backend | S | F-API-037 | GET and POST /admin/permissions list the admin roster and a grant is a high-risk change request; a grant creates a pending change request, not an immediate permission | sponsor |
| F-API-086 | POST /admin/devices/{id}/replace | API | backend | S | N-031, F-SYS-003 | POST /admin/devices/{id}/replace offers upload-first or revoke-now for the old phone and issues the OTP for the new one | sponsor |
| F-SYS-004 | Change password and policy *(theme day 4)* | SYS | backend | S | F-SYS-001 | Change-password rejects fewer than 12 characters, a missing case or digit, any of the last 10 passwords and a change within 24 hours, and stores an Argon2id hash for a compliant one | parity |
| F-SYS-034 | Target sanity guards *(theme day 4)* | SYS | backend | S | F-API-021a | A negative target is rejected at entry and a zero or null denominator renders a dash instead of 0 percent; a stored zero target renders a dash on the web, the SR and the TSO surfaces | changed |
| F-SYS-061 | Loyalty ledger earning and expiry job | SYS | backend | M | F-SR-021, F-API-006 | 50 points post once per survey response uuid even when the batch is replayed, the nightly job expires points past their date and an overdraw the device could not know about is accepted and flagged | new-required |
| F-SYS-069 | Memo sequence gap report | SYS | backend | S | F-SYS-027 | A number deliberately removed from a seeded sequence appears in the nightly gap detector and a sale_abort record explains a burned number | new-required |
| N-044 | Cluster request handling on the server: request type cluster, verification and approval, cluster history so past sales keep the cluster they were made in *(theme day 4)* | SR | backend | S | N-040, F-API-055 | Approving a cluster request changes the outlet's current cluster, writes a history row and leaves yesterday's memos in the old cluster | screenshot |
| N-051 | Report handlers batch B (Data Entry Log, Final Submit Log, GIGO, DSS, DS-RRS, Top Sheet, Daily Tracking, Leaderboard, AMO Call Report, SR Outlets, Discount, Free Sample) | WEB | backend | L | F-API-017, F-SYS-015 | Each handler returns the seeded day's rows equal to hand-computed control totals, scoped to the caller, in json and xlsx | sponsor |
| F-SYS-020 | In-app updater | SYS | android-core | M | F-API-029, N-034 | On a non-enrolled test phone the update check finds a newer release, downloads it resumably with SHA-256 verification and migrates the local database keeping pending rows; a forced min_version blocks a new day but not an open offline day | changed |
| F-SYS-021 | PDA to Support (send data file) | SYS | android-core | S | F-API-030 | Settings PDA to Support sends the data file with app version and last sync; offline it queues with a visible state and uploads on reconnect; success and failure are shown | parity |
| F-SYS-024 | Activity log | SYS | android-core | S | F-SYS-008 | Screen and action events travel inside the next batch (no extra request) and appear once in activity_log; replaying the batch does not duplicate the events | new-required |
| F-SYS-028 | Local working-data purge | SYS | android-core | S | F-SYS-008 | Synced rows older than 7 business days are purged and rows that are unsynced or rejected are never purged; a seeded 8-day-old synced row is purged and an 8-day-old unsynced row is kept | changed |
| F-SYS-074 | Map component and geocoding | SYS | android-core | M | N-053 | The attendance address resolves online only with coordinates as the fallback, the map provider and tile-cache cap come from config, the map loads only when its screen opens and there is no always-on map in the SR app | changed |
| F-SR-004 | In-app update prompt | SR | android-sr | S | F-SYS-020 | The Update Available page shows release notes and percent progress and a forced update splash blocks a new day but not an open offline day | changed |
| F-SR-006 | Settings: PDA to Support | SR | android-sr | S | F-SYS-021 | Settings PDA to Support shows progress and the result and queues the file offline; checked on the Galaxy A06 against the seeded day, in Bangla and in English, and the state survives a kill and relaunch | parity |
| F-SR-020 | AV and KV during the call | SR | android-sr | M | F-SR-060 | An outlet's assigned AV then KV then survey then sale play in that order from the Wi-Fi-downloaded cache, each view is logged offline and a missing item is skipped without blocking the sale | parity |
| F-SR-021 | POSM survey and photo | SR | android-sr | M | F-SR-060, F-SYS-030 | The POSM survey shows Q1 and the Q1.1 photo only when Q1 is yes, asks the confirmation, and a replayed upload posts the 50 points once | parity |
| F-SR-041 | Astha: route information | SR | android-sr | M | F-API-021a | Astha route information shows year, quarter and month chips with STD target per brand (target, achievement, remaining, percent) and one All Brand memo row; a target of 0 shows a dash | parity |
| F-SR-042 | Astha: shop information | SR | android-sr | M | F-SR-041 | Astha shop information lists Astha retailer cards and the outlet view shows STD target per brand without Remaining and the memo target | parity |
| F-SR-043 | Diamond League redemption | SR | android-sr | L | F-SYS-061, F-SR-055 | Diamond League redemption shows the live Pts Remaining, a gift grid with disabled plus when cost exceeds the balance, one confirm redeems the basket and the points deduct once even if the upload is replayed | parity |
| F-SR-044 | Photo Capture: Astha gift photo | SR | android-sr | M | F-SYS-010, F-SR-079 | Astha gift photo lists outlets with a chosen gift, takes one hand-over photo, shows 'saved' and then 'Photo already captured' with Submit hidden | parity |
| F-SR-045 | Photo Capture: campaign gift verify | SR | android-sr | M | F-SR-044 | Campaign gift verify shows one capture slot per redeemed gift and enables Submit only when every slot has a photo; checked on the Galaxy A06 against the seeded day, in Bangla and in English, and the state survives a kill and relaunch | parity |
| F-SR-048 | Tutorial video list | SR | android-sr | S | F-API-027 | The tutorial list shows from cache with the empty-state text, playback is online only and never autoplays or downloads in the background | parity |
| F-SR-049 | Outlet detail card *(theme day 3)* | SR | android-sr | S | F-SR-016 | The outlet card shows name, owner, phone, cluster, channel, open dues, loyalty points and last visit from the local data | new-required |
| F-SR-055 | Outlet Points | SR | android-sr | S | F-API-005 | Outlet Points shows the league label, balance, expiring points and expiry date as of the previous day and links to redemption | parity |
| F-SR-064 | Device-health line on Home | SR | android-sr | S | N-023 | Home shows battery, free storage, pending rows and last sync with warning thresholds, and the values match the device | new-required |
| N-041 | SR app Google Maps view on demand: outlet pin, geofence circle and the phone's position on the out-of-range screen *(theme day 3)* | SR | android-sr | M | F-SR-017 | On the out-of-range screen the SR taps Map and sees the outlet pin, the radius circle and own position; the map loads only on tap and a map failure never blocks Force Sale | sponsor |
| F-AMO-002 | Home KPI tiles (month to date) | AMO | android-amo | S | F-API-018 | The four month-to-date tiles (today's target, total call target, control-call target, joint-call target) show values from /app/home and are display-only | parity |
| F-AMO-004 | Control Call: route, outlet, geo gate | AMO | android-amo | M | F-SR-017, F-AMO-044 | A Control Call picks route and retailer from the zone-wide list, checks the radius in airplane mode, shows the AMO out-of-range text with Manual Override and Refresh, and records visit kind amo_control_call | parity |
| F-AMO-006 | Control Call: Sale | AMO | android-amo | M | F-SR-025, F-SR-028, F-AMO-004 | A control-call sale uses the SR sale flow against the AMO's own stock, is stored as kind amo_control_call and counts in route STD but not in the SR strike rate | parity |
| F-AMO-007 | SR Perf. Assessment: distribution | AMO | android-amo | S | F-AMO-004 | SR Performance Assessment lists the 15 brands, a tick writes one distribution_check row per brand; checked on the Galaxy A06 against the seeded zone, in Bangla and in English | parity |
| F-AMO-008 | SR Perf. Assessment: OOS | AMO | android-amo | S | F-AMO-007 | OOS can be ticked only for brands ticked as distributed; checked on the Galaxy A06 against the seeded zone, in Bangla and in English | parity |
| F-AMO-009 | SR Perf. Assessment: POSM | AMO | android-amo | S | F-AMO-007 | POSM is tri-state (unanswered, yes, no), Save shows the saved message and returns to the outlet picker with the route kept | parity |
| F-AMO-011 | Joint Call assessment | AMO | android-amo | M | F-AMO-004 | Joint Call shows the route's assigned SR read-only, takes 1 to 5 stars for the three known items and relationship and service quality, saves with the saved message and keeps route and retailer | parity |
| F-AMO-012 | AMO Sale tile (standalone) | AMO | android-amo | S | F-AMO-006 | The standalone Sale tile opens the same geo-gated route and retailer path with Sale preselected and there is no un-gated AMO sale | parity |
| F-AMO-016 | Team Location map | AMO | android-amo | M | N-053, F-API-023 | Team Location lists the SRs then shows a pin per SR with 'last seen HH:MM (n min ago)' and its source, greyed after 120 minutes, with a list fallback offline | changed |
| F-AMO-017 | Team Performance | AMO | android-amo | M | F-API-018, F-API-021a | Team Performance shows Monthly and Till Date tabs, a zone card and a card per route with four category bars (green from 80, amber from 40, red below) and a brand table behind Details | parity |
| F-AMO-020 | Live Dashboard (zone) | AMO | android-amo | M | F-API-016 | Live Dashboard filters by zone and route on an explicit Filter press and shows sales, live strike rate (1 of 81 is 1.23 percent), geo-fencing status and login and submit status with an as-of stamp | parity |
| F-AMO-021 | SR Stock | AMO | android-amo | S | F-API-024 | SR Stock lists SRs with masked phones and shows each SR's lifted stock by SKU for today with per-category totals; checked on the Galaxy A06 against the seeded zone, in Bangla and in English | parity |
| F-AMO-022 | Verify: new outlet | AMO | android-amo | M | F-API-055, F-SR-037 | Verifying a new outlet opens the pending list and the form with route and cluster editable and sub-channel and geo class required; Save marks it verified and Cancel makes no server call | changed |
| F-AMO-032 | Report: STD Memo Report | AMO | android-amo | S | F-API-017b | The STD Memo Report defaults from the first of the month to yesterday with a frozen first column and per-route STD by category and memo count | parity |
| F-AMO-033 | Report: Sales summary up to now | AMO | android-amo | S | F-API-017b | Sales Summary Up To Now shows route cards with CPR and total memos and a brand table per route; a zero target shows a dash | parity |
| F-AMO-034 | Settings | AMO | android-amo | S | F-SYS-019, F-SYS-021 | AMO Settings has language, PDA to Support and Log Out; checked on the Galaxy A06 against the seeded zone, in Bangla and in English | parity |
| F-AMO-035 | Price compliance check | AMO | android-amo | M | F-AMO-004 | Price compliance records the observed shelf price per SKU at a control call against the outlet list and counts in the reconciliation row | manual |
| F-AMO-036 | Visit kind tagging | AMO | android-amo | S | F-AMO-004 | Every AMO outlet open is tagged control or joint so the SR strike rate excludes AMO calls; checked on the Galaxy A06 against the seeded zone, in Bangla and in English | new-required |
| F-TSO-002 | Dashboard: sales by category and achievement | TSO | android-tso | M | F-API-016, F-TSO-027 | The Dashboard shows four sales tiles (cigarette sticks, bidi, lighter in boxes, match in dozens) with one decimal and a ring for achievement against the day target | parity |
| F-TSO-003 | Dashboard: By Channel STD | TSO | android-tso | S | F-TSO-002 | The By Channel STD donut shows STD per channel (GT, DCC, Astha, RCC, MT, HoReCa); checked on the Galaxy A06 against the seeded zone, in Bangla and in English | parity |
| F-TSO-004 | Dashboard: CPR card | TSO | android-tso | S | F-TSO-002 | The CPR card shows target outlet, successful calls and strike rate (2 of 44 is 4.5 percent); checked on the Galaxy A06 against the seeded zone, in Bangla and in English | parity |
| F-TSO-005 | Dashboard: By Segment Value Contribution | TSO | android-tso | S | F-TSO-002 | By Segment Value Contribution shows value versus volume per segment using net value; checked on the Galaxy A06 against the seeded zone, in Bangla and in English | parity |
| F-TSO-006 | Dashboard: By Brand Call/Memo Ratio | TSO | android-tso | S | F-TSO-002 | By Brand Call/Memo Ratio shows memos containing the brand over total active memos per brand; checked on the Galaxy A06 against the seeded zone, in Bangla and in English | parity |
| F-TSO-007 | Dashboard: Login and Bikroy Joma status with drill-downs | TSO | android-tso | M | F-API-016, F-TSO-002 | Login Status (1 of 4 is 25 percent) and Bikroy Joma Status with the Not Logged In and Not Uploaded lists show the right (user, route) pairs for the seeded day | parity |
| F-TSO-009 | Leave: apply | TSO | android-tso | S | F-API-022 | Leave apply takes type, date, days and reason, queues with a client uuid and shows 'not sent yet' until acknowledged | parity |
| F-TSO-010 | Final Submit (per zone per day) | TSO | android-tso | M | F-API-039, F-API-009 | Final Submit takes the five dropdowns from server scope, Get Sales Data shows the already-submitted alert for a repeat, lists routes with FF name or 'SR Not Set', asks an explicit confirm and submits once per zone and day; it is disabled offline | parity |
| F-TSO-011 | My Periphery: My Team map | TSO | android-tso | M | N-053, F-API-023 | My Team shows a map of the SRs' last synced fixes with age and source, grouped by route with a legend, with a list fallback offline | changed |
| F-TSO-013 | My Call: Set Plan | TSO | android-tso | M | F-API-020 | Set Plan takes date, zone and route, multi-selects outlets and saves a union by outlet idempotently; checked on the Galaxy A06 against the seeded zone, in Bangla and in English | parity |
| F-TSO-014 | My Call: My Visit Plan | TSO | android-tso | S | F-API-020 | My Visit Plan shows Pending and Completed tabs and an outlet becomes Completed when its Visit Query is submitted; checked on the Galaxy A06 against the seeded zone, in Bangla and in English | parity |
| F-TSO-015 | Visit Query (retailer questionnaire) | TSO | android-tso | S | F-TSO-014 | Visit Query shows the two printed questions and the delegate radio defaulting to No and Submit marks the outlet Completed without GPS or photo | parity |
| F-TSO-016 | Assign Task from visit | TSO | android-tso | S | F-API-026, F-TSO-015 | Assign Task from a visit needs a task type and date, assigns the SR active on that route and is blocked with a message when the route shows SR Not Set | parity |
| F-TSO-017 | Target Status | TSO | android-tso | M | F-API-021a | Target Status shows Monthly and Till Date tabs, territory and zone cards with achieved over target and percent, and item tables; 3944 gives a till-date target of 3420 | parity |
| F-TSO-021 | Final Submit preview and read endpoint | TSO | android-tso | S | F-API-039 | The preview read returns salesDate, alreadySubmitted, submittedAt and routes with the FF name and feeds the alert at Get Sales Data | parity |
| F-TSO-024 | TSO Settings | TSO | android-tso | S | F-SYS-019, F-SYS-021 | TSO Settings offers language, version and update check, send data file and change password; checked on the Galaxy A06 against the seeded zone, in Bangla and in English | new-required |
| F-TSO-027 | TSO login snapshot and read model | TSO | android-tso | M | F-API-018 | The TSO login snapshot (about 2,500 outlets, under 1 MB gzipped) and the zone, route and outlet pickers load, every aggregate screen shows 'as of hh:mm' and there is no polling timer | new-required |
| F-WEB-004 | Products: Category *(theme day 4)* | WEB | web-dashboard | S | F-API-035c | Browse Category lists name, status and sort read-only; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | parity |
| F-WEB-006 | Products: Brand *(theme day 4)* | WEB | web-dashboard | S | F-API-035c | Browse Brand lists the 17 brands read-only; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | parity |
| F-WEB-011 | Report: Task Planner | WEB | web-dashboard | S | N-048 | Task Planner lists tasks assigned and resolved for the scope and dates equal to the seeded tasks; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | parity |
| F-WEB-012 | Report: By-Route Geo Capture | WEB | web-dashboard | S | N-048 | By-Route Geo Capture lists outlets per route with and without coordinates including placeholders; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | parity |
| F-WEB-014 | Report: SR Efficiency | WEB | web-dashboard | S | N-048 | SR Efficiency shows target outlets, visited, successful, CPR, STD, memos and geo percent per SR for a date range; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | parity |
| F-WEB-016 | Report: Data Entry Log | WEB | web-dashboard | S | N-051, F-SYS-025 | Data Entry Log shows per route DOWNLOAD and UPLOAD groups with MIN, MAX and count in Dhaka time; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | parity |
| F-WEB-018 | Report: CPR and BSR | WEB | web-dashboard | S | N-048 | CPR and BSR shows route-wise strike rate and brand strike rate with both denominators; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | parity |
| F-WEB-019 | Report: By Outlet Report | WEB | web-dashboard | S | N-048 | By Outlet Report takes category, product type, status, sub channel and dates and shows per outlet visits, memos, STD and dues with PII gating | parity |
| F-WEB-021 | Report: GIGO (attendance) | WEB | web-dashboard | S | N-051 | GIGO shows check-in and check-out times and coordinates with accuracy and a locality hint per user for one date; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | parity |
| F-WEB-023 | Report: Discount Report | WEB | web-dashboard | S | N-051 | Discount Report shows offer discount and DRP discount separately by promotion group for the scope and dates; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | parity |
| F-WEB-027 | Report: TSO Top Sheet Performance | WEB | web-dashboard | S | N-051 | TSO Top Sheet Performance shows the territory summary of STD against target, CPR, login and submit and geo percent; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | parity |
| F-WEB-031 | Outlet: SR Outlets Reports | WEB | web-dashboard | S | N-051 | SR Outlets Reports lists New Outlets, Close Outlets and Info Changes for a date range with the manual's columns; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | parity |
| F-WEB-033 | Credentials: change password *(theme day 4)* | WEB | web-dashboard | S | F-SYS-004 | Credentials shows the password guideline and Old, New and Confirm fields and enforces the policy with authored errors | parity |
| F-WEB-034 | Astha Gift Choice Report *(theme day 6)* | WEB | web-dashboard | S | N-052 | Astha Gift Choice Report filters by Gift Status and exports the choices and photo status; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | parity |
| F-WEB-038 | Daily Tracking Dashboard | WEB | web-dashboard | M | F-API-015 | The Daily Tracking Dashboard shows route buckets with the exception bucket distinct from not logged in and a same-time-yesterday comparator | parity |
| F-WEB-039 | Daily Tracking "take action" | WEB | web-dashboard | S | F-WEB-038 | After 17:00 the take-action option stores a note and notifies the route TSO and AMO without reassigning; the note is stored and the route TSO's phone receives the notification | manual |
| F-WEB-056 | Route-wise Memo Report | WEB | web-dashboard | S | N-048 | Route-wise Memo Report shows memo counts per route and product for the scope and dates; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | parity |
| F-TSO-023 | Temporary password reset and unlock *(theme day 4)* | TSO | web-admin | S | F-API-004, F-ADM-007 | A TSO issues a temporary password valid for 24 hours that forces a change and unlocks a locked account in scope, and both actions are audited | new-required |
| F-TSO-025 | Radius edit or propose *(theme day 4)* | TSO | web-admin | S | F-ADM-039 | A TSO changes the radius for the own territory within bounds or proposes a change for approval, and an increase above 150 m escalates | sponsor |
| F-WEB-003 | Retailer detail and edit | WEB | web-admin | M | F-API-035b, F-SYS-059 | Retailer detail shows the four sections and an edit is audited; editing location, name or owner raises a request unless the editor holds approve rights | parity |
| F-WEB-032 | Outlet Approval Panel | WEB | web-admin | M | F-API-055, F-ADM-037 | The Outlet Approval Panel filters New, Close or Info and offers Verify, Reject with a reason and Approve with the confirm dialog; approving a new outlet assigns the code and approving a closure sets it closed | parity |
| F-WEB-051 | Web Final Submit | WEB | web-admin | M | F-API-009, F-API-048 | Web Final Submit shows the back-date banner and DSS advisory, lists routes with Submit and Delete Section Data (an audited void with reason, only before Final Submit) and applies the same server rule as the app | changed |
| F-ADM-004 | Product hierarchy CRUD *(theme day 4)* | ADM | web-admin | M | F-API-035c | Category, segment, brand, variant and SKU CRUD sets status per level, sales_enable, sort, base unit, pack size and type and image, each audited | parity |
| F-ADM-005 | Price management (5 types, effective-dated) *(theme day 4)* | ADM | web-admin | M | F-API-080 | Price management publishes effective-dated prices for the five types stored to three decimals, with a mandatory preview of SKUs, outlets and devices affected, and the change reaches the next bundle | parity |
| F-ADM-008 | User scope assignment *(theme day 4)* | ADM | web-admin | S | N-011, F-API-035b | User scope assignment attaches geography nodes with role-node consistency (AMO zone, TSO territory, DMO division, WM wing) and a temporary acting scope with an end date | parity |
| F-ADM-010 | Outlet CRUD (Retailer, Wholesale outlet) *(theme day 4)* | ADM | web-admin | M | F-API-035b | Outlet CRUD creates, edits, closes and reopens outlets with kind, status, location_confirmed and PII gating, each audited | changed |
| F-ADM-011 | Classification CRUD *(theme day 4)* | ADM | web-admin | S | F-API-035b | Classification pages edit channel, sub-channel and geo classification as code tables so a business user adds a value without a deployment | parity |
| F-ADM-016 | Offer and promotion CRUD *(theme day 4)* | ADM | web-admin | M | F-API-035a | Offer CRUD creates a promotion with bn and en text, validity, qualifying SKU set, ratio and reward SKU, and the next bundle carries it; the seeded offer reproduces 437.50 | new-required |
| F-ADM-021 | Task type CRUD | ADM | web-admin | S | F-API-035a | Task type CRUD edits OOS, General and Irregular Visit with Bangla labels and the types assignable per role; each save adds an audit row with its reason and a user without the role gets 403 | parity |
| F-ADM-023 | QC fault-type administration | ADM | web-admin | S | F-API-035 | QC fault-type administration edits the 11 codes with group MFC or MKT and applies_to app or web; each save adds an audit row with its reason and a user without the role gets 403 | new-required |
| F-ADM-029 | Day reopen and final-submit override | ADM | web-admin | S | F-API-009 | An ops admin reopens a final-submitted zone-day with a reason and the action is audited; late syncs after the final are listed | new-required |
| F-ADM-042 | Config page P5: Change requests and approvals | ADM | web-admin | M | F-API-061 | Change requests show a diff and the blast radius, approve, reject or schedule, with two-person approval for the highest risk class | sponsor |
| F-ADM-046 | Config page P9: Master data CRUD *(theme day 4)* | ADM | web-admin | L | N-011, F-API-035 | The master-data CRUD page gives one navigation to geography, clusters, products, prices, sales plan, routes, assignments, users, outlets, classifications, task types and the holiday calendar, every write audited | sponsor |
| F-ADM-052 | Config page P15: Day control | ADM | web-admin | S | F-ADM-029 | Day control shows reopen of a zone-day, the late-sync queue and the missing check-out list; reopening a zone-day writes an audit row and the late-sync list shows the seeded late batch | new-required |
| F-ADM-064 | Role x menu x action matrix editor | ADM | web-admin | M | F-API-037 | The role by menu by action matrix is data: an admin changes a role's menu and the user sees the change without a deployment | sponsor |
| F-ADM-070 | Outlet reactivation | ADM | web-admin | S | F-API-035b | An admin reopens a wrongly closed outlet with a reason and the outlet returns to active with an audit row; each save adds an audit row with its reason and a user without the role gets 403 | new-required |
| F-ADM-071 | SR transfer between routes | ADM | web-admin | S | F-ADM-003 | An SR is moved to another route from a date, targets stay with the route and the SR's next bundle carries the new route | new-required |
| F-ADM-076 | SR lifecycle wizard (minimal) and user_admin | ADM | web-admin | M | F-ADM-007, F-API-035b | The SR lifecycle covers create, bind, disable and reassign, and a disabled user's phone still uploads earlier rows as parked rows | new-required |
| F-ADM-078 | Replace-device wizard (P11) | ADM | web-admin | M | F-API-086 | The replace-device wizard shows the old phone's pending rows, offers 'upload first' or 'revoke now' and issues the OTP for the new phone | sponsor |
| N-054 | Load-test data generator: 8,500 synthetic users, about 4.6 lakh outlets, routes of the three kinds, a replayable day of calls and visits on a scratch database | SYS | qa | M | N-006, N-008 | The generator fills a scratch database with 8,500 users and 4.6 lakh outlets in under 30 minutes and replays one synthetic day of traffic through the batch endpoint | sponsor |
| N-055 | Day 5 ten-minute check script: an AMO verifies an outlet request and a TSO closes a zone-day | SYS | qa | S | F-AMO-022, F-TSO-010 | The script runs the SR request, AMO verification, web approval and the TSO Final Submit and prints PASS for each step | sponsor |

### Day 6 — Scale, security and battery

Ten-minute check: the load report passes; the battery log for a scripted 8-hour day. 100 rows, 292 agent-hours, 38 sessions.

| Id | Name | Role | Lane | Size | Needs | Acceptance test | Source |
| --- | --- | --- | --- | --- | --- | --- | --- |
| F-ADM-058 | Audited data void (Delete Section Data) *(theme day 5)* | ADM | backend | S | F-API-048 | A data void records route, date, scope and reason and tombstones the client uuids so a late upload of voided rows is rejected, and the void is refused after Final Submit | changed |
| F-ADM-081 | Price-change rails *(theme day 5)* | ADM | backend | S | F-API-080 | A price publish shows the mandatory preview and refuses a change above the configured percent without a second approver | sponsor |
| F-API-013 | GET /leaderboard?view=&level=&productLevel= *(theme day 5)* | API | backend | S | F-SYS-015 | GET /leaderboard returns achievement ranking from rollups for the view and level; a caller outside the scope gets no rows (scope-leak harness) and the body equals the seeded data | parity |
| F-API-017a | GET /reports/memo-number-gaps *(theme day 5)* | API | backend | S | F-SYS-069 | GET /reports/memo-number-gaps returns the gap report from dw for the caller's scope; a caller outside the scope gets no rows (scope-leak harness) and the body equals the seeded data | new-required |
| F-API-021 | POST /targets/*, /target-revisions/* (the write half, 5c; the read half is F-API-021a, the Astha write half F-API-021b, D-533) | API | backend | M | F-API-021a | POST /targets and /target-revisions store target sets and approval events with values at or above 0; a negative target is rejected and an approval event is stored | changed |
| F-API-021b | POST /programs/astha/* (Astha targets and gift choice) | API | backend | S | F-SYS-005 | POST /programs/astha/* stores Astha targets and gift choice idempotently and locks a choice once the SR's photo exists | new-required |
| F-API-038 | POST /admin/data-entry *(theme day 5)* | API | backend | S | F-API-006 | POST /admin/data-entry writes a manual backfill through the ingest path with source manual; the same memo number keyed twice is stored once and flagged source manual | new-required |
| F-API-045 | POST /outlets/outlet-kind/bulk | API | backend | S | F-API-035b | POST /outlets/outlet-kind/bulk marks outlets idempotently by batchUuid with one audit row per outlet; the same request replayed changes nothing and the response matches the contract | new-required |
| F-API-049 | POST /admin/entry-unlock | API | backend | S | F-SYS-059 | POST /admin/entry-unlock creates or expires an audited unlock grant; an expired grant no longer allows back-dated entry | new-required |
| F-API-050 | POST /web-entry/route-day *(theme day 5)* | API | backend | M | F-SYS-005, F-SYS-059 | POST /web-entry/route-day stores one entry per route-day with a client uuid, a re-save replaces with audit and app rows are never added | new-required |
| F-API-051 | POST /web-entry/outlet-sku | API | backend | S | F-SYS-005 | POST /web-entry/outlet-sku stores an Astha entry with a client uuid; the same client uuid sent twice stores one entry | new-required |
| F-API-052 | POST /web-entry/qc *(theme day 5)* | API | backend | S | F-SYS-005 | POST /web-entry/qc stores market and warehouse QC entries with a client uuid as a separate source; the same client uuid sent twice stores one entry | new-required |
| F-API-053 | GET /reports/<name>/print and POST /reports/export *(theme day 5)* | API | backend | M | F-API-017, F-SYS-064 | GET /reports/<name>/print and POST /reports/export give a server-rendered print view and an asynchronous export with a download link, both logged | new-required |
| F-API-054 | POST /targets/upload and GET /targets/sample | API | backend | M | F-API-021 | POST /targets/upload validates an Excel file all-or-nothing and GET /targets/sample returns the template; a file with one bad row is rejected whole and the error sheet names the row | new-required |
| F-API-060 | GET /admin/config/density?scope= and GET /admin/config/calibration?scope= *(theme day 5)* | API | backend | M | F-API-037, F-SYS-015 | GET /admin/config/density and /calibration return the outlet density index and the calibration report from stored fixes | sponsor |
| F-API-062 | GET /admin/config/versions, GET .../versions/{v}, POST .../versions/{v}/revert, POST .../rollback-to/{v} *(theme day 5)* | API | backend | M | F-API-037 | GET /admin/config/versions and POST .../revert and .../rollback-to create a new version and delete nothing; the old value is still in the history after a revert | sponsor |
| F-API-065 | GET and PUT /admin/flags | API | backend | S | F-API-037 | GET and PUT /admin/flags edit the feature-flag matrix by scope and flags never change the shape of captured data; a flag change appears in the next config delta | sponsor |
| F-API-070 | GET /sync/generation | API | backend | S | F-API-006 | GET /sync/generation returns {generation, kind, restore_point_utc, lost_after_utc, minted_at} so a device can re-send rows acked after a restore | sponsor |
| F-SYS-058 | PII read budgets and export log | SYS | backend | M | F-API-017 | A list endpoint masks personal columns by default, enforces an hourly row budget and every export is logged with its filters and a PII flag | new-required |
| F-SYS-063 | Retention, archival and partition job | SYS | backend | M | N-006 | Monthly partitions are created three months ahead, the job writes a manifest and never drops a partition in this build | sponsor |
| F-SYS-064 | Async export job (Excel, PDF, print) *(theme day 5)* | SYS | backend | M | F-API-017 | A 200,000-row export runs as a background job, returns a download link, the xlsx holds the same rows as the screen and formula-injection characters are escaped | new-required |
| F-SYS-067 | Bundle pre-generation and D+1 pre-fetch | SYS | backend | M | F-API-005 | A 22:00 snapshot for the next day exists for every active user and the morning download serves it with ETag and 304; 8,500 simulated bundles finish inside the load-test budget | sponsor |
| F-SYS-084 | Held-rows list *(theme day 4)* | SYS | backend | S | F-SYS-050 | A device with pending rows and no contact for over 4 hours, or with rows after 17:30, appears in the held-rows list with user, route, zone, rows and age | new-required |
| F-SYS-089 | Re-sync late window | SYS | backend | S | F-SYS-047 | Rows older than the window that are re-sent after a restore are accepted and flagged resync_late and none is quarantined | sponsor |
| F-SYS-090 | Working-day windows | SYS | backend | M | F-ADM-033 | The stale-bundle age and the backdate window count working days, and after a 5-day break the first morning starts on the pre-fetched snapshot | new-required |
| F-SYS-091 | Config stamp regress and no-grace tightening | SYS | backend | S | F-SYS-053 | A row stamped below a config version the device had already received is flagged config_stamp_regress and the third such row raises the FS-34 signal | sponsor |
| F-SYS-094 | Route-day void barrier *(theme day 5)* | SYS | backend | S | F-API-048 | After a data void, unsent rows captured before the void are rejected voided_by_admin and rows captured after it are accepted | sponsor |
| F-SYS-096 | Device-captured facts in dw *(theme day 5)* | SYS | backend | M | F-SYS-015 | Integrity, activity and consent records land in their fact tables and the daily screen-use aggregate equals the activity log | new-required |
| N-049 | Phase 2 hooks: a stable external reference on every record and a transactional domain-event outbox that later portals can subscribe to *(theme day 4)* | SYS | backend | M | F-API-006, N-007 | Every ingested record has a stable external reference and a domain event is written in the same transaction; a test subscriber reads memo_created events in order with no gaps and no duplicates | sponsor |
| N-056 | Admission control and backpressure: 429 with Retry-After and jitter, buffered ingest, dashboards shed first, connection pooling limits | SYS | backend | M | F-API-006, F-API-014 | Under a 3 x burst the ingest returns 429 with Retry-After instead of errors, no accepted row is lost, and the dashboard endpoints shed before ingest does | sponsor |
| F-SYS-029 | Bounded image cache *(theme day 3)* | SYS | android-core | S | N-001 | Pack thumbnails cache on disk with an LRU under the configured cap and AV downloads only on Wi-Fi; filling the cache past the cap evicts the oldest images first | parity |
| F-SYS-032 | Error reporting (privacy-aware) | SYS | android-core | S | N-001 | A forced crash in each app and a forced web error reach the error store with device id and stack trace but without phone numbers or outlet names | new-required |
| F-SYS-037 | Wi-Fi-only photo setting *(theme day 3)* | SYS | android-core | S | F-SYS-010 | With Wi-Fi-only on, queued photos stay pending on mobile data and upload on Wi-Fi, and evidence photos fall back to mobile after 6 hours | new-required |
| F-SYS-047 | Server-generation re-sync | SYS | android-core | M | F-API-070, F-SYS-008 | After a server restore announces a new generation, the device re-sends rows acked since the restore point and the registry dedupes them with zero duplicates | sponsor |
| F-SYS-071 | Per-user encrypted local store | SYS | android-core | M | F-SYS-008 | The Room database is encrypted with a per-user Keystore-wrapped key so a copied database file is unreadable, while the APK stays under 30 MB per ABI and the battery gate passes | new-required |
| F-SYS-073 | Urgent config push | SYS | android-core | S | N-038 | An FCM data message for a kill switch makes the phone pull config after a jittered delay and no FCM message ever triggers an upload | new-required |
| F-SYS-079 | Check-out and Sales Submit upload jitter | SYS | android-core | S | F-SYS-008 | When the opening of the 17:00 gate is the only reason to upload, the upload starts after a random 0 to 90 s; Manual Sync ignores the delay | sponsor |
| F-SYS-080 | Generation digest and targeted re-send | SYS | android-core | M | F-SYS-047 | After a restore, per-(date,type) counts and 16 bucket hashes identify only the differing rows and only those rows are re-sent | sponsor |
| F-SYS-081 | Daily field telemetry (R4 and R5 evidence) | SYS | android-core | S | F-SYS-008 | One object of at most 1 KB a day rides the batch with mobile and Wi-Fi bytes, GPS fixes and battery at 08:00, 12:00 and 17:00 and lands in fact_device_day | sponsor |
| F-SR-074 | Sort outlets by distance | SR | android-sr | S | F-SR-017 | With a fix available the picker sorts by distance to the SR, and with the setting off the order is unchanged; checked on the Galaxy A06 against the seeded day, in Bangla and in English, and the state survives a kill and relaunch | sponsor |
| F-AMO-010 | Control Call: Survey | AMO | android-amo | S | F-AMO-043 | The Control Call menu Survey entry opens the AMO survey; checked on the Galaxy A06 against the seeded zone, in Bangla and in English | parity |
| F-AMO-031 | Astha with route selector | AMO | android-amo | S | F-SR-041 | AMO Astha has a route dropdown and the outlet view shows one All Brand memo row; checked on the Galaxy A06 against the seeded zone, in Bangla and in English | parity |
| F-AMO-043 | AMO Survey screen | AMO | android-amo | S | F-AMO-004 | The AMO Survey form reuses the SR survey component, is counted in the reconciliation row and earns no points; checked on the Galaxy A06 against the seeded zone, in Bangla and in English | parity |
| F-WEB-009 | Products: Product Tree *(theme day 4)* | WEB | web-dashboard | S | F-API-035c | Browse Product Tree expands from All Products to Category down to SKU; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | parity |
| F-WEB-010 | Route Planning: Browse Routes *(theme day 4)* | WEB | web-dashboard | S | F-API-011, F-API-020b | Browse Routes lists routes with the AMO and SR assigned and route kind, and an AMO route shows SR Not Set as normal | parity |
| F-WEB-013 | Report: STD Memo Report *(theme day 5)* | WEB | web-dashboard | S | N-048 | STD Memo Report takes the manual's filters and shows STD and memos together or separately, with Get Data and Get Excel | parity |
| F-WEB-017 | Report: Final Submit Log *(theme day 5)* | WEB | web-dashboard | S | N-051 | Final Submit Log shows per zone Done or Not Done with first and last route submit time and count; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | parity |
| F-WEB-020 | Report: Astha Report | WEB | web-dashboard | S | N-052 | Astha Report shows per outlet per brand target, achievement, remaining and percent for year, quarter and months; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | parity |
| F-WEB-022 | Report: Campaign Gift Redemption | WEB | web-dashboard | S | N-052 | Campaign Gift Redemption lists redemptions, points and the photo-verified flag; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | parity |
| F-WEB-024 | Report: By Outlet By Day *(theme day 5)* | WEB | web-dashboard | S | N-048 | By Outlet By Day shows an outlet by day matrix of visited, sold and STD; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | parity |
| F-WEB-025 | Report: Online/Offline Sales *(theme day 5)* | WEB | web-dashboard | S | N-048 | Online/Offline Sales splits memos by whether the device had connectivity at commit; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | parity |
| F-WEB-026 | Report: Free Sample *(theme day 5)* | WEB | web-dashboard | S | N-051 | Free Sample shows free-sample quantities by SKU by route from the is_free lines; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | parity |
| F-WEB-028 | Report: TSO Daily Tracking Dashboard *(theme day 5)* | WEB | web-dashboard | S | N-051 | TSO Daily Tracking Dashboard buckets routes at 100, 90 to 100, 80 to 90 and below 80 percent; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | parity |
| F-WEB-029 | Target: Target Allocation Report | WEB | web-dashboard | S | N-052 | Target Allocation Report shows monthly targets by route and zone for a date and exports to Excel; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | parity |
| F-WEB-035 | Tutorial (manuals) *(theme day 5)* | WEB | web-dashboard | S | F-API-027 | Tutorial lists the four manuals and videos managed in the portal; a video added in the portal appears here | parity |
| F-WEB-036 | Performance Leaderboard *(theme day 5)* | WEB | web-dashboard | S | F-API-013 | Performance Leaderboard shows achievement percent by wing to territory by product level for the mtd, target and volume views | parity |
| F-WEB-037 | Superstar Campaign Report | WEB | web-dashboard | S | N-052 | Superstar Campaign Report shows per outlet category, slab, base target and criteria-met flag; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | parity |
| F-WEB-040 | Excel export of every report *(theme day 5)* | WEB | web-dashboard | M | F-API-017, F-SYS-064 | Every report offers one Get Excel that runs the same query as the screen, with the formula sanitiser and a watermark sheet | changed |
| F-WEB-046 | Leave approval (DMO) *(theme day 5)* | WEB | web-dashboard | S | F-API-022 | A DMO approves or rejects a TSO's leave and the decision reaches the TSO on the next list refresh; the TSO's leave status changes at the next list refresh | manual |
| F-WEB-047 | Final-submit status panel *(theme day 5)* | WEB | web-dashboard | S | F-API-014 | The final-submit panel shows zones submitted versus remaining today with Submit % and Day-completion % and a per-zone badge in the Final Submit picker | parity |
| F-WEB-049 | Loyalty Program: Diamond League Report | WEB | web-dashboard | S | N-052 | The Diamond League report shows an outlet-wise points statement (earned, spent, balance, expiring) for a date range | parity |
| F-WEB-053 | DS-RRS Report *(theme day 5)* | WEB | web-dashboard | S | N-051, F-API-053 | DS-RRS offers Get Data, Get Excel and a Print view that shows discount data before Final Submit; the print view equals the screen totals | parity |
| F-WEB-054 | AMO Call Report (Supervisory Module) *(theme day 5)* | WEB | web-dashboard | S | N-051 | AMO Call Report summarises control calls, joint calls, own sales calls and outlets covered by date or month; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | parity |
| F-WEB-055 | DSS Report (Sales Summary) *(theme day 5)* | WEB | web-dashboard | M | N-051 | DSS shows one row per route with SKU columns and sub-channel summary rows, drills to outlet-wise sales and includes web-entered data within 60 seconds with a data-as-of stamp | parity |
| F-WEB-062 | Route-wise QC Report *(theme day 5)* | WEB | web-dashboard | S | N-052 | Route-wise QC Report takes a date range and QC type and exports to Excel; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | parity |
| F-WEB-064 | Memo number gap report *(theme day 5)* | WEB | web-dashboard | S | F-SYS-069 | The memo-number gap report shows missing sequence numbers per user and day with the sale_abort explanation; the figures equal the control totals of the seeded day and a user outside the scope sees no rows | new-required |
| F-WEB-067 | Geofence calibration report | WEB | web-dashboard | S | F-API-060 | The geofence calibration report shows a distance histogram per geo class and territory from stored fixes with force-sale share | sponsor |
| F-WEB-068 | Date stamp and reason chip on every tile *(theme day 4)* | WEB | web-dashboard | S | F-WEB-001 | Every dashboard tile shows its business date and an as-of stamp and a tile that is not live today carries a reason chip | new-required |
| F-TSO-020 | Astha gift choice per outlet | TSO | web-admin | M | F-API-021b | The Astha Gift Choice panel lists route-scoped Astha outlets with a gift dropdown, saves explicitly and locks the choice once the SR's photo exists | parity |
| F-WEB-030 | Target: Target Revise List (approval queue) | WEB | web-admin | M | F-API-021 | Target Revise List shows the approval queue with month filter, status and an approve or reject action by the approver chain | changed |
| F-WEB-048 | Astha Web Entry | WEB | web-admin | M | F-API-051 | Astha Web Entry shows an outlet by SKU grid for Astha-channel outlets with an explicit Save and flags overlap with app memos instead of adding it | parity |
| F-WEB-050 | Web Entry (Astha-channel outlets are excluded from the classes: Astha quantities are keyed only in Astha Web Entry, rule R-050, D-537, G-qa-67; the Save button is PARITY (manual S-18); the tool is an AGGREGATE entry and cannot restore a credit memo, which is the paper-memo backfill F-ADM-075) (route-day aggregate entry) *(theme day 5)* | WEB | web-admin | L | F-API-050 | Web Entry takes date, geo cascade, classifications and route and a per-SKU grid of Issue, Return, Sale (read-only), Memos and Successful Call at most target outlets; one entry per route-day and a re-save replaces with audit | parity |
| F-WEB-052 | QC Entry (Market QC) *(theme day 5)* | WEB | web-admin | M | F-API-052 | QC Entry takes geo cascade, route and date and a grid of SKU by the five manufacturing and five marketing fault columns saved as a separate web source | parity |
| F-WEB-060 | Warehouse QC Entry *(theme day 5)* | WEB | web-admin | S | F-API-052 | Warehouse QC Entry takes zone and a single date with the same grid as Market QC; each save adds an audit row with its reason and a user without the role gets 403 | parity |
| F-WEB-063 | Report export log viewer | WEB | web-admin | S | F-SYS-058 | The export log viewer shows who exported which report with which filters, rows and whether PII was included; an export made during the test appears here within a minute | new-required |
| F-ADM-014 | Set Target | ADM | web-admin | M | F-API-054, F-API-021 | Set Target takes wing, division, territory and month and a manual route by variant grid or an Excel upload, and every set is a header needing approval with targets at or above 0 | changed |
| F-ADM-015 | Target revision workflow config | ADM | web-admin | S | F-API-021 | The approval levels and approvers of target revisions are configurable with default one level; each save adds an audit row with its reason and a user without the role gets 403 | changed |
| F-ADM-020 | Survey, questionnaire, rubric and content definitions *(theme day 5)* | ADM | web-admin | M | F-API-035a | The survey, rubric and content pages edit POSM questions, the joint-call rubric and AV and KV assets with per-outlet assignment and validity, delivered with the next delta | new-required |
| F-ADM-024 | Data Entry (web back-office and manual backfill) *(theme day 5)* | ADM | web-admin | M | F-API-038, F-WEB-050 | Data Entry groups Web Entry, Final Submit and Astha Web Entry and a dead-phone day can be keyed by printed memo number with source manual, flagged in the Online/Offline report | changed |
| F-ADM-025 | Supervisory Module | ADM | web-admin | S | F-API-035 | The supervisor target table holds control-call and joint-call targets by AMO and month and feeds the AMO home tiles | changed |
| F-ADM-026 | Tutorial content management *(theme day 5)* | ADM | web-admin | S | F-API-027 | Tutorial content management uploads and lists videos and the four manuals per role; each save adds an audit row with its reason and a user without the role gets 403 | parity |
| F-ADM-028 | Feedback inbox *(theme day 5)* | ADM | web-admin | S | F-API-032 | The feedback inbox lists TSO feedback by category and status and sets the status; each save adds an audit row with its reason and a user without the role gets 403 | new-required |
| F-ADM-036 | Dues adjustment and write-off *(theme day 5)* | ADM | web-admin | S | F-SYS-060 | A finance adjustment row corrects a disputed due with a reason and an approver (maker-checker) and the outlet balance changes by that amount | new-required |
| F-ADM-043 | Config page P6: History and rollback *(theme day 5)* | ADM | web-admin | M | F-API-062 | History and rollback lists versions per key and scope, compares them and reverts one to a new version; reverting a value creates a new version and the phone receives it at its next sync | sponsor |
| F-ADM-051 | Config page P14: Data-quality quarantine review *(theme day 4)* | ADM | web-admin | S | F-ADM-030 | The quarantine page lists the same items as the quarantine review and links from sync health; a parked row in the seed appears here and in the review list | new-required |
| F-ADM-055 | Config page P18: Feature flags and waves | ADM | web-admin | S | F-API-065 | The feature-flag page edits flags by scope and shows the matrix; a flag change reaches an enrolled phone at its next sync | new-required |
| F-ADM-056 | Wholesale outlet bulk marking | ADM | web-admin | M | F-API-045 | Wholesale Retailers filters by wholesale status, selects outlets with a basket and live count, confirms in a dialog and submits idempotently with one audit row per outlet | parity |
| F-ADM-057 | Entry unlock grants and back-date window | ADM | web-admin | S | F-API-049 | An entry unlock grant (zone or route, date range, reason, expiry) replaces 'call support' for the web entry back-date cut-off and is audited | new-required |
| F-ADM-059 | Target Excel template, upload and error report | ADM | web-admin | M | F-API-054 | The target template download and upload validate all-or-nothing and return a downloadable error sheet for bad rows; each save adds an audit row with its reason and a user without the role gets 403 | parity |
| F-ADM-060 | Reason-code and list tables *(theme day 5)* | ADM | web-admin | M | F-API-037 | Force, edit, void, exception, visit-outcome, QC-fault, feedback and task reasons are editable code tables with Bangla and English labels | sponsor |
| F-ADM-065 | SKU pack image management *(theme day 4)* | ADM | web-admin | S | F-API-035c | A SKU pack image upload is stored as one compressed thumbnail and reaches the Stock, Sale and Memo screens; each save adds an audit row with its reason and a user without the role gets 403 | screenshot |
| F-ADM-066 | Outlet badge legend *(theme day 5)* | ADM | web-admin | S | F-API-037 | The outlet badge legend edits the colour, programme and label of the four eligibility dots; each save adds an audit row with its reason and a user without the role gets 403 | screenshot |
| F-ADM-067 | Print template management | ADM | web-admin | M | F-API-037, N-018 | Print template management edits memo, stock slip, summary and cancel templates as versioned data and the phone prints the new version after the next sync | sponsor |
| N-057 | Autoscaling rules and a read replica for dashboards; pooling tuned | SYS | infra | M | N-012 | During the load test the API scales out within 2 minutes and the dashboard queries run on the replica; killing one API instance loses no accepted write | sponsor |
| N-062 | Observability: App Insights dashboards, sync-health and error alerts, budget alert checked, release markers | SYS | infra | M | N-012, F-SYS-026 | A seeded error and a seeded stuck batch each raise an alert within 5 minutes and appear on the dashboard | sponsor |
| N-058 | Load and chaos test on Azure Load Testing: design point (4.5 lakh calls and 5 lakh visits a day, 8,500 users, morning download storm, evening upload storm, the 17:00 wave), then 12,750 users and a 3 x burst, with one instance killed | SYS | qa | L | N-056, N-057, F-SYS-067, N-054 | The report shows no lost or duplicated rows, dashboard p95 at most one second throughout, and no sale blocked | sponsor |
| N-059 | Database failover and point-in-time restore drills with a row-by-row reconciliation of device and server rows | SYS | qa | L | N-056, N-057, F-SYS-047 | After a zone failover and a restore the reconciliation shows zero missing and zero duplicated rows once devices re-send | sponsor |
| N-060 | Battery and data runs: a scripted 8-hour day on the Galaxy A06, A07 and Honor X5c Plus with the device-owner policy and battery-saver exemptions | SYS | qa | M | N-039, F-SYS-081, N-035 | Non-screen drain is at most 6 percent of the battery over 8 hours, at most 80 GPS fixes, at most 1 MB a day of mobile data without photos and 3 MB with them | sponsor |
| N-061 | Security review: scope-leak harness over every endpoint, secrets scan, dependency scan, token and device-proof tests, MASVS-style checks on the three APKs | SYS | qa | L | N-014, F-SYS-005, F-SYS-072 | No endpoint returns a row outside the caller's scope across the full harness; no secret or key is in the repository or APKs; all critical findings are fixed or ticketed | sponsor |
| N-063 | Day 6 ten-minute check script: read the load report and the battery log | SYS | qa | S | N-058, N-060 | The script opens the load report and the 8-hour battery log and prints the pass or fail of each budget | sponsor |

### Day 7 — Fixes and the release candidate

Ten-minute check: a real route with printed memos. 3 rows, 10 agent-hours, 2 sessions.

| Id | Name | Role | Lane | Size | Needs | Acceptance test | Source |
| --- | --- | --- | --- | --- | --- | --- | --- |
| N-064 | Release candidate: signed release APKs for the three apps, release manifest, web and API release, published to the release store | SYS | infra | M | N-013, F-ADM-027 | The three signed release APKs install on enrolled phones and an upgrade from the Day 5 build preserves pending rows | sponsor |
| N-065 | Field-test script and checklist: a real route with printed memos, supervisor checks, spoofing and blocking checks | SYS | qa | M | N-064 | The sponsor runs the script on a real route and every line passes or is logged as a defect with the phone, time and photo | sponsor |
| N-066 | Day 7 ten-minute check script: install the release candidate and print a memo | SYS | qa | S | N-064 | The script installs the release candidate on an enrolled phone and prints one memo on the MP-58N | sponsor |

## 4. Days 8 to 10

No planned rows. Reserve for anything that failed a ten-minute check, the cross-region replica and the move rehearsal of `docs/23`.

## 5. Self-check output

The script `check_backlog.py` (kept outside the repository) parses the CSV and asserts: (a) every F-id of `docs/15` is present exactly once, (b) no dependency points to a later day, none is missing, there is no cycle and no same-day chain is longer than 16 hours, (c) the agent-hours per lane per day, and (d) that the counts in this page equal the CSV. Run on 2026-10-05; output:

```
check_backlog.py: docs/25-build-backlog.csv and docs/25-build-backlog.md against docs/15-feature-inventory.md
PASS  CSV is UTF-8 and has no carriage returns
PASS  CSV columns present - id,name,decision,role,lane,size,day,theme_day,dependencies,acceptance_test,source,docs15_status,unknown_assumed,scope_note,drop_reason
PASS  every CSV record has the same number of cells (no embedded newlines)
PASS  ids in the CSV are unique - []
PASS  (a) every F-id of docs/15 is in the CSV exactly once - 509 in docs/15, 509 in CSV, missing [], extra []
PASS  (a) the three decision counts add up
PASS  N ids are N-001 upward with no gap - 66 N rows
PASS  every row is BUILD or DROP
PASS  all parity and changed features are BUILD - []
PASS  every DROP is a new feature and has a reason - []
PASS  every BUILD row has a valid lane, size, day 1 to 7, source and an acceptance test - []
PASS  acceptance tests are sentences
PASS  Day 7 carries only the release candidate, the field test and the check - []
PASS  Days 8 to 10 carry no planned rows
PASS  every BUILD row has a role
PASS  (b) every dependency is a BUILD row - []
PASS  (b) no dependency points to a later day - []
PASS  (b) the dependency graph has no cycle
PASS  (b) no same-day dependency chain is longer than 16 agent-hours - longest 16
PASS  (c) total agent-hours computed from the CSV - 1590
PASS  (c) the markdown hours table equals the hours computed from the CSV for every lane and day
PASS  (c) sessions needed in the markdown = ceil(hours/8) for every lane and day
PASS  (c) no lane has more than 8 agent-hours per parallel session on any day
PASS  (c) lane-days over 8 hours at one session per lane (reported, not hidden) - 35 of 51 lane-days that carry work
      hours per day  : D1=116 D2=196 D3=286 D4=334 D5=356 D6=292 D7=10  total 1590
      sessions per day: D1=17 D2=26 D3=39 D4=43 D5=46 D6=38 D7=2
PASS  (d) BUILD and DROP counts by role in the markdown equal the CSV
PASS  (d) BUILD counts and hours by lane in the markdown equal the CSV
PASS  (d) BUILD counts and hours by day in the markdown equal the CSV
PASS  (d) BUILD rows by lane and day in the markdown equal the CSV
PASS  (d) every BUILD row appears once in the day tables of the markdown - 479 rows in the markdown, 479 BUILD in the CSV
PASS  (d) every row is on the same day in the markdown and the CSV
PASS  (d) every DROP row appears once in the dropped list of the markdown - 96 in the markdown, 96 in the CSV
PASS  (d) the dropped-list category total equals the DROP count

BUILD 479 (F-ids 413, N rows 66), DROP 96, rows 575
RESULT: ALL CHECKS PASSED
```
