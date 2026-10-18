package com.aktcl.aron.feature.stock

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.database.entity.SkuEntity
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronStepper
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.aron.PackshotImage
import com.aktcl.aron.core.ui.aron.packCategoryOf
import com.aktcl.aron.core.ui.GlassKind
import com.aktcl.aron.core.ui.GlassSurface
import com.aktcl.aron.core.ui.GlassTier
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.LocalGlassTier
import com.aktcl.aron.core.ui.MoneyText
import com.aktcl.aron.core.ui.RowPosition
import com.aktcl.aron.core.ui.localizedNumber

object StockTags {
    const val SAVE = "stk_save"
    const val MESSAGE = "stk_message"
    const val TOTALS = "stk_totals"
    /** The scrolling list of SKU rows; the totals panel is its last item. */
    const val LIST = "stk_list"
    const val PRINT = "stk_print"
    fun row(skuId: Long) = "stk_row_$skuId"
}

/** What the last Save (or print) said, shown as a banner above the list. */
enum class StockMessage { SAVED, REFUSED_SAME_VALUES, NOTHING_ENTERED, SAVE_FAILED, PRINT_FAILED }

/** Why the Print secondary is disabled (screens-sr s4): the reason is shown as a caption in the bar. */
enum class StockPrintBlock { SAVE_FIRST, PRINTER_OFF }

@Composable
private fun categoryLabel(code: String): String = when (code) {
    "cigarette" -> stringResource(R.string.stk_cat_cigarette)
    "bidi" -> stringResource(R.string.stk_cat_bidi)
    "lighter" -> stringResource(R.string.stk_cat_lighter)
    "match" -> stringResource(R.string.stk_cat_match)
    else -> code
}

@Composable
private fun unitLabel(unit: String): String = when (unit) {
    "piece" -> stringResource(R.string.stk_unit_piece)
    "dozen" -> stringResource(R.string.stk_unit_dozen)
    else -> stringResource(R.string.stk_unit_stick)
}

/** The order the groups appear in (screens-sr s4): cigarette, bidi, lighter, match, then anything else by code. */
private fun categoryRank(code: String): Int = when (code) { "cigarette" -> 0; "bidi" -> 1; "lighter" -> 2; "match" -> 3; else -> 4 }

/**
 * The Stock screen content (F-SR-014, screens-sr s4): a column head, SKU groups by category with the unit once, flat
 * rows (thumbnail, name, "loaded today" caption, the derived stock as a numeral, the Issue stepper on the second line),
 * the category dock above the bar, and the bar with Print (secondary) and Save (primary). It renders its own bar, so the
 * host puts nothing in the frame's bottom slot. Save never waits for the printer or the network (Q-UI-03).
 */
@Composable
fun StockContent(
    rows: List<StockRow>,
    totals: List<CategoryTotal>,
    message: StockMessage?,
    slipNotPrinted: Boolean,
    onIssue: (skuId: Long, qty: Int) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    softCeiling: Long = StockLoad.DEFAULT_SOFT_CEILING,
    onPrint: (() -> Unit)? = null,
    printEnabled: Boolean = true,
    /** Why Print is disabled; null derives it from [printEnabled] (false reads as the printer being off). */
    printBlock: StockPrintBlock? = null,
    /** "synced at" caption of the empty state, when the host knows it. */
    syncedAt: String? = null,
) {
    val c = LocalAronColors.current
    val groups = rows.groupBy { it.sku.categoryCode }.toList().sortedBy { categoryRank(it.first) }
    val unitByCategory = groups.associate { (code, rs) -> code to rs.first().sku.baseUnit }
    val hasChange = rows.any { it.entered > 0 }
    // the key figure is the stock in the bag only when the host supplies it; otherwise it is the total load, and says so
    val hasStock = rows.any { it.stockBase != null }
    val block = printBlock ?: if (printEnabled) null else StockPrintBlock.PRINTER_OFF
    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                when (message) {
                    StockMessage.SAVED -> AronBanner(
                        stringResource(R.string.stk_saved), Modifier.testTag(StockTags.MESSAGE), BannerKind.Success,
                        actionLabel = if (onPrint != null) stringResource(R.string.stk_print_short) else null, onAction = onPrint,
                    )
                    StockMessage.REFUSED_SAME_VALUES -> AronBanner(stringResource(R.string.stk_refused), Modifier.testTag(StockTags.MESSAGE), BannerKind.Warning)
                    StockMessage.NOTHING_ENTERED -> AronBanner(stringResource(R.string.stk_nothing), Modifier.testTag(StockTags.MESSAGE), BannerKind.Warning)
                    StockMessage.SAVE_FAILED -> AronBanner(stringResource(R.string.stk_save_failed), Modifier.testTag(StockTags.MESSAGE), BannerKind.Error)
                    StockMessage.PRINT_FAILED -> AronBanner(
                        stringResource(R.string.stk_print_failed), Modifier.testTag(StockTags.MESSAGE), BannerKind.Warning,
                        actionLabel = if (onPrint != null) stringResource(R.string.stk_retry) else null, onAction = onPrint,
                    )
                    null -> Unit
                }
                if (slipNotPrinted && message != StockMessage.SAVED) AronBanner(stringResource(R.string.stk_slip_not_printed), kind = BannerKind.Warning)
            }
            if (rows.isEmpty()) {
                AronEmptyState(stringResource(R.string.stk_empty), icon = AronIcons.Stock, syncedAt = syncedAt)
            } else {
                ColumnHead(hasStock)
                LazyColumn(Modifier.weight(1f).testTag(StockTags.LIST), contentPadding = PaddingValues(bottom = AronTokens.Space.L)) {
                    groups.forEach { (code, group) ->
                        item(key = "head_$code", contentType = "head") {
                            Text(
                                stringResource(R.string.stk_group, categoryLabel(code), unitLabel(unitByCategory.getValue(code))),
                                style = MaterialTheme.typography.labelMedium, color = c.textSecondary,
                                modifier = Modifier.fillMaxWidth().padding(start = AronTokens.Space.Screen, end = AronTokens.Space.Screen, top = AronTokens.Space.L, bottom = AronTokens.Space.S).semantics { heading() },
                            )
                        }
                        group.forEachIndexed { i, r ->
                            val position = when {
                                group.size == 1 -> RowPosition.Only
                                i == 0 -> RowPosition.First
                                i == group.lastIndex -> RowPosition.Last
                                else -> RowPosition.Middle
                            }
                            item(key = r.sku.skuId, contentType = "sku") {
                                StockSkuRow(r, position, divider = i != group.lastIndex, softCeiling = softCeiling, onIssue = onIssue)
                            }
                        }
                    }
                    // the totals panel closes the list, just above Save: pinned it would take a third of a small phone's screen
                    item(key = "totals", contentType = "totals") { CategoryDock(rows, totals, hasStock) }
                }
            }
        }
        StockBar(
            hasChange = hasChange, onSave = onSave, onPrint = onPrint, printBlock = block,
        )
    }
}

/**
 * The column head (screens-sr s4) in `type.label`: SKU, then Stock when the rows carry the on-hand figure, else Total
 * load. It names the two columns of line 1; the Issue word sits on line 2 beside the stepper, where that figure is.
 */
@Composable
private fun ColumnHead(hasStock: Boolean) {
    val c = LocalAronColors.current
    Row(Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.stk_col_sku), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
        Text(stringResource(if (hasStock) R.string.stk_col_stock else R.string.stk_col_loaded), style = MaterialTheme.typography.labelMedium, color = c.textSecondary, textAlign = TextAlign.End)
    }
}

/** The flat row paint of a long list (android-glass s6.3): `surface.glass.field`, `radius.card` on the first and last row. */
@Composable
private fun Modifier.flatRow(position: RowPosition): Modifier {
    val c = LocalAronColors.current
    val solid = LocalGlassTier.current == GlassTier.C || c.sunlight
    val r = AronTokens.Radius.Card
    val shape = when (position) {
        RowPosition.Only -> RoundedCornerShape(r)
        RowPosition.First -> RoundedCornerShape(topStart = r, topEnd = r)
        RowPosition.Last -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
        RowPosition.Middle -> RoundedCornerShape(0.dp)
    }
    return background(if (solid) c.surfaceSolid else c.surfaceField, shape)
}

/**
 * One SKU row (screens-sr s4, 136 dp): line 1 is the thumbnail slot (with the packs pill), the name in `type.bodyStrong`,
 * the "loaded today" caption and the key figure as a numeral over its label: the stock in the bag plus the increment
 * being entered when the host supplies [StockRow.stockBase], else the total load (loaded today plus the increment),
 * labelled as such so a number under "Stock" never misstates the bag after a sale. Line 2 is the Issue label at the
 * start and the stepper at the trailing edge with the unit beside it. Step 1 in the SKU's own unit (unchanged rule).
 *
 * TalkBack (android-glass s6.3): the row is ONE merged node reading "name, loaded today, stock, issue, packs" with the
 * stepper's plus and minus as custom actions; the stepper's own buttons stay touchable but leave the semantics tree.
 */
@Composable
private fun StockSkuRow(r: StockRow, position: RowPosition, divider: Boolean, softCeiling: Long, onIssue: (Long, Int) -> Unit) {
    val c = LocalAronColors.current
    val unit = unitLabel(r.sku.baseUnit)
    val dividerColor = c.borderDivider
    val dividerWidth = if (c.sunlight) AronTokens.Stroke.SunlightHairline else AronTokens.Stroke.Hairline
    val stock = r.stockBase
    val figure = (stock ?: r.loadedBase) + r.entered
    val packs = if (r.sku.basePerPack > 1) figure / r.sku.basePerPack else null
    val loadedCaption = stringResource(R.string.stk_loaded_today, localizedNumber(r.loadedBase), unit)
    val issueLabel = stringResource(R.string.stk_col_issue)
    val plusLabel = stringResource(R.string.stk_more)
    val minusLabel = stringResource(R.string.stk_less)
    val max = StockLoad.MAX_ENTRY.toInt()
    val summary = listOfNotNull(
        r.sku.shortName,
        loadedCaption,
        stringResource(if (stock != null) R.string.stk_col_stock else R.string.stk_col_loaded) + " " + localizedNumber(figure) + " " + unit,
        issueLabel + " " + localizedNumber(r.entered) + " " + unit,
        packs?.let { stringResource(R.string.stk_packs, localizedNumber(it)) },
    ).joinToString(", ")
    val actions = listOfNotNull(
        if (r.entered < max) CustomAccessibilityAction(plusLabel) { onIssue(r.sku.skuId, r.entered.toInt() + 1); true } else null,
        if (r.entered > 0) CustomAccessibilityAction(minusLabel) { onIssue(r.sku.skuId, r.entered.toInt() - 1); true } else null,
    )
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AronTokens.Space.Screen)
            .flatRow(position)
            .testTag(StockTags.row(r.sku.skuId))
            .semantics(mergeDescendants = true) {
                contentDescription = summary
                customActions = actions
            },
    ) {
        AronListRow(
            title = r.sku.shortName,
            subtitle = loadedCaption,
            leading = { SkuThumb(r.sku, packs) },
            trailingContent = {
                Column(horizontalAlignment = Alignment.End) {
                    MoneyText(localizedNumber(figure), style = MaterialTheme.typography.headlineLarge)
                    Text(
                        stringResource(if (stock != null) R.string.stk_stock_unit else R.string.stk_load_total_unit, unit),
                        style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1,
                    )
                }
            },
        )
        Row(
            Modifier.fillMaxWidth().padding(start = AronTokens.Space.L, end = AronTokens.Space.L, bottom = AronTokens.Space.M),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(issueLabel, style = MaterialTheme.typography.labelMedium, color = c.textSecondary, maxLines = 1)
            Spacer(Modifier.weight(1f))
            // the buttons stay touchable; the merged row above carries the value and the actions for TalkBack
            Box(Modifier.clearAndSetSemantics { }) {
                AronStepper(
                    value = r.entered.toInt(), onValueChange = { onIssue(r.sku.skuId, it) },
                    minusLabel = minusLabel, plusLabel = plusLabel,
                    max = max,
                )
            }
            Spacer(Modifier.size(AronTokens.Space.S))
            Text(unit, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1)
        }
        if (r.entered > softCeiling) {
            AronBanner(stringResource(R.string.stk_high), Modifier.padding(start = AronTokens.Space.L, end = AronTokens.Space.L, bottom = AronTokens.Space.M), BannerKind.Warning)
        }
        if (divider) {
            Spacer(
                Modifier.fillMaxWidth().padding(start = AronTokens.Space.L).height(dividerWidth)
                    .drawBehind { drawRect(dividerColor) },
            )
        }
    }
}

/**
 * The pack picture (PARITY-SPEC s2.5, s2.6, stock.md UI-SR-19): the SKU's cached thumbnail, or the neutral pack
 * silhouette with the category glyph when none is on the phone yet, never a letter circle. The packs equivalent rides as
 * a small `accent.container` pill at its bottom end (screens-sr s4, Q-UI-11, UI-SR-18) when the SKU is packed. The row's
 * merged description reads the packs; the picture itself is silent.
 */
@Composable
private fun SkuThumb(sku: SkuEntity, packs: Long?) {
    val c = LocalAronColors.current
    val pillFill = if (c.sunlight) c.accentHi else c.accentContainer
    val pillInk = if (c.sunlight) c.textOnAccent else c.accentOnContainer
    val pillEdge = c.surfaceSolid
    Box(Modifier.size(PackshotWidth, PackshotHeight).clearAndSetSemantics { }) {
        PackshotImage(sku.skuId.toString(), Modifier.size(PackshotWidth, PackshotHeight), category = packCategoryOf(sku.categoryCode))
        if (packs != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = AronTokens.Space.Xs, y = AronTokens.Space.Xs)
                    .heightIn(min = PackPillHeight)
                    .drawWithCache {
                        val r = CornerRadius(size.height / 2f); val w = AronTokens.Stroke.Focus.toPx(); val edge = Stroke(w)
                        onDrawBehind {
                            drawRoundRect(pillEdge, cornerRadius = r)
                            drawRoundRect(pillFill, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(r.x - w / 2))
                            drawRoundRect(pillEdge, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(r.x - w / 2), style = edge)
                        }
                    }
                    .padding(horizontal = AronTokens.Space.S),
                contentAlignment = Alignment.Center,
            ) {
                Text(localizedNumber(packs), style = MaterialTheme.typography.labelMedium, color = pillInk, maxLines = 1)
            }
        }
    }
}

/** The packs pill on the picture: 20 dp so it covers only the corner of the pack. */
private val PackPillHeight = 20.dp

/** The pack picture in the row: the pack's own shape (about 0.62 wide to 1 high, PARITY-SPEC s2.5). */
private val PackshotWidth = 48.dp
private val PackshotHeight = 78.dp

/**
 * The totals panel (manual "ক্যাটাগরি / মোট ইস্যু / স্টক", S3): one row per category, all four always shown (zeros included),
 * on an opaque raised surface. Issued is today's total in base units; stock is the bag after the issue being entered when
 * the host supplies it, else the total load (the head says which, as on the SKU rows).
 */
@Composable
private fun CategoryDock(rows: List<StockRow>, totals: List<CategoryTotal>, hasStock: Boolean) {
    val c = LocalAronColors.current
    val line = if (c.sunlight) AronTokens.Stroke.SunlightHairline else AronTokens.Stroke.Hairline
    val divider = c.borderDivider
    val issued = totals.associate { it.categoryCode to it.issuedBase }
    val unitOf = mapOf("cigarette" to "stick", "bidi" to "stick", "lighter" to "piece", "match" to "dozen")
    val cats = listOf("cigarette", "bidi", "lighter", "match")
    Column(
        Modifier.fillMaxWidth().testTag(StockTags.TOTALS).padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.Xs)
            .background(c.surfaceSolidRaised, MaterialTheme.shapes.medium).padding(horizontal = AronTokens.Space.M, vertical = AronTokens.Space.Xs),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.stk_col_category), Modifier.weight(1.2f), style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
            Text(stringResource(R.string.stk_col_total_issue), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = c.textSecondary, textAlign = TextAlign.End)
            Text(stringResource(if (hasStock) R.string.stk_col_stock else R.string.stk_col_loaded), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = c.textSecondary, textAlign = TextAlign.End)
        }
        cats.forEach { code ->
            val unit = unitLabel(rows.firstOrNull { it.sku.categoryCode == code }?.sku?.baseUnit ?: unitOf.getValue(code))
            val stock = rows.filter { it.sku.categoryCode == code }.sumOf { (it.stockBase ?: it.loadedBase) + it.entered }
            Box(Modifier.fillMaxWidth().height(line).drawBehind { drawRect(divider) })
            Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(categoryLabel(code), Modifier.weight(1.2f), style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                Text(localizedNumber(issued[code] ?: 0L) + " " + unit, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = c.textPrimary, textAlign = TextAlign.End)
                Text(localizedNumber(stock) + " " + unit, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = c.textPrimary, textAlign = TextAlign.End)
            }
        }
    }
}

/**
 * The action bar of Stock (android-glass s6.7): the `ActionBar` recipe, the 104 dp height reserved for the reason caption,
 * Print (secondary, 40 percent, printer icon) and Save (primary, 60 percent). Built from the kit's surface and buttons
 * because the host's tests find Save and Print by their test tags.
 */
@Composable
private fun StockBar(hasChange: Boolean, onSave: () -> Unit, onPrint: (() -> Unit)?, printBlock: StockPrintBlock?) {
    val c = LocalAronColors.current
    // each disabled button names its own reason (screens-sr s4); both read on one line when both are disabled
    val printReason = when {
        onPrint == null -> null
        printBlock == StockPrintBlock.SAVE_FIRST -> stringResource(R.string.stk_reason_save_first)
        printBlock == StockPrintBlock.PRINTER_OFF -> stringResource(R.string.stk_reason_connect_printer)
        else -> null
    }
    val reason = listOfNotNull(if (!hasChange) stringResource(R.string.stk_reason_no_change) else null, printReason)
        .takeIf { it.isNotEmpty() }?.joinToString(stringResource(R.string.stk_reason_join))
    GlassSurface(modifier = Modifier.fillMaxWidth(), kind = GlassKind.ActionBar) {
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .imePadding()
                .heightIn(min = AronTokens.Size.BottomBarWithReason)
                .padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.M),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
        ) {
            if (reason != null) Text(reason, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M), verticalAlignment = Alignment.CenterVertically) {
                if (onPrint != null) {
                    AronSecondaryButton(
                        stringResource(R.string.stk_print_short), onPrint, Modifier.weight(0.4f).testTag(StockTags.PRINT),
                        enabled = printBlock == null, icon = AronIcons.Print, tall = true,
                    )
                }
                AronPrimaryButton(
                    stringResource(R.string.stk_save), onSave, Modifier.weight(if (onPrint != null) 0.6f else 1f).testTag(StockTags.SAVE),
                    enabled = hasChange, icon = AronIcons.Save,
                )
            }
        }
    }
}
