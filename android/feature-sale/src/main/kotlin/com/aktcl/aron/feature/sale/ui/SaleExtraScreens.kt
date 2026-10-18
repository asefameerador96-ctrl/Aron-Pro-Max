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
import com.aktcl.aron.core.ui.AronConfirmDialog
import com.aktcl.aron.core.ui.AronTextButton
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
import com.aktcl.aron.feature.sale.domain.QcEntryModel
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
 * Product QC (F-SR-027, screens-sr s8, manual p36-38): this memo's SKUs as cards; each card shows the maximum QC, what is
 * done and what is left in taka (only when the bundle gives the cap, MQ-03), then the six fault rows in two groups (production
 * MFC: three; transport MKT: three), a stepper in the SKU's unit on each; a row can never be raised past what is left under
 * the cap. The summary table (SKU, type, quantity) and the total deduction follow (defect quantity times the price at capture,
 * the same function as the Review). The bar's primary asks "submit QC?" (green Yes, red No) before it completes QC.
 * Renders its own bottom bar.
 */
@Composable
fun QcScreen(
    skus: List<SaleSku>,
    review: SaleReview,
    qtyOf: (skuId: Long, faultCode: String) -> Long,
    onQty: (skuId: Long, faultCode: String, qty: Long) -> Unit,
    locked: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
    /** The per-SKU taka cap of the bundle, or null: the basis of the maximum is not known yet (MQ-03). */
    capMtkOf: (skuId: Long) -> Long? = { null },
    onClearSku: (skuId: Long) -> Unit = {},
) {
    val c = LocalAronColors.current
    var askSubmit by remember { mutableStateOf(false) }
    val groups = listOf(QcEntryModel.PRODUCTION to R.string.qc_production, QcEntryModel.TRANSPORT to R.string.qc_transport)
    val entries = review.qc.map { it.entry }
    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            if (locked) AronBanner(stringResource(R.string.qc_locked), kind = BannerKind.Info)
            else AronBanner(stringResource(R.string.qc_pick), kind = BannerKind.Info)
            if (skus.isEmpty()) AronEmptyState(stringResource(R.string.qc_empty), icon = AronIcons.Sale)
            skus.forEach { sku ->
                val cap = capMtkOf(sku.skuId)
                val done = QcEntryModel.doneMtk(sku, entries)
                AronCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                            SkuThumb(sku.name)
                            Text(skuDisplayName(sku), style = MaterialTheme.typography.titleSmall, color = c.textPrimary, modifier = Modifier.weight(1f))
                        }
                        if (cap != null) QcAmountLine(stringResource(R.string.qc_max), money(cap))
                        QcAmountLine(stringResource(R.string.qc_done_amount), money(done))
                        QcEntryModel.remainingMtk(cap, done)?.let { QcAmountLine(stringResource(R.string.qc_remaining), money(it)) }
                        groups.forEach { (group, label) ->
                            Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = c.textSecondary, modifier = Modifier.padding(top = AronTokens.Space.S))
                            QcEntryModel.typesOf(group).forEach { type ->
                                Text(qcTypeLabel(type.code), style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val otherQc = review.qc.filterNot { it.entry.skuId == sku.skuId && it.entry.faultTypeCode == type.code }.sumOf { it.settlementMtk }
                                    val memoRoom = (review.totals.grossMtk - review.totals.offerDiscountMtk - review.totals.drpDiscountMtk - otherQc).coerceAtLeast(0)
                                    val max = if (locked) 0 else QcEntryModel.maxQty(sku, entries, type.code, cap, memoRoom).coerceAtMost(QcEntryModel.MAX_QTY).toInt()
                                    AronStepper(
                                        qtyOf(sku.skuId, type.code).toInt(), { if (!locked) onQty(sku.skuId, type.code, it.toLong()) },
                                        stringResource(R.string.sale_less), stringResource(R.string.sale_more), min = 0, max = max,
                                    )
                                    Spacer(Modifier.width(AronTokens.Space.S))
                                    Text(unitLabel(sku.baseUnit), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                                }
                            }
                        }
                        if (!locked && done > 0) AronTextButton(stringResource(R.string.qc_clear), { onClearSku(sku.skuId) })
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
                                Text(qcTypeLabel(d.entry.faultTypeCode), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, maxLines = 3)
                                Text(localizedNumber(d.entry.defectQty), Modifier.weight(0.6f), style = MaterialTheme.typography.titleSmall, color = c.textPrimary, textAlign = TextAlign.End)
                            }
                        }
                    }
                }
            }
            Text(stringResource(R.string.qc_total_qty, localizedNumber(review.qc.sumOf { it.entry.defectQty })), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
            Text(stringResource(R.string.qc_deduction, "\u2212 " + money(review.totals.qcDeductionMtk)), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
        }
        AronBottomBar(
            secondary = onCancel?.let { AronBarAction(stringResource(R.string.qc_cancel), onClick = it) },
            primary = AronBarAction(stringResource(R.string.qc_done), onClick = { askSubmit = true }, enabled = !locked, icon = AronIcons.Check),
        )
    }
    if (askSubmit) {
        AronConfirmDialog(
            stringResource(R.string.qc_confirm_title), stringResource(R.string.qc_confirm_message),
            stringResource(R.string.sale_yes), stringResource(R.string.sale_no),
            onConfirm = { askSubmit = false; onDone() }, onDismiss = { askSubmit = false },
        )
    }
}

@Composable
private fun QcAmountLine(label: String, value: String) {
    val c = LocalAronColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
    }
}

@Composable
internal fun qcTypeLabel(code: String): String = when (code) {
    "damaged_crushed" -> stringResource(R.string.qc_type_damaged_crushed)
    "short_outer_pack_stick" -> stringResource(R.string.qc_type_short_outer_pack_stick)
    "other_mfc" -> stringResource(R.string.qc_type_other_mfc)
    "expired_stock" -> stringResource(R.string.qc_type_expired_stock)
    "damaged_in_transit" -> stringResource(R.string.qc_type_damaged_in_transit)
    "taste" -> stringResource(R.string.qc_type_taste)
    else -> code
}

