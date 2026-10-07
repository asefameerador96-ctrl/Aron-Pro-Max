# Status: lane android-geo-dpc

## State (2026-10-07)
The build works again (owner-approved Maven mirror in settings.gradle.kts). All rows below compile and pass their host tests (core-geo 64 tests, dpc 38), plus lint and the SR release build. Each had an independent Opus checker; every confirmed defect was fixed and the checker's failing test kept as a regression test.

| Row | State | Notes |
|---|---|---|
| N-021 fix manager | built, checked; device half pending | battery trace on the A06 (device-checks.md); caller in feature modules (wiring request) |
| N-025 GNSS capture | built, checked; device half pending | A06, A07, Honor X5c Plus fix carries satellites or the not-supported flag |
| N-026 key attestation, Play Integrity | parts done, wiring pending | login, nonce and enrolment callers: `docs/requests/android-geo-dpc-wiring.md`; marker field: `android-geo-dpc-integrity-marker.md` |
| F-SYS-031 integrity signals | parts done, wiring pending | batch hook in core-sync; `root_hints` member: `android-geo-dpc-root-hints.md` |
| N-029 policy core | built, checked; DEVICE-PENDING | prod-policy checks on an enrolled A06 |
| N-032 app blocking | built, checked (DPC side); DEVICE-PENDING | check-in/out call from F-SR-011; airplane-mode and reboot check on a phone |
| N-035 breadcrumbs | built, checked (5 checker findings fixed); foreground-only until the lead rules on `android-geo-dpc-breadcrumb-background.md` (F-SYS-023 forbids background location); DEVICE-PENDING | battery on the A06; wiring (controller install, geo_breadcrumb sink, check-in/out calls) in the wiring request |
| N-034 managed update | built, checked (3 confirmed + 5 plausible checker findings fixed); DEVICE-PENDING | update worker wiring (prompt UI for `AwaitingUser`, unique work) in the wiring request; device check on an enrolled phone |
| N-030 enrolment by QR | client built, checked; server and wiring pending | needs N-031 (server) and `android-geo-dpc-enrol-replay.md`; Android 12+ activities wait for `android-geo-dpc-provisioning-activities.md`; app start wiring in the wiring request; device check D-04 |

## Handoff (2026-10-07, READY TO RECYCLE)
- **Git:** lanes push only to `lane/android-geo-dpc` (docs/26 s3); the integrator promotes green heads. Merge INT before every push.
- **In progress:** nothing; every row of the lane is built and checked.
- **Next three:**
  1. Watch `docs/status/train.md`: if the integrator reports this lane's head breaking a candidate, fix it.
  2. When android-core lands the wiring call sites, re-run the module tests and flip N-026, F-SYS-031 and N-030 to done.
  3. When android-core answers "allowlisted" (docs/requests/android-geo-dpc-provisioning-activities.md), add the two provisioning activities to android/dpc/src/main/AndroidManifest.xml (exact XML in that request).
- **Waiting on others:** N-031 enrolment replay (backend, `android-geo-dpc-enrol-replay.md`).
- **Local build:** `/tmp/claude-0/g.sh`-style quiet Gradle runs; Robolectric SDK 34 jars cannot download here (429), CI is the check for them.
- **Checker pattern that worked:** a fresh Opus agent per row with the row text, acceptance, files and "write a Recheck*.kt failing test"; keep its tests as regression tests.

## What software cannot stop (honest limits)
- RF-level GNSS simulators and modified hardware give clean fixes with no mock flag; the server's statistical rules (teleport, zero jitter, same point, GNSS C/N0 spread) flag them over a day, not per fix.
- A rooted phone that hides root defeats the on-phone hints; Play Integrity's device verdict is the stronger check (server side).
- Balanced-power fixes often do not use the GNSS chip, so many fixes carry zero satellites; that is reported honestly, not invented.

## Interfaces for feature lanes (N-021, `com.aktcl.aron.core.geo`)
```kotlin
class FixManager(
    source: LocationSource,            // FallbackLocationSource.of(context): fused, platform provider without Play services
    access: LocationAccess,            // AndroidLocationAccess(context)
    deviceState: DeviceStateReader,    // AndroidDeviceStateReader(context, clock)
    clock: WallClock,
    ledger: FixLedger = MemoryFixLedger(),   // PrefsFixLedger(context) in the app
    gnss: FixWindowObserver = FixWindowObserver.None, // N-025 fills it
    settings: () -> FixSettings = { FixSettings() },  // cfg.geo.fix_timeout_s, fix_accuracy_mode, fix_reuse_max_age_s, require_precise
) {
    suspend fun take(purpose: FixPurpose, cycle: String? = null, refreshCount: Int = 0): TakenFix
    suspend fun warmUp(cycle: String)   // on entering the outlet list (D-74); at most 8 unused warm-ups per day
    suspend fun forget(cycle: String)
    suspend fun wastedWarmUps(): Int
}
// TakenFix: every GeoFix member (purpose, fixStatus, lat, lng, accuracyM, ..., isMock, reused, refreshCount, gnssJson, device).
// Never throws for location problems: fixStatus is ok / timeout / permission_denied / location_off / provider_unavailable.
```
Example (visit open, then the verdict from shared:rules, then storage):
```kotlin
fixManager.warmUp("outlet-list")                       // when the outlet list opens
val fix = fixManager.take(FixPurpose.VISIT_OPEN, cycle = "outlet-list", refreshCount = refreshes)
val v = GeoVerdicts.verdict(FixInput(fix.isOk, fix.lat ?: 0.0, fix.lng ?: 0.0, fix.accuracyM, fix.isMock), outletGeo, radiusM, maxAccuracyM, policy)
captureRepository.recordVisitOpen(visit, fix.toEntity(ClientIds.newUuid(), visit.clientUuid))
```
`TakenFix.toEntity` (the field-by-field mapping to `GeoFixEntity`) is in core-geo's test `ScriptedSrDayTest.kt`; copy it into the feature until a shared place exists. A refresh (`refreshCount > 0`) always takes a fresh fix.

## Interfaces for feature lanes (N-032, `com.aktcl.aron.dpc`)
```kotlin
val dpc = DeviceOwnerPolicy.get(context)          // process-wide; applies the stored policy offline
dpc.receive(policy)                               // policy from enrolment, login or a config delta with policy_changed
dpc.onCheckInCommitted()                          // right after the check-in attendance_event commits (F-SR-011)
dpc.onCheckOutCommitted()                         // right after the check-out attendance_event commits
dpc.configure(trustedClock::nowMs, calendar::isWorkingDay) // app start, before reapply()
dpc.reapply()                                     // app start (Application.onCreate); boot is handled by the DPC itself
BatteryExemption.requestIfNeeded(activity, policy.selfProtection.batteryOptimisationExempt) // login / enrolment screen
```
`BlockingOutcome(active, since, suspended, failed, changed)`: send a `device_status` when `changed`.

## Decisions (android-geo-dpc)
- **GD-01:** D-74 says "a fix up to 60 s old and 30 m away may be reused". It is read as: same purpose cycle, at most `cfg.geo.fix_reuse_max_age_s` old by elapsed realtime, and reported accuracy at most 30 m. A manual refresh never reuses a fix.
- **GD-02:** `QUERY_ALL_PACKAGES` is declared to detect mock-location apps (the apps install outside Play).
- **GD-03:** a phone without working Play services falls back to the platform LocationManager for the single fix.
- **GD-04:** Play Integrity `requestHash` is lower-case hex of SHA-256(nonce + device_uuid); confirmation requested from backend.
- **GD-05:** the applied policy and the blocking state are small files in no-backup storage until `aron-device.db` exists (docs/24 s5.2 names it; no lane has built it yet).
- **GD-06:** the hard-end release uses an inexact alarm (`setAndAllowWhileIdle`, within minutes) so no exact-alarm permission is needed; every app start and boot re-evaluates too.
- **GD-07:** unknown working-day calendar counts as a working day for blocking (the rep checked in).
- **GD-08:** a new device key is always created under a new alias (`DeviceKeySpecs.newAlias()`); the old key is deleted only after the server accepts the new one, so a failed re-enrolment never strands the phone.
- **GD-09:** no public Android API grants the battery exemption silently to a device owner; the DPC opens the one-tap system prompt and reports `self:battery_optimisation_exempt` until it is held.
- **GD-10:** warm-up fixes (D-74) are capped at 8 unused per business date so a day with slow walks still stays within the 80-fix budget.

## Traps found
- Never `git clean` in this shared tree: parallel checkers' untracked test files get deleted.
- Breadcrumb state is elapsed-realtime based; a reboot is detected by the wall-minus-elapsed offset and re-anchors the window.
- Robolectric's DevicePolicyManager shadow records restrictions in UserManager; read restrictions back with `UserManager.hasUserRestriction` (also the effective state on a phone).
- The state files live in credential-encrypted storage: do not handle `LOCKED_BOOT_COMPLETED`.
- An updated system app keeps `FLAG_SYSTEM`; testing `FLAG_UPDATED_SYSTEM_APP` would make allowlist mode suspend the Play Store.
- **GD-11:** `android:dpc` now depends on `:shared:contract` for the v1.2 wire DTOs (lead ruling 2026-10-07), beyond the docs/24 s2.1 table (core-common only). `EnrolDeviceResponse` and `DevicePolicy` stay local mirrors until shared adds them.
