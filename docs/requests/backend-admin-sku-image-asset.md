# Request: `app.sku.image_asset_id` (to: db)

From: backend-admin. Blocks contract v1.3 item 5 (`SkuWrite`/`SkuPatch.image_asset_id`, `Sku.image_url`).

`app.sku` has only `thumbnail_media_uuid` (a phone media uuid). The admin asset store (`app.admin_asset`, purpose `sku_image` already allowed in V0023) needs a link:

- `ALTER TABLE app.sku ADD COLUMN image_asset_id uuid REFERENCES app.admin_asset(asset_id)` (nullable), forward-only; plus the data-dictionary comment ("Admin asset of the pack image; null when none").
- `app.touch_master` already bumps `version`/`updated_at` on update, so no trigger work.

backend-admin then validates purpose `sku_image` and the 300 KB cap (via the asset), writes the column on create and patch (null removes), and returns `image_url` (read URL of the blob path) on `Sku`.
