-- V0205 validates what V0203 and V0204 added NOT VALID (separate transaction, squawk rule). Every existing row satisfies them: no check-out has closed_by other than the
-- default `rep`, and the widened memo patterns accept everything the old ones did.
SET lock_timeout = '5s';
ALTER TABLE app.attendance_event VALIDATE CONSTRAINT attendance_event_closed_by_check;
ALTER TABLE app.app_user VALIDATE CONSTRAINT app_user_memo_username;
ALTER TABLE app.memo VALIDATE CONSTRAINT memo_memo_no_check;
ALTER TABLE app.memo_void VALIDATE CONSTRAINT memo_void_memo_no_check;
ALTER TABLE app.sale_abort VALIDATE CONSTRAINT sale_abort_memo_no_check;
