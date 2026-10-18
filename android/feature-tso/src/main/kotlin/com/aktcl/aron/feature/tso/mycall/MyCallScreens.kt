package com.aktcl.aron.feature.tso.mycall

import androidx.compose.foundation.layout.Arrangement
import com.aktcl.aron.feature.tso.zoneName
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.feature.tso.R
import kotlinx.coroutines.launch
import com.aktcl.aron.core.ui.aron.AronCard
import com.aktcl.aron.core.ui.aron.AronListRow
import com.aktcl.aron.feature.tso.TsoButton
import com.aktcl.aron.core.ui.aron.AronTabs
import com.aktcl.aron.feature.tso.TsoAction
import com.aktcl.aron.feature.tso.TsoBanner
import com.aktcl.aron.feature.tso.TsoBannerKind
import com.aktcl.aron.feature.tso.TsoBottomBar
import com.aktcl.aron.feature.tso.TsoEmpty
import com.aktcl.aron.feature.tso.TsoError
import com.aktcl.aron.feature.tso.TsoFrame
import com.aktcl.aron.feature.tso.TsoSectionTitle
import com.aktcl.aron.feature.tso.TsoStepper

object MyCallTags {
    const val SET_PLAN = "tso_mc_set_plan"
    const val VISIT_PLAN = "tso_mc_visit_plan"
    const val DATE = "tso_mc_date"
    const val SHOW = "tso_mc_show"
    const val SAVE = "tso_mc_save"
    const val UNAVAILABLE = "tso_mc_unavailable"
    const val SAVED = "tso_mc_saved"
    const val ERRORS = "tso_mc_errors"
    const val SUBMIT = "tso_mc_submit"
    const val NO_SR = "tso_mc_no_sr"
    const val ASSIGNED = "tso_mc_assigned"
    const val COMMENT = "tso_mc_comment"
    fun zone(id: Long) = "tso_mc_zone_$id"
    fun route(id: Long) = "tso_mc_route_$id"
    fun outlet(id: Long) = "tso_mc_outlet_$id"
    fun yes(criterionId: Long) = "tso_mc_yes_$criterionId"
    fun no(criterionId: Long) = "tso_mc_no_$criterionId"
    fun text(criterionId: Long) = "tso_mc_text_$criterionId"
    fun type(code: String) = "tso_mc_type_$code"
    const val DELEGATE_YES = "tso_mc_delegate_yes"
    const val DELEGATE_NO = "tso_mc_delegate_no"
}

@Composable
private fun zoneLabel(id: Long) = zoneName(id)

@Composable
private fun Pickers(
    routes: List<PlanRoute>, load: PickLoad, zoneId: Long?, routeId: Long?, onZone: (Long) -> Unit, onRoute: (Long) -> Unit,
) {
    if (load == PickLoad.UNAVAILABLE) TsoBanner(stringResource(R.string.tso_mc_unavailable), Modifier.testTag(MyCallTags.UNAVAILABLE), kind = TsoBannerKind.Offline)
    TsoSectionTitle(stringResource(R.string.tso_mc_zone_label))
    zonesOf(routes).forEach { z -> AronListRow(zoneLabel(z), Modifier.testTag(MyCallTags.zone(z)), onClick = { onZone(z) }, trailing = if (z == zoneId) "✓" else null) }
    if (zoneId != null) {
        TsoSectionTitle(stringResource(R.string.tso_mc_route_label))
        routes.filter { it.zoneId == zoneId }.forEach { r ->
            AronListRow(r.name, Modifier.testTag(MyCallTags.route(r.id)), subtitle = r.code + (r.label?.let { "  ·  $it" } ?: ""), onClick = { onRoute(r.id) }, trailing = if (r.id == routeId) "✓" else null)
        }
    }
}

@Composable
private fun OutletCard(o: PlanOutlet, bangla: Boolean, modifier: Modifier = Modifier, trailing: String? = null, onClick: (() -> Unit)? = null) {
    AronListRow(
        o.title(bangla), modifier, subtitle = listOfNotNull(o.code, o.owner, o.phone, o.cluster).joinToString("  ·  "), trailing = trailing, onClick = onClick,
    )
}

@Composable
fun MyCallHub(onSetPlan: () -> Unit, onVisitPlan: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    TsoFrame(stringResource(R.string.tso_drawer_my_call), onBack, modifier, tile = com.aktcl.aron.feature.home.RoleHomes.TsoTile.MY_CALL) {
        Column(Modifier.fillMaxSize().padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            com.aktcl.aron.feature.tso.TsoMenuGrid(
                listOf(
                    com.aktcl.aron.feature.tso.TsoMenuTile(stringResource(R.string.tso_mc_visit_plan), "visit_plan", MyCallTags.VISIT_PLAN, onVisitPlan),
                    com.aktcl.aron.feature.tso.TsoMenuTile(stringResource(R.string.tso_mc_set_plan), "set_plan", MyCallTags.SET_PLAN, onSetPlan),
                ),
            )
        }
    }
}

/** F-TSO-013 Set Plan. */
@Composable
fun SetPlanScreen(state: SetPlanState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val ui by state.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val bn = LocalAppLanguage.current == AppLanguage.BN
    LaunchedEffect(state) { state.loadRoutes() }
    val saved = ui.result is SetPlanResult.Saved
    TsoFrame(
        stringResource(R.string.tso_mc_set_plan), onBack, modifier, tile = "set_plan",
        bottomBar = { TsoBottomBar(primary = if (saved) TsoAction(stringResource(R.string.tso_leave_done), onBack) else TsoAction(stringResource(R.string.tso_mc_set_plan), { scope.launch { state.submit() } }), modifier = Modifier.testTag(MyCallTags.SAVE)) },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            (ui.result as? SetPlanResult.Saved)?.let { TsoBanner(stringResource(R.string.tso_mc_saved, it.newOutlets), Modifier.testTag(MyCallTags.SAVED), kind = TsoBannerKind.Success) }
            if (ui.result is SetPlanResult.Failed) TsoBanner(stringResource(R.string.tso_mc_save_failed), kind = TsoBannerKind.Error)
            TsoSectionTitle(stringResource(R.string.tso_mc_date))
            com.aktcl.aron.feature.tso.TsoDateField(ui.date, { state.setDate(it) }, Modifier.testTag(MyCallTags.DATE), min = state.todayIso(), max = state.maxIso())
            Pickers(ui.routes, ui.routesLoad, ui.zoneId, ui.routeId, state::pickZone, state::pickRoute)
            if (ui.routeId != null) TsoButton(stringResource(R.string.tso_mc_show_outlets), { scope.launch { state.showOutlets() } }, Modifier.testTag(MyCallTags.SHOW))
            if (ui.outletsLoad == PickLoad.UNAVAILABLE) TsoBanner(stringResource(R.string.tso_mc_unavailable), kind = TsoBannerKind.Offline)
            if (ui.outletsLoad == PickLoad.READY) {
                TsoSectionTitle(stringResource(R.string.tso_mc_outlets_selected, ui.selected.size))
                if (ui.outlets.isEmpty()) Text(stringResource(R.string.tso_mc_no_outlets), style = MaterialTheme.typography.bodyMedium)
                else TsoButton(stringResource(R.string.tso_mc_select_all), { state.selectAll() })
                ui.outlets.forEach { o -> OutletCard(o, bn, Modifier.testTag(MyCallTags.outlet(o.id)), trailing = if (o.id in ui.selected) "✓" else null, onClick = { state.toggle(o.id) }) }
            }
            (ui.result as? SetPlanResult.Invalid)?.errors?.forEach { e ->
                TsoBanner(stringResource(when (e) {
                    PlanError.NO_ROUTE -> R.string.tso_mc_err_route; PlanError.NO_OUTLETS -> R.string.tso_mc_err_outlets; PlanError.DATE_FORMAT -> R.string.tso_leave_err_date_format
                    PlanError.DATE_PAST -> R.string.tso_mc_err_date_past; PlanError.DATE_FAR -> R.string.tso_leave_err_date_far }), Modifier.testTag(MyCallTags.ERRORS), kind = TsoBannerKind.Error)
            }
        }
    }
}

/** F-TSO-014 My Visit Plan. */
@Composable
fun VisitPlanScreen(state: VisitPlanState, onOpenOutlet: (planUuid: String, outlet: PlanOutlet, route: PlanRoute) -> Unit, onOpenTask: (planUuid: String, outlet: PlanOutlet, route: PlanRoute) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val ui by state.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val bn = LocalAppLanguage.current == AppLanguage.BN
    LaunchedEffect(state) { state.loadRoutes(); if (state.ui.value.routeId != null) state.refresh() }
    TsoFrame(stringResource(R.string.tso_mc_visit_plan), onBack, modifier, tile = "visit_plan") {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            TsoSectionTitle(stringResource(R.string.tso_mc_date))
            com.aktcl.aron.feature.tso.TsoDateField(ui.date, { state.setDate(it) }, Modifier.testTag(MyCallTags.DATE), min = null, max = state.maxIso())
            Pickers(ui.routes, ui.routesLoad, ui.zoneId, ui.routeId, state::pickZone, state::pickRoute)
            if (ui.routeId != null) TsoButton(stringResource(R.string.tso_mc_show_plan), { scope.launch { state.show() } }, Modifier.testTag(MyCallTags.SHOW))
            when (ui.planLoad) {
                PickLoad.UNAVAILABLE -> TsoBanner(stringResource(R.string.tso_mc_unavailable), kind = TsoBannerKind.Offline)
                PickLoad.READY -> {
                    AronTabs(listOf(stringResource(R.string.tso_mc_pending, ui.pending.size), stringResource(R.string.tso_mc_completed, ui.completed.size)), ui.tab, { state.tab(it) })
                    val ids = if (ui.tab == 0) ui.pending else ui.completed
                    if (ids.isEmpty()) Text(stringResource(if (ui.plan == null) R.string.tso_mc_no_plan else R.string.tso_mc_nothing_here), style = MaterialTheme.typography.bodyMedium)
                    val route = ui.routes.firstOrNull { it.id == ui.routeId }
                    ids.forEach { id ->
                        val o = ui.outlets[id] ?: PlanOutlet(id, id.toString(), id.toString(), null, "", null, null)
                        val canQuery = ui.tab == 0 && ui.plan != null && route != null
                        val needsTask = ui.tab == 1 && id in ui.taskPending && ui.plan != null && route != null
                        OutletCard(
                            o, bn, Modifier.testTag(MyCallTags.outlet(id)), trailing = if (needsTask) stringResource(R.string.tso_mc_task_waiting) else null,
                            onClick = if (canQuery) ({ onOpenOutlet(ui.plan!!.planUuid, o, route!!) }) else if (needsTask) ({ onOpenTask(ui.plan!!.planUuid, o, route!!) }) else null,
                        )
                    }
                }
                else -> Unit
            }
        }
    }
}

/** F-TSO-015 Visit Query: no GPS, no photo. */
@Composable
fun VisitQueryScreen(state: VisitQueryState, outletTitle: String, onDone: (delegate: Boolean) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val ui by state.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val bn = LocalAppLanguage.current == AppLanguage.BN
    val submitted = ui.result as? QueryResult.Submitted
    LaunchedEffect(submitted) { submitted?.let { onDone(it.delegate) } }
    TsoFrame(
        stringResource(R.string.tso_mc_visit_query), onBack, modifier, tile = "query",
        bottomBar = { TsoBottomBar(primary = TsoAction(stringResource(R.string.tso_mc_submit), { scope.launch { state.submit() } }, enabled = submitted == null), modifier = Modifier.testTag(MyCallTags.SUBMIT)) },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            state.criteria.forEach { c ->
                AronCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                        Text(c.label(bn), style = MaterialTheme.typography.titleSmall)
                        if (c.text) com.aktcl.aron.feature.tso.TsoTextField(ui.texts[c.id].orEmpty(), { state.text(c.id, it) }, Modifier.fillMaxWidth().testTag(MyCallTags.text(c.id)), minLines = 2, label = { Text(stringResource(R.string.tso_mc_enter_answer)) }, name = c.label(bn))
                        else YesNo(ui.answers[c.id], { state.answer(c.id, it) }, MyCallTags.yes(c.id), MyCallTags.no(c.id))
                    }
                }
            }
            AronCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                    Text(stringResource(R.string.tso_mc_delegate), style = MaterialTheme.typography.titleSmall)
                    YesNo(ui.delegate, { state.delegate(it) }, MyCallTags.DELEGATE_YES, MyCallTags.DELEGATE_NO)
                }
            }
            if (ui.result is QueryResult.Failed) TsoBanner(stringResource(R.string.tso_mc_save_failed), kind = TsoBannerKind.Error)
        }
    }
}

@Composable
private fun YesNo(value: Boolean?, onPick: (Boolean) -> Unit, yesTag: String, noTag: String) {
    // two equal, full-width targets: easy to hit with a thumb outdoors; the chosen one also says so with a tick, never by colour alone
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
        com.aktcl.aron.core.ui.AronYesButton(stringResource(R.string.tso_mc_yes) + if (value == true) " ✓" else "", { onPick(true) }, Modifier.weight(1f).testTag(yesTag))
        com.aktcl.aron.core.ui.AronNoButton(stringResource(R.string.tso_mc_no) + if (value == false) " ✓" else "", { onPick(false) }, Modifier.weight(1f).testTag(noTag))
    }
}

/** F-TSO-016 Assign Task. */
@Composable
fun AssignTaskScreen(state: AssignTaskState, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val ui by state.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val bn = LocalAppLanguage.current == AppLanguage.BN
    val assigned = ui.result is TaskResult.Assigned
    TsoFrame(stringResource(R.string.tso_mc_assign_task), onDone, modifier, tile = "task", bottomBar = { TsoBottomBar(primary = if (assigned) TsoAction(stringResource(R.string.tso_leave_done), onDone) else TsoAction(stringResource(R.string.tso_mc_assign), { scope.launch { state.submit() } }, enabled = ui.result !is TaskResult.NoSr), modifier = Modifier.testTag(MyCallTags.SUBMIT)) },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            if (state.typeList.isEmpty()) TsoBanner(stringResource(R.string.tso_mc_no_task_types), kind = TsoBannerKind.Warning)
            if (ui.result is TaskResult.NoSr) TsoBanner(stringResource(R.string.tso_mc_no_sr), Modifier.testTag(MyCallTags.NO_SR), kind = TsoBannerKind.Warning)
            if (assigned) TsoBanner(stringResource(R.string.tso_mc_assigned, state.srName ?: ""), Modifier.testTag(MyCallTags.ASSIGNED), kind = TsoBannerKind.Success)
            if (ui.result is TaskResult.Failed) TsoBanner(stringResource(R.string.tso_mc_save_failed), kind = TsoBannerKind.Error)
            state.srName?.let { Text(stringResource(R.string.tso_mc_assignee, it), style = MaterialTheme.typography.bodyMedium) }
            TsoSectionTitle(stringResource(R.string.tso_mc_task_type))
            state.typeList.forEach { t -> AronListRow(t.label(bn), Modifier.testTag(MyCallTags.type(t.code)), onClick = { state.pickType(t.code) }, trailing = if (t.code == ui.typeCode) "✓" else null) }
            TsoSectionTitle(stringResource(R.string.tso_mc_date))
            com.aktcl.aron.feature.tso.TsoDateField(ui.date, { state.setDate(it) }, Modifier.testTag(MyCallTags.DATE), min = state.todayIso(), max = state.maxIso())
            TsoSectionTitle(stringResource(R.string.tso_mc_comment))
            com.aktcl.aron.feature.tso.TsoTextField(ui.comment, { state.setComment(it.take(MAX_TASK_COMMENT * 2)) }, Modifier.fillMaxWidth().testTag(MyCallTags.COMMENT), minLines = 3, name = stringResource(R.string.tso_mc_comment))
            (ui.result as? TaskResult.Invalid)?.errors?.forEach { e ->
                TsoBanner(stringResource(when (e) {
                    TaskError.NO_TYPE -> R.string.tso_mc_err_type; TaskError.DATE_FORMAT -> R.string.tso_leave_err_date_format; TaskError.DATE_PAST -> R.string.tso_mc_err_date_past
                    TaskError.DATE_FAR -> R.string.tso_leave_err_date_far; TaskError.COMMENT_LONG -> R.string.tso_mc_err_comment }), Modifier.testTag(MyCallTags.ERRORS), kind = TsoBannerKind.Error)
            }
        }
    }
}

/** Shown instead of a silent hub when the questionnaire (or the task types) is not on the phone yet. */
@Composable
fun MyCallUnavailable(messageRes: Int, onBack: () -> Unit, modifier: Modifier = Modifier) {
    TsoFrame(stringResource(R.string.tso_drawer_my_call), onBack, modifier, tile = com.aktcl.aron.feature.home.RoleHomes.TsoTile.MY_CALL) {
        Column(Modifier.fillMaxSize().padding(AronTokens.Space.Screen)) { TsoBanner(stringResource(messageRes), Modifier.testTag(MyCallTags.UNAVAILABLE), kind = TsoBannerKind.Warning) }
    }
}
