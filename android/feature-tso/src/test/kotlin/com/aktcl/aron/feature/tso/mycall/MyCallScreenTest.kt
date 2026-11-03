package com.aktcl.aron.feature.tso.mycall

import com.aktcl.aron.feature.tso.a11yProblems
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.feature.tso.TsoNav
import com.aktcl.aron.feature.tso.TsoScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * My Call screens (screenshots 31 to 38, D-TSO-MAN-35): the form with today's date and the dropdowns, Select Outlets,
 * Visit Plan Outlets without tab counts and "No Outlets Found", the 5-line card with the channel, Visit Query with the two
 * free-text questions and the delegate radio, Assign Task with the outlet card; and CR-4: the system back and the
 * on-screen back go to the same place at every step.
 */
@RunWith(AndroidJUnit4::class)
// the field phone (360 x 800 dp, as the goldens): the frame keeps its content above the Home disc (X4b), so the default 470 dp
// Robolectric screen leaves the form and the outlet list too little room to show the outlets at all
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class MyCallScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun show(language: AppLanguage, content: @androidx.compose.runtime.Composable () -> Unit) {
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent { CompositionLocalProvider(LocalContext provides context) { AronTheme(language) { content() } } }
        compose.waitForIdle()
    }

    private fun systemBack() { compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() }
    private fun screenBack() { compose.onNodeWithContentDescription("Back").performClick(); compose.waitForIdle() }

    private val boolRubric = Rubric(5, 1, listOf(Criterion(1, "এসআর কি নিয়মিত ভিজিট করে?", "Does the SR visit regularly?"), Criterion(2, null, "Does the SR print memos regularly?")))
    /** The questionnaire as V0139 seeds it: two free-text questions (D-TSO-MAN-35). */
    private val seeded = Rubric(9, 1, listOf(
        Criterion(1, "SR আপনার দোকান নিয়মিত ভিজিট করে?", "Does the SR visit your shop regularly?", text = true),
        Criterion(2, "SR নিয়মিত মেমো প্রিন্ট করে দেয়?", "Does the SR give you a printed memo regularly?", text = true),
    ))

    private class Src(var plans: List<VisitPlanItem>? = emptyList()) : PlanSource {
        override suspend fun routes() = listOf(PlanRoute(1, "R1", "Route One", 10, "(Sat, Mon)", RouteSr(7, "Rahim")), PlanRoute(2, "R2", "Route Two", 10, null, null))
        override suspend fun outlets(routeId: Long) = listOf(
            PlanOutlet(1, "DHK-344-011", "Shop One", null, "Karim", "Cluster A", "01711000000", "GT"),
            PlanOutlet(2, "DHK-344-012", "Shop Two", null, "Salam", null, null),
        )
        override suspend fun plans(date: String): List<VisitPlanItem>? = plans
        override suspend fun locallyDone(planUuid: String) = emptySet<Long>()
        override suspend fun taskPending(planUuid: String) = emptySet<Long>()
    }

    private class Nav : TsoNav {
        var backs = 0; var homes = 0
        override fun open(screen: TsoScreen, arg: String?) = Unit
        override fun back() { backs++ }
        override fun home() { homes++ }
    }

    // ---- Set Plan (31, 32) and Select Outlets (33)

    @Test fun setPlanOpensOnTodayWithTheOnlyZoneChosenAndNeedsARoute() {
        var shown = 0
        val st = SetPlanState(Src(), PlanQueue { 1 }, { "2026-10-17" })
        show(AppLanguage.EN) { SetPlanScreen(st, onBack = {}, onShowOutlets = { shown++ }) }
        compose.onNodeWithText("October 17, 2026").assertIsDisplayed()
        compose.onNodeWithText("Zone 10").assertIsDisplayed() // the only zone is chosen
        compose.onNodeWithTag(MyCallTags.SHOW).performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithTag(MyCallTags.MISSING_ROUTE).assertIsDisplayed(); assertEquals(0, shown)
        compose.onNodeWithTag(MyCallTags.ROUTE).performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithText("Route One").performClick(); compose.waitForIdle()
        assertEquals(1L, st.ui.value.routeId)
        compose.onNodeWithTag(MyCallTags.SHOW).performScrollTo().performClick(); compose.waitForIdle()
        assertEquals(1, shown)
        assertEquals("accessibility: " + compose.a11yProblems(), emptyList<String>(), compose.a11yProblems())
    }

    @Test fun selectOutletsShowsTheCardLinesTicksAndSaves() {
        var saved = 0
        val st = SetPlanState(Src(), PlanQueue { it.outletIds.size }, { "2026-10-17" })
        st.pickRoute(1)
        show(AppLanguage.EN) { SelectOutletsScreen(st, onBack = {}, onSaved = { saved++ }) }
        compose.onNodeWithText("DHK-344-011 | Cluster A").assertIsDisplayed()
        compose.onNodeWithText("Karim | 01711000000").assertIsDisplayed()
        compose.onNodeWithText("Salam").assertIsDisplayed() // no phone without the pii claim: no empty "| "
        compose.onNodeWithTag(MyCallTags.outlet(1)).performClick(); compose.waitForIdle()
        assertTrue(1L in st.ui.value.selected)
        compose.onNodeWithTag(MyCallTags.SELECT_ALL).performClick(); compose.waitForIdle()
        assertEquals(setOf(1L, 2L), st.ui.value.selected)
        compose.onNodeWithTag(MyCallTags.SAVE).performClick(); compose.waitForIdle()
        assertEquals(SetPlanResult.Saved(2), st.ui.value.result); assertEquals(1, saved)
        assertEquals("accessibility: " + compose.a11yProblems(), emptyList<String>(), compose.a11yProblems())
    }

    @Test fun setPlanBackPathsAreTheSameForTheSystemAndTheScreen() {
        val nav = Nav()
        val deps = MyCallDeps(Src(), PlanQueue { 1 }, QueryQueue { true }, TaskQueue { true }, { seeded }, { emptyList() })
        show(AppLanguage.EN) { SetPlanEntry(deps, "u1", { "2026-10-17" }, nav) }
        fun toOutlets(pick: Boolean) {
            if (pick) {
                compose.onNodeWithTag(MyCallTags.ROUTE).performScrollTo().performClick(); compose.waitForIdle()
                compose.onNodeWithText("Route One").performClick(); compose.waitForIdle()
            }
            compose.onNodeWithTag(MyCallTags.SHOW).performScrollTo().performClick(); compose.waitForIdle()
            compose.onNodeWithTag(MyCallTags.SAVE).assertExists()
        }
        toOutlets(pick = true); systemBack()
        compose.onNodeWithTag(MyCallTags.SHOW).assertExists(); assertEquals(0, nav.backs)
        toOutlets(pick = false); screenBack()
        compose.onNodeWithTag(MyCallTags.SHOW).assertExists(); assertEquals(0, nav.backs)
        screenBack(); assertEquals("the form leaves through the navigator", 1, nav.backs)
    }

    // ---- My Visit Plan (34), Visit Plan Outlets (35, 36), Visit Query (37), Assign Task (38)

    private val today = "2026-10-17"
    private fun planned() = listOf(VisitPlanItem(PlanIds.plan(7, today, 1), today, 1, listOf(PlannedOutlet(1, PlanStatus.PENDING))))

    @Test fun visitPlanOutletsHasNoCountsAndSaysNoOutletsFound() {
        val st = VisitPlanState(Src(plans = emptyList())) { today }
        kotlinx.coroutines.runBlocking { st.loadRoutes(); st.pickRoute(1); st.show() }
        show(AppLanguage.EN) { VisitPlanOutletsScreen(st, { _, _, _ -> }, { _, _, _ -> }, onBack = {}) }
        compose.onNodeWithText("Pending").assertIsDisplayed(); compose.onNodeWithText("Completed").assertIsDisplayed()
        compose.onNodeWithText("No Outlets Found").assertIsDisplayed()
        st.tab(1); compose.waitForIdle()
        compose.onNodeWithText("No Outlets Found").assertIsDisplayed()
    }

    @Test fun thePlannedOutletCardHasFiveLinesWithTheChannel() {
        val st = VisitPlanState(Src(plans = planned())) { today }
        kotlinx.coroutines.runBlocking { st.loadRoutes(); st.pickRoute(1); st.show() }
        var opened: Long? = null
        show(AppLanguage.EN) { VisitPlanOutletsScreen(st, { _, o, _ -> opened = o.id }, { _, _, _ -> }, onBack = {}) }
        for (line in listOf("Shop One", "Code: DHK-344-011", "Owner: Karim", "Contact: 01711000000", "Channel: GT Channel", "Cluster: Cluster A")) compose.onNodeWithText(line).assertIsDisplayed()
        compose.onNodeWithTag(MyCallTags.outlet(1)).performClick(); compose.waitForIdle()
        assertEquals(1L, opened)
        assertEquals("accessibility: " + compose.a11yProblems(), emptyList<String>(), compose.a11yProblems())
    }

    @Test fun banglaCardLinesAndTabs() {
        val st = VisitPlanState(Src(plans = planned())) { today }
        kotlinx.coroutines.runBlocking { st.loadRoutes(); st.pickRoute(1); st.show() }
        show(AppLanguage.BN) { VisitPlanOutletsScreen(st, { _, _, _ -> }, { _, _, _ -> }, onBack = {}) }
        compose.onNodeWithText("বাকি").assertIsDisplayed(); compose.onNodeWithText("চ্যানেল: GT চ্যানেল").assertIsDisplayed()
        compose.onNodeWithText("যোগাযোগ: ০১৭১১০০০০০০").assertIsDisplayed()
    }

    @Test fun visitPlanBackPathsAreTheSameForTheSystemAndTheScreen() {
        val nav = Nav()
        val queued = mutableListOf<QuerySubmit>()
        val deps = MyCallDeps(Src(plans = planned()), PlanQueue { 1 }, QueryQueue { queued += it; true }, TaskQueue { true }, { seeded }, { listOf(TaskType("irregular_visit", null, "Irregular Visit", 3)) })
        show(AppLanguage.EN) { VisitPlanEntry(deps, "u2", { today }, nav) }
        compose.onNodeWithText("October 17, 2026").assertIsDisplayed() // My Visit Plan opens on today too
        compose.onNodeWithTag(MyCallTags.ROUTE).performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithText("Route One").performClick(); compose.waitForIdle()
        compose.onNodeWithTag(MyCallTags.SHOW).performScrollTo().performClick(); compose.waitForIdle()
        fun openQuery() { compose.onNodeWithTag(MyCallTags.outlet(1)).performClick(); compose.waitForIdle(); compose.onNodeWithTag(MyCallTags.text(1)).assertExists() }
        openQuery(); systemBack()
        compose.onNodeWithTag(MyCallTags.outlet(1)).assertExists() // Visit Query -> Visit Plan Outlets
        openQuery(); screenBack()
        compose.onNodeWithTag(MyCallTags.outlet(1)).assertExists()
        systemBack(); compose.onNodeWithTag(MyCallTags.SHOW).assertExists() // outlets -> form
        compose.onNodeWithTag(MyCallTags.SHOW).performScrollTo().performClick(); compose.waitForIdle()
        screenBack(); compose.onNodeWithTag(MyCallTags.SHOW).assertExists()
        assertEquals(0, nav.backs)
        // delegate Yes goes on to Assign Task; back from it returns to the outlets, where the outlet is now Completed
        compose.onNodeWithTag(MyCallTags.SHOW).performScrollTo().performClick(); compose.waitForIdle()
        openQuery()
        compose.onNodeWithTag(MyCallTags.text(1)).performTextInput("Yes, every Saturday")
        compose.onNodeWithTag(MyCallTags.DELEGATE_YES).performScrollTo().performClick()
        compose.onNodeWithTag(MyCallTags.SUBMIT).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(MyCallTags.INFO).assertExists() // Assign Task with the outlet card
        compose.onNodeWithText("Shop One", substring = true).assertExists(); compose.onNodeWithText("Route One", substring = true).assertExists()
        systemBack()
        compose.onNodeWithText("Pending").assertExists()
        assertEquals(mapOf(1L to "Yes, every Saturday"), queued.single().texts); assertTrue(queued.single().delegate)
        screenBack(); screenBack(); assertEquals(1, nav.backs)
    }

    @Test fun visitQueryShowsTheSeededQuestionsWithAnswerFieldsAndDelegateDefaultsToNo() {
        val st = VisitQueryState(seeded, "p", 1, 1) { true }
        show(AppLanguage.EN) { VisitQueryScreen(st, "Shop One", onDone = {}, onBack = {}) }
        compose.onNodeWithText("Does the SR visit your shop regularly?").assertIsDisplayed()
        compose.onNodeWithText("Does the SR give you a printed memo regularly?").assertIsDisplayed()
        assertEquals(2, compose.onAllNodesWithText("Enter your answer", useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithText("Do you want to delegate the task?").performScrollTo().assertIsDisplayed()
        assertEquals(false, st.ui.value.delegate)
        compose.onNodeWithTag(MyCallTags.text(2)).performTextInput("x".repeat(1500))
        assertEquals(MAX_ANSWER_TEXT, st.ui.value.texts.getValue(2).length) // 1000 at most (amends D-199's 500)
        compose.onNodeWithTag(MyCallTags.SUBMIT).performClick(); compose.waitForIdle()
        assertEquals(QueryResult.Submitted(false), st.ui.value.result)
        assertEquals("accessibility: " + compose.a11yProblems(), emptyList<String>(), compose.a11yProblems())
    }

    @Test fun banglaQuestionsUseTheBanglaLabelWhenThereIsOne() {
        val st = VisitQueryState(boolRubric, "p", 1, 1) { true }
        show(AppLanguage.BN) { VisitQueryScreen(st, "Shop One", onDone = {}, onBack = {}) }
        compose.onNodeWithText("এসআর কি নিয়মিত ভিজিট করে?").assertIsDisplayed()
        compose.onNodeWithText("Does the SR print memos regularly?").assertIsDisplayed() // no Bangla label: English falls back, never blank
        compose.onNodeWithText("কাজ দিতে চান?").performScrollTo().assertIsDisplayed()
    }

    @Test fun submitThenDelegateYesCallsOnDone() {
        var delegated: Boolean? = null
        val st = VisitQueryState(boolRubric, "p", 1, 1) { true }
        st.answer(1, true); st.answer(2, true); st.delegate(true)
        show(AppLanguage.EN) { VisitQueryScreen(st, "Shop One", onDone = { delegated = it }, onBack = {}) }
        compose.onNodeWithTag(MyCallTags.SUBMIT).performClick(); compose.waitForIdle()
        assertEquals(true, delegated)
    }

    @Test fun assignTaskShowsTheOutletCardAndTheTypeLabel() {
        val made = mutableListOf<TaskSubmit>()
        val st = AssignTaskState(listOf(TaskType("irregular_visit", null, "Irregular Visit", 3)), PlanRoute(1, "R2", "Apsis Route 2", 10, null, RouteSr(7, "Rahim")), 11, { made += it; true }, { today })
        var assigned = 0
        show(AppLanguage.EN) { AssignTaskScreen(st, onDone = {}, outlet = PlanOutlet(11, "DHK-344-011", "outlet name 11", null, "o", "Apsis Cluster", null), onAssigned = { assigned++ }) }
        for (t in listOf("Outlet Name:", "outlet name 11", "Apsis Cluster", "Apsis Route 2", "October 17, 2026", "Select Task Type")) compose.onNodeWithText(t, substring = true).assertExists()
        compose.onNodeWithTag(MyCallTags.TYPE).performClick(); compose.waitForIdle()
        compose.onNodeWithText("Irregular Visit").performClick(); compose.waitForIdle()
        compose.onNodeWithTag(MyCallTags.COMMENT).performTextInput("Visit every Saturday")
        compose.onNodeWithTag(MyCallTags.SUBMIT).performClick(); compose.waitForIdle()
        assertEquals("irregular_visit", made.single().typeCode); assertEquals("Visit every Saturday", made.single().comment); assertEquals(1, assigned)
        assertEquals("accessibility: " + compose.a11yProblems(), emptyList<String>(), compose.a11yProblems())
    }

    @Test fun assignTaskOnARouteWithoutSrIsBlockedWithTheMessage() {
        val st = AssignTaskState(listOf(TaskType("irregular_visit", null, "Irregular Visit", 1)), PlanRoute(1, "R", "N", 10, null, null), 1, { true }, { today })
        show(AppLanguage.EN) { AssignTaskScreen(st, onDone = {}) }
        compose.onNodeWithTag(MyCallTags.NO_SR).assertIsDisplayed()
        assertEquals("accessibility: " + compose.a11yProblems(), emptyList<String>(), compose.a11yProblems())
    }
}

