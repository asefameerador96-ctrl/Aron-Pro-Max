# mdm-dpc: device-owner command channel (lane mdm, DPC half)

Branch `lane/mdm-dpc`. Code: `android/dpc/.../dpc/mdm/`. Tests: `MdmTest` (13, green). Additive: `DeviceOwnerPolicy` gained `mdmHook`/`mdmApplier` and one manifest receiver; BlockingEngine, HardEndAlarm, PolicyApplier are unchanged.

## Done
- `CommandProcessor.sync()`: retry owed acks, pull, verify, execute once, ack. Call from the sync path and from an FCM data-message nudge (the nudge carries no trusted data).
- Verification: ES256 over `aron-command-v1\n<command_id>\n<device_uuid>\n<type>\n<created_at>\n<expires_at>\n<params.toString()>` (compact JSON in the order the server sent). Unsigned, bad signature (also a command made for another phone), expired and unknown type are skipped and listed in `SyncReport.rejected`, never acked.
- Dedupe and ack retry: `CommandLedger` (atomic file, newest 500). The ledger is written before the ack, so a command never runs twice; an ack that failed offline is re-sent with the stored result at the next sync.
- Executors (`CommandGateway`, Android impl `AndroidMdmGateway`): lock (+ lock-screen message), unlock (clears the message; Android cannot remotely unlock), message (bn/en notification), locate (one network-then-GPS fix, 20 s cap, result `lat,lng,accuracy_m,fix_at_ms`), reboot, factory_reset (lifts `no_factory_reset`, then `wipeData`, both after the ack attempt; refused when not device owner), apply_policy. Result capped at 2 KB.
- `MdmPolicyApplier`: allowed apps, weekday/time windows in Dhaka from the trusted clock (overnight windows, ISO 1 = Monday), kiosk via `setLockTaskPackages` + lock-task launch, location mode flag (`DeviceOwnerPolicy.mdmApplier?.locationMode()` = off|on_demand|interval). Packages the attendance BlockingEngine holds are never released by it. Window-boundary alarm `MdmAlarm` (inexact) re-runs `reevaluateBlocking()`, which also runs at start and boot.

## Contract the backend must follow (apply_policy)
`{"policy_id": null}` clears the policy (acked `{"cleared":true}`, idempotent). Otherwise `DeviceCommand.params` = `{"policy_id": <int>, "version": <int>, "policy": {allowed_packages, block_windows, kiosk, location_sampling}}`. An older version of the same policy_id is ignored (acked with `applied:false`). `message` params: `text_bn` / `text_en`; `lock`: optional `message`.

## Left for the app wiring (not in android/dpc)
1. A `CommandApi` over the app's device-proof HTTP client (`GET /v1/devices/me/commands`, `POST .../{id}/ack`); it throws on any network error.
2. The server's command-signing public key (JWK x,y) to `CommandVerifier.fromJwk`, and the device uuid; where the app keeps the directive key today is not in `android/dpc`.
3. The FCM service calling `CommandProcessor.sync()`; the sync worker calling it on every sync.
4. The geo layer reading `locationMode()`.

## Environment note
`CheckerRegressionTest` (existing, Robolectric) fails here only because the Robolectric android-all artifact cannot be fetched from this container; it is unrelated to this change.
