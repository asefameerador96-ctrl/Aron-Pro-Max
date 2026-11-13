package com.aktcl.aron.feature.sale.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aktcl.aron.core.ui.AronLook
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.PackshotImage
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.feature.sale.R
import com.aktcl.aron.feature.sale.domain.SaleReview
import com.aktcl.aron.feature.sale.domain.SaleSku

object SlideTags {
    const val ROW = "slide_row"
    const val RIBBON = "slide_ribbon"
    const val COUNT = "slide_count"
    const val SUBMIT = "slide_submit"
    const val SAVE = "slide_quick_save"
}

/**
 * The vendor's Slide page (manuals 33, 34), "স্লাইড": the outlet heading with route and cluster, a table "এসকেইউ / খালি প্যাকেট" with a pack
 * picture, a grey stepper and a red OFFER ribbon on every SKU that carries an offer, and the gradient "জমা দিন". A tap on the ribbon opens the
 * "ছাড়" pop-up with the offer rule (no dates or amounts are invented: the offer programmes are deferred, the figures stay what the bundle holds);
 * a tap on the count opens the quick-add dialog (1, 5, 10, 20, 50 up and down, then সংরক্ষণ). The empty-packet counts and the reward
 * come from the same draft logic as the earlier screen, so the review still shows the reward as a deduction.
 */
@Composable
fun SlideManualScreen(
    skus: List<SaleSku>, review: SaleReview, emptyOf: (Long) -> Long, onEmpty: (Long, Long) -> Unit, onSubmit: () -> Unit,
    outletLines: List<String>, modifier: Modifier = Modifier,
) {
    val look = LocalAronLook.current
    val offered = skus.filter { it.drp != null }
    var offerOf by remember { mutableStateOf<SaleSku?>(null) }
    var quickOf by remember { mutableStateOf<SaleSku?>(null) }
    Column(modifier.fillMaxSize().background(look.page)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            outletLines.firstOrNull()?.let { Text(it, color = look.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            outletLines.drop(1).forEach { Text(it, color = look.inkMuted, fontSize = 14.sp) }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(AronLook.CardRadius)).background(look.card)) {
                Row(Modifier.fillMaxWidth().background(look.purple).padding(horizontal = AronTokens.Space.M, vertical = AronTokens.Space.M), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                    Text(stringResource(R.string.slide_col_sku), Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.slide_col_empty), color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                }
                if (offered.isEmpty()) Text(stringResource(R.string.slide_none), color = look.inkMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(AronTokens.Space.L))
                offered.forEach { sku ->
                    val name = skuDisplayName(sku)
                    val n = emptyOf(sku.skuId)
                    Box(Modifier.fillMaxWidth().testTag(SlideTags.ROW)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.M, vertical = AronTokens.Space.S),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
                        ) {
                            PackshotImage(sku.skuId.toString(), Modifier.size(AronLook.PackshotWidth, AronLook.PackshotHeight), category = packCategoryOf(sku.categoryCode))
                            Text(name, Modifier.weight(1f), color = look.purple, fontSize = 17.sp, maxLines = 2)
                            // the grey stepper box: a red minus, the count (a tap opens the quick-add dialog), a purple plus
                            Row(Modifier.clip(RoundedCornerShape(12.dp)).background(look.rowAlt), verticalAlignment = Alignment.CenterVertically) {
                                Text("−", color = look.red, fontSize = 26.sp, textAlign = TextAlign.Center, modifier = Modifier.heightIn(min = AronLook.Touch).widthIn(min = AronLook.Touch)
                                    .clickable(enabled = n > 0, role = Role.Button, onClickLabel = stringResource(R.string.sale_less)) { onEmpty(sku.skuId, (n - 1).coerceAtLeast(0)) }.padding(top = 6.dp))
                                Text(localizedNumber(n), color = look.ink, fontSize = 20.sp, textAlign = TextAlign.Center,
                                    modifier = Modifier.widthIn(min = 40.dp).heightIn(min = AronLook.Touch).clickable(role = Role.Button) { quickOf = sku }.padding(top = 10.dp).testTag(SlideTags.COUNT))
                                Text("+", color = look.purple, fontSize = 26.sp, textAlign = TextAlign.Center, modifier = Modifier.heightIn(min = AronLook.Touch).widthIn(min = AronLook.Touch)
                                    .clickable(enabled = n < 999_999, role = Role.Button, onClickLabel = stringResource(R.string.sale_more)) { onEmpty(sku.skuId, (n + 1).coerceAtMost(999_999)) }.padding(top = 6.dp))
                            }
                        }
                        // the red corner ribbon: a tap opens the offer pop-up
                        Text(
                            stringResource(R.string.slide_offer_ribbon), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.TopEnd).rotate(0f).clip(RoundedCornerShape(bottomStart = 10.dp)).background(RibbonRed)
                                .clickable(role = Role.Button) { offerOf = sku }.padding(horizontal = 8.dp, vertical = 2.dp).testTag(SlideTags.RIBBON),
                        )
                    }
                    review.drp.firstOrNull { it.skuId == sku.skuId }?.let {
                        Text(stringResource(R.string.slide_reward, localizedNumber(it.rewardPacks), "− " + money(it.valueMtk)), color = look.ink, fontSize = 14.sp, modifier = Modifier.padding(horizontal = AronTokens.Space.M).padding(bottom = AronTokens.Space.S))
                    }
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).padding(AronTokens.Space.M),
        ) {
            Box(
                Modifier.fillMaxWidth().heightIn(min = AronLook.ButtonHeight).clip(RoundedCornerShape(AronLook.ButtonRadius))
                    .background(Brush.horizontalGradient(listOf(look.barStart, look.barMid, look.barEnd))).clickable(role = Role.Button, onClick = onSubmit).testTag(SlideTags.SUBMIT),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.slide_submit), color = look.onBar, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(AronTokens.Space.M)) }
        }
    }
    offerOf?.let { sku -> OfferPopup(sku) { offerOf = null } }
    quickOf?.let { sku -> QuickAddDialog(sku, emptyOf(sku.skuId), onSave = { v -> onEmpty(sku.skuId, v); quickOf = null }, onDismiss = { quickOf = null }) }
}

private val RibbonRed = Color(0xFFC62828)
private val Lilac = Color(0xFFECE6F0)

/** "ছাড়": the offer rule of the SKU (what the bundle holds), a divider, and the amber close. No dates or amounts the bundle does not carry. */
@Composable
private fun OfferPopup(sku: SaleSku, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Lilac).padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.slide_discount_title), Modifier.weight(1f), color = Color(0xFF1F4FA8), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("✕", color = Color(0xFF212121), fontSize = 20.sp, modifier = Modifier.heightIn(min = AronLook.Touch).widthIn(min = AronLook.Touch).clickable(role = Role.Button, onClickLabel = stringResource(R.string.slide_close_x), onClick = onClose).padding(top = 10.dp), textAlign = TextAlign.Center)
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 1.dp, max = 1.dp).background(Color(0xFF9E9E9E)))
            Text(skuDisplayName(sku) + ": " + stringResource(R.string.slide_offer_text, localizedNumber((sku.drp?.emptyPerReward ?: 0).toLong())), color = Color(0xFF424242), fontSize = 16.sp)
            Box(
                Modifier.fillMaxWidth().heightIn(min = AronLook.ButtonHeight).clip(RoundedCornerShape(AronLook.ButtonRadius)).background(Color(0xFFE08A00)).clickable(role = Role.Button, onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.slide_close), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(AronTokens.Space.M)) }
        }
    }
}

/** The quick-add dialog (manual 34): the SKU with a stepper, ten teal buttons (1, 5, 10, 20, 50 up and down) and সংরক্ষণ. */
@Composable
private fun QuickAddDialog(sku: SaleSku, start: Long, onSave: (Long) -> Unit, onDismiss: () -> Unit) {
    val look = LocalAronLook.current
    var n by remember { mutableStateOf(start) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Lilac).padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                PackshotImage(sku.skuId.toString(), Modifier.size(AronLook.PackshotWidth, AronLook.PackshotHeight), category = packCategoryOf(sku.categoryCode))
                Text(skuDisplayName(sku), Modifier.weight(1f), color = Color(0xFF1F4FA8), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("−", color = Color(0xFF1B5E20), fontSize = 28.sp, modifier = Modifier.heightIn(min = AronLook.Touch).widthIn(min = AronLook.Touch).clickable(enabled = n > 0, role = Role.Button) { n = (n - 1).coerceAtLeast(0) }.padding(top = 4.dp), textAlign = TextAlign.Center)
                Text(localizedNumber(n), color = look.ink, fontSize = 22.sp)
                Text("+", color = Color(0xFF1B5E20), fontSize = 28.sp, modifier = Modifier.heightIn(min = AronLook.Touch).widthIn(min = AronLook.Touch).clickable(enabled = n < 999_999, role = Role.Button) { n = (n + 1).coerceAtMost(999_999) }.padding(top = 4.dp), textAlign = TextAlign.Center)
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFFD9CFE6)).padding(AronTokens.Space.M), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                listOf(1L, 5L, 10L, 20L, 50L).forEach { step ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                        QuickButton(localizedNumber(step), Modifier.weight(1f)) { n = (n + step).coerceAtMost(999_999) }
                        QuickButton("−" + localizedNumber(step), Modifier.weight(1f)) { n = (n - step).coerceAtLeast(0) }
                    }
                }
            }
            Box(
                Modifier.fillMaxWidth().heightIn(min = AronLook.ButtonHeight).clip(RoundedCornerShape(AronLook.ButtonRadius))
                    .background(Brush.horizontalGradient(listOf(look.barStart, look.barMid, look.barEnd))).clickable(role = Role.Button) { onSave(n) }.testTag(SlideTags.SAVE),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.slide_save), color = look.onBar, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(AronTokens.Space.M)) }
        }
    }
}

@Composable
private fun QuickButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.heightIn(min = AronLook.Touch).clip(RoundedCornerShape(12.dp)).background(Color(0xFF00695C)).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}
