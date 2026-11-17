-- Pre-flight of V0191 (read-only): the mirror of its raising guard, run by the migrate role BEFORE Flyway applies anything
-- (backend:platform MigrationPreflight; docs/30 "Pre-flight of data-dependent migrations"). It lists the clusters whose names differ only by
-- case, spacing or composition inside one zone, as zone code and cluster ids: NEVER a name. The normalisation is inlined (the function
-- app.norm_cluster_name does not exist before V0191) and must stay identical to V0191's; a test compares the two character classes.
-- Inactive clusters count: the unique index V0191 creates covers every status.
SELECT z.code AS zone_code, array_agg(c.id ORDER BY c.id) AS cluster_ids
  FROM app.cluster c
  JOIN app.zone z ON z.id = c.zone_id
 GROUP BY c.zone_id, z.code, lower(btrim(regexp_replace(normalize(c.name, NFC), '[\s   -     　]+', ' ', 'g')))
HAVING count(*) > 1
 ORDER BY z.code, min(c.id)
