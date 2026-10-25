-- V0118 reuse surface (data-architecture review 2026-10-25 High 4 and Low 13, 14, 15; lead action row 4,
-- D-DATA-ARCH-REVIEW-01): the approved reading surface (BI, the lake, later verticals read only dw.v_*) reaches memo
-- lines, visits, dues, stock and outlet x SKU days, with stable dimensions, one effectiveness flag and a pinned contract.
--
-- 1. dw.fact_memo_line: one row per memo line, partitioned by business_date like dw.fact_memo. It is kept in step with
--    dw.fact_memo by an AFTER trigger on that fact (dw.sync_fact_memo_lines): every write of a memo fact (the
--    projector's route-day rebuild, a restamp) rewrites that memo's lines from app.memo_line, a deleted memo fact deletes
--    them. No projector change; a rebuild of the route-day rebuilds its lines. Backfilled from the facts present.
-- 2. New stable views, each with data_origin and is_effective, no names of people, no coordinates, no phone numbers:
--    v_memo (the memo UUID, not the printed memo number), v_memo_line, v_visit, v_due_ledger, v_stock_movement,
--    v_outlet_sku_daily, v_dim_geo, v_dim_outlet, v_dim_user, v_dim_product (products carry no origin of their own:
--    data_origin is the constant production there).
-- 3. is_effective on every dw.v_* view (appended last): true when the row counts in an ordinary total. Aggregate and
--    calendar rows: true. v_memo: status active and at least one line (the dashboards' memo count); v_memo_line: its memo
--    active and the line not voided; v_visit: not voided; v_stock_movement: not voided; v_memo_flags: active, not
--    data-voided and with a line; v_outlet_masked and the dimensions: status active; v_device_command: not rejected, expired or failed;
--    v_final_submit_log: some submit of the zone-day still stands.
-- 4. dw.view_contract_version() = '1.0': the column contract of the dw.v_* views, pinned by docs/dw-view-contract.md and
--    DwViewContractTest (an added column needs a minor bump, a removed or renamed one a major bump, before the file can
--    be regenerated). Lake manifests carry it.
-- 5. The printed memo number <username>-<yyMMdd>-<seq> contains the SR's login: personal data in the dictionary
--    (app.memo, app.memo_void, app.sale_abort, app.due_ledger, app.due_collection.against_memo_no, dw.fact_memo,
--    dw.v_memo_flags). The new views export the memo UUID only; v_memo_flags keeps memo_no for the flagged-memos endpoint;
--    the grant map now leaves memo_no out of bi_reader's view grants (column grants).
-- 6. dw.agg_reconcile (nightly reconcile findings) and dw.agg_restatement_log (what moved on a closed day), shaped by
--    docs/16 s8.6-s8.8 for backend-reports' rebuild/reconcile slice (lead row 3). Unpartitioned at pilot size (row 9).
-- D-DB-REUSE-SURFACE-01.

SET lock_timeout = '5s';

-- ---------- 1. dw.fact_memo_line ----------
CREATE TABLE dw.fact_memo_line (
  business_date     date        NOT NULL,
  line_client_uuid  uuid        NOT NULL,
  memo_client_uuid  uuid        NOT NULL,
  line_no           smallint    NOT NULL,
  user_id           bigint,
  route_id          bigint,
  outlet_id         bigint,
  sku_id            bigint      NOT NULL,
  line_kind         text        NOT NULL,
  qty_entered       integer     NOT NULL,
  unit_entered      text        NOT NULL,
  pack_factor       integer     NOT NULL,
  qty_base          bigint      NOT NULL,
  price_type        text,
  base_price_mtk    bigint,
  price_per_qty     integer,
  gross_mtk         bigint      NOT NULL,
  offer_id          bigint,
  voided            boolean     NOT NULL,
  memo_status       text        NOT NULL,
  data_origin       app.data_origin,
  updated_at        timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (line_client_uuid, business_date)
) PARTITION BY RANGE (business_date);
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX fact_memo_line_memo ON dw.fact_memo_line (memo_client_uuid, business_date);
INSERT INTO app.partition_policy (parent, retention_class) VALUES ('dw.fact_memo_line', 'event_fact');
SELECT app.ensure_partitions('2026-01-01', '2027-12-01');

CREATE FUNCTION dw.sync_fact_memo_lines() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP IN ('UPDATE', 'DELETE') THEN
    DELETE FROM dw.fact_memo_line WHERE memo_client_uuid = OLD.memo_client_uuid AND business_date = OLD.business_date;
  END IF;
  IF TG_OP IN ('INSERT', 'UPDATE') THEN
    -- Also by the new key: lines left behind (a key change, a TRUNCATE in a session with triggers off) never make the
    -- rewrite fail on a duplicate.
    DELETE FROM dw.fact_memo_line WHERE memo_client_uuid = NEW.memo_client_uuid AND business_date = NEW.business_date;
    INSERT INTO dw.fact_memo_line (business_date, line_client_uuid, memo_client_uuid, line_no, user_id, route_id, outlet_id,
                                   sku_id, line_kind, qty_entered, unit_entered, pack_factor, qty_base, price_type,
                                   base_price_mtk, price_per_qty, gross_mtk, offer_id, voided, memo_status, data_origin)
    SELECT l.business_date, l.client_uuid, l.memo_client_uuid, l.line_no, NEW.user_id, NEW.route_id, NEW.outlet_id,
           l.sku_id, l.line_kind, l.qty_entered, l.unit_entered, l.pack_factor, l.qty_base, l.price_type,
           l.base_price_mtk, l.price_per_qty, l.gross_mtk, l.offer_id, l.voided_at IS NOT NULL, NEW.status, l.data_origin
      FROM app.memo_line l
     WHERE l.memo_client_uuid = NEW.memo_client_uuid AND l.business_date = NEW.business_date;
  END IF;
  RETURN NULL;
END $$;
COMMENT ON FUNCTION dw.sync_fact_memo_lines() IS 'AFTER INSERT/UPDATE/DELETE on dw.fact_memo: rewrites that memo''s rows of dw.fact_memo_line from app.memo_line (or deletes them).';
CREATE TRIGGER fact_memo_sync_lines AFTER INSERT OR UPDATE OR DELETE ON dw.fact_memo
  FOR EACH ROW EXECUTE FUNCTION dw.sync_fact_memo_lines();
-- A TRUNCATE of the memo facts (a wipe and rebuild) fires no row trigger: the lines go with them (DELETE, so the worker
-- role needs no TRUNCATE privilege on the line facts).
CREATE FUNCTION dw.truncate_fact_memo_lines() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  DELETE FROM dw.fact_memo_line;
  RETURN NULL;
END $$;
COMMENT ON FUNCTION dw.truncate_fact_memo_lines() IS 'AFTER TRUNCATE on dw.fact_memo: empties dw.fact_memo_line with it.';
CREATE TRIGGER fact_memo_truncate_lines AFTER TRUNCATE ON dw.fact_memo
  FOR EACH STATEMENT EXECUTE FUNCTION dw.truncate_fact_memo_lines();
-- Backfill: touching every memo fact runs the trigger (its origin trigger recomputes the same value).
UPDATE dw.fact_memo SET updated_at = updated_at;

COMMENT ON TABLE dw.fact_memo_line IS 'One row per memo line of a memo in dw.fact_memo, kept in step with that fact by trigger (rebuilt with the memo''s route-day).
owner: worker | capture: SERVER | retention: event_fact | pii: none';
COMMENT ON COLUMN dw.fact_memo_line.business_date IS 'Asia/Dhaka business date of the memo.';
COMMENT ON COLUMN dw.fact_memo_line.line_client_uuid IS 'Client-generated UUID of the line (app.memo_line.client_uuid).';
COMMENT ON COLUMN dw.fact_memo_line.memo_client_uuid IS 'Client-generated UUID of the memo (dw.fact_memo.memo_client_uuid).';
COMMENT ON COLUMN dw.fact_memo_line.line_no IS 'Line number within the memo.';
COMMENT ON COLUMN dw.fact_memo_line.user_id IS 'User who captured the memo (app.app_user); names are resolved by the API, not in dw.';
COMMENT ON COLUMN dw.fact_memo_line.route_id IS 'Route of the memo.';
COMMENT ON COLUMN dw.fact_memo_line.outlet_id IS 'Outlet of the memo.';
COMMENT ON COLUMN dw.fact_memo_line.sku_id IS 'SKU sold (app.sku).';
COMMENT ON COLUMN dw.fact_memo_line.line_kind IS 'Line kind as captured: sale, drp_reward, promo_free or free_sample (app.memo_line.line_kind).';
COMMENT ON COLUMN dw.fact_memo_line.qty_entered IS 'Quantity as the SR entered it, in unit_entered.';
COMMENT ON COLUMN dw.fact_memo_line.unit_entered IS 'Unit the quantity was entered in: stick, piece, dozen or pack.';
COMMENT ON COLUMN dw.fact_memo_line.pack_factor IS 'Base units per pack of the SKU at capture; qty_base = qty_entered x pack_factor when unit_entered is pack, else qty_entered.';
COMMENT ON COLUMN dw.fact_memo_line.qty_base IS 'Quantity in the SKU base unit (sticks, pieces or dozens).';
COMMENT ON COLUMN dw.fact_memo_line.price_type IS 'Price type the line was priced at.';
COMMENT ON COLUMN dw.fact_memo_line.base_price_mtk IS 'Price per price_per_qty base units at capture, integer milli-taka.';
COMMENT ON COLUMN dw.fact_memo_line.price_per_qty IS 'Number of base units base_price_mtk is quoted for.';
COMMENT ON COLUMN dw.fact_memo_line.gross_mtk IS 'Gross value of the line, integer milli-taka.';
COMMENT ON COLUMN dw.fact_memo_line.offer_id IS 'Offer the line belongs to, if any (offers are deferred; hook only).';
COMMENT ON COLUMN dw.fact_memo_line.voided IS 'True when the line itself carries a void tombstone.';
COMMENT ON COLUMN dw.fact_memo_line.memo_status IS 'Status of the memo (active, voided, superseded), copied from dw.fact_memo.';
COMMENT ON COLUMN dw.fact_memo_line.data_origin IS 'Origin of the line (app.memo_line.data_origin, stamped at capture).';
COMMENT ON COLUMN dw.fact_memo_line.updated_at IS 'UTC instant the row was last rewritten.';

-- ---------- 2. new stable views ----------
CREATE VIEW dw.v_memo AS
SELECT f.business_date, f.memo_client_uuid, f.user_id, f.route_id, f.zone_id, f.outlet_id, f.status, f.committed_at,
       f.line_count, f.gross_mtk, f.offer_discount_mtk, f.drp_discount_mtk, f.qc_deduction_mtk, f.net_mtk, f.paid_mtk,
       f.due_mtk, f.is_credit, f.captured_offline, f.entry_source, f.received_at, f.geo_key, f.outlet_key, f.user_key,
       f.updated_at, f.data_origin, (f.status = 'active' AND f.line_count > 0) AS is_effective
  FROM dw.fact_memo f;

CREATE VIEW dw.v_memo_line AS
SELECT l.business_date, l.memo_client_uuid, l.line_client_uuid, l.line_no, l.user_id, l.route_id, l.outlet_id, l.sku_id,
       l.line_kind, l.qty_entered, l.unit_entered, l.pack_factor, l.qty_base, l.price_type, l.base_price_mtk,
       l.price_per_qty, l.gross_mtk, l.offer_id, l.voided, l.memo_status, l.updated_at, l.data_origin,
       (l.memo_status = 'active' AND NOT l.voided) AS is_effective
  FROM dw.fact_memo_line l;

CREATE VIEW dw.v_visit AS
SELECT v.business_date, v.visit_client_uuid, v.user_id, v.route_id, v.zone_id, v.outlet_id, v.visit_kind, v.opened_at,
       v.ended_at, v.outcome_code, v.call_declined, v.planned, v.completed, v.device_verdict, v.server_verdict,
       v.geo_action, v.distance_m, v.server_distance_m, v.radius_m, v.within_fence, v.is_mock, v.voided, v.entry_source,
       v.geo_key, v.outlet_key, v.user_key, v.updated_at, v.data_origin, NOT v.voided AS is_effective
  FROM dw.fact_visit v;

CREATE VIEW dw.v_due_ledger AS
SELECT d.id AS ledger_entry_id, d.business_date, d.outlet_id, d.entry_kind, d.amount_mtk, d.memo_client_uuid,
       d.source_client_uuid, d.user_id, d.created_at, d.data_origin, true AS is_effective
  FROM app.due_ledger d;

CREATE VIEW dw.v_stock_movement AS
SELECT s.client_uuid AS movement_client_uuid, s.business_date, s.user_id, s.route_id, s.zone_id, s.kind, s.sku_id,
       s.qty_entered, s.unit_entered, s.pack_factor, s.qty_base, s.reason_code, s.captured_offline, s.received_at,
       s.voided_at, s.data_origin, s.voided_at IS NULL AS is_effective
  FROM app.stock_movement s;

CREATE VIEW dw.v_outlet_sku_daily AS
SELECT a.business_date, a.outlet_id, a.route_id, a.sku_id, a.sold_qty_base, a.memo_count, a.updated_at, a.data_origin,
       true AS is_effective
  FROM dw.agg_daily_outlet_sku a;

CREATE VIEW dw.v_dim_geo AS
SELECT g.route_id, g.route_code, g.route_name, g.route_kind, g.visit_kind, g.route_class, g.zone_id, g.zone_name,
       g.house_id, g.house_name, g.territory_id, g.territory_name, g.division_id, g.division_name, g.wing_id, g.wing_name,
       g.status, g.updated_at, g.data_origin, g.status = 'active' AS is_effective
  FROM dw.dim_geo g;

CREATE VIEW dw.v_dim_outlet AS
SELECT o.outlet_id, o.outlet_code, o.outlet_name, o.route_id, o.cluster_id, o.zone_id, o.channel, o.sub_channel_id,
       o.geo_class, o.outlet_kind, o.location_confirmed, o.status, o.updated_at, o.data_origin,
       o.status = 'active' AS is_effective
  FROM dw.dim_outlet o;

CREATE VIEW dw.v_dim_user AS
SELECT u.user_id, u.role, u.designation, u.home_zone_id, u.status, u.pilot, u.updated_at, u.data_origin,
       u.status = 'active' AS is_effective
  FROM dw.dim_user u;

CREATE VIEW dw.v_dim_product AS
SELECT p.sku_id, p.sku_code, p.short_name, p.base_unit, p.base_per_pack, p.category_id, p.category_code, p.category_name,
       p.segment_id, p.segment_code, p.segment_name, p.brand_id, p.brand_code, p.brand_name, p.variant_id, p.variant_code,
       p.variant_name, p.updated_at, 'production'::app.data_origin AS data_origin, true AS is_effective
  FROM dw.dim_product p;

COMMENT ON VIEW dw.v_memo IS 'Stable view: one row per memo (dw.fact_memo) with its money, scope keys and SCD2 keys; the memo UUID, never the printed memo number (personal: it embeds the login). is_effective = active with at least one line.
owner: worker | capture: SERVER | retention: event_fact | pii: none';
COMMENT ON VIEW dw.v_memo_line IS 'Stable view: one row per memo line (dw.fact_memo_line) in entered and base units with its price at capture. is_effective = memo active and line not voided.
owner: worker | capture: SERVER | retention: event_fact | pii: none';
COMMENT ON VIEW dw.v_visit IS 'Stable view: one row per visit (dw.fact_visit) with both geo verdicts and distances, no coordinates and no photo. is_effective = not voided.
owner: worker | capture: SERVER | retention: event_fact | pii: none';
COMMENT ON VIEW dw.v_due_ledger IS 'Stable view: every signed entry of the outlet dues ledger (app.due_ledger, append-only; reversals are entries of their own), without the printed memo number and the free-text note. is_effective = true.
owner: backend:sync | capture: SERVER | retention: transaction | pii: none';
COMMENT ON VIEW dw.v_stock_movement IS 'Stable view: every stock movement (issue, return and the other kinds of app.stock_movement) in entered and base units. is_effective = not voided.
owner: backend:sync | capture: OFFLINE | retention: transaction | pii: none';
COMMENT ON VIEW dw.v_outlet_sku_daily IS 'Stable view: quantity sold and memo count per outlet, SKU and business date (dw.agg_daily_outlet_sku). is_effective = true.
owner: worker | capture: SERVER | retention: event_fact | pii: none';
COMMENT ON VIEW dw.v_dim_geo IS 'Stable view: the current route and geography dimension (dw.dim_geo; versions in dw.dim_geo_version). is_effective = route active.
owner: worker | capture: SERVER | retention: master | pii: none';
COMMENT ON VIEW dw.v_dim_outlet IS 'Stable view: the current outlet dimension (dw.dim_outlet): shop name and code, placement and class; no owner name, phone number or coordinates. is_effective = outlet active.
owner: worker | capture: SERVER | retention: master | pii: none';
COMMENT ON VIEW dw.v_dim_user IS 'Stable view: the current user dimension (dw.dim_user): role, designation, home zone, status; no names or logins. is_effective = account active.
owner: worker | capture: SERVER | retention: master | pii: none';
COMMENT ON VIEW dw.v_dim_product IS 'Stable view: the current product dimension (dw.dim_product): SKU, base unit, pack size and the category, segment, brand and variant tree. is_effective = true.
owner: worker | capture: SERVER | retention: master | pii: none';

-- Column comments: a column the view takes unchanged from its source keeps the source's comment; the others below.
DO $$
DECLARE
  r record;
  c record;
BEGIN
  FOR r IN SELECT * FROM (VALUES ('v_memo', 'dw.fact_memo'), ('v_memo_line', 'dw.fact_memo_line'), ('v_visit', 'dw.fact_visit'),
                                 ('v_due_ledger', 'app.due_ledger'), ('v_stock_movement', 'app.stock_movement'),
                                 ('v_outlet_sku_daily', 'dw.agg_daily_outlet_sku'), ('v_dim_geo', 'dw.dim_geo'),
                                 ('v_dim_outlet', 'dw.dim_outlet'), ('v_dim_user', 'dw.dim_user'), ('v_dim_product', 'dw.dim_product')) AS v(view, src)
  LOOP
    FOR c IN SELECT a.attname, col_description(s.attrelid, s.attnum) AS d
               FROM pg_attribute a JOIN pg_attribute s ON s.attrelid = r.src::regclass AND s.attname = a.attname AND s.attnum > 0
              WHERE a.attrelid = ('dw.' || r.view)::regclass AND a.attnum > 0 AND col_description(s.attrelid, s.attnum) IS NOT NULL
    LOOP
      EXECUTE format('COMMENT ON COLUMN dw.%I.%I IS %L', r.view, c.attname, c.d);
    END LOOP;
  END LOOP;
END $$;
COMMENT ON COLUMN dw.v_due_ledger.ledger_entry_id IS 'Ledger entry (app.due_ledger.id).';
COMMENT ON COLUMN dw.v_stock_movement.movement_client_uuid IS 'Client-generated UUID of the stock movement (idempotency key).';
COMMENT ON COLUMN dw.v_dim_product.data_origin IS 'Always production: products carry no origin of their own (every SKU is catalogue data).';
DO $$
DECLARE
  v text;
BEGIN
  FOREACH v IN ARRAY ARRAY['v_memo', 'v_memo_line', 'v_visit', 'v_due_ledger', 'v_stock_movement', 'v_outlet_sku_daily',
                           'v_dim_geo', 'v_dim_outlet', 'v_dim_user', 'v_dim_product'] LOOP
    EXECUTE format('COMMENT ON COLUMN dw.%I.is_effective IS %L', v,
      'True when the row counts in an ordinary total (the rule of this view is in its description).');
  END LOOP;
END $$;

-- ---------- 3. is_effective on every existing stable view (appended last; grants kept) ----------
CREATE OR REPLACE VIEW dw.v_calendar AS
 SELECT d.business_date,
    d.month,
    d.iso_weekday,
    d.iso_weekday = 5 AS is_friday,
    d.is_weekend,
    h.off_id IS NOT NULL AS is_holiday,
    COALESCE(h.off_name, m.makeup_name) AS holiday_name,
    m.makeup_id IS NOT NULL AS is_makeup_day,
    m.makeup_id IS NOT NULL OR NOT (d.is_weekend OR h.off_id IS NOT NULL) AS is_working_day,
    true AS is_effective
   FROM dw.dim_date d
     LEFT JOIN LATERAL ( SELECT c.id AS off_id,
            c.name_en AS off_name
           FROM app.calendar_holiday c
          WHERE c.date = d.business_date AND c.scope_type = 'global'::text AND c.revoked_at IS NULL AND NOT c.selling_day) h ON true
     LEFT JOIN LATERAL ( SELECT c.id AS makeup_id,
            c.name_en AS makeup_name
           FROM app.calendar_holiday c
          WHERE c.date = d.business_date AND c.scope_type = 'global'::text AND c.revoked_at IS NULL AND c.selling_day) m ON true;

CREATE OR REPLACE VIEW dw.v_attendance AS
 SELECT business_date,
    user_id,
    role,
    zone_id,
    check_in_at,
    check_out_at,
        CASE
            WHEN check_in_at IS NOT NULL AND check_out_at IS NOT NULL THEN round(EXTRACT(epoch FROM check_out_at - check_in_at) / 3600.0, 2)
            ELSE NULL::numeric
        END AS hours_in_field,
    check_in_is_mock,
    check_out_is_mock,
    updated_at,
    data_origin,
    true AS is_effective
   FROM dw.fact_attendance f;

CREATE OR REPLACE VIEW dw.v_calendar_month AS
 SELECT month,
    count(*)::integer AS days,
    count(*) FILTER (WHERE is_friday)::integer AS fridays,
    count(*) FILTER (WHERE is_holiday)::integer AS holidays,
    count(*) FILTER (WHERE is_working_day)::integer AS working_days,
    true AS is_effective
   FROM dw.v_calendar
  GROUP BY month;

CREATE OR REPLACE VIEW dw.v_collections AS
 SELECT a.business_date,
    a.outlet_id,
    o.outlet_code,
    o.outlet_name,
    COALESCE(a.route_id, o.route_id) AS route_id,
    o.zone_id,
    a.net_mtk AS sales_net_mtk,
    a.due_mtk AS new_due_mtk,
    a.dues_collected_mtk,
    a.updated_at,
    a.data_origin,
    true AS is_effective
   FROM dw.agg_daily_outlet a
     LEFT JOIN dw.dim_outlet o ON o.outlet_id = a.outlet_id
  WHERE a.due_mtk <> 0 OR a.dues_collected_mtk <> 0;

CREATE OR REPLACE VIEW dw.v_daily_outlet AS
 SELECT a.business_date,
    a.outlet_id,
    o.outlet_code,
    o.outlet_name,
    COALESCE(a.route_id, o.route_id) AS route_id,
    o.cluster_id,
    o.zone_id,
    o.channel,
    o.sub_channel_id,
    o.geo_class,
    o.outlet_kind,
    o.location_confirmed,
    a.visited,
    a.geo_valid,
    a.active_memo_count,
    a.cigarette_sold_sticks,
    a.bidi_sold_sticks,
    a.lighter_sold_pieces,
    a.match_sold_dozens,
    a.net_mtk,
    a.due_mtk,
    a.dues_collected_mtk,
    a.updated_at,
    a.data_origin,
    true AS is_effective
   FROM dw.agg_daily_outlet a
     LEFT JOIN dw.dim_outlet o ON o.outlet_id = a.outlet_id;

CREATE OR REPLACE VIEW dw.v_daily_route AS
 SELECT a.business_date,
    a.route_id,
    g.route_code,
    g.route_name,
    g.visit_kind,
    a.zone_id,
    g.zone_name,
    g.territory_id,
    g.territory_name,
    g.division_id,
    g.division_name,
    g.wing_id,
    g.wing_name,
    a.planned,
    a.exception_approved,
    a.day_state,
    a.logged_in_at,
    a.sales_submitted_at,
    a.final_submitted_at,
    a.target_outlets,
    a.visited_outlets,
    a.successful_calls,
    dw.pct(a.successful_calls::numeric, a.target_outlets::numeric) AS strike_rate_pct,
    a.visits,
    a.geo_valid_visits,
    dw.pct(a.geo_valid_visits::numeric, a.visits::numeric) AS geo_valid_pct,
    a.force_sale_visits,
    a.mock_visits,
    a.suspicious_visits,
    a.active_memo_count,
    a.gross_mtk,
    a.offer_discount_mtk,
    a.drp_discount_mtk,
    a.qc_deduction_mtk,
    a.net_mtk,
    a.paid_mtk,
    a.due_mtk,
    a.dues_collected_mtk,
    a.late_rows_after_final,
    a.updated_at,
    a.data_origin,
    true AS is_effective
   FROM dw.agg_daily_route a
     LEFT JOIN dw.dim_geo g ON g.route_id = a.route_id;

CREATE OR REPLACE VIEW dw.v_daily_sku AS
 SELECT a.business_date,
    a.route_id,
    g.route_code,
    g.zone_id,
    g.territory_id,
    a.sku_id,
    p.sku_code,
    p.short_name,
    p.base_unit,
    p.base_per_pack,
    p.brand_id,
    p.brand_name,
    p.category_code,
    a.sold_qty_base,
    a.free_qty_base,
    a.issued_qty_base,
    a.returned_qty_base,
    a.gross_mtk,
    a.memo_count,
    a.updated_at,
    a.data_origin,
    true AS is_effective
   FROM dw.agg_daily_route_sku a
     LEFT JOIN dw.dim_product p ON p.sku_id = a.sku_id
     LEFT JOIN dw.dim_geo g ON g.route_id = a.route_id;

CREATE OR REPLACE VIEW dw.v_daily_sr AS
 SELECT business_date,
    user_id,
    array_agg(DISTINCT route_id) FILTER (WHERE k = 'v'::text AND route_id IS NOT NULL) AS route_ids,
    count(*) FILTER (WHERE k = 'v'::text) AS visits,
    count(DISTINCT outlet_id) FILTER (WHERE k = 'v'::text AND reached) AS visited_outlets,
    count(DISTINCT outlet_id) FILTER (WHERE k = 'm'::text) AS successful_calls,
    count(*) FILTER (WHERE k = 'v'::text AND server_verdict = 'in_range'::text) AS geo_valid_visits,
    dw.pct(count(*) FILTER (WHERE k = 'v'::text AND server_verdict = 'in_range'::text)::numeric, count(*) FILTER (WHERE k = 'v'::text)::numeric) AS geo_valid_pct,
    count(*) FILTER (WHERE k = 'v'::text AND geo_action = 'force_sale'::text) AS force_sale_visits,
    count(*) FILTER (WHERE k = 'v'::text AND is_mock) AS mock_visits,
    count(*) FILTER (WHERE k = 'm'::text) AS active_memo_count,
    COALESCE(sum(gross_mtk), 0::numeric)::bigint AS gross_mtk,
    COALESCE(sum(net_mtk), 0::numeric)::bigint AS net_mtk,
    COALESCE(sum(due_mtk), 0::numeric)::bigint AS due_mtk,
    count(*) FILTER (WHERE k = 'm'::text AND captured_offline) AS memos_captured_offline,
    min(at) FILTER (WHERE k = 'v'::text) AS first_visit_at,
    max(end_at) FILTER (WHERE k = 'v'::text) AS last_visit_end_at,
    count(*) FILTER (WHERE k = 'm'::text AND entry_source = 'manual'::text) AS memos_entered_manually,
    max(data_origin) AS data_origin,
    true AS is_effective
   FROM ( SELECT 'v'::text AS k,
            fact_visit.business_date,
            fact_visit.user_id,
            fact_visit.route_id,
            fact_visit.outlet_id,
            fact_visit.outcome_code IS DISTINCT FROM 'abandoned'::text AND fact_visit.call_declined IS NOT TRUE AS reached,
            fact_visit.server_verdict,
            fact_visit.geo_action,
            fact_visit.is_mock,
            NULL::bigint AS gross_mtk,
            NULL::bigint AS net_mtk,
            NULL::bigint AS due_mtk,
            NULL::boolean AS captured_offline,
            fact_visit.opened_at AS at,
            COALESCE(fact_visit.ended_at, fact_visit.opened_at) AS end_at,
            fact_visit.entry_source,
            fact_visit.data_origin
           FROM dw.fact_visit
          WHERE NOT fact_visit.voided AND fact_visit.visit_kind = 'sr_call'::text
        UNION ALL
         SELECT 'm'::text AS text,
            fact_memo.business_date,
            fact_memo.user_id,
            fact_memo.route_id,
            fact_memo.outlet_id,
            NULL::boolean AS bool,
            NULL::text AS text,
            NULL::text AS text,
            NULL::boolean AS bool,
            fact_memo.gross_mtk,
            fact_memo.net_mtk,
            fact_memo.due_mtk,
            fact_memo.captured_offline,
            NULL::timestamp with time zone AS timestamptz,
            NULL::timestamp with time zone AS timestamptz,
            fact_memo.entry_source,
            fact_memo.data_origin
           FROM dw.fact_memo
          WHERE fact_memo.status = 'active'::text AND fact_memo.line_count > 0) x
  GROUP BY business_date, user_id;

CREATE OR REPLACE VIEW dw.v_device_command AS
 SELECT seq,
    command_id,
    device_id,
    type,
    state,
    reason,
    created_by,
    created_at,
    expires_at,
    approved_by,
    approved_at,
    decision_reason,
    push_sent_at,
    sent_at,
    acked_at,
    COALESCE(( SELECT max(u.data_origin) AS max
           FROM app.device_binding b
             JOIN app.app_user u ON u.id = b.user_id
          WHERE b.device_id = c.device_id AND b.status = 'active'::text), 'production'::app.data_origin) AS data_origin,
    state <> ALL (ARRAY['rejected'::text, 'expired'::text, 'failed'::text]) AS is_effective
   FROM app.device_command c;

CREATE OR REPLACE VIEW dw.v_final_submit_log AS
 SELECT DISTINCT ON (zone_id, business_date) zone_id,
    business_date,
    submitted_at AS first_submitted_at,
    submitted_by AS first_submitted_by,
    via AS first_via,
    count(*) OVER w AS submits,
    max(submitted_at) OVER w AS last_submitted_at,
    NOT bool_or(reopened_at IS NULL) OVER w AS reopened_now,
    max(data_origin) OVER w AS data_origin,
    bool_or(reopened_at IS NULL) OVER w AS is_effective
   FROM app.final_submit f
  WINDOW w AS (PARTITION BY zone_id, business_date)
  ORDER BY zone_id, business_date, submitted_at, id;

CREATE OR REPLACE VIEW dw.v_geo_integrity AS
 SELECT business_date,
    user_id,
    count(*) FILTER (WHERE k = 'v'::text) AS visits,
    count(*) FILTER (WHERE k = 'v'::text AND server_verdict = 'in_range'::text) AS server_in_range,
    dw.pct(count(*) FILTER (WHERE k = 'v'::text AND server_verdict = 'in_range'::text)::numeric, count(*) FILTER (WHERE k = 'v'::text)::numeric) AS geo_valid_pct,
    count(*) FILTER (WHERE k = 'v'::text AND server_verdict = 'out_of_range'::text) AS server_out_of_range,
    count(*) FILTER (WHERE k = 'v'::text AND device_verdict = 'no_fix'::text) AS no_fix,
    count(*) FILTER (WHERE k = 'v'::text AND device_verdict = 'accuracy_too_low'::text) AS accuracy_too_low,
    count(*) FILTER (WHERE k = 'v'::text AND (device_verdict = 'mocked'::text OR is_mock)) AS mocked_visits,
    count(*) FILTER (WHERE k = 'v'::text AND geo_action = 'force_sale'::text) AS force_sale_visits,
    count(*) FILTER (WHERE k = 'v'::text AND geo_action = 'blocked'::text) AS blocked_visits,
    count(*) FILTER (WHERE k = 'v'::text AND server_verdict IS NOT NULL AND server_verdict <> device_verdict) AS device_server_mismatch,
    count(*) FILTER (WHERE k = 'f'::text) AS fixes,
    count(*) FILTER (WHERE k = 'f'::text AND is_mock) AS mock_fixes,
    round(avg(accuracy_m)::numeric, 1) AS avg_accuracy_m,
    max(data_origin) AS data_origin,
    true AS is_effective
   FROM ( SELECT 'v'::text AS k,
            fact_visit.business_date,
            fact_visit.user_id,
            fact_visit.server_verdict,
            fact_visit.device_verdict,
            fact_visit.geo_action,
            fact_visit.is_mock,
            NULL::double precision AS accuracy_m,
            fact_visit.data_origin
           FROM dw.fact_visit
          WHERE NOT fact_visit.voided
        UNION ALL
         SELECT 'f'::text AS text,
            fact_geo_fix.business_date,
            fact_geo_fix.user_id,
            NULL::text AS text,
            NULL::text AS text,
            NULL::text AS text,
            fact_geo_fix.is_mock,
            fact_geo_fix.accuracy_m,
            fact_geo_fix.data_origin
           FROM dw.fact_geo_fix) x
  GROUP BY business_date, user_id;

CREATE OR REPLACE VIEW dw.v_memo_flags AS
 SELECT m.id AS memo_id,
    m.client_uuid AS memo_client_uuid,
    m.family_uuid AS memo_family_uuid,
    m.memo_no,
    m.business_date,
    m.status,
    m.user_id,
    m.outlet_id,
    o.code AS outlet_code,
    o.name AS outlet_name,
    s.route_id,
    z.id AS zone_id,
    z.territory_id,
    t.division_id,
    d.wing_id,
    m.gross_mtk,
    m.server_flags AS flags,
    m.received_at AS stored_at,
    m.voided_at,
    m.data_origin,
    m.status = 'active'::text AND m.voided_at IS NULL AND m.line_count > 0 AS is_effective
   FROM app.memo m
     JOIN app.outlet o ON o.id = m.outlet_id
     LEFT JOIN LATERAL ( SELECT COALESCE(m.route_id, p.route_id, o.route_id) AS route_id,
            COALESCE(m.zone_id, app.route_zone_on(p.route_id, m.business_date), ( SELECT r.zone_id
                   FROM app.route r
                  WHERE r.id = p.route_id), o.zone_id) AS zone_id
           FROM ( SELECT ( SELECT h.route_id
                           FROM app.outlet_placement_history h
                          WHERE m.route_id IS NULL AND h.outlet_id = m.outlet_id AND h.valid_from <= m.business_date AND (h.valid_to IS NULL OR h.valid_to > m.business_date)) AS route_id) p) s ON true
     LEFT JOIN app.zone z ON z.id = s.zone_id
     LEFT JOIN app.territory t ON t.id = z.territory_id
     LEFT JOIN app.division d ON d.id = t.division_id
  WHERE m.server_flags <> '{}'::text[];

CREATE OR REPLACE VIEW dw.v_outlet_masked AS
 SELECT id AS outlet_id,
    code AS outlet_code,
    name AS outlet_name,
    name_bn AS outlet_name_bn,
    zone_id,
    route_id,
    cluster_id,
    channel,
    sub_channel_id,
    geo_class,
    outlet_kind,
    price_type,
    status,
    location_confirmed,
        CASE
            WHEN contact_number ~ '^01[0-9]{9}$'::text THEN '01*****'::text || "right"(contact_number, 3)
            ELSE NULL::text
        END AS contact_number_masked,
    updated_at,
    data_origin,
    status = 'active'::text AS is_effective
   FROM app.outlet o;

CREATE OR REPLACE VIEW dw.v_route_monthly_target AS
 SELECT t.month,
    t.route_id,
    t.sku_id,
    s.category_code,
    s.base_unit,
    t.qty_base AS monthly_qty_base,
    cm.working_days,
        CASE
            WHEN cm.working_days > 0 THEN t.qty_base::numeric / cm.working_days::numeric
            ELSE NULL::numeric
        END AS daily_qty_base,
    t.batch_uuid,
    t.updated_at,
    r.data_origin,
    true AS is_effective
   FROM app.route_monthly_target t
     JOIN app.sku s ON s.id = t.sku_id
     JOIN app.route r ON r.id = t.route_id
     LEFT JOIN dw.v_calendar_month cm ON cm.month = t.month;

CREATE OR REPLACE VIEW dw.v_working_day AS
 SELECT business_date AS day,
    is_working_day AS working,
    true AS is_effective
   FROM dw.v_calendar;


COMMENT ON COLUMN dw.v_memo_flags.is_effective IS 'True when the memo counts: status active, not data-voided and at least one line (as dw.v_memo).';
COMMENT ON COLUMN dw.v_outlet_masked.is_effective IS 'True when the outlet is active.';
COMMENT ON COLUMN dw.v_device_command.is_effective IS 'True unless the command was rejected, expired or failed.';
COMMENT ON COLUMN dw.v_final_submit_log.is_effective IS 'True while some final submit of the zone-day still stands (not every submit reopened).';
DO $$
DECLARE
  v text;
BEGIN
  FOREACH v IN ARRAY ARRAY['v_attendance', 'v_calendar', 'v_calendar_month', 'v_collections', 'v_daily_outlet', 'v_daily_route',
                           'v_daily_sku', 'v_daily_sr', 'v_geo_integrity', 'v_route_monthly_target', 'v_working_day'] LOOP
    EXECUTE format('COMMENT ON COLUMN dw.%I.is_effective IS %L', v,
      'Always true: the row is an aggregate or reference row built from effective rows only.');
  END LOOP;
END $$;

-- ---------- 4. view contract version ----------
CREATE FUNCTION dw.view_contract_version() RETURNS text
LANGUAGE sql IMMUTABLE
AS $$ SELECT '1.0' $$;
COMMENT ON FUNCTION dw.view_contract_version() IS 'Version of the column contract of the dw.v_* views (docs/dw-view-contract.md): minor = columns added, major = a column removed or renamed. Lake manifests carry it.';

-- ---------- 5. the printed memo number is personal data ----------
DO $$
DECLARE
  r record;
  d text;
BEGIN
  FOR r IN SELECT * FROM (VALUES ('app.memo', 'memo_no'), ('app.memo_void', 'memo_no'), ('app.sale_abort', 'memo_no'),
                                 ('app.due_ledger', 'memo_no'), ('app.due_collection', 'against_memo_no'),
                                 ('dw.fact_memo', 'memo_no'), ('dw.v_memo_flags', 'memo_no')) AS v(rel, col)
  LOOP
    SELECT col_description(a.attrelid, a.attnum) INTO d FROM pg_attribute a WHERE a.attrelid = r.rel::regclass AND a.attname = r.col;
    IF d !~ '^\[pii:' THEN
      EXECUTE format('COMMENT ON COLUMN %s.%I IS %L', r.rel, r.col,
        '[pii:personal] ' || d || ' Contains the SR''s login, so it is personal data: exports use the memo UUID instead.');
    END IF;
    d := obj_description(r.rel::regclass);
    IF d ~ 'pii: none$' THEN
      EXECUTE format('COMMENT ON %s %s IS %L', CASE WHEN r.rel LIKE 'dw.v\_%' THEN 'VIEW' ELSE 'TABLE' END, r.rel,
        regexp_replace(d, 'pii: none$', 'pii: personal'));
    END IF;
  END LOOP;
END $$;

-- The BI reader never gets the printed memo number (personal). The grant map's v_* rows now honour except_tables (the
-- generator below is V0020's with that one condition added): bi_reader's v_* row skips dw.v_memo_flags, and its own
-- row grants that view without memo_no (column grants). The API reads memo_no with its own role (flagged memos).
CREATE OR REPLACE FUNCTION app.apply_db_role_grants()
 RETURNS void
 LANGUAGE plpgsql
AS $fn$
DECLARE
  g    record;
  t    record;
  cols text;
BEGIN
  FOR t IN SELECT n.nspname, c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname IN ('app','dw') AND c.relkind IN ('r','p','v','m') LOOP
    EXECUTE format('REVOKE ALL ON %I.%I FROM api_rw, worker_rw, jobs_rw, web_ro, bi_reader, auth_rw, pii_reader, support_ro',
                   t.nspname, t.relname);
  END LOOP;
  FOR g IN SELECT * FROM app.db_role_grant LOOP
    FOR t IN SELECT c.oid, c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
              WHERE n.nspname = g.schema_name AND c.relkind IN ('r','p','v','m') AND NOT c.relispartition
                AND ((g.object = 'v_*' AND c.relkind = 'v' AND c.relname LIKE 'v\_%' AND c.relname <> ALL (g.except_tables))
                  OR (g.object <> 'v_*' AND (c.relname = g.object OR (g.object LIKE '*%' AND c.relname <> ALL (g.except_tables)))))
    LOOP
      IF cardinality(g.only_columns) > 0 THEN
        cols := (SELECT string_agg(quote_ident(x), ', ') FROM unnest(g.only_columns) x);
      ELSIF cardinality(g.except_columns) > 0 THEN
        cols := (SELECT string_agg(quote_ident(a.attname), ', ' ORDER BY a.attnum) FROM pg_attribute a
                  WHERE a.attrelid = t.oid AND a.attnum > 0 AND NOT a.attisdropped AND a.attname <> ALL (g.except_columns));
      ELSE
        cols := NULL;
      END IF;
      IF cols IS NULL THEN
        EXECUTE format('GRANT %s ON %I.%I TO %I', g.privileges, g.schema_name, t.relname, g.role);
      ELSE
        EXECUTE format('GRANT %s (%s) ON %I.%I TO %I', g.privileges, cols, g.schema_name, t.relname, g.role);
      END IF;
    END LOOP;
  END LOOP;
  EXECUTE 'GRANT USAGE ON ALL SEQUENCES IN SCHEMA app TO api_rw, worker_rw, auth_rw';
  EXECUTE 'GRANT USAGE ON ALL SEQUENCES IN SCHEMA dw TO worker_rw';
  EXECUTE 'REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA app, dw FROM PUBLIC';
  EXECUTE 'GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA app TO api_rw, worker_rw, auth_rw';
  EXECUTE 'GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA dw TO api_rw, worker_rw, web_ro, bi_reader';
  EXECUTE 'REVOKE EXECUTE ON FUNCTION app.apply_db_role_grants(), app.ensure_partitions(date, date), app.apply_login_limits() '
          'FROM api_rw, worker_rw, auth_rw';
  EXECUTE 'GRANT EXECUTE ON FUNCTION app.ensure_partitions(date, date) TO jobs_rw';
END $fn$;
COMMENT ON FUNCTION app.apply_db_role_grants() IS 'Re-applies every GRANT of the map app.db_role_grant (docs/16 s13.4b); a v_* row honours except_tables since V0118.';
UPDATE app.db_role_grant SET except_tables = ARRAY['v_memo_flags']
 WHERE role = 'bi_reader' AND schema_name = 'dw' AND object = 'v_*';
INSERT INTO app.db_role_grant (role, schema_name, object, privileges, except_tables, except_columns, only_columns, note) VALUES
  ('bi_reader', 'dw', 'v_memo_flags', 'SELECT', '{}', '{memo_no}', '{}', 'flagged memos without the printed memo number (personal, V0118)');

-- ---------- 6. reconcile and restatement records (backend-reports' rebuild/reconcile slice) ----------
CREATE TABLE dw.agg_reconcile (
  id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  run_at           timestamptz NOT NULL DEFAULT now(),
  business_date    date        NOT NULL,
  grain            text        NOT NULL CHECK (grain IN ('route', 'zone')),
  key_id           bigint      NOT NULL,
  metric           text        NOT NULL CHECK (metric IN ('net_mtk', 'gross_mtk', 'memo_count', 'visit_count', 'qty_base')),
  source_value     bigint      NOT NULL,
  agg_value        bigint      NOT NULL,
  diff             bigint      GENERATED ALWAYS AS (source_value - agg_value) STORED,
  consecutive_runs integer     NOT NULL DEFAULT 1 CHECK (consecutive_runs >= 1),
  re_enqueued      boolean     NOT NULL DEFAULT false,
  resolved_at      timestamptz
);
-- One open finding per date, grain, key and metric: the nightly run upserts it and counts consecutive runs.
-- squawk-ignore require-concurrent-index-creation
CREATE UNIQUE INDEX agg_reconcile_open ON dw.agg_reconcile (business_date, grain, key_id, metric) WHERE resolved_at IS NULL;

CREATE TABLE dw.agg_restatement_log (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  changed_at     timestamptz NOT NULL DEFAULT now(),
  business_date  date        NOT NULL,
  grain          text        NOT NULL CHECK (grain IN ('route', 'route_sku', 'route_brand', 'route_segment', 'route_variant',
                                                       'outlet', 'outlet_sku', 'zone', 'hourly_zone', 'month')),
  key1           bigint      NOT NULL,
  key2           bigint,
  reason         text        NOT NULL CHECK (reason IN ('late_batch', 'memo_superseded', 'memo_voided', 'target_revision',
                                                        'dim_backdate', 'web_entry', 'import', 'rebuild', 'reconcile')),
  old_row        jsonb       NOT NULL DEFAULT '{}'::jsonb,
  new_row        jsonb       NOT NULL DEFAULT '{}'::jsonb,
  delta          jsonb       NOT NULL DEFAULT '{}'::jsonb,
  rebuild_id     uuid
);
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX agg_restatement_log_key ON dw.agg_restatement_log (business_date, grain, key1);
CREATE TRIGGER agg_restatement_log_append_only BEFORE UPDATE OR DELETE ON dw.agg_restatement_log
  FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();

COMMENT ON TABLE dw.agg_reconcile IS 'Nightly reconcile findings (docs/16 s8.6): a source total from app that disagrees with its aggregate for a key and business date; the key is re-enqueued and an alert raised when it survives two runs.
owner: worker | capture: SERVER | retention: audit | pii: none';
COMMENT ON COLUMN dw.agg_reconcile.id IS 'Server surrogate key.';
COMMENT ON COLUMN dw.agg_reconcile.run_at IS 'UTC instant of the reconcile run that found (or last confirmed) the difference.';
COMMENT ON COLUMN dw.agg_reconcile.business_date IS 'Asia/Dhaka business date checked.';
COMMENT ON COLUMN dw.agg_reconcile.grain IS 'Aggregate grain checked: route (dw.agg_daily_route) or zone (dw.agg_daily_zone).';
COMMENT ON COLUMN dw.agg_reconcile.key_id IS 'route_id or zone_id of the checked row.';
COMMENT ON COLUMN dw.agg_reconcile.metric IS 'Figure compared: net_mtk, gross_mtk, memo_count, visit_count or qty_base.';
COMMENT ON COLUMN dw.agg_reconcile.source_value IS 'Figure recomputed from the app source rows (money in milli-taka).';
COMMENT ON COLUMN dw.agg_reconcile.agg_value IS 'Figure the aggregate held (money in milli-taka).';
COMMENT ON COLUMN dw.agg_reconcile.diff IS 'source_value - agg_value.';
COMMENT ON COLUMN dw.agg_reconcile.consecutive_runs IS 'Number of consecutive nightly runs that found this difference (alert at 2).';
COMMENT ON COLUMN dw.agg_reconcile.re_enqueued IS 'True once the key was re-enqueued for a rebuild.';
COMMENT ON COLUMN dw.agg_reconcile.resolved_at IS 'UTC instant a later run found the figures equal; null while open.';
COMMENT ON TABLE dw.agg_restatement_log IS 'Restatement log (docs/16 s8.6-s8.7): one row per aggregate key whose figures changed on a closed business date, with the old and new figures and why. Append-only.
owner: worker | capture: SERVER | retention: audit | pii: none';
COMMENT ON COLUMN dw.agg_restatement_log.id IS 'Server surrogate key.';
COMMENT ON COLUMN dw.agg_restatement_log.changed_at IS 'UTC instant the aggregate changed.';
COMMENT ON COLUMN dw.agg_restatement_log.business_date IS 'Asia/Dhaka business date of the changed aggregate row.';
COMMENT ON COLUMN dw.agg_restatement_log.grain IS 'Aggregate grain: route, route_sku, route_brand, route_segment, route_variant, outlet, outlet_sku, zone, hourly_zone or month.';
COMMENT ON COLUMN dw.agg_restatement_log.key1 IS 'First key of the row (route_id, outlet_id or zone_id by grain).';
COMMENT ON COLUMN dw.agg_restatement_log.key2 IS 'Second key of the row (sku_id, brand_id, segment_id, variant_id or hour by grain); null for one-key grains.';
COMMENT ON COLUMN dw.agg_restatement_log.reason IS 'Why it changed: late_batch, memo_superseded, memo_voided, target_revision, dim_backdate, web_entry, import, rebuild or reconcile.';
COMMENT ON COLUMN dw.agg_restatement_log.old_row IS 'Figures before the change (empty object for a new row); counts and milli-taka only, no names.';
COMMENT ON COLUMN dw.agg_restatement_log.new_row IS 'Figures after the change (empty object for a removed row).';
COMMENT ON COLUMN dw.agg_restatement_log.delta IS 'new minus old per figure.';
COMMENT ON COLUMN dw.agg_restatement_log.rebuild_id IS 'Audited rebuild request that caused the change (reason rebuild), if any.';

SELECT app.apply_db_role_grants();
