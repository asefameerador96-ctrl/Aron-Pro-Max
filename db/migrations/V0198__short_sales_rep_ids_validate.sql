-- V0198 validates what V0197 added NOT VALID (separate transaction, squawk rule). The new rules accept everything the old ones accepted.
SET lock_timeout = '5s';
ALTER TABLE app.app_user VALIDATE CONSTRAINT app_user_memo_username;
ALTER TABLE app.memo VALIDATE CONSTRAINT memo_memo_no_check;
ALTER TABLE app.memo_void VALIDATE CONSTRAINT memo_void_memo_no_check;
ALTER TABLE app.sale_abort VALIDATE CONSTRAINT sale_abort_memo_no_check;
