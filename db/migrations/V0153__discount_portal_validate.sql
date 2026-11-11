-- V0153 validates the V0152 app.offer.status CHECK (added NOT VALID; separate transaction, squawk rule).
-- app.offer holds no row that could fail it (nothing wrote offers before the portal, and the old CHECK allowed a subset).
SET lock_timeout = '5s';
ALTER TABLE app.offer VALIDATE CONSTRAINT offer_status_check;
