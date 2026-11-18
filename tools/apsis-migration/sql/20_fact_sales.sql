-- trans_sales_by_outlet -> apsis_hist.sales_line, one chunk (month of the vendor date). One transaction, idempotent:
-- the chunk's earlier rows are deleted and re-inserted; keys are the deterministic row_uuid; no float anywhere.
-- psql variables: chunk, src_tz, qty_unit ('base' | 'pack').
CREATE TEMP TABLE _src ON COMMIT DROP AS
SELECT DISTINCT ON (c._row_uuid)
       c._row_uuid, btrim(c.outlet_code::text) AS outlet_code, NULLIF(btrim(c.sku_code::text), '') AS sku_code,
       NULLIF(btrim(c.route_code::text), '') AS route_code, lower(NULLIF(btrim(c.user_code::text), '')) AS user_code,
       CASE WHEN length(btrim(c.sale_date::text)) = 10 THEN btrim(c.sale_date::text)::date
            ELSE app.dhaka_date(btrim(c.sale_date::text)::timestamp AT TIME ZONE :'src_tz') END AS bd,
       COALESCE(NULLIF(btrim(c.qty::text), '')::numeric, 0) AS qty,
       COALESCE(NULLIF(btrim(c.amount::text), '')::numeric, 0) AS amt,
       COALESCE(NULLIF(btrim(c.is_zero::text), '')::numeric, 0) AS zflag
  FROM @C_SALES@ c
 WHERE c._chunk = :'chunk' AND c.outlet_code IS NOT NULL AND c.sale_date IS NOT NULL
   AND btrim(c.sale_date::text) <> '' AND left(btrim(c.sale_date::text), 4) <> '0000'
 ORDER BY c._row_uuid;

SELECT apsis_hist.ensure_partition('sales_line', d) FROM (SELECT DISTINCT bd AS d FROM _src) x;
DELETE FROM apsis_hist.sales_line WHERE chunk_key = :'chunk';
INSERT INTO apsis_hist.sales_line (row_uuid, business_date, chunk_key, outlet_code, outlet_id, sku_code, sku_id, route_code, route_id,
                                   zone_id, territory_id, division_id, wing_id, user_code, qty_base, gross_mtk, is_zero_sale)
SELECT s._row_uuid, s.bd, :'chunk', s.outlet_code, og.outlet_id, s.sku_code, k.sku_id, s.route_code,
       COALESCE(rg.route_id, og.route_id), COALESCE(rg.zone_id, og.zone_id), COALESCE(rg.territory_id, og.territory_id),
       COALESCE(rg.division_id, og.division_id), COALESCE(rg.wing_id, og.wing_id), s.user_code,
       -- quantity in base units: vendor quantity x pack factor when the vendor counts packs (Q9), half up
       (sign(s.qty) * floor(abs(s.qty * CASE WHEN :'qty_unit' = 'pack' THEN COALESCE(k.base_per_pack, 1) ELSE 1 END) + 0.5))::bigint,
       -- integer milli-taka from the decimal taka amount, half away from zero, exact numeric arithmetic
       (sign(s.amt) * floor(abs(s.amt) * 1000 + 0.5))::bigint,
       (s.zflag = 1 OR s.sku_code IS NULL OR (s.qty = 0 AND s.amt = 0))
  FROM _src s
  LEFT JOIN apsis_map.outlet_geo og ON og.outlet_code = s.outlet_code
  LEFT JOIN apsis_map.route_geo rg ON rg.route_code = s.route_code
  LEFT JOIN apsis_map.sku_map k ON k.sku_code = s.sku_code;
