# UI parity: pack pictures (packshots) in Stock and Sale (lane ui-packshots)

<!-- HANDOVER (for a recycled session): read this block first. Lane branch lane/ui-packshots; lead session_01MbUQSxrP7AB9tbyANjUTPS.
Items: (1) FAST PATH dev pack pictures, reworked on the kit PackCard of ui-sr-a (lead ruling 2026-10-18), this push;
(2) SERVER PATH (built, Opus checker PASS, held locally on branch local/packshots-item2 until the lead says push) bundle image_url/asset/version
+ Room column + preload = NOT STARTED (T1: contract + Room migration; ask the lead for the Opus checker; V0087 reserved
if a db migration is needed); (3) ADMIN PATH check + runbook = NOT STARTED. -->

Owner question (2026-10-18): "why is there no packshot in the Stock and Sale menus". Reference: `docs/ui-reference/sr/stock.webp`,
`stock.md` UI-SR-18..20, PARITY-SPEC s2.5 and s2.6.

**Plainly:** the real pack art comes from **AKTCL**. Until AKTCL uploads it in the admin portal, the dev builds show
pictures cropped from the owner's own screenshot (three arts: Maxim Regular, Bolt, Wild Berry), and only for the six
SKUs that screenshot shows. Every other SKU shows the neutral pack silhouette. No production build carries the crops.

## 1. What shows a pack picture now

| Screen / row | Reference | Status | Notes |
|---|---|---|---|
| SR Stock, every SKU row (kit `PackCard`, lane ui-sr-a) | `sr/stock.webp` | DONE (picture) | The 56 x 90 dp picture slot of the kit card is drawn by this lane's provider; the purple packs badge sits on it. Evidence: `docs/evidence/ui-parity/packshots-stock.png`. The card layout is ui-sr-a's (the SKU code wraps on 360 dp; reported to ui-sr-a). |
| SR Sale, product list (kit `PackCard`) | `sr/sale-select.png` (list layout = Stock) | DONE (picture) | Same provider. Evidence: `docs/evidence/ui-parity/packshots-sale.png` (MaxR with its picture; Prime Gold and Aster with the silhouette, no art for them). |
| AMO control call Stock and Sale (shared feature modules) | none (`-noref`) | DONE (picture) | The provider is set in app-amo. AMO-only SKU tables (team stock, stock correction, price compliance) are text tables with no picture slot. Layout from the manual; needs an owner screenshot. |
| TSO | none | n/a | No SKU picture slot in the TSO app today; the provider is set in app-tso. |

Silhouette (no picture yet): the kit's grey pack per category (cigarette, bidi, lighter, match), never a letter circle and
never a spinner.

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
- Source (1) has no data yet: the bundle does not carry a SKU image reference (item 2 of this lane).
- Today's Stock and Sale screens are still the Calm Glass rows; their 48 x 78 dp picture slot calls the kit's
  `PackshotImage`. When feature-stock / feature-sale move to the kit `PackCard`, pass `skuId = sku.skuId.toString()` and
  the pictures come along with no further wiring.

## 4. Proof

- `docs/evidence/ui-parity/packshots-stock.png` and `packshots-sale.png`: the reference on the left, ours on the right,
  parity shell only (kit PackCard rows). Every seeded Maxim SKU (MaxR, MaxB, MaxWB, each 10S and 20S) shows its picture;
  in Sale, Aster and Flame Box show the silhouette (no art). Goldens `stock_bn_packshots` and `sale_bn_packshots` (tall
  captures, so every row is in the picture).
- `PackshotWiringTest` (ui-screenshots): the kit PackCard rows of the new Stock and Sale screens ask the provider by the
  server SKU id, and the picture it returns is drawn; rows without a picture keep the silhouette.
- Goldens: only this lane's own `stock_bn_packshots` and `sale_bn_packshots` (all six seeded Maxim SKUs, the provider
  installed around those two captures only). Every other Stock and Sale golden is ui-sr-a's, untouched: the generic
  screenshot hosts install no provider, so they show the silhouette, as on INT (lead ruling 2026-10-19).
- `SkuPackshotsTest` (dev mapping, no art in production, id lookup, cache key, admin thumbnail wins, broken or missing
  is null, downsampling) and `BitmapPackshotProviderTest` (first-frame memory hit, load, a failing source is the
  silhouette).

| Item | sha | CI |
|---|---|---|
| 1 fast path (withdrawn) | 564c5c3e | run 39175158082 green |
| 1 fast path on the kit PackCard | (this push) | pending |

## 5. Server path (item 2; lead rulings 2026-10-18)

- **Contract (additive, no version bump):** `Sku.image_asset_id` (nullable uuid). The bundle fills it from
  `app.sku.image_asset_id`, only while that asset is a `sku_image`. The bundle **never** sends `image_url`: a SAS signed
  per build would move the bundle fingerprint and expire after a day. The phone builds the path on its own API
  origin, because the server has no public-origin setting and pre-generates bundles off-request (lead informed; the
  lead's ruling had asked for a baked `image_url`). New operation `GET /v1/sku-images/{asset_id}` (`getSkuImage`): the
  API **streams the bytes itself** (no signed URL leaves it), with `Cache-Control: private, max-age=31536000, immutable`,
  `ETag` = the asset id, and 304 on `If-None-Match`. It is served only when the asset exists, has purpose `sku_image`, is
  at most 300 KB, is JPEG or PNG, and a SKU binds it; anything else, a malformed id included, is a bare 404. Any
  signed-in user may call it, because pack art is the same for every reach. Slices, `WireDtos`,
  `web/src/contract/openapi.d.ts` and docs/24 Appendix A are updated in the same push.
- **Backend:** `backend/media` `SkuImageRead` + route, `BlobBytesReader` port (Azure: `AzureBlobSasIssuer.readBytes`,
  which checks the size on the properties before downloading), `BlobPorts.imageRead`. `BundleService.products` selects
  the asset id. Tests: `SkuImageReadTest`; `BundleImageStabilityTest` (two independent builds of the same catalogue
  give the same bundle hash, and binding a picture changes it once); a `BundleAcceptanceTest` assertion; a conformance
  fixture (200); scope registry `exempt` (global pack art). One `scope-blind-baseline.txt` line ("global by design",
  like the code lists and tutorials) is flagged to the checker, because the file says it may only shrink.
- **Phone:** Room **v8** (`AutoMigration(7, 8)`, `sku.image_asset_id`, exported `schemas/8.json`, `Migration78Test`);
  `ReferenceRepository.skuRow` maps it (`SeededDayLoadTest.aSkuImageAssetIsStoredFromTheBundle`).
  `SyncApi.skuImage` reads the bytes through `AronApiClient` (the token is added; the body is capped at 300 KB:
  `SkuImageApiTest`). `ImageCache.store` accepts only JPEG, PNG or WebP magic within the cap, writes atomically and
  evicts. `PackshotPreload.afterSync` runs from each app's sync runner `afterRun`: it lists the pictured SKUs from Room
  and downloads each uncached asset once (mobile data allowed, at most 100 per run, one run at a time). The fetched
  SKU's stand-in is then dropped from memory. The screens only read disk.
- **No Postgres migration** (the column exists since V0075): V0087 is not used.
- **Not yet proven on the dev account:** the end-to-end run (admin upload, then phone sync, then picture) needs this
  push deployed to dev and one picture uploaded (docs/runbooks/sku-pack-pictures.md).
