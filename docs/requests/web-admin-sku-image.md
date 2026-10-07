# Request: SKU pack image member (F-ADM-065)

Lane: web-admin.

`POST /v1/admin/assets` accepts purpose `sku_image`, but `Sku`, `SkuWrite` and `SkuPatch` carry no image member, so an uploaded pack image cannot be bound to a SKU and nothing reaches the Stock, Sale and Memo screens.

Proposed: `image_asset_id` (Uuid or null) on `SkuWrite` and `SkuPatch`, and `image_url` (uri or null) on `Sku`, like `GiftWrite.image_asset_id` and `Gift.image_url`.

Stub: F-ADM-065 is parked (DEPENDENCY-WAIT). The upload ticket flow of F-ADM-026 (`web/src/components/admin/tutorial-manager.tsx`, `web/src/lib/admin/tutorials.ts`) is the pattern; the SKU form will reuse it with the 300 KB image limit.
