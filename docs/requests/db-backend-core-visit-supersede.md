# Answer: superseding the paper visit when the phone's memo matches a backfill (db → backend-core, 2026-10-15)

Answers step 3 of `docs/requests/backend-core-backfill-vs-phone-memo-review.md` (lead row). **No new column**:
the data-void tombstone already does exactly this. Documented in `V0081` (comment on `app.visit.voided_at`).

## What `IngestService.matchBackfill` writes (same transaction as steps 1, 2, 4, 5)
```sql
UPDATE app.visit
   SET voided_at = now()
 WHERE client_uuid = :paperVisitUuid      -- the paper memo's visit_client_uuid
   AND business_date = :businessDate      -- prunes to one partition
   AND visit_kind = 'web_entry'
   AND voided_at IS NULL                  -- idempotent: a replay updates 0 rows
RETURNING route_id;                       -- the paper visit's own route (from the web request)
```
- `:paperVisitUuid` is the paper memo's `visit_client_uuid`: add that column to the paper-memo SELECT in
  `matchBackfill` (it does not read it today).
- Keep the phone `sr_call` visit as stored (it carries the fix and the geo verdict).
- Do **not** re-point `app.memo.visit_client_uuid`: it is not a free column of the memo guard (the update is
  refused), and nothing counts through it. The paper memo stays the one memo; its strike counts once.
- Put the reason `superseded_by_device_visit` and the phone visit's client_uuid in the step-6 `backfill_matched`
  audit row (before/after), as every data void does.
- Enqueue the route-day dirty key (step 7) for the phone memo's route **and** for the `route_id` returned above when
  it differs (the paper visit's route comes from the web request and is not compared), so `dw.fact_visit.voided` and
  `agg_daily_route` are rebuilt for both.
- Set the paper visit's `app.ingest_registry` row to `status = 'voided'`, as `DataVoidApi` does for a data void
  (counts are unaffected: the digest treats `voided` as accepted).

## Why it counts once
- `voided_at` is write-once on a synced visit (V0007 guard `'=voided_at'`); `api_rw` may set it.
- The projector (`Aggregator.kt`) builds the visit set with `voided_at IS NULL` and writes `fact_visit.voided`;
  `dw.v_daily_sr` and `dw.v_geo_integrity` read `NOT voided`. Visit and strike counts take `sr_call` only, so the
  paper `web_entry` visit is not a visit there; `dw.v_geo_integrity` counts every non-voided kind, so the paper visit
  counts there (as `no_fix`) until the match voids it. Voiding also keeps it out of every per-visit list.
- Strike (`successful_calls`) counts distinct outlets with an active memo: the one paper memo.

Test: `db/src/test/kotlin/com/aktcl/aron/db/VisitSupersedeV0081Test.kt` (phone and paper visit for one outlet and
day: after the update, one live visit; a second update changes nothing; clearing `voided_at` is refused).
