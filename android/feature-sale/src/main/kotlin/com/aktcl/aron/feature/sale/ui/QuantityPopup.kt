package com.aktcl.aron.feature.sale.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronLook
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronMinus
import com.aktcl.aron.core.ui.aron.AronPlus
import com.aktcl.aron.core.ui.aron.PackBadge
import com.aktcl.aron.core.ui.aron.PackshotImage
import com.aktcl.aron.core.ui.entryUnitLabel
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.feature.sale.R
import com.aktcl.aron.feature.sale.domain.QuickSteps
import com.aktcl.aron.feature.sale.domain.SaleSku
import com.aktcl.aron.feature.sale.domain.SkuStats

object QtyPopupTags {
    const val POPUP = "qty_popup"
    const val FIELD = "qty_popup_field"
    const val SAVE = "qty_popup_save"
    const val CLOSE = "qty_popup_close"
    const val PREV = "qty_popup_prev"
    const val ADS = "qty_popup_ads"
    const val SDLW = "qty_popup_sdlw"
    fun plus(n: Long) = "qty_popup_plus_$n"
    fun minus(n: Long) = "qty_popup_minus_$n"
}

/**
 * The quantity popup (SR manual s8 / 22, D-SR-MAN-S4-05): the SKU's picture with its packs badge, a -/+ stepper around the typed quantity,
 * the quick buttons (n on the left, -n on the right, in the entry unit: packets, boxes or dozens, rule 5), the আগের দিন / ADS / SDLW
 * panel, and সংরক্ষণ. The quantity is edited locally and reaches the sale only on সংরক্ষণ, so the red close drops a half-typed number.
 * Quick buttons clamp to 0 and [MaxLineQty]; the figures are whole units, integer math only. Stats unknown or kept in another unit read "N/A".
 */
@Composable
fun QuantityPopup(sku: SaleSku, qty: Long, stats: SkuStats?, onSave: (Long) -> Unit, onClose: () -> Unit) {
    val look = LocalAronLook.current
    val c = com.aktcl.aron.core.ui.LocalAronColors.current
    var value by rememberSaveable { mutableLongStateOf(qty) }
    val unit = entryUnitLabel(sku.entryLabelKey)
    val name = skuDisplayName(sku)
    val usable = stats?.takeIf { it.unit == sku.entry.unit.wire }
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().testTag(QtyPopupTags.POPUP).clip(RoundedCornerShape(AronLook.CardRadius + 4.dp)).background(c.accentContainer)
                .heightIn(max = 640.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Icon(
                    AronIcons.Close, contentDescription = stringResource(R.string.qty_popup_close), tint = look.red,
                    modifier = Modifier.size(32.dp).clickable(role = Role.Button, onClick = onClose).testTag(QtyPopupTags.CLOSE),
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    PackshotImage(sku.skuId.toString(), Modifier.size(64.dp, 56.dp), null, packCategoryOf(sku.categoryCode))
                    if (sku.packFactor > 1 && sku.entry.unit != com.aktcl.aron.rules.QtyUnit.PACK) PackBadge(localizedNumber(sku.packFactor.toLong()), Modifier.align(Alignment.BottomEnd))
                }
                Text(sku.code, color = look.purple, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                AronMinus({ value = (value - 1).coerceAtLeast(0) }, stringResource(R.string.sale_less) + ", " + name, enabled = value > 0)
                BasicTextField(
                    value = if (value == 0L) "" else localizedNumber(value),
                    onValueChange = { typed -> value = typed.filter(Char::isDigit).take(6).fold(0L) { a, ch -> a * 10 + Character.digit(ch, 10) }.coerceAtMost(MaxLineQty) },
                    singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = look.ink, textAlign = TextAlign.Center),
                    cursorBrush = SolidColor(look.purple), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(72.dp).heightIn(min = AronLook.Touch).testTag(QtyPopupTags.FIELD)
                        .semantics { contentDescription = name + ", " + unit },
                    decorationBox = { inner -> Box(Modifier.fillMaxWidth().heightIn(min = AronLook.Touch), contentAlignment = Alignment.Center) { inner() } },
                )
                AronPlus({ value = (value + 1).coerceAtMost(MaxLineQty) }, stringResource(R.string.sale_more) + ", " + name, enabled = value < MaxLineQty)
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(listOf(look.purple, look.red))).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuickSteps.forCategory(sku.categoryCode).forEach { n ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        QuickButton(localizedNumber(n), Modifier.weight(1f).testTag(QtyPopupTags.plus(n))) { value = (value + n).coerceAtMost(MaxLineQty) }
                        QuickButton("-" + localizedNumber(n), Modifier.weight(1f).testTag(QtyPopupTags.minus(n))) { value = (value - n).coerceAtLeast(0) }
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SolidColor(c.warningContainer)).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                StatLine(stringResource(R.string.qty_stat_prev), usable?.previousDay?.let { localizedNumber(it) }, QtyPopupTags.PREV)
                StatLine(stringResource(R.string.qty_stat_ads), usable?.adsHundredths?.let { hundredths(it) }, QtyPopupTags.ADS)
                StatLine(stringResource(R.string.qty_stat_sdlw), usable?.sdlw?.let { localizedNumber(it) }, QtyPopupTags.SDLW)
            }
            // the kit's primary button pairs its own ink with its fill in every look (Sunlight included)
            com.aktcl.aron.core.ui.aron.AronPrimaryButton(
                stringResource(R.string.qty_popup_save), { onSave(value) }, Modifier.align(Alignment.CenterHorizontally).testTag(QtyPopupTags.SAVE),
                glyph = com.aktcl.aron.core.ui.aron.ButtonGlyph.Save,
            )
            Spacer(Modifier.size(2.dp))
        }
    }
}

@Composable
private fun QuickButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = AronLook.Touch).clip(RoundedCornerShape(8.dp)).background(LocalAronLook.current.card).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = LocalAronLook.current.purple, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun StatLine(label: String, value: String?, tag: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = com.aktcl.aron.core.ui.LocalAronColors.current.warningOnContainer, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(value ?: stringResource(R.string.qty_stat_na), color = com.aktcl.aron.core.ui.LocalAronColors.current.warningOnContainer, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag(tag))
    }
}

/** `3.33` for 333, in the language's digits: integer division and remainder only. */
@Composable
internal fun hundredths(h: Long): String = localizedNumber(h / 100) + "." + (h % 100).toString().padStart(2, '0').let { com.aktcl.aron.core.ui.localizedDigits(it) }
