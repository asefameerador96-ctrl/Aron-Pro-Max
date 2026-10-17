# Status: lane android-amo (wave 2, started 2026-10-17)

## HANDOVER (READY TO RECYCLE, 2026-10-17, session 1 of the lane)
**State:** all on `lane/android-amo` (merged with INT; local Gradle works online: `echo sdk.dir=/opt/android-sdk > local.properties`; `./gradlew :android:feature-amo:testDebugUnitTest :android:app-amo:compileDebugKotlin :android:core-database:testDebugUnitTest -q 2>&1 | grep -v JAVA_TOOL`). CI on the lane head has not been looked at yet: check it first.

**Done (see the csv):** F-AMO-001, 003, 004, 005, 007, 008, 009, 011, 036; plus the shared AMO record store. Checkers: Sonnet on 001 and 003-036 (fixes in), Opus T1 on the record store (3 of 4 findings fixed). Sonnet checker ran on 007/008/009/011: one defect (Joint Call SR after relaunch), fixed. Known minor: closeVisit records `abandoned` with zero sale after a saved assessment.

**Architecture decisions**
- `app-amo/AmoDay.kt` is the thin AMO twin of `app-sr/SrDay.kt` (capture, reference, meta provider, fix source, attendance, visit flow, Force Sale = Manual Override, media, AMO records). SrDay/SrSaleKit/SrSaleHosts/MediaShell stay in app-sr (MediaShell was copied into app-amo). Reusing the SR *sale* needs SrSaleKit (app-sr): at F-AMO-006 either copy the wiring into app-amo or extract a shared module (SR lanes are idle; an extraction moves the sr_* string resources too, so prefer copying `SrSaleKit`/`RoomSaleReads` into app-amo with AMO strings).
- Real screens plug into `AmoApp(screens = mapOf(AmoScreen.X to { back -> ... }))` built in `app-amo/MainActivity.kt` once `AmoDay` exists. A screen not in the map shows the placeholder.
- AMO-only records go through `CaptureRepository.recordAmo(AmoRecordEntity)` (table `amo_record`, Room v6) with payloads from `AmoPayloads` (checked against the contract by `AmoRecordsTest`); family/rank/route rules are enforced there (docs/24 s4). Types: distribution_check(+line), call_assessment(+answer), price_compliance_check, risk_review, outlet_request_verification, task. `day_submit` with scope `supervisor_day` already exists (DaySubmitEntity).
- Zone routes/outlets: `reference.routesOfDay`; brands: `section("products")` nodes level=brand; rubrics: `section("rubrics")`; team and pending requests: `section("supervisor")`.
- F-AMO-001: the "17/13" counts destinations (Settings and Astha included); Astha deferred so 16 for a full AMO, 13 for SS (no Team Location/Performance/SR Stock). SS = zero SRs in `team` (MQ-19 assumed). Open: pending badge counts any other requester, not only SRs.
- Manual Override daily cap: default 10 (ASSUMED, MQ-14), read from `cfg.amo.manual_override_max_per_day` if the registry ever has it (a request file for the key is still to be written).

**Next three rows:** F-AMO-043 (AMO Survey: reuse the SR POSM survey component without points) then F-AMO-010 (menu entry; `ControlCallMenu(onSurvey=...)`), F-AMO-035 (price compliance, uses `AmoPayloads.priceCompliance`), then Day-4 list: F-AMO-013/014/015/029/049/030 (sale/memo/summary/stock: need the sale wiring decision above), 018/019 (tasks: `AmoPayloads.task`; needs F-API-026 client), 022-027 and 028 (outlet verification and own requests: `AmoPayloads.outletRequestVerification`, `OutletRequests`, photo pipeline is in AmoDay), 038 (Exceptions: `risk_review`), N-053 (map; `core-map` exists), then Day-5 report/dashboard/team rows that need an online client (none exists for team/dashboard/reports in core-network; write `AmoApi` over `AronApiClient.call`, cache the last answer in `bundle_section`-like storage for the offline fallback).

**Traps / open**
- Replay of a purged UUID is queued again for every capture type (server dedupes); ignored test `CheckerAmoRecordsTest.aReplayAfterPurgeDoesNotEnqueueAgain`. android-core to decide.
- `AmoDay` has no `onBlocked` auto-close and no `configCheck` (SrDay has them); a mock-blocked AMO call stays open until its menu Back (which closes it).
- Photos: `MediaShell` copy + CAMERA permission are in app-amo; `attachMedia` runs when the day opens.
- No Android emulator or phone here: every `checked on the Galaxy A06` acceptance is DEVICE-PENDING, none filed in `docs/status/device-checks.md` yet (D-AMO-ATT, D-AMO-CC: control call in airplane mode, Manual Override photo, assessment, joint call, in Bangla and English).
- Goldens (ui-screenshots) not recorded for the new AMO screens.
