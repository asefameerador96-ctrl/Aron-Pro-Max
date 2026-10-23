-- V0110 dw.agg_daily_outlet: drop the deprecated mixed-unit sold_qty_base (contract step of V0109's expand/contract;
-- backend-reports request docs/requests/db-backend-reports-outlet-qty.md, answer db-backend-reports-outlet-qty-answer.md).
-- The column added cigarette and bidi sticks, lighter pieces and match dozens into one number. V0109 took it off
-- dw.v_daily_outlet and added the four per-category columns; backend-reports' Aggregator fills those, and train 15
-- removes its last mention of sold_qty_base. This migration ships only after that change is on INT and deployed
-- (a worker that still names the column would fail every outlet rebuild with 42703). Nothing else reads or writes it:
-- no view, function or trigger depends on it (a plain
-- DROP COLUMN, no CASCADE, so a dependency would fail the migration instead of being dropped with it).
-- The sold_qty_base columns of agg_daily_route_sku, agg_daily_route_brand, agg_daily_route_segment and
-- agg_daily_outlet_sku are other tables and are not touched here.
-- Catalogue-only change (the column is marked dropped; no table rewrite).

SET lock_timeout = '5s';

-- Contract step of V0109 (the writer stopped naming it first; the view lost it in V0109).
-- squawk-ignore ban-drop-column
ALTER TABLE dw.agg_daily_outlet DROP COLUMN sold_qty_base;
