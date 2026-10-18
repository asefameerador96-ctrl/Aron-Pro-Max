package com.aktcl.aron.core.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * The SKU a pack picture is asked for (PARITY-SPEC s2.6, stock.md UI-SR-19): the server id, the SKU code (the dev
 * fallback keys on it) and the category (the silhouette's glyph).
 */
@Immutable
data class PackshotSku(val skuId: Long, val code: String, val categoryCode: String)

/**
 * Resolves a SKU to its pack picture (PARITY-SPEC s2.6). [load] reads only what is on the phone (the bounded thumbnail
 * cache, a bundled dev picture) and never the network, never throws, and returns null when there is no picture: the row
 * then shows the neutral pack silhouette. [cached] answers from memory without suspending, so a row that scrolls back
 * into view shows its picture on the first frame (no silhouette flash). A sale never waits on either.
 */
fun interface PackshotProvider {
    suspend fun load(sku: PackshotSku): Bitmap?

    fun cached(sku: PackshotSku): Bitmap? = null

    companion object {
        /** No pictures (previews, tests, a build without the cache): every SKU shows the silhouette. */
        val None: PackshotProvider = PackshotProvider { null }
    }
}

/** The app's provider; each field app sets it once above its screens (MainActivity). */
val LocalPackshotProvider = staticCompositionLocalOf { PackshotProvider.None }

/** The pack picture's corner (PARITY-SPEC s2.5: 4 dp). */
private val PackshotRadius = 4.dp

/** Test tags: which of the two a row shows. */
object PackshotTags {
    const val IMAGE = "packshot:image"
    const val SILHOUETTE = "packshot:silhouette"
}

/**
 * The pack picture of [sku] filling [modifier]'s size (the caller sizes it: about 68 x 120 dp in the Stock and Sale
 * cards, PARITY-SPEC s2.5), cropped to fill, 4 dp corners. Without a picture: a neutral pack silhouette with the
 * category's glyph, never a letter circle and never a spinner. Decorative: the row's own description names the SKU.
 */
@Composable
fun PackshotImage(sku: PackshotSku, modifier: Modifier = Modifier) {
    val provider = LocalPackshotProvider.current
    val first = remember(sku, provider) { runCatching { provider.cached(sku) }.getOrNull() }
    val bitmap by produceState(first, sku, provider) {
        if (value == null) value = runCatching { provider.load(sku) }.getOrNull()
    }
    val shape = RoundedCornerShape(PackshotRadius)
    val image = remember(bitmap) { bitmap?.takeUnless { it.isRecycled }?.asImageBitmap() }
    if (image != null) {
        Image(
            image, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape).clearAndSetSemantics { testTag = PackshotTags.IMAGE },
        )
    } else {
        PackSilhouette(sku.categoryCode, modifier)
    }
}

/** The neutral pack: a grey box with a lid line and the category glyph in the middle (PARITY-SPEC s2.6). */
@Composable
fun PackSilhouette(categoryCode: String, modifier: Modifier = Modifier) {
    val c = LocalAronColors.current
    val fill = c.surfaceSolidRaised
    val ink = c.borderInput
    Box(
        modifier.clearAndSetSemantics { testTag = PackshotTags.SILHOUETTE }.drawBehind {
            val r = CornerRadius(PackshotRadius.toPx())
            val w = 1.5.dp.toPx()
            drawRoundRect(fill, cornerRadius = r)
            drawRoundRect(ink, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(r.x - w / 2), style = Stroke(w))
            val lid = size.height * 0.22f
            drawLine(ink, Offset(0f, lid), Offset(size.width, lid), w)
            drawGlyph(categoryCode, ink, Offset(size.width / 2, lid + (size.height - lid) / 2), minOf(size.width, size.height - lid) * 0.6f)
        },
    )
}

/** One line-art glyph per category, [d] wide, centred on [c]: a cigarette, a bidi, a lighter or a matchbox. */
private fun DrawScope.drawGlyph(category: String, ink: androidx.compose.ui.graphics.Color, c: Offset, d: Float) {
    val w = maxOf(1.5.dp.toPx(), d * 0.06f)
    val line = Stroke(w)
    when (category) {
        "cigarette" -> {
            val h = d * 0.22f
            val tl = Offset(c.x - d / 2, c.y - h / 2)
            drawRoundRect(ink, tl, Size(d, h), CornerRadius(h / 4), style = line)
            drawRect(ink, tl, Size(d * 0.3f, h)) // the filter
        }
        "bidi" -> {
            val p = Path().apply {
                moveTo(c.x - d / 2, c.y - d * 0.07f); lineTo(c.x + d / 2, c.y - d * 0.13f)
                lineTo(c.x + d / 2, c.y + d * 0.13f); lineTo(c.x - d / 2, c.y + d * 0.07f); close()
            }
            drawPath(p, ink, style = line)
            drawLine(ink, Offset(c.x - d * 0.2f, c.y - d * 0.1f), Offset(c.x - d * 0.2f, c.y + d * 0.1f), w) // the thread
        }
        "lighter" -> {
            val bw = d * 0.42f; val bh = d * 0.7f
            val tl = Offset(c.x - bw / 2, c.y - bh / 2 + d * 0.12f)
            drawRoundRect(ink, tl, Size(bw, bh), CornerRadius(bw / 5), style = line)
            drawRect(ink, Offset(tl.x, tl.y - d * 0.1f), Size(bw * 0.6f, d * 0.1f)) // the cap
            drawCircle(ink, d * 0.08f, Offset(c.x + bw * 0.1f, tl.y - d * 0.22f)) // the flame
        }
        else -> { // match: a matchbox with one stick
            val bw = d * 0.8f; val bh = d * 0.45f
            val tl = Offset(c.x - bw / 2, c.y - bh / 2 + d * 0.1f)
            drawRoundRect(ink, tl, Size(bw, bh), CornerRadius(w), style = line)
            drawLine(ink, Offset(c.x - bw * 0.1f, tl.y), Offset(c.x + bw * 0.25f, tl.y - d * 0.32f), w)
            drawCircle(ink, d * 0.07f, Offset(c.x + bw * 0.27f, tl.y - d * 0.35f)) // the head
        }
    }
}
