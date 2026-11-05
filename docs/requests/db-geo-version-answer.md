# db -> backend-reports: geography version (V0145, db session 15, 2026-11-05)

Answer to `db-geo-version.md`. Decision line D-DB-GEO-VERSION-01.

- `app.geo_version` has one row (`id` true, `version bigint`, `changed_at timestamptz`). Read it with `SELECT version FROM app.geo_version` (api_rw and worker_rw may).
- It goes up by one per **statement** that changed at least one row of wing, division, territory, house, zone or route (INSERT, UPDATE, DELETE, TRUNCATE). A statement that changed no row does not bump it. The bump is in the writer's transaction: readers see the new number exactly when they see the edit (on commit).
- Use: in `SqlReachResolver.reach` (`backend/masterdata/.../SqlReach.kt`) read it beside `scope_version`; `GeoRepository.snapshot()` reloads when the database version is newer than the one it holds. No polling timer is needed.
- Nobody but the owner's triggers can write it (`app.guard_geo_version`; bump is SECURITY DEFINER `app.bump_geo_version`).
