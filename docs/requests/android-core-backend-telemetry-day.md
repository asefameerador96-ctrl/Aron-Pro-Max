# android-core to backend-core: ingest `telemetry.day` into fact_device_day (F-SYS-081)

**Filed 2026-10-07 by android-core (seventh session).** Routed by the lead.

From this push the phones put the daily telemetry object (doc 17 s4.4, D-507, D-509) in the `/v1/sync/batch` body's
`telemetry` member (`SyncBatchRequest.telemetry`, already in the contract as `JsonElement?`). Today the backend ignores it.

Shape (one object, at most 1,024 bytes, for a business date that has closed on the phone):

```json
{"d":"2026-10-05","b_mob":182340,"b_wifi":0,"cpu_ms":48210,"wake_ms":61000,"starts":3,"gps":64,
 "bat":[96,71,null],"plug":0,"regained":"2026-10-05T11:02:17.000Z"}
```

- `b_mob` is every byte of the app uid on a metered network, photos included. `b_mob_media`, when present, is the photo
  part of it (the media uploader does not report it yet, so it is absent: unknown, not zero); store
  `bytes_mobile_app = b_mob - b_mob_media` when both are there. `bat` is the 08:00, 12:00, 17:00 Dhaka percent or null
  when no sample fell in the half hour after; `regained` is the last time of the day the default network came back.
- The object is per DEVICE, not per user: on a shared phone one stream exists and rides whichever user's batch goes
  first, so key it by the device from the token and `d`, never by user.
- A day the server refuses twice (a 400/422 on a batch carrying it) is dropped by the phone, so parse it leniently:
  an unreadable `telemetry` member should be ignored, never fail the batch.
- The phone keeps the object until a batch that carried it is answered, so a lost answer sends the same date again,
  and a batch that is resent carries it too: please **upsert by (device from the token, `d`)**, never insert twice.
- A member may be missing when the phone trims to the cap: treat missing as unknown, not zero.

Ask: map into `dw.fact_device_day` (`bytes_mobile_app`, `bytes_mobile_media`, `bytes_wifi_app`, `cpu_ms`,
`wake_lock_ms`, `engine_starts`, `gps_fixes`, `battery_pct_08/_12/_17`, `charged_today`) and register
`cfg.telemetry.enabled` (default true) and `cfg.telemetry.device_max_bytes_per_day` (1,024) with delivery to the phone.
Also tell the phone if the contract should type the object (a `TelemetryDay` DTO in shared:contract).
