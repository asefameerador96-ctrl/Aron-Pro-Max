# Request (android-geo-dpc → android-core, android-sr-a): call sites for the geo, integrity and DPC parts

android-geo-dpc owns `android/core-geo` and `android/dpc` only. Every part below is built and tested there, but the row is only *done* once these callers exist in modules other lanes own. Until then the status file lists the rows as "parts done, wiring pending".

| Caller (owner) | Call | Row |
|---|---|---|
| Visit open, attendance, force sale, outlet capture (android-sr-a, feature-*) | `FixManager.take(purpose, cycle, refreshCount)`; map `TakenFix` to `GeoFixEntity` field by field (example: `core-geo` test `ScriptedSrDayTest.toEntity`) and commit with the capture | N-021, N-025 |
| App DI (android-core, app-*) | `FixManager(FallbackLocationSource.of(ctx), AndroidLocationAccess(ctx), AndroidDeviceStateReader(ctx, clock), clock, PrefsFixLedger(ctx), AndroidGnssObserver(ctx, clock), settings = { cfg.geo.* })` | N-021, N-025 |
| Check-in / check-out commit (android-sr-a, F-SR-011 and its check-out) | `DeviceOwnerPolicy.get(ctx).onCheckInCommitted()` / `onCheckOutCommitted()` after the transaction commits; send a `device_status` when the outcome is `changed` | N-032 |
| Application.onCreate (android-core, app-*) | `DeviceOwnerPolicy.get(ctx).configure(trustedClock::nowMs, calendar::isWorkingDay)`, then `reapply()` | N-029, N-032 |
| Login (android-sr-a, feature-auth) and the bundle/config delta (android-core) | `DeviceOwnerPolicy.get(ctx).receive(policy)` with `GET /v1/devices/me/policy`; `BatteryExemption.requestIfNeeded(activity, policy.selfProtection.batteryOptimisationExempt)` | N-029 |
| Login and before each batch (android-core, core-sync) | `IntegritySignalsReader.read()`; when `IntegritySignalsTracker.changed()`, add a `device_status` (trigger `integrity_change`) to the outbox, then `recordSent()` | F-SYS-031 |
| Login and every `integrity.refresh_h` (android-core) | `IntegrityEvidenceService(nonce = POST /v1/devices/nonce, PlayIntegritySource(ctx, projectNumber), deviceUuid).collect()`; put `Evidence` in `DeviceStatusReport.play_integrity`; never wait on it before login completes | N-026 |
| Enrolment (android-geo-dpc, N-030, after N-031) | `AndroidDeviceKeyStore.create(DeviceKeySpecs.newAlias(), IntegrityCodec.enrolmentChallenge(token))`, send JWK and chain; delete the old alias only after the server accepts | N-026 |
| `X-Device-Proof` signer (android-core, core-network `DeviceProofSigner`) | `AndroidDeviceKeyStore.sign(alias, proofString.toByteArray())` | N-026 |

Nothing here waits on the network before a sale; integrity calls run after login or in the sync worker.
