# Status: lane android-core (Day 2)

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

## In progress
- **F-SYS-011** constrained background sync: `WorkManagerSyncScheduler`, `SyncWorker`, `AronWorkerFactory` (app `Configuration.Provider`, default initializer removed), `NoPollingLintTest`. Built and green; checker next.

## Next (in this order, per the lead): F-SYS-046, then one Room v3 migration for the routed requests (docs/requests/android-sr-a-task-tables.md, android-sr-a-outlet-request-capture.md, android-sr-b-core-records.md) together with F-SYS-027 (memo counter), then F-SYS-009, F-SYS-007, the AUD rows.

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

## Handover (READY TO RECYCLE, 2026-10-07, previous session)
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
- **AC-14:** the save debounce is "5 s after the first save of a burst" (`ExistingWorkPolicy.KEEP`), not "5 s of quiet": REPLACE would cancel a running upload on every save.
- **AC-12:** ownership. Per the lead's Day-1 notes, android-core owns the three app shells' build wiring. The Day-1 login screen in feature-auth and the home placeholder in feature-home were built here because N-001 needs them and android-sr had no Day-1 rows. android-sr takes them over from Day 2 (F-SR-001, F-SR-008).

## Requests filed
- `docs/requests/android-core-contract-dtos.md`: host the auth, bundle and record DTOs in `shared:contract`. Local mirrors are marked `REQUEST:` and checked member by member against the YAML.

## Environment
- Maven Central answered 429. I used the mirror init script of docs/24-build-spec-verification.md s9 in `~/.gradle/init.d` (not committed).
