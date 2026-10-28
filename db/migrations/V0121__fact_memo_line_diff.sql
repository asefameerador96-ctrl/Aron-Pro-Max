-- V0121 dw.fact_memo_line follows its memo by diff, not by rewrite (fleet follow-up (a) of the db session 12 handover,
-- lead 2026-10-28; D-DB-MEMO-LINE-DIFF-01). Before, every write of a dw.fact_memo row (the projector upserts every memo
-- of a route-day it rebuilds, changed or not) deleted and re-inserted all lines of that memo: at fleet volume that is
-- millions of dead rows a day in the line partitions for no change. Now dw.sync_fact_memo_lines():
--   * DELETE of the memo fact, or an UPDATE that moves its key (memo uuid or business date): the lines under the old
--     key go, as before;
--   * INSERT or UPDATE: lines under the memo's key that app.memo_line no longer has are deleted; every source line is
--     upserted on (line_client_uuid, business_date) and an existing line is updated only when one of its columns
--     differs, stamping updated_at then. Source lines identical to their fact row are filtered out before the upsert, so
--     an unchanged memo neither writes a new row version nor takes a row lock (ON CONFLICT ... DO UPDATE ... WHERE false
--     would still lock and WAL-log each conflicting row); the upsert's own WHERE stays as a guard for a concurrent change.
--   * app.restamp_data_origin (V0116) skips dw tables without an origin trigger: dw.fact_memo_line has none (its origin
--     is the source line's), and the fact_memo pass already refreshes its lines through this diff, so the extra
--     "SET data_origin = data_origin" pass over every line row is dropped.
-- Same result as the rewrite (ReuseSurfaceTest's earlier cases unchanged; its new case covers the diff); the TRUNCATE trigger is unchanged.

SET lock_timeout = '5s';

CREATE OR REPLACE FUNCTION dw.sync_fact_memo_lines() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'DELETE' OR (TG_OP = 'UPDATE' AND (OLD.memo_client_uuid, OLD.business_date) IS DISTINCT FROM (NEW.memo_client_uuid, NEW.business_date)) THEN
    DELETE FROM dw.fact_memo_line WHERE memo_client_uuid = OLD.memo_client_uuid AND business_date = OLD.business_date;
  END IF;
  IF TG_OP IN ('INSERT', 'UPDATE') THEN
    -- Lines the memo no longer has (none in normal operation: lines are never deleted at the source).
    DELETE FROM dw.fact_memo_line f
     WHERE f.memo_client_uuid = NEW.memo_client_uuid AND f.business_date = NEW.business_date
       AND NOT EXISTS (SELECT 1 FROM app.memo_line l
                        WHERE l.client_uuid = f.line_client_uuid AND l.business_date = f.business_date
                          AND l.memo_client_uuid = NEW.memo_client_uuid);
    INSERT INTO dw.fact_memo_line AS f (business_date, line_client_uuid, memo_client_uuid, line_no, user_id, route_id, outlet_id,
                                        sku_id, line_kind, qty_entered, unit_entered, pack_factor, qty_base, price_type,
                                        base_price_mtk, price_per_qty, gross_mtk, offer_id, voided, memo_status, data_origin)
    SELECT l.business_date, l.client_uuid, l.memo_client_uuid, l.line_no, NEW.user_id, NEW.route_id, NEW.outlet_id,
           l.sku_id, l.line_kind, l.qty_entered, l.unit_entered, l.pack_factor, l.qty_base, l.price_type,
           l.base_price_mtk, l.price_per_qty, l.gross_mtk, l.offer_id, l.voided_at IS NOT NULL, NEW.status, l.data_origin
      FROM app.memo_line l
     WHERE l.memo_client_uuid = NEW.memo_client_uuid AND l.business_date = NEW.business_date
       AND NOT EXISTS (SELECT 1 FROM dw.fact_memo_line x
                        WHERE x.line_client_uuid = l.client_uuid AND x.business_date = l.business_date
                          AND (x.memo_client_uuid, x.line_no, x.user_id, x.route_id, x.outlet_id, x.sku_id, x.line_kind,
                               x.qty_entered, x.unit_entered, x.pack_factor, x.qty_base, x.price_type, x.base_price_mtk,
                               x.price_per_qty, x.gross_mtk, x.offer_id, x.voided, x.memo_status, x.data_origin)
                              IS NOT DISTINCT FROM
                              (l.memo_client_uuid, l.line_no, NEW.user_id, NEW.route_id, NEW.outlet_id, l.sku_id, l.line_kind,
                               l.qty_entered, l.unit_entered, l.pack_factor, l.qty_base, l.price_type, l.base_price_mtk,
                               l.price_per_qty, l.gross_mtk, l.offer_id, l.voided_at IS NOT NULL, NEW.status, l.data_origin))
    ON CONFLICT (line_client_uuid, business_date) DO UPDATE
       SET memo_client_uuid = EXCLUDED.memo_client_uuid, line_no = EXCLUDED.line_no, user_id = EXCLUDED.user_id,
           route_id = EXCLUDED.route_id, outlet_id = EXCLUDED.outlet_id, sku_id = EXCLUDED.sku_id,
           line_kind = EXCLUDED.line_kind, qty_entered = EXCLUDED.qty_entered, unit_entered = EXCLUDED.unit_entered,
           pack_factor = EXCLUDED.pack_factor, qty_base = EXCLUDED.qty_base, price_type = EXCLUDED.price_type,
           base_price_mtk = EXCLUDED.base_price_mtk, price_per_qty = EXCLUDED.price_per_qty, gross_mtk = EXCLUDED.gross_mtk,
           offer_id = EXCLUDED.offer_id, voided = EXCLUDED.voided, memo_status = EXCLUDED.memo_status,
           data_origin = EXCLUDED.data_origin, updated_at = now()
     WHERE (f.memo_client_uuid, f.line_no, f.user_id, f.route_id, f.outlet_id, f.sku_id, f.line_kind, f.qty_entered,
            f.unit_entered, f.pack_factor, f.qty_base, f.price_type, f.base_price_mtk, f.price_per_qty, f.gross_mtk,
            f.offer_id, f.voided, f.memo_status, f.data_origin)
           IS DISTINCT FROM
           (EXCLUDED.memo_client_uuid, EXCLUDED.line_no, EXCLUDED.user_id, EXCLUDED.route_id, EXCLUDED.outlet_id,
            EXCLUDED.sku_id, EXCLUDED.line_kind, EXCLUDED.qty_entered, EXCLUDED.unit_entered, EXCLUDED.pack_factor,
            EXCLUDED.qty_base, EXCLUDED.price_type, EXCLUDED.base_price_mtk, EXCLUDED.price_per_qty, EXCLUDED.gross_mtk,
            EXCLUDED.offer_id, EXCLUDED.voided, EXCLUDED.memo_status, EXCLUDED.data_origin);
  END IF;
  RETURN NULL;
END $$;
COMMENT ON FUNCTION dw.sync_fact_memo_lines() IS 'AFTER INSERT/UPDATE/DELETE on dw.fact_memo: brings that memo''s rows of dw.fact_memo_line in line with app.memo_line by diff (deletes lines it no longer has, inserts new ones, updates a line only when a column differs); a key move or delete removes the old key''s lines.';

-- restamp: the dw pass touches only tables with an origin trigger (same function as V0116 otherwise).
CREATE OR REPLACE FUNCTION app.restamp_data_origin(p_from date DEFAULT NULL) RETURNS bigint
LANGUAGE plpgsql
AS $$
DECLARE
  r       record;
  args    text;
  has_bd  boolean;
  n       bigint;
  total   bigint := 0;
BEGIN
  FOR r IN SELECT * FROM app.data_origin_capture ORDER BY pass, table_name LOOP
    args := CASE WHEN r.parent IS NULL THEN '' ELSE format(', %L, %L, %L', r.parent, r.child_col, r.parent_col) END;
    has_bd := EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = format('app.%I', r.table_name)::regclass
                        AND attname = 'business_date' AND NOT attisdropped);
    EXECUTE format('UPDATE app.%I x SET data_origin = app.derive_data_origin(to_jsonb(x)%s)
                     WHERE %s data_origin IS DISTINCT FROM app.derive_data_origin(to_jsonb(x)%s)',
                   r.table_name, args, CASE WHEN has_bd THEN '($1 IS NULL OR business_date >= $1) AND' ELSE '' END, args)
      USING p_from;
    GET DIAGNOSTICS n = ROW_COUNT;
    total := total + n;
  END LOOP;
  -- dw: a write fires the table's origin trigger, which recomputes the value. Tables without one (dw.fact_memo_line,
  -- refreshed through its memo fact, V0121) are skipped.
  FOR r IN SELECT c.oid::regclass AS rel, EXISTS (SELECT 1 FROM pg_attribute b WHERE b.attrelid = c.oid AND b.attname = 'business_date') AS bd
             FROM pg_class c JOIN pg_namespace s ON s.oid = c.relnamespace JOIN pg_attribute a ON a.attrelid = c.oid
            WHERE s.nspname = 'dw' AND c.relkind IN ('r', 'p') AND NOT c.relispartition AND a.attname = 'data_origin' AND NOT a.attisdropped
              AND EXISTS (SELECT 1 FROM pg_trigger t WHERE t.tgrelid = c.oid AND t.tgname = c.relname || '_origin')
            ORDER BY 1 LOOP
    EXECUTE format('UPDATE %s SET data_origin = data_origin %s', r.rel, CASE WHEN r.bd THEN 'WHERE $1 IS NULL OR business_date >= $1' ELSE '' END)
      USING p_from;
  END LOOP;
  RETURN total;
END $$;
COMMENT ON FUNCTION app.restamp_data_origin(date) IS 'Re-derives data_origin on every registry table (rows on or after p_from; null = all) from the current tags of users, outlets, routes and parents, then refreshes dw (tables with an origin trigger; memo lines follow their memo fact). Owner only (the origin guard refuses other roles). Returns the number of app rows changed.';
