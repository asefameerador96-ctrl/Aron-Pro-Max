# UI parity: pack pictures (packshots) in Stock and Sale (lane ui-packshots)

<!-- HANDOVER (for a recycled session): read this block first. Lane branch lane/ui-packshots; lead session_01MbUQSxrP7AB9tbyANjUTPS.
Items: (1) FAST PATH dev pack pictures: pushed 564c5c3e. (2) SERVER PATH: built (section 5): additive contract
(Sku.image_asset_id + getSkuImageReadUrl), Room v8, preload after sync. T1: the lead's Opus checker is asked before
'ready'; no Postgres migration (V0087 released). (3) ADMIN PATH: the web upload exists; runbook
docs/runbooks/sku-pack-pictures.md. Open: AMO/TSO-only SKU tables have no picture slot; owner screenshots needed. -->

Owner question (2026-10-18): "why is there no packshot in the Stock and Sale menus". Reference: `docs/ui-reference/sr/stock.webp`,
`stock.md` UI-SR-18..20, PARITY-SPEC s2.5 and s2.6.

**Plainly:** the real pack art comes from **AKTCL**. Until AKTCL uploads it in the admin portal, the dev builds show
pictures cropped from the owner's own screenshot (three arts: Maxim Regular, Bolt, Wild Berry), and only for the six
SKUs that screenshot shows. Every other SKU shows the neutral pack silhouette. No production build carries the crops.

## 1. What shows a pack picture now

| Screen / row | Reference | Status | Notes |
|---|---|---|---|
| SR Stock, every SKU row | `sr/stock.webp` | PARTIAL | Pack picture (48 x 78 dp, 4 dp corners) with the packs pill at its bottom-right, in place of the letter disc. Still differs: the card layout (68 x 120 dp picture, purple SKU code, red/purple square steppers, totals panel) is the PackCard of lane ui-kit / feature-stock. |
| SR Sale, product list (collapsed and expanded rows) | `sr/stock.webp` layout, `sr/sale-select.png` | PARTIAL | Same picture in each row; same layout caveat. |
| SR Sale, Slide (DRP) and QC cards | none | PARTIAL | Same picture; no reference screenshot. |
| AMO (control call Stock and Sale, via feature-stock / feature-sale) | none (`-noref`) | PARTIAL | The provider is set in app-amo, so the shared rows show pictures. AMO-only SKU tables (team stock, stock correction, price compliance) are text tables with no picture slot. Layout from manual, needs owner screenshot. |
| TSO | none | n/a | No SKU rows with a picture slot in the TSO app today; the provider is set in app-tso so any future row gets pictures. |

Silhouette (no picture yet): grey pack with a lid line and a category glyph (cigarette, bidi, lighter, matchbox); never a
letter circle, never a spinner; decorative for TalkBack (the row text names the SKU).

## 2. Dev SKU to picture mapping (dev seed `db/seed/02_products.sql`)

| SKU code | Picture (`android/dev-packshots/dev-packshots/`) |
|---|---|
| MaxR-10S, MaxR-20S | `maxim-regular.webp` |
| MaxB-10S, MaxB-20S | `maxim-bolt.webp` |
| MaxWB-10S, MaxWB-20S | `maxim-wildberry.webp` (the screenshot cuts this pack off; its lower part, the pictorial warning that every Maxim pack shares, is taken from the Regular pack) |
| the other 31 seed SKUs (Avon, ARIS, Marise, Black Diamond, Sunmoon, Supreme, the bidis, Aster, MAX, FB, SL) | silhouette (no art in any screenshot) |

The 10- and 20-stick packs share one picture because the screenshot shows the same art for both. Crops: 146 x 237 px
WebP, 6.8 to 7.4 KB each (22 KB total in a dev APK), made by `tools/packshots/crop_dev_packshots.py` (deterministic).

## 3. How it works (phone)

- `core-ui` (lane ui-kit's API, `core.ui.aron`): `PackshotProvider` / `LocalPackshotProvider` / `PackshotImage(skuId)`
  with the kit's silhouette. This lane adds `BitmapPackshotProvider` (memory hit on the first frame, else a load off the
  main thread; a throw is the silhouette) and `packCategoryOf(category_code)`. Key: the SKU's server id as a string
  (`sku.skuId.toString()`); a non-numeric key is taken as the SKU code. **Feature lanes: pass the id, not the code.**
- `core-sync` `SkuPackshots`: looks the id up in the signed-in user's local Room catalogue (`ReferenceDao.sku`), then
  (1) the admin thumbnail in the bounded `ImageCache` under the stable key `sku-image:<asset id>:v<version>` (never the
  signed URL); (2) the dev crops, only when `BuildConfig.DEV_PACKSHOTS`. Disk and assets only, never the network;
  decoded bitmaps in a 4 MB memory LRU (RGB_565, downsampled to 400 px high).
- The three apps set `LocalPackshotProvider` once around `setContent` (MainActivity) from a Hilt singleton.
- `-Paron.devPackshots=true` (root `build.gradle.kts`) adds `android/dev-packshots` to the APK assets and sets
  `BuildConfig.DEV_PACKSHOTS`. Only the ci.yml `android-release` job (dev origin) passes it; `infra/tests/check_infra.py`
  fails if any other workflow (release-app.yml, production) mentions it.
- Source (1) is fed by the server path (section 5) once the admin uploads the pictures.
- Today's Stock and Sale screens are still the Calm Glass rows; their 48 x 78 dp picture slot calls the kit's
  `PackshotImage`. When feature-stock / feature-sale move to the kit `PackCard`, pass `skuId = sku.skuId.toString()` and
  the pictures come along with no further wiring.

## 4. Proof

- Side by side: `docs/evidence/ui-parity/sr-stock-sale-packshots.png` (left: the reference; middle: our Stock with the
  five reference SKUs; right: our Sale list, MaxR with its picture, Prime Gold and Aster with the silhouette).
- Goldens: `stock_bn_packshots` (new), and the re-recorded Stock, Sale and QC goldens (the letter disc became the
  picture or the silhouette); the screenshot harness serves the same dev crops (`TestPackshots`).
- Tests: `SkuPackshotsTest` (dev mapping, no art in production, cache key stability, admin thumbnail wins and is read
  from disk without a download, broken or missing is null, downsampling), the kit's `AronLookGalleryTest` covers `PackshotImage` with and
  without a provider.

| Item | sha | CI |
|---|---|---|
| 1 fast path | 564c5c3e52803f3f747095036b4104b7a04b64fb | run 39175158082 |
| 2 server path + 3 admin runbook | (next push) | pending; Opus checker asked |

## 5. Server path (item 2)

- **Contract (additive, no version bump):** `Sku.image_asset_id` (nullable uuid). The bundle fills it from
  `app.sku.image_asset_id`, only while that asset is a `sku_image`, and **never** sends a signed `image_url`: a SAS
  signed per build would move the bundle fingerprint and expire after a day. New operation
  `GET /v1/sku-images/{asset_id}/read-url` (`getSkuImageReadUrl`) answers `MediaReadUrl` with a 5-minute read URL.
  Any signed-in user may call it, because pack art is the same for every reach. Only an asset that a SKU binds is
  served; anything else is 404. Slices, `WireDtos`, `web/src/contract/openapi.d.ts` and docs/24 Appendix A are
  regenerated or updated in the same push.
- **Backend:** `backend/media` `SkuImageRead` + route; `BundleService.products` selects the asset id;
  `SkuImageReadTest`; a `BundleAcceptanceTest` assertion (the asset id is carried, no SKU carries an `image_url`); a
  conformance fixture; scope registry `exempt` (global pack art); one `scope-blind-baseline.txt` line ("global by
  design", like the code lists and tutorials). That line is flagged to the checker because the file says it may only
  shrink.
- **Phone:** Room **v8** (`AutoMigration(7, 8)`, `sku.image_asset_id`, exported `schemas/8.json`, `Migration78Test`);
  `ReferenceRepository.skuRow` maps it (`SeededDayLoadTest.aSkuImageAssetIsStoredFromTheBundle`).
  `SyncApi.skuImageReadUrl`. `PackshotPreload.afterSync` runs from each app's sync runner `afterRun`: it lists the
  pictured SKUs from Room, asks for one read URL per uncached asset, and downloads one bounded thumbnail (300 KB, mobile
  data allowed) keyed `sku-image:<asset id>`, at most 100 per run, one run at a time. The fetched SKU's stand-in is then
  dropped from memory. The screens only read disk.
- **No Postgres migration** (the column exists since V0075): V0087 is not used.
