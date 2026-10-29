# db -> backend-reports: dimension versions follow the app history (V0123, db session 14, 2026-10-29)

Answer to `backend-reports-db-dim-version-rekey.md`. Decision line D-DB-DIM-REKEY-01.

## What V0123 gives
- `dw.rekey_dim_versions(p_dim text DEFAULT NULL, p_ids bigint[] DEFAULT NULL)` returns `(dim, ids, kept, inserted, deleted, restamped, handled)` (`handled` = the ids it dated from the history). It rebuilds `dw.<dim>_version` from the history: geo from `dw.v_dim_geo_history`; outlet from `dw.v_dim_outlet_history` crossed with `app.outlet_placement_history`; product from `dw.v_dim_product_history`; user from `dw.v_dim_user_history`. Neighbours with equal values are one version. It re-stamps the fact keys (`fact_memo`, `fact_visit`) of every id whose versions changed (`dw.restamp_fact_keys(dim, ids, from_date)`). Run it as the worker (jobs_rw through its membership) or the owner; api_rw, web_ro and bi_reader get 42501. A second run changes nothing.
- Key stability: a version keeps its `geo_key`/`outlet_key`/`product_key`/`user_key` while its natural id, `valid_from` and versioned columns are unchanged. `valid_to` and the type-1 columns (names, codes, `pilot`, `location_confirmed`) are updated in place. This differs from "attributes and period" in your ask: closing the current version keeps its key.
- The first version of an id is "since ever", as in V0043.
- An id whose history disagrees with the dimension row on a versioned column the row holds (for example, a direct SQL outlet move that `outlet_placement_history` did not see) is left on V0043's projector-date behaviour until the history catches up.
- The dimension triggers are now statement level (`dw.dim_versions_follow`): one rekey per projector statement for the ids whose columns moved, so the projector's `refreshDimensions` needs no change. An `updated_at`-only refresh costs one transition-table comparison.

## Facts: the exact re-stamp condition (lead condition 1)
The rekey re-stamps facts itself, so the range rebuild needs no hook for keys:
- **Which ids.** For every natural id whose versions it changed (a version deleted, a `valid_to` moved, or a version inserted), it runs `dw.restamp_fact_keys(dim, ids, from_date)`. `from_date` is the earliest `valid_from` among those changed versions; "since ever" means all dates.
- **What it writes.** For the `fact_memo` and `fact_visit` rows of those ids (`route_id` for geo, `outlet_id` for outlet, `user_id` for user) dated on or after `from_date`, it sets `geo_key`/`outlet_key`/`user_key = dw.<x>_key_on(id, business_date)` wherever that differs, and sets `updated_at = now()`. An incremental reader by `updated_at` therefore sees the change.
- **Product.** No fact stores `product_key`.
- **Ids the history does not date.** These take V0043's projector-date step, and their facts from today on are re-stamped.
- **Your range rebuild.** It still recomputes keys with `*_key_on` as today. It only needs to run for keys if someone edits `dw.*_version` by hand.

## What backend-reports does (optional)
1. After V0123 is on INT, the facts are already re-stamped by the migration. For a later back-dated history change or an `app.rebuild_master_history`, run `SELECT * FROM dw.rekey_dim_versions()`; it re-stamps the facts of the ids it changed. No range rebuild is needed for the keys.
2. If past versions should carry a renamed former parent's new name, call the full rekey nightly. It costs 0.2 s at pilot ×10 and 0.9 s at 27k outlets, measured locally. The trigger renames only the ids whose dimension row changed.
3. **Ask (backend-analytics owns the Aggregator): close a race.** A rekey deletes a key and re-stamps the facts that are committed. A fact upsert whose statement started before that commit can still write the old key. Either fix is enough:
   - (a) at the start of a fact-writing transaction that writes no dimension, take `pg_advisory_xact_lock_shared(hashtext('dw.rekey_dim_versions'), hashtext(d))` for `d` = `'dim_geo'`, `'dim_outlet'`, `'dim_user'`, in that order; the rekey takes them exclusively. Never take them in a transaction that later writes a dimension: two sessions moving from shared to exclusive would deadlock;
   - (b) widen the 7-day key fix-up in `refreshDimensions` from `geo_key IS NULL ...` to `geo_key IS DISTINCT FROM dw.geo_key_on(route_id, business_date) ...`. It is cheap on the indexed `(route_id, business_date)`.
4. Kotlin side: nothing else is required. `geo_key_on`, `outlet_key_on`, `product_key_on` and `user_key_on` keep their signatures.
