# backend-core-qa-1 — sync: memo_no_invalid never raised; pack_factor not re-checked; Final Submit preview question

From lane qa, wave-4 data-flow trace (`DataFlowTraceTest`, `docs/evidence/data-flow-trace-2026-10-23.md`, hops 14, 15, 11).
Checked by an Opus checker: items 1 and 2 CONFIRMED against the production code.

## 1. `memo_no_invalid` is never produced (docs/24 s7.5, s5 outcome table)
Spec: the server rejects a memo number that does not match the user, the business date and the user's block
(`seq = bind_ordinal x 500 + n`, overflow `5000 + ordinal x 1000 + ...`).
Code: `MEMO_NO_INVALID` appears only in `ReasonTexts.kt`; `IngestService.kt:246` reads `memo_no` with no check; the
contract pattern catches the format only.
Repro (sr1001, ordinal 0, block 500, Saturday 2026-10-24; request shape, redacted): `POST /v1/sync/batch` with a
`visit`, `memo` (`payload.memo_no` as below), `memo_line`, `visit_close`:

| memo_no | answer for the `memo` ack |
|---|---|
| `sr1001-261024-050` (in block) | accepted (correct) |
| `lkxsr1001-261024-051` (another user) | accepted (should be rejected `memo_no_invalid`) |
| `sr1001-261023-052` (another date) | accepted |
| `sr1001-261024-3000` (outside every block) | accepted |
| `MEMO-1` | rejected `schema_invalid` (format only) |

Worse than it looks: the duplicate check (`IngestService.kt:286`) is global, so a forged `lkxsr1001-...` memo from
sr1001 would make the real lkxsr1001 memo with that number `quarantined memo_no_duplicate`. The paper-backfill path
already refuses another login's number (`PaperBackfillCheckerTest`). Failing test: `MemoNumberRuleTest`, attached below.

## 2. `pack_factor` is not compared with `sku.base_per_pack` (docs/34 s4.2 line 114: "re-checked on the server")
Repro: a `memo_line` for `MaxR-20S` (base_per_pack 20), `qty_entered` 2, `unit_entered` pack, `pack_factor` 10,
`qty_base` 20, `gross_mtk` computed from 20 sticks, memo totals consistent: memo and line both `accepted`. The server
stores 20 sticks and the money for 20 sticks for two 20-stick packets (should be 40). Only `qty_base = qty_entered x
pack_factor` (DB CHECK) and gross arithmetic are checked. Impact: a phone with a stale or tampered pack size books
wrong stock, sales and money, and every aggregate repeats it. Suggested: reject `qty_invalid` (retryable after a
bundle refresh) when `unit_entered = pack` and `pack_factor <> sku.base_per_pack` as of the price-list date.
Failing test: hop 15 `a pack_factor that differs from the SKU's base_per_pack is refused`.

## 3. Question: Final Submit preview lists an unplanned route (not asserted as a bug)
`GET /v1/day/final-submit/preview?zone_id=<Z-GUL>&business_date=2026-10-24` returned `GUL-SR-1` (planned Sun/Wed, not
Saturday) as `state logged_in, had_data false`, so the warning `routes_not_sales_submitted` fires on a day when
nothing was due. Cause: the first bundle of the day marks every route-day of the SR logged in
(`BundleService.kt:404-433`), and the preview lists every active route of the zone (`DayApi.kt:244-249`, consistent with
F-TSO-010 "routes with FF name or SR Not Set"). The login card and `final-submit-status` count planned routes only (1),
so the preview and the cards disagree (2 routes vs 1). Please say whether the listing is intended; if so add `planned`
to the preview route so the app can grey it and not warn.

## Attached tests (add them with the fix; they fail on INT today, so they are not in qa's head)
`docs/requests/attachments/MemoNumberRuleTest.kt.txt` (s1: 1 passes, 3 fail today) and `docs/requests/attachments/PackFactorRecheckTest.kt.txt`
(s2: the right pack size passes, the wrong one fails today). Drop the `.txt`, put them under `backend/app/src/test/kotlin/com/aktcl/aron/backend/app/`; they extend `DataFlowTraceSupport` (on INT with qa's head).
