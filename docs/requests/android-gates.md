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

## 3. Check-out submits the day (rule 3): what the server should expect and may flag

- **Order.** The phone now sends a day's `day_submit` only after every earlier record of that business date is acked, rejected or
  quarantined, and the `check_out` after the `day_submit` is final. `CHECKOUT_WITHOUT_SUBMIT` (judged after the batch) should therefore
  stop firing for reps who forgot Sales Submit. It should ALSO accept a check-out of a day with no captures at all: the phone writes no
  `day_submit` for a day with nothing captured ("every route-day with captures gets its submit").
- **Automatic close of a forgotten day.** The phone's own check-out for a day the rep never checked out of has `business_date` D, a
  `captured_at` on a LATER date, and a fix with `fix_status = provider_unavailable` and no coordinates. Please flag it
  (a risk signal such as "no check-out, closed by the phone"); it is not a rejection.
- **A `day_submit` can arrive with a non-zero `pending_count`** (the real number of rows still waiting when the check-out was pressed);
  the existing settle (wait for the counts, deadline, `submit_count_mismatch`) already copes.
- **A refused or quarantined `day_submit` unlocks the phone**, which then lets the rep submit again with the next `submit_cycle`; the
  server uses the newest cycle. There is still no server-side void of an accepted submit.
- **Not built (stated to the owner):** a per-rep server-side midnight close.

## 5. Memo usernames of three characters (lead, 2026-11-13): the server checks still say four

The phone now accepts a memo username of 3 to 32 characters (`^[a-z][a-z0-9]{2,31}$`). The database does not: `app_user_memo_username`
(`V0002__geography_routes_people.sql:195`, `^[a-z][a-z0-9]{3,31}$`) and the three `memo_no` CHECKs (`V0007__field_transactions.sql:696, 957, 1937`,
`^[a-z][a-z0-9]{3,31}-[0-9]{6}-[0-9]{3,4}$`), the matching text of `docs/24-build-spec.md` s7.5 (4 to 32), and the ingest validation that answers
`memo_no_invalid`. Until a **forward** migration relaxes all of them to `{2,31}` (never edit a shipped migration), a login with a 3-character username
will have every memo refused by the server with `memo_no_invalid`. So: do not create a 3-character SR or AMO login before that migration ships.
Also noticed: the phone's `ErrorReporter` redacts usernames with `\b[a-z]{2,5}\d{3,}\b`; a short login with no digits is not matched. That is a log-scrubbing
gap of another lane, listed here so it is not lost.

## 4. Nothing else

No contract member is needed for slice 1: the phone sends the same records it always did. If the flag should reach the
phone (it need not), that is a later contract addition, not this round.
