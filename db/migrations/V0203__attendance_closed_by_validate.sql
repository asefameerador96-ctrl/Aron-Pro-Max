-- V0203 validates what V0201 added NOT VALID (separate transaction, squawk rule). Every existing row satisfies it: no check-out has closed_by other than the default `rep`.
-- (The memo-username widening and its validation are the master-data train's, V0197 and V0198; this round no longer carries its own.)
SET lock_timeout = '5s';
ALTER TABLE app.attendance_event VALIDATE CONSTRAINT attendance_event_closed_by_check;
