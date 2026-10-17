# Status: lane android-tso (wave 2, started 2026-10-17)

## Done (all pushed to lane/android-tso, each with a fresh checker; defects fixed with tests)
F-TSO-001 (Opus), 008, 009, 010+021 (Opus), 011, 012, 013-016, 018 (with photo), 024, 028, 029 (Sonnet).
F-TSO-027, 002-007 were handed to the android-amo session by the lead (2026-10-18); not done by this lane.

## How it is built (read before touching)
- `feature-tso`: one package per area (`leave`, `feedback`, `mycall`, `finalsubmit`, `periphery`, `password`) with pure states (StateFlow, ports), JSON readers (org.json, bad rows dropped), screens on the Calm Glass kit. `TsoApp` wires them; state survives recreation through `rememberRetained` (keys carry the username; epochs in `RetainedCounter`).
- `app-tso`: `TsoReadModels` (online GET + cache in the encrypted per-user `sync_meta`, wiped with the DB), `TsoOutbox` (generic durable queue: outbox row + mirror rows in ONE Room transaction, idempotent by client uuid), `MediaShell`/`SystemShell` (copies of the AMO ones), `MainActivity` hosts it all.
- Every stable client uuid goes through `StableIds.v4` (the server accepts only UUID v4; `nameUUIDFromBytes` is v3 and is refused).
- Final Submit is online only by design; everything else a TSO writes is offline-first.

## Waiting / filed
- `docs/requests/android-tso-record-mapping.md`: zone names and the Wing/Division/Territory/House/Zone cascade (needs a scope-tree read; Final Submit shows one zone list), the TSO bundle must carry `rubrics`, `task_type` and `feedback_category`, confirm `GET /v1/visit-plans` filter, offline password verifier after a password change (android-core).
- DEVICE-PENDING lines are in `docs/status/device-checks.md` (D-TSO-001, 008/009, 011/012, 018/029 + photo, 024, 028).

## Known limits (not defects)
- My Team list is not grouped by route (legend counts only); Retailer "clustered markers" is the server cap of 300 plus LiteMapScreen's own cap.
- A rejected record keeps its "not sent yet" mirror row until the server shows the uuid.
- Zone label is "Zone <id>" (no name in the contract reads).

## Traps
- Local Gradle works online: `echo sdk.dir=/opt/android-sdk > local.properties`; run from the repo root; filter `Picked up JAVA_TOOL_OPTIONS`.
- Robolectric windows are small: use `performScrollTo` or tags on lazy lists; the real activity context is needed for `rememberLauncherForActivityResult`.
- Merging `lane/android-amo` conflicts in `TsoApp.kt` and strings: keep both (host-supplied `screens` map and the TSO flows).
