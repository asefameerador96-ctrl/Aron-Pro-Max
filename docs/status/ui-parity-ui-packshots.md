# UI parity: pack pictures (packshots) in Stock and Sale (lane ui-packshots)

<!-- HANDOVER (for a recycled session): read this block first. Lane branch lane/ui-packshots; lead session_01MbUQSxrP7AB9tbyANjUTPS.
Items: (1) FAST PATH dev pack pictures = this file's section 1, pushed; (2) SERVER PATH bundle image_url/asset/version
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

- `core-ui` `PackshotProvider` / `LocalPackshotProvider` / `PackshotImage(PackshotSku)` / `PackSilhouette`. The kit's
  PackCard (lane ui-kit) should call `PackshotImage` for its picture slot.
- `core-sync` `SkuPackshots`: (1) the admin thumbnail in the bounded `ImageCache` under the stable key
  `sku-image:<asset id>:v<version>` (never the signed URL); (2) the dev crops, only when `BuildConfig.DEV_PACKSHOTS`.
  Disk and assets only, never the network; decoded bitmaps in a 4 MB memory LRU (RGB_565, downsampled to 400 px high).
- `-Paron.devPackshots=true` (root `build.gradle.kts`) adds `android/dev-packshots` to the APK assets and sets
  `BuildConfig.DEV_PACKSHOTS`. Only the ci.yml `android-release` job (dev origin) passes it; `infra/tests/check_infra.py`
  fails if any other workflow (release-app.yml, production) mentions it.
- Step (1) has no data yet: the bundle does not carry a SKU image reference (item 2 of this lane).

## 4. Proof

- Side by side: `docs/evidence/ui-parity/sr-stock-sale-packshots.png` (left: the reference; middle: our Stock with the
  five reference SKUs; right: our Sale list, MaxR with its picture, Prime Gold and Aster with the silhouette).
- Goldens: `stock_bn_packshots` (new), and the re-recorded Stock, Sale and QC goldens (the letter disc became the
  picture or the silhouette); the screenshot harness serves the same dev crops (`TestPackshots`).
- Tests: `SkuPackshotsTest` (dev mapping, no art in production, cache key stability, admin thumbnail wins and is read
  from disk without a download, broken or missing is null, downsampling), `PackshotImageTest` (silhouette, picture,
  first-frame memory hit, a throwing provider is the silhouette).

| Item | sha | CI |
|---|---|---|
| 1 fast path | (this push) | pending |
