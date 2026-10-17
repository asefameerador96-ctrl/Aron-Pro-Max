-- V0080 validates the V0079 amount caps (added NOT VALID; separate transaction, squawk rule).

SET lock_timeout = '5s';

ALTER TABLE app.dues_adjustment VALIDATE CONSTRAINT dues_adjustment_amount_cap;
ALTER TABLE app.due_ledger VALIDATE CONSTRAINT due_ledger_amount_cap;
