package com.aktcl.aron.feature.tso.mycall

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.feature.tso.R
import com.aktcl.aron.feature.tso.TsoBanner
import com.aktcl.aron.feature.tso.TsoBannerKind
import com.aktcl.aron.feature.tso.TsoButtonStyle
import com.aktcl.aron.feature.tso.TsoCard
import com.aktcl.aron.feature.tso.TsoDates
import com.aktcl.aron.feature.tso.TsoDropdownField
import com.aktcl.aron.feature.tso.TsoFrame
import com.aktcl.aron.feature.tso.TsoGlowField
import com.aktcl.aron.feature.tso.TsoGradientButton
import com.aktcl.aron.feature.tso.TsoLoading
import com.aktcl.aron.feature.tso.TsoMenuGrid
import com.aktcl.aron.feature.tso.TsoMenuTile
import com.aktcl.aron.feature.tso.TsoNav
import com.aktcl.aron.feature.tso.TsoPillTone
import com.aktcl.aron.feature.tso.TsoSegmentedTabs
import com.aktcl.aron.feature.tso.TsoStatusPill
import com.aktcl.aron.feature.tso.leave.backOnly
import com.aktcl.aron.feature.tso.zoneName
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.launch

object MyCallTags {
    const val SET_PLAN = "tso_mc_set_plan"
    const val VISIT_PLAN = "tso_mc_visit_plan"
    const val DATE = "tso_mc_date"
    const val ZONE = "tso_mc_zone"
    const val ROUTE = "tso_mc_route"
    const val MISSING_ROUTE = "tso_mc_missing_route"
    const val SHOW = "tso_mc_show"
    const val SAVE = "tso_mc_save"
    const val SELECT_ALL = "tso_mc_select_all"
    const val UNAVAILABLE = "tso_mc_unavailable"
    const val SAVED = "tso_mc_saved"
    const val ERRORS = "tso_mc_errors"
    const val EMPTY = "tso_mc_empty"
    const val SUBMIT = "tso_mc_submit"
    const val NO_SR = "tso_mc_no_sr"
    const val ASSIGNED = "tso_mc_assigned"
    const val COMMENT = "tso_mc_comment"
    const val TYPE = "tso_mc_type"
    const val INFO = "tso_mc_info"
    fun zone(id: Long) = "tso_mc_zone_$id"
    fun route(id: Long) = "tso_mc_route_$id"
    fun outlet(id: Long) = "tso_mc_outlet_$id"
    fun waiting(id: Long) = "tso_mc_waiting_$id"
    fun yes(criterionId: Long) = "tso_mc_yes_$criterionId"
    fun no(criterionId: Long) = "tso_mc_no_$criterionId"
    fun text(criterionId: Long) = "tso_mc_text_$criterionId"
    fun type(code: String) = "tso_mc_type_$code"
    const val DELEGATE_YES = "tso_mc_delegate_yes"
    const val DELEGATE_NO = "tso_mc_delegate_no"
}

/** The hub of the former tile Home (kept for the legacy [MyCallFlow]; the drawer opens My Visit Plan and Set Plan directly). */
@Composable
fun MyCallHub(onSetPlan: () -> Unit, onVisitPlan: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    TsoFrame(stringResource(R.string.tso_drawer_my_call), onBack, modifier, tile = com.aktcl.aron.feature.home.RoleHomes.TsoTile.MY_CALL) {
        Column(Modifier.fillMaxSize().padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            TsoMenuGrid(
                listOf(
                    TsoMenuTile(stringResource(R.string.tso_mc_visit_plan), "visit_plan", MyCallTags.VISIT_PLAN, onVisitPlan),
                    TsoMenuTile(stringResource(R.string.tso_mc_set_plan), "set_plan", MyCallTags.SET_PLAN, onSetPlan),
                ),
            )
        }
    }
}

/**
 * The date field of screenshots 31, 34 and 38: the bold label above ([label], none on Assign Task), the calendar icon then
 * the long date ("October 26, 2026"; Bangla month names and digits under bn). A tap opens the date dialog limited to
 * [min]..[max]; the field always holds a date (today by default), so it never shows a placeholder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun McDateField(value: String, onChange: (String) -> Unit, label: String?, modifier: Modifier = Modifier, min: String? = null, max: String? = null) {
    val look = LocalAronLook.current
    var open by rememberSaveable { mutableStateOf(false) }
    val shown = TsoDates.long(value, LocalAppLanguage.current)
    val name = label ?: stringResource(R.string.tso_mc_date)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = look.ink)
        Row(
            modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).background(look.card).border(1.dp, look.purple, RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClickLabel = name) { open = true }
                .semantics { contentDescription = "$name, $shown" }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(AronIcons.Calendar, contentDescription = null, tint = look.ink)
            Text(shown, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = look.ink)
        }
    }
    if (open) {
        fun ms(d: String?) = d?.let { runCatching { LocalDate.parse(it).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() }
        val minMs = ms(min); val maxMs = ms(max)
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = ms(value),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = (minMs == null || utcTimeMillis >= minMs) && (maxMs == null || utcTimeMillis <= maxMs)
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton({ picker.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }; open = false }, colors = ButtonDefaults.textButtonColors(contentColor = look.purple)) {
                    Text(stringResource(R.string.tso_date_ok))
                }
            },
            dismissButton = { TextButton({ open = false }, colors = ButtonDefaults.textButtonColors(contentColor = look.purple)) { Text(stringResource(R.string.tso_fs_cancel)) } },
        ) { Box { DatePicker(picker) } }
    }
}

/** Select Zone and Select Route as dropdowns (screenshots 31, 32, 34): the routes of the chosen zone, by name. */
@Composable
private fun ZoneRoutePickers(routes: List<PlanRoute>, load: PickLoad, zoneId: Long?, routeId: Long?, missingRoute: Boolean, onZone: (Long) -> Unit, onRoute: (Long) -> Unit) {
    if (load == PickLoad.UNAVAILABLE) TsoBanner(stringResource(R.string.tso_mc_unavailable), Modifier.testTag(MyCallTags.UNAVAILABLE), kind = TsoBannerKind.Offline)
    val zones = zonesOf(routes)
    val names = zones.associateWith { zoneName(it) }
    TsoDropdownField(
        zones, zoneId, onZone, { names[it] ?: it.toString() }, Modifier.testTag(MyCallTags.ZONE),
        label = stringResource(R.string.tso_mc_select_zone), placeholder = stringResource(R.string.tso_mc_select_zone),
    )
    val inZone = routes.filter { it.zoneId == zoneId }
    TsoDropdownField(
        inZone, inZone.firstOrNull { it.id == routeId }, { onRoute(it.id) }, { it.name }, Modifier.testTag(MyCallTags.ROUTE),
        label = stringResource(R.string.tso_mc_select_route), placeholder = stringResource(R.string.tso_mc_select_route), enabled = zoneId != null,
    )
    if (missingRoute) Text(stringResource(R.string.tso_mc_err_route), Modifier.testTag(MyCallTags.MISSING_ROUTE), style = MaterialTheme.typography.bodySmall, color = LocalAronLook.current.red)
}

/**
 * Set Plan (screenshots 31 and 32, D-TSO-MAN-35): Select Date (today, up to `cfg.tso.plan_max_days_ahead` days ahead),
 * Select Zone, Select Route, and the lime "Show Outlet", which opens Select Outlets ([onShowOutlets]); without a route it
 * says "Choose a route". Home button (s8 row 31).
 */
@Composable
fun SetPlanScreen(state: SetPlanState, onBack: () -> Unit, modifier: Modifier = Modifier, nav: TsoNav = backOnly(onBack), onShowOutlets: () -> Unit = {}) {
    val ui by state.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) { state.loadRoutes() }
    TsoFrame(stringResource(R.string.tso_mc_set_plan), nav, homeButton = true, modifier = modifier, onBack = onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.L)) {
            McDateField(ui.date, { state.setDate(it) }, stringResource(R.string.tso_mc_select_date), Modifier.testTag(MyCallTags.DATE), min = state.todayIso(), max = state.maxIso())
            ZoneRoutePickers(ui.routes, ui.routesLoad, ui.zoneId, ui.routeId, ui.missingRoute, state::pickZone, state::pickRoute)
            TsoGradientButton(
                stringResource(R.string.tso_mc_show_outlets), { if (state.ui.value.routeId == null) scope.launch { state.showOutlets() } else onShowOutlets() },
                Modifier.padding(top = AronTokens.Space.L).testTag(MyCallTags.SHOW), style = TsoButtonStyle.Lime,
            )
        }
    }
}

/** One outlet of Select Outlets (screenshot 33): name, "code | cluster", "owner | phone", and the tick (a ring until chosen). */
@Composable
private fun OutletPickCard(o: PlanOutlet, bangla: Boolean, selected: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    TsoCard(
        modifier.fillMaxWidth().toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() }),
        glow = selected,
    ) {
        Row(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(o.title(bangla), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = look.ink)
                Text(listOfNotNull(o.code, o.cluster).joinToString(" | "), style = MaterialTheme.typography.bodyMedium, color = look.inkMuted)
                Text(localizedDigits(listOfNotNull(o.owner.ifBlank { null }, o.phone).joinToString(" | ")), style = MaterialTheme.typography.bodySmall, color = look.inkMuted)
            }
            if (selected) Icon(AronIcons.CheckCircle, contentDescription = null, tint = look.goStart, modifier = Modifier.size(32.dp))
            else Box(Modifier.size(28.dp).clip(CircleShape).border(2.dp, look.inkMuted, CircleShape))
        }
    }
}

/**
 * Select Outlets (screenshot 33, D-TSO-MAN-35): the route's outlets with a tick each, the outlets already in this date's
 * plan ticked (the save is a union), "Select all" (kept, an improvement), and the lime "Set Plan" docked. Set Plan queues
 * the plan on the phone at once (no network); the flow then shows a toast and goes back to the form ([onSaved]).
 */
@Composable
fun SelectOutletsScreen(state: SetPlanState, onBack: () -> Unit, onSaved: () -> Unit, modifier: Modifier = Modifier, nav: TsoNav = backOnly(onBack)) {
    val ui by state.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val bn = LocalAppLanguage.current == AppLanguage.BN
    LaunchedEffect(state) { if (state.ui.value.outletsLoad == PickLoad.IDLE) state.showOutlets() }
    LaunchedEffect(ui.result) { if (ui.result is SetPlanResult.Saved) onSaved() }
    TsoFrame(
        stringResource(R.string.tso_mc_select_outlets), nav, homeButton = true, modifier = modifier, onBack = onBack,
        bottomBar = {
            TsoGradientButton(
                stringResource(R.string.tso_mc_set_plan), { scope.launch { state.submit() } }, Modifier.testTag(MyCallTags.SAVE), style = TsoButtonStyle.Lime,
                enabled = ui.outletsLoad == PickLoad.READY && ui.outlets.isNotEmpty() && ui.result !is SetPlanResult.Saved,
            )
        },
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            if (ui.result is SetPlanResult.Failed) TsoBanner(stringResource(R.string.tso_mc_save_failed), kind = TsoBannerKind.Error)
            (ui.result as? SetPlanResult.Invalid)?.errors?.forEach { e ->
                TsoBanner(stringResource(when (e) {
                    PlanError.NO_ROUTE -> R.string.tso_mc_err_route; PlanError.NO_OUTLETS -> R.string.tso_mc_err_outlets; PlanError.DATE_FORMAT -> R.string.tso_leave_err_date_format
                    PlanError.DATE_PAST -> R.string.tso_mc_err_date_past; PlanError.DATE_FAR -> R.string.tso_leave_err_date_far }), Modifier.testTag(MyCallTags.ERRORS), kind = TsoBannerKind.Error)
            }
            when (ui.outletsLoad) {
                PickLoad.IDLE, PickLoad.LOADING -> TsoLoading(Modifier.weight(1f))
                PickLoad.UNAVAILABLE -> TsoBanner(stringResource(R.string.tso_mc_unavailable), Modifier.testTag(MyCallTags.UNAVAILABLE), kind = TsoBannerKind.Offline)
                PickLoad.READY -> if (ui.outlets.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.tso_mc_no_outlets_found), Modifier.testTag(MyCallTags.EMPTY), style = MaterialTheme.typography.bodyLarge, color = LocalAronLook.current.ink)
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(localizedDigits(stringResource(R.string.tso_mc_selected_count, localizedNumber(ui.selected.size.toLong()), localizedNumber(ui.outlets.size.toLong()))), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = LocalAronLook.current.inkMuted)
                        TextButton({ state.selectAll() }, Modifier.heightIn(min = 48.dp).testTag(MyCallTags.SELECT_ALL), colors = ButtonDefaults.textButtonColors(contentColor = LocalAronLook.current.purple)) {
                            Text(stringResource(R.string.tso_mc_select_all))
                        }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                        items(ui.outlets, key = { it.id }) { o -> OutletPickCard(o, bn, o.id in ui.selected, { state.toggle(o.id) }, Modifier.testTag(MyCallTags.outlet(o.id))) }
                    }
                }
            }
        }
    }
}

/**
 * My Visit Plan (screenshot 34, D-TSO-MAN-35): Select Date (today), Select Zone, Select Route and "Show Outlet", which
 * opens Visit Plan Outlets ([onShow]). Home button (s8 row 34).
 */
@Composable
fun VisitPlanScreen(state: VisitPlanState, onShow: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier, nav: TsoNav = backOnly(onBack)) {
    val ui by state.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) { state.loadRoutes() }
    TsoFrame(stringResource(R.string.tso_mc_visit_plan), nav, homeButton = true, modifier = modifier, onBack = onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.L)) {
            McDateField(ui.date, { state.setDate(it) }, stringResource(R.string.tso_mc_select_date), Modifier.testTag(MyCallTags.DATE), min = null, max = state.maxIso())
            ZoneRoutePickers(ui.routes, ui.routesLoad, ui.zoneId, ui.routeId, ui.missingRoute, state::pickZone, state::pickRoute)
            TsoGradientButton(
                stringResource(R.string.tso_mc_show_outlets), { if (state.ui.value.routeId == null) scope.launch { state.show() } else onShow() },
                Modifier.padding(top = AronTokens.Space.L).testTag(MyCallTags.SHOW), style = TsoButtonStyle.Lime,
            )
        }
    }
}

/** "Label: value" with the label bold, as the outlet and task cards print it (screenshots 36 and 38). */
@Composable
private fun LabelLine(label: String, value: String, strong: Boolean = false) {
    val look = LocalAronLook.current
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal, color = if (strong) look.ink else look.inkMuted)) { append(label); append(" ") }
            withStyle(SpanStyle(color = look.inkMuted)) { append(value) }
        },
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** The 5-line outlet card of screenshot 36: name, Code, Owner, Contact, Channel and Cluster, a chevron when it opens. */
@Composable
private fun PlannedOutletCard(o: PlanOutlet, bangla: Boolean, waitingTask: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    val none = stringResource(R.string.tso_mc_none)
    TsoCard(modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(o.title(bangla), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = look.ink)
                Text(stringResource(R.string.tso_mc_code_line, o.code), style = MaterialTheme.typography.bodyMedium, color = look.inkMuted)
                Text(stringResource(R.string.tso_mc_owner_line, o.owner.ifBlank { none }), style = MaterialTheme.typography.bodyMedium, color = look.inkMuted)
                Text(localizedDigits(stringResource(R.string.tso_mc_contact_line, o.phone ?: none)), style = MaterialTheme.typography.bodyMedium, color = look.inkMuted)
                Text(o.channel?.let { stringResource(R.string.tso_mc_channel_line, it) } ?: stringResource(R.string.tso_mc_channel_none), style = MaterialTheme.typography.bodyMedium, color = look.inkMuted)
                Text(stringResource(R.string.tso_mc_cluster_line, o.cluster ?: none), style = MaterialTheme.typography.bodyMedium, color = look.inkMuted)
                if (waitingTask) TsoStatusPill(stringResource(R.string.tso_mc_task_waiting), Modifier.padding(top = 6.dp).testTag(MyCallTags.waiting(o.id)), tone = TsoPillTone.Warning)
            }
            if (onClick != null) Icon(AronIcons.ChevronRight, contentDescription = null, tint = look.inkMuted)
        }
    }
}

/**
 * Visit Plan Outlets (screenshots 35 and 36, D-TSO-MAN-35): the Pending and Completed tabs without counts, the 5-line
 * outlet cards, "No Outlets Found" when the tab is empty (or there is no plan). A pending outlet opens the Visit Query; a
 * completed one whose query said "delegate" opens Assign Task until it is assigned. An outlet completed on this phone
 * moves to Completed at once (offline).
 */
@Composable
fun VisitPlanOutletsScreen(
    state: VisitPlanState, onOpenOutlet: (planUuid: String, outlet: PlanOutlet, route: PlanRoute) -> Unit,
    onOpenTask: (planUuid: String, outlet: PlanOutlet, route: PlanRoute) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier, nav: TsoNav = backOnly(onBack),
) {
    val ui by state.ui.collectAsStateWithLifecycle()
    val bn = LocalAppLanguage.current == AppLanguage.BN
    TsoFrame(stringResource(R.string.tso_mc_visit_plan_outlets), nav, homeButton = true, modifier = modifier, onBack = onBack) {
        Column(Modifier.fillMaxSize().padding(horizontal = AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            TsoSegmentedTabs(listOf(stringResource(R.string.tso_mc_tab_pending), stringResource(R.string.tso_mc_tab_completed)), ui.tab, { state.tab(it) })
            when (ui.planLoad) {
                PickLoad.IDLE, PickLoad.LOADING -> TsoLoading(Modifier.weight(1f))
                PickLoad.UNAVAILABLE -> TsoBanner(stringResource(R.string.tso_mc_unavailable), Modifier.testTag(MyCallTags.UNAVAILABLE), kind = TsoBannerKind.Offline)
                PickLoad.READY -> {
                    val ids = if (ui.tab == 0) ui.pending else ui.completed
                    val route = ui.routes.firstOrNull { it.id == ui.routeId }
                    val plan = ui.plan
                    if (ids.isEmpty() || plan == null || route == null) {
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.tso_mc_no_outlets_found), Modifier.testTag(MyCallTags.EMPTY), style = MaterialTheme.typography.bodyLarge, color = LocalAronLook.current.ink, textAlign = TextAlign.Center)
                        }
                    } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                        items(ids, key = { it }) { id ->
                            val o = ui.outlets[id] ?: PlanOutlet(id, id.toString(), id.toString(), null, "", null, null)
                            val needsTask = ui.tab == 1 && id in ui.taskPending
                            val open: (() -> Unit)? = when {
                                ui.tab == 0 -> ({ onOpenOutlet(plan.planUuid, o, route) })
                                needsTask -> ({ onOpenTask(plan.planUuid, o, route) })
                                else -> null
                            }
                            PlannedOutletCard(o, bn, needsTask, open, Modifier.testTag(MyCallTags.outlet(id)))
                        }
                    }
                }
            }
        }
    }
}

/** One radio choice with its word (a 48 dp row; the whole row is the target). */
@Composable
private fun RadioChoice(text: String, selected: Boolean, tag: String, onPick: () -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Row(
        modifier.heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onPick).testTag(tag).padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = look.ink, unselectedColor = look.inkMuted))
        Text(text, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge, color = look.ink)
    }
}

@Composable
private fun YesNo(value: Boolean?, onPick: (Boolean) -> Unit, yesTag: String, noTag: String) {
    Row(Modifier.fillMaxWidth()) {
        RadioChoice(stringResource(R.string.tso_mc_yes), value == true, yesTag, { onPick(true) }, Modifier.weight(1f))
        RadioChoice(stringResource(R.string.tso_mc_no), value == false, noTag, { onPick(false) }, Modifier.weight(1f))
    }
}

/**
 * Visit Query (screenshot 37, D-TSO-MAN-35): each question of the seeded retailer questionnaire in bold with its answer
 * field ("Enter your answer", at most 1000 characters; answers are optional, D-199), "Do you want to delegate the task?"
 * with Yes / No (No by default), and the lime Submit docked. No GPS, no photo. Submit queues the assessment on the phone;
 * the flow then opens Assign Task (Yes) or returns to the outlets ([onDone]).
 */
@Composable
fun VisitQueryScreen(state: VisitQueryState, outletTitle: String, onDone: (delegate: Boolean) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier, nav: TsoNav = backOnly(onBack)) {
    val ui by state.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val bn = LocalAppLanguage.current == AppLanguage.BN
    val look = LocalAronLook.current
    val submitted = ui.result as? QueryResult.Submitted
    LaunchedEffect(submitted) { submitted?.let { onDone(it.delegate) } }
    TsoFrame(
        stringResource(R.string.tso_mc_visit_query), nav, homeButton = true, modifier = modifier, onBack = onBack,
        bottomBar = {
            TsoGradientButton(stringResource(R.string.tso_mc_submit), { scope.launch { state.submit() } }, Modifier.testTag(MyCallTags.SUBMIT), style = TsoButtonStyle.Lime, enabled = submitted == null)
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            state.criteria.forEach { c ->
                if (c.text) {
                    TsoGlowField(
                        ui.texts[c.id].orEmpty(), { state.text(c.id, it) }, Modifier.testTag(MyCallTags.text(c.id)),
                        label = c.label(bn), placeholder = stringResource(R.string.tso_mc_enter_answer), singleLine = false, minLines = 1,
                    )
                } else {
                    Text(c.label(bn), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = look.ink)
                    YesNo(ui.answers[c.id], { state.answer(c.id, it) }, MyCallTags.yes(c.id), MyCallTags.no(c.id))
                }
            }
            Text(stringResource(R.string.tso_mc_delegate), Modifier.padding(top = AronTokens.Space.M), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = look.ink)
            YesNo(ui.delegate, { state.delegate(it) }, MyCallTags.DELEGATE_YES, MyCallTags.DELEGATE_NO)
            if (ui.result is QueryResult.Failed) TsoBanner(stringResource(R.string.tso_mc_save_failed), kind = TsoBannerKind.Error)
        }
    }
}

/**
 * Assign Task (screenshot 38, D-TSO-MAN-35): the outlet card (Outlet Name, Cluster, Route), the task type dropdown
 * (active `task_type` items, the label shown, never the code), the due date (today, up to the configured days ahead), the
 * comment, and the lime Assign Task docked. The task goes to the SR of the route (never a free pick); a route that shows
 * "SR Not Set" blocks it with the message. When [onAssigned] is given it runs once the task is queued (the flow returns to
 * the outlets with a toast); otherwise the button turns into Done ([onDone]).
 */
@Composable
fun AssignTaskScreen(
    state: AssignTaskState, onDone: () -> Unit, modifier: Modifier = Modifier, nav: TsoNav = backOnly(onDone), onBack: () -> Unit = onDone,
    outlet: PlanOutlet? = null, onAssigned: (() -> Unit)? = null,
) {
    val ui by state.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val bn = LocalAppLanguage.current == AppLanguage.BN
    val assigned = ui.result is TaskResult.Assigned
    LaunchedEffect(assigned) { if (assigned) onAssigned?.invoke() }
    TsoFrame(
        stringResource(R.string.tso_mc_assign_task), nav, homeButton = true, modifier = modifier, onBack = onBack,
        bottomBar = {
            if (assigned && onAssigned == null) TsoGradientButton(stringResource(R.string.tso_leave_done), onDone, Modifier.testTag(MyCallTags.SUBMIT), style = TsoButtonStyle.Lime)
            else TsoGradientButton(stringResource(R.string.tso_mc_assign_task), { scope.launch { state.submit() } }, Modifier.testTag(MyCallTags.SUBMIT), style = TsoButtonStyle.Lime, enabled = ui.result !is TaskResult.NoSr && !assigned)
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            if (state.typeList.isEmpty()) TsoBanner(stringResource(R.string.tso_mc_no_task_types), kind = TsoBannerKind.Warning)
            if (ui.result is TaskResult.NoSr) TsoBanner(stringResource(R.string.tso_mc_no_sr), Modifier.testTag(MyCallTags.NO_SR), kind = TsoBannerKind.Warning)
            if (assigned) TsoBanner(stringResource(R.string.tso_mc_assigned, state.srName ?: ""), Modifier.testTag(MyCallTags.ASSIGNED), kind = TsoBannerKind.Success)
            if (ui.result is TaskResult.Failed) TsoBanner(stringResource(R.string.tso_mc_save_failed), kind = TsoBannerKind.Error)
            if (outlet != null || state.routeName != null) TsoCard(Modifier.fillMaxWidth().testTag(MyCallTags.INFO), glow = true) {
                Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    outlet?.let { LabelLine(stringResource(R.string.tso_mc_info_outlet), it.title(bn), strong = true) }
                    outlet?.cluster?.let { LabelLine(stringResource(R.string.tso_mc_info_cluster), it, strong = true) }
                    state.routeName?.let { LabelLine(stringResource(R.string.tso_mc_info_route), it, strong = true) }
                }
            }
            val types = state.typeList
            TsoDropdownField(
                types, types.firstOrNull { it.code == ui.typeCode }, { state.pickType(it.code) }, { it.label(bn) }, Modifier.testTag(MyCallTags.TYPE),
                placeholder = stringResource(R.string.tso_mc_select_task_type), enabled = !assigned && ui.result !is TaskResult.NoSr,
            )
            McDateField(ui.date, { state.setDate(it) }, null, Modifier.testTag(MyCallTags.DATE), min = state.todayIso(), max = state.maxIso())
            TsoGlowField(
                ui.comment, { state.setComment(it.take(MAX_TASK_COMMENT)) }, Modifier.testTag(MyCallTags.COMMENT),
                placeholder = stringResource(R.string.tso_mc_comment), singleLine = false, minLines = 5, enabled = !assigned,
            )
            state.srName?.let { Text(stringResource(R.string.tso_mc_assignee, it), style = MaterialTheme.typography.bodySmall, color = LocalAronLook.current.inkMuted) }
            (ui.result as? TaskResult.Invalid)?.errors?.forEach { e ->
                TsoBanner(stringResource(when (e) {
                    TaskError.NO_TYPE -> R.string.tso_mc_err_type; TaskError.DATE_FORMAT -> R.string.tso_leave_err_date_format; TaskError.DATE_PAST -> R.string.tso_mc_err_date_past
                    TaskError.DATE_FAR -> R.string.tso_leave_err_date_far; TaskError.COMMENT_LONG -> R.string.tso_mc_err_comment }), Modifier.testTag(MyCallTags.ERRORS), kind = TsoBannerKind.Error)
            }
        }
    }
}

/** Shown instead of a silent screen when the questionnaire (or the task types) is not on the phone yet. */
@Composable
fun MyCallUnavailable(messageRes: Int, onBack: () -> Unit, modifier: Modifier = Modifier, nav: TsoNav = backOnly(onBack)) {
    TsoFrame(stringResource(R.string.tso_mc_visit_query), nav, homeButton = true, modifier = modifier, onBack = onBack) {
        Column(Modifier.fillMaxSize().padding(AronTokens.Space.Screen)) { TsoBanner(stringResource(messageRes), Modifier.testTag(MyCallTags.UNAVAILABLE), kind = TsoBannerKind.Warning) }
    }
}
