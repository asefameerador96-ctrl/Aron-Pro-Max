# Request: RoomPrintLedger findings from the T1 check (android-print to android-core, 2026-10-07)

`RoomPrintLedger` passes the shared contract (`core-printing/src/testContract/.../PrintLedgerContract.kt`, run by
core-database `RoomPrintLedgerContractTest`, 9/9). The independent Opus checker then found the cases below; each one was
reproduced with a test. android-print fixed its own half in `ReprintPolicy` / `MemoPrinting` / `MemLedger`. Once these
are fixed in Room, android-print adds each case to the contract so both ledgers stay in step.

## Blocking
1. **A stock slip flags one row of a multi-SKU Save** (same as wiring-gaps item 15). `recordStock` writes one
   `stock_movement` per SKU; `setSlipPrinted` updates `WHERE client_uuid = :uuid` only.
   ```kotlin
   repo.recordStock(listOf(a, b)); ledger.record(slipEvent(ref = a.clientUuid, PRINTED))
   // expected slip_printed {a=true, b=true}, was {a=true, b=false}
   ```
   Wanted: set and clear `slip_printed` on every row of the Save named by `ref_client_uuid`. The callers
   (feature-stock) will pass the first saved movement, sorted by sku_id. You choose how a Save is identified: a
   `save_uuid` column (cleanest), or `business_date + captured_at + kind` of the named row (one `CaptureMeta` per Save).
2. **A void slip or due receipt that names the memo counts as a printed copy.** `printedCount` counts every `printed`
   event, and `markPrinted` runs for every kind.
   ```kotlin
   ledger.record(PrintEvent(uuid, "void_slip", memo, null, 1, PRINTED, true, 3, null, 1_000))
   // memo.print_count = 1 and printed_at set; expected 0 and null
   ```
   Wanted: only kinds outside `ReprintPolicy.NOT_COPIES` (`void_slip`, `due_receipt`) count toward `print_count`,
   set `printed_at` or `slip_printed`, or keep a flag in the `failed_user` check. `ReprintPolicy.isCopy(event)` is
   public in core-printing; using it keeps the two ledgers identical.

## Minor
3. **`printed_at` format:** `Instant.ofEpochMilli(..).toString()` drops `.000` (`2026-10-05T04:20:00Z`), unlike every other
   timestamp (`.SSSZ`), so text comparison misorders it. Please use the same millisecond formatter as the captures.
4. **`history()` order:** Room orders by `at_ms`, so a clock that went backwards reorders events; the reference keeps
   insertion order. Please order by `rowid` (insertion order). Nothing depends on the order today because the reprint
   policy only counts events.
5. **Job downgrade:** `savePending(paperOut = true)` followed by `savePending(paperOut = false)` and then `record(failed)`
   leaves `printed_at` set with `print_count` 0. `MemoPrinting` never does this. Please make the upsert keep
   `paper_out = 1` once it is set: a paper that came out never comes back.
