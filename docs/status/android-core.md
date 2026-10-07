# Status: lane android-core (Day 3)

## Done (builder, then an independent checker per row; every confirmed defect fixed with a test)
- **N-001** Native build and the three app shells. `:android:app-{sr,amo,tso}:assembleDebug` gives `com.aktcl.aron.sr/.amo/.tso`.
  - Each shell: Compose login (feature-auth), then a home placeholder (feature-home); Hilt wiring in the app module.
  - API base URL: a bare origin from `aron.apiBaseUrl`, validated at configuration time; a release build refuses http.
  - core-network: OkHttp, kotlinx.serialization, phone headers, `X-Aron-Api` edge detection, RFC 9457 problems, one refresh-and-repeat on `ERR_TOKEN_EXPIRED`/`ERR_SCOPE_CHANGED`, the `X-Device-Proof` hook and proof strings.
  - core-session: tokens encrypted with an Android Keystore AES-GCM key; Argon2id offline unlock (7 days, 10 failures then a doubling cool-down on elapsedRealtime, high-water clock against rollback).
  - core-session: a refresh failure flags re-auth and never ends local work; the upload grant survives logout; shared-phone users are kept separate.
  - Login is proven against MockWebServer serving the contract's own `phoneOk` and `BundleSr` examples (`SessionRepositoryTest`).
- **F-SYS-033** Labels ARON SR/AMO/TSO in both languages and the package ids, checked by aapt2 and by `AppCoexistenceTest`.
- **F-SYS-044** No shared authority, permission or sharedUserId; exported components are an explicit allowlist.
  - `-Paron.applicationIdSuffix=.copy` builds a fourth package, `com.aktcl.aron.sr.copy`.
  - **Device part pending:** printing from each app with the same paired MP-58N.
- **F-SYS-018** Languages, digits, fonts and the build gate.
  - Bangla and English resources; Bangla at first run with a persistent switch (`AppLocale`, no process-wide default-locale change).
  - Bengali digits through `LocaleDigits` (South Asian grouping), applied to dates and numbers, never to identifiers.
  - Fonts: Noto Sans Bengali 400 and 700, plus subset Noto Sans 400 and 700. The four files total 336 KB raw (182 KB in the APK). The OFL text ships as `assets/licenses/OFL-noto-fonts.txt`.
  - Build gate: `HardcodedStringScanTest` (in `core-ui` `testDebugUnitTest`, which CI runs) fails on any prose-like literal in app-*, feature-*, core-ui and dpc, and on any string without a `values-bn` twin. 17 gap cases found by the checker are tests.
- **N-016** Room database for the SR day.
  - Tables: route, outlet, sku, geo_fix, attendance_event, stock_movement, visit, visit_close, memo, memo_line, memo_discount, qc_line, outbox, sync_meta. Every device table is keyed by its client_uuid.
  - Captures write domain rows plus outbox records in one transaction. Payloads are built from the stored row and checked against the contract (encoded JSON, required members, nested GeoFix).
  - Tests: migration from the exported schema plus an identity-hash check; duplicate-uuid rejection in every table; kill and relaunch with a persisted in-flight batch; a seeded SR day (200 outlets, 60 SKUs) loads in about 0.2 s on the host.
  - A per-user database file (`aron-u<id>.db`) with a SQLCipher factory.

- **F-SYS-008** Idempotent batch upload (`core-sync` `SyncEngine`). Two Opus checker rounds; every finding is a test (`CheckerF008Test`).
  - Persisted in-flight batches are resent first with the same `batch_uuid` and `X-Batch-Attempt` + 1 (kill or lost answer ends in `replayed`).
  - New batches: commit order, at most 200 rows and 256 KiB raw, families kept whole where they fit, membership persisted in the transaction that marks them in flight; gzip, batch proof, acks applied in one transaction.
  - `outbox.attempts` now counts definitive failures only (retryable reject, isolated family, unreadable answer), never re-batches; skip-ahead after 5, `retry_exhausted` after 10.
  - 500 and 413 bisect to the failing family, which is held back for the run; a 400/422 counts only against the families its `errors[].pointer` names; 401 refreshes once; 403/426/429/503/transport keep the batch for an identical resend.
  - Replayed answers apply acks only; quarantine resolutions are stashed until the row is quarantined.
  - Property test: 40 seeds of lost answers, kills before and after the server, 500/503, retryable rejects, tiny batches and replays of old batches by a second path: exactly one memo and one set of lines per sale, every row acked with the server's id.
  - Not in this row: `device_money` (F-SYS-009 reconciliation), time anchors (F-SYS-049 supplies them through `SyncEngine(timeAnchors=)`), scheduling (F-SYS-011).

- **F-SYS-006** Day bundle into Room (three Opus checker rounds; `CheckerF006Test`, `CheckerF006DbTest`).
  - Room v2: `price`, `config_value`, `bundle_section` (auto-migration 1->2 with a test). One-transaction apply that only moves forward within a day; long text chunked under the 2 MB CursorWindow.
  - A prefetch of a later day is kept aside, the morning request is conditional on it (a 304 promotes it), and offline at day start it is promoted. A prefetch never counts as the day's login.
  - `BundleDownloader` stages pages per version and resumes after a kill; pages are verified (version, section, page, row total).
  - App shells: `UserDatabases` (per-user file, Keystore-wrapped 32-byte key used as a raw SQLCipher key, opened off the main thread) and `BundleDownloaders`.
- **F-SYS-049** Trusted time (`TrustedClockSource`, three Opus checker rounds; `CheckerF049Test`). One estimate per boot from `X-Server-Time`: late replies never step time back, a far reading needs the wall clock or a second reading, in-process anchors survive clock changes, boot identity from BOOT_COUNT, then boot_id, then wall-minus-uptime. The AC-04 offline-unlock guard runs on the raw wall clock (`WallClock.wallClockMs()`).
- **AUD-PERF-05** key half: raw SQLCipher key, cached Keystore key. Still open: session restore off the main thread.

- **F-SYS-011** Constrained background sync (Opus checker; `CheckerF011Test`). `WorkManagerSyncScheduler` + `SyncWorker` + `AronWorkerFactory` (app `Configuration.Provider`, default WorkManager initializer removed). Every job needs a network; one expedited job after a failure, then backoff; holds and Retry-After pause automatic triggers; the 15-minute periodic job exists only while rows wait. `NoPollingLintTest` scans every module for foreground services, alarms, timers, delayed posts, polling loops and sub-15-minute periodic work.
- **F-SYS-027** Memo numbering and the **Room v3 records** routed by android-sr-a, android-sr-b and android-print (two Opus checker rounds; `CheckerV3Test`). See "Room v3" below.

- **F-SYS-046** Connectivity trigger (Opus checker; `CheckerF046Test`). `ConnectivityFlush`: default-network callback, 5 s of quiet, `HEAD /v1/health`, then one request per user on the phone with rows waiting. It runs under its own WorkManager name so a backoff cannot delay it. No polling.

- **F-SYS-092** Resume config check (ruling R9; Opus checker, `CheckerF092Test`). `ResumeConfigCheck.checkOnResume(userId)` is one conditional `GET /v1/config/delta` when the last contact of this boot is more than 5 min old.
  - At most 24 answered requests per business date (AC-17); offline costs nothing. One check at a time per user.
  - A delta is applied in one transaction only when it continues the held version; a gap or a 410 flags `bundle.refresh_needed`.
  - A later bundle never rolls config back. The batch ack's server version is kept apart (`sync.config_version_server`).
  - android-sr-a: bind `ConfigCheck { resumeConfigCheck.checkOnResume(activeUserId) }` and LAUNCH it from onResume without waiting. Today `VisitFlow.kt:138` awaits it inside the visit lock during a geo refresh, which R9 forbids.
- **RoomPrintLedger** follow-ups for android-print (wiring gap 15 and ledger findings 1 to 5) are done:
  - a slip flags every movement of its Save;
  - a void slip or due receipt is not a copy;
  - `printed_at` has milliseconds;
  - history is in insertion order;
  - paper-out is never downgraded.

## Open issue for another lane
- `NoPollingLintTest` (F-SYS-011 acceptance: no timer under 60 s) fails on INT because of `app-sr/.../SrApp.kt:104`, `while (true) { delay(30_000) ... }` (android-sr). The lint allows a loop with a literal wait of 60 s or more. Better: wait until the next real boundary (the 17:00 gate, midnight) or refresh on resume.

## Room v3 (on INT 2026-10-07): for android-sr-a, android-sr-b and android-print
- `CaptureRepository.recordNumberedSale(sale, MemoNumbering(username, bindOrdinal, blockSize))` returns the memo number. It is reserved in its own committed step (a failed save burns it; never reused). The format is `<username>-<yyMMdd>-<seq3>`: device block, then overflow, then `MemoSeqExhausted`.
- `recordDueCollection(entity, fix?)`: own family, rank 0; `visit_client_uuid` is a parent reference. `amount_mtk` must be at least 10 and at most the outstanding; `cash` only.
- `recordVisitSkip(entity)`: no fix; `reason_code` must match `^[a-z][a-z0-9_]{1,40}$`.
- `recordDaySubmit(entity)`: own family, rank 0, one submit per (date, route, scope, cycle).
  - After a submit, visit open, sale, visit close, skip and route dues of that route-day are refused (`IllegalStateException`) until `reopenRouteDay(date, routeId, voidedCycle)`.
- `recordOutletRequest(entity, fix)`: `request_type` from the contract; `outlet_id` null only for `new`; at most 4 photo uuids; the origin visit must exist.
- `resolveTask(event, resolvedAt)`: an unknown task is refused. `ReferenceRepository.tasks()` reads the bundle's tasks; a local resolution is kept until its event is acked.
- `CaptureDao` reads:
  - memo children: `discountsOf`, `qcLinesOf`, `dueCollectionsOf`;
  - by business date: `qcLinesOn`, `visitClosesOn`, `dueCollectionsOn`, `visitSkipsOn`, `daySubmitsOn`;
  - memo history: `memosBetween`, `supersededIn`;
  - other: `outletRequests`, `taskEventsOf`.
- `qc_return` subtracts in `stockBalanceOn`.
- **Print** (android-print request s1): `RoomPrintLedger(db, envelope = { base -> CaptureMeta })` implements `PrintLedger`.
  - A memo print is in the visit family at rank 3 while the route-day is open. Otherwise (a reprint after Sales Submit) it is its own family without a `route_id`.
  - Tables: `print_event` (outbox `print_event`), local `print_job`, and `memo.printed_at` / `print_count`. A stock slip flips `slip_printed` on the `stock_movement` named by `ref_client_uuid`, which is one row per SKU: tell android-core if a slip must cover several rows.

## Fourth session (2026-10-07, from ~09:30Z)
- **android-sys app-shell wiring** (docs/requests/android-sys-app-wiring.md items 1, 2, 6; android-sys-logout-wiring.md shell part):
  - core-sync `shell/MediaShell` (Hilt singleton in all three shells): `install()` in Application.onCreate sets `MediaRuntime.wiring` (every known user, that user's upload grant, `OutboxRecordProbe` over the outbox plus `CaptureDao.photoOwnerExists`, `media_meta` sink -> `recordMediaMeta`; a fix row not on the phone goes as `fix: null`) and asks once for an upload; `cfg.media.*` read from the user's bundle; `wifiOnly` (WifiOnlySetting) and `scheduler` on the trusted clock; `afterSync(report)` asks for an upload when a sync run acked rows (`SessionSyncRunner(afterRun = ...)`).
  - app-sr: `SrDay.attachMedia(mediaShell.componentsFor(userId, ::businessDate))` (camera, `MediaPhotoPipeline` replacing `NoCameraPipeline`, `resume()`); `CameraCaptureOverlay` drawn above the screens; photos are claimed (`attach`) before the visit (force_sale) and the outlet request (outlet_capture) commit, with the stored fix uuid. The Force Sale shot reused by its location request stays with the visit.
  - Logout: core-sync `shell/ShellLogout.flow(role, userId)` (LogoutFlow + DatabaseLogoutPorts); SR keeps its confirmation and calls `logout()`; AMO and TSO use `check()`, `UnsentItemsDialog` and a toast for `WipeIncomplete`.
  - `SessionComponents.okHttp` is public (the media blob client derives from it).
  - Permission gates: app-sr already had them (android-sr-a). AMO/TSO shells are still the home placeholder: no attendance/sale screens to gate yet.
  - **Still open:** updater (item 4), PDA to Support tile (item 5, waits on the support key), Wi-Fi-only row in Settings (needs a slot in feature-home `SettingsContent`, android-sr-a).
- **Checker round on ec28160 and 3926bd6f** (fresh Opus): 1 confirmed, 1 plausible, both fixed with tests:
  - A delta that got no answer (offline, 503, 401, failure) no longer spends the server version (`BundleDownloader.ANSWERED`; `BundleDownloaderTest.anUnansweredDeltaIsTriedAgainAfterTheNextSyncRun`).
  - `UserDatabases.close` now blocks `of()` for that user until `allowOpen` (ShellLogout calls it when the logout ends), so a worker cannot reopen and cache a database whose files are being deleted (`CheckerUserDatabasesCloseTest`).
- **Checker round 1 on the wiring** (fresh Opus): 2 confirmed, 4 plausible; fixed:
  - the media worker no longer opens every user's database (`MediaStore` makes its folder when built: `MediaShell.hasQueue` looks first);
  - logout never crashes the app (a failed count or a second tap shows `logout_failed`);
  - a queued sync of a wiped TSO user creates no database (`UserDatabases.exists`, checked by `SessionSyncRunner`);
  - an answered but refused delta (4xx, unreadable) spends the version again; only offline, 401, 429, 503 and 5xx do not (`BundleDownloader.spendsVersion`);
  - an outlet request retried after a failed commit keeps one fix uuid, so the photo's stamp names the stored fix;
  - photo file I/O off the main thread (resume, claim, unsent count, install).
  - Not changed: the Force Sale photo's stamp is the visit's stored fix (the contract's `media_meta.fix` is a stored row); the shutter fix travels in the location request.
- **INT red job (lead #4)**: `core-media/consumer-rules.pro`; ACCESS_NETWORK_STATE in core-sync and core-system manifests; core-sync lint also needed `AndroidDevice` `isLocationEnabled` behind API 28 (providers below). `:android:core-sync:lintDebug :android:core-system:lintDebug :android:core-media:mergeDebugConsumerProguardFiles` pass locally.
- **Next:** updater (item 4), support tile (item 5, waits on the key), the Wi-Fi-only row (needs a slot in feature-home Settings, android-sr-a), then the requests naming android-core and `my-rows.py android-core --todo`.

## Handover (READY TO RECYCLE, 2026-10-07 ~08:45Z, third session)
- **Done this session** (each with independent Opus checkers; every confirmed defect is a test):
  - **DTO stubs removed** (one checker round, no defects): auth, bundle head parts, the SR record payloads, Route/RouteSnapshot/BundleOutlet/Sku/SkuPrice, ResolvedConfigValue, TimeAnchor come from `com.aktcl.aron.contract.*`. Kept local: `BundleHead`, `LoginStatus`, `Grant`, the lenient `Problem` reader (an error body must still map to a code), the v3 payloads, `MediaMetaPayload`, `ConfigSection`/`ConfigDeltaWire`, `DeviceNonceDto` (not generated). Wire change: null `distance_m`/`outlet_lat`/`outlet_lng` are now left out of `geo` (optional in the contract).
  - **Geo/integrity/DPC wiring** (docs/requests/android-geo-dpc-wiring.md; three checker rounds): core-sync `device/DeviceRuntime` (Hilt singleton in all three shells) builds the FixManager (`cfg.geo.*` via `DayConfig`, `integrity_ref`), configures the DPC (trusted clock, bundle calendar incl. `calendar_changes`, server `DayPlan` precedence) and re-applies off the main thread after loading the active user's calendar; `DeviceStatusReporter` runs in the sync worker before each batch: `integrity_change` reports (root_hints, R13/R18) and Play Integrity evidence at login (`SessionRepository.onlineLogins`), check-in (`check_in` trigger) and every `cfg.device.integrity_refresh_h`, marker when none (R12), 25 s deadline, never for Sales Submit/Sync button/check-out runs, never queued behind another run; `KeystoreProofSigner` (X-Device-Proof once enrolled); `aron.playIntegrityProjectNumber` build property (0 = not_configured, no network).
  - **R9 binding**: `ResumeConfigCheck` provided; app-sr binds VisitFlow's `ConfigCheck` as a launch (never awaited) and runs it on resume.
  - **F-SYS-009** (three checker rounds): `ReconciliationRepository` (core-database): `device_counts`, `deviceMoney` with the server's `IngestService.money` definitions (refused/quarantined rows out, refused edits retire nothing), rows from `cfg.sync.reconcile_types` (flat R17 or nested), states MATCH/MISMATCH/NO_SERVER_YET, reasons NOT_SENT/AWAITING_SERVER/SERVER_HAS_FEWER/SERVER_HAS_MORE for rows and money. Every batch carries `device_money`. **For android-sr-b (F-SR-034):** render `ReconRow.reason`/`MoneyCheck.reason` as text, Server column blank with `deviceAsOf` when `server == null`; build `day_submit` with `deviceCountsJson(date)` / `deviceMoney(date)`.
  - **F-SYS-007** (one checker round, fixed): `ReferenceRepository.applyBundleDelta` (one transaction, open memos follow moved outlets, resolutions stashed with the cursor), `SyncApi.bundleDelta`, `BundleDownloader.refreshDelta/refreshIfServerNewer/refreshOnForeground`; triggers: after a fully answered sync run when the server's bundle is newer (once per version), on foreground per `cfg.bundle.delta_min_interval_min` (app-sr). The phone never marks a login on a delta (s4.9 lets the server count it: the server lane decides).
  - **android-sys requests**: `CaptureRepository.recordMediaMeta` (media-meta), `UserDatabases.close`, `SessionRepository.forgetUser` (logout core parts).
- **Next** (in this order):
  1. android-sys app-shell wiring: docs/requests/android-sys-app-wiring.md (MediaRuntime in each Application, `mediaScheduler.requestUpload()` after a sync run that acked rows, `MediaMetaSink` -> `recordMediaMeta`, updater, support tile) and the shell part of android-sys-logout-wiring.md (`LogoutFlow` in the three shells). Not yet re-checked by a checker: commit ec28160 (media-meta, close, forgetUser) and the F-SYS-007/F-SYS-009 fix commit 3926bd6f; give both one more checker round.
  2. Open checker notes not fixed (judged low value or another lane's): FCM delta nudge not wired (N-038); AMO/TSO have no foreground delta trigger; a background delta does not reload SrDay (sr-a); `targets`/`achievement_mtd` delta sections ignored (deferred, docs/27); templates replaced by `kind` only; `ReferenceRepository.apply` clears `calendar_changes` even when it keeps a newer delta config; `retry_exhausted` rows read SERVER_HAS_FEWER; `DpcBootReceiver.reapply` may run once before the calendar loads.
  3. Then `my-rows.py android-core --todo` in day order (F-SYS-052, F-SYS-072, N-038, AUD-*).
- **Traps found this session:**
  15. A fresh container has no Android SDK: run `tools/android-sdk.sh` and write `sdk.dir=/opt/android-sdk` to `local.properties` (git-ignored).
  16. Push only to `lane/android-core` (lead #3, docs/26 s3); the integrator promotes to INT.
  17. `kotlinx.coroutines.async` called by its full name inside `runBlocking` resolves to the deprecated top-level function: import it.
  18. The generated DTOs are strict: hand-written bundle fixtures need every required member (Route `updated_at`, `version`).

## Handover (READY TO RECYCLE, 2026-10-07 ~06:55Z, second session)
- **Done this session:**
  - F-SYS-008, F-SYS-006, F-SYS-049, F-SYS-011, F-SYS-046, F-SYS-027, F-SYS-092.
  - Room v3 for android-sr-a (tasks, outlet requests), android-sr-b (core records) and android-print (RoomPrintLedger plus follow-ups).
  - AUD-PERF-05, key half.
  - Each row had an independent Opus checker (two or three rounds for most). Every confirmed defect is a test in a `Checker*Test`.
- **In progress:** nothing.
- **Next three** (lead's order):
  1. Delete the local DTO stubs and use `com.aktcl.aron.contract.*` (shared-2 asked; see docs/status/shared.md for differences). Stubs to delete:
     - core-network `AuthDtos.kt`;
     - core-network `BundleDtos.kt`, except `BundleHead`;
     - core-database `record/RecordPayloads.kt` (records: keep the explicit-null encoding rules, see trap 3);
     - core-database `reference/BundleReference.kt`.
     A drift test guards the shared file; never edit it by hand.
  2. F-SYS-009 device-versus-server reconciliation. `server_totals` are already stored per date in `sync.server_totals.<date>`; `device_money` is not yet sent with `day_submit`.
  3. F-SYS-007 bundle delta refresh (`GET /v1/sync/delta`). `BundleDownloader` already handles the full bundle and pages.
  - After those: the AUD rows (PERF-05 session restore off the main thread, TP-4 real process-kill test, PERF-06, PERF-04, TP-5) and AUD-DG-03 (release signingConfig from `ANDROID_SIGNING_*`, coordinate with infra through docs/requests). Then `--todo` in day order.
- **Traps found this session:**
  10. Checker subagents and your own Gradle runs collide on the same module's `test-results`. Give each checker other modules, or a git worktree outside the repo (`/tmp/...`, never `.claude/worktrees`).
  11. shared:contract DTO changes break `core-*` without warning. After every INT merge, compile the apps and run `:shared:contract:jvmTest` before pushing.
  12. Robolectric tests that need another SDK jar (core-ui uses sdk 34) fail locally with 429; the lead says CI is the judge.
  13. Kotlin nests block comments: a `/*` inside a KDoc opens one.
  14. A JUnit `@Before fun x() = runBlocking { ... }` that returns a value breaks the runner; declare `: Unit`.

## Second re-check (independent agent, on the pushed fixes)
- Found a release-build regression: the https guard broke the configuration cache. Fixed; `assembleRelease` now builds (10.7 MB unsigned with R8) and an http base URL fails. **Ask to infra:** add `:android:app-sr:assembleRelease` to CI so this cannot regress silently.
- Three session edge cases (rollback without an unlock attempt, reboot plus clock forward, upload grant on password change), two memo checks, and the scanner's misses and false positives: all fixed, every case is a test.
- Scanner false-positive budget: realistic Day-2 code (Room `@Query`, log tags, Timber, MIME types, date patterns, time zones, exceptions, previews, `@Named`, headers, work names, routes, JSON keys) passes. Other lanes opt a line out only with `// i18n-ignore: <reason>`.

## Awaiting a device or the CI emulator job (prepared, compiled, not run here)
- `core-session` `DeviceCryptoTest`: Keystore cipher round trip and tamper rejection; native Argon2id parameters and time on the phone.
- `core-database` `SqlCipherDeviceTest`: the per-user file is encrypted and reopens with its key only.
- Manual on the Galaxy A06:
  - install the three APKs side by side;
  - log in against the Azure API once it is up;
  - print from each app;
  - time the seeded day load with SQLCipher (the host figure is not a phone figure).

## Not done / next (Day 2 rows)
- Sync worker loop, batching and acks (F-SYS-008); full bundle apply into Room (F-SYS-006); the trusted clock (F-SYS-049).
  - The session and the device proof use `WallClock.System` until F-SYS-049 replaces it.
- App wiring of the user database and its Keystore-wrapped SQLCipher passphrase lands with F-SYS-006, the first screen that reads Room.
- No `memo_counter` table yet: that is F-SYS-027.
- Bundle apply has no monotonic version or date guard yet (F-SYS-006).
- Quarantine resolutions are not applied yet (F-SYS-008).

## Interfaces for feature lanes (stable, 2026-10-07)

Use these; do not reach into Room, OkHttp or the token store directly (docs/24 s5.1). Local wire DTOs are marked
`REQUEST:` and move to `shared:contract` when the shared lane lands them, with the same names.

**core-database** (`com.aktcl.aron.core.database`): one Room database per user. In app code inject `UserDatabases` (Hilt, app module) and call `userDatabases.of(userId)` (suspend; opens once, encrypted). In Robolectric tests use `AronDatabase.open(context, userId, null)` or an in-memory builder.
- `CaptureRepository(db)`: commits a capture plus its outbox records in ONE transaction. A duplicate client UUID throws `SQLiteConstraintException`. A malformed capture throws `IllegalArgumentException` or `IllegalStateException` before any write. Methods: `recordAttendance(event, fix)`, `recordStock(movements)`, `recordVisitOpen(visit, fix)`, `recordSale(SaleCapture(memo, lines, discounts, qcLines, editFix))`, `recordVisitClose(close)`.
  ```kotlin
  val visitUuid = ClientIds.newUuid()
  val fix = GeoFixEntity(clientUuid = ClientIds.newUuid(), ownerClientUuid = visitUuid, purpose = "visit_open", /* fix fields */)
  captureRepository.recordVisitOpen(VisitEntity(clientUuid = visitUuid, meta = captureMeta, fixClientUuid = fix.clientUuid, /* ... */), fix)
  syncScheduler.requestSync(userId)               // then ask for an upload; never wait for it
  ```
- Capture rules enforced:
  - every `*Entity.clientUuid` is a lower-case UUID v4 (`ClientIds.newUuid()`);
  - every visit-family row has `meta.routeId`;
  - QC lines saved with a memo carry that memo's uuid and `appliedToMemo = true`;
  - an edited memo carries `supersedesClientUuid`, `editReasonCode` and `editFix` together;
  - a memo's outlet is its visit's outlet.
- `ReferenceRepository(db)`: `apply(BundleReference)` replaces routes, outlets and SKUs in one transaction. Reads: `routesOfDay(businessDate): List<RouteDay(route, outlets)>` (planned route first, outlets in visit order), `skus()`, `bundleVersion()`.
- DAOs for reads: `db.captureDao()` (`visitsOn`, `memosOn`, `linesOf`, `attendanceOn`, `stockOn`, `stockBalanceOn`, `memo`, `visit`, `fix`), `db.referenceDao()`, `db.outboxDao()`.
- `ReferenceRepository` (F-SYS-006) also reads: `businessDate()`, `priceOn(skuId, priceType, businessDate)`, `config(key, nowIso)` (JSON text, scheduled values on trusted time), `section(name)` (raw JSON of `user`, `code_lists`, `calendar`, `templates`, `reason_texts`, `offers`, `tasks`, `supervisor`, `programmes`, `content`, ... and `route.<id>` for open memos, plan, targets, day state). `apply` returns `ApplyResult` and never rolls back to an older snapshot.
- Outbox API (sync engine only):
  - `nextPending(limit)`, `markInFlight(batchUuid, seqs)`, `inFlight(batchUuid)`, `inFlightBatches()`
  - `applyAck(clientUuid, state, code, serverId, at)`: the state is one of `OutboxState.ACKED`, `REJECTED`, `QUARANTINED` or `PENDING`; anything else throws.
  - `returnToPending(batchUuid, lastCode)`, `committedCounts(businessDate)`, `purgeAckedBefore(iso)`
- Adding a record type later (redemption, gift_photo, ...) means a new entity, a `RecordMapping` function and a Room migration plus migration test. The outbox is generic over `record_type`.

**core-sync** (`com.aktcl.aron.core.sync`):
- `SyncScheduler.requestSync(userId, trigger = SyncTrigger.WRITE_DEBOUNCE)`. Call it after every commit; it only schedules.
- Use `SyncTrigger.DAY_SUBMIT` for Sales Submit, `CHECKOUT` after check-out and `MANUAL` for the Sync button.
- `SyncScheduler.None` is for previews and tests. Batch build and ack handling stay inside core-sync (F-SYS-008); feature code never calls them.
- `SyncEngine(userId, db, SyncBatchApi(client, signer), SessionUploadAuth(session), deviceUuid, appVersion, clock).run(trigger): SyncReport` and `BundleDownloader(db, syncApi, stagingDir, clock).download()` are for the scheduler (F-SYS-011) and the login flow; feature code does not call them.

**core-network** (`com.aktcl.aron.core.network`):
- `AronApiClient.call(path, CallAuth.Grant(Grant.FULL), build = { get() }, decode = { body, meta -> ... }): ApiResult<T>`.
- `ApiResult` is one of `Success(value, meta)`, `NotModified`, `Failure(httpStatus, problem)` or `Transport(OFFLINE|TIMEOUT|EDGE_RESPONSE|MALFORMED)`. Branch on `problem.problemCode` only.
- An expired token is refreshed once automatically.
- Existing APIs: `AuthApi` (login, refresh, logout) and `SyncApi` (`bundle(forDate, ifNoneMatch)`, `healthy()`).
- A feature lane adding an endpoint writes a small `XxxApi(client)` the same way, in its own module.
  ```kotlin
  when (val r = syncApi.bundle()) { is ApiResult.Success -> r.value.head; is ApiResult.Transport -> offline(); else -> Unit }
  ```

**Day bundle** (core-sync): inject `BundleDownloaders`; at login and at day start call `bundleDownloaders.of(userId).download()` (`APPLIED`, `UNCHANGED`, `PREFETCH_PROMOTED` mean the day is ready offline; `OFFLINE` keeps the previous bundle). Evening prefetch: `download("<tomorrow>")`. Read everything through `ReferenceRepository(db)`.

**Trusted time** (core-session): `SessionComponents.clock` (a `WallClock`) and `SessionComponents.trustedClock` (`businessDate()`, `isAtOrAfterDhaka(LocalTime.of(17, 0))`, `clockOffsetMs()` for `clock_offset_ms`, `bootCountNow()`, `recentAnchors()`). Never use `System.currentTimeMillis()` for a business date or a gate.

**core-session** (`com.aktcl.aron.core.session`):
- Injected `SessionComponents` gives `session`, `syncApi` and `apiClient`.
- `session.state: StateFlow<SessionState>` is either `LoggedOut` or `Active(user, mode, reauthRequired, updateRequired)`.
- `session.login(username, password): LoginOutcome` and `session.logout()`.
- Selling needs only `Active`, never a live token.

**core-common**:
- `ClientIds.newUuid()`
- `LocaleDigits.localize(text, language)` and `formatInteger(n, language)`
- `AppLanguage`
- `WallClock`: implemented by the trusted clock (F-SYS-049); inject `SessionComponents.clock`.
- Business date: `com.aktcl.aron.rules.BusinessDate.of(epochMs)` from shared:rules.
- Money formatting per the lead's ruling, `1,234.50 ৳`: use the shared `Formats` when the shared lane publishes it.

**core-ui** (`com.aktcl.aron.core.ui`):
- `AronTheme(language) { ... }`: bundled Bengali/Latin fonts.
- `LocalAppLanguage`, `localizedDigits(text)` (dates and numbers only, never identifiers), `localizedNumber(n)`.
- `LanguageToggle(current, onSelect)`, `AppLocale.wrap/set/current`.
- Every string goes in `res/values` + `res/values-bn` of your module. The build fails otherwise: `HardcodedStringScanTest`. Opt a line out only with `// i18n-ignore: <reason>`.
- The component kit (tiles, stepper, press-and-hold, dialogs, empty/error/offline states) is N-023, my next UI row.

## Traps found (Day 2 session)
7. A checker subagent running Gradle on the same module at the same time breaks `test-results` (`in-progress-results-generic.bin`). Wait for it or run other modules.
8. `markInFlight` no longer increments `attempts`; use `countFailure` for a definitive failure.
9. N-023 (UI kit) moved to the android-core-ui sublane.

## Handover of the first session (2026-10-07, kept for its traps 1-6)
- **Done:** N-001, F-SYS-033, F-SYS-044 (device part pending), F-SYS-018, N-016. All checker findings are fixed, and a second re-check is fixed too. The interfaces above are published, and the `SyncScheduler` interface is in core-sync.
- **In progress:** nothing.
- **Next three rows** (`python3 tools/my-rows.py android-core --todo`):
  1. F-SYS-008, idempotent batch upload (T1, L). Build on `OutboxDao`: persisted `batch_uuid`, gzip, `X-Device-Proof` batch string (`ProofStrings.batch`), ack mapping `accepted`/`duplicate` to `OutboxState.ACKED`, retryable reject to `PENDING`, bisect on 500.
  2. F-SYS-006, bundle download into Room (T1, M). Extend `BundleReference`; add monotonic version and date guards; wire `AronDatabase.open` with a Keystore-wrapped SQLCipher passphrase in the app shells.
  3. F-SYS-049, trusted clock (T1, S). Use `TrustedClock` from shared:rules plus `ApiResponseListener`, and replace `WallClock.System` in `SessionComponents`.
  Then F-SYS-011, F-SYS-046, N-023, F-SYS-023, F-SYS-027.
- **Traps found:**
  1. The configuration cache is on with `problems=fail`. Never capture script values in task actions; copy them to a local first.
  2. Maven Central answers 429. Use the mirror init script from docs/24-build-spec-verification.md s9 (machine-local).
  3. The record payload encoder must keep `encodeDefaults=false, explicitNulls=true`. DTO members without a default are required and are written as null; optional members need a default.
  4. Never let a checker's worktree under `.claude/worktrees/` be committed (`git add -A` will pick it up). Remove it with `git worktree remove --force`.
  5. Robolectric Compose tests: text below the fold needs `assertExists`, not `assertIsDisplayed`.
  6. `HardcodedStringScanner` judges a literal by its enclosing call. A new developer-only call needs adding to `devCalls`, never a blanket exemption.

## Lead review (2026-10-06)
- AC-01 to AC-12 accepted by the lead. Ownership: android-sr takes feature-auth and feature-home from Day 2; android-core keeps core-*, the three app shells and the build wiring.
- Scope change (docs/27): targets, loyalty/Astha and offer programmes are deferred. Day-1 code already complies: the bundle parsers accept empty `targets` and ignore `offers` (ignoreUnknownKeys, `RouteSnapshot.targets` defaults to empty), no programme UI exists, and the memo keeps its `memo_discount` component (offer discount zero until an engine exists).

## Decisions taken (android-core). DECISIONS.md is read-only for lanes (docs/26 s2), so they are recorded here.
- **AC-01:** before enrolment exists, a dev phone mints and keeps a local device_uuid. Enrolment (dpc lane) replaces it with `DeviceIdentity.setEnrolledUuid`. Reason: login needs a device_uuid on Day 1.
- **AC-02:** the offline verifier and the profile live in an encrypted file per username hash, not in the user's Room database. Reason: the user id is unknown until the username is matched. docs/24 s8.1 says "in the user's encrypted database"; the protection is equivalent (Keystore AES-GCM).
- **AC-03:** the offline cool-down starts at 60 s, doubles per failure after the 10th, and is capped at 1 h. It runs on elapsedRealtime, falling back to the wall clock only after a reboot. docs/24 gives "doubling" but no base or cap.
- **AC-04:** a date earlier than the last online login by more than 10 minutes refuses offline unlock (CLOCK_INCONSISTENT). Offline age is measured from the highest wall time seen. Reason: reps tamper with clocks; prod phones have auto-time enforced.
- **AC-05:** login falls back to offline unlock on transport failure, an edge page, 429, 5xx and 426 (the session is flagged update-required; docs/24 s3.7 lets an open offline day finish). It never falls back on 401 or 403.
- **AC-06:** only `ERR_AUTH_REFRESH_INVALID`, `ERR_AUTH_REFRESH_REUSED`, `ERR_PASSWORD_CHANGED` (and `ERR_AUTH_USER_DISABLED` for the full grant) end a refresh family. Device-proof or suspension errors keep the tokens.
- **AC-07:** `outbox.payload_sha256` is SHA-256 of the stored `payload_json` bytes, a local integrity check. The server's ingest hash is its own RFC 8785 computation (docs/24 s3.3).
- **AC-08:** the Day-1 Room schema replaces the skeleton's version 1 in place, with no migration. Nothing was ever installed that opened the old database: the skeleton activity never touched Room.
- **AC-09:** Bangla mode renders in Noto Sans Bengali, which also covers Basic Latin. English mode renders in subset Noto Sans.
- **AC-10:** the language preference lives in SharedPreferences, not DataStore. Reason: it must be read synchronously in `attachBaseContext`.
- **AC-11:** WorkManager's `SystemJobService` and `DiagnosticsReceiver` and profileinstaller's receiver are exported by those libraries behind system-only permissions. They are allowlisted by name. This deviates from the literal list in docs/24 s5.8.
- **AC-13:** below Android 12 (minSdk 26) an expedited job would run as a foreground service, which docs/24 s5.4 forbids outside printing; there the after-failure and Sales Submit jobs are plain network-constrained jobs. The Galaxy A06 (Android 14) gets expedited jobs.
- **AC-15:** due_collection and day_submit follow the docs/24 s4.2 table (own family, rank 0), not the routed request's "visit family"; day_submit stays the route-day's last record because the route-day refuses captures after it.
- **AC-16:** a memo number is reserved in its own transaction before the sale is written (the row's "a failed save burns a number"), where docs/24 s7.5 says "the same transaction"; a number is therefore never handed out twice even if a failed save was printed.
- **AC-17:** the resume config check is capped at 24 requests per business date; docs/24 names a daily cap but the s9.5 registry has no key for it.
- **AC-14:** the save debounce is "5 s after the first save of a burst" (`ExistingWorkPolicy.KEEP`), not "5 s of quiet": REPLACE would cancel a running upload on every save.
- **AC-18:** the lenient `Problem` reader stays local (the contract `Problem` requires `request_id` etc.; a strict parse would turn an edge or older server's error into MALFORMED instead of its code).
- **AC-19:** `device_status` goes only as an outbox record from the sync worker (D24-53), not also by `POST /v1/devices/me/status`; the report rides the batch that the worker is about to send.
- **AC-20:** device-wide integrity evidence rides whichever user's run comes first on a shared phone (the report describes the phone, not the user).
- **AC-12:** ownership. Per the lead's Day-1 notes, android-core owns the three app shells' build wiring. The Day-1 login screen in feature-auth and the home placeholder in feature-home were built here because N-001 needs them and android-sr had no Day-1 rows. android-sr takes them over from Day 2 (F-SR-001, F-SR-008).

## Requests filed
- `docs/requests/android-core-contract-dtos.md`: host the auth, bundle and record DTOs in `shared:contract`. Local mirrors are marked `REQUEST:` and checked member by member against the YAML.

## Environment
- Maven Central answered 429. I used the mirror init script of docs/24-build-spec-verification.md s9 in `~/.gradle/init.d` (not committed).
