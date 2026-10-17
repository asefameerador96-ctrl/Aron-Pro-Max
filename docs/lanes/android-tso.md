# Lane brief: android-tso (wave 2, started 2026-10-17)

Session model: **Sonnet** (docs/29 s3; F-TSO-001 login and logout is T1: Opus checker). Owns: `android/feature-tso`, `android/app-tso` screens and wiring. Read `docs/lanes/README.md` first.

- Scope: the TSO app (docs/08-tso-app.md observed behaviour; the build spec sections your rows name are binding). `python3 tools/my-rows.py android-tso --todo` lists about 22 rows: dark navy login and logout, chrome (hamburger, greeting, seven drawer entries), leave list, dashboard tiles, My Periphery retailer radius map (needs `core-map`), My Feedback with one compressed image queued offline, and the rest. Read-mostly screens come from the dashboard reads in the contract slices; everything a TSO writes is offline-first and idempotent by client UUID.
- TRIM (docs/27): no target, no achievement ring, no loyalty, no discount programme. Keep hooks only.
- Auth: **no bind OTP for the TSO app** (D-AUTH-TSO-NOBIND, owner decision). The enrolment gate is on at dev: a phone needs an enrolment token for flavour `tso`. Test user `tso1001`; the shared dev password is in Key Vault, never in git, logs or chat.
- Reuse the Calm Glass kit in `android/core-ui` and `docs/32-design-system.md` (vibrant palette, green Yes / red No buttons, outdoor-first contrast, Sunlight mode, empty/loading/offline/error/long-Bangla states). The TSO login keeps its dark navy theme from the manual as a kit theme, not a copy of the kit. Goldens: Linux only (`-Proborazzi.record=true`).
- Devices: the owner's Honor X5c Plus is the only phone in reach; "checked on the Galaxy A06" rows become DEVICE-PENDING lines in `docs/status/device-checks.md` and do not block the next row. Phone numbers appear only for roles allowed PII (scope comes from the token, never from the client).
- Git: push to `lane/android-tso`. Status, time log and handover: `docs/status/android-tso.md` and `docs/status/android-tso.csv` (format of `android-sr-a`).
