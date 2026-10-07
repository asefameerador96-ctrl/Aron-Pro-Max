-- V0039 AUD-PERF-08: drop the provable prefix-duplicate indexes on the capture tables.
--   app.memo_no_lookup (memo_no)                 leading prefix of the unique memo_no_unique (memo_no, business_date);
--   app.memo_discount_business_date_idx          leading prefix of memo_discount_business_date_kind_idx;
--   app.domain_event_id (id)                     leading prefix of the primary key (id, business_date);
--   app.qc_entry_line_business_date_idx          leading prefix of qc_entry_line_business_date_sku_id_idx.
-- The audit named the first two; IndexHygieneTest (new) found the other two. Any query a dropped index served is
-- served by the longer one (a btree serves every leading prefix with the same operator class), so no plan loses an
-- index, and every insert into these tables writes one index entry less. The other standalone business_date indexes
-- stay until a dev load run shows zero scans (audit PERF-08). IndexHygieneTest fails on any new prefix duplicate.
-- app.memo and app.domain_event are partitioned (no DROP INDEX CONCURRENTLY on a partitioned index) and all four
-- tables are pilot-sized in the test account and empty in a new (final) account, so the drops are marked for squawk;
-- lock_timeout bounds the wait.

SET lock_timeout = '5s';

-- squawk-ignore require-concurrent-index-deletion
DROP INDEX app.memo_no_lookup;
-- squawk-ignore require-concurrent-index-deletion
DROP INDEX app.memo_discount_business_date_idx;
-- squawk-ignore require-concurrent-index-deletion
DROP INDEX app.domain_event_id;
-- squawk-ignore require-concurrent-index-deletion
DROP INDEX app.qc_entry_line_business_date_idx;
