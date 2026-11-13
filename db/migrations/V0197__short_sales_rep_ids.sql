-- Lane masterdata-fit, push 1 (M13, D-OWNER-PARITY-02): the field's sales-rep ids are two letters then digits and can be 3 characters long.
-- The memo-username rule ({3,31} after the first letter = 4 to 32 characters) would reject such a rep, and the memo number embeds the username.
-- The rule becomes {2,31} (3 to 32 characters) for app_user and for the memo_no checks of memo, memo_void and sale_abort (partitions inherit the
-- parent constraint). The usernames themselves are never changed: the code of the field stays the login. Expand only: every existing value still
-- satisfies the wider rule. Added NOT VALID here, validated in V0198 (squawk rule).

SET lock_timeout = '5s';

-- Lock safety (squawk / V0144 pattern): the ALTERs below take ACCESS EXCLUSIVE on hot tables. All the locks are taken by NOWAIT inside a
-- sub-transaction and retried (about 60 s); a busy table makes the sub-transaction release everything and sleep holding nothing, so the
-- migration never queues behind a long transaction and never blocks the sync writers behind its own queued lock request.
DO $$
DECLARE n int := 0;
BEGIN
  LOOP
    BEGIN
      LOCK TABLE app.app_user IN ACCESS EXCLUSIVE MODE NOWAIT;
      LOCK TABLE app.memo IN ACCESS EXCLUSIVE MODE NOWAIT;
      LOCK TABLE app.memo_void IN ACCESS EXCLUSIVE MODE NOWAIT;
      LOCK TABLE app.sale_abort IN ACCESS EXCLUSIVE MODE NOWAIT;
      EXIT;
    EXCEPTION WHEN lock_not_available THEN
      n := n + 1;
      IF n >= 120 THEN RAISE EXCEPTION 'V0197: the tables stayed locked for 60 s'; END IF;
      PERFORM pg_sleep(0.5);
    END;
  END LOOP;
END $$;

ALTER TABLE app.app_user DROP CONSTRAINT app_user_memo_username;
ALTER TABLE app.app_user ADD CONSTRAINT app_user_memo_username CHECK (role NOT IN ('SR','AMO') OR username ~ '^[a-z][a-z0-9]{2,31}$') NOT VALID;

ALTER TABLE app.memo DROP CONSTRAINT memo_memo_no_check;
ALTER TABLE app.memo ADD CONSTRAINT memo_memo_no_check CHECK (memo_no ~ '^[a-z][a-z0-9]{2,31}-[0-9]{6}-[0-9]{3,4}$') NOT VALID;
ALTER TABLE app.memo_void DROP CONSTRAINT memo_void_memo_no_check;
ALTER TABLE app.memo_void ADD CONSTRAINT memo_void_memo_no_check CHECK (memo_no ~ '^[a-z][a-z0-9]{2,31}-[0-9]{6}-[0-9]{3,4}$') NOT VALID;
ALTER TABLE app.sale_abort DROP CONSTRAINT sale_abort_memo_no_check;
ALTER TABLE app.sale_abort ADD CONSTRAINT sale_abort_memo_no_check CHECK (memo_no ~ '^[a-z][a-z0-9]{2,31}-[0-9]{6}-[0-9]{3,4}$') NOT VALID;
