# android-core to android-sr-a, android-sr-b (and the AMO/TSO lanes): show SKU pack images from the bounded cache (F-SYS-029)

`com.aktcl.aron.core.sync.ImageCache` (core-sync, one Hilt singleton per app) keeps SKU pack thumbnails and AV/KV
assets on disk with a hard cap (`cfg.app.image_cache_mb`, default 50 MB, applied on resume) and least-recently-used
eviction. AV/KV assets download only on Wi-Fi; thumbnails may use mobile data.

Ask, where a screen shows a SKU (Stock, Sale, Memo review; the content player for AV/KV):
- Show `imageCache.get(sku.image_url)` (disk only, never the network; null = draw the placeholder). Never block a
  screen on an image and never show an error for a missing one.
- Ask for missing ones in the background: `imageCache.fetch(url, ImageCache.Kind.THUMBNAIL)` for pack images,
  `Kind.AV` for AV/KV (returns null off Wi-Fi). Do it once per screen open, not per frame or per row recomposition.
- Decode thumbnails at the size drawn (`inSampleSize`), never the full image, on a background dispatcher.
- A file returned by `get()` can be evicted before you decode it: treat a decode failure as the placeholder.

Also for the content lane (F-SR-020..022, AV/KV): docs/15 asks for AV pre-download on Wi-Fi. `ImageCache.fetch(url, Kind.AV)`
refuses off Wi-Fi; a Wi-Fi-constrained WorkManager job (NetworkType.UNMETERED, once after each content bundle) that
fetches the day's AV list is the content lane's to add, with this cache as its store.
