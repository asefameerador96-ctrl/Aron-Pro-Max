-- V0041 app.geo_breadcrumb becomes range-partitioned by business_date, like app.geo_fix (row N-036; answers
-- docs/requests/backend-core-breadcrumb-partitioning.md). At fleet size breadcrumbs are about 400 k rows a day; monthly
-- partitions give pruning on the business_date filters and let retention drop whole months.
--
-- Columns keep their names, order and types, so the generic writer (jsonb_populate_record(NULL::app.geo_breadcrumb, ..))
-- is unchanged. Uniqueness follows the other partitioned capture tables (V0007, V0021): (client_uuid, business_date)
-- plus the statement-level app.client_uuid_once_rows check; app.ingest_registry stays the global uniqueness point.
-- external_ref (migration reference) becomes unique per business_date, the most a partitioned table can enforce.
-- The old table is renamed, its rows copied with their ids, then dropped: nothing references it (partitioned parents
-- are never FK targets) and no view reads it. Pilot-size data, so the copy is short; lock_timeout protects writers.
-- No partition-drop retention exists yet for any parent (docs/24 sets none for breadcrumbs); ops may drop old months.

SET lock_timeout = '5s';

-- Rename, rebuild and drop run in this one migration transaction: clients only ever see app.geo_breadcrumb.
-- squawk-ignore renaming-table
ALTER TABLE app.geo_breadcrumb RENAME TO geo_breadcrumb_v0007;

CREATE TABLE app.geo_breadcrumb (
  id                   bigint GENERATED ALWAYS AS IDENTITY,
  client_uuid          uuid NOT NULL,
  family_uuid          uuid NOT NULL,
  business_date        date NOT NULL,
  business_date_device date,
  user_id              bigint NOT NULL REFERENCES app.app_user(id),
  device_id            bigint CONSTRAINT geo_breadcrumb_device_fk REFERENCES app.device(id),
  acting_for_user_id   bigint REFERENCES app.app_user(id),
  captured_at          timestamptz NOT NULL,
  captured_elapsed_ms  bigint CHECK (captured_elapsed_ms >= 0),
  boot_count           int CHECK (boot_count >= 0),
  clock_offset_ms      bigint,
  captured_offline     boolean NOT NULL DEFAULT false,
  schema_version       int NOT NULL DEFAULT 1 CHECK (schema_version >= 1),
  config_version       bigint NOT NULL CHECK (config_version >= 0),
  bundle_version       text,
  bundle_stale         boolean NOT NULL DEFAULT false,
  first_batch_uuid     uuid,
  received_at          timestamptz NOT NULL DEFAULT now(),
  created_at           timestamptz NOT NULL DEFAULT now(),
  voided_at            timestamptz,
  external_ref         varchar(64),
  fix_status           text CHECK (fix_status IN ('ok','timeout','permission_denied','location_off','provider_unavailable')),
  fix_lat              double precision CHECK (fix_lat BETWEEN -90 AND 90),
  fix_lng              double precision CHECK (fix_lng BETWEEN -180 AND 180),
  fix_accuracy_m       double precision CHECK (fix_accuracy_m >= 0),
  fix_is_mock          boolean,
  PRIMARY KEY (id, business_date),
  UNIQUE (client_uuid, business_date),
  UNIQUE (external_ref, business_date)
) PARTITION BY RANGE (business_date);
CREATE INDEX ON app.geo_breadcrumb (user_id, business_date);
CREATE TRIGGER geo_breadcrumb_immutable BEFORE UPDATE OR DELETE ON app.geo_breadcrumb
  FOR EACH ROW EXECUTE FUNCTION app.guard_synced_row('=voided_at');
CREATE TRIGGER geo_breadcrumb_client_uuid_once AFTER INSERT ON app.geo_breadcrumb REFERENCING NEW TABLE AS new_rows
  FOR EACH STATEMENT EXECUTE FUNCTION app.client_uuid_once_rows('geo_breadcrumb');
INSERT INTO app.partition_policy (parent) VALUES ('app.geo_breadcrumb');
SELECT app.ensure_partitions('2026-01-01', '2027-12-01');

-- Copy with the original ids; the identity continues after the highest copied id.
INSERT INTO app.geo_breadcrumb OVERRIDING SYSTEM VALUE SELECT * FROM app.geo_breadcrumb_v0007;
SELECT setval(pg_get_serial_sequence('app.geo_breadcrumb', 'id'), max(id)) FROM app.geo_breadcrumb HAVING max(id) IS NOT NULL;

-- The data dictionary comments move with the columns.
DO $$
DECLARE r record;
BEGIN
  EXECUTE format('COMMENT ON TABLE app.geo_breadcrumb IS %L', obj_description('app.geo_breadcrumb_v0007'::regclass, 'pg_class'));
  FOR r IN SELECT a.attname, col_description(a.attrelid, a.attnum) AS c
             FROM pg_attribute a WHERE a.attrelid = 'app.geo_breadcrumb_v0007'::regclass AND a.attnum > 0 AND NOT a.attisdropped
  LOOP
    EXECUTE format('COMMENT ON COLUMN app.geo_breadcrumb.%I IS %L', r.attname, r.c);
  END LOOP;
END $$;
COMMENT ON COLUMN app.geo_breadcrumb.external_ref IS 'Stable external reference for cross-walks with other systems (Apsis, ERP); unique per business date when set.';

-- squawk-ignore ban-drop-table
DROP TABLE app.geo_breadcrumb_v0007;

-- The new table was created beside the old one, so PostgreSQL suffixed its generated names with 1; give them back the
-- house names (geo_breadcrumb_pkey, ..._check, ..._fkey, ..._idx, geo_breadcrumb_id_seq).
DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT conname FROM pg_constraint WHERE conrelid = 'app.geo_breadcrumb'::regclass AND conname ~ '1$' LOOP
    EXECUTE format('ALTER TABLE app.geo_breadcrumb RENAME CONSTRAINT %I TO %I', r.conname, left(r.conname, -1));
  END LOOP;
  FOR r IN SELECT c.relname FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
            WHERE i.indrelid = 'app.geo_breadcrumb'::regclass AND c.relname ~ '1$' LOOP
    EXECUTE format('ALTER INDEX app.%I RENAME TO %I', r.relname, left(r.relname, -1));
  END LOOP;
  IF to_regclass('app.geo_breadcrumb_id_seq1') IS NOT NULL THEN
    ALTER SEQUENCE app.geo_breadcrumb_id_seq1 RENAME TO geo_breadcrumb_id_seq;
  END IF;
END $$;

SELECT app.apply_db_role_grants();
