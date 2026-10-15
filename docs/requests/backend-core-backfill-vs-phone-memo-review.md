# Request: review action "same memo as backfill" (backend-core → lead, for the backend-admin replacement; 2026-10-15)

**Context (F-API-038):** a dead-phone day is keyed on the web from the printed memos (`IngestService.ingestManual`,
`entry_source = manual` once db lands `backend-core-memo-source.md`). If the phone later comes back and uploads its
own copy of a memo already keyed, the memo number collides (`memo_no_unique` on `(memo_no, business_date)`).

## What happens today (phone path, unchanged)
| Phone record | Outcome |
|---|---|
| `memo` (same `memo_no`, phone `client_uuid`) | quarantined `memo_no_duplicate` (`app.sync_quarantine` open item; registry row `quarantined`) |
| its `visit` (`sr_call`, with fix and geo verdict) | **accepted and stored** (a second visit at the outlet that day, next to the paper `web_entry` visit) |
| its `memo_line`s, `memo_discount`, `print_event`, `visit_close`... | children of a quarantined parent: quarantined with the same code (held, not lost) |
| `due_collection` / `memo_void` aimed at the phone memo uuid | quarantined with the same code (the collection is real cash and must not be lost) |

The money is stored once (the paper memo); the phone's evidence (fix, signature, print events) and any later
collections are held.

## The action
`POST /v1/admin/quarantine/{client_uuid}/resolve` with `action = same_as_backfill` (or a separate operation; contract
change by backend-admin), on an open `memo_no_duplicate` item whose stored twin (same `memo_no`, same business date)
has `entry_source = manual`. Roles: SUPPORT, ADMIN, SUPERADMIN; separation of duties is not needed (no money changes),
but the resolver's reach must cover the route.

It must, in one transaction, idempotently:
1. **Quarantined memo:** mark the item `resolved` (`resolution = same_as_backfill`), set its registry row to `accepted`
   with `server_id` = the paper memo's id (so the phone's resends are `duplicate` and its digest reconciles; the lead
   note "a released quarantine must leave the registry row accepted" applies). Do not insert a second memo.
2. **Compare first:** lines (sku, qty_base, price) and totals of the phone memo payload against the paper memo. If they
   differ, refuse (409 with the differences) and leave the item for a data correction (memo edit/void by the TSO);
   the phone copy is the better evidence and the reviewer decides.
3. **Visits:** keep the phone `sr_call` visit (it has the geo evidence) and void the paper `web_entry` visit with a
   data-void tombstone (`voided_at`, reason `superseded_by_device_visit`) and point the paper memo's
   `visit_client_uuid` at the phone visit — or, simpler, keep both and mark the paper visit `superseded`; dashboards
   must count one visit and one strike (needs a db column or the void; db to decide).
4. **Children:** phone `memo_line`s / `memo_discount` / `print_event` / `visit_close`: registry rows `accepted` with no
   new rows (the paper memo's lines stand); `print_event` may be stored against the paper memo (evidence).
5. **Dues:** every `due_collection` / `memo_void` aimed at the phone memo uuid is re-pointed to the paper memo's
   `client_uuid` and released through the normal ingest path (DuesLedger rules apply: idempotent by its own uuid,
   ledger row posted once). This is the part that must not be skipped: it is real cash.
6. **Audit:** one `AuditLog.write` row per action (entity `memo`, id = paper memo, action `backfill_matched`,
   before/after: phone memo uuid, visits, released child uuids), plus the quarantine item's own resolution fields.
7. **Day state and aggregates:** enqueue the dirty keys for the route-day (F-SYS-086) so the tile reflects one sale.

Tests (T1, Opus checker): phone after paper with equal content → one memo, one counted visit, dues posted once against
the paper memo, resends duplicate; differing content → 409; replay of the action → same answer; a collection uploaded
after the action → posts to the paper memo.

backend-core can supply the ingest-side release helper (re-point + release through `process`) if backend-admin wants it.
