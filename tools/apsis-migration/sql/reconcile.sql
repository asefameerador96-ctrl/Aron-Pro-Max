-- Reconciliation queries. Each section returns ONE jsonb of NUMBERS (never a name, phone, code or memo text). The pass/fail decision is made
-- in gates.py against the thresholds, so a section here never says "ok" on its own. Sections are split on the "-- name:" lines by runner.reconcile().
-- name: daily_money
WITH r AS (SELECT source_tz FROM apsis_ctl.run ORDER BY id DESC LIMIT 1),
s AS (SELECT apsis_map.business_date(sale_date::text, (SELECT source_tz FROM r)) AS bd,
             sum(round(COALESCE(apsis_map.num(amount::text), 0) * 1000)) AS g
        FROM (SELECT DISTINCT ON (_row_uuid) * FROM apsis_stage.c_sales WHERE outlet_code IS NOT NULL AND btrim(outlet_code::text) <> ''
                 AND (qty IS NULL OR btrim(qty::text) = '' OR apsis_map.num(qty::text) IS NOT NULL)
                 AND (amount IS NULL OR btrim(amount::text) = '' OR apsis_map.num(amount::text) IS NOT NULL) ORDER BY _row_uuid) c
       WHERE apsis_map.business_date(sale_date::text, (SELECT source_tz FROM r)) IS NOT NULL GROUP BY 1),
h AS (SELECT business_date AS bd, sum(gross_mtk) AS g FROM apsis_hist.sales_line GROUP BY 1)
SELECT jsonb_build_object('days_vendor', count(s.bd), 'days_loaded', count(h.bd),
  'days_differing', count(*) FILTER (WHERE s.g IS DISTINCT FROM h.g),
  'max_abs_diff_mtk', COALESCE(max(abs(COALESCE(s.g, 0) - COALESCE(h.g, 0))), 0),
  'total_vendor_mtk', COALESCE(sum(s.g), 0), 'total_loaded_mtk', COALESCE(sum(h.g), 0))
FROM s FULL JOIN h USING (bd);
-- name: sku_qty
WITH r AS (SELECT source_tz, qty_unit FROM apsis_ctl.run ORDER BY id DESC LIMIT 1),
s AS (SELECT btrim(c.sku_code::text) AS sku_code,
             sum(round(COALESCE(apsis_map.num(c.qty::text), 0)
                 * CASE WHEN (SELECT qty_unit FROM r) = 'pack' THEN COALESCE(k.base_per_pack, 1) ELSE 1 END)) AS q
        FROM (SELECT DISTINCT ON (_row_uuid) * FROM apsis_stage.c_sales WHERE outlet_code IS NOT NULL AND btrim(outlet_code::text) <> ''
                 AND (qty IS NULL OR btrim(qty::text) = '' OR apsis_map.num(qty::text) IS NOT NULL)
                 AND (amount IS NULL OR btrim(amount::text) = '' OR apsis_map.num(amount::text) IS NOT NULL)
                 AND apsis_map.business_date(sale_date::text, (SELECT source_tz FROM r)) IS NOT NULL ORDER BY _row_uuid) c
        LEFT JOIN apsis_map.sku_map k ON k.code_norm = apsis_map.norm_code(c.sku_code)
       WHERE c.sku_code IS NOT NULL GROUP BY 1),
h AS (SELECT sku_code, sum(qty_base) AS q FROM apsis_hist.sales_line WHERE sku_code IS NOT NULL GROUP BY 1)
SELECT jsonb_build_object('skus_vendor', count(s.sku_code), 'skus_loaded', count(h.sku_code),
  'skus_differing', count(*) FILTER (WHERE s.q IS DISTINCT FROM h.q))
FROM s FULL JOIN h USING (sku_code);
-- name: outlets_per_route
WITH v AS (SELECT btrim(route_code::text) AS route_code, count(DISTINCT btrim(outlet_code::text)) AS n
             FROM apsis_stage.c_outlet_route WHERE apsis_map.mysql_date(valid_to::text) IS NULL GROUP BY 1),
o AS (SELECT r.code AS route_code, count(*) AS n FROM app.outlet o JOIN app.route r ON r.id = o.route_id GROUP BY 1)
SELECT jsonb_build_object('routes_vendor', count(v.route_code), 'routes_ours', count(o.route_code),
  'routes_with_different_outlet_count', count(*) FILTER (WHERE v.n IS DISTINCT FROM o.n))     -- informational: the live master may differ legitimately
FROM v FULL JOIN o USING (route_code);
-- name: memo_numbers
WITH m AS (SELECT btrim(memo_no::text) AS memo_no, count(DISTINCT btrim(outlet_code::text)) AS n
             FROM apsis_stage.c_sales WHERE memo_no IS NOT NULL AND btrim(memo_no::text) <> '' GROUP BY 1)
SELECT jsonb_build_object('applicable', count(*) > 0, 'memo_numbers', count(*), 'numbers_on_more_than_one_outlet', count(*) FILTER (WHERE n > 1)) FROM m;
-- name: money_quality
SELECT jsonb_build_object('stage_lines', count(*), 'amount_null_or_blank', count(*) FILTER (WHERE amount IS NULL OR btrim(amount::text) = ''),
  'amount_negative', count(*) FILTER (WHERE apsis_map.num(amount::text) < 0), 'qty_null_or_blank', count(*) FILTER (WHERE qty IS NULL OR btrim(qty::text) = ''),
  'qty_negative', count(*) FILTER (WHERE apsis_map.num(qty::text) < 0))
FROM (SELECT DISTINCT ON (_row_uuid) * FROM apsis_stage.c_sales ORDER BY _row_uuid) c;
-- name: time_zone
-- Informational evidence only (never a pass/fail gate): the vendor's own route-day "visited" figure against our recomputation from the loaded geo rows
-- under the chosen zone, routes matched by normalised code, and only when the geo table has a route column. The zone itself is decided from the dry run's
-- evidence and signed by the owner.
WITH v AS (SELECT apsis_map.norm_code(route_code) AS rc, business_date, visited FROM apsis_hist.route_day_vendor WHERE visited IS NOT NULL),
o AS (SELECT apsis_map.norm_code(route_code) AS rc, business_date, count(DISTINCT outlet_code) AS n FROM apsis_hist.visit_geo WHERE route_code IS NOT NULL GROUP BY 1, 2)
SELECT jsonb_build_object('geo_has_route_column', (SELECT count(*) > 0 FROM apsis_hist.visit_geo WHERE route_code IS NOT NULL),
  'vendor_route_days', count(v.rc), 'route_days_equal', count(*) FILTER (WHERE v.rc IS NOT NULL AND v.visited = COALESCE(o.n, 0)),
  'chosen_zone', (SELECT source_tz FROM apsis_ctl.run ORDER BY id DESC LIMIT 1))
FROM v FULL JOIN o USING (rc, business_date);
-- name: control
SELECT jsonb_build_object(
  'default_partition_rows', (SELECT (SELECT count(*) FROM apsis_hist.sales_line_default) + (SELECT count(*) FROM apsis_hist.visit_geo_default)
                                  + (SELECT count(*) FROM apsis_hist.attendance_default) + (SELECT count(*) FROM apsis_hist.raw_archive_default)),
  'sales_rows', (SELECT count(*) FROM apsis_hist.sales_line),
  'sales_rows_without_outlet_id', (SELECT count(*) FROM apsis_hist.sales_line WHERE outlet_id IS NULL),
  'sales_rows_without_sku_id', (SELECT count(*) FROM apsis_hist.sales_line WHERE sku_code IS NOT NULL AND sku_id IS NULL),
  'sales_rows_outlet_without_scope', (SELECT count(*) FROM apsis_hist.sales_line WHERE outlet_id IS NOT NULL AND (zone_id IS NULL OR wing_id IS NULL)),
  'sales_rows_deleted_by_vendor', (SELECT count(*) FROM apsis_hist.sales_line WHERE vendor_deleted),
  'sales_lines_nonzero', (SELECT count(*) FROM apsis_hist.sales_line WHERE NOT is_zero_sale),
  'sales_lines_zero_money', (SELECT count(*) FROM apsis_hist.sales_line WHERE NOT is_zero_sale AND gross_mtk = 0),
  'sales_lines_zero_qty', (SELECT count(*) FROM apsis_hist.sales_line WHERE NOT is_zero_sale AND qty_base = 0),
  'sales_lines_negative_money', (SELECT count(*) FROM apsis_hist.sales_line WHERE gross_mtk < 0),
  'rejected_rows', (SELECT count(*) FROM apsis_hist.reject),
  'rows_loaded_all_tables', (SELECT (SELECT count(*) FROM apsis_hist.sales_line) + (SELECT count(*) FROM apsis_hist.visit_geo) + (SELECT count(*) FROM apsis_hist.attendance) + (SELECT count(*) FROM apsis_hist.raw_archive)),
  'geo_rows', (SELECT count(*) FROM apsis_hist.visit_geo),
  'geo_rows_without_outlet_id', (SELECT count(*) FROM apsis_hist.visit_geo WHERE outlet_id IS NULL),
  'chunks_not_verified', (SELECT count(*) FROM apsis_ctl.chunk WHERE chunk_key <> '_stage' AND vendor_table NOT LIKE '\_%' AND state <> 'verified'));
