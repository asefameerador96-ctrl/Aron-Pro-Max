# SR/AMO Sale History (SH2): what is built, and the one part that needs wiring (2026-10-17)

Built (shared `feature-memo`, used by the SR and AMO hosts, offline): "pick one date" then the per-SKU table (SKU, quantity in its
unit, value) and the **exact** total of the rows shown (manual SR-S-20; the manual's own footer is wrong by 400). Source: the live
memos of the phone's 7-day window (superseded memos are excluded, same SKU across memos of the date is summed). Domain:
`SaleHistoryBuilder` (`HistoryDay.skuRows`, `skuTotalMtk`), tested with an exact-sum case; UI: `SaleHistoryScreen`; goldens
`history_bn_*` in `SrStateScreenshotTests`. The AMO host already calls the same screen, so AMO gets the same table with no second
implementation (F-AMO-013).

Not built: the **online fallback** (`GET /v1/memos?outlet_id=&from=&to=`, F-API-025) for dates older than the 7-day window.
There is no authenticated API client in `app-sr` or `app-amo` yet (the TSO app has one in `TsoReadModels`), and a network read
in the SR sale path is a sync-surface change, so it is left for the lead's decision rather than slipped in here.
Proposed: a `HistoryRemote` port in `feature-memo` (`suspend fun memos(outletId, from, to): List<StoredMemo>?`, null when offline),
implemented in each host with the shared `AronApiClient` (Grant.FULL, up to 5 pages of 100), mapping `MemoView.lines` to
`MemoItem(sku_id, qty_base, base_price_mtk, gross_mtk)` and skipping `status != active`; the screen then offers "older dates" and
sets `fromServer = true` (the banner exists). The builder and the table already take such rows unchanged.
