# 23 — Seven-day build plan (Phase 1)

**Status: DRAFT for the sponsor's confirmation, 2026-10-05.** This document replaces the schedule of `docs/14` (34 to 38 weeks, 8 to 10 engineers) for the first build. `docs/14` to `21` stay as reference for rules, schema and risks; where they conflict with this page, this page wins.

## 1. Constraints and aim

One human (the sponsor) plus Claude (Max 20x), one laptop, GitHub, Azure, a paid Google Maps API. Seven days at most. Aim: something better and smoother than Apsis, built so that Phase 2 plugs in without a rewrite.

**Phase 1 ships (7 days):**

| Piece | What it is |
|---|---|
| Backend | API, sync service, workers and PostgreSQL on Azure, designed and load-tested for 8,500 users at once |
| SR app | native Kotlin Android: the full offline selling day, Bluetooth printing, sync |
| AMO app | native Kotlin: control and joint calls, outlet verification, tasks, team view |
| TSO app | native Kotlin: dashboard, Final Submit, visit plans, leave |
| Web | one web app with role-based access: dashboards, reports, and the **admin portal** |
| Admin portal | change the geofence radius and every other operating setting, manage users, devices, releases and the app-block list from a screen, no backend change |
| Device control | Device Owner mode on enrolled phones: block chosen apps from check-in to check-out, lock down spoofing routes |
| Geo integrity | precise low-battery location, on-device geofence, layered anti-spoofing |

**Not in the 7 days (Phase 2 or later):** Apsis data import and cutover (Apsis is simply switched off when the new system is ready), the discount portal, Astha portal, target portal and target engine, the BOD "mother dashboard", the indent portal, Astha / Diamond League / Superstar logic, and the long tail of the 37 web reports (the 8 to 10 that matter come first).

## 2. What changes against the earlier plan

| Earlier plan | Now | Why |
|---|---|---|
| Flutter app, three flavours | **Native Kotlin** (Jetpack Compose), three apps from one Gradle build | direct device control: Device Owner, Play Integrity, GNSS raw data, fused location, battery tuning |
| Node.js backend | **Kotlin (Ktor) backend** with a shared Kotlin Multiplatform module for the sync contract, pricing, memo totals and geofence maths | the phone and the server run the same code, so a memo total or a distance can never differ between them |
| Web in Next.js | unchanged: Next.js + TypeScript | fastest route to dashboards and the admin portal |
| No device management (shared phones) | **Device Owner** on enrolled phones; apps still work on non-enrolled phones, with no app-blocking or lock-down | what the sponsor asked for; it needs a factory-reset phone (see section 4) |
| Migration, pilot, waves (Phase 7) | removed | sponsor's decision |
| 34 to 38 weeks | 7 days | sponsor's constraint |

## 3. Architecture

```
 SR / AMO / TSO apps (Kotlin)          Web (Next.js)  = dashboards + admin portal
   Room local DB + outbox                       |
   WorkManager, FCM nudge                       |
   Device Owner policy                          |
        \                                      /
         \____ HTTPS (gzip, idempotent) ______/
                        |
              Azure Front Door (TLS, WAF)
                        |
        Azure Container Apps: API (Ktor)   Worker (aggregates, jobs)
                        |                        |
        Azure Database for PostgreSQL Flexible Server (zone-redundant HA,
        built-in pooling, read replica for dashboards)
                        |
        Blob Storage (photos via SAS, bundle snapshots)   Key Vault   App Insights
```

- **Offline first.** Every action writes to the phone's database and an outbox in one transaction. The sync service sends it as soon as there is a connection (a short debounce, no polling), and the server saves it by a phone-made UUID, so a retry never doubles anything. Push (FCM) only nudges; data always arrives by sync.
- **Reads come from stored aggregates**, never the transaction log, so dashboards stay fast during the morning download and evening upload peaks.
- **Extensible for Phase 2.** Offers, targets, programmes and stock movements are data (rule tables and append-only ledgers), not code; every record has a stable external reference; domain events are written to an outbox so the mother dashboard and indent portal can subscribe later.
- Sizing and failure modes come from `docs/18`, rebased to this scope in Day 6.

## 4. Geo integrity and device control: what is possible

| Layer | What it does | Limit |
|---|---|---|
| Location | one on-demand fix at attendance, outlet open and force sale; optional low-power breadcrumbs (batched, admin-set, off by default); geofence maths on the phone, re-checked on the server | no continuous GPS, by design (battery) |
| Mock detection | reads Android's mock-location flag on every fix; a mocked fix can never validate a call | a rooted phone can hide it |
| Play Integrity and key attestation | the server learns whether the phone is genuine, unmodified and unrooted | needs Google Play services |
| GNSS consistency | satellite count, signal strength and raw measurements checked against the claimed fix; faked fixes rarely carry consistent satellite data | needs per-device tuning |
| Server plausibility | impossible speed, teleporting, zero jitter, one coordinate for a whole route, device-versus-server disagreement | statistical, not instant |
| **Device Owner lock-down** | disable developer options and USB debugging, block installing from unknown sources, block uninstall of Aron, auto-grant and pin the location permission, allow only an approved app list | **only on enrolled phones** |

**Honest limit:** on a locked-down Device Owner phone with a strong integrity verdict, every software spoofing route is either impossible or detected. Nothing in software stops an RF-level GPS simulator or a physically modified phone; the server flags those statistically and supervisors see the flag. "Cannot be faked at all" cannot be promised; "very hard and always visible" can.

**App blocking.** With Device Owner, the app can suspend a configured list of packages (social media, games) at check-in and release them at check-out, even offline. Without Device Owner this is not reliable, so it is offered only on enrolled phones. **Enrolling a phone means a factory reset** (QR code at first boot, or `adb` on a fresh phone), so it is an operational step for the fleet, not a software switch.

## 5. Day by day

Each day ends with a 10-minute check you can run on a real phone or in the browser; a day is done only when its check passes.

| Day | Build | Your 10-minute check |
|---|---|---|
| 1 | Monorepo, CI/CD, Azure infrastructure as code and first deploy; schema v1; login with device binding and server-side scope; Android project skeleton (shared modules, Room, sync engine core, Device Owner module) | log in on the phone; the API answers from Azure; a test row syncs |
| 2 | SR offline selling loop: bundle download, route of the day (Daily, 3F, 2F), attendance, stock, outlet pick, geofence, sale with prices and discount lines, memo, **Bluetooth print**, outbox and idempotent ingest | airplane mode: check in, sell, print; turn the network on; the sale appears on the server once |
| 3 | SR day completed: dues, edit and reprint, summary, Sales Submit with reconciliation, outlet requests, tasks with push, QC; anti-spoofing layers; app-block on check-in | enable a mock-location app: the call is refused and flagged; check in: chosen apps are suspended; check out: released |
| 4 | Web dashboard with roles; sync-health; core reports; **admin portal** (geofence radius at all levels, rules, users, devices, releases, block list) with audit | change the radius in the portal; the phone obeys at its next sync |
| 5 | AMO and TSO apps on the shared modules; Final Submit; verification; team map (Google Maps) | an AMO verifies an outlet request; a TSO closes a zone-day |
| 6 | Scale and resilience: Azure Load Testing at the design point (4.5 lakh calls and 5 lakh visits a day, 8,500 users, morning and evening storms), failover and restore drills; battery and data measurement on the phone | the load report passes its thresholds; battery log for a scripted 8-hour day |
| 7 | Fixes, security pass, signed release APKs, field-test script, hand-over notes | a full day on a real route with real printed memos |

## 6. What I need from you

| When | What |
|---|---|
| Today | **Azure:** subscription ID and one service principal for GitHub Actions (I will write `infra/bootstrap.sh`; you run it once with `az login`). **Google:** a Maps key for Android and one for the web, and a Firebase project for push. All go in GitHub secrets, never in the repo. |
| Today | **Phones:** the model and Android version of one or two test phones; one that can be factory-reset for Device Owner. **Printer:** the Bluetooth model. |
| Before Day 2 | photos of a printed memo, a stock slip and a day summary |
| Each evening | run the day's 10-minute check and tell me what broke |

Faster loop: if Claude Code runs on your laptop, install Android Studio, `adb` and the Azure CLI there. I can then install to your phone, read its logs and run the emulator myself. This cloud session builds and unit-tests but cannot touch a phone.

## 7. Risks, said plainly

1. **7 days is tight for the full scope.** The earlier plan costed full parity at 333 to 437 person-weeks. Seven days delivers the core of each piece, built well and extensible, not all 509 features. If time runs short, order of sacrifice: web report long tail, AMO and TSO extras, SR convenience features. Never: offline correctness, idempotent sync, geo integrity, load test.
2. **Real-device testing is your time.** Printing, Bluetooth, GPS and battery cannot be tested without a phone and a printer.
3. **"Cannot break at 8,500 users"** means designed and load-tested to the design point with failure drills. It is a measured claim, not a guarantee.
4. **Device Owner needs a factory reset** and is only as good as the fleet's enrollment.
5. **Claude usage limits.** Heavy parallel agent runs use the quota fast; I will fan out only where it saves real time.
6. **Google's rule on apps installed outside Play** (developer verification, rolling out from September 2026): check on Day 1 how it affects sideloaded and Device Owner apps.

## 8. Questions, with the default I will use if you do not answer

| # | Question | Default |
|---|---|---|
| 1 | Are the field phones AKTCL's to factory-reset and enrol? | Device Owner is optional per phone; everything else works without it |
| 2 | Lowest Android version in the fleet? | Android 8 (API 26) |
| 3 | Which apps to block, and when? | a list set in the admin portal, blocked from check-in to check-out; phone, SMS, Maps always allowed |
| 4 | Azure region? | Southeast Asia (Singapore), as in the earlier plan; changeable on Day 1 |
| 5 | Bangla in the 7 days? | Bangla and English toggle with the core strings; the full string catalogue later |
| 6 | The cut list in section 1: agreed? | agreed |
