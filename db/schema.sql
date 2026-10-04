-- Aron rebuild — PostgreSQL schema (starting point)
-- Conventions: UTC timestamptz; business_date = Asia/Dhaka date on transactions.
-- Device-originated rows carry client_uuid UNIQUE (idempotency key for sync).
-- This is the spine to start from; extend per docs/03 and docs/10.

-- ---------- enums ----------
CREATE TYPE role            AS ENUM ('sr','amo','tso','dmo','wm','top','admin');
CREATE TYPE price_type      AS ENUM ('outlet','cc','distributor','reporting','nto');
CREATE TYPE channel_type    AS ENUM ('GT','DCC','Astha','RCC','MT','HoReCa');
CREATE TYPE geo_class       AS ENUM ('Hill','Urban','SemiUrban','Rural');
CREATE TYPE request_type    AS ENUM ('new','close','info');
CREATE TYPE request_status  AS ENUM ('pending','verified','approved','rejected');
CREATE TYPE task_type       AS ENUM ('oos','general','irregular_visit');
CREATE TYPE force_reason    AS ENUM ('internet_problem','location_change');
CREATE TYPE day_state       AS ENUM ('not_started','logged_in','in_field','synced','sales_submitted','final_submitted');

-- ---------- geography ----------
CREATE TABLE wing      (id bigserial PRIMARY KEY, code text UNIQUE NOT NULL, name text NOT NULL);
CREATE TABLE division  (id bigserial PRIMARY KEY, code text UNIQUE NOT NULL, name text NOT NULL, wing_id bigint NOT NULL REFERENCES wing(id));
CREATE TABLE territory (id bigserial PRIMARY KEY, code text UNIQUE NOT NULL, name text NOT NULL, division_id bigint NOT NULL REFERENCES division(id));
CREATE TABLE house     (id bigserial PRIMARY KEY, code text UNIQUE NOT NULL, name text NOT NULL, territory_id bigint NOT NULL REFERENCES territory(id));
CREATE TABLE zone      (id bigserial PRIMARY KEY, code text UNIQUE NOT NULL, name text NOT NULL,
                        territory_id bigint NOT NULL REFERENCES territory(id), house_id bigint REFERENCES house(id));
CREATE TABLE cluster   (id bigserial PRIMARY KEY, zone_id bigint NOT NULL REFERENCES zone(id), cluster_type text, cluster_name text NOT NULL);
CREATE TABLE route     (id bigserial PRIMARY KEY, code text UNIQUE NOT NULL, name text NOT NULL,
                        zone_id bigint NOT NULL REFERENCES zone(id), visit_days text);  -- e.g. 'Sun,Tue,Thu' or 'Daily'

CREATE TABLE territory_geo_config (territory_id bigint PRIMARY KEY REFERENCES territory(id), radius_m int NOT NULL DEFAULT 100);

-- ---------- users, scope, devices ----------
CREATE TABLE app_user (id bigserial PRIMARY KEY, username text UNIQUE NOT NULL, full_name text, phone text,
                       role role NOT NULL, status text NOT NULL DEFAULT 'active', created_at timestamptz NOT NULL DEFAULT now());
-- A user's reach: one row per geography node they may see (resolve the subtree server-side).
CREATE TABLE user_scope (id bigserial PRIMARY KEY, user_id bigint NOT NULL REFERENCES app_user(id),
                         node_type text NOT NULL,  -- 'wing'|'division'|'territory'|'zone'|'route'
                         node_id bigint NOT NULL, UNIQUE (user_id, node_type, node_id));
CREATE TABLE route_assignment (id bigserial PRIMARY KEY, route_id bigint NOT NULL REFERENCES route(id),
                               user_id bigint NOT NULL REFERENCES app_user(id), role role NOT NULL,
                               valid_from date NOT NULL, valid_to date);
CREATE TABLE device (id bigserial PRIMARY KEY, user_id bigint NOT NULL REFERENCES app_user(id),
                     device_uuid uuid UNIQUE NOT NULL, bound_at timestamptz, last_seen_at timestamptz, status text DEFAULT 'active');

-- ---------- product ----------
CREATE TABLE product_category (id bigserial PRIMARY KEY, name text NOT NULL, sales_enable boolean DEFAULT true, sort int);
CREATE TABLE product_segment  (id bigserial PRIMARY KEY, category_id bigint NOT NULL REFERENCES product_category(id), name text NOT NULL, sales_enable boolean DEFAULT true, sort int);
CREATE TABLE product_brand    (id bigserial PRIMARY KEY, segment_id bigint NOT NULL REFERENCES product_segment(id), name text NOT NULL, sales_enable boolean DEFAULT true, sort int);
CREATE TABLE product_variant  (id bigserial PRIMARY KEY, brand_id bigint NOT NULL REFERENCES product_brand(id), name text NOT NULL, sales_enable boolean DEFAULT true, sort int);
CREATE TABLE sku (id bigserial PRIMARY KEY, variant_id bigint NOT NULL REFERENCES product_variant(id),
                  code text UNIQUE NOT NULL, short_name text, name text NOT NULL,
                  pack_size int, pack_type text, unit text NOT NULL DEFAULT 'stick',  -- stick|piece|dozen
                  sales_enable boolean DEFAULT true, sort int, image text);
CREATE TABLE sku_price (id bigserial PRIMARY KEY, sku_id bigint NOT NULL REFERENCES sku(id),
                        price_type price_type NOT NULL, amount_minor bigint NOT NULL,
                        valid_from date NOT NULL, valid_to date);
CREATE TABLE sales_plan (zone_id bigint NOT NULL REFERENCES zone(id), sku_id bigint NOT NULL REFERENCES sku(id),
                         enabled boolean NOT NULL DEFAULT true, PRIMARY KEY (zone_id, sku_id));

-- ---------- classifications ----------
CREATE TABLE sub_channel (id bigserial PRIMARY KEY, channel channel_type NOT NULL, name text NOT NULL);

-- ---------- outlet ----------
CREATE TABLE outlet (
  id bigserial PRIMARY KEY,
  outlet_code text UNIQUE NOT NULL,
  name text NOT NULL, owner_name text, contact_number text,
  address text, nid text, tin text, trade_license text,       -- PII: restrict exposure
  latitude double precision, longitude double precision,
  route_id bigint REFERENCES route(id),
  cluster_id bigint REFERENCES cluster(id),
  zone_id bigint REFERENCES zone(id),
  channel channel_type, sub_channel_id bigint REFERENCES sub_channel(id), geo_classification geo_class,
  status text NOT NULL DEFAULT 'active',
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON outlet (route_id);
CREATE INDEX ON outlet (zone_id);

CREATE TABLE outlet_change_request (
  id bigserial PRIMARY KEY, client_uuid uuid UNIQUE,
  outlet_id bigint REFERENCES outlet(id),               -- null for 'new'
  type request_type NOT NULL, proposed jsonb NOT NULL,
  requested_by bigint REFERENCES app_user(id), verified_by bigint REFERENCES app_user(id), approved_by bigint REFERENCES app_user(id),
  status request_status NOT NULL DEFAULT 'pending',
  created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE outlet_photo (id bigserial PRIMARY KEY, outlet_id bigint NOT NULL REFERENCES outlet(id),
  blob_url text NOT NULL, latitude double precision, longitude double precision,
  purpose text, captured_at timestamptz);

-- ---------- field transactions ----------
CREATE TABLE attendance (id bigserial PRIMARY KEY, user_id bigint NOT NULL REFERENCES app_user(id), business_date date NOT NULL,
  check_in_at timestamptz, check_in_lat double precision, check_in_lng double precision,
  check_out_at timestamptz, UNIQUE (user_id, business_date));

CREATE TABLE stock_issue (id bigserial PRIMARY KEY, user_id bigint NOT NULL REFERENCES app_user(id),
  business_date date NOT NULL, sku_id bigint NOT NULL REFERENCES sku(id),
  issued_qty int NOT NULL DEFAULT 0, returned_qty int NOT NULL DEFAULT 0,
  UNIQUE (user_id, business_date, sku_id));

CREATE TABLE visit (
  id bigserial PRIMARY KEY, client_uuid uuid UNIQUE NOT NULL,
  user_id bigint NOT NULL REFERENCES app_user(id), outlet_id bigint NOT NULL REFERENCES outlet(id),
  started_at timestamptz NOT NULL, business_date date NOT NULL,
  lat double precision, lng double precision, gps_accuracy_m double precision, mock_location boolean DEFAULT false,
  geo_validated boolean NOT NULL DEFAULT false, photo_validated boolean NOT NULL DEFAULT false,
  force_reason force_reason, is_zero_sale boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX ON visit (business_date);
CREATE INDEX ON visit (outlet_id);

CREATE TABLE memo (
  id bigserial PRIMARY KEY, client_uuid uuid UNIQUE NOT NULL,
  visit_id bigint NOT NULL REFERENCES visit(id), outlet_id bigint NOT NULL REFERENCES outlet(id), user_id bigint NOT NULL REFERENCES app_user(id),
  business_date date NOT NULL, printed_at timestamptz,
  gross_minor bigint NOT NULL DEFAULT 0, discount_minor bigint NOT NULL DEFAULT 0, net_minor bigint NOT NULL DEFAULT 0,
  is_credit boolean NOT NULL DEFAULT false, paid_minor bigint NOT NULL DEFAULT 0, due_minor bigint NOT NULL DEFAULT 0,
  edit_reason text, supersedes_memo_id bigint REFERENCES memo(id),
  created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX ON memo (business_date);

CREATE TABLE memo_line (id bigserial PRIMARY KEY, memo_id bigint NOT NULL REFERENCES memo(id),
  sku_id bigint NOT NULL REFERENCES sku(id), qty int NOT NULL, unit_price_minor bigint NOT NULL,
  discount_minor bigint NOT NULL DEFAULT 0, offer_id bigint);

CREATE TABLE qc_entry (id bigserial PRIMARY KEY, visit_id bigint NOT NULL REFERENCES visit(id),
  sku_id bigint NOT NULL REFERENCES sku(id), production_fault_qty int NOT NULL DEFAULT 0, transport_fault_qty int NOT NULL DEFAULT 0);

CREATE TABLE survey_response (id bigserial PRIMARY KEY, visit_id bigint NOT NULL REFERENCES visit(id),
  question_key text NOT NULL, answer text, photo_url text);
CREATE TABLE drp_collection (id bigserial PRIMARY KEY, visit_id bigint NOT NULL REFERENCES visit(id),
  kind text, qty int NOT NULL DEFAULT 0, offer_id bigint);

CREATE TABLE due_collection (id bigserial PRIMARY KEY, client_uuid uuid UNIQUE NOT NULL,
  outlet_id bigint NOT NULL REFERENCES outlet(id), user_id bigint NOT NULL REFERENCES app_user(id),
  against_memo_id bigint REFERENCES memo(id), amount_minor bigint NOT NULL, business_date date NOT NULL, collected_at timestamptz);

CREATE TABLE loyalty_ledger (id bigserial PRIMARY KEY, outlet_id bigint NOT NULL REFERENCES outlet(id),
  points_delta int NOT NULL, reason text, program text, at timestamptz NOT NULL DEFAULT now());
CREATE TABLE redemption (id bigserial PRIMARY KEY, client_uuid uuid UNIQUE NOT NULL, outlet_id bigint NOT NULL REFERENCES outlet(id),
  gift text, points_spent int, cash_minor bigint, photo_url text, business_date date, at timestamptz);
CREATE TABLE gift_photo (id bigserial PRIMARY KEY, outlet_id bigint NOT NULL REFERENCES outlet(id),
  program text, gift text, photo_url text, at timestamptz);

CREATE TABLE task (id bigserial PRIMARY KEY, assigned_by bigint REFERENCES app_user(id), assignee_id bigint NOT NULL REFERENCES app_user(id),
  outlet_id bigint REFERENCES outlet(id), type task_type NOT NULL, text text, due_date date,
  status text NOT NULL DEFAULT 'pending', resolved_at timestamptz, created_at timestamptz NOT NULL DEFAULT now());

CREATE TABLE call_assessment (id bigserial PRIMARY KEY, client_uuid uuid UNIQUE NOT NULL,
  assessor_id bigint NOT NULL REFERENCES app_user(id), sr_id bigint REFERENCES app_user(id), outlet_id bigint REFERENCES outlet(id),
  kind text NOT NULL,                 -- 'joint_call' | 'retailer_questionnaire'
  scores jsonb, answers jsonb, business_date date, at timestamptz);
CREATE TABLE distribution_check (id bigserial PRIMARY KEY, client_uuid uuid UNIQUE NOT NULL,
  assessor_id bigint NOT NULL REFERENCES app_user(id), outlet_id bigint NOT NULL REFERENCES outlet(id),
  brand_id bigint REFERENCES product_brand(id), present boolean, oos boolean, posm boolean, business_date date, at timestamptz);

-- ---------- sync & logs ----------
CREATE TABLE sync_batch (id bigserial PRIMARY KEY, device_id bigint REFERENCES device(id), user_id bigint REFERENCES app_user(id),
  uploaded_at timestamptz NOT NULL DEFAULT now(), counts jsonb);
CREATE TABLE route_log (route_id bigint NOT NULL REFERENCES route(id), business_date date NOT NULL,
  download_first timestamptz, download_last timestamptz, upload_first timestamptz, upload_last timestamptz,
  down_count int DEFAULT 0, up_count int DEFAULT 0, PRIMARY KEY (route_id, business_date));
CREATE TABLE final_submit (zone_id bigint NOT NULL REFERENCES zone(id), business_date date NOT NULL,
  submitted_by bigint REFERENCES app_user(id), submitted_at timestamptz, PRIMARY KEY (zone_id, business_date));
CREATE TABLE activity_log (id bigserial PRIMARY KEY, user_id bigint REFERENCES app_user(id), action text, at timestamptz NOT NULL DEFAULT now());

-- ---------- targets ----------
CREATE TABLE target (id bigserial PRIMARY KEY,
  scope_type text NOT NULL,    -- 'route' | 'zone'
  scope_id bigint NOT NULL,
  product_level text NOT NULL, -- 'category' | 'brand' | 'sku'
  product_id bigint NOT NULL,
  month date NOT NULL,         -- first of month
  std_target numeric(14,2) NOT NULL DEFAULT 0, memo_target int NOT NULL DEFAULT 0);
CREATE INDEX ON target (scope_type, scope_id, month);
CREATE TABLE target_revision (id bigserial PRIMARY KEY, target_scope_type text, target_scope_id bigint,
  config_name text, start_date date, end_date date, status text, approval_level int, approved_by bigint REFERENCES app_user(id),
  is_final boolean DEFAULT false, created_by bigint REFERENCES app_user(id), created_at timestamptz DEFAULT now());

-- ---------- aggregates (read side) — see docs/10 ----------
CREATE TABLE fact_daily_route_sku (business_date date, route_id bigint, sku_id bigint,
  std_qty numeric(14,2) DEFAULT 0, memo_count int DEFAULT 0, PRIMARY KEY (business_date, route_id, sku_id));
CREATE TABLE fact_daily_outlet (business_date date, outlet_id bigint,
  visited boolean, geo_valid boolean, std_qty numeric(14,2) DEFAULT 0, memo_count int DEFAULT 0, is_zero_sale boolean,
  PRIMARY KEY (business_date, outlet_id));
CREATE TABLE fact_daily_route (business_date date, route_id bigint,
  target_outlets int, successful_calls int, geo_valid_calls int, photo_valid_calls int,
  login_state day_state, PRIMARY KEY (business_date, route_id));
-- rollups by zone/territory/division/wing can be views over the above.
