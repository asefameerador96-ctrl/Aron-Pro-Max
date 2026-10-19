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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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

/**
 * The ONE pack badge (sr/stock.webp, UI-SR-18): a solid `aron.purple` circle with a white bold figure, overlapping the packshot's
 * bottom-end corner. [PackCard] places it; use it wherever a packshot carries the packs figure, never a tinted or pill badge.
 */
@Composable
fun PackBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.sizeIn(minWidth = AronLook.PackBadge, minHeight = AronLook.PackBadge).clip(CircleShape).background(LocalAronLook.current.purple),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = lookStyle(12.sp, bold = true, color = Color.White), modifier = Modifier.padding(horizontal = 4.dp)) }
}

/** Widths shared by [PackCard] and [PackListHeader] so the column headers sit over their columns. */
/** 52 dp: a 5-digit stick count with its separator (`১২,০০০`) and the `স্টক` header fit on one line, shrinking to 12 sp at most. */
private val StockColumn = 52.dp
private val QtyField = 48.dp

/**
 * Column widths follow the SCREEN width (not the row's), so [PackListHeader] and [PackCard] agree whatever padding the caller puts
 * around either. The quantity field grows (sr/stock.webp: the underline fills the space between minus and plus): 48 dp on a 360 dp
 * phone, 75 dp at 411 dp, at most 96 dp, leaving an 18 sp SKU code (about 80 dp) its room.
 */
private fun qtyFieldFor(screen: Dp): Dp = (screen - 336.dp).coerceIn(QtyField, 96.dp)

/**
 * Below 400 dp of screen (360 dp phones) the packshot and the stock column give up 12 and 8 dp so an 18 sp SKU code such as
 * `MaxR-10S` stays on one line as on sr/stock.webp (it shrinks a little, wrapping is only the fallback); 320 dp still fits the plus.
 */
private fun isNarrowScreen(screen: Dp): Boolean = screen < 400.dp
private fun stockColumnFor(screen: Dp): Dp = if (isNarrowScreen(screen)) 44.dp else StockColumn
private fun packshotWidthFor(screen: Dp): Dp = if (isNarrowScreen(screen)) 44.dp else AronLook.PackshotWidth

@Composable
private fun screenWidth(): Dp = LocalConfiguration.current.screenWidthDp.dp

/**
 * The column headers above a pack list (sr/stock.webp: `এসকেইউ` / `ইস্যু` / `স্টক`, grey 20 sp). [quantity] is the issue column on
 * Stock and the sale column on Sale.
 */
@Composable
fun PackListHeader(sku: String, quantity: String, stock: String, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    val screen = screenWidth()
    Row(modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        FitText(sku, lookStyle(20.sp, color = look.columnHeader), 12.sp, Modifier.weight(1f).padding(start = 16.dp), oneLineDown = 12.sp)
        FitText(quantity, lookStyle(20.sp, color = look.columnHeader, align = TextAlign.Center), 12.sp, Modifier.width(AronLook.Touch * 2 + qtyFieldFor(screen)), oneLineDown = 12.sp)
        FitText(stock, lookStyle(20.sp, color = look.columnHeader, align = TextAlign.End), 12.sp, Modifier.width(stockColumnFor(screen)), oneLineDown = 12.sp)
    }
}

/**
 * One pack row of Stock, Sale and the review (PARITY-SPEC s2.5, sr/stock.webp): a white 12 dp card with the [PackshotImage] at the
 * start and the purple round [packBadge] overlapping its bottom end, the [skuCode] in purple 18 sp medium (the reference weight), the red [AronMinus], the
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
    val screen = screenWidth()
    val qtyField = qtyFieldFor(screen)
    val narrow = isNarrowScreen(screen)
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
            PackshotImage(skuId, Modifier.size(packshotWidthFor(screen), AronLook.PackshotHeight), packshotDescription, category)
            if (packBadge != null) PackBadge(packBadge, Modifier.align(Alignment.BottomEnd).offset(x = AronLook.PackBadge / 2, y = (-6).dp))
        }
        Spacer(Modifier.width(if (narrow) 2.dp else 6.dp))
        // one line, shrinking from 18 sp down to 14 sp (sr/stock.webp shows the code on one line); a longer name such as "Prime Gold
        // 10S" then wraps between words, each word shrinking to at most 12 sp: no code is ever cut
        FitText(skuCode, lookStyle(18.sp, medium = true, color = look.purple), 12.sp, Modifier.weight(1f), oneLineDown = 14.sp)
        AronMinus(onMinus, minusLabel, enabled = minusEnabled)
        BasicTextField(
            value = quantity,
            onValueChange = { typed -> onQuantityChange(typed.filter(Char::isDigit).take(6)) },
            enabled = editable,
            singleLine = true,
            textStyle = lookStyle(20.sp, bold = true, color = look.ink, align = TextAlign.Center),
            cursorBrush = SolidColor(look.purple),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .width(qtyField)
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
        FitText(stock, lookStyle(18.sp, bold = true, color = look.ink, align = TextAlign.End), 12.sp, Modifier.width(stockColumnFor(screen)), oneLineDown = 12.sp)
    }
}
