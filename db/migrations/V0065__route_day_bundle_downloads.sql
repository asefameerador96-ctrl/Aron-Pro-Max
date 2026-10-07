-- V0065 bundle downloads per route-day (F-SYS-025, Data Entry Log; answers docs/requests/backend-core-route-day-downloads.md).
-- Uploads already have first and last (route_day.in_field_at, last_batch_at) and counts (app.sync_batch); downloads
-- had only the first (target_frozen_at). On every full GET /v1/sync/bundle (not a 304, not a delta page) backend-core
-- sets last_bundle_at = greatest(last_bundle_at, now()) and bundle_count = bundle_count + 1 for each route-day in the
-- bundle; backend-reports shows first = target_frozen_at, last = last_bundle_at, count = bundle_count in Dhaka time.
-- api_rw's '*/update' row and worker_rw's route_day row already cover the columns. The update goes through
-- route_day_touch, so it also moves updated_at and version, as every route-day write does.
-- A constant default and a nullable column are catalogue-only changes; the CHECK is added NOT VALID (no scan under the
-- lock) and validated in V0066.

SET lock_timeout = '5s';

ALTER TABLE app.route_day ADD COLUMN last_bundle_at timestamptz;
ALTER TABLE app.route_day ADD COLUMN bundle_count int NOT NULL DEFAULT 0;
ALTER TABLE app.route_day ADD CONSTRAINT route_day_bundle_count_nonneg CHECK (bundle_count >= 0) NOT VALID;

COMMENT ON COLUMN app.route_day.last_bundle_at IS 'UTC time of the last full bundle download that carried this route-day (Data Entry Log DOWNLOAD MAX); null before the first.';
COMMENT ON COLUMN app.route_day.bundle_count IS 'Number of full bundle downloads that carried this route-day (Data Entry Log DOWNLOAD count); 304 answers and delta pages are not counted.';
