-- Realistic-volume dataset for the hot-path query-plan review (docs/22 P-01, P-13, P-17; docs/18 s2). NEVER for
-- production. Load into a scratch database that has all migrations and db/seed applied, as a superuser:
--   psql -d aron_perf -v ON_ERROR_STOP=1 -v days=7 -f db/perf/generate.sql
-- Shape: 10 wings, 50 divisions, 291 territories, 1,051 zones, 11,336 routes (Daily 3,242, 3F 6,083, 2F 2,009 in
-- the visit-day groups of P-17), about 735k outlets (64 per route), 8,500 SRs, 1,051 AMOs, 291 TSOs; per trading day
-- about 460k planned outlets, 65 percent visited (about 300k visits, the observed 270–345k), 85 percent of visits
-- with a memo, 1.95 lines per memo. Facts, route aggregates, registry rows and outbox events for every record.
-- Triggers and foreign keys are skipped while loading (session_replication_role = replica); the rows satisfy them.
\set ON_ERROR_STOP 1
\if :{?days}
\else
  \set days 7
\endif
SET session_replication_role = replica;
SET synchronous_commit = off;
SELECT setseed(0.22);

-- ---------- geography ----------
INSERT INTO app.wing (code, name) SELECT 'PW' || w, 'Perf wing ' || w FROM generate_series(1, 10) w;
INSERT INTO app.division (code, name, wing_id)
  SELECT 'PD' || d, 'Perf division ' || d, (SELECT id FROM app.wing WHERE code = 'PW' || (1 + (d - 1) % 10)) FROM generate_series(1, 50) d;
INSERT INTO app.territory (code, name, division_id)
  SELECT 'PT' || t, 'Perf territory ' || t, (SELECT id FROM app.division WHERE code = 'PD' || (1 + (t - 1) % 50)) FROM generate_series(1, 291) t;
INSERT INTO app.house (code, name, territory_id) SELECT 'PH' || t.code, 'House ' || t.code, t.id FROM app.territory t WHERE t.code LIKE 'PT%';
INSERT INTO app.zone (code, name, territory_id, house_id)
  SELECT 'PZ' || z, 'Perf zone ' || z, t.id, h.id
    FROM generate_series(1, 1051) z JOIN app.territory t ON t.code = 'PT' || (1 + (z - 1) % 291) JOIN app.house h ON h.territory_id = t.id;
INSERT INTO app.cluster (zone_id, name, cluster_type) SELECT z.id, 'Market ' || c, 'market' FROM app.zone z, generate_series(1, 3) c WHERE z.code LIKE 'PZ%';

-- ---------- routes: kind and visit-day mask by P-17 (bit0 Sat ... bit6 Fri) ----------
CREATE TEMP TABLE pr AS
SELECT r, CASE WHEN r <= 3242 THEN 'daily' WHEN r <= 3242 + 6083 THEN '3f' ELSE '2f' END AS kind,
       CASE WHEN r <= 3242 THEN 127                                  -- every day (Friday off by calendar)
            WHEN r <= 3242 + 6083 THEN (ARRAY[42, 21])[1 + r % 2]    -- Sun/Tue/Thu, Sat/Mon/Wed
            ELSE (ARRAY[36, 18, 9])[1 + r % 3] END AS mask            -- Mon/Thu, Sun/Wed, Sat/Tue
  FROM generate_series(1, 11336) r;
INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask, sequence_no)
  SELECT 'PR' || pr.r, 'Perf route ' || pr.r, z.id, 'sr', pr.kind, pr.mask, 1 + pr.r / 1051
    FROM pr JOIN app.zone z ON z.code = 'PZ' || (1 + (pr.r * 7919) % 1051);

-- ---------- outlets: 30..98 per route, mean 64 ----------
INSERT INTO app.outlet (code, name, owner_name, zone_id, route_id, cluster_id, channel, lat, lng, location_basis,
                        location_confirmed, location_accuracy_m, outlet_kind, price_type, visit_sequence)
SELECT 'PO' || r.id || '-' || n, 'Store ' || n, 'Owner ' || n, r.zone_id, r.id,
       (SELECT min(c.id) FROM app.cluster c WHERE c.zone_id = r.zone_id),
       CASE WHEN n % 10 = 0 THEN 'Astha' ELSE 'GT' END,
       22.0 + (r.id % 400) * 0.01 + n * 0.0003, 89.0 + (r.id / 400) * 0.01 + n * 0.0003, 'master', true, 10,
       CASE WHEN n = 1 THEN 'wholesale' ELSE 'retail' END, CASE WHEN n = 1 THEN 'distributor' ELSE 'outlet' END, n
  FROM app.route r CROSS JOIN LATERAL generate_series(1, 30 + (r.id * 37) % 69) n
 WHERE r.code LIKE 'PR%';

-- ---------- people, scope and assignments ----------
INSERT INTO app.app_user (username, full_name, role, home_zone_id)
  SELECT 'psr' || s, 'Perf SR ' || s, 'SR', NULL FROM generate_series(1, 8500) s;
INSERT INTO app.app_user (username, full_name, role, home_zone_id)
  SELECT 'pamo' || lower(z.code), 'Perf AMO ' || z.code, 'AMO', z.id FROM app.zone z WHERE z.code LIKE 'PZ%';
INSERT INTO app.app_user (username, full_name, role)
  SELECT 'ptso' || lower(t.code), 'Perf TSO ' || t.code, 'TSO' FROM app.territory t WHERE t.code LIKE 'PT%';
INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from)
  SELECT u.id, 'zone', z.id, DATE '2026-01-01' FROM app.app_user u JOIN app.zone z ON u.username = 'pamo' || lower(z.code);
INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from)
  SELECT u.id, 'territory', t.id, DATE '2026-01-01' FROM app.app_user u JOIN app.territory t ON u.username = 'ptso' || lower(t.code);
-- Route n goes to SR 1 + (n - 1) % 8500: 2,836 SRs hold two routes, the rest one (1.33 routes per SR).
INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from)
  SELECT r.id, u.id, 'primary', DATE '2026-01-01'
    FROM app.route r JOIN app.app_user u ON u.username = 'psr' || (1 + (substr(r.code, 3)::int - 1) % 8500)
   WHERE r.code LIKE 'PR%';

-- ---------- field history: :days trading days ending today (Fridays skipped) ----------
CREATE TEMP TABLE pdays AS
SELECT d::date AS d FROM generate_series(app.dhaka_date(now()) - 40, app.dhaka_date(now()), interval '1 day') d
 WHERE extract(isodow FROM d) <> 5 ORDER BY d DESC LIMIT :days;

CREATE TEMP TABLE pv AS
SELECT gen_random_uuid() AS visit_uuid, gen_random_uuid() AS memo_uuid, gen_random_uuid() AS batch_uuid,
       pd.d AS business_date, o.id AS outlet_id, o.route_id, a.user_id, o.visit_sequence AS seq,
       ((pd.d + time '09:00') AT TIME ZONE 'Asia/Dhaka') + make_interval(mins => o.visit_sequence * 5) AS at,
       random() < 0.85 AS sold, 1 + floor(random() * random() * 4.2)::int AS lines,
       random() < 0.02 AS mocked, u.username,
       row_number() OVER (PARTITION BY a.user_id, pd.d ORDER BY o.route_id, o.visit_sequence) AS memo_seq
  FROM pdays pd
  JOIN app.route r ON r.code LIKE 'PR%' AND (r.visit_days_mask >> ((extract(isodow FROM pd.d)::int + 1) % 7)) & 1 = 1
  JOIN app.route_assignment a ON a.route_id = r.id
  JOIN app.app_user u ON u.id = a.user_id
  JOIN app.outlet o ON o.route_id = r.id
 WHERE random() < 0.65;

INSERT INTO app.visit (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, visit_kind,
                       outlet_id, opened_at, sequence_no, planned, verdict, radius_m_used, max_accuracy_m_used,
                       location_basis, geo_action, fix_status, fix_lat, fix_lng, fix_accuracy_m, fix_is_mock, distance_m,
                       received_at, first_batch_uuid)
SELECT visit_uuid, visit_uuid, business_date, user_id, route_id, at, 1, 'sr_call', outlet_id, at, seq, true,
       CASE WHEN mocked THEN 'mocked' ELSE 'in_range' END, 100, 50, 'master',
       CASE WHEN mocked THEN 'force_sale' ELSE 'sale_allowed' END, 'ok', 23.8, 90.4, 12, mocked, 30,
       at + interval '2 hours', batch_uuid
  FROM pv;

INSERT INTO app.memo (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version,
                      visit_client_uuid, outlet_id, memo_no, memo_kind, committed_at, price_list_date, price_type,
                      gross_mtk, offer_discount_mtk, drp_discount_mtk, qc_deduction_mtk, round_adj_mtk, net_mtk,
                      paid_mtk, due_mtk, is_credit, line_count, discount_line_count, qc_line_count, received_at, first_batch_uuid)
SELECT memo_uuid, memo_uuid, business_date, user_id, route_id, at + interval '3 minutes', 1, visit_uuid, outlet_id,
       username || '-' || to_char(business_date, 'YYMMDD') || '-' || lpad(memo_seq::text, 3, '0'), 'sale',
       at + interval '3 minutes', business_date, 'outlet',
       lines * 650000, 0, 0, 0, 0, lines * 650000,
       CASE WHEN seq % 9 = 0 THEN 0 ELSE lines * 650000 END, CASE WHEN seq % 9 = 0 THEN lines * 650000 ELSE 0 END,
       seq % 9 = 0, lines, 0, 0, at + interval '2 hours', batch_uuid
  FROM pv WHERE sold;

INSERT INTO app.memo_line (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version,
                           memo_client_uuid, line_no, sku_id, line_kind, qty_entered, unit_entered, pack_factor, qty_base,
                           price_type, price_valid_from, base_price_mtk, price_per_qty, gross_mtk, received_at, first_batch_uuid)
SELECT gen_random_uuid(), pv.memo_uuid, pv.business_date, pv.user_id, pv.route_id, pv.at + interval '3 minutes', 1,
       pv.memo_uuid, l, s.id, 'sale', 50, 'stick', 1, 50, 'outlet', DATE '2026-01-01', 13000, 1, 650000,
       pv.at + interval '2 hours', pv.batch_uuid
  FROM pv CROSS JOIN LATERAL generate_series(1, pv.lines) l
  JOIN LATERAL (SELECT id FROM app.sku ORDER BY id OFFSET ((pv.seq * 7 + l * 13) % 39) LIMIT 1) s ON true
 WHERE pv.sold;

-- Ingest registry: one row per visit and memo record (lines travel inside their memo record).
INSERT INTO app.ingest_registry (client_uuid, record_type, payload_sha256, status, server_id, business_date, user_id,
                                 first_batch_uuid, received_at)
SELECT visit_uuid, 'visit', sha256(visit_uuid::text::bytea), 'accepted', NULL::bigint, business_date, user_id, batch_uuid, at + interval '2 hours' FROM pv
UNION ALL
SELECT memo_uuid, 'memo', sha256(memo_uuid::text::bytea), 'accepted', NULL::bigint, business_date, user_id, batch_uuid, at + interval '2 hours' FROM pv WHERE sold;

-- Outbox: visit.closed and memo.created, in capture order.
INSERT INTO app.domain_event (event_type, payload_version, aggregate_type, aggregate_id, business_date, payload, source_client_uuid, created_at)
SELECT e.t, 1, e.a, e.id, pv.business_date, e.p, e.u, pv.at + interval '2 hours'
  FROM pv CROSS JOIN LATERAL (VALUES
    ('visit.closed', 'visit', pv.visit_uuid::text, pv.visit_uuid,
     jsonb_build_object('visit_uuid', pv.visit_uuid, 'route_id', pv.route_id, 'outlet_id', pv.outlet_id, 'user_id', pv.user_id,
                        'visit_kind', 'sr_call', 'verdict', CASE WHEN pv.mocked THEN 'mocked' ELSE 'in_range' END)),
    ('memo.created', 'memo', pv.memo_uuid::text, pv.memo_uuid,
     jsonb_build_object('memo_uuid', pv.memo_uuid, 'route_id', pv.route_id, 'outlet_id', pv.outlet_id, 'user_id', pv.user_id,
                        'memo_kind', 'sale', 'net_mtk', pv.lines * 650000))) AS e(t, a, id, u, p)
 WHERE e.t = 'visit.closed' OR pv.sold
 ORDER BY pv.at, e.t DESC;

-- Route-days of the planned routes.
INSERT INTO app.route_day (route_id, business_date, planned, assigned_user_id, state, target_outlets, logged_in_at, synced_at, final_submitted_at)
SELECT r.id, pd.d, true, a.user_id, CASE WHEN pd.d < app.dhaka_date(now()) THEN 'final_submitted' ELSE 'in_field' END,
       (SELECT count(*) FROM app.outlet o WHERE o.route_id = r.id),
       (pd.d + time '08:30') AT TIME ZONE 'Asia/Dhaka', (pd.d + time '18:00') AT TIME ZONE 'Asia/Dhaka',
       CASE WHEN pd.d < app.dhaka_date(now()) THEN (pd.d + time '21:00') AT TIME ZONE 'Asia/Dhaka' END
  FROM pdays pd
  JOIN app.route r ON r.code LIKE 'PR%' AND (r.visit_days_mask >> ((extract(isodow FROM pd.d)::int + 1) % 7)) & 1 = 1
  JOIN app.route_assignment a ON a.route_id = r.id;

-- ---------- dw: dimensions, facts and the route-day aggregate ----------
INSERT INTO dw.dim_geo (route_id, route_code, route_name, route_kind, visit_kind, zone_id, zone_name, territory_id,
                        territory_name, division_id, division_name, wing_id, wing_name, status)
SELECT r.id, r.code, r.name, r.kind, r.visit_kind, z.id, z.name, t.id, t.name, d.id, d.name, w.id, w.name, r.status
  FROM app.route r JOIN app.zone z ON z.id = r.zone_id JOIN app.territory t ON t.id = z.territory_id
  JOIN app.division d ON d.id = t.division_id JOIN app.wing w ON w.id = d.wing_id
ON CONFLICT DO NOTHING;

INSERT INTO dw.fact_visit (business_date, visit_client_uuid, user_id, outlet_id, route_id, zone_id, visit_kind, opened_at,
                           device_verdict, geo_action, is_mock, planned, last_event_id)
SELECT pv.business_date, pv.visit_uuid, pv.user_id, pv.outlet_id, pv.route_id, r.zone_id, 'sr_call', pv.at,
       CASE WHEN pv.mocked THEN 'mocked' ELSE 'in_range' END, CASE WHEN pv.mocked THEN 'force_sale' ELSE 'sale_allowed' END,
       pv.mocked, true, 0
  FROM pv JOIN app.route r ON r.id = pv.route_id;
INSERT INTO dw.fact_memo (business_date, memo_client_uuid, memo_no, user_id, outlet_id, route_id, zone_id, status, committed_at,
                          line_count, gross_mtk, offer_discount_mtk, drp_discount_mtk, qc_deduction_mtk, net_mtk, paid_mtk,
                          due_mtk, is_credit, captured_offline, received_at, last_event_id)
SELECT m.business_date, m.client_uuid, m.memo_no, m.user_id, m.outlet_id, m.route_id, r.zone_id, 'active', m.committed_at,
       m.line_count, m.gross_mtk, 0, 0, 0, m.net_mtk, m.paid_mtk, m.due_mtk, m.is_credit, true, m.received_at, 0
  FROM app.memo m JOIN app.route r ON r.id = m.route_id WHERE r.code LIKE 'PR%';

INSERT INTO dw.agg_daily_route (business_date, route_id, zone_id, planned, day_state, target_outlets, visited_outlets,
                                visits, successful_calls, geo_valid_visits, mock_visits, active_memo_count, gross_mtk,
                                net_mtk, paid_mtk, due_mtk, last_event_id)
SELECT rd.business_date, rd.route_id, r.zone_id, true, rd.state, rd.target_outlets,
       coalesce(v.n, 0), coalesce(v.n, 0), coalesce(m.n, 0), coalesce(v.ok, 0), coalesce(v.n - v.ok, 0), coalesce(m.n, 0),
       coalesce(m.g, 0), coalesce(m.g, 0), coalesce(m.p, 0), coalesce(m.du, 0), 0
  FROM app.route_day rd JOIN app.route r ON r.id = rd.route_id
  LEFT JOIN (SELECT business_date, route_id, count(*) n, count(*) FILTER (WHERE NOT mocked) ok FROM pv GROUP BY 1, 2) v
         ON v.business_date = rd.business_date AND v.route_id = rd.route_id
  LEFT JOIN (SELECT business_date, route_id, count(*) n, sum(net_mtk) g, sum(paid_mtk) p, sum(due_mtk) du
               FROM dw.fact_memo GROUP BY 1, 2) m ON m.business_date = rd.business_date AND m.route_id = rd.route_id
 WHERE r.code LIKE 'PR%';

INSERT INTO dw.agg_daily_outlet (business_date, outlet_id, route_id, visited, geo_valid, active_memo_count, net_mtk, due_mtk, last_event_id)
SELECT pv.business_date, pv.outlet_id, pv.route_id, true, NOT pv.mocked,
       CASE WHEN pv.sold THEN 1 ELSE 0 END, CASE WHEN pv.sold THEN pv.lines * 650000 ELSE 0 END,
       CASE WHEN pv.sold AND pv.seq % 9 = 0 THEN pv.lines * 650000 ELSE 0 END, 0
  FROM pv;

RESET session_replication_role;
ANALYZE;
SELECT (SELECT count(*) FROM pdays) AS days, (SELECT count(*) FROM app.outlet) AS outlets,
       (SELECT count(*) FROM app.visit) AS visits, (SELECT count(*) FROM app.memo) AS memos,
       (SELECT count(*) FROM app.memo_line) AS lines, (SELECT count(*) FROM app.ingest_registry) AS registry,
       (SELECT count(*) FROM app.domain_event) AS events, pg_size_pretty(pg_database_size(current_database())) AS size;
