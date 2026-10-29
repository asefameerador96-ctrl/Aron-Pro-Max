package com.aktcl.aron.feature.tso.mycall

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.aktcl.aron.feature.tso.R
import com.aktcl.aron.feature.tso.RetainedCounter
import com.aktcl.aron.feature.tso.rememberRetained
import kotlinx.coroutines.launch

/**
 * The My Call sub-navigation (F-TSO-013..016): hub, Set Plan, My Visit Plan, then Visit Query and Assign Task for one
 * outlet. States survive recreation and are per user. Moved out of TsoApp by a1-stubs with the behaviour unchanged.
 */
@Composable
fun MyCallFlow(deps: MyCallDeps, userKey: String, today: () -> String, onBack: () -> Unit) {
    var step by rememberSaveable { mutableStateOf("hub") }
    val counter = rememberRetained("tso-$userKey-mc-epoch") { RetainedCounter() }
    val sel = rememberRetained("tso-$userKey-mc-sel") { McSelection() }
    val e = counter.n
    val scope = rememberCoroutineScope()
    BackHandler(enabled = step != "hub") { step = if (step == "task") "plan" else "hub"; if (step == "hub") counter.n++ }
    when (step) {
        "set" -> {
            val st = rememberRetained("tso-$userKey-mc-set-$e") { SetPlanState(deps.source, deps.planQueue, today, { java.time.LocalDate.parse(today()).plusDays(1).toString() }) }
            SetPlanScreen(st, onBack = { step = "hub"; counter.n++ })
        }
        "plan" -> {
            val st = rememberRetained("tso-$userKey-mc-plan-$e") { VisitPlanState(deps.source, today) }
            LaunchedEffect(Unit) { if (sel.outlet != null) st.refresh() }
            VisitPlanScreen(
                st, onBack = { step = "hub"; counter.n++ },
                onOpenOutlet = { planUuid, outlet, route ->
                    sel.planUuid = planUuid; sel.outlet = outlet; sel.route = route
                    scope.launch { sel.rubric = deps.loadRubric(); step = "query" }
                },
                onOpenTask = { planUuid, outlet, route ->
                    sel.planUuid = planUuid; sel.outlet = outlet; sel.route = route
                    scope.launch { sel.types = deps.loadTaskTypes(); step = "task" }
                },
            )
        }
        "query" -> {
            val outlet = sel.outlet; val route = sel.route; val rubric = sel.rubric
            if (outlet != null && route != null && rubric == null) {
                MyCallUnavailable(R.string.tso_mc_no_rubric, onBack = { step = "plan" })
            } else if (outlet == null || route == null || rubric == null) {
                MyCallHub({ step = "set" }, { step = "plan" }, onBack) // nothing selected after a restore: back to the hub
            } else {
                val st = rememberRetained("tso-$userKey-mc-query-${sel.planUuid}-${outlet.id}") { VisitQueryState(rubric, sel.planUuid, outlet.id, route.id, deps.queryQueue) }
                VisitQueryScreen(
                    st, outlet.name, onBack = { step = "plan" },
                    onDone = { delegate -> if (delegate) { scope.launch { sel.types = deps.loadTaskTypes(); step = "task" } } else step = "plan" },
                )
            }
        }
        "task" -> {
            val outlet = sel.outlet; val route = sel.route
            if (outlet == null) MyCallHub({ step = "set" }, { step = "plan" }, onBack) else {
                val st = rememberRetained("tso-$userKey-mc-task-${sel.planUuid}-${outlet.id}") { AssignTaskState(sel.types, route, outlet.id, deps.taskQueue, today, sel.planUuid, QueryIds.task(sel.planUuid, outlet.id)) }
                AssignTaskScreen(st, onDone = { step = "plan" })
            }
        }
        else -> MyCallHub({ step = "set" }, { step = "plan" }, onBack)
    }
}
