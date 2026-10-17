# Status: lane android-tso (wave 2, started 2026-10-17)


## Package items (2026-10-17)
- 1 Replay proof: kill-and-relaunch + duplicate/out-of-order replay for leave, feedback, visit plan, visit query, assigned task, my-call, password change (`TsoWriteReplayTest`, 14 tests; Opus checked).
- 2 F-SR-048 Tutorial list in the SR app: cached list, empty text, online-only tap, https-only, no autoplay or download; Linux goldens `sr_tutorial_*` (Sonnet checked).
- 3 Parity audit vs docs/08 and the manual (Opus): fixes applied; table and requests in `docs/requests/android-tso-parity-findings.md`.
- 4 Bangla + long-text sweep: lint green on feature-tso/feature-home, units localized (`tso_unit_m`), 3-line and 200% font-scale goldens (Home, Leave apply, Final Submit).
- 5 SR Home/Sale/Review/Memo state coverage: 21 new goldens (`SrStateScreenshotTests`), matrix and 5 presentation gaps in `docs/requests/android-tso-sr-state-coverage.md`; no SR logic edited.
- 6 Accessibility pass: `A11yAudit` (feature-tso tests: every clickable >= 48 dp and has a name, toggles say their state; self-tested to fail on a bad control) swept over every Home tile screen, the drawer and 8 screen states (Leave, Feedback, Final Submit, Visit Query, Set Plan, Assign Task); it found real defects, all fixed: text fields had no spoken name (`TsoTextField(name=...)` on Feedback, Leave, Assign Task, Visit Query answers, Change Password). Kit sweep in `KitGalleryTest` now also asserts every control has a name (clean). Colour-only status: chips and banners carry icon and words (kit). Known: reading order follows layout order, no explicit traversal index used; real TalkBack run is a device check.
- 7 SR parity audit (Opus): `docs/requests/android-tso-sr-parity-audit.md` (0 blockers, 11 majors, 62 minors); first batch of presentation fixes done (pack badge, stick word, picker wording, outlet on Sale/Review, memo number), goldens re-recorded.
- 7b Q2 six-type QC entry (lead ruling): done, awaiting the Opus checker; request for the max-QC source and a contract-value defect found in `docs/requests/android-tso-qc-max-source.md`.
- 7c SH2 per-SKU Sale History (shared feature-memo, SR + AMO): done (pick a date, per-SKU table, exact total, goldens, tests); the GET /memos online fallback is not wired (no API client in the SR/AMO apps), proposal in `docs/requests/android-tso-sale-history-fallback.md`.
- 7d Q4 QC submit confirm (green Yes / red No): done inside the QC screen.

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

## Parity audit (Opus checker, 2026-10-18): per-screen result after the fixes of this pass
Source: docs/08, docs/evidence/manuals/manual-tso.md and delta-tso.md (docs/ui-reference has no TSO screenshots). Not counted as divergences: Target Status and achievement rings (docs/27), Calm Glass styling (docs/32), "last fix with age" instead of live on My Team (G-10), the unsent-data logout guard (G-01), the Final Submit confirm (G-07), Settings in place of Target Status, the own-feedback list.
| Screen | Result after this pass | Open owner |
|---|---|---|
| Login | match; dark navy theme, version, sun switch, enrolment step are deliberate; button reads "Log in" | feature-auth (label) |
| Default language | fixed: English on first run (D-181), switch in Settings | - |
| Home and greeting | greeting and logout icon match; Home lands on a tile Home (deliberate), the header shows twice (chrome bar + role header) | docs note |
| Home button | strip below every non-Home screen (deliberate, never over a bar) | - |
| Drawer | fixed: logout icon in the header, subtitles of the manual, My Call order; Settings replaces Target Status; hubs instead of expanding groups (deliberate) | - |
| Dashboard tiles/cards, Not Logged In / Not Uploaded | not this lane (android-amo); blocker: rows lack user and department | android-amo, backend (requests/android-tso-parity-findings.md) |
| Leave list | fixed: titles "Leave Applications", "Apply For Leave", "Reason:", "n Days"; type on the card and newest-first order are deliberate improvements | - |
| Leave apply | fixed: real date picker, labels "Leave Apply / Select leave Type / Select Date / Number of Days / Apply", Casual/Sick/Earn; type is preselected (Casual) and days use a stepper (deliberate) | - |
| Final Submit | fixed: Success dialog, Routes heading, zone shown; zone names and the five-level cascade need a scope-tree read | contract, backend |
| My Team / Retailer | radius options match; zone names pending | contract |
| Set Plan / My Visit Plan | fixed: date picker with the long date, hub order and subtitles; card fields lack labels and channel | backend (channel), later UI pass |
| Visit Query | fixed: answers optional, free-text and yes/no criteria, delegate label of the manual, default No; two printed questions need seeding | backend (rubric seed) |
| Assign Task | date picker; shows the assignee; no Outlet/Cluster/Route card; list of types not filtered | backend (assignable types) |
| My Feedback form | labels differ ("Category/Title/Description"); image from camera not gallery | android-sys (gallery source) |
| My Feedback list, Settings | deliberate improvements | - |
| Logout dialog | fixed: title "Log Out!"; body and the unsent guard are deliberate | - |
| Date formats | fixed: ISO in lists and Sales Date, long form in pickers and plan screens (TsoDates), Bangla month names and digits | - |
