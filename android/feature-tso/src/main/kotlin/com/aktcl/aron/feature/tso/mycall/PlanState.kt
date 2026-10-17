package com.aktcl.aron.feature.tso.mycall

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads for My Call. Every call returns what is on the phone at once and refreshes online once; null means nothing yet and
 * no network (the screen then says so, it never shows a made-up list). Scope is the server's: the client sends no zone ids.
 */
interface PlanSource {
    suspend fun routes(): List<PlanRoute>?
    suspend fun outlets(routeId: Long): List<PlanOutlet>?

    /** Plans of one date (server rows with the local overlay: a plan saved on this phone shows at once, `sent` false). */
    suspend fun plans(date: String): List<VisitPlanItem>?

    /** Outlets completed on this phone (Visit Query submitted) whose completion the server list does not show yet. */
    suspend fun locallyDone(planUuid: String): Set<Long>

    /** Outlets whose Visit Query said "Delegate task? Yes" and whose task has not been assigned yet: Assign Task stays reachable for them. */
    suspend fun taskPending(planUuid: String): Set<Long>
}

/** Queues the plan record and one plan-outlet record per new outlet in one transaction; returns how many outlets were new. */
fun interface PlanQueue {
    suspend fun save(save: PlanSave): Int
}

sealed interface SetPlanResult {
    data object Editing : SetPlanResult
    data class Saved(val newOutlets: Int) : SetPlanResult
    data class Invalid(val errors: List<PlanError>) : SetPlanResult
    data object Failed : SetPlanResult
}

enum class PickLoad { IDLE, LOADING, READY, UNAVAILABLE }

/** F-TSO-013: date, zone, route, "Show Outlet", multi-select, "Set Plan". Saving twice for a date and route is a union. */
class SetPlanState(private val source: PlanSource, private val queue: PlanQueue, private val today: () -> String, private val tomorrow: () -> String = { today() }) {
    private val _s = MutableStateFlow(Ui(date = tomorrow()))
    val ui: StateFlow<Ui> = _s.asStateFlow()
    private var busy = false

    /** The date picker's bounds: today to [MAX_PLAN_AHEAD_DAYS] days ahead. */
    fun todayIso() = today()
    fun maxIso() = java.time.LocalDate.parse(today()).plusDays(MAX_PLAN_AHEAD_DAYS).toString()

    data class Ui(
        val date: String, val routes: List<PlanRoute> = emptyList(), val routesLoad: PickLoad = PickLoad.IDLE, val zoneId: Long? = null, val routeId: Long? = null,
        val outlets: List<PlanOutlet> = emptyList(), val outletsLoad: PickLoad = PickLoad.IDLE, val alreadyPlanned: Set<Long> = emptySet(),
        val selected: Set<Long> = emptySet(), val result: SetPlanResult = SetPlanResult.Editing,
    )

    suspend fun loadRoutes() {
        _s.value = _s.value.copy(routesLoad = PickLoad.LOADING)
        val r = runCatching { source.routes() }.getOrNull()
        _s.value = _s.value.copy(routes = r.orEmpty(), routesLoad = if (r == null) PickLoad.UNAVAILABLE else PickLoad.READY)
    }

    fun setDate(d: String) { _s.value = _s.value.copy(date = d.take(10), result = SetPlanResult.Editing, outlets = emptyList(), outletsLoad = PickLoad.IDLE, selected = emptySet()) }
    fun pickZone(z: Long) { _s.value = _s.value.copy(zoneId = z, routeId = null, outlets = emptyList(), outletsLoad = PickLoad.IDLE, selected = emptySet(), result = SetPlanResult.Editing) }
    fun pickRoute(r: Long) { _s.value = _s.value.copy(routeId = r, outlets = emptyList(), outletsLoad = PickLoad.IDLE, selected = emptySet(), result = SetPlanResult.Editing) }

    /** "Show Outlet": the route's outlets, with those already in a plan for this date marked (they stay selected: the save is a union). */
    suspend fun showOutlets() {
        val s = _s.value; val rid = s.routeId ?: return
        _s.value = s.copy(outletsLoad = PickLoad.LOADING)
        val outs = runCatching { source.outlets(rid) }.getOrNull()
        val planned = runCatching { source.plans(s.date) }.getOrNull().orEmpty().filter { it.routeId == rid }.flatMap { p -> p.outlets.map { it.outletId } }.toSet()
        _s.value = _s.value.copy(outlets = outs.orEmpty(), outletsLoad = if (outs == null) PickLoad.UNAVAILABLE else PickLoad.READY, alreadyPlanned = planned, selected = planned.filter { id -> outs.orEmpty().any { it.id == id } }.toSet())
    }

    fun toggle(outletId: Long) {
        val s = _s.value; if (s.result is SetPlanResult.Saved) return
        _s.value = s.copy(selected = if (outletId in s.selected) s.selected - outletId else s.selected + outletId, result = SetPlanResult.Editing)
    }

    fun selectAll() { _s.value = _s.value.copy(selected = _s.value.outlets.map { it.id }.toSet()) }

    suspend fun submit() {
        val s = _s.value
        if (busy || s.result is SetPlanResult.Saved) return
        val errors = validatePlan(s.date, s.routeId, s.selected, today())
        if (errors.isNotEmpty()) { _s.value = s.copy(result = SetPlanResult.Invalid(errors)); return }
        busy = true
        try {
            val n = queue.save(PlanSave(s.date, checkNotNull(s.routeId), s.selected))
            _s.value = _s.value.copy(result = SetPlanResult.Saved(n), alreadyPlanned = _s.value.alreadyPlanned + s.selected)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { _s.value = _s.value.copy(result = SetPlanResult.Failed) } finally { busy = false }
    }
}

/** F-TSO-014: My Visit Plan, date + zone + route, tabs Pending / Completed. */
class VisitPlanState(private val source: PlanSource, private val today: () -> String) {
    private val _s = MutableStateFlow(Ui(date = today()))
    val ui: StateFlow<Ui> = _s.asStateFlow()
    fun maxIso() = java.time.LocalDate.parse(today()).plusDays(MAX_PLAN_AHEAD_DAYS).toString()

    data class Ui(
        val date: String, val routes: List<PlanRoute> = emptyList(), val routesLoad: PickLoad = PickLoad.IDLE, val zoneId: Long? = null, val routeId: Long? = null,
        val plan: VisitPlanItem? = null, val planLoad: PickLoad = PickLoad.IDLE, val outlets: Map<Long, PlanOutlet> = emptyMap(),
        val pending: List<Long> = emptyList(), val completed: List<Long> = emptyList(), val tab: Int = 0, val taskPending: Set<Long> = emptySet(),
    )

    suspend fun loadRoutes() {
        _s.value = _s.value.copy(routesLoad = PickLoad.LOADING)
        val r = runCatching { source.routes() }.getOrNull()
        _s.value = _s.value.copy(routes = r.orEmpty(), routesLoad = if (r == null) PickLoad.UNAVAILABLE else PickLoad.READY)
    }

    fun setDate(d: String) { _s.value = _s.value.copy(date = d.take(10), plan = null, planLoad = PickLoad.IDLE, pending = emptyList(), completed = emptyList()) }
    fun pickZone(z: Long) { _s.value = _s.value.copy(zoneId = z, routeId = null, plan = null, planLoad = PickLoad.IDLE, pending = emptyList(), completed = emptyList()) }
    fun pickRoute(r: Long) { _s.value = _s.value.copy(routeId = r, plan = null, planLoad = PickLoad.IDLE, pending = emptyList(), completed = emptyList()) }
    fun tab(i: Int) { _s.value = _s.value.copy(tab = i) }

    suspend fun show() {
        val s = _s.value; val rid = s.routeId ?: return
        _s.value = s.copy(planLoad = PickLoad.LOADING)
        val plans = runCatching { source.plans(s.date) }.getOrNull()
        if (plans == null) { _s.value = _s.value.copy(planLoad = PickLoad.UNAVAILABLE); return }
        val plan = plans.firstOrNull { it.routeId == rid }
        val outs = runCatching { source.outlets(rid) }.getOrNull().orEmpty().associateBy { it.id }
        val local = plan?.let { runCatching { source.locallyDone(it.planUuid) }.getOrDefault(emptySet()) }.orEmpty()
        val (p, c) = splitPlan(plan, local)
        val tp = plan?.let { runCatching { source.taskPending(it.planUuid) }.getOrDefault(emptySet()) }.orEmpty()
        _s.value = _s.value.copy(plan = plan, planLoad = PickLoad.READY, outlets = outs, pending = p, completed = c, taskPending = tp)
    }

    /** Re-reads after a Visit Query was submitted so the outlet moves to Completed without leaving the screen. */
    suspend fun refresh() = show()
}

/** Everything My Call needs from the host; null hides the flow (previews and tests). */
class MyCallDeps(
    val source: PlanSource, val planQueue: PlanQueue, val queryQueue: QueryQueue, val taskQueue: TaskQueue,
    val loadRubric: suspend () -> Rubric?, val loadTaskTypes: suspend () -> List<TaskType>,
)

/** The outlet the rep opened from My Visit Plan, kept across recreation while Visit Query and Assign Task are open. */
class McSelection {
    var planUuid: String = ""
    var outlet: PlanOutlet? = null
    var route: PlanRoute? = null
    var rubric: Rubric? = null
    var rubricMissing: Boolean = false
    var types: List<TaskType> = emptyList()
}
