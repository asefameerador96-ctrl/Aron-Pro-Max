-- V0204 sr-server round 1.20.0 (D-SV2-16; android-gates request section 5, lead 2026-11-13): memo usernames of three characters. The phone accepts
-- ^[a-z][a-z0-9]{2,31}$ (3 to 32 characters); the database said {3,31} (4 to 32) in app_user_memo_username and in the memo_no CHECK of memo, memo_void and sale_abort,
-- so every memo of a 3-character login would be refused memo_no_invalid. Widening only: a row that satisfied the old pattern satisfies the new one, so nothing here can
-- raise on data (no pre-flight mirror needed, docs/30 4a). Each widened CHECK is NOT VALID here and validated by V0205 (squawk rule).
-- Lock: ONE LOCK ... NOWAIT retried for about 60 s (V0189 pattern); LOCK on the partitioned memo parent also locks its partitions.
SET lock_timeout = '5s';

DO $$
DECLARE n int := 0; t text; c text;
BEGIN
  LOOP
    BEGIN
      LOCK TABLE app.app_user, app.memo, app.memo_void, app.sale_abort IN ACCESS EXCLUSIVE MODE NOWAIT;
      EXIT;
    EXCEPTION WHEN lock_not_available THEN
      n := n + 1;
      IF n >= 120 THEN RAISE EXCEPTION 'V0204: app.app_user, memo, memo_void or sale_abort stayed locked for 60 s'; END IF;
      PERFORM pg_sleep(0.5);
    END;
  END LOOP;

  ALTER TABLE app.app_user DROP CONSTRAINT IF EXISTS app_user_memo_username;
  ALTER TABLE app.app_user ADD CONSTRAINT app_user_memo_username
    CHECK (role NOT IN ('SR', 'AMO') OR username ~ '^[a-z][a-z0-9]{2,31}$') NOT VALID;

  -- The three memo_no CHECKs were written inline in V0007 (generated names <table>_memo_no_check).
  FOREACH t IN ARRAY ARRAY['memo', 'memo_void', 'sale_abort'] LOOP
    c := t || '_memo_no_check';
    EXECUTE format('ALTER TABLE app.%I DROP CONSTRAINT IF EXISTS %I', t, c);
    EXECUTE format('ALTER TABLE app.%I ADD CONSTRAINT %I CHECK (memo_no ~ %L) NOT VALID', t, c, '^[a-z][a-z0-9]{2,31}-[0-9]{6}-[0-9]{3,4}$');
  END LOOP;
END $$;

COMMENT ON CONSTRAINT app_user_memo_username ON app.app_user IS 'Memo-producing roles (SR, AMO) log in with ^[a-z][a-z0-9]{2,31}$ (3 to 32 characters; docs/24 s7.5), because the username is part of every memo number.';
