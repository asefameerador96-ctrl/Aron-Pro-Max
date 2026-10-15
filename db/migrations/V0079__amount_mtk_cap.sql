-- V0079 caps |amount_mtk| at 10^13 milli-taka (10 billion taka) on app.dues_adjustment (V0074) and app.due_ledger
-- (V0007), matching the API cap backend-admin enforces (lead, 2026-10-15). A BETWEEN, not abs(): abs() of the bigint
-- minimum overflows. Column types and dw views are unchanged. The CHECKs are added NOT VALID (no scan under the lock)
-- and validated in V0080; due_ledger is append-only, so existing rows are only read by the validation.

SET lock_timeout = '5s';

ALTER TABLE app.dues_adjustment ADD CONSTRAINT dues_adjustment_amount_cap
  CHECK (amount_mtk BETWEEN -10000000000000 AND 10000000000000) NOT VALID;
ALTER TABLE app.due_ledger ADD CONSTRAINT due_ledger_amount_cap
  CHECK (amount_mtk BETWEEN -10000000000000 AND 10000000000000) NOT VALID;

COMMENT ON COLUMN app.dues_adjustment.amount_mtk IS 'Signed change of the outlet balance in milli-taka; never zero, negative for a write-off, |amount| at most 10^13 (V0079).';
COMMENT ON COLUMN app.due_ledger.amount_mtk IS 'Signed change of the outlet balance in milli-taka (1 Tk = 1000 mtk; + raises, - lowers); never zero, |amount| at most 10^13 (V0079).';
