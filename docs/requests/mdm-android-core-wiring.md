# mdm app wiring (was a request to android-core; done by lane mdm on the lead's instruction, 2026-10-22)

No android-core lane runs in wave 4, so lane mdm wired the DPC command channel itself, additive only:

- `android/dpc/.../mdm/MdmChannel.kt`: builds the `CommandProcessor` over the device-owner policy's own directory.
- `android/core-sync/.../mdm/MdmCommandShell.kt`: `DeviceMdmCommandHttp` (pull, ack, `GET /v1/auth/jwks` under the phone's own `X-Device-Proof`, no user needed; a 4xx ack is not owed forever), `MdmCommandRunner` (cached server keys, one refresh when a signature fails, never throws), `MdmCommandShell` (one per app, `afterSync()` and `pullAndApplyIo()`).
- Push: `PushPullKind.MDM` and FCM data message `kind=mdm_nudge` (no payload) -> one pull job; `SessionPushPull` takes the MDM pull before the signed-in-user check.
- Sync: each app's `SessionModule` calls `mdm.afterSync()` at the end of every sync run and provides `MdmCommandShell` (SR, AMO, TSO).
- Test: `core-sync/.../MdmCommandRunnerTest` (a queued command is pulled, verified, executed and acked once; a rotated key is refreshed once; a forged command never runs; offline and not-enrolled are quiet; `mdm_nudge` parses). The wiring is identical in the three apps because it lives in `core-sync`; compile of all three is part of the lane check.

Still open (belongs to the geo layer, android-geo-dpc / android-sr-a): read `DeviceOwnerPolicy.mdmApplier?.locationMode()` (`off | on_demand | interval`, and `locationIntervalMin()`) where location samples are decided; `off` means no fix except a `locate` command. And the optional `network_type` in the status report (mdm-contract-network-type.md).
