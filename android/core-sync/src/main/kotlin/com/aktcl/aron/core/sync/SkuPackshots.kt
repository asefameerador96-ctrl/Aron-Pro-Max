package com.aktcl.aron.core.sync

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pack pictures of the Stock and Sale rows (PARITY-SPEC s2.6, stock.md UI-SR-19, docs/04): resolves a SKU to a small
 * decoded bitmap from what is already on the phone. The key is the kit's `PackshotImage(skuId)` string: the server SKU id
 * (looked up in the user's local catalogue with [lookup]) or, when it is not a number, the SKU code itself. In this order:
 *  1. the admin-managed thumbnail in the bounded [ImageCache], under the stable key [cacheKey] (image asset id and
 *     version, never the signed URL, which changes while the picture does not);
 *  2. DEV builds only ([devAssets] non-null, set when BuildConfig.DEV_PACKSHOTS): a picture cropped from the owner's
 *     reference screenshot, bundled under `assets/dev-packshots/` and mapped by SKU code in its `index.txt`.
 * Never the network, never throws; null means "no picture" and the row shows the silhouette. Decoded bitmaps sit in a
 * small memory LRU ([memoryBytes], RGB_565, downsampled to [maxHeightPx]) so scrolling does not decode again.
 */
class SkuPackshots(
    private val images: ImageCache?,
    private val devAssets: AssetManager?,
    /** The SKU as the local bundle has it (its code and thumbnail reference), or null (unknown SKU, no user signed in). */
    private val lookup: suspend (skuId: Long) -> SkuInfo? = { null },
    memoryBytes: Int = DEFAULT_MEMORY_BYTES,
    private val maxHeightPx: Int = DEFAULT_MAX_HEIGHT_PX,
) {
    /** The admin image of a SKU as the bundle carries it: where to fetch it and which version it is. */
    data class SkuImageRef(val url: String, val assetId: String, val version: Int)

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

    private suspend fun fromCache(ref: SkuImageRef?): Bitmap? {
        val cache = images ?: return null
        if (ref == null) return null
        val file = cache.get(ref.url, cacheKey(ref.assetId, ref.version)) ?: return null
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

        /** The stable cache key of an admin SKU image: the same picture under a new signed URL is not fetched again. */
        fun cacheKey(assetId: String, version: Int): String = "sku-image:$assetId:v$version"

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
