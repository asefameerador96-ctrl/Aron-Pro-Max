# Start here

This repository holds the plan and specification for rebuilding Aron, AKTCL's field-sales platform, in-house. This page is the entry point: what exists, what the plan says in one page, what AKTCL must decide, and where the detail lives. Every number below is quoted from the documents named beside it; nothing here is new.

## Read in this order

| Who | Read | Why |
|---|---|---|
| Sponsor, Board | this page, then `docs/14-master-build-plan.md` s1.2, s2.1, s2.5b, s4, s6 | the six requirements as checks, the schedule, the Board envelope, what you must decide, the risks |
| Business owner (field operations) | `docs/ui-reference/` and `docs/ui-reference/questions.md` | the screens as built today, and the questions only you can answer |
| Tech lead | `docs/14` s1.3, then 16 (data), 17 (sync), 18 (scale), 21 (security) | the design |
| Claude Code build session | `CLAUDE.md`, then `DECISIONS.md`, then the sub-milestone in `docs/14` s3 and the owning document | the order of work |

## The plan on one page

**What gets built**: 509 features (188 reproduce today's behaviour, 65 change it, 256 are new), from the field apps to the admin console (`docs/15`).

**In what order** (`docs/14` s2.1; weeks are the plan's assumption of 8 to 10 engineers, Week 1 starting Sunday 2026-10-11):

| Phase | What exists at the end | Weeks | Ends week |
|---|---|---|---|
| 0 Foundations | repo, CI/CD, schema v2, login with device binding and server-side scope, string catalogue; nothing sells yet | 3 | 3 |
| 1 Vertical slice | one rep, one sale, **offline, then synced, then on one dashboard tile**, with the device count equal to the server count | 4.5 | 7.5 |
| 2 Full SR day | the whole selling day: promotions, QC, dues, outlets, geofence rules, day close; pilot-ready | 9 | 16.5 |
| 3 Supervisors | AMO and TSO apps; Final Submit closes a zone-day | 5 | 21.5 |
| 4 Web | dashboards and reports from stored data only; load tests for 8,500 users | 6.5 | 31 |
| 5 Programmes | Astha, Diamond League, Superstar, targets | 5 | 37 |
| 6 Admin | every master record editable, full config console | 4 | 38 |
| 7 Migration and cutover | shadow import, pilot, waves, decommission | 10+ | 42.5+ |

First production wave is planned for week 37 (Sunday 2027-06-20); the plan allows 34 to 38 weeks to get there. Decommissioning Apsis ends about 2027-08-04. Each sub-milestone has an entry condition, an exit that can be run by hand, and a demo of at most 30 minutes, closed by an exit report with two signatures.

**Your six requirements, as checks** (`docs/14` s1.2):

| | Headline check |
|---|---|
| R1 Data | every captured field is a typed column; the web has no access to transactional tables; a new report is a new view, not a code change |
| R2 Features | every feature has a test gate; all 104 manual-register findings are closed by an owner document; the printed memo matches a signed baseline |
| R3 Scale | design point 4.5 lakh calls and 5 lakh visits a day, 8,500 users, tested at 1.5 times the fleet; morning refresh storm about 130 requests a second; ingest proven at 8,000 rows a second; dashboards p95 at most 1 second |
| R4 Battery | on the reference phone over an 8-hour day: non-screen drain at most 6% of a 5,000 mAh battery, at most 80 GPS fixes, at most 1 MB a day without photos and 3 MB with them |
| R5 Offline and immediate sync | a full day in airplane mode works including print; online rows are acknowledged within 60 seconds without pressing Sync; kill-and-relaunch loses and doubles nothing |
| R6 Admin config | an admin changes the geofence radius (eight levels, 20 to 2,000 m) with a reason; critical keys need a second approver; the change reaches 95% of selling phones within 15 minutes |

## What AKTCL must decide

101 decisions are marked MUST-CONFIRM, each with a default the build uses until you answer, and each due at a named sub-milestone (`docs/14` s4.2). The ones due first:

| By | Decision |
|---|---|
| At sub-milestone 0a (week 1) | name the sponsor's delegate and a deputy (D-156); give the Board envelope: engineer rate, Apsis fee and **contract end date**, team size (D-555, `docs/14` s2.5b) |
| Week 1 | send the Apsis data-dump request letter (D-303); start the legal questions on data residency, PII and employee-location notice (D-05; answers due before the shadow import) |
| By the end of Phase 0 | the fleet census: which phone models, RAM and Android versions (D-11, D-12) |
| By sub-milestone 1a (about week 4.5) | physical 58 mm samples of the printed memo, stock slip and day summary (`ui-reference/questions.md` Q-UI-09) |
| By sub-milestone 2a (week 7.5 to 10) | the promotion catalogue; units for lighters and matches; what the memo's Discount section lists; the dot colours; what the Sales Journey, KPI and Cluster screens do |

## What the plan costs and how far to trust it

- People: 333 to 437 person-weeks (`docs/14` s2.4). The rate is unknown, so no money figure is given. Azure is about USD 1,780 a month at pilot size, 3,690 at wave 1 and 6,630 at full fleet (`docs/18` s3.3).
- A team of 4 to 5 instead of 8 to 10 moves wave 1 from about week 37 to week 66 to 80 (`docs/14` s2.5b).
- Weeks and the team size are **assumptions**; they are re-baselined at the end of Phase 1c from measured speed.
- An independent skeptic round (four reviewers, two rounds) concluded: **sign off to start Phase 0 and 1; not yet for the 8,500-user launch.** The open points are plan edits, not redesign; the main one is that AKTCL cannot make the old Apsis app read-only, so selling in both systems on a switched route must be detected and prevented (`docs/14` s7, `docs/evidence/planning/review-report.md`).
- The plan documents are 2.7 MB and were checked by scripts and targeted reading, not line by line. One mechanical check was run afterwards by hand: the data-platform SQL was loaded into PostgreSQL 16 (173 tables created; the macros it documents as expanded by a preprocessor were left unexpanded). It exposed two genuine errors, now fixed: the primary keys of `dw.fact_device_integrity` and `dw.fact_activity` lacked the partition column.
- `CLAUDE.md` and `README.md` still point at `docs/12-phases.md`, which `docs/14` supersedes. The ready-to-paste precedence text is in `docs/14` s9.2b (D-534); it is for the sponsor to apply to `CLAUDE.md`.

## Where things are

| Path | Holds |
|---|---|
| `docs/01` to `13` | the original specification (still binding where the plan does not change it) |
| `docs/14` to `21` | the plan: master plan, features, data, sync, scale and Azure, admin and config, tests, security |
| `docs/22` | what the Apsis data sample shows (volume, calendar, outlets, route kinds) |
| `docs/ui-reference/` | screenshots of the current apps with findings, and the question list |
| `docs/evidence/manuals/` | the four user manuals as screen, rule and message inventories, and the register of 104 findings |
| `docs/evidence/verification/` | independent re-checks of the contested manual claims against the PDFs |
| `docs/evidence/planning/` | the seven specialist analyses, four critic reports and the plan skeleton |
| `DECISIONS.md` | every decision with its status |
| `scripts/` | `profile-apsis-data.py` (regenerates `docs/22`), `update-agent-skills.sh` |
| `.claude/` | hooks and the vendored Android and Azure skills |

Phone numbers in the evidence files were redacted. Raw Apsis data is not in the repository.
