-- V0004 outlets and their placement and location history (row N-005, schema v1a). docs/24 s11.2, s12.1, s12.2;
-- contract Outlet, OutletDetail. Field-created outlets arrive through app.outlet_change_request (V0007).

CREATE TABLE app.sub_channel (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  channel     text NOT NULL CHECK (channel IN ('GT','DCC','Astha','RCC','MT','HoReCa')),
  code        text NOT NULL UNIQUE CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$'),
  name        text NOT NULL CHECK (length(name) BETWEEN 1 AND 120),
  name_bn     text CHECK (length(name_bn) <= 120),
  status      text NOT NULL DEFAULT 'active' CHECK (status IN ('active','inactive')),
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  version     int NOT NULL DEFAULT 1 CHECK (version >= 1)
);

-- Outlet codes are text, never numbers (both DHK-344-011 and 2689479 occur). NID, TIN and trade licence are PII
-- the SR bundle never carries (cfg.bundle.outlet_fields). location_basis drives the geofence verdict (s11.2).
CREATE TABLE app.outlet (
  id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code                text NOT NULL UNIQUE CHECK (code ~ '^[0-9A-Za-z][0-9A-Za-z._/-]{0,31}$'),
  name                text NOT NULL CHECK (length(name) BETWEEN 1 AND 120),
  name_bn             text CHECK (length(name_bn) <= 120),
  owner_name          text NOT NULL CHECK (length(owner_name) <= 120),
  contact_number      text CHECK (contact_number ~ '^01[3-9][0-9]{8}$'),            -- PII
  address             text CHECK (length(address) <= 300),
  nid                 text,                                                           -- PII
  tin                 text,                                                           -- PII
  trade_license       text,                                                           -- PII
  zone_id             bigint NOT NULL REFERENCES app.zone(id),
  route_id            bigint REFERENCES app.route(id),
  cluster_id          bigint NOT NULL REFERENCES app.cluster(id),
  channel             text NOT NULL CHECK (channel IN ('GT','DCC','Astha','RCC','MT','HoReCa')),
  sub_channel_id      bigint REFERENCES app.sub_channel(id),
  geo_class           text REFERENCES app.geo_class_def(geo_class),
  lat                 double precision CHECK (lat BETWEEN -90 AND 90),
  lng                 double precision CHECK (lng BETWEEN -180 AND 180),
  location_basis      text NOT NULL DEFAULT 'none' CHECK (location_basis IN ('master','provisional','placeholder','none')),
  location_confirmed  boolean NOT NULL DEFAULT false,
  location_accuracy_m double precision CHECK (location_accuracy_m >= 0),
  provisional_lat     double precision CHECK (provisional_lat BETWEEN -90 AND 90),
  provisional_lng     double precision CHECK (provisional_lng BETWEEN -180 AND 180),
  outlet_kind         text NOT NULL DEFAULT 'retail' CHECK (outlet_kind IN ('retail','wholesale')),
  price_type          text NOT NULL DEFAULT 'outlet' CHECK (price_type IN ('outlet','cc','distributor')),
  status              text NOT NULL DEFAULT 'active' CHECK (status IN ('active','closed','merged','archived')),
  visit_sequence      int CHECK (visit_sequence >= 1),
  merged_into_id      bigint REFERENCES app.outlet(id),
  closed_at           timestamptz,
  origin_request_uuid uuid,                                                           -- outlet_change_request that created it
  external_ref        varchar(64) UNIQUE,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  version             int NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_by          bigint,
  updated_by          bigint,
  CHECK ((lat IS NULL) = (lng IS NULL)),
  CHECK ((provisional_lat IS NULL) = (provisional_lng IS NULL)),
  CHECK (location_basis IN ('placeholder','none') OR lat IS NOT NULL OR provisional_lat IS NOT NULL),
  CHECK (NOT location_confirmed OR lat IS NOT NULL),
  CHECK ((status = 'merged') = (merged_into_id IS NOT NULL))
);
CREATE INDEX ON app.outlet (route_id);
CREATE INDEX ON app.outlet (zone_id);
CREATE INDEX ON app.outlet (cluster_id);
CREATE INDEX outlet_updated ON app.outlet (updated_at, id);          -- updated_since reads and bundle deltas

-- Route and cluster placement history (OutletDetail.placement_history); the open row mirrors outlet.route_id.
CREATE TABLE app.outlet_placement_history (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  outlet_id   bigint NOT NULL REFERENCES app.outlet(id),
  route_id    bigint REFERENCES app.route(id),
  cluster_id  bigint REFERENCES app.cluster(id),
  valid_from  date NOT NULL,
  valid_to    date,                                  -- exclusive; null = open
  created_at  timestamptz NOT NULL DEFAULT now(),
  created_by  bigint,
  CHECK (valid_to IS NULL OR valid_to > valid_from),
  EXCLUDE USING gist (outlet_id WITH =, daterange(valid_from, valid_to, '[)') WITH &&)
);
CREATE INDEX ON app.outlet_placement_history (route_id, valid_from);

-- Every pin the outlet has had (OutletDetail.location_history). Append-only.
CREATE TABLE app.outlet_location_history (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  outlet_id         bigint NOT NULL REFERENCES app.outlet(id),
  lat               double precision NOT NULL CHECK (lat BETWEEN -90 AND 90),
  lng               double precision NOT NULL CHECK (lng BETWEEN -180 AND 180),
  accuracy_m        double precision CHECK (accuracy_m >= 0),
  source            text NOT NULL CHECK (source IN ('migration','capture','force_sale','base_update','web_edit','verification')),
  basis             text NOT NULL DEFAULT 'master' CHECK (basis IN ('master','provisional')),
  source_client_uuid uuid,                            -- outlet_change_request or visit that supplied the fix
  valid_from        timestamptz NOT NULL DEFAULT now(),
  created_by        bigint,
  created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON app.outlet_location_history (outlet_id, valid_from);
CREATE TRIGGER outlet_location_history_append_only BEFORE UPDATE OR DELETE ON app.outlet_location_history
  FOR EACH ROW EXECUTE FUNCTION app.deny_mutation();

CREATE TRIGGER sub_channel_touch BEFORE UPDATE ON app.sub_channel FOR EACH ROW EXECUTE FUNCTION app.touch_master();
CREATE TRIGGER outlet_touch      BEFORE UPDATE ON app.outlet      FOR EACH ROW EXECUTE FUNCTION app.touch_master();
