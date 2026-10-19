# Request: AV/KV content assets must not reach phones as a stored one-day SAS (to: backend-admin)

From: lane ui-packshots, 2026-10-18 (found while building the pack-picture path; the lead logged it and routes it).
Docs only; nothing here is built yet.

## The defect

`AdminContentApi` writes `app.content_item.asset_url = d.blob.readUrl(asset.blobPath)` on create and on update
(`backend/masterdata/.../AdminContentApi.kt`, the INSERT and the UPDATE of `content_item`). `readUrl` is a read SAS
valid for `readTtl`, 24 h (`AzureBlobSasIssuer`). The bundle (`BundleContent`) sends that stored URL to the phones, and
`ContentPrefetchWorker` downloads it later, on Wi-Fi, often days after the item was saved. After 24 h the signature has
expired, so the download fails and the call logs `skipped_missing`. AV/KV content older than a day therefore never
reaches a phone that did not fetch it on the first day. A SAS is also a bearer secret that should not sit in a table or
a bundle.

## Proposed fix (the same pattern as the pack pictures, lead ruling 2026-10-18)

1. Keep the stable `asset_id` on `content_item` (it is there already, V0023) and stop storing a signed URL. Either make
   `asset_url` the stable API path `/v1/content-assets/{asset_id}`, or add `ContentItem.asset_id` and let the phone
   build the path on its own API origin. The second matches `Sku.image_asset_id`; the server has no public-origin
   setting.
2. New `GET /v1/content-assets/{asset_id}`: any signed-in field user, streamed by the API with its managed identity
   (no SAS leaves it), `Cache-Control: private, max-age=31536000, immutable` and `ETag` = asset id (assets are
   immutable), and a bare 404 unless the asset exists, has purpose `content_av` or `content_kv`, and a live
   `content_item` uses it. Videos can reach `cfg.content.max_item_mb`, so stream with range support rather than
   buffering the whole file.
3. Phone: `ContentAssets.prefetch` downloads through `AronApiClient` (token added) and keeps keying the cache by
   `sha256`, as now.
4. Tests: a bundle hash stability test (two builds of the same content give the same hash), the endpoint's
   200/304/404, and a prefetch of an item created more than 24 h ago.

Contract: additive. No Postgres migration if option 1b is taken (the column exists).
