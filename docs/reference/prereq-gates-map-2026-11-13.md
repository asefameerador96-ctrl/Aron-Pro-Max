# Prerequisite gates: what the apps and server do today, and the gaps (2026-11-13)

Map made at integration commit 4e0645556 for D-OWNER-PREREQ-01 (check-in gate, stock gate, check-out submits sales). Read-only analysis by four readers; each gap was then re-read by a verifier (36 of 36 claimed gaps held; the refuted ones are not marked here, so re-check a gap against the code before building on it). Nothing here is built yet.


## stock gate

### Today

Read at origin/claude/wonderful-thompson-k6ejnf = 4e0645556. Read-only, nothing changed.

HOW THE PHONE KNOWS ITS STOCK
- There is no stored stock counter. The balance is recomputed on every read from Room: `current = issued + adjusted - qcReturned - sold`, all in BASE units (sticks/pieces/dozens) (android/feature-stock/src/main/kotlin/com/aktcl/aron/feature/stock/StockTracker.kt:23, 41-57). Inputs are `stock_movement` rows and the memo lines of live memos for ONE business date (StockTracker.kt:60-62; android/core-database/.../dao/CaptureDao.kt:130-135, 146-147).
- The issue is typed by the rep himself on the Stock screen, in field units (packet/box/dozen), stored with qty_entered + unit_entered + pack_factor + qty_base (StockLoad.kt:117-124; entity android/core-database/.../entity/CaptureEntities.kt:64-76). Unit comes from the SKU master only (StockLoad.kt:36-38). It is written to Room and the outbox in one transaction, no network (android/core-database/.../repo/CaptureRepository.kt:84-95; android/app-sr/.../SrDay.kt:260-261). I found no server-to-phone stock issue: core-sync mentions stock_movement only in an upload type list (android/core-sync/.../SyncEngine.kt:834). So "an issue that has not arrived from the server" does not exist today; stock is self-declared and immediately local.
- Kinds the tracker understands: issue, adjustment, qc_return (StockTracker.kt:50-52). `qcReturn()` is never called from main code and `correctTotal()` is called only by the AMO (android/app-amo/.../AmoRecordWriter.kt:211-216); the SR app has no return, damage, transfer or correct-total writer. Only `issue` is ever written by the SR (StockLoad.kt:121).
- Double tap / retry on stock Save is guarded by an in-flight signature and a 5-minute same-values guard persisted in SharedPreferences (StockLoad.kt:108-116, 150; SrDay.kt:771-773).

WHAT THE SALE SCREEN DOES WHEN QUANTITY EXCEEDS STOCK: A WARNING, NEVER A BLOCK
- Catalog gives each SKU `stockBase` = tracker current, or null when the SKU has no stock movement today (android/app-sr/.../RoomSaleReads.kt:38-47).
- Review sets `exceedsStock = base > stockBase`, false when stockBase is null (android/feature-sale/.../domain/SaleReview.kt:92); documented "a warning, never a block" (SaleReview.kt:22-23; StockTracker.kt:22).
- exceedsStock is not a ReviewProblem (SaleReview.kt:35-54), so `canCommit` stays true (SaleReview.kt:66) and the committer saves (SaleCommitter.kt:63-64). UI shows one red line under the card (android/feature-sale/.../ui/SaleScreens.kt:385-391); plus/typed entry is capped only at 999,999 (SaleScreens.kt:375-376, 423); Save is enabled on canCommit (SaleScreens.kt:509).
- Per unit: the comparison is in base units, entered quantity converted with the SKU's pack factor (SaleReview.kt:89), so 20s and 10s packets, dozens and box of 25 are each compared correctly per SKU; the stock figure is shown in entry units with a "+n" base remainder (SaleScreens.kt:405-408). Loose base units are refused unless a config flag is on (SaleReview.kt:86).

CASES
- SKU never loaded today: stockBase null, no warning at all, sale saves (RoomSaleReads.kt:45; SaleModels.kt:23-24).
- Carry-over from yesterday: none. Every read is `WHERE business_date = :businessDate` (CaptureDao.kt:146-147); yesterday's leftover is invisible, so the rep must re-enter it as today's issue.
- Negative stock: allowed and displayed (StockTracker.kt:22-23); only `toReturn` is floored at 0 (StockTracker.kt:26).
- Two memos in a row: each call re-reads the catalog from Room at `flow.start` (android/feature-sale/.../domain/SaleFlow.kt:55-57; android/app-sr/.../SrSaleHosts.kt:151), so memo 2 sees stock after memo 1.
- Stale snapshot inside one sale: the catalog is read once at start and reused for every review and for the commit (SaleFlow.kt:92, 104); stock is not re-read at commit.
- Edit of a memo: the new memo supersedes the old; only live memos count as sold (StockTracker.kt:36-39, 43), so after the save the delta is right. During the edit, though, the old memo is still live, so the stock figure already has its quantity subtracted while the draft is pre-filled with that same quantity (android/app-sr/.../SrSaleKit.kt:289-296). Example: issued 10, memo sold 10, open edit unchanged: stock 0, line 10, warning fires.
- Edit after stock changed: beginEdit calls flow.start, so it reads the then-current stock (SrSaleKit.kt:291).
- Void: I found no phone-side memo_void writer in any android main source (only type lists: RouteDayLock.kt:159, SyncEngine.kt:834). A server/admin void is "not known to the phone until the next bundle" (android/core-database/.../repo/Reconciliation.kt:78-79) and StockTracker has no voided filter, so stock does not come back on the phone.
- Server-refused memo: Reconciliation excludes refused rows (Reconciliation.kt:93-94) but StockTracker does not, so a refused memo still consumes phone stock.
- Double tap on sale Save: mutex plus remembered result plus fixed memo UUID, one memo only (SaleFlow.kt:88-96); kill-and-relaunch detects a landed commit (SaleFlow.kt:63-64).
- AMO: sells from its own stock through the same SaleFlow, catalog and warning (android/app-amo/.../RoomSaleReads.kt:34-44; AmoDay.kt:75), including after Force Sale / Manual Override (AmoDay.kt:260, 306), which is a geo override only. Same warn-only behaviour.
- TSO: no sale path; SaleFlow/SaleCommitter are used only by app-sr and app-amo.

SERVER
- No stock sufficiency check at ingest. The only stock-related ingest rule is pack_factor vs the SKU's base_per_pack (backend/sync/.../IngestService.kt:1050-1058; MemoChecks.kt:107). app.stock_movement is an append-only ledger with signed qty_base (db/migrations/V0007__field_transactions.sql:496-497).
- An over-stock sale is accepted and counted in full: sold comes from active memo lines (backend/analytics/.../Aggregator.kt:217-224), issued from kind='issue' only (Aggregator.kt:225-227), and the ds-rrs report's closing stock = issued - sold - free - returned would go negative (backend/analytics/.../ReportHandlersB.kt:170). The day digest reports issued and sold per SKU but does not compare them (IngestService.kt:1800-1811).

### Gaps claimed

1. **Over-stock is a warning only; it is not a ReviewProblem, so Save stays enabled and the committer writes the memo.**
   - Where: android/feature-sale/src/main/kotlin/com/aktcl/aron/feature/sale/domain/SaleReview.kt:22-23,35-54,66,92; SaleCommitter.kt:63-64; ui/SaleScreens.kt:385-391,509
   - Risk: Rule 2 is not enforced anywhere today, for SR or AMO.

2. **A SKU with no stock movement today has stockBase = null, which means no check and not even a warning.**
   - Where: android/app-sr/src/main/kotlin/com/aktcl/aron/sr/RoomSaleReads.kt:45; android/app-amo/src/main/kotlin/com/aktcl/aron/amo/RoomSaleReads.kt:41; SaleReview.kt:92
   - Risk: A rep who never opens Stock can sell unlimited; a hard block must treat 'no row' as zero.

3. **Stock is self-declared: the only source of an issue is the rep typing it on his own phone, with a soft ceiling that warns and allows.**
   - Where: android/feature-stock/src/main/kotlin/com/aktcl/aron/feature/stock/StockLoad.kt:63-64,104,117-124; android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrDay.kt:260-261
   - Risk: A hard block can be bypassed by typing a larger issue before the sale; the block only proves the rep declared the stock, not that he holds it.

4. **No carry-over: every stock and sales read is for one business date, so yesterday's unsold stock is invisible today.**
   - Where: android/core-database/src/main/kotlin/com/aktcl/aron/core/database/dao/CaptureDao.kt:130-135,146-154; StockTracker.kt:60-62
   - Risk: With a hard block, a rep carrying leftover stock is blocked until he re-enters it as a new issue, which then double-counts 'issued' on the server.

5. **The edit draft is compared against stock that already has the edited memo's own quantity subtracted (the old memo is still live until the edit commits).**
   - Where: android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrSaleKit.kt:289-296; StockTracker.kt:36-43; android/app-amo/src/main/kotlin/com/aktcl/aron/amo/AmoSaleKit.kt:226
   - Risk: A hard block as written would refuse an unchanged or reduced edit once stock is sold out; the check must add back the superseded memo's lines (delta).

6. **Stock is read once at flow.start and that snapshot is used at commit; the commit does not re-read stock inside its transaction.**
   - Where: android/feature-sale/src/main/kotlin/com/aktcl/aron/feature/sale/domain/SaleFlow.kt:55-57,88-96,104
   - Risk: A draft resumed after a kill restarts with fresh stock, but any stock change while a sale is open is not seen; the block would not be atomic with the memo insert.

7. **The per-line check ignores other draws on the same SKU in the same memo: DRP reward packs (memo_discount qty) and QC faulty sticks are not counted against stock.**
   - Where: SaleReview.kt:100-108 (DRP reward qty), SaleCommitter.kt:79-91; StockTracker.kt:43,52
   - Risk: Low while offers are deferred (docs/27), but QC returns never reduce stock at all.

8. **qcReturn() exists but is never called; SR has no return, damage, transfer or correct-total writer (only the AMO can correct its own total).**
   - Where: android/feature-stock/src/main/kotlin/com/aktcl/aron/feature/stock/StockAdjustments.kt:15-18,24-39; android/app-amo/src/main/kotlin/com/aktcl/aron/amo/AmoRecordWriter.kt:211-216
   - Risk: Under a hard block the SR has no way to fix a wrong count except adding more issue; he cannot reduce it.

9. **Voided and server-refused memos still count as sold in the phone's stock tracker; no phone-side memo void writer was found.**
   - Where: StockTracker.kt:36-43 (no voided/refused filter); android/core-database/src/main/kotlin/com/aktcl/aron/core/database/repo/Reconciliation.kt:78-79,93-94
   - Risk: After a void or refusal the rep's stock stays reduced and a hard block would wrongly stop a sale of goods he still holds.

10. **Negative stock is permitted and displayed.**
   - Where: StockTracker.kt:22-26
   - Risk: Existing days with negative balances stay negative; the block needs a rule for a balance already below zero.

11. **Server has no stock sufficiency check at ingest; an over-stock memo is accepted and fully counted.**
   - Where: backend/sync/src/main/kotlin/com/aktcl/aron/backend/sync/IngestService.kt:1050-1058,1800-1811; backend/sync/src/main/kotlin/com/aktcl/aron/backend/sync/MemoChecks.kt:107
   - Risk: A modified or old-version phone can still send over-stock sales; sold totals rise and closing stock goes negative (backend/analytics/src/main/kotlin/com/aktcl/aron/backend/analytics/ReportHandlersB.kt:170).

12. **Server aggregate counts only kind='issue' as issued and only 'return'/'qc_return' as returned; 'adjustment' movements are in neither, while the phone's balance includes them.**
   - Where: backend/analytics/src/main/kotlin/com/aktcl/aron/backend/analytics/Aggregator.kt:225-227 vs StockTracker.kt:50-52
   - Risk: Phone and server disagree on stock held after an AMO correction, so any server-side re-check would use a different number than the phone.

13. **Server stock aggregate is keyed by route_id; AMO stock movements outside a call carry no route.**
   - Where: Aggregator.kt:227; android/app-amo/src/main/kotlin/com/aktcl/aron/amo/AmoDay.kt:567
   - Risk: AMO sales on a route can show with no matching issued stock in route-level closing figures.


### Open questions

- Carry-over: should yesterday's unsold stock count as today's opening stock on the phone, or must the rep return everything and be issued fresh each day? Today there is no carry-over (CaptureDao.kt:146-147).

- Stock source: is the rep's own typed issue enough as the basis for the block, or must an issue be confirmed by someone else (depot/AMO/server) before it counts? Today it is self-declared and a larger issue can be typed at any time (StockLoad.kt:104,117-124).

- SKU with no stock entry today: confirm it is treated as zero stock (sale blocked), not as unknown (RoomSaleReads.kt:45).

- Should the server also refuse or only flag an over-stock memo at ingest? A refusal after an offline sale means a printed memo the server never books; a flag keeps the sale and marks it for review. No such check exists today (IngestService.kt:1050-1058).

- Does the hard block apply to the AMO's own sales (same flow, AmoDay.kt:75) as well as the SR's?

- Should QC faulty sticks and, later, free/reward packs reduce the stock the rep can sell? qcReturn() exists but is unused (StockAdjustments.kt:15-18).

- Does the SR need a way to lower a wrong stock count (correct total / return) once the block is hard? Only the AMO has it today (AmoRecordWriter.kt:211-216).

- Not determined: whether any bundle or day-state download puts stock or memo rows back on a reinstalled or replaced phone. I found no such path in core-sync (only an upload type list at SyncEngine.kt:834) but did not read the bundle applier in full. If none exists, a rep on a fresh install mid-day would show zero stock and be blocked.

- Not determined: bidi pack sizes (confirmation pending per standing rule 5); the check itself is unit-agnostic because it compares base units (SaleReview.kt:89-92).


## check-in gate

### Today

Read at origin/claude/wonderful-thompson-k6ejnf = 4e0645556. Paths are repo-relative.

SUMMARY: No check-in gate exists anywhere today, on the phone or the server. Check-in is a deliberate "prompt, never a block". Today's ruling reverses a recorded default (D-329), so it needs a DECISIONS entry and a real gate. The TSO app has no attendance at all.

1. WHERE ATTENDANCE STATE LIVES AND IS READ
- Durable store: Room table `attendance_event` (kind `check_in` / `check_out`, `business_date` from CaptureMeta), android/core-database/.../entity/CaptureEntities.kt:52-61. The database is per user (`databases.of(userId)`, android/app-sr/.../MainActivity.kt:163).
- Write: `CaptureRepository.recordAttendance` puts the fix, the event and the outbox row in one transaction (android/core-database/.../repo/CaptureRepository.kt:55-62). The client UUID is made in AttendanceFlow.kt:140. It is offline by construction, and a failed fix still commits (AttendanceFlow.kt:139-146; DECISIONS.md:383).
- Read: `CaptureDao.attendanceOn(businessDate)` (android/core-database/.../dao/CaptureDao.kt:140-141), via `SrDay.attendanceToday()` (android/app-sr/.../SrDay.kt:619) and `AmoDay.attendanceToday()` (android/app-amo/.../AmoDay.kt:212).
- In-memory copy: `AttendanceFlow.state` (android/feature-attendance/.../AttendanceFlow.kt:83-84), filled by `restore()` (87-91).
  - SR calls restore once per composition of SrApp (android/app-sr/.../SrApp.kt:269).
  - AMO calls it once when AmoDay is built (android/app-amo/.../MainActivity.kt:192).
- The only consumers of "checked in" are:
  - the Home dock/hero state, display only (android/feature-home/.../HomeModel.kt:108-128; android/app-sr/.../SrHomeState.kt:99-101);
  - the check-out button (AttendanceFlow.kt:126, 200);
  - the app-update day gate `dayOpen()` (SrDay.kt:622);
  - the DPC app-blocking and breadcrumb hooks that fire on commit (SrDay.kt:378-386; AmoDay.kt:196-203).
- Two more check-in-derived stores exist outside Room: the DPC BlockingState (android/dpc/.../DeviceOwnerPolicy.kt:80-87) and the breadcrumb owner/window (android/core-sync/.../device/DeviceRuntime.kt:136-141).

2. IS THERE A GATE? NO
- AttendanceFlow.kt:25 says it outright: "The check-in message is a prompt, never a block (Q-UI-04, cfg.day.require_checkin_before_sale off)".
- The registry has `cfg.day.checkin_gate` enum(off, soft, hard), default "soft" (db/migrations/V0006__cfg_key_registry.sql:64; docs/19-admin-config.md:504). No Android or backend code reads it: grep for `checkin_gate` finds only that SQL line and DECISIONS.md:383. DayConfig reads other cfg.day keys but not this one (android/core-sync/.../device/DayConfig.kt:63-84).
- DECISIONS.md:383 (D-329, MUST-CONFIRM): "a missed check-in does not block selling".
- SR Home tiles open their screens unconditionally (SrApp.kt:374-391). `HomeDayState.NOT_CHECKED_IN` only picks the dock label (HomeModel.kt:111, 122).
- CaptureRepository's day checks cover Sales Submit and Final Submit only (`requireOpenRouteDay` at CaptureRepository.kt:101, 136, 162, 179, 220, 251, 263, 282, 312, 348; `DayRefusal` has only FINAL_SUBMIT and SALES_SUBMIT, android/core-database/.../repo/RouteDayLock.kt:280-283). `recordStock` (CaptureRepository.kt:84) has only the Final Submit part (203).

3. REACHABLE WITHOUT A CHECK-IN TODAY
SR (all of SrScreen, SrApp.kt:127):
- Stock (SrApp.kt:377, 448; write at SrDay.kt:261).
- Sale picker, visit open, Force Sale, No Sale, Skip (SrApp.kt:378, 449-505, 517-537; SrDay.kt:409, 824; SrSaleKit.kt:306).
- Memo, memo edit, and Mark Paid via the memo screens (SrApp.kt:507-510; due write at CaptureRepository.kt:209-220).
- Summary and its print (511), Sales Submit (512; also from Settings, 441).
- Tasks resolve (544-546; android/feature-tasks/.../RoomTaskStore.kt:29).
- Outlet menu, requests, clusters (548-571; SrDay.kt:666, 694).
- Tutorial and survey writes (SrDay.kt:514, 557).
- Journey, KPI, Settings, Support, Printer diagnostics (513-514, 418-447).
- Relaunch path: `restoreOpenVisit()` (SrApp.kt:268) puts the rep back into an open visit, and the Sale tile goes straight to VISIT when one is open (378).
- Notification path: a task push opens TASKS (SrApp.kt:283-291; MainActivity.kt:65-77, 129).
- Manifest: only the launcher intent, no deep links (android/app-sr/src/main/AndroidManifest.xml as shown at the ref).

AMO (AmoScreen, android/feature-amo/.../AmoApp.kt:34-39; hosts mapped in android/app-amo/.../MainActivity.kt:225-253, no condition):
- Stock, Sale, Memo, Summary (247-250), Sales Submit (225).
- Control Call, Joint Call (251-252).
- Outlet hub (237), Team hub including Task Delegation and SR Stock (240-245), Report (238).
- Writes: AmoDay.kt:272, 410, 560; AmoRecordWriter.kt:38, 78, 117, 127, 147, 165, 180, 203, 216; AmoSaleKit.kt:242.
- No push-intent handling found in AMO MainActivity.

Background paths: sync workers, bundle download and push pulls run regardless of check-in (SrApp.kt:271; AMO MainActivity.kt:219-223). Breadcrumbs already need a check-in (android/core-geo/.../Breadcrumbs.kt:42).

4. MIDNIGHT, CHECK-OUT, SECOND PHONE, KILL-AND-RELAUNCH
- Kill and relaunch: safe. State is rebuilt from Room (SrApp.kt:269; AMO MainActivity.kt:192; AttendanceFlow.kt:86-91).
- Across Dhaka midnight with the app left open: the in-memory state goes stale.
  - SR boundary code calls only `attendance.tick()` (SrApp.kt:274-279), which re-uses the previous `checkedInAt` (AttendanceFlow.kt:94). ON_RESUME calls `refreshSubmit()`, not `restore()` (SrApp.kt:292-294). So yesterday's check-in still reads as checked in until SrApp is recomposed from scratch.
  - SrDay sits in a ViewModel (android/app-sr/.../SrDayHolder.kt:7-9), so it also survives a recreate.
  - AMO has no boundary tick and no re-restore at all (the only restore is MainActivity.kt:192).
  - A gate built on `AttendanceFlow.state` would therefore let a rep sell on a new business date without checking in. A gate must read Room by the capture's own `business_date`.
  - The SR boundary timer is also hard-coded to 17:00 (SrDay.kt:147-153), not the configured check-out time.
- After check-out: nothing closes. Every screen and write stays open; only Sales Submit or Final Submit close captures (RouteDayLock.kt:32-38). Check-out itself needs the Sales Submit first (AttendanceFlow.kt:129-132; SrDay.kt:394-395).
- Second phone: attendance is local only. The bundle's RouteDayStateDto has no checked-in field for the SR (backend/sync/.../BundleDtos.kt:232-243). SupervisorDayStateDto has `checked_in` (backend/sync/.../DayApi.kt:51), but I found no Android reader of it. So a second phone shows "not checked in". A second check-in there hits the server unique index `attendance_event_once (user_id, business_date, kind)` (db/migrations/V0007__field_transactions.sql:399) and returns `attendance_duplicate` (backend/sync/.../RecordWriter.kt:148-150).

5. AMO AND TSO
- AMO has attendance: the same AttendanceFlow (AmoDay.kt:191-209) and screen (android/feature-amo/.../AmoAttendance.kt:19-41).
- TSO has none. TsoScreen has no attendance destination (android/feature-tso/.../TsoNav.kt:14-21). app-tso and feature-tso do not depend on feature-attendance; only app-sr, app-amo and feature-amo do (android/app-sr/build.gradle.kts:106, android/app-amo/build.gradle.kts:105, android/feature-amo/build.gradle.kts:19). The server would accept a TSO check-in as a supervisor_day (backend/sync/.../DayStates.kt:31, 47-49, 117-118).

6. SERVER
- It neither refuses nor flags a sale, visit or stock row on a day with no check-in.
- In IngestService the only attendance rules are check-out too early (916-920, accepted and flagged), CHECKOUT_WITHOUT_SUBMIT (720-756) and the device gate (1001-1010, GATED_TYPES at 1950).
- RiskSignalCode has no no-check-in code (contract/slices/schemas/RiskSignalCode.yaml:10-31).
- A first `visit` moves the route-day to in_field with no check-in (DayStates.kt:93-95).
- "Successful Login" on dashboards does require a check-in, reporting only (backend/analytics/.../DashboardSummaries.kt:44).

7. ENTRY POINTS TO GATE (SR and AMO)
- SR tiles: Stock, Sale (PICKER, VISIT, FORCE, NO_SALE, SKIP), Memo, EDIT, Mark Paid, Sales Submit, Outlet requests and clusters, Tasks resolve.
- SR resume and notification paths: resume-into-open-visit (SrApp.kt:268, 378) and the task push (283-291).
- AMO: Stock, Sale, Memo, Sales Submit, Control Call, Joint Call, Outlet hub writes, Task Delegation, SR Stock correction.
- Write layer: `recordStock`, `recordVisitOpen`, `recordSale`, `recordNumberedSale`, `recordVisitClose`, `recordDueCollection`, `recordVisitSkip`, `recordAmo`, `recordOutletRequest(s)`, `recordClusterCreate`, `recordDaySubmit` (CaptureRepository.kt:84-387). A third `DayRefusal` beside the existing two is the natural single choke point, read from `attendance_event` by `meta.businessDate`.

8. ENTRY POINTS THAT MUST STAY OPEN
- Login, enrolment, OTP bind (android/app-sr/.../MainActivity.kt:145-157).
- Location notice, permission screens, route picker (MainActivity.kt:176-183; SrApp.kt:340-352).
- First-bundle download and all sync (SrApp.kt:271, 355-368).
- The Attendance screen itself (SrApp.kt:405-416; AMO MainActivity.kt:253-267).
- Settings, Support, Printer diagnostics (SrApp.kt:418-447; AmoApp.kt:101-107).
- The app update host (MainActivity.kt:183).
- Read-only views: Summary, Journey, KPI, Tutorial, AMO Report, Team Location, Team Performance, Live Dashboard.
- Reprints via the print ledger, `recordAttendance`, `recordBreadcrumb`, `recordDeviceStatus`, `recordMediaMeta`.

### Gaps claimed

1. **No check-in gate exists in the SR app: every tile opens its screen and every write commits without a check-in.**
   - Where: android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrApp.kt:374-391; android/feature-attendance/src/main/kotlin/com/aktcl/aron/feature/attendance/AttendanceFlow.kt:25; android/feature-home/src/main/kotlin/com/aktcl/aron/feature/home/HomeModel.kt:111,122
   - Risk: Directly contradicts rule 1. A tile-only gate would be bypassed by the resume-into-open-visit path (SrApp.kt:268, 378) and the task notification path (SrApp.kt:283-291).

2. **No check-in gate exists in the AMO app: all host screens are mapped unconditionally.**
   - Where: android/app-amo/src/main/kotlin/com/aktcl/aron/amo/MainActivity.kt:225-253; android/feature-amo/src/main/kotlin/com/aktcl/aron/feature/amo/AmoApp.kt:96-108
   - Risk: An AMO can do stock, own sales, control and joint calls, outlet verification and task delegation with no attendance.

3. **The TSO app has no attendance feature at all (no screen, no AttendanceFlow, no module dependency).**
   - Where: android/feature-tso/src/main/kotlin/com/aktcl/aron/feature/tso/TsoNav.kt:14-21; android/app-sr/build.gradle.kts:106 and android/app-amo/build.gradle.kts:105 are the only app modules depending on feature-attendance
   - Risk: Rule 1 cannot apply to the TSO without first building TSO check-in. The server side already accepts it (backend/sync/src/main/kotlin/com/aktcl/aron/backend/sync/DayStates.kt:31,47-49).

4. **The write layer has no check-in refusal: CaptureRepository checks only Sales Submit and Final Submit, and DayRefusal has only those two values.**
   - Where: android/core-database/src/main/kotlin/com/aktcl/aron/core/database/repo/CaptureRepository.kt:84,101,136,162,179,203,220,251,263,348,457; android/core-database/src/main/kotlin/com/aktcl/aron/core/database/repo/RouteDayLock.kt:32-61,280-283
   - Risk: A UI-only gate can be bypassed by any path that reaches a commit (restored visit, process death mid-sale, a future screen). The gate belongs in the same transaction as the write, reading attendance_event by the capture's business_date.

5. **The registry key cfg.day.checkin_gate (off/soft/hard, default soft) is read by no code, and DECISIONS D-329 records 'a missed check-in does not block selling'.**
   - Where: db/migrations/V0006__cfg_key_registry.sql:64; docs/19-admin-config.md:504; DECISIONS.md:383; android/core-sync/src/main/kotlin/com/aktcl/aron/core/sync/device/DayConfig.kt:63-84
   - Risk: The owner ruling needs a new DECISIONS entry superseding D-329, and a decision on whether the gate is hard-coded or driven by this key. Changing a default means a new forward-only migration, never an edit of V0006. If config-driven, a phone with no bundle yet needs a safe default.

6. **In-memory attendance state goes stale across Dhaka midnight. The SR boundary only calls tick(), which re-uses yesterday's checkedInAt; ON_RESUME never calls restore(). The AMO has no boundary handling at all.**
   - Where: android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrApp.kt:274-279,292-294; android/feature-attendance/src/main/kotlin/com/aktcl/aron/feature/attendance/AttendanceFlow.kt:94; android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrDayHolder.kt:7-9; android/app-amo/src/main/kotlin/com/aktcl/aron/amo/MainActivity.kt:192
   - Risk: A gate built on AttendanceFlow.state would let a rep who leaves the app open overnight sell on the new business date without checking in, and Home would still show yesterday's check-in. The gate must read Room per business_date, and the flow must restore() at the boundary and on resume.

7. **Nothing closes after check-out: all operational screens and writes stay open (only Sales Submit or Final Submit lock captures).**
   - Where: android/core-database/src/main/kotlin/com/aktcl/aron/core/database/repo/RouteDayLock.kt:32-38; android/feature-attendance/src/main/kotlin/com/aktcl/aron/feature/attendance/AttendanceFlow.kt:124-134
   - Risk: Whether 'not checked in' includes 'already checked out' is undecided. Today check-out needs a Sales Submit first, which already locks sales; rule 3 (auto-submit at check-out) will change that ordering.

8. **A second phone cannot know the user already checked in: attendance is local Room only and the SR bundle's RouteDayStateDto carries no checked-in field.**
   - Where: backend/sync/src/main/kotlin/com/aktcl/aron/backend/sync/BundleDtos.kt:232-243; backend/sync/src/main/kotlin/com/aktcl/aron/backend/sync/DayApi.kt:51; db/migrations/V0007__field_transactions.sql:399; backend/sync/src/main/kotlin/com/aktcl/aron/backend/sync/RecordWriter.kt:148-150
   - Risk: With a hard gate, a rep on a replacement phone must check in again to work; that second check-in is refused by the server as attendance_duplicate while the sales made after it are accepted. The local gate should treat the local check-in as sufficient, and the duplicate outcome must not be shown as an error or re-block the rep.

9. **The server neither refuses nor flags a visit, memo, stock movement or due collection on a user-day with no check-in; a first visit even moves the route-day to in_field.**
   - Where: backend/sync/src/main/kotlin/com/aktcl/aron/backend/sync/IngestService.kt:916-920,1001-1010,1950; backend/sync/src/main/kotlin/com/aktcl/aron/backend/sync/DayStates.kt:93-95; contract/slices/schemas/RiskSignalCode.yaml:10-31
   - Risk: No server-side evidence when a tampered or old app version sells without a check-in. A hard server refusal would be unsafe for offline-first (sales can upload before or in a different batch from the check-in). A post-batch risk signal like CHECKOUT_WITHOUT_SUBMIT (IngestService.kt:720-756) is the existing pattern; it needs a contract code and a database constraint migration.

10. **The SR boundary timer is hard-coded to 17:00 instead of cfg.day.checkout_earliest_time.**
   - Where: android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrDay.kt:147-153 versus SrDay.kt:371
   - Risk: Minor: with a non-default check-out time the Home state does not refresh at the configured minute until a resume.


### Open questions

- Does rule 1 apply to the TSO? The TSO app has no check-in today (android/feature-tso/.../TsoNav.kt:14-21), so applying it means building TSO attendance first.

- After check-out, should operational screens be blocked again for the rest of the business date, or does only 'never checked in' block?

- Which screens count as 'operational' beyond stock, sale, collection and visit? Candidates: Sales Submit, outlet requests and clusters, task resolve, tutorial/survey answers, and for the AMO control/joint calls, outlet verification, task delegation and SR stock correction.

- Should the gate be unconditional, or driven by the existing cfg.day.checkin_gate key (set to hard)? If config-driven, what applies on a phone with no bundle yet?

- Should the server raise a risk signal for sales on a user-day with no check-in (it cannot safely refuse them offline-first), or is the phone gate alone enough?

- Not determined: what the phone does with an attendance_duplicate outcome (no Android handler found by grep).

- Not determined: whether AMO or TSO logout wipes the local database and so loses the day's check-in (the SR keeps its data, android/app-sr/.../MainActivity.kt:195).

- Not determined: whether device binding already prevents a second phone the same day.

- Not determined: behaviour when the trusted clock is unavailable at check-in.

- Not read: the full AMO hub internals (AmoOutletHub, AmoTeamHub, ControlCallHost) beyond their write calls, and whether any test asserts the prompt-not-block behaviour and would need to change.


## check-out and submit

### Today

Read at origin/claude/wonderful-thompson-k6ejnf = 4e0645556 (git show / git grep only; nothing changed). Paths are repo-relative; A = android, B = backend/sync/src/main/kotlin/com/aktcl/aron/backend/sync.

1. CHECK-OUT TODAY (SR and AMO share one flow)
- Gates, in order, at the press: checked in, not already checked out, not busy (A/feature-attendance/.../AttendanceFlow.kt:126); trusted Dhaka time >= cfg.day.checkout_earliest_time, default 17:00, recomputed at the press (:127, default :74; SR wiring A/app-sr/.../SrDay.kt:371,390; AMO A/app-amo/.../AmoDay.kt:206); then "Sales Submit first": the local day_submit must stand, re-read at the press, a failed read counts as not submitted (:129-132, :102-108). Button state is the same rule (:200); the screen text is att_submit_first / att_submit_first_offline (A/feature-attendance/src/main/res/values/strings.xml:34-36).
- SR gate source: RoomDaySource.alreadySubmitted(today) OR the zone's Final Submit lock (SrDay.kt:395). AMO: its supervisor_day submit (AmoDay.kt:209,359).
- What it writes: one geo fix + one attendance_event(kind=check_out) + one outbox row in ONE Room transaction (CaptureRepository.recordAttendance, A/core-database/.../repo/CaptureRepository.kt:55-62). It does not pass through the route-day lock, so it is accepted after a day_submit. No network in the path; the fix is one on-demand reading (AttendanceFlow.kt:139). After the commit, outside the transaction and each in runCatching: DPC onCheckOutCommitted (releases attendance app-blocking; A/dpc/.../DeviceOwnerPolicy.kt:83-87), breadcrumb window end, requestSync(CHECKOUT) (SrDay.kt:376-386).
- Kill and relaunch: restore() rebuilds state from today's stored attendance rows (AttendanceFlow.kt:88-92; SrApp.kt:269).
- Nothing on the phone stops captures after check-out; only a standing day_submit (or a locking Final Submit) does (RouteDayLock, A/core-database/.../repo/RouteDayLock.kt:32-38).

2. SALES SUBMIT TODAY
- Preconditions (SalesSubmitRules.gate, A/feature-dayclose/.../SalesSubmit.kt:134-145): BLOCKS = already submitted; any outbox row pending or in_flight; any per-type count where the server count is unknown or below the device count (CountRow.matches is false when the server count is null, :19-21). NOT blocking = rejected and quarantined rows (notes carried into the record, :138-142; a retry_exhausted row is state rejected, A/core-sync/.../SyncRules.kt:37,61) and outstanding dues (warning only, :144).
- The pending/in_flight/rejected/quarantined counts are over the WHOLE outbox, every date (RoomDayAdapters.kt:16-28).
- Consequence: Sales Submit cannot be made offline today. It needs a server answer (server_totals or the digest) covering every device row. The class comment "Submit queues offline" (SalesSubmitViewModel.kt:63) only describes the day_submit row itself waiting in the outbox.
- NOT checked before submit: an open visit (no visit_close), an open memo edit/draft, a pending or failed print. I found no such check in feature-dayclose (grep for visit_close/draft/edit in its main sources returned nothing). After the submit a still-open call is ended on the phone with the submit text (SrDay.kt:225-250, SrSaleHosts.kt:70-71).
- What it writes (RoomDaySubmitWriter.queue, RoomDayAdapters.kt:155-171 -> CaptureRepository.recordDaySubmit :337-351, one Room transaction: day_submit row + outbox row): new client UUID, scope route_day (SR) or supervisor_day with route null (AMO, AmoSaleKit.kt:300), submit_cycle = max local cycle + 1, device_counts = outbox rows of the date per record type (OutboxDao.kt:149-150), device_money (MoneyTotalsJson :116-141: active memo count, gross, discounts, QC, net, paid, due, due collected, net by category, issued and sold qty in BASE units per SKU), rejected_count, quarantined_count, pending_count HARD-CODED 0 (:166), dues fields, stock_slip_printed. Then requestSync(DAY_SUBMIT) (SalesSubmitViewModel.kt:101-103).
- Local guards: one submit per (date, route, scope, cycle) (CaptureRepository.kt:345-347); refused when the route-day is already closed (:348).
- Effect on the phone: visit, memo, visit_close, visit_skip, due_collection, amo records, survey answers, content views of that route-day throw RouteDaySubmittedException (CaptureRepository.kt:101,136,162,220,251,263,282,312). An AMO supervisor_day submit closes every route record of the date too (RouteDayLock.kt:36-37).

3. SERVER
- day_submit is stored in app.route_day_event keyed by client_uuid UNIQUE (TypeRules.kt:20; db/migrations/V0007__field_transactions.sql:402-447). There is no unique key on (route, date, scope, cycle).
- SR: route_day gets submit_received_at, rows_awaited, settle_deadline_at (now + cfg.day.submit_settle_timeout_min, 30), submit_cycle (B/DayStates.kt:96-107). State submit_pending_rows -> sales_submitted when nothing is parked and the registry holds at least the device counts per type, or at the deadline with submit_count_mismatch = true (:150-169); the worker settles expired ones without a batch (B/RouteDayPlanning.kt:102-119).
- AMO: supervisor_day.sales_submitted_at set at once, no settle (DayStates.kt:113-125).
- Check-out: attendance_event, one per user/date/kind (unique index attendance_event_once, V0007:399; a second with another UUID is refused attendance_duplicate, B/RecordWriter.kt:148-150). Before 17:00 it is accepted and flagged checkout_too_early (B/IngestService.kt:912-919). For an SR it changes no route_day column (DayStates.kt:95 sets nothing for check_out); for AMO it sets supervisor_day.checked_out_at (:118).
- A check-out accepted while the user has no effective day_submit for the date raises risk signal CHECKOUT_WITHOUT_SUBMIT, judged after the whole batch so either order within one batch is fine (IngestService.kt:719-762).
- The server does NOT refuse a sale that arrives after a Sales Submit: RecordOutcomeCode has no such code (contract/slices/schemas/RecordOutcomeCode.yaml) and IngestService reads submit state only to report it (:1816). The Sales Submit lock is phone-only.
- Submit void is not built: DayApi.kt:158 lists "Submit void, reopen, cover and exceptions" as dropped rows; no code sets route_day.submit_voided (only the column, V0007:315,340, and table submit_void_event, V0007:2075). On the phone CaptureRepository.reopenRouteDay (:438) has no caller in any main source set.

4. DOES ANYTHING CLOSE THE DAY WHEN THE REP NEVER CHECKS OUT OR SUBMITS?
- No per-rep close exists. The only server close is the zone-level Final Submit: by the TSO (POST /v1/day/final-submit, DayApi.kt:173,412) or by the midnight job AutoFinalSubmitJob, which is OFF by default (cfg.day.auto_final_submit_enabled = false, db/migrations/V0134__tso_manual_config.sql:42; AutoFinalSubmitJob.kt:40,91-92). Neither writes a day_submit or a check-out for the rep; a route-day closed that way has final_submitted_at with sales_submitted_at still null, and the preview shows it "not uploaded" (DayApi.kt:359).
- Late rows: a figure row stored after final_submitted_at increments route_day.late_rows_after_final and final_submit.late_rows; day_submit and attendance are explicitly not late rows (DayStates.kt:73-91). Under the shipped policy accept_and_flag rows are stored and counted late; under quarantine/reject they are held after_final_submit or refused day_final_submitted (IngestService.kt:1220-1249).

5. AMO / TSO ANALOGUES
- AMO: same AttendanceFlow and same Sales Submit view model, scope supervisor_day (AmoDay.kt:192-210, AmoSaleKit.kt:288-300). Rule 3 applies to it one-for-one.
- TSO: Final Submit is online-only by design, no queue (A/feature-tso/.../finalsubmit/FinalSubmit.kt:167-168); client UUID derived from user:zone:date:submit_seq so a retry replays (:178-185); not-uploaded routes warn and never block (DayApi.kt:207). The TSO app has no attendance flow at all: AttendanceFlow is constructed only in SrDay.kt:373 and AmoDay.kt:192, and a grep for attendance/check-in in app-tso and feature-tso main sources returned nothing. So there is no TSO check-out to hang an automatic Final Submit on.

6. WHAT "CHECK-OUT SUBMITS AUTOMATICALLY" REQUIRES (derived from the above; nothing is built)
- Order: day_submit first, then check_out, with day_submit taking the lower outbox seq (upload is ORDER BY seq, OutboxDao.kt:27), so the server never sees a check-out without its submit.
- One local transaction: day_submit row + its outbox row + fix + attendance row + its outbox row in a single Room withTransaction. Today they are two separate transactions (CaptureRepository.kt:55 and :337).
- Idempotent: if a submit already stands for the date/route, write only the check-out. If none, write exactly one, cycle = max + 1; the existing per-cycle check (:345) plus the press mutex (AttendanceFlow.kt:120) prevent a second.
- Offline: must work with no network, which today's submit gate forbids. The auto-submit must skip the "all acked + counts match server" gate and record truthfully what was not yet sent (pending_count is currently always 0).
- Pending / rejected / retry_exhausted: pending rows must not block check-out. The server already copes: it waits for the counts, then settles at the 30-minute deadline with submit_count_mismatch = true. Rejected, quarantined and retry_exhausted rows already do not block.
- Kill between the two writes: impossible if it is one transaction. If kept as two, a relaunch must find "submitted, not checked out", which today's gate already handles (check-out simply becomes enabled).
- 5 PM rule: unchanged; the auto-submit happens only inside a check-out that passed the 17:00 gate. Note the manual Sales Submit has no time gate today, so an early manual submit still locks the day before 17:00.
- Never checks out: nothing closes that rep's day (section 4).
- Late counters and reopen: an auto day_submit and the check-out are state records, so they do not move late-row counters even after a Final Submit. There is no way to undo an auto-submit made by mistake, because submit void is not built and the phone's reopen has no caller.

COULD NOT DETERMINE: whether a worker is deployed and running settleExpired/AutoFinalSubmitJob in the dev environment (code only); what docs/24 s4.2/s4.9 say beyond the code comments (I read code, not the docs); behaviour of the Sales Submit and Attendance screens beyond the state they are passed (SalesSubmitScreen.kt and AttendanceScreen.kt bodies not read in full).

### Gaps claimed

1. **Check-out is hard-blocked until a Sales Submit exists; it never writes one. Rule 3 needs the opposite: check-out writes the day_submit itself when none stands.**
   - Where: android/feature-attendance/src/main/kotlin/com/aktcl/aron/feature/attendance/AttendanceFlow.kt:129-133,200; android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrDay.kt:395; android/app-amo/src/main/kotlin/com/aktcl/aron/amo/AmoDay.kt:209
   - Risk: A rep who forgets Sales Submit cannot check out at all today; offline he is stuck until the network returns (string att_submit_first_offline).

2. **Sales Submit cannot be made offline: the gate needs zero pending/in-flight rows in the whole outbox and a server count per type that is known and not below the device count.**
   - Where: android/feature-dayclose/src/main/kotlin/com/aktcl/aron/feature/dayclose/SalesSubmit.kt:19-21,134-145; RoomDayAdapters.kt:16-28
   - Risk: Reusing this gate for the auto-submit puts the network in the path of check-out, breaking offline-first. The auto path needs its own rule that allows pending rows.

3. **day_submit and check-out are two separate Room transactions; there is no combined write.**
   - Where: android/core-database/src/main/kotlin/com/aktcl/aron/core/database/repo/CaptureRepository.kt:55-62 and 337-351
   - Risk: A kill between them leaves submit-without-check-out (recoverable) or, if ordered wrongly, check-out-without-submit, which the server flags as a risk signal against the rep.

4. **pending_count in the day_submit is hard-coded 0, and device_counts includes every outbox row of the date whatever its state.**
   - Where: android/feature-dayclose/src/main/kotlin/com/aktcl/aron/feature/dayclose/RoomDayAdapters.kt:158,166; android/core-database/src/main/kotlin/com/aktcl/aron/core/database/dao/OutboxDao.kt:149-150
   - Risk: An auto-submit made with unsent rows would tell the server nothing was pending. The server then waits up to 30 minutes and marks submit_count_mismatch if the rows have not arrived.

5. **No pre-submit check for an open visit, an open memo edit or an unprinted memo. A submit closes the route-day and the open call is then ended on the phone with a refusal text.**
   - Where: android/feature-dayclose/src/main/kotlin/com/aktcl/aron/feature/dayclose/SalesSubmit.kt:134-145 (no such block); android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrDay.kt:225-250
   - Risk: An automatic submit at check-out while a call or edit is open silently discards that work in progress; needs a decided rule (block check-out, or close the call first).

6. **Nothing closes an individual rep's day on the server when he never submits and never checks out. The only automatic close is the zone-level midnight Final Submit, shipped OFF, and it writes no day_submit or check-out for the rep.**
   - Where: backend/sync/src/main/kotlin/com/aktcl/aron/backend/sync/AutoFinalSubmitJob.kt:40,91-92; db/migrations/V0134__tso_manual_config.sql:42; DayApi.kt:359
   - Risk: The owner's rule only fires on check-out. A rep who forgets both leaves the route-day in in_field/synced forever, shown 'not uploaded' to the TSO.

7. **The phone only looks at the business date of 'now'. A rep who opens the app the next morning has no path to submit or check out yesterday; yesterday's captures stay un-submitted.**
   - Where: android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrDay.kt:334-339,395 (businessDate() of the current time); AttendanceFlow.kt:88-92 (restore takes today's events only)
   - Risk: Forgotten days are never closed by the phone either, so the gap above has no client-side backstop.

8. **Submit void / reopen of a Sales Submit is not built on the server, and the phone's reopen has no caller.**
   - Where: backend/sync/src/main/kotlin/com/aktcl/aron/backend/sync/DayApi.kt:158; android/core-database/src/main/kotlin/com/aktcl/aron/core/database/repo/CaptureRepository.kt:438 (no caller in any main source set)
   - Risk: An automatic submit is irreversible: once written, the rep cannot sell, collect or edit for that date on that phone, and no admin action can undo it.

9. **The server does not enforce 'day_submit is the route-day's last record': a sale arriving after a Sales Submit is stored normally. The lock exists only on the phone that made the submit.**
   - Where: contract/slices/schemas/RecordOutcomeCode.yaml (no such code); backend/sync/src/main/kotlin/com/aktcl/aron/backend/sync/IngestService.kt:1816 (state only read back)
   - Risk: A second phone or a reinstall of the same login can keep selling after an auto-submit; the server totals then exceed the submit's device_counts with no flag.

10. **No server uniqueness on (route, date, scope, submit_cycle); only client_uuid is unique. Each phone computes the cycle from its own local rows.**
   - Where: db/migrations/V0007__field_transactions.sql:402-447; RoomDayAdapters.kt:157
   - Risk: Two installs of one login that both auto-submit produce two cycle-1 day_submits for the same route-day; settle reads the newest by id, so the counts used are arbitrary.

11. **A route-day day_submit needs a route (requireRoute) and the check-out meta may carry none; a rep working two routes in a day has one check-out but two route-days.**
   - Where: android/core-database/src/main/kotlin/com/aktcl/aron/core/database/repo/CaptureRepository.kt:341; AttendanceFlow.kt:142; backend/sync/.../DayStates.kt:28 (one row per route and date)
   - Risk: The auto-submit must decide which route-days to submit (every route with captures today, not just the selected one) or some stay open.

12. **The manual Sales Submit has no time gate, while check-out is gated at 17:00. The device lock after a submit is immediate.**
   - Where: android/feature-dayclose/src/main/kotlin/com/aktcl/aron/feature/dayclose/SalesSubmit.kt:134-145; AttendanceFlow.kt:127
   - Risk: Not changed by rule 3, but once check-out auto-submits, the separate Sales Submit tile becomes a way to lock the day early by accident; its role needs deciding.

13. **TSO has no check-out and Final Submit is online-only with no queue, so rule 3 has no TSO equivalent to attach to.**
   - Where: android/feature-tso/src/main/kotlin/com/aktcl/aron/feature/tso/finalsubmit/FinalSubmit.kt:167-168; AttendanceFlow constructed only in SrDay.kt:373 and AmoDay.kt:192
   - Risk: If the owner expects 'TSO check-out auto Final Submits', that is new scope: a TSO attendance flow plus an offline-queued final submit.


### Open questions

- When check-out happens with rows still unsent (offline or mid-upload), may it auto-submit anyway and let the server settle later (possible 'count mismatch' flag after 30 minutes), or must check-out wait for the upload? Offline-first implies submit anyway.

- If a call or a memo edit is open at check-out, should check-out be refused until it is closed, or should the auto-submit end it?

- A rep who never checks out: should the server close his day (a per-rep automatic submit at midnight), should the phone offer to close yesterday at next launch, or is the TSO's Final Submit enough? The midnight zone job exists but is off by default.

- Does the separate Sales Submit tile stay as an optional early close, or is it removed now that check-out submits? Today it can lock the day before 5 PM.

- An automatic submit cannot be undone (submit void is not built). Is an admin reopen of a rep's Sales Submit needed before this ships?

- For a rep on two routes in one day, should check-out submit every route-day that has captures?

- Does the rule apply to the AMO supervisor day the same way (code path is identical), and is anything expected for the TSO, who has no check-out in the app?


## critic (what the three maps missed)

### Today

Read-only at origin/claude/wonderful-thompson-k6ejnf = 4e0645556 (git show / git grep; nothing changed or run). The three mappers' findings stand; I did not re-verify them beyond the lines I needed. Below are only the things none of them covered, each checked against the code. Paths are repo-relative.

What the code does today, relevant to the new gaps:
- A capture's business date is always "now": SrDay.metaProvider sets businessDate = BusinessDate.of(clock.nowMs()) (android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrDay.kt:348-356), and the sale committer's metaSource ignores the draft's date (android/app-sr/.../SrSaleKit.kt:97-99). The stock figure for a sale is read for the draft's own date, fixed when the draft was created (android/feature-sale/.../domain/SaleFlow.kt:55-59).
- Check-out reads the Sales Submit for businessDate() of now (SrDay.kt:395) and writes the check-out with a now-dated meta (android/feature-attendance/.../AttendanceFlow.kt:142).
- A locally stored day_submit locks the route-day whatever its outbox state: RouteDayLock.routeOpen reads only the day_submit rows and the reopen meta key (android/core-database/.../repo/RouteDayLock.kt:63-67).
- Upload order is not strictly commit order: a row sent familySkipAfter times or more moves behind the others, and bisect-isolated families are left out (android/core-database/.../dao/OutboxDao.kt:30-37; android/core-sync/.../SyncEngine.kt:237).
- The server raises CHECKOUT_WITHOUT_SUBMIT when an accepted check-out's batch ends with no effective day_submit for that user-date (backend/sync/.../IngestService.kt:719-762). I found no code that withdraws the signal when the submit arrives later.
- Stock can still be saved after a Sales Submit (CaptureRepository.kt:82-91), and the stock Save refuses the same values within 5 minutes (android/feature-stock/.../StockLoad.kt:107-110, 150).
- SR and AMO logout always proceeds and keeps the data; only the TSO is refused with unsent rows (android/core-system/.../logout/LogoutFlow.kt:56-60).
- The web portal has a sale entry path of its own: the paper backfill (IngestService.ingestManual, IngestService.kt:226-270, roles at :2066) and the Data Entry page for Web Entry, QC and Final Submit (web/src/components/admin/config/data-entry-view.tsx:1).
- A check-in commits with a failed or mock fix (AttendanceFlow.kt:136-146; the mock flag is display only at :153).
- The attendance blocking engine suspends other apps from check-in until check-out or the hard end time (android/dpc/.../blocking/BlockingEngine.kt:66-70, 146-155); its state is per device, not per user.

Could not determine: which server codes can refuse a day_submit in practice (I confirmed only that the generic ingest steps apply to every type); whether the memo list ever offers an earlier day's memo for edit (the SR memo reads are for today only, android/feature-memo/.../ui/RoomMemoAdapters.kt:21); the value of policy.familySkipAfter; whether the server's date-window step refuses a day's rows uploaded several days late.

### Gaps claimed

1. **Check-out after Dhaka midnight submits and closes the wrong day. The auto-submit would take its date from 'now', not from the check-in it closes.**
   - Where: android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrDay.kt:348-356,395; android/feature-attendance/src/main/kotlin/com/aktcl/aron/feature/attendance/AttendanceFlow.kt:94,126,142; android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrApp.kt:274-279
   - Risk: A rep who left the app open and checks out at 00:10 still shows as checked in (stale state, already mapped). The check-out and the automatic day_submit would be dated D+1: an empty submit for a day with no check-in, while day D keeps its sales un-submitted and its check-in unclosed. The auto-submit must be keyed to the business date of the open check-in, and the check-out must carry that date too.

2. **The three rules would read three different dates for one sale that straddles midnight: the stock figure uses the draft's date, the memo is stamped with the date of 'now', and a check-in gate would read the memo's date.**
   - Where: android/feature-sale/src/main/kotlin/com/aktcl/aron/feature/sale/domain/SaleFlow.kt:55-59; android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrSaleKit.kt:97-99; android/feature-sale/src/main/kotlin/com/aktcl/aron/feature/sale/domain/SaleCommitter.kt:68; android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrDay.kt:630-637 (StockLoad cached in the ViewModel)
   - Risk: Only in a live process across midnight (a relaunch does not restore yesterday's call, SrDay.kt:312-315). The stock check would pass against yesterday's stock while the memo lands on a date with no check-in and no stock. Each gate must name which date it reads, and they must agree.

3. **A stored automatic day_submit locks the phone even if the server refuses or quarantines it. The lock never looks at the outbox state.**
   - Where: android/core-database/src/main/kotlin/com/aktcl/aron/core/database/repo/RouteDayLock.kt:63-67; android/core-database/src/main/kotlin/com/aktcl/aron/core/database/repo/CaptureRepository.kt:345-349; backend/sync/src/main/kotlin/com/aktcl/aron/backend/sync/DayApi.kt:158
   - Risk: Today the rep presses Submit and sees the result. With an automatic submit he never looks. If the row is refused, the phone is locked for the day, the server has no submit, and the check-out raises CHECKOUT_WITHOUT_SUBMIT against him. There is no reopen path (already mapped), so this is unrecoverable without support.

4. **Writing day_submit before check-out does not guarantee the server sees them in that order. A row that failed several sends is moved behind the others, and a bisect-isolated family is left out of the batch.**
   - Where: android/core-database/src/main/kotlin/com/aktcl/aron/core/database/dao/OutboxDao.kt:30-37; android/core-sync/src/main/kotlin/com/aktcl/aron/core/sync/SyncEngine.kt:237; backend/sync/src/main/kotlin/com/aktcl/aron/backend/sync/IngestService.kt:719-762
   - Risk: The check-out can be accepted in a batch that does not hold the submit. The signal is judged once per check-out batch and I found nothing that clears it later, so the rep is flagged for a submit the app made for him. Either tie the two records into one family, or have the server re-judge the signal when the submit arrives.

5. **Logout is an exit that skips rule 3. SR and AMO logout always goes ahead, with no check for an open check-in or un-submitted sales.**
   - Where: android/core-system/src/main/kotlin/com/aktcl/aron/core/system/logout/LogoutFlow.kt:56-60,65-72
   - Risk: On shared phones a rep can log out instead of checking out; the day is never submitted, and the next user logs in on the same device. The blocking engine's check-in state is per device, so the first rep's check-in also keeps the other apps suspended for the next user until the hard end time (android/dpc/src/main/kotlin/com/aktcl/aron/dpc/blocking/BlockingEngine.kt:84-92,146-155).

6. **The web portal can book sales with no check-in and no stock check: the paper backfill writes a visit, a memo and its lines for an SR, and the Data Entry page offers Web Entry and QC.**
   - Where: backend/sync/src/main/kotlin/com/aktcl/aron/backend/sync/IngestService.kt:226-270,2066-2067; web/src/components/admin/config/data-entry-view.tsx:1; web/src/app/admin/data-entry/page.tsx:13
   - Risk: If the server later flags 'sale without check-in' or 'sale above stock', every dead-phone backfill would be flagged, and a backfilled day has no stock movement at all. The rules need an explicit exemption or an explicit answer for this path.

7. **Stock can be saved after the automatic submit, so the submit's frozen issued and sold figures stop matching the ledger.**
   - Where: android/core-database/src/main/kotlin/com/aktcl/aron/core/database/repo/CaptureRepository.kt:82-91,198-201; android/feature-dayclose/src/main/kotlin/com/aktcl/aron/feature/dayclose/RoomDayAdapters.kt:116-141,158
   - Risk: After check-out the Stock screen still accepts an issue (only Final Submit refuses it). Rule 1 does not close it either unless 'checked out' counts as 'not checked in'. A rep could raise his issue after the day is closed to explain a shortfall.

8. **A write-layer check-in gate keyed on the record's date would also refuse records that must still go through: the visit_close of a call already open, the day_submit and check-out themselves, print events, task events and yesterday's late closes.**
   - Where: android/core-database/src/main/kotlin/com/aktcl/aron/core/database/repo/CaptureRepository.kt:158-165,337-351; android/feature-tasks/src/main/kotlin/com/aktcl/aron/feature/tasks/RoomTaskStore.kt:29-31; android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrDay.kt:229-234
   - Risk: The mappers listed what to gate and what stays open by screen, not by record type. A blanket third DayRefusal would leave an open call unclosable (server sees a visit with no close) and could block the auto-submit itself. The gate needs a per-type list, like FinalSubmitLock.LOCKED_TYPES.

9. **A check-in commits with a failed or mock GPS reading, so the gate proves a tap, not presence.**
   - Where: android/feature-attendance/src/main/kotlin/com/aktcl/aron/feature/attendance/AttendanceFlow.kt:136-153; android/core-database/src/main/kotlin/com/aktcl/aron/core/database/repo/CaptureRepository.kt:55-62
   - Risk: Under rule 1 the check-in becomes the key to the whole day. A rep can unlock stock and sales from anywhere with GPS off or a fake-GPS app; only the server sees the mock flag later. Decide whether a mock or failed fix still opens the day (offline-first argues for accept and flag).

10. **The stock Save refuses the same quantities within 5 minutes, which becomes a hard stop on a legitimate top-up once sales are blocked by stock.**
   - Where: android/feature-stock/src/main/kotlin/com/aktcl/aron/feature/stock/StockLoad.kt:107-110,150
   - Risk: Low. A rep who loads 10 packets, sells them and loads another 10 of the same SKU inside five minutes is refused and cannot sell. The guard was safe while over-stock was only a warning.

11. **Two-route day: the auto-submit and the stock gate use different keys. Stock is one balance per user and date; the submit and its lock are per route.**
   - Where: android/core-database/src/main/kotlin/com/aktcl/aron/core/database/dao/CaptureDao.kt:150-154; android/core-database/src/main/kotlin/com/aktcl/aron/core/database/repo/RouteDayLock.kt:63-79; android/feature-dayclose/src/main/kotlin/com/aktcl/aron/feature/dayclose/RoomDayAdapters.kt:116-141
   - Risk: The mapped multi-route gap covers which route-days to submit. Not covered: each route's day_submit would carry the whole date's issued and sold quantities, so the server cannot check sold against issued per route, and a manual submit of route A before check-out leaves route B selling from the same stock.


### Open questions

- After midnight, should a late check-out close the day of the open check-in (my reading of the ruling), or be refused and leave that day for a morning catch-up submit?

- If the automatic day_submit is refused or quarantined by the server, who clears the locked phone: an automatic local reopen, or support? Submit void is not built.

- Should logout be refused, or trigger the same automatic submit, while the rep is checked in with un-submitted sales?

- Do the web paper backfill and Web Entry stay exempt from the check-in and stock rules?

- Does a check-in with a failed or mock GPS reading open the day?

- Should stock entry close at check-out along with sales?

- Not determined: which server outcome codes can refuse a day_submit in practice, and the value of the skip-ahead threshold (policy.familySkipAfter).

- Not determined: whether a day's rows uploaded several days late pass the server's date-window step; if not, a forgotten day cannot be closed late at all.


## Verifier corrections

- Could not refute: the gap holds at commit 4e0645556. All paths below are under android/feature-sale/src/ unless stated otherwise.

**Evidence**
- **Over-stock is only a flag on the line.** `ReviewLine.exceedsStock` is documented "a warning, never a block (F-SR-023)" (main/kotlin/com/aktcl/aron/feature/sale/domain/SaleReview.kt:22-23) and is computed as `sku.stockBase?.let { base > it } ?: false` (SaleReview.kt:92).
- **It never becomes a problem.** The `ReviewProblem` sealed interface (SaleReview.kt:35-54) has no stock variant, and `canCommit` is `problems.isEmpty()` (SaleReview.kt:66).
- **The committer does not check stock.** `SaleCommitter.commit` gates only on `review.canCommit` (main/kotlin/com/aktcl/aron/feature/sale/domain/SaleCommitter.kt:63-64), then builds and records the memo (SaleCommitter.kt:71-103).
- **The UI only warns.** It shows red text under the card, with the comment "a warning, never a block" (main/kotlin/com/aktcl/aron/feature/sale/ui/SaleScreens.kt:385-391). The quantity is clamped to `MaxLineQty`, not to stock (SaleScreens.kt:370,375-376). Save is enabled by `review.canCommit` (SaleScreens.kt:509).
- **A test pins this behaviour.** `packBadgeAndStockWarningAreReadOnlyAndNeverBlock` enters 26 pieces against stock 25 and asserts both `exceedsStock` and `canCommit` are true (test/kotlin/com/aktcl/aron/feature/sale/domain/SaleReviewTest.kt:58-67).
- **The spec says the same.** docs/15-feature-inventory.md:1906 (G-feat-44) reads "Stock check warns and allows; movement flagged stock_negative."

**Two additions to the gap as stated**
- **Unknown stock is unguarded.** When `stockBase` is null there is not even a warning (SaleReview.kt:92). It is null when the SKU has no stock row (android/app-sr/src/main/kotlin/com/aktcl/aron/sr/RoomSaleReads.kt:45; same in android/app-amo/src/main/kotlin/com/aktcl/aron/amo/RoomSaleReads.kt:41), so a fix must decide how to treat a missing stock row.
- **The config key is not wired.** `cfg.sale.stock_check` is named in docs/15-feature-inventory.md:349, but a grep for `stock_check`, `stockCheck` and `stock_negative` in android/ and backend/src/main (non-test) returned nothing.

**Not determined**
- Whether `CaptureRepository.recordSale` / `recordNumberedSale` enforce a stock limit inside the Room transaction; I did not open them. The passing test above only exercises the review calculator.
- Whether the server rejects an over-stock memo on sync. My grep of backend/src/main/kotlin for stock combined with memo/sale/exceed/negative/reject returned nothing, but I did not read the sync handler.
- Whether edits of an earlier memo or DRP reward quantities should count against stock.

- Not refuted: the gap holds at commit 4e0645556, and it is slightly wider than stated.

Evidence (all at 4e0645556):
- android/app-sr/src/main/kotlin/com/aktcl/aron/sr/RoomSaleReads.kt:45 and android/app-amo/src/main/kotlin/com/aktcl/aron/amo/RoomSaleReads.kt:41 both set `stockBase = stock[s.skuId]?.takeIf { it.hasStockRow }?.current`. The KDoc on the line above each (SR :37, AMO :33) says "null when the SKU has no stock row".
- android/feature-stock/src/main/kotlin/com/aktcl/aron/feature/stock/StockTracker.kt:54 sets `hasStockRow = id in byKind`, i.e. true only when the SKU has a stock movement on that business date. StockTracker.kt:60-62 reads movements for that business date only, and :59 says SKUs with neither a movement nor a sale are absent from the map. So stockBase is null in two cases: no movement and no sale today (absent), and no movement but already sold today (present, hasStockRow false). The field comment at StockTracker.kt:19 says so directly: "the sale warning then has nothing to compare with".
- android/feature-sale/src/main/kotlin/com/aktcl/aron/feature/sale/domain/SaleReview.kt:92 computes `sku.stockBase?.let { base > it } ?: false`, so a null stockBase gives exceedsStock = false: no check.
- android/feature-sale/src/main/kotlin/com/aktcl/aron/feature/sale/ui/SaleScreens.kt:377 shows the stock figure as "–" for null, and :386 shows the red warning text only when exceedsStock is true. So there is no warning either.
- android/feature-sale/src/test/kotlin/com/aktcl/aron/feature/stock/StockTrackerTest.kt:48 (path: android/feature-stock/src/test/kotlin/com/aktcl/aron/feature/stock/StockTrackerTest.kt:48) asserts a SKU with hasStockRow false and current = -4, i.e. selling a never-loaded SKU is an expected, tested state.

Wider than the gap says: even when stockBase is not null, exceeding it never blocks. SaleReview.kt:66 has `canCommit = problems.isEmpty()`, and no ReviewProblem is raised for stock anywhere in review() (SaleReview.kt:78-134). The code comments state the intent: StockTracker.kt:22 "the sale review warns, never blocks" and SaleScreens.kt:385 "a warning, never a block". Tests pin it: SaleReviewTest.kt:58 `packBadgeAndStockWarningAreReadOnlyAndNeverBlock` and SaleScreensTest.kt:43 `entryShowsPackBadgeTotalAndStockWarningAndNeverBlocksReview`. The only reader of exceedsStock in android/ is SaleScreens.kt:386.

Could not determine:
- Whether the server rejects an over-stock memo on sync. A grep of backend/ for exceedsStock, overStock, insufficient-stock style terms found nothing relevant, but I did not read the backend ingest code line by line.
- Whether stock carried over from an earlier business date is meant to count. The tracker reads one business date only (StockTracker.kt:60-62); I did not check whether any opening-balance movement is written at day start.
- I did not read the TSO app; the cited files are SR and AMO only.

- NOT refuted: the gap holds at 4e0645556, and it is slightly worse than stated. All paths under android/ are relative to the repo root; backend paths likewise.

What the code does:
- The only producer of a kind="issue" stock movement is StockLoad.save, built from the rep's own typed/stepped `entered` map (android/feature-stock/src/main/kotlin/com/aktcl/aron/feature/stock/StockLoad.kt:97-101, 106, 117-124). A grep for `"issue"` across android/, backend/ and db/ finds no other writer (only the reader at feature-stock/.../StockTracker.kt:50).
- The SR host commits it straight to the local store: android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrDay.kt:260-261 (StockSaving.save -> capture.recordStock). AMO does the same at android/app-amo/src/main/kotlin/com/aktcl/aron/amo/AmoDay.kt:560. recordStock's only refusal is the Final Submit day lock (android/core-database/src/main/kotlin/com/aktcl/aron/core/database/repo/CaptureRepository.kt:84-91); there is no quantity check.
- The ceiling is soft: StockLoad.kt:63-64 ("warn and allow") and :104 ("the screen warns, Save still works"); save() at :108-127 never consults it. The screen only shows red text (feature-stock/.../StockScreen.kt:210-211). The only hard bound is MAX_ENTRY = 100,000 entry units per SKU (StockLoad.kt:99, 152).
- Worse than claimed: the ceiling is not config-driven in practice. SrDay.kt:636 constructs StockLoad without softCeiling, so it is always the hardcoded default 20,000 (StockLoad.kt:151; StockScreen.kt:94 defaults the same). `cfg.stock.max_issue_qty` appears in no db migration, backend or app wiring (grep hits only StockLoad.kt:63 and docs; docs/19-admin-config.md:587 lists it with default "none").
- A second self-declared path exists: "correct total" posts a signed `adjustment` of up to +/-10,000,000 base units from the rep's own entry (feature-stock/.../StockAdjustments.kt:24-38), wired for AMO at app-amo/.../AmoRecordWriter.kt:215-216. No SR call site of correctTotal was found in non-test code.
- On-hand is derived purely from those self-declared movements minus sales (StockTracker.kt:23, 41-56, 59-63); its own comment says "May be negative; the sale review warns, never blocks" (:22).
- Consequence for owner ruling (2): the sale side is also warn-only today — SaleReview.kt:22-23 ("a warning, never a block (F-SR-023)"), SaleReview.kt:92 (no stock row => no warning at all), SaleScreens.kt:385-389. So even a hard sale<=stock gate would be bounded only by a number the rep typed himself.
- No server-originated stock was found: no stock/opening rows in BundleService or core-sync (grep for stock|opening returned only packshot and record-type-list hits), and no depot/distributor/TSO issue table or endpoint (grep for distributor_issue|stock_allocation|opening_stock|depot in backend, db/migrations, android returned only comments at StockTracker.kt:11, 25). Server-side the day's "issued" is just the sum of the rep's uploaded movements (backend/sync/.../IngestService.kt:1808-1810).

Could not determine:
- Whether the server applies any per-record quantity cap to stock_movement beyond the pack-factor check (IngestService.kt:1055) and the payload shape (PayloadShapes.kt:49); I did not read the full ingest path or TypeRules.kt:22 handler.
- Whether TSO/portal has any stock approval or issue UI (app-tso was grepped only for stock-movement writers; none found).
- docs/16-data-platform.md:1108 describes a server fingerprint double-tap refusal; I did not verify it is implemented.

- Not refuted: the gap holds as a statement of fact at 4e0645556, with one framing caveat.

Evidence (all at 4e0645556):
- android/core-database/src/main/kotlin/com/aktcl/aron/core/database/dao/CaptureDao.kt:130-131 `memosOn` and :134-135 `linesOn` filter `WHERE business_date = :businessDate`; :146-147 `stockOn` and :150-154 `stockBalanceOn` do the same. The only other stock read in that DAO is by client UUID (:143-144). There is no query that sums movements or sales across dates.
- android/feature-stock/src/main/kotlin/com/aktcl/aron/feature/stock/StockTracker.kt:60-63 `of(db, businessDate)` builds balances only from those three single-date reads; `current = issued + adjusted - qcReturned - sold` (:23) therefore has no opening or carried term. `StockBalance` (:9-21) has no opening/carry field.
- Every caller I found passes the current business date: app-sr RoomSaleReads.kt:39,57,89,148,160; app-sr SrDay.kt:633,635; app-amo RoomSaleReads.kt:35,53,76; app-amo AmoRecordWriter.kt:212; app-amo AmoStockCorrectHost.kt:39; app-amo AmoDay.kt:570.
- No carry-over write exists either: the only stock write found in feature-stock is kind "issue" for the current day (android/feature-stock/.../StockLoad.kt:121). A grep for carry/opening/closing/yesterday across android/ and backend/src/main found no stock carry-over or opening-balance code.

Caveat on framing: the current model appears deliberate rather than accidental. StockTracker.kt:25-26 defines `toReturn` as "stock that goes back to the depot at day end", i.e. each day starts from zero and is reloaded by an issue. So "yesterday's unsold stock is invisible today" is accurate, but whether it is a defect depends on whether reps physically keep stock overnight; the owner's ruling as relayed does not say. It matters for the new hard stock gate: StockTracker.kt:22 says the sale review "warns, never blocks", and once that becomes a block, a rep holding yesterday's stock with no issue recorded today would have a balance of zero (or no stock row, :19-20) and be unable to sell.

Could not determine: whether the server keeps a cross-date stock balance. My grep of backend/src/main for stock_movement/stock_balance/opening_stock/on_hand returned nothing, which more likely means the backend sources live under a different path than that it has no stock code; I did not look further. I also did not check whether the reference bundle delivers a server-computed opening balance to the device, though no entity or DAO for one appears under core-database dao/ or entity/.

- Not refuted: the gap holds at 4e0645556. The code has no add-back of the edited memo's own quantity anywhere on the edit path.

Evidence, all at 4e0645556:
- `beginEdit` calls `flow.start(...)` before any edit context exists, then pre-fills the old lines and only afterwards calls `flow.withEdit(...)` (android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrSaleKit.kt:289-296; same in android/app-amo/src/main/kotlin/com/aktcl/aron/amo/AmoSaleKit.kt:224-230).
- `flow.start` loads the catalog once from the catalog source (android/feature-sale/src/main/kotlin/com/aktcl/aron/feature/sale/domain/SaleFlow.kt:57), which is `reads.catalog(date, priceType)` (SrSaleKit.kt:95; AmoSaleKit.kt:92).
- That catalog sets `stockBase` to `StockTracker.of(db, date)[sku].current` (android/app-sr/src/main/kotlin/com/aktcl/aron/sr/RoomSaleReads.kt:39-45; android/app-amo/src/main/kotlin/com/aktcl/aron/amo/RoomSaleReads.kt:35-41).
- `current` is issued + adjusted - qcReturned - sold (android/feature-stock/src/main/kotlin/com/aktcl/aron/feature/stock/StockTracker.kt:23), and `sold` sums every memo not yet superseded (StockTracker.kt:36-43). The memo being edited is still live until the edit commits, so its quantity is already subtracted.
- The review compares the full draft quantity against that figure: `sku.stockBase?.let { base > it }` (android/feature-sale/src/main/kotlin/com/aktcl/aron/feature/sale/domain/SaleReview.kt:92).
- `withEdit` only copies the edit context into the draft and does not touch the catalog (SaleFlow.kt:75). A grep for `stockBase` / `supersedes` across feature-sale, app-sr and app-amo main sources shows no adjustment for the superseded memo.

Effect: with 10 packets issued and 10 sold on one memo, editing that memo down to 8 compares 8 against 0 and flags it as exceeding stock.

Correction to the gap's framing: today this comparison is only a warning, never a block (SaleReview.kt:22-23; StockTracker.kt:22; shown at android/feature-sale/src/main/kotlin/com/aktcl/aron/feature/sale/ui/SaleScreens.kt:386-388). So the current symptom is a false warning on edits. It becomes a wrongful refusal once the owner's rule 2 turns the comparison into a hard gate, unless the edited memo's per-SKU base quantity is added back to available stock for an edit draft. No hard stock gate exists yet in SaleReview or the flow.

Not determined:
- Whether `SaleCommitter.commit` runs any stock check of its own; I read only its edit lines (android/feature-sale/src/main/kotlin/com/aktcl/aron/feature/sale/domain/SaleCommitter.kt:99-113), where the grep showed no stock reference.
- Whether the server (backend/) re-checks stock on sync; not examined.
- app-tso was not examined.
- Runtime behaviour was not exercised; this is from reading the code only.

- Not refuted: the gap holds at commit 4e0645556, and it is wider than stated. Stock is not only a stale snapshot at commit; no code path blocks a sale on stock at all.

Evidence (all at 4e0645556):
- Stock is read once, in start(): android/feature-sale/src/main/kotlin/com/aktcl/aron/feature/sale/domain/SaleFlow.kt:57 calls catalogSource.skus(...) and publishes it (SaleFlow.kt:65, 109-111). Production source is android/app-sr/src/main/kotlin/com/aktcl/aron/sr/RoomSaleReads.kt:39-45 (StockTracker.of(db, date), stockBase = current, or null when the SKU has no stock row), wired at android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrSaleKit.kt:95,102.
- The snapshot is never refreshed: every edit carries s.catalog forward (SaleFlow.kt:104,106) and commit passes the same s.catalog to the committer (SaleFlow.kt:89-92). In the android/*/src/main call sites I grepped, flow.start is called only from SaleViewModel.kt:26 (screen start) and the edit paths SrSaleKit.kt:291 and AmoSaleKit.kt:226.
- The committer does not re-read stock: SaleCommitter.kt:63-64 only re-runs SaleReviewCalculator.review on the passed catalog and throws on review.problems; it has no stock or DAO read for stock (SaleCommitter.kt:57-105).
- The Room transaction does not read stock either: CaptureRepository.recordSale (android/core-database/src/main/kotlin/com/aktcl/aron/core/database/repo/CaptureRepository.kt:134-155) checks purge, route-day lock, the visit and the edit fix, then inserts memo, lines, discounts, QC lines and outbox rows. recordNumberedSale (CaptureRepository.kt:172-183) adds only the purge/replay/route-day checks and the number reserve.
- Over-stock is a warning only, never a block: SaleReview.kt:22-23 ("a warning, never a block (F-SR-023)"); exceedsStock is computed at SaleReview.kt:92; the ReviewProblem list (SaleReview.kt:35-54) has no stock problem, so canCommit (SaleReview.kt:66) is unaffected. Its only consumer is the UI at feature-sale/.../ui/SaleScreens.kt:386. A null stockBase (no stock row) gives no warning at all (SaleModels.kt:23-24, SaleReview.kt:92).

Corrected statement of the gap: owner rule (2) is unmet in two layers. (a) There is no stock block anywhere: over-stock only sets a UI warning flag, and a SKU with no stock row is not even flagged. (b) Even if a block were added to the review, it would run against the catalog snapshot taken at flow.start, not against stock re-read inside the commit transaction. A fix needs both an on-device hard check in base units (qty via pack_factor) and a re-read of current stock inside the recordSale transaction; an edit must add back the superseded memo's quantities.

Not determined: whether the server (backend/) rejects an over-stock memo on sync (not read; out of this gap's scope); the internals of StockTracker (what "current" nets out, e.g. voids or superseded memos); whether the AMO app's catalog carries stock at all (only AmoSaleKit.kt:226 was seen); test sources were not searched.

- Not refuted: the gap holds at 4e0645556, and is wider than stated. Paths: SR = android/feature-sale/src/main/kotlin/com/aktcl/aron/feature/sale/domain, ST = android/feature-stock/src/main/kotlin/com/aktcl/aron/feature/stock.

1. The only stock comparison is per sale line: SR/SaleReview.kt:92 `sku.stockBase?.let { base > it } ?: false`. It compares that one line's qty_base with the SKU's balance; nothing sums draws per SKU across the memo.

2. It is a warning, not a gate. SaleReview.kt:22 says "a warning, never a block (F-SR-023)"; `ReviewProblem` (SaleReview.kt:35-54) has no stock problem, and `canCommit` is `problems.isEmpty()` (SaleReview.kt:66). SaleCommitter.kt:63-64 rejects only on review problems, so an over-stock sale commits. StockTracker.kt:22 confirms: "May be negative; the sale review warns, never blocks". The UI only shows a warning text (feature-sale/.../ui/SaleScreens.kt:386-388).

3. DRP reward packs are not counted. `rewardQtyBase = packs * packFactor` is computed at SaleReview.kt:100-108 and used only as a money discount (SaleReview.kt:119-120); it is never compared with stock. It is stored as `MemoDiscountEntity.qtyBase` (SaleCommitter.kt:79-84). StockTracker's `sold` sums `MemoLineEntity` only (ST/StockTracker.kt:43) and `balances()` takes no discount rows (StockTracker.kt:41, 62), so reward packs never reduce the on-hand balance for later memos either.

4. QC faulty sticks are not counted. QC entries become a money deduction only (SaleReview.kt:110-117) and `QcLineEntity` rows (SaleCommitter.kt:85-91). The tracker subtracts only `qc_return` stock movements (StockTracker.kt:52), and the one factory for them, `StockAdjustments.qcReturn` (ST/StockAdjustments.kt:15-18), has no non-test caller under android/ in my grep. `SaleCapture` carries memo, lines, discounts, qcLines and editFix, no stock movement (SaleCommitter.kt:102).

5. An unloaded SKU skips even the warning: `stockBase` is null when the SKU has no stock row today (app-sr/.../sr/RoomSaleReads.kt:45, app-amo/.../amo/RoomSaleReads.kt:41), and null gives `exceedsStock = false` (SaleReview.kt:92).

Not determined:
- Whether a draft can hold two lines for the same SKU (which would also slip past the per-line check); SaleDraft was not read.
- Whether the owner intends QC faulty sticks to draw on stock (replacement given from the bag) or to be a money-only deduction; the code treats the memo side as money only.
- Server side: a keyword grep of backend/src/main for a stock-exceed/insufficient rejection found nothing, but the backend sale-accept path was not read in full.
- The `qc_return` caller search was a grep for `qcReturn(` / `StockAdjustments.`; a movement built by hand with kind "qc_return" elsewhere was not excluded.

- Not refuted: the gap holds at commit 4e0645556. All paths below are under android/ unless prefixed.

Evidence:
- qcReturn() is defined at feature-stock/src/main/kotlin/com/aktcl/aron/feature/stock/StockAdjustments.kt:15-18. A grep for "qcReturn" over android/ and backend/ finds only test callers (feature-stock/src/test/.../StockAdjustmentsTest.kt:13,15,16). No production code calls it.
- The literal "qc_return" appears in non-test code only at StockAdjustments.kt:17 (the unused builder) and StockTracker.kt:52 (the reader). The tracker subtracts qc_return from the balance (StockTracker.kt:23,52), but nothing in the apps ever writes one.
- capture.recordStock has exactly three production callers:
  - app-sr/src/main/kotlin/com/aktcl/aron/sr/SrDay.kt:260-261 (saveStock, the Stock screen load)
  - app-amo/src/main/kotlin/com/aktcl/aron/amo/AmoDay.kt:560 (AMO load)
  - app-amo/src/main/kotlin/com/aktcl/aron/amo/AmoRecordWriter.kt:216 (AMO correctStock)
- The SR's only stock writer is the load, which always emits kind = "issue" (feature-stock/.../StockLoad.kt:120-121). Entry is clamped to 0 or more (StockLoad.kt:99), so a load cannot act as a negative return.
- correctTotal() (StockAdjustments.kt:24-39) has one production caller, AmoRecordWriter.kt:211-216, reached through AmoDay.kt:643 and AmoStockCorrectHost.kt:58. The correction UI lives in feature-amo (StockCorrection.kt:32,45). There is no SR equivalent, even though the comment at AmoStockCorrectHost.kt:21 calls it "the SR's correct total path (F-SR-081)".
- No Android production code constructs a movement of kind "return", "damaged" or "short"; the only StockMovementEntity constructors are StockLoad.kt:120 and StockAdjustments.kt:17,38. The server schema does allow those kinds (db/migrations/V0007__field_transactions.sql:522: 'issue','return','adjustment','damaged','short','qc_return').
- No "transfer" kind exists on either side: it is absent from the V0007:522 CHECK list and from every Android movement constructor.

Refinements to the gap statement:
- The AMO likewise has no qc_return, return, damaged or short writer; it has only the load and correct-total.
- "Transfer" is missing from the schema as well as the app, so it is not merely an unwired SR writer.

Not determined:
- Whether the SR QC flow (the qc_line record) is meant to reduce stock by some other route. I only confirmed that no stock_movement of kind qc_return is produced.
- Whether the backend ingest accepts or validates 'return', 'damaged' and 'short' movements beyond the DB CHECK; backend/ was grepped only for "qcReturn".
- I did not open the body of CaptureRepository.recordStock, the SR UI hosts, or the listed line 211-216 context beyond the function itself.

- Not refuted: the gap holds at 4e0645556.

Evidence:
- android/feature-stock/src/main/kotlin/com/aktcl/aron/feature/stock/StockTracker.kt:36-39 — liveMemos() only removes memos superseded by an edit. :41-43 — "sold" sums every line of every such memo; there is no refused, quarantined or voided filter. :60-63 — of() feeds it the raw rows from captureDao.
- android/core-database/src/main/kotlin/com/aktcl/aron/core/database/dao/CaptureDao.kt:130-131, 134-135, 146-147 — memosOn / linesOn / stockOn are plain "WHERE business_date" selects with no state or void column.
- The refused set does exist but only reconciliation uses it: OutboxDao.kt:156-158 (refusedUuids = outbox state rejected/quarantined) and Reconciliation.kt:93-97, 104, 109 (deviceMoney filters memos, lines and stock movements by it). Reconciliation.kt:78-79 states in its own comment that a memo the server voided later is not known to the phone until the next bundle.
- Every StockTracker caller uses the unfiltered of(): app-sr RoomSaleReads.kt:39, 57, 89, 148, 160; app-sr SrDay.kt:635; app-amo RoomSaleReads.kt:35, 53, 76; AmoRecordWriter.kt:212; AmoStockCorrectHost.kt:39.
- No phone-side memo void writer: "memo_void" appears in non-test android code only as a type name in lists (RouteDayLock.kt:159, core-geo FixModel.kt:10, core-sync SyncEngine.kt:834). A grep for voidMemo / MemoVoid / "voided" in core-database, core-sync, feature-sale and feature-stock main sources found no writer, and no "DELETE FROM memo" exists that would remove a refused memo's rows from Room.

Two additions to the gap as stated:
- StockTracker also does not exclude refused stock_movement rows (StockTracker.kt:50-52), although Reconciliation.kt:109 does. A refused issue or adjustment therefore still inflates on-hand stock; this matters as much as the memo side for a hard stock gate.
- StockTracker.kt:22 documents current as "May be negative; the sale review warns, never blocks", so the tracker is today a warning input, not a gate.

Not determined:
- Whether a later reference bundle delivers server-side memo voids into the phone's memo table. No void column or bundle handler was seen in the grep; ReferenceRepository.kt:296 only handles submit voids (route-day reopen). I did not read the whole file.
- I did not read feature-memo or feature-sale in full; the absence of a void writer rests on the grep patterns above.
- The backend memo_void ingest path was not examined.

- Not refuted; the gap holds at commit 4e0645556 (precise lines: StockTracker.kt:22-23, not 22-26; line 26 is the clamped toReturn).

Evidence (all paths under android/ at 4e0645556):
- feature-stock/src/main/kotlin/com/aktcl/aron/feature/stock/StockTracker.kt:22-23 — `current = issued + adjusted - qcReturned - sold`, documented "May be negative; the sale review warns, never blocks." Only `toReturn` (line 26) is clamped with maxOf(0, current).
- feature-stock/src/test/.../StockTrackerTest.kt:41-43 — test `overSellingGoesNegativeForTheWarningButReturnNeverDoes` asserts current == -2; line 48 asserts current == -4 for a SKU sold with no stock row. Negative stock is the tested, intended behaviour.
- feature-sale/src/main/kotlin/com/aktcl/aron/feature/sale/domain/SaleReview.kt:22-23 — `exceedsStock` is "a warning, never a block (F-SR-023)". Its only main-code consumer is feature-sale/.../ui/SaleScreens.kt:385-390, which prints a red warning text ("a warning, never a block"); no save path reads it.
- feature-sale/.../domain/SaleModels.kt:23 — stockBase is null when unknown, "no warning then"; app-sr/.../RoomSaleReads.kt:45 and app-amo/.../RoomSaleReads.kt:41 pass null when the SKU has no stock row (hasStockRow false), so a never-loaded SKU sells with not even a warning.
- Displayed: app-sr/.../SrDay.kt:635-636 feeds unclamped `current` into StockLoad as currentBaseBySku; feature-stock/.../StockLoad.kt:26 and :32 render figureEntry/figureRemainderBase from stockBase with no clamp; StockScreenTest.kt:97-103 explicitly renders stockBase = -7 in the totals panel. app-sr RoomSaleReads.kt:162 and app-amo RoomSaleReads.kt:78 also pass raw `current`.

Could not determine: whether the server rejects or flags an over-stock memo on sync. A grep of backend/src/main for stock + exceed/insufficient/negative/oversell returned nothing, but I did not read the sync handlers line by line. I also did not trace the full sale-save function (SaleFlow) line by line; the conclusion that nothing blocks rests on `exceedsStock` having a single UI-only consumer and on the explicit "never blocks" comments and tests.

- NOT refuted: the gap holds as stated at 4e0645556, with one nuance and two extra mismatches.

Confirmed:
- Server aggregate: backend/analytics/src/main/kotlin/com/aktcl/aron/backend/analytics/Aggregator.kt:225-227 (rebuildAggRouteSku) builds issued as sum(qty_base) FILTER (kind = 'issue') and ret as FILTER (kind IN ('return','qc_return')); 'adjustment' (and 'damaged','short') go into neither column of dw.agg_daily_route_sku (insert at :228-231). The DB allows all six kinds: db/migrations/V0007__field_transactions.sql:522.
- Phone: android/feature-stock/src/main/kotlin/com/aktcl/aron/feature/stock/StockTracker.kt:50-52 computes issued ('issue'), adjusted ('adjustment'), qcReturned ('qc_return'); current = issued + adjusted - qcReturned - sold (StockTracker.kt:23). Adjustments are really produced on the phone as signed movements: android/feature-stock/.../StockAdjustments.kt:38, used by AMO at android/app-amo/.../AmoRecordWriter.kt:212-215.

Nuance (partial server compensation, not in the aggregate itself):
- backend/analytics/.../TeamApi.kt:186-194 (team stock "current") re-reads app.stock_movement and adds signed 'adjustment' and subtracts 'damaged'/'short' on top of the aggregate, so that one endpoint's current_qty_base does include adjustments. Its issued_qty_base/returned_qty_base fields (TeamApi.kt:190) still exclude them.
- backend/analytics/.../ReportHandlersB.kt:168-170 computes closing_qty_base = issued - sold - free - returned straight from the aggregate with no adjustment term, so that report disagrees with the phone whenever an adjustment exists.
- backend/sync/.../SalesSubmitDigest.kt:171 uses kind IN ('issue','adjustment'), matching the phone's CaptureDao.kt:149-151 (issue and adjustment add, others subtract) — i.e. server code is internally inconsistent on what "issued" means.

Additional mismatches found while checking:
- The phone's balance ignores 'return', 'damaged' and 'short' movements entirely (StockTracker.kt:50-52), whereas the server aggregate counts 'return' as returned (Aggregator.kt:226) and TeamApi subtracts damaged/short (TeamApi.kt:193).
- The phone's own docstring says the balance "May be negative; the sale review warns, never blocks" (StockTracker.kt:22), which is relevant to owner ruling (2).

Not determined: whether any server-side sync validator re-checks a sale against stock at all (I only searched analytics/sync for the kind filters, not the full sync ingest path); whether the phone ever emits 'return', 'damaged' or 'short' movements; whether other consumers of dw.v_daily_sku (db/migrations/V0015:89, V0118:400-401) derive a balance from these columns.

- Not refuted at commit 4e0645556: the server has no stock-sufficiency check at ingest. All paths below are under backend/sync/src/main/kotlin/com/aktcl/aron/backend/sync/ unless stated.

Evidence:
- The numbered ingest steps in IngestService.kt run from shape (844) through registry (862), date window (895), references (929), memo number (947), scope (961), device gate (1001), signature (1021), content fingerprint (1036), pack size (1050), money (1061), zero price (1066), parents (1093), day lock (1139), visit kind (1149) to ledger rules and store (1156). None compares a memo line's qty_base with the rep's held stock.
- The only quantity rule is step 7b at IngestService.kt:1050-1058. It calls MemoChecks.packFactorProblem (MemoChecks.kt:106-111), which checks only that pack_factor equals the SKU's base_per_pack. It says nothing about how much stock exists.
- app.stock_movement is read in backend/sync main code only for totals: IngestService.kt:1808-1810 (the "issued" sum, next to the "sold" sum at 1800-1807, returned side by side in MoneyTotals at 1811 with no comparison) and SalesSubmitDigest.kt:170.
- The only other non-test readers are in backend/analytics: Aggregator.kt:227 and TeamApi.kt:194 (aggregation and the team stock view; "current = issued - sold ..." at TeamApi.kt:186 is display only).
- contract/slices/schemas/RecordOutcomeCode.yaml has no stock code. The nearest are qty_invalid (pack size) and insufficient_points (loyalty).
- The memo flags written are pack_factor_mismatch (IngestService.kt:1057), price_mismatch (MemoChecks.kt:254) and arithmetic_mismatch (MemoChecks.kt:269, 273, 283). The "over" flag at 275-283 means more child rows than the header counts, not over stock.
- In db/migrations the stock_movement triggers are immutability (V0007:535, V0042:119) and capture-context stamping (V0042:108). No trigger or CHECK enforces a balance.

So an over-stock memo that passes the pack, arithmetic and price checks is stored and counted in the "sold" totals at IngestService.kt:1800-1807.

Correction to the cited location: MemoChecks.kt:107 is the standalone pack-factor rule for stock_movement. It is relevant only as the nearest existing check.

Not determined:
- I did not read the bodies of the registered handlers' checks at steps 8b and 11 (IngestService.kt:1080, 1156). A grep for stock, on-hand, insufficient and oversell across backend/sync/src/main found no sufficiency logic, and stock_movement is not read anywhere else at ingest.
- I did not read the SQL bodies of every DB trigger function; none of the triggers on stock_movement or memo_line is named for a balance.
- Whether the Android apps block over-stock sales on the device is outside this gap and was not examined.
- Nothing was executed; this is from reading code only.

- Not refuted: both halves of the gap are in the code at 4e0645556. One refinement: the server does also hold per-user stock sums, but none of them is a gate.

**Confirmed**
- **Server aggregate is route-keyed.** `backend/analytics/.../Aggregator.kt:225-227` (`rebuildAggRouteSku`) sums `app.stock_movement` with `WHERE route_id = :r AND business_date = :d`, into `dw.agg_daily_route_sku (business_date, route_id, sku_id, ...)` (:228). A movement with a NULL route never lands in any row.
- **The worker skips route-less movements.** `backend/analytics/.../AggregationWorker.kt:92` picks routes to rebuild with `route_id IS NOT NULL`.
- **The team stock view inherits the same key.** `backend/analytics/.../TeamApi.kt:190-194` computes current stock from `dw.agg_daily_route_sku` joined to movements `ON mv.route_id = a.route_id`.
- **AMO movements outside a call carry no route.** `android/app-amo/.../AmoDay.kt:567` sets `routeId = visitSession.current.value?.routeId?.takeIf { it != 0L }`, which is null with no call open. `saveStock` uses it at :559-560, and the KDoc at :553-557 says so explicitly.

**Refinement**
- **User-keyed sums exist on the server, but only for totals.** `backend/sync/.../IngestService.kt:1809` and `backend/sync/.../SalesSubmitDigest.kt:170-171` sum `stock_movement` by `user_id` and date for the submit digest and totals. Neither rejects a sale.
- **The phone's balance is not route-keyed.** `android/core-database/.../CaptureDao.kt:150-154` (`stockBalanceOn`) groups by SKU and date only, so the AMO's route-less movements do count on the device (`AmoDay.kt:570`).
- **Over-stock is a warning today, not a block.** `android/feature-sale/.../domain/SaleReview.kt:22-23` says "a warning, never a block (F-SR-023)"; it is shown at `ui/SaleScreens.kt:386`.

So a stock gate should key on user and business date (the device balance, or the `user_id` sums), not on the route aggregate.

**Not determined**
- I found no server-side sale-versus-stock rejection by grep (`insufficient`, `stock_short`, `oversell`, `stockBalance`) over non-test `backend/` and `android/`; I did not read `IngestService.kt` or `MemoChecks.kt` end to end.
- I did not read the `stock_movement` migration to confirm `route_id` is nullable in the schema; it is inferred from the `IS NOT NULL` filter and the AMO KDoc ("as on the server").
- I did not check whether SR stock movements always carry a route (`SrDay.kt:633` was seen only for the balance load).

- Not refuted: the gap holds at commit 4e0645556. I found no check-in gate in the SR app's navigation, its local write layer, or the server ingest.

Evidence (all paths at 4e0645556):
- **Tiles open unconditionally.** In android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrApp.kt:374-391 the `onTile` handler sets the screen for STOCK (377), SALE (378), OUTLET (380), MEMO, SUMMARY and SALES_SUBMIT without reading the `attendance` state collected at SrApp.kt:254.
- **Screens are not gated on check-in either.** STOCK renders `StockHost(day)` directly (SrApp.kt:448). PICKER (SrApp.kt:449-477) is wrapped only in `PermissionGate(GatedFeature.SALE)`, which is a permission gate, and `day.sale.startCall` refuses only on the Final Submit / Sales Submit lock (SrApp.kt:454-455, 469).
- **Check-in is a prompt by design.** android/feature-attendance/src/main/kotlin/com/aktcl/aron/feature/attendance/AttendanceFlow.kt:24-26 says "The check-in message is a prompt, never a block (Q-UI-04, `cfg.day.require_checkin_before_sale` off)". docs/25-build-backlog.md:206 records the same assumption.
- **Home only suggests check-in.** android/feature-home/src/main/kotlin/com/aktcl/aron/feature/home/HomeModel.kt:111 and 122 map NOT_CHECKED_IN to the CHECK_IN dock, which is a next-step hint with an accent ring (HomeModel.kt:98-101), not a block.
- **Local writes do not check attendance.** In android/core-database/src/main/kotlin/com/aktcl/aron/core/database/repo/CaptureRepository.kt, `recordStock` (84-95) checks only UUIDs, purge and `requireNotFinal`; `recordVisitOpen` (97-106) checks route and `requireOpenRouteDay`; the `recordSale` preconditions (112-134) have no attendance lookup. The transaction bodies of `recordSale` beyond line 135, `recordNumberedSale` (172) and `recordDueCollection` (209) were not read line by line, but the grep below returns nothing for them.
- **No attendance reference in the feature modules.** A grep for `attendance|checkedIn|NOT_CHECKED` over the main sources of feature-sale, feature-stock, feature-outlet, feature-dayclose and feature-memo returns no hits.
- **The config key exists but nothing reads it.** `cfg.day.checkin_gate` (off/soft/hard, default "soft") is registered at db/migrations/V0006__cfg_key_registry.sql:64 and documented at docs/19-admin-config.md:504 ("hard blocks Stock and Sale until check-in"). A grep for `checkin_gate|checkinGate|require_checkin` over android/, backend/ and web/ finds only the comment at AttendanceFlow.kt:25.
- **Server does not reject for a missing check-in.** backend/sync/src/main/kotlin/com/aktcl/aron/backend/sync/IngestService.kt gates attendance and sales only on device integrity (1001-1005, GATED_TYPES at 1950) and on a too-early check-out (916).

Additions to the gap statement:
- The only existing reference to "not checked in" outside Home is the breadcrumb tracker (android/core-geo/src/main/kotlin/com/aktcl/aron/core/geo/Breadcrumbs.kt:33,42), which is unrelated to operational gating.
- The setting for this already exists as `cfg.day.checkin_gate` with a `hard` value; it is just unwired, so the owner's ruling maps onto an existing key.

Not determined:
- Whether the AMO and TSO apps gate anything on check-in; I did not inspect them (the gap is about the SR app).
- Whether `web/` exists at this commit and exposes the key in the admin portal; the grep over it returned nothing either way.
- Runtime behaviour was not exercised; this is a static read only.

- Gap HOLDS (not refuted) at 4e0645556. Evidence:

- android/app-amo/src/main/kotlin/com/aktcl/aron/amo/MainActivity.kt:223-252 builds the `screens` map conditional only on `day` being non-null (`day?.let { d -> mapOf(...) }`). SALES_SUBMIT (225), OUTLET (237), SALES_JOURNEY (246), STOCK (247), SALE (248), MEMO (249), SUMMARY (250), CONTROL_CALL (251) and JOINT_CALL (252) are mapped with no attendance condition. Minor correction to the cited range: line 253 is the ATTENDANCE entry itself, so the unconditional operational entries are 225-252.
- The only reads of attendance state in the AMO app are the restore after a kill (MainActivity.kt:192, `d.attendance.restore(d.attendanceToday())`) and the Attendance screen itself (MainActivity.kt:254, `d.attendance.state.collectAsStateWithLifecycle()`). A grep for `attendance.state|attendance.restore|attendanceToday|showCheckInPrompt` across android/app-amo/src/main and android/feature-amo/src/main returns only AmoDay.kt:212 and MainActivity.kt:192, 254; no sale/stock/call host consumes it.
- android/feature-amo/src/main/kotlin/com/aktcl/aron/feature/amo/AmoApp.kt:86-108: navigation is `onTile = { screen = AmoScreen.of(it) }` (line 98) and the `else` branch at 108 renders `screens.getValue(s)` whenever a host exists; AmoApp has no attendance/check-in parameter (signature lines 60-84) and tiles come from `AmoMenu.tiles(reach, ...)` (line 88), i.e. gated by reach only.
- The gate that does exist in the AMO app is a different one: android/app-amo/src/main/kotlin/com/aktcl/aron/amo/AmoDayLockGate.kt:23-47 (AmoLockGate) handles only route-day Final Submit / Sales Submit refusals (DayRefusal), not attendance. `PermissionGate(GatedFeature.ATTENDANCE)` in android/feature-amo/.../AmoAttendance.kt:35 is an Android-permission gate on the Attendance screen, not a check-in gate.
- No write-layer backstop either: in android/core-database/src/main/kotlin/com/aktcl/aron/core/database/repo, the only attendance references are the attendance write itself (CaptureRepository.kt:55-61), purge (LocalPurge.kt:10, 92) and RouteDayLock.kt:18, 156 (which list attendance_event as a type exempt from the lock). A repo-wide grep (android + backend, non-test) for notCheckedIn/requireCheckIn/CheckInGate/CHECK_IN_REQUIRED etc. finds check-in state only in the SR home model (android/feature-home/.../HomeModel.kt:92, 109-111, 122; android/app-sr/.../SrHomeState.kt:99) and the breadcrumb sampler (android/core-geo/.../Breadcrumbs.kt:33, 42), none in the AMO app.

Not determined: I did not read every line inside the hub hosts (AmoSaleHub, ControlCallHost, AmoTeamHub, AmoSubmitHost, AmoJourneyHost, AmoOutletHub) or the shared SaleFlow/VisitFlow; the conclusion that they contain no check-in gate rests on the greps above returning no attendance-state reads in android/app-amo/src/main and android/feature-amo/src/main, and no attendance precondition in the core-database repo layer. I did not check whether the backend rejects AMO records synced without a same-day check-in (the backend grep showed only analytics uses, backend/analytics/.../DashboardSummaries.kt:32-49, 167), and whether read-only AMO screens (reports, team location, live dashboard, printer, support) should fall under the owner's "operational" gate is a scoping decision, not something the code settles.

- Gap holds at 4e0645556; I could not refute it. One correction to the "Where" line: the module dependency list is incomplete, though that does not affect the TSO finding.

Evidence:
- `git grep -i attendance` over android/feature-tso and android/app-tso returns zero hits. A search for checkin / check_in / check-in / AttendanceFlow in the same two modules also finds nothing about attendance.
- The TsoScreen enum has no attendance or check-in/check-out destination (android/feature-tso/src/main/kotlin/com/aktcl/aron/feature/tso/TsoNav.kt:14-21). Its destinations are DASHBOARD, LOGIN_SUBMIT_STATUS, NOTIFICATIONS, SETTINGS, CHANGE_PASSWORD, SUPPORT_UPLOAD, the TICKET_* screens, LEAVE, FINAL_SUBMIT, MY_TEAM, RETAILER, MY_VISIT_PLAN, SET_PLAN, TARGET_STATUS, TARGET_DETAILS, FEEDBACK_NEW, FEEDBACK_LIST and DEVICE_OTP.
- android/app-tso/build.gradle.kts:98-113 and :133 list the project dependencies (core-*, dpc, feature-auth, feature-home, feature-tasks, feature-tso, feature-tickets); feature-attendance is not among them.
- android/feature-tso/build.gradle.kts:16-19 depends only on core-ui, core-common, feature-home and core-map.
- feature-home, feature-tasks, feature-tickets and feature-auth build files contain no reference to attendance, so nothing reaches the TSO app transitively.
- AttendanceFlow (android/feature-attendance/src/main/kotlin/com/aktcl/aron/feature/attendance/AttendanceFlow.kt:66) is constructed in production code only at android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrDay.kt:373 and android/app-amo/src/main/kotlin/com/aktcl/aron/amo/AmoDay.kt:192.
- The TSO host hard-codes the day as never open: `UpdateHost(updateShell, dayOpen = { false }, ...)` at android/app-tso/src/main/kotlin/com/aktcl/aron/tso/MainActivity.kt:274. There is no TSO day-open state to gate on.
- The only "checkout" mentions in app-tso are sync-scheduler settings (checkoutJitterS, CheckoutGate) at android/app-tso/src/main/kotlin/com/aktcl/aron/tso/SessionModule.kt:91-94, not an attendance feature.

Correction to the "Where" line: besides app-sr (android/app-sr/build.gradle.kts:106) and app-amo (android/app-amo/build.gradle.kts:105), feature-attendance is also a dependency of android/feature-amo/build.gradle.kts:19 and a test dependency of android/ui-screenshots/build.gradle.kts:40.

Not determined:
- Whether the owner's ruling is meant to cover the TSO at all. The TSO app has no stock, sale or collection screens in TsoScreen; its field actions are visit plan, feedback, leave, tickets and final submit. Whether those count as "operational things" needing a check-in gate is an owner or spec question I did not resolve.
- Whether the backend accepts or expects attendance rows for the TSO role. A filtered search of backend/src/main for attendance lines mentioning TSO or role returned nothing, but I did not read the attendance handlers.
- I did not read TsoApp.kt or the feature-tso screens line by line; the absence claim rests on the zero-hit searches and the dependency lists.

- Not refuted: the gap holds at commit 4e0645556. Evidence (all under android/core-database/src/main/kotlin/com/aktcl/aron/core/database/):

- repo/RouteDayLock.kt:278-281 — enum DayRefusal has exactly FINAL_SUBMIT and SALES_SUBMIT; DayRefusal.of (285-289) maps only RouteDayFinalSubmittedException and RouteDaySubmittedException.
- repo/RouteDayLock.kt:32-38 (isOpen), 45-51 (requireOpen), 57-61 (refusal) — the only inputs are FinalSubmitLock.refuses, routeOpen (63-67, reads day_submit cycles and a reopen meta key) and supervisorOpen (69-73). No attendance read. Attendance appears in this file only in comments (lines 18, 156) listing attendance_event as a type the lock does not refuse.
- repo/CaptureRepository.kt:457 — requireOpenRouteDay delegates straight to RouteDayLock.requireOpen; 460-461 requireNotFinal checks only FinalSubmitLock. dayRefusal (202-205) returns only those two refusals.
- Write paths: recordStock (84-95) calls only requireNotFinal; recordVisitOpen (97-106) calls requireRoute + requireOpenRouteDay(visit); recordSale (112, check at line 136) calls requireOpenRouteDay(memo); recordNumberedSale (172-179) the same route-day check before reserving a memo number; recordDueCollection (209) goes through the same day check (acceptsDueCollection at 190 = dayRefusal == null). None reads attendance.
- dao/CaptureDao.kt:140-141 — attendanceOn(businessDate) exists, but within core-database/src/main and core-sync/src/main no repository or lock code calls it (grep for "attendance" there returns only the entity, DAO, recordAttendance at CaptureRepository.kt:55-62, purge/sync type lists, and DeviceRuntime.onAttendanceCommitted at core-sync .../device/DeviceRuntime.kt:133-136).

Related, not a write-layer gate: check-in state exists only as UI/home state — android/feature-home/src/main/kotlin/com/aktcl/aron/feature/home/HomeModel.kt:92,111,122 (HomeDayState.NOT_CHECKED_IN -> HomeDock.CHECK_IN) and android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrHomeState.kt:99-101. That chooses the home dock action; it does not refuse a capture in the repository.

Minor precision on the claim's line list: lines 220, 251, 263, 348 of CaptureRepository.kt were not individually opened; from the function index they fall inside recordDueCollection (209), recordAmo (231), recordVisitSkip (258) and recordDaySubmit (337). The claim that they carry only Sales/Final Submit checks is consistent with the absence of any attendance read in the file but was not verified line by line.

Not determined: whether individual screens (stock, sale, visit, collection in feature-* modules) or navigation in app-sr/app-amo/app-tso block entry before check-in (a grep for check-in gating terms in feature-sales, feature-stock, feature-visit and app-sr main sources found nothing beyond the home state above, but the screens were not read); whether the backend refuses captures on a day with no check-in (same grep over backend/src/main found nothing, backend not read further).

- Gap holds at 4e0645556; one detail of the claim needs correcting (D-329 says "off", the registry default is "soft").

Evidence the key is read by no code:
- `git grep -i "checkin_gate|checkin.gate"` over the whole tree at 4e0645556 returns hits only in db/migrations/V0006__cfg_key_registry.sql:64 (registry row, enum off/soft/hard, default "soft"), DECISIONS.md:383 and docs (docs/19-admin-config.md:504 and :1204, docs/24-build-spec.md:930, docs/15-feature-inventory.md:327/1167/1249/2203, docs/evidence/...). There are zero hits under android/, backend/ or web/.
- android/core-sync/src/main/kotlin/com/aktcl/aron/core/sync/device/DayConfig.kt:63-84 (`refresh`) reads cfg.geo.*, cfg.day.checkout_earliest_time, cfg.auth.offline_unlock_*, cfg.sync.*_jitter_s, cfg.map.* and the calendar; it does not read cfg.day.checkin_gate.

Evidence no other code gates on check-in (so the gap is not met under another name):
- android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrApp.kt:374-391: the Home `onTile` handler opens STOCK, SALE (PICKER/VISIT), OUTLET, MEMO, SALES_SUBMIT etc. unconditionally; no attendance or day-state check.
- android/feature-home/src/main/kotlin/com/aktcl/aron/feature/home/HomeModel.kt:108-127: NOT_CHECKED_IN only selects the next-step dock (HomeDock.CHECK_IN, a hint pointing at the Attendance tile); SrApp.kt:397 passes it as `dock` only.
- android/feature-attendance/.../AttendanceFlow.kt:26,198 and AttendanceScreen.kt:211: `showCheckInPrompt` only renders a prompt string on the Attendance screen.
- grep for attendance/check-in under android/feature-sale/src/main and android/feature-stock/src/main returns only feature-sale/.../domain/EditGate.kt:40, which is a geofence/QC edit gate, not attendance.
- Server: backend/sync/.../DayStates.kt:21 treats "the first check-in attendance_event or the first visit" as starting in_field, i.e. a visit without check-in is accepted; IngestService.kt:1001-1005 and :1950 gate attendance/memo types on device integrity, not on a prior check-in. I found no ingest rule that rejects or flags a memo/visit/collection lacking a check-in.

Correction to the claim: DECISIONS.md:383 (D-329, MUST-CONFIRM by 2e) says "a missed check-in does not block selling (cfg.day.checkin_gate off)", while the registry default (V0006:64) and docs/19:504 say "soft" (prompts, never blocks). Docs/decision disagree on off vs soft; both are non-blocking, and docs/15-feature-inventory.md:1167 says "Check-in is a prompt, not a gate, for Stock and Sale". All contradict the owner's new ruling (hard block).

Not determined: I did not read the AMO/TSO app tile handlers line by line (grep for dayState/NOT_CHECKED_IN/checkin_gate in app-amo and app-tso returned nothing, so no gate there either, but I did not confirm their attendance wiring); there is no android/feature-collection or feature-visit module (collection/visit live in feature-sale/app-sr hosts), and I did not read every host in app-sr beyond the grep; test sources were excluded from the greps.

- Not refuted: the gap holds at 4e0645556 as claimed, with two refinements.

Evidence (all at 4e0645556):
- `AttendanceFlow.restore()` is the only path that reads stored events into the state (android/feature-attendance/src/main/kotlin/com/aktcl/aron/feature/attendance/AttendanceFlow.kt:87-91). `tick()` recomputes from the in-memory `ui.value.checkedInAt` / `checkedOutAt` (AttendanceFlow.kt:94), and `refreshSubmit()` only re-reads the submit flag and then calls `tick()` (AttendanceFlow.kt:97-100). Nothing in the flow knows the business date.
- SR: `restore(day.attendanceToday())` is called once, in `LaunchedEffect(Unit)` (android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrApp.kt:266-269). The boundary effect (17:00 and Dhaka midnight, SrDay.kt:147-153) calls only `day.attendance.tick(); day.reload(); day.taskBoard.load()` (SrApp.kt:275-279). ON_RESUME calls only `refreshSubmit()`, `reload()` and `taskBoard.load()` (SrApp.kt:292-294). The attendance screen's own effect also calls only `refreshSubmit()` (SrApp.kt:409). A grep for `restore(` across android/app-sr/src/main finds the attendance call at SrApp.kt:269 only.
- The SrDay (and its AttendanceFlow) lives in a ViewModel keyed per user (android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrDayHolder.kt:7-9; android/app-sr/src/main/kotlin/com/aktcl/aron/sr/MainActivity.kt:156-159), so yesterday's `checkedInAt` survives backgrounding and resume. It is refreshed only when SrApp enters composition again (activity recreate, e.g. language switch) or the process dies.
- AMO: `d.attendance.restore(d.attendanceToday())` runs once in `LaunchedEffect(s.user.userId)` (android/app-amo/src/main/kotlin/com/aktcl/aron/amo/MainActivity.kt:187-192). There is no `tick()` call and no boundary timer anywhere in android/app-amo/src/main (grep). The only other attendance call is `LaunchedEffect(d) { d.attendance.refreshSubmit() }` (MainActivity.kt:256), which reuses the stale values.

Refinements:
1. "AMO has no boundary handling at all" is true for attendance only. The AMO ON_RESUME does refresh the Home date and target cards via `vm.onResume()` (MainActivity.kt:203-207) and bumps `resumeTick` for the bundle (MainActivity.kt:77-81, 217); neither touches `d.attendance`.
2. The stale state cuts both ways, which matters for the owner's rule 1. After midnight in a live process, a gate built on `AttendanceState.checkedInAt` would treat yesterday's check-in as today's. Separately, `checkIn()` returns `Ignored` when `checkedInAt != null` (AttendanceFlow.kt:110-113) and `checkInEnabled = inAt == null` (AttendanceFlow.kt:199), so the rep cannot check in for the new day until the process or composition restarts.

Not determined:
- Whether any operational gate consumes `AttendanceState` today. The state's own comment says check-in is "a prompt, never a block" (AttendanceFlow.kt:25-26); I did not trace the stock/sale/visit hosts.
- The TSO app's attendance wiring: no `restore(`, `.tick(` or `refreshSubmit` matches in android/app-tso/src/main, but I did not look further.
- Nothing was run on a device; this is from reading the code.

- The gap holds, but "all operational screens and writes stay open" overstates it for SR and AMO. No lock keys on check-out itself, yet check-out is only possible after Sales Submit, and that submit already refuses most captures. All citations are at commit 4e0645556.

**What is confirmed (no check-out lock exists)**
- `RouteDayLock.isOpen` reads only the Final Submit lock, the route's `day_submit` cycle and the AMO supervisor-day submit; it never reads an attendance event (android/core-database/src/main/kotlin/com/aktcl/aron/core/database/repo/RouteDayLock.kt:32-38, 63-73).
- `recordAttendance` only inserts the event and its outbox row (android/core-database/.../repo/CaptureRepository.kt:60-61).
- The check-out committer only runs the device-owner hook, breadcrumbs and a sync request (android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrDay.kt:374-386; android/app-amo/src/main/kotlin/com/aktcl/aron/amo/AmoDay.kt:198-203).
- `checkedOutAt` / `checkedOut` is consumed only by the attendance buttons and screen, and by the home state and its "day complete" banner (AttendanceFlow.kt:126, 200; AttendanceScreen.kt:188, 367; app-sr/.../SrHomeState.kt:99-101; feature-home/.../HomeModel.kt:112; HomeScreen.kt:252). The grep for `checkedOut|afterCheckoutTime` over app-* and feature-* returned no other non-test consumer, so no screen or write is gated on it.
- Check-in is documented as "a prompt, never a block" (AttendanceFlow.kt:25).

**What the gap misses (SR and AMO are mostly locked by the time they check out)**
- Check-out is refused until the day's Sales Submit is committed locally (AttendanceFlow.kt:129-132, 196-200), wired for SR (SrDay.kt:395) and AMO (AmoDay.kt:209).
- A standing Sales Submit already refuses these writes on that route-day: visit open (CaptureRepository.kt:101), memo (136, 179), visit_close (162), routed due_collection (220), AMO records (251), visit_skip (263), and the writes at 282 and 312.
- So ruling (3) inverts today's order: the code forces submit before check-out, where the owner wants check-out to do the submit.

**What really stays open after check-out**
- **Stock:** `recordStock` checks only the Final Submit lock; the comment says "A Sales Submit does not refuse stock" (CaptureRepository.kt:82, 90, 203).
- **Routeless due collection:** never checked (CaptureRepository.kt:204, 220).
- **After a server reopen:** a voided submit reopens every capture although the rep is checked out (RouteDayLock.kt:63-67, 81-82).
- **Final Submit path for SR:** `finalLocked()` also opens check-out without a Sales Submit (SrDay.kt:395), and under the default `accept_and_flag` policy the Final Submit lock refuses nothing (RouteDayLock.kt:130-134, 172).
- **Other routes:** the lock is per route-day, so a different `routeId` on the same date is unaffected (RouteDayLock.kt:63-66).
- **TSO:** `salesSubmitted` is null by design, so there is no submit gate and no check-out lock (AttendanceFlow.kt:78-79).
- **Screens:** none is closed on check-out; refusals surface only at write time as a `DayRefusal`.

**Not determined**
- Whether the backend refuses records captured after a check-out: my grep of `backend/src/main` for check-out refusal logic returned nothing, but I did not read `IngestService`.
- What `finalLocked()` and `daySubmitted()` do internally; I read only their call sites.
- The app-tso attendance wiring; the TSO statement rests on the AttendanceFlow doc comment.
- Whether the SR screens hide entry points after Sales Submit.

- Gap holds at 4e0645556 for the SR; not refuted. Two refinements for the supervisor apps and the `state` field.

What the code shows:
- **Phone side is local only.** Checked-in state is rebuilt solely from the Room table: `AttendanceFlow.restore(today)` (android/feature-attendance/.../AttendanceFlow.kt:86-91) is fed by `captureDao().attendanceOn(businessDate())` (android/core-database/.../dao/CaptureDao.kt:140-141; android/app-sr/.../SrDay.kt:619; SrApp.kt:269; android/app-amo/.../AmoDay.kt:212, MainActivity.kt:192). The only writer of that table is the local capture path (CaptureDao.kt:29, CaptureRepository.kt:60, AttendanceFlow.kt:144). `SrDay.dayOpen()` also reads only Room (SrDay.kt:621-622).
- **SR bundle carries no checked-in field.** `RouteDayStateDto` has route_id, business_date, state, planned, submit_cycle, submit_voided, submit_count_mismatch, logged_in_at, sales_submitted_at, final_submitted_at (backend/sync/.../BundleDtos.kt:232-243). All three builders select exactly those columns (BundleService.kt:460-469, DayApi.kt:279-291, IngestService.kt:1816-1824).
- **`logged_in_at` is not check-in.** It is the first bundle download or day_open (BundleService.kt:393, 445-446; DayStates.kt:93).
- **`app.route_day` has no checked_in_at column** (db/migrations/V0007__field_transactions.sql:296-325; the only later ALTERs are V0065:13-15). An SR check-in only sets `in_field_at` (DayStates.kt:95), which the first visit also sets (DayStates.kt:94) and which is not in the DTO.
- **A second phone's check-in is refused at sync.** The partial unique index `attendance_event_once (user_id, business_date, kind)` (V0007:399) maps to `ATTENDANCE_DUPLICATE` (RecordWriter.kt:148-150); test at backend/app/src/test/.../CheckoutWithoutSubmitTest.kt:59. So a local-only gate on phone two would force a second check-in that the server rejects.

Refinements:
1. **AMO/TSO: the server does expose it, the phone does not use it.** `SupervisorDayStateDto.checked_in` exists (DayApi.kt:51), is read from `app.supervisor_day.checked_in_at` (DayApi.kt:189-192; V0007:335; set at DayStates.kt:117), and is carried in the supervisor bundle (SupervisorBundle.kt:33) and the batch response (BatchDtos.kt:74). A grep of android/ non-test sources for `checked_in`, `supervisor_day`, `SupervisorDayState` and `DayStateListDto` found no consumer; the only `supervisor_day` hits are the day_submit scope string (AmoSaleKit.kt:288-300, CaptureRepository.kt:340, RouteDayLock.kt:70). For supervisors the gap is client-side only.
2. **SR `state` is an ambiguous proxy.** `in_field` or later hints at a check-in, but a visit sets it too (DayStates.kt:21, 94-95), and it gives no check-in time.

Not determined:
- Whether app-tso wires attendance the same way; I only read the SR and AMO wiring.
- Whether any Android code parses `RouteDayStateDto.state` into the attendance gate; none turned up in the greps, but I did not read the SyncEngine bundle-apply code line by line.
- The request's "route_day.checked_in_at" style fix would also need `dw`-independent sourcing: `dw.agg_daily_route.checked_in_at` exists (V0108:12) but is a reporting aggregate, not served to the phone.

- Not refuted: the gap holds as stated at commit 4e0645556, but its impact is narrow.

- **Hard-coded timer:** `millisToNextBoundary()` at android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrDay.kt:147-153 uses the literal `val seventeen = 17L * 3_600_000L` (line 150) and picks 17:00 or Dhaka midnight (line 151). Its KDoc at line 146 says "next 17:00". It never reads the config.
- **Config is read elsewhere in the same class:** `configuredCheckoutMinutes()` at SrDay.kt:371 resolves `checkoutMinutes?.invoke() ?: deviceRuntime?.dayConfig?.checkoutEarliestMinutes ?: DayConfig.DEFAULT_CHECKOUT_MINUTES`. It feeds `checkoutAtLabel()` (SrDay.kt:369) and the attendance flow's `checkoutEarliestMinutes` (SrDay.kt:389-390).
- **The sync-side gate is also config-driven:** android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SessionModule.kt:94 passes `gateMinutes = { runtime.dayConfig.checkoutEarliestMinutes }`.
- **Only caller found:** android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrApp.kt:275-278, a `LaunchedEffect` that delays `day.millisToNextBoundary()` and then bumps `boundary` and `resumeTick`. The comment at SrApp.kt:273 also says "17:00 check-out gate".

This is a UI-refresh timer only. The check-out gate itself and its label use the configured time. If `cfg.day.checkout_earliest_time` is not 17:00, the Home state would not re-read at the configured time on its own; it would wait for a resume, a sync request (SrApp.kt:262), 17:00 or midnight.

Not determined:
- Whether any deployed bundle sets `cfg.day.checkout_earliest_time` to something other than 17:00.
- The value of `DayConfig.DEFAULT_CHECKOUT_MINUTES` (not opened; the comment at SrDay.kt:389 says 17:00).
- Whether AMO and TSO have an equivalent hard-coded boundary timer; the grep showed no `millisToNextBoundary` outside app-sr, but I did not inspect their refresh logic.

- Not refuted: the gap holds at 4e0645556. One addition the gap statement misses: a config key for this already exists but nothing reads it.

Evidence (all at 4e0645556):
- backend/sync/src/main/kotlin/com/aktcl/aron/backend/sync/DayStates.kt:94 — a `visit` sets `in_field_at = COALESCE(in_field_at, :at)` with no check-in condition; line 95 does the same for a check-in. The header comment at DayStates.kt:21 states `in_field` is "the first check-in attendance_event or the first visit".
- backend/sync/src/main/kotlin/com/aktcl/aron/backend/sync/IngestService.kt:916 — the only attendance-specific ingest check is `checkout_too_early` (flag only). Lines 1001-1010 and 1950 (`GATED_TYPES = attendance_event, memo, memo_void, due_collection`) are the device-enrolment/integrity gate, not a check-in gate; `visit` and `stock_movement` are not even in that set.
- `git grep attendance_event` over backend/sync/src/main shows no query of app.attendance_event when a visit, memo, stock_movement or due_collection is ingested (hits only at DayStates.kt:21,47,57,95,117,118; IngestService.kt:720,916,1950,1963; PayloadShapes.kt:18; RecordWriter.kt:150; TypeRules.kt:21).
- contract/slices/schemas/RecordOutcomeCode.yaml — no "not checked in" outcome code (attendance codes are only `attendance_duplicate`, `checkout_too_early`).
- contract/slices/schemas/RiskSignalCode.yaml:10-31 — no missing-check-in signal (`CHECKOUT_WITHOUT_SUBMIT` is the only day-flow code).
- db migrations: the only attendance trigger found is `attendance_event_immutable` (db/migrations/V0007__field_transactions.sql:395); no DB-level check-in constraint on other tables.

Addition: `cfg.day.checkin_gate` (enum off/soft/hard, default `soft`) is registered at db/migrations/V0006__cfg_key_registry.sql:64 and docs/24-build-spec.md:930, but `git grep -i "checkin_gate|checkinGate"` over the whole tree returns only that migration, DECISIONS.md:383 and docs — no reader in backend/ or android/. The recorded decision D-329 (DECISIONS.md:383) says "a missed check-in does not block selling", marked MUST-CONFIRM; docs/19-admin-config.md:504 defines `hard` as blocking Stock and Sale until check-in. The owner's ruling therefore overturns D-329 and needs the default changed to `hard` plus actual enforcement.

Design caution for the fix: under offline-first, a check-in and the day's sales can arrive in separate or out-of-order batches, so a hard server reject of a memo with no check-in yet on the server could drop a genuine sale. Server-side flag or quarantine-with-release (as the device gate at IngestService.kt:1001-1010 does) fits the non-negotiables better than reject; the hard block belongs on the phone.

Not determined: whether the Android apps gate locally by some other name (I searched only the `checkin_gate` identifier in android/, not the feature code); whether analytics treats no-check-in days specially beyond the "successful login" definition (backend/analytics/.../DashboardSummaries.kt:44), which is reporting, not a refusal or flag on the records.

- Not refuted: the gap holds at 4e0645556. All paths below are under android/, with DC = feature-dayclose/src/main/kotlin/com/aktcl/aron/feature/dayclose.

Evidence that the Sales Submit gate needs the network:
- DC/SalesSubmit.kt:137 blocks whenever pending + inFlight > 0.
- DC/RoomDayAdapters.kt:24-28 takes those counts from outbox.countInState, which is `SELECT COUNT(*) FROM outbox WHERE state = :state` with no date filter (core-database/src/main/kotlin/com/aktcl/aron/core/database/dao/OutboxDao.kt:23-24). The comment at RoomDayAdapters.kt:17-18 confirms it is the whole outbox, not just today's rows.
- DC/SalesSubmit.kt:143 blocks on any per-type row that does not match. CountRow.matches (SalesSubmit.kt:19-21) is true only when the server count is non-null and >= the device count, so a null server count (never synced) is a mismatch.
- The server counts come from the last sync response's server_totals and are null before the first answer (app-sr/src/main/kotlin/com/aktcl/aron/sr/SrSaleKit.kt:385-393; DC/SalesSubmitViewModel.kt:26-28,116).
- DC/SalesSubmitViewModel.kt:100 returns without queuing when gate.enabled is false; DC/SalesSubmitScreen.kt:175,246 disables the button the same way.

Net effect: a rep who sold offline always has pending memo rows, so Sales Submit cannot be made offline. Only the day_submit record itself is queued offline, after everything else has synced and the counts agree (SalesSubmit.kt:130-132; SalesSubmitScreen.kt:245 shows the offline label only when canSubmit is already true).

Related to owner ruling 3 (check-out auto-submits):
- Check-out does not submit anything. For SR and AMO it is refused unless a day_submit is already committed locally (feature-attendance/src/main/kotlin/com/aktcl/aron/feature/attendance/AttendanceFlow.kt:63-64,129-132).
- The only non-test caller of recordDaySubmit is RoomDaySubmitWriter.queue (DC/RoomDayAdapters.kt:155-162), and the only non-test caller of DaySubmitWriter.queue is SalesSubmitViewModel.submit (DC/SalesSubmitViewModel.kt:101).
- So today, with no network, check-out is transitively blocked too.

Corrections to the gap's wording:
- The compare uses this phone's per-device digest count when the server supplies one, otherwise the login total (SalesSubmit.kt:20).
- Rejected and quarantined rows do not block; they are carried as notes (SalesSubmit.kt:138-142).

Not determined:
- The SR app's AttendanceFlow wiring: my grep pattern matched only the AMO wiring (app-amo/src/main/kotlin/com/aktcl/aron/amo/AmoDay.kt:209,359). The SR gate is asserted only by the AttendanceFlow.kt:63 comment.
- Whether the backend rejects a day_submit that arrives with pending_count > 0 or before its memos: the backend grep printed no hits, and RoomDaySubmitWriter hardcodes pendingCount = 0 (RoomDayAdapters.kt:166).
- Nothing was built or run; this is from reading the code only.

- Not refuted: at 4e0645556 check-out is blocked until a Sales Submit exists and never writes one. Paths are under android/ and backend/ at that commit.

Evidence the block exists:
- feature-attendance/.../AttendanceFlow.kt:129-132 — at the press, the gate is re-read and a missing submit returns AttendanceResult.Ignored ("no Sales Submit, no check-out").
- AttendanceFlow.kt:196,200,203 — checkOutEnabled requires !needsSubmit, and needsSalesSubmit drives the screen's link to Sales Submit.
- AttendanceFlow.kt:63-64,78-79 — the class doc states the gate; it is null only for the TSO.
- AttendanceFlow.kt:102-108 — a failed gate read counts as not submitted, so check-out stays off.
- app-sr/.../SrDay.kt:395 — the SR gate is RoomDaySource(db).alreadySubmitted(businessDate()) || finalLocked(). A zone Final Submit also opens check-out, but that is still not a write.
- app-amo/.../AmoDay.kt:209 (daySubmitted() at :359) — the AMO gate is the same local read.

Evidence check-out never writes a day_submit:
- AttendanceFlow.kt:133,136-165 — capture() commits only the attendance event and its fix.
- core-database/.../CaptureRepository.kt:55-62 — recordAttendance inserts the fix, the attendance row and one attendance outbox row, nothing else.
- SrDay.kt:376-386 and AmoDay.kt:195-203 — the committers call recordAttendance, then only side effects and requestSync(CHECKOUT).
- The only non-test writer of day_submit is the manual Sales Submit screen: CaptureRepository.recordDaySubmit (:337-350) is called from RoomDaySubmitWriter.queue (feature-dayclose/.../RoomDayAdapters.kt:155-162), which is called from SalesSubmitViewModel.submit() (SalesSubmitViewModel.kt:95-103). Kit wiring is at SrSaleKit.kt:377-379 and AmoSaleKit.kt:289-300.
- The check-out handlers call only attendance.checkOut(): SrApp.kt:411 and app-amo MainActivity.kt:262.

Server side does not compensate:
- backend/sync/.../IngestService.kt:719-730 — a check-out with no effective day_submit only raises a CHECKOUT_WITHOUT_SUBMIT signal; the doc comment says "the phone gate is the rule".
- backend/sync/.../DayStates.kt:118 — a check_out only sets checked_out_at.
- backend/analytics/.../AggregationWorker.kt:230 and DashboardSummaries.kt:197-204 — the old midnight auto-submit stamp was removed.

Scope notes for the fix:
- SalesSubmitViewModel.submit() refuses when !s.gate.enabled (:100). An auto-submit at check-out has to decide what happens when that gate has blockers; I did not read the gate conditions.
- I did not open SrDay.finalLocked() or the TSO app's AttendanceFlow construction; the "TSO has no gate" statement rests on the doc comment at AttendanceFlow.kt:43,78.
- I did not check test or androidTest sources for tests that pin the current behaviour.

- Not refuted: the gap holds at commit 4e0645556. There is no combined day_submit + check-out write, and check-out is in fact blocked until a manual Sales Submit exists, which is the opposite of the owner's ruling (3).

Evidence (all paths under android/ at 4e0645556):
- core-database/src/main/kotlin/com/aktcl/aron/core/database/repo/CaptureRepository.kt:55-62 — recordAttendance is its own db.withTransaction: it inserts the fix, the attendance event and one attendance outbox row. Nothing about day_submit.
- Same file, 337-351 — recordDaySubmit is a separate db.withTransaction: it inserts the day_submit row and its outbox row. Nothing about attendance.
- core-database/.../dao/CaptureDao.kt:29 and :49 — insertAttendance and insertDaySubmit are each called from exactly one non-test place (CaptureRepository.kt:60 and :349), so no other transaction joins them.
- feature-dayclose/src/main/kotlin/com/aktcl/aron/feature/dayclose/RoomDayAdapters.kt:145-171 — the only non-test caller of recordDaySubmit is RoomDaySubmitWriter.queue (:162), wired at app-sr/.../SrSaleKit.kt:379 and app-amo/.../AmoSaleKit.kt:300 (the manual Sales Submit screen path).
- feature-attendance/src/main/kotlin/com/aktcl/aron/feature/attendance/AttendanceFlow.kt:124-134 — checkOutLocked returns AttendanceResult.Ignored when the Sales Submit gate is not true (:129-132, "no Sales Submit, no check-out"); otherwise it only calls capture("check_out", ...) (:133), which reaches committer.commit (:146).
- app-sr/src/main/kotlin/com/aktcl/aron/sr/SrDay.kt:373-398 — the SR committer calls only capture.recordAttendance (:376); the gate is salesSubmitted = alreadySubmitted(businessDate()) || finalLocked() (:395, D-NIGHT-CHECKOUT-01).
- app-amo/src/main/kotlin/com/aktcl/aron/amo/AmoDay.kt:195 and :209 — the AMO has the same shape: recordAttendance only, with a salesSubmitted gate.
- A grep for auto-submit / checkOutAndSubmit / submitAndCheckOut in android/ and backend/ (non-test) finds no device-side code. The only hits are backend analytics comments about the zone-level Final Submit (backend/analytics/.../AggregationWorker.kt:230, AutoFinalSubmitJob), which is not the rep's day_submit.

Refinement of the gap wording: the missing piece is larger than "no combined write". Today a rep who forgot to submit cannot check out at all (AttendanceFlow.kt:129-132). Meeting the ruling needs a new CaptureRepository method that writes day_submit (when absent for the route-day) and the check_out attendance event, each with its outbox row, in one Room transaction with day_submit ordered first, plus a change to the check-out gate.

Not determined:
- Whether the TSO app has a check-out to day_submit path (only app-tso SessionModule.kt:90 surfaced, a sync-jitter comment).
- Whether the backend accepts a check_out and a day_submit arriving in the same batch in either order (SrDay.kt:396-397 says the server accepts attendance and a late Sales Submit after the lock, but I did not read the backend code).
- The lines of AttendanceFlow.capture after :160.

- Not refuted, but the gap is only half a defect. The hard-coded pending_count matters once check-out auto-submits; device_counts including every outbox state is the design the server relies on and should stay.

All paths are at commit 4e0645556.

**What the code does today**
- `pendingCount = 0` is a literal in the only day_submit writer: android/feature-dayclose/src/main/kotlin/com/aktcl/aron/feature/dayclose/RoomDayAdapters.kt:166.
- `device_counts` is built from the `counts` argument (RoomDayAdapters.kt:158), which comes from `deviceCounts()` -> `OutboxDao.committedCounts` (RoomDayAdapters.kt:30-31). That query has no state filter: android/core-database/src/main/kotlin/com/aktcl/aron/core/database/dao/OutboxDao.kt:149-150.
- The literal 0 is truthful today because the writer is reached only through `SalesSubmitViewModel.submit()`, which returns unless `gate.enabled` (SalesSubmitViewModel.kt:95-101).
- The gate blocks whenever `pending + inFlight > 0` (SalesSubmit.kt:137), and those counts cover the whole outbox, not just the date (RoomDayAdapters.kt:17-18, 24-27).
- `RoomDaySubmitWriter` is constructed only in app-sr/SrSaleKit.kt:379 and app-amo/AmoSaleKit.kt:300. The server's own online submit path also writes `pending_count` 0 (backend/sync/.../DayApi.kt:304).

**Where the gap becomes real (ruling 3)**
- There is no auto-submit at check-out. Check-out is instead blocked until a Sales Submit is already stored: app-sr/SrDay.kt:394-395, with the AMO equivalent noted at app-amo/AmoDay.kt:208.
- An offline auto-submit at check-out must skip the Unsynced block at SalesSubmit.kt:137. From that point the literal 0 breaks the contract, which defines `pending_count` as "Rows of this route-day not yet acknowledged when the SR tapped submit" (contract/openapi.yaml:8928).
- The fix is to fill it from the existing `OutboxDao.unsentCounts(businessDate)` (OutboxDao.kt:152-154).
- `recordDaySubmit` only checks that the value is not negative (CaptureRepository.kt:343).

**Why the second half is not a defect**
- The server sets `rows_awaited` to the sum of `device_counts` (backend/sync/.../DayStates.kt:96, 101).
- It settles the submit only when its registry holds at least that many rows per type in accepted, voided, rejected or quarantined (DayStates.kt:159-164); otherwise the timeout settles it with `submit_count_mismatch` (DayStates.kt:165-168).
- So unsent rows must be in `device_counts`, or an offline submit would settle before the day's sales arrive. Filtering to acked rows would be wrong.

**Could not determine**
- Whether any server code reads `pending_count` beyond shape validation; I only found it in PayloadShapes.kt:26 and DayApi.kt:304.
- Whether the CountMismatch block (SalesSubmit.kt:143) also blocks an offline submit when server counts are null; I did not read `CountRow.matches`.
- Nothing was built or run; this is from reading the source only.

- Not refuted: at 4e0645556 the gap holds. Nothing on the SR submit path checks for an open visit, an open memo edit or an unprinted memo before the day_submit is queued.

Evidence (all at 4e0645556):
- **Gate:** `SalesSubmitRules.gate` (android/feature-dayclose/src/main/kotlin/com/aktcl/aron/feature/dayclose/SalesSubmit.kt:134-145) blocks only on AlreadySubmitted, Unsynced (pending + inFlight) and CountMismatch. The `SubmitBlock` sealed interface (SalesSubmit.kt:117-123) has no open-visit, open-edit or unprinted-memo variant.
- **View model:** `SalesSubmitViewModel.submit()` (android/feature-dayclose/.../SalesSubmitViewModel.kt:95-106) recomputes, checks `gate.enabled` and Idle progress, then queues. `recompute()` (108-136) reads outbox states, counts, dues and rejected rows only. `DaySource` (15-24) has no method for an open visit or print state.
- **Writer:** `RoomDaySubmitWriter.queue` (android/feature-dayclose/.../RoomDayAdapters.kt:155-171) records only `stockSlipPrinted` (the stock slip, not memos) as a payload field; it does not block on it.
- **Repository:** `CaptureRepository.recordDaySubmit` (android/core-database/.../repo/CaptureRepository.kt:337-351) checks uuid, scope, route, cycle and `requireOpenRouteDay`. It has no check for a visit without a `visit_close` or for a memo with `printed_at` null.
- **Wiring and navigation:** `SrSaleKit.newSubmitViewModel` (android/app-sr/.../SrSaleKit.kt:377-383) passes no visit session. `SubmitHost` (android/app-sr/.../SrSaleHosts.kt:311-328) and the home tile (android/app-sr/.../SrApp.kt:383, 512) open Sales Submit without reading `visitSession`. Home is reachable with a call open (SrApp.kt:378).
- **Consequence as claimed:** once the submit stands, the open call ends on the phone with a refusal and no `visit_close` is queued. See `SrDay.closeVisitAbandoned` / `openCallRefusal` / `endCallIfClosed` (android/app-sr/.../SrDay.kt:229-254), `SrCallLock.refusal` / `endedBy` (SrDay.kt:796-805), `SrSaleKit.closeVisit` (SrSaleKit.kt:197-206), and the text in `SrDaySubmittedDialog` (android/app-sr/.../SrDayLockGate.kt:96-101).
- **Search:** a grep of android/core-database, android/feature-dayclose and android/feature-attendance main sources for open-visit or unprinted-memo queries found only `PrintDao.markMemoPrinted` (android/core-database/.../dao/PrintDao.kt:38), which is an update, not a gate.

One nuance: an open call whose visit row is still pending or in flight in the outbox blocks submit indirectly through Unsynced (SalesSubmit.kt:137). That is a sync check, not an open-visit check, and it clears once the visit is acknowledged.

Not determined:
- Whether an in-progress memo edit draft is persisted anywhere; I only confirmed that nothing on the submit path reads one.
- The AMO submit path (AmoSaleHosts.kt:210) and the server side (backend/) were not inspected for an equivalent check.
- Whether any check-out code path calls submit automatically was not examined here.

- Not refuted: the gap holds at 4e0645556, with one precision to add.

Evidence (all at 4e0645556):
- The only server job that closes a day is the zone-level midnight job. backend/sync/src/main/kotlin/com/aktcl/aron/backend/sync/AutoFinalSubmitJob.kt:83-84 closes only dates before today; :91-92 reads cfg.day.auto_final_submit_enabled at the zone's wing and skips the zone when it is not true (default false in code).
- The switch ships false: db/migrations/V0134__tso_manual_config.sql:14 ("auto-close off until confirmed") and :42 (default 'false'). No later migration or code turns it on (git grep of the key finds only AutoFinalSubmitJob.kt:40,183 and V0134:14,42).
- What the job writes when on (DayService.closeLocked, backend/sync/.../DayApi.kt:462-501): one app.final_submit row with via='job' and submitted_by NULL (:467-473), missing app.route_day rows (:478-481), route_day.final_submitted_at for the zone's routes (:486-492), state advance and dirty marks (:493), and a zone_day.final_submitted event (:494). It inserts no app.route_day_event (day_submit) and no attendance check-out. DayApi.kt:218 states the job "only closes zone-days" and has no ingest (AutoFinalSubmitJob.kt:63, ingest = null).
- A rep's route-day counts as uploaded only from rd.sales_submitted_at (DayApi.kt:359), which is set only by a stored day_submit record (DayStates.kt:23-25, :119). So after a job close a rep who never submitted stays "not uploaded" (warnings at DayApi.kt:395-399).
- Other worker jobs started in backend/app/.../Main.kt:89-95 are bundle pre-generation, geo re-check sweep, retention, outlet-request lapse, inbox prune and AutoFinalSubmitJob; the log line at :96 also names aggregation, risk signals and route-day planning. None is a per-rep day close.
- The server only detects the "checked out without submit" case: IngestService.kt:720-721 and :732-762 raise a CHECKOUT_WITHOUT_SUBMIT risk signal and leave the check-out unchanged (:729-730 "the phone gate is the rule"). It writes no day_submit.
- The phone has no automatic submit either: day_submit is written only by RoomDaySubmitWriter (android/feature-dayclose/.../RoomDayAdapters.kt:144-162), built in SrSaleKit.kt:379 and AmoSaleKit.kt:300 for the Sales Submit screen (SalesSubmitViewModel.kt:40). The CoroutineWorkers in android/ are media, content assets, sync, push pull and support upload (MediaWork.kt:114, ContentAssets.kt:76, SyncWork.kt:363, PushPull.kt:90, SupportUpload.kt:155); none queues a day_submit or check-out.

Precision: when the switch is on, the job does close the rep's route-day at zone level (route_day.final_submitted_at, state final_submitted, DayApi.kt:486-493), so "nothing closes the rep's day" is exact only with the switch off, which is the shipped state. In both states no day_submit and no check-out is written for the rep. AMO and TSO days are supervisor_day rows (DayStates.kt:27) and the job touches only route_day, so they are never closed by it.

Not determined: whether any live environment has a config override turning the switch on (database data, not in the repository); the bodies of RiskSignalJob, RouteDayPlanningJob and RetentionJob were not read line by line (a grep for inserts or updates of final_submit, route_day_event or attendance in them found none); the phone's check-out screen logic was not read, so whether check-out on the phone is blocked until submit is not confirmed here.

- Not refuted: the gap holds at 4e0645556. Two nuances to add: yesterday's captures themselves still upload, and the server closes the zone-day at midnight, but no SR Sales Submit or check-out for yesterday can ever be produced on the phone.

Evidence (all paths under android/ unless noted):

- **Single date source.** `SrDay.businessDate()` is `BusinessDate.of(clock.nowMs())` (app-sr/src/main/kotlin/com/aktcl/aron/sr/SrDay.kt:145). No function in SrDay takes another date.
- **Pending-submit check is today only.** `salesSubmitPending()` reads `businessDate()` (SrDay.kt:334-339). `dayOpen()` likewise (SrDay.kt:622). The Home state uses `day.businessDate()` (SrHomeState.kt:108-110).
- **Check-out gate is today only.** `salesSubmitted = { RoomDaySource(db).alreadySubmitted(businessDate()) || finalLocked() }` (SrDay.kt:395); `finalLocked()` also uses `businessDate()` (SrDay.kt:211).
- **Attendance restore is today only.** `attendanceToday()` is `attendanceOn(businessDate())` (SrDay.kt:619), fed to `attendance.restore(...)` at app start (SrApp.kt:269). `restore` only sees that list (feature-attendance/.../AttendanceFlow.kt:87-91). The next morning `checkedInAt` is null, so `checkOutLocked()` returns Ignored (AttendanceFlow.kt:126). Yesterday's open check-in cannot be closed.
- **Any event written is stamped with now.** `metaProvider` sets `businessDate = BusinessDate.of(now)` (SrDay.kt:348-356), and `AttendanceFlow.capture` uses it (AttendanceFlow.kt:142).
- **Sales Submit screen is today only.** `newSubmitViewModel()` passes `day::businessDate` (SrSaleKit.kt:377-379). `submit()` calls `writer.queue(businessDate(), ...)` (feature-dayclose/.../SalesSubmitViewModel.kt:95-104, 108-109). `RoomDaySubmitWriter` takes its meta from `day.metaProvider.meta(0L)` (feature-dayclose/.../RoomDayAdapters.kt:155-170). `SubmitHost` passes `today = day.businessDate()` (SrSaleHosts.kt:311-327). There is no date picker or earlier-day path.
- **No auto-submit on the phone.** The attendance committer only records the event, runs device-policy hooks and requests a sync (SrDay.kt:375-387). Check-out is refused without a submit, never auto-submitted (AttendanceFlow.kt:128-132). A grep for auto-submit, yesterday, previous day and unsubmitted across app-sr, feature-dayclose, feature-attendance and core-sync main sources found nothing relevant.
- **Server does not fill the gap.** `AutoFinalSubmitJob` is started in backend/app/src/main/kotlin/com/aktcl/aron/backend/app/Main.kt:95 and writes the zone-level Final Submit only. backend/sync/src/main/kotlin/com/aktcl/aron/backend/sync/DayApi.kt:218 says "Online Sales Submit only; null for the midnight AutoFinalSubmitJob, which only closes zone-days". It does not create an SR `day_submit` or a check-out.

Nuance on "stay un-submitted": the captures (memos, visits) are outbox rows and upload regardless of date; `SyncEngine.kt:688` reads `outbox.earliestBusinessDate()`. What is missing is yesterday's `day_submit` record and yesterday's `check_out` event.

Not determined:
- The AMO and TSO apps were not read; only the SR path was checked.
- Whether the server would accept a `day_submit` or `check_out` carrying yesterday's business date if the phone sent one. DayApi.kt:218, AutoFinalSubmitJob.kt and LateAfterFinalTest were not read in full.
- The exact body of `RoomDaySource.alreadySubmitted` beyond RoomDayAdapters.kt:49.

- Gap holds (not refuted) at 4e0645556, with one precision: a reopen DOES exist on the server, but it is the Final Submit (zone-day) reopen, not a Sales Submit void.

Server:
- backend/sync/.../DayApi.kt:158 states it outright: "Submit void, reopen, cover and exceptions are dropped rows". Routes registered are only /day/sales-submit (161), /day/final-submit/preview (165), /day/final-submit (173) and /day/reopen (178).
- /day/reopen (DayApi.kt:565-641) is the audited admin reopen of a final-submitted ZONE-day (D-TSO-MAN-29): it sets final_submit.reopened_at (625-626) and calls DayStates.reopen, which only clears final_submitted_at and recomputes state (DayStates.kt:204-213). It does not clear sales_submitted_at, does not set submit_voided, does not bump/void a submit cycle; with sales_submitted_at still set the route-day goes back to 'sales_submitted' (DayStates.kt:191).
- No server code ever writes submit_voided: "git grep 'submit_voided\s*='" over backend/ and db/ hits only a DTO named argument (BundleService.kt:468). The column exists with default false (db/migrations/V0007__field_transactions.sql:315, 340) and is only read (DayApi.kt:282/288/359, DayStates.kt:152/192, IngestService.kt:746/1816, RouteDayPlanning.kt:110, BundleService.kt:460, OpsApi.kt:248). All UPDATEs of app.route_day / app.supervisor_day in backend main (BundleService.kt:409-445, DayApi.kt:488, DayStates.kt:80/103/123/138/167/178/208) never touch submit_voided; day_submit ingest only sets submit_received_at/submit_cycle (DayStates.kt:96-97, 119-120). DataVoidApi (backend/masterdata/.../DataVoidApi.kt:141-157) voids data rows and writes a route_day_void_barrier, not a submit void.

Phone:
- CaptureRepository.reopenRouteDay is defined at android/core-database/.../repo/CaptureRepository.kt:438 (delegating to RouteDayLock.reopen, RouteDayLock.kt:81-82). Its only callers are tests: android/core-database/src/test/.../DayRecordsTest.kt:91 and :110. No call in any main source set of android/ (grep for reopenRouteDay / RouteDayLock.reopen).
- No main-source Android code reads submit_voided / submitVoided at all (grep over android/ excluding test and androidTest returns nothing), so even if the server set the flag, nothing on the phone would write the "route_day.reopened.<date>.<route>" meta key (RouteDayLock.kt:84) that RouteDayLock.isOpen/submitStands compare against (RouteDayLock.kt:65-66, 71-72, 76-79). ReferenceRepository.kt:296 mentions a submit void only in a comment about delta cursors.

Consequence for the owner's rule (3): once a Sales Submit (manual or an automatic one at check-out) is stored on the phone, the route-day is locked for captures (CaptureRepository.requireOpenRouteDay, CaptureRepository.kt:457; RouteDayLock.requireOpen RouteDayLock.kt:45) and there is no built path, server or phone, to undo that Sales Submit. The Final Submit reopen clears only the Final Submit lock (the phone tests SrLockFixture.kt:173-176 / AmoLockFixture.kt:175-178 model that by day_state going back to 'open').

Not determined: whether the contract/OpenAPI source outside backend/ and android/ declares a submit-void endpoint (not searched beyond backend, android, db); whether the web portal offers a UI for /day/reopen; runtime behaviour (nothing was built or run, read-only review).

- Not refuted: the gap holds at 4e0645556. Evidence (all paths at that commit):

1. No server gate on Sales Submit. The only day lock in ingest is dayLockVerdict, backend/sync/src/main/kotlin/com/aktcl/aron/backend/sync/IngestService.kt:1219-1251. It reads only route_day.final_submitted_at (line 1237), i.e. the TSO/midnight zone-day Final Submit, not the rep's Sales Submit. It also returns null straight away under the shipped default policy accept_and_flag (line 1235; default confirmed in docs/24-build-spec.md:504).

2. sales_submitted_at / submit_received_at are never used to accept or refuse a record. In backend/sync main code outside DayStates/DayApi/Bundle*, the only references are the read-back in IngestService.kt:1816-1824 (dayStates DTO) and the settle job in RouteDayPlanning.kt:108-110. DayStates.afterStored (DayStates.kt:43-109) only stamps timestamps on day_submit (lines 96-97); for a memo arriving later it does nothing with the submit state, and the late-row counter is conditioned on final_submitted_at IS NOT NULL only (DayStates.kt:79-90).

3. No outcome code exists for it. contract/slices/schemas/RecordOutcomeCode.yaml has after_final_submit and day_final_submitted (Final Submit only); nothing for "after sales submit". docs/24-build-spec.md:430 describes edit_not_allowed as covering "edit after Sales Submit", but the only server emitter is DuesLedger.kt:39, which checks memo status (active/voided/superseded), not the route-day's submit state. So even memo edits after a Sales Submit are not refused on that ground.

4. The spec itself puts the lock on the phone only: docs/24-build-spec.md:502 "After it the phone locks capture for that route-day except dues collection and printing."

5. The one server reaction near this area is the opposite case: checkoutWithoutSubmit (IngestService.kt:725-763) raises a CHECKOUT_WITHOUT_SUBMIT risk signal when a check-out arrives with no day_submit; the check-out is stored unchanged ("the phone gate is the rule", line 729).

Corrections / nuance to the gap statement:
- The cited line 1816 is right (state only read back). Add IngestService.kt:1235-1237 and DayStates.kt:79-97 as the places where the missing check would sit.
- Any fix must respect existing design: dues collection and printing stay allowed after Sales Submit (docs/24:502); submit void reopens the day and the next submit carries submit_cycle+1 (docs/24:503), so "last record" must be per effective, non-voided cycle; and offline ordering means a memo captured BEFORE the submit can legitimately arrive after it (the settle logic with device_counts/rows_awaited, DayStates.kt:96-103, exists for that). A server rule would have to compare captured_at with the submit's capture time, not arrival order, and should hold rather than drop a sale.

Not determined: I did not verify the phone-side lock (android/), nor whether a new auto-submit-at-check-out could itself produce memos sequenced after the day_submit. I searched backend/sync main sources and the contract; I did not read the test sources or other backend modules for a gate, though the grep over backend/ and contract/ for day_submit/sales_submitted_at showed none.

- Not refuted: the gap holds as stated at 4e0645556, but the server tolerates same-cycle duplicates deterministically, so it is less harmful than the wording implies.

**Confirmed**
- **No server uniqueness.** `app.route_day_event` has only `client_uuid uuid NOT NULL UNIQUE` (db/migrations/V0007__field_transactions.sql:404) and `external_ref ... UNIQUE` (:424). `submit_cycle` carries only a range check, `CHECK (submit_cycle BETWEEN 1 AND 50)` (:432). The only index is on `business_date` (:445). No later migration adds a unique index on this table: a grep of db/ for it outside V0007 returns only the V0016 comments (V0016:1970-2000).
- **Cycle is computed from local rows.** The phone takes the highest cycle among its own Room `day_submit` rows for that date and route and adds 1 (android/feature-dayclose/.../RoomDayAdapters.kt:157), with a fresh `newUuid()` per submit (:164).
- **The only duplicate-cycle guard is on the phone.** `CaptureRepository.kt:345-346` checks the same local Room table and nothing else.
- **The server never rejects a repeated cycle.** `DayApi.salesSubmit` validates only `submit_cycle in 1..50` (backend/sync/.../DayApi.kt:237) and is idempotent by `client_uuid` (:240, :259). Ingest folds the cycle in with `submit_cycle = GREATEST(submit_cycle, :cycle)` for the route-day (DayStates.kt:96-97) and the supervisor-day (:119-120). `submit_cycle` does not appear in IngestService.kt main code.

**Mitigations the gap statement omits**
- A second `day_submit` for the same (route, date, scope, cycle) under a different `client_uuid` is stored as an extra row, not rejected. This happens after a reinstall or wiped Room database, on a second phone, or with a cover rep.
- The server then picks the effective submit deterministically: `ORDER BY submit_cycle DESC, id DESC LIMIT 1` (DayStates.kt:156), so the later-stored row's device counts win.
- `sales_submitted_at` and `submit_received_at` are set with `COALESCE`, so they are not moved by the duplicate (DayStates.kt:96, :119).
- A retried upload of the same record is safe by `client_uuid`.

**Relevance to the owner's rule 3**
An auto-submit at check-out would go through this same writer. On a phone whose local `day_submit` rows are missing, or whose manual submit came from another device, it would emit cycle 1 again under a new UUID and the server would accept it as a second row.

**Not determined**
- Whether the `ingest_registry` or `RecordWriter` path applies a semantic duplicate check for `day_submit` beyond `client_uuid`. I found none by grep but did not read IngestService.kt in full.
- Whether the bundle's `day_states.submit_cycle` is ever used to seed the phone's next cycle. `RouteDayLock.kt:64-78` only compares it for lock state; line 157 does not read it.
- No check-out auto-submit code path was looked for in this pass.

- NOT refuted: the gap holds, and it sits on a larger one. At 4e0645556 check-out does not submit anything; it is refused until a Sales Submit already exists. All paths are under the ref; nothing was built or run, this is from reading only.

Larger gap (ruling 3 is the opposite of today's code):
- android/feature-attendance/.../AttendanceFlow.kt:129-131: `checkOutLocked` re-reads `salesSubmitted` and returns `Ignored` when it is not true ("no Sales Submit, no check-out"). Lines 196 and 200 keep the check-out button disabled meanwhile.
- android/app-sr/.../SrDay.kt:395 wires that gate for the SR; android/app-amo/.../AmoDay.kt:209 for the AMO.
- The only caller of `recordDaySubmit` is `RoomDaySubmitWriter.queue` (android/feature-dayclose/.../RoomDayAdapters.kt:162), reached only from `SalesSubmitViewModel.submit()` (SalesSubmitViewModel.kt:94-103), which is a button press.
- A grep for auto-submit in android/ and backend/ found only the zone-level final-submit job (backend/analytics/.../AggregationWorker.kt:230), nothing for check-out.

The claimed gap, confirmed point by point:
1. A route-day submit needs a route. android/core-database/.../CaptureRepository.kt:341 calls `requireRoute(submit.meta)` for scope `route_day`; lines 470-472 throw when `meta.routeId == null`.
2. The check-out meta can carry no route. AttendanceFlow.kt:142 sets `routeId = null` when `routeIdOf()` is null. For the SR that is `SrDay.routeId` (SrDay.kt:388), a nullable field (SrDay.kt:156) that is null when the bundle has no route (SrDay.kt:176-177). So the check-out event's meta cannot be reused for a day_submit as it stands.
3. One check-out, several route-days.
   - The SR writer builds its meta with `metaProvider.meta(0L)` (android/app-sr/.../SrSaleKit.kt:379), which resolves to the single currently chosen route (SrDay.kt:353). The chosen route is one preference per date (SrDay.kt:215-216).
   - The submit cycle is counted per route (RoomDayAdapters.kt:157; CaptureDao.kt:96-97).
   - The server keeps one row per route and date (backend/sync/.../DayStates.kt:28). A day_submit updates only the route in its envelope (DayStates.kt:54, 96-106). A route-less check-in fans out to every assigned route (DayStates.kt:55-57), but a day_submit has no such fan-out and without a route falls to `emptyList()` (DayStates.kt:58).

Related defects the fix must cover:
- `RouteDayLock.submitStands` is true when any one route's submit stands (android/core-database/.../RouteDayLock.kt:76-79). A two-route rep who submits route A can already check out with route B never submitted.
- The submit payload is per date, not per route: `MoneyTotalsJson.build(db, businessDate, ...)` (RoomDayAdapters.kt:116-141) and the counts in SalesSubmitViewModel.kt:117-119. An auto-submit per route would send whole-day counts in each route's record unless they are split.
- `recordDaySubmit` runs `requireOpenRouteDay` (CaptureRepository.kt:348), so auto-submit must skip routes already submitted and not reopened.
- The AMO path uses scope `supervisor_day` with a null route (android/app-amo/.../AmoSaleKit.kt:300), so the route problem is SR-only.

Fix direction: at check-out, in the same Room transaction as the attendance event, enumerate the day's route-days from stored rows (distinct `route_id` of the date's visits, memos and dues, plus the chosen route) and queue one `route_day` day_submit per route that has no standing submit. Each needs its own client UUID and per-route cycle, and must be ordered before the check-out record. The `salesSubmitted` gate has to be dropped or inverted.

Not determined:
- How server `settle` treats whole-date device counts against a per-route row (DayStates.kt:148 onward was only partly read).
- Whether the server refuses a route-less `route_day` day_submit at ingest (IngestService and TypeRules validation not read).
- Whether the SR UI lets a rep change the chosen route mid-day after the first pick (RoutePicker in SrApp.kt:298, 352 not read in full).
- TSO app behaviour (it has no `salesSubmitted` gate per AttendanceFlow.kt:78; not checked further).

- Gap NOT refuted: it holds at 4e0645556, with one path correction (AttendanceFlow.kt is in feature-attendance, not feature-dayclose).

Evidence (all paths at ref 4e0645556):

1. Manual Sales Submit has no time gate.
- android/feature-dayclose/src/main/kotlin/com/aktcl/aron/feature/dayclose/SalesSubmit.kt:134-145 — SalesSubmitRules.gate() takes only (states, counts, dues, alreadySubmitted). Blocks are AlreadySubmitted, Unsynced (pending+inFlight > 0) and CountMismatch; there is no clock input.
- android/feature-dayclose/src/main/kotlin/com/aktcl/aron/feature/dayclose/SalesSubmitViewModel.kt:95-106 — submit() checks only gate.enabled and progress == Idle, then writer.queue(). The constructor (66-77) has no clock or checkout-time dependency.
- A grep of android/feature-dayclose/src/main for minute|checkout|earliest|LocalTime|dhaka finds only display strings (SalesSubmitScreen.kt:157, 236-237, 336; strings.xml:54 "Check out after 5 PM").
- android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrSaleHosts.kt:311-327 — SubmitHost passes no time condition.
- android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrApp.kt:383 — the Home tile opens SrScreen.SUBMIT unconditionally.
- android/feature-home/src/main/kotlin/com/aktcl/aron/feature/home/HomeScreen.kt:527 — SELLING_TILES disables the tile only when the bundle cannot sell.
- android/feature-home/src/main/kotlin/com/aktcl/aron/feature/home/HomeModel.kt:114 — a SUBMITTED state before check-out time exists by design; line 115 (routeDone || afterCheckoutTime) only drives the dock hint, not a gate.
- android/core-database/src/main/kotlin/com/aktcl/aron/core/database/repo/CaptureRepository.kt:337-351 — recordDaySubmit has no time check.
- Server: backend/sync/src/main/kotlin/com/aktcl/aron/backend/sync/IngestService.kt:916 — the too-early check applies only to attendance_event with kind check_out, not to day_submit. No day_submit-too-early code was found in backend main sources.

2. Check-out is time-gated at 17:00 (configurable).
- android/feature-attendance/src/main/kotlin/com/aktcl/aron/feature/attendance/AttendanceFlow.kt:127 — `if (dhakaMinutesNow() < checkoutEarliestMinutes()) return AttendanceResult.Ignored`. Default is 17*60 at line 74; line 200 gates the button the same way.
- android/core-sync/src/main/kotlin/com/aktcl/aron/core/sync/device/DayConfig.kt:75, 96 — the value comes from cfg.day.checkout_earliest_time, default 17:00.

3. The device lock after a submit is immediate and local.
- CaptureRepository.kt:348-350 — recordDaySubmit inserts the day_submit row and its outbox row in one transaction.
- android/core-database/src/main/kotlin/com/aktcl/aron/core/database/repo/RouteDayLock.kt:63-67 — routeOpen() is false as soon as a day_submit cycle greater than the reopened cycle exists locally; no server ack and no time condition. Lines 57-60 turn that into DayRefusal.SALES_SUBMIT for later captures.

Related facts bearing on owner ruling (3):
- Check-out today REQUIRES a prior manual Sales Submit rather than performing one. AttendanceFlow.kt:129-132: with salesSubmitted wired, check-out is Ignored unless a local day_submit exists. Wired for SR at android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrDay.kt:395 and for AMO at android/app-amo/src/main/kotlin/com/aktcl/aron/amo/AmoDay.kt:209.
- No auto-submit on check-out exists. checkOutLocked() (AttendanceFlow.kt:124-134) only captures the attendance event.
- The manual gate needs every row acknowledged and device counts ≤ server counts (SalesSubmit.kt:137, 143; CountRow.matches at 21). A submit therefore cannot be queued fully offline, so an auto-submit at check-out that reused this gate would put the network in the check-out path.

Not determined:
- Whether the TSO app has any Sales Submit path. Only SR and AMO hosts were found; AttendanceFlow.kt:78 says the gate is null for TSO.
- AMO's submit host (android/app-amo/.../AmoSaleHosts.kt around line 198) was not read line by line. It uses the same SalesSubmitViewModel/RoomDaySubmitWriter (android/app-amo/.../AmoSaleKit.kt:300), so the same absence of a time gate is inferred, not confirmed.
- Backend handling of a day_submit captured before 17:00 was checked by grep only, not by reading the full day_submit ingest path.

- Not refuted: the code at 4e0645556 matches the claim. One qualification on whether it is a real gap, see the last paragraph.

Evidence:
- **Final Submit is online-only with no queue.** android/feature-tso/src/main/kotlin/com/aktcl/aron/feature/tso/finalsubmit/FinalSubmit.kt:167 says "Online only by design (docs/24): there is no queue", and the interface follows at :168-175. The only implementation is android/app-tso/src/main/kotlin/com/aktcl/aron/tso/TsoReadModels.kt:508-549: a direct POST to /v1/day/final-submit (:537-541), with no outbox write. A transport error maps to FsFailure.OFFLINE (:511) and the screen ends in FsPhase.Failed (FinalSubmit.kt:328); nothing is persisted for retry.
- **The submit is idempotent on manual retry only.** The client uuid is deterministic from user, zone, date and submit_seq (FinalSubmit.kt:182-185), so a repeated tap replays safely, but nothing retries automatically.
- **TSO has no attendance or check-out.** AttendanceFlow is constructed only at android/app-sr/src/main/kotlin/com/aktcl/aron/sr/SrDay.kt:373 and android/app-amo/src/main/kotlin/com/aktcl/aron/amo/AmoDay.kt:192. android/app-tso/build.gradle.kts:98-133 and android/feature-tso/build.gradle.kts:16-19 have no feature-attendance dependency. A case-insensitive grep for attendance, check-in and check-out across the app-tso and feature-tso main sources returns only the sync-scheduler wiring at android/app-tso/src/main/kotlin/com/aktcl/aron/tso/SessionModule.kt:90-94 (CheckoutGate and checkoutJitterS, upload jitter timing) and an unrelated string at android/feature-tso/src/main/res/values/strings.xml:200.

Qualification: TSO Final Submit is a different operation from the rep's Sales Submit. It closes a zone-day across all routes after a server preview (FinalSubmit.kt:213-218) and is not the TSO's own sales. The app-tso module list (build.gradle.kts:98-133) shows no sale or stock feature module, so the TSO app appears to have no own-sales to auto-submit. Rule 3 as the owner worded it (check-out submits the rep's forgotten sales) therefore has nothing to attach to in the TSO app, and whether the TSO needs any equivalent is a product decision rather than a clear defect.

Not determined: whether the backend has TSO attendance endpoints or auto-finalises a zone-day (backend not inspected beyond one grep that returned nothing); the content of docs/24 behind the "online only by design" comment; and whether the owner's rule 1 (check-in gate) is meant to cover TSO.
