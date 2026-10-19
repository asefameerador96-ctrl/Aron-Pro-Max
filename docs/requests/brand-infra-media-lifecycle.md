# brand to infra: the media lifecycle rule also tiers the pack pictures

From: stream J5 (docs), 2026-10-19. Owner of `infra/`: infra. Nothing in the brand work depends on this; it belongs to the next
infra change. Not run: no `az` or Bicep command was run for this note (agents do not touch dev Azure).

## What the code says (read on 2026-10-19)

`infra/modules/storage.bicep` has a lifecycle rule `media-tiering` with `prefixMatch: ['${mediaContainer}/']`: every block blob
in the media container (`media`, `ARON_BLOB_CONTAINER_MEDIA`) goes to Cool after 30 days without modification and to Cold after
90 (its comment says "photos"). The admin asset store writes the pack pictures into that same container, at
`assets/sku_image/<asset_id>.<ext>` (`AdminContentApi.kt:416`, `assets/${purpose}/...`; the content assets, purposes starting
`content_`, go to `assets/content_*/...` the same way). The field photos the rule was written for sit under
`photos/<business_date>/...` (`MediaModule.kt:138`, `MediaUpload.kt:71`).

The phones no longer read a blob link: `GET /v1/sku-images/{asset_id}` makes the API read the blob with its managed identity and
stream the bytes (`SkuImageRead.kt`, `AzureBlobSasIssuer.readBytes`, D-PACK-03), and each phone asks once per asset id and then
keeps the picture in its disk cache. So the Cool and Cold read and retrieval charges fall on the API's read after a picture has
aged 30 or 90 days, for every phone that is new or has evicted its cache.

## Ask

The rule is wrong in kind, not in size (42 pictures of about 20 KB). Narrow the filter to the photo prefix
(`'${mediaContainer}/photos/'`), or add an exclusion for `assets/`. Check first that `photos/` is the only prefix the rule was
meant for; this note does not claim there is no other.
