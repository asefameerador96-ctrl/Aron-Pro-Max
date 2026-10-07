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
