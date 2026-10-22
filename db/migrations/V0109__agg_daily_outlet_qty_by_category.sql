-- V0109 dw.agg_daily_outlet: sold quantity per category in that category's base unit, replacing the mixed-unit
-- sold_qty_base on the stable view (backend-reports request docs/requests/db-backend-reports-outlet-qty.md, qa
-- backend-reports-qa-1, lead ruling 2026-10-22: a mixed-unit quantity must not stay on the dw surface; docs/16 P8).
-- sold_qty_base was sum(memo_line.qty_base) over every SKU of the outlet-day, i.e. cigarette and bidi sticks, lighter
-- pieces and match dozens added into one number; no API reads it any more.
-- 1. Four columns, one per app.sku.category_code (the CHECK allows exactly these four), each in the category's base
--    unit: cigarette and bidi sticks, lighter pieces, match dozens. NOT NULL DEFAULT 0 is a catalogue-only change.
-- 2. Backfill of every existing row with the Aggregator's rule (active memos with lines, non-voided sale lines, every
--    route's memos of the outlet-day, as its rebuildAggOutlet), split by the SKU's category. Later rebuilds are the
--    Aggregator's (backend-reports fills the four columns in the same route-day rebuild).
-- 3. dw.v_daily_outlet loses sold_qty_base and gains the four columns (a view column cannot be dropped in place:
--    the view is recreated with its grants and comments; the data dictionary's change log records the removal).
-- Expand/contract: the table column stays for now, marked deprecated, because the deployed Aggregator still writes
-- it; a later migration drops it once the Aggregator no longer does (backend-reports says when).

SET lock_timeout = '5s';

ALTER TABLE dw.agg_daily_outlet
  ADD COLUMN cigarette_sold_sticks bigint NOT NULL DEFAULT 0,
  ADD COLUMN bidi_sold_sticks bigint NOT NULL DEFAULT 0,
  ADD COLUMN lighter_sold_pieces bigint NOT NULL DEFAULT 0,
  ADD COLUMN match_sold_dozens bigint NOT NULL DEFAULT 0;

UPDATE dw.agg_daily_outlet a
   SET cigarette_sold_sticks = q.cigarette, bidi_sold_sticks = q.bidi, lighter_sold_pieces = q.lighter, match_sold_dozens = q.match
  FROM (
    SELECT m.business_date, m.outlet_id,
           coalesce(sum(ml.qty_base) FILTER (WHERE s.category_code = 'cigarette'), 0) AS cigarette,
           coalesce(sum(ml.qty_base) FILTER (WHERE s.category_code = 'bidi'), 0) AS bidi,
           coalesce(sum(ml.qty_base) FILTER (WHERE s.category_code = 'lighter'), 0) AS lighter,
           coalesce(sum(ml.qty_base) FILTER (WHERE s.category_code = 'match'), 0) AS match
      FROM app.memo_line ml
      JOIN app.memo m ON m.client_uuid = ml.memo_client_uuid AND m.business_date = ml.business_date
      JOIN app.sku s ON s.id = ml.sku_id
     WHERE m.status = 'active' AND m.line_count > 0 AND m.voided_at IS NULL
       AND ml.voided_at IS NULL AND ml.line_kind = 'sale'
     GROUP BY m.business_date, m.outlet_id
  ) q
 WHERE a.business_date = q.business_date AND a.outlet_id = q.outlet_id;

-- Lead ruling 2026-10-22: the mixed-unit column leaves the stable view (change log in docs/data-dictionary.md); the view
-- is recreated at once in this transaction with every other column, its grants and comments. No view depends on it.
-- squawk-ignore ban-drop-view
DROP VIEW dw.v_daily_outlet;
CREATE VIEW dw.v_daily_outlet AS
SELECT a.business_date, a.outlet_id, o.outlet_code, o.outlet_name, coalesce(a.route_id, o.route_id) AS route_id,
       o.cluster_id, o.zone_id, o.channel, o.sub_channel_id, o.geo_class, o.outlet_kind, o.location_confirmed,
       a.visited, a.geo_valid, a.active_memo_count, a.cigarette_sold_sticks, a.bidi_sold_sticks, a.lighter_sold_pieces,
       a.match_sold_dozens, a.net_mtk, a.due_mtk, a.dues_collected_mtk, a.updated_at
  FROM dw.agg_daily_outlet a
  LEFT JOIN dw.dim_outlet o ON o.outlet_id = a.outlet_id;

-- The role grants of the map (V0014), as for every new dw object.
SELECT app.apply_db_role_grants();

COMMENT ON COLUMN dw.agg_daily_outlet.sold_qty_base IS 'DEPRECATED (V0109), dropped by a later migration: mixed base units (sticks, pieces and dozens added together), never aggregate it; use the four *_sold_* columns.';
COMMENT ON COLUMN dw.agg_daily_outlet.cigarette_sold_sticks IS 'Cigarettes sold, in sticks: sum of qty_base of the active memos'' non-voided sale lines of cigarette SKUs, every route of the outlet-day (V0109).';
COMMENT ON COLUMN dw.agg_daily_outlet.bidi_sold_sticks IS 'Bidi sold, in sticks: sum of qty_base of the active memos'' non-voided sale lines of bidi SKUs, every route of the outlet-day (V0109).';
COMMENT ON COLUMN dw.agg_daily_outlet.lighter_sold_pieces IS 'Lighters sold, in pieces: sum of qty_base of the active memos'' non-voided sale lines of lighter SKUs, every route of the outlet-day (V0109).';
COMMENT ON COLUMN dw.agg_daily_outlet.match_sold_dozens IS 'Matches sold, in dozens: sum of qty_base of the active memos'' non-voided sale lines of match SKUs, every route of the outlet-day (V0109).';

COMMENT ON VIEW dw.v_daily_outlet IS 'Stable view: per outlet and business date, visit, geo validity, sales per category in base units and dues, with the outlet''s classification.
owner: worker | capture: SERVER | retention: event_fact | pii: none';
COMMENT ON COLUMN dw.v_daily_outlet.business_date IS 'Asia/Dhaka business date of the row (cutoff 00:00 Dhaka); all day-level rollups key off it.';
COMMENT ON COLUMN dw.v_daily_outlet.outlet_id IS 'Outlet (app.outlet).';
COMMENT ON COLUMN dw.v_daily_outlet.outlet_code IS 'Outlet code.';
COMMENT ON COLUMN dw.v_daily_outlet.outlet_name IS 'Outlet name.';
COMMENT ON COLUMN dw.v_daily_outlet.route_id IS 'Route (app.route) being worked.';
COMMENT ON COLUMN dw.v_daily_outlet.cluster_id IS 'Outlet cluster (market group) inside the zone.';
COMMENT ON COLUMN dw.v_daily_outlet.zone_id IS 'Zone (app.zone).';
COMMENT ON COLUMN dw.v_daily_outlet.channel IS 'Outlet channel: GT, DCC, Astha, RCC, MT or HoReCa.';
COMMENT ON COLUMN dw.v_daily_outlet.sub_channel_id IS 'Outlet sub-channel (app.sub_channel).';
COMMENT ON COLUMN dw.v_daily_outlet.geo_class IS 'Geographic class of the outlet area: Hill, Urban, SemiUrban or Rural.';
COMMENT ON COLUMN dw.v_daily_outlet.outlet_kind IS 'retail or wholesale.';
COMMENT ON COLUMN dw.v_daily_outlet.location_confirmed IS 'True when the outlet pin is confirmed.';
COMMENT ON COLUMN dw.v_daily_outlet.visited IS 'True when the outlet had an SR visit that was not abandoned.';
COMMENT ON COLUMN dw.v_daily_outlet.geo_valid IS 'True when a visit was in range by the server''s re-check.';
COMMENT ON COLUMN dw.v_daily_outlet.active_memo_count IS 'Number of active (not voided or superseded) memos with at least one line.';
COMMENT ON COLUMN dw.v_daily_outlet.cigarette_sold_sticks IS 'Cigarettes sold, in sticks (V0109; replaces the mixed-unit sold_qty_base).';
COMMENT ON COLUMN dw.v_daily_outlet.bidi_sold_sticks IS 'Bidi sold, in sticks (V0109).';
COMMENT ON COLUMN dw.v_daily_outlet.lighter_sold_pieces IS 'Lighters sold, in pieces (V0109).';
COMMENT ON COLUMN dw.v_daily_outlet.match_sold_dozens IS 'Matches sold, in dozens (V0109).';
COMMENT ON COLUMN dw.v_daily_outlet.net_mtk IS 'Net payable value in milli-taka (gross minus discounts and QC deduction, rounded to the paisa).';
COMMENT ON COLUMN dw.v_daily_outlet.due_mtk IS 'Amount left on credit (due) in milli-taka.';
COMMENT ON COLUMN dw.v_daily_outlet.dues_collected_mtk IS 'Dues collected against earlier credit memos, in milli-taka.';
COMMENT ON COLUMN dw.v_daily_outlet.updated_at IS 'UTC instant of the last update.';
