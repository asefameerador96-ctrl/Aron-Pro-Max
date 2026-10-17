-- V0085 (DB-04, audit docs/audit/production-readiness-2026-10-15.md): the sync-health page and the reconciliation
-- reads look up one user's memos and visits by business date; dw.fact_memo and dw.fact_visit carry only the
-- (zone_id, business_date) indexes of V0036, so each such read scanned the month's partition per user. On the
-- partitioned parents: one index per existing partition, inherited by every future one (app.ensure_partitions).

SET lock_timeout = '5s';

-- squawk-ignore require-concurrent-index-creation
CREATE INDEX fact_memo_user_date ON dw.fact_memo (user_id, business_date);
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX fact_visit_user_date ON dw.fact_visit (user_id, business_date);
