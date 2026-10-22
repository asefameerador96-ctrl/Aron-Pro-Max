# Lane brief: mdm

Session model: **Sonnet** (docs/29 s3). Owns: the MDM section of the web portal (`web/src/app/admin/mdm`),
its API (`backend/.../mdm`, prefix `/admin/mdm/*`), and the command channel of the device-owner DPC
(`android/dpc`, additive only; the DPC's device-side policy code belongs to android-geo-dpc's finished work,
reuse it).

Read `docs/lanes/README.md` first, then `docs/34-owner-directive-2026-10-22.md` s5 and s1 (the `mdm` module
grant), `docs/21-security-privacy.md` (device trust), the device, enrolment and release tables (V0010 and
later), and `android/dpc/README.md` if present.

- Scope: device list and detail, policies (allowed apps, app-blocking schedules by weekday and time, kiosk /
  lock task, location sampling policy), actions (lock, unlock, message, locate, reboot, factory reset with a
  two-person confirmation and an audit row), enrolment (QR / token), compliance flags and alerts (rooted, mock
  location, outdated app, developer options), activity log. The owner's reference console (`D:\Aron MDM`) is
  not yet available: build the data layer and plain pages first; when the lead files
  `docs/ui-reference/web/mdm-*.webp` match the look.
- Commands are rows in a `device_command` table (client-idempotent by UUID, state `queued → sent → acked /
  failed / expired`), pushed by FCM data message when the device is online and pulled by the DPC on every
  sync; the device acks with its result. Never a continuous channel.
- Every action is role-gated (`SUPERADMIN`, or `ADMIN` with `mdm: w`; `r` sees lists only) and audited with a
  reason. Factory reset needs a second approver.
- Device data shown is what the DPC already reports; add fields only through the contract (`contract/`) with
  a slice and a migration request to db.
- Tests: API tests per endpoint with role matrix; a DPC unit test that applies a schedule policy; Playwright
  for the list and detail pages. Bangla and English strings in the i18n layer.
- Status file `docs/status/mdm.md`; requests `docs/requests/mdm-<name>.md`.
