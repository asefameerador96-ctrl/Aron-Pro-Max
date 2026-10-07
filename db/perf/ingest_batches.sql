-- AUD-PERF-01 measurement: ingest-shaped batches (200 visits, their memos and two lines each) into the realistic-volume
-- database of generate.sql, with every trigger and constraint active, timed per batch; everything is rolled back.
--   psql -d aron_perf -v batches=40 -f db/perf/ingest_batches.sql
\set ON_ERROR_STOP 1
\if :{?batches}
\else
  \set batches 40
\endif
BEGIN;
CREATE TEMP TABLE perf_batch (n int, kind text, ms numeric) ON COMMIT DROP;
CREATE TEMP TABLE perf_rows (visit_uuid uuid, memo_uuid uuid, outlet_id bigint, route_id bigint, user_id bigint, seq int) ON COMMIT DROP;
CREATE TEMP TABLE perf_src ON COMMIT DROP AS
SELECT o.id AS outlet_id, o.route_id, a.user_id, row_number() OVER () AS rn
  FROM app.outlet o JOIN app.route_assignment a ON a.route_id = o.route_id
 WHERE o.code LIKE 'PO%' ORDER BY random() LIMIT 200 * :batches;

DO $$
DECLARE
  b  int;
  t0 timestamptz;
  d  date := app.dhaka_date(now());
BEGIN
  FOR b IN 0 .. (SELECT count(*) / 200 - 1 FROM perf_src) LOOP

    TRUNCATE perf_rows;
    INSERT INTO perf_rows SELECT gen_random_uuid(), gen_random_uuid(), outlet_id, route_id, user_id, rn::int
      FROM perf_src WHERE rn > b * 200 AND rn <= (b + 1) * 200;

    t0 := clock_timestamp();
    INSERT INTO app.visit (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, visit_kind,
                           outlet_id, opened_at, sequence_no, planned, verdict, radius_m_used, max_accuracy_m_used,
                           location_basis, geo_action, fix_status, fix_lat, fix_lng, fix_accuracy_m, fix_is_mock, distance_m, received_at)
    SELECT visit_uuid, visit_uuid, d, user_id, route_id, now(), 1, 'sr_call', outlet_id, now(), 1 + seq % 900, true, 'in_range',
           100, 50, 'master', 'sale_allowed', 'ok', 23.8, 90.4, 12, false, 30, now()
      FROM perf_rows;
    INSERT INTO perf_batch VALUES (b, 'visit', extract(epoch FROM clock_timestamp() - t0) * 1000);

    t0 := clock_timestamp();
    INSERT INTO app.memo (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version,
                          visit_client_uuid, outlet_id, memo_no, memo_kind, committed_at, price_list_date, price_type,
                          gross_mtk, offer_discount_mtk, drp_discount_mtk, qc_deduction_mtk, round_adj_mtk, net_mtk,
                          paid_mtk, due_mtk, is_credit, line_count, discount_line_count, qc_line_count, received_at)
    SELECT memo_uuid, memo_uuid, d, user_id, route_id, now(), 1, visit_uuid, outlet_id,
           'pperf' || (seq / 10000) || '-' || to_char(d, 'YYMMDD') || '-' || lpad((seq % 10000)::text, 4, '0'), 'sale', now(), d, 'outlet',
           1300000, 0, 0, 0, 0, 1300000, 1300000, 0, false, 2, 0, 0, now()
      FROM perf_rows;
    INSERT INTO perf_batch VALUES (b, 'memo', extract(epoch FROM clock_timestamp() - t0) * 1000);

    t0 := clock_timestamp();
    INSERT INTO app.memo_line (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version,
                               memo_client_uuid, line_no, sku_id, line_kind, qty_entered, unit_entered, pack_factor, qty_base,
                               price_type, price_valid_from, base_price_mtk, price_per_qty, gross_mtk, received_at)
    SELECT gen_random_uuid(), memo_uuid, d, user_id, route_id, now(), 1, memo_uuid, l,
           (SELECT min(id) FROM app.sku) + l, 'sale', 50, 'stick', 1, 50, 'outlet', DATE '2026-01-01', 13000, 1, 650000, now()
      FROM perf_rows CROSS JOIN generate_series(1, 2) l;
    INSERT INTO perf_batch VALUES (b, 'memo_line', extract(epoch FROM clock_timestamp() - t0) * 1000);
  END LOOP;
END $$;

SELECT kind, count(*) AS batches,
       round(percentile_cont(0.5) WITHIN GROUP (ORDER BY ms)::numeric, 1) AS p50_ms,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY ms)::numeric, 1) AS p95_ms,
       round(max(ms), 1) AS max_ms
  FROM perf_batch GROUP BY kind ORDER BY kind;
SELECT round(percentile_cont(0.95) WITHIN GROUP (ORDER BY s)::numeric, 1) AS batch_total_p95_ms
  FROM (SELECT n, sum(ms) AS s FROM perf_batch GROUP BY n) x;
ROLLBACK;
