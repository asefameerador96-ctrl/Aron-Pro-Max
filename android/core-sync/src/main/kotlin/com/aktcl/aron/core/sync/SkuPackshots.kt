package com.aktcl.aron.core.sync

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pack pictures of the Stock and Sale rows (PARITY-SPEC s2.6, stock.md UI-SR-19, docs/04): resolves a SKU to a small
 * decoded bitmap from what is already on the phone. The key is the kit's `PackshotImage(skuId)` string: the server SKU id
 * (looked up in the user's local catalogue with [lookup]) or, when it is not a number, the SKU code itself. In this order:
 *  1. the admin-managed thumbnail in the bounded [ImageCache], under the stable key [cacheKey] (the bundle's
 *     `image_asset_id`: a new upload is a new asset; never the signed URL, which changes while the picture does not);
 *  2. DEV builds only ([devAssets] non-null, set when BuildConfig.DEV_PACKSHOTS): a picture cropped from the owner's
 *     reference screenshot, bundled under `assets/dev-packshots/` and mapped by SKU code in its `index.txt`.
 * [bitmap] never touches the network and never throws; null means "no picture" and the row shows the silhouette. Decoded
 * bitmaps sit in a small memory LRU ([memoryBytes], RGB_565, downsampled to [maxHeightPx]) so scrolling does not decode
 * again. The thumbnails reach the cache through [preload], which the sync runner starts after each run (never from a
 * screen): one download per picture not cached yet, straight from the API, mobile data allowed (300 KB cap, docs/04).
 */
class SkuPackshots(
    private val images: ImageCache?,
    private val devAssets: AssetManager?,
    /** The SKU as the local bundle has it (its code and thumbnail reference), or null (unknown SKU, no user signed in). */
    private val lookup: suspend (skuId: Long) -> SkuInfo? = { null },
    memoryBytes: Int = DEFAULT_MEMORY_BYTES,
    private val maxHeightPx: Int = DEFAULT_MAX_HEIGHT_PX,
) {
    /** The admin image of a SKU as the bundle carries it (`Sku.image_asset_id`). */
    data class SkuImageRef(val assetId: String)

    /** What the picture needs of a SKU: its code (the dev art) and its admin image, if any. */
    data class SkuInfo(val code: String, val image: SkuImageRef? = null)

    private val memory = object : LruCache<String, Bitmap>(memoryBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val devIndex: Map<String, String> by lazy { readDevIndex() }

    /** From memory only (no disk, no suspension): the row's first frame. */
    fun cached(skuId: String): Bitmap? = memory.get(skuId)

    /** The picture of the SKU from disk or the dev assets, or null. */
    suspend fun bitmap(skuId: String): Bitmap? {
        memory.get(skuId)?.let { return it }
        val decoded = try {
            val info = skuId.toLongOrNull()?.let { lookup(it) } ?: SkuInfo(skuId).takeIf { skuId.toLongOrNull() == null }
            info?.let { fromCache(it.image) ?: fromDevAssets(it.code) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null // a broken or evicted file is the silhouette, never an error
        } ?: return null
        memory.put(skuId, decoded)
        return decoded
    }

    /** Drops the decoded pictures (a new bundle brought new versions). */
    fun invalidate() = memory.evictAll()

    private val preloading = kotlinx.coroutines.sync.Mutex()

    /**
     * Downloads the pictures of [pictured] (SKU id to asset id) that are not cached yet: [download] reads the bytes from
     * the API (`GET /v1/sku-images/{asset_id}`; null: refused or offline, tried again after the next sync), stored under
     * the asset id. At most [MAX_PRELOAD] per run; one run at a time. Returns the pictures stored; never throws. A stored
     * SKU's decoded picture is dropped so the dev stand-in gives way to the real one.
     */
    suspend fun preload(pictured: List<Pair<Long, String>>, download: suspend (assetId: String) -> ByteArray?): Int {
        val cache = images ?: return 0
        if (!preloading.tryLock()) return 0
        return try {
            var fetched = 0
            for ((skuId, assetId) in pictured.distinctBy { it.second }.take(MAX_PRELOAD)) {
                val key = cacheKey(assetId)
                if (cache.get(key, key) != null) continue
                val bytes = try { download(assetId) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null } ?: continue
                if (cache.store(key, bytes) != null) {
                    fetched++
                    pictured.filter { it.second == assetId }.forEach { memory.remove(it.first.toString()) }
                }
            }
            fetched
        } finally {
            preloading.unlock()
        }
    }

    private suspend fun fromCache(ref: SkuImageRef?): Bitmap? {
        val cache = images ?: return null
        if (ref == null) return null
        val file = cache.get(cacheKey(ref.assetId), cacheKey(ref.assetId)) ?: return null
        return withContext(Dispatchers.IO) { decodeFile(file) }
    }

    private suspend fun fromDevAssets(code: String): Bitmap? {
        val assets = devAssets ?: return null
        val art = devIndex[code.trim().lowercase()] ?: return null
        return withContext(Dispatchers.IO) {
            assets.open("$DEV_DIR/$art.webp").use { BitmapFactory.decodeStream(it, null, opts(1)) }
        }
    }

    private fun decodeFile(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outHeight <= 0) return null
        return BitmapFactory.decodeFile(file.path, opts(sampleSize(bounds.outHeight, maxHeightPx)))
    }

    private fun opts(sample: Int) = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }

    /** `index.txt`: one `SKU-CODE=art` per line, `#` comments; codes match case-insensitively. */
    private fun readDevIndex(): Map<String, String> {
        val assets = devAssets ?: return emptyMap()
        return runCatching {
            assets.open("$DEV_DIR/index.txt").bufferedReader().useLines { lines -> parseDevIndex(lines.toList()) }
        }.getOrDefault(emptyMap())
    }

    companion object {
        /** About 60 pack pictures at 146 x 237 RGB_565 (about 70 KB each). */
        const val DEFAULT_MEMORY_BYTES = 4 * 1024 * 1024
        /** 120 dp at xxhdpi plus a margin; a bigger upload is downsampled on decode. */
        const val DEFAULT_MAX_HEIGHT_PX = 400
        const val DEV_DIR = "dev-packshots"

        /** Pictures fetched per sync run at most (the pilot catalogue has about 40 SKUs). */
        const val MAX_PRELOAD = 100

        /** The stable cache key of an admin SKU image: the same picture under a new signed URL is not fetched again. */
        fun cacheKey(assetId: String): String = "sku-image:" + assetId.lowercase()

        /** The largest power of two that keeps the decoded height at or above [target]. */
        fun sampleSize(height: Int, target: Int): Int {
            var s = 1
            while (height / (s * 2) >= target) s *= 2
            return s
        }

        fun parseDevIndex(lines: List<String>): Map<String, String> = lines.asSequence()
            .map { it.substringBefore('#').trim() }
            .filter { '=' in it }
            .associate { it.substringBefore('=').trim().lowercase() to it.substringAfter('=').trim() }
            .filter { (k, v) -> k.isNotEmpty() && Regex("^[a-z0-9-]+$").matches(v) }
    }
}

/**
 * The sync runner's hook for the pack pictures (PARITY-SPEC s2.6): after each run, the user's pictured SKUs from the
 * local bundle are preloaded in the background (the run is done and the network was just up). Never on a screen's path.
 */
class PackshotPreload(
    private val packshots: SkuPackshots,
    private val databases: com.aktcl.aron.core.database.UserDatabases,
    private val syncApi: com.aktcl.aron.core.network.SyncApi,
) {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    fun afterSync(userId: Long) {
        scope.launch {
            try {
                if (!databases.exists(userId)) return@launch
                val pictured = databases.of(userId).referenceDao().skuImages().map { it.skuId to it.assetId }
                packshots.preload(pictured) { id -> (syncApi.skuImage(id) as? com.aktcl.aron.core.network.ApiResult.Success)?.value }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        }
    }
}
