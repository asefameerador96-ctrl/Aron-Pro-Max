-- V0011 aggregate (dw) tables and the dirty-key queue (row N-007, schema v1c). docs/24 s6.3, s12.1, s12.4.
-- Dashboards and reports read only dw (from the read replica when configured), never the transaction tables. The
-- projector (backend worker) reads app.domain_event in id order and upserts these rows idempotently; last_event_id
-- records the newest event folded into a row, so a replayed event never counts twice. Money is bigint milli-taka,
-- quantities integer base units; every row is keyed by the Dhaka business_date.

-- ---------- dimensions ----------
CREATE TABLE dw.dim_date (
  business_date   date PRIMARY KEY,
  iso_weekday     smallint NOT NULL CHECK (iso_weekday BETWEEN 1 AND 7),   -- 5 = Friday
  visit_day_bit   smallint NOT NULL CHECK (visit_day_bit BETWEEN 0 AND 6), -- bit of route.visit_days_mask (0 = Saturday)
  month           date NOT NULL,
  quarter         text NOT NULL CHECK (quarter ~ '^[0-9]{4}-Q[1-4]$'),
  iso_week        smallint NOT NULL,
  is_weekend      boolean NOT NULL,                                        -- cfg.calendar.weekend_days default (Friday)
  is_holiday      boolean NOT NULL DEFAULT false,                          -- refreshed from app.calendar_holiday
  updated_at      timestamptz NOT NULL DEFAULT now()
);
INSERT INTO dw.dim_date (business_date, iso_weekday, visit_day_bit, month, quarter, iso_week, is_weekend)
SELECT d, extract(isodow FROM d)::smallint, ((extract(isodow FROM d)::int + 1) % 7)::smallint, date_trunc('month', d)::date,
       to_char(d, 'YYYY') || '-Q' || to_char(d, 'Q'), extract(week FROM d)::smallint, extract(isodow FROM d) = 5
  FROM generate_series(date '2026-01-01', date '2030-12-31', interval '1 day') AS g(t), LATERAL (SELECT g.t::date AS d) x;

CREATE TABLE dw.dim_geo (
  route_id        bigint PRIMARY KEY,
  route_code      text NOT NULL,
  route_name      text NOT NULL,
  route_kind      text NOT NULL,
  visit_kind      text,
  zone_id         bigint NOT NULL,
  zone_name       text NOT NULL,
  territory_id    bigint NOT NULL,
  territory_name  text NOT NULL,
  division_id     bigint NOT NULL,
  division_name   text NOT NULL,
  wing_id         bigint NOT NULL,
  wing_name       text NOT NULL,
  status          text NOT NULL,
  updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON dw.dim_geo (zone_id);
CREATE INDEX ON dw.dim_geo (territory_id);

CREATE TABLE dw.dim_product (
  sku_id          bigint PRIMARY KEY,
  sku_code        text NOT NULL,
  short_name      text NOT NULL,
  base_unit       text NOT NULL,
  base_per_pack   int NOT NULL,
  variant_id      bigint NOT NULL,
  variant_name    text NOT NULL,
  brand_id        bigint NOT NULL,
  brand_name      text NOT NULL,
  segment_id      bigint NOT NULL,
  segment_name    text NOT NULL,
  category_id     bigint NOT NULL,
  category_name   text NOT NULL,
  category_code   text NOT NULL,
  updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON dw.dim_product (brand_id);

CREATE TABLE dw.dim_outlet (
  outlet_id          bigint PRIMARY KEY,
  outlet_code        text NOT NULL,
  outlet_name        text NOT NULL,
  route_id           bigint,
  cluster_id         bigint NOT NULL,
  zone_id            bigint NOT NULL,
  channel            text NOT NULL,
  sub_channel_id     bigint,
  geo_class          text,
  outlet_kind        text NOT NULL,
  location_confirmed boolean NOT NULL,
  status             text NOT NULL,
  updated_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON dw.dim_outlet (route_id);

-- ---------- facts (one row per source record; range-partitioned like their sources) ----------
CREATE TABLE dw.fact_visit (
  business_date       date NOT NULL,
  visit_client_uuid   uuid NOT NULL,
  user_id             bigint NOT NULL,
  route_id            bigint,
  zone_id             bigint,
  outlet_id           bigint NOT NULL,
  visit_kind          text NOT NULL,
  opened_at           timestamptz NOT NULL,
  ended_at            timestamptz,
  outcome_code        text,
  call_declined       boolean,
  device_verdict      text NOT NULL,
  server_verdict      text,
  geo_action          text NOT NULL,
  distance_m          double precision,
  is_mock             boolean,
  planned             boolean NOT NULL,
  voided              boolean NOT NULL DEFAULT false,
  last_event_id       bigint NOT NULL,
  updated_at          timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (visit_client_uuid, business_date)
) PARTITION BY RANGE (business_date);
CREATE INDEX ON dw.fact_visit (route_id, business_date);
CREATE INDEX ON dw.fact_visit (outlet_id, business_date);
INSERT INTO app.partition_policy (parent) VALUES ('dw.fact_visit');

CREATE TABLE dw.fact_memo (
  business_date        date NOT NULL,
  memo_client_uuid     uuid NOT NULL,
  memo_no              text NOT NULL,
  user_id              bigint NOT NULL,
  route_id             bigint,
  zone_id              bigint,
  outlet_id            bigint NOT NULL,
  status               text NOT NULL,                -- active, voided, superseded
  committed_at         timestamptz NOT NULL,
  line_count           smallint NOT NULL,
  gross_mtk            bigint NOT NULL,
  offer_discount_mtk   bigint NOT NULL,
  drp_discount_mtk     bigint NOT NULL,
  qc_deduction_mtk     bigint NOT NULL,
  net_mtk              bigint NOT NULL,
  paid_mtk             bigint NOT NULL,
  due_mtk              bigint NOT NULL,
  is_credit            boolean NOT NULL,
  captured_offline     boolean NOT NULL,
  received_at          timestamptz NOT NULL,
  last_event_id        bigint NOT NULL,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (memo_client_uuid, business_date)
) PARTITION BY RANGE (business_date);
CREATE INDEX ON dw.fact_memo (route_id, business_date);
CREATE INDEX ON dw.fact_memo (outlet_id, business_date);
INSERT INTO app.partition_policy (parent) VALUES ('dw.fact_memo');

CREATE TABLE dw.fact_geo_fix (
  business_date        date NOT NULL,
  source_client_uuid   uuid NOT NULL,
  slot                 text NOT NULL,
  user_id              bigint NOT NULL,
  route_id             bigint,
  purpose              text NOT NULL,
  captured_at          timestamptz NOT NULL,
  lat                  double precision,
  lng                  double precision,
  accuracy_m           double precision,
  provider             text NOT NULL,
  is_mock              boolean NOT NULL,
  satellites_used      smallint,
  last_event_id        bigint NOT NULL,
  PRIMARY KEY (source_client_uuid, slot, business_date)
) PARTITION BY RANGE (business_date);
CREATE INDEX ON dw.fact_geo_fix (user_id, business_date);
INSERT INTO app.partition_policy (parent) VALUES ('dw.fact_geo_fix');

CREATE TABLE dw.fact_device_day (
  business_date       date NOT NULL,
  device_id           bigint NOT NULL,
  user_id             bigint,
  app_version         text,
  first_contact_at    timestamptz,
  last_contact_at     timestamptz,
  batches             int NOT NULL DEFAULT 0,
  records             int NOT NULL DEFAULT 0,
  rejected            int NOT NULL DEFAULT 0,
  quarantined         int NOT NULL DEFAULT 0,
  pending_rows_max    int,
  battery_pct_min     smallint,
  last_event_id       bigint NOT NULL DEFAULT 0,
  updated_at          timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (business_date, device_id)
);

-- ---------- aggregates ----------
-- KPIs of docs/24 s12.4 per route-day; zone and higher levels roll up from here (or from agg_daily_zone).
CREATE TABLE dw.agg_daily_route (
  business_date            date NOT NULL,
  route_id                 bigint NOT NULL,
  zone_id                  bigint NOT NULL,
  planned                  boolean NOT NULL DEFAULT false,
  exception_approved       boolean NOT NULL DEFAULT false,
  day_state                text NOT NULL DEFAULT 'not_started',
  logged_in_at             timestamptz,
  sales_submitted_at       timestamptz,
  final_submitted_at       timestamptz,
  target_outlets           int NOT NULL DEFAULT 0,
  visited_outlets          int NOT NULL DEFAULT 0,
  successful_calls         int NOT NULL DEFAULT 0,
  visits                   int NOT NULL DEFAULT 0,
  geo_valid_visits         int NOT NULL DEFAULT 0,
  force_sale_visits        int NOT NULL DEFAULT 0,
  mock_visits              int NOT NULL DEFAULT 0,
  suspicious_visits        int NOT NULL DEFAULT 0,          -- visits of suspicious user-days (s11.4)
  active_memo_count        int NOT NULL DEFAULT 0,
  gross_mtk                bigint NOT NULL DEFAULT 0,
  offer_discount_mtk       bigint NOT NULL DEFAULT 0,
  drp_discount_mtk         bigint NOT NULL DEFAULT 0,
  qc_deduction_mtk         bigint NOT NULL DEFAULT 0,
  net_mtk                  bigint NOT NULL DEFAULT 0,
  paid_mtk                 bigint NOT NULL DEFAULT 0,
  due_mtk                  bigint NOT NULL DEFAULT 0,
  dues_collected_mtk       bigint NOT NULL DEFAULT 0,
  late_rows_after_final    int NOT NULL DEFAULT 0,
  last_event_id            bigint NOT NULL DEFAULT 0,
  updated_at               timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (business_date, route_id)
);
CREATE INDEX ON dw.agg_daily_route (zone_id, business_date);

CREATE TABLE dw.agg_daily_route_sku (
  business_date       date NOT NULL,
  route_id            bigint NOT NULL,
  sku_id              bigint NOT NULL,
  sold_qty_base       bigint NOT NULL DEFAULT 0,
  free_qty_base       bigint NOT NULL DEFAULT 0,
  issued_qty_base     bigint NOT NULL DEFAULT 0,
  returned_qty_base   bigint NOT NULL DEFAULT 0,
  gross_mtk           bigint NOT NULL DEFAULT 0,
  memo_count          int NOT NULL DEFAULT 0,
  last_event_id       bigint NOT NULL DEFAULT 0,
  updated_at          timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (business_date, route_id, sku_id)
);
CREATE INDEX ON dw.agg_daily_route_sku (sku_id, business_date);

-- BSR per brand (s12.4): memos containing the brand, counted once per memo whatever the number of its SKUs.
CREATE TABLE dw.agg_daily_route_brand (
  business_date       date NOT NULL,
  route_id            bigint NOT NULL,
  brand_id            bigint NOT NULL,
  memo_count          int NOT NULL DEFAULT 0,          -- active memos with at least one line of the brand
  sold_qty_base       bigint NOT NULL DEFAULT 0,
  gross_mtk           bigint NOT NULL DEFAULT 0,
  last_event_id       bigint NOT NULL DEFAULT 0,
  updated_at          timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (business_date, route_id, brand_id)
);
CREATE INDEX ON dw.agg_daily_route_brand (brand_id, business_date);

CREATE TABLE dw.agg_daily_zone (
  business_date            date NOT NULL,
  zone_id                  bigint NOT NULL,
  territory_id             bigint NOT NULL,
  target_routes            int NOT NULL DEFAULT 0,
  logged_in_routes         int NOT NULL DEFAULT 0,
  sales_submitted_routes   int NOT NULL DEFAULT 0,
  final_submitted          boolean NOT NULL DEFAULT false,
  target_outlets           int NOT NULL DEFAULT 0,
  visited_outlets          int NOT NULL DEFAULT 0,
  successful_calls         int NOT NULL DEFAULT 0,
  visits                   int NOT NULL DEFAULT 0,
  geo_valid_visits         int NOT NULL DEFAULT 0,
  force_sale_visits        int NOT NULL DEFAULT 0,
  mock_visits              int NOT NULL DEFAULT 0,
  suspicious_visits        int NOT NULL DEFAULT 0,
  suspicious_user_days     int NOT NULL DEFAULT 0,
  active_memo_count        int NOT NULL DEFAULT 0,
  gross_mtk                bigint NOT NULL DEFAULT 0,
  net_mtk                  bigint NOT NULL DEFAULT 0,
  dues_collected_mtk       bigint NOT NULL DEFAULT 0,
  last_event_id            bigint NOT NULL DEFAULT 0,
  updated_at               timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (business_date, zone_id)
);
CREATE INDEX ON dw.agg_daily_zone (territory_id, business_date);

CREATE TABLE dw.agg_hourly_zone (
  business_date       date NOT NULL,
  zone_id             bigint NOT NULL,
  hour_of_day         smallint NOT NULL CHECK (hour_of_day BETWEEN 0 AND 23),   -- Dhaka hour
  visits              int NOT NULL DEFAULT 0,
  active_memo_count   int NOT NULL DEFAULT 0,
  net_mtk             bigint NOT NULL DEFAULT 0,
  records_received    int NOT NULL DEFAULT 0,
  last_event_id       bigint NOT NULL DEFAULT 0,
  updated_at          timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (business_date, zone_id, hour_of_day)
);

CREATE TABLE dw.agg_daily_outlet (
  business_date       date NOT NULL,
  outlet_id           bigint NOT NULL,
  route_id            bigint,
  visited             boolean NOT NULL DEFAULT false,
  geo_valid           boolean NOT NULL DEFAULT false,
  active_memo_count   int NOT NULL DEFAULT 0,
  sold_qty_base       bigint NOT NULL DEFAULT 0,
  net_mtk             bigint NOT NULL DEFAULT 0,
  due_mtk             bigint NOT NULL DEFAULT 0,
  dues_collected_mtk  bigint NOT NULL DEFAULT 0,
  last_event_id       bigint NOT NULL DEFAULT 0,
  updated_at          timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (business_date, outlet_id)
);
CREATE INDEX ON dw.agg_daily_outlet (outlet_id);

-- ---------- dirty keys ----------
-- Work queue of things to rebuild: a user's bundle snapshot (refreshed at cfg.bundle.refresh_time and on delta),
-- an aggregate row to recompute after a late, voided or resolved record. One row per kind, subject and date;
-- repeated dirtying bumps the counter and clears the claim. Workers claim with FOR UPDATE SKIP LOCKED (rows unclaimed,
-- or claimed longer ago than the worker's lease, which covers a crashed worker), and when done delete only if
-- dirty_count is unchanged since the claim (a key dirtied again meanwhile stays queued).
CREATE TABLE app.dirty_key (
  kind              text NOT NULL CHECK (kind IN ('bundle_user','route_day_agg','zone_day_agg','outlet_day_agg','device_day_agg',
                                                  'route_snapshot','risk_user_day')),
  subject_id        bigint NOT NULL,
  business_date     date NOT NULL,
  first_dirtied_at  timestamptz NOT NULL DEFAULT now(),
  last_dirtied_at   timestamptz NOT NULL DEFAULT now(),
  dirty_count       int NOT NULL DEFAULT 1 CHECK (dirty_count >= 1),
  reason            text,
  claimed_at        timestamptz,
  claimed_by        text,
  PRIMARY KEY (kind, subject_id, business_date)
);
CREATE INDEX dirty_key_queue ON app.dirty_key (kind, claimed_at, last_dirtied_at);

-- Marks a key dirty (idempotent; safe inside any write transaction).
CREATE FUNCTION app.mark_dirty(p_kind text, p_subject bigint, p_date date, p_reason text DEFAULT NULL) RETURNS void
LANGUAGE sql
AS $$
  INSERT INTO app.dirty_key (kind, subject_id, business_date, reason) VALUES (p_kind, p_subject, p_date, p_reason)
  ON CONFLICT (kind, subject_id, business_date)
  DO UPDATE SET last_dirtied_at = now(), dirty_count = app.dirty_key.dirty_count + 1, claimed_at = NULL, claimed_by = NULL,
                reason = coalesce(excluded.reason, app.dirty_key.reason)
$$;

-- Partitions of the new dw facts for the build period (the worker keeps them ahead afterwards).
SELECT app.ensure_partitions('2026-01-01', '2027-12-01');
