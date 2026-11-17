package com.aktcl.aron.feature.sale.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronLook
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronMinus
import com.aktcl.aron.core.ui.aron.AronPlus
import com.aktcl.aron.core.ui.aron.PackCategory
import com.aktcl.aron.core.ui.aron.PackshotImage

object AmoSkuCardTags {
    fun card(skuId: Long) = "amo_sku_$skuId"
    fun rate(skuId: Long) = "amo_sku_rate_$skuId"
    fun discount(skuId: Long) = "amo_sku_discount_$skuId"
    fun stock(skuId: Long) = "amo_sku_stock_$skuId"
}

/**
 * The AMO sale entry's SKU card (owner screenshots 23, 24, 26; D-PAR-AMO-07): the packshot with a round badge on its lower right, the code, minus,
 * the quantity and plus, and at the right a column of three figures: the rate (cart), the discount (always "0%" while programme discounts
 * are deferred: nothing is invented) and the stock. [stock] is the figure the stock gate uses (the same text the SR card shows). The shared
 * core-ui PackCard is not touched: this card is built here from its public parts (PackshotImage, AronMinus, AronPlus).
 */
@Composable
internal fun AmoSkuCard(
    skuId: Long, code: String, caption: String?, quantity: String, onQuantityChange: (String) -> Unit, onMinus: () -> Unit, onPlus: () -> Unit,
    rate: String?, discount: String, stock: String, badge: String, minusLabel: String, plusLabel: String, quantityLabel: String,
    category: PackCategory, minusEnabled: Boolean, plusEnabled: Boolean, rateLabel: String, discountLabel: String, stockLabel: String,
    modifier: Modifier = Modifier,
) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(AronLook.CardRadius)
    val narrow = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 400 // a 360 dp phone: slimmer packshot and quantity box so the code keeps a line
    val blue = look.choiceFill ?: look.purple // the old rate and badge are blue: the AMO navy role (contrast-checked)
    Row(
        modifier.fillMaxWidth().testTag(AmoSkuCardTags.card(skuId)).shadow(2.dp, shape).clip(shape).background(look.card).padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(end = AronLook.PackBadge / 2)) {
            PackshotImage(skuId.toString(), Modifier.size(if (narrow) 40.dp else AronLook.PackshotWidth, AronLook.PackshotHeight), null, category)
            Box(
                Modifier.align(Alignment.BottomEnd).offset(x = AronLook.PackBadge / 2, y = (-6).dp).sizeIn(minWidth = AronLook.PackBadge, minHeight = AronLook.PackBadge).clip(CircleShape).background(blue),
                contentAlignment = Alignment.Center,
            ) { Text(badge, style = MaterialTheme.typography.labelMedium, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = AronTokens.Space.Xs)) }
        }
        Spacer(Modifier.width(AronTokens.Space.Xs))
        Column(Modifier.weight(1f)) {
            Text(code, style = if (narrow) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = look.purple, maxLines = 2)
            if (caption != null && !narrow) Text(caption, style = MaterialTheme.typography.bodySmall, color = look.inkMuted) // the rate is in the column on a narrow phone
        }
        AronMinus(onMinus, minusLabel, enabled = minusEnabled)
        BasicTextField(
            value = quantity, onValueChange = { onQuantityChange(it.filter(Char::isDigit).take(6)) }, singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge.copy(color = look.ink, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold),
            cursorBrush = SolidColor(look.purple), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(if (narrow) 30.dp else AronTokens.Touch.Min).heightIn(min = AronTokens.Touch.Min).semantics { contentDescription = quantityLabel },
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = AronTokens.Touch.Min).drawBehind {
                        val y = size.height - 4.dp.toPx(); drawLine(look.underline, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx())
                    },
                    contentAlignment = Alignment.Center,
                ) { inner() }
            },
        )
        AronPlus(onPlus, plusLabel, enabled = plusEnabled)
        Column(Modifier.padding(start = AronTokens.Space.Xs), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
            if (rate != null) FigureLine(AronIcons.Sale, rate, blue, rateLabel, Modifier.testTag(AmoSkuCardTags.rate(skuId)))
            FigureLine(AronIcons.Save, discount, look.amber, discountLabel, Modifier.testTag(AmoSkuCardTags.discount(skuId)))
            FigureLine(AronIcons.Stock, stock, look.ink, stockLabel, Modifier.testTag(AmoSkuCardTags.stock(skuId)))
        }
    }
}

@Composable
private fun FigureLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tint: androidx.compose.ui.graphics.Color, label: String, modifier: Modifier) {
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = "$label $text" }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(AronTokens.Space.L))
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = tint, maxLines = 1)
    }
}
