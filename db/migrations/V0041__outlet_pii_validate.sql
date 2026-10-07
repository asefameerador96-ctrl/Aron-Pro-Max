-- V0041 validates the V0040 outlet PII constraints (added NOT VALID; separate transaction, squawk rule).

SET lock_timeout = '5s';

ALTER TABLE app.outlet VALIDATE CONSTRAINT outlet_pii_key_id_fkey;
ALTER TABLE app.outlet VALIDATE CONSTRAINT outlet_pii_enc_shape;
