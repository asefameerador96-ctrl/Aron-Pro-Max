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
