# HANDOVER (lane mdm session 3 -> session 4, 2026-10-24, context about 200k)
**Candidate:** 091707789f8b (run 40142118596, all 9 jobs green, nothing skipped; Opus T1 PASS after 5 fixes, re-check PASS); rides train 37. Base INT c427d842.
**Shipped this session:** work-hours allow-list (D-NIGHT-MDM-ALLOWLIST-01; D-MDM-ALLOWLIST-01..05): runbook `docs/runbooks/mdm-work-hours-allowlist.md`, policy member `work_hours_allowlist` (jsonb, served ahead of the contract, omitted when absent), DPC enforcement (`MdmPolicyApplier`, protected set, fail open, check-in/out re-run, elapsed-time `setWindow` alarm), portal card `work-hours-allowlist-card.tsx`.
**Open:**
1. Contract: `docs/requests/contract-v1.9-shapes-mdm.md` is mapped by the lead to the **1.10** queue (part A allow-list member, part B packages catalogue). After 1.10: drop the local web types (`MdmPolicyFull`, `MdmWorkHoursAllowlist` in `web/src/lib/admin/mdm.ts`, the `Policy &` widening in `web/mock/mdm.ts`), add the member to the conformance fixture, then build part B (db request `db-mdm-device-packages.md`, backend, DPC upload on hash change, picker).
2. Device rows DEVICE-PENDING: D-MDM-ALLOWLIST-01 (waits for the Honor device-owner enrolment, owner rows U), D-MDM-01, D-MDM-BREADCRUMB-01.
3. Portal look: the owner's MDM screenshots are still missing.
**Traps.** Never add SCHEDULE/USE_EXACT_ALARM (NoPollingLintTest); MDM alarms use elapsed time from the trusted clock. Block windows now refuse ARON, dialler and system packages (server) and never suspend the protected set (phone). Old traps below still hold.

# Session 3 (2026-10-24) — work-hours allow-list (D-NIGHT-MDM-ALLOWLIST-01)
- Runbook `docs/runbooks/mdm-work-hours-allowlist.md` (answer, enrolment, portal steps, limits, device-lab steps); device row D-MDM-ALLOWLIST-01 in `docs/status/device-checks.md` (DEVICE-PENDING).
- Built: `work_hours_allowlist` in the policy jsonb (no migration), served ahead of contract 1.9 row 6 (shape: `docs/requests/contract-v1.9-shapes-mdm.md`, also the packages catalogue, part B, not built); backend validation + audit + delivery (`MdmApiTest.theWorkHoursAllowListIsValidatedAuditedAndDelivered`); DPC enforcement in `MdmPolicyApplier` with the protected set and fail-open rules (`MdmAllowlistTest`, 15); portal card `work-hours-allowlist-card.tsx` (vitest `mdm-allowlist.test.tsx`, e2e in `mdm.spec.ts`).
- Decisions D-MDM-ALLOWLIST-01..05. Edges are not exact (lint forbids the exact-alarm permission): up to about 10 min late while in use.
- Open: packages catalogue (contract 1.9 part B, then db request + backend + DPC upload + picker); the device row; drop the local web types once 1.9 regenerates `openapi.d.ts`.

# HANDOVER (lane mdm session 2 -> session 3, 2026-10-23, context about 380k)
**Candidate:** c5de7c78 (network_type both ends; INT c07c66dc merged). Run 39961480341: all jobs green except one Docker Hub 500 at the base-image pull (not ours), failed job re-run once; the lead judges at 14:40 UTC (train 19, else train 20 with no further re-run).
**On INT already:** ModuleGrants in production, breadcrumb install SR/AMO (D-MDM-BREADCRUMB-01 incl. lead ruling: the DPC `breadcrumbsEnabled` is the single background-location switch), device-lab rows, handover of session 2.
**Open, none buildable without input:**
1. Portal look: owner's MDM screenshots (`docs/ui-reference/web/mdm-*.webp`); the lead calls the lane when they arrive.
2. Device rows DEVICE-PENDING in `docs/status/device-checks.md`: D-MDM-01, D-MDM-BREADCRUMB-01 (battery/data figures here are estimates).
3. Packages catalogue endpoint for the policy editor: its contract change goes to `docs/requests/contract-v1.8-queue.md` (opened by the lead with 1.7.0; not queued yet), then backend + web picker.
4. Decided NO (lead): boot/battery reconcile for the breadcrumb controller.
**Traps.** Expiry tests move the MDM service clock, never UPDATE device_command (db's guard). Production MDM access = `ModuleGrants` (token `modules`); `MdmApiTest` uses a table-backed grant seam because test tokens carry no `modules`. `BreadcrumbPayload` is local to core-database until the contract batch adds the DTO. TSO has no background location (D-GEO-BG-01). Dispatch ci.yml only when the push run skipped jobs you need (a duplicate holds the concurrency group); `rerun_failed_jobs` once for a non-ours red. Merge INT before any push.

# HANDOFF (lane mdm session 2 -> next, 2026-10-23)
**State.** lane/mdm = INT 52bdd477 merged; train 11 carried 3c9522f1 (ModuleGrants in production, breadcrumb install SR/AMO, tests green, on dev). Idle: nothing buildable without input.
**Waiting on the owner:** MDM console screenshots (`docs/ui-reference/web/mdm-*.webp`) to match the portal look; the lead calls this lane when they arrive.
**Waiting on a phone (DEVICE-PENDING in `docs/status/device-checks.md`):** D-MDM-01 (lock, message, locate, reboot, factory reset with a second approver, block window, kiosk, sampling) and D-MDM-BREADCRUMB-01 (battery and data; the figures in this file are estimates).
**Contract:** `network_type` landed in 1.7.0 and is wired both ends (backend `DeviceStatusReportDto` with enum check, `DeviceStatusReporter` from one ConnectivityManager read; absent when the read fails). Next contract change of this lane, the packages catalogue endpoint for the policy editor, goes to `docs/requests/contract-v1.8-queue.md` (opened with 1.7.0; not queued yet).
**Done this session:** shim removed (db V0102/V0103), ModuleGrants (the table grant survives only as a test seam in MdmApiTest), approval/expiry/read-only tests, `withMdmSampling`, breadcrumb install with Opus T1 fixes. Context used: about 330k tokens.
**Traps.** Expiry tests move the MDM service clock, never UPDATE device_command (db's guard). `BreadcrumbPayload` is local to core-database until the contract batch adds the DTO. TSO has no background location (D-GEO-BG-01). Merge INT before any push.

# Session 2 (2026-10-22) — progress
- Train 11 candidate: 3c9522f1 (run #1410 green, lead integrating). Context used so far: about 300k tokens.
- Open rows now: boot/battery reconcile (needs a boot receiver + RECEIVE_BOOT_COMPLETED in the SR/AMO manifests and a runtime battery receiver; the manifest permission audit must be extended; low value while app start/sync restores it), packages catalogue endpoint (contract change: goes through the v1.7 queue), D-MDM-01 and D-MDM-BREADCRUMB-01 device rows (DEVICE-PENDING), portal look (owner's screenshots).
- Merged INT b561b267. FreshDb DDL shim, `mdm_ddl.sql` and `MdmDdlSyncTest` deleted; mdm suites run on db's V0102/V0103 (13 MdmApiTest green).
- `DbGrants` (reads `app.user_module_grant`, `mdm` r/w; SUPERADMIN always write) wired in `Wiring.kt` instead of `RoleGrants`, until the token carries `modules`. Tests: read-only ADMIN cannot act or decide, revoked grant closes the door, ADMIN with write approves a factory reset (requester cannot), pending reset that expired does not break the pull (db's request). Expiry tests move the service clock, never UPDATE rows.
- Geo seam: `BreadcrumbSettings.withMdmSampling(mode, intervalMin)` in core-geo (off / interval / on_demand) with `MdmSamplingTest`. `BreadcrumbController` is not constructed in production code yet; whoever wires it passes `{ cfg.withMdmSampling(applier.locationMode(), applier.locationIntervalMin()) }`. Sale and check-in fixes are NOT affected by the policy (geo-validation must keep working).
- Device-lab steps D-MDM-01 added to `docs/status/device-checks.md` (DEVICE-PENDING).
## Breadcrumb install (D-MDM-BREADCRUMB-01) — expected cost, NOT yet measured on a phone
- Off by default (`cfg.geo.breadcrumbs_enabled=false`); on only with config true or MDM `interval`, checked in, battery above `batteryFloorPct` (20%). Requests: fused, balanced power, batched, one request alive at a time.
- Battery (estimate, to be measured in the device-lab row): a batched balanced request at 15–30 min costs on the order of 1–2% of a battery per 8 h day; at the 5 min floor 2–3%. The gate drops points that moved less than 50 m or arrive inside the interval, so the stored points are at most 16 (30 min), 32 (15 min), 96 (5 min) per 8 h day.
- Data (estimate): a `geo_breadcrumb` record with its GeoFix is about 0.7 KB of JSON; 16 points ≈ 11 KB raw, ≈ 5 KB gzipped per day; 96 points ≈ 67 KB raw, ≈ 30 KB gzipped (under 0.002% of the 2 GB day pack). Rides the existing sync batches, no new upload.
- Opus T1 check done (4 defects fixed: cold-start install, user/day window, MDM interval needs the background grant, `captured_offline`; remarks: stop() churn, MDM callback). Open: Android 8+ limits background deliveries to a few per hour, so the 5 min floor is a request, not a promise; no reconcile on battery/boot broadcasts (next app start or sync restores it).
- Tests: `BreadcrumbWiringTest` (core-sync; shared by SR and AMO because both use `DeviceRuntime`): not started when off, MDM interval/off/on_demand, floor, low battery, mock flag kept, sink; `CaptureRepositoryTest.aBreadcrumbIsOneStandaloneRecordWithItsOwnFix`.

- DECIDED (lead, 2026-10-23 00:02 UTC): NO boot/battery reconcile for the breadcrumb controller (wiring doc row 18). Reason: it would add RECEIVE_BOOT_COMPLETED to two manifests for a controller that app start or the next sync already restores, breadcrumbs are off by default (D-MDM-BREADCRUMB-01), and the manifest permission audit stays as it is. Battery and data figures stay estimates until the device rows run.
- Still waiting: owner's MDM screenshots (`docs/ui-reference/web/mdm-*.webp`); network_type in the status report (contract queue); packages catalogue endpoint.

# HANDOFF (lane mdm session 1 -> session 2, 2026-10-22)

**Where things are.** Branch `lane/mdm`; contract 1.6.0 (tag `admin-mdm`, 16 operations `/v1/admin/mdm/*`, plus `GET /v1/devices/me/commands`, `POST /v1/devices/me/commands/{id}/ack`). Merged with INT 2c10a157. The candidate is the head named in the lead's message; check `docs/status/lane-sessions.md` and the last CI run on the branch.

**Rows done**
- Contract and slices; Appendix A rows in docs/24; public-operation pin (`RouteInventoryTest`), scope registry lines and blind-read line for `/admin/mdm`, conformance fixtures in `ContractConformanceTest`.
- Backend `backend/analytics/.../mdm` (service, routes, grants seam `MdmGrants`/`RoleGrants`), `MdmApiTest` (10), `MdmDdlSyncTest`; wiring in `Wiring.kt` (always mounted, 503 until `ARON_MDM_ENABLED=1`; app tests set it).
- DPC `android/dpc/.../mdm` (poll, verify, ledger, executors, MdmPolicyApplier with Dhaka weekday/time windows, kiosk, location mode, clear policy) with `MdmTest` (14); app wiring in `android/core-sync/.../mdm` (HTTP, runner, shell, `mdm_nudge` push, jwks key cache, per-sync pull) and the three `SessionModule`s; `MdmCommandRunnerTest`.
- Web `web/src/app/admin/mdm/**` (list, detail with actions and second approver, policies with schedule grid, enrolment/QR, alerts, activity), bn+en, vitest, `e2e/mdm.spec.ts`.

**device_command wiring state.** Tables are NOT in db yet: `docs/requests/db-mdm-device-command.md` (DDL incl. `seq`). Until db lands the migration, `FreshDb.create` (backend/platform testFixtures) applies `mdm_ddl.sql` when `app.device_command` is absent; delete that shim and the resource when the migration exists. In production the endpoints answer 503 until the flag is on and the tables exist. Commands are signed ES256 (`aron-command-v1`) over params as read back from jsonb; the phone verifies with the key from `/v1/auth/jwks`.

**Open vs docs/34 s5**
1. Match the look to the owner's MDM console screenshots (`docs/ui-reference/web/mdm-*.webp`, not filed yet); today the pages use the plain admin kit.
2. Real `mdm` module grant (request `mdm-authz-module-grant.md`): replace `RoleGrants` when backend-authz exposes the `modules` claim; add a read-only ADMIN route test.
3. Geo layer must read `DeviceOwnerPolicy.mdmApplier?.locationMode()` (request `mdm-android-core-wiring.md`, last paragraph).
4. Status report `network_type` (request `mdm-contract-network-type.md`): the list shows `unknown` until it exists.
5. Device-lab steps for lock, locate, reboot, factory reset (second approver), kiosk and a block window, to add to `docs/status/device-checks.md` (DEVICE-PENDING): nothing was run on a phone.
6. Polish: device detail activity log paging, policy editor packages picker from a catalogue endpoint (the contract has none; free text plus suggestions today).

**Traps.** Order commands by `seq`, never `created_at`. Search parameter is `q` (the API also takes `search`). Never commit a `web/node_modules` symlink. The lint scanners reject literal UI strings in `dpc` (use `strings.xml` bn/en) and AlarmManager outside `dpc/blocking` and `dpc/mdm/AndroidMdm.kt`. Intermittent, not ours: `RefreshTest.concurrentUseOfOneTokenRotatesOnce`, `BindDeviceTest.anUnboundPhoneBindsOnceWithTheTsoOtp`.

**Open questions for the owner.** None blocking; the screenshots and the module-grant model are the two inputs waiting.

---

# Status: lane mdm (session 1, 2026-10-22)

Head 6c5a51c8 (INT 658f65d0 merged, contract 1.6.0). CI #1343: Android debug/release, size gate, backend app tests, web incl. e2e, images green. Red: the contract oasdiff gate (false red, lead's fix 28b22517 pending on INT) and backend:auth RefreshTest#concurrentUseOfOneTokenRotatesOnce (intermittent, backend-authz's). Not declared until the whole run is green after INT carries 28b22517.


Branch `lane/mdm` (from INT + lead-directive docs). Brief: `docs/lanes/mdm.md`; directive `docs/34` s5 (D-MDM-01).

## Done
- **Contract 1.4.0** (additive): tag `admin-mdm`, 17 operations `/v1/admin/mdm/*` (devices, detail, activity, commands, decision, policy assign, policies CRUD, enrolment tokens, alerts, fleet activity) and the phone's `GET /v1/devices/me/commands`, `POST /v1/devices/me/commands/{id}/ack`; slices regenerated.
- **db request** `docs/requests/db-mdm-device-command.md` (exact DDL: `mdm_policy`, `device.mdm_policy_id/version`, `device_command` with `seq`). Backend is **behind `ARON_MDM_ENABLED=1`** until it lands; tests apply the same DDL (`mdm_ddl.sql`, kept equal by `MdmDdlSyncTest`).
- **Backend** `backend/analytics/.../mdm`: list with filters and derived compliance flags, detail, activity (audit + status events), alerts, commands (idempotent by UUID, 200 repeat / 409 different body, reason mandatory, audited), factory reset with second approver (`pending_approval`, no signature until approved, requester cannot decide), device pull/ack by device proof (first result wins, expiry), policies (validation, optimistic version, apply_policy fan-out, assign/clear), enrolment tokens through the MDM gate. `MdmApiTest` (10) green incl. role matrix. FCM nudge `kind=mdm_nudge` wired in `Wiring.kt`.
- **DPC** `android/dpc/.../mdm` (see `docs/status/mdm-dpc.md`): poll, verify, dedupe ledger, executors, policy applier with weekday/time windows (Dhaka), kiosk, location mode; 14 unit tests.
- **Web** `web/src/app/admin/mdm/**` (see `docs/status/mdm-web.md`): list, detail with actions, policy editor with schedule grid, enrolment/QR, alerts, activity; bn+en; vitest + Playwright (list, detail).

- **Fixes from CI**: DPC notification title/channel are string resources (bn/en); lock-task ActivityOptions only from API 28 (lint); routes always mounted, 503 until `ARON_MDM_ENABLED=1`; test shim `FreshDb` applies the pending DDL until db lands it (delete then).
- **App wiring** (core-sync, SR/AMO/TSO): command poll on every sync run and on `mdm_nudge`, key from `/v1/auth/jwks`, `MdmCommandRunnerTest`. Contract version set to 1.6.0 (lead's numbering).

## Not done / waiting
- Owner's MDM console screenshots (`docs/ui-reference/web/mdm-*.webp`): look not matched yet.
- Geo layer reading `mdmApplier?.locationMode()` (see `docs/requests/mdm-android-core-wiring.md`). Real `mdm` module grant: `docs/requests/mdm-authz-module-grant.md`. Network type: `docs/requests/mdm-contract-network-type.md`. Migration: db request above.
- Device proof run on a phone (lock, locate, factory reset, kiosk): DEVICE-PENDING, steps to be added to `docs/status/device-checks.md` once the app wiring lands.

## Next three
1. Match the look to the screenshots when filed. 2. Swap `RoleGrants` for module claims. 3. Add the device-lab steps for MDM actions.

## Traps
- `created_at` can tie under a fixed test clock: order by `seq`, never by time.
- The search query parameter is `q` (the API also accepts `search`).
- Active-only token lists use the database clock.
