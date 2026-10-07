# Status: lane android-sr-a (2026-10-07)

## Working mode
Local Gradle cannot resolve (Maven Central 429, no mirror by lead's ruling). Code is pushed to `lane/android-sr-a`; CI there is the build and test. Nothing is merged to INT until CI is green for that commit. Logic and tests come first (pure Kotlin over core-database and shared:rules); Compose screens follow when the N-023 kit slices land.

## Interface for android-sr-b: visit and outlet state (agreed here, in `feature-outlet`)
Package `com.aktcl.aron.feature.outlet`:
- `VisitSession.current: StateFlow<OpenVisit?>`: the committed visit of the call in progress. **Sale, review, QC and dues screens read this and never open a visit.** `OpenVisit(visitUuid, outletId, routeId, geoVerdict, geoAction, geoValidated, photoValidated, forceReasonCode, openedAtIso)`. `geoValidated` is true only for an `in_range` verdict; a force sale has `photoValidated = true`, `geoValidated = false`. Call `VisitSession.close()` when the visit ends (your visit_close commit).
- `VisitFlow(fixes, metaProvider, committer, session, settings, ...)`: `open(outlet)`, `refresh()`, `forceSale(reasonCode, photoUuid)`; state in `VisitFlow.state: StateFlow<VisitUiState>` (`Idle`, `ReadingFix`, `NeedsDecision`, `Open`, `Blocked`).
- The visit row is committed (one transaction with its fix and outbox record, via `VisitCommitter` = `CaptureRepository.recordVisitOpen`) when the verdict is final: at once when in range; after Force Sale otherwise; or as `blocked` under mock policy `block_sale`. Refresh re-reads and re-evaluates in memory.
- Ports android-geo-dpc implements: `LocationFixSource.readFix(purpose): FixReading` (one balanced-power fix; failure is a non-ok `FixReading`, never an exception; carries the mock flag and device-state facts).
- Stock (F-SR-014): `StockLoad` in `feature-stock`; the day's loaded total per SKU comes from `captureDao().stockBalanceOn(businessDate)`; sr-b's sale stock column subtracts sales from that.
- Picker (F-SR-016/074): `OutletPicker.rows(...)` over `OutletEntity`.

## Done (2026-10-07, 164 unit and Robolectric tests green locally in my modules; app-sr assemble and lint green)
Logic plus screens, Bangla and English, offline, wired in `app-sr` (`SrApp`, `SrDay`, `SrDayHolder`):
- Home header, tiles, task badge, stale/expired/missing bundle banners, device-health line, Settings (language, logout with confirmation)
- Attendance check-in and press-and-hold check-out; Stock; Sale picker (chips, distance sort ready), visit open and geo check, Refresh GPS, locked/blocked states; Tasks with swipe-resolve (Room); Outlet menu (4 tiles) and request forms (new, close, info, cluster, add to route; Room `recordOutletRequest`); OTP model and screen; permissions onboarding; route picker; tutorial list screen; outlet card model.
- Force Sale: controller, screen and shell wiring are done; it **cannot complete a photo step until F-SYS-030/010 (camera and media queue) land** (`NoCameraPipeline` placeholder in `SrDay`).
- T1 checker (Opus) on F-SR-017/019: 6 defects fixed. T2 checkers (Sonnet): batch logic round and screens round (7 defects: double tap crash, hook failure, tick, restore, permissions, wiring, refresh) all fixed.

## Not done / waiting
- F-SR-001 login screen is the Day-1 one plus the day-start bundle download in the background (`SrDay.downloadBundle`); the "first bundle with progress" screen and the single version source need a design pass.
- F-SR-004 update prompt, F-SR-006 PDA to Support: need F-SYS-020/021.
- F-SR-018 photo, F-SR-037/039 photo steps: need F-SYS-030/010.
- F-SR-020/021: need F-SR-060 (sr-b). F-SR-048 tutorials: screen done, data needs F-API-027 and a cache table. N-041 Google Maps on tap: not started.
- F-SR-015 stock-slip print hook: waits for the android-print wiring checklist.
- `DeviceOwnerPolicy.configure(trustedClock...)` and `reapply()` belong in the Application (android-core); `ConfigCheck` implementation with android-core.
- Hand-offs: sr-b plugs in through `SrApp(onOtherTile)` and the open `VisitSession`; the placeholder visit end (`SrDay.closeVisitAbandoned`) is theirs to replace.

## Later today
- Printing wired for the stock slip (PrinterManager singleton, MemoPrinting on RoomPrintLedger, recover at day start, Print after Save, slip warning from Room). Open with android-core: item 15 (slip flag on every row of one Save).
- N-041 built (option A, ruling R19): OutletMapActivity started only on tap with outlet pin, radius circle and phone position; text distances always shown; release APK 11.2 MB unsigned (gate 30 MB). Needs the CI mapsApiKey (the same secret) and the owner adding the SR package to the key restriction; the tile view itself is DEVICE-PENDING.
- Owner design rule (docs/32 s2a, outdoor-first): screens use the kit's components and tokens only; numbers, status and actions sit on solid cards, glass only for chrome. When `ContentCard` lands in the kit, swap it in for the plain Columns on Stock, Attendance and Tasks; no hard-coded colours exist in my modules today.

## Camera wired (2026-10-07, after android-sys core-media)
`SrDay.media` (MediaComponents with the upload scheduler), `CameraCaptureOverlay` at the top of SrApp, the photo pipeline replaces the no-camera placeholder, photos are claimed (`attach`) just before the visit (force sale) or the outlet request is committed, `media.resume()` at start. A force-sale photo that its location request also cites stays owned by the visit. Photo steps (F-SR-018, F-SR-037, F-SR-039) are now complete in code; the camera itself is DEVICE-PENDING (D-S2).

## HANDOFF (2026-10-07 ~08:50, lane recycled at the lead's request)
**State:** everything is on `lane/android-sr-a` (head after this commit). Local Gradle works (mirror; `tools/android-sdk.sh` once per container). My modules: feature-outlet, feature-stock, feature-attendance, feature-tasks, feature-home, feature-auth, and the SR shell in `app-sr` (`SrApp`, `SrDay`, `SrDayHolder`, `SrModule`, `OutletMapActivity`, `PermissionState`). Push ONLY to `lane/android-sr-a` after merging INT (integrator promotes it).

**Done (logic, screens, shell wiring, tests green locally):** Home, Attendance, Stock (+ printer, Print after Save), Sale picker, visit geo check, Refresh, Force Sale (reason + photo + location request), Tasks (Room), Outlet menu and request forms (Room), Settings (language, logout confirm), OTP, permission onboarding + core-system gate, route picker, tutorial list screen, Maps on tap (N-041, R19), camera and photo claim before commit, no polling (resume + real boundaries), ConfigCheck launched not awaited.

**NEXT THREE (in this order):**
1. **Stock print confirmation (blocking for F-SR-015 and the owner's check D-P2a):** replace `AronConfirmDialog` in `SrApp.StockHost` with core-printing's `PrintAttemptDialogs` (on `lane/android-print` b6d07b54, reaches INT through the train; read docs/requests/android-print-wiring-gaps.md "Status 2026-10-07 08:50"): a tap outside or Back must NOT answer "No". Also: `lastSaved` lives only in screen memory, so offer Print for today's unprinted Saves from Room (the stock rows with slip_printed = false) and run print and confirm in a scope that outlives the screen (SrDay-level scope).
2. **Host the sale and memo screens (D-P2b):** `SrApp` has no SALE/MEMO/SUMMARY destinations. android-sr-b's ViewModels plug in through `SrApp(onOtherTile)` and the open `VisitSession`; replace the placeholder visit end (`SrDay.closeVisitAbandoned`, "Close visit" button on the VISIT screen) with their flow; add `MemoPrintingReprinter(day.printing)`.
3. **Kit follow-ups:** android-core-ui asked to drop `columns = 4` from `AronTileGrid` in `feature-home/HomeScreen.kt` and `feature-outlet/OutletMenu.kt` (default null = 3 columns at 360 dp, 2 at font scale 1.5+); do it once the new kit is on INT (today `columns` is still a required parameter in `Kit.kt`), then tell android-core-ui so they re-record the 3 Home goldens. Swap in `ContentCard` for plain Columns on Stock, Attendance, Tasks.

**Then:** wiring items from docs/requests/android-sys-app-wiring.md that live in app-sr (updater F-SR-004, PDA to Support F-SR-006, Wi-Fi-only row in Settings, `LogoutFlow` F-SR-007 wiring); read `reprint_max`, `confirm_after_print`, `disconnect_idle_s` from config when ConfigCheck has them; F-SR-001 first-bundle progress screen and single version source; F-SR-020/021 (need F-SR-060 from sr-b); F-SR-048 data (needs F-API-027 and a cache table).

**Traps:** (a) INT is green-only now; a red CI on my head is mine. (b) `core-system` and `core-media` need `consumer-rules.pro`; I committed android-sys's versions (identical on both sides). (c) Compose tests: a button below the fold in a scrolling Column needs `performScrollTo()` before `performClick()`. (d) Robolectric downloads need the mirror; `HardcodedStringScanTest` flags prose literals, strings go in `values` and `values-bn`; an apostrophe in a string resource must be escaped (`\'`). (e) A photo can belong to one record only (`media.attach`); the force-sale photo stays with the visit even when its location request cites it. (f) `DeviceOwnerPolicy.configure(trustedClock...)` and the `ConfigCheck` implementation belong to android-core (Application). (g) Rulings: R8 (OPEN visit row, no outbox until verdict final; `OpenVisitStore` still has no Room table: docs/requests/android-sr-a-open-visit-row.md), R9, R19.
**Open requests:** `docs/requests/android-sr-a-open-visit-row.md` (open_visit table), map key in CI (`mapsApiKey`, lead asked infra), D-S1/D-S2/D-P2 device checks.
