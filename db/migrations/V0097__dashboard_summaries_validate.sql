-- V0097 validates the V0096 range CHECK on dw.agg_daily_route (added NOT VALID; separate transaction, squawk rule).

SET lock_timeout = '5s';

ALTER TABLE dw.agg_daily_route VALIDATE CONSTRAINT agg_daily_route_v0096_ranges;
