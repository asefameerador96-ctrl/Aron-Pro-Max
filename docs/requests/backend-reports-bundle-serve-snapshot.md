# backend-reports request: serving stored bundle snapshots (F-SYS-067, second half)

Filed 2026-10-15 by backend-reports. Built and tested: the worker's 22:00 / 03:30 / 04:30 passes (`BundlePreGenerator`):
D+1 bundles for every active SR, AMO and TSO, paced at `cfg.bundle.regen_max_per_s`, advisory-locked per phase and date,
coverage check with an immediate build of what is missing below 99 %. The pre-built row is the `app.bundle_snapshot` seq, so
the Wi-Fi pre-fetch and the morning request see a stable ETag and get 304 while the content is unchanged.

**Not built, and why:** the bodies are not stored and the API still builds on request. Serving a stored body is only safe if
every change that touches a user's bundle marks the user dirty, and nothing produces `bundle_user` dirty keys today
(`grep mark_dirty db/migrations`: no producer). A stale stored bundle would ship old prices or a removed outlet to the
field, which is worse than a 200 ms rebuild.

## To db (migration)
Producers for `app.mark_dirty('bundle_user', user_id, business_date, reason)` (the date is the next business day and today)
on changes to: `route_assignment`, `outlet` (route, status, name, location, radius inputs), `route`, `route_planned`,
`sku_price`/price lists, `sku` (status), `calendar_holiday`, `task` (assignee), `survey`/`survey_version`, content items,
`cfg_value` of a bundle-relevant key (the whole fleet: mark by scope, or bump a `config_version` the snapshot compares).
Debounce 60 s is the consumer's job (`last_dirtied_at`).

## To infra
A `bundles` container in the storage account (`ARON_BLOB_CONTAINER_BUNDLES`, default `bundles`), 3-day lifecycle rule, the
API and worker identities `Storage Blob Data Contributor` (the same account as `exports`).

## Then (this lane)
Store the gzip body at `<date>/<user>/<seq>.json.gz` when a pass builds, serve it from `GET /v1/sync/bundle` when the user has
no dirty key and the stored seq is the latest, and build on request otherwise (`bundle_hold`: yesterday's snapshot plus delta
when a build fails). The acceptance "8,500 simulated bundles inside the load budget" belongs to the N-058 load test: the
worker paces to 20 builds per second, so 8,500 users take about 7 minutes.
