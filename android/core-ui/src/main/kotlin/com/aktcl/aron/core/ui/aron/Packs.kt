package com.aktcl.aron.core.ui.aron

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.AronLook
import com.aktcl.aron.core.ui.LocalAronLook

/** The SKU category, for the silhouette glyph and the totals rows (cigarettes in sticks, bidi, lighters in pieces, matches in dozens). */
enum class PackCategory { Cigarette, Bidi, Lighter, Match }

/**
 * Where packshots come from (PARITY-SPEC s2.6). The ui-packshots lane implements it over the ImageCache (bounded LRU, thumbnails) and
 * provides it once at the app root with `CompositionLocalProvider(LocalPackshotProvider provides ...)`. [painter] is composable so an
 * implementation can load off the main thread (`produceState`) and recompose when the thumbnail lands; it returns null while nothing is
 * cached, and must never block, throw or show a spinner.
 */
interface PackshotProvider {
    @Composable
    fun painter(skuId: String): Painter?
}

/** The default: no packshots, every pack shows the neutral silhouette. */
object NoPackshots : PackshotProvider {
    @Composable
    override fun painter(skuId: String): Painter? = null
}

val LocalPackshotProvider = staticCompositionLocalOf<PackshotProvider> { NoPackshots }

/**
 * The pack picture of [skuId] (PARITY-SPEC s2.6): the cached thumbnail, cropped to fill, 4 dp radius; while none is cached, a neutral
 * pack silhouette in light grey with the [category] glyph (never a letter circle, never a spinner). [contentDescription] is its
 * TalkBack text (null: decorative, the row already reads the SKU code).
 */
@Composable
fun PackshotImage(skuId: String, modifier: Modifier = Modifier, contentDescription: String? = null, category: PackCategory = PackCategory.Cigarette) {
    val painter = LocalPackshotProvider.current.painter(skuId)
    val shape = RoundedCornerShape(4.dp)
    if (painter != null) {
        Image(painter, contentDescription, modifier.clip(shape), contentScale = ContentScale.Crop)
    } else {
        val look = LocalAronLook.current
        Canvas(modifier.clip(shape).background(look.silhouette).decorative(contentDescription)) { silhouette(category, look.silhouetteInk) }
    }
}

private fun DrawScope.silhouette(category: PackCategory, ink: Color) {
    val w = size.width
    val h = size.height
    val line = w * 0.05f
    when (category) {
        PackCategory.Cigarette -> {
            // a flip-top pack: body, lid line, two filter tips standing out of the top
            drawRoundRect(ink.copy(alpha = 0.35f), Offset(w * 0.18f, h * 0.30f), Size(w * 0.64f, h * 0.52f), CornerRadius(w * 0.06f))
            drawLine(ink, Offset(w * 0.18f, h * 0.42f), Offset(w * 0.82f, h * 0.42f), line)
            drawRoundRect(ink, Offset(w * 0.32f, h * 0.18f), Size(w * 0.12f, h * 0.14f), CornerRadius(w * 0.03f))
            drawRoundRect(ink, Offset(w * 0.52f, h * 0.20f), Size(w * 0.12f, h * 0.12f), CornerRadius(w * 0.03f))
        }
        PackCategory.Bidi -> {
            // a tied bundle of sticks
            for (i in 0 until 5) drawLine(ink, Offset(w * (0.28f + i * 0.11f), h * 0.22f), Offset(w * (0.24f + i * 0.13f), h * 0.80f), line * 1.4f)
            drawLine(ink.copy(alpha = 0.6f), Offset(w * 0.20f, h * 0.52f), Offset(w * 0.82f, h * 0.52f), line * 1.6f)
        }
        PackCategory.Lighter -> {
            drawRoundRect(ink.copy(alpha = 0.35f), Offset(w * 0.30f, h * 0.34f), Size(w * 0.40f, h * 0.48f), CornerRadius(w * 0.08f))
            drawRoundRect(ink, Offset(w * 0.30f, h * 0.24f), Size(w * 0.40f, h * 0.10f), CornerRadius(w * 0.03f))
            drawCircle(ink, w * 0.06f, Offset(w * 0.42f, h * 0.18f))
        }
        PackCategory.Match -> {
            drawRoundRect(ink.copy(alpha = 0.35f), Offset(w * 0.14f, h * 0.44f), Size(w * 0.72f, h * 0.30f), CornerRadius(w * 0.05f))
            drawLine(ink, Offset(w * 0.30f, h * 0.40f), Offset(w * 0.70f, h * 0.22f), line * 1.2f)
            drawCircle(ink, w * 0.07f, Offset(w * 0.72f, h * 0.21f))
        }
    }
}

/** Widths shared by [PackCard] and [PackListHeader] so the column headers sit over their columns. */
private val StockColumn = 40.dp
private val QtyField = 48.dp
private val QtyColumn = AronLook.Touch * 2 + QtyField

/**
 * The column headers above a pack list (sr/stock.webp: `এসকেইউ` / `ইস্যু` / `স্টক`, grey 20 sp). [quantity] is the issue column on
 * Stock and the sale column on Sale.
 */
@Composable
fun PackListHeader(sku: String, quantity: String, stock: String, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(sku, style = lookStyle(20.sp, color = look.columnHeader), modifier = Modifier.weight(1f).padding(start = 16.dp))
        Text(quantity, style = lookStyle(20.sp, color = look.columnHeader, align = TextAlign.Center), modifier = Modifier.width(QtyColumn))
        Text(stock, style = lookStyle(20.sp, color = look.columnHeader, align = TextAlign.End), modifier = Modifier.width(StockColumn))
    }
}

/**
 * One pack row of Stock, Sale and the review (PARITY-SPEC s2.5, sr/stock.webp): a white 12 dp card with the [PackshotImage] at the
 * start and the purple round [packBadge] overlapping its bottom end, the [skuCode] in purple 18 sp bold, the red [AronMinus], the
 * underlined [quantity] field (tap to type; [onQuantityChange] gets digits only, Bangla digits included, at most 6), the purple
 * [AronPlus], and the [stock] figure at the end. Strings come resolved and localised (digits too) from the caller.
 */
@Composable
fun PackCard(
    skuId: String,
    skuCode: String,
    quantity: String,
    onQuantityChange: (String) -> Unit,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    stock: String,
    minusLabel: String,
    plusLabel: String,
    quantityLabel: String,
    modifier: Modifier = Modifier,
    packBadge: String? = null,
    packshotDescription: String? = null,
    category: PackCategory = PackCategory.Cigarette,
    minusEnabled: Boolean = true,
    plusEnabled: Boolean = true,
    editable: Boolean = true,
) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(AronLook.CardRadius)
    Row(
        modifier
            .fillMaxWidth()
            .shadow(2.dp, shape)
            .clip(shape)
            .background(look.card)
            .padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(end = AronLook.PackBadge / 2)) {
            PackshotImage(skuId, Modifier.size(AronLook.PackshotWidth, AronLook.PackshotHeight), packshotDescription, category)
            if (packBadge != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = AronLook.PackBadge / 2, y = (-6).dp)
                        .sizeIn(minWidth = AronLook.PackBadge, minHeight = AronLook.PackBadge)
                        .clip(CircleShape)
                        .background(look.purple),
                    contentAlignment = Alignment.Center,
                ) { Text(packBadge, style = lookStyle(12.sp, bold = true, color = Color.White), modifier = Modifier.padding(horizontal = 4.dp)) }
            }
        }
        Spacer(Modifier.width(6.dp))
        // one line, shrinking from 18 sp to 12 sp before it wraps (sr/stock.webp shows the code on one line); large text may wrap to two
        val wide = LocalDensity.current.fontScale >= 1.15f
        Text(
            skuCode, style = lookStyle(18.sp, bold = true, color = look.purple), modifier = Modifier.weight(1f),
            maxLines = if (wide) 2 else 1, softWrap = wide,
            autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 18.sp, stepSize = 1.sp),
        )
        AronMinus(onMinus, minusLabel, enabled = minusEnabled)
        BasicTextField(
            value = quantity,
            onValueChange = { typed -> onQuantityChange(typed.filter(Char::isDigit).take(6)) },
            enabled = editable,
            singleLine = true,
            textStyle = lookStyle(20.sp, color = look.ink, align = TextAlign.Center),
            cursorBrush = SolidColor(look.purple),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .width(QtyField)
                .heightIn(min = AronLook.Touch)
                .semantics { contentDescription = quantityLabel },
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = AronLook.Touch).drawBehind {
                        val y = size.height - 4.dp.toPx()
                        drawLine(look.underline, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx())
                    },
                    contentAlignment = Alignment.Center,
                ) { inner() }
            },
        )
        AronPlus(onPlus, plusLabel, enabled = plusEnabled)
        Text(stock, style = lookStyle(18.sp, color = look.ink, align = TextAlign.End), modifier = Modifier.width(StockColumn))
    }
}
