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
| SR Stock, every SKU row (kit `PackCard`, lane ui-sr-a) | `sr/stock.webp` | DONE (picture) | The 56 x 90 dp picture slot of the kit card is drawn by this lane's provider; the purple packs badge sits on it. Evidence: `docs/evidence/ui-parity/packshots-stock.png`. The card layout is ui-sr-a's (the SKU code wraps on 360 dp: reported to ui-sr-a). |
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
- Goldens: `stock_bn_packshots` and `sale_bn_packshots` (all six seeded Maxim SKUs). The Stock and Sale goldens whose fixture has MaxR 10S are
  re-recorded (they now show its picture); no other golden changes. The harness serves the dev crops (`TestPackshots`).
- `SkuPackshotsTest` (dev mapping, no art in production, id lookup, cache key, admin thumbnail wins, broken or missing
  is null, downsampling) and `BitmapPackshotProviderTest` (first-frame memory hit, load, a failing source is the
  silhouette).

| Item | sha | CI |
|---|---|---|
| 1 fast path (withdrawn) | 564c5c3e | run 39175158082 green |
| 1 fast path on the kit PackCard | (this push) | pending |
