-- V0203 sr-server round 1.20.0 (D-SV2-15; D-LEAD-PREREQ-03 ruling 6): who closed a check-out. `closed_by` is `rep` (the rep pressed check-out) or `phone` (the phone closed a
-- forgotten day at a later start, no location read). A phone close is stored and acknowledged, but every reader treats it as "no check-out" (no time, no hours, no
-- supervisor_day.checked_out_at, never checkout_too_early). Old phones never send the member: the column default is `rep`. A fast default (PG 11+), no table rewrite.
-- The CHECK is NOT VALID here and validated by V0205 (squawk rule). Lock: ONE LOCK ... NOWAIT retried for about 60 s (V0189 pattern).
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
      IF n >= 120 THEN RAISE EXCEPTION 'V0203: app.attendance_event stayed locked for 60 s'; END IF;
      PERFORM pg_sleep(0.5);
    END;
  END LOOP;

  ALTER TABLE app.attendance_event ADD COLUMN IF NOT EXISTS closed_by text NOT NULL DEFAULT 'rep';
  -- Only a check-out can be closed by the phone; a check-in is always the rep's.
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'attendance_event_closed_by_check' AND conrelid = 'app.attendance_event'::regclass) THEN
    ALTER TABLE app.attendance_event ADD CONSTRAINT attendance_event_closed_by_check
      CHECK (closed_by IN ('rep', 'phone') AND (closed_by = 'rep' OR kind = 'check_out')) NOT VALID;
  END IF;
END $$;

COMMENT ON COLUMN app.attendance_event.closed_by IS 'Who closed a check-out: rep (the rep pressed check-out; every check-in and every record of an older phone) or phone (the phone closed a forgotten day at a later start, no location). A phone close is read everywhere as "no check-out".';
