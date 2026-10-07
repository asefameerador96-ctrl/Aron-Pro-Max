# Status: lane android-sr-b (2026-10-07)

## Done
Nothing is marked done yet (no row has its screen or independent checker). Written, pushed to `lane/android-sr-b`, CI verifying:
`feature-sale/domain`: `SaleDraft` + `SaleDraftOps` (quantity entry in stick/piece/dozen/pack, slide empty packets, QC, credit paid, zero sale, QC lock), `SaleReviewCalculator` (lines, pack badge, stock warning, category subtotals, DRP reward, QC deduction, net via `shared:rules` `MemoMath`; offer line zero), `SaleCommitter` (memo, lines, discounts, QC and outbox in one Room transaction via `CaptureRepository.recordSale`; zero sale; edit with supersedes), `FileDraftStore` (atomic draft, survives kill).
Tests: `SaleReviewTest`, `SaleCommitterTest` (Robolectric Room: atomic rollback, replay writes nothing, draft kill and relaunch).

## Blocked (screens)
Compose screens wait on N-023 (android-core-ui slices). Rows needing sr-a state: F-SR-014/017 (see below).
Local Gradle gets Maven Central 429: verify through CI on `lane/android-sr-b`.

## Interfaces I need (sr-a / android-core)
- From android-sr-a (feature-outlet): the open visit for an outlet, `visitUuid`, `outletId`, `routeId`, outlet `priceType`, visit `geo_verdict`; a call to close the visit with an outcome (F-SR-057, mine to write the record).
- From android-core: `MemoNumbers` (F-SYS-027 counter, in one transaction with the memo) and `CaptureMetaSource` (trusted clock, boot count, bundle/config versions). Prices and offers are not in Room yet (F-SYS-006): I read them through `SaleSku` built by a `PriceBook` the app wires.
- Stock: `SaleSku.stockBase` from sr-a's current-stock tracker (F-SR-050).

## Memo data model for android-print (F-SR-028)
Print from stored rows only: `MemoEntity` (memo_no, committed_at, outlet_id, price_type, gross/offer/drp/qc/net/paid/due mtk, is_credit, supersedes_client_uuid), `MemoLineEntity` (line_no, sku_id, qty_entered, unit_entered, qty_base, base_price_mtk, gross_mtk), `MemoDiscountEntity` (kind, sku_id, qty_base, value_mtk), `QcLineEntity` (sku_id, qty_base, settlement_mtk). Read through `CaptureDao.memo`, `linesOf`; I will add `MemoReadModel` (memo plus lines plus discounts plus QC) in `feature-memo` with a `reprint` flag so a reprint prints "duplicate".

## Next three
CI result then fixes; F-SR-060 and F-SR-023 screens when the kit lands; Memo menu model (F-SR-030).

## Checker findings (2026-10-07)
T2 checker (Sonnet) on F-SR-036/030/032/054/010/069/034/035: 4 confirmed defects, all fixed with the checker's tests kept (`CheckerTest.kt` in feature-memo and feature-dayclose):
1. Home money category rows missed memo-level discounts and QC on categories with no sale: now a `""` row and QC-only categories appear; rows add up to the grand total.
2. Day summary Discount column dropped memo-level offers: a `""` category row carries them.
3. Sales Submit gate blocked forever on a rejected row: rejected and quarantined rows are now notes (carried into the day_submit counts), not blocks. Q-UI-08 stays an open sponsor question.
Flagged, not a defect: `MemoDetail.canMarkPaid` uses the stored due; the UI must use `DueLedger.markPaid != null`.
T1 checker (Opus) on the sale rows: pending.
