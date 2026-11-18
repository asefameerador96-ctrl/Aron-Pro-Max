-- V0234 (PROVISIONAL number, lane sr-targets-server; the lead renumbers at landing, never run outside lane CI before that)
-- Indexes for the month-to-date reads of the SR programme sections of the bundle (B3 of the lead review, D-TKPI-10). The reads are
-- "route_id = ANY(:r) AND business_date BETWEEN :first AND :through" on dw.agg_daily_route_sku and dw.agg_daily_route_brand, whose keys lead with
-- business_date, and "outlet_id = ANY(:o) AND business_date BETWEEN ..." on dw.fact_memo_line, which had no usable index for outlet_id. On PostgreSQL 16
-- (no skip scan) a date-range index walks every route of the month: at the final account (8,500 routes x ~40 SKUs) that is millions of index entries per
-- bundle build. These indexes lead with the route (or the outlet), so a build reads only its own rows (about 30 days x ~40 SKUs per route).
--
-- Build: the plain CREATE INDEX of V0036 / V0062 (a migration runs inside a transaction, so CONCURRENTLY is not possible, and a partitioned table cannot be
-- indexed CONCURRENTLY on the parent anyway). The tables are small at pilot size on the test account and empty in a new final account. IF THE FINAL ACCOUNT
-- IS MOVED WITH DATA, create the same three indexes CONCURRENTLY by hand BEFORE this migration (IF NOT EXISTS makes the migration a no-op then); for
-- dw.fact_memo_line create them per partition CONCURRENTLY, then CREATE INDEX ON ONLY the parent and ATTACH PARTITION each. Plans are in
-- docs/status/sr-targets-server.md (section "Fleet-size plans").
SET lock_timeout = '5s';

-- squawk-ignore require-concurrent-index-creation
CREATE INDEX IF NOT EXISTS agg_daily_route_sku_route_date ON dw.agg_daily_route_sku (route_id, business_date);
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX IF NOT EXISTS agg_daily_route_brand_route_date ON dw.agg_daily_route_brand (route_id, business_date);
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX IF NOT EXISTS fact_memo_line_outlet_date ON dw.fact_memo_line (outlet_id, business_date);
