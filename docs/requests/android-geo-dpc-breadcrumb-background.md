# Request (android-geo-dpc → lead): breadcrumbs vs "location in-use only" (F-SYS-023)

Two binding texts disagree:
- docs/24 s10.2 and N-035: "`ACCESS_BACKGROUND_LOCATION` is granted only while `cfg.geo.breadcrumbs_enabled` is true"; breadcrumbs are batched fixes taken while the rep walks the route, so mostly with the screen off. That needs the permission declared in the manifest (the DPC then grants it only while breadcrumbs are on and denies it otherwise).
- F-SYS-023 (core-system `ManifestPermissionAuditTest`): no source or merged manifest may request `ACCESS_BACKGROUND_LOCATION`.

Interim (pushed): the permission is not declared, the audit passes, and breadcrumbs (off by default) collect only while Aron is in use. With the screen off, Android delivers no background locations to an app without the permission.

Options for a ruling:
1. Keep in-use only; N-035 accepts "breadcrumbs only while the app is in the foreground" (cheap, privacy-friendly, weak as a trail).
2. Allow the permission in the SR and AMO apps only, the DPC granting it only while `cfg.geo.breadcrumbs_enabled`; core-system's audit adds that one exception (owner of core-system edits the test).
3. A foreground service with a visible notification while breadcrumbs run (no background permission; costs a permanent notification and more battery).

I recommend 2: it matches docs/24 s10.2, the permission is denied and pinned whenever breadcrumbs are off, and breadcrumbs are off by default.
