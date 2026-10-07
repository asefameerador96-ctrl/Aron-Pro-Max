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

## Done (CI green on lane branch)
Nothing is marked done yet; waiting for the first green CI run.

## Built, awaiting CI (logic and tests; screens pending N-023)
- F-SR-017, F-SR-019: `VisitFlow` + `VisitFlowTest` (13 cases: in range, out of range, refresh cap, force sale, mocked, blocked, no fix, no outlet location).
- F-SR-016, F-SR-074 logic: `OutletPicker` + `OutletPickerTest`.
- F-SR-014 logic: `StockLoad` + `StockLoadTest`.

## Decisions taken (DECISIONS.md is read-only for lanes)
- **SRA-01** A visit record has one fix and one final verdict (contract action: sale_allowed, force_sale, blocked), so the row is committed when the verdict is final, not before. A kill mid-refresh restarts the check. Reason: the contract has no refreshed or pending verdict.
- **SRA-02** Picker label shows the phone as 11 digits (normalised); closed, merged and archived outlets are hidden (reading of "11-digit phones and closed outlets hidden").
- **SRA-03** Stock re-save guard window default 120 s (`cfg` key not in the registry; request to follow if the lead wants it configurable).
- **SRA-04** Picker chip is the first character of the name, Latin upper-cased, Bangla as is, anything else `#`.

## Blocked / waiting
Compose screens: N-023 kit. Real fix source: N-021. Bundle into Room and app wiring: F-SYS-006.

## Next
F-SR-011/012 attendance logic, F-SR-046/047 tasks logic (needs F-API-026 DTO), F-SR-079 capture component state, F-SR-063 stale-bundle rules.
