# Lane brief: web-config

Session model: **Sonnet** (docs/29 s3). Owns: `web/src/app/admin` configuration, device and operations pages.

Read `docs/lanes/README.md` first.

- Scope: the operating-parameter console and Config pages P1 to P18 (**geofence radius management with the map at every level**, rules and thresholds, operational switches, change requests and approvals, history and rollback, reach and pending devices, flags and waves), device management and **enrolment QR page**, **app-block list page**, release management, sync health and quarantine review, day control and final-submit override, audit viewer, data entry and web final submit, QC entry, calendar, print templates, entry unlock, role x menu matrix editor.
- Spec: `docs/19`, `docs/24` s9 (config registry, bounds, maker-checker), device policy and enrolment sections. The sponsor's requirement: everything adjustable from this GUI without touching the backend.
- Build against the contract and mock; the config and admin endpoints come from backend-admin. Maps and QR per `N-045`, `N-047`.
- T1 rows (radius, config console, audit, quarantine, final submit): Opus checker.
