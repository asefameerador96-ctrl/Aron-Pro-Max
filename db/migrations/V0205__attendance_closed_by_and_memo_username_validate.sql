-- V0205 validates what V0203 and V0204 added NOT VALID (separate transaction, squawk rule). Every existing row satisfies them: no check-out has closed_by other than the
-- default `rep`, and the widened memo patterns accept everything the old ones did.
-- It also splits the "one check-out per user and day" rule (lead review of 2f5922ee, low 2): a REAL check-out must outrank a phone close of the same day, whatever the arrival
-- order (shared phone, reinstall), so the old unique index attendance_event_once (user_id, business_date, kind) is replaced by two partial ones: at most ONE rep row per user, day and
-- kind (a second check-in or a second rep check-out is still `attendance_duplicate`) and at most ONE phone close per user and day. A phone close and a rep check-out can then both
-- be stored; every reader looks at the rep rows only (closed_by = 'rep'), so the phone close no longer counts once the rep's own check-out exists. Cannot raise on data: every
-- existing row is `rep` and was already unique on (user_id, business_date, kind). The index build holds a SHARE lock (reads go on, writes wait); the table is small at pilot size and
-- a few million rows a year at fleet size (seconds). One LOCK ... NOWAIT retried for about 60 s (V0191 pattern), so the build never queues behind a long writer.
SET lock_timeout = '5s';
ALTER TABLE app.attendance_event VALIDATE CONSTRAINT attendance_event_closed_by_check;
ALTER TABLE app.app_user VALIDATE CONSTRAINT app_user_memo_username;
ALTER TABLE app.memo VALIDATE CONSTRAINT memo_memo_no_check;
ALTER TABLE app.memo_void VALIDATE CONSTRAINT memo_void_memo_no_check;
ALTER TABLE app.sale_abort VALIDATE CONSTRAINT sale_abort_memo_no_check;

DO $$
DECLARE n int := 0;
BEGIN
  LOOP
    BEGIN
      LOCK TABLE app.attendance_event IN ACCESS EXCLUSIVE MODE NOWAIT;
      EXIT;
    EXCEPTION WHEN lock_not_available THEN
      n := n + 1;
      IF n >= 120 THEN RAISE EXCEPTION 'V0205: app.attendance_event stayed locked for 60 s'; END IF;
      PERFORM pg_sleep(0.5);
    END;
  END LOOP;
  CREATE UNIQUE INDEX attendance_event_once_rep ON app.attendance_event (user_id, business_date, kind) WHERE voided_at IS NULL AND closed_by = 'rep';
  CREATE UNIQUE INDEX attendance_event_once_phone ON app.attendance_event (user_id, business_date) WHERE voided_at IS NULL AND closed_by = 'phone';
  DROP INDEX app.attendance_event_once;
END $$;
