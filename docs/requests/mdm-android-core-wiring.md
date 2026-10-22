# Request (mdm → android-core / android-geo-dpc, 2026-10-22): wire the DPC command channel into the app

The DPC half is on lane/mdm (`android/dpc/.../mdm`, `docs/status/mdm-dpc.md`); the server half is `GET /v1/devices/me/commands`
and `POST /v1/devices/me/commands/{id}/ack` (contract 1.4.0, behind `ARON_MDM_ENABLED=1` until db lands the migration).
Not done by this lane because it lives outside `android/dpc`:

1. `CommandApi` over the app's device-proof HTTP client (same `X-Device-Id` / `X-Device-Proof` as `GET /v1/devices/me/policy`; the ack body is signed like the status POST).
2. The server's command-signing public key for `CommandVerifier.fromJwk`: take it from `GET /v1/auth/jwks` (the ES256 key that signs access tokens also signs commands, kid in the token header); cache it with the policy, refresh on a verify failure once.
3. Call `CommandProcessor.sync()` from the sync worker (every sync, as the brief says) and from the FCM data message `kind=mdm_nudge` (no payload; one pull).
4. The geo layer reads `mdmApplier?.locationMode()` (`off | on_demand | interval`) and the interval; `off` means no fix except a `locate` command.
5. Status report: add the optional `network_type` (`wifi|mobile|offline`) to `DeviceStatusReport` when the contract owner agrees (see mdm-contract-network-type.md); the MDM list shows `unknown` until then.
