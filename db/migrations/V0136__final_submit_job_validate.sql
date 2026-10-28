-- V0136 validates the four V0135 constraints (added NOT VALID; separate transaction, squawk rule).

SET lock_timeout = '5s';

ALTER TABLE app.final_submit VALIDATE CONSTRAINT final_submit_via_check;
ALTER TABLE app.final_submit VALIDATE CONSTRAINT final_submit_job_actor;
ALTER TABLE app.sync_rejected VALIDATE CONSTRAINT sync_rejected_code_check;
ALTER TABLE app.sync_quarantine VALIDATE CONSTRAINT sync_quarantine_code_check;
