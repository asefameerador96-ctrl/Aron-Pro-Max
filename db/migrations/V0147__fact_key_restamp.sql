-- V0147 every fact key (dw.fact_memo, dw.fact_visit: geo_key, outlet_key, user_key) follows the dimension versions
-- (D-DB-DIM-REKEY-01, split of the old V0144 tail). Idempotent: only rows whose key differs are written. Rows another
-- transaction holds are skipped (FOR UPDATE SKIP LOCKED, setting aron.rekey_skip_locked), never waited for, so there is
-- no deadlock with the worker; the loop repeats until no fact key is wrong (at most about 2 minutes, then it raises and
-- Flyway retries the migration on the next deploy). dw.restamp_fact_keys is defined by V0144.
SET lock_timeout = '5s';

SELECT set_config('aron.rekey_skip_locked', 'on', true);
DO $$
DECLARE d text; pass int := 0; todo bigint; ids bigint[];
BEGIN
  LOOP
    FOR d IN VALUES ('dim_geo'), ('dim_outlet'), ('dim_user') LOOP
      ids := CASE d
        WHEN 'dim_geo'    THEN ARRAY(SELECT DISTINCT f.id FROM (SELECT route_id FROM dw.fact_memo UNION SELECT route_id FROM dw.fact_visit) f(id))
        WHEN 'dim_outlet' THEN ARRAY(SELECT DISTINCT f.id FROM (SELECT outlet_id FROM dw.fact_memo UNION SELECT outlet_id FROM dw.fact_visit) f(id))
        ELSE                   ARRAY(SELECT DISTINCT f.id FROM (SELECT user_id FROM dw.fact_memo UNION SELECT user_id FROM dw.fact_visit) f(id)) END;
      PERFORM dw.restamp_fact_keys(d, ids, NULL);
    END LOOP;
    SELECT (SELECT count(*) FROM dw.fact_memo WHERE route_id IS NOT NULL AND geo_key IS DISTINCT FROM dw.geo_key_on(route_id, business_date))
         + (SELECT count(*) FROM dw.fact_memo WHERE outlet_id IS NOT NULL AND outlet_key IS DISTINCT FROM dw.outlet_key_on(outlet_id, business_date))
         + (SELECT count(*) FROM dw.fact_memo WHERE user_id IS NOT NULL AND user_key IS DISTINCT FROM dw.user_key_on(user_id, business_date))
         + (SELECT count(*) FROM dw.fact_visit WHERE route_id IS NOT NULL AND geo_key IS DISTINCT FROM dw.geo_key_on(route_id, business_date))
         + (SELECT count(*) FROM dw.fact_visit WHERE outlet_id IS NOT NULL AND outlet_key IS DISTINCT FROM dw.outlet_key_on(outlet_id, business_date))
         + (SELECT count(*) FROM dw.fact_visit WHERE user_id IS NOT NULL AND user_key IS DISTINCT FROM dw.user_key_on(user_id, business_date))
      INTO todo;
    EXIT WHEN todo = 0;
    pass := pass + 1;
    IF pass >= 240 THEN RAISE EXCEPTION 'V0147: % fact keys still locked or wrong after 2 minutes', todo; END IF;
    PERFORM pg_sleep(0.5);
  END LOOP;
END $$;
SELECT set_config('aron.rekey_skip_locked', 'off', true);
