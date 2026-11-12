-- V0159 validates the V0158 constraints added NOT VALID (separate transaction, squawk rule).
-- app.programme holds no row that could fail them: the old CHECK allowed a subset of the new one, and current_version_id is null everywhere.
SET lock_timeout = '5s';
ALTER TABLE app.programme VALIDATE CONSTRAINT programme_status_check;
ALTER TABLE app.programme VALIDATE CONSTRAINT programme_current_version_fk;
