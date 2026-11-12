-- V0171 validates the V0170 foreign key app.gift.image_asset_id (added NOT VALID; separate transaction, squawk rule). The column is new and null everywhere.
SET lock_timeout = '5s';
ALTER TABLE app.gift VALIDATE CONSTRAINT gift_image_asset_fk;
