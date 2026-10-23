# backend-core-qa-1 — answer (backend-core, session 14, 2026-10-22)

Answers `docs/requests/backend-core-qa-1.md` (lane qa, wave-4 data-flow trace, QA-FLOW-02, -06, -04).
Tests: `backend/app/src/test/kotlin/com/aktcl/aron/backend/app/MemoNumberPackSizeTest.kt` (items 1 and 2) and
`DayApiTest.theFinalSubmitPreviewWarnsOnlyForRoutesDueThatDay` (item 3). Opus checker run before push (see
`docs/status/backend-core.md`).

## 1. `memo_no_invalid` (QA-FLOW-02) — fixed

`IngestService.process` step 4b, rule in `backend/sync/.../MemoNumbers.kt` (docs/24 s7.5, D24-24, D24-48). A `memo`
is refused `memo_no_invalid` (rejected, not retryable) unless its number is `<username>-<yyMMdd>-<seq>` with:

- `username` = the **uploader's** login (the capturing user; a cover rep's memo carries the cover rep's own login; a
  paper backfill carries the SR's login, `forUserId`);
- `yyMMdd` = the record's `business_date`;
- `seq` inside one of the user's four ordinal blocks `1 .. 4 x cfg.memo.seq_block_size` (block 500: 1..2000) or the
  overflow ranges `5001..5999`, `6001..6999`, `7001..7999`, `8001..8999`.

Choices (in DECISIONS.md, 2026-10-22):
- **Any of the four ordinals, not only the uploading phone's.** A phone rebound to another slot still uploads numbers
  printed under the old one; a paper backfill comes from no phone. Tying the check to the current binding would reject
  real, printed sales for good. The cross-user and cross-date forgeries qa found are closed either way.
- The block size is the current `cfg.memo.seq_block_size` (as the bundle sends it). The key is future-dated and applies
  to new binds (docs/17); if it is ever lowered, numbers printed under the old size above `4 x new size` would be
  refused. Not reachable today (no value has changed); noted as an open item.
- The check runs after the registry step: a memo already stored is a `duplicate` on resend and is never re-judged.
- **Children of a refused memo** (`memo_line`, `memo_discount`, `qc_line` naming it) are refused `memo_no_invalid`
  too (final), before any check that would park them (a bad pack size included), instead of parking `parent_missing`
  for 7 days. A line sent before its memo parks first and is refused on its next resend.
- **Forged numbers and the global duplicate check:** a forged number is now refused before the writer, so it is never
  stored, and the `(memo_no, business_date)` uniqueness only ever meets the owner's own numbers. Test
  `aForgedNumberNeverHoldsTheRealOwnersMemo`: sr1001 sends `sr2002-...`, refused; sr2002's real memo with that
  number is then `accepted` (before the fix it was `quarantined memo_no_duplicate`). The owner's own second memo
  under one number is still `memo_no_duplicate`.
- **Paper backfill** (`ingestManual`): another login's number is refused `memo_no_invalid` (`stored = false`,
  `duplicate_memo_no = false`), even when that user's memo with the number is stored; before, the pre-check answered
  `duplicate_memo_no` and named the other user's memo id.

qa's four rows (hop 14): own number accepted; `lkxsr1001-...`, another date, `...-3000` → `rejected memo_no_invalid`;
`MEMO-1` stays `schema_invalid` (contract pattern). The hop 14 lines of `data-flow-trace-known-findings.txt` can go.

## 2. `pack_factor` re-check (QA-FLOW-06) — fixed

`MemoChecks.packFactorHolds` / `packFactorProblem`, `IngestService.process` step 7b (docs/34 s4.2). A `memo_line` or
`stock_movement` with `unit_entered = pack` whose `pack_factor` is not `app.sku.base_per_pack` is **parked
`qty_invalid` with `retryable: true`** (lead ruling: retryable after a bundle refresh). For a memo line, the memo and
the memo's other children in the segment are parked with it, so neither the wrong quantity nor its money (dues ledger)
is booked. The registry row is `parked`, so the phone's resend is judged again: once the master agrees it is stored,
once (test `aParkedFamilyIsStoredOnceWhenTheMasterAgrees`); unchanged it answers the same.

- qa's example (MaxR-20S, 2 packs, `pack_factor` 10, `qty_base` 20): memo and both lines `rejected qty_invalid`,
  retryable, nothing in `app.memo`, `app.memo_line` or `app.due_ledger`. With `pack_factor` 20 the line stores 40.
- A split family (header stored first, the wrong line later): the printed memo stays; the line is parked and the memo
  gets `server_flags` `pack_factor_mismatch` for review. The other order (the wrong line first, its memo later): the
  memo finds its line parked `qty_invalid` and is parked with it, its good lines park `parent_missing`; nothing books.
- Paths outside a family segment (a reviewer's accept, the backfill match) judge each line on its own.
- Lines in loose units (`stick`, `piece`) carry `pack_factor` 1 and are not judged here (the loose-units rule
  `cfg.sale.allow_loose_base_units` is a separate server re-check, not built by this row).
- **Ack retryable vs the catalogue:** `qty_invalid` is `rejected / retryable: false` in the contract catalogue; a
  parked record now answers `retryable: true` whatever its code's default (every parked record is re-judged on resend).
  The only code this changes today is `qty_invalid` from this rule. android-core: treat the ack's `retryable` member as
  authoritative (keep the rows and resend after the next bundle), not the code's catalogue default.
- **What "retryable" buys:** a captured line never changes, so a resend passes only if the server's `base_per_pack`
  is corrected (the server was the wrong side). If the phone was wrong, the rows stay parked and the phone gives up at
  its retry limit; the sale then needs a reviewer or a paper keying. That is the intended trade (never book a wrong
  quantity), but it is a held sale, not a self-healing one.
- **"As of the price-list date":** `app.sku` keeps no pack-size history (only the dw SCD2 copy, which lags the
  projector), so the comparison is with the current value. A pack-size change is a master-data event between days;
  if the lead wants a dated rule, db would add `app.sku_pack_history` (not requested yet).
- Parked rows become final after `cfg.sync.parked_ttl_days` (7) only when the parked-row worker exists (open since
  F-SYS-055); until then they stay parked and visible in `app.sync_rejected` with `retryable = true`.

## 3. Final Submit preview lists an unplanned route (QA-FLOW-04) — warning fixed, `not_due` listing proposed

Lead ruling 2026-10-22: `routes_not_sales_submitted` fires only for routes planned on that business date or that have
data; an unplanned route with `had_data` false is listed as `not_due`, not warned.

Done (server only, no contract change): `DayApi.previewIn` still lists every active route (F-TSO-010), but all three
warnings (`routes_not_sales_submitted`, `routes_with_pending_rows`, `routes_not_logged_in`) look only at routes that
are due: `route_day.planned` true, or `had_data`, or a state past `logged_in`, or pending rows reported (an SR who
submitted with rows still on the phone always warns). A route with **no** route-day row yet (planner not run, SR Not
Set) still counts as due, as before, so a planner failure never hides a missing login. `finalSubmit` stores the same list
of route states. The preview and the login card now agree on what warns.

Not done (needs a contract change, proposed): showing such a route **as** `not_due`. `state` is the shared
`DayState` enum (bundle, day states, TSO app); adding `not_due` to it is a response enum break on several operations.
Proposal: add an optional `planned: boolean` member to the preview route item (`FinalSubmitPreview.routes[]`,
additive, not an oasdiff break), filled from `route_day.planned` (null when no row), and the TSO app and web grey a
route with `planned = false` and `had_data = false` as "not due". One commit: `contract/openapi.yaml`, the slice,
`FinalSubmitPreviewRouteDto`, web `openapi.d.ts`. Lead 2026-10-22: it lands with the contract v1.7 batch
(D-CONTRACT-BATCH-01); queue line in `docs/requests/backend-core-contract-v1.7-planned.md`.

For qa: hop 11 asserts the listed routes equal the planned ones; under the ruling the unplanned route stays listed,
so that hop should assert the warnings instead (no `routes_not_sales_submitted` for GUL-SR-1 on Saturday) and, once
`planned` exists, that GUL-SR-1 carries `planned: false`.
