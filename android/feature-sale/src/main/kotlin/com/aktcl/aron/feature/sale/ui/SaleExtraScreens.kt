package com.aktcl.aron.feature.sale.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronBarAction
import com.aktcl.aron.core.ui.AronBottomBar
import com.aktcl.aron.core.ui.AronCard
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronStepper
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.MoneyText
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.feature.sale.R
import com.aktcl.aron.feature.sale.domain.PaidInput
import com.aktcl.aron.feature.sale.domain.SaleReview
import com.aktcl.aron.feature.sale.domain.SaleSku

/**
 * Credit prompt (F-SR-026, screens-sr s8): the paid amount (0 or more, below the total, two decimals) in a kit field, the
 * live due line as a numeral, and the bar's primary. [onCancel], when given, is the bar's secondary: closing without
 * confirming turns credit off (the host clears the paid amount).
 */
@Composable
fun CreditPrompt(review: SaleReview, onPaid: (Long?) -> Unit, modifier: Modifier = Modifier, onCancel: (() -> Unit)? = null) {
    val c = LocalAronColors.current
    var text by remember { mutableStateOf("") }
    val parsed = PaidInput.parse(text)
    val valid = parsed != null && parsed < review.totals.netMtk
    val showError = text.isNotEmpty() && !valid
    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            AronCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                    Text(stringResource(R.string.credit_title), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                    Text(stringResource(R.string.credit_body), style = MaterialTheme.typography.bodyLarge, color = c.textSecondary)
                    KitTextField(text, { text = it }, stringResource(R.string.credit_paid_label), isError = showError, keyboardType = KeyboardType.Decimal)
                    if (showError) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                            Icon(AronIcons.Warning, contentDescription = null, tint = c.dangerOnContainer, modifier = Modifier.size(AronTokens.Space.L))
                            Text(stringResource(R.string.credit_error), style = MaterialTheme.typography.bodySmall, color = c.dangerOnContainer)
                        }
                    }
                    Text(stringResource(R.string.review_net), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    MoneyText(money(review.totals.netMtk), style = MaterialTheme.typography.headlineLarge)
                    if (valid) {
                        Text(stringResource(R.string.credit_due_label, money(review.totals.netMtk - parsed!!)), style = MaterialTheme.typography.headlineLarge, color = c.textPrimary)
                    }
                }
            }
        }
        AronBottomBar(
            secondary = onCancel?.let { AronBarAction(stringResource(R.string.credit_cancel), onClick = it) },
            primary = AronBarAction(stringResource(R.string.credit_apply), onClick = { onPaid(parsed) }, enabled = valid, icon = AronIcons.Check),
            // an empty field: the bar says what to do; a wrong value: the inline error line says it once
            reasonWhenDisabled = if (text.isEmpty()) stringResource(R.string.credit_reason_enter) else null,
        )
    }
}

/** Slide (DRP): only SKUs with an active offer; empty packets by stepper; the reward shows as a deduction (F-SR-022). */
@Composable
fun SlideScreen(skus: List<SaleSku>, review: SaleReview, emptyOf: (Long) -> Long, onEmpty: (Long, Long) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalAronColors.current
    val offered = skus.filter { it.drp != null }
    // no bar on this screen, so the scrolling content ends above the navigation bar (edge-to-edge)
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(AronTokens.Space.Screen),
        verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        Text(stringResource(R.string.slide_title), style = MaterialTheme.typography.titleLarge, color = c.textPrimary)
        if (offered.isEmpty()) AronEmptyState(stringResource(R.string.slide_none), icon = AronIcons.Sale)
        offered.forEach { sku ->
            AronCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                        SkuThumb(sku.name)
                        Text(skuDisplayName(sku), style = MaterialTheme.typography.titleSmall, color = c.textPrimary, modifier = Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AronStepper(
                            emptyOf(sku.skuId).toInt(), { onEmpty(sku.skuId, it.toLong()) }, stringResource(R.string.sale_less), stringResource(R.string.sale_more),
                            min = 0, max = 999_999, step = sku.drp!!.emptyPerReward.coerceAtMost(10),
                        )
                        Spacer(Modifier.width(AronTokens.Space.S))
                        Text(stringResource(R.string.sale_unit_pack), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    }
                    review.drp.firstOrNull { it.skuId == sku.skuId }?.let {
                        Text(stringResource(R.string.slide_reward, localizedNumber(it.rewardPacks), "− " + money(it.valueMtk)), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                    }
                }
            }
        }
    }
}

/**
 * Product QC (F-SR-027, screens-sr s8): this memo's SKUs as cards, each with the production and transport fault groups
 * and a stepper in the SKU's unit; the summary table (SKU, type, quantity) with the total settlement; the bar's primary
 * completes QC. The deduction is defect quantity times price. Renders its own bottom bar.
 */
@Composable
fun QcScreen(
    skus: List<SaleSku>,
    review: SaleReview,
    qtyOf: (skuId: Long, group: String) -> Long,
    onQty: (skuId: Long, group: String, qty: Long) -> Unit,
    locked: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
) {
    val c = LocalAronColors.current
    val groups = listOf(PRODUCTION to R.string.qc_production, TRANSPORT to R.string.qc_transport)
    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            if (locked) AronBanner(stringResource(R.string.qc_locked), kind = BannerKind.Info)
            else AronBanner(stringResource(R.string.qc_pick), kind = BannerKind.Info)
            if (skus.isEmpty()) AronEmptyState(stringResource(R.string.qc_empty), icon = AronIcons.Sale)
            skus.forEach { sku ->
                AronCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                            SkuThumb(sku.name)
                            Text(skuDisplayName(sku), style = MaterialTheme.typography.titleSmall, color = c.textPrimary, modifier = Modifier.weight(1f))
                        }
                        groups.forEach { (group, label) ->
                            Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AronStepper(
                                    qtyOf(sku.skuId, group).toInt(), { if (!locked) onQty(sku.skuId, group, it.toLong()) },
                                    stringResource(R.string.sale_less), stringResource(R.string.sale_more), min = 0, max = if (locked) 0 else 999_999,
                                )
                                Spacer(Modifier.width(AronTokens.Space.S))
                                Text(unitLabel(sku.baseUnit), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                            }
                        }
                    }
                }
            }
            if (review.qc.isNotEmpty()) {
                Text(stringResource(R.string.qc_summary), style = MaterialTheme.typography.labelMedium, color = c.textSecondary, modifier = Modifier.padding(top = AronTokens.Space.S))
                AronCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.review_col_sku), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                            Text(stringResource(R.string.qc_col_type), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                            Text(stringResource(R.string.review_col_qty), Modifier.weight(0.6f), style = MaterialTheme.typography.labelMedium, color = c.textSecondary, textAlign = TextAlign.End)
                        }
                        review.qc.forEach { d ->
                            val sku = skus.firstOrNull { it.skuId == d.entry.skuId }
                            Row(Modifier.fillMaxWidth().heightIn(min = AronTokens.Touch.Min), verticalAlignment = Alignment.CenterVertically) {
                                Text(sku?.let { skuDisplayName(it) } ?: d.entry.skuId.toString(), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, maxLines = 2)
                                Text(stringResource(if (d.entry.faultGroup == PRODUCTION) R.string.qc_production else R.string.qc_transport), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, maxLines = 2)
                                Text(localizedNumber(d.entry.defectQty), Modifier.weight(0.6f), style = MaterialTheme.typography.titleSmall, color = c.textPrimary, textAlign = TextAlign.End)
                            }
                        }
                    }
                }
            }
            Text(stringResource(R.string.qc_deduction, "− " + money(review.totals.qcDeductionMtk)), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
        }
        AronBottomBar(
            secondary = onCancel?.let { AronBarAction(stringResource(R.string.qc_cancel), onClick = it) },
            primary = AronBarAction(stringResource(R.string.qc_done), onClick = onDone, enabled = !locked, icon = AronIcons.Check),
        )
    }
}

const val PRODUCTION = "MFC"
const val TRANSPORT = "TFC"
