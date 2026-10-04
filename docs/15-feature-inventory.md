# 15 — Feature Inventory and Traceability

> - **F-id universe:** 509 features in seven roles (SYS, SR, AMO, TSO, WEB, ADM, API): the 275 of the lens inventory plus 164 added from the four manuals, the field critic, the UI notes, decisions D-01 to D-269 and, at the editorial merge, 11 admin and security endpoints (F-API-058 to F-API-068) and, in round 2, 38 rows for the gap resolution (D-500 to D-553) and, in round 3, 32 rows for the third skeptic pass (F-SR-081, F-AMO-049, F-SYS-086 to F-SYS-097, F-ADM-078 to F-ADM-085, F-API-077 to F-API-086; D-554 to D-601); ranges and counts in s1.2 and s1.3, no id is reused.
> - **Parity rule:** the manual's behaviour is built unless it breaks CLAUDE.md constraints 1 to 8; every row is `parity`, `changed` (a DELIBERATE CHANGE with a D-id) or `new`; the rules now stated for the underspecified areas are in s9 with the new decisions D-320 to D-349.
> - **New from the manuals:** the missing screens, reports and rules (Sale History, Outlet Points, SKU Target, Web Entry, DSS, DS-RRS, web QC, Final Submit preview and the rest) have F-ids in s3 to s8; every gap this document owns is resolved by id in the Traceability section.
> - **Localisation:** the 251 manual message entries (225 app-owned) are seeded in /packages/i18n as bn and en keys <area>.<screen>.<element>; digits follow the UI language; the authored failure set and the review step are in s11 (D-181, D-338, D-344).
> - **RTM model:** each F-id is one rtm.yaml row linking R, G, D, Q, cfg keys, gates and evidence; rtm-check fails CI when a row, a manual screen or an owned gap is uncovered (s12).

## 1 Scope, id scheme and counts

This document is the single list of what the field apps, the web portal, the admin console, the API and the cross-cutting services must do. It is built from the lens inventory (275 rows), the four manual inventories (192 screens, 113 flows, 416 rules, 251 messages, 115 entities), the manual register (104 entries), the field critic, the verification files and the UI notes. Where a source disagrees with the skeleton, the skeleton wins and the difference is an open item.

### 1.1 How to read the tables

A reference written as "s<n>" with no document number means a section of this document; other documents are cited as "doc NN s<n>".

| Column or code | Meaning |
| --- | --- |
| ID | F-<ROLE>-<nnn>; ROLE is SR, AMO, TSO (apps), WEB (dashboards, reports, portals), ADM (admin, master data, config), API (endpoints), SYS (cross-cutting) |
| Off (offline class) | OFF fully offline on the local database; QUE written offline, queued, uploaded later; HYB local first with an online fallback; CAC read snapshot with an as-of stamp; ONL online-only; ONF online first, then cached; n/a server-side |
| Prt | what the feature prints on the 58 mm printer: memo, stock, summary, receipt, cancel slip; "-" none; web: view or Y for a print view |
| Pic | takes or shows a photo: Y, opt (optional), "-" none |
| Geo | Y the feature is gated or branched on the geofence distance check; fix it records a fix but does not gate; "-" none |
| Writes, Reads | working table names (schema v2); "L:" is the local mirror only; "dw." is the analytics layer; doc 16 s2 is authoritative and a mismatch is resolved in its favour and the RTM regenerated |
| cfg keys | config keys the feature depends on, canonical names per D-92; doc 19 s3 holds the registry row for each |
| Refs | G (gap), D (decision), Q or MQ (question), UI-SR-nn (screenshot note) |
| Ph | sub-milestone code (0a to 7e) in which the feature first works end to end; the gate column is the T-id family of that sub-milestone, and doc 20 s3 places the individual T-ids; T-1-24 (kill and relaunch), T-1-35 (print), T-1-41 and T-2-41 (memo parity) are cited where they apply |
| St | `parity` reproduces behaviour observed in the Apsis apps or web (a screen, rule or label), including where only the plumbing changed to meet CLAUDE.md and where the spec lacked it; `changed` differs in a way users can see and carries a D-id; `new` is not observed in Apsis (IMPROVEMENT or an implied need); for F-API rows the status describes the function served, because the new API is our own |
| Evid | manual page ("SR p12"), "spec only" (no manual evidence) or a UI-SR-nn note |

Markers in descriptions follow doc 14: PARITY, IMPROVEMENT, DELIBERATE CHANGE, ASSUMPTION, "unknown; confirm with the business" with the proceed-with default.

### 1.2 Id scheme and ranges

| Role | Lens inventory | Pinned by the skeleton (s5.2) | Minted here | Unused reserve |
| --- | --- | --- | --- | --- |
| F-SYS | 001 to 046 | 047 to 050 | 051 to 085 | 086 to 089 |
| F-SR | 001 to 050 | 051 to 066 | 067 to 080 | 081 to 099 |
| F-AMO | 001 to 036 | 037 to 043 | 044 to 048 | 049 to 069 |
| F-TSO | 001 to 020 | 021 to 026 | 027 to 031 | 032 to 049 |
| F-WEB | 001 to 047 | 048 to 059 | 060 to 072 | 073 to 089 |
| F-ADM | 001 to 037 | 038 to 055 (config pages P1 to P18) | 056 to 077 (072 is page P19) | 078 to 079 |
| F-API | 001 to 038 and 020b | 039 to 046 | 047 to 076 (058 to 068 at the editorial merge, 069 to 076 and the read and write halves 017a, 017b, 021a, 021b, 035a, 035b, 035c in round 2) | 077 to 079 |

Collision rule on record: the manual deltas proposed F-SR-051 to F-SR-053 for other items; the lens meanings stay (stock return, cash deposit, returns and damaged goods) and manual-derived SR items start at F-SR-054. Config page Pn of doc 19 s5.2 is F-ADM-(037+n). D-ids minted by this document are D-320 to D-338 and D-340 to D-349 (D-339 is unused); new gaps are G-15-01 to G-15-09.

### 1.3 Counts

Per role and status, counted from the tables in s2 to s8 (not by hand):

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

Per sub-milestone and role (a feature is counted once, under the sub-milestone in which it first works end to end):

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

Per docs/12 phase, for continuity with the original roadmap:

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

The sub-milestones 0a, 0b, 4d and 7c to 7e carry no feature rows by design: 0a and 0b are tooling and schema, 4d is a scale proof, and 7c to 7e are operations covered by F-SYS-041 to F-SYS-043 and F-SYS-065 (first usable in 6c and 7b).

### 1.4 Coverage statement and how this document closes the gap

Counted by the four manual deltas against docs/01 to 13, docs/22, schema.sql and the lens drafts:

| Manual | Items | Fully covered | Partly | Missing | Contradicted |
| --- | --- | --- | --- | --- | --- |
| SR | 381 | 161 | 169 | 34 | 17 |
| AMO | 327 | 88 | 199 | 24 | 16 |
| TSO | 144 (plus 77 field checks) | 32 | 88 | 6 | 18 |
| Web | 235 | 63 | 118 | 42 | 12 |
| All four | 1,087 | 344 (31.6%) | 574 (52.8%) | 106 (9.8%) | 63 (5.8%) |

Fully plus partly covered is 84.5%. These figures are upper bounds for the spec of record: an item counted as covered when the spec or a lens draft covered it, and the register lists items covered only by a lens (SR S-08, S-09, S-16, R-016, the device-OTP table, the app-release manifest, per-screen cfg keys; on the web the password-history tables, outlet kind, gift assignment, route_log view, target revisions and the report export log). The register gives no count of those plan-only items, so none is stated here.

This document closes the gap in five ways, each checkable:

| Gap | Closed by | Check |
| --- | --- | --- |
| 192 screens (60 SR, 62 AMO, 24 TSO, 46 Web) | every screen maps to at least one F-id in s12.4 | rtm-check: screen with no F-id fails |
| 251 messages | key plan and the 54 parity-critical strings in s11.5; authored set in s11.5 | catalogue completeness gate in each phase |
| 416 rules | rules now stated in s9.1 (84 rows), 39 underspecified rules with defaults in s9.2, and the rule text inside each feature row | each rule is a row of /plan/manual-coverage.yaml mapped to an F-id, a cfg key, a doc 16 table.column or an explicit "not built, because X"; rtm-check rule 11 fails on an unmapped row (D-531) |
| 104 manual register entries | each has one owner; the 70 owned here are resolved by id in the Traceability section; the others are cited in the feature rows that implement them | gaps.yaml (s12) |
| 37-page web spec against the 41-page manual | page parity map in s6.1 (24 pages in both, 17 manual-only, 13 spec-only, 54 in the union) | page map reviewed in 4a |

### 1.5 Features implied but absent from the original spec (now included)

The lens identified 34 features the business implies but docs/01 to 13 never name. Each now has an F-id:

| # | Implied feature | Feature ids | Decision or note |
| --- | --- | --- | --- |
| b1 | End-of-day stock return and reconciliation | F-SR-051 | D-324 |
| b2 | Cash deposit and distributor settlement | F-SR-052, F-WEB-059 | D-325 |
| b3 | Memo reprint rules | F-SR-031, F-SR-066, F-SR-073 | D-322 |
| b4 | Returns and damaged goods after QC | F-SR-053 | D-323 |
| b5 | SR transfer between routes mid-month | F-ADM-071 | D-331 |
| b6 | Substitute SR and same-day cover | F-AMO-037, F-ADM-062, F-ADM-003 | D-85 |
| b7 | Multiple visits to one outlet per day | F-SYS-078, F-SR-017 | D-250 |
| b8 | Holiday and non-selling-day calendar | F-ADM-033, F-SYS-056 | D-28 |
| b9 | Route visit-day exceptions and day exceptions | F-SR-059, F-AMO-045, F-WEB-065 | D-39 |
| b10 | Shared phone, several users per device | F-SYS-052, F-SR-072 | D-66 |
| b11 | Sell to a newly captured outlet before approval | F-SR-078 | D-326 |
| b12 | Printer failure path | F-SR-028, F-SR-073 | D-77, D-76 |
| b13 | Stock insufficiency validation | F-SR-023, F-SR-050 | D-321 |
| b14 | Credit limits and dues ageing | F-SR-026, F-SYS-060, F-WEB-058 | D-320 |
| b15 | Rounding with 3-decimal prices | F-SYS-045 | D-15, D-19 |
| b16 | Password reset, forgot, lockout | F-TSO-023, F-ADM-007 | D-102 |
| b17 | Notifications to the apps without polling | F-SYS-073, F-SYS-006 | D-09, D-84 |
| b18 | Outlet with no saved location | F-SR-017, F-SR-018 | D-95 |
| b19 | Outlet route or cluster change request | F-SR-076, F-SR-037 | D-330 |
| b20 | Outlet reopen and reactivation | F-ADM-070 | D-330 |
| b21 | SR onboarding and offboarding | F-ADM-007, F-ADM-009, F-ADM-068 | D-119 |
| b22 | Sales Submit while offline | F-SR-035 | D-64 |
| b23 | Final Submit with unsubmitted routes | F-TSO-010 | D-198 |
| b24 | Day reopen | F-ADM-029 | D-55 |
| b25 | Credential migration and first login at cutover | F-SYS-066, F-ADM-068 | D-119 |
| b26 | Old app read-only per wave | F-SYS-041, F-SYS-065 | D-148; AKTCL cannot set the Apsis app read-only itself (G-feat-67, owned by doc 20) |
| b27 | Admin audit trail | F-ADM-034, F-SYS-059 | D-113 |
| b28 | Config change propagation to phones in the field | F-SYS-053, F-ADM-012 | D-89 |
| b29 | Manual backfill (Data Entry) | F-ADM-024, F-API-038 | D-40 |
| b30 | Price change mid-day | F-SYS-007, F-SYS-045 | D-129 |
| b31 | Sales-plan change effect on a device holding stock | F-ADM-006 | server flags, never rejects |
| b32 | Supervisor attendance and leave | F-TSO-008, F-TSO-009 | D-337 (SR and AMO leave not built) |
| b33 | Visit kind for AMO calls | F-AMO-036, F-SYS-078 | D-26 |
| b34 | Feedback triage | F-ADM-028, F-TSO-029 | G-feat-48 |

### 1.6 Features added by source

| Source | Added feature ids |
| --- | --- |
| Manual register, SR | F-SR-054 (Sale History), F-SR-055 (Outlet Points), F-SR-056 (SKU target), F-SR-060 (call-start prompt), F-SR-079 (GEO and photo component) |
| Manual register, AMO | F-AMO-039 (supervisor day), F-AMO-040 (SS and tiles), F-AMO-043 (AMO survey), F-AMO-044 (zone-wide bundle) |
| Manual register, TSO | F-TSO-021 (preview), F-TSO-024 (Settings), F-TSO-027 (read model), F-TSO-028 (chrome), F-TSO-029 (feedback list) |
| Manual register, Web | F-WEB-048 to F-WEB-056, F-WEB-060 to F-WEB-062, F-ADM-056 to F-ADM-059, F-ADM-064 |
| Field critic | F-SR-057 to F-SR-059, F-SR-064, F-SR-070 to F-SR-073, F-SR-076, F-SR-078, F-AMO-037, F-AMO-041, F-AMO-042, F-AMO-045, F-AMO-046, F-TSO-026, F-WEB-059, F-WEB-064 to F-WEB-067, F-ADM-062, F-ADM-063, F-SYS-068 to F-SYS-070 |
| UI notes (docs/ui-reference) | F-SR-067 to F-SR-069, F-SR-074, F-SR-075, F-ADM-065, F-ADM-066 |
| Decisions D-01 to D-269 | F-SYS-047 to F-SYS-050, F-SYS-052 to F-SYS-067, F-SYS-071 to F-SYS-078, F-SR-061 to F-SR-066, F-TSO-022, F-TSO-023, F-TSO-025, F-WEB-057, F-WEB-058, F-ADM-060, F-ADM-061, F-ADM-067 to F-ADM-069, F-API-039 to F-API-057 |

Proved by: T-0-40..49 (RTM files exist), T-1-01..04 (counts reconcile through the generated RTM), T-4-41 (page map).

## 2 System features (F-SYS)

Cross-cutting services used by every role. 78 rows; the first 46 are the lens inventory (updated where the manuals or decisions changed them), F-SYS-047 to F-SYS-050 are pinned by the skeleton and F-SYS-051 to F-SYS-078 are minted here. Offline class "n/a" means server-side with no device behaviour. Phase "7a" for the importer rows means the full importer; the skeleton and seed load land in 0b (docs/12 Phase 0).

| ID | Name | Roles | What it does, rules now stated, evidence | Off | Writes | Reads | API | cfg keys | Refs (G, D, Q) | Ph | Gates | St |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-SYS-001 | Username and password login | all-app, web | Authenticates; returns ES256 access token and rotated refresh token carrying role and top scope nodes; uniform error; lockout keyed by (username, device); same usernames as Apsis. First login on a device needs the network, later opens use F-SYS-052. No error text is printed in any manual, so the set is authored (G-man-102). Evid: SR p4, TSO p4, Web p2. | ONF | app.audit_log, app.device.last_seen_at | app_user, user_scope | F-API-001 | cfg.auth.access_ttl_min, cfg.auth.refresh_ttl_days, cfg.auth.lockout_attempts, cfg.auth.web_remember_me_days | G-feat-34, G-man-091, G-man-102; D-101, D-102, D-119 | 0c | T-0-70..76 | parity |
| F-SYS-002 | Token refresh | all-app, web | Silent refresh piggybacked under 5 min to expiry; rotation with reuse detection; a failed refresh never blocks local capture or local reads (R5). | ONF | app.refresh_token | app.refresh_token | F-API-002 | cfg.auth.refresh_ttl_days | D-101 | 0c | T-0-70..76 | parity |
| F-SYS-003 | Device binding by OTP | SR, AMO (TSO views) | Unknown device login creates a 4-digit OTP; the TSO reads it on the web panel (F-TSO-022) and tells the SR; binding is single-use with TTL and attempts; shared phones bind several users (D-66). Online-only. Evid: SR p5-6, Web p46. | ONL | app.device, app.device_otp | app_user, app.device | F-API-003 | cfg.auth.otp_length, cfg.auth.otp_ttl_min, cfg.auth.otp_visible_roles, cfg.auth.max_users_per_device, cfg.auth.max_devices_per_user, cfg.auth.reverify_on_new_version | G-feat-11, G-feat-41, G-man-021; D-80, D-103; Q4 | 0c | T-0-70..76 | changed |
| F-SYS-004 | Change password and policy | all roles | At least 12 characters, lower and upper case and a digit, not one of the last 10, not within 24 h of the last change (web, PARITY); field roles at least 8 with a deny-list (proposed). No mobile app has change password today; the Settings entry on TSO is an IMPROVEMENT (D-204). Evid: Web p45 (M-009..M-013). | ONL | app.password_history | app.password_history | F-API-004 | cfg.auth.password_min_len, cfg.auth.password_history_depth, cfg.auth.password_min_age_h | D-102, D-204, D-244 | 0c | T-0-70..76 | parity |
| F-SYS-005 | Server-side scope resolution | API | Expands user_scope nodes to the subtree and intersects every query via ScopeContext plus row-level security; SR scope is the active route_assignment for the business date; the client never sends scope ids. | n/a | - | user_scope, app.route_assignment, geography | all reads | - | D-106 | 0c | T-0-70..76 | parity |
| F-SYS-006 | Reference bundle download (the login event) | all-app | One gzip payload per role: SR assigned routes for the date; AMO zone-wide outlets, pending requests, SR list (paged, 2 MB gz cap); TSO login snapshot plus pickers. Carries business date, config snapshot, per-outlet eligibility flags and opening balances. A first bundle of the Dhaka day (or an offline day_open) is the Login % event; later deltas are not. | ONF | app.bundle_download, app.route_day.logged_in_at, L:* | reference tables, dw aggregates, cfg snapshot | F-API-005 | cfg.bundle.stale_max_days, cfg.bundle.regen_max_per_s | G-man-065, G-man-069; D-30, D-70, D-72, D-129 | 1a | T-1-20..24, 35, 41 | changed |
| F-SYS-007 | Bundle delta refresh | all-app | ?since= returns changed rows only; a delta never counts as a login; price and offer changes apply to new memos only. | ONL | app.bundle_download | updated_at on reference tables | F-API-005 | cfg.bundle.stale_max_days | G-feat-52; D-30 | 1b | T-1-25..34, 51..52 | new |
| F-SYS-008 | Idempotent batch upload | all-app | POST /sync/batch carries one ordered flat records[] list with envelope fields; server upserts by client_uuid through ingest_registry, replays a stored response for a repeated batch_uuid, returns accepted, rejected, conflicts, parked, server_totals, day_states. Parents before children; out-of-order tolerated. | QUE | app.ingest_registry, app.sync_batch, app.sync_rejected, all device-originated tables | L:* where sync_state=pending | F-API-006 | cfg.sync.batch_max_rows, cfg.sync.retry_backoff_s, cfg.sync.family_skip_after | G-data-01, G-data-06; D-21, D-59..D-62, D-65 | 1b | T-1-25..34, 51..52 | new |
| F-SYS-009 | Device-versus-server reconciliation | SR, AMO | Per-type counts device versus the last server_totals; row set is config-driven per role and app version (SR 5 rows; AMO 8 or 9); Server column blank with a timestamp before the first sync; a mismatch is visible, never silent. Evid: SR p71-73, AMO p66-68. | OFF | L:* | L:*, last server_totals | F-API-006 | cfg.sync.reconcile_types, cfg.sync.reason_texts | G-man-031; D-222 | 1b | T-1-25..34, 51..52 | parity |
| F-SYS-010 | Media queue and photo upload | all-app | Separate queue of compressed photos (150 KB, long edge 1024 px); direct upload by write-only SAS on Wi-Fi first (evidence falls back to mobile after 6 h); a slow photo never blocks a record. | QUE | app.media_object, outlet_photo, survey_response, redemption, gift_photo, feedback image links | L:media_queue | F-API-007, F-API-057 | cfg.media.photo_max_kb, cfg.media.long_edge_px, cfg.media.wifi_only_default, cfg.media.evidence_mobile_fallback_h | D-75 | 2a | T-2-26, 30, 53, 72 | new |
| F-SYS-011 | Constrained background sync | all-app | WorkManager one-off (network connected) after a failed send plus a 15 min periodic job registered only while rows are pending; no foreground service, no alarms, no timer under 60 s. | QUE | as F-SYS-008 | L:* | F-API-006 | cfg.sync.periodic_min, cfg.sync.batch_max_rows | D-59, D-73 | 1b | T-1-25..34, 51..52 | new |
| F-SYS-012 | Server geo re-check on ingest | API | Recomputes Haversine from the stored fix and the outlet location using the radius resolved for the visit business date; sets the authoritative geo_validated_server; device flag kept separately; a mocked fix is never valid. | n/a | app.visit.geo_validated_server, geo_mismatch | app.outlet, cfg.config_value | F-API-006 | cfg.geo.radius_m, cfg.geo.mock_policy | D-48, D-87, D-117 | 2d | T-2-10..19, 60..69 | new |
| F-SYS-013 | Anti-spoofing plausibility flags | API | Teleport speed, perfect accuracy, zero jitter, one coordinate for a whole route, radio-environment mismatch; signals stored as risk_signal and surfaced to supervisors; only the mock warning is shown to the SR. | n/a | app.risk_signal | app.geo_fix, app.visit | F-API-034 | cfg.geo.mock_policy, cfg.geo.max_speed_kmh, cfg.geo.max_accuracy_m, cfg.sec.fraud.* | D-96, D-109, D-110, D-123 | 2d | T-2-10..19, 60..69 | new |
| F-SYS-014 | Quarantine of invalid references | API | Records whose outlet or SKU is out of scope or unknown are parked, not dropped; admin retry, fix-and-accept, discard (four-eyes for data-entry-class reasons). | n/a | app.sync_rejected, app.sync_conflict | scope | F-API-006 | cfg.sync.reason_texts | D-65 | 1b | T-1-25..34, 51..52 | new |
| F-SYS-015 | Aggregation into fact and aggregate tables | worker | Transactional outbox worker recomputes per dirty (business_date, route) key (never increments); late batches re-aggregate their own business date; zone to wing rollups are views; force sale is photo-valid, never geo-valid. | n/a | dw.agg_daily_route, agg_daily_outlet, agg_daily_route_sku, fact_memo, agg_daily_user, agg_month_zone_product | app.visit, app.memo, app.memo_line, targets | - | cfg.agg.poll_interval_s | D-61, D-140, D-263 | 1c | T-1-01..04, 60..69 | new |
| F-SYS-016 | Day state machine | API, SR, AMO, TSO | route_day owns not_started, logged_in, in_field, synced, sales_submitted; the user owns attendance; the zone owns final_submit and projects it onto route_day; AMO and TSO own supervisor_day; an SR on two routes has two route_day rows. | OFF | app.route_day, app.supervisor_day, app.final_submit | - | F-API-006 (day_states) | cfg.day.checkout_earliest_time, cfg.day.submit_settle_timeout_min | G-man-032; D-27, D-64 | 1c | T-1-01..04, 60..69 | changed |
| F-SYS-017 | Business-date stamping | all-app, API | UTC time plus Asia/Dhaka business_date on every transaction; derived from the trusted capture time (monotonic anchor primary); a 23:55 sale lands on its own date. | OFF | all transaction tables | - | - | cfg.sync.max_clock_skew_min, cfg.sync.max_backdate_days | D-20; CLAUDE.md 7 | 1a | T-1-20..24, 35, 41 | parity |
| F-SYS-018 | Localisation layer and bundled fonts | all-app, web | Message keys <area>.<screen>.<element> in /packages/i18n with bn and en catalogues; one Bengali plus one Latin font; per-role default locale; digit script follows the UI language; no hardcoded strings. Catalogue plan in s11. | OFF | - | L:prefs | - | cfg.app.default_locale, cfg.i18n.digit_script, cfg.i18n.overrides | G-man-101, G-man-103; D-181 | 0c | T-0-70..76 | parity |
| F-SYS-019 | Language toggle | SR, AMO (TSO IMPROVEMENT) | Settings: en or বাংলা, no confirmation, persists per device. TSO has no toggle today and defaults to English (D-181). Evid: SR p77-78, AMO p78. | OFF | L:prefs | - | - | cfg.app.default_locale | G-man-027, G-man-028; D-181, D-204 | 1a | T-1-20..24, 35, 41 | parity |
| F-SYS-020 | In-app updater | all-app | Two stages of one flow: online check, Bangla Install-unknown-apps guidance (canRequestPackageInstalls), resumable download with percent and SHA-256, OS install, then on-device migration with n/m progress preserving pending rows. min_version blocks a new day only; an open offline day finishes. Evid: SR p7-10, AMO p5-7. | ONL | L:prefs | app_release | F-API-029 | cfg.release.min_version, cfg.release.update_prompt_policy, cfg.release.update_wifi_only, cfg.release.finish_offline_day_before_force | G-man-022; D-10, D-79, D-208, D-223, D-224 | 2e | T-2-31..35, 47..49, 53..57 | changed |
| F-SYS-021 | PDA to Support (send data file) | all-app | Uploads the device data and sync file to support with app version and last sync; queued when offline; progress and failure shown; one file name across apps (the manuals use three). Evid: SR p78, AMO p78. | ONF | Blob support container, app.support_upload | L:* | F-API-030, F-API-066 | cfg.support.max_upload_mb, cfg.support.pda_upload_wifi_only | G-man-025 | 2e | T-2-31..35, 47..49, 53..57 | parity |
| F-SYS-022 | Logout | all-app | SR and AMO end the session and keep the local database; TSO wipes all local data only on a fully reconciled device and is refused with "N items not yet sent" plus Sync now and Cancel otherwise. Evid: SR p79, TSO p21. | OFF | L:* (TSO wipe) | L:* | F-API-031 | cfg.app.logout_block_when_pending, cfg.app.logout_wipes_data | G-man-024, G-feat-41; D-69, D-174 | 2e | T-2-31..35, 47..49, 53..57 | changed |
| F-SYS-023 | Runtime permission flow | all-app | Location (precise, while using) blocks Sale and Attendance with a Bangla rationale and a Settings deep link; Bluetooth denied only disables printing; camera only, no RECORD_AUDIO; handles Only-this-time and Location-services-off. Evid: SR p4, p19, p26. | OFF | - | - | - | cfg.geo.require_precise, cfg.app.location_denied_policy | G-man-020; D-74, D-115; Q15 | 1a | T-1-20..24, 35, 41 | changed |
| F-SYS-024 | Activity log | all-app, API | Screen and action audit per user, shipped inside the sync batch (no chatter). | QUE | app.activity_log | - | F-API-006 | - | - | 2e | T-2-31..35, 47..49, 53..57 | new |
| F-SYS-025 | Route log (Data Entry Log source) | API | First and last download and upload time and counts per route and day; MIN is the first and MAX the last event time (the manual sample is swapped, I-33). | n/a | app.route_log | - | - | - | G-man-097; D-240 | 1c | T-1-01..04, 60..69 | parity |
| F-SYS-026 | Sync-health dashboard | ops, ADM | Login %, Submit % (of logged-in), Day-completion %, final-submit by zone, trickle latency, rejected and quarantined counts, config ack %, pending photos; drill zone to route to device. | ONL | - | app.route_day, app.sync_batch, app.sync_rejected | F-API-028 | cfg.sla.*, cfg.ops.dashboard_refresh_min_s | D-45, D-128 | 1c | T-1-01..04, 60..69 | new |
| F-SYS-027 | Memo numbering | SR, AMO, API | New series <username>-<yyMMdd>-<seq3> composed on the device in the memo insert transaction; disjoint 500-number blocks per device-bind ordinal; server keeps it verbatim (unique per business date); imported Apsis memos keep their original number. Failed saves burn a number; gaps are reported (F-WEB-064). | OFF | app.memo.memo_no | app.device.bind_ordinal | - (local in 1a; server verbatim from 1b) | cfg.memo.number_format | G-data-08, G-field-14; D-35; Q5 | 1a | T-1-20..24, 35, 41 | changed |
| F-SYS-028 | Local working-data purge | all-app | Purge by business-date age (cfg.app.local_history_days, 7), never rows that are not synced or are rejected, never "on final submit". | OFF | L:* | - | - | cfg.app.local_history_days | D-83 | 2e | T-2-31..35, 47..49, 53..57 | changed |
| F-SYS-029 | Bounded image cache | all-app | Product thumbnails and AV/KV assets on disk with LRU and a hard size cap; one compressed thumbnail per SKU; AV pre-downloaded on Wi-Fi. | OFF | L:cache | - | - | cfg.app.image_cache_mb | D-12, UI-SR-19 | 2a | T-2-36..44 | parity |
| F-SYS-030 | Photo capture and compression pipeline | all-app | Camera-only for evidence photos; compress on device, strip EXIF, store SHA-256 and perceptual hash; stamp fix with lat, lng, accuracy and mock flag; one retake; release the camera. | OFF | L:media_queue | - | F-API-007 | cfg.media.photo_max_kb, cfg.media.long_edge_px | D-75; G-man-019 | 2c | T-2-26, 30, 53, 72 | parity |
| F-SYS-031 | Integrity signals at login | all-app, API | Developer options, root hint, Keystore attestation, Play Integrity verdict; stored and weighted, never a hard block alone. | ONL | app.device.integrity | - | F-API-001 | cfg.geo.integrity_weight | D-105 | 2d | T-2-10..19, 60..69 | new |
| F-SYS-032 | Error reporting (privacy-aware) | all-app, web, API | Sentry for Flutter and web with PII scrubbed, device id and stack only; buffered; batched. | QUE | external | - | - | cfg.telemetry.device_max_bytes_per_day | D-13 | 2e | T-2-31..35, 47..49, 53..57 | new |
| F-SYS-033 | Role-aware single codebase, three flavours | all-app | One Flutter codebase producing SR, AMO and TSO flavours with their own applicationIds and launcher labels "ARON SR", "ARON AMO", "ARON TSO"; Home tiles resolved per user. | OFF | - | token role | - | cfg.app.home_tiles | G-man-055; D-01, D-205 | 1a | T-1-20..24, 35, 41 | changed |
| F-SYS-034 | Target sanity guards | API, web, app | Reject target below 0 at entry (the entry endpoint is the target upload of 5c); flag at read; any percent with a zero or null denominator shows a dash; zero target shows a dash (DELIBERATE CHANGE from the observed 0%). | OFF | app.target | app.target, dw.agg_* | F-API-021a | cfg.kpi.card_pct_cap | D-50 | 1c | T-1-01..04, 60..69 | changed |
| F-SYS-035 | Suggested order quantity hook | API, SR | Per-outlet suggested quantity delivered in the bundle and shown at order entry; field ships empty until Q6 is answered. | OFF | - | app.outlet_suggestion | F-API-005 | cfg.sale.suggested_qty_enabled | Q6 | 2a | T-2-36..44 | new |
| F-SYS-036 | APK size budget | all-app | Per-ABI split APKs, stripped assets, one Bengali plus one Latin font; APK at most 30 MB per ABI and installed at most 70 MB (the Apsis baseline is 74-80 MB APK and 92-101 MB installed). | n/a | - | - | - | - | D-73, D-224 | 1c | T-1-01..04, 60..69 | new |
| F-SYS-037 | Wi-Fi-only photo setting | all-app | Per-device toggle for the media queue transport. | OFF | L:prefs | - | - | cfg.media.wifi_only_default | D-75 | 2c | T-2-26, 30, 53, 72 | new |
| F-SYS-038 | Apsis dump importer and ID crosswalk | ADM, eng | Staged importer in the order of docs/11 (geography, products, users and routes, outlets, targets, dues and loyalty, transactions, media); idempotent re-import through the crosswalk; quarantine at most 1 percent per table. | n/a | stg.*, stg.id_crosswalk, stg.import_run | dump files | F-API-033 | - | D-153, D-251..D-259 | 7a | T-7-80..82 | new |
| F-SYS-039 | Post-import reconciliation report | ADM, eng | Row counts and control totals per zone (outlets, MTD STD, memos, open dues, loyalty balances) versus the old reports; no cutover until it matches. | n/a | stg.control_total | all | F-API-033 | - | D-153 | 7a | T-7-80..82 | new |
| F-SYS-040 | Delta re-import before a wave | ADM, eng | Re-import of the latest Apsis delta so balances are current at switch; reconciled by 23:00 or the wave is deferred. | n/a | as F-SYS-038 | dump delta | F-API-033 | - | D-71 | 7b | T-7-83..87 | new |
| F-SYS-041 | Wave rollout control | ADM | Marks territories and wings as on the new system; gates bundle and updater by wave; AKTCL cannot set the Apsis app read-only itself (G-feat-67). | n/a | app.rollout_wave | geography | F-API-029, F-API-033 | cfg.release.wave_pct, cfg.flag.new_app_login_enabled | G-feat-67; D-147, D-148 | 7a | T-6-43, 51 | new |
| F-SYS-042 | Wave rollback | ADM | Flag flip at wave scope with captured rows still uploading; new-app data exportable in the Apsis dump shape (F-SYS-065). | n/a | app.rollout_wave.status | - | F-API-033 | cfg.flag.new_app_login_enabled | D-148 | 7b | T-7-83..87 | new |
| F-SYS-043 | Parallel-run comparison report | ADM | Daily per-route comparison of memos, STD per SKU, value, dues, geo % and Submit % (of logged-in) against the imported Apsis daily data; pilot pass rule is ten consecutive trading days with zero sync loss. | n/a | dw.v_parallel_compare | dw.*, imported Apsis daily | F-API-033 | cfg.flag.parallel_run_mode | D-145, D-154 | 7b | T-7-83..87 | new |
| F-SYS-044 | Side-by-side coexistence | all-app | Different applicationId per flavour so both apps run on one pilot phone; the same Bluetooth printer must serve both. | n/a | - | - | - | - | D-01 | 1a | T-1-20..24, 35, 41 | new |
| F-SYS-045 | Price snapshot on memo | API, SR, AMO | Memo lines store unit_price_mtk and the price type used at capture; bundle carries outlet and cc lists plus the per-outlet resolved type; money is bigint milli-taka; totals are summed from unrounded lines and rounded once, round_adj_mtk stored. | OFF | app.memo_line | app.sku_price | - | cfg.memo.rounding_mode | G-feat-46, G-feat-52, G-field-08; D-15, D-19, D-32 | 1a | T-1-20..24, 35, 41 | parity |
| F-SYS-046 | Immediate-sync trigger on connectivity regained | all-app | Event-driven flush: 5 s debounce with a family hold of at most 180 s, validated connectivity regained, foreground, manual, Sales Submit, Wi-Fi for the media queue; no polling. | QUE | as F-SYS-008 | L:* | F-API-006 | cfg.sync.debounce_s | D-59, D-261; R5 | 1b | T-1-25..34, 51..52 | new |
| F-SYS-047 | Server-generation re-sync | all-app, API | Every response carries X-Server-Generation; on change the device flips rows acked within the resync window back to pending and re-sends; registry dedupe absorbs it. | QUE | L:* | L:* | F-API-006, F-API-070 | cfg.sync.resync_window_h | D-63 | 1b | T-1-25..34, 51..52 | new |
| F-SYS-048 | Poison-row isolation and skip-ahead | API, all-app | Per-record savepoints; a record that throws becomes rejected(server_error) plus a quarantine row, never a 500; the client skips a family after repeated failure so the rest of the day uploads. | QUE | app.sync_rejected | - | F-API-006 | cfg.sync.family_skip_after | D-65 | 1b | T-1-25..34, 51..52 | new |
| F-SYS-049 | Trusted time anchor | all-app | Monotonic anchor (server time plus elapsed realtime plus boot id) is primary, clock offset at last contact is the fallback; check-out gate and business date use corrected time. | OFF | app.*.captured_elapsed_ms, boot_id | - | - | cfg.sync.max_clock_skew_min | G-fraud-02; D-20 | 1b | T-1-25..34, 51..52 | new |
| F-SYS-050 | Device telemetry headers and support visibility | all-app, API | X-Pending-Rows, X-Last-Sync-Error and X-App-Version recorded at most once per 10 min; device telemetry rides inside the sync batch (1 KB per day cap); POST /support/ping on demand. | QUE | app.device_day | - | F-API-046 | cfg.telemetry.device_max_bytes_per_day | D-136, D-138 | 2e | T-2-31..35, 47..49, 53..57 | new |
| F-SYS-051 | Number, date, time and money formatting profile | all-app, web | Digit script follows the UI language including the rasterised memo (confirm); Western 3-digit grouping; money 2 dp with the taka sign on memo and QC, exact paisa on dues; ISO dates in lists and long dates in pickers; 12-hour time hh:mm AM/PM. One formatter set in /packages. | OFF | - | - | - | cfg.i18n.digit_script, cfg.i18n.grouping, cfg.i18n.date_style, cfg.ui.date_format | G-man-103, UI-SR-08; D-213, D-344 | 0c | T-0-70..76 | changed |
| F-SYS-052 | Offline unlock and shared-phone user switching | all-app | Argon2id verifier written at each online password login, valid while the refresh token is live and the last online login is within 7 days (range 1 to 14), 10 failures with doubling cool-down; one SQLite file per user; A's rows upload under A's token while B works. | OFF | L:session | L:session | F-API-001 | cfg.auth.offline_unlock_max_days, cfg.auth.max_users_per_device | G-man-020; D-66, D-68, D-265 | 0c | T-0-70..76 | new |
| F-SYS-053 | Config propagation to the field | all-app, API | X-Config-Version on every response, GET /config/delta on the next natural request, ack on the next sync, scheduled values stored on the device, restrictive fallback when the clock is suspect; kill switches never block upload. | QUE | cfg.config_ack | cfg.config_value | F-API-040, F-API-041, F-API-042 | cfg.sys.schedule_horizon_days | D-89, D-90 | 1c | T-1-01..04, 60..69 | new |
| F-SYS-054 | Release gating and kill switches | all-app, ADM | min_version blocks a new day login only; blocked_versions blocks new captures only; neither blocks upload or wipes data; version-scoped sync hold at the edge for an upload loop; every operational switch has a mandatory duration. | QUE | cfg.config_value | - | F-API-042 | cfg.release.min_version, cfg.ops.sync_hold_by_version | D-79, D-130 | 2d | T-2-10..19, 60..69 | new |
| F-SYS-055 | Batch replay and content fingerprint | API | Stored response replay by (device_id, batch_uuid) within 24 h; same batch_uuid with a different row set returns 409; content fingerprint catches replay with regenerated uuids. | n/a | app.sync_batch.response, ingest_registry | - | F-API-006 | - | D-21, D-62 | 1b | T-1-25..34, 51..52 | new |
| F-SYS-056 | Route-day planning job | worker | At 00:05 Dhaka creates route_day for the date from visit days, the working-day calendar and overrides so denominators exist from midnight; target outlets are fixed at bundle time. | n/a | app.route_day | app.route, cfg.calendar.* | - | cfg.calendar.weekend_days, cfg.calendar.holidays | D-28, D-57, D-71 | 2e | T-2-31..35, 47..49, 53..57 | new |
| F-SYS-057 | Risk signal store and review events | API, AMO, TSO, web | Server signals FS-01..FS-29 stored in risk_signal; reviewed, dismissed or confirmed by idempotent events; AMO dismissals re-sampled to the TSO; nothing auto-reversed. | QUE | app.risk_signal, app.risk_review | app.visit, app.geo_fix | F-API-034 | cfg.sec.fraud.* | D-109 | 2d | T-2-10..19, 60..69 | new |
| F-SYS-058 | PII read budgets and export log | API, web | List endpoints mask personal columns by default with a logged reveal, hourly and daily row budgets, exports above a threshold need approval and carry a watermark sheet; every export is logged. | ONL | app.report_export_log, app.security_event | app.outlet | F-API-017 | cfg.pii.field_roles, cfg.pii.mask_style | G-man-039, G-man-062; D-108, D-121 | 4c | T-4-14, T-4-75 | new |
| F-SYS-059 | Append-only audit log | API | app.audit_log, config_change_audit, security_event and report_export_log (REVOKE, trigger, hash chain) exported daily to WORM storage. | n/a | app.audit_log, cfg.config_change_audit | - | F-API-037 | - | D-113 | 2d | T-2-10..19, 60..69 | new |
| F-SYS-060 | Dues ledger, allocation and ageing | API, worker | Every credit memo, collection, void reversal and adjustment is a ledger row; FIFO allocation for ageing regardless of the retailer's choice; buckets 0-7, 8-30, 31-60, 61+ days (ASSUMPTION); opening balances land in an "opening" bucket. | n/a | dw.fact_due_ledger, dw.agg_outlet_balance | app.memo, app.due_collection | - | cfg.kpi.dues_buckets, cfg.credit.allow_partial_collection | G-feat-45; D-37; K-18 | 2b | T-2-21..24, 43 | new |
| F-SYS-061 | Loyalty ledger earning and expiry job | worker | Points computed on the server only; ledger rows carry source_type and source_id so a replay never double-credits; nightly expiry row; redemption over balance is accepted-and-flagged when the device balance was sufficient, rejected otherwise. | n/a | app.loyalty_ledger | app.survey_response, app.redemption | - | cfg.loyalty.earning_rules, cfg.loyalty.expiry_days | G-feat-20, G-man-040, G-man-041; D-41, D-266 | 5a | T-5-10, 41 | new |
| F-SYS-062 | Server recompute checks | API | Recomputes geo verdict, prices (price_mismatch flag, never rejects a printed memo), totals (rejects on arithmetic), points and business date; quantity multiples and large quantities are flags; coordinates outside Bangladesh are flagged. | n/a | dw.fact_dq_flag | - | F-API-006 | cfg.sale.max_line_qty_base, cfg.sale.max_lines_per_memo | D-117, D-246, D-260 | 2a | T-2-36..44 | new |
| F-SYS-063 | Retention, archival and partition job | worker | Monthly RANGE partitions created 3 months ahead; archive job writes a manifest and drops only after verified upload; photo tiering Hot, Cool, Cold, Archive. | n/a | app.* partitions | - | - | cfg.retention.* | D-23, D-131, D-132 | 6c | T-6-43, 51 | new |
| F-SYS-064 | Async export job (Excel, PDF, print) | API, web | Every report renders on screen first; xlsx, pdf and print are formatters over the same query; large ranges run as a background job with a download link; spreadsheet-injection sanitiser; one label "Get Excel". | ONL | app.report_export_log | dw.* | F-API-017, F-API-053 | cfg.ops.report_export_max_rows, cfg.report.max_range_days | G-man-095; D-118, D-238 | 4b | T-4-42..46 | new |
| F-SYS-065 | Apsis-shape export for rollback | ADM, eng | Export job that writes new-app data captured during a wave in the Apsis dump shape, because AKTCL cannot make the Apsis app read-only itself. | n/a | - | app.* (wave scope) | F-API-033 | - | D-148 | 7b | T-7-83..87 | new |
| F-SYS-066 | Credential migration at cutover | ADM, eng | If the dump carries verifiable hashes the importer verifies then re-hashes at first login; otherwise TSO-issued temporary passwords per SR with forced change; same usernames either way. | ONL | app.password_history, app_user.must_change | dump users | F-API-033 | - | G-feat-66; D-119 | 7a | T-7-80..82 | new |
| F-SYS-067 | Bundle pre-generation and D+1 pre-fetch | worker, all-app | 22:00 snapshot for D+1 for Wi-Fi pre-fetch, 03:30 refresh for dirtied users, 04:30 coverage check (99 percent), else yesterday's snapshot plus delta (bundle_hold). | CAC | app.bundle_snapshot | reference tables | F-API-005 | cfg.bundle.regen_max_per_s | D-71, D-126, D-129 | 2e | T-2-31..35, 47..49, 53..57 | new |
| F-SYS-068 | Opening-balance provenance | all-app | Bundle carries per outlet opening_due_mtk, opening_points, as_of_date and the Apsis memo numbers of open memos; the outlet card shows the old-system balance and the movements since. | CAC | app.opening_balance | app.opening_balance | F-API-005 | - | G-field-22 | 7a | T-7-80..82 | new |
| F-SYS-069 | Memo sequence gap report | API | Nightly gap detector per user and day from memo_no; sale_abort activity explains burned numbers; unexplained gaps appear on sync-health. | n/a | dw.memo_seq_gap | app.memo | - | - | G-field-14 | 2e | T-2-31..35, 47..49, 53..57 | new |
| F-SYS-070 | Text normalisation and Bangla collation | all-app, API | /packages/text: Bengali to Western digits at input and ingest (flag when changed), NFC and format-character stripping, server-generated name_sort_key shipped in the bundle so SQLite and Postgres order outlet lists identically; phone normalised to 11 digits. | OFF | - | - | - | cfg.app.alphabet_filter, cfg.outlet.mobile_regex | G-field-10, G-man-037; D-118, D-345 | 1a | T-1-20..24, 35, 41 | new |
| F-SYS-071 | Per-user encrypted local store | all-app | SQLCipher with a per-user 256-bit key wrapped by Android Keystore; falls back to file-based encryption if the APK exceeds 30 MB per ABI or the battery gate fails. | OFF | L:* | L:* | - | - | D-67 | 1b | T-1-25..34, 51..52 | new |
| F-SYS-072 | Device proof and record signatures | all-app, API | EC P-256 key in Keystore registered at bind; X-Device-Proof on refresh, bind and batch; each record carries a signature (record mode in the pilot, enforce before wave 1). | OFF | app.device.public_key | app.device | F-API-003, F-API-006 | - | D-104 | 2d | T-2-10..19, 60..69 | new |
| F-SYS-073 | Urgent config push | all-app | FCM data messages only for kill switch, min_version, blocked versions and reverts, with a jittered pull; off in the pilot; no FCM-triggered uploads. | CAC | - | - | F-API-040 | cfg.ops.push_enabled | D-09, D-84 | 2d | T-2-10..19, 60..69 | new |
| F-SYS-074 | Map component and geocoding | AMO, TSO | Google Maps SDK, 2D, lite mode, loaded only when its screen opens, bounded tile cache, no map in the SR flavour; attendance address online-only with coordinates as the fallback; MapLibre is the fallback if cost blocks. | HYB | - | L:cache tiles | - | cfg.map.provider, cfg.map.api_key_ref, cfg.map.tile_cache_mb, cfg.map.3d_enabled | G-man-059, G-field-18; D-08; Q42 | 3a | T-3-20..24, 41 | changed |
| F-SYS-075 | Employee-location notice and consent | all-app | Bangla and English notice at first login stating when location is recorded; acceptance stored; raw fixes rounded in dw after 730 days. | OFF | app.user_consent | - | F-API-001 | - | D-120 | 2e | T-2-31..35, 47..49, 53..57 | new |
| F-SYS-076 | Importer outlet normalisation and archived stubs | eng | Outlet code normalised as text with a crosswalk, unparseable codes quarantined, 175,031 archived outlet stubs created from the sales sample, Created At parsed with Dhaka assumed, geo_class nullable. | n/a | stg.*, app.outlet (archived) | dump files | F-API-033 | - | D-25, D-251..D-258 | 7a | T-7-80..82 | new |
| F-SYS-077 | Parallel-run print and capture mode | SR, AMO | off, capture_only or print_test_watermark; the test print carries "পরীক্ষামূলক - এটি রসিদ নয়" and no previous-due line; parallel due collections are excluded from balances. | OFF | app.*.parallel flag | - | - | cfg.flag.parallel_run_mode | G-field-12; D-152; Q49 | 2e | T-2-31..35, 47..49, 53..57 | new |
| F-SYS-078 | Multi-visit and visit-kind policy | API, all-app | sr_call, amo_control_call, amo_joint_call, tso_visit, web_entry; route KPIs include every active memo regardless of seller, user KPIs use coalesce(acting_for_user_id, user_id); a second visit to one outlet on one day is allowed after a zero sale. | OFF | app.visit.kind | app.visit | F-API-006 | cfg.day.multi_visit_same_outlet_policy, cfg.kpi.count_abandoned_visits | G-feat-08, G-field-16; D-26, D-250 | 2a | T-2-36..44 | new |
| F-SYS-079 | Check-out and Sales Submit upload jitter | SR, AMO | When the only reason an upload happens now is that the 17:00 clock gate has just opened, the upload starts after U(0, `cfg.sync.checkout_upload_jitter_max_s`) (default 90 s); the offline-completed local state (checked out, submitted_local) does not wait; Manual Sync ignores it; earlier rows were already uploaded by T1 (D-505, doc 17 s4.1 T7). | OFF | L:outbox | - | F-API-006 | cfg.sync.checkout_upload_jitter_max_s, cfg.day.checkout_earliest_time | G-qa-29; D-505 | 2e | T-4-155, T-1-54 | new |
| F-SYS-080 | Generation digest and targeted re-send | all-app | After a generation change, at Sales Submit, daily on Wi-Fi and from the reconciliation screen the device sends per (business date, type) a count and 16 bucket hashes (first hex digit of the client uuid, commutative sum); the server answers with the differing buckets and the device re-sends only those rows; the outbox purge is held for rows acked after the restore point until a clean reconcile (D-517). | QUE | L:sync_meta, L:outbox | app.ingest_registry | POST /sync/digest, F-API-070 | cfg.sync.digest_days, cfg.sync.resync_safety_margin_h | G-qa-41; D-63, D-517 | 1b | T-1-56, T-1-152 | new |
| F-SYS-081 | Daily field telemetry (R4 and R5 evidence) | all-app | One object a day, at most 1 KB, inside the sync batch: app-uid mobile and Wi-Fi bytes, CPU ms, wake-lock ms, engine starts, GPS fixes, whole-device battery at 08:00, 12:00 and 17:00, `connectivity_regained_at`; stored in `fact_device_day`; drives SH-19 to SH-23, the wave go/no-go and the auto-freeze (D-507, D-509). | QUE | L:sync_meta, app.device_day | - | F-API-006 | cfg.telemetry.device_max_bytes_per_day, cfg.telemetry.enabled, cfg.telemetry.bat17_floor_pct | G-qa-31, G-qa-33; D-136, D-507, D-509 | 2e | T-2-153, T-2-154 | new |
| F-SYS-082 | Apsis delta import and the nightly route-day feed | importer | Imports the delta contract of doc 16 s12.6 (dues per memo, loyalty, outlet changes, targets and assignments, control totals) before each wave and the nightly DL-6 route-day aggregates for unswitched routes as `source = apsis_parallel`; the AKTCL-staff fallback accepts the same columns from a sheet (D-514, D-548). | n/a | stg.*, dw.agg_daily_route | stg.* | F-API-033 | cfg.flag.parallel_run_mode | G-qa-37, G-qa-80; D-71, D-514, D-548 | 7a | T-7-150, T-7-151, T-7-152 | new |
| F-SYS-083 | Switched-route dimension and coverage | API, web | `dw.dim_geo.cutover_wave` and `on_new_system_from`; KPIs and alerts default to switched routes; the coverage banner and scope toggle; leaderboard "partial" below `cfg.kpi.leaderboard_min_switched_pct` (D-548). | n/a | dw.dim_geo, agg_daily_route.on_aron | dw.* | F-API-014, F-API-028 | cfg.kpi.leaderboard_min_switched_pct | G-qa-80; D-548 | 4a | T-4-153 | new |
| F-SYS-084 | Held-rows list | TSO, support | Devices that reported pending rows and have not made contact for more than `cfg.sla.pending_rows_alert_h` (4 h) or hold rows after 17:30, with user, route, zone, rows, age and device model; built from `X-Pending-Rows` and last contact; no new phone traffic (D-511, SH-24). | n/a | - | app.device, sync_batch | F-API-028, F-API-071 | cfg.sla.pending_rows_alert_h | G-qa-48; D-511 | 2e | T-2-140, T-2-153 | new |
| F-SYS-085 | Wave rollback return path | importer, ops | Exports the wave's open dues, credit memos since the switch and stock in the Apsis dump shape for the staffed re-key of days 1 to 3, a nightly Aron dues sheet for dispute handling and the fix-forward-only rule from day 4 (D-549). | n/a | stg.export_* | dw.fact_due_ledger | F-API-033 | cfg.flag.new_app_login_enabled | G-qa-81; D-148, D-549 | 7b | T-7-153 | new |
| F-SYS-086 | Route-day and web-entry dirty-key triggers | API | AFTER INSERT OR UPDATE triggers on every writer of `route_day`, `supervisor_day`, the day-exception, final-submit, submit-void, route-assignment and web-entry tables and `data_void` call a SECURITY DEFINER function that enqueues the route-day, so the first bundle download, the settle timeout, a submit void, cover and web entry mark dirty keys exactly like ingest; the settle timer runs as `app.settle_due_route_days()`; K-01 and K-02 have one source (dw) with a stated lag (D-567, doc 16 s8.6b). | n/a | dw.agg_dirty | app.route_day | - | - | G-qa-102; D-567 | 1c | T-1-155, T-3-158 | new |
| F-SYS-087 | Late delta DL-1b and DL-2b | ADM, importer | Re-imports the dues, loyalty, outlet and assignment rows changed in Apsis after the final cut (T-1 20:00): at 06:00 on day T and each morning T+1 to T+3; changed users receive a bundle delta before the first sale with "balance as of <time>" (F-SYS-068); rows that cannot be imported go to the straggler sheet (F-SYS-097). | n/a | stg.*, app.opening_balance | stg.* | F-API-033 | cfg.cutover.late_delta_time, cfg.cutover.apsis_complete_by_time, cfg.cutover.final_delta_due_time | G-qa-88, G-qa-122; D-556 | 7a | T-7-163 | new |
| F-SYS-088 | Apsis residual detection | API, web | The nightly Apsis route-day feed (DL-6) is also loaded for SWITCHED routes as `source = apsis_residual`; any Apsis memo or collection on an `on_aron` route raises a per-route alert to the AMO, TSO and L1 and the tile SH-26 (threshold `cfg.sla.apsis_residual_alert`, default 0); a route with an unbound SR does not switch (`held`). | n/a | dw.agg_daily_route | stg.* | F-API-085 | cfg.sla.apsis_residual_alert | G-qa-122; D-556 | 7b | T-7-164 | new |
| F-SYS-089 | Re-sync late window | API | A `trigger = resync` batch is judged against the announced `restore_point_utc`, not the receipt time: rows within `cfg.sync.resync_late_max_days` of the anchor are accepted and flagged `resync_late` instead of being quarantined as `business_date_out_of_window` (DQ-09, DQ-71). | n/a | app.* flags | app.ingest_registry | F-API-006, F-API-070 | cfg.sync.resync_late_max_days, cfg.sync.max_backdate_days | G-qa-108; D-572 | 1b | T-1-156 | new |
| F-SYS-090 | Working-day windows | API, SR, AMO, TSO | The stale-bundle age, the backdate window and the offline-unlock window count working days with calendar ceilings and per-break overrides; the next WORKING day's snapshot is built and pre-fetched on the last working evening; the config accept window counts working time. | OFF | - | dw.dim_date, cfg.calendar.* | F-API-005 | cfg.calendar.window_unit, cfg.bundle.stale_max_cal_days_ceiling, cfg.calendar.break_overrides, cfg.calendar.prefetch_next_working_day | G-qa-123; D-584 | 2e | T-2-163 | new |
| F-SYS-091 | Config stamp regress and no-grace tightening | API | The server records the highest config version delivered to each device; a row stamped below a version delivered more than the apply grace before the capture is `config_stamp_regress` (DQ-73), judged under the as-of value, and FS-34 fires after three such rows; a C3 tightening may carry `no_grace`. | n/a | app.device_config_seen | cfg.resolve | F-API-006 | cfg.sys.config_apply_grace_min, cfg.sys.no_grace_tighten_allowed, cfg.sec.fraud.stamp_regress_min_rows | G-qa-107; D-571 | 2d | T-2-162 | new |
| F-SYS-092 | Resume config check | SR, AMO, TSO | One conditional `GET /config/check` (304 of about 300 B or the delta inline) on app resume, on "Refresh GPS" and on opening a visit when online and the last contact is older than `cfg.sync.config_check_min_gap_min`; no timer, no wake-up, at most `cfg.sync.config_check_max_per_day` a day. | ONF | app.sync_meta | cfg config version | F-API-083 | cfg.sync.config_check_min_gap_min, cfg.sync.config_check_max_per_day | G-qa-96, G-qa-106; D-563 | 2d | T-2-165 | new |
| F-SYS-093 | Retention hold | API, jobs | The archival, partition-drop, photo-sweep and quarantine-purge jobs consult `app.retention_hold` and skip any partition or blob that holds a row covered by an open hold; every placement and lift is audited. | n/a | app.retention_hold | app.retention_hold | F-API-082 | cfg.retention.hold_max_days, cfg.retention.hold_roles | G-qa-139; D-600 | 6c | T-6-152 | new |
| F-SYS-094 | Route-day void barrier | API | `data_void` writes a barrier `(route_id, business_date, voided_at)`; ingest rejects any record of that route-day captured before `voided_at` as `voided_by_admin` whether or not its uuid is known, so unsent rows of a voided route-day cannot resurrect; rows captured after stay legitimate. | n/a | app.route_day_void_barrier | app.route_day_void_barrier | F-API-048 | cfg.web.delete_section_data_scope | G-qa-113; D-577 | 4c | T-4-166 | new |
| F-SYS-095 | Device directive channel | API, SR, AMO, TSO | A signed, idempotent per-device directive (send PDA, ping, redownload bundle) carried in any response, a 401 body or a config delta, acted on at the next foreground contact and acknowledged by a `directive_ack` record; created from P19 (F-ADM-085). | QUE | app.device_directive | app.device_directive | F-API-079 | cfg.support.directive_ttl_h, cfg.support.directive_types | G-qa-133; D-594 | 2e | T-2-169 | new |
| F-SYS-096 | Device-captured facts in dw | worker | `fact_device_integrity`, `fact_activity` with `agg_daily_screen_use`, and `fact_consent` store the record types `device_integrity`, `activity_log` and `consent_accept` so every device-captured type has a dw object (capture map generated from the doc 17 s2.3 enum). | n/a | dw.fact_device_integrity, dw.fact_activity, dw.agg_daily_screen_use, dw.fact_consent | app.device_integrity, app.activity_log, app.user_consent | - | cfg.retention.activity_log_days | G-qa-91; D-559 | 2e | T-4-168, T-0-150 | new |
| F-SYS-097 | Wave straggler reconciliation sheet | ADM, finance | A 3-day sheet `stg.straggler` (outlet, memo or collection, Apsis timestamp, amount, state, owner, closed_at) owned by a named AKTCL clerk and reviewed by finance each morning, fed by DL-1b and the late Apsis uploads. | n/a | stg.straggler | stg.* | F-API-033 | cfg.cutover.late_delta_time | G-qa-88; D-556 | 7a | T-7-163 | new |

Proved by: T-0-70..76 (auth and scope), T-1-20..34 and T-1-51..52 (sync, idempotency, reconciliation), T-1-01..04 and T-1-60..69 (aggregation, config), T-2-10..19 (geo), T-7-80..82 (import).

## 3 SR app (F-SR)

79 rows in the order of the SR's day (the spine of docs/06). Screens, labels, dialogs and the printed memo are reproduced screen for screen unless a D-id says otherwise. Evidence pages are those of the SR manual (80 pages, 60 screens); UI-SR-nn are the AKTCL screenshot notes. Message texts are in s11.5 by key.

### 3.1 Setup and session

| ID | Name | What it does, rules now stated, evidence | Off | Prt | Pic | Geo | Writes | Reads | API | cfg keys | Refs (G, D, Q) | Ph | Gates | St |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-SR-001 | Install, login, first bundle | Sideloaded signed APK with its own applicationId; login with own username and password; the bundle download is the logged-in event and is resumable; version shown on login and Settings from one source; vendor footer replaced by AKTCL branding. Evid: SR p2-4. | ONF | - | - | - | app.bundle_download, app.route_day, L:* | bundle | F-API-001, F-API-005 | cfg.auth.access_ttl_min, cfg.bundle.stale_max_days | G-man-020, G-man-022; D-01, D-30, D-208, D-244 | 1a | T-1-20..24, 35, 41 | parity |
| F-SR-002 | Device OTP prompt | On an unbound phone or (parity setting) after a new version: Enter OTP, four boxes, Verify; wrong-OTP, expiry and retry texts are authored. Re-verify after an in-place update is off by default. Evid: SR p5-6. | ONL | - | - | - | app.device | app.device_otp | F-API-003 | cfg.auth.otp_length, cfg.auth.reverify_on_new_version | G-feat-11, G-man-021; D-80, D-103 | 0c | T-0-70..76 | changed |
| F-SR-003 | Permissions onboarding and gating | Location (precise), camera, Bluetooth; denial rules per F-SYS-023; no microphone request. Evid: SR p4, p19, p26. | OFF | - | - | - | - | - | - | cfg.geo.require_precise, cfg.app.location_denied_policy | G-man-020; D-74, D-115 | 1a | T-1-20..24, 35, 41 | changed |
| F-SR-004 | In-app update prompt | Update Available page with Network Status, release notes, percent progress, Install-unknown-apps hop, forced splash and processing n/m; see F-SYS-020. Evid: SR p7-10. | ONL | - | - | - | L:prefs | app_release | F-API-029 | cfg.release.min_version, cfg.release.update_wifi_only | G-man-022; D-79, D-223 | 2e | T-2-31..35, 47..49, 53..57 | changed |
| F-SR-005 | Settings: language | English or বাংলা; no confirmation; persists. Evid: SR p77-78. | OFF | - | - | - | L:prefs | - | - | cfg.app.default_locale | D-181 | 1a | T-1-20..24, 35, 41 | parity |
| F-SR-006 | Settings: PDA to Support | Single tap sends the data file; the new app adds progress, failure and an offline queue. Evid: SR p78. | ONF | - | - | - | Blob | L:* | F-API-030 | cfg.support.max_upload_mb | G-man-025 | 2e | T-2-31..35, 47..49, 53..57 | parity |
| F-SR-007 | Settings: Logout | Confirm "আপনি কি নিশ্চিত যে লগআউট করতে চান?"; SR keeps the local database and the engine keeps uploading. Evid: SR p79. | OFF | - | - | - | - | - | F-API-031 | cfg.app.logout_block_when_pending | G-man-024; D-69 | 2e | T-2-31..35, 47..49, 53..57 | parity |

### 3.2 Home

| ID | Name | What it does, rules now stated, evidence | Off | Prt | Pic | Geo | Writes | Reads | API | cfg keys | Refs (G, D, Q) | Ph | Gates | St |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-SR-008 | Home header | "SR - <name> (<username>)" and "<route name>, <ISO date>"; route label is name plus visit days in brackets (the manual glues name and kind); with two planned routes a picker (F-SR-065). Evid: SR p10, UI-SR home. | OFF | - | - | - | - | L:route_assignment | - | cfg.app.route_label_format | G-man-037, G-man-054, G-feat-40; D-214, D-242 | 1a | T-1-20..24, 35, 41 | parity |
| F-SR-009 | Home tile grid | Attendance, Stock, Sale, Memo, Summary, Sales Submit (বিক্রয় জমা), Outlet, Tutorial, Task Delegation (open-task badge), Astha, Loyalty Point, Photo Capture; plus Sales Journey and KPI tiles seen in the AKTCL screenshots (F-SR-067, F-SR-068). Tile set is per user: Loyalty Point and Photo Capture are absent on the SR-16858 account (10 tiles). Evid: SR p10, p44, UI-SR-01/02. | OFF | - | - | - | - | L:task (badge) | - | cfg.app.home_tiles | G-man-055; D-167, D-342 | 1a | T-1-20..24, 35, 41 | parity |
| F-SR-010 | Home KPI strip | Outlets visited x/y, Strike rate (successful calls / target outlets, 6/60 = 10%), Issue and Current stock shown per category with its unit (never one summed figure), Non-visit = target - visited, No-sale = zero-sale calls; Bengali digits. Evid: SR p10, UI-SR-09/10. | OFF | - | - | - | - | L:visit, L:memo, L:stock_movement, L:target; /app/home on sync | F-API-018 | cfg.app.kpi_strip_items, cfg.kpi.card_pct_cap | G-feat-38, G-man-054; D-46, D-56 | 2a | T-2-36..44 | changed |
| F-SR-069 | Home money summary cards | Two cards of live day totals: per-category value (Match, Lighter, ...), Total (gross), Discount, QC, Grand total (net); second card Total discount, DRP discount, Total QC, Grand total, Total taka. Needs offer discount, DRP discount and QC deduction stored separately; the same Bangla word means different totals on the two cards, so one label per term. Evid: UI-SR-04..07. | OFF | - | - | - | - | L:memo, L:memo_line | - | cfg.i18n.overrides | G-man-002; D-18, D-346 | 2a | T-2-36..44 | parity |
| F-SR-067 | Sales Journey tile | Tile "বিক্রয় যাত্রা" present in the AKTCL screenshots and in no manual or spec. Unknown; confirm with the business. Proceed-with default: tile hidden by cfg.app.home_tiles until its content is captured from the live app; likely a route-progress or visit-plan view. Evid: UI-SR-01. | OFF | - | - | - | - | - | - | cfg.app.home_tiles | G-15-01; D-342 | 2a | T-2-133 | parity |
| F-SR-068 | KPI tile | Tile "কেপিআই" present in the screenshots and in no manual. Unknown; confirm with the business. Default: hidden until captured; probable fuller target-versus-achievement page fed by the KPI registry. Evid: UI-SR-02. | OFF | - | - | - | - | - | - | cfg.app.home_tiles | G-15-01; D-342 | 2a | T-2-133 | parity |
| F-SR-056 | SKU-wise Target and Achievement | Home target card (STD bar, uncapped) with Details to per-brand or variant cards: Target, Achieved, Remaining, Achievement %, ADS, TADS, PADS, RADS (TADS = target / elapsed_working_days(as_of), RADS = remaining / remaining_working_days(as_of), both rounded half up, the day counts read from the `dim_date` calendar for the as-of date and never hard-coded; the fixture is "as of 2026-04-18 with the April 2026 calendar" (14 elapsed, 11 remaining): 500 gives TADS 36 and RADS 45, 900 gives 64 and 82; the meaning of "target ADS" rests on one data point and stays MQ-27; PADS undefined until a non-zero sample, D-58, G-qa-66). Computed locally from the bundle target plus local sales; target 0 shows a dash. Evid: SR p10-11. | OFF | - | - | - | - | L:target, L:memo_line | F-API-012 | cfg.kpi.tilldate_basis.sr_ads, cfg.kpi.tilldate_rounding.sr_ads | G-man-053, UI-SR-03; D-50, D-51, D-58 | 2a | T-2-36..44 | parity |
| F-SR-064 | Device-health line on Home | Battery, free storage, pending rows and last sync with thresholds; a support code (error, version, config version, last 4 of device id, bind state, storage, battery) on every blocking screen so the SR can read it aloud. | OFF | - | - | - | - | L:* | - | cfg.app.health_warn_battery_pct | G-field-15 | 2e | T-2-31..35, 47..49, 53..57 | new |
| F-SR-077 | Support code on blocking screens | Short code decodable by the helpdesk for pre-login failures when no telemetry has arrived; the decoder page is a support tool owned by doc 20 s7 and doc 18 s7. | OFF | - | - | - | - | L:* | - | cfg.support.contacts | G-field-15; D-138 | 2e | T-2-31..35, 47..49, 53..57 | new |

### 3.3 Day start

| ID | Name | What it does, rules now stated, evidence | Off | Prt | Pic | Geo | Writes | Reads | API | cfg keys | Refs (G, D, Q) | Ph | Gates | St |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-SR-063 | Offline day start and stale-bundle banner | With no signal at day open the app sells on a cached bundle up to 2 days old with a banner, flags rows bundle_stale and appends a day_open event so Login % stays truthful; older shows memos and dues read-only until online (check-in still allowed). | OFF | - | - | - | app.route_day (day_open) | L:bundle | F-API-006 | cfg.bundle.stale_max_days | G-sync-03; D-30, D-70 | 2e | T-2-31..35, 47..49, 53..57 | new |
| F-SR-072 | Identity confirmation on a shared phone | At the first capture of a business date on a device with more than one bound user: "আপনি কি <name> (<code>)?"; acting_for_user_id is stored when the route's assignee differs. | OFF | - | - | - | app.visit.acting_for_user_id | L:session, route assignees | F-API-006 | cfg.auth.confirm_identity_on_first_capture | G-field-05; D-66, D-85 | 2e | T-2-31..35, 47..49, 53..57 | new |
| F-SR-011 | Attendance check-in | Reverse-geocoded address when online, else coordinates; Refresh = one new fix; Check In opens a sheet with the time chip and a press-and-hold button; then "চেক ইন সম্পন্ন হয়েছে" and the button disables. A prompt, not a gate, for Stock and Sale (UI-SR-13). No fix indoors is allowed. Evid: SR p12-14, UI-SR-11..13. | OFF | - | - | fix | app.attendance_event, app.geo_fix | - | - (local in 1a; the batch path is first used by the 1b slice) | cfg.app.hold_to_confirm_ms, cfg.geo.refresh_max, cfg.day.checkin_gate | G-man-029, G-feat-18; D-74, D-208 | 1a | T-1-20..24, 35, 41 | parity |
| F-SR-013 | Bluetooth printer pairing and state | OS pairing (RPP02N, PIN 0000), then the printer icon on Stock, Review, Memo and Summary connects (red slashed = not connected, green = connected, banner "প্রিন্টার কানেক্ট করা হয়েছে"); auto-reconnect; Print enabled only when connected and saved. Evid: SR p16-20. | OFF | - | - | - | L:prefs | - | - | cfg.print.pairing_pins, cfg.print.models | G-man-023, G-feat-43; D-76, D-220 | 1a | T-1-20..24, 35, 41 | parity |
| F-SR-014 | Stock load (issue by SKU) | Per SKU stepper and typed Issue quantity for sales-plan SKUs; the screen shows today's loaded total per SKU READ-ONLY and a Save posts ONLY the entered increment as a new movement (the first draft left "replace or add on re-save" open, so a repeated Save or a double tap doubled stock, the issue KPI, the Summary return quantity and the stock warning, D-580); a same-values re-save inside `cfg.stock.resave_guard_window_min` is refused as a double tap; an explicit "correct total" posts a signed adjustment (F-SR-081), Stock = opening + issues - sold - returned computed locally; category totals panel (Cigarette, Bidi, Lighter, Match); Save always works offline; the unit of each category is shown. Evid: SR p20-21, UI-SR-15..18. | OFF | - | - | - | app.stock_movement(kind=issue) | L:sku, L:sales_plan | F-API-006 | cfg.stock.max_issue_qty, cfg.stock.require_printed_slip, cfg.stock.save_mode, cfg.stock.resave_guard_window_min | G-man-007, G-feat-02, G-qa-116; D-16, D-17, D-580; MQ-68 | 2a | T-2-36..44, T-2-161 | changed |
| F-SR-015 | Print stock memo | Prints the issued stock by SKU as the slip handed to the distributor's manager; Save is never blocked by the printer, the slip carries stock_slip_printed and Sales Submit warns when it is false. Evid: SR p21, UI-SR-17. | OFF | stock | - | - | L:print_event | app.stock_movement | - | cfg.stock.require_printed_slip, cfg.print.template_version | G-man-014; D-76 | 2a | T-2-36..44 | parity |

### 3.4 Per-outlet call

| ID | Name | What it does, rules now stated, evidence | Off | Prt | Pic | Geo | Writes | Reads | API | cfg keys | Refs (G, D, Q) | Ph | Gates | St |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-SR-016 | Route outlet list with alphabet filter | Dropdown of today's outlets labelled "name (code-phone-cluster)" (SR picker; other screens use name (sub-channel) or name (code)); filter chips are the first letters present, case-insensitive, bilingual; phone normalised to 11 digits and shown with leading 0; archived and closed outlets hidden; eligibility dots (F-SR-075). Evid: SR p22, UI-SR-21..25. | OFF | - | - | - | - | L:outlet, L:visit | - | cfg.app.outlet_list_label_format, cfg.app.alphabet_filter, cfg.outlet.mobile_regex | G-man-037, G-field-10; D-345 | 1a | T-1-20..24, 35, 41 | changed |
| F-SR-065 | Route picker for an SR with several routes | When two or more routes are planned for the date the SR picks the route; one bundle flips every route_day; the header names the route in use. | OFF | - | - | - | - | L:route_assignment | - | cfg.route.allow_unplanned_day | G-feat-40; D-27, D-257 | 2a | T-2-36..44 | new |
| F-SR-074 | Sort outlets by distance | When a fix exists the picker sorts by distance to avoid choosing a neighbour in a 55 m cell (IMPROVEMENT, default off). | OFF | - | - | fix | - | L:outlet | - | cfg.sale.sort_by_distance | UI-SR-25; D-340 | 2d | T-2-10..19, 60..69 | new |
| F-SR-075 | Outlet eligibility dots | Four coloured dots before each outlet mark discount or programme eligibility (colours stand for Astha tier, Diamond League, Superstar, promotion groups; mapping unknown; confirm). Bundle carries per-outlet flags; legend is admin data (F-ADM-066). Evid: UI-SR-22. | OFF | - | - | - | - | L:outlet_flags | F-API-005 | cfg.ui.outlet_badges | G-15-02; D-341 | 2a | T-2-133 | parity |
| F-SR-017 | Open visit and geo check | Selecting an outlet opens the visit row at once; one fused fix (acquisition starts on entering the list, a fix up to 60 s and 30 m old is reused); Haversine against the outlet coordinates and the resolved radius; out of range shows "আপনি নির্বাচিত খুচরা বিক্রেতার সীমার মধ্যে নেই।" with Force Sale and Refresh; mocked fix never valid; the Sale History and Points buttons stay HIDDEN until their sub-milestones (Sale History 2b, F-SR-054; Points 5a, F-SR-055) through `cfg.ui.visit_screen_buttons` (the first draft claimed they "stay available" in 1a, D-583). Evid: SR p22-25. | OFF | - | - | Y | app.visit, app.geo_fix | L:outlet, radius from bundle | - (local in 1a) | cfg.geo.radius_m, cfg.geo.max_accuracy_m, cfg.geo.fix_timeout_s, cfg.geo.fix_reuse_max_age_s, cfg.geo.no_location_policy | G-feat-39, G-field-13; D-74, D-95, D-264 | 1a | T-1-24, T-1-20..24, 35, 41 | parity |
| F-SR-019 | Refresh GPS fix | Re-reads one fix and re-evaluates; capped to protect the battery. When online and the last contact is older than `cfg.sync.config_check_min_gap_min` it first sends the conditional config check (F-SYS-092), so an SR out of range after an admin widened the radius is re-checked under the new value. Evid: SR p25. | OFF | - | - | Y | app.geo_fix | - | - (local in 1a) | cfg.geo.refresh_max | G-man-029 | 1a | T-1-20..24, 35, 41 | parity |
| F-SR-018 | Force Sale | Out of range or no fix: choose exactly one reason (ইন্টারনেট সমস্যা or লোকেশন চেঞ্জ; no_outlet_location added; `permission_denied` is NOT a reason: a denied location permission BLOCKS sale and attendance because a force sale needs a fix, D-74, doc 17 s10.3, doc 19 `cfg.sale.force_reasons` has three reasons, D-74 negative test in T-2-77 and T-1-30, G-qa-63), take an outlet photo, proceed; photo_validated true, geo_validated false. The photo raises a location-change request and the device keeps a provisional flagged location; only a missing or placeholder location is set at once (DELIBERATE CHANGE: the manual says the location is updated when the photo is taken). Evid: SR p25, p27, p31, p42. | QUE | - | Y | Y | app.visit, app.outlet_photo, app.outlet_change_request(type=location) | - | F-API-006, F-API-007 | cfg.sale.force_reasons, cfg.geo.outlet_location_change_approval, cfg.geo.first_capture_sets_location, cfg.sale.outlet_photo_every_call | G-man-016, G-man-017, G-feat-39; D-95, D-163, D-347 | 2d | T-2-10..19, 60..69 | changed |
| F-SR-060 | Start-call confirmation prompt | After the geo gate: "আপনি কি কল শুরু করতে চান?" with হ্যাঁ and না; the visit row exists from outlet open, call_started_at is separate; না returns to the list and is not counted visited; AV, KV, survey and sale begin only after হ্যাঁ. Evid: SR p27, p31, p42. | OFF | - | - | - | app.visit.call_started_at, call_declined | - | F-API-006 | cfg.sale.call_start_prompt | G-man-008; D-78, D-160 | 2a | T-2-36..44 | parity |
| F-SR-020 | AV and KV during the call | Per-outlet assigned AV (landscape video, pre-downloaded on Wi-Fi) then KV (image with বন্ধ করুন) in the fixed order AV, KV, survey, sale; viewed events logged; skip rules unknown. Evid: SR p32. | OFF | - | - | - | app.activity_log | L:cache, outlet_content_assignment | F-API-005 | cfg.content.* | G-man-043, G-feat-12 | 2a | T-2-131 | parity |
| F-SR-021 | POSM survey and photo | Shown for configured outlets: Q1* POSM present (হ্যাঁ or না); Q1.1* photo only if হ্যাঁ ("submit the photo, earn 50 points"); confirm "আপনি কি সার্ভে জমা দেওয়ার বিষয়ে নিশ্চিত?"; +50 points are posted by the server (source = survey_response uuid), never for an AMO survey. Evid: SR p33. | OFF | - | Y | - | app.survey_response | survey_question | F-API-006, F-API-007 | cfg.survey.posm_questions, cfg.loyalty.earning_rules | G-man-041, G-man-043; D-41 | 2a | T-2-132 | parity |
| F-SR-022 | Slide (DRP) empty-pack collection | "Collect DRP Discount" / "স্লাইড সংগ্রহ" lists SKUs with an active offer (OFFER ribbon, detail dialog "ছাড়" with validity); empty packets per SKU by stepper or manual dialog with shortcut steps +1, 5, 10, 20, 50; "জমা দিন" saves. Fixture: 10 empty MaxR-10S packets give 1 reward pack, 80.00 shown as a deduction, quantity total unchanged. One label for the feature. Evid: SR p27-30. | OFF | - | - | - | app.drp_collection | offer | F-API-006 | cfg.drp.shortcut_steps, cfg.promo.rules | G-man-004, G-feat-13; D-33, D-217 | 2a | T-2-36..44 | parity |
| F-SR-062 | Free sample capture | Free-sample lines by SKU for the visit: is_free memo line at price 0 and kind free_sample; reported by route; whether a free-sample-only memo is a successful call is unknown; confirm (default counts, D-46). | OFF | - | - | - | app.memo_line(is_free) | offer, sku | F-API-006 | cfg.promo.rules | G-feat-23; D-46, D-333 | 5b | T-5-42 | new |
| F-SR-061 | Price compliance capture | Shared component with F-AMO-035: observed shelf price per SKU against the outlet price list; disabled in the SR flavour by default; the AMO reconciliation row "মূল্য সম্মতি" counts it. | OFF | - | - | Y | app.price_compliance_check | sku_price | F-API-006 | cfg.sale.price_compliance_sr | G-feat-01; D-334 | 3a | T-3-20..24, 41 | new |
| F-SR-023 | Sale: SKU quantity entry | Per SKU quantity in sticks for cigarette and bidi (pack badge read-only = qty / pack size, stepper step = pack size; loose sticks unknown), pieces for lighter, dozens for match; three unlabelled read-only indicator slots (available stock, percent, free quantity: hypotheses); running total bar and Sale History shortcut; price type resolved by outlet, never chosen; stock warning, soft ceiling for large lines, 60-line memo. Evid: SR p34, UI-SR-15..19. | OFF | - | - | Y | L:memo_draft (L:), app.memo_line | L:sku, L:sales_plan, L:stock, suggestion | - | cfg.sale.qty_entry_unit, cfg.sale.max_line_qty_base, cfg.sale.max_lines_per_memo, cfg.sale.stock_check, cfg.sale.allow_price_type_override | G-man-001, G-man-005, G-feat-44, G-feat-68, G-feat-69, G-field-08; D-16, D-17, D-32, D-246, D-260, D-321 | 2a | T-2-41, T-2-36..44 | changed |
| F-SR-024 | Offers auto-apply | Active promotions apply to lines and memo as offer_discount; the DRP/slide reward is separate; the home card proves automatic discounts exist (total discount 437.50) while no Review screenshot shows a discount line (where it renders is unknown). Discount lines stored as (sku, qty, value, kind) so goods-in-kind and money discounts both fit (UI-SR-26). Evid: SR p34, UI-SR-26. | OFF | - | - | - | app.memo_line.offer_id, memo_offer | offer, promotion | - | cfg.promo.rules | G-feat-13, G-man-004; D-33, D-166, D-343 | 2a | T-2-36..44 | parity |
| F-SR-025 | Review (নিরীক্ষণ) | Lines with category subtotals, slide block, totals; net = gross - offer discount - DRP discount - QC settlement; every non-zero component printed and shown; credit checkbox and Product QC are independent buttons in any order; printer icon top right; draft persisted at "এগিয়ে যান". Evid: SR p34-39, p43-44. | OFF | - | - | - | app.memo (draft), L:memo_draft | L:memo_draft | - | cfg.memo.allow_negative_net, cfg.memo.rounding_mode | G-man-002, G-man-006, G-feat-46; D-18, D-19, D-77, D-203 | 2a | T-2-41, T-2-36..44 | changed |
| F-SR-026 | Credit (বাকি) with partial payment | Checkbox "এই বিক্রয়টি বাকি হিসাবে চিহ্নিত করুন" opens "পরিশোধিত টাকার পরিমাণ লিখুন": collected amount at least 0 and strictly less than the grand total, 2 decimals; due shown to the paisa in the dialog and the checkbox label (the manual truncates 61.50 to 61); no credit limit in Apsis. One label বাকি (ক্রেডিট retired). Evid: SR p34-35. | OFF | - | - | - | app.memo(is_credit, paid_mtk, due_mtk) | outlet dues | - | cfg.credit.allow_zero_payment, cfg.credit.partial_payment_min_pct, cfg.credit.max_due_mtk, cfg.credit.max_days | G-man-009, G-feat-14; D-212, D-213, D-320 | 2a | T-2-36..44 | parity |
| F-SR-027 | Product QC | Yellow "প্রোডাক্ট QC" opens the SKU carousel and QC Entry (max QC in taka with done and remaining; production faults x3 and transport faults x3, defect sticks per type; সংরক্ষণ, বাতিল = discard edits, ডিলিট = remove); summary table "MFC Fault, MKT Fault"; confirm "আপনি কি QC জমা দিতে চান?"; QC is a money deduction at defect sticks x price at capture; completing QC locks edits at that outlet. Evid: SR p36-38, UI-SR-06. | OFF | - | - | - | app.qc_entry, app.qc_entry_line | qc_fault_type | - | cfg.qc.fault_types, cfg.qc.expired_stock_months, cfg.qc.max_amount_basis | G-man-002, G-man-003; D-34, D-159, D-183 | 2a | T-2-36..44 | parity |
| F-SR-028 | Print memo | Print is the commit: dialog 1 "আপনি কি নিশ্চিত? / বিক্রয় জমা হবে", yes commits the immutable record before and regardless of printing; dialog 2 "আপনি কি এই বিক্রয়টি প্রিন্ট করতে চান?", না leaves printed_at null (reprint from Memo); success "বিক্রয় সফল ভাবে জমা হয়েছে". Template is data with 7 memo kinds; layout comes from physical 58 mm samples (never shown in a manual). Evid: SR p38-39. | OFF | memo | - | - | app.memo.printed_at, L:print_event | app.memo | - | cfg.print.template_version, cfg.sale.require_printer_before_sale | G-man-006, G-man-014, G-feat-43, G-sync-02; D-76, D-77, D-158 | 1a | T-1-35, T-1-41, T-1-20..24, 35, 41 | parity |
| F-SR-073 | Print confirmation "ছাপা ঠিক আছে?" | After each print one tap confirms the paper is readable; না marks the job failed_user, and the next print carries no duplicate marker and does not count toward the reprint limit. | OFF | memo | - | - | app.print_event.user_confirmed | - | - | cfg.print.confirm_after_print, cfg.memo.reprint_max | G-field-20; D-76 | 2a | T-2-36..44 | new |
| F-SR-029 | Zero sale | "এগিয়ে যান" with no SKU asks "আপনি কি জিরো (০) বিক্রয় করতে চান?"; the review is printable and completes with Print; no credit checkbox; writes a memo row with line_count 0 that consumes a number, counts as a visit and a no-sale, not as a memo or a successful call; one outcome reason (F-SR-057). Evid: SR p42-43. | OFF | memo | - | Y | app.visit.is_zero_sale, app.memo (line_count 0) | - | - | cfg.visit.outcome_codes | G-feat-38; D-36, D-202 | 2a | T-2-36..44 | parity |
| F-SR-057 | Visit outcome and skip record | Every visit has an outcome (sold, zero_sale_stock_ok, closed, owner_absent, refused, competitor_exclusive, not_reached, abandoned); a skip record without fix or geo gate marks an outlet not reached; abandoned visits are excluded from visited and CPR; three consecutive closed outcomes raise a task to the AMO. Whether Apsis records a closed-shop outcome is unknown (Q50). | OFF | - | - | - | app.visit.outcome_code, kind=skip | - | F-API-006 | cfg.visit.outcome_codes, cfg.visit.closed_streak_task, cfg.kpi.count_abandoned_visits | G-field-03; D-38; Q50 | 2a | T-2-133 | new |
| F-SR-078 | Sell to a not-yet-approved outlet | A newly captured outlet may be sold to the same day: memo and visit reference the request client_uuid as a provisional outlet and are re-linked on approval; blocked when cfg.outlet.sell_before_approval is false. | OFF | memo | - | Y | app.visit.outlet_client_uuid | app.outlet_change_request | F-API-006 | cfg.outlet.sell_before_approval | G-feat-42; D-326 | 2c | T-2-26, 30, 53, 72 | new |

### 3.5 Memo, dues and corrections

| ID | Name | What it does, rules now stated, evidence | Off | Prt | Pic | Geo | Writes | Reads | API | cfg keys | Refs (G, D, Q) | Ph | Gates | St |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-SR-030 | Memo menu | Pick an already visited outlet to view its memo: items table, discount table (SKU, quantity, value), Total discount, Total QC, grand total, outlet due in red beside the name; Print, Edit and Mark paid. Multiple memos per outlet per day exist (P-06) so the selector shows memo number or time. Evid: SR p44-47, UI-SR-26..29. | OFF | memo | - | - | - | L:memo, L:due_collection | - | cfg.app.local_history_days | G-feat-38; D-83 | 2b | T-2-21..24, 43 | parity |
| F-SR-054 | Sale History | Per outlet and date: SKU quantity and value across all memos for that outlet within the user's scope; reachable before and after the geo gate; writes nothing; local window plus online fallback GET /memos?outlet=&date= with an offline banner; footer is the exact sum of rows (the manual's 20,260 versus 20,660 is a bug). One label for "পূর্বের সেল ডাটা দেখুন" and "Sale History". Evid: SR p23, p34. | HYB | - | - | - | - | L:memo, memo_line; GET /memos | F-API-025 | cfg.app.local_history_days | G-man-013; D-83, D-206, D-211, D-218 | 2b | T-2-21..24, 43 | parity |
| F-SR-031 | Memo reprint | Re-prints a committed memo; the paper carries a duplicate marker (what the Apsis printout shows is unknown); reprint count stored and limited; a reprint after a "না" print confirmation carries no marker. | OFF | memo | - | - | L:print_event | app.memo | - | cfg.memo.reprint_watermark, cfg.memo.reprint_max | G-feat-04, G-field-20; D-322 | 2b | T-2-21..24, 43 | changed |
| F-SR-066 | Reprint duplicate marker and edited-memo number | Reprint prints "supersedes <memo_no>" for an edited memo and the duplicate marker for a reprint; the edited memo has its own number from the same series. | OFF | memo | - | - | L:print_event | app.memo | - | cfg.memo.reprint_watermark | G-feat-04, G-field-20; D-322 | 2b | T-2-21..24, 43 | new |
| F-SR-032 | Due collection (বাকি পরিশোধ) | Memo menu shows the outlet with "x ৳ বাকি" and the credit box; "পরিশোধিত করুন" then "আপনি কি নিশ্চিত?" settles the WHOLE memo and a due_collection row (amount = remaining) is always written; success "এই মেমোটি সফলভাবে পরিশোধিত হিসেবে চিহ্নিত হয়েছে।"; partial later collection is an off-by-default enhancement; FIFO allocation for reporting. Evid: SR p44-45. | OFF | - | - | - | app.due_collection | L:memo, imported balances | F-API-006 | cfg.credit.allow_partial_collection, cfg.credit.collection_partial_roles | G-man-010, G-feat-45; D-37, D-161 | 2b | T-2-21..24, 43 | parity |
| F-SR-070 | Due receipt print | A separate receipt for a collection against an older memo: outlet, memo(s) allocated, amount, remaining balance, collector, time and a short code of the due_collection uuid (IMPROVEMENT; Apsis has none, Q47). The printed previous-due line carries a staleness marker "<date> পর্যন্ত". | OFF | receipt | - | - | L:print_event | app.due_collection | - | cfg.print.due_receipt, cfg.memo.due_balance_staleness_marker | G-field-09; D-37; Q47 | 2b | T-2-21..24, 43 | new |
| F-SR-071 | Due dispute capture | The retailer disputes a balance: claimed amount paid, date and collector are captured as an event that becomes an AMO task and a finance queue item; disputed outlets get a badge on the memo list. | OFF | - | - | Y | app.due_dispute, app.task | L:memo | F-API-006 | - | G-field-09 | 2b | T-2-21..24, 43 | new |
| F-SR-033 | Sale edit | Memo menu, Edit: SR inside the outlet geofence and no QC done at the outlet (outlet-level lock, not per memo); choose one of three reasons (first "ভুল SKU নির্বাচিত।"; the other two captured from the live app; wrong_outlet dropped); the sale page reopens pre-filled with the outlet read-only; a new memo row supersedes the old and the outlet due is adjusted. Server re-checks the geofence. Evid: SR p46-47. | OFF | memo | - | Y | app.memo (supersedes_memo_id) | L:memo | F-API-006 | cfg.memo.edit_reasons, cfg.memo.edit_roles, cfg.memo.edit_after_print_policy | G-man-012, G-feat-15, G-feat-16, G-fraud-17; D-86, D-200, D-201 | 2b | T-2-21..24, 43 | parity |
| F-SR-058 | Memo void after print | Event memo_void (reason, fix, retailer acknowledgement) under the same guards as edit and also before Sales Submit, chain depth at most 3; memo status void, due reversal in the ledger, stock back; a cancel slip "বাতিল — মেমো <memo_no>" is printed. Whether Apsis can cancel at all is unknown (Q43). | OFF | cancel slip | - | Y | app.memo_void, dw.fact_due_ledger | app.memo | F-API-006 | cfg.memo.void_reasons, cfg.memo.print_void_slip | G-field-04, G-feat-16; D-86; Q43 | 2b | T-2-21..24, 43 | new |
| F-SR-053 | Returns and damaged goods after QC | Faulty sticks collected at QC leave the SR's stock as a qc_return movement and travel back to the distributor on the day-end return; QC itself stays a money deduction; replacement or credit note is not part of Apsis (ASSUMPTION). | OFF | - | - | - | app.stock_movement(kind=qc_return) | app.qc_entry_line | F-API-006 | cfg.stock.* | G-feat-05, G-field-21; D-323 | 2b | T-2-21..24, 43 | new |

### 3.6 Day close

| ID | Name | What it does, rules now stated, evidence | Off | Prt | Pic | Geo | Writes | Reads | API | cfg keys | Refs (G, D, Q) | Ph | Gates | St |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-SR-036 | Summary and summary print | Per-SKU memo count, quantity, value, discount, discounted value and return; per-category totals (Cigarette, Bidi, Lighter, Match); Total, Discount, Grand total; Print. The SR Summary tile has no manual page, so the content is assumed equal to the AMO Summary and confirmed from the live app. Evid: AMO p46, SR p10. | OFF | summary | - | - | L:print_event | L:memo_line, L:stock_movement | - | cfg.print.template_version | G-man-054, G-feat-60 | 2a | T-2-36..44 | parity |
| F-SR-051 | End-of-day stock return and reconciliation | Physical return entered per SKU (unsold, damaged, short), the distributor's count and variance reason; confirmed by the distributor-house keeper or by the AMO as proxy (confirmed_by nullable until Q41 is answered); the Summary return quantity becomes a statement the keeper can sign. | OFF | return slip | - | - | app.stock_movement(kind=return, damaged, short), confirmed_by | L:stock_movement | F-API-006 | cfg.stock.* | G-feat-02, G-field-01, G-field-21; D-324; Q41 | 2b | T-2-21..24, 43 | new |
| F-SR-052 | Cash deposit and distributor settlement | cash_handover event (declared, counted, variance, counted_by, fix) at day end; settlement view for the keeper (F-WEB-059); sums paid plus collected less float. | OFF | receipt | - | Y | app.cash_handover | L:memo, L:due_collection | F-API-006 | cfg.stock.* | G-feat-03, G-field-01; D-325; Q41 | 2b | T-2-21..24, 43 | new |
| F-SR-034 | Sync | Flush pending rows and show device versus server counts for outlet, sale, stock, QC and promotion (the manual's "ডাটা সিঙ্ক করুন"); automatic after each sale (R5) but the screen still works as the end-of-day check. Evid: SR p71-73. | OFF | - | - | - | as F-SYS-008 | L:* | F-API-006 | cfg.sync.reconcile_types | G-man-031; D-59, D-222 | 1b | T-1-25..34, 51..52 | changed |
| F-SR-035 | Sales Submit (বিক্রয় জমা) | Enabled after sync; tapping with retailers still owing shows "আপনার এখনো ১টি রিটেইলারের কাছে বাকি রয়েছে। আপনি আপনার বিক্রয় জমা দিতে চান?" (count is dynamic; warns, never blocks); an outbox event that is always the last record of the day and settles on the server; success "বিক্রয় সফল ভাবে জমা হয়েছে"; submitted_with_dues recorded. Evid: SR p71-73. | QUE | - | - | - | app.route_day.sales_submitted_at, app.day_submit | L:memo.due_mtk | F-API-008 | cfg.day.sales_submit_dues_warning, cfg.day.sales_submit_offline_queue, cfg.day.submit_settle_timeout_min | G-man-030, G-man-031, G-feat-65; D-64, D-173, D-216 | 2e | T-2-31..35, 47..49, 53..57 | changed |
| F-SR-012 | Attendance check-out | Enabled at corrected Dhaka time 17:00 or later (inclusive), press-and-hold, then "চেক আউট সম্পন্ন হয়েছে" and the day-complete message; an outbox event. Evid: SR p14-15, UI-SR-14. | OFF | - | - | fix | app.attendance_event | - | F-API-006 | cfg.day.checkout_earliest_time, cfg.app.hold_to_confirm_ms | G-man-029, G-feat-18; D-209, D-225 | 2e | T-2-31..35, 47..49, 53..57 | parity |
| F-SR-059 | Field-side day exception | Outbox event day_exception (rain, hartal, market closed, DH out of stock, breakdown, sick without leave) for the own route; approved by the TSO in the app; an approved exception removes the route from the Login %, Submit % (of logged-in), Day-completion % and Daily Tracking denominators and labels it exception. | OFF | - | - | - | app.day_exception | L:route_day | F-API-047 | cfg.day.exception_reasons, cfg.day.exception_requires_approval, cfg.day.exception_max_days | G-field-02, G-feat-10; D-39 | 2e | T-2-31..35, 47..49, 53..57 | new |
| F-SR-080 | Day reopen after a submit void | After a Sales Submit the route-day is locked locally when `cfg.day.sales_submit_locks_capture` is true; a submit void by the TSO, L1 with confirmation or ops_admin returns `submit_voided` in the next response or delta: the phone shows "জমা বাতিল হয়েছে; আবার বিক্রি করা যাবে", unlocks capture, keeps the old `day_submit` as superseded and uses a new client uuid for the next submit; no local data is lost and no row is re-sent (D-539). | QUE | - | - | - | L:day_local, L:outbox | route_day | F-API-069 | cfg.day.sales_submit_locks_capture, cfg.day.submit_undo_window_min | G-qa-70; D-539, OI-17-14 | 3b | T-3-150, T-3-61 | new |
| F-SR-081 | Stock "correct total" | The explicit path for a wrong stock load: shows today's loaded total per SKU, the SR enters the correct total and a reason, and the app posts a SIGNED `adjustment` movement (correct total minus loaded total) in one family, never an overwrite; refused when `cfg.stock.correct_total_enabled` is false; a same-values re-save is refused by the double-tap guard. The AMO stock screen has the same path (F-AMO-049). Evid: SR manual F-45 shows the Stock screen only; the re-save behaviour of Apsis is unknown (MQ-68). | OFF | - | - | - | app.stock_movement (kind adjustment) | L:stock_movement | F-API-006 | cfg.stock.correct_total_enabled, cfg.stock.save_mode, cfg.stock.resave_guard_window_min | G-qa-116; D-580; MQ-68 | 2a | T-2-161 | new |

### 3.7 Outlet management

| ID | Name | What it does, rules now stated, evidence | Off | Prt | Pic | Geo | Writes | Reads | API | cfg keys | Refs (G, D, Q) | Ph | Gates | St |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-SR-037 | Outlet request: new shop | Outlet tile, New shop: cluster (not route), shop name, owner, mobile (11 digits), "GEO এবং ছবি ধারণ করুন" (one photo and one fix), Save with no confirm and success "সফল"; request goes to AMO verification then web approval; GEO and photo required (the manual says "must" only for info change; required here as an IMPROVEMENT). Evid: SR p53-55. | QUE | - | Y | fix | app.outlet_change_request(type=new), app.outlet_photo | L:cluster | F-API-006, F-API-007 | cfg.outlet.mobile_regex, cfg.outlet.amo_request_approval | G-man-033, G-man-019, G-feat-42; D-43, D-162 | 2c | T-2-26, 30, 53, 72, T-2-135 | changed |
| F-SR-038 | Outlet request: permanently closed | Cluster, outlet (label name (sub-channel)), read-only name, owner and mobile, Save, confirm "এই দোকানটি স্থায়ীভাবে বন্ধ হতে যাচ্ছে", success; no reason, photo or GEO; open dues warn (default) per cfg.outlet.close_block_if_dues; the outlet stays visible until approval. Evid: SR p56-57. | QUE | - | - | - | app.outlet_change_request(type=close) | L:outlet | F-API-006 | cfg.outlet.close_block_if_dues, cfg.outlet.close_block_if_dues | G-man-033, G-field-09; D-25 | 2c | T-2-26, 30, 53, 72, T-2-135 | parity |
| F-SR-039 | Outlet request: info change | Cluster, outlet, then edit name, owner, mobile; GEO and photo must be captured ("ছবি ধারণ করা সম্পন্ন হয়েছে"); Save, confirm "এই পরিবর্তন সংরক্ষণ করা হবে", success. Evid: SR p58-60. | QUE | - | Y | fix | app.outlet_change_request(type=info), app.outlet_photo | L:outlet | F-API-006, F-API-007 | cfg.outlet.mobile_regex | G-man-033, G-man-019; D-162 | 2c | T-2-26, 30, 53, 72, T-2-135 | parity |
| F-SR-040 | Own-request status | Pending, verified, approved or rejected shown against the SR's own requests; a rejected request shows its reason (IMPROVEMENT). | CAC | - | - | - | - | bundle outletRequests | F-API-005 | cfg.outlet.reject_requires_reason | G-man-034; D-43 | 2c | T-2-26, 30, 53, 72 | new |
| F-SR-076 | Outlet missing from my route request | Request type "add this existing outlet to my route/cluster" so a cutover-week missing or wrongly routed outlet is not re-created through New shop (duplicate risk, FS-03). | QUE | - | - | - | app.outlet_change_request(type=info, route/cluster) | app.outlet | F-API-006 | cfg.outlet.amo_request_approval | G-feat-61, G-feat-62; D-330 | 2c | T-2-26, 30, 53, 72 | new |
| F-SR-079 | GEO and photo capture component | Shared capture screen for new shop, info change, force sale and verification: one fix at the shutter with the mock flag, thumbnail and "GEO captured, +/- n m" confirmation, one retake, compressed per F-SYS-030. Evid: SR p54, p59. | OFF | - | Y | fix | L:media_queue | - | - | cfg.media.photo_max_kb | G-man-019 | 2c | T-2-26, 30, 53, 72 | changed |

### 3.8 Programmes

| ID | Name | What it does, rules now stated, evidence | Off | Prt | Pic | Geo | Writes | Reads | API | cfg keys | Refs (G, D, Q) | Ph | Gates | St |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-SR-041 | Astha: route information | Tabs Route info and Shop info; year then quarter (Q-<n> (<Mon>-<Mon>)), month chips (zero chips = whole quarter); STD target per brand (Target, Achievement, Remaining, %); Memo target one "All Brand" row. Target 0 or less shows a dash. Evid: SR p48-49. | CAC | - | - | - | - | program_outlet_target, agg | F-API-005, F-API-021a | cfg.astha.quarter_start_month, cfg.astha.memo_target_month_filter | G-man-045; D-168, D-231 | 5a | T-5-10, 41 | parity |
| F-SR-042 | Astha: shop information | Astha retailer cards (name (code-phone-cluster), owner, contact, sub-channel tier, wing, division, territory, zone) and the outlet view: STD target per brand without Remaining, memo target per brand. Evid: SR p50-52. | CAC | - | - | - | - | L:outlet | - | - | G-man-045; D-168 | 5a | T-5-10, 41 | parity |
| F-SR-043 | Diamond League redemption | Pick outlet, see "Diamond League (<month>)" and live "Pts Remaining"; gift grid (cash back 2 Tk per point up to 199 points, 2-step kitchen rack 200, chair 200, tornado fan 400 (the inventory's 800 is a misread)); + disabled when cost exceeds remaining, - disabled at 0; one confirm redeems a basket of lines; "Confirm redemption of these items? Points will be deducted." then "Redemption successful". Evid: SR p61-63. | QUE | - | - | - | app.redemption, app.redemption_line, app.loyalty_ledger | L:loyalty balance, gift catalog | F-API-006 | cfg.loyalty.cash_rate_mtk_per_point, cfg.loyalty.cash_max_points, cfg.loyalty.gift_catalog | G-feat-20, G-man-042; D-41, D-219, D-266 | 5a | T-5-10, 41 | parity |
| F-SR-055 | Outlet Points | "Points" from the sale outlet screen: league label (Diamond League (April)), balance (110 Pts), Expiring Points, Expiry Date (2026-05-07), as of the previous day; links to Redemption; redemption only from unexpired points. Evid: SR p24. | CAC | - | - | - | - | bundle snapshot | F-API-005 | cfg.loyalty.expiry_days | G-man-040; D-41 | 5a | T-5-10, 41 | parity |
| F-SR-044 | Photo Capture: Astha gift photo | Outlets with a gift chosen on the web Astha Gift Choice panel; gift name (e.g. "27 pcs Dinner Set"), one hand-over photo per outlet (delete, reload, zoom), confirm, "Photo capture details saved successfully."; afterwards "Photo already captured." with the Submit button hidden for that outlet. Q-28 default (D-582): Submit is enabled only when EVERY capture slot of the outlet has a photo (Astha has one slot); a partial campaign submit is allowed only if MQ-28 is confirmed from a capture of the live app, and then the missing slot is flagged `photo_missing` and raises a task to the AMO. Evid: SR p64-67. | QUE | - | Y | fix | app.gift_photo | bundle giftAssignments | F-API-006, F-API-007 | cfg.astha.gift_choice_lock | G-man-047; D-192 | 5a | T-5-10, 41, T-5-123 | parity |
| F-SR-045 | Photo Capture: campaign gift verify | Outlets with redeemed gifts; campaign dropdown ("Diamond League (March) Gift Verify"); one capture slot per redeemed gift; Submit is enabled when all slots are filled (the Q-28 default of F-SR-044: a partial submit only if MQ-28 confirms it, with the missing slot flagged and a task raised, D-582); "Campaign details saved successfully." Evid: SR p68-70. | QUE | - | Y | fix | app.gift_photo, redemption.photo | app.redemption | F-API-006, F-API-007 | - | G-feat-20 | 5a | T-5-10, 41, T-5-123 | parity |

### 3.9 Tasks and content

| ID | Name | What it does, rules now stated, evidence | Off | Prt | Pic | Geo | Writes | Reads | API | cfg keys | Refs (G, D, Q) | Ph | Gates | St |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-SR-046 | Task list and badge | Tasks assigned by AMO or TSO (type OOS, General, Irregular Visit; outlet; text; completion date); status চলমান (ongoing) or সম্পন্ন (completed); resolved tasks stay on the list; badge = open task count; list arrives with the bundle delta. Evid: SR p75-76. | CAC | - | - | - | - | bundle tasks | F-API-005 | cfg.task.statuses, cfg.task.types | G-man-049, G-feat-19; D-167, D-328 | 3b | T-3-155, T-5-43 | parity |
| F-SR-047 | Task resolve (swipe) | Swipe left to right then Resolve; status becomes সম্পন্ন; no confirm; works offline and is queued with a client uuid. Evid: SR p76. | QUE | - | - | - | app.task (status, resolved_at) | - | F-API-006, F-API-026 | cfg.task.statuses | G-man-049 | 3b | T-3-155, T-5-43 | parity |
| F-SR-048 | Tutorial video list | List from the backend, empty state "কোনো টিউটোরিয়াল পাওয়া যায়নি।"; list cached, playback online and never autoplays or downloads in the background. Evid: SR p74. | HYB | - | - | - | - | tutorial_video | F-API-027 | cfg.content.tutorial_videos | G-feat-47 | 2e | T-2-134 | parity |
| F-SR-049 | Outlet detail card | Name, owner, phone, cluster, channel, open dues, loyalty points and last visit from the list entry; opening-balance provenance (F-SYS-068). | OFF | - | - | - | - | L:outlet, dues, loyalty | - | - | G-field-22 | 1a | T-1-20..24, 35, 41 | new |
| F-SR-050 | Current stock tracker | Running stock per SKU = issued - sold - returned computed locally after every sale; feeds the KPI strip, the Summary return quantity and the stock warning at sale entry. | OFF | - | - | - | L:stock_view | L:stock_movement, L:memo_line | - | cfg.sale.stock_check | G-feat-44; D-321 | 2a | T-2-36..44 | new |

Proved by: T-1-20..24 and T-1-41 (offline day, kill and relaunch, print parity), T-2-36..44 (sale, QC, DRP, credit, stock), T-2-21..24 and T-2-43 (corrections, goldens), T-2-26, T-2-30 (outlets and media), T-2-10..19 (geo, force sale), T-2-31..35 and T-2-47..49 (day close, pilot readiness), T-5-10 and T-5-41 (programmes).

## 4 AMO app (F-AMO)

46 rows. The AMO works one zone (login = the zone), coaches, verifies and can sell; same offline, sync and geo rules as the SR app. The AMO bundle is zone-wide (F-AMO-044). Per-user tile resolution (F-AMO-040) replaces the fixed 17-tile list. Evidence pages are those of the AMO manual (80 pages, 62 screens).

### 4.1 Home, tiles and day state

| ID | Name | What it does, rules now stated, evidence | Off | Prt | Pic | Geo | Writes | Reads | API | cfg keys | Refs (G, D, Q) | Ph | Gates | St |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-AMO-001 | Home header and tile grid | Header "<display name> (<username>)" and "<territory/point><role>, <business date>"; 17 tiles in the full set (Attendance, Joint Call, Control Call, Team Location, Team Performance, Task Delegation, Live Dashboard, SR Stock, Outlet, Stock, Sale, Memo, Summary, Sales Submit, Astha, Report, Settings); tiles resolved per user (F-AMO-040); badge only on Outlet = pending SR verification requests in the AMO's scope, shown only above zero (Task Delegation has no badge on the AMO home); collapse chevron. Evid: AMO p8-12. | CAC | - | - | - | - | bundle (zone scope) | F-API-005 | cfg.app.home_tiles | G-man-056, G-man-057, G-man-055; D-169, D-229 | 3a | T-3-20..24, 41 | changed |
| F-AMO-002 | Home KPI tiles (month to date) | Four tiles: Today's target (done/total), Total call target (all saved control plus joint visits), Control-call target, Joint-call target; display-only; targets come from the supervisor target table (origin unknown; confirm). Evid: AMO p11. | CAC | - | - | - | - | /app/home, supervisor_target | F-API-018 | cfg.target.supervisor_targets | G-man-057, G-feat-57 | 3a | T-3-20..24, 41 | parity |
| F-AMO-040 | SS designation and per-user menu variants | AMO build shows a 13-tile set for user ss344002 (no Team Performance, SR Stock, Astha) and 16 or 17 for AMOs; rule: Team Performance and SR Stock need subordinate SRs in scope, Astha needs Astha outlets in scope, Report is gated by flag. What SS means (Sales Supervisor or substitute) is unknown; confirm. Evid: AMO p8-12, Web p18. | CAC | - | - | - | app_user.designation | user scope | F-API-005 | cfg.app.home_tiles | G-man-055, G-man-100; D-187; MQ-19 | 3a | T-3-20..24, 41 | parity |
| F-AMO-039 | Supervisor day state | supervisor_day (user x business date: checked_in, synced, sales_submitted) separate from route_day so an AMO submit needs no routeId and does not flip an SR route; AMO sales on a route do not count toward that route's Login % or Submit % (of logged-in). | OFF | - | - | - | app.supervisor_day | - | F-API-008 | cfg.kpi.target_route_kinds | G-man-032; D-27 | 3a | T-3-20..24, 41 | new |

### 4.2 Attendance and bundle

| ID | Name | What it does, rules now stated, evidence | Off | Prt | Pic | Geo | Writes | Reads | API | cfg keys | Refs (G, D, Q) | Ph | Gates | St |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-AMO-003 | Attendance (press-and-hold) | As F-SR-011/012: address with Refresh, check-in sheet with time chip and hold, four states (Bangla and English done-state strings both appear), check-out from 17:00; coordinates offline. Evid: AMO p17-20. | OFF | - | - | fix | app.attendance_event | - | F-API-006 | cfg.day.checkout_earliest_time, cfg.app.hold_to_confirm_ms | G-man-029; D-209, D-225 | 3a | T-3-20..24, 41 | parity |
| F-AMO-044 | Zone-wide AMO bundle | Outlets of every route of the zone (id, code, name, owner, phone, cluster, lat/lng, route, due, status; 3,500 to 11,000 for a 54-route zone), pending verification requests, SR list, tasks; paged above 2,000 rows with a 2 MB gz cap, delta-refreshed; a Control Call at any zone outlet must work in airplane mode. | CAC | - | - | - | L:* | bundle | F-API-005 | cfg.bundle.stale_max_days | G-man-065; D-72 | 3a | T-3-20..24, 41 | parity |

### 4.3 Calls: control, joint, assessment, survey

| ID | Name | What it does, rules now stated, evidence | Off | Prt | Pic | Geo | Writes | Reads | API | cfg keys | Refs (G, D, Q) | Ph | Gates | St |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-AMO-004 | Control Call: route, outlet, geo gate | Pick route and retailer (label name-code-phone-cluster, "--" for an empty phone); outside the radius: "AMO এবং রিটেইলার রেঞ্জের মধ্যে নেই।" with ম্যানুয়াল ওভাররাইড and রিফ্রেশ; visit kind amo_control_call; the standalone Sale tile opens this same path (one code path, no un-gated AMO sale). Evid: AMO p25-27. | OFF | - | - | Y | app.visit(kind=amo_control_call), app.geo_fix | L:outlet | F-API-006 | cfg.geo.radius_m | G-man-015; D-26, D-221 | 3a | T-3-20..24, 41 | parity |
| F-AMO-005 | Manual Override (outlet photo) | Photo at an out-of-range outlet; like Force Sale it raises a location-change request and the call proceeds (DELIBERATE CHANGE: the manual updates the location at once); override cap per day. Evid: AMO p26. | QUE | - | Y | Y | app.outlet_photo, app.outlet_change_request(type=location) | - | F-API-006, F-API-007 | cfg.geo.override_max_per_day, cfg.geo.outlet_location_change_approval | G-man-017; D-95, D-163 | 3a | T-3-20..24, 41 | changed |
| F-AMO-006 | Control Call: Sale | Same sale flow as F-SR-023..028 against the AMO's own stock; memo kind amo_control_call, counted in route STD and amo_successful_calls, never in the SR strike rate. Evid: AMO p28-29. | OFF | memo | - | Y | as F-SR-023..028 | as SR | F-API-006 | as SR | G-man-015, G-field-16; D-26 | 3a | T-3-20..24, 41 | parity |
| F-AMO-012 | AMO Sale tile (standalone) | Merged into F-AMO-006: the tile opens the route and retailer picker with Sale preselected; "View previous sale data" date picker is F-AMO-013. | OFF | memo | - | Y | as F-AMO-006 | as F-AMO-006 | F-API-006 | as F-AMO-006 | G-man-015 | 3a | T-3-20..24, 41 | parity |
| F-AMO-007 | SR Perf. Assessment: distribution | Tick the 15 brands present (Maxim ... Salmon, order from the manual) from a product-tree flag; one distribution_check row per brand. Evid: AMO p31. | OFF | - | - | Y | app.distribution_check | product_brand | F-API-006 | cfg.rubric.distribution_brands | G-man-050 | 3a | T-3-20..24, 41 | parity |
| F-AMO-008 | SR Perf. Assessment: OOS | OOS may be ticked only for brands ticked as distributed (oos implies present, UI and CHECK). | OFF | - | - | Y | app.distribution_check.oos | product_brand | F-API-006 | cfg.rubric.distribution_brands | G-man-050 | 3a | T-3-20..24, 41 | parity |
| F-AMO-009 | SR Perf. Assessment: POSM | Tri-state unanswered, yes, no (yes and no exclusive); Save, success "ডেটা সফলভাবে সংরক্ষিত হয়েছে।", then back to the outlet picker with the route kept and the retailer cleared. Evid: AMO p32. | OFF | - | - | Y | app.distribution_check.posm | - | F-API-006 | - | G-man-050 | 3a | T-3-20..24, 41 | parity |
| F-AMO-043 | AMO Survey screen | Tile exists (p27) and the form is never shown; probably the SR POSM survey reused. Feature-flagged until captured from the live app; counted in the reconciliation row সার্ভে; no +50 points. Evid: AMO p27. | OFF | - | Y | Y | app.survey_response | survey_question | F-API-006, F-API-007 | cfg.survey.amo_questions | G-man-044; D-348 | 3a | T-3-20..24, 41 | parity |
| F-AMO-010 | Control Call: Survey | Entry point to F-AMO-043. | OFF | - | Y | Y | app.survey_response | survey_question | F-API-006 | cfg.survey.amo_questions | G-man-044 | 3a | T-3-20..24, 41 | parity |
| F-AMO-011 | Joint Call assessment | Route and retailer with geo gate; read-only header "রুট" and "খুচরা বিক্রেতাঃ"; the assessed SR is the route's active assignee on the date (acting user if cover), shown read-only and recorded; 1 to 5 stars (Low to উচ্চ): five-step sales call (Summarise the situation; ধারণাটি বর্ণনা করুন; জিনিস টা কিভাবে কাজ করবে তা ব্যাখ্যা করা; items 4 and 5 are not in the manual), relationship, service quality; success "ডেটা সফলভাবে সংরক্ষিত হয়েছে।" and the form returns with route and retailer kept. Rubric is versioned data with 2 disabled placeholders. Evid: AMO p23-24. | OFF | - | - | Y | app.call_assessment(kind=joint_call), app.visit(kind=amo_joint_call) | assessment_rubric | F-API-006 | cfg.rubric.joint_call, cfg.rubric.require_all_rated, cfg.rubric.default_rating | G-man-051, G-man-052, G-feat-56; D-221, D-349 | 3a | T-3-20..24, 41 | parity |
| F-AMO-041 | Acting-for sale | The AMO sells the rest of a dead-phone SR's route on his own phone: memos and visits carry acting_for_user_id with the route assignee referenced; KPIs of users use the acting user, route KPIs include the memo. | OFF | memo | - | Y | app.memo.acting_for_user_id | route assignees | F-API-006 | cfg.auth.confirm_identity_on_first_capture | G-field-05; D-26, D-85 | 3a | T-3-20..24, 41 | new |
| F-AMO-046 | Joint-call QR verification | The SR's new-outlet request shows a QR of its client_uuid; the AMO scans it and a verification event is parked until the request lands on the server (IMPROVEMENT). | OFF | - | - | fix | app.outlet_change_request (verification parked until the request lands, DQ-03) | L:* | F-API-006 | - | G-field-23 | 3a | T-3-20..24, 41 | new |

### 4.4 Sale, memo and summary

| ID | Name | What it does, rules now stated, evidence | Off | Prt | Pic | Geo | Writes | Reads | API | cfg keys | Refs (G, D, Q) | Ph | Gates | St |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-AMO-014 | Memo menu: Print, Edit, Mark paid | As F-SR-030..033 (two layouts exist: credit memo "রুট" with category subtotals and credit panel; cash memo "সেকশনঃ" with one মোট row); Mark paid settles the whole memo; Edit is grey on every screenshot (reason unknown: apply the SR rules); an AMO's due label and dues count cover the AMO's own credit memos only. Evid: AMO p42-45. | OFF | memo | - | Y | app.memo, app.due_collection | L:memo | F-API-006 | cfg.memo.edit_roles, cfg.credit.collection_partial_roles | G-man-010, G-man-011, G-man-012, G-man-014; D-37, D-214 | 3a | T-3-20..24, 41 | parity |
| F-AMO-013 | View previous sale data (date picker) | As F-SR-054 for the AMO: per outlet and date aggregate; local window plus GET /memos fallback; totals computed exactly (the manual footer is wrong by 400). Evid: AMO p30. | HYB | - | - | - | - | L:memo, GET /memos | F-API-025 | cfg.app.local_history_days | G-man-013; D-206, D-211 | 3a | T-3-20..24, 41 | parity |
| F-AMO-015 | Summary and print | Whole-day table per SKU (memo count, quantity, value, discount, discount value, return) plus a second block Total, Discount (-), Grand total ("সর্ব মোট" with a space); Print. Evid: AMO p46. | OFF | summary | - | - | L:print_event | L:memo_line | - | cfg.print.template_version | G-man-002, G-man-014 | 3a | T-3-20..24, 41 | parity |
| F-AMO-029 | AMO stock load and stock memo | Issue and Stock columns, category totals, Save and Print as F-SR-014/015 with the SAME re-save rule (loaded total read-only, increment-only Save, double-tap guard, "correct total" as F-AMO-049, D-580); whose stock the AMO screen shows and what Print outputs is unconfirmed (the Print button stays grey after connecting on p16: treated as a defect). Evid: AMO p14-16. | OFF | stock | - | - | app.stock_movement | L:sku | F-API-006 | cfg.stock.max_issue_qty | G-man-007 | 3a | T-3-20..24, 41, T-3-160 | parity |
| F-AMO-030 | AMO Sales Submit (extended reconciliation) | Rows Outlet, Sale, Stock, QC, Promotion, Distribution and OOS performance, Price compliance, Joint call, Survey (8 or 9 shown); Server column is the last server_totals; types without a capture screen show 0; Sync then Sales Submit with the unpaid-dues confirm; AMO submit writes supervisor_day. Evid: AMO p66-68. | QUE | - | - | - | app.supervisor_day | L:* | F-API-008 | cfg.sync.reconcile_types | G-man-030, G-man-031, G-man-032; D-173, D-222 | 3a | T-3-20..24, 41 | changed |

### 4.5 Team and live views

| ID | Name | What it does, rules now stated, evidence | Off | Prt | Pic | Geo | Writes | Reads | API | cfg keys | Refs (G, D, Q) | Ph | Gates | St |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-AMO-016 | Team Location map | SR list then a map pin per SR showing the last synced fix with "last seen HH:MM (n min ago)" and its source, greyed after 120 min, list fallback offline (DELIBERATE CHANGE: the manual says live). Evid: AMO p33. | HYB | - | - | - | - | user_last_fix | F-API-023 | cfg.tso.team_location_max_age_min, cfg.map.provider | G-man-058, G-man-059; D-08, D-170 | 3a | T-3-20..24, 41 | changed |
| F-AMO-017 | Team Performance | Tabs Monthly Target and Till Date Target; zone card and one card per route with four category bars (green from 80, amber from 40, red below 40; printed percent capped at 100), Details to a brand table (Item, Target, Achievement, Remaining, %; uncapped, 2 decimals); tapping a category bar opens that category only; till-date basis about 25/30. Evid: AMO p34-36. | CAC | - | - | - | - | dw.agg_month_zone_product, target | F-API-018, F-API-021a | cfg.kpi.tilldate_basis.amo_team_performance, cfg.kpi.bar_bands, cfg.kpi.card_pct_cap | G-man-060, G-man-067; D-50, D-51, D-52, D-195 | 3a | T-3-20..24, 41 | parity |
| F-AMO-018 | Task Delegation: assigned list | "Assigned Tasks" tab: outlet, text, status (চলমান), completion date; resolutions shown; no Task Delegation badge on the AMO home. Evid: AMO p37. | CAC | - | - | - | - | app.task | F-API-026 | cfg.task.statuses | G-man-049; D-167 | 3b | T-3-155, T-5-43 | parity |
| F-AMO-019 | Task Delegation: assign (+) | Route or section, outlet, read-only outlet info card (outlet, owner, SR name, SR mobile masked), Task Type, completion date (not before today unless configured), free-text description; assignee is the outlet's SR on the due date; success toast "Save Successfully!". Evid: AMO p38. | QUE | - | - | - | app.task | route_assignment | F-API-006, F-API-026 | cfg.task.types, cfg.task.description_max_len, cfg.task.allow_past_due_date | G-man-049; D-328 | 3b | T-3-155, T-5-43 | parity |
| F-AMO-020 | Live Dashboard (zone) | Zone and route dropdowns plus an explicit Filter press (saves data): Sales (total sales count or quantity, total taka: "মোট বিক্রয় 38" equals the sale quantity total, label to be verified), Live strike rate (1/81 = 1.23%), Geo-fencing status (target outlets, geo-validated, photo-validated, total visited, geo %), Login and submit status (Submit % (of logged-in)). Explicit load, "as of" stamp. Evid: AMO p39. | ONL | - | - | - | - | dw.agg_daily_route, agg_daily_outlet | F-API-016 | cfg.kpi.submit_pct_denominator | G-man-061, G-man-066; D-45, D-48 | 3a | T-3-20..24, 41 | parity |
| F-AMO-021 | SR Stock | SR list (code, route, masked phone "***********", total outlets) then lifted stock by SKU for today; the SR identifier rule (SR-12237, SR-Kakoli - 1, sr334001 all appear) is one display-name rule; no mixed-unit grand total (per category). Evid: AMO p40-41. | ONL | - | - | - | - | app.stock_movement (today) | F-API-024 | cfg.pii.mask_style | G-man-062; D-108, D-171 | 3a | T-3-20..24, 41 | parity |

### 4.6 Outlet verification and operations

| ID | Name | What it does, rules now stated, evidence | Off | Prt | Pic | Geo | Writes | Reads | API | cfg keys | Refs (G, D, Q) | Ph | Gates | St |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-AMO-022 | Verify: new outlet | Pending list (route, cluster, shop, owner, contact), then the form: route and cluster pre-filled and editable, name, owner, mobile, Sub-Channel and Geo Classification (required; 19 percent of outlets lack a class), GEO and photo optional, বাতিল = discard with no server call, সংরক্ষণ = Verify; success "ডেটা সফলভাবে সংরক্ষিত হয়েছে।"; the web approves or rejects. Evid: AMO p47-50. | QUE | - | opt | fix | app.outlet_change_request(status=verified) | bundle requests | F-API-006, F-API-055 | cfg.outlet.verify_roles, cfg.outlet.verify_requires_subchannel, cfg.outlet.verify_requires_geo_class | G-man-034, G-man-035; D-43, D-172, D-230 | 3a | T-3-20..24, 41 | changed |
| F-AMO-023 | Verify: outlet closure | Request list and detail (route, cluster, read-only outlet fields); Save verifies; success text is English "Data Updated Successfully" on this screen (standardised); the balance and points of the outlet are shown to the verifier. | QUE | - | - | - | app.outlet_change_request | bundle requests | F-API-006, F-API-055 | cfg.outlet.close_block_if_dues | G-man-034, G-field-09 | 3a | T-3-20..24, 41 | parity |
| F-AMO-024 | Verify: info change | Edit form with an old-versus-new diff so the AMO can verify; GEO and photo optional; Save verifies. Evid: AMO p53-54. | QUE | - | opt | - | app.outlet_change_request | bundle requests | F-API-006, F-API-055 | cfg.outlet.verify_roles | G-man-034, G-man-035 | 3a | T-3-20..24, 41 | parity |
| F-AMO-025 | AMO own: New shop | Cluster, name, owner, mobile, GEO and photo; goes to the web approver (no AMO verification); route assigned by the approver unless the AMO picks one. Evid: AMO p55-57. | QUE | - | Y | fix | app.outlet_change_request(type=new) | L:cluster | F-API-006, F-API-007 | cfg.outlet.amo_request_approval | G-man-033; D-162 | 3a | T-3-20..24, 41 | parity |
| F-AMO-026 | AMO own: Permanent close | Cluster, retailer (name-code-phone-cluster), confirm, success; open dues warn. Evid: AMO p58-59. | QUE | - | - | - | app.outlet_change_request(type=close) | L:outlet | F-API-006 | cfg.outlet.close_block_if_dues | G-man-033 | 3a | T-3-20..24, 41 | parity |
| F-AMO-027 | AMO own: Info change | Cluster, retailer, edit fields, GEO and photo, confirm, success. Evid: AMO p60-62. | QUE | - | Y | fix | app.outlet_change_request(type=info) | L:outlet | F-API-006, F-API-007 | cfg.outlet.mobile_regex | G-man-033 | 3a | T-3-20..24, 41 | parity |
| F-AMO-028 | Update Base (location recalibration) | Route, outlet, "আপডেট বেস", confirm "আপনি কি নিশ্চিত, খুচরা বিক্রেতার অবস্থান আপডেট করতে?", photo, then pick the exact point on the map and "নিশ্চিত করুন". New guards: AMO within 100 m of the chosen point, a move limit, no mocked fix, a monthly cap, a success or pending message; routed through approval; offline falls back to the current fix with numeric adjustment. Evid: AMO p64-65. | HYB | - | Y | Y | app.outlet_change_request(type=location), app.outlet_photo | map tiles | F-API-006, F-API-007 | cfg.geo.update_base_max_distance_m, cfg.geo.update_base_max_move_m | G-man-018, G-feat-39; D-95, D-111 | 3a | T-3-20..24, 41 | changed |

### 4.7 Astha and reports

| ID | Name | What it does, rules now stated, evidence | Off | Prt | Pic | Geo | Writes | Reads | API | cfg keys | Refs (G, D, Q) | Ph | Gates | St |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-AMO-031 | Astha with route selector | As the SR Astha screens (F-SR-041/042) with a route dropdown; the outlet view shows a single "All Brand" memo row. Evid: AMO p69-73. | CAC | - | - | - | - | program_outlet_target | F-API-021a | cfg.astha.memo_target_month_filter | G-man-045; D-168 | 5a | T-5-10, 41 | parity |
| F-AMO-032 | Report: STD Memo Report | Date range default first of month to yesterday (include-today toggle, max range), frozen first column instead of the hidden swipe; per-route STD by category plus memo count; column set captured from the live app. Evid: AMO p75. | ONL | - | - | - | - | dw.agg_daily_route_sku | F-API-017b | cfg.report.default_range, cfg.report.include_today, cfg.report.max_range_days | G-man-063, G-feat-53 | 3a | T-3-20..24, 41 | parity |
| F-AMO-033 | Report: Sales summary up to now | Route cards (CPR percent, total memos) then route detail: brand table Target (fractional, monthly x 15/17), Sales, %, Memo; zero target shows a dash (DELIBERATE CHANGE from 0%); MTD CPR = successful calls / target outlets over trading days to yesterday. Evid: AMO p76. | ONL | - | - | - | - | dw.agg_month_zone_product, target | F-API-017b | cfg.kpi.tilldate_basis.amo_sales_summary | G-man-064; D-50, D-51 | 3a | T-3-20..24, 41 | parity |

### 4.8 Settings, supervision and exceptions

| ID | Name | What it does, rules now stated, evidence | Off | Prt | Pic | Geo | Writes | Reads | API | cfg keys | Refs (G, D, Q) | Ph | Gates | St |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-AMO-034 | Settings | Language, PDA to Support, Log Out. Evid: AMO p77-79. | OFF | - | - | - | L:prefs | - | - | cfg.app.default_locale | G-man-025 | 3a | T-3-20..24, 41 | parity |
| F-AMO-035 | Price compliance check | Shared component F-SR-061 at a control call: observed price per SKU versus the outlet list; counted in the AMO reconciliation row; fields and rules are not in any manual (ASSUMPTION). | OFF | - | - | Y | app.price_compliance_check | sku_price | F-API-006 | cfg.sale.price_compliance_sr | G-feat-01; D-334 | 3a | T-3-20..24, 41 | new |
| F-AMO-036 | Visit kind tagging | Every AMO outlet open is tagged control or joint so SR CPR excludes AMO calls; see F-SYS-078. | OFF | - | - | - | app.visit.kind, subject_sr_id | - | - | - | G-field-16; D-26 | 3a | T-3-20..24, 41 | new |
| F-AMO-037 | Same-day cover assignment | AMO action "Assign cover for today" (route, user in zone, up to 7 days); bumps scope_version so the substitute's next delta carries the route; online-only or queued as an outbox event; offered inline on the Not Logged In list; interim web action in F-ADM-062. | QUE | - | - | - | app.route_assignment(kind=cover) | route, app_user | F-API-043 | cfg.route.cover_max_days, cfg.route.cover_requires_tso_approval | G-field-06, G-feat-07; D-85; Q44 | 3a | T-3-20..24, 41 | new |
| F-AMO-038 | Exceptions screen | Today's fraud and anomaly signals for the zone (geo, mock, teleport, closed-streak) with review, dismiss and confirm offline as idempotent events; the SR never sees these (D-123). A dismissal is re-sampled to the TSO. | QUE | - | - | - | app.risk_review | risk_signal (bundle, at most 200 rows) | F-API-034 | cfg.sec.fraud.* | D-109, D-123 | 3a | T-3-20..24, 41 | new |
| F-AMO-042 | Distribution-house confirmation proxy | The AMO confirms issue, return and cash of the SRs on the keeper's behalf (confirmed_by) when the business gives the keeper no login (Q41); unconfirmed rows are marked on the settlement view. | QUE | stock, receipt | - | Y | app.stock_movement.confirmed_by, app.cash_handover.counted_by | L:stock_movement | F-API-006 | cfg.stock.* | G-field-01; D-324, D-325; Q41 | 3a | T-3-20..24, 41 | new |
| F-AMO-045 | Zone day exception | The AMO declares rain, hartal or market closed for several routes and a date range with a reason; the TSO approves in the app; approved exceptions leave the denominators. | QUE | - | - | - | app.day_exception | route, zone | F-API-047, F-API-044 | cfg.day.exception_reasons, cfg.day.exception_requires_approval | G-field-02; D-39 | 3a | T-3-20..24, 41 | new |
| F-AMO-047 | Bulk mark absent | The AMO marks several routes of the zone absent for a date range in one action (up to `cfg.day.bulk_absent_max_routes`); each becomes a `day_exception` per route per day for the TSO to approve; a route with no SR for `cfg.route.vacancy_alert_days` planned days enters the vacancy list (D-551). | QUE | - | - | - | day_exception | route_day | F-API-076 | cfg.day.bulk_absent_max_routes, cfg.route.vacancy_alert_days | G-qa-83; D-337, D-551 | 3a | T-3-20..24 | new |
| F-AMO-048 | Approve consistent location proposals | The AMO approves all location proposals that agree within `cfg.outlet.bulk_approve_consistent_m` with at least `cfg.outlet.bulk_approve_min_evidence` independent visits; never a move above the alert distance, never a remote verification; one open request per outlet (D-545). | ONL | - | - | - | outlet_change_request | outlet_change_evidence | F-API-074 | cfg.outlet.bulk_approve_consistent_m, cfg.outlet.bulk_approve_min_evidence | G-qa-76; D-545 | 3a | T-3-11, T-4-152 | new |
| F-AMO-049 | AMO stock "correct total" | The same rule as F-SR-081 on the AMO stock screen: loaded total read-only, increment-only Save, double-tap guard, signed `adjustment` for a correction (D-580). | OFF | - | - | - | app.stock_movement (kind adjustment) | L:stock_movement | F-API-006 | cfg.stock.correct_total_enabled, cfg.stock.resave_guard_window_min | G-qa-116; D-580; MQ-68 | 3a | T-3-160 | new |

Proved by: T-3-20..24 and T-3-41 (AMO flows, zone bundle in airplane mode, verification loop), T-2-10..19 (geo), T-5-10 (Astha), T-4-41 (reads shared with the web).

## 5 TSO app (F-TSO)

29 rows. The TSO app is a read snapshot plus a few queued control actions; Final Submit and the maps are online-only (D-82). The default locale is English with a switch (D-181). Evidence pages are those of the TSO manual (22 pages, 24 screens). The TSO also operates web pages (QC, Sales Plan, Web Entry, outlet approval, OTP, Set Target, wholesale marking, gift choice): F-TSO-019 to F-TSO-023 and F-TSO-025 and F-TSO-020 are those actor features, and s6 and s7 hold the pages.

### 5.1 Session, home and dashboard

| ID | Name | What it does, rules now stated, evidence | Off | Prt | Pic | Geo | Writes | Reads | API | cfg keys | Refs (G, D, Q) | Ph | Gates | St |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-TSO-001 | Login (dark theme) and logout | Dark navy theme; username and password; the "Log Out!" dialog wipes all local data only on a fully reconciled device and is refused with the pending count otherwise (DELIBERATE CHANGE). Evid: TSO p4, p21. | ONF | - | - | - | L:* (wipe) | - | F-API-001, F-API-031 | cfg.app.logout_block_when_pending | G-man-024, G-feat-41; D-69, D-174 | 3b | T-3-25..28, 42 | changed |
| F-TSO-027 | TSO login snapshot and read model | TSO is a read-snapshot plus queued-writes client: GET /app/home?role=tso plus zone, route and outlet pickers for the territory (about 2,500 outlets, under 1 MB gzipped), delta-refreshed; every aggregate screen shows "as of hh:mm"; no polling timer. | CAC | - | - | - | L:* | /app/home, pickers | F-API-018 | cfg.bundle.stale_max_days | G-man-069; D-72, D-82 | 3b | T-3-25..28, 42 | new |
| F-TSO-028 | TSO app chrome (parity) | Header hamburger top-left, "Good day, <name>", logout icon top-right; blue Home FAB bottom-centre on Dashboard, Select Outlets, My Teams or Outlets, Visit Query and Feedback (not on Final Submit selection); round back button with a green title; purple-outlined dropdowns; full-width green primary button, grey when disabled; seven drawer entries with subtitles (View Dashboard, Manage your leaves, Submit Sales Data, Check your team and outlet, Create and manage plans, Check targets, Manage your feedbacks); ISO dates in lists, "MMMM D, YYYY" in pickers. Evid: TSO p8, M-23. | OFF | - | - | - | - | - | - | cfg.app.drawer_items.tso | G-man-084; D-233 | 3b | T-3-25..28, 42 | parity |
| F-TSO-024 | TSO Settings | The current TSO drawer has no Settings; one is added as an IMPROVEMENT (language, version and update check, send data file, change password); no mobile app changes the password today (web only). | ONF | - | - | - | L:prefs | - | F-API-004, F-API-029 | cfg.app.drawer_items.tso, cfg.app.default_locale | G-man-027; D-181, D-204 | 3b | T-3-25..28, 42 | new |
| F-TSO-002 | Dashboard: sales by category and achievement | Four tiles per territory for the day: Sales (Cigarette) in sticks, Sales (Bidi), Sales (Lighter in Box), Sales (Match in Dozen), one decimal, ring = achievement % against the day target (monthly / days in month, same switch as till-date); tile list, unit label and decimals are config so Digonto lines can be added. Evid: TSO p4-5. | CAC | - | - | - | - | dw.agg_daily_route_sku, target | F-API-016, F-API-018 | cfg.tso.dashboard_tiles, cfg.kpi.daily_target_basis, cfg.tso.product_scope | G-man-073, G-man-076; D-49; Q8, Q14 | 3b | T-3-25..28, 42 | parity |
| F-TSO-003 | Dashboard: By Channel STD | Donut of STD by channel (GT, DCC, Astha, RCC, MT, HoReCa); metric is STD volume per the screen title (the web tile is calls-based); the ▸ glyph's drill-down is unknown. Evid: TSO p5. | CAC | - | - | - | - | agg by channel | F-API-016 | cfg.dashboard.channels | G-man-076; D-233 | 3b | T-3-25..28, 42 | parity |
| F-TSO-004 | Dashboard: CPR card | Target Outlet, Successful Calls, Strike Rate % (2/44 = 4.5%); target outlets per the day plan. Evid: TSO p5. | CAC | - | - | - | - | dw.agg_daily_route | F-API-016 | cfg.kpi.target_route_kinds | D-46, D-57 | 3b | T-3-25..28, 42 | parity |
| F-TSO-005 | Dashboard: By Segment Value Contribution | Value versus volume per segment; the empty chart in the manual means the populated shape is unknown; value uses net_mtk. | CAC | - | - | - | - | dw.agg_daily_route_sku | F-API-016 | cfg.tso.dashboard_cards | G-man-076; D-49 | 3b | T-3-25..28, 42 | parity |
| F-TSO-006 | Dashboard: By Brand Call/Memo Ratio | BSR per brand (memos containing the brand / total active memos; brand reach as secondary); whether "Maxim - Platinum Series" is a brand, variant or series level is unknown. | CAC | - | - | - | - | facts | F-API-016 | cfg.tso.dashboard_cards | G-man-076; D-47; Q9 | 3b | T-3-25..28, 42 | parity |
| F-TSO-007 | Dashboard: Login and Bikroy Joma status with drill-downs | Login Status (logins / target routes: 1 of 4 = 25%) and Bikroy Joma Status (submitted / logged in), captions Target Route, Total Login, Login Count, Total Bikroy Joma; lists Not Logged In (no bundle that day) and Not Uploaded (no sales submit that day regardless of login: a superset); rows are (assigned user, planned route) pairs, cards count routes; Dep Name is the zone name; route label as printed (name plus kind, no separator). Evid: TSO p6-7. | CAC | - | - | - | - | dw.agg_daily_route, app.route_day, route_assignment | F-API-016 | cfg.kpi.submit_pct_denominator, cfg.kpi.target_route_kinds | G-man-066, G-man-072, G-feat-09; D-29, D-44, D-45, D-236 | 3b | T-3-25..28, 42 | parity |

### 5.2 Leave

| ID | Name | What it does, rules now stated, evidence | Off | Prt | Pic | Geo | Writes | Reads | API | cfg keys | Refs (G, D, Q) | Ph | Gates | St |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-TSO-008 | Leave: list | Cards with from to dates, Reason, "n Day(s)" and a status badge "<approver role> approval pending" (DMO for a TSO); no type or balance on the card; imported rows are never recomputed. Evid: TSO p10. | CAC | - | - | - | - | app.leave_application | F-API-022 | cfg.leave.types, cfg.leave.approver_role_by_applicant_role | G-man-074; D-176, D-234 | 3b | T-3-26, T-3-155, T-3-128 | parity |
| F-TSO-009 | Leave: apply | Leave Type (Casual, Sick, Earn), one Date, typed integer days at least 1, Reason, Apply; to_date derived for new rows; queued with a client uuid and a "not sent yet" badge; no day cap; overlap, past-day and balance checks are config with parity defaults all off. Evid: TSO p11. | QUE | - | - | - | app.leave_application | - | F-API-022 | cfg.leave.allow_past_days, cfg.leave.block_overlap, cfg.leave.balance_enforced, cfg.leave.max_consecutive_days | G-man-074, G-man-075, G-feat-25; D-176, D-197 | 3b | T-3-26, T-3-155, T-3-128 | parity |

### 5.3 Final Submit and day close

| ID | Name | What it does, rules now stated, evidence | Off | Prt | Pic | Geo | Writes | Reads | API | cfg keys | Refs (G, D, Q) | Ph | Gates | St |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-TSO-010 | Final Submit (per zone per day) | Five labelled dropdowns Wing, Division, Territory, House, Zone from server scope only (single options auto-selected); Get Sales Data enabled when Zone chosen and calls the preview endpoint, which fires the Bangla alert "আপনি ইতিমধ্যেই আজকের জন্য 'FINAL SUBMIT' জমা দিয়েছেন!" for a repeat; then Sales Date (server Dhaka date, read-only) and routes with "FF: <name>" or "SR Not Set" (not blocking); an explicit confirm before the irreversible Submit (IMPROVEMENT); success "Final Submit Done Successfully...". Online-only, retry-safe by client_uuid, once per zone and day by primary key; late batches accepted and flagged after_final_submit. Evid: TSO p12-14. | ONL | - | - | - | app.final_submit, app.route_day | dw.agg_daily_route, route_assignment | F-API-009, F-API-039 | cfg.day.final_submit_confirm, cfg.day.final_submit_allow_not_set_routes, cfg.day.final_submit_earliest_time | G-man-071, G-man-077, G-feat-17, G-feat-64; D-55, D-198, D-262 | 3b | T-3-25..28, 42 | parity |
| F-TSO-021 | Final Submit preview and read endpoint | GET /day/final-submit/preview (scope-checked): salesDate, alreadySubmitted, submittedAt, routes with FF name or null; feeds the alert at Get Sales Data; the web page uses the same server rule. | ONL | - | - | - | - | app.final_submit, route_assignment | F-API-039 | - | G-man-070; D-82, D-235 | 3b | T-3-25..28, 42 | parity |
| F-TSO-026 | Final-submit delegation and auto-close | An approved TSO leave may name an acting TSO or the DMO who may final-submit; optional system auto-close at a set time; the Final Submit Log labels the kind (manual, delegated, auto). Off until confirmed. | ONL | - | - | - | app.final_submit.kind | leave_application, user_scope | F-API-009 | cfg.day.final_submit_delegate_roles, cfg.day.final_submit_autoclose_time | G-field-07; D-262; Q45, Q53 | 3b | T-3-25..28, 42 | new |

### 5.4 My Periphery

| ID | Name | What it does, rules now stated, evidence | Off | Prt | Pic | Geo | Writes | Reads | API | cfg keys | Refs (G, D, Q) | Ph | Gates | St |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-TSO-011 | My Periphery: My Team map | Zone dropdown and a map of the SRs' last synced fixes with age label and source; list fallback offline; markers grouped by route with a legend (the screen title is "My Teams"). The 3D buildings of the current map are not carried over. Evid: TSO p15. | HYB | - | - | - | - | user_last_fix | F-API-023 | cfg.tso.team_location_max_age_min, cfg.map.3d_enabled | G-man-058, G-man-078; D-08, D-170 | 3b | T-3-25..28, 42 | changed |
| F-TSO-012 | My Periphery: Retailer radius map | Zone and radius (50, 100, 300 m; unit shown, default first option) with a single fix as centre (zone centroid fallback and a location-off message); markers clustered and capped; phone only for roles allowed PII. Evid: TSO p15. | HYB | - | - | - | fix | - | F-API-019 | cfg.tso.periphery_radius_options_m, cfg.tso.periphery_default_radius_m, cfg.tso.periphery_max_markers | G-man-078; D-08 | 3b | T-3-25..28, 42 | parity |

### 5.5 My Call

| ID | Name | What it does, rules now stated, evidence | Off | Prt | Pic | Geo | Writes | Reads | API | cfg keys | Refs (G, D, Q) | Ph | Gates | St |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-TSO-013 | My Call: Set Plan | Date, zone, route then Show Outlet; multi-select outlet cards (name, code and the cluster line, owner, phone) with select-all and search as improvements; no cap by default; a repeat Set Plan for the same date and route is a union by outlet, idempotent; date today or later (assumption); no un-planning. Evid: TSO p18. | QUE | - | - | - | app.visit_plan, app.visit_plan_outlet | outlet | F-API-020 | cfg.tso.visit_plan_max_outlets, cfg.tso.plan_backdate_days, cfg.tso.plan_max_days_ahead | G-man-079; D-175, D-197 | 3b | T-3-25..28, 42 | parity |
| F-TSO-014 | My Call: My Visit Plan | Date, zone, route then tabs Pending (default) and Completed; an outlet becomes Completed when its Visit Query is submitted (delegate Yes or No); a Completed card opens a read-only view of the answers (improvement); no edit or delete of a plan; the day's plan is cached for offline. Evid: TSO p16-17. | CAC | - | - | - | - | app.visit_plan_outlet | F-API-020 | - | G-man-080, G-feat-49; D-327 | 3b | T-3-25..28, 42 | parity |
| F-TSO-015 | Visit Query (retailer questionnaire) | Two free-text questions as printed ("SR আপনার দোকান নিয়মিত ভিজিট করে?", "SR নিয়মিত মেমো প্রিন্ট করে দেয়?") plus a built-in "Do you want to delegate the task?" radio defaulting to No; answers optional (500 characters, ASSUMPTION); Submit saves and marks the outlet Completed; Yes opens Assign Task; no GPS or photo (a TSO visit is not geo-validated). Evid: TSO p17. | QUE | - | - | - | app.call_assessment(kind=retailer_questionnaire), visit_plan_outlet.status | - | F-API-006 | cfg.survey.tso_visit_query_questions, cfg.tso.visit_query_answer_max_chars | G-man-081, G-feat-49; D-199 | 3b | T-3-25..28, 42 | parity |
| F-TSO-016 | Assign Task from visit | Read-only outlet, cluster and route; Task Type required (only Irregular Visit is evidenced), date, optional Comment; assignee is the SR active on that route on the due date; blocked with a new message when the route shows "SR Not Set" or is an AMO route. Evid: TSO p17. | QUE | - | - | - | app.task | route_assignment | F-API-006, F-API-026 | cfg.tso.assignable_task_types, cfg.task.types | G-man-082; D-328 | 3b | T-3-26, T-3-155, T-3-128 | parity |

### 5.6 Target Status and Feedback

| ID | Name | What it does, rules now stated, evidence | Off | Prt | Pic | Geo | Writes | Reads | API | cfg keys | Refs (G, D, Q) | Ph | Gates | St |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-TSO-017 | Target Status | Tabs Monthly Target and Till Date Target; Territory card and Zone cards with Cigarette, Bidi, Lighter, Match rows "achieved/target" and % (bar capped at 100); Details to an item table at variant level (Item, Target, Achievement, Remaining, %; uncapped, 2 decimals, e.g. 1271.19); till-date = ceil(item target x 26/30) summed (3944 gives 3420). Evid: TSO p19. | CAC | - | - | - | - | dw.agg_month_zone_product, target | F-API-012, F-API-021a | cfg.kpi.tilldate_basis.tso_target_status, cfg.kpi.tilldate_rounding.tso_target_status, cfg.kpi.card_pct_cap | G-man-060, G-man-067, G-man-068; D-31, D-50, D-51, D-195 | 3b | T-3-25..28, 42 | parity |
| F-TSO-018 | My Feedback | Feedback Category (only "Suggestion" is evidenced; list is config), Title, Descriptions, one image from the Android photo picker (compressed, uploaded through the media queue), Save queued offline with success and failure toasts; a simple own-feedback list behind the drawer chevron (F-TSO-029). Evid: TSO p20. | QUE | - | Y | - | app.feedback | - | F-API-032 | cfg.feedback.categories, cfg.feedback.max_images | G-man-083, G-feat-48; D-197 | 3b | T-3-25..28, 42 | parity |
| F-TSO-029 | My Feedback list | Own submitted feedback (title, category, date, status) behind the drawer chevron; the manual shows no sub-screen, so this is built and confirmed on a screenshot. | CAC | - | - | - | - | app.feedback | F-API-032 | cfg.feedback.categories | G-man-083 | 3b | T-3-25..28, 42 | new |

### 5.7 Actor features and web counterparts

| ID | Name | What it does, rules now stated, evidence | Off | Prt | Pic | Geo | Writes | Reads | API | cfg keys | Refs (G, D, Q) | Ph | Gates | St |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-TSO-019 | Device OTP issuance (as actor) | The TSO has no app screen: the 4-digit OTP created at the SR's login attempt is read on the web SR Device OTP panel (F-TSO-022) and told to the SR; launch-day volume is handled by pre-bind day and bulk pre-issue (F-ADM-069). | ONL | - | - | - | app.device_otp | app_user in scope | F-API-003, F-API-036 | cfg.auth.otp_ttl_min | G-feat-11, G-man-021; D-103, D-126 | 0c | T-0-70..76 | changed |
| F-TSO-022 | SR Device OTP panel (web, view only) | Wing to Zone filters (All Selected (n)), View, search and refresh; table superset of the 8 SR-manual columns (Sr No., Field Force ID, Name, Username, Zone ID, Zone, Create Time, OTP) and the 5 web columns; every SR in the selected zone is listed (an open-request filter is an improvement); "No Data" empty state; OTP visible only to the TSO of that scope. Evid: SR p5-6, Web p46. | ONL | - | - | - | - | app.device_otp | F-API-036 | cfg.auth.otp_visible_roles, cfg.auth.otp_ttl_min | G-man-021; D-103, D-164, D-165 | 0c | T-0-70..76 | changed |
| F-TSO-023 | Temporary password reset and unlock | The TSO (or support) issues a temporary password (24 h, forces change) and unlocks a locked account; no self-service reset on the web today (PARITY); the SR is notified of a reset; a reset plus OTP issue for the same user by the same actor within 24 h holds the bind for a second person (D-112). | ONL | - | - | - | app_user.must_change, app.security_event | app_user in scope | F-API-004 | cfg.auth.lockout_attempts | G-feat-34, G-man-091; D-102, D-112 | 2e | T-2-31..35, 47..49, 53..57 | new |
| F-TSO-025 | Radius edit or propose | TSO changes the geofence radius for own territory within bounds or proposes a change request (default propose); any increase above 150 m is a C3 change. | ONL | - | - | - | cfg.config_value | cfg.config_value | F-API-037 | cfg.geo.tso_radius_mode, cfg.geo.radius_increase_escalation_m | G-cfg-03; D-93, D-94; Q24 | 2d | T-2-10..19, 60..69 | new |
| F-TSO-020 | Astha gift choice per outlet | Web Astha Gift Choice Panel: route-scoped Astha outlets with a per-row gift dropdown (Ceiling Fan (56 inch), 24 pcs Dinner Set, 27 pcs Dinner Set), save and lock rules unknown (default explicit Save, lock when the SR photo exists); feeds F-SR-044 through the bundle. Evid: Web p47. | ONL | - | - | - | app.gift_assignment | outlet, gift_catalog | F-API-021b | cfg.astha.gift_choice_roles, cfg.astha.gift_choice_lock, cfg.astha.gift_catalog | G-feat-21, G-man-047; D-192 | 5a | T-5-10, 41 | parity |
| F-TSO-030 | Confirm or perform a submit void | The TSO voids a route-day's Sales Submit in the zone or confirms an L1 proposal inside `cfg.day.submit_undo_window_min`; reason required; audited (D-539, D-540). | ONL | - | - | - | route_day, app.submit_void_event | route_day | F-API-069 | cfg.day.submit_undo_window_min | G-qa-70, G-qa-71; D-539, D-540 | 3b | T-3-150 | new |
| F-TSO-031 | Approve consistent location proposals | The TSO approves the proposals the AMOs escalated after 72 h and the consistent groups in one action (D-545). | ONL | - | - | - | outlet_change_request | outlet_change_evidence | F-API-074 | cfg.sla.location_request_escalate_h, cfg.outlet.bulk_approve_consistent_m | G-qa-76; D-545 | 3b | T-4-152 | new |

Proved by: T-3-25..28 and T-3-42 (TSO flows, final-submit loop, once per zone per day), T-0-70..76 (OTP and scope), T-4-41 (shared aggregates).

## 6 Web (F-WEB)

67 rows. Every page reads aggregates in the analytics layer and scope from the token; reports render on screen first and Excel, PDF and print are formatters over the same query (F-WEB-040, F-SYS-064). The TSO web portal is also a back-office write tool: the pages that write are F-WEB-048, F-WEB-050 to F-WEB-052, F-WEB-060, F-ADM-006, F-ADM-014, F-ADM-056 and F-TSO-020 and F-TSO-022; their rules are in s7 and doc 19 s8.

### 6.1 Page parity map (37 spec pages, 41 manual pages)

The manual shows 15 menu items and 41 pages under the TSO login (screens S-03 to S-46 less two dialogs (S-40, S-42) and the Sales Plan edit state (S-10)). docs/09 lists 37 pages in 13 menus. They overlap in 24 pages; 17 manual pages are in no spec list; 13 spec pages have no evidence in the TSO manual (other roles' menus or unbuilt: the sidebar is clipped in the screenshots, so no role or build explanation is accepted, D-185, D-243). The union is 54 page-level surfaces; the menu is data per role (F-ADM-064, doc 19 s5.3).

Spec pages (docs/09) and where each is built:

| Spec page (docs/09) | Menu group | Feature | In the TSO manual |
| --- | --- | --- | --- |
| Dashboard | Dashboard | F-WEB-001 | yes |
| Browse Retailer | Retailer | F-WEB-002 | yes |
| Category | Products | F-WEB-004 | yes |
| Segment | Products | F-WEB-005 | yes |
| Brand | Products | F-WEB-006 | yes |
| Variant | Products | F-WEB-007 | yes |
| SKU | Products | F-WEB-008 | yes |
| Product Tree | Products | F-WEB-009 | yes |
| Browse Routes | Route Planning | F-WEB-010 | yes |
| Task Planner | Reports | F-WEB-011 | spec only |
| By-Route Geo Capture | Reports | F-WEB-012 | spec only |
| STD Memo Report | Reports | F-WEB-013 | yes |
| SR Efficiency | Reports | F-WEB-014 | yes |
| Route-wise STD | Reports | F-WEB-015 | yes |
| Data Entry Log | Reports | F-WEB-016 | yes |
| Final Submit Log | Reports | F-WEB-017 | yes |
| CPR and BSR | Reports | F-WEB-018 | yes |
| By Outlet Report | Reports | F-WEB-019 | yes |
| Astha Report | Reports | F-WEB-020 | yes |
| GIGO (attendance) | Reports | F-WEB-021 | yes |
| Campaign Gift Redemption | Reports | F-WEB-022 | spec only |
| Discount Report | Reports | F-WEB-023 | spec only |
| By Outlet By Day | Reports | F-WEB-024 | spec only |
| Online/Offline Sales | Reports | F-WEB-025 | spec only |
| Free Sample | Reports | F-WEB-026 | spec only |
| TSO Top Sheet Performance | Reports | F-WEB-027 | spec only |
| TSO Daily Tracking Dashboard | Reports | F-WEB-028 | spec only |
| Target Allocation Report | Target | F-WEB-029 | yes |
| Target Revise List | Target | F-WEB-030 | yes |
| SR Outlets Reports | Outlet | F-WEB-031 | yes |
| Outlet Approval Panel | Outlet | F-WEB-032 | yes |
| Credentials | Credentials | F-WEB-033 | yes |
| Astha Gift Choice Report | Astha Gift Panel | F-WEB-034 | yes |
| Tutorial | Tutorial | F-WEB-035 | spec only |
| Performance Leaderboard | Performance Leaderboard | F-WEB-036 | spec only |
| Superstar Campaign Report | Superstar Program | F-WEB-037 | spec only |
| Daily Tracking Dashboard | Daily Tracking Dashboard | F-WEB-038 | spec only |

Manual pages that are in no spec list:

| Manual page | Feature |
| --- | --- |
| QC Entry | F-WEB-052 |
| QC Report (Market and Warehouse) | F-WEB-061 |
| Warehouse QC Entry | F-WEB-060 |
| Route Wise QC Report | F-WEB-062 |
| Sales Plan | F-ADM-006 |
| Web Entry | F-WEB-050 |
| Final Submit | F-WEB-051 |
| Astha Web Entry | F-WEB-048 |
| DSS Report | F-WEB-055 |
| DS-RRS Report | F-WEB-053 |
| Route Wise Memo Report | F-WEB-056 |
| Loyalty Program: Diamond League Report | F-WEB-049 |
| Set Target | F-ADM-014 |
| AMO Call Report | F-WEB-054 |
| Retailer Wholesale Outlet | F-ADM-056 |
| SR Device OTP | F-TSO-022 |
| Astha Gift Choice Panel | F-TSO-020 |

### 6.2 Feature rows

Standard behaviours on every data page are F-WEB-040 (export), F-WEB-041 (scope filter) and F-WEB-042 (PII). Offline class is ONL for every row; report parameters are one ReportQuery object (doc 16 s9).

| ID | Name | Roles | What it does, rules now stated, evidence | Prt | Pic | Writes | Reads | API | cfg keys | Refs (G, D, Q) | Ph | Gates | St |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-WEB-001 | Dashboard (home) | TSO, DMO, WM, top, admin | Loads for the user's scope for today without a button press and offers a date-range Filter (orange, top right); tiles: Sales (Cigarette), Sales (Bidi), Sales (Lighter in Pcs), Sales (Match in Dozen), Live Strike Rate (1/43 = 2.3%), By Channel Successful Call, Final Submit Status (Total Zone, Total Service Zone, Remaining, and the Final Submit % ring of the web manual S-03 and R-019: final-submitted zones / Total Service Zone, K-15), Login/Submit Status, By Channel STD, STD/Value/Memo breakdown, By Segment Contribution, Sales Trend, Geo Fencing, FF Geo Location; "i" tooltips fed by the KPI registry; Value needs net_mtk in the aggregate; Total Service Zone is DERIVED as the zones with at least one target (planned sr-kind) route that day, which is also the K-15 denominator; there is no manual zone flag (D-536, G-qa-65). EVERY tile defaults to today, sales tiles included (Final Submit is a late-evening act, so "last final-submitted" would show yesterday all through the selling day; D-544, G-qa-75); every tile shows its business date and an as-of stamp, a reason chip from the enum replica_lag, agg_stale, non_working_day, no_data_yet, degraded_mode explains any figure that is not live today, and a non-working-day banner is shown (F-WEB-068). Evid: Web p3. | - | - | - | dw.agg_daily_route, agg_daily_outlet, user_last_fix | F-API-014 | cfg.dashboard.tiles, cfg.dashboard.unit_labels, cfg.dashboard.channels, cfg.dashboard.tile_info | G-man-096, G-field-19; D-188, D-45 | 4a | T-4-41, 51..52 | changed |
| F-WEB-002 | Browse Retailer (list) | TSO+, admin | Get Data and Get Excel over five All Selected (n) geo filters; columns Zone Code, Zone Name, Outlet Name, Owner Name, Contact Number, Route Code, Route, Outlet Code, Cluster Type, Cluster Name, Address (per-column search); PII columns follow cfg.pii.field_roles; every Excel export is logged. Evid: Web p4. | - | - | - | app.outlet | F-API-010, F-API-019 | cfg.pii.field_roles, cfg.pii.mask_style | G-man-039, G-feat-59; D-108, D-207 | 4b | T-4-42..46 | parity |
| F-WEB-003 | Retailer detail and edit | TSO+, admin | Sections basic info, address, business, additional detail; edit audited; field lists unknown (G-feat-54); whether web edit bypasses AMO verification is unknown (default: it does not for location, name and owner). | view | Y | app.outlet (audited) | app.outlet, outlet_photo, visit history | F-API-019, F-API-035b | cfg.outlet.verify_roles | G-feat-54, G-data-29 | 4c | T-4-125, T-4-14 | parity |
| F-WEB-004 | Products: Category | all web | Read-only: Category Name, Status (Active badge), Sort. Evid: Web p12. | - | - | - | product_category | F-API-035c | - | G-man-038 | 4b | T-4-42..46 | parity |
| F-WEB-005 | Products: Segment | all web | Read-only: Category, Segment, Status, Sort (segment sort starts at 3). Evid: Web p13. | - | - | - | product_segment | F-API-035c | - | G-man-038 | 4b | T-4-42..46 | parity |
| F-WEB-006 | Products: Brand | all web | Read-only 17 brands: Category, Segment, Brand, Status, Sort. Evid: Web p14. | - | - | - | product_brand | F-API-035c | - | G-man-038 | 4b | T-4-42..46 | parity |
| F-WEB-007 | Products: Variant | all web | Read-only: Category, Segment, Brand, Variant, Status, Sort (duplicate sort values exist: sort semantics defined). Evid: Web p15. | - | - | - | product_variant | F-API-035c | - | G-man-038; D-31 | 4b | T-4-42..46 | parity |
| F-WEB-008 | Products: SKU | all web | Export Excel; Product, SKU, Short Name, Pack Size, Pack Type, Sort, Outlet, C&c and Distributor price shown to three decimals (7.935) and only the price types the role may see. Evid: Web p16. | - | - | - | sku, sku_price | F-API-035c | cfg.pii.field_roles | G-man-038; D-15 | 4b | T-4-42..46 | parity |
| F-WEB-009 | Products: Product Tree | all web | Tree All Products to Category (Expand) and down to SKU. Evid: Web p17. | - | - | - | product tables | F-API-035c | - | - | 4b | T-4-42..46 | parity |
| F-WEB-010 | Route Planning: Browse Routes | TSO+, admin | Get Data by geo filter; routes with the AMO and the SR assigned (AMO routes carry no SR: "SR Not Set" is normal); route kind sr or amo; name and visit-day label stored separately. The result table is not shown in the manual. Evid: Web p18. | - | - | - | route, route_assignment | F-API-011, F-API-020b | cfg.kpi.target_route_kinds | G-man-100, G-feat-07; D-29, D-242 | 4b | T-4-42..46 | parity |
| F-WEB-011 | Report: Task Planner | TSO+ | Tasks assigned and resolved by scope and date. No manual evidence (spec only). | - | - | - | app.task | F-API-017 | cfg.report.lists.* | G-feat-53 | 4b | T-4-42..46 | parity |
| F-WEB-012 | Report: By-Route Geo Capture | TSO+ | Outlets per route with and without coordinates, including location_confirmed false placeholders. Spec only. | view | - | - | app.outlet, outlet_photo | F-API-017 | cfg.report.lists.* | G-feat-53; D-253 | 4b | T-4-42..46 | parity |
| F-WEB-013 | Report: STD Memo Report | TSO+ | Date range, Location (Wing level), Date Grouping (Total), Category, Product Type, Active Status, Select Products; STD and Memo together or separately; Get Data and Get Excel. Evid: Web p22. | - | - | - | dw.agg_daily_route_sku | F-API-017 | cfg.report.std_criteria_divisor, cfg.report.lists.* | G-man-095, G-feat-53; D-49 | 4b | T-4-42..46 | parity |
| F-WEB-014 | Report: SR Efficiency | TSO+ | Date range; per SR target outlets, visited, successful, CPR, STD, memos, geo %; user-level KPI on coalesce(acting_for_user_id, user_id); metrics undefined in the manual (Get Excel only). Evid: Web p23. | - | - | - | dw.agg_daily_user | F-API-017 | cfg.report.lists.* | G-feat-53; D-26 | 4b | T-4-42..46 | parity |
| F-WEB-015 | Report: Route-wise STD | TSO+ | Wing_Code to Route columns then one column per SKU (Product Type SKU, 38 selected); in-app heading "Route Wise Live STD Report" is a name only, the sample is a past range. Evid: Web p26. | - | - | - | dw.agg_daily_route_sku | F-API-017 | cfg.report.lists.* | G-man-094; D-241 | 4b | T-4-42..46 | parity |
| F-WEB-016 | Report: Data Entry Log | TSO+, admin | "Download Upload Log": per route DOWNLOAD and UPLOAD groups with MIN (first), MAX (last) and Count in Dhaka time. Evid: Web p27. | - | - | - | app.route_log | F-API-017 | - | G-man-097; D-240 | 4b | T-4-42..46 | parity |
| F-WEB-017 | Report: Final Submit Log | TSO+, admin | Per zone Submit Status (Done, Not Done), MAX_TIME, MIN_TIME, COUNT; the three numbers are first and last route sales-submit time and routes submitted while the zone is Not Done; labelled "at submit"; kind manual, delegated or auto. Evid: Web p28. | - | - | - | app.final_submit, app.route_day | F-API-017 | - | G-man-097; D-55 | 4b | T-4-42..46 | parity |
| F-WEB-018 | Report: CPR and BSR | TSO+ | Route Wise BSR & CPR (three display names stored as aliases); both BSR denominators shown. Evid: Web p30. | - | - | - | dw.agg_daily_route, fact_memo | F-API-017 | cfg.report.lists.* | G-man-094; D-47, D-241; Q9 | 4b | T-4-42..46 | parity |
| F-WEB-019 | Report: By Outlet Report | TSO+ | Filters Category, Product Type, Active Status, Sub Channel (9), Report Type, date range; button is "Download Report" in the app; per outlet visits, memos, STD, dues; PII-gated. Evid: Web p31. | - | - | - | dw.agg_daily_outlet | F-API-017 | cfg.report.lists.* | G-feat-53 | 4b | T-4-42..46 | parity |
| F-WEB-020 | Report: Astha Report | TSO+ | Year, Quarter (Q-<n> (<Mon>-<Mon>)), Month (multi); per outlet per brand target, achievement, remaining, % and memo target; targets at or above 0. Evid: Web p32. | - | - | - | program_outlet_target, dw facts | F-API-017 | cfg.astha.* | G-man-045 | 5a | T-5-10, 41 | parity |
| F-WEB-021 | Report: GIGO (attendance) | TSO+ | Check-in and check-out times and locations per user and day (single date): coordinates, accuracy and a `locality_hint` derived by the worker from the stored fix (nearest cluster name within 500 m, else the zone name); no address is stored and no geocoder is called (D-538, G-qa-68). "GIGO" is not expanded in the manual. Evid: Web p33. | - | - | - | app.attendance_event | F-API-017 | - | G-man-104 | 4b | T-4-42..46 | parity |
| F-WEB-022 | Report: Campaign Gift Redemption | TSO+ | Diamond League redemptions, points and photo-verified flag. Spec only. | view | - | - | redemption, gift_photo | F-API-017 | cfg.loyalty.* | G-feat-20 | 5a | T-5-10, 41 | parity |
| F-WEB-023 | Report: Discount Report | TSO+ | Discounts by promotion group (about 22) by scope and date: offer discount and DRP/slide discount separately; offer definitions are the Q13 catalogue. Spec only. | - | - | - | memo_line.offer_id, promotion | F-API-017 | cfg.promo.rules | G-feat-13 | 4b | T-4-42..46 | parity |
| F-WEB-024 | Report: By Outlet By Day | TSO+ | Outlet x day matrix of visited, sold and STD. Spec only. | - | - | - | dw.agg_daily_outlet | F-API-017 | cfg.report.lists.* | G-feat-53 | 4b | T-4-42..46 | parity |
| F-WEB-025 | Report: Online/Offline Sales | TSO+ | Memos captured with and without connectivity, using captured_online on the visit; the definition of online is unknown. Spec only. | - | - | - | app.visit.captured_online | F-API-017 | - | G-feat-58 | 4b | T-4-42..46 | parity |
| F-WEB-026 | Report: Free Sample | TSO+ | Free samples by SKU by route from free-sample memo lines. Spec only. | - | - | - | app.memo_line(is_free) | F-API-017 | - | G-feat-23 | 5b | T-5-42 | parity |
| F-WEB-027 | Report: TSO Top Sheet Performance | DMO+ | Territory summary: STD versus target by category, CPR, login and submit, geo %. Spec only. | - | - | - | territory rollups | F-API-017 | cfg.kpi.bands | G-feat-26 | 4b | T-4-42..46 | parity |
| F-WEB-028 | Report: TSO Daily Tracking Dashboard | DMO+ | Routes bucketed 100, 90 to 100, 80 to 90, below 80 percent of sales and memo targets (same engine as F-WEB-038). Spec only. | - | - | - | dw.agg_daily_route, target | F-API-015 | cfg.kpi.bands | G-feat-26 | 4a | T-4-41, 51..52 | parity |
| F-WEB-029 | Target: Target Allocation Report | TSO+ | Zone All Selected (4) and a single date; Get Excel; monthly targets by route and zone with split; caption says view but only download exists. Evid: Web p36. | - | - | - | target | F-API-021 | cfg.target.split_method | G-feat-24, G-man-068 | 5c | T-5-43 | parity |
| F-WEB-030 | Target: Target Revise List (approval queue) | TSO, DMO, WM, WMO, admin | Target Approval List: month filter, View; Target Name, Product Type (variant), Target Type (stt), Start Date, End Date, Approval Status ("WMO approval pending"), eye icon to view details and a download of the target file; approve or reject by the approver chain (level order is not evidenced); revisions use the same events. Evid: Web p37. | - | - | target_set, target_revision, approval events | target_set | F-API-021 | cfg.target.approval_levels | G-man-068, G-feat-24; D-31, D-179 | 5c | T-5-43 | changed |
| F-WEB-031 | Outlet: SR Outlets Reports | TSO+ | Report Category New Outlets, Close Outlets, Info Changes with a date range; columns Zone, Route, Cluster, Date, Outlet Name, Owner Name, Phone, Latitude, Longitude, Status; sortable. Evid: Web p39. | view | - | - | outlet_change_request | F-API-017 | - | G-man-034 | 4b | T-4-42..46 | parity |
| F-WEB-032 | Outlet Approval Panel | TSO+, admin | One Outlet Type filter (New, Close, Info) with Verify, Reject (reason box, authored) and Approve; Approve confirm "Approve outlet request? / Do you want to approve this request?" with "Yes, approve it" and Cancel; columns include Cluster, Owner, Phone, Latitude, Longitude, Status plus requester, verified-by and request date; approving a closure sets status closed, approving a new outlet assigns the code. Evid: Web p40-41. | view | Y | app.outlet, outlet_change_request | requests, photos | F-API-035b, F-API-055 | cfg.outlet.approve_roles, cfg.outlet.reject_requires_reason | G-man-034, G-feat-55; D-43, D-186 | 3a | T-3-41, T-4-125 | parity |
| F-WEB-033 | Credentials: change password | all web | Password Changing Guideline (12 characters, mixed case and digit, not the last 10, not within 24 h); Old, New, Confirm; errors authored; the example password in the manual is never reproduced (D-244). Evid: Web p45. | - | - | app.password_history | - | F-API-004 | cfg.auth.password_min_len, cfg.auth.password_history_depth, cfg.auth.password_min_age_h | G-man-091; D-102, D-244 | 0c | T-0-70..76 | parity |
| F-WEB-034 | Astha Gift Choice Report | TSO+ | Gift Status Yes or No filter (meaning inferred: gift chosen or not); Get Excel; lists choices and hand-over photo status. Evid: Web p48. | view | - | - | gift_assignment, gift_photo | F-API-017 | cfg.astha.* | G-man-047; D-192 | 5a | T-5-10, 41 | parity |
| F-WEB-035 | Tutorial (manuals) | all web | The four manuals and videos; content managed in F-ADM-026. Spec only. | - | - | - | tutorial_video | F-API-027 | cfg.content.tutorial_videos | G-feat-47 | 4b | T-4-42..46 | parity |
| F-WEB-036 | Performance Leaderboard | DMO+, top | Achievement % by wing to territory by product level; views mtd, target, volume; targets at or above 0. Spec only. | - | - | - | dw.agg_month_zone_product | F-API-013 | cfg.kpi.bands | G-feat-26 | 4a | T-4-41, 51..52 | parity |
| F-WEB-037 | Superstar Campaign Report | TSO+ | Per outlet category, incentive slab, base target, STD and memo targets with achievement and criteria-met flag; enrolment and slab rules unknown. Spec only. | - | - | - | program_enrolment, facts | F-API-017 | cfg.superstar.* | G-feat-22 | 5b | T-5-42 | parity |
| F-WEB-038 | Daily Tracking Dashboard | DMO+, TSO | Route buckets with the exception bucket distinct from not logged in; same-time-yesterday comparator. Spec only. | - | - | - | dw.agg_daily_route, target | F-API-015 | cfg.kpi.bands, cfg.day.take_action_after | G-feat-29, G-field-02 | 4a | T-4-41, 51..52 | parity |
| F-WEB-039 | Daily Tracking "take action" | DMO+, TSO | After 17:00 an action on an under-performing route: a note and a notification to the TSO (default; no reassignment); the manual shows nothing. | - | - | tracking_action | dw.agg_daily_route | F-API-015 | cfg.day.take_action_after | G-feat-29; D-335 | 4a | T-4-41, 51..52 | new |
| F-WEB-040 | Excel export of every report | all web | Same query, xlsx formatting step; one label "Get Excel"; the column sets of the 11 Excel-only reports (the count is 11, not 13) are captured from real .xlsx files; formula sanitiser and watermark. | - | - | app.report_export_log | - | F-API-017 | cfg.ops.report_export_max_rows | G-feat-53, G-man-095; D-191, D-238 | 4b | T-4-42..46 | changed |
| F-WEB-041 | Standard scope filter | all web | Five-level cascade defaulted and bounded by the token; report style All Selected (n), entry style single-select pre-filled; the client never sends scope ids. | - | - | - | geography in scope | all reads | cfg.report.lists.*, cfg.report.page_size_default, cfg.report.page_size_options | G-man-095 | 4a | T-4-41, 51..52 | parity |
| F-WEB-042 | PII gating per role | all web, API | Role by field matrix: SR phone hidden from the AMO (11 asterisks); retailer phone visible on route-scoped field screens; TSO web baseline shows Address, NID, TIN, Trade Licence columns (null where blank or the placeholder 123); NID, TIN, licence envelope-encrypted. | - | - | - | app.outlet | F-API-019 | cfg.pii.field_roles, cfg.pii.mask_style | G-man-039, G-man-062, G-feat-59; D-107, D-108, D-207 | 4c | T-4-14, T-4-70..76 | parity |
| F-WEB-043 | Web login and session | all web | User ID (case-insensitive), password with show/hide, Remember me (default off), no forgot-password link (reset via TSO or support, PARITY); access token in memory and refresh in an HttpOnly cookie; MFA for admin bundles. Evid: Web p2. | - | - | app.audit_log | app_user | F-API-001 | cfg.auth.web_remember_me_days, cfg.auth.web_session_idle_min, cfg.auth.mfa_required_roles | G-man-091; D-114 | 0c | T-0-70..76 | parity |
| F-WEB-044 | Suspicious-location report | AMO (app), TSO+, admin | Visits and routes flagged by F-SYS-013 with a pattern over time and mock counts (improvement). | view | - | - | app.risk_signal, visit | F-API-034 | cfg.sec.fraud.* | D-109 | 4b | T-4-42..46 | new |
| F-WEB-045 | Sync-health dashboard (ops) | admin | As F-SYS-026. | - | - | - | app.route_log, sync_batch | F-API-028 | cfg.sla.* | D-45 | 4a | T-4-41, 51..52 | new |
| F-WEB-046 | Leave approval (DMO) | DMO | Approve or reject TSO leave; the DMO decision reaches the TSO on the next list refresh. | - | - | app.leave_application | app.leave_application | F-API-022 | cfg.leave.approver_role_by_applicant_role | G-feat-25; Q16 | 3b | T-3-155 | new |
| F-WEB-047 | Final-submit status panel | TSO+, admin | Zones submitted versus remaining, today, with a Submit % (of logged-in) and Day-completion % pair; also a per-zone badge in the Final Submit zone picker (improvement). | - | - | - | app.final_submit, zone | F-API-014 | cfg.kpi.submit_pct_denominator | G-man-077; D-45, D-55 | 3b | T-3-43, T-4-41, 51..52 | parity |
| F-WEB-048 | Astha Web Entry | TSO | Outlet x SKU quantity grid for Astha-channel outlets (route, channel Astha, categories, date, Filter); explicit Save (the manual shows none); feeds fact_daily_outlet, per-brand and Astha quarter achievement; overlap with SR-app memos is flagged, never added. Evid: Web p21. | - | - | app.web_entry_outlet_sku | outlet, sku | F-API-051 | cfg.astha.*, cfg.web.entry_enabled_zones | G-man-046; D-40 | 5a | T-5-10, 41 | parity |
| F-WEB-049 | Loyalty Program: Diamond League Report | TSO+ | Outlet-wise points statement (earned, spent, balance, expiring) on screen and in Excel with a date range. Evid: Web p34. | - | - | - | loyalty_ledger, dw.agg_outlet_balance | F-API-017 | cfg.loyalty.* | G-man-048 | 5a | T-5-10, 41 | parity |
| F-WEB-050 | Web Entry (Astha-channel outlets are excluded from the classes: Astha quantities are keyed only in Astha Web Entry, rule R-050, D-537, G-qa-67; the Save button is PARITY (manual S-18); the tool is an AGGREGATE entry and cannot restore a credit memo, which is the paper-memo backfill F-ADM-075) (route-day aggregate entry) | TSO | Date, geo cascade, Select Classifications, Select Route; grid per SKU: Issue, Return, Sale (read-only, Issue - Return inferred), Memos, Successful Call (route level, at most Target Outlet) with class columns per sub-channel summing to Sale; one entry per route-day, re-save replaces with audit; own source table, mutually exclusive with app rows by default (flagged if both). Evid: Web p19. | - | - | app.web_entry_route_day, web_entry_line | sku, route | F-API-050 | cfg.web.entry_classes, cfg.web.entry_validate_calls_le_target, cfg.web.entry_enabled_zones | G-man-085; D-40 | 4c | T-4-61, T-4-123 | parity |
| F-WEB-051 | Web Final Submit | TSO | Banner (Bangla back-date cut-off with "call support"), advisory "Before Final Submit, Please Checkout Sales Data From DSS Report. If Everything OK, Then Proceed to Final Submit", Date of Data Entry picker; table Route / SR Name / Status (exist or --) / Actions with Submit and Delete Section Data; Delete is an audited void with reason and tombstones, only before Final Submit, default scope web-entry rows; same server rule as the app. Evid: Web p20. | - | - | app.final_submit, app.data_void | app.route_day | F-API-009, F-API-048 | cfg.web.delete_section_data_roles, cfg.web.delete_section_data_scope, cfg.web.delete_section_data_before_final_only, cfg.day.final_submit_requires_dss_ack | G-man-086, G-man-087; D-40, D-182, D-184 | 4c | T-4-62, T-4-123, T-4-63 | changed |
| F-WEB-052 | QC Entry (Market QC) | TSO | Geo cascade, route, date, Submit; grid SKU x Manufacturing Fault (5) and Marketing Fault (5) columns, cells default 0; fault labels from the shared qc_fault_type table (11 codes, applies_to app or web); web rows are a separate source, never added to app QC. Evid: Web p5. | - | - | app.qc_summary_entry | sku | F-API-052 | cfg.qc.fault_types, cfg.qc.max_qty_per_cell, cfg.qc.web_reentry_policy, cfg.qc.web_entry_roles | G-man-089; D-34, D-159, D-183 | 4c | T-4-64 | parity |
| F-WEB-053 | DS-RRS Report | TSO | Field Force Type, single date, route, category; Get Data, Get Excel and Print (print view shows discount data before Final Submit); layout and meaning of DS-RRS are unknown: obtain a sample printout (hypothesis: route-day delivery and settlement statement). Evid: Web p25. | Y | - | app.report_export_log (print) | dw.fact_memo, offer discounts | F-API-017, F-API-053 | cfg.report.lists.* | G-man-093; D-157 | 4b | T-4-42..46 | parity |
| F-WEB-054 | AMO Call Report (Supervisory Module) | TSO, DMO+ | Field Force Type AMO, AMO, Report Type Summary, Choose Date, Report By By Date or By Month; per AMO control calls, joint calls, own sales calls, outlets covered; other report types unknown. Evid: Web p38. | - | - | - | call_assessment, distribution_check, AMO visits | F-API-017 | cfg.report.amo_call_types | G-man-098, G-feat-31 | 4b | T-4-42..46 | parity |
| F-WEB-055 | DSS Report (Sales Summary) | TSO | Field Force Type (SR or AMO), date, categories; per route one row with SKU columns for SKUs enabled in the zone and summary rows per sub-channel as of the sale date (Total, DCC, Gold, RCC, Platinum, Diamond, GT, Silver STD); the route name drills to outlet-wise sales; the pre-Final-Submit check: it INCLUDES web-entered data (a Web Entry save for a route-day appears in the totals and the drill-down within 60 s and DSS equals the Web Entry totals for that route-day, D-578), and shows a "data as of" stamp with a staleness alert at `cfg.sla.dss_stale_alert_s`. Evid: Web p24. | - | - | - | dw.agg_daily_route_sku, dim_outlet history, dw.fact_web_entry_line (the web-entry inclusion lands at 4c) | F-API-017 | cfg.report.lists.* | G-man-092; D-184 | 4b | T-4-42..46, T-4-167 | parity |
| F-WEB-056 | Route-wise Memo Report | TSO+ | Same parameter object as Route-wise STD; Get Data, Get Excel; memo counts per route and product. Evid: Web p29. | - | - | - | dw.agg_daily_route_sku | F-API-017 | cfg.report.lists.* | G-man-094 | 4b | T-4-42..46 | parity |
| F-WEB-057 | Exceptions page | TSO+, admin, security | Risk signals with dismissal review and re-sampling; the web twin of F-AMO-038. | - | - | app.risk_review | app.risk_signal | F-API-034 | cfg.sec.fraud.* | D-109 | 2d | T-2-47, T-2-10..19 | new |
| F-WEB-058 | Dues ageing report | TSO+, finance | Outstanding due by memo date bucket (0-7, 8-30, 31-60, 61+ days) per outlet, route and zone with an opening bucket; disputed outlets flagged. | - | - | - | dw.fact_due_ledger | F-API-017 | cfg.kpi.dues_buckets | K-18; D-37 | 4b | T-4-42..46 | new |
| F-WEB-059 | Distribution-house settlement view | TSO+, AMO, DH if Q41 says so | Per SR per day: issued, sold, returned, cash declared and counted, credit created, collections, variance and confirmation state; printable. | Y | - | - | dw.agg_daily_user_sku, collected_mtk, stock_movement | F-API-017 | cfg.stock.* | G-field-01; D-324, D-325; Q41 | 4b | T-4-42..46 | new |
| F-WEB-060 | Warehouse QC Entry | TSO | Zone and single date, no route; same grid as F-WEB-052; the caption says date range but the control is a single date. Evid: Web p7. | - | - | app.qc_summary_entry | sku | F-API-052 | cfg.qc.fault_types, cfg.qc.web_entry_roles | G-man-089; D-34 | 4c | T-4-64, T-4-123 | parity |
| F-WEB-061 | QC Report (Market and Warehouse) | TSO+ | Date range, Get Excel and Download PDF over both QC sources shown separately. Evid: Web p6. | - | - | app.report_export_log | app.qc_summary_entry, app.qc_entry_line | F-API-017 | cfg.qc.fault_types | G-man-089 | 4c | T-4-64, T-4-121 | parity |
| F-WEB-062 | Route-wise QC Report | TSO+ | Date range and QC Type (Market QC or Warehouse QC), Get Excel. Evid: Web p8. | - | - | - | app.qc_summary_entry | F-API-017 | cfg.qc.fault_types | G-man-089 | 4c | T-4-64, T-4-121 | parity |
| F-WEB-063 | Report export log viewer | security, admin | Who exported which report with which filters, rows and whether PII columns were included; watermark sheet reference. | - | - | - | app.report_export_log | F-API-017 | cfg.pii.field_roles | G-man-039; D-108, D-121 | 4c | T-4-14, T-4-125 | new |
| F-WEB-064 | Memo number gap report | sales ops, admin | Per user and day the missing sequence numbers with the sale_abort explanation (improvement; a free lost-row detector). | - | - | - | dw.memo_seq_gap | F-API-017a | - | G-field-14 | 2e | T-2-31..35, 47..49, 53..57 | new |
| F-WEB-065 | Day exceptions report | TSO+ | Approved and pending day exceptions by reason, route and date; Login % and Daily Tracking show them as exception, not not-logged-in. | - | - | - | app.day_exception | F-API-044 | cfg.day.exception_reasons | G-field-02; D-39 | 4a | T-4-41, 51..52 | new |
| F-WEB-066 | Visit outcome report | TSO+ | Closed, owner absent, refused, not reached by outlet and route, with the three-consecutive-closed list. | - | - | - | dw.agg_daily_outlet.outcome_code | F-API-017 | cfg.visit.outcome_codes | G-field-03; D-38 | 4b | T-4-42..46 | new |
| F-WEB-067 | Geofence calibration report | admin, TSO | Distance histogram per geo class and territory from stored fixes, force-sale share, density view; the evidence for the radius values (doc 19 s6 owns the geofence page). | - | - | - | app.geo_fix, app.visit | F-API-037 | cfg.geo.radius_m | G-field-11; D-93, D-254 | 7b | T-7-83..87 | new |
| F-WEB-068 | Date stamp and reason chip on every tile | all web roles | Every dashboard tile shows its business date and an "as of hh:mm" stamp; a tile that is not live today carries a chip from the fixed enum replica_lag, agg_stale, non_working_day, no_data_yet, degraded_mode, readable on the Support desk (P19); `today` is the single default (D-544). | - | - | - | dw.agg_daily_zone, agg_run | F-API-014 | cfg.dashboard.tile_info | G-qa-75; D-544 | 4a | T-4-41 | new |
| F-WEB-069 | Coverage banner and scope toggle | WM, top, DMO, TSO | "X percent of planned routes are on Aron" banner and a switched or all-routes toggle on wing and national dashboards, the leaderboard "partial" rule, and the Apsis-feed label in the all-routes view (D-548). | - | - | - | dw.dim_geo, agg_daily_route | F-API-014 | cfg.kpi.leaderboard_min_switched_pct | G-qa-80; D-548 | 4a | T-4-153 | new |
| F-WEB-070 | Wholesale order (bex) pages | distribution house, sales ops | RESERVED, built but absent from every role's menu in cfg.web.menu_by_role (no new key; the module also ships disabled in the web build) and turned on only if the census shows use, or left off once the sponsor signs the retirement of the function (Parity Exceptions Register PX-01, D-502, D-523): the current build has bex order pages; whether any AKTCL role or distribution house uses them is answered by the usage census (D-503). | - | - | bex_* (to be defined if the census says yes) | bex_* | F-API-035 | cfg.web.menu_by_role | G-qa-27, G-qa-61; D-42, D-502, D-523; Q17 | 6a | T-7-158 | new |
| F-WEB-071 | Back-margin commission letters | sales ops, distribution house | RESERVED, built but absent from every role's menu in cfg.web.menu_by_role until the census shows use, or left off once retirement is signed (PX-02): the current build has back-margin commission letters; use is answered by the census. | - | - | letter_* (to be defined) | letter_* | F-API-035 | cfg.web.menu_by_role | G-qa-27; D-42, D-502; Q17 | 6a | T-7-158 | new |
| F-WEB-072 | Voice recording page | admin | RESERVED, absent from every role's menu in cfg.web.menu_by_role until the census shows use, or left off once retirement is signed (PX-03): docs/13 Q15 names a `voicerecording` page of the web build that is absent from the 54-page union map; whether call audio is recorded and kept is unknown; the census decides; no microphone permission is requested by the new apps (D-115). | - | - | - | - | F-API-035 | cfg.web.menu_by_role | G-qa-27; D-115, D-502; Q15 | 6a | T-7-158 | new |

Proved by: T-4-41 and T-4-51..52 (dashboards from rollups, scope), T-4-42..46 (report set against control totals), T-4-14 and T-4-70..76 (panels, PII budgets), T-5-41..43 (programme reports and targets).

### 6.3 Web Entry and Astha Web Entry: Save and exclusion

The manual shows a Save button on Web Entry (register S-18) and none on Astha Web Entry. Parity: Web Entry keeps its Save button; Astha Web Entry keeps none (the grid saves per cell). Astha-channel outlets are excluded from the Web Entry classes: Astha quantities are keyed only through Astha Web Entry (rule R-050), and a data-quality rule flags the same outlet-day present in both grids (D-537; F-WEB-048, F-WEB-050).

### 6.4 Parity Exceptions Register

R2 says no current function is missed. A function that is live in the current build, or visible in it, and is not built, is hidden, or is reserved here appears in this register. Every row needs an owner, the sponsor signature and a date; the register is part of the 0c exit and the usage census (T-0-49 item i, D-503) supplies the evidence for each row. The 7b entry condition is "no unsigned row, and no Apsis function in the usage census without a replacement or a signed retirement" (T-7-158, mirrored in T-7-88; D-502).

| PX | Function (as seen in the current build) | Built here as | Default until signed | Owner role | Evidence needed | Signature and date | Decision, gate |
| --- | --- | --- | --- | --- | --- | --- | --- |
| PX-01 | Wholesale order (bex) pages | F-WEB-070, built, absent from every menu | Off; the wholesale mark in F-ADM only marks (D-42) | sales operations, distribution houses | usage census; Q17 | unsigned (due 1c, D-523) | D-42, D-502, D-523; T-0-153, T-7-158 |
| PX-02 | Back-margin commission letters | F-WEB-071, built, absent from every menu | Off | sales operations, distribution houses | usage census; Q17 | unsigned (due 1c) | D-42, D-502; T-0-153, T-7-158 |
| PX-03 | Voice recording page (`voicerecording`, docs/13 Q15) | F-WEB-072, built, absent from every menu; no microphone permission in any app | Off | admin, sponsor | usage census; Q15 | unsigned (due 0c) | D-115, D-502; T-0-153, T-7-158 |
| PX-04 | Sales Journey and KPI home tiles (in screenshots, in no manual) | F-SR-067, F-SR-068, hidden through cfg.app.home_tiles | Hidden until captured from the live app (D-342) | AKTCL product owner | live-app capture, 4a at the latest | unsigned | D-342, D-503; G-15-01; T-0-153 |
| PX-05 | AMO Survey tile (tile exists, form never shown) | F-AMO-043, hidden through cfg.flag.amo_survey | Hidden until the form is captured | AKTCL product owner | live-app capture | unsigned | D-349 (survey), D-502; T-0-153 |
| PX-06 | Joint Call rubric items 4 and 5 and the default star (manual page ends mid item) | F-AMO-011: 3 live items and 2 disabled placeholders | Placeholders disabled | AKTCL product owner | the missing manual page or the live app | unsigned | D-348, D-502; OI-15-07 |
| PX-07 | Digonto and paan-masala lines for the TSO (docs/13 Q14) | F-TSO-002: tile list and unit labels are config, no Digonto row built | Tobacco only | sales operations | census; Q14 | unsigned | D-502; T-0-153 |
| PX-08 | DMO, WM, WMO and Top menus (only the TSO menu is shown in the manual) | F-ADM-064: menus are data in cfg.web.menu_by_role, built from the spec | Spec menus | sales operations | one screenshot per role | unsigned | D-336, D-502; OI-15-09 |
| PX-09 | Geo-triggered volume suggestion (the current app proposes a quantity when the rep is near an outlet) | F-SYS-035: hook ships empty and the hint is off | Off | sales operations | the rule from the live app | unsigned | D-502; T-0-153 |
| PX-10 | Programme outlets (Astha tiers hold about 76,100 outlets) in wave 1 | wave scope in doc 14 s7 and RK-23 | Programme zones are not excluded from wave 1 by default if the exclusion removes nearly every zone; the sponsor decides | programme owner, sales operations | zone-by-zone programme counts | unsigned (due 0c) | D-306, D-502; OI-14-06 |

A row leaves the register only by a signature: either "replaced by F-id" (with its gate) or "retired" (with the date and the owner). A new Apsis function found by the census is added as PX-11 and later; the register is a data file (`/plan/parity-exceptions.yaml`) checked by rtm-check and read by T-0-153.

## 7 Admin and master data (F-ADM)

71 rows: F-ADM-001 to F-ADM-037 (lens inventory, updated), F-ADM-038 to F-ADM-055 (config console pages P1 to P18 of doc 19 s5.2, F-ADM-(037+n) is page Pn; names from the lens, doc 19 is authoritative and its s11 sets the phases) and F-ADM-056 to F-ADM-071 (back-office tools minted here). Every admin write produces an audit row (R6). Behaviour of the tools and the rails (risk classes, approvals, canary) is doc 19 s7 and s8; this document states what each does and which rule it enforces.

### 7.1 Feature rows

| ID | Name | Roles | What it does, rules now stated | Writes | Reads | API | cfg keys | Refs (G, D, Q) | Ph | Gates | St |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-ADM-001 | Geography CRUD | master_data | Wing, Division, Territory, House, Zone (dep codes from 5001) and Cluster; zone gains dep_id, dep_name (the zone name in the sample), email, address, pda_contact_no and data_entry_date; codes unique; soft-delete by status, never with children. | geography tables, audit | - | F-API-035 | - | G-man-088, G-man-072; D-180 | 6a | T-6-41 | parity |
| F-ADM-002 | Route CRUD | master_data, TSO (own zone) | Route (ids from 10001), code, name, zone, kind sr or amo, visit_kind daily, 3f or 2f and visit_days (importer fills them from the route name; editable); name and visit-day label stored separately, never keyed on name. | route | zone | F-API-035 | cfg.route.* | G-man-100, UI-SR-21; D-29, D-242, D-257 | 6a | T-6-41 | changed |
| F-ADM-003 | Route assignment (SR, SS, cover) | master_data, TSO | Assign an SR to a route with valid_from and valid_to, overlap check, kind primary or cover; SS is a supervisor-tier designation in the AMO build (meaning unknown), not necessarily a substitute; a mid-month transfer re-attributes nothing historic: targets stay with the route, dues with the outlet, bundle re-downloaded. | route_assignment | app_user, route | F-API-035 | cfg.route.cover_max_days | G-feat-06, G-feat-07, G-man-055; D-187, D-331 | 6a | T-6-41 | changed |
| F-ADM-004 | Product hierarchy CRUD | master_data | Category, Segment, Brand, Variant, SKU with status (active or inactive per level), sales_enable, sort, image, base unit, report_unit and report_factor, pack size and type; segment and variant sort semantics defined (global versus per parent). | product tables | - | F-API-035 | - | G-man-038; D-16, D-227 | 6a | T-6-41 | parity |
| F-ADM-005 | Price management (5 types, effective-dated) | finance_admin | outlet, cc, distributor, reporting, nto with valid_from; three decimals stored as milli-taka; future-dated only with a MANDATORY preview (SKUs, price types, outlets and devices affected, percent jump) and a plausibility guard (`cfg.price.max_change_pct`, 15: a larger move needs `finance_approver`, above 100 percent is refused unless two approvers sign), a same-day correction lane for rows published within `cfg.price.correction_window_h` and a break-glass restore of the previous `list_version` (F-ADM-081, D-589); back-dating is a maker-checker action with a blast-radius preview; takes effect in the next bundle. MINIMAL in 2e (publish a price list with the rails of F-ADM-081; the pilot needs a revision path), full in 6a. | sku_price | sku | F-API-080 | - | G-feat-52; D-15, D-98 | 2e | T-6-41, T-2-172 | parity |
| F-ADM-006 | Sales Plan (zone x SKU) | master_data, TSO (own zones) | Web page (Web p9-11): per zone Email, Address, PDA Contact No., Enabled SKUs (chips with x and a picker tree "SKU List" with tri-state checkbox), Data Entry Date; pencil to edit, green tick saves one zone atomically with audit, red x cancels; "apply to all zones of this territory"; a plan change reaches phones at the next bundle and an offline phone keeps the old plan (server flags sku_not_in_plan, never rejects). MINIMAL in 2e (view and save a zone's SKUs for the pilot and wave zones), full in 6a. | sales_plan, zone fields | zone, sku | F-API-035b | cfg.sales_plan.edit_roles | G-man-088, G-feat-51; D-190 | 2e | T-6-41, T-2-172 | changed |
| F-ADM-007 | User CRUD | security_admin, TSO (reset) | Create and disable users (sr334001, amo5001), employee_code, designation, role, name, phone, status; usernames preserved from Apsis; temporary password with forced change; onboarding and offboarding (device revoke, pending-data recovery, dues reassignment). | app_user, password_history | - | F-API-035 | cfg.auth.lockout_attempts | G-feat-34, G-feat-63; D-102, D-119 | 6a | T-6-41 | parity |
| F-ADM-008 | User scope assignment | security_admin | Attach geography nodes with role-node consistency (AMO zone, TSO territory, DMO division, WM wing); temporary acting scope with valid_to for delegated final submit. | user_scope | geography | F-API-035 | - | D-91 | 6a | T-6-41 | parity |
| F-ADM-009 | Device management | device admin, TSO | List devices per user, last seen, app version, config version, integrity trust level, printer model; revoke blocks sync not local capture and warns about pending rows; OTP log. | device | device | F-API-035 | - | G-feat-41, G-feat-63; D-66 | 6c | T-6-43, 51 | parity |
| F-ADM-010 | Outlet CRUD (Retailer, Wholesale outlet) | master_data, TSO | Create, edit, close, reopen outlets; outlet_kind (retail, wholesale) separate from the channel enum; resolved price type; status active, closed, merged, archived; location_confirmed; PII gating; code assignment on approval (F-ADM-037); bulk marking in F-ADM-056. | outlet | classifications | F-API-035 | cfg.outlet.* | G-feat-33, G-feat-62; D-25, D-32, D-42 | 6a | T-6-41 | changed |
| F-ADM-011 | Classification CRUD | master_data | Channel (GT, DCC, Astha, RCC, MT, HoReCa), nine sub-channels (Astha tiers Platinum, Gold, Diamond, Silver), geo classification (Hill, Urban, SemiUrban, Rural; nullable); enums become code tables so a business user adds a value without a deploy. | sub_channel, channel, geo_classification | - | F-API-035 | - | D-258 | 6a | T-6-41 | parity |
| F-ADM-012 | Geofence configuration | config_editor, TSO (bounded) | Radius at global (100 m), wing, division, territory, geo_class, zone and outlet with effective dating, bounds 20 to 2000 m, outlet override above 3 x the zone value needs an approver, what-if on stored fixes, density view; reaches phones through the bundle and delta and is stamped on each visit (radius_m_used). Delivered by page P2 (F-ADM-039); doc 19 s6 owns the behaviour. | cfg.config_value | geography, geo_fix | F-API-037 | cfg.geo.radius_m, cfg.geo.radius_min_m, cfg.geo.radius_max_m, cfg.geo.tso_radius_mode | G-field-11, G-cfg-03, G-feat-50; D-87, D-93, D-94, D-254; R6 | 1c | T-1-01..04, 60..69 | changed |
| F-ADM-013 | Operating-parameter console | config_editor | All cfg keys by area with typed validation, risk classes C0 to C3, reason, audit and a reach widget; apps receive the snapshot in the bundle; web reads live. Delivered by pages P1, P3 to P7 (F-ADM-038, F-ADM-040..044); doc 19 s3 holds the key registry. | cfg.config_value, cfg.config_change_audit | - | F-API-037 | all cfg keys | G-cfg-02, G-feat-50; D-87, D-88, D-90; R6 | 1c | T-1-01..04, 60..69 | new |
| F-ADM-014 | Set Target | TSO, WMO | Web "Target Settings": Wing, Division, Territory (All Selected), month picker, Apply; manual route x variant grid or Download Sample and Upload Excel panel; every monthly set is a header (name, product type variant, target type stt, start and end date, status, source) needing approval; target at or above 0; no automatic split. Evid: Web p35. | target_set, target | product, route | F-API-021, F-API-054 | cfg.target.product_types, cfg.target.types, cfg.target.split_method, cfg.target.template_version, cfg.target.upload_max_rows, cfg.target.entry_window | G-man-068, G-man-090, G-feat-24; D-31, D-189 | 5c | T-5-43 | changed |
| F-ADM-015 | Target revision workflow config | config_approver | Approval levels and who approves at each (default one level, WMO); statuses draft, submitted, wmo_pending, approved, rejected, returned (only "WMO approval pending" is evidenced). | target_approval_event | - | F-API-021 | cfg.target.approval_levels | G-feat-24, G-man-068; D-31, D-179; Q12 | 5c | T-5-43 | changed |
| F-ADM-016 | Offer and promotion CRUD | master_data | Promotion groups (about 22) with bn and en text, valid_from and valid_to, qualifying brand or SKU set, ratio, reward SKU, scope, DRP offers and free-sample rules; seeded from the Q13 catalogue (the only evidenced offer: 100 sticks of empty MaxR packs get 1 pack MaxR 10s, 25 Nov to 30 Dec 2025); test-a-memo simulator (F-ADM-061). | offer, promotion | product | F-API-035a | cfg.promo.rules, cfg.drp.shortcut_steps | G-feat-13, G-man-004; D-33, D-143; Q13 | 2a | T-2-36..44 | new |
| F-ADM-017 | Diamond League setup | master_data, program owner | Earning rules (seed: POSM survey Q1.1 photo +50), monthly period with redeem_until (April league expires 2026-05-07), gift catalogue with point costs, cash back rate and cap scope; expiry rule. | program, gift_catalog | - | F-API-035a | cfg.loyalty.earning_rules, cfg.loyalty.expiry_days, cfg.loyalty.cash_rate_mtk_per_point, cfg.loyalty.cash_max_points, cfg.loyalty.gift_catalog | G-feat-20, G-man-040, G-man-041, G-man-042; D-41 | 5a | T-5-10, 41 | new |
| F-ADM-018 | Astha program setup | master_data, TSO | Quarter, per-outlet per-brand STD targets and one memo target, gift catalogue and tiers; gift choice per outlet is the web panel (F-TSO-020). | program_outlet_target, gift_assignment, gift_catalog | outlet (Astha) | F-API-021b | cfg.astha.quarter_start_month, cfg.astha.gift_catalog | G-feat-21, G-man-047; D-192 | 5a | T-5-10, 41 | new |
| F-ADM-019 | Superstar setup | master_data | Monthly enrolment per outlet: category, incentive slab, base target, STD and memo targets; criteria-met computation job (rules unknown; default enrolment by admin, slabs as data). | program_enrolment, program_enrolment | outlet | F-API-035a | cfg.superstar.* | G-feat-22; D-332 | 5b | T-5-42 | new |
| F-ADM-020 | Survey, questionnaire, rubric and content definitions | master_data | POSM survey questions (Q1 required yes/no, Q1.1 conditional photo with points_reward), AMO survey, TSO Visit Query questions, joint-call rubric (3 known items plus 2 disabled placeholders), AV and KV assets with per-outlet, cluster, route or channel assignment and validity. | survey_question, assessment_rubric, campaign_content, outlet_content_assignment | - | F-API-035a | cfg.survey.*, cfg.rubric.*, cfg.content.* | G-feat-12, G-feat-56, G-man-043, G-man-051; D-349 | 2a | T-2-36..44 | new |
| F-ADM-021 | Task type CRUD | master_data | OOS, General, Irregular Visit and new types; statuses ongoing and completed with bn labels; assignable types per role. | task_type | - | F-API-035a | cfg.task.types, cfg.task.statuses, cfg.tso.assignable_task_types | G-feat-19, G-man-049, G-man-082; D-328 | 5c | T-5-43 | parity |
| F-ADM-022 | SR Device OTP administration | TSO, device admin | Superset of the web panel (F-TSO-022): re-issue (admin improvement), scoped filters, search, refresh, OTP log; one active OTP per user; launch-day throughput through F-ADM-069. | device_otp | app_user in scope | F-API-036 | cfg.auth.otp_ttl_min | G-feat-11, G-man-021; D-103 | 0c | T-0-70..76 | changed |
| F-ADM-023 | QC fault-type administration | master_data | One qc_fault_type table (11 codes: the 6 app and 10 web labels with the overlap map, stable group codes MFC and MKT, applies_to app or web); the TSO's QC entry and report pages are F-WEB-052 and F-WEB-060..062. | qc_fault_type | - | F-API-035 | cfg.qc.fault_types, cfg.qc.expired_stock_months | G-feat-32, G-man-089, G-man-003; D-34, D-159, D-183 | 6a | T-6-41 | new |
| F-ADM-024 | Data Entry (web back-office and manual backfill) | TSO, support | "Data Entry" is the web group Web Entry, Final Submit and Astha Web Entry (F-WEB-050, F-WEB-051, F-WEB-048); a dead-phone day is also keyed through the same ingest by printed memo_no with source=manual, flagged in Online/Offline reports; back-date window per F-ADM-057. | web_entry tables, field tables (source=manual) | - | F-API-038, F-API-050 | cfg.web.entry_backdate_days | G-feat-30, G-man-085; D-40, D-97 | 4c | T-4-61, T-4-123, T-2-151 | changed |
| F-ADM-025 | Supervisory Module | master_data | AMO call reporting (F-WEB-054) plus the supervisor target table (control-call and joint-call targets by AMO and month; origin unknown). | supervisor_target | app_user | F-API-035 | cfg.target.supervisor_targets | G-feat-31, G-feat-57, G-man-098 | 6a | T-6-41 | changed |
| F-ADM-026 | Tutorial content management | content admin | Upload and list tutorial videos and the four manuals per role; list cached on devices. | tutorial_video | - | F-API-027 | cfg.content.tutorial_videos | G-feat-47 | 6a | T-6-41 | parity |
| F-ADM-027 | App release management | release_mgr | Upload APK per ABI (checksum, size gate), publish, set latest, min_version, blocked versions, staged rollout by wave and wave_pct, adoption by version; Page P12. | app_release | - | F-API-029 | cfg.release.min_version, cfg.release.wave_pct | G-man-022; D-10, D-79 | 2e | T-2-31..35, 47..49, 53..57 | new |
| F-ADM-028 | Feedback inbox | admin, DMO | Read and triage TSO feedback by category and status. | feedback.status | feedback | F-API-032 | cfg.feedback.categories | G-feat-48, G-man-083 | 3b | T-3-25..28, 42 | new |
| F-ADM-029 | Day reopen and final-submit override | ops_admin | Reopen a final-submitted zone-day (reason, window), late-sync queue after final, missing check-out list; audited; who may reopen and what may change is Q11. | final_submit.reopened_by, audit | final_submit | F-API-009 | cfg.day.reopen_roles | G-feat-17, G-man-071; D-55; Q11 | 3b | T-3-25..28, 42 | new |
| F-ADM-030 | Sync quarantine review | ops_admin, TSO | Filter by reason, PII-masked payload, re-map outlet or SKU, accept, discard or return to device; bulk by reason; four-eyes for data-entry-class reasons; Page P14. | sync_rejected.resolved_* | sync_rejected | F-API-028 | cfg.sync.reason_texts | D-65 | 2e | T-2-31..35, 47..49, 53..57 | new |
| F-ADM-031 | Rollout wave and rollback console | release_mgr | F-SYS-041 and F-SYS-042 UI; Page P18. | rollout_wave | geography | F-API-033 | cfg.flag.new_app_login_enabled | D-147, D-148 | 7b | T-7-83..87 | new |
| F-ADM-032 | Import and reconciliation console | importer | Run an import, view the reconciliation report and the parallel-run comparison; Page P17. | import_run, control_total | - | F-API-033 | - | D-153 | 7a | T-7-80..82 | new |
| F-ADM-033 | Working-day calendar | master_data | Weekend days (default Friday), national and per-wing holidays with selling_day, make-up days, route-day overrides; future-dated only for past dates; drives Login %, till-date bases and Daily Tracking. | cfg.calendar | - | F-API-037 | cfg.calendar.weekend_days, cfg.calendar.holidays | G-feat-09, G-feat-10; D-28, D-247 | 4a | T-4-41, 51..52 | new |
| F-ADM-034 | Admin audit log viewer | audit.view | Unified config, master-data and permission changes by actor, key, scope and date with export; Page P16. | - | app.audit_log, cfg.config_change_audit | F-API-037 | - | G-feat-50; D-113 | 6b | T-6-60..69 | new |
| F-ADM-035 | Loyalty balance adjustment | finance_admin | Manual plus or minus points with a reason as an adjustment ledger row; day-one balances must be exact. | loyalty_ledger | loyalty_ledger | F-API-035a | cfg.loyalty.* | D-41 | 5a | T-5-10, 41 | new |
| F-ADM-036 | Dues adjustment and write-off | finance_admin (maker-checker) | Manual due correction as an adjustment ledger row with reason and approver; closing an outlet with open dues routes to this queue (outlet status closed_with_dues until cleared). MINIMAL in 2e (a finance adjustment row with reason and approver for a disputed due, the tool for the retailer disputes docs/11 calls the most dangerous), full in 6a. | due_collection(kind=adjustment) | memo, fact_due_ledger | F-API-035b | cfg.outlet.close_block_if_dues | G-feat-45, G-field-09; D-37 | 2e | T-6-41, T-2-172 | new |
| F-ADM-037 | Outlet code assignment rule | master_data | Generates DHK-344-011-style codes on approval; the rule is unknown (field-created outlets seem to carry letters, 7-digit numbers dominate); outlet_code is text. | outlet.outlet_code, code_sequence | - | F-API-035b | - | G-feat-55; D-237, D-251 | 4c | T-3-41, T-4-125 | new |
| F-ADM-038 | Config page P1: Config home | config_editor | Current config_version, pending requests, recent changes, reach widget (acked %), anomaly watches, break-glass items. | - | cfg.* | F-API-037, F-API-064 | - | R6; D-90 | 1c | T-1-01..04, 60..69 | new |
| F-ADM-039 | Config page P2: Geofence radius management (map) | config_editor, TSO | See F-ADM-012; map, density view, what-if, calibration. | cfg.config_value | geo_fix | F-API-037 | cfg.geo.* | R6; D-93 | 1c | T-1-01..04, 60..69 | new |
| F-ADM-040 | Config page P3: Rules and thresholds console | config_editor | All S and O keys by area; check-out time and min_version first. | cfg.config_value | cfg.* | F-API-037 | cfg.day.checkout_earliest_time, cfg.release.min_version | R6; D-90 | 1c | T-1-01..04, 60..69 | new |
| F-ADM-041 | Config page P4: Operational switches | ops_admin | Kill switch, read-only, sync hold, bundle hold, banner with timers and break-glass. | cfg.config_value | - | F-API-037 | cfg.ops.* | R6; D-130 | 2d | T-2-10..19, 60..69 | new |
| F-ADM-042 | Config page P5: Change requests and approvals | config_approver | Inbox, diff, blast radius, approve, reject, schedule; C2 delayed apply, C3 two-person. | cfg.config_request | - | F-API-037, F-API-061 | - | R6; D-88 | 2d | T-2-10..19, 60..69 | new |
| F-ADM-043 | Config page P6: History and rollback | config_editor | Timeline per key and scope, compare, revert, rollback-to-version, export. | cfg.config_value | cfg.config_change_audit | F-API-037, F-API-062 | - | R6; D-88 | 2d | T-2-64, T-6-61 | new |
| F-ADM-044 | Config page P7: Reach and pending devices | config_editor | Devices per config version, acked share, pending list. | - | cfg.config_ack | F-API-037, F-API-063 | - | R6; D-89, D-90 | 1c | T-1-01..04, 60..69 | new |
| F-ADM-045 | Config page P8: Programme setup | program owner | Astha, Diamond League, Superstar, Promotions (rule builder, simulator), Free samples. | programme tables | - | F-API-035a | cfg.astha.*, cfg.loyalty.*, cfg.superstar.*, cfg.promo.* | R6 | 2a | T-2-41, T-5-60, T-5-61 | new |
| F-ADM-046 | Config page P9: Master data CRUD | master_data | Geography, clusters, products, prices, sales plan, routes, assignments, users and scope, outlets, classifications, task and leave types, holiday calendar. | master tables | - | F-API-035b | - | R6 | 2e | T-2-31..35, T-6-62 | new |
| F-ADM-047 | Config page P10: Targets | TSO, WMO | Set target (bulk upload, split preview), revision approval queue, approval-level config. | target tables | - | F-API-021 | cfg.target.* | R6 | 5c | T-5-43 | new |
| F-ADM-048 | Config page P11: Device management | device admin, TSO | Devices, bound users, last sync, app version, config version, integrity, revoke, OTP issue and log. | device, device_otp | device | F-API-036 | cfg.auth.* | R6 | 0c | T-0-70..76 | new |
| F-ADM-049 | Config page P12: Release management | release_mgr | APK upload, publish, min_version, blocked versions, staged rollout, adoption chart. | app_release | - | F-API-029 | cfg.release.* | R6 | 2e | T-2-31..35, 47..49, 53..57 | new |
| F-ADM-050 | Config page P13: Sync health | ops_admin | Login %, Submit % (of logged-in), final-submit by zone, trickle latency, error rate, quarantine backlog, config ack %, pending photos. | - | dw.* | F-API-028 | cfg.sla.* | R6 | 1c | T-1-01..04, 60..69 | new |
| F-ADM-051 | Config page P14: Data-quality quarantine review | ops_admin | As F-ADM-030. | sync_rejected | sync_rejected | F-API-028 | - | R6 | 2e | T-2-31..35, 47..49, 53..57 | new |
| F-ADM-052 | Config page P15: Day control | ops_admin | Reopen zone-day, late-sync queue, missing check-outs. | final_submit | final_submit | F-API-009 | cfg.day.reopen_roles | R6; D-55 | 3b | T-3-25..28, 42 | new |
| F-ADM-053 | Config page P16: Audit viewer | audit.view | As F-ADM-034. | - | audit tables | F-API-037 | - | R6; D-113 | 2d | T-0-64, T-2-64, T-6-61 | new |
| F-ADM-054 | Config page P17: Import and reconciliation console | importer | As F-ADM-032. | import_run | - | F-API-033 | - | R6 | 7a | T-7-80..82 | new |
| F-ADM-055 | Config page P18: Feature flags and waves | release_mgr | Flag matrix by scope, wave membership, per-wave overrides. | cfg.flag | - | F-API-037, F-API-065 | cfg.flag.* | R6; D-99 | 6c | T-6-43, 51 | new |
| F-ADM-056 | Wholesale outlet bulk marking | TSO (marking), master_data | Web "Wholesale Retailers" (Web p42-44): Wholesale Status Yes or No, Outlet Search, table with select-all (indeterminate), floating basket with live count, Selected Outlets dialog with Remove, Submit; idempotent by batchUuid, one audit row per outlet and outlet_class_history; unmark behind cfg.outlet.wholesale_unmark_allowed; effect on price type, target-outlet counting and geo gate is defined before building. | outlet.outlet_kind, outlet_class_history | outlet | F-API-045 | cfg.outlet.wholesale_marking_roles, cfg.outlet.wholesale_unmark_allowed, cfg.outlet.wholesale_price_type | G-man-036; D-42; Q17 | 6a | T-6-41 | parity |
| F-ADM-057 | Entry unlock grants and back-date window | finance_admin, support | Web entry back-date cut-off (default today only) with per-zone data_entry_date and audited entry_unlock_grant (zone or route, date range, reason, expiry) replacing "call support who edits the database"; the app-sync window is a separate key. | entry_unlock_grant | zone | F-API-049 | cfg.web.entry_backdate_days, cfg.web.entry_unlock_roles, cfg.web.entry_unlock_max_days, cfg.sync.max_backdate_days | G-man-087; D-97 | 4c | T-4-63 | new |
| F-ADM-058 | Audited data void (Delete Section Data) | TSO (own scope), higher role for app memos | data_void (route, business date, scope, reason, voided_by, summary) with ingest tombstones so a late upload is rejected as voided_by_admin; only before Final Submit; default scope web-entry rows. | data_void, ingest_registry tombstones | route_day | F-API-048 | cfg.web.delete_section_data_roles, cfg.web.delete_section_data_scope | G-man-086; D-22, D-40, D-182 | 4c | T-4-62, T-4-63, T-4-123 | changed |
| F-ADM-059 | Target Excel template, upload and error report | TSO | Sample workbook (route code, route name, variant code, STD target, optional memo target), all-or-nothing validation (at least 0, numeric, known route and variant, territory in scope, duplicates, month) with a downloadable error sheet; file stored in Blob and linked to the target_set. | target_upload (media_object) | route, variant | F-API-054 | cfg.target.template_version, cfg.target.upload_max_rows | G-man-090; D-31, D-189 | 5c | T-5-43 | parity |
| F-ADM-060 | Reason-code and list tables | config_editor | Force, edit, void, exception, visit-outcome, QC-fault, feedback and task lists as code tables with bn and en labels (never enums). | cfg code tables | - | F-API-037 | cfg.sale.force_reasons, cfg.memo.edit_reasons, cfg.memo.void_reasons, cfg.day.exception_reasons, cfg.visit.outcome_codes | G-cfg-10, G-man-012; D-200 | 6b | T-6-60..69 | new |
| F-ADM-061 | Promotion test-a-memo simulator | master_data | Enter quantities for an outlet and see applied offers, offer discount, DRP discount and the printed totals before publishing a rule. | - | offer | F-API-035a | cfg.promo.rules | G-feat-13; D-33 | 2a | T-2-36..44 | new |
| F-ADM-062 | Interim web cover assignment | TSO, support | Web form for same-day cover until the AMO app action (F-AMO-037) ships. | route_assignment(kind=cover) | route, app_user | F-API-043 | cfg.route.cover_max_days | G-field-06; D-85 | 2e | T-2-31..35, 47..49, 53..57 | new |
| F-ADM-063 | Re-attribution action | TSO + finance approval | Audited event that moves a route-day's rows to the acting user in dw (app rows are never updated). | app.attribution_event | route_day | F-API-035b, F-API-056 | - | G-field-05; D-66, D-85 | 3a | T-3-20..24, 41 | new |
| F-ADM-064 | Role x menu x action matrix editor | security_admin | Menus and permissions are data (cfg.web.menu_by_role); the union of the TSO menu (15 items, 41 pages) and the spec menu; doc 19 s5.3 holds the matrix. | cfg.web_menu | - | F-API-037 | cfg.web.menu_by_role | G-man-099; D-185, D-243; Q16 | 4c | T-4-65, T-4-120 | new |
| F-ADM-065 | SKU pack image management | master_data | One compressed thumbnail per SKU shown in Stock, Sale and Memo; current approved pack art only; cached with a bounded LRU on devices. | sku.image | sku | F-API-035 | cfg.app.image_cache_mb | UI-SR-19 | 6a | T-6-41 | new |
| F-ADM-066 | Outlet badge legend | config_editor | Colour, programme and label of the four eligibility dots on the outlet list. | cfg.ui_badges | - | F-API-037 | cfg.ui.outlet_badges | G-15-02; D-341 | 6b | T-6-60..69 | new |
| F-ADM-067 | Print template management | config_editor | Memo, stock slip, summary, due receipt and cancel slip templates as data with version and golden prints; physical 58 mm samples are the oracle. | cfg.print_template | - | F-API-037 | cfg.print.template_version, cfg.print.models | G-man-014; D-76, D-158 | 6b | T-6-60..69 | new |
| F-ADM-068 | Wave temporary-password batch | security_admin | Per-wave batch of temporary passwords (forced change) when the dump carries no verifiable hashes; audited. | app_user.must_change | app_user | F-API-033 | - | G-feat-66; D-119 | 7a | T-7-80..82 | new |
| F-ADM-069 | Bulk OTP pre-issue per zone | TSO, device admin | Pre-issue and list OTPs for a wave's zones on the pre-bind day; audited; per-wave TTL override. | device_otp | app_user | F-API-036 | cfg.auth.otp_ttl_min | G-feat-11; D-103, D-126; Q52 | 7b | T-7-83..87 | new |
| F-ADM-070 | Outlet reactivation | master_data | Reopen a wrongly closed outlet (status back to active) with reason and audit; also reactivates archived stubs a rep needs back (cutover week). MINIMAL in 2e (reactivate one outlet or archived stub; cutover week needs it), full in 6a. | outlet.status | outlet | F-API-035b | - | G-feat-62; D-25, D-330 | 2e | T-6-41, T-2-172 | new |
| F-ADM-071 | SR transfer between routes | master_data, TSO | Effective-dated reassignment: targets stay with the route, MTD achievement attribution by assignment, bundle re-download, dues stay with the outlet. MINIMAL in 2e (one effective-dated reassignment), full in 6a. | route_assignment | route_assignment | F-API-035b | - | G-feat-06; D-331 | 2e | T-6-41, T-2-172 | new |
| F-ADM-072 | Config page P19: Support desk | support_l1, support_l2, TSO, ops_admin | One search box (username, phone, employee code, memo number, outlet code, batch uuid, support code), the decoded support code, the user and device card with bundle freshness, route assignment, route_day state, last bundle, printer paired, free storage, the last 50 requests, a deep link to quarantine, the "why does this tile show yesterday" reason chip and "Create ticket" (D-541). | audit_log | device, route_day, sync_batch, memo | F-API-071 | cfg.support.contacts | G-qa-72, G-qa-71; D-541 | 2e | T-2-157, T-2-58 | new |
| F-ADM-073 | Emergency non-working-day declaration | ops_admin, support_l2 (propose) | Declares a hartal, cyclone, curfew or sudden holiday at wing, division, territory or zone scope, effective now, exempt from the freeze, two-person only when retroactive; sets `planned` false for route-days not yet logged in, keeps logged-in routes counting, suppresses SH-01 and Sev1 for the scope (D-542). | cfg.holiday, route_day | route_day | F-API-072 | cfg.calendar.emergency_declare_roles, cfg.calendar.emergency_max_days | G-qa-73; D-542 | 2e | T-4-154 | new |
| F-ADM-074 | Submit void and day-control actions (P15) | ops_admin, support_l2, support_l1 (C), TSO | Voids one route-day's Sales Submit with a reason and a confirmer; reopen and on-behalf Final Submit as before (D-539). | app.submit_void_event | route_day | F-API-069 | cfg.day.submit_undo_window_min, cfg.day.reopen_roles | G-qa-70; D-539 | 3b | T-3-150, T-3-61 | new |
| F-ADM-075 | Supervised paper-memo backfill | support_l2, TSO, ops_admin | Memo-level recovery for a dead or lost phone: support keys each printed memo by `memo_no`, the zone TSO approves, the memo, lines and dues are written with `entry_source = manual`, deterministic uuid, the device row wins if it later uploads (D-543). Replaces the aggregate Web Entry as the recovery path. | app.paper_backfill, memo, memo_line, due_collection | memo | F-API-073 | cfg.entry.paper_backfill_window_days | G-qa-74; D-543 | 2e | T-2-151 | new |
| F-ADM-076 | SR lifecycle wizard (minimal) and user_admin | user_admin (TSO, DMO), security_admin, master_data | Create, bind, disable-with-upload and reassign with maker-checker; a disabled user's phone uploads earlier rows as parked rows; the full wizard stays 6c (D-551). | app_user, user_scope, route_assignment | app_user | F-API-035b | cfg.user.admin_checker_required, cfg.user.dismissal_upload_grace_h | G-qa-83; D-551 | 2e | T-2-159 | new |
| F-ADM-077 | Emergency-widen lane and temporary relief | break-glass holders, config_approver | The two bounded loosening paths of doc 19 s7.6b with expiry, caps and a two-sided watch (D-527). | cfg.config_value, audit | cfg | F-API-075 | cfg.geo.radius_emergency_max_m, cfg.geo.emergency_widen_max_hours, cfg.geo.radius_incident_ceiling_m, cfg.sys.temporary_relief_max_h | G-qa-52, G-qa-77; D-527 | 2d | T-2-160, T-2-68 | new |
| F-ADM-078 | Replace-device wizard (P11) | TSO own zone, support_l2, ops_admin, support_l1 with TSO confirmation | One wizard for "my phone is replaced": the old device's pending-row count, "upload first" (the old device moves to the state `replaced` with the upload-only grant of `cfg.auth.revoked_device_grace_upload_h`) or "revoke now", the OTP for the new phone, and a CHECKER (DMO, security_admin or delegate) who approves before the bind so the takeover rule does not hold it; the maker is never the checker (D-586). | app.device, app.device_otp, audit_log | app.device | F-API-086 | cfg.auth.revoked_device_grace_upload_h, cfg.auth.max_devices_per_user, cfg.auth.unbind_block_if_pending_rows | G-qa-125, G-qa-124; D-586, D-585 | 2e | T-2-167 | new |
| F-ADM-079 | Held-binds queue (P11, P15 tile) | DMO, security_admin, delegates; L1 and L2 read | Every bind held by the takeover rule, a replacement or an actor-device reuse: age, user, zone, maker, device model, reason, the old device's pending rows; one-click release or reject (F-API-068); delegate after 15 minutes; SLA 30 minutes in selling hours; alert at 45 minutes (SH-27); runbooks RB-45 and RB-46. | device, audit_log | device | F-API-078, F-API-068 | cfg.auth.held_bind_sla_min, cfg.auth.held_bind_delegate_roles, cfg.sla.held_bind_alert_min | G-qa-125; D-586 | 2e | T-2-167 | new |
| F-ADM-080 | Replay console (P20) | support_l2 (enter), zone TSO and ops_admin (approve) | Upload or select a decrypted PDA bundle (plaintext in memory only); mandatory dry run against `ingest_registry` with counts by verdict (`new`, `duplicate_same`, `duplicate_different`, `voided`, `over_window`, `scope_violation`, `replay_excess`); second approval by the zone TSO; apply through the normal ingest path under the original user's identity with `entry_source = support_replay`; idempotent (twice equals once); `replay_excess` rows are never applied; at most `cfg.support.replay_max_rows`. | app.* via ingest, audit_log | app.ingest_registry | F-API-077, F-API-066 | cfg.support.replay_max_rows | G-qa-126; D-587 | 2e | T-2-168 | new |
| F-ADM-081 | Price-change rails | finance_admin, finance_approver | Mandatory preview, percent-jump guard (`cfg.price.max_change_pct`), refusal above 100 percent without two approvers, a same-day correction lane for rows published within `cfg.price.correction_window_h` (memos re-flagged `price_corrected_after`, never rewritten), and the break-glass restore of the previous `list_version`. | sku_price, audit_log | sku_price, memo_line | F-API-080 | cfg.price.max_change_pct, cfg.price.correction_window_h, cfg.price.backdate_allowed | G-qa-128; D-589 | 2e | T-2-166 | new |
| F-ADM-082 | Revert a bulk batch (P9) | master_data with checker, ops_admin | Builds the inverse change of a bulk operation from the audit before-values of its `batchUuid`, with the same preview, freeze, cap and maker-checker, within `cfg.master.revert_batch_window_days`; rows changed since are listed and skipped unless forced; also the supported undo of a wholesale mark. | master tables, audit_log | audit_log | F-API-081 | cfg.master.revert_batch_window_days, cfg.sys.bulk_op_max_rows | G-qa-134; D-595 | 6a | T-6-150 | new |
| F-ADM-083 | Retention-hold console (P16 tab) | security_admin, pii_officer | Place and lift holds by scope and data class with a reason, case id and a second approver; expiry at most `cfg.retention.hold_max_days`; lists the partitions and blobs the jobs skipped because of a hold. | app.retention_hold, audit_log | app.retention_hold | F-API-082 | cfg.retention.hold_max_days, cfg.retention.hold_roles | G-qa-139; D-600 | 6c | T-6-152 | new |
| F-ADM-084 | Support ticket store (P19) | support_l1, support_l2, TSO own zones | A minimal built-in store behind "Create ticket": category, opened, first-response and resolved timestamps, owner, severity, linked user, device, memo, outlet and batch, SLA timers (`cfg.sla.ticket_respond_min`, `cfg.sla.ticket_workaround_h`) and a contact log by category (SR, AMO, TSO, retailer dispute) that feeds the D-553 contact-rate measurement; the daily known-issue board. | app.support_ticket, app.support_contact_log | same | F-API-084 | cfg.support.ticket_categories, cfg.sla.ticket_respond_min, cfg.sla.ticket_workaround_h, cfg.support.backfill_l1_enabled | G-qa-138; D-599 | 2e | T-2-170 | new |
| F-ADM-085 | Device directive button (P19) | support_l1, support_l2, TSO own zones, ops_admin | "Ask the phone to send its PDA", "Ping", "Redownload the bundle": creates one signed directive for the device with an expiry; shown on the device card as pending, acted or expired. | app.device_directive, audit_log | app.device_directive | F-API-079 | cfg.support.directive_ttl_h, cfg.support.directive_types | G-qa-133; D-594 | 2e | T-2-169 | new |

### 7.2 Admin CRUD matrix: master and configuration entities (part A)

Ops: C create, R read, U update, D soft-delete by status (transactions are never deleted), Imp imported from the Apsis dump, Aud audited write with before and after. Roles are the permission bundles of D-91; "TSO" means within the TSO's own scope.

| Entity | Ops | Who | Rule | Feature |
| --- | --- | --- | --- | --- |
| wing | C R U D, Imp, Aud | master_data | code unique | F-ADM-001 |
| division | C R U D, Imp, Aud | master_data | parent wing | F-ADM-001 |
| territory | C R U D, Imp, Aud | master_data | parent division; radius lives in cfg, not here | F-ADM-001 |
| house | C R U D, Imp, Aud | master_data | parent territory | F-ADM-001 |
| zone | C R U D, Imp, Aud | master_data, TSO (own zone fields) | dep codes from 5001; dep_id, dep_name, email, address, pda_contact_no, data_entry_date | F-ADM-001, F-ADM-006 |
| cluster | C R U D, Imp, Aud | master_data, TSO | cluster_type (Transit Hub 99.7 percent), zone | F-ADM-001 |
| route | C R U D, Imp, Aud | master_data, TSO | ids from 10001; kind sr or amo; visit_kind daily, 3f, 2f; visit_days; name apart from label | F-ADM-002 |
| route_assignment | C R U D, Imp, Aud | master_data, TSO | primary or cover; valid_from and valid_to; no overlapping SR on one route and day | F-ADM-003, F-ADM-071 |
| product_category | C R U D, Imp (seed), Aud | master_data | status active or inactive; sales_enable; sort | F-ADM-004 |
| product_segment | C R U D, Imp (seed), Aud | master_data | as above | F-ADM-004 |
| product_brand | C R U D, Imp (seed), Aud | master_data | as above | F-ADM-004 |
| product_variant | C R U D, Imp (seed), Aud | master_data | target level; duplicate sort values defined | F-ADM-004 |
| sku | C R U D, Imp (seed), Aud | master_data | code unique; base unit; report_unit and factor; pack size and type; image | F-ADM-004, F-ADM-065 |
| sku_price | C R U (no delete; end-date), Imp, Aud | finance_admin | 5 types; effective-dated; 3 decimals as milli-taka; future-dated only | F-ADM-005 |
| sales_plan | C R U D (bulk), Imp, Aud | master_data, TSO (own zones) | zone x SKU; bulk by territory; effect on devices at next bundle | F-ADM-006 |
| channel | C R U D (enum becomes table), Aud | master_data | GT, DCC, Astha, RCC, MT, HoReCa | F-ADM-011 |
| sub_channel | C R U D, Imp, Aud | master_data | nine values incl. Astha tiers | F-ADM-011 |
| geo_classification | C R U D (enum becomes table), Aud | master_data | Hill, Urban, SemiUrban, Rural; nullable on outlet | F-ADM-011 |
| app_user | C R U D, Imp, Aud; reset password, disable | security_admin, TSO (reset) | role, employee_code, designation, username scheme preserved | F-ADM-007 |
| user_scope | C R U D, Imp, Aud | security_admin | role-node consistency; temporary acting scope with valid_to | F-ADM-008 |
| device | R U (revoke), Aud | device admin, TSO | bound via OTP; last seen; integrity trust level | F-ADM-009 |
| device_otp | C R | TSO, device admin | issue, re-issue, bulk pre-issue; AES-GCM; log | F-ADM-022, F-ADM-069 |
| cfg.config_value (replaces territory_geo_config) | C R U (versioned), Aud | config_editor, config_approver | typed, scoped, effective-dated, risk class; radius at 8 scope levels | F-ADM-012, F-ADM-013 |
| cfg code tables (reasons, outcome, QC fault, task, leave types, feedback categories) | C R U D, Aud | config_editor | never enums; bn and en labels | F-ADM-060, F-ADM-023 |
| cfg.calendar (weekend days, holidays, overrides) | C R U D, Aud | master_data | future-dated only for past dates | F-ADM-033 |
| cfg.web_menu (role x menu x action) | C R U, Aud | security_admin | menus are data | F-ADM-064 |
| cfg.print_template | C R U (versioned), Aud | config_editor | golden print per kind | F-ADM-067 |
| offer / promotion | C R U D, Imp?, Aud | master_data | rule schema from the Q13 catalogue; simulator | F-ADM-016, F-ADM-061 |
| loyalty_program, loyalty_gift | C R U D, Aud | program owner | earning rules, period, catalogue, cash rate and cap | F-ADM-017 |
| astha_target | C R U D, Imp, Aud | program owner, TSO | at least 0; per brand per outlet per quarter | F-ADM-018 |
| astha_gift_choice, gift_catalog | C R U D, Imp, Aud | TSO, program owner | one gift per outlet per quarter; lock on photo | F-TSO-020, F-ADM-018 |
| superstar_enrolment, superstar_slab | C R U D, Aud | program owner | criteria job; rules unknown | F-ADM-019 |
| supervisor_target | C R U D, Aud | master_data | control-call and joint-call targets by AMO and month | F-ADM-025 |
| survey_question | C R U D, Aud | master_data | POSM, AMO survey, TSO Visit Query | F-ADM-020 |
| assessment_rubric | C R U D (versioned), Aud | master_data | joint-call rubric with 2 disabled placeholders | F-ADM-020 |
| campaign_content, outlet_content_assignment | C R U D, Aud | content admin | AV and KV assets with per-outlet assignment and validity | F-ADM-020 |
| tutorial_video | C R U D, Aud | content admin | list cached on devices | F-ADM-026 |
| qc_fault_type | C R U D, Aud | master_data | 11 codes with applies_to | F-ADM-023 |
| task_type | C R U D, Aud | master_data | OOS, General, Irregular Visit | F-ADM-021 |
| target_set, target | C R U (bulk), Imp, Aud | TSO, WMO | at least 0; fractional; header with approval events | F-ADM-014, F-ADM-059 |
| target_set_approval (levels) | C R U, Aud | config_approver | default one level WMO | F-ADM-015 |
| app_release | C R U D, Aud | release_mgr | checksum, size gate, min_version, blocked, wave_pct | F-ADM-027 |
| rollout_wave, cfg.flag | C R U, Aud | release_mgr | wave membership, flags | F-ADM-031, F-ADM-055 |
| import_run, id_crosswalk, import_reconciliation | C R, Aud | importer | idempotent re-import | F-ADM-032 |
| outlet | C R U D (status), Imp, Aud | master_data, TSO (own scope) | outlet_kind; status active, closed, merged, archived; PII gating; reactivation | F-ADM-010, F-ADM-070, F-WEB-003 |
| outlet_kind_history | C R, Aud | TSO | one row per outlet per bulk marking | F-ADM-056 |
| outlet_change_request | R U (verify, reject, approve), Aud | AMO app, TSO+, admin | lifecycle pending, verified, approved or rejected | F-WEB-032, F-AMO-022 |
| outlet_photo | R, D (privacy) | admin | - | F-WEB-003 |
| task | C R U D | AMO, TSO, admin | assignee = route SR on the due date | F-AMO-019, F-TSO-016 |
| leave_application | C R U (approve) | TSO (create), DMO (decide) | one date plus typed days | F-TSO-009, F-WEB-046 |
| visit_plan, visit_plan_outlet | C R | TSO | union by outlet | F-TSO-013 |
| feedback | C R U (status) | TSO (create), admin | category list is config | F-ADM-028 |
| web_entry_route_day, web_entry_line, web_entry_outlet_sku | C R U (replace with audit) | TSO within the back-date window | client uuid per submission; exclusive with app rows | F-WEB-050, F-WEB-048 |
| qc_web_entry (market, warehouse) | C R U | TSO | separate source | F-WEB-052, F-WEB-060 |
| entry_unlock_grant | C R U (expire), Aud | finance_admin, support | zone or route, date range, reason, expiry | F-ADM-057 |
| data_void | C R, Aud | TSO (own scope), higher role for app memos | audited void with tombstones | F-ADM-058 |
| attribution_event | C R, Aud | TSO + finance approval | applied at aggregation only | F-ADM-063 |
| loyalty_ledger (adjustment rows) | R, C adjustment, Aud | finance_admin | day-one balances exact | F-ADM-035 |
| due_collection (adjustment rows) | R, C adjustment, Aud | finance_admin (maker-checker) | write-off queue; closed_with_dues | F-ADM-036 |
| final_submit (reopen) | R U (reopen), Aud | ops_admin | Q11 | F-ADM-029 |
| sync_rejected | R U (resolve) | ops_admin | retry, fix and accept, discard | F-ADM-030 |
| tracking_action | C R | DMO+, TSO | note plus notification | F-WEB-039 |
| day_exception | C R U (approve) | SR, AMO (raise), TSO (approve) | reason code, date range | F-SR-059, F-AMO-045 |
| route_assignment (cover) | C R | AMO, TSO, support | up to 7 days | F-AMO-037, F-ADM-062 |

### 7.3 Transactional entities: read, flag or correct (part B)

Admins never edit these in place; a correction is a new row or event (D-22).

| Entity | Ops | Who | Rule | Feature |
| --- | --- | --- | --- | --- |
| attendance_event | R; correction by Web Entry or admin event | admin | GIGO report | F-WEB-021 |
| stock_movement | R; correction event (adjustment) | admin | issue, return, damaged, short, qc_return, confirmed_by | F-SR-014, F-SR-051 |
| visit | R; flag review | admin, TSO+ | suspicious flags, outcome codes | F-WEB-044, F-WEB-066 |
| memo, memo_line | R; void or supersede row only | admin | never edited in place | F-SR-033, F-SR-058 |
| memo_void | R | admin | event with reason | F-SR-058 |
| qc_entry, qc_entry_line | R | admin | QC reports | F-WEB-061 |
| survey_response | R | admin | - | F-ADM-020 |
| drp_collection | R | admin | - | F-WEB-023 |
| due_collection | R; adjustment row | admin | - | F-ADM-036 |
| loyalty_ledger | R; adjustment row | admin | - | F-ADM-035 |
| redemption, redemption_line | R | admin | - | F-WEB-022 |
| gift_photo | R | admin | - | F-WEB-034 |
| call_assessment | R | admin | - | F-WEB-054 |
| distribution_check | R | admin | - | F-WEB-054 |
| price_compliance | R | admin | - | F-AMO-035 |
| cash_handover | R | admin | - | F-WEB-059 |
| due_dispute | R | admin, finance | AMO task plus finance queue | F-SR-071 |
| sync_batch | R | admin | sync health | F-SYS-026 |
| route_log | R | admin | Data Entry Log | F-WEB-016 |
| route_day, supervisor_day | R | admin | day state | F-SYS-016 |
| activity_log | R | admin | audit | F-ADM-034 |
| audit_log, config_change_audit, security_event, report_export_log | R (append-only) | audit.view | immutable, hash chain | F-SYS-059 |
| risk_signal, risk_review | R; review events | TSO+ | nothing auto-reversed | F-SYS-057 |

Part A has 64 entity rows and part B has 23; the lens counted 58 entities in its single list, all of which are inside these two tables (several rows here group tables that share one screen, for example the four product levels are four rows and the offer pair is one).

### 7.4 Back-office write tools by role

| Role | Tools (features) | Rail |
| --- | --- | --- |
| TSO (own scope) | Web Entry F-WEB-050, Astha Web Entry F-WEB-048, QC entry F-WEB-052 and F-WEB-060, Sales Plan F-ADM-006, Set Target F-ADM-014 and F-ADM-059, outlet approval F-WEB-032, wholesale marking F-ADM-056, SR Device OTP F-TSO-022, gift choice F-TSO-020, data void F-ADM-058 (web-entry rows) | back-date window F-ADM-057; audit on every write |
| master_data | geography, routes, products, classifications, offers, survey and rubric, content, outlets | future-dated rules for price, targets and calendar (D-98) |
| finance_admin | prices, loyalty and dues adjustments, entry unlock grants, re-attribution approval | maker-checker (D-91) |
| config_editor and config_approver | config console pages P1 to P7, reason-code tables, print templates | risk classes C0 to C3, two-person for C3 (D-88) |
| release_mgr | releases, waves, flags (F-ADM-027, F-ADM-031, F-ADM-055) | change-freeze windows (D-100) |
| ops_admin | day reopen, quarantine, sync health | audit; reopen reason (D-55) |
| security_admin | users, scope, role x menu matrix, temporary passwords | MFA for admin bundles (D-114) |
| importer | import and reconciliation console | quarantine at most 1 percent (D-153) |

Proved by: T-6-41 (CRUD with audit, future-dating), T-6-60..69 (config console, risk classes, audit viewer), T-6-43 and T-6-51 (devices, OTP, releases), T-4-70..76 (back-office tools and PII), T-5-43 (targets).

## 8 API catalogue (F-API)

69 endpoints: F-API-001 to F-API-038 and 020b (docs/09 set plus lens additions, updated), F-API-039 to F-API-046 (pinned), F-API-047 to F-API-057 (minted here) and F-API-058 to F-API-068 (minted at the editorial merge for the admin and security endpoints that docs 19 and 21 marked "needs F-id"; paths in docs 19 s10 and 21 s5). The new system defines its own API (CLAUDE.md guardrail); the wire contract is generated from /packages/contract (D-150) and doc 17 s4 owns the record types.

### 8.1 Conventions

| Item | Rule | Decision |
| --- | --- | --- |
| Base path, format | /v1, JSON snake_case, envelope { code, message, details, request_id } with stable ERR_<AREA>_<NAME> codes | master plan API conventions |
| Scope | every read is intersected with the caller's scope on the server; no endpoint accepts scope ids from the client; every foreign key in a write body is resolved through ScopeContext | D-106 |
| Idempotency | device writes keyed by client_uuid in ingest_registry; web writes carry a client uuid per submission; batch replay by (device_id, batch_uuid) | D-21, D-62 |
| Headers | X-Config-Version, X-Server-Generation, X-Batch-Attempt, X-Pending-Rows, X-Last-Sync-Error, X-Device-Proof, X-App-Version, X-Device-Id | master plan API conventions |
| Lists | paginated with updated_since; bundles paged above 2,000 rows with a 2 MB gzip cap | D-72 |
| Classes (Cls) | same codes as the Off column of s1.1 | s10 |
| Limits | batch at most 500 rows, 1 MiB compressed, 8 MiB decompressed, memo lines at most 60; unknown JSON keys rejected | D-116 |

### 8.2 Endpoints

| ID | Endpoint | Callers | What it does, idempotency, scope | Cls | Writes | Reads | Used by | Ph | Gates | St |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F-API-001 | POST /auth/login | all | Returns token, refreshToken, user, scope (claims: role, top scope nodes, scope_version); rate limit per username and device (not per IP: carrier NAT); integrity payload optional; uniform errors; per-replica hash concurrency limiter with 503 Retry-After. | ONF | audit_log, device | app_user, user_scope, device | F-SYS-001 | 0c | T-0-70..76 | changed |
| F-API-002 | POST /auth/refresh | all | Rotated opaque refresh token with reuse detection and 60 s grace replay. | ONF | refresh_token | refresh_token | F-SYS-002 | 0c | T-0-70..76 | changed |
| F-API-003 | POST /auth/bind-device | SR, AMO | {deviceUuid, otp} plus device public key; single-use OTP with TTL and 5 attempts; needs a password-authenticated bind_required token. | ONL | device, device_otp | device_otp | F-SYS-003 | 0c | T-0-70..76 | changed |
| F-API-004 | POST /auth/change-password | all | Policy-checked change; Argon2id; history of 10; 24 h minimum age. | ONL | password_history | password_history | F-SYS-004 | 0c | T-0-70..76 | parity |
| F-API-005 | GET /sync/bundle?since= | all-app | Day bundle per role (routes, outlets, products, prices both lists, salesPlan, targets, offers, loyaltyBalances, geoConfig, tasks, giftAssignments, config snapshot, outletRequests status, suggestions, content refs, businessDate, opening balances); gzip, ETag and 304, delta, paged above 2,000 rows (2 MB gz cap). | ONF | bundle_download, route_day | reference, dw | F-SYS-006, F-SYS-007 | 1a | T-1-20..24, 35, 41 | changed |
| F-API-006 | POST /sync/batch | all-app | One ordered flat records[] list with envelope (batch_uuid, device_uuid, schema_version, counts); idempotent per client_uuid, replay by batch_uuid, 409 on a different row set; response accepted, rejected(reason_code, retryable), conflicts, parked, server_totals, day_states, hold_s; headers X-Config-Version, X-Server-Generation; at most 500 rows, 1 MiB compressed; 429 with Retry-After on a storm. Replaces the per-type arrays of docs/09 (D-60). | QUE | all field tables, sync_batch, ingest_registry | L:* | F-SYS-008 | 1b | T-1-25..34, 51..52 | changed |
| F-API-007 | POST /media/upload (multipart fallback) | all-app | Small non-evidence files only; evidence photos use direct upload by SAS (F-API-057); idempotent by (client_uuid, purpose). | QUE | media_object | - | F-SYS-010 | 2a | T-2-26, 30, 53, 72 | changed |
| F-API-008 | POST /day/sales-submit | SR, AMO | {routeId or supervisor, businessDate}; the device path is the day_submit record at the end of the batch; this endpoint serves an online retry and the web; idempotent; returns the dues-warning payload; the server defers the sales_submitted transition until its totals reach the claimed device counts (30 min timeout). | QUE | route_day, supervisor_day | route_day | F-SR-035, F-AMO-030 | 2e | T-2-31..35, 47..49, 53..57 | changed |
| F-API-009 | POST /day/final-submit | TSO, web | {zoneId, businessDate, clientUuid}; online-only; once per zone per day by primary key; the same client_uuid returns the first success, another uuid gets 409 with the same Bangla text; delegated and auto kinds. | ONL | final_submit | route_day | F-TSO-010, F-WEB-051 | 3b | T-3-25..28, 42 | parity |
| F-API-010 | GET /outlets | web, app | Scoped, paginated, updated_since, PII-gated. | ONL | - | outlet | F-WEB-002 | 1a | T-1-20..24, 35, 41 | parity |
| F-API-011 | GET /routes | web, app | Scoped routes and assignments. | ONL | - | route, route_assignment | F-WEB-010 | 1a | T-1-20..24, 35, 41 | parity |
| F-API-012 | GET /targets | web, app | Scoped targets at variant, brand, category or SKU level. | ONL | - | target | F-TSO-017, F-SR-056 | 1a | T-1-20..24, 35, 41 | parity |
| F-API-013 | GET /leaderboard?view=&level=&productLevel= | web | Achievement ranking from rollups. | ONL | - | dw.agg_month_zone_product | F-WEB-036 | 4a | T-4-41, 51..52 | parity |
| F-API-014 | GET /dashboard/national?date= | web | Scoped dashboard rollups with date-range filter; under 1 s; cache per scope hash for 30 s; as-of stamp. | ONL | - | dw.* | F-WEB-001 | 1c | T-1-01..04, 60..69 | parity |
| F-API-015 | GET /dashboard/daily-tracking?date= and POST .../action | web | Buckets, exception bucket and take-action note. | ONL | tracking_action | dw.agg_daily_route | F-WEB-038, F-WEB-039 | 4a | T-4-41, 51..52 | parity |
| F-API-016 | GET /dashboard/live?zone= | AMO, TSO app | Zone and territory tiles from the same aggregates as the web; as-of stamp; explicit load. | ONL | - | dw.* | F-AMO-020, F-TSO-002..007 | 3a | T-3-20..24, 41 | parity |
| F-API-017 | GET /reports/<name>?filters&format=json/xlsx/pdf/print (the full ReportQuery registry; the early read halves are F-API-017a and F-API-017b, D-533) | web, AMO | One handler per report on a ReportQuery object; xlsx, pdf and print are formatters; asynchronous export for large ranges; logged export; sanitiser. | ONL | report_export_log | dw.* | F-WEB-011..037, F-WEB-053..062 | 4b | T-4-42..46 | changed |
| F-API-018 | GET /app/home?role=&date= | all-app | KPI strip and tiles scoped to SR, zone or territory; reconciles local KPI after sync; TSO login snapshot. | CAC | - | dw.* | F-SR-010, F-AMO-002, F-TSO-027 | 1c | T-1-01..04, 60..69 | parity |
| F-API-019 | GET /outlets/nearby?lat&lng&radius | TSO | Periphery query on a geo index; radius from the configured list; cluster cap. | ONL | - | outlet | F-TSO-012 | 3b | T-3-25..28, 42 | parity |
| F-API-020 | GET and POST /visit-plans | TSO | Set and list visit plans; union by outlet; idempotent by client uuid. | QUE | visit_plan | visit_plan | F-TSO-013, F-TSO-014 | 3b | T-3-25..28, 42 | parity |
| F-API-020b | GET /routes/:id/assignments | web | Route to SR or AMO assignment (SR Not Set is normal for AMO routes). | ONL | - | route_assignment | F-WEB-010 | 4b | T-4-42..46 | parity |
| F-API-021 | POST /targets/*, /target-revisions/* (the write half, 5c; the read half is F-API-021a, the Astha write half F-API-021b, D-533) | web, app | Targets, target sets, approval events, Astha targets and gift choice; at least 0. | ONL | target_set, astha_* | target | F-WEB-029, F-WEB-030, F-ADM-014 | 5c | T-5-43 | changed |
| F-API-022 | GET and POST /leave, POST /leave/:id/decision | TSO, DMO | Apply (queued with client uuid), list, decide. | QUE | leave_application | leave_application | F-TSO-008, F-TSO-009, F-WEB-046 | 3b | T-3-155 | parity |
| F-API-023 | GET /team/locations?zone= | AMO, TSO | Last synced fix per SR with age and source; no continuous tracking. | HYB | - | user_last_fix | F-AMO-016, F-TSO-011 | 3a | T-3-20..24, 41 | changed |
| F-API-024 | GET /team/stock?zone=&date= | AMO | SR lifted stock; SR phone not returned. | ONL | - | stock_movement | F-AMO-021 | 3a | T-3-20..24, 41 | parity |
| F-API-025 | GET /memos?outlet=&date= | SR, AMO | Previous sale data beyond the local window; scoped. | HYB | - | memo, memo_line | F-SR-054, F-AMO-013 | 2b | T-2-21..24, 43 | parity |
| F-API-026 | GET and POST /tasks, POST /tasks/:uuid/resolve | AMO, TSO, SR | Assign, list, resolve (also through the batch); idempotent by client_uuid. | QUE | task | task | F-AMO-019, F-TSO-016, F-SR-047 | 3b | T-3-155, T-5-43 | parity |
| F-API-027 | GET /tutorials | all | Video and manual list. | CAC | - | tutorial_video | F-SR-048, F-WEB-035 | 2e | T-2-134 | parity |
| F-API-028 | GET /ops/sync-health, GET and POST /ops/quarantine | admin | Sync health and quarantine actions. | ONL | sync_rejected | route_log, sync_batch | F-SYS-026, F-ADM-030 | 1c | T-1-01..04, 60..69 | new |
| F-API-029 | GET /app/update-check?version=&role=&abi= | all-app | Latest release, min version, APK URL, SHA-256; wave-aware. | ONL | - | app_release | F-SYS-020 | 2e | T-2-31..35, 47..49, 53..57 | changed |
| F-API-030 | POST /support/pda-upload | all-app | SAS to Blob; size cap. | ONF | support_upload | - | F-SYS-021 | 2e | T-2-31..35, 47..49, 53..57 | parity |
| F-API-031 | POST /auth/logout | all | Revokes the full refresh grant at once; with `scope=upload` (after the last outbox ACK) it also revokes the upload-only grant (D-471); TSO wipe is client-side after reconciliation. | ONF | refresh_token | - | F-SYS-022 | 2e | T-2-31..35, 47..49, 53..57 | parity |
| F-API-032 | POST /feedback, GET /admin/feedback | TSO, admin | Feedback with optional image link. | QUE | feedback | feedback | F-TSO-018, F-ADM-028 | 3b | T-3-25..28, 42 | parity |
| F-API-033 | /admin/migration/* | admin | Import run, reconcile, waves, compare, export, credential batch. | ONL | import_*, rollout_wave | - | F-SYS-038..043 | 7a | T-7-80..82 | new |
| F-API-034 | GET /reports/suspicious-locations | web, AMO, TSO | Flags from F-SYS-013 and review events. | ONL | risk_review | risk_signal | F-WEB-044, F-AMO-038 | 2d | T-2-10..19, 60..69 | new |
| F-API-035 | /admin/* CRUD of all 58 entities (6a; the early halves are F-API-035a, 035b, 035c by entity group, D-533) | admin | Master data (geography, products, prices, sales plan, routes, assignments, users, scope, outlets, approvals, classifications, offers, surveys, rubrics, task types, tutorials, releases); every write audited; optimistic concurrency. | ONL | master tables, audit_log | master tables | F-ADM-* | 6a | T-6-41 | changed |
| F-API-036 | POST /admin/device-otp and GET /admin/device-otp | TSO, admin | View, re-issue and bulk pre-issue; AES-GCM stored; scoped. | ONL | device_otp | device_otp | F-ADM-022, F-ADM-069 | 0c | T-0-70..76 | changed |
| F-API-037 | GET and PUT /admin/config, GET /config/snapshot | admin, all-app | cfg values with scope, effective date, reason and risk class; version stamp. | ONL | cfg.config_value, audit | cfg.* | F-ADM-012, F-ADM-013 | 1c | T-1-01..04, 60..69 | changed |
| F-API-038 | POST /admin/data-entry | admin, support | Manual backfill through the same ingest (source=manual). | ONL | field tables | - | F-ADM-024 | 4c | T-4-61, T-4-65 | new |
| F-API-039 | GET /day/final-submit/preview?zoneId= | TSO, web | Scope-checked: salesDate, alreadySubmitted, submittedAt, routes with FF name or null. | ONL | - | final_submit, route_day | F-TSO-021 | 3b | T-3-25..28, 42 | new |
| F-API-040 | GET /config/delta | all-app | Config changes since a version, on the next natural request; ETag. | ONF | - | cfg.config_value | F-SYS-053 | 1c | T-1-01..04, 60..69 | new |
| F-API-041 | POST /config/ack | all-app | Ack of the applied config version, carried on the next sync or bundle request. | QUE | cfg.config_ack | - | F-SYS-053 | 1c | T-1-01..04, 60..69 | new |
| F-API-042 | GET /config/public | unauthenticated | Front Door cached 60 s: min_version, banner, helpdesk number. | ONF | - | cfg.* | F-SYS-053, F-SYS-054 | 1c | T-1-01..04, 60..69 | new |
| F-API-043 | POST /day/cover | AMO, TSO, web | Same-day cover for a route (route, user in zone, dates up to 7 days); bumps scope_version. | QUE | route_assignment | route | F-AMO-037, F-ADM-062 | 2e | T-2-31..35, T-2-47 | new |
| F-API-044 | GET /day/exceptions | AMO, TSO, web | Pending and approved day exceptions for the scope. | ONL | - | day_exception | F-WEB-065, F-AMO-045 | 2e | T-2-31..35, 47..49, 53..57 | new |
| F-API-045 | POST /outlets/outlet-kind/bulk | TSO | {batchUuid, outletIds[], kind, reason}; idempotent by batchUuid; one audit row per outlet. | ONL | outlet_class_history | outlet | F-ADM-056 | 6a | T-6-41 | new |
| F-API-046 | POST /support/ping | support, API | On-demand device state request answered on the device's next request. | ONL | device_state | - | F-SYS-050 | 2e | T-2-31..35, 47..49, 53..57 | new |
| F-API-047 | POST /day/exceptions | SR, AMO, TSO | Raise, approve or reject a day exception (also a record in the batch from the app). | QUE | day_exception | route | F-SR-059, F-AMO-045 | 2e | T-2-31..35, 47..49, 53..57 | new |
| F-API-048 | POST /admin/data-void | TSO, admin | Audited void with reason and scope; tombstones the client uuids; refuses after Final Submit. | ONL | data_void, ingest_registry | route_day | F-ADM-058, F-WEB-051 | 4c | T-4-62 | new |
| F-API-049 | POST /admin/entry-unlock | finance_admin, support | Create or expire an entry_unlock_grant. | ONL | entry_unlock_grant | zone | F-ADM-057 | 4c | T-4-63 | new |
| F-API-050 | POST /web-entry/route-day | TSO | Web Entry submission with a client uuid; one entry per route-day, re-save replaces with audit. | ONL | web_entry_route_day, web_entry_line | route, sku | F-WEB-050 | 4c | T-4-61 | new |
| F-API-051 | POST /web-entry/outlet-sku | TSO | Astha Web Entry submission with a client uuid. | ONL | web_entry_outlet_sku | outlet, sku | F-WEB-048 | 5a | T-5-10, 41 | new |
| F-API-052 | POST /web-entry/qc | TSO | Market and warehouse QC entry with a client uuid. | ONL | qc_summary_entry | sku | F-WEB-052, F-WEB-060 | 4c | T-4-64 | new |
| F-API-053 | GET /reports/<name>/print and POST /reports/export | web | Server-rendered print view (HTML or PDF) and asynchronous export job with a download link; both logged. | ONL | report_export_log | dw.* | F-WEB-053, F-SYS-064 | 4b | T-4-42..46 | new |
| F-API-054 | POST /targets/upload and GET /targets/sample | TSO | Excel upload with all-or-nothing validation and an error sheet; sample workbook download. | ONL | target_set, media_object | route, variant | F-ADM-014, F-ADM-059 | 5c | T-5-43 | new |
| F-API-055 | POST /outlet-requests/:uuid/verify, /reject, /approve | AMO app, web | Verify (app or web), reject with reason and approve (web only), idempotent by event uuid; approving a closure sets status closed. | QUE | outlet_change_request, outlet | outlet_change_request | F-AMO-022..024, F-WEB-032 | 3a | T-3-20..24, 41 | new |
| F-API-056 | POST /admin/attribution | TSO, finance_admin | Re-attribution event for a route-day (applied at aggregation). | ONL | attribution_event | route_day | F-ADM-063 | 3a | T-3-20..24, 41 | new |
| F-API-057 | POST /media/sas | all-app | Accepts up to 10 items per call; user-delegation SAS pinned to photos/{business_date}/{device_uuid}/{client_uuid}.jpg, write-only, 15 minutes; the record syncs first and the photo follows. | ONF | media_object | - | F-SYS-010 | 2a | T-2-26, 30, 53, 72 | new |
| F-API-058 | GET /admin/config/whatif?key=&scope=&value=&days= | F editor, TSO own scope | Re-evaluates stored fixes under a candidate radius and reports how many visits would change verdict (doc 19 s6); read-only, scope-checked. | ONL | - | dw.fact_visit, dw.agg_outlet_density | F-ADM-039 | 2d | T-2-66 | new |
| F-API-059 | GET /admin/config/blast-radius?scope_type=&scope_id= | cfg.view | Zones, routes, outlets, users, devices and overrides a change at that scope would touch; its counts equal the devices targeted at commit. | ONL | - | cfg.config_value, app.device | F-ADM-038 | 2d | T-2-65 | new |
| F-API-060 | GET /admin/config/density?scope= and GET /admin/config/calibration?scope= | F editor | Outlet density index and the calibration report built from the pilot fixes (docs/22 P-10). | ONL | - | dw.agg_outlet_density, dw.fact_visit | F-ADM-039 | 2d | T-2-67 | new |
| F-API-061 | POST /admin/config/requests, GET .../requests?status=, POST .../{id}/approve, reject, cancel, adopt, break-glass | per risk class | Change-request workflow: submit, approve (two-person for C3), cancel, adopt a TSO proposal, restrictive-only break-glass; every transition audited. | ONL | cfg.config_change_request, cfg.config_change_audit | cfg.config_change_request | F-ADM-042 | 2d | T-2-60, T-2-68 | new |
| F-API-062 | GET /admin/config/versions, GET .../versions/{v}, POST .../versions/{v}/revert, POST .../rollback-to/{v} | editor; approvals by class | History, one-click revert and rollback-to-version; each produces a new version and deletes nothing. | ONL | cfg.config_value, cfg.config_version | cfg.config_version | F-ADM-043 | 2d | T-2-64, T-6-61 | new |
| F-API-063 | GET /admin/config/reach/{version} and GET .../reach/{version}/pending?zone= | cfg.view | Delivery view: targeted, applied, pending devices and how far behind each is. | ONL | - | cfg.config_ack, cfg.config_version_scope | F-ADM-044 | 1c | T-1-65 | new |
| F-API-064 | GET and POST /admin/permissions | security_admin | Admin permission roster; a grant is a C3 request of type grant (D-91). | ONL | cfg.admin_grant, audit_log | cfg.permission_bundle | F-ADM-038 | 1c | T-1-65 | new |
| F-API-065 | GET and PUT /admin/flags | release_mgr | Feature-flag matrix by scope and wave; flags never change the shape of captured data (D-99). | ONL | cfg.config_value | cfg.config_item | F-ADM-055 | 6c | T-6-43, T-7-62 | new |
| F-API-066 | POST /support/decrypt | support, MFA and a ticket | Decrypts a PDA to Support bundle through Key Vault unwrap; plaintext is streamed, never written to disk; every call is audited (D-480). | ONL | audit_log | media_object | F-SYS-021 | 2e | T-2-78 | new |
| F-API-067 | POST /admin/binds/cohort-ack (path proposed, ASSUMPTION) | DMO, security_admin | Acknowledges a wave cohort so binds by TSO-issued temporary passwords are not held by the takeover rule (D-486). | ONL | device_otp, audit_log | device | F-TSO-022 | 2e | T-3-79 | new |
| F-API-068 | POST /admin/binds/{id}/release (path proposed, ASSUMPTION) | DMO, security_admin | Releases or rejects a bind held by the takeover chain (D-112); the SR is notified. | ONL | device, audit_log | device | F-TSO-023 | 2e | T-3-79 | new |
| F-API-017a | GET /reports/memo-number-gaps | web | The memo-number gap report as its own read endpoint, so F-WEB-064 (2e) does not wait for the full registry of F-API-017 (4b). Scope from the token; rows from dw. | ONL | - | dw.fact_memo | F-WEB-064 | 2e | T-2-31..35, T-2-47 | new |
| F-API-017b | GET /reports/std-memo, /reports/sales-summary (AMO app reports) | AMO | The two AMO Report-tile reads (F-AMO-032, F-AMO-033) before the web registry exists; same ReportQuery handler class, a subset of reports; AMO scope from the token. | ONL | - | dw.agg_daily_route_sku, agg_daily_route | F-AMO-032, F-AMO-033 | 3a | T-3-20..24, 41 | new |
| F-API-021a | GET /targets/*, achievement and Astha reads | web, app | The read half of F-API-021: targets, achievement, till-date bases and Astha reads for the SR home strip (F-SYS-034), Team Performance (F-AMO-017), Target Status (F-TSO-017) and the Astha screens. Seed or imported targets until 5c writes exist; at least 0; dash on zero. | ONL | - | target, dw.agg_month_* | F-SYS-034, F-AMO-017, F-TSO-017, F-SR-041, F-AMO-031 | 1c | T-1-01..03, T-3-20..24 | new |
| F-API-021b | POST /programs/astha/* (Astha targets and gift choice) | TSO, admin | The Astha write half: per-outlet per-brand targets, gift choice with the lock when the photo exists (D-192); idempotent by client uuid. | ONL | astha_*, gift_assignment | astha_* | F-TSO-020, F-ADM-018 | 5a | T-5-10, 41, 62 | new |
| F-API-035a | /admin/* CRUD: offers, surveys, rubrics, content, programme definitions | admin | The early entity group of F-API-035: promotion groups, DRP, survey and rubric definitions, content, Astha, Diamond League and Superstar setup, gift catalogue; every write audited. | ONL | master tables, audit_log | master tables | F-ADM-016, F-ADM-017, F-ADM-019, F-ADM-020, F-ADM-021, F-ADM-035, F-ADM-045, F-ADM-061 | 2a | T-2-36..44 | new |
| F-API-035b | /admin/* CRUD: users, scope, routes, assignments, outlets, classifications, approvals (pilot minimum) | admin, TSO | The second entity group: what the pilot needs from P9 (users, routes, assignments, outlets, classifications) and the outlet detail and approval reads; every write audited. Round 3 (D-590): also the day-one tools in minimal form: the sales plan of the pilot and wave zones (F-ADM-006), the dues adjustment (F-ADM-036), outlet reactivation (F-ADM-070) and SR transfer (F-ADM-071). | ONL | master tables, audit_log | master tables | F-ADM-046, F-ADM-063, F-ADM-037, F-WEB-003, F-WEB-032, F-ADM-006, F-ADM-036, F-ADM-070, F-ADM-071 | 2e | T-2-31..35 | new |
| F-API-035c | /admin/* CRUD: products, prices, product tree | admin | The third entity group: the six product pages of the web (F-WEB-004 to F-WEB-009) and the price lists. | ONL | master tables, audit_log | master tables | F-WEB-004 to F-WEB-009 | 4b | T-4-42..46 | new |
| F-API-069 | POST /day/submit-void | TSO, support_l1 (C), support_l2, ops_admin | Voids the effective Sales Submit of one route-day with a reason and the confirmer; clears `sales_submitted_at`, bumps `submit_seq`, the phone unlocks capture; refused for a final-submitted zone until reopened; idempotent by request uuid (D-539). | ONL | app.submit_void_event, route_day | route_day | F-ADM-074, F-TSO-030, F-SR-080 | 3b | T-3-150, T-3-61 | new |
| F-API-070 | GET /sync/generation | device | Returns `{generation, kind, restore_point_utc, lost_after_utc, minted_at}` so the device re-sends every synced row acked at or after the restore point minus the safety margin (D-517). | ONF | - | cfg generation row | F-SYS-047, F-SYS-080 | 1b | T-1-56, T-1-152 | new |
| F-API-071 | GET /support/search, POST /support/decode | support_l1, support_l2, TSO, ops_admin | P19: search by username, phone, employee code, memo number, outlet code, `batch_uuid`; decode a support code (error, build, config version, device CRC); searches audited, PII masked (D-541). | ONL | audit_log | device, route_day, sync_batch, memo | F-ADM-072 | 2e | T-2-157, T-2-58 | new |
| F-API-072 | POST /calendar/emergency | ops_admin (L2 proposes) | Emergency non-working-day declaration (kind emergency_off) at wing, division, territory or zone scope, effective now, exempt from the freeze, second approver only if retroactive; re-enqueues the route-days (D-542). | ONL | cfg.holiday, route_day | route_day | F-ADM-073 | 2e | T-4-154 | new |
| F-API-073 | POST /entries/paper-memo, POST /entries/paper-memo/{id}/approve | support_l2, TSO, ops_admin | Supervised paper-memo backfill: enter by printed `memo_no`, approve by the zone TSO; deterministic uuid; `entry_source = manual` (D-543). | ONL | app.paper_backfill, memo, memo_line, due_collection | memo | F-ADM-075 | 2e | T-2-151 | new |
| F-API-074 | POST /outlet-requests/bulk-approve | AMO, TSO | Approves all location proposals that agree within `cfg.outlet.bulk_approve_consistent_m` with at least `cfg.outlet.bulk_approve_min_evidence` independent visits; never for a move above the alert distance or a remote verification (D-545). | ONL | outlet_change_request, outlet_change_event | outlet_change_evidence | F-AMO-048, F-TSO-031 | 3a | T-3-11, T-4-152 | new |
| F-API-075 | POST /admin/config/emergency-widen, POST /admin/config/temporary-relief | break-glass holder, two approvers | The two bounded loosening lanes of doc 19 s7.6b (D-527). | ONL | cfg.config_value, audit | cfg | F-ADM-077 | 2d | T-2-160, T-2-68 | new |
| F-API-076 | POST /day/mark-absent/bulk | AMO | Bulk absence for several routes of the AMO's zone as day exceptions, up to `cfg.day.bulk_absent_max_routes`; the TSO approves (D-551). | ONL | day_exception | route_day | F-AMO-047 | 3a | T-3-20..24 | new |
| F-API-077 | POST /admin/support/replay/dry-run, apply, approve | support_l2, TSO, ops_admin | The replay console of F-ADM-080: dry run (read-only verdict counts), approval, apply through ingest under the original user's identity; idempotent by `client_uuid`. | ONL | app.* via ingest, audit_log | app.ingest_registry | F-ADM-080 | 2e | T-2-168 | new |
| F-API-078 | GET /admin/binds/held | DMO, security_admin, delegates, L1 and L2 (read) | The held-binds queue with age, reason and the old device's pending rows; release and reject are F-API-068. | ONL | - | device | F-ADM-079 | 2e | T-2-167 | new |
| F-API-079 | POST /admin/devices/{id}/directive, GET /admin/devices/{id}/directives | support, TSO, ops_admin | Creates and lists signed per-device directives; the directive also travels in responses (doc 17 s4.4). | ONL | app.device_directive, audit_log | app.device_directive | F-ADM-085, F-SYS-095 | 2e | T-2-169 | new |
| F-API-080 | POST /admin/price/preview, publish, correct | finance_admin, finance_approver | Price rails: preview, publish with the percent guard, same-day correction; idempotent by `batchUuid`. | ONL | sku_price, audit_log | sku_price, memo_line | F-ADM-081, F-ADM-005 | 2e | T-2-166 | new |
| F-API-081 | POST /admin/bulk/{batchUuid}/revert | master_data, ops_admin | Revert a bulk batch from its audit before-values; new `batchUuid`. | ONL | master tables, audit_log | audit_log | F-ADM-082 | 6a | T-6-150 | new |
| F-API-082 | POST /admin/retention-holds, GET and DELETE /admin/retention-holds/{id} | security_admin, pii_officer | Place, list and lift a retention hold; audited. | ONL | app.retention_hold, audit_log | app.retention_hold | F-ADM-083, F-SYS-093 | 6c | T-6-152 | new |
| F-API-083 | GET /config/check | device | The resume check: `If-None-Match: <X-Config-Version>`; 304 or the delta inline; at most once per `cfg.sync.config_check_min_gap_min`. | ONF | - | cfg config version | F-SYS-092, F-SR-019 | 2d | T-2-165 | new |
| F-API-084 | POST /support/tickets, PATCH /support/tickets/{id}, GET /support/tickets | support, TSO | The minimal ticket store and contact log of P19. | ONL | app.support_ticket | app.support_ticket | F-ADM-084 | 2e | T-2-170 | new |
| F-API-085 | POST /admin/migration/apsis-feed | importer | Loads the nightly Apsis route-day feed (DL-6) for unswitched routes and, as `apsis_residual`, for switched ones. | ONL | stg.*, dw.agg_daily_route | - | F-SYS-088 | 7b | T-7-152, T-7-164 | new |
| F-API-086 | POST /admin/devices/{id}/replace | TSO, support_l2, ops_admin | Steps of the replace-device wizard (upload first or revoke now, OTP, checker approval, bind). | ONL | device, device_otp, audit_log | device | F-ADM-078 | 2e | T-2-167 | new |

Proved by: T-1-20..34 and T-1-51..52 (batch, replay, poison rows), T-0-70..76 (auth, scope-leak harness), T-1-60..69 (config endpoints), T-2-53, T-2-72 (media SAS), T-3-25..28 (final-submit preview), T-4-42..46 (report handlers).

## 9 Rules now stated

### 9.1 Rules by area

The register and the verification files turned the manuals' observed behaviour into rules the spec lacked. Class: PARITY reproduces observed behaviour; IMPROVEMENT is not observed; DELIBERATE CHANGE differs from observed behaviour and carries its D-id; ASSUMPTION is our call; DEFAULT is the proceed-with value. Where the manual shows nothing, the row says unknown or confirm.

| Area | Rule | Class | Decision | cfg keys |
| --- | --- | --- | --- | --- |
| Sale | Cigarette and bidi quantity is entered, stored and shown in sticks; lighter in pieces; match in dozens; every match and lighter cell shows its unit; the stepper step is the pack size; loose sticks allowed or not is unknown, confirm (MQ-02) | PARITY | D-16, D-17, D-157 | cfg.sale.qty_entry_unit |
| Sale | Price type is an outlet attribute resolved on the server and carried in the bundle; the SR never chooses; both the outlet and cc lists are in the bundle | IMPROVEMENT | D-32 | cfg.sale.allow_price_type_override |
| Sale | Net = gross - offer discount - DRP (slide) discount - QC settlement; quantity totals are not reduced by slide or QC; every non-zero component is shown and printed | PARITY | D-18, D-158 | cfg.memo.allow_negative_net |
| Sale | Totals are summed from unrounded line values and rounded once to the paisa; round_adj_mtk is stored; the mode is future-dated only | PARITY | D-19 | cfg.memo.rounding_mode |
| Sale | Draft memo and visit are written at "এগিয়ে যান"; Print shows "আপনি কি নিশ্চিত? / বিক্রয় জমা হবে" and a yes commits the immutable record before and regardless of printing; the print question "না" leaves printed_at null | PARITY | D-77 | cfg.sale.require_printer_before_sale |
| Sale | QC and the credit checkbox are independent in any order; QC is optional | PARITY | D-203 | - |
| Sale | After the geo gate the prompt "আপনি কি কল শুরু করতে চান?" appears; না returns to the list and is not counted visited | PARITY | D-78, D-160 | cfg.sale.call_start_prompt |
| Sale | A zero sale writes a memo row with line_count 0, consumes a number, counts as a visit and a no-sale, not as a memo or a successful call; one outcome reason is asked | PARITY | D-36, D-38, D-202 | cfg.visit.outcome_codes |
| Sale | A memo holds at most 60 lines; a very large line is a soft ceiling with an anomaly flag, never a hard cap (26,683 lines are 10,000 sticks or more) | IMPROVEMENT | D-246, D-260 | cfg.sale.max_lines_per_memo, cfg.sale.max_line_qty_base |
| Sale | Quantity above current stock warns and allows (a top-up may be unrecorded); the movement is flagged stock_negative for review | IMPROVEMENT | D-321 | cfg.sale.stock_check |
| Sale | A second visit to one outlet on one day is allowed after a zero sale; no design assumes one memo per outlet-day | IMPROVEMENT | D-250 | cfg.day.multi_visit_same_outlet_policy |
| Sale | An outlet photo is taken only inside the Force Sale and Manual Override chain; no every-call photo is evidenced | PARITY | D-347 | cfg.sale.outlet_photo_every_call |
| Sale | Offers auto-apply as offer_discount; the DRP reward is a separate deduction; discount lines are stored as (sku, qty, value, kind); where an automatic discount renders on Review is unknown, confirm (MQ-06) | PARITY | D-33, D-166, D-343 | cfg.promo.rules |
| Credit and dues | The credit checkbox opens the collected-amount dialog: amount at least 0 and strictly less than the grand total, 2 decimals, due shown to the paisa in the dialog and in the checkbox label (the manual truncates 61.50 to 61) | PARITY | D-213 | cfg.credit.allow_zero_payment |
| Credit and dues | One credit label "বাকি"; "ক্রেডিট" is retired | PARITY | D-212 | - |
| Credit and dues | No credit limit, eligibility rule or maximum age exists in Apsis; default is no limit and an ageing report | PARITY | D-320 | cfg.credit.max_due_mtk, cfg.credit.max_days |
| Credit and dues | Mark as paid settles the whole memo; a due_collection row (amount = remaining) is always written | PARITY | D-37, D-161 | cfg.credit.allow_partial_collection |
| Credit and dues | Partial later collection is an off-by-default enhancement; FIFO allocation is used for ageing reports regardless of the retailer's choice | IMPROVEMENT | D-37 | cfg.credit.collection_partial_roles, cfg.kpi.dues_buckets |
| Credit and dues | An AMO's due label and the dues count at Sales Submit cover the AMO's own credit memos only | DEFAULT | D-37 | - |
| Credit and dues | Sales Submit warns about retailers still owing (dynamic count) and never blocks; submitted_with_dues is recorded | PARITY | D-173, D-216 | cfg.day.sales_submit_dues_warning |
| Credit and dues | A printed previous-due line older than today carries a staleness marker; a collection against an old memo gets a receipt | IMPROVEMENT | G-field-09 | cfg.memo.due_balance_staleness_marker, cfg.print.due_receipt |
| Credit and dues | Closing an outlet with open dues warns by default; the outlet is closed_with_dues until finance clears | IMPROVEMENT | D-25 | cfg.outlet.close_block_if_dues, cfg.outlet.close_block_if_dues |
| QC | QC is a money deduction: defect sticks x price at capture (inferred from 10 x 8.00 = 80.00); the maximum QC is a taka cap per SKU shown with done and remaining; its basis is unknown (594.50 is not 10 x 8.00), confirm (MQ-03, MQ-04) | PARITY | D-34 | cfg.qc.max_amount_basis |
| QC | Six app fault types in two groups (production, transport); expired stock is "4 months+" and the threshold is config; web Market and Warehouse QC have their own 10 labels in the same code table and are a separate source | PARITY | D-34, D-159, D-183 | cfg.qc.fault_types, cfg.qc.expired_stock_months |
| QC | Completing QC at an outlet locks edits at that outlet (outlet level, not per memo; the day scope of the lock is unconfirmed) | PARITY | D-201 | - |
| Slide and DRP | The slide screen lists only SKUs with an active offer; the offer dialog shows text and validity; quantity by stepper or manual dialog with shortcut steps; fixture: 10 empty MaxR-10S packets give 1 reward pack of 80.00 shown as a deduction | PARITY | D-33 | cfg.drp.shortcut_steps |
| Slide and DRP | The reward unit is a memo line with is_free and offer_id so volume and stock include it (confirm); behaviour outside the validity window is unknown | ASSUMPTION | D-18, D-33 | - |
| Stock | Issue = quantity received today (replace or add on re-save); Stock = opening + issues - sold - returned, computed locally; a second load the same day is a second movement | PARITY | D-56 | cfg.stock.max_issue_qty |
| Stock | Save always works offline and never waits for the printer; the slip is stock_slip_printed false until printed and Sales Submit warns (a supervisor override exists) | IMPROVEMENT | D-76 | cfg.stock.require_printed_slip |
| Stock | Day-end return is an event (unsold, damaged, short) with the distributor count, variance reason and confirmed_by (DH login or AMO proxy, Q41) | IMPROVEMENT | D-324 | cfg.stock.* |
| Stock | Cash hand-over is an event (declared, counted, variance, counted_by); a shortfall is a business matter and never blocks Sales Submit | IMPROVEMENT | D-325 | cfg.stock.* |
| Edit and void | Edit is allowed only inside the outlet geofence, before QC at that outlet, with one of three reasons (first "ভুল SKU নির্বাচিত।"); a new memo supersedes the old; the server re-checks | PARITY | D-200, D-201 | cfg.memo.edit_reasons, cfg.memo.edit_roles |
| Edit and void | Edit and void are allowed on the same business date and before Sales Submit; chain depth at most 3; a void is an event with reason, due reversal, stock back and a cancel slip | IMPROVEMENT | D-86 | cfg.memo.void_reasons, cfg.memo.print_void_slip |
| Edit and void | A reprint marks the paper as a duplicate; a reprint after a "না" print confirmation does not; the count is limited | IMPROVEMENT | D-322, D-76 | cfg.memo.reprint_watermark, cfg.memo.reprint_max |
| Outlet requests | New, close and info forms pick a Cluster; the route is derived server-side; both route_id and cluster_id are stored; AMO verification forms show both | PARITY | D-162 | cfg.outlet.* |
| Outlet requests | Lifecycle pending, verified (AMO app Save or web Verify), then approved or rejected on the web only; AMO "বাতিল" discards the form with no server call (inference, confirm); a rejected request shows its reason to the SR | PARITY | D-43, D-172, D-230 | cfg.outlet.verify_roles, cfg.outlet.approve_roles, cfg.outlet.reject_requires_reason |
| Outlet requests | Sub-Channel and Geo Classification are required at AMO verification of a new outlet; GEO and photo required for new shop and info change | IMPROVEMENT | G-man-035, D-43 | cfg.outlet.verify_requires_subchannel, cfg.outlet.verify_requires_geo_class |
| Outlet requests | Approving a closure sets status closed; approving a new outlet assigns the code; outlets are never deleted | PARITY | D-25, D-43, D-186 | - |
| Outlet requests | A photo at Force Sale or Manual Override raises a location-change request and the call proceeds with a provisional flagged location; only a missing or placeholder location is set at once | DELIBERATE CHANGE | D-95, D-163 | cfg.geo.outlet_location_change_approval, cfg.geo.first_capture_sets_location |
| Outlet requests | Update Base needs the AMO within 100 m of the chosen point, a move limit, no mocked fix and a monthly cap | DELIBERATE CHANGE | D-95, D-111 | cfg.geo.update_base_max_distance_m, cfg.geo.update_base_max_move_m |
| Outlet requests | A shop captured today may be sold to today with a provisional outlet re-linked on approval | IMPROVEMENT | D-326 | cfg.outlet.sell_before_approval |
| Tasks, leave, plans | Task statuses চলমান and সম্পন্ন map to the enum; resolved tasks stay on the SR list; Resolve works offline; the badge counts open tasks; assignee is the outlet's route SR on the due date and "SR Not Set" blocks creation | PARITY | D-167, D-328 | cfg.task.statuses, cfg.task.types |
| Tasks, leave, plans | Leave captures one from_date and a typed integer day count of at least 1; to_date is derived for new rows; imported rows are never recomputed; no 30-day cap; overlap, past-day and balance checks exist off by default | PARITY | D-176, D-197, D-234 | cfg.leave.allow_past_days, cfg.leave.block_overlap, cfg.leave.balance_enforced |
| Tasks, leave, plans | A visit plan has no outlet cap by default, repeat Set Plan unions by outlet, an outlet is Completed when its Visit Query is submitted, no edit or delete of a plan | PARITY | D-175, D-327 | cfg.tso.visit_plan_max_outlets |
| Tasks, leave, plans | Visit Query is two free-text questions plus a built-in delegate radio defaulting to No; no GPS or photo | PARITY | D-199 | cfg.survey.tso_visit_query_questions |
| Tasks, leave, plans | Feedback categories are config; only "Suggestion" is evidenced; one image | PARITY | D-197 | cfg.feedback.categories, cfg.feedback.max_images |
| Final submit | One Final Submit per zone per day by primary key; the duplicate check fires at Get Sales Data through the preview endpoint; the same client_uuid returns the first success, another uuid gets 409 with the same text | PARITY | D-55, D-82, D-235, D-262 | - |
| Final submit | Routes with "FF: SR Not Set" and routes with no upload do not block; the app asks an explicit confirm before the irreversible submit | IMPROVEMENT | D-198 | cfg.day.final_submit_confirm, cfg.day.final_submit_allow_not_set_routes |
| Final submit | Sales Date is the server Dhaka business date and read-only in the app; no time gate is applied (by silence of the manual) | PARITY | D-198 | cfg.day.final_submit_earliest_time |
| Final submit | Late batches for a closed zone-day are accepted, aggregated into their business date and flagged after_final_submit; reopening is an audited admin action; delegation and auto-close are off until confirmed | IMPROVEMENT | D-55, D-262 | cfg.day.final_submit_delegate_roles, cfg.day.final_submit_autoclose_time |
| Programmes | Astha views: route STD table with Remaining and one "All Brand" memo row; outlet view without Remaining and one memo row in the AMO app but per brand in the SR app; zero month chips means the whole quarter; whether the memo target recomputes for chosen months is unknown | PARITY | D-168, D-231 | cfg.astha.memo_target_month_filter |
| Programmes | Diamond League redemption is a basket of lines in one confirm; cash back 2 Tk per point up to 199 points (cap scope unknown); tornado fan 400; the server computes points and accepts-and-flags an overdraw the device could not know about | PARITY | D-41, D-219, D-266 | cfg.loyalty.cash_rate_mtk_per_point, cfg.loyalty.cash_max_points |
| Programmes | The POSM Q1.1 photo earns +50 points posted by the server with source_id = the survey response uuid; never for an AMO survey; points expire by a nightly ledger row | PARITY | D-41 | cfg.loyalty.earning_rules, cfg.loyalty.expiry_days |
| Programmes | Astha gift hand-over: one photo per outlet, then "Photo already captured."; the gift is chosen on the web Astha Gift Choice panel and travels in the bundle | PARITY | D-192 | cfg.astha.gift_choice_lock |
| Programmes | Superstar: enrolment by admin per outlet per month, slabs as data, criteria job sets sales_criteria_met; rules unknown, confirm | ASSUMPTION | D-332 | cfg.superstar.* |
| Programmes | Free sample is an is_free memo line at price 0 with its own report; whether a free-sample-only memo is a successful call is unknown (default counts) | ASSUMPTION | D-46, D-333 | - |
| Targets and KPI display | Summary cards and bars cap the printed percent at 100; detail tables are uncapped with 2 decimals (1271.19); the SR home card is uncapped; remaining = max(target - achieved, 0) | PARITY | D-50, D-196 | cfg.kpi.card_pct_cap |
| Targets and KPI display | A target of 0 or below shows a dash (the manual prints 0.00 percent and -37,500.00 percent for -20); targets are at least 0 at entry and flagged at read | DELIBERATE CHANGE | D-50 | cfg.target.achievement_pct_cap |
| Targets and KPI display | Till-date basis is per surface: TSO ceil(item x 26/30), AMO team about 25/30, AMO report monthly x 15/17, SR ADS 14 elapsed and 11 remaining | PARITY | D-51, D-195 | cfg.kpi.tilldate_basis.<surface> |
| Targets and KPI display | Bar colours green from 80, amber from 40, red below 40 (the 40 boundary is a placeholder); the 4-band KPI set is separate | PARITY | D-52 | cfg.kpi.bar_bands, cfg.kpi.bands |
| Targets and KPI display | SR SKU screen: TADS = target / 14, RADS = remaining / 11 rounded, ADS = achieved / elapsed selling days, PADS undefined until a non-zero sample is captured | PARITY | D-58 | - |
| Targets and KPI display | Targets are variant level; every monthly set is a header needing approval (WMO seen); entry is manual route x variant or Excel upload; no automatic split | PARITY | D-31, D-178, D-179, D-189 | cfg.target.approval_levels, cfg.target.split_method |
| Targets and KPI display | Two submit figures with distinct names: "Submit % (of logged-in)" on TSO, AMO and web tiles and "Day-completion %" as the secondary; Login % = logged-in routes / target routes (1 of 4 = 25%) | PARITY | D-44, D-45, D-177 | cfg.kpi.submit_pct_denominator |
| Targets and KPI display | CPR (strike rate) = successful calls / target outlets (6/60 = 10%); AMO control-call sales never inflate it | PARITY | D-46, D-26, D-57 | - |
| Attendance and geo | Check-in is a prompt, not a gate, for Stock and Sale (config); no fix is allowed and stored as null; check-out is enabled at corrected Dhaka time 17:00 or later (inclusive) and a forgotten check-out never blocks Sales Submit | PARITY | D-209, D-225, D-329 | cfg.day.checkin_gate, cfg.day.checkout_earliest_time |
| Attendance and geo | Hold-to-confirm on check-in and check-out; the attendance address is online-only with coordinates as the offline fallback | PARITY | D-08, D-74 | cfg.app.hold_to_confirm_ms |
| Attendance and geo | One fused fix per event; radius resolved by outlet, zone, geo class, house, territory, division, wing; a mocked fix is never geo-valid; policy default warn_rep; a missing outlet location opens a force sale (no_outlet_location) | PARITY | D-74, D-87, D-93, D-95, D-96 | cfg.geo.radius_m, cfg.geo.mock_policy, cfg.geo.no_location_policy |
| Attendance and geo | Team Location shows the last synced fix with "last seen HH:MM (n min ago)" and its source, greyed after 120 min | DELIBERATE CHANGE | D-170 | cfg.tso.team_location_max_age_min |
| Device and session | The device OTP has 4 digits, a view-only TSO panel listing every SR of the zone; re-asking after an in-place update is off by default | PARITY | D-80, D-103, D-164 | cfg.auth.otp_length, cfg.auth.reverify_on_new_version |
| Device and session | SR and AMO logout keep the local database; TSO logout wipes only a reconciled device and is otherwise refused with the pending count | DELIBERATE CHANGE | D-69, D-174 | cfg.app.logout_block_when_pending |
| Device and session | Selling on a cached bundle up to 2 days old with a banner; older is read-only; a day_open event keeps Login % truthful | IMPROVEMENT | D-70, D-30 | cfg.bundle.stale_max_days |
| Device and session | Location denied blocks Sale and Attendance with a Bangla rationale; Bluetooth denied disables printing only; no microphone permission | PARITY | D-74, D-115 | cfg.geo.require_precise, cfg.app.location_denied_policy |
| Device and session | The updater is two stages of one flow; min_version blocks a new day only; an open offline day finishes | IMPROVEMENT | D-79, D-223 | cfg.release.min_version |
| Labels and format | Route label = name + " (" + visit days + ")"; outlet labels per screen; phones normalised to 11 digits and shown with the leading 0; an empty phone shows a dash | PARITY | D-214, D-242 | cfg.app.outlet_list_label_format, cfg.app.route_label_format, cfg.outlet.mobile_regex |
| Labels and format | Digit script follows the UI language including the printed memo (confirm the printed memo); stored data is ASCII | DEFAULT | D-344 | cfg.i18n.digit_script |
| Labels and format | The alphabet filter uses the first grapheme cluster, case-insensitive, with Bangla and Latin chips (the Apsis strip is case-sensitive: a bug) | DELIBERATE CHANGE | D-345 | cfg.app.alphabet_filter |
| Labels and format | Vendor strings ("Apsis", "Firefly Outlets Reports", "Developed by Apsis Solutions", the example password) are never reproduced | PARITY | D-244 | - |
| Labels and format | TSO default locale is English with a language switch added; SR and AMO default to Bangla | DEFAULT | D-181 | cfg.app.default_locale |
| Web | Web Entry, Astha Web Entry and web QC entry are separate sources with their own client uuids; web and app rows for one route-day are mutually exclusive by default and flagged if both exist, never added | IMPROVEMENT | D-40 | cfg.web.entry_enabled_zones |
| Web | Delete Section Data is an audited void with reason and tombstones, only before Final Submit, default scope web-entry rows | DELIBERATE CHANGE | D-22, D-40, D-182 | cfg.web.delete_section_data_scope |
| Web | Back-date window: today only by default, per-zone date and audited unlock grants instead of "call support" | IMPROVEMENT | D-97 | cfg.web.entry_backdate_days |
| Web | PII baseline: the TSO sees Address, NID, TIN and Trade Licence columns (null where blank or the placeholder 123); SR phones are hidden from the AMO; every export is logged | DEFAULT | D-108, D-207 | cfg.pii.field_roles |
| Web | Reports render on screen first; one export label "Get Excel"; single-date screens are single-date; the dashboard loads for today without a press and also offers a range Filter | DELIBERATE CHANGE | D-188, D-191, D-238, D-239 | cfg.report.* |
| Web | The menu is the union of the TSO menu (15 items, 41 pages) and the spec menu and is data per role | DEFAULT | D-185, D-243 | cfg.web.menu_by_role |

### 9.2 Underspecified rules in the original spec and the chosen default

The lens listed 39 features the spec names but under-specifies. Each now has a default; the ones still needing the business are MUST-CONFIRM in the Open items section.

| # | Feature | Missing rule | Chosen default | Decision | Feature ids |
| --- | --- | --- | --- | --- | --- |
| a1 | Offers auto-apply | Rule schema for about 22 groups | Offer master with trigger, effect, stacking, rounding, validity and scope; discount lines stored as (sku, qty, value, kind); the Q13 catalogue is a 2a entry condition | D-33, D-143, D-343 | F-SR-024, F-ADM-016 |
| a2 | Sale edit reasons | Three reason labels; edit after print; void of the old memo | First reason "ভুল SKU নির্বাচিত।", two to be captured; edit allowed after print under the geofence and QC rules; the superseded memo stays in history | D-200, D-201, D-86 | F-SR-033 |
| a3 | Credit sale | Who may grant credit, limits, days, overdue block | No limit, no overdue block (parity); ageing report; keys exist for a business limit | D-320 | F-SR-026 |
| a4 | Due collection | Allocation, partial across memos, receipt, collector | Whole-memo settlement; FIFO allocation for reporting; receipt IMPROVEMENT; collector recorded | D-37 | F-SR-032, F-SYS-060 |
| a5 | Zero sale | Memo row, print, counts | Printable zero memo row, consumes a number, counts as visit and no-sale, not a memo or a successful call | D-36 | F-SR-029 |
| a6 | KPI strip Non-visit and No-sale | Exact definitions | Non-visit = target outlets - visited; No-sale = zero-sale calls | D-56 | F-SR-010 |
| a7 | Check-out at 17:00 | Clock source, wrong clock, forgotten check-out | Corrected Dhaka time, inclusive; forgotten check-out never blocks; server closes it as "no check-out" | D-209, D-20, D-329 | F-SR-012 |
| a8 | Sales Submit | Offline, blocking, undo | ASSUMPTION: a sale captured after Sales Submit is accepted, flagged and settled; no undo; confirm with the business | D-64, D-173 | F-SR-035 |
| a9 | Final Submit | Not Set routes, late syncs, reopen | Not Set does not block; late batches accepted and flagged; reopen is an audited admin action | D-55, D-198 | F-TSO-010, F-ADM-029 |
| a10 | Login % and Submit % (of logged-in) | Holiday denominators, delta refresh | Calendar-aware denominators; a delta is not a login; two named Submit figures | D-28, D-30, D-44, D-45 | F-TSO-007 |
| a11 | Device OTP | TTL, re-bind, devices per user, shared phones | 4 digits, TTL 120 min, 5 attempts, 3 users per device and 2 devices per user | D-66, D-103 | F-SYS-003 |
| a12 | Force Sale photo updates location | Which flow approves, no-location case | Location-change request plus provisional flagged location; missing or placeholder location set at once | D-95, D-163 | F-SR-018 |
| a13 | Update Base | Request type, approver, offline tiles | Request type location, approver TSO for moves above 300 m, numeric fallback offline | D-95, D-111 | F-AMO-028 |
| a14 | Price compliance | Whole screen | Observed shelf price per SKU at a control call; AMO only; counted in the reconciliation row (ASSUMPTION) | D-334 | F-AMO-035, F-SR-061 |
| a15 | Joint call rubric | Items 4 and 5, scoring | Three known items plus two disabled placeholders as versioned data; star 1 pre-selected for parity (confirm) | D-349 | F-AMO-011 |
| a16 | AMO KPI tiles | Origin of control-call and joint-call targets | Supervisor target table by AMO and month (admin); unknown how Apsis sets it, confirm | D-349 | F-AMO-002, F-ADM-025 |
| a17 | Team Location live | Source and freshness | Last synced fix, age label, source, greyed after 120 min | D-170 | F-AMO-016, F-TSO-011 |
| a18 | Task Delegation | Statuses, overdue, reassign, evidence | Two statuses, overdue computed, reassign by route assignment, no evidence (parity) | D-328 | F-SR-046, F-AMO-019 |
| a19 | Diamond League | Earning rules, monthly reset, double-spend | Seed +50 POSM rule; full earning rules required before 5a; nightly expiry; accept-and-flag overdraw | D-41, D-266 | F-SR-043, F-SYS-061 |
| a20 | Astha gift choice | Surface, lock, one gift per quarter | Web Astha Gift Choice panel; explicit Save; lock when the SR photo exists; one gift per outlet per quarter | D-192 | F-TSO-020 |
| a21 | Superstar | Enrolment, slabs, criteria | Admin enrolment, slab data, criteria job; rules unknown | D-332 | F-ADM-019, F-WEB-037 |
| a22 | Free sample | Capture screen | is_free memo line at price 0 with its own report | D-333 | F-SR-062 |
| a23 | Target split and approvals | Formula, levels | No automatic split; manual or Excel; one approval level WMO, chain configurable | D-31, D-189 | F-ADM-014, F-ADM-015 |
| a24 | Leave | DMO UI, types, balances, SR and AMO leave | DMO approves in the web; Casual, Sick, Earn; balances off; SR and AMO leave not built | D-176, D-337 | F-TSO-008, F-WEB-046 |
| a25 | Daily Tracking take action | The action | Note plus notification to the route TSO and AMO; no reassignment | D-335 | F-WEB-039 |
| a26 | Admin pages Data Entry, Supervisory Module, QC | Their content | Data Entry = Web Entry, web Final Submit, Astha Web Entry; Supervisory = AMO Call Report; QC = Market and Warehouse QC entry and reports | D-40, D-34 | F-WEB-050, F-WEB-054, F-WEB-052 |
| a27 | Retailer edit sections | Field lists, bypass of verification | Sections basic info, address, business, additional detail; web edit of location, name and owner raises a request unless the editor holds approve rights | D-43 | F-WEB-003 |
| a28 | Outlet Approval Panel | Code assignment, closure and info | One panel for New, Close and Info; code assigned on approval by a sequence rule (unknown) | D-43, D-186 | F-WEB-032, F-ADM-037 |
| a29 | Excel exports | Column sets of the 11 download-only reports | Real .xlsx samples requested; column sets frozen per report from them | D-191 | F-WEB-040 |
| a30 | Online/Offline Sales report | Definition of online | captured_online = the device had validated connectivity when the memo was committed (ASSUMPTION) | - | F-WEB-025 |
| a31 | PII gating | Role x field matrix | SR phone hidden from the AMO; retailer phone visible on route-scoped field screens; NID, TIN, licence envelope-encrypted | D-107, D-108 | F-WEB-042 |
| a32 | Stock load | Multiple loads, unit, exceeding stock | Each load a movement; entry in each SKU's unit; warn and allow above stock | D-16, D-321 | F-SR-014, F-SR-050 |
| a33 | Memo numbering | Series continuity, reprint and edit number | New series <username>-<yyMMdd>-<seq3>; reprint keeps the number with a duplicate marker; an edited memo has its own number and prints "supersedes" | D-35, D-322 | F-SYS-027, F-SR-066 |
| a34 | Summary print | Layout, official return statement | Layout from physical samples; the day-end return event is the official statement | D-76, D-324 | F-SR-036, F-SR-051 |
| a35 | Tutorial, AV and KV, survey content | Content management | Per-outlet assignment with validity; admin CRUD; AV pre-downloaded on Wi-Fi | D-75 | F-ADM-020, F-ADM-026 |
| a36 | Logout on SR and AMO | Retained data, pending rows | Keep the local database; the engine keeps uploading | D-69 | F-SYS-022 |
| a37 | Microphone | Call audio | Not requested; any recording needs a consent design first | D-115 | F-SYS-023 |
| a38 | Wholesale and distributor | bex pages, back margin | Out of scope; wholesale marking only | D-42 | F-ADM-056, F-ADM-010 |
| a39 | DMO, WM and Top Management views | Pages and approvals | Web-only; menus are data; DMO approves TSO leave; WMO approves targets | D-336 | F-ADM-064 |

### 9.3 Decisions raised by this document (D-320 to D-349)

Status words are those of doc 14 s2: DEFAULT means we proceed unless AKTCL objects; MUST-CONFIRM names the sub-milestone that cannot exit without the answer. The doc 14 author copies these rows into DECISIONS.md. D-339 is unused.

| ID | Decision | Status | Why or source | Docs |
| --- | --- | --- | --- | --- |
| D-320 | Credit: Apsis shows no credit limit, eligibility rule or maximum age; the new app allows credit on any outlet; cfg.credit.max_due_mtk and cfg.credit.max_days default 0 (off); overdue blocks nothing; the ageing report is the control | DEFAULT; MUST-CONFIRM (by 2a): any business limit | G-feat-14; manual p34-35 shows none | 15, 16 |
| D-321 | Stock insufficiency at sale: cfg.sale.stock_check = warn (off, warn, block); quantity above current stock warns and allows because a DH top-up may be unrecorded; the movement is flagged stock_negative | DEFAULT | G-feat-44; UI-SR-16 (stock derived) | 15, 16, 17 |
| D-322 | Reprint: always allowed from the Memo menu; the paper carries a duplicate marker (text from the physical samples; what Apsis prints is unknown) unless the previous print was marked failed_user; cfg.memo.reprint_max default 5 (ASSUMPTION) | DEFAULT; MUST-CONFIRM (by 1a): paper marking (UI-SR-29) | G-feat-04, G-field-20 | 15, 17 |
| D-323 | Returns after QC: defect sticks leave the SR stock as a qc_return movement and go back with the day-end return; QC stays a money deduction; no replacement or credit-note path (none visible in Apsis) | ASSUMPTION; MUST-CONFIRM (by 2b) | G-feat-05 | 15, 16 |
| D-324 | Stock return and counter-confirmation: day-end movements return, damaged, short with counted_qty, variance reason and confirmed_by (nullable); confirmer is the AMO as proxy by default, a dh role (scope node house) if Q41 says so; unconfirmed rows are marked; Sales Submit is not blocked | DEFAULT; MUST-CONFIRM (by 3a): Q41 | G-feat-02, G-field-01, G-field-21 | 15, 16, 21 |
| D-325 | Cash deposit: cash_handover event (declared, counted, variance, counted_by, fix) with the same confirmer as D-324; a shortfall is settled outside the system and never blocks | DEFAULT; MUST-CONFIRM (by 3a): Q41 | G-feat-03, G-field-01 | 15, 16 |
| D-326 | Selling to a not-yet-approved outlet is allowed the same day (cfg.outlet.sell_before_approval true) with a provisional outlet client_uuid re-linked on approval; a rejected outlet keeps its memos and is flagged for the AMO | DEFAULT; MUST-CONFIRM (by 2c) | G-feat-42 | 15, 16, 17 |
| D-327 | Visit plan completion: an outlet is Completed when its Visit Query is submitted (delegate Yes or No); no edit or delete of a plan; a repeat Set Plan is a union by outlet; no geo gate for Visit Query | DEFAULT | G-feat-49, G-man-080 | 15 |
| D-328 | Task lifecycle: statuses ongoing and completed as config, overdue computed from the due date, reassign by changing the route assignment, no evidence on resolve (parity), badge = open count, assignee = outlet's route SR on the due date, creation blocked for "SR Not Set" | DEFAULT | G-feat-19, G-man-049, G-man-082 | 15 |
| D-329 | Attendance edge cases: check-in without a fix is stored with null location and flagged; a forgotten check-out never blocks Sales Submit and the server closes it as "no check-out" at the end of the business date; a missed check-in does not block selling (cfg.day.checkin_gate off) | DEFAULT; MUST-CONFIRM (by 2e): whether the live app blocks selling before check-in (UI-SR-13) | G-feat-18, G-man-029 | 15, 17 |
| D-330 | Outlet reactivation and reassignment: admin reactivation with reason and audit; SR request "add to my route" (info type) verified by the AMO and approved on the web; outlets are never deleted | DEFAULT | G-feat-61, G-feat-62 | 15, 16 |
| D-331 | SR transfer mid-month: assignment is effective-dated; targets stay with the route; user KPIs attribute by assignment dates; route KPIs are unchanged; dues stay with the outlet; the bundle is re-downloaded | DEFAULT | G-feat-06 | 15, 16 |
| D-332 | Superstar: enrolment by admin per outlet per month, slabs as data, a criteria job sets sales_criteria_met; the rules are unknown | DEFAULT; MUST-CONFIRM (by 5b) | G-feat-22 | 15, 16 |
| D-333 | Free sample: an is_free memo line at price 0 with kind free_sample, own report; counts as a successful call only if D-46 is confirmed that way | DEFAULT; MUST-CONFIRM (by 5b) | G-feat-23 | 15, 16 |
| D-334 | Price compliance: the AMO records the observed shelf price per SKU at a control call; compliant when equal to the outlet list price within a tolerance; counted in the reconciliation row; off for the SR flavour | ASSUMPTION; MUST-CONFIRM (by 3a) | G-feat-01 | 15, 16 |
| D-335 | Daily Tracking "take action": a note and a notification to the route TSO and AMO stored as tracking_action; no reassignment | DEFAULT | G-feat-29 | 15, 19 |
| D-336 | DMO, Wing Manager, WMO and Top Management are web-only; menus and permissions are data; DMO approves TSO leave, WMO approves target sets; Top Management read-only dashboards | DEFAULT; MUST-CONFIRM (by 4a): MQ-48 | G-feat-26; Q16 | 15, 19 |
| D-337 | SR and AMO leave is not built (only TSO leave appears in any manual); an absent SR is handled by day exception and cover | DEFAULT; MUST-CONFIRM (by 3b) | G-feat-25 | 15 |
| D-338 | Bangla review and accessibility: an AKTCL reviewer (named by 0c) signs the bn and en catalogue side by side before each release; acceptance is 100 percent keys present, no truncation at 360 x 640 dp and font scale 1.3, tap targets at least 48 dp, colour never the only signal, icon-only tiles carry text labels | DEFAULT; MUST-CONFIRM (by 0c): reviewer | G-qa-15, G-qa-19 | 15, 17, 20 |
| D-340 | Sort-by-distance in the outlet picker is an IMPROVEMENT, default off (cfg.sale.sort_by_distance) | DEFAULT | UI-SR-25 | 15 |
| D-341 | Eligibility dots: legend is admin data (cfg.ui.outlet_badges), per-outlet flags travel in the bundle; all four dots are drawn, filled when eligible and outlined when not (ASSUMPTION); the colour-to-programme mapping is unknown | ASSUMPTION; MUST-CONFIRM (by 2a) | UI-SR-22; G-15-02 | 15, 19 |
| D-342 | Home tiles Sales Journey and KPI are hidden through cfg.app.home_tiles until their content is captured from the live app; tile set per user (Loyalty Point and Photo Capture absent on one account) | DEFAULT; MUST-CONFIRM (by 2a) | UI-SR-01/02; G-15-01 | 15 |
| D-343 | Discount lines are stored as (sku, qty, value, kind) so goods-in-kind, per-SKU discount value and quantity-only lines all fit; the Memo screen discount table lists them; what "SL Match 3 / 0.00" means is unknown | DEFAULT; MUST-CONFIRM (by 2a) | UI-SR-26; G-15-03 | 15, 16 |
| D-344 | Digit script follows the UI language for screens and, by default, the rasterised printed memo; stored values are ASCII; cfg.i18n.digit_script default follow_ui | DEFAULT; MUST-CONFIRM (by 1a): printed memo digits from the physical samples | G-man-103; UI-SR-08 | 15, 17 |
| D-345 | Outlet list filter and collation: first grapheme cluster, case-insensitive, Bangla and Latin chips; name_sort_key generated on the server and shipped in the bundle so device and web order identically; phones normalised to 11 digits (DELIBERATE small fix of the case-sensitive Apsis strip) | DEFAULT | G-field-10, G-man-037; UI-SR-23 | 15, 16, 17 |
| D-346 | One Bangla label per money term on screens and the printed memo: gross, offer discount, DRP/slide discount, QC deduction, net payable; proposed from the manual: মোট (gross), ডিসকাউন্ট, স্লাইড, QC, সর্বমোট (net); the sponsor's delegate signs the set | DEFAULT; MUST-CONFIRM (by 1a) | UI-SR-07; G-man-002 | 15, 17 |
| D-347 | Outlet photo only inside Force Sale and Manual Override; cfg.sale.outlet_photo_every_call false; the in-range path shows no photo in any page | DEFAULT | G-man-016 (V-outlets) | 15, 17 |
| D-348 | The AMO Survey tile is feature-flagged until its screen is captured from the live app; it reuses the SR survey component and earns no points | DEFAULT; MUST-CONFIRM (by 3a) | G-man-044 | 15 |
| D-349 | Joint call rubric: three known items plus two disabled placeholders as versioned data; star 1 pre-selected for parity (cfg.rubric.default_rating 1, require_all_rated false); the assessed SR is the route assignee on the date; control-call and joint-call targets come from the supervisor target table | DEFAULT; MUST-CONFIRM (by 3a) | G-man-051, G-man-052, G-feat-57 | 15 |

Proved by: T-2-36..44 (sale and memo rules), T-2-21..24 (corrections), T-2-43 and T-2-41 (golden fixtures), T-3-41 and T-3-42 (supervisor rules), T-5-41..43 (programme and target rules).

## 10 Online and offline behaviour matrix

The manuals show four stated connectivity facts (update download, Sale History, Sync and Sales Deposit, the TSO Final Submit round trip); everything else is a requirement from CLAUDE.md constraints 1 and 2: the network is never in the critical path of a sale. Classes: OFFLINE (fully local), LOCAL, QUEUED (written offline, uploaded later), HYBRID (local first, online fallback), CACHED (read snapshot with an as-of stamp), ONLINE-FIRST (online once, then cached), ONLINE-ONLY. The current build has one explicit upload moment (end of day: "ডাটা সিঙ্ক করুন" then "বিক্রয় জমা"); per-sale background sync is a deliberate behaviour change (D-59), so the reconciliation screen must still work as the manual's end-of-day check.

| Role | Surface | Manual evidence | Class | Requirement | Features |
| --- | --- | --- | --- | --- | --- |
| SR, AMO, TSO | First login on a device and password check | implied (OTP step follows; no offline login text) | ONLINE-FIRST | Online once; a cached session reopens offline (offline unlock up to 7 days); token refresh never blocks local reads | F-SYS-001, F-SYS-052 |
| SR | Device OTP verify; TSO web OTP list | SR p5-6; Web p46 | ONLINE-ONLY | A new device cannot be bound offline; launch-day capacity by pre-bind day; no re-verify on an in-place update | F-SYS-003, F-TSO-022 |
| SR, AMO | Update check, APK download, install | SR p7-8 (Network Status line); AMO p5-6 | ONLINE-ONLY | Wi-Fi preferred, resumable, never blocks upload of pending rows | F-SYS-020 |
| SR, AMO | Forced-update gate and post-update data processing (n/m) | SR p9-10; AMO p7 | LOCAL | min_version blocks a new day only; an open offline day finishes; migration preserves pending rows | F-SYS-020 |
| SR, AMO | Reverse-geocoded address on Attendance | SR p12; AMO p17 (implied) | HYBRID | Address when online, else coordinates plus the last address | F-SR-011, F-SYS-074 |
| SR, AMO | Check-in and check-out | not stated | QUEUED | Fix and time stored locally, uploaded later; no fix allowed | F-SR-011, F-SR-012 |
| SR, AMO | Printer pairing, connection, printing | Bluetooth | OFFLINE | Fully local; printer never required to save a sale | F-SR-013, F-SR-028 |
| SR, AMO | Stock, sale, review, credit, QC, zero sale, commit, memo view, reprint, mark paid, edit | not stated; CLAUDE.md 1 | OFFLINE | The network is never in the critical path of a sale; draft persisted at Proceed | F-SR-014..033 |
| SR, AMO | Outlet list and geofence check | force reason "internet problem" implies Apsis depends on connectivity at times | OFFLINE | Check against downloaded coordinates; the AMO needs the zone-wide bundle | F-SR-017, F-AMO-044 |
| SR, AMO | Sale History (previous sales by date) | SR p23 "mobile data must be ON" (stated) | HYBRID | Local window of 7 days plus online fallback; offline banner outside the window | F-SR-054, F-AMO-013 |
| SR | Outlet Points (as of the previous day) | SR p24 | CACHED | Bundle snapshot with as-of date | F-SR-055 |
| SR, AMO | Force sale, manual override, outlet photo, Astha and campaign photos | not stated | QUEUED | Photo queued, uploaded on Wi-Fi first; a location change waits for approval | F-SR-018, F-SR-044, F-SR-045 |
| SR | Slide (DRP), survey, AV and KV | not stated; AV and KV are media assets | OFFLINE | Assets pre-downloaded on Wi-Fi into a bounded cache | F-SR-020..022, F-SYS-029 |
| SR, AMO | Astha targets and achievement | not stated | CACHED | Bundle snapshot with an as-of label | F-SR-041, F-AMO-031 |
| SR, AMO | Outlet new, close, info change | not stated | QUEUED | Offline queue with a visible pending state | F-SR-037..040 |
| SR | Gift redemption | not stated | QUEUED | Local balance; the server arbitrates overdraw (accept and flag) | F-SR-043 |
| SR, AMO | Sync Data and Sales Deposit | SR p71-73 and AMO p66-68 show "অনলাইন" only | ONLINE-ONLY in Apsis; QUEUED here | Sales Submit is an outbox event; add the missing Offline, progress and failure states; the screen stays the end-of-day check | F-SR-034, F-SR-035, F-SYS-009 |
| SR | Tutorial | SR p74 (empty state only) | HYBRID | List cached; playback online and never in the background | F-SR-048 |
| SR, AMO | Task list and Resolve | not stated | QUEUED | List from the bundle; Resolve offline | F-SR-046, F-SR-047 |
| SR, AMO | PDA to Support | SR p78; AMO p78 | ONLINE-FIRST | Queue when offline; Wi-Fi preferred | F-SYS-021 |
| SR, AMO, TSO | Logout | TSO p21 wipes; SR and AMO silent | OFFLINE | Refused while anything is unsynced (TSO); never wipes unsynced data | F-SYS-022 |
| SR | Day exception, cover, identity confirmation | not in any manual | QUEUED | Outbox events; approval by the TSO in the app when online | F-SR-059, F-SR-072 |
| SR | Stock return, cash hand-over, void, due dispute | not in any manual | QUEUED | Outbox events | F-SR-051, F-SR-052, F-SR-058, F-SR-071 |
| AMO | Team Location map | AMO p33 (Google Maps, other users' fixes) | ONLINE-ONLY | List fallback with last cached fixes and age | F-AMO-016 |
| AMO | Live Dashboard, SR Stock, STD Memo Report, Sales Summary Up To Now | AMO p39-41, p74-76 ("live") | ONLINE-ONLY | Explicit load; optional last-view cache with as-of | F-AMO-020, F-AMO-021, F-AMO-032, F-AMO-033 |
| AMO | Team Performance | AMO p34-36 | CACHED | Snapshot with as-of; refresh online | F-AMO-017 |
| AMO | Update Base | AMO p64-65 (steps offline, tiles online) | HYBRID | Tile-less fallback: accept the current fix and adjust numerically | F-AMO-028 |
| AMO | Joint and Control Call picker | AMO p21, p25 | OFFLINE | Zone-wide outlet list in the AMO bundle | F-AMO-004, F-AMO-044 |
| AMO | Outlet verification (SR requests) | AMO p47-54 | QUEUED | List arrives with the bundle; verify offline; photos through the media queue | F-AMO-022..024 |
| AMO | Exceptions, cover, zone day exception | not in any manual | QUEUED | Review events and exceptions are outbox events; cover needs the server to reach the substitute | F-AMO-037, F-AMO-038, F-AMO-045 |
| TSO | Dashboard, Not Logged In and Not Uploaded, Target Status | not stated ("that day", "Live") | CACHED | Last snapshot with "as of hh:mm"; no polling timer | F-TSO-002..007, F-TSO-017 |
| TSO | Final Submit: Get Sales Data and Submit | TSO p12-14 (duplicate check and success imply a round trip) | ONLINE-ONLY | Disabled offline with "needs internet"; cannot be queued (once per zone per day, first wins) | F-TSO-010, F-TSO-021 |
| TSO | My Team and Retailer maps | TSO p15 | HYBRID | Cached fixes and outlets; tiles need the network | F-TSO-011, F-TSO-012 |
| TSO | Leave, Set Plan, Visit Query, Assign Task, Feedback | not stated | QUEUED | Client uuid; sync-state badge; image through the media queue | F-TSO-009, F-TSO-013, F-TSO-015, F-TSO-016, F-TSO-018 |
| Web | All 46 screens | Web R-092 (online by nature) | ONLINE-ONLY | No offline mode; reports show "data as of"; large exports run as background jobs with a download link | F-WEB-*, F-SYS-064 |
| Web | Final Submit and Delete Section Data versus a phone that uploads later | Web p20 | ONLINE-ONLY | A late upload lands in a defined state: rejected `voided_by_admin` when the route-day was voided (a tombstone, or the route-day void barrier for unsent rows, D-577), and ACCEPTED, aggregated and flagged `after_final_submit` when only the zone-day was final-submitted (a final-submitted zone-day never rejects, `day_closed` is not a reject code, D-579); visible on the device-versus-server screen and the Final Submit Log, never silently merged | F-WEB-051, F-ADM-058 |
| Web | Sales Plan, outlet approval, wholesale marking, target approval, gift choice | Web p9-11, p40-47 | ONLINE-ONLY | Changes reach phones at the next bundle; an offline phone keeps the old plan and the server flags sku_not_in_plan, never rejects; actions idempotent (double click) | F-ADM-006, F-WEB-032, F-ADM-056, F-WEB-030, F-TSO-020 |
| Web | Web Entry, Astha Web Entry, QC entry | Web p5-7, p19-21 | ONLINE-ONLY | Each submission carries a client uuid so a retry cannot double a number; web rows and app rows coexist for one route-day and are flagged | F-WEB-050, F-WEB-048, F-WEB-052 |

Count by class in this matrix: CACHED 4, HYBRID 5, LOCAL 1, OFFLINE 6, ONLINE-FIRST 2, ONLINE-ONLY 10, QUEUED 10.

Proved by: T-1-20..24 (airplane-mode day), T-1-25..34 (sync), T-3-41 (AMO control call in airplane mode), T-3-42 (TSO online-only matrix), T-2-31..35 (offline Sales Submit and stale bundle).

## 11 Localisation, glossary and message catalogue

All strings live in /packages/i18n with bn and en catalogues and Bengali fonts bundled (CLAUDE.md constraint 8). Bangla-first holds for SR and AMO; the TSO manual is English-first, so the TSO defaults to English with a language switch (D-181, G-man-028).

### 11.1 Glossary

| Term | Bangla label | Expansion or meaning | Status | Where |
| --- | --- | --- | --- | --- |
| OHS |  | On-hand stock (as counted at the shop) | proposed; confirm | Joint-call rubric item 1 |
| OOS |  | Out of stock | confirmed by use (SR task text, AMO OOS performance) | AMO assessment, tasks |
| SOQ |  | Suggested order quantity | proposed; links F-SYS-035 and Q6 | Joint-call rubric item 2 |
| POSM |  | Point-of-sale material (sticker or banner) | confirmed by use | SR survey, AMO POSM |
| STD / STT | এস টি ডি | Volume sold in the SKU's own unit; the API calls it STT; the screens never expand it | confirmed as volume (docs/22 P-04) | everywhere; K-07 |
| CPR |  | Strike rate: successful calls / target outlets | confirmed (UI-SR-09) | K-04 |
| BSR |  | Brand strike rate: memos containing the brand / total active memos | proposed; denominator Q9 | K-10 |
| Bikroy Joma | বিক্রয় জমা | Sales deposit; the end-of-day Sales Submit | confirmed (TSO tile, SR tile) | F-SR-035, F-TSO-007 |
| FF |  | Field Force: the SR assigned to a route ("FF: SR Not Set") | confirmed by use | F-TSO-010 |
| Dep Name |  | Depot name; in the sample it equals the zone name | proposed; confirm | F-TSO-007, zone.dep_name |
| DSS |  | Sales Summary report used to check sales before Final Submit; the expansion is unknown | expansion unknown; confirm | F-WEB-055 |
| DS-RRS |  | Unknown; hypothesis: route-day delivery and settlement statement | unknown; obtain a sample printout | F-WEB-053 |
| GIGO |  | Unknown; appears as the attendance report | unknown; confirm | F-WEB-021 |
| WMO |  | Approver role of target sets ("WMO approval pending"); level order unknown | unknown; confirm | F-WEB-030 |
| DMO |  | Divisional Marketing Officer; approves TSO leave | confirmed (docs/01, TSO p10) | F-WEB-046 |
| PDA | পিডিএ | "PDA to Support": send the device data file; also the zone "PDA Contact No." | confirmed by use | F-SYS-021, F-ADM-006 |
| SS |  | Supervisor-tier user on an AMO build (ss344002); not necessarily a substitute SR | unknown; confirm (MQ-19) | F-AMO-040 |
| AV, KV |  | Audio-visual and key-visual promotional assets shown before the survey and sale | confirmed by use | F-SR-020 |
| DRP / slide | স্লাইড | Empty-pack collection against a time-limited offer; one label "স্লাইড সংগ্রহ" with the alias "Collect DRP Discount" | confirmed (D-217) | F-SR-022 |
| QC |  | Quality control: returned defective sticks, a money deduction; groups MFC (manufacturing) and MKT (marketing and transport) | confirmed | F-SR-027 |
| MFC, MKT |  | Group codes shown in the QC summary ("MFC Fault, MKT Fault"); the app group "পরিবহন ত্রুটি" is MKT | confirmed (D-159) | F-SR-027 |
| ADS, TADS, PADS, RADS |  | Average daily sales; target ADS (target / 14); P-ADS undefined; required ADS (remaining / 11) | TADS and RADS confirmed by arithmetic; ADS inferred; PADS unknown | F-SR-056 |
| HLP |  | Pack type in the seed catalogue (hinge-lid pack is a hypothesis) | unknown; confirm | sku.pack_type |
| C&c, CC |  | Cash-and-carry price type (wholesale buyers) | proposed; confirm | sku_price.cc |
| GT, DCC, RCC, MT, HoReCa |  | General trade, and the other channel codes; expansions other than GT are not printed | GT proposed; others unknown | channel |
| Astha tiers | আস্থা | Sub-channels Platinum, Gold, Diamond, Silver of the Astha channel | confirmed (docs/22 P-14) | F-SR-041 |
| Diamond League |  | Monthly outlet loyalty league with points and gifts | confirmed | F-SR-043 |
| Superstar |  | Monthly outlet campaign with incentive slabs | spec only | F-WEB-037 |

### 11.2 Terminology

| Concept | Labels seen | Rule |
| --- | --- | --- |
| Route / section | "রুট" on Review, Sale and AMO screens; "সেকশন" on the memo sample and in callouts | Same field, one stored name; the label per screen follows the manual; "সেকশন" prints only where a physical memo sample prints it (D-214). Route name and visit-day label are stored apart (D-242). |
| Cluster / zone | "Savar Metro" is a cluster on the AMO Review screen and a zone on the Astha screen | Keep cluster and zone separate everywhere (D-226). |
| House | "House" on web entry pages, "Distribution House" on report pages, "Dep Name" on TSO lists | House is the distribution house between territory and zone; one translation per surface. |
| Retailer / outlet / shop | রিটেইলার, খুচরা বিক্রেতা, দোকান, Outlet | Table name outlet; screen labels as printed. |
| Memo / sale | মেমো, বিক্রয়, সেল | Memo for the document, বিক্রয় for the activity and tile. |
| Credit | বাকি (also ক্রেডিট, Baki) | One label "বাকি" (D-212). |
| Sales Deposit / Submit | বিক্রয় জমা, Bikroy Joma, Sales Submit | One Bangla label; Bikroy Joma stays the transliteration on TSO surfaces. |
| Totals | মোট, সর্বমোট, সর্ব মোট, মোট ডিসকাউন্ট | One label per money term (D-346). |

### 11.3 Label-format templates

| Where | Template |
| --- | --- |
| Sale, Astha shop list, AMO pickers, AMO close and info | name (code-phone-cluster) |
| SR close and info picker | name (sub-channel) |
| Review and Memo outlet header | name (code) |
| Astha outlet detail | খুচরা বিক্রেতার নাম : name (code) |
| Route label | route name + " (" + visit days + ")"; "Daily" kept as written; no glued suffix |
| SR header | SR - <name> (<username>); line 2 <route label>, <ISO date> |
| AMO header | <display name> (<username>); line 2 <point name><role>, <business date> |
| TSO header | Good day, <display name> |
| TSO outlet card (Set Plan) | name; code / address, or the cluster line when the address is blank (13 of 734,789 outlets have one); owner / phone |
| Phone | 11 digits with the leading 0; stored text; empty shows "-" (the manual shows "--"); the SR phone is never shown to the AMO |
| Task text | <outlet> দোকানে <brand> এ OOS আছে (pattern, free text) |

### 11.4 Number, date, time and money profile

| Item | Rule | Source |
| --- | --- | --- |
| Digits | Bangla digits when the UI language is বাংলা, Latin digits for English; the printed memo follows the same setting unless the physical samples show otherwise; stored values are ASCII | G-man-103, D-344 |
| Grouping | Western 3-digit grouping (2,082,820 in the AMO sample); no lakh grouping unless AKTCL asks | G-man-103 |
| Money | 2 decimals with the taka sign after the amount on memo and QC ("৫৯৪.৫০৳"); dues to the paisa; storage in milli-taka | D-15, D-213 |
| Dates in lists and headers | ISO YYYY-MM-DD | SR p10, TSO p10 |
| Dates in pickers | Long form ("November 26, 2025"); web Sales Plan edit shows MM/DD/YYYY and web Final Submit shows YYYY/MM/DD in the manual: unified to the ISO form in lists and the long form in pickers | G-man-103 |
| Time | 12-hour hh:mm AM/PM in sheets and chips, Dhaka time; all stored times UTC plus business date | D-20 |
| Quarter and month | "Q-<n> (<Mon>-<Mon>)", month chips "Oct Nov Dec", month pickers "April 2026" | SR p48, Web p35 |
| Percent | 2 decimals in detail tables, 1 decimal on tiles, capped per D-50 | D-50 |
| Units | Unit label shown wherever a quantity is shown (sticks, pieces, dozens, boxes); never "units" | D-16 |

### 11.5 Message catalogue plan

The four manuals contain 251 message entries: SR 99 (12 Android-owned, 87 app-owned), AMO 90 (12, 78), TSO 24 (2, 22) and Web 38 (none Android-owned), so 225 are app-owned and seeded as keys; the 26 Android dialogs (install, permission, Bluetooth pairing) are not app strings. Entries include labels and placeholders, not only messages. Key form: <area>.<screen>.<element>, one key per message, bn verbatim with typos corrected and an en translation (the AMO mixes three success wordings; one is chosen and "saved on device" is distinguished from "uploaded"). Overrides are config (cfg.i18n.overrides).

Key areas:

| Key prefixes | Scope | Surfaces |
| --- | --- | --- |
| auth.*, update.*, permission.* | login, OTP, install and update flow, permission prompts | SR, AMO, TSO |
| attendance.*, printer.* | attendance states and sheets, printer states and banners | SR, AMO |
| sale.*, geo.*, call.*, slide.*, survey.*, review.*, credit.*, qc.*, commit.*, zero.*, memo.*, edit.* | the selling flow and its dialogs | SR, AMO |
| outlet.*, loyalty.*, photo.*, astha.* | outlet requests, points and redemption, gift photos, Astha | SR, AMO |
| assessment.*, team.*, live.*, verify.*, report.* | AMO supervision, verification and reports | AMO |
| deposit.*, task.*, tutorial.*, settings.*, logout.* | day close, tasks, chrome | SR, AMO, TSO |
| dashboard.*, leave.*, final_submit.*, plan.*, query.*, feedback.*, periphery.*, target.* | TSO surfaces | TSO |
| web.* | login, QC, entry, final submit, outlet panel, wholesale, credentials, OTP, gift panels, filters, statuses | Web |

Parity-critical strings (kept verbatim; Bangla in the manual's script; each has an en twin where the manual shows only one language):

| Key | Verbatim text | Source | Note |
| --- | --- | --- | --- |
| auth.otp.heading | Enter OTP | SR-M-005 |  |
| auth.otp.hint | Enter the 4-digit OTP provided by your TSO. | SR-M-006 | en only in Apsis; bn twin authored |
| auth.otp.new_device | Its look like you are trying to login in a new device. Or you installed new version of the app. So you need to verify your device to complete the login process. | SR-M-004 | grammar corrected in the en text; bn twin authored |
| update.forced | কিছু নতুন আপডেট পাওয়া গেছে, এই অ্যাপটি ব্যবহার চালিয়ে যেতে, আপনাকে অবশ্যই আপডেট করতে হবে! | SR-M-016 | forced gate wording; min_version blocks a new day only |
| update.processing | অ্যাপ আপডেট হচ্ছে / টিপস: অ্যাপটি বন্ধ করবেন না | SR-M-018, SR-M-020 | with the n/m counter |
| attendance.not_checked_in | আপনি এখনো চেক ইন করেননি। কাজ শুরু করার আগে অনুগ্রহ করে চেক ইন করুন। | SR-M-021 |  |
| attendance.checked_in | চেক ইন সম্পন্ন হয়েছে | SR-M-023 | the AMO shows English "Already Checked-In" too: both exist |
| attendance.not_checked_out | আপনি এখনো চেক আউট করেননি। আজকের কাজ শেষ করার আগে অনুগ্রহ করে চেক আউট করুন। | SR-M-025 |  |
| attendance.checkout_note | বিঃদ্রঃ চেকআউট প্রক্রিয়াটি বিকাল ৫ ঘটিকা থেকে করতে পারবেন। | SR-M-026 | time is data: cfg.day.checkout_earliest_time |
| attendance.day_done | আপনি আজকের জন্য চেক ইন এবং চেক আউট সম্পন্ন করেছেন। সহযোগিতার জন্য ধন্যবাদ। | SR-M-029 |  |
| attendance.hold | চাপ দিয়ে ধরে রাখুন | SR-M-030 |  |
| printer.connected | প্রিন্টার কানেক্ট করা হয়েছে | SR-M-036 |  |
| sale.select_retailer_empty | বিক্রয় চালিয়ে যেতে একজন খুচরা বিক্রেতা নির্বাচন করুন | SR-M-037 |  |
| geo.out_of_range | আপনি নির্বাচিত খুচরা বিক্রেতার সীমার মধ্যে নেই। | SR-M-039 | buttons ফোর্স সেল, রিফ্রেশ |
| geo.amo_out_of_range | AMO এবং রিটেইলার রেঞ্জের মধ্যে নেই। | AMO S-11, S-13 | buttons ম্যানুয়াল ওভাররাইড, রিফ্রেশ; one of the five printed guard texts |
| geo.location_on_note | বিঃদ্রঃ অবশ্যই মোবাইল ফোনের লোকেশন অন রাখতে হবে। | SR-M-044 |  |
| force.reason_prompt | আপনার ফোর্স সেলের কারণ কী? | SR-M-043 | options ইন্টারনেট সমস্যা, লোকেশন চেঞ্জ; list is config |
| sale.history_online_note | বিঃদ্রঃ অবশ্যই মোবাইল ফোনের ডাটা অন রাখতে হবে। | SR-M-040 | shown only outside the local window |
| call.start | আপনি কি কল শুরু করতে চান? | SR-M-047 | হ্যাঁ, না |
| survey.required | আপনার (*) মার্ক করা প্রশ্নের উত্তর দিতে হবে | SR-M-050 |  |
| survey.confirm | আপনি কি সার্ভে জমা দেওয়ার বিষয়ে নিশ্চিত? | SR-M-053 |  |
| credit.dialog_title | পরিশোধিত টাকার পরিমাণ লিখুন | SR-M-055 | body SR-M-056 |
| commit.confirm | আপনি কি নিশ্চিত? / বিক্রয় জমা হবে | SR-M-059 |  |
| commit.print_prompt | আপনি কি এই বিক্রয়টি প্রিন্ট করতে চান? | SR-M-060 |  |
| commit.success | সফল / বিক্রয় সফল ভাবে জমা হয়েছে | SR-M-061 |  |
| zero.confirm | বিক্রয়ের জন্য কোনও SKU নির্বাচন করা হয় নি / আপনি কি জিরো (০) বিক্রয় করতে চান? | SR-M-062 |  |
| qc.empty | QC করার জন্য কোন পণ্য নেই | SR-M-064 |  |
| qc.submit_confirm | ইনফর্মেশন / আপনি কি QC জমা দিতে চান? | SR-M-065 |  |
| memo.credit_box | এই বিক্রয়টি বাকিতে করা হয়েছে। | SR-M-068 |  |
| memo.paid_success | এই মেমোটি সফলভাবে পরিশোধিত হিসেবে চিহ্নিত হয়েছে। | SR-M-070 |  |
| edit.reason_prompt | দয়া করে বিক্রয় এডিট করার কারন লিখুন | SR-M-071 |  |
| edit.reason.wrong_sku | ভুল SKU নির্বাচিত। | SR-M-072 | the other two reasons are captured from the live app |
| common.no_data | তথ্য পাওয়া যায়নি | SR-M-073 |  |
| outlet.close_confirm | আপনি কি নিশ্চিত? / এই দোকানটি স্থায়ীভাবে বন্ধ হতে যাচ্ছে | SR-M-075 |  |
| outlet.photo_done | ছবি ধারণ করা সম্পন্ন হয়েছে | SR-M-077 |  |
| outlet.info_confirm | আপনি কি নিশ্চিত? / এই পরিবর্তন সংরক্ষণ করা হবে | SR-M-078 |  |
| loyalty.redeem_confirm | Confirm redemption of these items? Points will be deducted. | SR-M-081 | success SR-M-082 "Redemption successful"; bn twin authored |
| photo.astha_saved | Photo capture details saved successfully. | SR-M-084 | "Photo already captured." SR-M-086; campaign SR-M-088 |
| deposit.sync_advisory | আজকের কাজ শেষ করার আগে, অবশ্যই সব অপারেশন ডাটা সিঙ্ক করুন এবং এই সেকশন থেকে বিক্রয় জমা সাবমিট করুন। | SR-M-090, AMO S-51 | one of the five printed guard texts |
| deposit.dues_warn | আপনার এখনো ১টি রিটেইলারের কাছে বাকি রয়েছে। আপনি আপনার বিক্রয় জমা দিতে চান? | SR-M-091 | the digit is a dynamic count; one of the five printed guard texts |
| deposit.success | বিক্রয় সফল ভাবে জমা হয়েছে | SR-M-092 |  |
| tutorial.empty | কোনো টিউটোরিয়াল পাওয়া যায়নি। | SR-M-093 |  |
| settings.pda_sent | সফল / ডাটা ফাইল পাঠানো হয়েছে। | SR-M-098 |  |
| logout.confirm | আপনি কি নিশ্চিত যে লগআউট করতে চান? | SR-M-099 |  |
| amo.saved | ডেটা সফলভাবে সংরক্ষিত হয়েছে। | AMO S-12, S-22, S-34 | three AMO success wordings (this, "Data Updated Successfully", "Save Successfully!") become one; "saved on device" is distinct from "uploaded" |
| amo.update_base_confirm | আপনি কি নিশ্চিত, খুচরা বিক্রেতার অবস্থান আপডেট করতে? | AMO S-43 |  |
| tso.final_submit.already | আপনি ইতিমধ্যেই আজকের জন্য 'FINAL SUBMIT' জমা দিয়েছেন! | TSO-M-06 | Bangla only in Apsis: en twin authored; one of the five printed guard texts |
| tso.final_submit.success | Final Submit Done Successfully... | TSO-M-05 | trailing ellipsis kept for parity |
| tso.logout.confirm | Log Out! / Your all app data will removed. | TSO-M-07 | grammar fixed; the pending-items guard text is authored (D-69) |
| tso.visit_query.q1 | SR আপনার দোকান নিয়মিত ভিজিট করে? | TSO-M-15 | q2 "SR নিয়মিত মেমো প্রিন্ট করে দেয়?" |
| web.final_submit.advisory | Before Final Submit, Please Checkout Sales Data From DSS Report. If Everything OK, Then Proceed to Final Submit | Web M-004 |  |
| web.entry.backdate_banner | আপনি {date} তারিখের পূর্বের কোন সেলস ডাটা এন্ট্রি করতে পারবেন না। ... | Web M-003 | full text and the support contact come from cfg.support.contacts; one of the five printed guard texts |
| web.outlet.approve | Approve outlet request? / Do you want to approve this request? / Yes, approve it / Cancel | Web M-005..M-008 | Verify and Reject dialogs authored in the same pattern |
| web.password.rules | Password must be at least twelve (12) characters. / ... lowercase, uppercase and a number / not the last 10 / not within 24 hours | Web M-010..M-013 | the example password line M-014 is never reproduced (D-244) |

Five guard or failure texts ARE printed in the manuals (verification-corrected): the AMO out-of-range text, the sync-before-submit advisory, the unpaid-dues confirm, the TSO already-submitted alert and the web back-date banner. They are in the table above and are parity strings.

Texts no manual prints and that are therefore authored with AKTCL in bn and en (G-man-102):

| Area | Messages to author |
| --- | --- |
| Login | wrong credentials, wrong role, locked account, session expired (keep working offline), no network, bind errors (wrong OTP, expired OTP, too many attempts) |
| Location | GPS fix failed or timed out (with Refresh), accuracy too low, location services off, permission denied (location, camera, Bluetooth) with Settings deep link, mocked-location warning (the only fraud text an SR ever sees) |
| Printing | printer not connected when Print is pressed, print failed, paper out, "ছাপা ঠিক আছে?" confirmation, duplicate and cancel-slip markers |
| Saving and sync | saved on device versus uploaded, save failure, photo failure, upload failure, partial sync failure with retry, pending and failed badges, rejected rows with the REJECT-policy reason texts only (FLAG-policy codes never reach the SR), "N items not yet sent" on logout, device-versus-server mismatch |
| Updates | interrupted update, update blocked for a new day (min_version), blocked version |
| Validation | required fields on login, leave, Visit Query, Assign Task, Feedback, outlet forms, credit amount at or above the total, negative or zero target, quantity above stock or above the soft ceiling |
| Empty states | no leave, no plan, no outlets, no routes, no memos, no tasks, no SR on this route, no data for the date |
| Connectivity | "needs internet" for online-only actions, offline banner for Sale History outside the local window, map "location off", stale-bundle banner |
| Web | login failure, save success, confirms for Verify, Reject, Delete Section Data, Submit, Final Submit and wholesale Submit, unsaved changes, session expired, back-date unlock text |
| Fraud and policy | never shown to the SR: every flag except the mock warning (D-123) |

### 11.6 Default locale per role

| Surface | Default | Switch | Decision |
| --- | --- | --- | --- |
| SR app | bn | yes | D-181 |
| AMO app | bn (both Bangla and English done-states appear in the manual) | yes | D-181 |
| TSO app | en (three Bangla strings only) | added as an IMPROVEMENT | D-181, D-204 |
| Web | en | no language switch today | cfg.app.default_locale |

### 11.7 Review and accessibility

An AKTCL reviewer, named by 0c, signs the bn and en catalogue side by side before every release (D-338); acceptance is every key present in both languages, no truncation at 360 x 640 dp with font scale up to 1.3, tap targets at least 48 dp, colour never the only signal (KPI bars carry the number), icon-only tiles carry text labels, and a golden test in bn for each parity string. The device lab covers the three reference devices of D-12.

Proved by: T-0-70..76 (localisation layer and fonts exist), T-1-41 and T-2-43 (strings and goldens), T-2-31..35 (usability and review), catalogue completeness in every phase exit.

## 12 Requirement and decision traceability model

### 12.1 The model

R (sponsor requirement) to F (feature) to G (gap), cfg (keys), D (decision), Q or MQ (question) to T (gate) to an evidence path. Every node type is one YAML file under /plan in the repo (rtm.yaml for F rows, gates.yaml, gaps.yaml); the generated RTM.md and GATES.md are what the phase exit report embeds (doc 20 s8). This document is the human-readable source from which rtm.yaml is seeded: the 509 F-ids (the count of s1.3, generated; the first draft said 428, G-qa-99), their phase, gap, decision and cfg columns are the seed; the gate column is the sub-milestone family until doc 20 s3 places individual T-ids.

### 12.2 Columns and shape

| RTM column | From this document |
| --- | --- |
| F-id, name, role | s2 to s8 |
| R | s12.5 |
| Phase (sub-milestone) | Ph column |
| Evidence | Evid text: manual page, spec, UI-SR-nn |
| Flags | Off class, Prt, Pic, Geo; idempotency and scope follow from Writes and API; i18n for any feature with UI |
| cfg keys | cfg keys column |
| Gaps, decisions, questions | Refs column |
| Gates | Gates column, then doc 20 s3 |
| Status | St column (parity, changed, new) |

Example row:

```yaml
- id: F-SR-028
  name: Print memo
  roles: [SR, AMO]
  req: [R2, R4, R5]
  phase: 1a
  evidence: {manual: "SR p38-39", spec: "docs/06 4d"}
  flags: {offline: OFF, idempotent: true, scope: true, i18n: true, battery: "no sensor; printer on demand"}
  cfg: [cfg.print.template_version, cfg.sale.require_printer_before_sale]
  gaps: [G-man-006, G-man-014, G-feat-43, G-sync-02]
  decisions: [D-76, D-77, D-158]
  questions: [Q5, Q57]
  gates: [T-1-35, T-1-41]
  status: parity
```

### 12.3 Coverage rules (rtm-check)

| # | Rule | Counts here |
| --- | --- | --- |
| 1 | Every F-id has a phase and at least one gate; every F with Off = OFF has an offline gate; every F that writes a device-originated table has an idempotency gate; every F with a read endpoint has a scope gate; every F with UI has an i18n gate; and (D-581) the gate NAMED in the F-id to T-id table of s12.6 tests the feature's behaviour, not only exists | 509 rows |
| 2 | Every manual screen maps to at least one F-id | 192 screens in s12.4 |
| 3 | Every manual message entry that is app-owned has a key | 225 entries (s11.5) |
| 4 | Every master gap owned by doc 15 has closing text here, a D-id and a gate | 111 gaps (Traceability) |
| 5 | Every cfg key named here is registered in doc 19 s3 and consumed by at least one F | count in the Traceability section |
| 6 | Every F-id cited by another document exists here | doc 14 merge check |
| 7 | Phase codes are only 0a to 7e of doc 14 s2 | enforced by the Ph column |
| 8 | Vendor strings and the bare KPI names defined in doc 16 s9 never appear | grep lint, doc 20 s8 |
| 9 | Every device-captured column maps to a dw column or an exclusion row EX-nn (doc 16 s8.9, `/plan/capture-map.yaml`) | the Writes column of the app-side rows here |
| 10 | Demo and gate ordering: a demo step or a gate never needs a feature from a later sub-milestone than its own | the Ph column |
| 11 | Every manual screen, message, rule and entity row is in `/plan/manual-coverage.yaml` | 192 screens, 225 app-owned messages, 416 rules |
| 12 | An API row's phase is no later than the earliest phase of any feature that calls it; read and write halves of a path carry their own rows (F-API-017a and 017b, 021a and 021b, 035a to 035c) | the API column |
| 13 | Counts in doc 14 s1.1 and s3 equal s1.3 here (generated, D-516); extended in round 3 (D-576, G-qa-99): the same generated numbers (week counts, F-id counts, the storage and key constants of the constants registry) must also equal the text of DECISIONS.md, doc 18 and doc 20 | 509 rows |

### 12.3b Row-level coverage: rules, entities and messages next to the screen map (D-554, D-581; G-qa-86, G-qa-118)

The sponsor's worry is a missed screen, field, rule or message. Doc 15 maps all 192 screens in s12.4 but mapped the 416 rules, the 115 entity and field rows and the 225 app-owned messages only by prose inside feature rows, deferred the matrix to `/plan/manual-coverage.yaml`, and that file, the four manual inventories, the delta register and the six V-* files lived only in a planning scratch folder. A sampled audit resolved 30 of 30 random rules and screens and about 40 register entries, so the matrix is probably nearly complete; it was simply not auditable. The fix is a deliverable of 0a, not a promise:

| Item | Rule |
| --- | --- |
| Generator | `scripts/gen-manual-coverage.ts` reads the four inventories under `/docs/evidence/manuals/` and writes `/plan/manual-coverage.yaml`: one row per screen (192), rule (416), entity or field (115) and app-owned message (225), each with the F-id, the cfg key or `table.column`, or an explicit `not_built: <reason>` row signed by the delegate; the generator fails when a row has none of them (T-0-161) |
| Commit before the first build session | The evidence files (the four inventories, the delta register, V-* verification files) are committed read-only under `/docs/evidence/manuals/` and `/docs/evidence/verification/`, docs 14 to 21 and DECISIONS.md are committed, and the precedence text of doc 14 s9.2 rule 11 is applied to CLAUDE.md and the README in the SAME commit (doc 14 s9.2b; T-0-160). Until the files exist the DECISIONS.md header says so |
| Messages | The 225 app-owned messages map to localisation keys; s11.5 quotes 54 of them; the rest are generated from the same table |
| Alias map | `/plan/key-aliases.yaml` lists the keys of the register s2.5 that doc 19 s3.3 retired or renamed (`cfg.i18n.numeral_system`, `cfg.print.pin_hint`, `cfg.auth.offline_session_max_days`, and the others of table A and B), so the coverage lint does not flag them as missing and D-92 stays true: the register names win except where this map says otherwise (D-583) |

### 12.4 Manual screen coverage map

Every screen of the four manual inventories and the feature that builds it. Android system dialogs are included because flows depend on them.

**SR manual (screens S-01 to S-60)** (60 screens)

| Screen | Title | Features |
| --- | --- | --- |
| S-01 | APK install | F-SYS-020, F-SYS-044, F-SR-001 |
| S-02 | Login | F-SR-001, F-SYS-001 |
| S-03 | Location permission | F-SR-003, F-SYS-023 |
| S-04 | OTP verification | F-SR-002, F-SYS-003 |
| S-05 | TSO web SR Device OTP | F-TSO-022, F-ADM-022 |
| S-06 | Update Available | F-SR-004, F-SYS-020 |
| S-07 | Install unknown apps and update confirm | F-SYS-020 |
| S-08 | Forced update splash | F-SYS-020 |
| S-09 | App data update in progress | F-SYS-020 |
| S-10 | Dashboard (home) | F-SR-008, F-SR-009, F-SR-010, F-SR-069, F-SR-067, F-SR-068 |
| S-11 | Target and Achievement (SKU list) | F-SR-056 |
| S-12 | Attendance | F-SR-011, F-SR-012 |
| S-13 | Check-in sheet | F-SR-011 |
| S-14 | Check-out sheet | F-SR-012 |
| S-15 | Printer pairing in Android settings | F-SR-013 |
| S-16 | Bluetooth permission and printer connect | F-SR-013, F-SR-003 |
| S-17 | Stock | F-SR-014, F-SR-015 |
| S-18 | Sale: retailer selection | F-SR-016, F-SR-075, F-SR-074 |
| S-19 | Sale: outlet selected | F-SR-017, F-SR-019, F-SR-054, F-SR-055 |
| S-20 | Sale Data | F-SR-054 |
| S-21 | Points | F-SR-055 |
| S-22 | Force Sale reason | F-SR-018 |
| S-23 | Camera and Audio permission | F-SR-003, F-SYS-023 |
| S-24 | Camera capture | F-SR-018, F-SR-079, F-SYS-030 |
| S-25 | Start-call prompt and sale footer | F-SR-060 |
| S-26 | Slide (DRP) | F-SR-022 |
| S-27 | Slide offer detail | F-SR-022 |
| S-28 | Slide manual-quantity dialog | F-SR-022 |
| S-29 | AV viewer | F-SR-020 |
| S-30 | KV viewer | F-SR-020 |
| S-31 | Survey: POSM availability | F-SR-021 |
| S-32 | Sale entry | F-SR-023, F-SR-065 |
| S-33 | Review | F-SR-025, F-SR-024 |
| S-34 | Partial payment dialog | F-SR-026 |
| S-35 | QC picker and summary | F-SR-027 |
| S-36 | QC entry | F-SR-027 |
| S-37 | Sale commit dialogs | F-SR-028, F-SR-073 |
| S-38 | Zero-sale dialog | F-SR-029, F-SR-057 |
| S-39 | Memo | F-SR-030, F-SR-031, F-SR-066 |
| S-40 | Mark-as-paid | F-SR-032 |
| S-41 | Sale-edit reason dialog | F-SR-033 |
| S-42 | Sale page in edit mode | F-SR-033 |
| S-43 | Astha: route information | F-SR-041 |
| S-44 | Astha: shop-wise list | F-SR-042 |
| S-45 | Astha: outlet detail | F-SR-042 |
| S-46 | Outlet menu | F-SR-037, F-SR-038, F-SR-039 |
| S-47 | New shop | F-SR-037, F-SR-079 |
| S-48 | Permanently closed outlet | F-SR-038 |
| S-49 | Information change | F-SR-039, F-SR-079 |
| S-50 | Loyalty Point menu | F-SR-043 |
| S-51 | Gift Redemption | F-SR-043 |
| S-52 | Photo Capture menu | F-SR-044, F-SR-045 |
| S-53 | Astha Photo Capture | F-SR-044 |
| S-54 | Campaign Photo Capture | F-SR-045 |
| S-55 | Sales Deposit | F-SR-034, F-SR-035, F-SYS-009 |
| S-56 | Tutorial | F-SR-048 |
| S-57 | Task Delegation | F-SR-046, F-SR-047 |
| S-58 | Settings | F-SR-005, F-SR-006, F-SR-007 |
| S-59 | Logout confirmation | F-SR-007, F-SYS-022 |
| S-60 | Summary (stub, no manual page) | F-SR-036 |

**AMO manual (screens S-01 to S-62)** (62 screens)

| Screen | Title | Features |
| --- | --- | --- |
| S-01 | APK installation | F-SYS-020, F-SYS-044 |
| S-02 | Login | F-SYS-001 |
| S-03 | Location permission | F-SYS-023 |
| S-04 | Update Available (Path A) | F-SYS-020 |
| S-05 | Forced in-app update (Path B) | F-SYS-020 |
| S-06 | AMO Dashboard | F-AMO-001, F-AMO-002, F-AMO-040 |
| S-07 | Printer pairing | F-SR-013 |
| S-08 | Attendance | F-AMO-003 |
| S-09 | Check-in sheet | F-AMO-003 |
| S-10 | Check-out sheet | F-AMO-003 |
| S-11 | Joint Call Activity | F-AMO-011, F-AMO-005 |
| S-12 | SR Call Assessment | F-AMO-011 |
| S-13 | Control Call Activity | F-AMO-004 |
| S-14 | Manual Override camera | F-AMO-005 |
| S-15 | Camera and Audio permission | F-SYS-023 |
| S-16 | Control Call menu | F-AMO-004, F-AMO-010 |
| S-17 | Sales (order entry) | F-AMO-006, F-AMO-012 |
| S-18 | Sale Review | F-AMO-006 |
| S-19 | Partial-payment dialog | F-AMO-006 |
| S-20 | Sale Data | F-AMO-013 |
| S-21 | Product QC | F-AMO-006 |
| S-22 | SR Performance Assessment | F-AMO-007, F-AMO-008, F-AMO-009 |
| S-23 | Survey | F-AMO-043, F-AMO-010 |
| S-24 | Team Location | F-AMO-016 |
| S-25 | Team Performance (summary) | F-AMO-017 |
| S-26 | Team Performance details | F-AMO-017 |
| S-27 | Task Delegation list | F-AMO-018 |
| S-28 | Assign Task | F-AMO-019 |
| S-29 | Live Dashboard | F-AMO-020 |
| S-30 | SR List | F-AMO-021 |
| S-31 | Lifted Stock | F-AMO-021 |
| S-32 | Outlet hub | F-AMO-001, F-AMO-022 |
| S-33 | New outlet verification: list | F-AMO-022 |
| S-34 | New outlet verification: form | F-AMO-022 |
| S-35 | GEO and photo capture | F-SR-079, F-AMO-022 |
| S-36 | Closure verification: list | F-AMO-023 |
| S-37 | Closure verification: detail | F-AMO-023 |
| S-38 | Info-change verification: list | F-AMO-024 |
| S-39 | Info-change verification: form | F-AMO-024 |
| S-40 | New shop (AMO) | F-AMO-025 |
| S-41 | Permanently closed (AMO) | F-AMO-026 |
| S-42 | Change information (AMO) | F-AMO-027 |
| S-43 | Update Base | F-AMO-028 |
| S-44 | Confirm location (map) | F-AMO-028, F-SYS-074 |
| S-45 | Stock | F-AMO-029 |
| S-46 | Memo selector | F-AMO-014 |
| S-47 | Memo detail | F-AMO-014 |
| S-48 | Mark credit memo as paid | F-AMO-014 |
| S-49 | Edit sale | F-AMO-014 |
| S-50 | Sales Summary | F-AMO-015 |
| S-51 | Sales Deposit | F-AMO-030, F-AMO-039 |
| S-52 | Astha: route information | F-AMO-031 |
| S-53 | Astha: shop-based information | F-AMO-031 |
| S-54 | Astha: retailer detail | F-AMO-031 |
| S-55 | Reports menu | F-AMO-032, F-AMO-033 |
| S-56 | STD Memo Report | F-AMO-032 |
| S-57 | Sales Summary Up To Now | F-AMO-033 |
| S-58 | Route detail | F-AMO-033 |
| S-59 | Settings | F-AMO-034 |
| S-60 | Language dropdown | F-SYS-019 |
| S-61 | PDA to Support | F-SYS-021 |
| S-62 | Logout confirmation | F-SYS-022 |

**TSO manual (screens S-01 to S-24)** (24 screens)

| Screen | Title | Features |
| --- | --- | --- |
| S-01 | Install (file manager) | F-SYS-020 |
| S-02 | Install complete, launcher icon | F-SYS-020 |
| S-03 | Login | F-TSO-001 |
| S-04 | Dashboard | F-TSO-002, F-TSO-003, F-TSO-004, F-TSO-005, F-TSO-006, F-TSO-007 |
| S-05 | Login and Bikroy Joma Status detail | F-TSO-007 |
| S-06 | Navigation drawer | F-TSO-028 |
| S-07 | Leave Applications | F-TSO-008 |
| S-08 | Leave Apply | F-TSO-009 |
| S-09 | Final Submit: selection | F-TSO-010 |
| S-10 | Final Submit: already-submitted alert | F-TSO-010, F-TSO-021 |
| S-11 | Final Submit: routes and Submit | F-TSO-010 |
| S-12 | Final Submit: success dialog | F-TSO-010 |
| S-13 | My Teams | F-TSO-011 |
| S-14 | Outlets (retailer map) | F-TSO-012 |
| S-15 | My Visit Plan: selector | F-TSO-014 |
| S-16 | Visit Plan Outlets | F-TSO-014 |
| S-17 | Visit Query | F-TSO-015 |
| S-18 | Assign Task | F-TSO-016 |
| S-19 | Set Plan: selector | F-TSO-013 |
| S-20 | Select Outlets and Set Plan | F-TSO-013 |
| S-21 | Target Status: summary | F-TSO-017 |
| S-22 | Target Status: details | F-TSO-017 |
| S-23 | Create New Feedback | F-TSO-018, F-TSO-029 |
| S-24 | Logout confirmation | F-TSO-001, F-SYS-022 |

**Web manual (screens S-01 to S-46)** (46 screens)

| Screen | Title | Features |
| --- | --- | --- |
| S-01 | Login page | F-WEB-043 |
| S-02 | Global shell and sidebar | F-ADM-064, F-WEB-041 |
| S-03 | Dashboard | F-WEB-001 |
| S-04 | Browse Retailer | F-WEB-002 |
| S-05 | QC Entry (Market QC) | F-WEB-052 |
| S-06 | QC Report (Market and Warehouse) | F-WEB-061 |
| S-07 | Warehouse QC Entry | F-WEB-060 |
| S-08 | Route Wise QC Report | F-WEB-062 |
| S-09 | Sales Plan (list) | F-ADM-006 |
| S-10 | Sales Plan (edit and SKU picker) | F-ADM-006 |
| S-11 | Browse Category | F-WEB-004 |
| S-12 | Browse Segment | F-WEB-005 |
| S-13 | Browse Brands | F-WEB-006 |
| S-14 | Browse Variant | F-WEB-007 |
| S-15 | Browse SKUs | F-WEB-008 |
| S-16 | Browse Product Tree | F-WEB-009 |
| S-17 | Browse Routes | F-WEB-010 |
| S-18 | Web Entry | F-WEB-050 |
| S-19 | Final Submit | F-WEB-051, F-ADM-058 |
| S-20 | Astha Web Entry | F-WEB-048 |
| S-21 | STD Memo Report | F-WEB-013 |
| S-22 | SR Efficiency Report | F-WEB-014 |
| S-23 | DSS Report | F-WEB-055 |
| S-24 | DS-RRS Report | F-WEB-053 |
| S-25 | Route Wise STD Report | F-WEB-015 |
| S-26 | Data Entry Log | F-WEB-016 |
| S-27 | Final Submit Log Report | F-WEB-017 |
| S-28 | Route Wise Memo Report | F-WEB-056 |
| S-29 | Route Wise BSR and CPR Report | F-WEB-018 |
| S-30 | By Outlet Report | F-WEB-019 |
| S-31 | Astha Report | F-WEB-020 |
| S-32 | GIGO Report | F-WEB-021 |
| S-33 | Loyalty Program: Diamond League Report | F-WEB-049 |
| S-34 | Set Target | F-ADM-014, F-ADM-059 |
| S-35 | Target Allocation Report | F-WEB-029 |
| S-36 | Target Approval List | F-WEB-030 |
| S-37 | AMO Call Report | F-WEB-054 |
| S-38 | SR Outlets Reports | F-WEB-031 |
| S-39 | Outlet Approval Panel | F-WEB-032 |
| S-40 | Approve outlet request dialog | F-WEB-032 |
| S-41 | Retailer Wholesale Outlet | F-ADM-056 |
| S-42 | Selected Outlets dialog | F-ADM-056 |
| S-43 | Credentials | F-WEB-033 |
| S-44 | SR Device OTP | F-TSO-022 |
| S-45 | Astha Gift Choice Panel | F-TSO-020 |
| S-46 | Astha Gift Choice Report | F-WEB-034 |

### 12.5 Sponsor requirements to features

| Req | Mechanism in this document | Features |
| --- | --- | --- |
| R1 Data | every captured record is structured in a typed table, aggregated by dirty key into dw tables, and every page reads dw only; reports are queries on stored data | F-SYS-015, F-SYS-017, F-SYS-025, F-SYS-060, F-SYS-061, F-SYS-063, F-SYS-064, F-WEB-001 to F-WEB-067, F-API-017 |
| R2 Features | every manual screen and spec page has an F-id; underspecified rules have defaults | s1.4, s6.1, s9, s12.4 |
| R3 Scale | event-driven sync, replay, poison-row isolation, pre-generated bundles, no polling, paged bundles | F-SYS-008, F-SYS-046, F-SYS-047, F-SYS-048, F-SYS-055, F-SYS-067, F-API-005, F-API-006, F-AMO-044 |
| R4 Battery | one fix per event, no foreground service, compressed photos, bounded caches, map only on its screen, APK budget | F-SYS-011, F-SYS-029, F-SYS-030, F-SYS-036, F-SYS-037, F-SYS-071, F-SYS-073, F-SYS-074, F-SR-017 |
| R5 Offline and immediate sync | OFF and QUE classes, outbox triggers, offline unlock, config delta, immediate flush on connectivity | F-SYS-008, F-SYS-046, F-SYS-052, F-SYS-053, F-SR-034, F-SR-035 |
| R6 Admin config | radius at 8 scope levels, 600-key console (565 before round 2), audit, propagation to the field, kill switches | F-ADM-012, F-ADM-013, F-ADM-038 to F-ADM-055, F-SYS-053, F-SYS-054, F-API-037, F-API-040 to F-API-042 |
| Process | phases 0 to 7 with sub-milestones; each F has a phase and a gate family; counts per sub-milestone in s1.3 | s1.3 |

Proved by: T-0-40..49 (rtm and gates files, rtm-check), T-6-60..69 (config coverage), T-7-80..89 (cutover rows).

### 12.6 Feature to gate: the explicit table (D-581, G-qa-117; T-0-163)

Doc 15 s12.1 said the Gates column is "the sub-milestone family until doc 20 s3 places individual T-ids", and doc 20 never cited an F-id. Thirty-five features shared the range T-2-36..44, whose gates cover the offer engine, memo arithmetic, quantity model, stock and price lists, printed-memo parity, the regression corpus, localisation and the upgrade matrix, and exercised none of F-SR-020 (AV and KV), F-SR-021 (POSM survey), F-SR-048 (Tutorial), F-SR-057, F-SR-067, F-SR-068, F-SR-075, F-API-027, the 11-digit mobile rule, the closure-with-dues guard or the confirm differences of the outlet forms. rtm-check proved only that a cited T-id exists, not that it tests the feature, so R2(a) "every F-id has a gate" could not be verified from the plan.

Rule. Every F-id carries one or more NAMED gates with a one-line assertion (the `covers:` field of `rtm.yaml`, generated into this table and into `GATES.md`); rtm-check fails when a feature cites a gate whose text names none of its behaviours (the check matches the `covers` keywords against the gate text) and when a gate is named by more than 12 features. The generator produces the full 509-row table at 0a (T-0-163); this pass supplies the rows for the known holes and for every round-3 feature, and re-points the features that shared the range.

| F-id | Named gate(s) | Assertion (one line) |
| --- | --- | --- |
| F-SR-020 | T-2-131 | AV pre-downloaded on Wi-Fi plays in the order AV, KV, survey, sale; each view writes a `content_view` event offline; a missing item is skipped, never blocks the sale |
| F-SR-021 | T-2-132 | Q1 required, Q1.1 photo only when Q1 is yes, the confirm dialog, +50 points posted once by the server on a replayed upload, none for an AMO survey |
| F-SR-057, F-SR-067, F-SR-068, F-SR-075 | T-2-133 | every outcome code writes its record; a skip needs no fix and no geo gate; three closed outcomes raise the AMO task; the Sales Journey and KPI tiles are hidden by default; eligibility dots follow the bundle flags |
| F-SR-048, F-API-027 | T-2-134 | list from the backend, the empty-state string, list cached, playback online only, no background download |
| F-SR-037, F-SR-038, F-SR-039 | T-2-135 | the 11-digit mobile rule with the stored test vectors; closure blocked while dues are open when `cfg.outlet.close_block_if_dues` is true; the confirm dialog of closure and its absence in new and info change |
| F-SR-010, F-SR-069, F-SR-056, F-SR-036, F-SR-050 | T-2-136 | the home strip, money cards, SKU target tile, Summary and current stock tracker equal the dw KPI definitions on the golden day |
| F-SR-060, F-SR-065, F-SYS-078, F-SYS-035 | T-2-137 | start-call prompt, route picker for an SR with several routes, multi-visit policy and visit kind, and the suggestion hook delivered empty and off |
| F-ADM-016, F-ADM-020, F-ADM-061, F-API-035a | T-2-138 | a promotion, survey, rubric or content item edited in P8 reaches a phone on the next delta with bn and en text, and the simulator equals the engine on the fixture |
| F-SR-014, F-SR-015, F-SR-081 | T-2-39, T-2-161 | the Stock screen shows the loaded total read-only; a Save posts the increment; a same-values re-save is refused; a correct total posts a signed adjustment |
| F-AMO-029, F-AMO-049 | T-3-160 | the same rule on the AMO stock screen |
| F-SR-022, F-SR-024, F-SR-026, F-SR-027, F-SR-029, F-SYS-062 | T-2-36, T-2-37 | the slide fixture (10 empty packets, a reward pack, deduction 80.00), offers, credit and partial payment, QC deduction, zero sale with QC (negative net) and the server recompute agree between Dart and TypeScript |
| F-SR-044, F-SR-045 | T-5-123 | Submit needs every capture slot (Q-28 default); "Photo already captured." hides Submit; a partial submit flags the missing slot |
| F-WEB-055 | T-4-167 | a Web Entry save appears in DSS within 60 s and DSS equals Web Entry for the route-day |
| F-SYS-086 | T-1-155, T-3-158 | every route_day writer produces a dirty row; a bundle-only login lights the tile in 60 s; a submit void changes Submit % |
| F-SYS-087, F-SYS-097 | T-7-163 | an Apsis upload at 21:30 on T-1 and another at 07:00 on T reach the phone or the straggler sheet |
| F-SYS-088 | T-7-164 | an Apsis memo on an `on_aron` route raises the residual alert |
| F-SYS-089 | T-1-156 | acked rows older than the window re-sent after a PITR are accepted and flagged, none quarantined |
| F-SYS-090 | T-2-163 | the first morning after a 5-day and a 9-day break starts on the pre-fetched snapshot |
| F-SYS-091 | T-2-162 | stamp regress and the no-grace tightening |
| F-SYS-092, F-API-083 | T-2-165 | a widened radius reaches an SR standing out of range through Refresh GPS |
| F-SYS-093, F-ADM-083, F-API-082 | T-6-152 | a held partition or blob is skipped by the archive jobs |
| F-SYS-094 | T-4-166 | unsent rows of a voided route-day are rejected, rows after the void are accepted |
| F-SYS-095, F-ADM-085, F-API-079 | T-2-169 | a directive queued for a silent phone is acted on at its next foreground contact and acknowledged |
| F-SYS-096 | T-4-168 | the three new analyst questions are answered under `bi_reader` |
| F-ADM-078, F-ADM-079, F-API-078, F-API-086, F-API-068, F-API-067 | T-2-167 | replace a phone with a pre-approved checker; a held bind is released in one click |
| F-ADM-080, F-API-077 | T-2-168 | replay twice equals once; `replay_excess` is flagged and never applied |
| F-ADM-081, F-API-080, F-ADM-005 | T-2-166 | the percent guard, the preview, the same-day correction and the break-glass restore |
| F-ADM-082, F-API-081 | T-6-150 | apply 5,000 assignment changes, revert, identical |
| F-ADM-084, F-API-084 | T-2-170 | SLA timers and the contact log |
| F-ADM-006, F-ADM-036, F-ADM-070, F-ADM-071 | T-2-172 | the day-one tools are usable by L2 and the TSO in minimal form |

## Open items

Every MUST-CONFIRM decision applied by this document appears here by D-id. Where this document disagrees with the skeleton the skeleton is followed and the disagreement is listed.

| ID | Item | Why open | Owner role | Needed by | What proceeds on the default meanwhile |
| --- | --- | --- | --- | --- | --- |
| OI-15-01 | Physical 58 mm memo, stock slip, summary, due receipt and cancel-slip samples; what a reprint prints; digits and Bangla labels of money terms on paper (D-158, D-322, D-344, D-346; G-sync-02, G-15-04) | The printed layout is never shown in any manual | sponsor's delegate with one SR and one retailer-facing manager | 1a | Golden prints are written from assumption and re-signed against the samples before the first golden print |
| OI-15-02 | Match entry unit (12 pieces versus 1 dozen inside the SR manual), lighter box size, loose sticks, negative net, maximum-QC basis, rounding equal to Apsis to the paisa (D-16, D-17, D-18, D-19, D-34; MQ-01 to MQ-04) | Arithmetic proves the rounding rule but not the unit | sales ops and finance | 2a | Sticks, pieces, dozens as in s9.1; stepper = pack size; negative net stored as a credit |
| OI-15-03 | Live promotion catalogue (about 22 groups) and rules, offer validity behaviour, where automatic discounts render (D-33, D-166; Q13, MQ-05, MQ-06) | Only one offer is evidenced | sales ops | 2a entry | 2a cannot exit without it; offers seeded with the one known fixture |
| OI-15-04 | Sales Journey and KPI tiles, eligibility-dot legend, meaning of the Memo discount table "SL Match 3 / 0.00", per-user tile rule (G-15-01, G-15-02, G-15-03, G-15-08; D-341, D-342, D-343) | Present in AKTCL screenshots, in no manual | AKTCL product owner | 2a | Tiles hidden, dots drawn per D-341, discount lines stored as (sku, qty, value, kind) |
| OI-15-05 | Stock Save versus printer, edit reasons 2 and 3, void and cancel paper in Apsis, check-in gate (G-15-05; D-86, D-200, D-329; Q43, MQ-18) | Not shown in any page | sales ops, live-app access | 2b | Save never waits for the printer; reasons captured from the live app; check-in is a prompt |
| OI-15-06 | Whose dues an AMO sees, DH counter-confirmation (login or AMO proxy), who may assign cover, meaning of SS, AMO request lifecycle details, Outlet badge definition, control and joint-call target origin (D-37, D-85, D-169, D-172, D-187, D-324, D-325; Q41, Q44, MQ-17, MQ-19, MQ-29) | Each is inferred from screenshots | sales ops, AMO lead | 3a | AMO proxy confirms; AMO sees own credit dues; cover by AMO action; badge = pending verifications above zero |
| OI-15-07 | Joint-call rubric items 4 and 5 and default star; AMO Survey screen; Team Performance bar-colour boundary between 25 and 55 percent; till-date bases (meaning of 17 and 15) (D-349, D-348, D-51, D-52; MQ-09, MQ-33) | The manual pages end mid item or show no sample | AKTCL product owner | 3a | Rubric ships with 3 items and 2 disabled placeholders; amber from 40; calendar bases as in D-51 |
| OI-15-08 | Final Submit reopen and who may change what, delegation and auto-close, time gate, basis of Submit % (of logged-in) on the web (D-55, D-198, D-262, D-45; Q11, Q45, Q53, MQ-34, MQ-41) | Not evidenced | sales ops and finance | 3b | Reopen is an audited admin action; no time gate; both Submit figures returned |
| OI-15-09 | Web back-office: what Delete and Status "exist" remove, Final Submit lock, Web Entry usage, back-date lever, PII in the real Excel exports, menus of DMO, WM and Top, real .xlsx samples of the 11 Excel-only reports and a DS-RRS sample printout (D-40, D-97, D-191, D-207, D-336; MQ-43 to MQ-48) | Only one role's menu is shown | TSO and sales ops | 4a to 4c | Audited void before Final Submit, today-only window, report column sets frozen from samples when they arrive |
| OI-15-10 | Programme rules: Diamond League earning rules, cash-cap scope and full catalogue, Astha gift choice save and lock, Superstar rules, free-sample counting (D-41, D-192, D-332, D-333; Q13, MQ-20 to MQ-22, MQ-26) | Only the +50 POSM rule and the spend side are evidenced | programme owner | 5a, 5b | Seed rule, explicit Save, lock on photo, admin enrolment, free sample counts as a call |
| OI-15-11 | Target approval chain and level order, whether the live target stays while a new set is pending (D-31, D-179; Q12, MQ-39) | Only "WMO approval pending" is evidenced | finance and sales ops | 5c | One level WMO; configurable list |
| OI-15-12 | Wholesale effects on price type, target-outlet counting and geo gate; bex and back-margin screens in or out (D-42; Q17) | No manual covers it | sales ops | 6a | Marking only; effects defined before building |
| OI-15-13 | Named Bangla reviewer for the string catalogue (D-338) | Nobody is named | sponsor | 0c | Reviewer is a gate on the first catalogue release |
| OI-15-14 | Table names in Writes and Reads are working names; doc 16 s2 is authoritative | Doc 16 is written in parallel | doc 16 author | 0b | Mismatches are fixed in the RTM when doc 16 lands Checked at the editorial merge: the Writes and Reads names that doc 16 renamed (app.rollout_wave, cfg.config_version_scope) are the doc 16 names; the remaining working names are listed in the RTM at T-0-46. |
| OI-15-15 | Config console page names P1 to P18 come from the lens; doc 19 s5.2 is authoritative and doc 19 s11 sets their phases; the Ph of F-ADM-038 to F-ADM-055 here is the first usable version | Doc 19 is written in parallel | doc 19 author | 1c | Phases as stated here until doc 19 amends Resolved at the editorial merge: F-ADM-043, 045, 046 and 053 now carry the doc 19 s11 first-usable phases (2d, 2a, 2e, 2d) and gates; counts in s1.3 regenerated. |
| OI-15-16 | Gate column cites the T-id family of the sub-milestone; individual T-ids are placed by doc 20 s3 | Doc 20 owns placement | doc 20 author | 0a | Families as in doc 14 s2 Resolved at the editorial merge: every range cited in this document was checked against doc 20 s3 and the ranges that doc 20 OI-20-21 lists as not minted were replaced by minted ids. |
| OI-15-17 | The skeleton asks for the "20 percent plan-only items" of register s0.3; the register lists the plan-only items but gives no count, so no figure is stated (s1.4) | No count exists | doc 14 author | 0a | Items listed without a percentage |
| OI-15-18 | The skeleton calls the web inventory "the 41-page union"; counted here the manual has 41 pages, the spec 37, the overlap 24 and the union 54 (s6.1) | Counting differs | doc 14 author | 4a | Doc 15 numbers used; this document otherwise follows the skeleton |
| OI-15-19 | Phase differences from the register: Web Entry, QC pages and Astha Web Entry are 4c, 4c and 5a here (register P5, P6 and P5) because the skeleton index assigns 4c and 5a; F-SYS-038 is 7a with the skeleton in 0b | Skeleton index wins | doc 14 author | 4c | Skeleton phases used |
| OI-15-20 | Parity Exceptions Register PX-01 to PX-10 (s6.4): owner, sponsor signature and date per row (D-502, D-523; G-qa-27, G-qa-47) | R2 says no function is missed; the register is the only place an exception can live | sponsor's delegate, with each row's owner | 0c exit (7b entry for the last signature) | Rows ship as built-but-hidden or as stated defaults; no unsigned row at 7b entry |
| OI-15-21 | Apsis usage census (page and endpoint visit counts from AKTCL's own Apsis admin or audit views, distribution-house and DMO/WM interviews) feeds PX-01 to PX-03 and PX-07 to PX-09 (D-503, Q15, Q17, MQ-48) | Nobody has counted use of the unseen functions | AKTCL IT, sales operations | 0c | Reserved F-WEB-070 to F-WEB-072 stay built and hidden; 6a is not entered while a row is open |
| OI-15-22 | Whether Sales Submit locks further capture on the route-day in the current app (parity unknown), and the submit void window (F-SR-080, F-TSO-030, F-ADM-074; D-539; doc 17 OI-17-14) | The manuals show Submit but not what is locked | sales operations, finance | 3b | cfg.day.sales_submit_locks_capture true; void within cfg.day.submit_undo_window_min by the TSO or L1 support with TSO confirmation |
| OI-15-23 | Sponsor-signed R5(f) photo exception and the typed-field list J-1 to J-8 touch rows here (F-SYS-068, F-SR-045; D-501, D-510) | Doc 16 and doc 17 own the text; these rows only cite it | sponsor | 0b and 2c | Defaults in docs 16 and 17 |
| OI-15-24 | The Apsis behaviour of the Stock screen on a second Save (MQ-68, D-580) and of a partial campaign gift photo Submit (MQ-28, D-582) | unknown; confirm with the business from a capture of the live app | sales ops, programme owner | 2a, 5a | increment-only Save with a correct-total path; Submit needs every slot |
| OI-15-25 | The full 509-row F-id to T-id table is generated at 0a; the explicit rows of s12.6 cover the known holes only | a script, not hand work | QA lead | 0a | s12.6 |

## Traceability

### Sponsor requirements and process

| Requirement | Mechanism here | Section | First works | Gates |
| --- | --- | --- | --- | --- |
| R1 Data | typed tables, dirty-key aggregation, web reads dw only, ReportQuery and exports | s2, s6, s8 | 1c | T-1-01..04, T-4-41..46 |
| R2 Features | 192 screens and 37 spec pages mapped, 84 rules, 39 defaults, 509 F-ids, Parity Exceptions Register | s1.4, s6.1, s9, s12.4 | 2a to 6a | T-2-36..44, T-3-41, T-3-42, T-4-42..46 |
| R3 Scale | event-driven sync, replay, pre-generated bundles, paging | s2, s8, s10 | 1b | T-1-25..34, T-4-53..57 |
| R4 Battery | one fix per event, no foreground service, compressed media, bounded caches | s2, s3.4 | 1a to 2c | T-1-35, T-2-26, T-2-30 |
| R5 Offline and immediate sync | OFF and QUE classes, triggers, offline unlock, config delta | s2, s10 | 1b | T-1-20..34 |
| R6 Admin config | radius at 8 scope levels, console pages P1 to P19, propagation, audit | s7 | 1c (minimal), 6b | T-1-60..69, T-6-60..69 |
| Process requirement | each F has a sub-milestone and a gate family; counts per sub-milestone in s1.3 | s1.3 | 0a | T-0-40..49 |

### Master gaps owned by this document (111)

Each row names the gap, the retired aliases merged into it, the features and decisions that resolve it, the section that carries the text and the closing sub-milestone (the gate is the family of that sub-milestone, s1.1). Ordered blockers first, then major, then minor; inside a severity by phase.

| Master id | Aliases (retired) | Title | Sev | Phase | Features | Decisions | Section | Resolution |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| G-feat-13 | G-qa-02, G-man-004 | Offer/promotion rule engine undefined (~22 groups, DRP offers) | blocker | 2a | F-SR-022, F-SR-024, F-ADM-016, F-ADM-061, F-WEB-023 | D-33, D-143, D-343 | s3.4, s7 | Offer engine, DRP/slide deduction and the offer simulator move to 2a; the Q13 catalogue is a 2a entry condition; discount lines stored as (sku, qty, value, kind). |
| G-feat-02 | G-field-21 | End-of-day stock return / reconciliation flow absent | blocker | 2b | F-SR-051 | D-324 | s3.6 | Day-end return event (unsold, damaged, short) with counter-confirmation by the DH keeper or the AMO as proxy. Gate: T-2-21, T-2-06. |
| G-feat-20 | G-man-041 | Diamond League point earning rules absent | blocker | 5a | F-SR-043, F-SR-021, F-SYS-061, F-ADM-017 | D-41 | s3.8, s9.1 | Seed rule +50 for the POSM photo; server-side points with source_id; full earning rules, cash-cap scope and catalogue are required before 5a (unknown; confirm). |
| G-man-101 | - | No verbatim string catalogue: 251 message entries across the four manuals (SR 99, AMO 90, TSO 24, Web 38) | major | 0c | F-SYS-018 | D-244 | s11.5 | Catalogue plan: 251 entries, about 225 app-owned, keys <area>.<screen>.<element>, bn verbatim plus en; Android-owned strings excluded. |
| G-man-103 | - | Number, date, time and money formatting profile | major | 0c | F-SYS-051 | D-344 | s11.4 | One formatting profile in /packages: digits, grouping, money, dates, time. |
| G-man-102 | - | No error, offline, validation or failure text exists in any manual - author the set | major | 1a | F-SYS-018 | D-123 | s11.5 | Authored failure, offline and validation set; the five printed guard texts catalogued verbatim (verification-corrected). |
| G-feat-08 | - | Multiple visits to one outlet per day | major | 2a | F-SYS-078, F-SR-017 | D-250 | s3.4 | Second visit to one outlet on one day allowed after a zero sale; no one-memo-per-outlet-day assumption. |
| G-feat-14 | - | Credit sale eligibility and limits | major | 2a | F-SR-026 | D-320 | s3.4, s9.1 | No credit limit in Apsis; default off with keys; ageing report is the control. |
| G-feat-40 | - | SR with several routes on one day (route picker, header) | major | 2a | F-SR-065, F-SR-008 | D-27, D-257 | s3.4 | Route picker when two or more routes are planned; one bundle flips every route_day. |
| G-feat-44 | - | Stock insufficiency validation at sale | major | 2a | F-SR-023, F-SR-050 | D-321 | s3.4 | Stock check warns and allows; movement flagged stock_negative. |
| G-field-03 | G-sync-08, G-feat-38, G-data-31 | No visit outcome / no-sale reason and no "skip" record: shut shop, owner absent, refused, stock sufficient... | major | 2a | F-SR-057, F-WEB-066 | D-38 | s3.4 | Visit outcome codes and a skip record; closed-streak task. |
| G-field-08 | G-feat-27, G-feat-33 | Price-type resolution per outlet is undefined: C&C/wholesale buyers exist (P-16); sku_price has five types; the... | major | 2a | F-SR-023, F-SYS-045 | D-32 | s3.4 | Price type resolved per outlet on the server and delivered in the bundle. |
| G-man-003 | - | QC in the app: six fault types in two groups; picker, entry, summary and submit screens | major | 2a | F-SR-027, F-ADM-023 | D-34, D-159 | s3.4, s7 | qc_fault_type table, picker, entry, summary, confirm and empty state built as printed. |
| G-man-005 | - | Sale card extras: three unlabelled indicators, pack badge, red eye-with-slash icon | major | 2a | F-SR-023 | D-220 | s3.4 | Three read-only unlabelled slots, derived pack badge, printer-status icon (not an eye); hypotheses to confirm from live screenshots. |
| G-man-007 | - | Stock screen: Issue vs Stock columns, category totals panel, Save/Print enabling, whose stock | major | 2a | F-SR-014, F-AMO-029 | D-16, D-56 | s3.3 | Issue versus Stock defined, category totals panel, Save always works, Print needs connection and save. |
| G-man-008 | - | Start-call confirmation before AV/KV, survey and sale ('আপনি কি কল শুরু করতে চান?') | major | 2a | F-SR-060 | D-78, D-160 | s3.4 | Explicit call-start prompt; visit at outlet open; call_started_at separate. |
| G-man-053 | - | SR SKU-wise Target and Achievement screen with ADS, TADS, PADS, RADS | major | 2a | F-SR-056 | D-58 | s3.2 | SKU Target and Achievement with ADS, TADS, PADS, RADS; fixtures 36/45 and 64/82. |
| G-feat-03 | - | Cash deposit / distributor settlement absent | major | 2b | F-SR-052 | D-325 | s3.6 | cash_handover event with counter-confirmation. |
| G-feat-05 | - | Returns / damaged goods path after QC undefined | major | 2b | F-SR-053 | D-323 | s3.5 | qc_return movement on the day-end return; no replacement path (ASSUMPTION). |
| G-feat-16 | G-field-04 | Edit after print: void/reprint semantics | major | 2b | F-SR-058 | D-86 | s3.5 | Memo void event with reason, due reversal, stock back and cancel slip. |
| G-fraud-17 | G-sec-16 | The memo edit window - the main sales-manipulation lever - is self-contradictory across keys and undefined as a... | major | 2b | F-SR-033 | D-86, D-201 | s3.5, s9.1 | Edit window fixed: same business date, inside the geofence, before QC at the outlet and before Sales Submit, chain depth at most 3. |
| G-man-010 | - | Mark-as-paid settles the whole memo only; spec says full or partial | major | 2b | F-SR-032 | D-37, D-161 | s3.5 | Mark-as-paid settles the whole memo; row always written; partial is an off-by-default enhancement. |
| G-man-012 | - | Sale edit: reasons list, outlet-level QC lock, AMO's greyed Edit button | major | 2b | F-SR-033, F-AMO-014 | D-200, D-201 | s3.5 | Reason 1 wrong_sku, two to capture; outlet-level QC lock; AMO Edit follows the SR rules. |
| G-man-013 | - | Sale History (previous sales of an outlet by date) for SR and AMO; online-only in the current build | major | 2b | F-SR-054, F-AMO-013, F-API-025 | D-206, D-211 | s3.5 | Sale History built as hybrid with exact totals. |
| G-feat-42 | - | Selling to a newly captured outlet before approval | major | 2c | F-SR-078 | D-326 | s3.4 | Provisional outlet sale re-linked on approval. |
| G-man-033 | G-feat-61 | Outlet new / close / info-change forms select Cluster, not Route (SR and AMO) | major | 2c | F-SR-037, F-SR-038, F-SR-039, F-SR-076, F-AMO-025 | D-162, D-330 | s3.7 | Forms pick Cluster; route derived; both ids stored; add-to-my-route request. |
| G-feat-39 | - | Outlet without saved location; location-update approval path | major | 2d | F-SR-017, F-SR-018, F-AMO-028 | D-95 | s3.4 | No-location policy force_sale_required; first capture sets a missing or placeholder location only. |
| G-feat-18 | - | Attendance edge cases (missed check-in, forgotten check-out, device clock) | major | 2e | F-SR-011, F-SR-012 | D-209, D-329 | s3.3, s3.6 | Attendance edge cases defaulted: no-fix check-in, forgotten check-out, corrected time. |
| G-field-02 | G-feat-10 | No field-side day exception: rain/flood, hartal, market closed (haat day), DH out of stock, breakdown... | major | 2e | F-SR-059, F-AMO-045, F-WEB-065 | D-39 | s3.6, s4.8 | Day exception event raised by SR or AMO and approved by the TSO. |
| G-man-030 | - | Sales Deposit dues rule: the manuals say both 'warn' and 'block' | major | 2e | F-SR-035 | D-173 | s3.6 | Warn, never block; count dynamic; submitted_with_dues recorded. |
| G-man-031 | - | Sales Deposit reconciliation: row set, what each count measures, online/offline/failure states | major | 2e | F-SYS-009, F-SR-034 | D-222 | s2 | Reconciliation rows config-driven; Server column is the last server_totals. |
| G-qa-15 | - | No Bangla localisation review process or reviewer; "Bangla-first" has no acceptance step | major | 2e | F-SYS-018 | D-338 | s11.7 | Named Bangla reviewer and a side-by-side acceptance step before each release. |
| G-feat-01 | G-data-12 | Price compliance capture has no screen, fields or rules | major | 3a | F-SR-061, F-AMO-035 | D-334 | s3.4, s4.8 | Price compliance capture defined (ASSUMPTION) and counted in the AMO reconciliation row. |
| G-field-01 | - | Distribution house is not an actor: stock issue, stock return and cash hand-over are self-declared by the SR... | major | 3a | F-AMO-042, F-SR-051, F-SR-052, F-WEB-059 | D-324, D-325 | s4.8 | AMO as DH proxy by default; DH login if Q41 says so; settlement view. |
| G-field-05 | - | Wrong-user capture on a shared phone and no acting_for: SR B sells under A's session; AMO sells for a... | major | 3a | F-SR-072, F-AMO-041, F-ADM-063 | D-66, D-85 | s3.3, s4.3 | Identity confirmation, acting_for_user_id, audited re-attribution. |
| G-field-06 | G-feat-07 | Same-day cover cannot start: no AMO/TSO-app action creates a route_assignment(kind=cover); the substitute's... | major | 3a | F-AMO-037, F-ADM-062, F-ADM-003, F-API-043 | D-85 | s4.8 | Same-day cover from the AMO app; web interim in 2e. |
| G-man-011 | - | Whose dues an AMO sees and settles | major | 3a | F-AMO-014 | D-37 | s4.4 | AMO due label and dues count cover the AMO's own credit memos only. |
| G-man-015 | - | AMO dashboard 'Sale' tile vs Control Call > Sale: one geo-gated path | major | 3a | F-AMO-004, F-AMO-006, F-AMO-012 | D-221 | s4.3 | One geo-gated path for the Sale tile and Control Call. |
| G-man-018 | - | Update Base: no distance limits, no success message, no mock-GPS check | major | 3a | F-AMO-028 | D-95, D-111 | s4.6 | Update Base guards: distance, move limit, mock check, monthly cap, message. |
| G-man-032 | - | AMO day state: the AMO owns no route, so no route_day | major | 3a | F-AMO-039 | D-27 | s4.1 | supervisor_day separate from route_day. |
| G-man-034 | - | Outlet request lifecycle: verify in app or web, reject only on web, approve on web; all three request types | major | 3a | F-AMO-022, F-AMO-023, F-AMO-024, F-WEB-032, F-API-055 | D-43, D-172, D-230 | s4.6, s6 | Verify in app or web; reject and approve on the web; AMO বাতিল discards. |
| G-man-051 | G-feat-56 | Joint Call 5-step rubric: items 4 and 5 are not in the manual | major | 3a | F-AMO-011, F-ADM-020 | D-349 | s4.3 | Rubric as versioned data with two disabled placeholders. |
| G-man-052 | - | Joint Call: which SR is assessed, default star rating, untouched save | major | 3a | F-AMO-011 | D-349 | s4.3 | Assessed SR = route assignee on the date; default rating parity (confirm). |
| G-man-055 | - | SS designation and per-user menu variants in the AMO app | major | 3a | F-AMO-040, F-AMO-001 | D-187 | s4.1 | Per-user tile resolution; SS meaning unknown. |
| G-man-058 | G-feat-36 | Team Location is 'live' in the manuals, last-synced fix in the spec (AMO and TSO) | major | 3a | F-AMO-016, F-TSO-011 | D-170 | s4.5, s5.4 | Last synced fix with age and source, greyed after 120 min. |
| G-feat-17 | - | After final submit: reopen path and late syncs | major | 3b | F-ADM-029, F-TSO-010 | D-55 | s5.3, s7 | Late syncs accepted and flagged; reopen is an audited admin action. |
| G-feat-25 | G-man-074, G-man-075 | Leave approval by DMO has no UI; SR/AMO leave absent | major | 3b | F-TSO-008, F-TSO-009, F-WEB-046 | D-176, D-337 | s5.2, s6 | DMO approves TSO leave in the web; SR and AMO leave not built. |
| G-field-07 | G-sre-23 | Final submit has no delegation and no auto-close: TSO on leave or offline at 19:00 leaves the zone open... | major | 3b | F-TSO-026 | D-262 | s5.3 | Delegation and auto-close exist as options, off until confirmed. |
| G-man-070 | - | Final Submit needs a read endpoint: the duplicate check fires at 'Get Sales Data', before Submit | major | 3b | F-TSO-021, F-API-039 | D-82, D-235 | s5.3 | Preview endpoint feeds the alert at Get Sales Data. |
| G-man-071 | G-feat-64 | Final Submit rules: Sales Date, 'SR Not Set' routes, no confirm before an irreversible action, no time gate | major | 3b | F-TSO-010 | D-198 | s5.3 | Not Set routes do not block; confirm added; no time gate. |
| G-man-072 | - | Login & Bikroy Joma lists: row unit, 'Not Uploaded' is a superset, Dep Name, route-kind suffix | major | 3b | F-TSO-007 | D-236 | s5.1 | Rows are (user, route) pairs; Not Uploaded is a superset; Dep Name is the zone name. |
| G-man-073 | - | TSO dashboard sales tiles: day-target basis for the achievement ring, units and number format | major | 3b | F-TSO-002 | D-49 | s5.1 | Day-target basis switch, unit labels and one decimal; tile list is config. |
| G-feat-29 | - | Daily Tracking "take action" undefined | major | 4a | F-WEB-039 | D-335 | s6 | Take action = note plus notification. |
| G-feat-53 | - | Column sets of the 11 download-only reports unknown | major | 4b | F-WEB-040, F-WEB-013, F-WEB-014 | D-191 | s6 | ReportQuery object; real .xlsx samples requested to freeze the 11 column sets. |
| G-man-092 | - | DSS Report (Sales Summary): the pre-Final-Submit check report is not in the spec or plan | major | 4b | F-WEB-055 | D-184 | s6 | DSS Report built with sub-channel summary rows and route drill-down. |
| G-man-093 | - | DS-RRS Report with a Print view that shows discount data before Final Submit | major | 4b | F-WEB-053 | D-157 | s6 | DS-RRS built as report plus print view; layout needs a sample (unknown). |
| G-man-098 | G-feat-31 | Supervisory Module = AMO Call Report (contents were unknown in the spec) | major | 4b | F-WEB-054, F-ADM-025 | - | s6, s7 | Supervisory Module is the AMO Call Report. |
| G-feat-26 | - | DMO / WM / Top Management views unspecified | major | 4c | F-WEB-027, F-WEB-028, F-WEB-036, F-WEB-038, F-ADM-064 | D-336 | s6, s7 | DMO, WM, Top views are web-only pages with menus as data. |
| G-feat-21 | G-man-047 | Astha gift choice surface ("TSO portal") undefined | major | 5a | F-TSO-020, F-WEB-034, F-ADM-018 | D-192 | s5.7, s6 | The "TSO portal" is the web Astha Gift Choice panel. |
| G-man-040 | - | Outlet Points screen: expiring points, expiry date, as-of date | major | 5a | F-SR-055 | D-41 | s3.8 | Outlet Points with expiry and as-of date. |
| G-feat-22 | - | Superstar enrolment/slab/criteria rules | major | 5b | F-WEB-037, F-ADM-019 | D-332 | s6, s7 | Superstar default model; rules unknown. |
| G-feat-23 | - | Free sample capture screen missing | major | 5b | F-SR-062, F-WEB-026 | D-333 | s3.4, s6 | Free sample capture as is_free line. |
| G-feat-24 | G-man-068 | Target split formula and approval levels | major | 5c | F-ADM-014, F-ADM-015, F-WEB-030 | D-31 | s6, s7 | No automatic split; approval chain configurable. |
| G-feat-06 | - | SR transfer between routes mid-month | major | 6a | F-ADM-071 | D-331 | s7 | Effective-dated route transfer. |
| G-man-104 | - | Glossary of terms used on screen (OHS, OOS, SOQ, POSM, STD/STT, CPR, BSR, DSS, DS-RRS, GIGO, WMO, PDA, FF... | minor | 0c | - | - | s11.1 | Glossary written with confirmed, proposed and unknown status. |
| G-man-037 | - | Retailer / route label formats and mobile-number normalisation | minor | 1a | F-SYS-070, F-SR-016 | D-345 | s11.3 | Label templates, phone normalisation, dynamic case-insensitive alphabet strip. |
| G-feat-68 | - | Wholesale and C&C buyers: soft quantity ceiling and anomaly flag instead of a hard cap (P-16) | minor | 2a | F-SR-023 | D-260 | s3.4 | Soft ceiling plus anomaly flag. |
| G-feat-69 | - | Longer memos: 1.95 lines per call on average, max 40, a memo must support 60 lines; print length and screen... | minor | 2a | F-SR-023 | D-246 | s3.4 | 60-line memo; print and screen length tested. |
| G-man-043 | - | AV, KV and survey are assigned per outlet and shown in a fixed order (AV, KV, survey, sale) | minor | 2a | F-SR-020, F-ADM-020 | - | s3.4 | AV, KV, survey assigned per outlet in the order AV, KV, survey, sale. |
| G-man-054 | - | SR dashboard: hidden content below the KPI tiles, route label format, Summary tile has no manual page | minor | 2a | F-SR-010, F-SR-036, F-SR-008 | - | s3.2 | Hidden dashboard content to be captured; route label defined; Summary assumed equal to AMO Summary. |
| G-feat-04 | - | Memo reprint rules undefined | minor | 2b | F-SR-031, F-SR-066 | D-322 | s3.5 | Reprint with duplicate marker and edited-memo number. |
| G-feat-15 | - | The 3 sale-edit reasons are unnamed | minor | 2b | F-SR-033 | D-200 | s3.5 | Reasons held as config; first is wrong_sku. |
| G-man-009 | - | Credit-sale partial-payment dialog: validation, rounding and label | minor | 2b | F-SR-026 | D-213 | s3.4 | Dialog validation and exact-paisa label. |
| G-man-016 | - | Is an outlet photo required on every call, or only on Force Sale? | minor | 2c | F-SR-018 | D-347 | s3.4 | Photo only inside Force Sale; key cfg.sale.outlet_photo_every_call off. |
| G-man-019 | - | Photo + GEO capture: requiredness and feedback (new shop, info change, verification) | minor | 2c | F-SR-079, F-SR-037, F-SR-039 | - | s3.7 | Shared GEO and photo component with confirmation and one retake. |
| G-man-029 | - | Attendance UX: address + Refresh, press-and-hold sheet, four states, check-in gate, clock source | minor | 2e | F-SR-011, F-SR-012, F-AMO-003 | D-209, D-225 | s3.3 | Attendance UX: Refresh, hold-to-confirm, four states, 17:00 inclusive. |
| G-qa-19 | - | No accessibility / low-literacy UX check for the field apps (icon-only tiles, font scaling, colour-only KPI... | minor | 2e | F-SYS-018 | D-338 | s11.7 | Accessibility checks: tap size, font scale, colour never the only signal. |
| G-feat-57 | - | Control-call / joint-call targets origin | minor | 3a | F-AMO-002, F-ADM-025 | D-349 | s4.1 | Supervisor targets from an admin table; origin in Apsis unknown. |
| G-field-23 | - | On-the-spot verification during a joint call is impossible (request not yet on the server) | minor | 3a | F-AMO-046 | - | s4.3 | QR verification of a not-yet-synced request (IMPROVEMENT). |
| G-man-035 | - | Verification lists and forms: fields, mandatory flags, option lists, what the AMO sees | minor | 3a | F-AMO-022, F-AMO-023, F-AMO-024 | - | s4.6 | Required sub-channel and geo class; old-versus-new diff. |
| G-man-044 | - | AMO Survey screen is not documented | minor | 3a | F-AMO-043 | D-348 | s4.3 | Tile flagged until the screen is captured. |
| G-man-050 | - | SR Performance Assessment: OOS must be a subset of distributed; POSM tri-state; return behaviour | minor | 3a | F-AMO-007, F-AMO-008, F-AMO-009 | - | s4.3 | OOS subset of distributed; POSM tri-state; return behaviour. |
| G-man-056 | - | AMO dashboard badge sits on Outlet only, not on Task Delegation | minor | 3a | F-AMO-001 | D-169 | s4.1 | Badge on Outlet only, shown above zero. |
| G-man-057 | - | AMO dashboard header date, KPI tile definitions, collapse chevron | minor | 3a | F-AMO-001, F-AMO-002 | - | s4.1 | Header date and KPI tiles defined; collapse chevron. |
| G-man-061 | - | AMO Live Dashboard: explicit Filter press, and what 'মোট বিক্রয়' counts | minor | 3a | F-AMO-020 | - | s4.5 | Explicit Filter press; label of the sales figure to verify. |
| G-man-063 | - | AMO STD Memo Report: columns, date rules, scrolling | minor | 3a | F-AMO-032 | - | s4.7 | Date rules and frozen first column. |
| G-feat-48 | - | Feedback triage sink | minor | 3b | F-ADM-028, F-TSO-029 | - | s5.6, s7 | Feedback inbox and own-feedback list. |
| G-feat-49 | - | Visit plan completion rule and geo gate for Visit Query | minor | 3b | F-TSO-014, F-TSO-015 | D-327 | s5.5 | Completion on Visit Query submit; no geo gate. |
| G-man-027 | - | TSO app has no Settings (language, version, update, PDA to Support, password) | minor | 3b | F-TSO-024 | D-204 | s5.1 | Settings added as an IMPROVEMENT. |
| G-man-028 | - | TSO UI is English-first; CLAUDE.md says Bangla-first | minor | 3b | F-TSO-001, F-SYS-019 | D-181 | s5.1, s11.6 | Default English with a switch; bilingual catalogue. |
| G-man-076 | - | TSO dashboard charts: metric naming, drill-down glyph, empty charts, brand label | minor | 3b | F-TSO-003, F-TSO-004, F-TSO-005, F-TSO-006 | D-233 | s5.1 | Card contracts and labels. |
| G-man-077 | - | Final Submit pickers: five-level cascade, scope-bounded options, 'all zones' callout | minor | 3b | F-TSO-010 | - | s5.3 | Five scope-bounded dropdowns; Get Sales Data enabled at Zone. |
| G-man-078 | - | TSO map screens: radius unit and default, centre, markers, density, empty states | minor | 3b | F-TSO-011, F-TSO-012 | - | s5.4 | Radius unit, default, centre, marker cap, empty states. |
| G-man-079 | - | Set Plan: outlet card shows address (not cluster), invented 30-outlet cap, repeat Set Plan | minor | 3b | F-TSO-013 | D-175 | s5.5 | Card shows address else cluster; no cap; union on repeat. |
| G-man-080 | - | Visit plan lifecycle: Pending/Completed rule, Completed card, edit and delete | minor | 3b | F-TSO-014 | D-327 | s5.5 | Completed on Visit Query submit. |
| G-man-081 | - | Visit Query: free-text answers, Bangla labels, delegate default No, outcome of 'No' | minor | 3b | F-TSO-015 | D-199 | s5.5 | Two text questions, delegate default No. |
| G-man-082 | - | TSO Assign Task: assignee when 'SR Not Set', task-type list, due date | minor | 3b | F-TSO-016 | D-328 | s5.5 | Assignee rule and block for SR Not Set. |
| G-man-083 | - | TSO Feedback: categories, the 'My Feedback' sub-menu, image rules | minor | 3b | F-TSO-018, F-TSO-029 | D-197 | s5.6 | Categories config; picker image; own list. |
| G-man-084 | - | TSO app chrome: greeting, Home FAB, drawer subtitles, back button, pickers, date formats | minor | 3b | F-TSO-028 | - | s5.1 | Chrome parity spec. |
| G-man-096 | - | Web dashboard: tiles, charts and date-range filter beyond the spec's list | minor | 4a | F-WEB-001 | D-188 | s6 | Dashboard tiles, range Filter, unit labels. |
| G-man-094 | - | Route-wise reports: Memo Report missing; STD and BSR & CPR carry three names each | minor | 4b | F-WEB-056, F-WEB-013, F-WEB-015, F-WEB-018 | D-241 | s6 | One name per report with stored aliases; Memo Report added. |
| G-man-097 | - | Data Entry Log and Final Submit Log: column semantics (MIN/MAX/COUNT, Done/Not Done) | minor | 4b | F-WEB-016, F-WEB-017 | D-240 | s6 | MIN first and MAX last event time. |
| G-data-29 | - | "business"/"additional detail" outlet sections, QC page, Supervisory Module, Daily-Tracking "take action"... | minor | 4c | F-WEB-003, F-ADM-023, F-ADM-025, F-WEB-039 | D-335 | s6, s7 | Outlet sections, QC page, Supervisory Module and take action all defined. |
| G-feat-54 | - | Retailer web edit field lists; bypass of verification | minor | 4c | F-WEB-003 | D-43 | s6 | Section field lists to be captured; web edit follows verification rules. |
| G-man-042 | - | Gift redemption: scope of the 199-point cash cap, full catalogue, stepper and dialog rules | minor | 5a | F-SR-043 | D-219 | s3.8 | Cap scope unknown; basket per confirm; tornado fan 400. |
| G-man-045 | - | Astha views: route vs outlet table shapes, month chips ignored by the memo target, empty state | minor | 5a | F-SR-041, F-SR-042, F-AMO-031 | D-168, D-231 | s3.8, s4.7 | Two shapes by app; chips rule unknown. |
| G-man-048 | - | Loyalty Program - Diamond League Report (outlet-wise points) is not a listed report | minor | 5a | F-WEB-049 | - | s6 | Outlet-wise points statement report. |
| G-feat-19 | G-man-049 | Task lifecycle beyond pending/completed | minor | 5c | F-SR-046, F-SR-047, F-ADM-021 | D-328 | s3.9 | Two statuses, overdue computed, reassign by assignment. |
| G-feat-51 | - | Sales-plan change effect on a device holding stock | minor | 6a | F-ADM-006 | - | s7 | Plan change reaches phones at the next bundle; server flags, never rejects. |
| G-feat-62 | - | Outlet reopen / reactivation | minor | 6a | F-ADM-070 | D-330 | s7 | Admin reactivation with reason. |
| G-field-22 | - | Opening-balance provenance is not visible at the shop on cutover week | minor | 7a | F-SYS-068 | - | s2 | Opening-balance provenance in the bundle and on the outlet card. |

### Manual register entries owned by this document (70 of 104)

An entry merged into a master is resolved through the master's row above.

| Entry | Master | Phase | Features | Decisions |
| --- | --- | --- | --- | --- |
| G-man-003 | G-man-003 | 2a | F-SR-027, F-ADM-023 | D-34, D-159 |
| G-man-004 | G-feat-13 | 2a | F-SR-022, F-SR-024, F-ADM-016, F-ADM-061, F-WEB-023 | D-33, D-143, D-343 |
| G-man-005 | G-man-005 | 2a | F-SR-023 | D-220 |
| G-man-007 | G-man-007 | 2a | F-SR-014, F-AMO-029 | D-16, D-56 |
| G-man-008 | G-man-008 | 2a | F-SR-060 | D-78, D-160 |
| G-man-009 | G-man-009 | 2b | F-SR-026 | D-213 |
| G-man-010 | G-man-010 | 2b | F-SR-032 | D-37, D-161 |
| G-man-011 | G-man-011 | 3a | F-AMO-014 | D-37 |
| G-man-012 | G-man-012 | 2b | F-SR-033, F-AMO-014 | D-200, D-201 |
| G-man-013 | G-man-013 | 2b | F-SR-054, F-AMO-013, F-API-025 | D-206, D-211 |
| G-man-015 | G-man-015 | 3a | F-AMO-004, F-AMO-006, F-AMO-012 | D-221 |
| G-man-016 | G-man-016 | 2c | F-SR-018 | D-347 |
| G-man-018 | G-man-018 | 3a | F-AMO-028 | D-95, D-111 |
| G-man-019 | G-man-019 | 2c | F-SR-079, F-SR-037, F-SR-039 | - |
| G-man-027 | G-man-027 | 3b | F-TSO-024 | D-204 |
| G-man-028 | G-man-028 | 3b | F-TSO-001, F-SYS-019 | D-181 |
| G-man-029 | G-man-029 | 2e | F-SR-011, F-SR-012, F-AMO-003 | D-209, D-225 |
| G-man-030 | G-man-030 | 2e | F-SR-035 | D-173 |
| G-man-031 | G-man-031 | 2e | F-SYS-009, F-SR-034 | D-222 |
| G-man-032 | G-man-032 | 3a | F-AMO-039 | D-27 |
| G-man-033 | G-man-033 | 2c | F-SR-037, F-SR-038, F-SR-039, F-SR-076, F-AMO-025 | D-162, D-330 |
| G-man-034 | G-man-034 | 3a | F-AMO-022, F-AMO-023, F-AMO-024, F-WEB-032, F-API-055 | D-43, D-172, D-230 |
| G-man-035 | G-man-035 | 3a | F-AMO-022, F-AMO-023, F-AMO-024 | - |
| G-man-037 | G-man-037 | 1a | F-SYS-070, F-SR-016 | D-345 |
| G-man-040 | G-man-040 | 5a | F-SR-055 | D-41 |
| G-man-041 | G-feat-20 | 5a | F-SR-043, F-SR-021, F-SYS-061, F-ADM-017 | D-41 |
| G-man-042 | G-man-042 | 5a | F-SR-043 | D-219 |
| G-man-043 | G-man-043 | 2a | F-SR-020, F-ADM-020 | - |
| G-man-044 | G-man-044 | 3a | F-AMO-043 | D-348 |
| G-man-045 | G-man-045 | 5a | F-SR-041, F-SR-042, F-AMO-031 | D-168, D-231 |
| G-man-047 | G-feat-21 | 5a | F-TSO-020, F-WEB-034, F-ADM-018 | D-192 |
| G-man-048 | G-man-048 | 5a | F-WEB-049 | - |
| G-man-049 | G-feat-19 | 5c | F-SR-046, F-SR-047, F-ADM-021 | D-328 |
| G-man-050 | G-man-050 | 3a | F-AMO-007, F-AMO-008, F-AMO-009 | - |
| G-man-051 | G-man-051 | 3a | F-AMO-011, F-ADM-020 | D-349 |
| G-man-052 | G-man-052 | 3a | F-AMO-011 | D-349 |
| G-man-053 | G-man-053 | 2a | F-SR-056 | D-58 |
| G-man-054 | G-man-054 | 2a | F-SR-010, F-SR-036, F-SR-008 | - |
| G-man-055 | G-man-055 | 3a | F-AMO-040, F-AMO-001 | D-187 |
| G-man-056 | G-man-056 | 3a | F-AMO-001 | D-169 |
| G-man-057 | G-man-057 | 3a | F-AMO-001, F-AMO-002 | - |
| G-man-058 | G-man-058 | 3a | F-AMO-016, F-TSO-011 | D-170 |
| G-man-061 | G-man-061 | 3a | F-AMO-020 | - |
| G-man-063 | G-man-063 | 3a | F-AMO-032 | - |
| G-man-068 | G-feat-24 | 5c | F-ADM-014, F-ADM-015, F-WEB-030 | D-31 |
| G-man-070 | G-man-070 | 3b | F-TSO-021, F-API-039 | D-82, D-235 |
| G-man-071 | G-man-071 | 3b | F-TSO-010 | D-198 |
| G-man-072 | G-man-072 | 3b | F-TSO-007 | D-236 |
| G-man-073 | G-man-073 | 3b | F-TSO-002 | D-49 |
| G-man-074 | G-feat-25 | 3b | F-TSO-008, F-TSO-009, F-WEB-046 | D-176, D-337 |
| G-man-075 | G-feat-25 | 3b | F-TSO-008, F-TSO-009, F-WEB-046 | D-176, D-337 |
| G-man-076 | G-man-076 | 3b | F-TSO-003, F-TSO-004, F-TSO-005, F-TSO-006 | D-233 |
| G-man-077 | G-man-077 | 3b | F-TSO-010 | - |
| G-man-078 | G-man-078 | 3b | F-TSO-011, F-TSO-012 | - |
| G-man-079 | G-man-079 | 3b | F-TSO-013 | D-175 |
| G-man-080 | G-man-080 | 3b | F-TSO-014 | D-327 |
| G-man-081 | G-man-081 | 3b | F-TSO-015 | D-199 |
| G-man-082 | G-man-082 | 3b | F-TSO-016 | D-328 |
| G-man-083 | G-man-083 | 3b | F-TSO-018, F-TSO-029 | D-197 |
| G-man-084 | G-man-084 | 3b | F-TSO-028 | - |
| G-man-092 | G-man-092 | 4b | F-WEB-055 | D-184 |
| G-man-093 | G-man-093 | 4b | F-WEB-053 | D-157 |
| G-man-094 | G-man-094 | 4b | F-WEB-056, F-WEB-013, F-WEB-015, F-WEB-018 | D-241 |
| G-man-096 | G-man-096 | 4a | F-WEB-001 | D-188 |
| G-man-097 | G-man-097 | 4b | F-WEB-016, F-WEB-017 | D-240 |
| G-man-098 | G-man-098 | 4b | F-WEB-054, F-ADM-025 | - |
| G-man-101 | G-man-101 | 0c | F-SYS-018 | D-244 |
| G-man-102 | G-man-102 | 1a | F-SYS-018 | D-123 |
| G-man-103 | G-man-103 | 0c | F-SYS-051 | D-344 |
| G-man-104 | G-man-104 | 0c | - | - |

### Manual register entries owned by other documents and the features that implement them (34)

| Entry | Features here | Owner doc |
| --- | --- | --- |
| G-man-001 | F-SR-023, F-SYS-045, F-AMO-006 | 16 |
| G-man-002 | F-SR-025, F-SR-027, F-SR-069, F-AMO-015 | 16 |
| G-man-006 | F-SR-028, F-SR-025 | 17 |
| G-man-014 | F-SR-028, F-SR-015, F-SR-036, F-ADM-067 | 17 |
| G-man-017 | F-SR-018, F-AMO-005 | 21 |
| G-man-020 | F-SR-003, F-SYS-023, F-SYS-052 | 17 |
| G-man-021 | F-SYS-003, F-TSO-022, F-ADM-022, F-ADM-069 | 21 |
| G-man-022 | F-SYS-020, F-SR-004 | 17 |
| G-man-023 | F-SR-013 | 17 |
| G-man-024 | F-SYS-022, F-TSO-001 | 17 |
| G-man-025 | F-SYS-021, F-SR-006 | 17 |
| G-man-026 | F-SYS-036 (device class, minSdk) | 17 |
| G-man-036 | F-ADM-056, F-API-045 | 19 |
| G-man-038 | F-WEB-004 to F-WEB-009, F-ADM-004 | 16 |
| G-man-039 | F-WEB-042, F-WEB-002, F-WEB-063 | 21 |
| G-man-046 | F-WEB-048, F-API-051 | 19 |
| G-man-059 | F-SYS-074, F-AMO-016, F-AMO-028, F-TSO-011, F-TSO-012 | 17 |
| G-man-060 | F-AMO-017, F-TSO-017, F-SR-056 | 16 |
| G-man-062 | F-AMO-021, F-WEB-042 | 21 |
| G-man-064 | F-AMO-033 | 16 |
| G-man-065 | F-AMO-044, F-SYS-006 | 17 |
| G-man-066 | F-TSO-007, F-AMO-020, F-WEB-047 | 16 |
| G-man-067 | F-TSO-017, F-AMO-017, F-SR-056 | 16 |
| G-man-069 | F-TSO-027 | 17 |
| G-man-085 | F-WEB-050, F-API-050 | 19 |
| G-man-086 | F-WEB-051, F-ADM-058, F-API-048 | 19 |
| G-man-087 | F-ADM-057, F-API-049 | 19 |
| G-man-088 | F-ADM-006, F-ADM-001 | 19 |
| G-man-089 | F-WEB-052, F-WEB-060 to F-WEB-062, F-ADM-023 | 19 |
| G-man-090 | F-ADM-014, F-ADM-059, F-API-054 | 19 |
| G-man-091 | F-SYS-001, F-WEB-043, F-WEB-033 | 21 |
| G-man-095 | F-WEB-040, F-SYS-064, F-API-017 | 16 |
| G-man-099 | F-ADM-064, F-WEB-041 | 19 |
| G-man-100 | F-ADM-002, F-WEB-010, F-AMO-039 | 16 |

### New gaps raised by this document (G-15-01 to G-15-09)

| Gap | Title | Sev | Phase | Resolution or default |
| --- | --- | --- | --- | --- |
| G-15-01 | Home tiles Sales Journey and KPI exist in AKTCL screenshots and in no manual or spec (UI-SR-01, UI-SR-02) | major | 2a | F-SR-067, F-SR-068 hidden by cfg.app.home_tiles until captured from the live app (D-342) |
| G-15-02 | Outlet eligibility dot legend (colour to programme) and whether a dot hides when not eligible are unknown (UI-SR-22) | minor | 2a | F-SR-075, F-ADM-066; default per D-341 |
| G-15-03 | Memo discount table lists quantity and value ("SL Match 3 / 0.00"); three readings fit the evidence (UI-SR-26) | major | 2a | Discount lines stored as (sku, qty, value, kind) (D-343); meaning to be confirmed with AKTCL before the memo golden is signed |
| G-15-04 | What the Apsis printout shows on a reprint, and whether new paper (due receipt, cancel slip) is acceptable to retailers, is unknown (UI-SR-29) | minor | 2b | D-322 default; physical samples requested with D-158 |
| G-15-05 | Whether Save on Stock is blocked while the printer is not connected is unknown (UI-SR-17) | minor | 2a | Default: Save always works, Sales Submit warns on an unprinted slip (cfg.stock.require_printed_slip) |
| G-15-06 | 13 spec web pages have no evidence in the TSO manual (Task Planner, By-Route Geo Capture, Campaign Gift Redemption, Discount, By Outlet By Day, Online/Offline Sales, Free Sample, TSO Top Sheet, TSO Daily Tracking, Tutorial, Leaderboard, Superstar Report, Daily Tracking Dashboard): role and columns unknown | minor | 4a | Built from the spec (spec only) with menus as data (D-185, D-336); MQ-48 |
| G-15-07 | SR and AMO leave appear in no manual; the absence of a rep has no capture except day exception and cover | minor | 3b | D-337 |
| G-15-08 | The per-user rule that hides Loyalty Point and Photo Capture on one SR account (10 versus 12 tiles) is unknown | minor | 2a | Tile set per user through cfg.app.home_tiles (F-SR-009); rule to be read from the Apsis user table in the dump |
| G-15-09 | Per-surface status vocabulary: web outlet request shows Pending, Verified, Approved but Rejected is never printed; statuses of target sets other than "WMO approval pending" are not printed | minor | 3a | Status enums are config; Rejected and the other target statuses are authored (D-43, D-179) |

### Round-2 gaps closed in this document (G-qa-25 to G-qa-85)

Skeptic gap n is recorded as G-qa-(24+n). Only the gaps with a row or a section here are listed; the rest are owned by docs 14 and 16 to 21 and appear in their own Traceability sections.

| Gap | Skeptic gap | Closed here by | Decision | Gate |
| --- | --- | --- | --- | --- |
| G-qa-27 | 3 | s6.4 Parity Exceptions Register; F-WEB-070 to F-WEB-072 | D-502, D-503 | T-0-153, T-7-158 |
| G-qa-29 | 5 | F-SYS-079 (upload jitter at the 17:00 gate) | D-505 | T-4-155 |
| G-qa-31, G-qa-33 | 7, 9 | F-SYS-081 (telemetry.day and the field-evidence gates) | D-507, D-509 | T-2-153, T-2-154 |
| G-qa-37 | 13 | F-SYS-082 (Apsis delta contract and feed label) | D-514 | T-7-150..152 |
| G-qa-41 | 17 | F-SYS-080 and F-API-070 (digest and generation endpoints) | D-517 | T-1-152 |
| G-qa-48 | 24 | F-SYS-084 (held-rows list), OEM condition matrix in doc 17 s4.1b | D-511 | T-2-153 |
| G-qa-52, G-qa-77 | 28, 53 | F-ADM-077 (emergency widen and temporary relief) | D-527 | T-2-160 |
| G-qa-56 | 32 | s1.4 (manual-coverage.yaml) and s12.3 rule 11 | D-531 | T-0-46 |
| G-qa-61 | 37 | F-WEB-070 (existence of bex pages before 6a) | D-523 | T-7-158 |
| G-qa-39, G-qa-59, G-qa-69 | 15, 35, 45 | generated counts in s1.3 and the corrected stale counts | D-516 | T-0-152 |
| G-qa-63 | 39 | F-SR-018: the force-sale reason list (permission_denied is not a reason) | none (small correction) | T-2-77 |
| G-qa-65 | 41 | F-WEB-001 (service-zone and Final Submit ring) | D-536 | T-3-43 |
| G-qa-66 | 42 | F-SR-056 (symbolic till-date, K-13) | none (D-58 amended) | T-2-43, T-2-124 |
| G-qa-67 | 43 | s6.3, F-WEB-050 and F-WEB-048 | D-537 | T-4-46 |
| G-qa-68 | 44 | F-WEB-021 (locality_hint) | D-538 | T-4-42..46 |
| G-qa-70 | 46 | F-SR-080, F-TSO-030, F-ADM-074, F-API-069 (submit void) | D-539 | T-3-150 |
| G-qa-71, G-qa-72 | 47, 48 | F-ADM-072 (P19 Support desk), F-TSO-030, authority matrix in doc 19 s5.1b | D-540, D-541 | T-2-157, T-2-158 |
| G-qa-73 | 49 | F-ADM-073 (emergency non-working day) | D-542 | T-4-154 |
| G-qa-74 | 50 | F-ADM-075 (supervised paper-memo backfill) | D-543 | T-2-151 |
| G-qa-75 | 51 | F-WEB-068 (date stamp and reason chip), F-WEB-001 | D-544 | T-4-43 |
| G-qa-76 | 52 | F-AMO-048, F-TSO-031 (bulk approve of location requests) | D-545 | T-4-152 |
| G-qa-80 | 56 | F-SYS-083, F-WEB-069 (switched-route dimension, coverage banner) | D-548 | T-4-153 |
| G-qa-81 | 57 | F-SYS-085 (rollback return path) | D-549 | T-7-153 |
| G-qa-83 | 59 | F-AMO-047, F-ADM-076 (bulk mark absent, user wizard and disable) | D-551 | T-3-154, T-2-159 |

Counts after round 3: 509 features (SYS 97, SR 81, AMO 49, TSO 31, WEB 72, ADM 85, API 94), generated into s1.3 by script; after round 2 there were 477 (SYS 85, SR 80, AMO 48, TSO 31, WEB 72, ADM 77, API 84); the previous 439 and the earlier 428 are retired (D-516).

### Decisions used (287)

D-01 to D-269 are the skeleton's; D-320 to D-349 are minted in s9.3; D-500 to D-553 are the round-2 gap-resolution decisions (DECISIONS.md M3).

D-01, D-08, D-09, D-10, D-12, D-13, D-15, D-16, D-17, D-18, D-19, D-20, D-21, D-22, D-23, D-25, D-26, D-27, D-28, D-29, D-30, D-31, D-32, D-33, D-34, D-35, D-36, D-37, D-38, D-39, D-40, D-41, D-42, D-43, D-44, D-45, D-46, D-47, D-48, D-49, D-50, D-51, D-52, D-55, D-56, D-57, D-58, D-59, D-60, D-61, D-62, D-63, D-64, D-65, D-66, D-67, D-68, D-69, D-70, D-71, D-72, D-73, D-74, D-75, D-76, D-77, D-78, D-79, D-80, D-82, D-83, D-84, D-85, D-86, D-87, D-88, D-89, D-90, D-91, D-92, D-93, D-94, D-95, D-96, D-97, D-98, D-99, D-100, D-101, D-102, D-103, D-104, D-105, D-106, D-107, D-108, D-109, D-110, D-111, D-112, D-113, D-114, D-115, D-116, D-117, D-118, D-119, D-120, D-121, D-123, D-126, D-128, D-129, D-130, D-131, D-132, D-136, D-138, D-140, D-143, D-145, D-147, D-148, D-150, D-152, D-153, D-154, D-157, D-158, D-159, D-160, D-161, D-162, D-163, D-164, D-165, D-166, D-167, D-168, D-169, D-170, D-171, D-172, D-173, D-174, D-175, D-176, D-177, D-178, D-179, D-180, D-181, D-182, D-183, D-184, D-185, D-186, D-187, D-188, D-189, D-190, D-191, D-192, D-195, D-196, D-197, D-198, D-199, D-200, D-201, D-202, D-203, D-204, D-205, D-206, D-207, D-208, D-209, D-211, D-212, D-213, D-214, D-216, D-217, D-218, D-219, D-220, D-221, D-222, D-223, D-224, D-225, D-226, D-227, D-229, D-230, D-231, D-233, D-234, D-235, D-236, D-237, D-238, D-239, D-240, D-241, D-242, D-243, D-244, D-246, D-247, D-250, D-251, D-253, D-254, D-257, D-258, D-259, D-260, D-261, D-262, D-263, D-264, D-265, D-266, D-269, D-320, D-321, D-322, D-323, D-324, D-325, D-326, D-327, D-328, D-329, D-330, D-331, D-332, D-333, D-334, D-335, D-336, D-337, D-338, D-339, D-340, D-341, D-342, D-343, D-344, D-345, D-346, D-347, D-348, D-349

Round-2 additions: D-471, D-480, D-486, D-500, D-501, D-502, D-503, D-505, D-507, D-509, D-511, D-514, D-516, D-517, D-523, D-527, D-531, D-533, D-536, D-537, D-538, D-539, D-540, D-541, D-542, D-543, D-544, D-545, D-548, D-549, D-551.

### Questions used

Q: Q1, Q4, Q5, Q6, Q8, Q9, Q11, Q12, Q13, Q14, Q15, Q16, Q17, Q24, Q41, Q42, Q43, Q44, Q45, Q47, Q49, Q50, Q52, Q53, Q57. MQ: MQ-01, MQ-02, MQ-03, MQ-04, MQ-05, MQ-06, MQ-09, MQ-17, MQ-18, MQ-19, MQ-20, MQ-22, MQ-26, MQ-29, MQ-33, MQ-34, MQ-39, MQ-41, MQ-43, MQ-48.

### Gate families used

T-0-40..49, T-0-70..76, T-1-01..04, T-1-20..24, T-1-20..34, T-1-24, T-1-25..34, T-1-35, T-1-41, T-1-51..52, T-1-60..69, T-2-10..19, T-2-21..24, T-2-26, T-2-30, T-2-31..35, T-2-36..44, T-2-41, T-2-43, T-2-47..49, T-3-20..24, T-3-25..28, T-3-41, T-3-42, T-4-14, T-4-41, T-4-42..46, T-4-51..52, T-4-70..76, T-5-10, T-5-41, T-5-41..43, T-5-42, T-5-43, T-6-41, T-6-43, T-6-51, T-6-60..69, T-7-80..82, T-7-80..89, T-7-83..87. Each is a family or id named in doc 14 s2 or in the field critic's gate list; doc 20 s3 places the individual ids.

### Config keys named (290)

Doc 19 s3 must hold a registry row for each; rtm-check fails the build otherwise. Names follow the master plan naming rules (retired aliases are not used).

| Area | Keys |
| --- | --- |
| agg | cfg.agg.poll_interval_s |
| app | cfg.app.alphabet_filter, cfg.app.default_locale, cfg.app.drawer_items.tso, cfg.app.health_warn_battery_pct, cfg.app.hold_to_confirm_ms, cfg.app.home_tiles, cfg.app.image_cache_mb, cfg.app.kpi_strip_items, cfg.app.local_history_days, cfg.app.location_denied_policy, cfg.app.logout_block_when_pending, cfg.app.logout_wipes_data, cfg.app.outlet_list_label_format, cfg.app.route_label_format |
| astha | cfg.astha.gift_catalog, cfg.astha.gift_choice_lock, cfg.astha.gift_choice_roles, cfg.astha.memo_target_month_filter, cfg.astha.quarter_start_month |
| auth | cfg.auth.access_ttl_min, cfg.auth.confirm_identity_on_first_capture, cfg.auth.lockout_attempts, cfg.auth.max_devices_per_user, cfg.auth.max_users_per_device, cfg.auth.mfa_required_roles, cfg.auth.offline_unlock_max_days, cfg.auth.otp_length, cfg.auth.otp_ttl_min, cfg.auth.otp_visible_roles, cfg.auth.password_history_depth, cfg.auth.password_min_age_h, cfg.auth.password_min_len, cfg.auth.refresh_ttl_days, cfg.auth.reverify_on_new_version, cfg.auth.web_remember_me_days, cfg.auth.web_session_idle_min |
| bundle | cfg.bundle.regen_max_per_s, cfg.bundle.stale_max_days |
| calendar | cfg.calendar.holidays, cfg.calendar.weekend_days |
| content | cfg.content.tutorial_videos |
| credit | cfg.credit.allow_partial_collection, cfg.credit.allow_zero_payment, cfg.credit.collection_partial_roles, cfg.credit.max_days, cfg.credit.max_due_mtk, cfg.credit.partial_payment_min_pct |
| dashboard | cfg.dashboard.channels, cfg.dashboard.tile_info, cfg.dashboard.tiles, cfg.dashboard.unit_labels |
| day | cfg.day.checkin_gate, cfg.day.checkout_earliest_time, cfg.day.exception_max_days, cfg.day.exception_reasons, cfg.day.exception_requires_approval, cfg.day.final_submit_allow_not_set_routes, cfg.day.final_submit_autoclose_time, cfg.day.final_submit_confirm, cfg.day.final_submit_delegate_roles, cfg.day.final_submit_earliest_time, cfg.day.final_submit_requires_dss_ack, cfg.day.multi_visit_same_outlet_policy, cfg.day.reopen_roles, cfg.day.sales_submit_dues_warning, cfg.day.sales_submit_offline_queue, cfg.day.submit_settle_timeout_min, cfg.day.take_action_after |
| drp | cfg.drp.shortcut_steps |
| feedback | cfg.feedback.categories, cfg.feedback.max_images |
| flag | cfg.flag.amo_survey, cfg.flag.new_app_login_enabled, cfg.flag.parallel_run_mode |
| geo | cfg.geo.first_capture_sets_location, cfg.geo.fix_reuse_max_age_s, cfg.geo.fix_timeout_s, cfg.geo.integrity_weight, cfg.geo.max_accuracy_m, cfg.geo.max_speed_kmh, cfg.geo.mock_policy, cfg.geo.no_location_policy, cfg.geo.outlet_location_change_approval, cfg.geo.override_max_per_day, cfg.geo.radius_increase_escalation_m, cfg.geo.radius_m, cfg.geo.radius_max_m, cfg.geo.radius_min_m, cfg.geo.refresh_max, cfg.geo.require_precise, cfg.geo.tso_radius_mode, cfg.geo.update_base_max_distance_m, cfg.geo.update_base_max_move_m |
| i18n | cfg.i18n.date_style, cfg.i18n.digit_script, cfg.i18n.grouping, cfg.i18n.overrides |
| kpi | cfg.kpi.bands, cfg.kpi.bar_bands, cfg.kpi.card_pct_cap, cfg.kpi.count_abandoned_visits, cfg.kpi.daily_target_basis, cfg.kpi.dues_buckets, cfg.kpi.submit_pct_denominator, cfg.kpi.target_route_kinds, cfg.kpi.tilldate_basis.<surface>, cfg.kpi.tilldate_basis.amo_sales_summary, cfg.kpi.tilldate_basis.amo_team_performance, cfg.kpi.tilldate_basis.sr_ads, cfg.kpi.tilldate_basis.tso_target_status, cfg.kpi.tilldate_rounding.sr_ads, cfg.kpi.tilldate_rounding.tso_target_status |
| leave | cfg.leave.allow_past_days, cfg.leave.approver_role_by_applicant_role, cfg.leave.balance_enforced, cfg.leave.block_overlap, cfg.leave.max_consecutive_days, cfg.leave.types |
| loyalty | cfg.loyalty.cash_max_points, cfg.loyalty.cash_rate_mtk_per_point, cfg.loyalty.earning_rules, cfg.loyalty.expiry_days, cfg.loyalty.gift_catalog |
| map | cfg.map.3d_enabled, cfg.map.api_key_ref, cfg.map.provider, cfg.map.tile_cache_mb |
| media | cfg.media.evidence_mobile_fallback_h, cfg.media.long_edge_px, cfg.media.photo_max_kb, cfg.media.wifi_only_default |
| memo | cfg.memo.allow_negative_net, cfg.memo.due_balance_staleness_marker, cfg.memo.edit_after_print_policy, cfg.memo.edit_reasons, cfg.memo.edit_roles, cfg.memo.number_format, cfg.memo.print_void_slip, cfg.memo.reprint_max, cfg.memo.reprint_watermark, cfg.memo.rounding_mode, cfg.memo.void_reasons |
| ops | cfg.ops.dashboard_refresh_min_s, cfg.ops.push_enabled, cfg.ops.report_export_max_rows, cfg.ops.sync_hold_by_version |
| outlet | cfg.outlet.amo_request_approval, cfg.outlet.approve_roles, cfg.outlet.close_block_if_dues, cfg.outlet.close_block_if_dues, cfg.outlet.mobile_regex, cfg.outlet.reject_requires_reason, cfg.outlet.sell_before_approval, cfg.outlet.verify_requires_geo_class, cfg.outlet.verify_requires_subchannel, cfg.outlet.verify_roles, cfg.outlet.wholesale_marking_roles, cfg.outlet.wholesale_price_type, cfg.outlet.wholesale_unmark_allowed |
| pii | cfg.pii.field_roles, cfg.pii.mask_style |
| print | cfg.print.confirm_after_print, cfg.print.due_receipt, cfg.print.models, cfg.print.pairing_pins, cfg.print.template_version |
| promo | cfg.promo.rules |
| qc | cfg.qc.expired_stock_months, cfg.qc.fault_types, cfg.qc.max_amount_basis, cfg.qc.max_qty_per_cell, cfg.qc.web_entry_roles, cfg.qc.web_reentry_policy |
| release | cfg.release.finish_offline_day_before_force, cfg.release.min_version, cfg.release.update_prompt_policy, cfg.release.update_wifi_only, cfg.release.wave_pct |
| report | cfg.report.amo_call_types, cfg.report.default_range, cfg.report.include_today, cfg.report.max_range_days, cfg.report.page_size_default, cfg.report.page_size_options, cfg.report.std_criteria_divisor |
| route | cfg.route.allow_unplanned_day, cfg.route.cover_max_days, cfg.route.cover_requires_tso_approval |
| rubric | cfg.rubric.default_rating, cfg.rubric.distribution_brands, cfg.rubric.joint_call, cfg.rubric.require_all_rated |
| sale | cfg.sale.allow_price_type_override, cfg.sale.call_start_prompt, cfg.sale.force_reasons, cfg.sale.max_line_qty_base, cfg.sale.max_lines_per_memo, cfg.sale.outlet_photo_every_call, cfg.sale.price_compliance_sr, cfg.sale.qty_entry_unit, cfg.sale.require_printer_before_sale, cfg.sale.sort_by_distance, cfg.sale.stock_check, cfg.sale.suggested_qty_enabled |
| sales_plan | cfg.sales_plan.edit_roles |
| stock | cfg.stock.max_issue_qty, cfg.stock.require_printed_slip |
| support | cfg.support.contacts, cfg.support.max_upload_mb, cfg.support.pda_upload_wifi_only |
| survey | cfg.survey.amo_questions, cfg.survey.posm_questions, cfg.survey.tso_visit_query_questions |
| sync | cfg.sync.batch_max_rows, cfg.sync.debounce_s, cfg.sync.family_skip_after, cfg.sync.max_backdate_days, cfg.sync.max_clock_skew_min, cfg.sync.periodic_min, cfg.sync.reason_texts, cfg.sync.reconcile_types, cfg.sync.resync_window_h, cfg.sync.retry_backoff_s |
| sys | cfg.sys.schedule_horizon_days |
| target | cfg.target.achievement_pct_cap, cfg.target.approval_levels, cfg.target.entry_window, cfg.target.product_types, cfg.target.split_method, cfg.target.supervisor_targets, cfg.target.template_version, cfg.target.types, cfg.target.upload_max_rows |
| task | cfg.task.allow_past_due_date, cfg.task.description_max_len, cfg.task.statuses, cfg.task.types |
| telemetry | cfg.telemetry.device_max_bytes_per_day |
| tso | cfg.tso.assignable_task_types, cfg.tso.dashboard_cards, cfg.tso.dashboard_tiles, cfg.tso.periphery_default_radius_m, cfg.tso.periphery_max_markers, cfg.tso.periphery_radius_options_m, cfg.tso.plan_backdate_days, cfg.tso.plan_max_days_ahead, cfg.tso.product_scope, cfg.tso.team_location_max_age_min, cfg.tso.visit_plan_max_outlets, cfg.tso.visit_query_answer_max_chars |
| ui | cfg.ui.date_format, cfg.ui.outlet_badges |
| visit | cfg.visit.closed_streak_task, cfg.visit.outcome_codes |
| web | cfg.web.delete_section_data_before_final_only, cfg.web.delete_section_data_roles, cfg.web.delete_section_data_scope, cfg.web.entry_backdate_days, cfg.web.entry_classes, cfg.web.entry_enabled_zones, cfg.web.entry_unlock_max_days, cfg.web.entry_unlock_roles, cfg.web.entry_validate_calls_le_target, cfg.web.menu_by_role |

Wildcard areas referenced (all keys of the area): cfg.astha.*, cfg.auth.*, cfg.calendar.*, cfg.content.*, cfg.flag.*, cfg.geo.*, cfg.loyalty.*, cfg.ops.*, cfg.outlet.*, cfg.promo.*, cfg.release.*, cfg.report.*, cfg.report.lists.*, cfg.retention.*, cfg.route.*, cfg.rubric.*, cfg.sec.fraud.*, cfg.sla.*, cfg.stock.*, cfg.superstar.*, cfg.survey.*, cfg.target.*.

Keys minted by this document, with no source registry row yet (doc 19 now holds them, s3.2.10 and the s3 area tables): cfg.sale.stock_check (D-321), cfg.outlet.sell_before_approval (D-326), cfg.sale.price_compliance_sr (D-334) and the four surface suffixes of cfg.kpi.tilldate_basis.<surface> and cfg.kpi.tilldate_rounding.<surface> (sr_ads, tso_target_status, amo_team_performance, amo_sales_summary; D-51 names the surfaces, not the suffixes).

### Added at the editorial merge

**Decisions that DECISIONS.md assigns to this document and that it applies without an inline citation:** D-300, D-301, D-302, D-304, D-306, D-309. Most are "See D-nn" aliases of a decision cited above or decisions raised by doc 14; the substance was not re-verified row by row.

### Added in round 2

Round-2 additions to the key list (all registered in doc 19 s3.2.10): cfg.calendar.emergency_declare_roles, cfg.calendar.emergency_max_days, cfg.day.bulk_absent_max_routes, cfg.day.sales_submit_locks_capture, cfg.day.submit_undo_window_min, cfg.entry.paper_backfill_window_days, cfg.geo.emergency_widen_max_hours, cfg.geo.radius_emergency_max_m, cfg.geo.radius_incident_ceiling_m, cfg.kpi.leaderboard_min_switched_pct, cfg.outlet.bulk_approve_consistent_m, cfg.outlet.bulk_approve_min_evidence, cfg.route.vacancy_alert_days, cfg.sla.location_request_escalate_h, cfg.sla.pending_rows_alert_h, cfg.sync.checkout_upload_jitter_max_s, cfg.sync.digest_days, cfg.sync.resync_safety_margin_h, cfg.sys.temporary_relief_max_h, cfg.telemetry.bat17_floor_pct, cfg.telemetry.enabled, cfg.user.admin_checker_required, cfg.user.dismissal_upload_grace_h. The parity-exception features F-WEB-070 to F-WEB-072 use the existing cfg.web.menu_by_role and add no key (D-502).

### Added by the round-3 gap resolution (D-554 to D-601)

| Kind | Ids | Handled in |
| --- | --- | --- |
| Gaps | G-qa-86, G-qa-91, G-qa-96, G-qa-99, G-qa-102, G-qa-107, G-qa-114, G-qa-115, G-qa-116, G-qa-117, G-qa-118, G-qa-119, G-qa-120, G-qa-121, G-qa-125, G-qa-126, G-qa-128, G-qa-129, G-qa-133, G-qa-134, G-qa-138, G-qa-139 | s1.3, s7.1, s8.2, s10, s12.3b, s12.6 |
| Decisions | D-554, D-556, D-559, D-563, D-567, D-571, D-572, D-577, D-578, D-579, D-580, D-581, D-582, D-583, D-584, D-585, D-586, D-587, D-589, D-590, D-594, D-595, D-599, D-600 | as above |
| Features added | F-SR-081, F-AMO-049, F-SYS-086 to F-SYS-097, F-ADM-078 to F-ADM-085, F-API-077 to F-API-086 (32) | s2, s3, s4, s7, s8 |
| Features re-phased | F-SYS-010, F-API-007, F-API-057 (2c to 2a); F-ADM-005, 006, 036, 070, 071 (minimal in 2e); F-API-067, F-API-068 (3b to 2e); F-SYS-041 (6c to 7a) | s2, s7, s8 |
| Gates | T-1-155, T-1-156, T-2-131 to T-2-138, T-2-161 to T-2-172, T-3-158 to T-3-160, T-4-166 to T-4-168, T-5-123, T-6-150, T-6-152, T-7-163, T-7-164 | s12.6 |
| Config keys | cfg.ui.visit_screen_buttons, cfg.stock.save_mode, cfg.stock.correct_total_enabled, cfg.stock.resave_guard_window_min and the keys named in the new rows | doc 19 s3.2.11 |
