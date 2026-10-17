package com.aktcl.aron.feature.tso.mycall

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

private fun r(id: Long, zone: Long) = PlanRoute(id, "R$id", "Route $id", zone, null)
private fun o(id: Long) = PlanOutlet(id, "DHK-$id", "Shop $id", null, "Owner $id", null, null)

private class Src(var routes: List<PlanRoute>? = listOf(r(1, 10), r(2, 10), r(3, 11)), var outlets: Map<Long, List<PlanOutlet>> = mapOf(1L to (1L..4L).map(::o)), var plans: List<VisitPlanItem>? = emptyList(), var done: Set<Long> = emptySet()) : PlanSource {
    override suspend fun routes() = routes
    override suspend fun outlets(routeId: Long) = outlets[routeId]
    override suspend fun plans(date: String) = plans
    override suspend fun locallyDone(planUuid: String) = done
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlanLogicTest {
    @Test fun idsAreDeterministicPerPlannerDateRouteAndOutlet() {
        val a = PlanIds.plan(7, "2026-10-20", 1)
        assertEquals(a, PlanIds.plan(7, "2026-10-20", 1))
        assertNotEquals(a, PlanIds.plan(8, "2026-10-20", 1)); assertNotEquals(a, PlanIds.plan(7, "2026-10-21", 1)); assertNotEquals(a, PlanIds.plan(7, "2026-10-20", 2))
        assertEquals(PlanIds.planOutlet(a, 5), PlanIds.planOutlet(a, 5)); assertNotEquals(PlanIds.planOutlet(a, 5), PlanIds.planOutlet(a, 6))
        java.util.UUID.fromString(a) // a valid uuid
    }

    @Test fun validation() {
        val t = "2026-10-17"
        assertTrue(validatePlan("2026-10-18", 1, setOf(1), t).isEmpty())
        assertTrue(validatePlan(t, 1, setOf(1), t).isEmpty())
        assertEquals(listOf(PlanError.NO_ROUTE, PlanError.NO_OUTLETS), validatePlan("2026-10-18", null, emptySet(), t))
        assertEquals(listOf(PlanError.DATE_PAST), validatePlan("2026-10-16", 1, setOf(1), t))
        assertEquals(listOf(PlanError.DATE_FAR), validatePlan("2027-01-01", 1, setOf(1), t))
        assertEquals(listOf(PlanError.DATE_FORMAT), validatePlan("18-10-2026", 1, setOf(1), t))
    }

    @Test fun zonesAreDistinctAndSorted() = assertEquals(listOf(10L, 11L), zonesOf(listOf(r(3, 11), r(1, 10), r(2, 10))))

    @Test fun splitPlanCountsLocalCompletionAndNeverDuplicates() {
        val p = VisitPlanItem("u", "2026-10-18", 1, listOf(PlannedOutlet(1, PlanStatus.PENDING), PlannedOutlet(2, PlanStatus.COMPLETED, "x"), PlannedOutlet(3, PlanStatus.PENDING), PlannedOutlet(3, PlanStatus.PENDING)))
        assertEquals(listOf(1L, 3L) to listOf(2L), splitPlan(p, emptySet()))
        assertEquals(listOf(1L) to listOf(2L, 3L), splitPlan(p, setOf(3)))
        assertEquals(emptyList<Long>() to emptyList<Long>(), splitPlan(null, setOf(3)))
    }

    @Test fun jsonReadsContractAndDropsBadRows() {
        val plans = PlanJson.parsePlans("""{"items":[{"plan_uuid":"u","planner_user_id":1,"plan_date":"2026-10-18","route_id":5,"outlets":[{"outlet_id":1,"status":"completed","completed_at":"2026-10-18T05:00:00Z"},{"outlet_id":2,"status":"pending","completed_at":null}]},{"plan_uuid":"bad"}],"next_cursor":null}""")!!
        assertEquals(1, plans.size); assertEquals(PlanStatus.COMPLETED, plans[0].outlets[0].status); assertEquals(PlanStatus.PENDING, plans[0].outlets[1].status)
        assertEquals(plans, PlanJson.parsePlans(PlanJson.plansToCache(plans)))
        val routes = PlanJson.parseRoutes("""{"items":[{"id":1,"code":"A","name":"N","zone_id":3,"status":"active","display_label":null},{"id":2,"code":"B","name":"N","zone_id":3,"status":"inactive"}]}""")!!
        assertEquals(listOf(1L), routes.map { it.id })
        assertEquals(null, PlanJson.parseRoutes("<html>"))
        val outs = PlanJson.parseOutlets("""{"items":[{"id":1,"code":"DHK-1","name":"S","name_bn":null,"owner_name":"O","contact_number":null}]}""")!!
        assertEquals(null, outs[0].phone)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SetPlanStateTest {
    private class Q : PlanQueue { val saves = mutableListOf<PlanSave>(); var fail = false; override suspend fun save(save: PlanSave): Int { if (fail) error("db"); saves += save; return save.outletIds.size } }

    private fun state(src: Src, q: Q) = SetPlanState(src, q, { "2026-10-17" }, { "2026-10-18" })

    @Test fun flowSelectsSavesAndIsIdempotentOnDoubleTap() = runBlocking {
        val q = Q(); val s = state(Src(), q)
        s.loadRoutes(); s.pickZone(10); s.pickRoute(1); s.showOutlets()
        assertEquals(4, s.ui.value.outlets.size)
        s.toggle(1); s.toggle(3)
        s.submit(); s.submit()
        assertEquals(1, q.saves.size); assertEquals(setOf(1L, 3L), q.saves[0].outletIds)
        assertEquals(SetPlanResult.Saved(2), s.ui.value.result)
    }

    @Test fun nothingSelectedIsInvalid() = runBlocking {
        val q = Q(); val s = state(Src(), q); s.loadRoutes(); s.pickRoute(1); s.showOutlets(); s.submit()
        assertTrue((s.ui.value.result as SetPlanResult.Invalid).errors.contains(PlanError.NO_OUTLETS)); assertTrue(q.saves.isEmpty())
    }

    @Test fun existingPlanOutletsComeSelectedSoTheSaveIsAUnion() = runBlocking {
        val plan = VisitPlanItem(PlanIds.plan(7, "2026-10-18", 1), "2026-10-18", 1, listOf(PlannedOutlet(1, PlanStatus.PENDING), PlannedOutlet(2, PlanStatus.PENDING)))
        val q = Q(); val s = state(Src(plans = listOf(plan)), q)
        s.loadRoutes(); s.pickRoute(1); s.showOutlets()
        assertEquals(setOf(1L, 2L), s.ui.value.selected)
        s.toggle(4); s.submit()
        assertEquals(setOf(1L, 2L, 4L), q.saves.single().outletIds)
    }

    @Test fun offlineWithoutCacheSaysUnavailableAndFailureIsRetryable() = runBlocking {
        val q = Q(); val s = state(Src(routes = null), q); s.loadRoutes()
        assertEquals(PickLoad.UNAVAILABLE, s.ui.value.routesLoad)
        val s2 = state(Src(), q.also { it.fail = true }); s2.loadRoutes(); s2.pickRoute(1); s2.showOutlets(); s2.toggle(1); s2.submit()
        assertEquals(SetPlanResult.Failed, s2.ui.value.result)
        q.fail = false; s2.submit(); assertEquals(SetPlanResult.Saved(1), s2.ui.value.result)
    }

    @Test fun changingRouteClearsTheSelection() = runBlocking {
        val s = state(Src(), Q()); s.loadRoutes(); s.pickRoute(1); s.showOutlets(); s.toggle(1); s.pickRoute(2)
        assertTrue(s.ui.value.selected.isEmpty()); assertTrue(s.ui.value.outlets.isEmpty())
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VisitPlanStateTest {
    @Test fun tabsPendingAndCompleted() = runBlocking {
        val plan = VisitPlanItem("u", "2026-10-17", 1, listOf(PlannedOutlet(1, PlanStatus.PENDING), PlannedOutlet(2, PlanStatus.COMPLETED), PlannedOutlet(3, PlanStatus.PENDING)))
        val s = VisitPlanState(Src(plans = listOf(plan), done = setOf(3)), { "2026-10-17" })
        s.loadRoutes(); s.pickRoute(1); s.show()
        assertEquals(listOf(1L), s.ui.value.pending); assertEquals(listOf(2L, 3L), s.ui.value.completed)
    }

    @Test fun noPlanForTheRouteIsEmptyNotAnError() = runBlocking {
        val s = VisitPlanState(Src(plans = emptyList()), { "2026-10-17" }); s.loadRoutes(); s.pickRoute(1); s.show()
        assertEquals(PickLoad.READY, s.ui.value.planLoad); assertTrue(s.ui.value.pending.isEmpty())
    }

    @Test fun offlineAndNothingCachedIsUnavailable() = runBlocking {
        val s = VisitPlanState(Src(plans = null), { "2026-10-17" }); s.loadRoutes(); s.pickRoute(1); s.show()
        assertEquals(PickLoad.UNAVAILABLE, s.ui.value.planLoad)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class QueryAndTaskTest {
    private val rubric = Rubric(5, 2, listOf(Criterion(1, null, "Does the SR visit regularly?"), Criterion(2, "এসআর কি নিয়মিত মেমো প্রিন্ট করে?", "Does the SR print memos regularly?")))
    private class QQ : QueryQueue { val subs = mutableListOf<QuerySubmit>(); var fail = false; override suspend fun submit(q: QuerySubmit): Boolean { if (fail) error("db"); subs += q; return true } }

    @Test fun rubricJsonPicksNewestRetailerRubricAndOnlyBoolEnabledCriteria() {
        val j = """[{"rubric_id":1,"version":1,"kind":"joint_call","criteria":[]},{"rubric_id":5,"version":1,"kind":"retailer_questionnaire","criteria":[{"criterion_id":1,"label_en":"old","answer_type":"bool"}]},
            {"rubric_id":5,"version":2,"kind":"retailer_questionnaire","criteria":[{"criterion_id":1,"label_en":"Q1","answer_type":"bool","enabled":true},{"criterion_id":2,"label_en":"Q2","label_bn":"প্র২","answer_type":"bool"},{"criterion_id":3,"label_en":"stars","answer_type":"score_1_5"},{"criterion_id":4,"label_en":"off","answer_type":"bool","enabled":false}]}]"""
        val r = RubricJson.retailer(j)!!
        assertEquals(2, r.version); assertEquals(listOf(1L, 2L), r.criteria.map { it.id }); assertEquals("প্র২", r.criteria[1].label(true))
        assertEquals(null, RubricJson.retailer(null)); assertEquals(null, RubricJson.retailer("{"))
    }

    @Test fun mustAnswerEveryQuestionDelegateDefaultsToNoAndSubmitIsOnce() = runBlocking {
        val q = QQ(); val s = VisitQueryState(rubric, "plan", 9, 1, q)
        assertEquals(false, s.ui.value.delegate)
        s.submit(); assertEquals(QueryResult.Unanswered, s.ui.value.result); assertTrue(q.subs.isEmpty())
        s.answer(1, true); s.answer(2, false); s.submit(); s.submit()
        assertEquals(1, q.subs.size); assertEquals(QueryResult.Submitted(false), s.ui.value.result); assertEquals(mapOf(1L to true, 2L to false), q.subs[0].answers)
    }

    @Test fun delegateYesAsksForTheTaskAndAFailureCanRetry() = runBlocking {
        val q = QQ().also { it.fail = true }; val s = VisitQueryState(rubric, "plan", 9, 1, q)
        s.answer(1, true); s.answer(2, true); s.delegate(true); s.submit()
        assertEquals(QueryResult.Failed, s.ui.value.result)
        q.fail = false; s.submit(); assertEquals(QueryResult.Submitted(true), s.ui.value.result)
    }

    @Test fun queryIdsAreDeterministic() {
        assertEquals(QueryIds.assessment("p", 1), QueryIds.assessment("p", 1)); assertNotEquals(QueryIds.assessment("p", 1), QueryIds.assessment("p", 2))
        assertNotEquals(QueryIds.answer("a", 1), QueryIds.answer("a", 2))
    }

    private val types = listOf(TaskType("irregular_visit", null, "Irregular Visit", 1), TaskType("no_memo", "মেমো নেই", "No memo", 2))
    private class TQ : TaskQueue { val made = mutableListOf<TaskSubmit>(); override suspend fun create(t: TaskSubmit): Boolean { made += t; return true } }

    @Test fun assignTaskNeedsTypeAndDateAndGoesToTheRoutesSr() = runBlocking {
        val q = TQ(); val route = PlanRoute(1, "R", "N", 10, null, RouteSr(77, "Rahim"))
        val s = AssignTaskState(types, route, 9, q, { "2026-10-17" }) { "t-1" }
        s.submit(); assertTrue((s.ui.value.result as TaskResult.Invalid).errors.contains(TaskError.NO_TYPE))
        s.pickType("irregular_visit"); s.setDate("2026-10-16"); s.submit(); assertTrue((s.ui.value.result as TaskResult.Invalid).errors.contains(TaskError.DATE_PAST))
        s.setDate("2026-10-20"); s.setComment("  go  "); s.submit(); s.submit()
        assertEquals(1, q.made.size); val t = q.made[0]
        assertEquals(TaskSubmit("t-1", "irregular_visit", "Irregular Visit", 77, 1, 9, "2026-10-20", "go"), t)
    }

    @Test fun routeWithNoSrBlocksAssigning() = runBlocking {
        val q = TQ(); val s = AssignTaskState(types, PlanRoute(1, "R", "N", 10, null, null), 9, q, { "2026-10-17" })
        assertEquals(TaskResult.NoSr, s.ui.value.result)
        s.pickType("no_memo"); s.submit(); assertEquals(TaskResult.NoSr, s.ui.value.result); assertTrue(q.made.isEmpty())
    }

    @Test fun taskTypesComeFromTheCodeListSorted() {
        val cl = """[{"list_key":"task_type","items":[{"code":"b","label_en":"B","sort":2},{"code":"a","label_en":"A","label_bn":"এ","sort":1},{"label_en":"bad"}]}]"""
        assertEquals(listOf("a", "b"), TaskTypes.parse(cl).map { it.code }); assertTrue(TaskTypes.parse(null).isEmpty())
    }
}
