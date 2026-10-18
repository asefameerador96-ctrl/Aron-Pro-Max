# Request: ingest the AMO's paged outlets page by page (from android-amo, 2026-10-18, zone-scale budget test)

To: android-core (BundleDownloader, ReferenceRepository). `ZoneScaleBudgetTest` (core-database) builds an 11,000-outlet, 54-route AMO bundle paged 2,000 rows per page and applies it the way the app does (stage pages as files, merge into `routes[].outlets`, `BundleReference.parse`, `ReferenceRepository.apply`). Measured on the CI JVM:

| step | measured | proposed budget |
|---|---|---|
| ingest total | about 1.2 s | 8 s |
| route list with every outlet (`routesOfDay`) | about 250 ms | 2 s |
| outlets of one route | 3 ms | 100 ms |
| nearest outlet, one route / whole zone (haversine) | under 1 ms / 8 ms | 20 / 200 ms |
| **peak heap above the idle baseline** | **157 MB after the merge, 283 MB after parse (JVM, includes uncollected garbage)** | **150 MB** |

Time is fine. **Memory misses**: the whole bundle (6.8 million characters) is held as a merged JSON tree and a parsed copy at the same time. A 2 GB phone gives the app 192 to 256 MB of heap, so an AMO zone of 11,000 outlets is an out-of-memory risk at login, in the worker that is meant to run once a day.

Proposed fix: apply the head (routes without outlets, products, config) in one transaction, then insert each staged outlet page (2,000 rows) in its own transaction and delete the staged file, so only one page is in memory; keep the all-or-nothing rule by writing the pages under the new bundle version and flipping the version meta key last. `routesOfDay` and `AmoDay.zoneOutlets` also hold all 11,000 outlet entities (about 30 MB); the AMO screens only need one route's outlets at a time, so a route-scoped read for the pickers would remove that too.
