-- V0035 SECURITY DEFINER functions search pg_temp last (V0033 checker). Without pg_temp in search_path PostgreSQL
-- searches it FIRST for relations, so a caller with TEMP on the database (every role, by default) could put a temporary
-- table or view in front of one the function reads and run code as the function's owner. app.ensure_partitions (V0014,
-- executable by jobs_rw) gets pg_temp last; app.outbox_horizon_lag (V0033) is created that way.

SET lock_timeout = '5s';

ALTER FUNCTION app.ensure_partitions(date, date) SET search_path = pg_catalog, app, pg_temp;
