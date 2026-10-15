-- V0075 app.sku.image_asset_id (contract v1.3 item 5; answers docs/requests/backend-admin-sku-image-asset.md): the
-- admin asset (purpose sku_image, V0023) of the pack image. backend-admin checks the purpose and the 300 KB cap on the
-- asset, writes the column on create and patch (null removes) and returns image_url. sku_touch (touch_master) moves
-- version and updated_at on every change. The column is new and NULL on every row; the FK is added NOT VALID (no scan
-- under the lock) and validated in V0076. api_rw's '*/update' row covers the column.

SET lock_timeout = '5s';

ALTER TABLE app.sku ADD COLUMN image_asset_id uuid;
ALTER TABLE app.sku ADD CONSTRAINT sku_image_asset_id_fkey
  FOREIGN KEY (image_asset_id) REFERENCES app.admin_asset(asset_id) NOT VALID;

COMMENT ON COLUMN app.sku.image_asset_id IS 'Admin asset of the pack image (app.admin_asset, purpose sku_image); null when none.';
