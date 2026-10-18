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
import com.aktcl.aron.core.ui.AronBarAction
import com.aktcl.aron.core.ui.AronBottomBar
import com.aktcl.aron.core.ui.AronCard
import com.aktcl.aron.core.ui.AronConfirmDialog
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.AronYesButton
import com.aktcl.aron.core.ui.AronSectionLabel
import com.aktcl.aron.core.ui.AronTextButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.MoneyText
import com.aktcl.aron.core.ui.RowPosition
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.feature.memo.R
import com.aktcl.aron.feature.memo.domain.DaySummary
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

/**
 * The Memo menu (F-SR-030; screens-sr s9): the info banner (dismissible per day: [onDismissBanner] fires and the host
 * remembers the business date, [bannerDismissed] hides it), then today's live memos as flat rows (outlet, memo number and
 * time, the total and a due chip); selecting one shows [MemoDetailView]. A query on local memos: offline, always.
 */
@Composable
fun MemoMenuScreen(
    rows: List<MemoMenuRow>,
    outletName: (Long) -> String,
    onOpen: (MemoMenuRow) -> Unit,
    modifier: Modifier = Modifier,
    bannerDismissed: Boolean = false,
    onDismissBanner: (() -> Unit)? = null,
) {
    val c = LocalAronColors.current
    ScrollColumn(modifier) {
        if (!bannerDismissed) {
            AronBanner(
                stringResource(R.string.memo_banner), Modifier.padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.M), BannerKind.Info,
                dismissLabel = if (onDismissBanner != null) stringResource(R.string.memo_banner_dismiss) else null, onDismiss = onDismissBanner,
            )
        }
        if (rows.isEmpty()) {
            AronEmptyState(stringResource(R.string.memo_empty), icon = AronIcons.Memo)
        } else {
            AronSectionLabel(stringResource(R.string.memo_section_today))
            Column(Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.Screen)) {
                rows.forEachIndexed { i, r ->
                    AronListRow(
                        outletName(r.outletId),
                        subtitle = stringResource(R.string.memo_row_meta, localizedDigits(shortMemoNo(r.memoNo)), dhakaTime(r.committedAtIso)),
                        onClick = { onOpen(r) },
                        flat = true,
                        position = rowPosition(i, rows.size),
                        divider = i < rows.lastIndex,
                        trailingContent = {
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
                                MoneyText(money(r.totalMtk), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.End)
                                if (r.dueMtk > 0) Pill(c.warningContainer, c.warningOnContainer, AronIcons.Warning, stringResource(R.string.memo_due_chip_amount, money(r.dueMtk)))
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(AronTokens.Space.L))
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
 * Memo detail (F-SR-030/031/032/033; screens-sr s9): the header card, the lines table (no per-row sign, "(৳)" in the
 * header), the discount and QC sections only when non-zero, the totals with a true minus, the credit box as a warning
 * container with the opaque "Mark paid" secondary, the reprint marker, and the bar with Print (primary) and Edit
 * (secondary). [canMarkPaid] must come from `DueLedger.markPaid(...) != null` (not the stored due), so the button
 * disappears after the collection. [editDisabledReason] disables Edit and says why (outside the geofence, QC done);
 * [printDisabledReason] does the same for Print (the reprint limit); [onHistory] adds the outlet's sale history link.
 * [skuUnit] names the unit word of a SKU (stick, piece, dozen): it sits beside every quantity (screens-sr s0), the bare
 * number only when the unit is unknown.
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
    val c = LocalAronColors.current
    var confirm by remember { mutableStateOf(false) }
    val memo = detail.memo
    ScreenWithBar(modifier, bar = {
        Column(Modifier.fillMaxWidth()) {
            // a disabled action always says why above the bar (android-glass s6.1): the bar carries the primary's reason (both
            // reasons when both are disabled); when only Edit is disabled its reason is the caption above the bar
            if (printDisabledReason == null && editDisabledReason != null) {
                Text(
                    editDisabledReason, style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
                    modifier = Modifier.padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S),
                )
            }
            AronBottomBar(
                secondary = AronBarAction(stringResource(R.string.memo_edit), onEdit, enabled = editDisabledReason == null, icon = AronIcons.Edit),
                primary = AronBarAction(stringResource(R.string.memo_print), onPrint, enabled = printDisabledReason == null, icon = AronIcons.Print, busy = printing),
                reasonWhenDisabled = printDisabledReason?.let { p -> listOfNotNull(p, editDisabledReason).joinToString(" ") },
            )
        }
    }) {
        ScrollColumn(contentPadding = PaddingValues(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            if (paidBanner) AronBanner(stringResource(R.string.memo_paid_done), kind = BannerKind.Success)
            // header: the outlet (or the memo number), the memo number and time, the reprint and supersede markers
            AronCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                        IconWell(AronIcons.Memo)
                        Column(Modifier.weight(1f)) {
                            Text(outletName ?: memo.memoNo, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 2)
                            Text(stringResource(R.string.memo_row_meta, memo.memoNo, dhakaTime(memo.committedAtIso)), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                        }
                    }
                    if (detail.reprintIsDuplicate) MarkerLine(AronIcons.Print, stringResource(R.string.memo_duplicate))
                    memo.supersedesMemoNo?.let { MarkerLine(AronIcons.Edit, stringResource(R.string.memo_replaces, localizedDigits(it))) }
                }
            }
            // the lines table: SKU, quantity in its own unit, price (৳)
            AronCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                    TableHeader(stringResource(R.string.memo_col_sku), stringResource(R.string.memo_col_qty), stringResource(R.string.memo_col_price))
                    detail.items.forEach { i -> TableRow(skuName(i.skuId), qtyText(i.qtyBase, skuUnit(i.skuId)), amount(i.grossMtk)) }
                    // M3: a money subtotal per category, in first-seen order (quantities of different units are never added)
                    detail.items.filter { MemoCategories.label(skuCategory(it.skuId)).isNotBlank() }.groupBy { skuCategory(it.skuId) }.forEach { (code, items) ->
                        TableRow2(stringResource(R.string.memo_total_category, MemoCategories.label(code)), amount(items.sumOf { it.grossMtk }))
                    }
                    if (detail.discountTable.isNotEmpty()) {
                        CardDivider()
                        Text(stringResource(R.string.memo_discount_section), style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
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
            AronCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                    TotalLine(stringResource(R.string.memo_total), money(memo.grossMtk))
                    DeductionLine(stringResource(R.string.memo_total_discount), minusMoney(detail.totalDiscountMtk))
                    DeductionLine(stringResource(R.string.memo_total_qc), minusMoney(detail.totalQcMtk))
                    CardDivider()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.memo_grand_total), style = MaterialTheme.typography.titleSmall, color = c.textPrimary, modifier = Modifier.weight(1f))
                        MoneyText(money(detail.grandTotalMtk), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.End)
                    }
                }
            }
            if (dueMtk > 0) CreditBox(money(dueMtk), canMarkPaid) { confirm = true }
            if (onHistory != null) AronTextButton(stringResource(R.string.history_title), onHistory, Modifier.fillMaxWidth(), icon = AronIcons.Calendar)
        }
    }
    if (confirm) {
        AronConfirmDialog(
            stringResource(R.string.memo_mark_paid_title), stringResource(R.string.memo_mark_paid_confirm), stringResource(R.string.memo_yes), stringResource(R.string.memo_no),
            onConfirm = { confirm = false; onMarkPaid() }, onDismiss = { confirm = false },
        )
    }
}

/** The credit box: a `warning.container` card (icon plus words), the due, and the OPAQUE "Mark paid" secondary inside it. */
@Composable
private fun CreditBox(dueText: String, canMarkPaid: Boolean, onMarkPaid: () -> Unit) {
    val c = LocalAronColors.current
    val bg = c.warningContainer; val ink = c.warningOnContainer; val edge = c.warning.copy(alpha = AronTokens.Alpha.BannerEdge)
    val stroke = if (c.sunlight) AronTokens.Stroke.SunlightHairline else AronTokens.Stroke.Hairline
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val r = CornerRadius(AronTokens.Radius.Card.toPx())
                drawRoundRect(bg, cornerRadius = r)
                val w = stroke.toPx()
                drawRoundRect(edge, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(r.x - w / 2), style = Stroke(w))
            }
            .padding(AronTokens.Space.L),
        verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            Icon(AronIcons.Warning, contentDescription = null, tint = ink, modifier = Modifier.size(AronTokens.Size.Icon))
            Text(stringResource(R.string.memo_credit_title), style = MaterialTheme.typography.titleSmall, color = ink, modifier = Modifier.weight(1f))
        }
        Text(stringResource(R.string.memo_due, dueText), style = MaterialTheme.typography.bodyLarge, color = ink)
        if (canMarkPaid) AronYesButton(stringResource(R.string.memo_mark_paid), onMarkPaid, Modifier.fillMaxWidth()) // the manual's green "পরিশোধিত করুন" (M6)
    }
}

@Composable
private fun MarkerLine(icon: ImageVector, text: String) {
    val c = LocalAronColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        Icon(icon, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(AronTokens.Size.IconSmall))
        Text(text, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
    }
}

/** A 44 dp squircle well in `accent.container` with a 24 dp glyph (android-glass s6.4). */
@Composable
internal fun IconWell(icon: ImageVector) {
    val c = LocalAronColors.current
    val fill = c.accentContainer
    Box(Modifier.size(AronTokens.Size.Well).drawBehind { drawRoundRect(fill, cornerRadius = CornerRadius(AronTokens.Radius.Well.toPx())) }, contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = c.accentOnContainer, modifier = Modifier.size(AronTokens.Size.Icon))
    }
}

/** A 28 dp pill with an icon and a word in opaque container colours (status never by colour alone). */
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
    val c = LocalAronColors.current
    val color = c.borderDivider
    val h = if (c.sunlight) AronTokens.Stroke.SunlightHairline else AronTokens.Stroke.Hairline
    Spacer(Modifier.fillMaxWidth().height(h).drawBehind { drawRect(color) })
}

/**
 * The dense table header: `type.label` in `text.secondary`, numbers right-aligned under their heads. Below
 * `fontScale.vertical` the number columns have their least widths (a wider number grows its column, the name gives way);
 * from it the heads sit at the end of one line and every row stacks its numbers under the name ([tablesStacked]).
 */
@Composable
internal fun TableHeader(name: String, qty: String, value: String) {
    val c = LocalAronColors.current
    val stacked = tablesStacked()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        Text(name, style = MaterialTheme.typography.labelMedium, color = c.textSecondary, modifier = Modifier.weight(1f))
        Text(qty, style = MaterialTheme.typography.labelMedium, color = c.textSecondary, textAlign = TextAlign.End, modifier = if (stacked) Modifier else Modifier.widthIn(min = QtyColumn))
        Text(value, style = MaterialTheme.typography.labelMedium, color = c.textSecondary, textAlign = TextAlign.End, modifier = if (stacked) Modifier else Modifier.widthIn(min = PriceColumn))
    }
}

/** One table line: the name wraps to two lines, the quantity and the amount never wrap or clip (they stack under the name from `fontScale.vertical`). */
@Composable
internal fun TableRow(name: String, qty: String, value: String) {
    val c = LocalAronColors.current
    if (tablesStacked()) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, maxLines = 2)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                if (qty.isNotEmpty()) MoneyText(qty, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall) else Spacer(Modifier.weight(1f))
                MoneyText(value, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.End)
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        Text(name, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, maxLines = 2, modifier = Modifier.weight(1f))
        MoneyText(qty, Modifier.widthIn(min = QtyColumn), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.End)
        MoneyText(value, Modifier.widthIn(min = PriceColumn), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.End)
    }
}

/** The two-column table header (name and amount) of the sale history: no empty quantity column. */
@Composable
internal fun TableHeader2(name: String, value: String) {
    val c = LocalAronColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        Text(name, style = MaterialTheme.typography.labelMedium, color = c.textSecondary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelMedium, color = c.textSecondary, textAlign = TextAlign.End, modifier = if (tablesStacked()) Modifier else Modifier.widthIn(min = PriceColumn))
    }
}

/** One two-column line (name and amount): the amount takes the width it needs, the name wraps to two lines. */
@Composable
internal fun TableRow2(name: String, value: String) {
    val c = LocalAronColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        Text(name, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, maxLines = 2, modifier = Modifier.weight(1f))
        MoneyText(value, if (tablesStacked()) Modifier else Modifier.widthIn(min = PriceColumn), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.End)
    }
}

/** A total line: the word and the amount with its sign. */
@Composable
internal fun TotalLine(label: String, value: String) {
    val c = LocalAronColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, modifier = Modifier.weight(1f))
        MoneyText(value, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.End)
    }
}

/** A deduction line: the word and "− 437.50 ৳" (the true minus is part of the text, so it is never colour alone). */
@Composable
internal fun DeductionLine(label: String, value: String) {
    val c = LocalAronColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, textAlign = TextAlign.End, maxLines = 1, softWrap = false)
    }
}

/** A caption over a value (the group foot pairs of screens-sr s10). */
@Composable
internal fun CaptionValue(caption: String, value: String, modifier: Modifier = Modifier) {
    val c = LocalAronColors.current
    Column(modifier) {
        Text(caption, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1)
        MoneyText(value, style = MaterialTheme.typography.titleSmall)
    }
}

/**
 * Day summary (F-SR-036; screens-sr s10): the info banner, then per category a group head (category and unit), the SKU
 * rows (value, memos, quantity, return, the discount pair when non-zero) and the group foot (four caption-over-value
 * pairs), the totals card with "discount and others (−)", and the bar with the grand total in its summary slot beside
 * Print. [unitLabel] names the unit word of a category code (stick, piece, dozen); quantities are never summed across units.
 */
@Composable
fun SummaryScreen(
    summary: DaySummary,
    skuName: (Long) -> String,
    onPrint: () -> Unit,
    modifier: Modifier = Modifier,
    categoryLabel: (String) -> String = { it },
    unitLabel: (String) -> String = { "" },
    printing: Boolean = false,
) {
    val c = LocalAronColors.current
    val empty = summary.skus.isEmpty()
    ScreenWithBar(modifier, bar = {
        Column(Modifier.fillMaxWidth()) {
            AronBottomBar(
                summaryLabel = stringResource(R.string.summary_grand_total),
                summaryValue = money(summary.grandTotalMtk),
                primary = AronBarAction(stringResource(R.string.summary_print), onPrint, enabled = !empty, icon = AronIcons.Print, busy = printing),
                reasonWhenDisabled = stringResource(R.string.summary_print_reason),
            )
        }
    }) {
        ScrollColumn {
            AronBanner(stringResource(R.string.summary_banner), Modifier.padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.M), BannerKind.Info)
            if (empty) {
                AronEmptyState(stringResource(R.string.summary_empty), icon = AronIcons.Summary)
            } else {
                summary.categories.forEach { cat ->
                    val unit = unitLabel(cat.categoryCode)
                    val catName = categoryLabel(cat.categoryCode)
                    AronSectionLabel(if (unit.isEmpty()) catName else stringResource(R.string.summary_group_head, catName, unit))
                    AronCard(Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.Screen)) {
                        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                            summary.skus.filter { it.categoryCode == cat.categoryCode }.forEach { r ->
                                Column(verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(skuName(r.skuId), style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 2, modifier = Modifier.weight(1f))
                                        MoneyText(money(r.valueMtk), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.End)
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                                        CaptionValue(stringResource(R.string.summary_col_memos), localizedNumber(r.memoCount.toLong()), Modifier.weight(0.8f))
                                        CaptionValue(stringResource(R.string.summary_col_qty), qtyText(r.qtyBase, unit), Modifier.weight(1.2f))
                                        CaptionValue(stringResource(R.string.summary_col_return), qtyText(r.returnQtyBase, unit), Modifier.weight(1.2f))
                                    }
                                    if (r.discountMtk != 0L) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                                            Column(Modifier.weight(1f)) {
                                                Text(stringResource(R.string.summary_col_discount), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                                                Text(minusMoney(r.discountMtk), style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, softWrap = false)
                                            }
                                            CaptionValue(stringResource(R.string.summary_col_after), money(r.discountedValueMtk), Modifier.weight(1f))
                                        }
                                    }
                                }
                                CardDivider()
                            }
                            // the group foot: four caption-over-value pairs, each in its own unit
                            Text(stringResource(R.string.summary_group_total, catName), style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                                CaptionValue(stringResource(R.string.summary_col_qty), qtyText(cat.qtyBase, unit), Modifier.weight(1f))
                                CaptionValue(stringResource(R.string.summary_col_value), money(cat.valueMtk), Modifier.weight(1f))
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                                CaptionValue(stringResource(R.string.summary_col_after), money(cat.discountedValueMtk), Modifier.weight(1f))
                                CaptionValue(stringResource(R.string.summary_col_return), qtyText(cat.returnQtyBase, unit), Modifier.weight(1f))
                            }
                        }
                    }
                }
                AronSectionLabel(stringResource(R.string.summary_totals))
                AronCard(Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.Screen)) {
                    Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                        TotalLine(stringResource(R.string.memo_total), money(summary.grossMtk))
                        DeductionLine(stringResource(R.string.summary_discount_and_others), minusMoney(summary.discountAndOthersMtk))
                        Text(stringResource(R.string.summary_memo_count, localizedNumber(summary.memoCount.toLong())), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    }
                }
                Spacer(Modifier.height(AronTokens.Space.L))
            }
        }
    }
}

/** "375 piece": a quantity in its own unit, or the bare number when the unit is unknown. */
@Composable
internal fun qtyText(qty: Long, unit: String): String =
    if (unit.isEmpty()) localizedNumber(qty) else stringResource(R.string.summary_qty_unit, localizedNumber(qty), unit)
