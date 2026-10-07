-- V0059 two more ingest_registry flags (answers docs/requests/backend-core-registry-flags-more.md):
--   config_stamp_regress (F-SYS-091, docs/24 s11.4): a row stamped with a config_version below one the device had
--     already applied; the third flagged row of a device and business date raises CONFIG_STAMP_REGRESS in backend-core,
--     which counts them through the partial index below.
--   checkout_too_early (BC-63): a check-out before cfg.day.checkout_earliest_time, accepted and flagged.
-- The V0056 CHECK is replaced: dropped and re-added NOT VALID (no scan under the lock); V0060 validates it. Each known
-- flag at most once: the cardinality must equal the number of distinct known flags present (no subquery needed).
-- The partial index holds only flagged rows (none at all in normal operation); app.ingest_registry is pilot-sized in the
-- test account, pruned after cfg.retention.ingest_registry_days, and empty in a new account, and a partitioned index
-- cannot be built CONCURRENTLY, so the build is marked for squawk; lock_timeout bounds the wait.

SET lock_timeout = '5s';

ALTER TABLE app.ingest_registry DROP CONSTRAINT ingest_registry_flags_known;
ALTER TABLE app.ingest_registry ADD CONSTRAINT ingest_registry_flags_known
  CHECK (flags <@ ARRAY['resync_late', 'config_stamp_regress', 'checkout_too_early']::text[]
         AND cardinality(flags) = ('resync_late' = ANY (flags))::int + ('config_stamp_regress' = ANY (flags))::int
                                + ('checkout_too_early' = ANY (flags))::int) NOT VALID;

-- squawk-ignore require-concurrent-index-creation
CREATE INDEX ingest_registry_config_stamp_regress ON app.ingest_registry (device_id, business_date)
  WHERE 'config_stamp_regress' = ANY (flags);

COMMENT ON COLUMN app.ingest_registry.flags IS 'Flags on the accepted record: resync_late (re-sent after a failover or restore, accepted past cfg.sync.max_backdate_days, F-SYS-089), config_stamp_regress (stamped with an older config_version than the device had applied, F-SYS-091), checkout_too_early (check-out before cfg.day.checkout_earliest_time, BC-63); empty when none.';
