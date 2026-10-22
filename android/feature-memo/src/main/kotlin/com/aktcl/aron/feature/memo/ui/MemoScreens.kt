package com.aktcl.aron.feature.memo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronLook
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronInfoCard
import com.aktcl.aron.core.ui.aron.AronSectionHeader
import com.aktcl.aron.core.ui.aron.AronSolidButton
import com.aktcl.aron.core.ui.aron.AronTextButton
import com.aktcl.aron.core.ui.aron.SolidTone
import com.aktcl.aron.core.ui.aron.AronPrimaryButton
import com.aktcl.aron.core.ui.aron.ButtonGlyph
import com.aktcl.aron.core.ui.aron.SummaryRow
import com.aktcl.aron.core.ui.aron.SummaryTable
import com.aktcl.aron.core.ui.aron.SummaryTotal
import com.aktcl.aron.core.ui.aron.TotalsLine
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.Role
import com.aktcl.aron.core.ui.aron.AronPrinterBadge
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.aktcl.aron.core.ui.AronBarAction
import com.aktcl.aron.core.ui.AronBottomBar
import com.aktcl.aron.core.ui.AronCard
import com.aktcl.aron.core.ui.AronConfirmDialog
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.AronYesButton
import com.aktcl.aron.core.ui.AronSectionLabel
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.MoneyText
import com.aktcl.aron.core.ui.RowPosition
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.entryUnitLabel
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.feature.memo.R
import com.aktcl.aron.feature.memo.domain.DaySummary
import com.aktcl.aron.feature.memo.domain.SkuSummaryRow
import com.aktcl.aron.feature.memo.domain.CategorySummaryRow
import com.aktcl.aron.feature.memo.domain.MemoDetail
import com.aktcl.aron.feature.memo.domain.MemoMenuRow
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.Money
import com.aktcl.aron.rules.UiLocale

@Composable
internal fun uiLocale(): UiLocale = if (LocalAppLanguage.current == AppLanguage.BN) UiLocale.BN else UiLocale.EN

/** "4,391.00 ৳": the amount with the taka sign (every total keeps its sign, screens-sr s0). */
@Composable
internal fun money(mtk: Long): String = Formats.money(mtk, uiLocale())

/** "4,391.00" without the sign, for the dense columns whose header carries "(৳)" (screens-sr s0). */
@Composable
internal fun amount(mtk: Long): String = Formats.digits(Money.formatTaka(mtk, 2), uiLocale())

/** A deduction: the true minus U+2212 and its word beside it, never colour alone (screens-sr s0). */
@Composable
internal fun minusMoney(mtk: Long): String = MINUS + money(kotlin.math.abs(mtk))

private const val MINUS = "− "

/** The short memo number of a row ("001" of "sr334001-261005-001"): the full number stays on the detail header and the slip. */
internal fun shortMemoNo(memoNo: String): String = memoNo.substringAfterLast('-')

/** Dhaka wall time `HH:mm` of an RFC 3339 instant, digits per locale; the raw text when it does not parse. */
@Composable
internal fun dhakaTime(iso: String): String {
    val locale = uiLocale()
    return runCatching { Formats.time(java.time.Instant.parse(iso).toEpochMilli(), locale) }.getOrDefault(iso)
}

/** The least widths of the dense table columns on a 328 dp content width (screens-sr s9: SKU, quantity, price); a wider number grows its column and the name gives way. */
private val QtyColumn = 84.dp
private val PriceColumn = 96.dp

/** From `fontScale.vertical` the dense tables reflow: the name on its own line, the numbers on the next (screens-sr s0: numbers never wrap or clip). */
@Composable
internal fun tablesStacked(): Boolean = LocalDensity.current.fontScale >= AronTokens.Size.VerticalFontScale

/** The white 12 dp card of the parity look (PARITY-SPEC `aron.card`). */
@Composable
internal fun WhiteCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(AronLook.CardRadius)).background(LocalAronLook.current.card), content = content)
}

/**
 * The Memo menu (F-SR-030; PARITY-SPEC memo.md): the info card (dismissible per day: [onDismissBanner] fires and the host
 * remembers the business date, [bannerDismissed] hides it; [printerBadge] is the host's printer state), then today's live memos
 * as white rows (outlet, memo number and time, the total and the due); selecting one shows [MemoDetailView]. A query on local
 * memos: offline, always.
 */
@Composable
fun MemoMenuScreen(
    rows: List<MemoMenuRow>,
    outletName: (Long) -> String,
    onOpen: (MemoMenuRow) -> Unit,
    modifier: Modifier = Modifier,
    bannerDismissed: Boolean = false,
    onDismissBanner: (() -> Unit)? = null,
    printerBadge: (@Composable () -> Unit)? = null,
) {
    val look = LocalAronLook.current
    ScrollColumn(modifier.background(look.page), contentPadding = PaddingValues(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
        if (!bannerDismissed) {
            AronInfoCard(stringResource(R.string.memo_banner), trailing = printerBadge)
            if (onDismissBanner != null) AronTextButton(stringResource(R.string.memo_banner_dismiss), onDismissBanner, Modifier.align(Alignment.End))
        }
        if (rows.isEmpty()) {
            Text(
                stringResource(R.string.memo_empty), style = MaterialTheme.typography.bodyLarge, color = look.inkMuted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = AronTokens.Space.Xl),
            )
        } else {
            AronSectionHeader(stringResource(R.string.memo_section_today))
            WhiteCard {
                rows.forEachIndexed { i, r ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(look.tileHairline))
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = AronLook.Touch).clickable(role = Role.Button) { onOpen(r) }.padding(AronTokens.Space.L),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(outletName(r.outletId), style = MaterialTheme.typography.titleSmall, color = look.purple, maxLines = 2)
                            Text(stringResource(R.string.memo_row_meta, localizedDigits(shortMemoNo(r.memoNo)), dhakaTime(r.committedAtIso)), style = MaterialTheme.typography.bodySmall, color = look.inkMuted)
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
                            MoneyText(money(r.totalMtk), style = MaterialTheme.typography.titleMedium, color = look.ink, textAlign = TextAlign.End)
                            if (r.dueMtk > 0) Text(stringResource(R.string.memo_due_chip_amount, money(r.dueMtk)), style = MaterialTheme.typography.labelMedium, color = look.red)
                        }
                    }
                }
            }
        }
    }
}

internal fun rowPosition(i: Int, n: Int): RowPosition = when {
    n == 1 -> RowPosition.Only
    i == 0 -> RowPosition.First
    i == n - 1 -> RowPosition.Last
    else -> RowPosition.Middle
}

/**
 * Memo detail (F-SR-030/031/032/033; PARITY-SPEC memo.md): the header card, the lines table (dark header, no per-row sign, "(৳)"
 * in the header), the discount and QC sections only when non-zero, the totals with a true minus and the purple grand total, the
 * credit box with the green "Mark paid", the reprint marker, and the buttons: Print (gradient) and the amber Edit.
 * [canMarkPaid] must come from `DueLedger.markPaid(...) != null` (not the stored due), so the button disappears after the
 * collection. [editDisabledReason] disables Edit and says why (outside the geofence, QC done); [printDisabledReason] does the
 * same for Print (the reprint limit); [onHistory] adds the outlet's sale history link. [skuUnit] names the unit word of a SKU
 * (stick, piece, dozen): it sits beside every quantity, the bare number only when the unit is unknown.
 */
@Composable
fun MemoDetailView(
    detail: MemoDetail,
    canMarkPaid: Boolean,
    dueMtk: Long,
    skuName: (Long) -> String,
    onPrint: () -> Unit,
    onEdit: () -> Unit,
    onMarkPaid: () -> Unit,
    modifier: Modifier = Modifier,
    outletName: String? = null,
    printing: Boolean = false,
    printDisabledReason: String? = null,
    editDisabledReason: String? = null,
    paidBanner: Boolean = false,
    onHistory: (() -> Unit)? = null,
    skuUnit: (Long) -> String = { "" },
    /** The category code of a SKU ("cigarette", "bidi", "lighter", "match"); drives the "মোট <category>" subtotal rows (M3). */
    skuCategory: (Long) -> String? = { null },
) {
    val look = LocalAronLook.current
    var confirm by remember { mutableStateOf(false) }
    val memo = detail.memo
    ScrollColumn(modifier.background(look.page), contentPadding = PaddingValues(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
        if (paidBanner) AronInfoCard(stringResource(R.string.memo_paid_done))
        // header: the outlet (or the memo number), the memo number and time, the reprint and supersede markers
        WhiteCard {
            Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
                Text(outletName ?: memo.memoNo, style = MaterialTheme.typography.titleSmall, color = look.purple, maxLines = 2)
                Text(stringResource(R.string.memo_row_meta, memo.memoNo, dhakaTime(memo.committedAtIso)), style = MaterialTheme.typography.bodySmall, color = look.inkMuted)
                if (detail.reprintIsDuplicate) MarkerLine(AronIcons.Print, stringResource(R.string.memo_duplicate))
                memo.supersedesMemoNo?.let { MarkerLine(AronIcons.Edit, stringResource(R.string.memo_replaces, localizedDigits(it))) }
            }
        }
        // the lines table: SKU, quantity in its own unit, price (৳)
        WhiteCard {
            Column(Modifier.fillMaxWidth().padding(AronTokens.Space.M), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                TableHeader(stringResource(R.string.memo_col_sku), stringResource(R.string.memo_col_qty), stringResource(R.string.memo_col_price))
                detail.items.forEach { i -> TableRow(skuName(i.skuId), lineQtyText(i.qtyEntered, i.unitEntered, skuCategory(i.skuId), i.qtyBase, skuUnit(i.skuId)), amount(i.grossMtk)) }
                // M3: a money subtotal per category, in first-seen order (quantities of different units are never added)
                detail.items.filter { MemoCategories.label(skuCategory(it.skuId)).isNotBlank() }.groupBy { skuCategory(it.skuId) }.forEach { (code, items) ->
                    TableRow2(stringResource(R.string.memo_total_category, MemoCategories.label(code)), amount(items.sumOf { it.grossMtk }))
                }
                if (detail.discountTable.isNotEmpty()) {
                    CardDivider()
                    TableHeader(stringResource(R.string.memo_discount_section), stringResource(R.string.memo_col_qty), stringResource(R.string.summary_col_value))
                    detail.discountTable.forEach { d ->
                        TableRow(d.skuId?.let(skuName) ?: stringResource(R.string.memo_discount_memo_level), qtyText(d.qtyBase, d.skuId?.let(skuUnit).orEmpty()), amount(d.valueMtk))
                    }
                }
                if (detail.totalQcMtk != 0L) {
                    CardDivider()
                    TableRow(stringResource(R.string.memo_total_qc), "", amount(detail.totalQcMtk))
                }
            }
        }
        // totals: gross, the discount and QC lines (zero too, as the manual p44 and the print show them) and net, each with its word and a true minus
        WhiteCard {
            Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                TotalLine(stringResource(R.string.memo_total), money(memo.grossMtk))
                DeductionLine(stringResource(R.string.memo_total_discount), minusMoney(detail.totalDiscountMtk))
                DeductionLine(stringResource(R.string.memo_total_qc), minusMoney(detail.totalQcMtk))
                CardDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.memo_grand_total), style = MaterialTheme.typography.titleMedium, color = look.purple, modifier = Modifier.weight(1f))
                    MoneyText(money(detail.grandTotalMtk), style = MaterialTheme.typography.headlineMedium, color = look.purple, textAlign = TextAlign.End)
                }
            }
        }
        if (dueMtk > 0) CreditBox(money(dueMtk), canMarkPaid) { confirm = true }
        // a disabled action always says why, above the buttons
        listOfNotNull(printDisabledReason, editDisabledReason).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = look.inkMuted) }
        AronPrimaryButton(stringResource(R.string.memo_print), onPrint, Modifier.fillMaxWidth(), enabled = printDisabledReason == null && !printing, glyph = ButtonGlyph.Print)
        AronSolidButton(stringResource(R.string.memo_edit), onEdit, Modifier.fillMaxWidth(), tone = SolidTone.Amber, enabled = editDisabledReason == null)
        if (onHistory != null) AronTextButton(stringResource(R.string.history_title), onHistory, Modifier.fillMaxWidth())
    }
    if (confirm) {
        AronConfirmDialog(
            stringResource(R.string.memo_mark_paid_title), stringResource(R.string.memo_mark_paid_confirm), stringResource(R.string.memo_yes), stringResource(R.string.memo_no),
            onConfirm = { confirm = false; onMarkPaid() }, onDismiss = { confirm = false },
        )
    }
}

/** The credit box: the pink notice card with the red edge, the due, and the green "Mark paid" inside it. */
@Composable
private fun CreditBox(dueText: String, canMarkPaid: Boolean, onMarkPaid: () -> Unit) {
    val look = LocalAronLook.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(AronLook.CardRadius)).background(Color(0xFFE6C8CA))) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(look.red))
        Column(Modifier.weight(1f).padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            Text(stringResource(R.string.memo_credit_title), style = MaterialTheme.typography.titleSmall, color = look.ink)
            Text(stringResource(R.string.memo_due, dueText), style = MaterialTheme.typography.bodyLarge, color = look.ink)
            if (canMarkPaid) AronSolidButton(stringResource(R.string.memo_mark_paid), onMarkPaid, Modifier.fillMaxWidth(), tone = SolidTone.Green) // the manual's green "পরিশোধিত করুন" (M6)
        }
    }
}

@Composable
private fun MarkerLine(icon: ImageVector, text: String) {
    val look = LocalAronLook.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        Icon(icon, contentDescription = null, tint = look.inkMuted, modifier = Modifier.size(AronTokens.Size.IconSmall))
        Text(text, style = MaterialTheme.typography.bodySmall, color = look.inkMuted)
    }
}

/** A 44 dp round well in `aron.purple` with a white 24 dp glyph. */
@Composable
internal fun IconWell(icon: ImageVector) {
    val look = LocalAronLook.current
    Box(Modifier.size(AronTokens.Size.Well).clip(CircleShape).background(look.purple), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(AronTokens.Size.Icon))
    }
}

/** A word with an icon in plain ink (status never by colour alone); [container] is kept for the callers that still pass one. */
@Composable
internal fun Pill(container: Color?, ink: Color, icon: ImageVector, label: String) {
    Row(
        Modifier
            .heightIn(min = AronTokens.Size.Chip)
            .drawBehind { container?.let { drawRoundRect(it, cornerRadius = CornerRadius(AronTokens.Radius.Chip.toPx())) } }
            .padding(horizontal = if (container != null) 10.dp else 0.dp, vertical = AronTokens.Space.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(AronTokens.Space.L))
        Spacer(Modifier.width(AronTokens.Space.S))
        Text(label, style = MaterialTheme.typography.labelMedium, color = ink, maxLines = 1)
    }
}

/** A hairline divider inside a card. */
@Composable
internal fun CardDivider() {
    val c = LocalAronLook.current.tileHairline
    Spacer(Modifier.fillMaxWidth().height(1.dp).drawBehind { drawRect(c) })
}

/**
 * The dense table header: the dark `#4D4D4D` strip with white 12 sp heads (PARITY-SPEC s1), numbers right-aligned under their heads. Below
 * `fontScale.vertical` the number columns have their least widths (a wider number grows its column, the name gives way);
 * from it the heads sit at the end of one line and every row stacks its numbers under the name ([tablesStacked]).
 */
@Composable
internal fun TableHeader(name: String, qty: String, value: String) {
    val look = LocalAronLook.current
    val stacked = tablesStacked()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(look.tableHeader).padding(horizontal = AronTokens.Space.M, vertical = AronTokens.Space.M),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
    ) {
        Text(name, style = MaterialTheme.typography.labelMedium, color = Color.White, modifier = Modifier.weight(1f))
        Text(qty, style = MaterialTheme.typography.labelMedium, color = Color.White, textAlign = TextAlign.End, modifier = if (stacked) Modifier else Modifier.widthIn(min = QtyColumn))
        Text(value, style = MaterialTheme.typography.labelMedium, color = Color.White, textAlign = TextAlign.End, modifier = if (stacked) Modifier else Modifier.widthIn(min = PriceColumn))
    }
}

/** One table line: the name wraps to two lines, the quantity and the amount never wrap or clip (they stack under the name from `fontScale.vertical`). */
@Composable
internal fun TableRow(name: String, qty: String, value: String) {
    val look = LocalAronLook.current
    if (tablesStacked()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.M), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = look.purple, maxLines = 2)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                if (qty.isNotEmpty()) MoneyText(qty, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = look.ink) else Spacer(Modifier.weight(1f))
                MoneyText(value, style = MaterialTheme.typography.titleSmall, color = look.ink, textAlign = TextAlign.End)
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.M), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        Text(name, style = MaterialTheme.typography.bodyLarge, color = look.purple, maxLines = 2, modifier = Modifier.weight(1f))
        MoneyText(qty, Modifier.widthIn(min = QtyColumn), style = MaterialTheme.typography.titleSmall, color = look.ink, textAlign = TextAlign.End)
        MoneyText(value, Modifier.widthIn(min = PriceColumn), style = MaterialTheme.typography.titleSmall, color = look.ink, textAlign = TextAlign.End)
    }
}

/** The two-column table header (name and amount) of the sale history: no empty quantity column. */
@Composable
internal fun TableHeader2(name: String, value: String) {
    val look = LocalAronLook.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(look.tableHeader).padding(horizontal = AronTokens.Space.M, vertical = AronTokens.Space.M),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
    ) {
        Text(name, style = MaterialTheme.typography.labelMedium, color = Color.White, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelMedium, color = Color.White, textAlign = TextAlign.End, modifier = if (tablesStacked()) Modifier else Modifier.widthIn(min = PriceColumn))
    }
}

/** One two-column line (name and amount): the amount takes the width it needs, the name wraps to two lines. */
@Composable
internal fun TableRow2(name: String, value: String) {
    val look = LocalAronLook.current
    Row(Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.M), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        Text(name, style = MaterialTheme.typography.bodyLarge, color = look.purple, maxLines = 2, modifier = Modifier.weight(1f))
        MoneyText(value, if (tablesStacked()) Modifier else Modifier.widthIn(min = PriceColumn), style = MaterialTheme.typography.titleSmall, color = look.purple, textAlign = TextAlign.End)
    }
}

/** A total line: the word and the amount with its sign. */
@Composable
internal fun TotalLine(label: String, value: String) {
    val look = LocalAronLook.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = look.ink, modifier = Modifier.weight(1f))
        MoneyText(value, style = MaterialTheme.typography.titleSmall, color = look.ink, textAlign = TextAlign.End)
    }
}

/** A deduction line: the word and "− 437.50 ৳" (the true minus is part of the text, so it is never colour alone). */
@Composable
internal fun DeductionLine(label: String, value: String) {
    val look = LocalAronLook.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = look.ink, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall, color = look.ink, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
    }
}

/** A caption over a value (the group foot pairs of screens-sr s10). */
@Composable
internal fun CaptionValue(caption: String, value: String, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Column(modifier) {
        Text(caption, style = MaterialTheme.typography.bodySmall, color = look.inkMuted, maxLines = 1)
        MoneyText(value, style = MaterialTheme.typography.titleSmall, color = look.ink)
    }
}

/**
 * Day summary (F-SR-036; PARITY-SPEC sr/summary.png): the info card, then the [SummaryTable] (dark header, per SKU a purple name over
 * its memo count, quantity, value, discount, discounted value and return; one subtotal row per category with its unit in the label,
 * so quantities are never summed across units; "discount and others (-)" and the grand total) and the full-width Print button.
 * [unitLabel] names the unit word of a category code (stick, piece, dozen). The discount line stays zero while the discount
 * programmes are deferred (docs/27).
 */
@Composable
fun SummaryScreen(
    summary: DaySummary,
    skuName: (Long) -> String,
    onPrint: () -> Unit,
    modifier: Modifier = Modifier,
    categoryLabel: (String) -> String = { it },
    unitLabel: (String) -> String = { "" },
    /** The unit word of a SKU's counted figures when the summary does not carry one itself (older callers). */
    skuUnit: (Long) -> String = { "" },
    printing: Boolean = false,
    /** The printer state of the info card (the host passes `AronPrinterBadge`); null shows none. */
    printerBadge: (@Composable () -> Unit)? = null,
) {
    val look = LocalAronLook.current
    val empty = summary.skus.isEmpty()
    // the SKU name with the word its figures are counted in: "MaxR-10S (প্যাকেট)"; every quantity of the row carries it (docs/34 s4.2)
    @Composable fun skuTitle(r: SkuSummaryRow): String {
        val word = if (r.unitKey.isNotEmpty()) entryUnitLabel(r.unitKey) else skuUnit(r.skuId)
        return if (word.isEmpty()) skuName(r.skuId) else skuName(r.skuId) + " (" + word + ")"
    }
    @Composable fun categoryUnit(c: CategorySummaryRow): String = if (c.unitKey.isNotEmpty()) entryUnitLabel(c.unitKey) else unitLabel(c.categoryCode)
    val printButton: @Composable () -> Unit = {
        AronPrimaryButton(
            stringResource(R.string.summary_print), onPrint, Modifier.fillMaxWidth(),
            enabled = !empty && !printing, glyph = ButtonGlyph.Print, reverse = true,
        )
    }
    if (empty || tablesStacked()) {
        // an empty day, or large text: the page scrolls as one and each SKU stacks its figures as caption-over-value pairs
        ScrollColumn(modifier.background(look.page), contentPadding = PaddingValues(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            AronInfoCard(stringResource(R.string.summary_banner), trailing = printerBadge)
            if (empty) {
                Text(
                    stringResource(R.string.summary_empty), style = MaterialTheme.typography.bodyLarge, color = look.inkMuted, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = AronTokens.Space.Xl),
                )
            } else {
                WhiteCard {
                    Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                        summary.skus.forEach { r ->
                            Text(skuTitle(r), style = MaterialTheme.typography.titleMedium, color = look.purple)
                            SummaryPairs(
                                stringResource(R.string.summary_col_memos) to localizedNumber(r.memoCount.toLong()), stringResource(R.string.summary_col_qty) to remFigure(r.qtyEntered, r.qtyRemBase),
                                stringResource(R.string.summary_col_value) to takaAmount(r.valueMtk), stringResource(R.string.summary_col_discount) to amount(r.discountMtk),
                                stringResource(R.string.summary_col_after) to takaAmount(r.discountedValueMtk), stringResource(R.string.summary_col_return) to remFigure(r.returnEntered, r.returnRemBase),
                            )
                            CardDivider()
                        }
                        summary.categories.forEach { cat ->
                            val unit = categoryUnit(cat)
                            val name = categoryLabel(cat.categoryCode)
                            Text(if (unit.isEmpty()) name else stringResource(R.string.summary_group_head, name, unit), style = MaterialTheme.typography.titleSmall, color = look.purple)
                            // the whole quantity on its own full-width line: "3 packets + 5 sticks (65 sticks)" never gets cut
                            Text(stringResource(R.string.summary_col_qty) + ": " + catQtyText(cat, unit), style = MaterialTheme.typography.bodyMedium, color = look.ink)
                            SummaryPairs(
                                stringResource(R.string.summary_col_value) to takaAmount(cat.valueMtk),
                                stringResource(R.string.summary_col_discount) to amount(cat.discountMtk), stringResource(R.string.summary_col_after) to takaAmount(cat.discountedValueMtk),
                                stringResource(R.string.summary_col_return) to remFigure(cat.returnEntered, cat.returnRemBase),
                            )
                        }
                        CardDivider()
                        Text(stringResource(R.string.summary_discount_and_others), style = MaterialTheme.typography.titleMedium, color = look.purple)
                        MoneyText(discountLine(summary.discountAndOthersMtk), style = MaterialTheme.typography.titleMedium, color = look.purple)
                        Text(stringResource(R.string.summary_grand_total), style = MaterialTheme.typography.titleMedium, color = look.purple)
                        MoneyText(amount(summary.grandTotalMtk), style = MaterialTheme.typography.headlineMedium, color = look.purple)
                    }
                }
            }
            printButton()
            if (empty) Text(stringResource(R.string.summary_print_reason), style = MaterialTheme.typography.bodySmall, color = look.inkMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        return
    }
    // sr/summary.png: the info card, the dark-headed SKU list (it scrolls), the category and total block docked below it on its own
    // #F0F0F0 panel, then Print. Under an unbounded host (a screen that still scrolls) the list takes its full height.
    BoxWithConstraints(modifier.background(look.page)) {
        val bounded = constraints.hasBoundedHeight
        Column(Modifier.then(if (bounded) Modifier.fillMaxSize() else Modifier).padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            AronInfoCard(stringResource(R.string.summary_banner), trailing = printerBadge)
            Column(Modifier.then(if (bounded) Modifier.weight(1f, fill = false) else Modifier).clip(RoundedCornerShape(AronLook.CardRadius))) {
                Column(Modifier.then(if (bounded) Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()) else Modifier)) {
                    SummaryTable(
                        headers = listOf(
                            stringResource(R.string.summary_col_memos), stringResource(R.string.summary_col_qty), stringResource(R.string.summary_col_value),
                            stringResource(R.string.summary_col_discount), stringResource(R.string.summary_col_after), stringResource(R.string.summary_col_return),
                        ),
                        rows = summary.skus.map { r ->
                            SummaryRow(
                                skuTitle(r),
                                listOf(
                                    localizedNumber(r.memoCount.toLong()), remFigure(r.qtyEntered, r.qtyRemBase), takaAmount(r.valueMtk),
                                    amount(r.discountMtk), takaAmount(r.discountedValueMtk), remFigure(r.returnEntered, r.returnRemBase),
                                ),
                            )
                        },
                        weights = SummaryColumns.shares,
                    )
                }
                SummaryDock(summary, categoryLabel) { categoryUnit(it) }
            }
            printButton()
        }
    }
}

/** The docked block of sr/summary.png on its own `#F0F0F0` panel: a purple row per category (quantity with its unit as a small suffix), "discount and others (-)" and the grand total. */
@Composable
private fun SummaryDock(summary: DaySummary, categoryLabel: (String) -> String, unitOf: @Composable (CategorySummaryRow) -> String) {
    val weights = SummaryColumns.shares
    val look = LocalAronLook.current
    val figure = TextStyle(fontSize = 13.sp, color = look.purple, textAlign = TextAlign.End)
    Column(Modifier.fillMaxWidth().background(look.rowAlt).padding(horizontal = 12.dp, vertical = 8.dp)) {
        summary.categories.forEach { cat ->
            val unit = unitOf(cat)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                Text(categoryLabel(cat.categoryCode), style = TextStyle(fontSize = 13.sp, color = look.purple), softWrap = false, modifier = Modifier.weight(weights[0]))
                Text(
                    buildAnnotatedString {
                        append(localizedNumber(cat.qtyEntered))
                        // "3 packets + 5 sticks (65 sticks)": the remainder carries its own word, the bracket is the base total
                        if (unit.isNotEmpty()) withStyle(SpanStyle(fontSize = 10.sp)) {
                            append(" "); append(unit)
                            if (cat.qtyRemBase != 0L) { append(" "); append(com.aktcl.aron.core.ui.remWithUnit(cat.qtyRemBase, entryUnitLabel(com.aktcl.aron.rules.EntryUnits.baseLabelKey(cat.categoryCode)))) }
                            baseBracket(cat)?.let { append(" "); append(it) }
                        } else if (cat.qtyRemBase != 0L) append(com.aktcl.aron.core.ui.remSuffix(cat.qtyRemBase))
                    },
                    style = figure, maxLines = 3, modifier = Modifier.weight(weights[1]),
                )
                Text(takaAmount(cat.valueMtk), style = figure, softWrap = false, modifier = Modifier.weight(weights[2]))
                Text(amount(cat.discountMtk), style = figure, softWrap = false, modifier = Modifier.weight(weights[3]))
                Text(takaAmount(cat.discountedValueMtk), style = figure, softWrap = false, modifier = Modifier.weight(weights[4]))
                Text(remFigure(cat.returnEntered, cat.returnRemBase), style = figure, modifier = Modifier.weight(weights[5]))
            }
        }
        val big = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = look.purple)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.summary_discount_and_others), style = big, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f))
            Text(discountLine(summary.discountAndOthersMtk), style = big.copy(textAlign = TextAlign.End), maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.summary_grand_total), style = big.copy(fontSize = 22.sp), maxLines = 1, modifier = Modifier.weight(1f))
            Text(amount(summary.grandTotalMtk), style = big.copy(fontSize = 22.sp, textAlign = TextAlign.End), maxLines = 1, softWrap = false)
        }
    }
}

/** "4,687.50৳": the taka sign glued to the figure, as the value cells of sr/summary.png. */
@Composable
private fun takaAmount(mtk: Long): String = amount(mtk) + TAKA

/** "-437.50": the deduction line of sr/summary.png, an ASCII hyphen and no sign or space. */
@Composable
private fun discountLine(mtk: Long): String = "-" + amount(kotlin.math.abs(mtk))

private const val TAKA = "\u09F3"

/** Caption-over-value cells two to a row (the stacked Summary at large text); an odd last cell takes a row's half. */
@Composable
private fun SummaryPairs(vararg cells: Pair<String, String>) {
    cells.toList().chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            row.forEach { (caption, value) -> CaptionValue(caption, value, Modifier.weight(1f)) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** `12` or `12+5`: whole entry units, then the base units left over (never floored away; the unit word sits in the row title). */
@Composable
internal fun remFigure(whole: Long, remBase: Long): String = localizedNumber(whole) + com.aktcl.aron.core.ui.remSuffix(remBase)

/** `(70 sticks)`: the base figure of a category line in brackets, only when the entry unit is not the base unit; null otherwise. */
@Composable
internal fun baseBracket(cat: CategorySummaryRow): String? {
    val base = com.aktcl.aron.rules.EntryUnits.baseLabelKey(cat.categoryCode)
    return if (cat.unitKey.isEmpty() || cat.unitKey == base) null else "(" + localizedNumber(cat.qtyBase) + " " + entryUnitLabel(base) + ")"
}

/** A category's quantity: `3 packets (70 sticks)`, with `+ 5 sticks` when it is not whole entry units; the base figure alone when its SKUs share no unit. */
@Composable
internal fun catQtyText(cat: CategorySummaryRow, unit: String): String {
    val base = com.aktcl.aron.rules.EntryUnits.baseLabelKey(cat.categoryCode)
    val main = if (cat.qtyRemBase == 0L) qtyText(cat.qtyEntered, unit)
    else stringResource(R.string.summary_qty_rem, localizedNumber(cat.qtyEntered), unit, com.aktcl.aron.core.ui.remWithUnit(cat.qtyRemBase, entryUnitLabel(base)))
    return main + (baseBracket(cat)?.let { " $it" } ?: "")
}

/**
 * A stored line's quantity as the rep counted it, with its unit word ("3 packets", "2 boxes", "5 dozen"; docs/34 s4.2). Lines
 * with no stored unit show the base quantity with the host's base-unit word.
 */
@Composable
internal fun lineQtyText(qtyEntered: Long, unitEntered: String, category: String?, qtyBase: Long, baseUnitWord: String): String =
    if (unitEntered.isNotEmpty()) qtyText(qtyEntered, entryUnitLabel(category.orEmpty(), unitEntered)) else qtyText(qtyBase, baseUnitWord)

/** "375 piece": a quantity in its own unit, or the bare number when the unit is unknown. */
@Composable
internal fun qtyText(qty: Long, unit: String): String =
    if (unit.isEmpty()) localizedNumber(qty) else stringResource(R.string.summary_qty_unit, localizedNumber(qty), unit)
