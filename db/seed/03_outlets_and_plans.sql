-- 60 outlets, 20 per seeded route, on a grid of about 70 m around the test route in Mirpur, Dhaka (route MIR-SR-D
-- starts at 23.8069 N, 90.3687 E). Coordinates are fixed (no random()), so every database gets the same pins.

INSERT INTO app.outlet (code, name, name_bn, owner_name, contact_number, address, zone_id, route_id, cluster_id, channel,
                        sub_channel_id, geo_class, lat, lng, location_basis, location_confirmed, location_accuracy_m,
                        outlet_kind, price_type, visit_sequence)
SELECT format('MIR-%s-%s', r.suffix, lpad(n::text, 3, '0')),
       format('%s Store %s', r.area, n),
       format('%s দোকান %s', r.area_bn, n),
       format('Owner %s-%s', r.suffix, n),
       '017' || lpad((10000000 + r.seq * 1000 + n)::text, 8, '0'),
       format('Road %s, %s, Dhaka', n, r.area),
       z.id, ro.id, cl.id,
       CASE WHEN n % 10 = 0 THEN 'Astha' ELSE 'GT' END,
       sc.id,
       'Urban',
       round((23.8069 + r.dlat + ((n - 1) / 5) * 0.00063)::numeric, 6)::double precision,
       round((90.3687 + r.dlng + ((n - 1) % 5) * 0.00069)::numeric, 6)::double precision,
       'master', true, 8,
       CASE WHEN n = 20 THEN 'wholesale' ELSE 'retail' END,
       CASE WHEN n = 20 THEN 'distributor' ELSE 'outlet' END,
       n
  FROM (VALUES ('D', 'MIR-SR-D', 1, 'Mirpur 10', 'মিরপুর ১০', 'Mirpur 10 Bazar', 0.0, 0.0),
               ('3F', 'MIR-SR-3F', 2, 'Mirpur 11', 'মিরপুর ১১', 'Mirpur 11 Bazar', 0.0040, 0.0),
               ('2F', 'MIR-SR-2F', 3, 'Pallabi', 'পল্লবী', 'Pallabi', 0.0080, 0.0)) AS r(suffix, route_code, seq, area, area_bn, cluster, dlat, dlng)
  CROSS JOIN generate_series(1, 20) AS n
  JOIN app.route ro ON ro.code = r.route_code
  JOIN app.zone z ON z.id = ro.zone_id
  JOIN app.cluster cl ON cl.zone_id = z.id AND cl.name = r.cluster
  JOIN app.sub_channel sc ON sc.code = CASE WHEN n % 10 = 0 THEN 'ASTHA-1' WHEN n % 2 = 0 THEN 'GT-TEA' ELSE 'GT-GROCERY' END
 WHERE NOT EXISTS (SELECT 1 FROM app.outlet x WHERE x.code = format('MIR-%s-%s', r.suffix, lpad(n::text, 3, '0')));

INSERT INTO app.outlet_placement_history (outlet_id, route_id, cluster_id, valid_from)
  SELECT o.id, o.route_id, o.cluster_id, DATE '2026-01-01' FROM app.outlet o
   WHERE o.code LIKE 'MIR-%' AND NOT EXISTS (SELECT 1 FROM app.outlet_placement_history h WHERE h.outlet_id = o.id);
INSERT INTO app.outlet_location_history (outlet_id, lat, lng, accuracy_m, source, valid_from)
  SELECT o.id, o.lat, o.lng, o.location_accuracy_m, 'migration', TIMESTAMPTZ '2026-01-01 00:00:00+06' FROM app.outlet o
   WHERE o.code LIKE 'MIR-%' AND NOT EXISTS (SELECT 1 FROM app.outlet_location_history h WHERE h.outlet_id = o.id);

-- Every active SKU with a non-zero outlet price is on the Mirpur zone's sales plan (the catalogue lists three SKUs
-- priced 0 in every type; they stay off the plan so nothing sells for 0 Tk).
INSERT INTO app.sales_plan (zone_id, sku_id, valid_from)
  SELECT z.id, s.id, DATE '2026-01-01' FROM app.zone z, app.sku s
   WHERE z.code = 'Z-MIR' AND s.status = 'active'
     AND EXISTS (SELECT 1 FROM app.sku_price p WHERE p.sku_id = s.id AND p.price_type = 'outlet' AND p.amount_mtk > 0)
     AND NOT EXISTS (SELECT 1 FROM app.sales_plan p WHERE p.zone_id = z.id AND p.sku_id = s.id);
