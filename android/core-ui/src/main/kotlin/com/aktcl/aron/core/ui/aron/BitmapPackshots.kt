package com.aktcl.aron.core.ui.aron

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter

/**
 * A [PackshotProvider] over plain bitmaps (lane ui-packshots, PARITY-SPEC s2.6): [cached] answers from memory on the first
 * frame (a row scrolling back in never flashes the silhouette), [load] reads disk or bundled assets off the main thread and
 * the row recomposes when it lands. Both must never touch the network; a throw is treated as "no picture" (the silhouette).
 * The field apps build it over core-sync `SkuPackshots`; tests build it over fixtures.
 */
class BitmapPackshotProvider(
    private val load: suspend (skuId: String) -> Bitmap?,
    private val cached: (skuId: String) -> Bitmap? = { null },
) : PackshotProvider {
    @Composable
    override fun painter(skuId: String): Painter? {
        val first = remember(skuId) { runCatching { cached(skuId) }.getOrNull() }
        val bitmap by produceState(first, skuId) {
            if (value == null) {
                value = try {
                    load(skuId)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
        }
        return remember(bitmap) { bitmap?.takeUnless { it.isRecycled }?.let { BitmapPainter(it.asImageBitmap()) } }
    }
}

/** The silhouette glyph of a bundle `category_code` (cigarette, bidi, lighter, match); anything else draws the cigarette pack. */
fun packCategoryOf(categoryCode: String): PackCategory = when (categoryCode.lowercase()) {
    "bidi" -> PackCategory.Bidi
    "lighter" -> PackCategory.Lighter
    "match" -> PackCategory.Match
    else -> PackCategory.Cigarette
}
