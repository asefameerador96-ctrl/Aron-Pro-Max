-- V0088 validates the V0087 CHECKs (added NOT VALID; separate transaction, squawk rule).

SET lock_timeout = '5s';

ALTER TABLE app.role_def VALIDATE CONSTRAINT role_def_role_check;
ALTER TABLE app.app_user VALIDATE CONSTRAINT app_user_no_top;
