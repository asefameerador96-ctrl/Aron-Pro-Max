# Request to db: a database-side geography version (for the final account, not now)

From: backend-reports (session 9), 2026-10-29. Not urgent for the pilot (5 to 10 users, one API instance).

**Problem.** `GeoRepository` reloads the geography snapshot every 60 s and on `invalidate()` in the process that made the admin write. At fleet size production runs 3 to 8 API replicas (up to 30): every other replica serves the old geography for up to 60 s after an edit, and the worker resolvers (Main.kt export and bundle workers) are never invalidated. A cached `Reach` is keyed on the snapshot version, so it follows the snapshot, but the snapshot itself lags.

**Ask.** A trigger-bumped `app.geo_version` (single row, bigint) incremented by any insert, update or delete on app.wing, division, territory, house, zone, route (and `zone.house_id`), read in the same query as `scope_version` in `SqlReachResolver.reach` (one extra column on a query already made on every call). `GeoRepository` reloads when the value it holds is older than the one read.

**Alternative without a migration.** Re-resolve the reach once, bypassing the cache, when `NodeMembers.allowed` fails, and retry the read; costs one extra reach computation on a refusal.

Backend side after the migration: `GeoRepository.snapshot()` takes the database version as an input to its freshness test; `GeoSnapshot.version` stays as the cache key.
