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
