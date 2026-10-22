package com.aktcl.aron.feature.sale.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronListRow
import com.aktcl.aron.core.ui.aron.AronPrimaryButton
import com.aktcl.aron.core.ui.aron.AronSwitchRow
import com.aktcl.aron.core.ui.aron.ButtonGlyph
import com.aktcl.aron.core.ui.aron.PackCard
import com.aktcl.aron.core.ui.aron.PackCategory
import com.aktcl.aron.core.ui.aron.RetailerPickerField
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.widthIn
import com.aktcl.aron.core.ui.AronLook
import com.aktcl.aron.core.ui.aron.PackListHeader
import com.aktcl.aron.core.ui.aron.SummaryRow
import com.aktcl.aron.core.ui.aron.SummaryTable
import com.aktcl.aron.core.ui.aron.SummaryTotal
import com.aktcl.aron.core.ui.aron.TotalsLine
import com.aktcl.aron.core.ui.aron.barBrush
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronBarAction
import com.aktcl.aron.core.ui.AronBottomBar
import com.aktcl.aron.core.ui.AronCard
import com.aktcl.aron.core.ui.AronConfirmDialog
import com.aktcl.aron.core.ui.AronEasing
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronStepper
import com.aktcl.aron.core.ui.AronTextButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.GlassTier
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.LocalGlassTier
import com.aktcl.aron.core.ui.LocalReducedMotion
import com.aktcl.aron.core.ui.MoneyText
import com.aktcl.aron.core.ui.RowPosition
import com.aktcl.aron.core.ui.entryUnitLabel
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.feature.sale.R
import com.aktcl.aron.feature.sale.domain.ReviewLine
import com.aktcl.aron.feature.sale.domain.ReviewProblem
import com.aktcl.aron.feature.sale.domain.SaleReview
import com.aktcl.aron.feature.sale.domain.SaleSku
import com.aktcl.aron.rules.EntrySpec
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.Money
import com.aktcl.aron.rules.QtyUnit
import com.aktcl.aron.rules.UiLocale
import com.aktcl.aron.rules.SignPosition

/** Money in the current language's digits: `1,234.50 ৳` (docs/24 s7.1; the lead's ruling puts the sign after the amount). */
@Composable
fun money(mtk: Long, decimals: Int = 2): String =
    Formats.money(mtk, if (LocalAppLanguage.current == AppLanguage.BN) UiLocale.BN else UiLocale.EN, decimals)

/** Money without its sign, for the dense columns whose header carries `(৳)` (screens-sr s0). */
@Composable
internal fun moneyBare(mtk: Long, decimals: Int = 2): String =
    Formats.digits(Money.formatTaka(mtk, decimals), if (LocalAppLanguage.current == AppLanguage.BN) UiLocale.BN else UiLocale.EN)

@Composable
internal fun unitLabel(unit: String): String = when (unit) {
    QtyUnit.PIECE.wire -> stringResource(R.string.sale_unit_piece)
    QtyUnit.DOZEN.wire -> stringResource(R.string.sale_unit_dozen)
    QtyUnit.PACK.wire -> stringResource(R.string.sale_unit_pack)
    else -> stringResource(R.string.sale_unit_stick)
}

@Composable
internal fun categoryLabel(code: String): String = when (code) {
    "cigarette" -> stringResource(R.string.sale_cat_cigarette)
    "bidi" -> stringResource(R.string.sale_cat_bidi)
    "lighter" -> stringResource(R.string.sale_cat_lighter)
    "match" -> stringResource(R.string.sale_cat_match)
    else -> code
}

/** The order of the category groups (screens-sr s7): cigarette, bidi, lighter, match, then anything else. */
internal fun categoryRank(code: String): Int = when (code) { "cigarette" -> 0; "bidi" -> 1; "lighter" -> 2; "match" -> 3; else -> 4 }

@Composable
internal fun skuDisplayName(sku: SaleSku): String = if (LocalAppLanguage.current == AppLanguage.BN) (sku.nameBn ?: sku.name) else sku.name

/** The flat row paint of a long list (android-glass s6.3), with the `accent.container` fill of an expanded SKU row. */
@Composable
internal fun Modifier.flatRow(position: RowPosition, selected: Boolean = false): Modifier {
    val c = LocalAronColors.current
    val solid = LocalGlassTier.current == GlassTier.C || c.sunlight
    val r = AronTokens.Radius.Card
    val shape = when (position) {
        RowPosition.Only -> RoundedCornerShape(r)
        RowPosition.First -> RoundedCornerShape(topStart = r, topEnd = r)
        RowPosition.Last -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
        RowPosition.Middle -> RoundedCornerShape(0.dp)
    }
    val fill = if (selected) c.accentContainer else if (solid) c.surfaceSolid else c.surfaceField
    return background(fill, shape)
}

internal fun positionOf(index: Int, size: Int): RowPosition = when {
    size == 1 -> RowPosition.Only
    index == 0 -> RowPosition.First
    index == size - 1 -> RowPosition.Last
    else -> RowPosition.Middle
}

/** The 48 dp thumbnail slot: no pack image is bundled yet, so an `accent.container` disc carries the SKU's initial (decorative). */
@Composable
internal fun SkuThumb(name: String) {
    val c = LocalAronColors.current
    val fill = c.accentContainer
    Box(
        Modifier.size(AronTokens.Touch.Min).drawBehind { drawRoundRect(fill, cornerRadius = CornerRadius(AronTokens.Radius.Chip.toPx())) }.clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(name.trim().take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = c.accentOnContainer)
    }
}

/** A dock chip (android-glass s6.17): 40 dp visual in a 48 dp hit area, opaque `surface.solid.raised`, selected = `accent.container` with the ring and a check. */
@Composable
internal fun DockChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    val c = LocalAronColors.current
    val fill = if (selected) c.accentContainer else c.surfaceSolidRaised
    val edge = if (selected) c.accent else c.borderDivider
    val ink = if (selected) c.accentOnContainer else c.textPrimary
    val stroke = if (c.sunlight) AronTokens.Stroke.SunlightHairline else if (selected) AronTokens.Stroke.Strong else AronTokens.Stroke.Hairline
    Box(
        modifier
            .heightIn(min = AronTokens.Touch.Min)
            .clip(RoundedCornerShape(AronTokens.Radius.Chip + AronTokens.Space.Xs))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = AronTokens.Space.Xs),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .heightIn(min = 40.dp)
                .drawWithCache {
                    // the ring and the stroke style are built once per size, not on every draw of a scrolling dock
                    val r = CornerRadius(AronTokens.Radius.Chip.toPx()); val w = stroke.toPx(); val edgeStyle = Stroke(w)
                    val inner = CornerRadius(r.x - w / 2); val edgeSize = Size(size.width - w, size.height - w); val edgeAt = Offset(w / 2, w / 2)
                    onDrawBehind {
                        drawRoundRect(fill, cornerRadius = r)
                        drawRoundRect(edge, edgeAt, edgeSize, inner, style = edgeStyle)
                    }
                }
                .padding(horizontal = AronTokens.Space.M),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs),
        ) {
            if (selected) Icon(AronIcons.Check, contentDescription = null, tint = ink, modifier = Modifier.size(AronTokens.Space.L))
            else if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(AronTokens.Size.IconSmall))
            Text(label, style = MaterialTheme.typography.labelMedium, color = ink, maxLines = 1)
        }
    }
}

/**
 * The kit-looking text field (android-glass s6.12): the label above in `type.caption` `text.secondary` (no floating
 * label), then the 56 dp field, `radius.chip`, raised surface, `border.input`, focus ring in `border.focus`. The field
 * node carries the label as its content description (the caption is decorative) and shows it as the placeholder while
 * empty. [modifier] lays out the whole control; [fieldModifier] goes on the field itself (a test tag).
 */
@Composable
internal fun KitTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    fieldModifier: Modifier = Modifier,
) {
    val c = LocalAronColors.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
        Text(label, Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        OutlinedTextField(
            value = value, onValueChange = onValueChange,
            placeholder = { Text(label, style = MaterialTheme.typography.bodyLarge, color = c.textPlaceholder) },
            modifier = fieldModifier.fillMaxWidth().heightIn(min = AronTokens.Touch.Primary).semantics { contentDescription = label },
            singleLine = singleLine, isError = isError,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = RoundedCornerShape(AronTokens.Radius.Chip),
            textStyle = MaterialTheme.typography.bodyLarge,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = c.surfaceSolidRaised, unfocusedContainerColor = c.surfaceSolidRaised, errorContainerColor = c.surfaceSolidRaised,
                focusedBorderColor = c.borderFocus, unfocusedBorderColor = c.borderInput, errorBorderColor = c.danger,
                focusedTextColor = c.textPrimary, unfocusedTextColor = c.textPrimary, errorTextColor = c.textPrimary,
                focusedPlaceholderColor = c.textPlaceholder, unfocusedPlaceholderColor = c.textPlaceholder, errorPlaceholderColor = c.textPlaceholder,
                cursorColor = c.accent,
            ),
        )
    }
}

/** Test tags of the sale screens. */
object SaleTags { const val QC_ONLY = "sale_qc_only" }

/**
 * Sale, step 2: the product list (F-SR-023, sr/stock.webp layout with the sale quantity). One kit [PackCard] per SKU: packshot with the
 * packs badge, the SKU name, the red minus, the underlined sale quantity (tap to type), the purple plus and the stock figure; under it
 * the "price / unit · in stock" caption (the unit and price stay visible on every row), and, once a quantity is entered, the packs
 * equivalent with the line total and the over-stock warning (a warning, never a block). Quantities are in the SKU's own unit.
 * A filter row (search, All, one pill per category) leads the list. The purple bottom panel carries the running total and the
 * gradient Proceed button. Stateless: the caller owns the quantities ([SaleFlow]) and passes the current review.
 */
@Composable
fun SaleEntryScreen(
    skus: List<SaleSku>,
    review: SaleReview?,
    qtyOf: (Long) -> Long,
    onQuantity: (skuId: Long, qty: Long) -> Unit,
    onReview: () -> Unit,
    onZeroSaleConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    /** SKUs sold to this outlet in the last 7 local days: listed first as a jump aid, no pre-filled quantity. */
    recentSkuIds: Set<Long> = emptySet(),
    onPreviousSales: (() -> Unit)? = null,
    /** Product QC with no sale lines (D-18): offered only while the memo has no line; null hides it (AMO, tests). */
    onQcOnly: (() -> Unit)? = null,
    /** The retailer of this visit as `name (code-phone-cluster)`: the pill at the top of the list (sr/sale-select.png, the manual's SR-S-32). */
    retailerLabel: String? = null,
) {
    val look = LocalAronLook.current
    var askZero by remember { mutableStateOf(false) }
    val lines = review?.lines.orEmpty().associateBy { it.sku.skuId }
    // a recent SKU is listed first, once: it leaves its category order (one row, one control set, per SKU); the list has no filter row
    val ordered = skus.sortedWith(compareBy({ it.skuId !in recentSkuIds }, { categoryRank(it.categoryCode) }))
    val hasLines = review?.lines?.isNotEmpty() == true
    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth()) {
            if (retailerLabel != null) RetailerPickerField(value = retailerLabel, placeholder = "", onClick = {}, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = AronTokens.Space.S))
            if (onPreviousSales != null) GreenButton(stringResource(R.string.sale_history), onPreviousSales, Modifier.padding(horizontal = 12.dp, vertical = AronTokens.Space.S))
            if (onQcOnly != null && !hasLines && skus.isNotEmpty()) LinkText(stringResource(R.string.sale_qc_only), onQcOnly, Modifier.testTag(SaleTags.QC_ONLY))
            when {
                skus.isEmpty() || review == null -> AronEmptyState(stringResource(R.string.sale_empty), icon = AronIcons.Sale)
                else -> {
                    PackListHeader(
                        sku = stringResource(R.string.sale_col_sku), quantity = stringResource(R.string.sale_col_sale), stock = stringResource(R.string.sale_col_stock),
                    )
                    LazyColumn(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(ordered, key = { it.skuId }, contentType = { "sku" }) { sku -> SaleSkuRow(sku, qtyOf(sku.skuId), lines[sku.skuId], onQuantity) }
                    }
                }
            }
        }
        // the single purple footer row of the manual (SR-S-32): the cart, the running total and the Proceed button beside it
        Row(
            Modifier.fillMaxWidth().background(look.panel).windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(AronIcons.Sale, contentDescription = null, tint = look.onPanel, modifier = Modifier.size(28.dp))
            MoneyText(moneyBare(review?.totals?.grossMtk ?: 0), style = MaterialTheme.typography.headlineMedium, color = look.onPanel, modifier = Modifier.weight(1f))
            AronPrimaryButton(
                stringResource(R.string.sale_review), onClick = { if (hasLines) onReview() else askZero = true },
                modifier = Modifier.widthIn(min = 140.dp), icon = rememberVectorPainter(AronIcons.ArrowForward),
            )
        }
    }
    if (askZero) {
        AronConfirmDialog(
            stringResource(R.string.sale_zero_confirm_title), stringResource(R.string.sale_zero_confirm_message),
            stringResource(R.string.sale_yes), stringResource(R.string.sale_no),
            onConfirm = { askZero = false; onZeroSaleConfirmed() }, onDismiss = { askZero = false },
        )
    }
}

/** The full-width green button of the manual (SR-S-32: "পূর্বের সেল ডাটা দেখুন", a history glyph and the label). */
@Composable
private fun GreenButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(AronLook.ButtonRadius)
    Row(
        modifier.fillMaxWidth().heightIn(min = AronLook.ButtonHeight).clip(shape)
            .background(if (look.sunlight) SolidColor(look.purple) else Brush.horizontalGradient(listOf(Color(0xFF00A650), Color(0xFF5CBB8A))))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(AronIcons.Clock, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

/** A purple underlined text link of the list header (previous sales, QC only); 48 dp tall. */
@Composable
private fun LinkText(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Box(modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.CenterStart) {
        Text(label, color = look.purple, fontSize = 15.sp, textDecoration = TextDecoration.Underline)
    }
}

/**
 * One SKU row: the kit [PackCard] plus its caption. The packs badge is the packs equivalent of the quantity (read-only, Q-UI-11).
 * Each control carries its own TalkBack label with the SKU name (minus, the quantity field, plus).
 */
@Composable
private fun SaleSkuRow(sku: SaleSku, qty: Long, line: ReviewLine?, onQuantity: (Long, Long) -> Unit) {
    val look = LocalAronLook.current
    val unit = entryUnitLabel(sku.entryLabelKey)
    val name = skuDisplayName(sku)
    val entry = sku.entry
    val max = MaxLineQty
    Column {
        // the label is the hyphenated SKU code on one line (sr/stock.webp); nothing is printed between the cards
        PackCard(
            skuId = sku.skuId.toString(), skuCode = sku.code,
            quantity = localizedNumber(qty), onQuantityChange = { onQuantity(sku.skuId, parseQty(it).coerceIn(0L, max)) },
            onMinus = { onQuantity(sku.skuId, (qty - 1).coerceAtLeast(0)) }, onPlus = { onQuantity(sku.skuId, (qty + 1).coerceAtMost(max)) },
            stock = sku.stockBase?.let { entryStockText(entry, it) } ?: "–",
            caption = sku.entryPriceMtk?.let { stringResource(R.string.sale_price_per_unit, moneyBare(it, if (it % 10L == 0L) 2 else 3), unit) },
            minusLabel = stringResource(R.string.sale_less) + ", " + name, plusLabel = stringResource(R.string.sale_more) + ", " + name,
            quantityLabel = stringResource(R.string.sale_qty_a11y, localizedNumber(qty), unit) + ", " + name,
            packBadge = line?.takeIf { entry.unit != QtyUnit.PACK && sku.packFactor > 1 && it.packBadge.first > 0 }?.let { localizedNumber(it.packBadge.first.toLong()) },
            category = packCategoryOf(sku.categoryCode),
            minusEnabled = qty > 0, plusEnabled = qty < max,
        )
        // the over-stock state stays visible (a warning, never a block): the stock figure is named in red under the card
        if (line?.exceedsStock == true) {
            Text(
                localizedDigits(stringResource(R.string.sale_stock_warning, entry.fromBase(sku.stockBase ?: 0L).toString(), sku.name, unit)),
                color = look.red, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp),
            )
        }
    }
}

/** Stock in the entry unit, e.g. `12`; stock that is not a whole number of entry units (older rows) appends `+n` base units. */
@Composable
private fun entryStockText(entry: EntrySpec, stockBase: Long): String {
    val whole = entry.fromBase(stockBase); val rest = entry.remainderBase(stockBase)
    return localizedNumber(whole) + if (rest > 0) "+" + localizedNumber(rest) else ""
}

/** Typed digits (Latin or Bangla) to a quantity; empty reads as zero. */
private fun parseQty(text: String): Long =
    text.filter(Char::isDigit).fold(0L) { acc, ch -> (acc * 10 + Character.digit(ch, 10)).coerceAtMost(MaxLineQty) }

/** The kit's pack category of a category code (the colour of the neutral silhouette when no packshot is cached). */
internal fun packCategoryOf(code: String): PackCategory = when (code) {
    "bidi" -> PackCategory.Bidi
    "lighter" -> PackCategory.Lighter
    "match" -> PackCategory.Match
    else -> PackCategory.Cigarette
}

/** The stepper's ceiling per line (unchanged rule). */
private const val MaxLineQty = 999_999L

/** Scroll movement under this many pixels does not flip the dock (a finger resting on the list). */
private const val ScrollSlackPx = 4

/**
 * Sale, step 3: the review (নিরীক্ষণ, sr/summary.png table layout): the lines in the kit [SummaryTable] (SKU name over its unit,
 * quantity, value; a purple subtotal row per category; the totals block with gross, every non-zero deduction with a true minus,
 * paid and due on credit, and the net), the credit switch, the QC and Slide rows, the "print later" caption and, docked at the
 * bottom, the purple net panel with the gradient Print button (the commit; the printer never disables it). [onSave] is Print: the
 * host's save-and-print flow asks its confirmation and commits on Yes.
 */
@Composable
fun ReviewScreen(
    review: SaleReview,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    /** The review header, one line per row: `Outlet (code)`, `Route: …`, `Cluster: …` (newline separated), when the host knows it. */
    outletLine: String? = null,
    onCreditToggle: ((Boolean) -> Unit)? = null,
    onQc: (() -> Unit)? = null,
    onSlide: (() -> Unit)? = null,
) {
    val look = LocalAronLook.current
    val t = review.totals
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = AronTokens.Space.S),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
        ) {
            // the review header of the manual (SR-S-33): the outlet with its code, then the route and the cluster on their own lines
            outletLine?.lines()?.filter { it.isNotBlank() }?.forEachIndexed { i, l ->
                Text(l, color = look.ink, fontSize = if (i == 0) 17.sp else 14.sp, fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal)
            }
            val byCategory = review.lines.groupBy { it.sku.categoryCode }.toList().sortedBy { categoryRank(it.first) }
            // the net is printed once, at the bottom of the table card (the Summary reference); the Print button sits on the grey page
            val totals = buildList {
                add(SummaryTotal(stringResource(R.string.review_gross), moneyGlued(t.grossMtk)))
                if (t.offerDiscountMtk != 0L) add(SummaryTotal(stringResource(R.string.review_offer_discount), negMoney(moneyGlued(t.offerDiscountMtk))))
                if (t.drpDiscountMtk != 0L) add(SummaryTotal(stringResource(R.string.review_drp_discount), negMoney(moneyGlued(t.drpDiscountMtk))))
                if (t.qcDeductionMtk != 0L) add(SummaryTotal(stringResource(R.string.review_qc), negMoney(moneyGlued(t.qcDeductionMtk))))
                add(SummaryTotal(stringResource(R.string.review_net), moneyGlued(t.netMtk)))
                if (review.settlement.isCredit) {
                    add(SummaryTotal(stringResource(R.string.review_paid), moneyGlued(review.settlement.paidMtk)))
                    add(SummaryTotal(stringResource(R.string.review_due), moneyGlued(review.settlement.dueMtk)))
                }
            }
            SummaryTable(
                headers = listOf(stringResource(R.string.review_col_sku), stringResource(R.string.review_col_qty), stringResource(R.string.review_col_value)),
                rows = byCategory.flatMap { (_, ls) ->
                    ls.map { l -> SummaryRow(l.sku.code, listOf(entryUnitLabel(l.sku.entryLabelKey), localizedNumber(l.entry.qtyEntered), moneyGlued(l.memoLine.grossMtk))) }
                },
                subtotals = byCategory.mapNotNull { (code, ls) ->
                    review.categories.firstOrNull { it.categoryCode == code }?.let { sub ->
                        TotalsLine(stringResource(R.string.review_subtotal, categoryLabel(code)), listOf(stringResource(R.string.review_qty_unit, localizedNumber(sub.qtyEntered), entryUnitLabel(ls.first().sku.entryLabelKey)), moneyGlued(sub.grossMtk)))
                    }
                },
                totals = totals,
                weights = listOf(1.2f, 1f, 1f),
            )
            val showCredit = onCreditToggle != null && review.lines.isNotEmpty()
            // credit: the purple checkbox of the manual, unchecked by default; Product QC: the yellow button
            if (showCredit) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = review.settlement.isCredit, role = Role.Checkbox, onValueChange = onCreditToggle!!)
                        .clip(RoundedCornerShape(AronLook.CardRadius)).background(look.card).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(checked = review.settlement.isCredit, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = look.purple, uncheckedColor = look.purple, checkmarkColor = Color.White))
                    Text(
                        if (review.settlement.isCredit) stringResource(R.string.review_credit_due, money(review.settlement.dueMtk)) else stringResource(R.string.review_credit_switch),
                        color = look.ink, fontSize = 16.sp, modifier = Modifier.weight(1f),
                    )
                }
            }
            if (onQc != null) YellowButton(stringResource(R.string.qc_title), onQc)
            if (onSlide != null) AronListRow(stringResource(R.string.slide_title), onClick = onSlide, trailing = "›")
            // a QC-only sale (D-18): the net is below zero and is stored as a credit; the sign is shown, never hidden
            if (t.netMtk < 0) AronBanner(stringResource(R.string.review_credit_note), kind = BannerKind.Warning)
            review.problems.forEach { p -> AronBanner(problemText(p), kind = BannerKind.Error) }
            Text(stringResource(R.string.review_print_later), color = look.inkMuted, fontSize = 13.sp)
        }
        if (!review.canCommit) Text(stringResource(R.string.review_problem_other), color = look.red, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(12.dp)) {
            // on the grey page the gradient runs purple to crimson, as on the Summary screen
            AronPrimaryButton(stringResource(R.string.review_save), onSave, Modifier.fillMaxWidth(), enabled = review.canCommit, glyph = ButtonGlyph.Print, reverse = true)
        }
    }
}

/** The yellow "Product QC" button of the manual (SR-S-33): a full-width rounded block, dark label. */
@Composable
private fun YellowButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(AronLook.ButtonRadius)
    Box(
        modifier.fillMaxWidth().heightIn(min = AronLook.ButtonHeight).clip(shape).background(Color(0xFFFFC107)).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color(0xFF1B1B1B), fontSize = 17.sp, fontWeight = FontWeight.Medium) }
}

/** Money with the ৳ glued to the digits and grouping as on the Summary screen ("4,687.50৳"). */
@Composable
internal fun moneyGlued(mtk: Long): String = moneyBare(mtk) + "৳"

/** A deduction with a true minus (never colour alone): "− 80.00 ৳". */
private fun negMoney(moneyText: String): String = "− $moneyText"

@Composable
private fun problemText(p: ReviewProblem): String = when (p) {
    is ReviewProblem.TooManyLines -> localizedDigits(stringResource(R.string.review_problem_too_many_lines, p.max.toString(), p.count.toString()))
    is ReviewProblem.UnitNotAllowed -> stringResource(R.string.review_problem_unit, p.skuId.toString())
    is ReviewProblem.QtyTooLarge -> stringResource(R.string.review_problem_qty_large, p.skuId.toString())
    is ReviewProblem.PaidOutOfRange, is ReviewProblem.PaidNotPaisa, ReviewProblem.PaidNegative -> stringResource(R.string.review_problem_paid)
    ReviewProblem.NothingToSell -> stringResource(R.string.review_problem_nothing)
    is ReviewProblem.NetNegative -> stringResource(R.string.review_problem_net_negative)
    is ReviewProblem.QcAboveCap -> stringResource(R.string.review_problem_qc_cap)
    is ReviewProblem.TooManyQcRows -> localizedDigits(stringResource(R.string.review_problem_qc_rows, p.max.toString()))
    else -> stringResource(R.string.review_problem_other)
}
