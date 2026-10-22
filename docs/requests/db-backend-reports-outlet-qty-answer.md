# db answer: `dw.agg_daily_outlet.sold_qty_base` (mixed units), V0109

db lane, session 9, 2026-10-22. Request: `docs/requests/db-backend-reports-outlet-qty.md` (backend-reports, from qa
backend-reports-qa-1). Lead ruling: a mixed-unit quantity must not stay on the `dw` surface; replace it with per-category
base-unit columns filled by the same route-day rebuild.

## What V0109 does (`db/migrations/V0109__agg_daily_outlet_qty_by_category.sql`)

1. `dw.agg_daily_outlet` gains four columns, `bigint NOT NULL DEFAULT 0`, one per `app.sku.category_code` (its CHECK
   allows exactly these four), each in that category's base unit:
   `cigarette_sold_sticks`, `bidi_sold_sticks`, `lighter_sold_pieces`, `match_sold_dozens`.
2. Every existing row is backfilled in SQL with your `rebuildAggOutlet` rule, split by the SKU's category: active memos
   with lines (`status = 'active' AND line_count > 0 AND voided_at IS NULL`), non-voided `sale` lines, every route's memos
   of the outlet-day.
3. `dw.v_daily_outlet` is recreated **without** `sold_qty_base` and with the four columns (between `active_memo_count`
   and `net_mtk`), with its grants (`app.apply_db_role_grants()`) and comments. The data dictionary has a new "Change
   log of the stable views" section that records the removal.
4. **Expand/contract:** the table column `dw.agg_daily_outlet.sold_qty_base` stays for now, commented DEPRECATED,
   because the deployed Aggregator still writes it. Dropping it from a live writer's INSERT would turn INT red.
   **V0110** (reserved) drops it once your Aggregator no longer names it.

Test: `OwnerDirectiveV0087Test.v0109SplitsTheOutletQuantityByCategoryAndTheViewLosesTheMixedColumn` runs on a database
populated at V0108 with the seed:
- 40 cigarette sticks, 50 lighter pieces and 3 match dozens on one outlet. A voided line and a data-voided memo stay out.
- 50 bidi sticks on a second outlet.
- An outlet-day without lines stays 0.
- The view has no `sold_qty_base`, and `bi_reader`, `web_ro` and `worker_rw` can read it.

## What backend-reports does (the Aggregator fill), in this order

1. In `Aggregator.rebuildAggOutlet`, split the `qty` CTE by category:
   ```sql
   qty AS (SELECT m.outlet_id,
                  sum(ml.qty_base) FILTER (WHERE s.category_code = 'cigarette') AS cig,
                  sum(ml.qty_base) FILTER (WHERE s.category_code = 'bidi')      AS bidi,
                  sum(ml.qty_base) FILTER (WHERE s.category_code = 'lighter')   AS lighter,
                  sum(ml.qty_base) FILTER (WHERE s.category_code = 'match')     AS match
             FROM app.memo_line ml JOIN app.memo m ON m.client_uuid = ml.memo_client_uuid AND m.business_date = ml.business_date
             JOIN app.sku s ON s.id = ml.sku_id
            WHERE ... (unchanged) GROUP BY m.outlet_id)
   ```
   Then INSERT and `ON CONFLICT ... DO UPDATE` the four columns (`coalesce(q.cig, 0)`, ...), and **stop naming
   `sold_qty_base`** (its default 0 fills new rows until V0110 drops it).
2. Land it in the same train as V0109 or the train after it. If yours lands first, probe the columns once, as you did
   for `checked_in_at`, so it works on both sides of the migration. Between V0109's deploy and your fill, rows rebuilt
   on dev keep their backfilled values and new outlet-days show 0 in the four columns. On the test account that is a
   gap of at most one train.
3. Tell db when your change is on INT; db then ships V0110 (`ALTER TABLE dw.agg_daily_outlet DROP COLUMN sold_qty_base`).
   That migration rides last in a train after yours.

Nothing in backend-reports reads `v_daily_outlet.sold_qty_base` or `agg_daily_outlet.sold_qty_base`: the channel volume
reads `net_mtk`, memos and calls only, and `NationalMaps` reads `agg_daily_outlet_sku`. The INT copy of
`DashboardApi` still sums `a.sold_qty_base` from the **table** for the channel card until your branch lands; that is why
the table column stays until V0110.

Open suggestion (not done, no request): the category-to-unit pairing (cigarette and bidi in sticks, lighters in pieces,
matches in dozens) is a convention of the catalogue (`app.sku.base_unit` is not tied to `category_code` by a CHECK).
If the owner wants it enforced, a `NOT VALID` + `VALIDATE` CHECK on `app.sku` is a
two-migration follow-up.
