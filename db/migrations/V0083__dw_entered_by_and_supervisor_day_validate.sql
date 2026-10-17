-- V0083 validates the V0082 CHECKs (added NOT VALID; separate transaction, squawk rule).

SET lock_timeout = '5s';

ALTER TABLE dw.fact_memo VALIDATE CONSTRAINT fact_memo_entered_by_manual_only;
ALTER TABLE dw.fact_visit VALIDATE CONSTRAINT fact_visit_entered_by_manual_only;
ALTER TABLE app.dirty_key VALIDATE CONSTRAINT dirty_key_kind_check;
