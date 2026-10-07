# Status: lane android-geo-dpc

## Blocked (2026-10-07)
- Gradle cannot resolve dependencies in this container: Maven Central answers HTTP 429. No registry mirror or init-script workaround is used (owner decision pending).
- The CI-as-compiler route (push to `lane/android-geo-dpc`) is waiting on the owner's go-ahead.

## Written, not yet compiled or run (local commits only, waiting on a build)
- **N-021** location fix manager (`android/core-geo`).
- **N-026** device key with attestation, ES256 raw signer, Play Integrity standard request with an explicit unavailable marker (`core-geo/integrity`). Request: `docs/requests/android-geo-dpc-integrity-marker.md`.
- **N-029** device-owner policy core (`android/dpc/policy`): model, versioned store, applier, boot re-apply.
- **F-SYS-031** integrity signals with root, hook and clone hints, change tracker. Request: `docs/requests/android-geo-dpc-root-hints.md`.
- **N-025** GNSS summary for the fix window only (`GnssAccumulator`, `AndroidGnssObserver`).
- **N-032** app blocking from check-in to check-out (`android/dpc/blocking`), DPC side; the check-in screen (F-SR-011) calls the hook.

## Not started (dependencies in other lanes)
- N-030 enrolment by QR (needs N-031, backend), N-035 breadcrumbs (needs F-SYS-011), N-034 managed update (needs F-API-029).

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
    suspend fun warmUp(cycle: String)   // on entering the outlet list (D-74)
    suspend fun forget(cycle: String)
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
dpc.reapply()                                     // app start (Application.onCreate); boot is handled by the DPC itself
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
