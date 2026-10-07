# Status: lane android-geo-dpc

## Blocked (2026-10-07)
- Gradle cannot resolve dependencies in this container: Maven Central answers HTTP 429. No registry mirror or init-script workaround is used (owner decision pending).
- The CI-as-compiler route (push to `lane/android-geo-dpc`) is waiting on the owner's go-ahead.

## In progress
- **N-021** location fix manager (`android/core-geo`): code and tests written, not yet compiled or run.

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

## Decisions (android-geo-dpc)
- **GD-01:** D-74 says "a fix up to 60 s old and 30 m away may be reused". It is read as: same purpose cycle, at most `cfg.geo.fix_reuse_max_age_s` old by elapsed realtime, and reported accuracy at most 30 m. A manual refresh never reuses a fix.
- **GD-02:** `QUERY_ALL_PACKAGES` is declared to detect mock-location apps (the apps install outside Play).
- **GD-03:** a phone without working Play services falls back to the platform LocationManager for the single fix.
