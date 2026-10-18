package com.aktcl.aron.feature.sale.ui

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
import com.aktcl.aron.core.ui.AronListRow
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
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.feature.sale.R
import com.aktcl.aron.feature.sale.domain.ReviewLine
import com.aktcl.aron.feature.sale.domain.ReviewProblem
import com.aktcl.aron.feature.sale.domain.SaleReview
import com.aktcl.aron.feature.sale.domain.SaleSku
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
 * Sale: SKU quantity entry (F-SR-023, screens-sr s7). Quantities are in the SKU's own unit with the unit shown, a
 * read-only pack badge, a running total in the bar and a stock warning that never blocks. Idle rows are 72 dp with a
 * trailing `+`; a row with a quantity stays expanded in `accent.container` with its line total and stepper. The filter
 * dock (search and categories) sits above the bar. Stateless: the caller owns the quantities ([SaleFlow] in the domain
 * package) and passes the current review. Renders its own bottom bar.
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
) {
    val c = LocalAronColors.current
    var askZero by remember { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val lines = review?.lines.orEmpty().associateBy { it.sku.skuId }
    val q = query.trim()
    val visible = skus.filter { s ->
        (category == null || s.categoryCode == category) &&
            (q.isEmpty() || s.name.contains(q, ignoreCase = true) || s.code.contains(q, ignoreCase = true) || s.nameBn?.contains(q, ignoreCase = true) == true)
    }
    // a recent SKU is listed first, once: it leaves its category group (one row, one stepper, one TalkBack stop per SKU)
    val recent = visible.filter { it.skuId in recentSkuIds }
    val groups = visible.filterNot { it.skuId in recentSkuIds }.groupBy { it.categoryCode }.toList().sortedBy { categoryRank(it.first) }
    val categories = skus.map { it.categoryCode }.distinct().sortedBy { categoryRank(it) }
    val hasLines = review?.lines?.isNotEmpty() == true
    val reducedMotion = LocalReducedMotion.current
    // the dock (screens-sr s7, android-glass s6.17): above the bar at the default scale, the first item of the list from font scale 1.3
    val dockInList = LocalDensity.current.fontScale >= AronTokens.Size.ReflowFontScale
    val listState = rememberLazyListState()
    var dockShown by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        // hides on scroll down, returns on scroll up (and at the top); the first item's index and offset say which way
        var lastIndex = listState.firstVisibleItemIndex
        var lastOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            val down = index > lastIndex || (index == lastIndex && offset > lastOffset + ScrollSlackPx)
            val up = index < lastIndex || (index == lastIndex && offset < lastOffset - ScrollSlackPx)
            if (down) dockShown = false else if (up || (index == 0 && offset == 0)) dockShown = true
            lastIndex = index; lastOffset = offset
        }
    }
    val dock: @Composable () -> Unit = {
        if (searching) {
            Row(Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.Xs), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                KitTextField(query, { query = it }, stringResource(R.string.sale_search), Modifier.weight(1f))
                DockChip(stringResource(R.string.sale_search_close), selected = false, onClick = { searching = false; query = "" }, icon = AronIcons.Close)
            }
        } else {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.Xs),
                horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
            ) {
                DockChip(stringResource(R.string.sale_search), selected = false, onClick = { searching = true }, icon = AronIcons.Search)
                DockChip(stringResource(R.string.sale_filter_all), selected = category == null, onClick = { category = null })
                categories.forEach { code -> DockChip(categoryLabel(code), selected = category == code, onClick = { category = code }) }
            }
        }
    }
    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth()) {
            if (onPreviousSales != null) {
                AronTextButton(stringResource(R.string.sale_history), onPreviousSales, Modifier.padding(horizontal = AronTokens.Space.S))
            }
            if (onQcOnly != null && !hasLines && skus.isNotEmpty()) {
                AronTextButton(stringResource(R.string.sale_qc_only), onQcOnly, Modifier.padding(horizontal = AronTokens.Space.S).testTag(SaleTags.QC_ONLY))
            }
            when {
                skus.isEmpty() || review == null -> AronEmptyState(stringResource(R.string.sale_empty), icon = AronIcons.Sale)
                visible.isEmpty() -> {
                    if (dockInList) dock()
                    AronEmptyState(
                        stringResource(R.string.sale_search_none), icon = AronIcons.Search,
                        actionLabel = stringResource(R.string.sale_clear), onAction = { query = ""; category = null },
                    )
                }
                else -> LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(bottom = AronTokens.Space.L)) {
                    if (dockInList) item(key = "dock", contentType = "dock") { dock() }
                    if (recent.isNotEmpty()) {
                        item(key = "recent_head", contentType = "head") { GroupLabel(stringResource(R.string.sale_group_recent)) }
                        recent.forEachIndexed { i, sku ->
                            item(key = "recent_${sku.skuId}", contentType = "sku") {
                                SaleSkuRow(sku, qtyOf(sku.skuId), lines[sku.skuId], positionOf(i, recent.size), onQuantity)
                            }
                        }
                    }
                    groups.forEach { (code, group) ->
                        item(key = "head_$code", contentType = "head") {
                            GroupLabel(stringResource(R.string.sale_group, categoryLabel(code), unitLabel(group.first().baseUnit)))
                        }
                        group.forEachIndexed { i, sku ->
                            item(key = sku.skuId, contentType = "sku") {
                                SaleSkuRow(sku, qtyOf(sku.skuId), lines[sku.skuId], positionOf(i, group.size), onQuantity)
                            }
                        }
                    }
                }
            }
        }
        // the filter dock above the bar (android-glass s6.17): search, All, one chip per category present; it folds away on
        // scroll down over `motion.fast` (a snap under reduced motion) and never while the search field is open
        if (skus.isNotEmpty() && !dockInList) {
            val fold = if (reducedMotion) snap<IntSize>() else tween(AronTokens.Motion.Fast, easing = AronEasing.Out)
            AnimatedVisibility(visible = dockShown || searching, enter = expandVertically(fold), exit = shrinkVertically(fold)) { dock() }
        }
        AronBottomBar(
            summaryLabel = stringResource(R.string.sale_total),
            summaryValue = money(review?.totals?.grossMtk ?: 0),
            primary = AronBarAction(stringResource(R.string.sale_review), onClick = { if (hasLines) onReview() else askZero = true }, icon = AronIcons.ArrowForward),
        )
    }
    if (askZero) {
        AronConfirmDialog(
            stringResource(R.string.sale_zero_confirm_title), stringResource(R.string.sale_zero_confirm_message),
            stringResource(R.string.sale_yes), stringResource(R.string.sale_no),
            onConfirm = { askZero = false; onZeroSaleConfirmed() }, onDismiss = { askZero = false },
        )
    }
}

@Composable
private fun GroupLabel(text: String) {
    val c = LocalAronColors.current
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = c.textSecondary,
        modifier = Modifier.fillMaxWidth().padding(start = AronTokens.Space.Screen, end = AronTokens.Space.Screen, top = AronTokens.Space.L, bottom = AronTokens.Space.S).semantics { heading() },
    )
}

/**
 * One SKU row (android-glass s6.3): idle = 72 dp flat row with the thumbnail, the name, the "price / unit · stock" caption
 * and a trailing 48 dp `+` (one tap adds 1 and the row expands); expanded = `accent.container` fill, line total as a
 * numeral, the stepper with the unit beside it, the packs caption, and the over-stock warning well (a warning, never a block).
 * The row morphs between the two over `motion.fast` (`animateContentSize`); under reduced motion it snaps.
 *
 * TalkBack: each row is ONE merged node. Idle: the row's own click action adds one (the `+` stays touchable, silent).
 * Expanded: the row reads name, caption, line total and quantity, with plus and minus as custom actions; the stepper's
 * buttons stay touchable but leave the semantics tree.
 */
@Composable
private fun SaleSkuRow(sku: SaleSku, qty: Long, line: ReviewLine?, position: RowPosition, onQuantity: (Long, Long) -> Unit) {
    val c = LocalAronColors.current
    val unit = unitLabel(sku.baseUnit)
    val price = if (sku.pricePerQty == 1) money(sku.unitPriceMtk) else stringResource(R.string.sale_price_per, money(sku.unitPriceMtk), localizedNumber(sku.pricePerQty.toLong()))
    val caption = sku.stockBase?.let { stringResource(R.string.sale_row_caption_stock, price, unit, stringResource(R.string.sale_in_stock, localizedNumber(it))) }
        ?: stringResource(R.string.sale_row_caption, price, unit)
    val name = skuDisplayName(sku)
    val expanded = qty > 0
    val addLabel = stringResource(R.string.sale_add)
    val morph = if (LocalReducedMotion.current) snap() else tween<IntSize>(AronTokens.Motion.Fast, easing = AronEasing.Out)
    Box(Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.Screen).animateContentSize(morph)) {
        if (!expanded) {
            Box(Modifier.semantics(mergeDescendants = true) { onClick(label = addLabel) { onQuantity(sku.skuId, 1); true } }) {
                AronListRow(
                    title = name, subtitle = caption, leading = { SkuThumb(name) }, flat = true, position = position, divider = position != RowPosition.Last && position != RowPosition.Only,
                    trailingContent = {
                        val fill = if (c.sunlight) c.accentHi else c.accentContainer
                        val ink = if (c.sunlight) c.textOnAccent else c.accentOnContainer
                        Box(
                            Modifier.size(AronTokens.Touch.Min).clip(CircleShape).drawBehind { drawCircle(fill) }
                                .clickable(role = Role.Button, onClick = { onQuantity(sku.skuId, 1) })
                                .clearAndSetSemantics { },
                            contentAlignment = Alignment.Center,
                        ) { Icon(AronIcons.Add, contentDescription = null, tint = ink, modifier = Modifier.size(AronTokens.Size.Icon)) }
                    },
                )
            }
        } else {
            ExpandedSkuRow(sku, qty, line, position, name, caption, unit, onQuantity)
        }
    }
}

@Composable
private fun ExpandedSkuRow(sku: SaleSku, qty: Long, line: ReviewLine?, position: RowPosition, name: String, caption: String, unit: String, onQuantity: (Long, Long) -> Unit) {
    val c = LocalAronColors.current
    val plusLabel = stringResource(R.string.sale_more)
    val minusLabel = stringResource(R.string.sale_less)
    val qtyState = stringResource(R.string.sale_qty_a11y, localizedNumber(qty), unit)
    val actions = listOfNotNull(
        if (qty < MaxLineQty) CustomAccessibilityAction(plusLabel) { onQuantity(sku.skuId, qty + 1); true } else null,
        if (qty > 0) CustomAccessibilityAction(minusLabel) { onQuantity(sku.skuId, qty - 1); true } else null,
    )
    Column(
        Modifier
            .fillMaxWidth()
            .flatRow(position, selected = true)
            .semantics(mergeDescendants = true) { stateDescription = qtyState; customActions = actions }
            .padding(horizontal = AronTokens.Space.L, vertical = AronTokens.Space.M),
        verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
    ) {
        // line 1: the thumbnail, then the name beside the line total and the caption on its own full-width line
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            SkuThumb(name)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 2)
                    if (line != null) MoneyText(money(line.memoLine.grossMtk), style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.End)
                }
                Text(caption, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // the quantity for TalkBack is the merged row's state description; the buttons stay touchable
            Box(Modifier.clearAndSetSemantics { }) {
                AronStepper(
                    value = qty.toInt(), onValueChange = { onQuantity(sku.skuId, it.toLong()) },
                    minusLabel = minusLabel, plusLabel = plusLabel, min = 0, max = MaxLineQty.toInt(),
                )
            }
            Spacer(Modifier.width(AronTokens.Space.S))
            Text(unit, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1)
        }
        if (line != null && sku.packFactor > 1 && line.packBadge.first > 0) {
            Text(localizedDigits(stringResource(R.string.sale_pack_badge, line.packBadge.first.toString(), line.packBadge.second.toString())), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
        if (line?.exceedsStock == true) {
            val well = c.warningContainer
            Row(
                Modifier.fillMaxWidth().drawBehind { drawRoundRect(well, cornerRadius = CornerRadius(AronTokens.Radius.Chip.toPx())) }.padding(horizontal = AronTokens.Space.M, vertical = AronTokens.Space.S),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
            ) {
                Icon(AronIcons.Warning, contentDescription = null, tint = c.warningOnContainer, modifier = Modifier.size(AronTokens.Size.IconSmall))
                Text(localizedDigits(stringResource(R.string.sale_stock_warning, (sku.stockBase ?: 0L).toString(), sku.name)), style = MaterialTheme.typography.bodySmall, color = c.warningOnContainer)
            }
        }
    }
}

/** The stepper's ceiling per line (unchanged rule). */
private const val MaxLineQty = 999_999L

/** Scroll movement under this many pixels does not flip the dock (a finger resting on the list). */
private const val ScrollSlackPx = 4

/**
 * Review (নিরীক্ষণ, screens-sr s8): the lines card (three columns, category subtotals, no per-row sign), the totals card
 * (gross, every non-zero deduction with a true minus, the net in `type.display`), the credit switch row, the QC row,
 * the "print later" caption, and the bar with the net and Print (the commit; the printer never disables it). Renders its
 * own bottom bar. [onSave] is Print: the host's save-and-print flow asks its confirmation and commits on Yes.
 */
@Composable
fun ReviewScreen(
    review: SaleReview,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    /** "Outlet (code) · route · cluster" under the top bar, when the host knows it. */
    outletLine: String? = null,
    onCreditToggle: ((Boolean) -> Unit)? = null,
    onQc: (() -> Unit)? = null,
    onSlide: (() -> Unit)? = null,
) {
    val c = LocalAronColors.current
    val t = review.totals
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
        ) {
            if (outletLine != null) Text(outletLine, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            if (review.lines.isNotEmpty()) LinesCard(review)
            AronCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                    Amount(stringResource(R.string.review_gross), money(t.grossMtk))
                    if (t.offerDiscountMtk != 0L) Amount(stringResource(R.string.review_offer_discount), minus(money(t.offerDiscountMtk)))
                    if (t.drpDiscountMtk != 0L) Amount(stringResource(R.string.review_drp_discount), minus(money(t.drpDiscountMtk)))
                    if (t.qcDeductionMtk != 0L) Amount(stringResource(R.string.review_qc), minus(money(t.qcDeductionMtk)))
                    Spacer(Modifier.height(AronTokens.Space.Xs))
                    Text(stringResource(R.string.review_net), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    MoneyText(money(t.netMtk), Modifier.fillMaxWidth(), style = MaterialTheme.typography.displayLarge)
                    if (review.settlement.isCredit) {
                        Amount(stringResource(R.string.review_paid), money(review.settlement.paidMtk))
                        Amount(stringResource(R.string.review_due), money(review.settlement.dueMtk))
                    }
                }
            }
            // the credit switch, QC and Slide rows are one flat group (no third and fourth shadowed card: screens-sr s8 budgets two)
            val showCredit = onCreditToggle != null && review.lines.isNotEmpty()
            val rowCount = listOf(showCredit, onQc != null, onSlide != null).count { it }
            if (rowCount > 0) {
                var index = 0
                fun next(): Pair<RowPosition, Boolean> = positionOf(index, rowCount).let { p -> index++; p to (index < rowCount) }
                Column(Modifier.fillMaxWidth()) {
                    if (showCredit) {
                        val (position, divider) = next()
                        CreditSwitchRow(
                            on = review.settlement.isCredit,
                            label = if (review.settlement.isCredit) stringResource(R.string.review_credit_due, money(review.settlement.dueMtk)) else stringResource(R.string.review_credit_switch),
                            onToggle = onCreditToggle!!, position = position, divider = divider,
                        )
                    }
                    if (onQc != null) {
                        val (position, divider) = next()
                        AronListRow(
                            stringResource(R.string.qc_title), onClick = onQc, flat = true, position = position, divider = divider,
                            trailingContent = { Icon(AronIcons.ChevronRight, contentDescription = null, tint = c.textSecondary) },
                        )
                    }
                    if (onSlide != null) {
                        val (position, divider) = next()
                        AronListRow(
                            stringResource(R.string.slide_title), onClick = onSlide, flat = true, position = position, divider = divider,
                            trailingContent = { Icon(AronIcons.ChevronRight, contentDescription = null, tint = c.textSecondary) },
                        )
                    }
                }
            }
            // a QC-only sale (D-18): the net is below zero and is stored as a credit; the sign is shown, never hidden
            if (t.netMtk < 0) AronBanner(stringResource(R.string.review_credit_note), kind = BannerKind.Warning)
            review.problems.forEach { p -> AronBanner(problemText(p), kind = BannerKind.Error) }
            Text(stringResource(R.string.review_print_later), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
        AronBottomBar(
            summaryLabel = stringResource(R.string.review_net), summaryValue = money(t.netMtk),
            primary = AronBarAction(stringResource(R.string.review_save), onClick = onSave, enabled = review.canCommit, icon = AronIcons.Print),
            reasonWhenDisabled = if (!review.canCommit) stringResource(R.string.review_problem_other) else null,
        )
    }
}

/** A deduction with a true minus (never colour alone): "− 80.00 ৳". */
private fun minus(moneyText: String): String = "− $moneyText"

/** The lines card: header `SKU | Quantity | Value (৳)`, 48 dp lines, 40 dp category subtotal rows, no per-row sign. */
@Composable
private fun LinesCard(review: SaleReview) {
    val c = LocalAronColors.current
    val byCategory = review.lines.groupBy { it.sku.categoryCode }.toList().sortedBy { categoryRank(it.first) }
    AronCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.L, vertical = AronTokens.Space.M)) {
            Row(Modifier.fillMaxWidth().padding(bottom = AronTokens.Space.Xs)) {
                Text(stringResource(R.string.review_col_sku), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                Text(stringResource(R.string.review_col_qty), Modifier.weight(0.7f), style = MaterialTheme.typography.labelMedium, color = c.textSecondary, textAlign = TextAlign.End)
                Text(stringResource(R.string.review_col_value), Modifier.weight(0.7f), style = MaterialTheme.typography.labelMedium, color = c.textSecondary, textAlign = TextAlign.End)
            }
            byCategory.forEach { (code, ls) ->
                ls.forEach { l ->
                    Row(Modifier.fillMaxWidth().heightIn(min = AronTokens.Touch.Min), verticalAlignment = Alignment.CenterVertically) {
                        Text(skuDisplayName(l.sku), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, maxLines = 2)
                        Text(stringResource(R.string.review_qty_unit, localizedNumber(l.qtyBase), unitLabel(l.sku.baseUnit)), Modifier.weight(0.7f), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, textAlign = TextAlign.End, maxLines = 1)
                        Text(moneyBare(l.memoLine.grossMtk), Modifier.weight(0.7f), style = MaterialTheme.typography.titleSmall, color = c.textPrimary, textAlign = TextAlign.End, maxLines = 1)
                    }
                }
                val sub = review.categories.firstOrNull { it.categoryCode == code }
                if (sub != null) {
                    val divider = c.borderDivider
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 40.dp).drawBehind { drawRect(divider, Offset.Zero, Size(size.width, AronTokens.Stroke.Hairline.toPx())) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.review_subtotal, categoryLabel(code)), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                        Text(stringResource(R.string.review_qty_unit, localizedNumber(sub.qtyBase), unitLabel(ls.first().sku.baseUnit)), Modifier.weight(0.7f), style = MaterialTheme.typography.labelMedium, color = c.textSecondary, textAlign = TextAlign.End, maxLines = 1)
                        Text(moneyBare(sub.grossMtk), Modifier.weight(0.7f), style = MaterialTheme.typography.labelMedium, color = c.textSecondary, textAlign = TextAlign.End, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** The credit switch row (android-glass s6.13): a flat 56 dp row of the group; the whole row toggles; the thumb carries a check when on. */
@Composable
private fun CreditSwitchRow(on: Boolean, label: String, onToggle: (Boolean) -> Unit, position: RowPosition, divider: Boolean) {
    val c = LocalAronColors.current
    val r = AronTokens.Radius.Card
    val shape = when (position) {
        RowPosition.Only -> RoundedCornerShape(r)
        RowPosition.First -> RoundedCornerShape(topStart = r, topEnd = r)
        RowPosition.Last -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
        RowPosition.Middle -> RoundedCornerShape(0.dp)
    }
    val dividerColor = c.borderDivider
    val dividerWidth = if (c.sunlight) AronTokens.Stroke.SunlightHairline else AronTokens.Stroke.Hairline
    val inset = AronTokens.Space.L
    Box(Modifier.fillMaxWidth().flatRow(position).drawBehind { if (divider) drawRect(dividerColor, Offset(inset.toPx(), size.height - dividerWidth.toPx()), Size(size.width - inset.toPx(), dividerWidth.toPx())) }) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = AronTokens.Touch.Primary).clip(shape)
                .toggleable(value = on, role = Role.Switch, onValueChange = onToggle)
                .padding(horizontal = AronTokens.Space.L, vertical = AronTokens.Space.S),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
        ) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            val track = if (on) (if (c.sunlight) c.accentHi else c.accent) else c.surfaceSolidRaised
            val ring = c.borderInput; val thumb = if (on) c.surfaceSolid else c.borderInput
            Box(
                Modifier.size(52.dp, 32.dp).drawBehind {
                    val r = CornerRadius(size.height / 2f)
                    drawRoundRect(track, cornerRadius = r)
                    if (!on) { val w = 2.dp.toPx(); drawRoundRect(ring, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(r.x - w / 2), style = Stroke(w)) }
                    val d = 20.dp.toPx(); val cx = if (on) size.width - 6.dp.toPx() - d / 2 else 6.dp.toPx() + d / 2
                    drawCircle(thumb, radius = d / 2, center = Offset(cx, size.height / 2))
                },
                contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                if (on) Icon(AronIcons.Check, contentDescription = null, tint = c.accent, modifier = Modifier.padding(end = 8.dp).size(AronTokens.Space.L))
            }
        }
    }
}

/** A labelled amount of the totals card: the value is a `MoneyText` (tokens s7.3: money never wraps or clips; a leading true minus survives the split). */
@Composable
private fun Amount(label: String, value: String) {
    val c = LocalAronColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = c.textSecondary, modifier = Modifier.weight(1f))
        MoneyText(value, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.End)
    }
}

@Composable
private fun problemText(p: ReviewProblem): String = when (p) {
    is ReviewProblem.TooManyLines -> localizedDigits(stringResource(R.string.review_problem_too_many_lines, p.max.toString(), p.count.toString()))
    is ReviewProblem.UnitNotAllowed -> stringResource(R.string.review_problem_unit, p.skuId.toString())
    is ReviewProblem.PaidOutOfRange, is ReviewProblem.PaidNotPaisa, ReviewProblem.PaidNegative -> stringResource(R.string.review_problem_paid)
    ReviewProblem.NothingToSell -> stringResource(R.string.review_problem_nothing)
    is ReviewProblem.NetNegative -> stringResource(R.string.review_problem_net_negative)
    is ReviewProblem.QcAboveCap -> stringResource(R.string.review_problem_qc_cap)
    else -> stringResource(R.string.review_problem_other)
}
