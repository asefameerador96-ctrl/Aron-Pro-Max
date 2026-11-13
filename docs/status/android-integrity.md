# Status: lane A-INT1 (android-integrity)

Directive: D-OWNER-MDM-02 (2026-11-13). Branch `lane/android-integrity`. Step 0 scout, written 2026-11-13, before any code.
State: STEP 0 DONE. Slice 1 (part 1 + part 2) and slice 2 (part 3) not started.

## (a) Where device owner / DevicePolicyManager / app blocking / provisioning is used

### Android (this lane changes these)
- `android/dpc` (whole module, 29 main files). Pieces that are device-owner only: `policy/*` (DpmGateway, AndroidDpmGateway, PolicyApplier, PolicyStore, DevicePolicy), `blocking/*` (BlockingEngine, AndroidSuspendGateway = setPackagesSuspended, HardEndAlarm), `mdm/*` (AndroidMdm kiosk and lock-task, MdmPolicyApplier, MdmChannel, DeviceCommands, MdmPolicyDoc), `enrolment/*` (ProvisioningActivities, EnrolmentCoordinator = QR provisioning), `DeviceOwnerPolicy.kt` (the facade), `AronDeviceAdminReceiver`, `DpcBootReceiver`, `aron_device_admin.xml`, strings.
- Pieces in `dpc` that are NOT device-owner dependent and that I keep (moved or left, see design): `update/ManagedUpdate` + `AndroidSelfInstaller` (package-installer session for our own APK; it lifts `no_install_apps` through DeviceOwnerPolicy today, that lift goes), `BatteryExemption` (user-granted ignore-battery-optimisation prompt), `enrolment/EnrolmentStore` + `EnrolmentKeys` + `ecJwk` (the device key and server device id: used by request proofs and record signatures, not only by provisioning).
- `dpc/src/main/AndroidManifest.xml`: receivers `AronDeviceAdminReceiver` (BIND_DEVICE_ADMIN, DEVICE_ADMIN_ENABLED, PROFILE_PROVISIONING_COMPLETE), `DpcBootReceiver` (BOOT_COMPLETED, MY_PACKAGE_REPLACED), `UpdateResultReceiver` (keep), `HardEndReceiver`, `MdmWindowReceiver`; permissions RECEIVE_BOOT_COMPLETED, REQUEST_INSTALL_PACKAGES, UPDATE_PACKAGES_WITHOUT_USER_ACTION, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.
- `app-sr|app-amo|app-tso/build.gradle.kts`: `implementation(project(":android:dpc"))`. `app-sr/.../SrDay.kt:25,379` and `app-amo/.../AmoDay.kt:31`: `DeviceOwnerPolicy.get(context).onCheckInCommitted()/onCheckOutCommitted()` (attendance-driven blocking). `app-tso` imports through core-sync only.
- `core-sync/build.gradle.kts:19` depends on `:android:dpc`. `core-sync/.../device/DeviceRuntime.kt:30,45,71` (`onAppCreate` configures and reapplies the policy), `device/AndroidDevice.kt:22-24,54,161-167` (reads `isDeviceOwnerApp`, BlockingStore, EnrolmentStore), `device/DeviceEnrolment.kt:20-32,77,204,270` (QR + code enrolment, `applyPolicy` = `DeviceOwnerPolicy.receive`), `mdm/MdmCommandShell.kt` (pulls signed device commands, applies MDM policy). Apps: `AronSRApplication.kt:22-45` (`deviceEnrolment.install`), `MainActivity.kt:43-153` (`EnrolmentGate` before first login), same in AMO/TSO.
- `feature-auth`: `EnrolmentScreen`, `EnrolmentQrScanner`, `EnrolmentViewModel`, `EnrolmentGate` (QR scan UI; the code-entry submit is the device registration).
- `core-geo`: `AndroidDeviceState.kt` reads `isDeviceOwnerApp` into `FixDeviceState.deviceOwner` (contract field stays, sent false).
- Tests that reference the removed code: `dpc/src/test/*` (38 tests), `core-sync` `MdmCommandRunnerTest`, `DeviceEnrolmentTest`, `RecordSignatureTest:234`, `app-*/AppCoexistenceTest` (checks the receiver name), `feature-*` day-lock tests only mention `deviceOwner` as a fixture field.
- Docs: `docs/24` s10, `docs/runbooks/honor-device-owner.md`, `docs/runbooks/mdm-work-hours-allowlist.md`, `docs/status/android-geo-dpc.md`.

### Server and web (listed only, I do not change them)
- Backend: `backend/analytics/.../mdm/*` (MdmApi, MdmService, MdmModels, MdmAccess), `backend/analytics/.../devices/*`, `backend/platform/.../AdminModules.kt:44,106-107` (module `mdm`, `/v1/admin/devices/**`, `/v1/admin/enrolment-tokens/**`), `backend/app/.../Wiring.kt`.
- Web: `web/src/app/admin/mdm/*` (devices, policies, enrolment, activity, alerts), `web/src/components/admin/mdm/*`, `web/src/lib/admin/mdm.ts`, `access-grants.ts`, `modules.ts`, `menu.ts`, `web/src/contract/openapi.d.ts`.
- Contract: `enrol-device` response carries `policy`; device-command and MDM policy endpoints; all stay dormant (D-OWNER-MDM-02 item 4).

## (b) Anti-spoofing that exists today
| Layer | Where | What it does today |
|---|---|---|
| Mock flag on every fix | `core-geo/AndroidLocation.kt:25-38` (`isMock` API 31+, `isFromMockProvider` below), `FixManager.kt:164,188-208`, contract `GeoFix.is_mock` (`WireDtos.kt:336`) | recorded on each fix, stored `app.geo_fix.is_mock` |
| On-device verdict | `shared/rules/.../Geo.kt:131-178` `GeoVerdicts.verdict` | mocked fix never gives `SALE_ALLOWED`; `MockPolicy` BLOCK_SALE / WARN_REP / SILENT_FLAG (config), so a bundle can still set WARN or SILENT. Used from `feature-outlet/VisitPorts.kt` |
| Mock-app presence | `core-geo/AndroidDeviceState.kt:20-45` `MockAppScanner`: lists installed non-system packages that request `ACCESS_MOCK_LOCATION`; cached 10 min; needs `QUERY_ALL_PACKAGES` (`core-geo/AndroidManifest.xml:10`) | `FixDeviceState.mockAppPresent`; evidence only |
| Settings facts | `AndroidDeviceState.kt:70-85` | dev options, ADB, auto time, device owner per fix |
| GNSS capture | `core-geo/AndroidGnss.kt`, `GnssSummary.kt`, `TakenFix.gnssJson` | satellites / C/N0 summary in the fix window; recorded, not judged on the phone |
| Root / hook / clone hints | `core-geo/integrity/IntegritySignals.kt:35-69` `RootHints` | su paths, test-keys, ro.debuggable, Magisk/KernelSU apps and mounts, Xposed/LSPosed packages, clone apps, secondary user, foreign data dir; sent as `DeviceStatusReport.root_hints` |
| Play Integrity / key attestation | `integrity/PlayIntegrity.kt`, `DeviceKey.kt`, `IntegrityCodec.kt`; project number from `BuildConfig.PLAY_INTEGRITY_PROJECT_NUMBER` (`SessionModule.kt:54`) | evidence at login/check-in, collected by next sync, never awaited; verdict is read on the server |
| Signals tracker | `IntegritySignalsTracker` | change in signals goes out as `device_status` trigger `integrity_change` |
| Server | `backend/masterdata/.../RiskSignals.kt:58-63,138-150` (GEO_MOCK, teleport, zero jitter, GEO_DEVICE_SERVER_MISMATCH), F-SYS-012 geo re-check, `DEVICE_DEBUG_ENABLED / DEVICE_MOCK_APP_PRESENT / DEVICE_INTEGRITY_FAIL` | statistical rules over a day |
| Not present today | | test-provider check, GNSS-versus-claimed-accuracy check, fix-age/elapsedRealtime consistency, previous-fix jump/speed on the phone, fused versus network comparison, emulator indicators, a gate on Developer options, any hard refusal that a bundle setting cannot soften |

## (c) Design

### Part 1: remove MDM from the apps
Delete from the apps' build: the `dpc` policy, blocking, mdm and provisioning code, `DpcBootReceiver`, `HardEndReceiver`, `MdmWindowReceiver`, provisioning activities and QR scanner path, `MdmCommandShell` and its worker use, `DeviceOwnerPolicy` calls in SrDay/AmoDay/DeviceRuntime. Server tables and endpoints are untouched (dormant).

Release of an installed device owner (new `DeviceOwnerRelease` in a small module, see below), run from `Application.onCreate` on a background dispatcher, ahead of everything else:
1. If `isDeviceOwnerApp(pkg)` is false: write the `released` flag, done (idempotent no-op).
2. Else, in this order, each step safe to repeat: (a) un-suspend every package this app suspended (read the stored `BlockingStore` suspended set, then also `setPackagesSuspended(all in set, false)`); (b) clear lock-task packages and kiosk (`setLockTaskPackages(empty)`, clear persistent preferred activities); (c) `clearUserRestriction` for the restrictions the old `PolicyApplier` set (list taken from its constants so none is missed); (d) cancel the HardEnd and MDM alarms by PendingIntent; (e) `clearDeviceOwnerApp(pkg)`; (f) write the flag file last.
3. A kill at any point leaves `isDeviceOwnerApp` true or the flag missing, so the next start repeats from the top; repeating a done step is harmless.
4. A tiny `AronDeviceAdminReceiver` stays in the manifest (empty class, no intent filter for provisioning) ONLY so that an upgraded device that is still registered as device owner keeps a resolvable admin component while it releases. This is the one receiver that serves the release; it can be deleted one release later. I flag this to the lead because the directive says "no receiver ... that only serves them"; it serves only the release.
5. The release code is kept for good in the apps (`dpc` is reduced to it) until the owner confirms no phone still holds device owner. Honest limit: a phone that never starts the new build keeps its restrictions; the lab/field must start the app once.

What I keep (not device-owner dependent): device key + server device registration (EnrolmentStore, proofs, record signatures, code-entry registration without QR provisioning); self-update install through the package installer (user confirms where the platform requires it; the silent path was device-owner only, so I will say that in the status when it is walked); battery-optimisation prompt; DeviceStatusReport (deviceOwner sent false); breadcrumbs (they only read `mdmApplier.locationMode()`, replaced by the bundle setting `cfg.geo.breadcrumbs_enabled`, and the MDM policy `interval` input is dropped); trusted clock; push; update flow.
`dpc` will be renamed in place to keep the diff reviewable: the module stays named `:android:dpc` with only `release/`, `update/`, `enrolment/EnrolmentStore` family and `BatteryExemption`; I will say if a module rename is cheaper once I see the compile graph.

### Part 2: developer-options gate
- New package `com.aktcl.aron.core.system.integrity` in `core-system` (core-ui untouched; I only use kit components). Pure logic (`DevOptionsPolicy`, `LabExemptionStore`, `IntegrityEventLog`) is Android-free and unit-tested; the Android reader uses `Settings.Global.DEVELOPMENT_SETTINGS_ENABLED` and `ADB_ENABLED`.
- Checked in `Application.onCreate`, in `ProcessLifecycleOwner` ON_START, and in every Activity's `onResume` (a shared `IntegrityGateHost`); also the "check again" button. No polling, no observer, no extra sensor: two `Settings.Global` int reads (microseconds).
- Gated state replaces the Activity content with one screen (Bangla first, English second): why, the exact steps (Settings > About phone > tap Build number is how it was turned on; to switch off: Settings > System > Developer options > top switch Off; and USB debugging Off), a "check again" button. No login, no sale UI, no navigation. Non-main activities (`OutletMapActivity`, any deep-link target) call the same host and finish if gated, so no deep link or recents entry gets behind the gate. Window is `FLAG_SECURE`-neutral (no data shown).
- (i) DATA IS NEVER LOST. The gate is a view layer only: it does not log out, clear the session, close or migrate the database, touch the outbox or cancel work. The WorkManager sync worker, push and alarms do not depend on any Activity and never read the gate, so queued records keep uploading while the screen is up. Test: a gated start leaves Room row counts and the outbox unchanged and a sync run still drains. Open-visit or half-done sale state stays in Room and is resumed when the gate lifts.
- (ii) Event record: `IntegrityEventLog` (append-only JSON lines in noBackupFiles, capped at 200, atomic write) stores `{at_utc, business_date, flag: "dev_options"|"adb", app_version, exempt: bool}` once per gate onset (not per resume). Sent with the next `device_status` as `integrity_events[]` and trimmed only after the server acked (reuse `IntegritySignalsTracker` pattern, `recordSent` after the outbox write).
- (iii) Lab exemption: bundle config `cfg.integrity.lab_exemption = {until_utc, issued_utc}` for this device (server decides by device id). The phone stores `{until = min(until_utc, now+7d), issued, bootCount, elapsedAtStore}` and refreshes it on every online bundle/config fetch. Fail-closed: absent, expired, unparsable, or clock-inconsistent means no exemption. Clock-change defence: the exemption is also bounded by `SystemClock.elapsedRealtime()` since storing (same boot, via `Settings.Global.BOOT_COUNT`), a wall-clock move backwards (now < issued) voids it, and after a reboot only the wall clock bounded by the last server time seen is used. Debug builds (`BuildConfig.DEBUG`) are exempt until the server piece lands; release builds never. Tests: expiry, wall clock set back, wall clock set forward past nothing, reboot, corrupt store, missing flag.
  Smallest server piece (migration left UNNUMBERED, no file written): table `app.device_lab_exemption(device_id bigint primary key references app.device(id), granted_by bigint not null, granted_at timestamptz not null default now(), expires_at timestamptz not null, note text)`; admin write `PUT/DELETE /v1/admin/devices/{id}/lab-exemption` under the existing `mdm` admin module grant (Super Admin and granted Admins only); the bundle builder adds `cfg.integrity.lab_exemption` when a row is unexpired. Max grant length capped server-side (14 days).
- (iv) No bypass: restart re-reads the settings (nothing persisted says "ok"); clock change cannot extend an exemption (above); a deep link or exported activity goes through the same host; the gate state is derived, never a stored "passed" flag.
- The look: shared kit (`AronTheme`, gradient top bar, white card, primary button) per PARITY-SPEC; strings in the localisation layer (values and values-bn).

### Part 3: anti-spoofing in layers (slice 2)
Each layer yields a code; none is called proof. Hard = refuse the geo-validated action; soft = attached to the fix and the visit.

| Code | Type | Check | Cost |
|---|---|---|---|
| `MOCK_FLAG` | HARD | `Location.isMock` / `isFromMockProvider` on every fix (exists) | none |
| `MOCK_APP_SELECTED` | HARD | `AppOpsManager.unsafeCheckOpNoThrow("android:mock_location", uid, pkg)` is MODE_ALLOWED for a visible candidate package | one AppOps read per candidate, cached 10 min |
| `TEST_PROVIDER` | HARD | provider name on the fix is not one of fused/gps/network/passive, or `LocationManager.getProviderProperties` of "gps" is the default test-provider shape while the fix claims gps | none to one binder call |
| `FAKE_GPS_APP_INSTALLED` | soft | package in a curated list (declared in `<queries>`, ~40 names) is installed | cached 10 min |
| `GNSS_NO_SATS` / `GNSS_NONE` | soft | provider `gps` with zero satellites used in fix, or no GnssStatus at all while accuracy < 20 m. Balanced fused fixes legitimately use no satellites, so only checked when provider is gps or accuracy < 20 m | reuses the existing GNSS window, no new sensor |
| `FIX_STALE` / `FIX_CLOCK_MISMATCH` | soft | `elapsedRealtime` age against now beyond bound; `Location.getTime` versus device wall clock minus fix age beyond bound | none |
| `JUMP` / `SPEED` | soft | haversine and speed against the previous fix (last fix kept in a small prefs file, one write per fix) beyond a bound from config | none |
| `PROVIDER_DISAGREE` | soft | fused versus `getLastKnownLocation(NETWORK)` (passive read, no new request) farther apart than max(accuracy sum, bound), only when the network fix is younger than 2 minutes | none |
| `ROOT_HINT` | soft | existing `RootHints`, now restricted to package names declared in `<queries>` | startup only |
| `EMULATOR` | soft | Build fingerprint / hardware / product markers, goldfish/ranchu props | startup only |
| `HOOK_FRAMEWORK` | soft | Xposed bridge class loaded, Frida default port on loopback refused/accepted, `/proc/self/maps` markers | startup + per sync, a few ms |
| `PLAY_INTEGRITY_DEVICE` | soft (server reads verdict) | fetched only online by the existing evidence path, cached; dormant while `PLAY_INTEGRITY_PROJECT_NUMBER` is 0. Owner must provide: the Google Cloud project number linked to the Play app (Play Console > App integrity); no key in the repo | one request per day, off the sale path |

Decision rule: `GeoVerdicts` gets the hard signals as input and returns `BLOCKED` with verdict `MOCKED` for them whatever `MockPolicy` the bundle says (a bundle cannot soften it in release builds). The visit screen shows Bangla and English text and records the attempt (a `geo_fix` row with the codes). Soft codes never block; they are written into the fix (`signals[]`) and the visit record and sent on sync.

Package visibility: remove `QUERY_ALL_PACKAGES` from `core-geo/AndroidManifest.xml` (Play restricts it to apps whose core purpose needs it; a sales app does not qualify, checked against the `play-policy-insights` and `android-permissions-security` guidance). Replace with an explicit `<queries>` list of package names. Consequence, stated plainly: the current generic scan "any installed app that requests ACCESS_MOCK_LOCATION" goes away; a fake-GPS app not on our list is only caught by the mock flag at fix time, not by presence.

Contract change PROPOSED (not made): `GeoFix.signals: string[]` (codes above), `DeviceStatusReport.integrity_events: {at, flag, exempt}[]`, `DeviceStatusReport.hook_hints`, and bundle `cfg.integrity.lab_exemption`. Server must accept the new codes into `app.geo_fix` (new column or jsonb) and map them into `app.risk_signal`. Waiting for the lead's contract version number.

## Not defeated, not tested on a device (honest)
- A rooted phone that hides its tools from the app, a hooking framework that hides itself, or a module that spoofs `Settings.Global`, `isMock`, AppOps and GnssStatus together defeats every on-phone check.
- RF-level GNSS simulators and modified hardware give clean fixes with no mock flag; only the server's statistical rules and Play Integrity can point at them, and only over time.
- Developer options can be reached again by enabling them in a second user/profile or with a ROM that hides the setting: the gate reads the global flag only.
- Nothing in this file has been walked on a phone yet. Device walks owed: gate screen on Honor/Samsung/A06 (Bangla fonts), turn-on/turn-off while the app is in recents, release of device owner on a phone that was enrolled, queued sync while gated, AppOps mock detection per OEM, battery impact.

## Decisions for the lead (answer only if you disagree; I proceed on these)
1. Keep a one-release empty `AronDeviceAdminReceiver` for the release step (above).
2. Remove `QUERY_ALL_PACKAGES`; accept the loss of generic mock-app enumeration.
3. Keep device registration (key + device id) without QR provisioning; `EnrolmentGate` stays as code entry. If you want the QR scanner and Camera permission removed from the apps too, say so.
