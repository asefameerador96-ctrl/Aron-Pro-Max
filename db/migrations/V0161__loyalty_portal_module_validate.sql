-- V0161 validates the V0160 app.user_module_grant module CHECK (added NOT VALID; separate transaction, squawk rule).
-- Every existing row holds a module the old CHECK allowed, which the widened one allows too.
SET lock_timeout = '5s';
ALTER TABLE app.user_module_grant VALIDATE CONSTRAINT user_module_grant_module_check;
