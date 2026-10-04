# 04 — Offline, Sync, Cache, Battery and Size

This is the heart of the system. The SR app must run a full selling day with no signal and reconcile exactly when it syncs, on a cheap phone, without eating the battery or the data pack.

## Local store (on the phone)

- A local SQLite database (Drift or sqflite) **mirrors the field tables** from `docs/03`. Capture writes to it; all screens read from it. The network is never in the write path.
- The schema mirrors the server's transactional tables, plus a `sync_state` column per row (`pending`, `syncing`, `synced`, `failed`) and the `client_uuid`.
- A separate **media queue** table holds photo files to upload (path, purpose, linked record UUID, state).

## The reference bundle (download at login)

`GET /sync/bundle` returns, in one payload, everything the SR needs for the day:

- the SR's routes and the outlets on them (with lat/long and cluster),
- the product list, prices, and the per-zone sales plan,
- the SR's targets and current achievement,
- active offers/promotions and loyalty balances per outlet,
- the territory geofence radius,
- any assigned tasks and Astha/Diamond-League gift assignments.

Downloading the bundle is what the dashboard counts as "logged in". Keep it compact (see size budget); gzip it; allow a delta refresh (`?since=<timestamp>`) so a re-login isn't a full re-download.

## The sync protocol

1. **Capture offline.** Every `visit`, `memo`, `memo_line`, etc. gets a `client_uuid` at creation. The `visit` UUID is created first; its children reference it.
2. **Upload in batches.** `POST /sync/batch` sends pending rows grouped by type. The server **upserts by `client_uuid`** — idempotent, so retries and duplicates are harmless.
3. **Reconcile.** The response returns accepted counts per type. The app compares them to its own counts and shows the device-vs-server screen (as the current apps do). A mismatch is visible, never silent.
4. **Photos separately.** The media queue uploads to Blob Storage on its own, Wi-Fi-preferred; a slow photo never blocks a sale from syncing. The record stores the returned blob URL.
5. **Resumable & partial.** Sync can stop and resume; already-synced rows flip to `synced` and are skipped. Ordering: reference/visit parents before children; the server also tolerates out-of-order by UUID.

## The day state machine

One route moves: `not_started → logged_in` (bundle downloaded) `→ in_field` (check-in) `→ synced` (uploaded) `→ sales_submitted` (SR closes the day, dues cleared) `→ final_submitted` (TSO closes the zone). Login % and submit % are just counts of routes in each state. Check-out opens at 5 pm; final submit is once per zone per day.

## Conflict and integrity rules

- A record is immutable once synced; an edit or a due payment is a **new row** that references the original (`supersedes_memo_id`, `against_memo_id`). This gives a clean audit trail.
- A memo can be edited only inside the outlet geofence and only before QC (as today), enforced on device and re-checked on server.
- The server validates that referenced outlets/SKUs exist and are in the SR's bundle scope; anything else is quarantined for review, not dropped.

## Cache strategy

- **Reference data** (outlets, products, prices, targets) lives in the local DB for the day and is delta-refreshed, not re-fetched each screen.
- **Images** (product thumbnails, marketing/AV-KV content) are cached on disk with a bounded LRU and an explicit max size; evict oldest. Never hold images only in memory.
- **No browser-storage reliance**; this is a native app with a real local DB.
- Clear per-day working data on a clean final-submit to keep the footprint small (keep an audit copy server-side, not on the phone).

## Battery budget (hard rules)

- **No continuous GPS.** Request a single fused/balanced-power fix on demand — at outlet open, attendance, force-sale, outlet capture. Never a persistent high-accuracy location stream. This is also the biggest battery fix over continuous-tracking designs.
- **No foreground service that holds sensors open.** Background sync runs via WorkManager with constraints (network connected, battery not low), batched — not a persistent socket or frequent polling.
- **Coalesce work.** Sync on explicit triggers (sale saved, manual sync, end-of-day) and a periodic constrained job, not a tight timer.
- **Camera/photos:** capture, compress (target ≤ 100–200 KB/photo, long edge ~1024 px), queue, release. Don't keep the camera warm.
- **Wake locks** only around an active sync batch, released immediately after.

## Data-pack budget

- Compress the bundle and all uploads (gzip). Target a normal day's sync well under tens of MB including photos.
- Photos: compress hard (above), Wi-Fi-preferred upload, allow "sync photos on Wi-Fi only" setting.
- No background analytics chatter; no auto-updating feeds. (The known 2 GB/day burn is mostly non-app usage — MDM/APN is the company-side lever — but the app itself must be frugal and must never be the cause.)

## App size budget

- Target install size materially smaller than the current ~90 MB APK. Steps: ship per-ABI split APKs / an app bundle (don't ship arm64+armeabi+x86 to every device), strip unused assets (the current apps bundle demo audio/images and multiple fonts), compress images, and keep one Bengali + one Latin font. Audit the asset list before release.

## Testing the sync engine (required)

- Property/fuzz tests: random duplicate, reordered, and partial batches must converge to the same server state (idempotency).
- Kill-and-relaunch mid-sale and mid-sync must lose nothing and double nothing.
- Clock-skew and timezone tests: a late-evening sale lands on the correct Dhaka business date.
