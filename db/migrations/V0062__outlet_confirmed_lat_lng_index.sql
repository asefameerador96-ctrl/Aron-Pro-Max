-- V0062 bounding-box index for GET /v1/outlets/nearby (F-API-019, D24-77; answers
-- docs/requests/backend-core-outlet-geo-index.md). The API selects candidates with
-- "lat BETWEEN :s AND :n AND lng BETWEEN :w AND :e" on active, confirmed outlets that have a route, then filters by
-- reach and haversine. A btree on (lat, lng) is enough for 50 to 300 m boxes (no PostGIS). Partial on exactly the
-- query's predicate so the planner can use it and the index skips outlets the endpoint never returns.

SET lock_timeout = '5s';

-- app.outlet is pilot size on the test account (60 seed outlets); the same plain build as V0036 and V0046. In the
-- final account, build it CONCURRENTLY by hand first if outlet already holds the imported register.
-- squawk-ignore require-concurrent-index-creation
CREATE INDEX outlet_confirmed_lat_lng ON app.outlet (lat, lng)
  WHERE status = 'active' AND location_confirmed AND route_id IS NOT NULL;
