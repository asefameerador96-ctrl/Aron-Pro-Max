# Request to the server lanes from lane android-gates (2026-11-13)

This lane changes **Android only** (no server code, no contract). What the phone now enforces needs a matching
server-side signal; this file is the request, nothing here is built by this lane.

## 1. Flag an over-stock memo at ingest (rule 2, D-OWNER-PREREQ-01 / D-LEAD-PREREQ-02)

The phone now refuses a sale line (plus the memo's reward packs of the same SKU) above the stock the rep holds, in base
units, inside the saving transaction. The ruling says the **server flags and never refuses**: a sale that happened and was
printed must still be booked.

What the server would need, in `IngestService` at memo ingest:

- Compute the rep's held stock for the memo's `business_date` the same way the phone does:
  `sum(stock_movement.qty_base) over kind in ('issue','adjustment') - sum over kind='qc_return'`, minus the sold
  `memo_line.qty_base` and the `memo_discount` reward quantities of that date's ACTIVE memos (a memo replaced by an edit
  counts once, through its replacement; an edit gets its superseded memo's quantity back).
- When the memo takes a SKU above that figure, accept it and raise a **risk signal / review flag** on the memo with the
  SKU, the requested base quantity and the held base quantity. Never reject, never drop lines.
- Note for whoever builds it: the phone's figure and the server aggregate disagree today. `Aggregator` counts only
  `kind='issue'` as issued and only `return`/`qc_return` as returned, so an AMO `adjustment` is in neither, while the
  phone's balance includes it (map gap 12). Reward packs are not subtracted server-side at all. Both need fixing first,
  or the flag will fire on correct memos.

## 2. Flag operational records on a user-day with no check-in (rule 1)

The phone now refuses every operational write without an open check-in on the record's business date. The ruling says the **server flags
and never refuses**: sales are offline-first and a batch can reach the server before the check-in that opened its day. The server has no
signal today (`RiskSignalCode` has no such code; a first `visit` even moves the route-day to `in_field`). Needed, in the pattern of
`CHECKOUT_WITHOUT_SUBMIT` (post-batch): a risk signal when a `visit`, `memo`, `stock_movement`, `due_collection` or AMO record of a user-day has no
`attendance_event` check-in for that business date once the batch (and the day's earlier batches) is settled. That needs a contract code
and the database constraint migration, so it is for the server lanes.

Also unhandled on Android and worth a server-side decision: a rep on a replacement phone checks in again, and the server answers
`attendance_duplicate` (unique `(user_id, business_date, kind)`). The phone's local check-in is what counts, so this should be an accepted
duplicate, not an error the rep sees.

## 3. Nothing else

No contract member is needed for slice 1: the phone sends the same records it always did. If the flag should reach the
phone (it need not), that is a later contract addition, not this round.
