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

## 4. Check-out: who closed it, and the two signal rules (ruling 6 of D-LEAD-PREREQ-03; review blocking 4) — PROPOSAL, the phone does NOT send the field until the lead confirms the name

**Why.** A day the rep never checked out is closed by the phone at the next start with a check-out that has no location. Today the server stores it as a
real check-out at the (late) capture time, which makes attendance and hours wrong, and AMO `supervisor_day.checked_out_at` takes that time. Separately the
phone no longer needs a Sales Submit before check-out, so a checked-in day with no route captures sends a check-out with no `day_submit` and the server raises
`CHECKOUT_WITHOUT_SUBMIT` (weight 20) against a rep who did nothing wrong.

**1. Contract field (attendance_event payload, kind `check_out` only).** `closed_by`, enum `rep` | `phone`. `rep` (or absent: old builds) = the rep pressed
check-out. `phone` = the phone closed a forgotten day at a later start (no location was read). Absent is treated as `rep`. Name proposed by this lane; the
lead decides it. Additive, optional, same contract minor.

**2. Server behaviour.**
- `closed_by = phone`: store the row and acknowledge it as accepted, but record it as "no check-out" for that user and date: `attendance` reports show no
  check-out time (not the capture time); AMO `supervisor_day.checked_out_at` is NOT set; `checked_out_at` for a route-day is not set from it. It still
  counts as the phone's closing of the day for the "no open attendance" views. It never raises `checkout_too_early` and is not judged by
  `cfg.day.checkout_earliest_time`.
- `closed_by = rep` / absent: unchanged.

**3. `CHECKOUT_WITHOUT_SUBMIT`.**
- Not raised for a user-day that has no route captures (no `visit`, `memo`, `due_collection`, `stock_movement`, AMO record) in the registry for that date.
- Withdrawn (the risk signal row cleared or closed) when an ACCEPTED `day_submit` for that user and date arrives after it (a later cycle, a re-sent or
  integrity-released submit, or the rep's own Sales Submit after the check-out).
- Not raised for a `closed_by = phone` check-out whose day has an accepted submit.

**4. Phone behaviour tied to this (this lane, after confirmation).** The check-out of a day that has a submit is sent only after a submit of that date is
ACCEPTED by the server (ruling 5), so the signal is rare; the field above lets the server tell a phone-made close from the rep's.

**5. Acceptance test (server lane).** (a) `closed_by=phone` check-out three days late: attendance shows no check-out time, AMO supervisor_day.checked_out_at
stays null, no `checkout_too_early`. (b) check-out on a day with no captures: no signal. (c) check-out then an accepted `day_submit` for the same
user-date: signal present after the first, withdrawn after the second.

## Question (A-GATE5, 2026-11-18): is `abandoned` right for a call that holds only a survey answer?
The SR's back press from a call that has a survey answer and no memo writes `abandoned` (not counted as a visit). The AMO's `AmoCallOutcome` counts the same content as an audit-only call (`zero_sale_stock_ok`). The 04:00 automatic end follows the rep's own way out on each app (SR: abandoned; AMO: audit only), by the lead's ruling, and nothing was changed in the back press. If a survey-only SR call should count as a visit, the back press and the automatic end change together; no change made here.
