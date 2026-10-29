package com.aktcl.aron.feature.tso.mycall

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.feature.tso.R
import com.aktcl.aron.feature.tso.TsoNav
import com.aktcl.aron.feature.tso.TsoScreen
import com.aktcl.aron.feature.tso.rememberRetained
import kotlinx.coroutines.launch

/**
 * Set Plan as its own drawer destination (TsoScreen.SET_PLAN, D-TSO-MAN-35): the form (screenshot 31) and Select Outlets
 * (33). After "Set Plan" the plan is queued on the phone, a toast says so and the form comes back with its picks. The
 * system back and the on-screen back go to the same place (CR-4): Select Outlets to the form, the form out through [nav].
 * The state survives recreation and is per user.
 */
@Composable
fun SetPlanEntry(deps: MyCallDeps, userKey: String, today: () -> String, nav: TsoNav) {
    var step by rememberSaveable { mutableStateOf(STEP_FORM) }
    val st = rememberRetained("tso-$userKey-mc-set") { SetPlanState(deps.source, deps.planQueue, today) }
    val context = LocalContext.current
    val savedToast = stringResource(R.string.tso_mc_plan_saved_toast)
    val toForm = { st.backToForm(); step = STEP_FORM }
    BackHandler(enabled = step == STEP_OUTLETS) { toForm() }
    if (step == STEP_OUTLETS) {
        SelectOutletsScreen(st, onBack = toForm, onSaved = { Toast.makeText(context, savedToast, Toast.LENGTH_SHORT).show(); toForm() }, nav = nav)
    } else {
        SetPlanScreen(st, onBack = { nav.back() }, nav = nav, onShowOutlets = { st.backToForm(); step = STEP_OUTLETS })
    }
}

/**
 * My Visit Plan as its own drawer destination (TsoScreen.MY_VISIT_PLAN, D-TSO-MAN-35): the form (34), Visit Plan Outlets
 * (35, 36), Visit Query (37) and Assign Task (38). Back paths, the same for the system back and the on-screen back (CR-4):
 * Visit Query and Assign Task to the outlets (read again, so a completed outlet moves to Completed), the outlets to the
 * form, the form out through [nav]. A query with "delegate: Yes" goes on to Assign Task; a task queued returns to the
 * outlets with a toast.
 */
@Composable
fun VisitPlanEntry(deps: MyCallDeps, userKey: String, today: () -> String, nav: TsoNav) {
    var step by rememberSaveable { mutableStateOf(STEP_FORM) }
    val st = rememberRetained("tso-$userKey-mc-plan") { VisitPlanState(deps.source, today) }
    val sel = rememberRetained("tso-$userKey-mc-sel") { McSelection() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val queryToast = stringResource(R.string.tso_mc_query_saved_toast)
    val taskToast = stringResource(R.string.tso_mc_task_saved_toast)
    val toOutlets = { step = STEP_OUTLETS; scope.launch { st.refresh() }; Unit }
    // a restored Visit Query or Assign Task whose outlet was not kept (process death) falls back to the outlets
    val shownStep = when {
        step == STEP_QUERY && (sel.outlet == null || sel.route == null) -> STEP_OUTLETS
        step == STEP_TASK && sel.outlet == null -> STEP_OUTLETS
        else -> step
    }
    LaunchedEffect(shownStep) { if (shownStep != step) toOutlets() }
    BackHandler(enabled = shownStep != STEP_FORM) { if (shownStep == STEP_OUTLETS) step = STEP_FORM else toOutlets() }
    when (shownStep) {
        STEP_OUTLETS -> VisitPlanOutletsScreen(
            st, nav = nav, onBack = { step = STEP_FORM },
            onOpenOutlet = { planUuid, outlet, route ->
                sel.planUuid = planUuid; sel.outlet = outlet; sel.route = route
                scope.launch { sel.rubric = deps.loadRubric(); step = STEP_QUERY }
            },
            onOpenTask = { planUuid, outlet, route ->
                sel.planUuid = planUuid; sel.outlet = outlet; sel.route = route
                scope.launch { sel.types = deps.loadTaskTypes(); step = STEP_TASK }
            },
        )
        STEP_QUERY -> {
            val outlet = checkNotNull(sel.outlet); val route = checkNotNull(sel.route); val rubric = sel.rubric
            when {
                rubric == null -> MyCallUnavailable(R.string.tso_mc_no_rubric, onBack = toOutlets, nav = nav)
                else -> {
                    val q = rememberRetained("tso-$userKey-mc-query-${sel.planUuid}-${outlet.id}") { VisitQueryState(rubric, sel.planUuid, outlet.id, route.id, deps.queryQueue) }
                    VisitQueryScreen(
                        q, outlet.title(com.aktcl.aron.core.ui.LocalAppLanguage.current == com.aktcl.aron.core.common.AppLanguage.BN), nav = nav, onBack = toOutlets,
                        onDone = { delegate ->
                            Toast.makeText(context, queryToast, Toast.LENGTH_SHORT).show()
                            if (delegate) scope.launch { sel.types = deps.loadTaskTypes(); step = STEP_TASK } else toOutlets()
                        },
                    )
                }
            }
        }
        STEP_TASK -> {
            val outlet = checkNotNull(sel.outlet)
            val days = st.ui.value.maxAheadDays
            val t = rememberRetained("tso-$userKey-mc-task-${sel.planUuid}-${outlet.id}") {
                AssignTaskState(sel.types, sel.route, outlet.id, deps.taskQueue, today, sel.planUuid, QueryIds.task(sel.planUuid, outlet.id), days)
            }
            AssignTaskScreen(
                t, onDone = toOutlets, nav = nav, onBack = toOutlets, outlet = outlet,
                onAssigned = { Toast.makeText(context, taskToast.format(t.srName ?: ""), Toast.LENGTH_SHORT).show(); toOutlets() },
            )
        }
        else -> VisitPlanScreen(st, onShow = { step = STEP_OUTLETS; scope.launch { st.show() } }, onBack = { nav.back() }, nav = nav)
    }
}

/**
 * The former tile Home's My Call (hub, Set Plan, My Visit Plan), kept for the legacy wiring and the shell tests that still
 * compose it; the app's AreasHost opens [SetPlanEntry] and [VisitPlanEntry] directly. Back from either returns to the hub.
 */
@Composable
fun MyCallFlow(deps: MyCallDeps, userKey: String, today: () -> String, onBack: () -> Unit) {
    var step by rememberSaveable { mutableStateOf("hub") }
    val hub = object : TsoNav {
        override fun open(screen: TsoScreen, arg: String?) = Unit
        override fun back() { step = "hub" }
        override fun home() { step = "hub"; onBack() }
    }
    BackHandler(enabled = step != "hub") { step = "hub" }
    when (step) {
        "set" -> SetPlanEntry(deps, userKey, today, hub)
        "plan" -> VisitPlanEntry(deps, userKey, today, hub)
        else -> MyCallHub({ step = "set" }, { step = "plan" }, onBack)
    }
}

private const val STEP_FORM = "form"
private const val STEP_OUTLETS = "outlets"
private const val STEP_QUERY = "query"
private const val STEP_TASK = "task"
