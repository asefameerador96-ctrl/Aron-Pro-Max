package com.aktcl.aron.feature.amo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.feature.outlet.OutletPicker

/** One row of the AMO outlet drop-down (screenshots 19, 22): the label, the green dot (a base location on file) and the brown badge (called today). */
data class CallOutletRow(val outlet: OutletEntity, val label: String, val chip: String, val hasBase: Boolean, val calledToday: Boolean)

/** The range area under the two drop-downs of a call picker. */
sealed interface CallRange {
    /** No outlet chosen yet. */
    data object Idle : CallRange
    /** The fix is being read. */
    data object Checking : CallRange
    /** Outside the retailer's radius: the warning, Manual Override (when [overrideAvailable]) and Refresh (when [refreshLeft]). */
    data class Out(val overrideAvailable: Boolean, val refreshLeft: Boolean) : CallRange
    /** In range, or overridden: the menu slot shows. */
    data object In : CallRange
}

/** Pure list logic of the AMO route/retailer pickers (owner answer 3: the green dot and the brown badge, exactly, no other colour). */
object CallPick {
    /**
     * Rows of the retailer drop-down: active outlets of the route, filtered by the alphabet chip, ordered by outlet code descending
     * (screenshot 19: newest code first; numeric codes compare as numbers, any other code as text).
     */
    fun rows(outlets: List<OutletEntity>, calledToday: Set<Long>, chip: String = OutletPicker.ALL_CHIP): List<CallOutletRow> =
        OutletPicker.rows(outlets, chip).map {
            CallOutletRow(it.outlet, it.label, it.chip, hasBase = it.outlet.lat != null && it.outlet.lng != null, calledToday = it.outlet.outletId in calledToday)
        }.sortedWith(CODE_DESC)

    /** The letters present in the route (case-insensitive, upper-case chips), Latin first; the caller shows "All" before them. */
    fun letters(outlets: List<OutletEntity>): List<String> = OutletPicker.chips(OutletPicker.rows(outlets))

    private val CODE_DESC = Comparator<CallOutletRow> { a, b ->
        val x = a.outlet.code.trim().toLongOrNull()
        val y = b.outlet.code.trim().toLongOrNull()
        when {
            x != null && y != null -> y.compareTo(x)
            x != null -> -1
            y != null -> 1
            else -> b.outlet.code.compareTo(a.outlet.code)
        }.let { if (it != 0) it else a.outlet.outletId.compareTo(b.outlet.outletId) }
    }
}

object CallPickTags {
    const val ROUTE = "amo_cp_route"; const val OUTLET = "amo_cp_outlet"; const val LIVE = "amo_cp_live"; const val WARNING = "amo_cp_warning"
    const val OVERRIDE = "amo_cp_override"; const val REFRESH = "amo_cp_refresh"; const val BAR = "amo_cp_bar"; const val CHECKING = "amo_cp_checking"
    const val ROUTE_PANEL = "amo_cp_route_panel"; const val OUTLET_PANEL = "amo_cp_outlet_panel"
    fun chip(letter: String) = "amo_cp_chip_$letter"
    fun route(id: Long) = "amo_cp_route_$id"
    fun outlet(id: Long) = "amo_cp_outlet_$id"
}

private val Border = 2.dp
private val Green = Color(0xFF22C922)
private val Brown = Color(0xFF8D5A2B)

/**
 * The picker of a Control Call, a Joint Call and the Sales tile (screenshots 17-22, 29-30): the route drop-down, the retailer drop-down
 * with the LIVE indicator (an indicator, not a button), the alphabet bar of the letters present, then the [range] area. [below] is what
 * the screen shows when the call may go on (the menu tiles). The lists open as full panels ([routePanel] / [outletPanel]); the host holds that state.
 */
@Composable
fun AmoCallPickContent(
    routeText: String?, outletText: String?, outletHasBase: Boolean, letters: List<String>, chip: String, live: Boolean,
    range: CallRange, enabledOutlet: Boolean, locked: Boolean,
    onOpenRoute: () -> Unit, onOpenOutlet: () -> Unit, onChip: (String) -> Unit, onOverride: () -> Unit, onRefresh: () -> Unit,
    modifier: Modifier = Modifier, below: @Composable () -> Unit = {},
    /** Sales path (screenshots 21 and 22): the Sales tile shows under the fields before any retailer is chosen. */
    belowWhenIdle: Boolean = false,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DropField(routeText, stringResource(R.string.amo_cp_route_placeholder), enabled = !locked, dot = false, onClick = onOpenRoute, modifier = Modifier.fillMaxWidth().testTag(CallPickTags.ROUTE))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DropField(outletText, stringResource(R.string.amo_cp_outlet_placeholder), enabled = enabledOutlet && !locked, dot = outletText != null && outletHasBase, onClick = onOpenOutlet, modifier = Modifier.weight(1f).testTag(CallPickTags.OUTLET))
            if (live) LiveBadge()
        }
        if (letters.isNotEmpty()) AlphabetBar(letters, chip, onChip, enabled = !locked)
        when (range) {
            CallRange.Idle -> if (belowWhenIdle) below()
            CallRange.Checking -> Text(stringResource(R.string.amo_cp_checking), style = amoText(16, color = LocalAronLook.current.inkMuted, align = TextAlign.Center), modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(CallPickTags.CHECKING))
            is CallRange.Out -> Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("⚠", style = amoText(36, color = Color(0xFFD32F2F)))
                Text(stringResource(R.string.amo_cp_out_of_range), style = amoText(17, color = Color(0xFF0B4A9E), align = TextAlign.Center), modifier = Modifier.testTag(CallPickTags.WARNING))
                if (range.overrideAvailable) GradientButton(stringResource(R.string.amo_cp_override), Color(0xFF0D4FA0), Color(0xFF4B1A9A), onOverride, Modifier.testTag(CallPickTags.OVERRIDE))
                if (range.refreshLeft) GradientButton(stringResource(R.string.amo_cp_refresh), Color(0xFF6B6B6B), Color(0xFF2E2E2E), onRefresh, Modifier.testTag(CallPickTags.REFRESH))
            }
            CallRange.In -> below()
        }
    }
}

@Composable
private fun GradientButton(text: String, top: Color, bottom: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(Brush.verticalGradient(listOf(top, bottom)))
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = amoText(18, bold = true, color = Color.White, align = TextAlign.Center), modifier = Modifier.padding(8.dp)) }
}

/** A white field with the 2 dp purple outline and a caret; [dot] draws the green dot of an outlet with a base location. */
@Composable
private fun DropField(text: String?, placeholder: String, enabled: Boolean, dot: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.heightIn(min = 52.dp).clip(shape).background(look.card).border(BorderStroke(Border, if (enabled) look.purple else look.disabledFill), shape)
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.DropdownList, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (dot) Box(Modifier.size(14.dp).clip(CircleShape).background(Green))
        Text(text ?: placeholder, style = amoText(if ((text?.length ?: 0) > 36) 13 else 16, color = if (text != null) look.ink else look.hint), modifier = Modifier.weight(1f), maxLines = 2)
        Text("▼", style = amoText(12, color = look.caret))
    }
}

/** The red LIVE badge (screenshot 17): an indicator only, never tappable. */
@Composable
private fun LiveBadge() {
    Box(Modifier.testTag(CallPickTags.LIVE).clip(RoundedCornerShape(8.dp)).background(Color(0xFFD32F2F)).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Text(stringResource(R.string.amo_cp_live), style = amoText(13, bold = true, color = Color.White))
    }
}

/** The charcoal alphabet bar: "All" first (selected = green gradient), then the letters present, wrapping to rows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlphabetBar(letters: List<String>, chip: String, onChip: (String) -> Unit, enabled: Boolean) {
    FlowRow(
        Modifier.fillMaxWidth().testTag(CallPickTags.BAR).clip(RoundedCornerShape(14.dp)).background(Color(0xFF555555)).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val all = OutletPicker.ALL_CHIP
        listOf(all to stringResource(R.string.amo_cp_all)).plus(letters.map { it to it }).forEach { (key, label) ->
            val on = key == chip
            Box(
                Modifier.testTag(CallPickTags.chip(key.ifEmpty { "ALL" })).size(width = if (key == all) 52.dp else 40.dp, height = 40.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (on) Brush.verticalGradient(listOf(Color(0xFFB7E34A), Color(0xFF2E9E3E))) else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)))
                    .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button) { onChip(key) },
                contentAlignment = Alignment.Center,
            ) { Text(label, style = amoText(16, bold = on, color = Color.White, align = TextAlign.Center)) }
        }
    }
}

/** The route list panel (screenshot 21): one row per route, no icons. */
@Composable
fun AmoRoutePanel(rows: List<AmoRouteRow>, selected: Long?, onPick: (AmoRouteRow) -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    LazyColumn(modifier.fillMaxWidth().testTag(CallPickTags.ROUTE_PANEL).background(look.card)) {
        items(rows, key = { it.routeId }) { r ->
            Text(
                r.shortTitle, style = amoText(17), modifier = Modifier.fillMaxWidth().testTag(CallPickTags.route(r.routeId))
                    .background(if (r.routeId == selected) Color(0xFFDDDCE0) else Color.Transparent).clickable(role = androidx.compose.ui.semantics.Role.Button) { onPick(r) }.padding(horizontal = 16.dp, vertical = 18.dp),
            )
        }
    }
}

/** The retailer list panel (screenshots 19, 22): the green dot, the brown badge, then "name (code-phone-cluster)"; the selected row is grey. */
@Composable
fun AmoOutletPanel(rows: List<CallOutletRow>, selectedId: Long?, onPick: (CallOutletRow) -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    val base = stringResource(R.string.amo_cp_dot_base)
    val today = stringResource(R.string.amo_cp_badge_today)
    LazyColumn(modifier.fillMaxWidth().testTag(CallPickTags.OUTLET_PANEL).background(look.card)) {
        items(rows, key = { it.outlet.outletId }) { r ->
            Row(
                Modifier.fillMaxWidth().testTag(CallPickTags.outlet(r.outlet.outletId))
                    .background(if (r.outlet.outletId == selectedId) Color(0xFFDDDCE0) else Color.Transparent).clickable(role = androidx.compose.ui.semantics.Role.Button) { onPick(r) }.padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (r.hasBase) Box(Modifier.size(14.dp).clip(CircleShape).background(Green).semantics { contentDescription = base })
                if (r.calledToday) Box(Modifier.size(24.dp).clip(CircleShape).background(Brown).semantics { contentDescription = today })
                Text(r.label, style = amoText(15), modifier = Modifier.weight(1f))
            }
        }
    }
}

/** The three white tiles of the Control Call menu (screenshot 18): icon over label. A null callback hides the tile. */
@Composable
fun AmoMenuTiles(tiles: List<MenuTile>, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Row(modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.forEach { t ->
            Column(
                Modifier.weight(1f).heightIn(min = 112.dp).clip(RoundedCornerShape(14.dp)).background(look.card).border(BorderStroke(1.dp, look.tileHairline), RoundedCornerShape(14.dp))
                    .testTag(t.tag).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = t.onClick).padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                androidx.compose.foundation.Image(painterResource(t.icon), null, Modifier.size(52.dp))
                Text(stringResource(t.label), style = amoText(14, color = look.tileLabel, align = TextAlign.Center), modifier = Modifier.padding(top = 6.dp).widthIn(max = 140.dp))
            }
        }
    }
}

data class MenuTile(@androidx.annotation.DrawableRes val icon: Int, @androidx.annotation.StringRes val label: Int, val tag: String, val onClick: () -> Unit)

/** The range area of the picker as a pure function of the host's state (tested; the host only gathers the inputs). */
object CallRangeRule {
    /**
     * [outletId] is the retailer on screen (null = none), [callOpen] a call open in the session for it, [checking] the fix is being read,
     * [needs] the (override available, refresh left) of a pending out-of-range answer FOR THIS retailer, [checked] the retailer the flow last
     * answered for. On the Sales path nothing was asked until the tile is tapped, so the area stays "in" (the tile starts the check).
     */
    fun of(outletId: Long?, callOpen: Boolean, checking: Boolean, needs: Pair<Boolean, Boolean>?, salesPath: Boolean, checked: Long?): CallRange = when {
        outletId == null -> CallRange.Idle
        callOpen -> CallRange.In
        checking -> CallRange.Checking
        needs != null -> CallRange.Out(needs.first, needs.second)
        salesPath && checked != outletId -> CallRange.In
        else -> CallRange.Idle
    }
}
