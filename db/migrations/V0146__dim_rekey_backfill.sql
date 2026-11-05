-- V0146 one full rekey of the dw dimension versions from the app history (D-DB-DIM-REKEY-01, split of the old V0144 tail).
-- Idempotent: dw.rekey_dim_versions() changes nothing when the versions already follow the history, so a re-run after a
-- failure is safe. Each dimension is its own attempt inside a sub-transaction and is retried when a lock is not
-- available (the worker holds rows of the version tables), for up to about 2 minutes per dimension (12 tries of up to 5 s lock wait plus a 5 s pause), in the Aggregator's order
-- (geo, product, outlet, user). Fact rows another transaction holds are skipped (aron.rekey_skip_locked); V0147 restamps
-- them. dw.rekey_dim_versions is defined by V0144.
SET lock_timeout = '5s';

SELECT set_config('aron.rekey_skip_locked', 'on', true);
DO $$
DECLARE d text; n int;
BEGIN
  FOREACH d IN ARRAY ARRAY['dim_geo', 'dim_product', 'dim_outlet', 'dim_user'] LOOP
    n := 0;
    LOOP
      BEGIN
        PERFORM count(*) FROM dw.rekey_dim_versions(d);
        EXIT;
      EXCEPTION WHEN lock_not_available THEN
        n := n + 1;
        IF n >= 12 THEN RAISE EXCEPTION 'V0146: the rekey of % kept meeting locks (is the worker paused?)', d; END IF;
        PERFORM pg_sleep(5);
      END;
    END LOOP;
  END LOOP;
END $$;
SELECT set_config('aron.rekey_skip_locked', 'off', true);
