# 23 — Build plan: 7 days, hard cap 10

> **Environment rule (binding, 2026-10-06):** the Azure subscription and Google project in use are temporary TEST accounts at pilot size (5 to 10 users). Fleet sizing (8,500 users), zone/geo redundancy, Front Door/WAF, read replicas, quota requests and the full load/failover proof belong to the later FINAL account. See `docs/28-environment-profiles.md`. Where this page says otherwise, docs/28 wins.
>
> **Scope update 2026-10-06 (binding):** target, loyalty/Astha and discount/promotion programmes are deferred; see `docs/27-deferred-programmes.md`. Where this page lists them, docs/27 wins.


**Status: v3, 2026-10-06 (v2 of 2026-10-05 plus the programme deferral and the test/final environment split). Sponsor decisions applied.** This replaces the schedule of `docs/14` (34 to 38 weeks, 8 to 10 engineers). `docs/14` to `22` stay as reference for rules, schema and risks; where they conflict with this page, this page wins.

## 1. Targets and rules

- **Environments:** build the whole system, run it at **pilot size on a temporary test account**; design every part so that the move to the final account and the 8,500-user sizing is a parameter change plus a data restore (`docs/28`).
- **Deadline:** 7 days. If needed, 3 more days (day 10). Nothing beyond day 10.
- **Scope: everything.** Everything the current Aron's three apps and web do (`docs/06` to `10`, corrected by the manuals and the screenshots in `docs/ui-reference/`), plus what the sponsor added: native Kotlin apps, precise low-battery geo tracking, fencing and validation, anti-spoofing, scheduled app blocking from check-in to check-out, offline with immediate sync, an admin portal that controls everything without a backend change, and an Azure back end built not to slow down or lose data.
- **Not in this build:** Apsis data import and cutover (Apsis is switched off when we are ready), and the Phase 2 portals (discount, Astha, target, indent, the BOD "mother dashboard", the target engine). Phase 1 keeps their hooks (section 3).
- **The backlog** is `docs/25-build-backlog.md`, written on Day 1 from `docs/15`: every parity and changed feature (253) is built; of the 256 "new" features of the earlier plan, those the sponsor's list or the running of the system needs are built, and the ones that are process rather than product (runbook libraries, parity-exception registers and the like) are not. The dropped list goes to the sponsor for review.

## 2. How the work is divided

The work is cut into small tasks, each owned by one expert agent with a written acceptance test.

| Lane (owns this folder) | Expert agents |
|---|---|
| `contract/`, `shared/` (Kotlin Multiplatform) | API contract; shared business rules: money, memo totals, discount lines, geofence maths, business date |
| `db/` | schema and migrations; seed data |
| `backend/` | auth and scope; sync (bundle, batch, ingest, idempotency); master data; config service; aggregates and dashboards API; notifications; media |
| `android/` | core (database, outbox, sync); geo and integrity; device-owner policy; printing; SR app; AMO app; TSO app; shared UI |
| `web/` | dashboards and reports; admin portal; maps |
| `infra/` | Azure as code; CI/CD; observability |
| `qa/` | contract tests; sync property tests; Android tests; load and chaos tests; security review |

Rules that keep it accurate:

1. **Contract first.** The API contract and the shared module land on Day 1, so lanes work in parallel without drifting.
2. **Builder, then checker.** Every task is built by one agent and then checked by a different agent that runs the build and tests and reviews the diff against the acceptance test. Nothing merges on one agent's word.
3. **One folder, one owner.** Lanes never edit each other's folders; changes go through the contract.
4. **Parallel sessions.** Each lane runs in its own Claude session on its own machine, on its own branch, merged to `main` through CI at least daily.
5. **Daily proof.** CI builds the APKs and the web app each day; you run a 10-minute check.

## 3. Architecture

```
 SR / AMO / TSO apps (native Kotlin)        Web (Next.js): dashboards + admin portal
   Room database + outbox                            |
   WorkManager, FCM nudge                            |
   Device-owner policy                               |
          \                                          /
           \______ HTTPS, gzip, idempotent _________/
                           |
                 Azure Front Door (TLS, WAF; final account only)
                           |
        Azure Container Apps: API (Ktor)    Worker (aggregates, jobs)
                           |                       |
        Azure Database for PostgreSQL (final: zone-redundant HA; test: single zone)
        (pooling; read replica in the final account; point-in-time restore)
                           |
        Blob Storage (photos by SAS, bundle snapshots)   Key Vault   App Insights
```

- **Offline first.** Every action is written to the phone's database and an outbox in one transaction. Sync sends it as soon as there is a connection, with a short debounce and no polling; the server saves it by a phone-made UUID, so a retry never doubles anything. Push (FCM) only nudges.
- **Dashboards read stored aggregates**, never the transaction log.
- **Hooks for Phase 2:** offers, targets, programmes and stock movements are data (rule tables, append-only ledgers); every record has a stable external reference; domain events go to an outbox that a later mother dashboard or indent portal can subscribe to.
- **Stack:** native Kotlin with Jetpack Compose, three apps from one Gradle build; Kotlin (Ktor) backend; a Kotlin Multiplatform module shared by phone and server so a memo total or a distance can never differ between them; Next.js and TypeScript for the web; PostgreSQL; Azure Container Apps.

## 4. Geo integrity and device control

Every field phone is factory-reset and enrolled as **device owner**. On such a phone:

| Layer | What it does |
|---|---|
| Lock-down | developer options and USB debugging off, installing from unknown sources off, Aron cannot be uninstalled, location permission granted and pinned, approved app list only, factory reset from Settings blocked |
| Scheduled app blocking | the configured apps (social media, games) are suspended at check-in and released at check-out, applied on the phone so it works offline |
| Location | one on-demand fix at attendance, outlet open and force sale; optional batched low-power breadcrumbs (admin-set, off by default); geofence maths on the phone, re-checked on the server |
| Mock detection | the mock-location flag is read on every fix; a mocked fix never validates a call |
| Integrity | Play Integrity and hardware key attestation tell the server the phone is genuine and unmodified; the phone also proves it is enrolled |
| GNSS consistency | satellite count, signal strength and raw measurements are checked against the claimed position |
| Server rules | impossible speed, teleporting, zero jitter, one coordinate for a whole route, phone-versus-server disagreement |
| Enrolment gate | in production the server accepts attendance and sales only from enrolled phones that pass integrity (`cfg.device.require_enrolled`); a phone wiped outside the process cannot log in until it is enrolled again |

**What is promised, and how it is proven.** With every phone enrolled, the software spoofing routes (mock-location apps, spoofing subscriptions, app cloning, developer tools) are closed: they cannot be installed or switched on, and where one exists the call is refused and flagged. I prove it on Day 3 by testing at least five well-known mock-location apps and one app-cloning tool on your test phones, before and after enrolment, and report each result. The one thing no software can stop is **RF-level GPS simulator hardware or a physically modified phone**; the server flags those statistically and supervisors see the flag. So "software spoofing: closed, tested" is a promise; "no one can ever cheat by any physical means" is not.

## 5. "It cannot break at 8,500 users": what is engineered and tested

> **Where each proof runs.** The design below is for the final account. In the TEST account (5 to 10 users) I build every mechanism and prove it at small scale (offline day, idempotency fuzz tests, a small smoke load test, a point-in-time restore drill). The full-scale proof (1.5x fleet, burst, failover drill, row-by-row reconciliation) runs in the FINAL account as soon as it is handed over, before sign-off. Until then the claim is "designed for 8,500 and tested small", not "proven at 8,500".

No one can honestly sign "the cloud never fails". These three properties can be built and measured, and they are what keeps the business running:

| Property | How | Proof |
|---|---|---|
| **No sale is ever blocked or lost by the cloud** | offline-first: selling never waits for the network; data stays on the phone until the server confirms it | Day 2 airplane-mode day; Day 6 server killed mid-day |
| **Phones cannot overload the server into failure** | admission control (429 with retry-after and jitter), buffered ingest, autoscaling, connection pooling, reads from aggregates, dashboards shed first | Day 6 smoke test here; full load test in the final account |
| **No confirmed write is ever lost or doubled** | zone-redundant PostgreSQL with a synchronous standby in a second zone, point-in-time restore, geo-redundant backup, idempotent writes | restore drill on Day 6 here; failover drill and reconciliation at scale in the final account |

Day 6 test, on Azure Load Testing against our own resources only: the design point (4.5 lakh calls and 5 lakh visits a day, 8,500 users, morning download storm, evening upload storm, the 17:00 wave), then 1.5 times the fleet (12,750 users) and a 3 times burst; kill a server instance; fail over the database; compare every row. Pass: no lost or duplicated rows, dashboards p95 at most one second throughout, no sale blocked. A region-wide Azure outage is covered by a cross-region replica built in the reserve days, with a stated recovery time.

## 6. Azure: separate from what is live, and movable

- Everything lives in one resource group, **`rg-aron-dev`**, in the temporary subscription. The GitHub deploy identity has rights **only on that group**, so the services already live cannot be touched.
- All infrastructure is code, with the subscription, region and names as parameters. **The move to the final account is re-running the same code** there, restoring the database from a backup, and re-pointing the web address. I rehearse the move once before the end into a second group.
- **Test profile only (docs/28):** small, cheapest database tier, single zone, no Front Door, scale-to-zero apps, a logging cap, and a budget alert at the monthly test budget (USD 100 shared with Maps). **No quota requests and no fleet-sized resources** in this account. Subscription quotas are shared with the live services, so I check the regional quota snapshot before creating anything.
- The runbook `docs/setup/move-to-final-account.md` is kept current by the infra lane.

## 7. Day by day

Each day ends with a 10-minute check you run. A day is done only when its check passes. Lanes run in parallel, so Android, backend and web progress together.

| Day | Done when | Your 10-minute check |
|---|---|---|
| 0 (tonight) | laptop set up; Azure bootstrap run; Google and Firebase set up; backlog and contract written | `adb devices` shows your phone; the bootstrap prints "Done" |
| 1 | contract, shared module, schema v1, backend skeleton, three app skeletons, web skeleton; infrastructure deployed to `rg-aron-dev`; CI builds everything | install the skeleton app on the Galaxy A06; log in; the API answers from Azure |
| 2 | SR selling loop: bundle, route of the day (Daily, 3F, 2F), attendance, stock, outlet pick, geofence, sale, discount lines, memo, **printing on the MP-58N**, outbox and idempotent ingest | airplane mode: check in, sell, print; network on; the sale appears once on the server |
| 3 | SR complete (dues, edit, reprint, summary, Sales Submit, outlet requests, tasks with push, QC); geo integrity; device-owner policy and app blocking; anti-spoofing tests | the spoofing apps are refused; check in suspends the chosen apps; check out releases them |
| 4 | web dashboards and reports; admin portal (radius at all levels, rules, master data, users, devices and enrolment QR, releases, block list, audit) | change the radius in the portal; the phone obeys at its next sync |
| 5 | AMO app and TSO app complete; Final Submit; verification; team map | an AMO verifies an outlet request; a TSO closes a zone-day |
| 6 | small smoke load test, restore drill, battery and data runs; security pass; move runbook written | the smoke report passes; the battery log for a scripted 8-hour day |
| 7 | fixes; release candidate APKs; field-test script | a real route with printed memos |
| 8 to 10 | reserve: anything that failed a check, move to the final account when it is handed over, then the full load test, failover drill and move rehearsal there | the failed checks pass |

## 8. What I need from you

| When | What |
|---|---|
| Tonight | follow `docs/setup/`: `tools/setup-laptop.ps1`, `gh auth login`, `az login`, `infra/bootstrap-azure.ps1`; run the browser setup (`docs/setup/chrome-google-firebase-github.md`); turn on USB debugging on the Galaxy A06 |
| Before Day 2 | photos of a printed memo, a stock slip and a day summary |
| Each evening | run the day's 10-minute check and tell me what broke |

**Decided by the sponsor (restated 2026-10-06): the current Azure and Google accounts are temporary test accounts, pilot size only; a final account comes later and the system is moved and scaled there.**

**Decided by the sponsor (2026-10-05):** every phone is factory-reset and enrolled; test phones Samsung Galaxy A06, Galaxy A07, Honor X5c Plus; printer MP-58N; temporary Azure subscription `fdd05880-48dd-46bd-be4c-36f7039e71d7` (final account later); repository `asefameerador96-ctrl/Aron-Pro-Max`; package ids `com.aktcl.aron.sr`, `.amo`, `.tso`.

## 9. Risks

1. **Scope against the clock.** Full scope in 7 days is only possible with strict lanes, small tasks and parallel sessions; day 8 to 10 are the buffer, not the plan. Any day that fails its check is reported to you the same evening, with what moves.
2. **Claude usage limits.** Many parallel sessions use the plan's quota quickly; if a window runs out, work pauses until it resets. I sequence the heaviest runs.
3. **Real-device testing is your time** (printing, Bluetooth, GPS, battery).
4. **Enrolling the fleet is operational work** (a factory reset per phone, a QR scan); the portal makes it quick but someone still touches each phone.
5. **Google's rule for apps installed outside Play** (developer verification, rolling out from September 2026): checked on Day 1 against enrolled-device installs.
6. **Phone makers' battery savers** (Samsung, Honor) can stop background work; the device-owner policy exempts Aron and Day 6 measures it on all three phones.
7. **Shared Azure quota** with the live services: check the snapshot first; request nothing in the test account.
8. **The 8,500-user proof depends on the final account's handover date.** If it arrives late, the full load test, failover drill and move rehearsal slip; I ask for the handover at least three days before the end and say so in each evening report.

## 10. Questions still open, with the default I use

| # | Question | Default |
|---|---|---|
| 1 | Which apps to block between check-in and check-out? Is WhatsApp allowed? | list set in the admin portal; blocked: Facebook, Instagram, TikTok, YouTube, Snapchat, games; WhatsApp and Messenger allowed; phone, SMS, Maps, camera always allowed |
| 2 | Bangla and English in the apps? | Bangla first with an English switch, as today; Bengali digits |
| 3 | Azure region while building? | Southeast Asia (Singapore); decided again for the final account |
| 4 | Lowest Android version in the fleet? | Android 8 (API 26); your test phones are newer |
| 5 | How do web users log in? | username and password with a second step for admins; single sign-on later |
| 6 | Which web roles? | wing manager, division manager (DMO), territory (TSO), admin, super admin, read-only analyst |
