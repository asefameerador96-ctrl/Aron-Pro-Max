-- V0001 foundation (row N-005, schema v1a). docs/24 s6.3, s12.
-- Schemas: app (transactions, master data, config, audit), dw (aggregates), stg (reserved for imports, empty).
-- Conventions used by every later migration:
--   * server surrogate keys: bigint GENERATED ALWAYS AS IDENTITY; device ids: uuid (v4, minted on the phone)
--   * money: bigint milli-taka (*_mtk); quantities: integer base units (*_qty_base / qty_base)
--   * instants: timestamptz (UTC); transaction rows also carry business_date (Asia/Dhaka date)
--   * enumerations are text + CHECK (values equal contract/openapi.yaml), so adding a value is an additive migration
--   * nothing is hard-deleted: status, valid_to or tombstone columns
-- Forward-only: never edit this file after it is pushed; add a new migration instead.

CREATE SCHEMA IF NOT EXISTS app;
CREATE SCHEMA IF NOT EXISTS dw;
CREATE SCHEMA IF NOT EXISTS stg;

-- Exclusion constraints on (id WITH =, daterange WITH &&) need btree_gist (trusted extension; on Azure Database
-- for PostgreSQL it must be allow-listed in azure.extensions, see docs/status/db.md).
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Business date of an instant: Asia/Dhaka (UTC+6, no DST), cutoff 00:00 (docs/24 s3.8, D24-29).
CREATE FUNCTION app.dhaka_date(p_at timestamptz) RETURNS date
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$ SELECT (p_at AT TIME ZONE 'Asia/Dhaka')::date $$;

-- Integer division rounding half away from zero (docs/24 s7.3, shared:rules Money.divHalfUp).
CREATE FUNCTION app.div_half_up(p_num bigint, p_den bigint) RETURNS bigint
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$ SELECT sign(p_num) * sign(p_den) * ((2 * abs(p_num) + abs(p_den)) / (2 * abs(p_den))) $$;

-- Maintains updated_at and the optimistic-concurrency version of master rows (docs/24 s3.3 item 4, D24-44).
CREATE FUNCTION app.touch_master() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  NEW.updated_at := now();
  NEW.version := OLD.version + 1;          -- always forward: a stale If-Match version can never match again
  RETURN NEW;
END $$;

-- Refuses UPDATE, DELETE and TRUNCATE on append-only tables (audit log, ledgers, event trails).
CREATE FUNCTION app.deny_mutation() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'table %.% is append-only: % is not allowed', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
    USING ERRCODE = 'insufficient_privilege';
END $$;

-- Monthly range partitions as data (docs/24 s12.1). Every range-partitioned parent is listed here (partition key of
-- type date: business_date); the worker calls app.ensure_partitions() daily so partitions exist three months ahead.
-- Rows outside every monthly partition land in <parent>_default; when their month is created they are re-routed.
CREATE TABLE app.partition_policy (
  parent       text PRIMARY KEY,                    -- schema-qualified parent table name
  key_column   text NOT NULL DEFAULT 'business_date',
  ahead_months int  NOT NULL DEFAULT 3 CHECK (ahead_months BETWEEN 1 AND 24)
);

CREATE FUNCTION app.ensure_partitions(p_from date DEFAULT NULL, p_to date DEFAULT NULL) RETURNS int
LANGUAGE plpgsql
AS $$
DECLARE
  p        record;
  m        date;
  m_end    date;
  last_m   date;
  part     text;
  dflt     text;
  swap     text;
  has_rows boolean;
  n        int := 0;
BEGIN
  FOR p IN SELECT * FROM app.partition_policy ORDER BY parent LOOP
    dflt := p.parent || '_default';
    swap := p.parent || '_default_swap';
    IF to_regclass(dflt) IS NULL THEN
      EXECUTE format('CREATE TABLE %s PARTITION OF %s DEFAULT', dflt, p.parent);
    END IF;
    m := date_trunc('month', coalesce(p_from, app.dhaka_date(now())))::date;
    last_m := date_trunc('month', coalesce(p_to, app.dhaka_date(now()) + make_interval(months => p.ahead_months)))::date;
    WHILE m <= last_m LOOP
      m_end := (m + interval '1 month')::date;
      part := format('%s_y%sm%s', p.parent, to_char(m, 'YYYY'), to_char(m, 'MM'));
      IF to_regclass(part) IS NULL THEN
        EXECUTE format('SELECT EXISTS (SELECT 1 FROM %s WHERE %I >= %L AND %I < %L)', dflt, p.key_column, m, p.key_column, m_end)
          INTO has_rows;
        IF has_rows THEN
          -- The month cannot attach while the default partition holds its rows. Swap in a fresh default, attach the
          -- month and re-route every row of the old default through the parent, then drop the old default. No row is
          -- updated or deleted, so append-only triggers are not involved and every row survives.
          EXECUTE format('ALTER TABLE %s DETACH PARTITION %s', p.parent, dflt);
          EXECUTE format('ALTER TABLE %s RENAME TO %I', dflt, split_part(swap, '.', 2));
          EXECUTE format('CREATE TABLE %s PARTITION OF %s DEFAULT', dflt, p.parent);
          EXECUTE format('CREATE TABLE %s PARTITION OF %s FOR VALUES FROM (%L) TO (%L)', part, p.parent, m, m_end);
          EXECUTE format('INSERT INTO %s OVERRIDING SYSTEM VALUE SELECT * FROM %s', p.parent, swap);
          EXECUTE format('DROP TABLE %s', swap);
        ELSE
          EXECUTE format('CREATE TABLE %s PARTITION OF %s FOR VALUES FROM (%L) TO (%L)', part, p.parent, m, m_end);
        END IF;
        n := n + 1;
      END IF;
      m := m_end;
    END LOOP;
  END LOOP;
  RETURN n;
END $$;

COMMENT ON FUNCTION app.ensure_partitions(date, date) IS
  'Creates missing monthly partitions for every app.partition_policy parent from p_from (default: this Dhaka month) '
  'to p_to (default: ahead_months ahead) and a DEFAULT partition; idempotent. Run daily by the worker.';

-- Lookup tables that give the text enums of the contract a stable integer where the config model needs one
-- (cfg_value.scope_id is an integer in the contract; role and geo_class scopes use these ordinals).
CREATE TABLE app.role_def (
  role    text PRIMARY KEY CHECK (role IN ('SR','AMO','TSO','DMO','WM','TOP','ANALYST','SUPPORT','ADMIN','SUPERADMIN')),
  ordinal smallint NOT NULL UNIQUE CHECK (ordinal BETWEEN 1 AND 99),
  field_role boolean NOT NULL                       -- SR, AMO, TSO capture by sync
);
INSERT INTO app.role_def (role, ordinal, field_role) VALUES
  ('SR', 1, true), ('AMO', 2, true), ('TSO', 3, true), ('DMO', 4, false), ('WM', 5, false), ('TOP', 6, false),
  ('ANALYST', 7, false), ('SUPPORT', 8, false), ('ADMIN', 9, false), ('SUPERADMIN', 10, false);

CREATE TABLE app.geo_class_def (
  geo_class text PRIMARY KEY CHECK (geo_class IN ('Hill','Urban','SemiUrban','Rural')),
  ordinal   smallint NOT NULL UNIQUE
);
INSERT INTO app.geo_class_def (geo_class, ordinal) VALUES ('Hill', 1), ('Urban', 2), ('SemiUrban', 3), ('Rural', 4);
