# Lane brief: android-sr-a

Session model: **Sonnet** (docs/29 s3). Owns: `android/feature-auth`, `feature-home`, `feature-attendance`, `feature-stock`, `feature-outlet`, `feature-tasks`, `app-sr` screens for these.

Read `docs/lanes/README.md` first.

- Scope (SR app, part A): install and first bundle, device OTP, permissions onboarding, settings, Home header, tile grid and KPI strip, attendance check-in and check-out, stock load and tracker, route outlet list with filter and distance sort, **open visit and geo check**, force sale, GPS refresh, GEO and photo component, outlet requests (new, closed, info change, cluster), tasks with badge and swipe-resolve, tutorials, outlet detail, offline day start and stale-bundle banner, identity confirmation on a shared phone.
- UI reference: `docs/ui-reference/sr/*.md` and images, `docs/06`; Bangla first; use `core-ui` kit and `core-database` (Room, outbox) from android-core; geofence maths from `shared:rules`; location only on demand (balanced power).
- The SR part B lane (sale, review, credit, QC, dues, summary, submit) builds on your outlet and visit state: agree the interface in `feature-outlet` early and write it down in your status file.
- Anti-spoofing and device-owner client work belongs to android-geo-dpc; call their interfaces (`core-geo`, `dpc`); do not duplicate. Print belongs to android-print.
- Acceptance includes an offline run of the screen: no network call blocks a screen.
