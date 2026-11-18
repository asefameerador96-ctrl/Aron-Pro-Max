package com.aktcl.aron.feature.sale.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.AronLook
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronCard
import com.aktcl.aron.core.ui.aron.AronInfoCard
import com.aktcl.aron.core.ui.aron.AronSolidButton
import com.aktcl.aron.core.ui.aron.SolidTone
import com.aktcl.aron.core.ui.aron.AronField
import com.aktcl.aron.core.ui.aron.AronMinus
import com.aktcl.aron.core.ui.aron.AronPlus
import com.aktcl.aron.core.ui.aron.AronPrimaryButton
import com.aktcl.aron.core.ui.aron.AronSecondaryButton
import com.aktcl.aron.core.ui.aron.ButtonGlyph
import com.aktcl.aron.core.ui.aron.PackshotImage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.imePadding
import com.aktcl.aron.core.ui.AronQtyField
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.clickable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronConfirmDialog
import com.aktcl.aron.core.ui.AronTextButton
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.MoneyText
import com.aktcl.aron.core.ui.entryUnitLabel
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
fun CreditPrompt(
    review: SaleReview, onPaid: (Long?) -> Unit, modifier: Modifier = Modifier, onCancel: (() -> Unit)? = null,
    /** The AMO host (owner screenshot 25, D-PAR-AMO-08): the same prompt as a dialog over the review, with the live due inside the field. Same parser, same bound, one [onPaid] write. */
    asDialog: Boolean = false,
) {
    val look = LocalAronLook.current
    var text by remember { mutableStateOf("") }
    val parsed = PaidInput.parse(text)
    val valid = parsed != null && parsed < review.totals.netMtk
    val showError = text.isNotEmpty() && !valid
    if (asDialog) { CreditDialog(review, text, { text = it }, parsed, valid, showError, onPaid, onCancel); return }
    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            AronCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                    Text(stringResource(R.string.credit_title), color = look.purple, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.credit_body), color = look.inkMuted, fontSize = 16.sp)
                    // the kit field: the label above, an underline that goes red on error with the sentence under it; the amount parser and rules are unchanged
                    AronField(
                        text, { text = it }, stringResource(R.string.credit_paid_label),
                        errorText = if (showError) stringResource(R.string.credit_error) else null, keyboardType = KeyboardType.Decimal,
                        fieldModifier = Modifier.testTag(CreditTags.PAID),
                    )
                    Text(stringResource(R.string.review_net), color = look.inkMuted, fontSize = 13.sp)
                    MoneyText(money(review.totals.netMtk), style = MaterialTheme.typography.headlineLarge, color = look.ink)
                    if (valid) {
                        Text(stringResource(R.string.credit_due_label, money(review.totals.netMtk - parsed!!)), color = look.purple, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        // R6: the manual's green yes / red no pair becomes the kit's gradient Yes and grey No on the purple panel (the answers of "how much are you collecting now?")
        Column(Modifier.fillMaxWidth().background(look.panel).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).imePadding()) {
            if (text.isEmpty()) Text(stringResource(R.string.credit_reason_enter), color = look.onPanelMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp))
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onCancel != null) AronSecondaryButton(stringResource(R.string.sale_no), onCancel, Modifier.weight(1f).testTag(CreditTags.NO))
                AronPrimaryButton(stringResource(R.string.sale_yes), { onPaid(parsed) }, Modifier.weight(1f).testTag(CreditTags.YES), enabled = valid)
            }
        }
    }
}

/** The error ink of the partial-payment box: 7:1 or better on [PopupTint]. */
internal val CreditErrorInk = androidx.compose.ui.graphics.Color(0xFF8F1712)

/**
 * The SR's partial-payment box (manual 27): over the Review, a lavender box with the title, the question, an outlined field "আদায়কৃত অর্থ" that shows
 * the live "বাকিঃ" due at its right, and the green হ্যাঁ and red না. Same rules as [CreditPrompt] (the amount must be below the net); না turns credit off.
 */
@Composable
fun CreditDialog(
    review: SaleReview, onPaid: (Long?) -> Unit, onCancel: () -> Unit,
    /** For the goldens: the text the field starts with. */
    initialText: String = "",
    /** False only in the goldens: a dialog focuses its first field, and the caret and keyboard of a focused field never let the test clock go idle. */
    focusable: Boolean = true,
) {
    val look = LocalAronLook.current
    val c = com.aktcl.aron.core.ui.LocalAronColors.current
    var text by remember { mutableStateOf(initialText) }
    val parsed = PaidInput.parse(text)
    val valid = parsed != null && parsed < review.totals.netMtk
    val showError = text.isNotEmpty() && !valid
    val paidLabel = stringResource(R.string.credit_paid_label)
    val boxColor = if (c.sunlight) c.surfaceSolid else PopupTint
    // the error ink is darker than the look's red so it holds 7:1 on the lavender box (CreditDialogContrastTest)
    val errorInk = if (c.sunlight) look.red else CreditErrorInk
    // a plain Dialog (not AlertDialog): the Material box measures its content by intrinsics, which a text field in it cannot answer
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onCancel, // Back is "না": credit off, back to the Review
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(max = 560.dp).clip(RoundedCornerShape(24.dp)).background(boxColor).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
        ) {
            Text(stringResource(R.string.credit_title), color = look.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                Text(stringResource(R.string.credit_body), color = look.ink, fontSize = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
                // the outlined field of the manual: a purple (red on error) border, the label sitting on the top edge, the typed amount at the left, the live due at the right
                val edge = if (showError) errorInk else look.purple
                Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 60.dp).border(2.dp, edge, RoundedCornerShape(4.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = text, onValueChange = { text = it }, singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, color = look.ink),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(look.purple),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f).testTag(CreditTags.PAID).semantics { contentDescription = paidLabel }.focusProperties { canFocus = focusable },
                        )
                        if (valid) Text(stringResource(R.string.credit_due_label, money(review.totals.netMtk - parsed!!)), color = look.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        paidLabel, color = edge, fontSize = 13.sp,
                        modifier = Modifier.padding(start = 10.dp).offset(y = (-9).dp).background(boxColor).padding(horizontal = 4.dp).clearAndSetSemantics { },
                    )
                }
                if (showError) Text(stringResource(R.string.credit_error), color = errorInk, fontSize = 13.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                com.aktcl.aron.core.ui.AronYesButton(stringResource(R.string.sale_yes), { onPaid(parsed) }, Modifier.testTag(CreditTags.YES), enabled = valid)
                com.aktcl.aron.core.ui.AronNoButton(stringResource(R.string.sale_no), onCancel, Modifier.testTag(CreditTags.NO))
            }
        }
    }
}

/** Slide (DRP): only SKUs with an active offer; empty packets by stepper; the reward shows as a deduction (F-SR-022). */
@Composable
fun SlideScreen(skus: List<SaleSku>, review: SaleReview, emptyOf: (Long) -> Long, onEmpty: (Long, Long) -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    val offered = skus.filter { it.drp != null }
    // no bar on this screen, so the scrolling content ends above the navigation bar (edge-to-edge)
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        Text(stringResource(R.string.slide_title), color = look.purple, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (offered.isEmpty()) AronEmptyState(stringResource(R.string.slide_none), icon = AronIcons.Sale)
        offered.forEach { sku ->
            AronCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                    val name = skuDisplayName(sku)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                        PackshotImage(sku.skuId.toString(), Modifier.size(AronLook.PackshotWidth, AronLook.PackshotHeight), category = packCategoryOf(sku.categoryCode))
                        Text(name, color = look.purple, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    }
                    val empty = emptyOf(sku.skuId).toInt()
                    val step = sku.drp!!.emptyPerReward.coerceAtMost(10)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AronMinus({ onEmpty(sku.skuId, (empty - step).coerceAtLeast(0).toLong()) }, stringResource(R.string.sale_less) + ", " + name, enabled = empty > 0)
                        Text(localizedNumber(empty.toLong()), color = look.ink, fontSize = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 64.dp))
                        AronPlus({ onEmpty(sku.skuId, (empty + step).coerceAtMost(999_999).toLong()) }, stringResource(R.string.sale_more) + ", " + name, enabled = empty < 999_999)
                        Spacer(Modifier.width(AronTokens.Space.S))
                        Text(stringResource(R.string.sale_unit_pack), color = look.inkMuted, fontSize = 14.sp)
                    }
                    review.drp.firstOrNull { it.skuId == sku.skuId }?.let {
                        Text(stringResource(R.string.slide_reward, localizedNumber(it.rewardPacks), "− " + money(it.valueMtk)), color = look.ink, fontSize = 16.sp)
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
/** Test tags of the credit prompt. */
object CreditTags {
    const val YES = "credit_yes"
    const val NO = "credit_no"
    /** The paid-amount field (the editable node). */
    const val PAID = "credit_paid"
}

/** Test tags of the QC screen. */
object QcTags {
    const val TILE = "qc_tile"
    const val SAVE = "qc_save"
    const val CANCEL = "qc_cancel"
    const val DELETE = "qc_delete"
    const val CARD = "qc_card"
    const val QTY_FIELD = "qc_qty_field"
    const val SUMMARY_ROW = "qc_summary_row"
    const val PACK_EQUIV = "qc_pack_equiv"
}

/** `= 2 packets + 5 sticks` for [qtyBase] sticks of a stick-based SKU with a pack factor; null when there is nothing to convert. Integer math. */
@Composable
internal fun qcPackEquivalent(sku: SaleSku, qtyBase: Long): String? {
    if (sku.baseUnit != "stick" || sku.packFactor <= 1 || qtyBase <= 0) return null
    val packs = qtyBase / sku.packFactor
    val rest = qtyBase % sku.packFactor
    val packWord = entryUnitLabel("packet")
    return when {
        packs == 0L -> null
        rest == 0L -> stringResource(R.string.qc_pack_equiv, localizedNumber(packs), packWord)
        else -> stringResource(R.string.qc_pack_equiv_both, localizedNumber(packs), packWord, localizedNumber(rest), unitLabel(sku.baseUnit))
    }
}

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
    /** True for the QC-only path (every SKU of the catalog is offered): a card opens on a tap so the list stays light. */
    collapsible: Boolean = false,
    /** The old SR's two steps (manual 25, 26): the SKU carousel with the QC summary, then one SKU's entry page; false keeps the one-scroll screen (AMO). */
    twoStep: Boolean = false,
) {
    if (twoStep) {
        QcTwoStepScreen(skus, review, qtyOf, onQty, locked, onDone, modifier, onCancel, capMtkOf, onClearSku)
        return
    }
    val c = LocalAronColors.current
    val look = LocalAronLook.current
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
                var open by rememberSaveable(sku.skuId, collapsible) { mutableStateOf(!collapsible) }
                val shown = open || done > 0
                AronCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                        Row(
                            (if (collapsible) Modifier.clickable(role = Role.Button) { open = !open } else Modifier).heightIn(min = AronTokens.Touch.Min).testTag(QcTags.CARD),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
                        ) {
                            PackshotImage(sku.skuId.toString(), Modifier.size(40.dp, 64.dp), category = packCategoryOf(sku.categoryCode))
                            Text(skuDisplayName(sku), color = look.purple, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        }
                        if (shown) {
                        if (cap != null) QcAmountLine(stringResource(R.string.qc_max), money(cap))
                        QcAmountLine(stringResource(R.string.qc_done_amount), money(done))
                        QcEntryModel.remainingMtk(cap, done)?.let { QcAmountLine(stringResource(R.string.qc_remaining), money(it)) }
                        groups.forEach { (group, label) ->
                            Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = c.textSecondary, modifier = Modifier.padding(top = AronTokens.Space.S))
                            QcEntryModel.typesOf(group).forEach { type ->
                                Text(qcTypeLabel(type.code), style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val max = qcMax(sku, review, entries, type.code, cap, locked) // one formula for both screens
                                    val cur = qtyOf(sku.skuId, type.code).toInt()
                                    AronMinus({ if (!locked) onQty(sku.skuId, type.code, (cur - 1).coerceAtLeast(0).toLong()) }, stringResource(R.string.sale_less), enabled = !locked && cur > 0)
                                    AronPlus({ if (!locked) onQty(sku.skuId, type.code, (cur + 1).coerceAtMost(max).toLong()) }, stringResource(R.string.sale_more), enabled = !locked && cur < max)
                                    Spacer(Modifier.width(AronTokens.Space.S))
                                    Text(unitLabel(sku.baseUnit), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                                    // the QC count stays in sticks (a defect count, not a trade unit, D-SR-MAN-S4-06); the packet equivalent sits beside it
                                    qcPackEquivalent(sku, cur.toLong())?.let { Text(it, Modifier.testTag(QcTags.PACK_EQUIV), style = MaterialTheme.typography.bodySmall, color = c.textSecondary) }
                                    Spacer(Modifier.width(AronTokens.Space.S))
                                    // D-17: a rep may type the count; a number above what is left is accepted down to it and flagged
                                    AronQtyField(
                                        qtyOf(sku.skuId, type.code).toInt(), { if (!locked) onQty(sku.skuId, type.code, it.toLong()) },
                                        name = qcTypeLabel(type.code) + " " + skuDisplayName(sku), max = max, enabled = !locked,
                                        clampedText = { stringResource(R.string.qc_typed_max, localizedNumber(it.toLong())) },
                                        modifier = Modifier.testTag(QcTags.QTY_FIELD),
                                    )
                                }
                            }
                        }
                        if (!locked && done > 0) AronTextButton(stringResource(R.string.qc_clear), { onClearSku(sku.skuId) })
                        }
                    }
                }
            }
            if (review.qc.isNotEmpty()) {
                Text(stringResource(R.string.qc_summary), style = MaterialTheme.typography.labelMedium, color = c.textSecondary, modifier = Modifier.padding(top = AronTokens.Space.S))
                // the manual's summary: one row per SKU, a column for each fault group and a total (p37)
                AronCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.review_col_sku), Modifier.weight(1.4f), style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                            Text(stringResource(R.string.qc_col_mfc), Modifier.weight(0.8f), style = MaterialTheme.typography.labelMedium, color = c.textSecondary, textAlign = TextAlign.End)
                            Text(stringResource(R.string.qc_col_mkt), Modifier.weight(0.8f), style = MaterialTheme.typography.labelMedium, color = c.textSecondary, textAlign = TextAlign.End)
                            Text(stringResource(R.string.qc_col_total), Modifier.weight(0.8f), style = MaterialTheme.typography.labelMedium, color = c.textSecondary, textAlign = TextAlign.End)
                        }
                        QcEntryModel.summary(entries).forEach { r ->
                            val sku = skus.firstOrNull { it.skuId == r.skuId }
                            Row(Modifier.fillMaxWidth().heightIn(min = AronTokens.Touch.Min).testTag(QcTags.SUMMARY_ROW), verticalAlignment = Alignment.CenterVertically) {
                                Text(sku?.let { skuDisplayName(it) } ?: r.skuId.toString(), Modifier.weight(1.4f), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, maxLines = 3)
                                Text(localizedNumber(r.mfcQty), Modifier.weight(0.8f), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, textAlign = TextAlign.End)
                                Text(localizedNumber(r.mktQty), Modifier.weight(0.8f), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, textAlign = TextAlign.End)
                                Text(localizedNumber(r.totalQty), Modifier.weight(0.8f), style = MaterialTheme.typography.titleSmall, color = c.textPrimary, textAlign = TextAlign.End)
                            }
                        }
                    }
                }
            }
            Text(stringResource(R.string.qc_total_qty, localizedNumber(review.qc.sumOf { it.entry.defectQty })), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
            Text(stringResource(R.string.qc_deduction, "\u2212 " + money(review.totals.qcDeductionMtk)), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
        }
        Column(Modifier.fillMaxWidth().background(look.panel).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onCancel != null) AronSecondaryButton(stringResource(R.string.qc_cancel), onCancel, Modifier.weight(1f))
                AronPrimaryButton(stringResource(R.string.qc_done), { askSubmit = true }, Modifier.weight(1f), enabled = !locked, glyph = ButtonGlyph.Submit)
            }
        }
    }
    if (askSubmit) {
        AronConfirmDialog(
            stringResource(R.string.qc_confirm_title), stringResource(R.string.qc_confirm_message),
            stringResource(R.string.sale_yes), stringResource(R.string.sale_no),
            onConfirm = { askSubmit = false; onDone() }, onDismiss = { askSubmit = false },
        )
    }
}

/** The step-2 entry of one SKU: the most that can still be entered for [code] (the same formula as the one-scroll screen). */
private fun qcMax(sku: SaleSku, review: SaleReview, entries: List<com.aktcl.aron.feature.sale.domain.QcEntry>, code: String, cap: Long?, locked: Boolean): Int {
    if (locked) return 0
    val otherQc = review.qc.filterNot { it.entry.skuId == sku.skuId && it.entry.faultTypeCode == code }.sumOf { it.settlementMtk }
    val memoRoom = QcEntryModel.memoRoomMtk(review.totals.grossMtk, review.totals.offerDiscountMtk + review.totals.drpDiscountMtk, otherQc)
    return QcEntryModel.maxQty(sku, entries, code, cap, memoRoom).coerceAtMost(QcEntryModel.MAX_QTY).toInt()
}

/**
 * The old SR's QC in two steps (SR manual 25 and 26, owner rule 14a): step 1 is the info card, a horizontal row of the SKUs' packshots, the QC summary
 * (SKU, QC type, quantity, the total and the settlement amount) and the "QC জমা দিন" bar; a tap on a SKU opens step 2, that SKU's entry page: the
 * maximum / done / remaining amounts, the fault-type table (group, type, quantity box) and সংরক্ষণ, বাতিল, ডিলিট. The quantities are applied live through
 * [onQty] exactly as the one-scroll screen does (nothing is held back in the screen); বাতিল puts back the values the page opened with, ডিলিট clears the SKU
 * ([onClearSku]). The open SKU and the values to restore are NOT saved (plain `remember`): after a rotation or a relaunch the sale is back on its first screen and QC opens on step 1,
 * so বাতিল can only put back values this session's page opened with. The discard question ("Discard everything entered in QC?") is asked only by step 1's বাতিল, and only when QC
 * is entered and not submitted; Back on step 2 returns to step 1 and step 2's বাতিল restores its own page, neither asks. What is saved, the caps and the credit logic are not changed.
 */
@Composable
private fun QcTwoStepScreen(
    skus: List<SaleSku>, review: SaleReview, qtyOf: (Long, String) -> Long, onQty: (Long, String, Long) -> Unit, locked: Boolean, onDone: () -> Unit,
    modifier: Modifier, onCancel: (() -> Unit)?, capMtkOf: (Long) -> Long?, onClearSku: (Long) -> Unit,
) {
    val c = LocalAronColors.current
    val look = LocalAronLook.current
    var askSubmit by remember { mutableStateOf(false) }
    // not saved across a restart on purpose: an unsubmitted QC does not survive one (SaleFlow.start drops it), so a restored open page would be stale
    var selected by remember { mutableStateOf<Long?>(null) }
    // "code=qty;code=qty" of the page as it opened, to put back on বাতিল
    var opened by remember { mutableStateOf("") }
    var askDiscard by remember { mutableStateOf(false) }
    val entries = review.qc.map { it.entry }
    val sku = selected?.let { id -> skus.firstOrNull { it.skuId == id } }
    if (selected != null && sku == null) selected = null
    androidx.activity.compose.BackHandler(enabled = sku != null) { selected = null }
    Column(modifier.fillMaxSize().background(look.page)) {
        if (sku == null) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.M), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                AronInfoCard(stringResource(if (locked) R.string.qc_locked else R.string.qc_pick))
                if (skus.isEmpty()) AronEmptyState(stringResource(R.string.qc_empty), icon = AronIcons.Sale)
                else androidx.compose.foundation.lazy.LazyRow(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(AronLook.CardRadius)).background(PopupTint.copy(alpha = 0.6f)).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(skus, key = { it.skuId }) { s ->
                        Column(
                            Modifier.width(112.dp).clip(RoundedCornerShape(10.dp)).background(look.card).clickable(role = Role.Button) {
                                selected = s.skuId
                                opened = QcEntryModel.typesOf(QcEntryModel.PRODUCTION).plus(QcEntryModel.typesOf(QcEntryModel.TRANSPORT)).joinToString(";") { t -> t.code + "=" + qtyOf(s.skuId, t.code) }
                            }.padding(8.dp).testTag(QcTags.CARD),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            PackshotImage(s.skuId.toString(), Modifier.size(80.dp, 112.dp), category = packCategoryOf(s.categoryCode))
                            Text(skuDisplayName(s), color = look.purple, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 2)
                        }
                    }
                }
                Text(stringResource(R.string.qc_summary), color = look.ink, fontSize = 20.sp, modifier = Modifier.padding(top = AronTokens.Space.S))
                // the summary table of manual 25 (right): SKU, QC type, quantity; the footer names the total and the settlement amount
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(AronLook.CardRadius)).background(look.card)) {
                    Row(Modifier.fillMaxWidth().background(look.tableHeader).padding(horizontal = 12.dp, vertical = 12.dp)) {
                        Text(stringResource(R.string.review_col_sku), Modifier.weight(1.2f), color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.qc_col_qctype), Modifier.weight(1.4f), color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.qc_col_qty), Modifier.weight(0.8f), color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
                    }
                    val faultMfc = stringResource(R.string.qc_fault_mfc); val faultMkt = stringResource(R.string.qc_fault_mkt)
                    if (review.qc.isEmpty()) Text(stringResource(R.string.qc_summary_none), Modifier.fillMaxWidth().padding(12.dp), color = look.inkMuted, textAlign = TextAlign.Center)
                    QcEntryModel.summary(entries).forEach { r ->
                        val rowSku = skus.firstOrNull { it.skuId == r.skuId }
                        // the fault names are the old app's English words on every language (manual 25)
                        val types = listOfNotNull(if (r.mfcQty > 0) faultMfc else null, if (r.mktQty > 0) faultMkt else null).joinToString(",")
                        Row(Modifier.fillMaxWidth().heightIn(min = AronTokens.Touch.Min).padding(horizontal = 12.dp, vertical = 6.dp).testTag(QcTags.SUMMARY_ROW), verticalAlignment = Alignment.CenterVertically) {
                            Text(rowSku?.let { skuDisplayName(it) } ?: r.skuId.toString(), Modifier.weight(1.2f), color = look.purple, maxLines = 3)
                            Text(types, Modifier.weight(1.4f), color = look.purple, maxLines = 3)
                            Text(localizedNumber(r.totalQty), Modifier.weight(0.8f), color = look.ink, textAlign = TextAlign.End, fontWeight = FontWeight.Bold)
                        }
                    }
                    Column(Modifier.fillMaxWidth().background(look.page.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(stringResource(R.string.qc_total_qty, localizedNumber(review.qc.sumOf { it.entry.defectQty })), color = look.ink, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.qc_deduction, "\u2212 " + money(review.totals.qcDeductionMtk)), color = look.ink, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().background(look.panel).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // বাতিল drops every SKU already saved on step 2: with QC entered it asks first
                    if (onCancel != null) AronSecondaryButton(stringResource(R.string.qc_cancel), { if (review.qc.isNotEmpty() && !locked) askDiscard = true else onCancel() }, Modifier.weight(1f))
                    AronPrimaryButton(stringResource(R.string.qc_done), { askSubmit = true }, Modifier.weight(1f), enabled = !locked, glyph = ButtonGlyph.Arrow)
                }
            }
        } else {
            val cap = capMtkOf(sku.skuId)
            val done = QcEntryModel.doneMtk(sku, entries)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.M), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                Text(stringResource(R.string.qc_entry_title), color = look.purple, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(AronLook.CardRadius)).background(PopupTint).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    PackshotImage(sku.skuId.toString(), Modifier.size(72.dp, 100.dp), category = packCategoryOf(sku.categoryCode))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                        Text(skuDisplayName(sku), color = look.purple, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        if (cap != null) QcAmountLine(stringResource(R.string.qc_max), money(cap))
                        QcAmountLine(stringResource(R.string.qc_done_amount), money(done))
                        QcEntryModel.remainingMtk(cap, done)?.let { QcAmountLine(stringResource(R.string.qc_remaining), money(it)) }
                    }
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(AronLook.CardRadius)).background(look.card)) {
                    Row(Modifier.fillMaxWidth().background(look.tableHeader).padding(horizontal = 12.dp, vertical = 12.dp)) {
                        Text(stringResource(R.string.qc_col_fault), Modifier.weight(0.8f), color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.qc_col_type), Modifier.weight(1.3f), color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.qc_col_qty) + " (" + unitLabel(sku.baseUnit) + ")", Modifier.weight(0.9f), color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
                    }
                    listOf(QcEntryModel.PRODUCTION to R.string.qc_production, QcEntryModel.TRANSPORT to R.string.qc_transport).forEach { (group, label) ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(stringResource(label), Modifier.weight(0.8f).padding(end = 6.dp), color = look.purple, fontWeight = FontWeight.Bold)
                            Column(Modifier.weight(2.2f), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                                QcEntryModel.typesOf(group).forEach { type ->
                                    val cur = qtyOf(sku.skuId, type.code).toInt()
                                    val max = qcMax(sku, review, entries, type.code, cap, locked)
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Column(Modifier.weight(1.3f)) {
                                            Text(qcTypeLabel(type.code), color = look.purple, fontSize = 15.sp)
                                            // the QC count stays in sticks (a defect count, not a trade unit, D-SR-MAN-S4-06); the packet equivalent is a caption
                                            qcPackEquivalent(sku, cur.toLong())?.let { Text(it, Modifier.testTag(QcTags.PACK_EQUIV), style = MaterialTheme.typography.bodySmall, color = c.textSecondary) }
                                        }
                                        Box(Modifier.weight(0.9f), contentAlignment = Alignment.CenterEnd) {
                                            AronQtyField(
                                                cur, { if (!locked) onQty(sku.skuId, type.code, it.toLong()) },
                                                name = qcTypeLabel(type.code) + " " + skuDisplayName(sku), max = max, enabled = !locked,
                                                clampedText = { stringResource(R.string.qc_typed_max, localizedNumber(it.toLong())) },
                                                modifier = Modifier.testTag(QcTags.QTY_FIELD),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                AronPrimaryButton(stringResource(R.string.qty_popup_save), { selected = null }, Modifier.fillMaxWidth(), glyph = ButtonGlyph.Save)
                AronSecondaryButton(
                    stringResource(R.string.qc_cancel),
                    {
                        // put back what the page opened with, then leave it; a locked QC writes nothing (the draft refuses it), the page only closes
                        if (!locked) opened.split(';').filter { it.contains('=') }.forEach { kv -> onQty(sku.skuId, kv.substringBefore('='), kv.substringAfter('=').toLongOrNull() ?: 0L) }
                        selected = null
                    },
                    Modifier.fillMaxWidth(),
                )
                if (!locked && done > 0) AronSolidButton(stringResource(R.string.qc_clear), { onClearSku(sku.skuId); selected = null }, Modifier.fillMaxWidth(), tone = SolidTone.Amber)
            }
        }
    }
    if (askSubmit) {
        AronConfirmDialog(
            stringResource(R.string.qc_confirm_title), stringResource(R.string.qc_confirm_message),
            stringResource(R.string.sale_yes), stringResource(R.string.sale_no),
            onConfirm = { askSubmit = false; onDone() }, onDismiss = { askSubmit = false }, manual = true,
        )
    }
    if (askDiscard && onCancel != null) {
        AronConfirmDialog(
            stringResource(R.string.qc_confirm_title), stringResource(R.string.qc_discard_message),
            stringResource(R.string.sale_yes), stringResource(R.string.sale_no),
            onConfirm = { askDiscard = false; onCancel() }, onDismiss = { askDiscard = false }, manual = true,
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

/** The old paid-amount dialog (screenshot 25): title, question, an outlined field "আদায়কৃত অর্থ" with "বাকিঃ n" inside it, and হ্যাঁ / না at the bottom right. */
@Composable
private fun CreditDialog(
    review: SaleReview, text: String, onText: (String) -> Unit, parsed: Long?, valid: Boolean, showError: Boolean, onPaid: (Long?) -> Unit, onCancel: (() -> Unit)?,
) {
    // a tap outside never drops the typed amount or turns credit off: "No" (or the back key) is the only way out
    androidx.compose.ui.window.Dialog(onDismissRequest = { onCancel?.invoke() }, properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false)) { CreditDialogCard(review, text, onText, parsed, valid, showError, onPaid, onCancel) }
}

/** The card inside the paid-amount dialog (public so its golden can render it without a dialog window). */
@Composable
fun CreditDialogCard(
    review: SaleReview, text: String, onText: (String) -> Unit, parsed: Long?, valid: Boolean, showError: Boolean, onPaid: (Long?) -> Unit, onCancel: (() -> Unit)?,
) {
    val look = LocalAronLook.current
    run {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(AronLook.CardRadius)).background(look.card).padding(AronTokens.Space.L),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
        ) {
            Text(stringResource(R.string.credit_title), color = look.ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.credit_body), color = look.ink, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            androidx.compose.material3.OutlinedTextField(
                value = text, onValueChange = onText, singleLine = true, isError = showError,
                label = { Text(stringResource(R.string.credit_paid_label)) },
                trailingIcon = {
                    // the live due, inside the field at the right; blank until the amount is valid
                    if (valid) Text(stringResource(R.string.credit_due_label, money(review.totals.netMtk - parsed!!)), color = look.purple, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = AronTokens.Space.M))
                },
                supportingText = { if (showError) Text(stringResource(R.string.credit_error)) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().testTag(CreditTags.PAID),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M, Alignment.End)) {
                DialogButton(stringResource(R.string.sale_yes), look.primaryStart ?: look.purple, valid, Modifier.testTag(CreditTags.YES)) { onPaid(parsed) }
                if (onCancel != null) DialogButton(stringResource(R.string.sale_no), look.stopFill ?: look.red, true, Modifier.testTag(CreditTags.NO)) { onCancel() }
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, fill: androidx.compose.ui.graphics.Color, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val look = LocalAronLook.current
    Box(
        modifier.heightIn(min = AronTokens.Touch.Min).widthIn(min = 72.dp).clip(RoundedCornerShape(AronLook.ButtonRadius)).background(if (enabled) fill else look.disabledFill)
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) androidx.compose.ui.graphics.Color.White else look.disabledText, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = AronTokens.Space.L)) }
}
