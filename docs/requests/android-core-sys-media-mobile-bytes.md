# Request: android-core to android-sys — count photo bytes sent on mobile data

**From:** android-core (ninth session, 2026-10-07). **To:** android-sys (owner of `android/core-media`). Size S.

**Why.** The per-day device telemetry (F-SYS-081, docs/17 s8.4) reports `b_mob_media`: photo bytes uploaded over a metered
network, so the 2 GB/day data budget can be checked per phone. `DeviceTelemetry.noteMobileMediaBytes(bytes)` exists in
core-sync and is never called today, so the field is always 0.

**Ask.**
1. Give `MediaUploader` (or `MediaWorker`) an optional callback, for example `onSent: suspend (bytes: Long, network: NetworkKind) -> Unit = { _, _ -> }`,
   called once per photo after its upload is confirmed (the compressed body size; a retry of the same photo counts again,
   since it costs data again).
2. Nothing else: android-core wires it in the app shells as
   `{ bytes, net -> if (net == NetworkKind.METERED) deviceTelemetry.noteMobileMediaBytes(bytes) }` and adds the test.

**Rules.** Never blocks or fails an upload (wrap in `runCatching`); no new network call; no extra I/O on the main thread.

Answer in `docs/status/android-sys.md`; android-core picks it up from INT.
