-- V0091 house active flag and de-duplication (owner directive 2026-10-22, docs/34 s2 "House level"; dashboard brief s1:
-- "the filter lists each active house once", 295 houses against 239 because some appear twice and some are deactivated).
-- 1. app.house.is_active: a stored generated column, true exactly when status = 'active'. status (V0002) stays the one
--    source of truth that the admin API writes; is_active is the name the dashboard filter and reports read. House is a
--    small master table (about 300 rows), so the rewrite under the lock is short.
-- 2. One-off de-duplication: houses with the same territory and the same name (trimmed, case-insensitive) are one
--    house. The survivor is the active one with the most zones, then the lowest id; the other rows' zones are moved to
--    the survivor and the duplicates are set inactive (never deleted: their ids may sit in old exports and the audit).
--    Every move and deactivation goes through the master touch trigger, so version and updated_at move forward.
-- 3. A unique index keeps it so: at most one ACTIVE house per territory and normalised name. Inactive duplicates are
--    allowed (history).
-- 4. dw.dim_geo and its SCD2 history dw.dim_geo_version gain house_id and house_name (nullable: a zone may name no
--    house) for the House filter; the projector (backend-reports) fills them from app.zone.house_id.

SET lock_timeout = '5s';

-- squawk-ignore adding-field-with-default
ALTER TABLE app.house ADD COLUMN is_active boolean GENERATED ALWAYS AS (status = 'active') STORED;

CREATE TEMP TABLE house_dup ON COMMIT DROP AS
WITH ranked AS (
  SELECT h.id, h.territory_id, lower(btrim(h.name)) AS norm,
         row_number() OVER (PARTITION BY h.territory_id, lower(btrim(h.name))
                            ORDER BY (h.status = 'active') DESC,
                                     (SELECT count(*) FROM app.zone z WHERE z.house_id = h.id) DESC, h.id) AS rn
    FROM app.house h
)
SELECT d.id AS dup_id, s.id AS keep_id
  FROM ranked d JOIN ranked s ON s.territory_id = d.territory_id AND s.norm = d.norm AND s.rn = 1
 WHERE d.rn > 1;

UPDATE app.zone z SET house_id = d.keep_id FROM house_dup d WHERE z.house_id = d.dup_id;
UPDATE app.house h SET status = 'inactive' FROM house_dup d WHERE h.id = d.dup_id AND h.status <> 'inactive';

-- squawk-ignore require-concurrent-index-creation
CREATE UNIQUE INDEX house_active_name_once ON app.house (territory_id, lower(btrim(name))) WHERE status = 'active';

ALTER TABLE dw.dim_geo ADD COLUMN house_id bigint, ADD COLUMN house_name text;
ALTER TABLE dw.dim_geo_version ADD COLUMN house_id bigint, ADD COLUMN house_name text;

COMMENT ON COLUMN app.house.status IS 'active or inactive; the one source of truth for the active flag (is_active is generated from it, V0091).';
COMMENT ON COLUMN app.house.is_active IS 'True when status is active (generated, V0091); the dashboard House filter lists active houses only.';
COMMENT ON COLUMN dw.dim_geo.house_id IS 'Distribution house of the route''s zone (app.zone.house_id); null when the zone names none.';
COMMENT ON COLUMN dw.dim_geo.house_name IS 'Name of that house.';
COMMENT ON COLUMN dw.dim_geo_version.house_id IS 'Distribution house of the route''s zone in this version; null when the zone named none.';
COMMENT ON COLUMN dw.dim_geo_version.house_name IS 'Name of that house in this version.';
