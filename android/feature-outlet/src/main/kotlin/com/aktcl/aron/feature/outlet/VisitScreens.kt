package com.aktcl.aron.feature.outlet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.LetterFilterBar
import com.aktcl.aron.core.ui.aron.RetailerPickerField
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.AronCard
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronMeter
import com.aktcl.aron.core.ui.AronSegmented
import com.aktcl.aron.core.ui.AronTextButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.MoneyText
import com.aktcl.aron.core.ui.RowPosition
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.rules.GeoVerdict
import com.aktcl.aron.rules.TextRules
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

object OutletTags {
    const val CHIPS = "out_chips"
    const val LIST = "out_list"
    const val REFRESH = "out_refresh"
    const val FORCE = "out_force"
    const val MAP = "out_map"
    const val RETRY = "out_retry"
    const val MESSAGE = "out_message"
}

/** What today's visits say about one outlet on the picker (screens-sr s5): visited with its memo total, or a skip outcome. */
data class PickerMark(val visited: Boolean = false, val memoTotalMtk: Long? = null, val outcomeCode: String? = null)

@Composable
private fun outcomeWord(code: String): String? = when (code) {
    "closed" -> stringResource(R.string.out_outcome_closed)
    "owner_absent" -> stringResource(R.string.out_outcome_owner_absent)
    "refused" -> stringResource(R.string.out_outcome_refused)
    "competitor_exclusive" -> stringResource(R.string.out_outcome_competitor)
    "not_reached" -> stringResource(R.string.out_outcome_not_reached)
    else -> null
}

/** "code · phone · cluster" (UI-SR-24): the phone as 11 ASCII digits, an unusable one left out. */
private fun captionOf(o: com.aktcl.aron.core.database.entity.OutletEntity): String {
    val phone = o.contactNumber?.let { TextRules.normalisePhone(it)?.value }
    return listOfNotNull(o.code, phone, o.clusterName).joinToString(" · ")
}

/**
 * The Sale picker (F-SR-016, F-SR-074, screens-sr s5): the subtitle, the visited meter, the segmented filter
 * (remaining / visited / all), flat rows (name, "code · phone · cluster", a visited check with the memo total, the
 * outcome word or a due chip) and the alphabet dock above the bottom inset, whose search chip turns into a text field.
 * Tapping a row is the primary action, so there is no bar. Works from the local bundle only.
 */
@Composable
fun OutletPickerContent(
    rows: List<PickerRow>,
    chips: List<String>,
    selectedChip: String,
    onChip: (String) -> Unit,
    onPick: (PickerRow) -> Unit,
    modifier: Modifier = Modifier,
    showDistance: Boolean = false,
    /** Today's visit marks by outlet id; empty when the host has none (every row then reads as remaining). */
    marks: Map<Long, PickerMark> = emptyMap(),
    syncedAt: String? = null,
    onReloadRoute: (() -> Unit)? = null,
    /**
     * The whole route, when [rows] is already narrowed by the alphabet chip: the meter and the segment counts read n/N of
     * the route, not of the letter. Null means [rows] is the route.
     */
    allRows: List<PickerRow>? = null,
) {
    val look = LocalAronLook.current
    // sr/sale-select.png: the pill field and the A-Z panel first; the route list opens when the SR taps the field or a letter
    var open by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    val visible = rows.filter { r ->
        q.isEmpty() || r.outlet.name.contains(q, ignoreCase = true) || r.outlet.nameBn?.contains(q, ignoreCase = true) == true || r.outlet.code.contains(q, ignoreCase = true)
    }
    val allLabel = stringResource(R.string.out_chip_all)
    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = AronTokens.Space.S), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            RetailerPickerField(value = null, placeholder = stringResource(R.string.out_picker_select), onClick = { open = true; searching = true })
            LetterFilterBar(
                letters = listOf(allLabel) + chips,
                selected = if (selectedChip == OutletPicker.ALL_CHIP) allLabel else selectedChip,
                onSelect = { l -> open = true; onChip(if (l == allLabel) OutletPicker.ALL_CHIP else l) },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                rows.isEmpty() && selectedChip == OutletPicker.ALL_CHIP -> AronEmptyState(
                    stringResource(R.string.out_picker_none), hint = stringResource(R.string.out_picker_none_hint), icon = AronIcons.Outlet,
                    actionLabel = onReloadRoute?.let { stringResource(R.string.out_picker_reload) }, onAction = onReloadRoute, syncedAt = syncedAt,
                )
                !open -> Text(
                    stringResource(R.string.out_picker_empty), color = look.ink, fontSize = 20.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                )
                else -> Column(Modifier.fillMaxSize()) {
                    if (searching) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = AronTokens.Space.Xs), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                            KitTextField(query, { query = it }, stringResource(R.string.out_search), Modifier.weight(1f))
                        }
                    }
                    if (visible.isEmpty()) {
                        AronEmptyState(
                            stringResource(R.string.out_filtered_none), icon = AronIcons.Search,
                            actionLabel = stringResource(R.string.out_show_all), onAction = { onChip(OutletPicker.ALL_CHIP); query = "" },
                        )
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize().testTag(OutletTags.LIST),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = AronTokens.Space.S), verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(visible, key = { it.outlet.outletId }, contentType = { "outlet" }) { r ->
                                PickerRowView(r, marks[r.outlet.outletId], showDistance, onPick = { onPick(r) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One picker row (screens-sr s5): the name in `type.bodyStrong` (2 lines), the "code · phone · cluster" caption, and the
 * due pill UNDER the caption so a wide amount never squeezes the name; trailing only the visited check with the memo total
 * or the outcome word. The whole row (72 dp or more) is the target, painted like the kit's flat row.
 */
@Composable
private fun PickerRowView(r: PickerRow, mark: PickerMark?, showDistance: Boolean, onPick: () -> Unit) {
    val c = LocalAronColors.current
    val look = LocalAronLook.current
    val outcome = mark?.outcomeCode?.let { outcomeWord(it) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .shadow(1.dp, shape)
            .clip(shape)
            .background(look.card)
            .clickable(role = Role.Button, onClick = onPick)
            .heightIn(min = PickerRowMin)
            .padding(horizontal = AronTokens.Space.L, vertical = AronTokens.Space.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
            Text(r.outlet.name, style = MaterialTheme.typography.titleSmall, color = look.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(captionOf(r.outlet), style = MaterialTheme.typography.bodySmall, color = look.inkMuted)
            if (r.outlet.openDueMtk > 0) {
                StatusPill(stringResource(R.string.out_due_chip, outletMoney(r.outlet.openDueMtk)), AronIcons.Wallet, c.warningContainer, c.warningOnContainer, Modifier.padding(top = AronTokens.Space.Xs))
            }
            if (showDistance) r.distanceM?.let { Text(stringResource(R.string.out_distance_m, localizedNumber(Math.round(it))), style = MaterialTheme.typography.bodySmall, color = look.inkMuted, maxLines = 1) }
        }
        when {
            mark?.visited == true && outcome == null -> Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
                    Icon(AronIcons.Check, contentDescription = null, tint = c.successOnContainer, modifier = Modifier.size(AronTokens.Space.L))
                    Text(stringResource(R.string.out_row_visited), style = MaterialTheme.typography.labelMedium, color = c.successOnContainer, maxLines = 1)
                }
                mark.memoTotalMtk?.let { Text(outletMoney(it), style = MaterialTheme.typography.bodySmall, color = look.inkMuted, maxLines = 1) }
            }
            outcome != null -> Text(outcome, style = MaterialTheme.typography.labelMedium, color = look.inkMuted, maxLines = 2)
        }
    }
}

private val PickerRowMin = 72.dp

/** The verdict card kinds (screens-sr s6): the card's container colour, ink and icon. */
private enum class GeoKind { Reading, InRange, Warning, Danger }

/** The geo card: an opaque `*.container` card with the kind's icon, the headline in `type.bodyStrong` and caption lines; never colour alone. */
@Composable
private fun GeoCard(kind: GeoKind, headline: String, lines: List<String>, modifier: Modifier = Modifier, extra: @Composable (() -> Unit)? = null) {
    val c = LocalAronColors.current
    val (bg, ink, edge, icon) = when (kind) {
        GeoKind.Reading -> Look(c.accentContainer, c.accentOnContainer, c.accent, AronIcons.Sync)
        GeoKind.InRange -> Look(c.successContainer, c.successOnContainer, c.success, AronIcons.CheckCircle)
        GeoKind.Warning -> Look(c.warningContainer, c.warningOnContainer, c.warning, AronIcons.Warning)
        GeoKind.Danger -> Look(c.dangerContainer, c.dangerOnContainer, c.danger, AronIcons.Warning)
    }
    val stroke = if (c.sunlight) AronTokens.Stroke.SunlightHairline else AronTokens.Stroke.Hairline
    val edgeColor = edge.copy(alpha = AronTokens.Alpha.BannerEdge)
    Column(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val r = CornerRadius(AronTokens.Radius.Card.toPx()); val w = stroke.toPx()
                drawRoundRect(bg, cornerRadius = r)
                drawRoundRect(edgeColor, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(r.x - w / 2), style = Stroke(w))
            }
            .padding(AronTokens.Space.L),
        verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(AronTokens.Size.Icon))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
                Text(headline, style = MaterialTheme.typography.titleSmall, color = ink)
                lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = ink) }
            }
        }
        extra?.invoke()
    }
}

private data class Look(val bg: Color, val ink: Color, val edge: Color, val icon: ImageVector)

/** Wording a role may replace on the visit check; both null = the SR's text. */
data class VisitCheckTexts(val outOfRange: String? = null, val forceLabel: String? = null)

/**
 * The visit check (F-SR-017, 018, 019, N-041; screens-sr s6): the outlet card, the geo card in the verdict's colour, the
 * offline range view, the text buttons, and the bar whose primary follows the verdict table. Renders its own bar. The
 * optional callbacks add what the host wires: the start-call primary once a visit is open, previous sales, no sale,
 * the system location setting and back.
 */
@Composable
fun VisitCheckContent(
    state: VisitUiState,
    onRefresh: () -> Unit,
    onForceSale: () -> Unit,
    onRetry: () -> Unit,
    onMap: (() -> Unit)?,
    modifier: Modifier = Modifier,
    outlet: OutletCard? = null,
    onStartCall: (() -> Unit)? = null,
    onPreviousSales: (() -> Unit)? = null,
    onNoSale: (() -> Unit)? = null,
    onOpenLocationSettings: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    /** Role wording over the defaults (the AMO's "not within retailer range" and "Manual Override"); null keeps the SR text. */
    texts: VisitCheckTexts? = null,
    /** The fix of the committed visit (in range), so the confirm step shows the distance and map without a tap (D-MAP-02). */
    geo: VisitGeoInfo? = null,
    /** The host's lite map drawn over the offline range picture; null keeps the picture alone (AMO, tests, no Maps key). */
    mapSlot: GeoMapSlot? = null,
) {
    val c = LocalAronColors.current
    val startLabel = stringResource(R.string.out_start_call)
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
        ) {
            val visitOutlet = (state as? VisitUiState.NeedsDecision)?.outlet ?: (state as? VisitUiState.CommitFailed)?.outlet
            if (outlet != null || visitOutlet != null) OutletCardView(outlet, visitOutlet)
            when (state) {
                VisitUiState.Idle -> Unit
                VisitUiState.ReadingFix -> GeoCard(GeoKind.Reading, stringResource(R.string.out_reading_fix), emptyList(), Modifier.testTag(OutletTags.MESSAGE))
                is VisitUiState.NeedsDecision -> {
                    val noCoordinates = state.result.verdict == GeoVerdict.NO_OUTLET_LOCATION
                    val overrideHeadline = texts?.outOfRange?.takeIf { state.result.verdict == GeoVerdict.OUT_OF_RANGE }
                    val headline = overrideHeadline ?: stringResource(
                        when (state.result.verdict) {
                            GeoVerdict.OUT_OF_RANGE -> R.string.out_out_of_range
                            GeoVerdict.NO_FIX -> R.string.out_no_fix
                            GeoVerdict.ACCURACY_TOO_LOW -> R.string.out_accuracy_low
                            GeoVerdict.NO_OUTLET_LOCATION -> R.string.out_no_outlet_location
                            GeoVerdict.MOCKED -> R.string.out_mocked
                            GeoVerdict.IN_RANGE -> R.string.out_in_range
                        },
                    )
                    val lines = buildList {
                        // distance and radius always show (D-MAP-02); with a located outlet and a fix the panel below carries them instead
                        val o = state.outlet
                        val panelShows = o.lat != null && o.lng != null && state.phoneLat != null && state.phoneLng != null
                        if (!panelShows) state.result.distanceM?.let { add(stringResource(R.string.out_distance_line, localizedNumber(Math.round(it)), localizedNumber(o.radiusM.toLong()))) }
                        if (state.mockWarning) add(stringResource(R.string.out_mocked_refresh))
                        // a refresh cannot give an outlet its coordinates (Geo.kt always answers FORCE_SALE), so no refresh line there
                        if (!noCoordinates) {
                            if (state.refreshLeft) add(stringResource(R.string.out_refresh_left_line, localizedNumber((state.refreshMax - state.refreshCount).toLong())))
                            else add(stringResource(R.string.out_no_refresh_left))
                        }
                    }
                    val kind = if (state.result.verdict == GeoVerdict.IN_RANGE) GeoKind.InRange else GeoKind.Warning
                    GeoCard(kind, headline, lines, Modifier.testTag(OutletTags.MESSAGE)) {
                        val info = VisitGeoInfo.of(state.outlet, state.result.verdict, state.result.distanceM, state.phoneLat, state.phoneLng)
                        if (info != null && info.hasFix) VisitGeoPanel(info, mapSlot = mapSlot, onOpenMaps = onMap)
                        // no fix: the Maps app still opens on tap, and never blocks Force Sale (N-041)
                        else if (onMap != null) AronTextButton(stringResource(R.string.out_open_maps), onMap, Modifier.testTag(OutletTags.MAP), icon = AronIcons.Map, color = c.textPrimary)
                    }
                }
                is VisitUiState.Blocked -> GeoCard(GeoKind.Warning, stringResource(R.string.out_blocked_mock), emptyList(), Modifier.testTag(OutletTags.MESSAGE))
                is VisitUiState.LocationBlocked -> GeoCard(
                    GeoKind.Warning, stringResource(if (state.reason == "location_off") R.string.out_location_off else R.string.out_location_denied), emptyList(), Modifier.testTag(OutletTags.MESSAGE),
                )
                is VisitUiState.CommitFailed -> GeoCard(GeoKind.Danger, stringResource(R.string.out_commit_failed), emptyList(), Modifier.testTag(OutletTags.MESSAGE))
                is VisitUiState.Open -> GeoCard(GeoKind.InRange, stringResource(R.string.out_in_range), emptyList(), Modifier.testTag(OutletTags.MESSAGE)) {
                    if (geo != null) VisitGeoPanel(geo, mapSlot = mapSlot, onOpenMaps = onMap)
                }
            }
            if (onPreviousSales != null || onNoSale != null) {
                // the two text buttons share a line when both fit (English) and wrap one under the other otherwise (Bangla on 360 dp)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                    if (onPreviousSales != null) AronTextButton(stringResource(R.string.out_history), onPreviousSales)
                    if (onNoSale != null) AronTextButton(stringResource(R.string.out_no_sale), onNoSale)
                }
            }
        }
        // the bar by verdict (screens-sr s6 table)
        when (state) {
            VisitUiState.ReadingFix -> OutletBar(BarAction(startLabel, {}, enabled = false), reason = stringResource(R.string.out_reason_wait_fix))
            is VisitUiState.NeedsDecision -> {
                val force = BarAction(texts?.forceLabel ?: stringResource(R.string.out_force_sale), onForceSale, OutletTags.FORCE)
                when {
                    // outlet has no coordinates: the primary is Force Sale (screens-sr s6 table); a refresh changes nothing
                    state.result.verdict == GeoVerdict.NO_OUTLET_LOCATION && state.forceSaleAvailable -> OutletBar(force)
                    state.refreshLeft -> OutletBar(
                        BarAction(stringResource(R.string.out_refresh_n, localizedNumber((state.refreshMax - state.refreshCount).toLong())), onRefresh, OutletTags.REFRESH, icon = AronIcons.Refresh),
                        secondary = if (state.forceSaleAvailable) force else null,
                    )
                    state.forceSaleAvailable -> OutletBar(force)
                    else -> OutletBar(BarAction(stringResource(R.string.out_refresh), {}, enabled = false), reason = stringResource(R.string.out_no_refresh_left))
                }
            }
            is VisitUiState.Blocked -> if (onBack != null) OutletBar(BarAction(stringResource(R.string.out_go_back), onBack))
            is VisitUiState.LocationBlocked -> if (onOpenLocationSettings != null) OutletBar(BarAction(stringResource(R.string.out_turn_on_location), onOpenLocationSettings))
            is VisitUiState.CommitFailed -> OutletBar(BarAction(stringResource(R.string.out_retry_short), onRetry, OutletTags.RETRY, icon = AronIcons.Refresh))
            is VisitUiState.Open -> if (onStartCall != null) OutletBar(BarAction(startLabel, onStartCall, icon = AronIcons.ArrowForward))
            VisitUiState.Idle -> Unit
        }
    }
}

/** The outlet card (F-SR-049): name, owner, "code · phone · cluster" and the wrapping chips (channel, due, last visit). */
@Composable
private fun OutletCardView(card: OutletCard?, visit: VisitOutlet?) {
    val c = LocalAronColors.current
    AronCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
            Text(card?.name ?: visit?.name.orEmpty(), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            if (card != null) Text(stringResource(R.string.out_owner, card.ownerName), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            val caption = listOfNotNull(card?.code ?: visit?.code, card?.phone, card?.clusterName).joinToString(" · ")
            if (caption.isNotEmpty()) Text(caption, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            if (card != null) {
                FlowRow(Modifier.padding(top = AronTokens.Space.Xs), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                    if (card.channel.isNotBlank()) StatusPill(card.channel, AronIcons.Outlet, c.offlineContainer, c.offlineOnContainer)
                    if (card.openDueMtk > 0) StatusPill(stringResource(R.string.out_due_chip, outletMoney(card.openDueMtk)), AronIcons.Wallet, c.warningContainer, c.warningOnContainer)
                    card.lastVisitAt?.let { StatusPill(stringResource(R.string.out_last_visit, localizedDigits(it.take(10))), AronIcons.Clock, c.accentContainer, c.accentOnContainer) }
                }
            }
        }
    }
}

/**
 * The range view (N-041, android-glass s6.18): an offline `Canvas` from data already on the phone. The outlet pin sits at
 * the centre, the radius circle is scaled to fit, the phone's dot is placed by the metre offsets of the fix, a bearing
 * arrow points from the dot toward the pin (the way to walk), and a scale bar reads the metres. No map SDK, no network,
 * never blocks Force Sale.
 */
@Composable
fun RangeView(outletLat: Double, outletLng: Double, radiusM: Int, phoneLat: Double, phoneLng: Double, modifier: Modifier = Modifier, inRange: Boolean = false, showScale: Boolean = true) {
    val c = LocalAronColors.current
    val ring = c.accent; val fill = c.accentContainer; val pin = c.textPrimary; val pinHole = c.surfaceSolid
    val dot = if (inRange) c.success else c.warning; val bar = c.textPrimary
    val dxM = (phoneLng - outletLng) * 111_320.0 * cos(Math.toRadians(outletLat))
    val dyM = (phoneLat - outletLat) * 110_540.0
    // the view fits the radius circle and the phone's dot on both axes, so the picture is as large as the canvas allows
    val extentX = max(radiusM * 1.15, kotlin.math.abs(dxM) * 1.15).coerceAtLeast(20.0)
    val extentY = max(radiusM * 1.15, kotlin.math.abs(dyM) * 1.15).coerceAtLeast(20.0)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }; val hPx = with(density) { RangeViewHeight.toPx() }; val inset = with(density) { 12.dp.toPx() }
        val pxPerM = minOf((wPx / 2f - inset) / extentX.toFloat(), (hPx / 2f - inset) / extentY.toFloat())
        val scaleM = niceScale((wPx / 3f / pxPerM).toDouble())
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
            Canvas(Modifier.fillMaxWidth().height(RangeViewHeight)) {
                val cx = size.width / 2f; val cy = size.height / 2f
                val r = radiusM * pxPerM
                drawCircle(fill, radius = r, center = Offset(cx, cy))
                drawCircle(ring, radius = r, center = Offset(cx, cy), style = Stroke(AronTokens.Stroke.Strong.toPx()))
                // the outlet pin
                drawCircle(pin, radius = 7.dp.toPx(), center = Offset(cx, cy))
                drawCircle(pinHole, radius = 3.dp.toPx(), center = Offset(cx, cy))
                // the phone, north up, and the bearing arrow from the dot toward the pin (drawn once the two are apart)
                val px = cx + (dxM * pxPerM).toFloat(); val py = cy - (dyM * pxPerM).toFloat()
                val gap = hypot(cx - px, cy - py)
                val headLen = 8.dp.toPx(); val pinR = 7.dp.toPx(); val dotR = 8.dp.toPx()
                if (gap > dotR + pinR + headLen) {
                    val ux = (cx - px) / gap; val uy = (cy - py) / gap
                    val tip = Offset(cx - ux * (pinR + 2.dp.toPx()), cy - uy * (pinR + 2.dp.toPx()))
                    val tail = Offset(px + ux * dotR, py + uy * dotR)
                    val w = AronTokens.Stroke.Strong.toPx()
                    drawLine(dot, tail, tip, strokeWidth = w, cap = StrokeCap.Round)
                    // two short strokes at 30 degrees either side of the shaft
                    val a = atan2(uy, ux)
                    for (s in floatArrayOf(-1f, 1f)) {
                        val b = a + s * (PI / 6).toFloat()
                        drawLine(dot, tip, Offset(tip.x - cos(b) * headLen, tip.y - sin(b) * headLen), strokeWidth = w, cap = StrokeCap.Round)
                    }
                }
                drawCircle(pinHole, radius = dotR, center = Offset(px, py))
                drawCircle(dot, radius = 6.dp.toPx(), center = Offset(px, py))
                // the scale bar, bottom start
                val barW = (scaleM * pxPerM).toFloat(); val y = size.height - 6.dp.toPx(); val x0 = 4.dp.toPx()
                val w = AronTokens.Stroke.Focus.toPx()
                drawLine(bar, Offset(x0, y), Offset(x0 + barW, y), strokeWidth = w, cap = StrokeCap.Round)
                drawLine(bar, Offset(x0, y - 4.dp.toPx()), Offset(x0, y), strokeWidth = w)
                drawLine(bar, Offset(x0 + barW, y - 4.dp.toPx()), Offset(x0 + barW, y), strokeWidth = w)
            }
            if (showScale) Text(stringResource(R.string.out_scale, localizedNumber(scaleM.toLong())), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
    }
}

internal val RangeViewHeight = 180.dp

/** A round scale-bar length at or under [m]: 10, 20, 50, 100, 200, 500, ... metres. */
private fun niceScale(m: Double): Double {
    val steps = doubleArrayOf(10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0)
    return steps.lastOrNull { it <= m } ?: steps.first()
}
