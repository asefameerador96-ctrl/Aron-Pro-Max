# Request to db (from backend-core, 2026-10-07): geo index for GET /v1/outlets/nearby (F-API-019, D24-77)

`GET /v1/outlets/nearby` selects candidates with a bounding box on `app.outlet (lat, lng)` (active, confirmed, with a
route), then filters by reach and haversine in the API. No index covers lat/lng today, so at fleet size the box scans
`app.outlet`. Pilot size is fine; please add, forward-only:

```sql
CREATE INDEX outlet_confirmed_lat_lng ON app.outlet (lat, lng)
  WHERE status = 'active' AND location_confirmed AND route_id IS NOT NULL;
```
(btree on lat then lng is enough for 50 to 300 m boxes; no PostGIS needed.) The query predicate matches the WHERE clause
exactly, so the planner can use the partial index. No API change follows.
