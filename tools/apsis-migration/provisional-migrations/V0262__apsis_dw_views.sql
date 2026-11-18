-- V0262 (PROVISIONAL, lane M-APSIS1): read views over the archive, for analytics roles only. A view family separate from the
-- live dw views: reports that must include history read these; day/live reports do not.
CREATE VIEW dw.v_hist_sales_line AS
  SELECT business_date, outlet_id, outlet_code, sku_id, sku_code, route_id, route_code, zone_id, territory_id, division_id, wing_id,
         user_code, qty_base, gross_mtk, is_zero_sale
    FROM apsis_hist.sales_line;
CREATE VIEW dw.v_hist_sales_day AS
  SELECT business_date, outlet_id, outlet_code, sku_id, route_id, zone_id, territory_id, division_id, wing_id,
         sum(qty_base) AS qty_base, sum(gross_mtk) AS gross_mtk, bool_and(is_zero_sale) AS is_zero_sale
    FROM apsis_hist.sales_line
   GROUP BY business_date, outlet_id, outlet_code, sku_id, route_id, zone_id, territory_id, division_id, wing_id;
CREATE VIEW dw.v_hist_visit_geo AS
  SELECT business_date, outlet_id, outlet_code, route_id, zone_id, territory_id, division_id, wing_id, captured_at,
         lat, lng, accuracy_m, distance_m, geo_validated
    FROM apsis_hist.visit_geo;
CREATE VIEW dw.v_hist_attendance AS
  SELECT business_date, user_code, check_in_at, check_out_at FROM apsis_hist.attendance;
COMMENT ON VIEW dw.v_hist_sales_line IS 'Apsis history (archive): sales lines by outlet, SKU and day, base units and milli-taka. Not live data.';
COMMENT ON VIEW dw.v_hist_sales_day IS 'Apsis history: sales per outlet, SKU and day (vendor duplicates summed).';
COMMENT ON VIEW dw.v_hist_visit_geo IS 'Apsis history: visit geo rows by outlet and day.';
COMMENT ON VIEW dw.v_hist_attendance IS 'Apsis history: field-force attendance (no device data).';
DO $$ BEGIN
  -- views run with their owner's rights: the analytics roles get the views only, never the archive tables.
  GRANT SELECT ON dw.v_hist_sales_line, dw.v_hist_sales_day, dw.v_hist_visit_geo, dw.v_hist_attendance TO bi_reader, web_ro;
END $$;
