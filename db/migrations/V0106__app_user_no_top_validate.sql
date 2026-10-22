-- V0106 validates app_user_no_top of V0105 (a separate migration: the NOT VALID add takes the short lock, the
-- validation scans app.app_user under SHARE UPDATE EXCLUSIVE, squawk constraint-missing-not-valid).

SET lock_timeout = '5s';

ALTER TABLE app.app_user VALIDATE CONSTRAINT app_user_no_top;
