-- V0202 sr-server train 117 (D-SV2-18; lead review of 2f5922ee, low 2): a REAL check-out must outrank a phone close of the same day, whatever the arrival order (shared phone,
-- reinstall). The old unique index attendance_event_once (user_id, business_date, kind) is replaced by two partial ones: at most ONE rep row per user, day and kind (a second check-in or a
-- second rep check-out is still `attendance_duplicate`) and at most ONE phone close per user and day. A phone close and a rep check-out can then both be stored; every reader looks at the
-- rep rows only (closed_by = 'rep'), so the phone close no longer counts once the rep's own check-out exists.
-- Cannot raise on data: every existing row is `rep` (the V0201 default) and was already unique on (user_id, business_date, kind); the phone index starts empty.
-- Lock: this is ACCESS EXCLUSIVE on app.attendance_event (reads wait too) for the time of the two index builds and the drop, nothing else: it is its OWN migration so the VALIDATE of
-- V0203 never holds it. ONE LOCK ... NOWAIT retried for about 60 s (V0191 pattern), so it never queues behind a long writer. A few seconds at fleet size
-- (millions of rows a year); both new indexes exist before the old one is dropped, so uniqueness is never missing. Idempotent: IF NOT EXISTS / IF EXISTS.
SET lock_timeout = '5s';

DO $$
DECLARE n int := 0;
BEGIN
  LOOP
    BEGIN
      LOCK TABLE app.attendance_event IN ACCESS EXCLUSIVE MODE NOWAIT;
      EXIT;
    EXCEPTION WHEN lock_not_available THEN
      n := n + 1;
      IF n >= 120 THEN RAISE EXCEPTION 'V0202: app.attendance_event stayed locked for 60 s'; END IF;
      PERFORM pg_sleep(0.5);
    END;
  END LOOP;
  CREATE UNIQUE INDEX IF NOT EXISTS attendance_event_once_rep ON app.attendance_event (user_id, business_date, kind) WHERE voided_at IS NULL AND closed_by = 'rep';
  CREATE UNIQUE INDEX IF NOT EXISTS attendance_event_once_phone ON app.attendance_event (user_id, business_date) WHERE voided_at IS NULL AND closed_by = 'phone';
  DROP INDEX IF EXISTS app.attendance_event_once;
END $$;
