# android-sr-b to android-core: capture support I need in core-database / core-sync (2026-10-07)

I build the sale, memo, due-collection and Sales Submit logic against `CaptureRepository`. These pieces do not exist yet and block F-SR-032, F-SR-035, F-SR-057 (skip), F-SR-030/054/036 reads. Shapes are the contract's, by schema name.

1. **`recordDueCollection(entity, fix?)`**: contract `DueCollectionPayload` (outlet_id, against_memo_client_uuid, against_memo_no, against_memo_business_date, amount_mtk >= 10, is_full_settlement, outstanding_before_mtk, payment_mode `cash`, visit_client_uuid?, fix?). One transaction with its outbox row, family = the visit when there is one.
2. **`recordVisitSkip(entity)`**: `VisitSkipPayload` (outlet_id, reason_code). No fix, no visit row (F-SR-057 "a skip needs no fix").
3. **`recordDaySubmit(entity)`**: `DaySubmitPayload` (scope, submit_cycle, device_counts, device_money, rejected/quarantined/pending counts, submitted_with_dues, dues_outstanding_mtk, retailers_with_dues, stock_slip_printed). It must be the last outbox record of the route-day (rank above every family record).
4. **Read queries on `CaptureDao`**: `discountsOf(memoUuid)`, `qcLinesOf(memoUuid)`, `memosBetween(fromDate, toDate)` (7-day Sale History, F-SR-054), `qcLinesOn(businessDate)`, `visitClosesOn(businessDate)`, `dueCollectionsOf(memoUuid)`, and a way to know which memos are superseded.
5. **F-SYS-027 memo counter**: `suspend fun next(businessDate): String` in the same transaction as `recordSale` (I take it through my `MemoNumbers` port today; a number consumed but not committed must not leak a gap that the server flags: tell me the intended behaviour).
6. A `stock_movement` kind for QC returns (`qc_return`) so F-SR-053 can write one; today only `issue`/`adjustment` add and others subtract in `stockBalanceOn`, so please confirm `qc_return` subtracts.

Until these exist I keep stubs behind ports in `feature-sale` / `feature-memo` marked `// REQUEST: docs/requests/android-sr-b-core-records.md`.
