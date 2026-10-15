-- V0076 validates the V0075 sku image asset FK (added NOT VALID; separate transaction, squawk rule).

SET lock_timeout = '5s';

ALTER TABLE app.sku VALIDATE CONSTRAINT sku_image_asset_id_fkey;
