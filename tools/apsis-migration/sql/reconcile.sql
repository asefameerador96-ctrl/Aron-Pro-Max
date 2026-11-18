-- Reconciliation. Each section is one query returning ONE jsonb of numbers (never a name, phone, code or memo text);
-- "ok" false fails the report. Sections are split on the "-- name:" lines by runner.reconcile().
-- name: tables
SELECT jsonb_build_object('ok', bool_and(rows_in >= rows_out AND NOT has_failed),
  'by_table', COALESCE(jsonb_object_agg(vendor_table, jsonb_build_object('rows_in', rows_in, 'rows_loaded', rows_out, 'skipped_no_date_or_key', rows_in - rows_out)), '{}'::jsonb))
FROM (SELECT vendor_table, sum(rows_in) AS rows_in, sum(rows_out) AS rows_out, bool_or(state = 'failed') AS has_failed
        FROM apsis_ctl.chunk WHERE chunk_key <> '_stage' AND vendor_table NOT LIKE '\_%' GROUP BY vendor_table) t;
-- name: months
SELECT jsonb_build_object('ok', COALESCE(bool_and(state = 'verified' AND rows_in >= rows_out), true),
  'by_month', COALESCE(jsonb_object_agg(vendor_table || ' ' || chunk_key, jsonb_build_object('rows_in', rows_in, 'rows_loaded', rows_out)), '{}'::jsonb))
FROM apsis_ctl.chunk WHERE chunk_key <> '_stage' AND chunk_key <> 'all' AND vendor_table NOT LIKE '\_%';
-- name: daily_money
WITH tz AS (SELECT source_tz FROM apsis_ctl.run ORDER BY id DESC LIMIT 1),
s AS (SELECT CASE WHEN length(btrim(sale_date::text)) = 10 THEN btrim(sale_date::text)::date
                  ELSE app.dhaka_date(btrim(sale_date::text)::timestamp AT TIME ZONE (SELECT source_tz FROM tz)) END AS bd,
             sum(round(COALESCE(NULLIF(btrim(amount::text), ''), '0')::numeric * 1000)) AS g
        FROM (SELECT DISTINCT ON (_row_uuid) * FROM apsis_stage.c_sales
               WHERE outlet_code IS NOT NULL AND sale_date IS NOT NULL AND btrim(sale_date::text) <> '' AND left(btrim(sale_date::text), 4) <> '0000'
               ORDER BY _row_uuid) c GROUP BY 1),
h AS (SELECT business_date AS bd, sum(gross_mtk) AS g FROM apsis_hist.sales_line GROUP BY 1)
SELECT jsonb_build_object('days_vendor', count(s.bd), 'days_loaded', count(h.bd),
  'days_differing', count(*) FILTER (WHERE s.g IS DISTINCT FROM h.g),
  'max_abs_diff_mtk', COALESCE(max(abs(COALESCE(s.g, 0) - COALESCE(h.g, 0))), 0),
  'total_vendor_mtk', COALESCE(sum(s.g), 0), 'total_loaded_mtk', COALESCE(sum(h.g), 0),
  'ok', count(*) FILTER (WHERE s.g IS DISTINCT FROM h.g) = 0)
FROM s FULL JOIN h USING (bd);
-- name: sku_qty
WITH r AS (SELECT split_part(note, '=', 2) AS qty_unit FROM apsis_ctl.run ORDER BY id DESC LIMIT 1),
s AS (SELECT btrim(c.sku_code::text) AS sku_code,
             sum(round(COALESCE(NULLIF(btrim(c.qty::text), ''), '0')::numeric
                 * CASE WHEN (SELECT qty_unit FROM r) = 'pack' THEN COALESCE(k.base_per_pack, 1) ELSE 1 END)) AS q
        FROM (SELECT DISTINCT ON (_row_uuid) * FROM apsis_stage.c_sales
               WHERE outlet_code IS NOT NULL AND sale_date IS NOT NULL AND btrim(sale_date::text) <> '' AND left(btrim(sale_date::text), 4) <> '0000'
               ORDER BY _row_uuid) c LEFT JOIN apsis_map.sku_map k ON k.sku_code = btrim(c.sku_code::text)
       WHERE c.sku_code IS NOT NULL GROUP BY 1),
h AS (SELECT sku_code, sum(qty_base) AS q FROM apsis_hist.sales_line WHERE sku_code IS NOT NULL GROUP BY 1)
SELECT jsonb_build_object('skus_vendor', count(s.sku_code), 'skus_loaded', count(h.sku_code),
  'skus_differing', count(*) FILTER (WHERE s.q IS DISTINCT FROM h.q), 'ok', count(*) FILTER (WHERE s.q IS DISTINCT FROM h.q) = 0)
FROM s FULL JOIN h USING (sku_code);
-- name: outlets_per_route
WITH v AS (SELECT btrim(route_code::text) AS route_code, count(DISTINCT btrim(outlet_code::text)) AS n
             FROM apsis_stage.c_outlet_route WHERE valid_to IS NULL OR left(btrim(valid_to::text), 4) IN ('', '0000') GROUP BY 1),
o AS (SELECT r.code AS route_code, count(*) AS n FROM app.outlet o JOIN app.route r ON r.id = o.route_id GROUP BY 1)
SELECT jsonb_build_object('routes_vendor', count(v.route_code), 'routes_ours', count(o.route_code),
  'routes_with_different_outlet_count', count(*) FILTER (WHERE v.n IS DISTINCT FROM o.n), 'ok', true)   -- informational: the live master may differ legitimately
FROM v FULL JOIN o USING (route_code);
-- name: memo_numbers
WITH m AS (SELECT btrim(memo_no::text) AS memo_no, count(DISTINCT btrim(outlet_code::text)) AS n
             FROM apsis_stage.c_sales WHERE memo_no IS NOT NULL AND btrim(memo_no::text) <> '' GROUP BY 1)
SELECT jsonb_build_object('applicable', count(*) > 0, 'memo_numbers', count(*), 'numbers_on_more_than_one_outlet', count(*) FILTER (WHERE n > 1),
  'ok', count(*) FILTER (WHERE n > 1) = 0) FROM m;
-- name: section_summary
WITH v AS (SELECT route_code, business_date, visited FROM apsis_hist.route_day_vendor),
o AS (SELECT route_code, business_date, count(DISTINCT outlet_code) AS n FROM apsis_hist.visit_geo WHERE route_code IS NOT NULL GROUP BY 1, 2)
SELECT jsonb_build_object('route_days_vendor', count(v.route_code), 'route_days_recomputed', count(o.route_code),
  'route_days_visited_differ', count(*) FILTER (WHERE v.route_code IS NOT NULL AND o.route_code IS NOT NULL AND v.visited IS DISTINCT FROM o.n), 'ok', true)
FROM v FULL JOIN o USING (route_code, business_date);
-- name: control
SELECT jsonb_build_object(
  'default_partition_rows', (SELECT (SELECT count(*) FROM apsis_hist.sales_line_default) + (SELECT count(*) FROM apsis_hist.visit_geo_default)
                                  + (SELECT count(*) FROM apsis_hist.attendance_default)),
  'sales_rows_without_outlet_id', (SELECT count(*) FROM apsis_hist.sales_line WHERE outlet_id IS NULL),
  'sales_rows_without_sku_id', (SELECT count(*) FROM apsis_hist.sales_line WHERE sku_code IS NOT NULL AND sku_id IS NULL),
  'chunks_not_verified', (SELECT count(*) FROM apsis_ctl.chunk WHERE chunk_key <> '_stage' AND vendor_table NOT LIKE '\_%' AND state <> 'verified'),
  'ok', (SELECT count(*) FROM apsis_hist.sales_line_default) + (SELECT count(*) FROM apsis_hist.visit_geo_default)
        + (SELECT count(*) FROM apsis_hist.attendance_default) = 0
        AND (SELECT count(*) FROM apsis_ctl.chunk WHERE chunk_key <> '_stage' AND vendor_table NOT LIKE '\_%' AND state <> 'verified') = 0);
