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

## Battery and data review of the TSO app against docs/04 (2026-10-18)
Result: **no breach found; one data item fixed.** Read from the code, not measured on a phone (D-TSO device checks cover the phone).
- **Polling and timers:** none in `feature-tso` or `app-tso` (grep for delay loops, timers, alarms, periodic work, wake locks: nothing; every read is one GET when a screen opens or on an explicit tap; no polling timer, F-TSO-027 rule).
- **Location:** one balanced-power fix per Find tap on the Retailer screen (core-geo Fused with platform fallback, 20 s timeout, listener released, never a stream, never last-known); My Team uses the server's last synced fixes, no phone location. No foreground service, no background location permission in the TSO app.
- **Photos:** the one feedback photo goes through the shared core-media pipeline: long edge 1024 px, JPEG 70, at most 150 KB (docs/04 target 100 to 200 KB), queued in the media queue, uploaded by WorkManager on Wi-Fi first (`cfg.media.wifi_only_default` true), never blocks a record; the camera is opened for the call only. Logout refuses while a photo is unsent.
- **Uploads:** every TSO write is an outbox row (leave, feedback, plan, query, task) sent by the shared sync engine in batches with the SR/AMO rules (WorkManager constraints, debounce); no TSO-specific sync code. Final Submit is one small online POST.
- **Downloads:** all list reads page at limit 100 or 500 and stop after 5 pages; OkHttp sends gzip. Fixed in this pass: routes and a route's outlets were re-read on every screen open; they are now served from the encrypted cache for 30 minutes (`REFERENCE_TTL_MS`), so a day of Set Plan, Visit Plan, Periphery and Final Submit costs a handful of requests. Leave, feedback, plans, team and nearby stay fresh-per-open (small, and the rep expects the latest decision or position).
- **Maps:** the tile image is the lite-mode static map, loaded only on the map tiles, cached on disk with a bounded cache (core-map); offline shows the last image and the list.
- **Size:** feature-tso adds the core-map and media wiring only; the APK size gate in CI is the check (infra `Release APKs and APK size gate`).
- **Mocked / assumed against the contract (not yet served):** zone names (labelled "Zone N"), the `rubrics`, `task_type`, `feedback_category` sections in the TSO bundle, the `visit-plans` filter by `planner_user_id`, the offline password verifier after a change. All filed in `docs/requests/android-tso-record-mapping.md`.
