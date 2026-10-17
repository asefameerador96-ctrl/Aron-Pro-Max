package com.aktcl.aron.feature.tso.mycall

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MyCallScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun show(language: AppLanguage, content: @androidx.compose.runtime.Composable () -> Unit) {
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent { CompositionLocalProvider(LocalContext provides context) { AronTheme(language) { content() } } }
        compose.waitForIdle()
    }

    private val rubric = Rubric(5, 1, listOf(Criterion(1, "এসআর কি নিয়মিত ভিজিট করে?", "Does the SR visit regularly?"), Criterion(2, null, "Does the SR print memos regularly?")))

    private class Src : PlanSource {
        override suspend fun routes() = listOf(PlanRoute(1, "R1", "Route One", 10, "(Sat, Mon)", RouteSr(7, "Rahim")))
        override suspend fun outlets(routeId: Long) = listOf(PlanOutlet(1, "DHK-344-011", "Shop One", null, "Karim", "Cluster A", "01711000000"), PlanOutlet(2, "DHK-344-012", "Shop Two", null, "Salam", null, null))
        override suspend fun plans(date: String): List<VisitPlanItem>? = emptyList()
        override suspend fun locallyDone(planUuid: String) = emptySet<Long>()
        override suspend fun taskPending(planUuid: String) = emptySet<Long>()
    }

    @Test fun visitQueryShowsTheTwoQuestionsAndDelegateDefaultsToNo() {
        val q = object : QueryQueue { override suspend fun submit(q: QuerySubmit) = true }
        val st = VisitQueryState(rubric, "p", 1, 1, q)
        show(AppLanguage.EN) { VisitQueryScreen(st, "Shop One", onDone = {}, onBack = {}) }
        compose.onNodeWithText("Does the SR visit regularly?").assertIsDisplayed()
        compose.onNodeWithText("Does the SR print memos regularly?").assertIsDisplayed()
        compose.onNodeWithText("Do you want to delegate the task?").performScrollTo().assertIsDisplayed()
        assertEquals(false, st.ui.value.delegate)
        compose.onNodeWithTag(MyCallTags.SUBMIT).performClick(); compose.waitForIdle()
        assertEquals("answers are optional: Submit goes through", QueryResult.Submitted(false), st.ui.value.result)
    }

    @Test fun banglaQuestionsUseTheBanglaLabelWhenThereIsOne() {
        val st = VisitQueryState(rubric, "p", 1, 1) { true }
        show(AppLanguage.BN) { VisitQueryScreen(st, "Shop One", onDone = {}, onBack = {}) }
        compose.onNodeWithText("এসআর কি নিয়মিত ভিজিট করে?").assertIsDisplayed()
        compose.onNodeWithText("Does the SR print memos regularly?").assertIsDisplayed() // no Bangla label: English falls back, never blank
        compose.onNodeWithText("কাজ দিতে চান?").performScrollTo().assertIsDisplayed()
    }

    @Test fun submitThenDelegateYesCallsOnDone() {
        var delegated: Boolean? = null
        val st = VisitQueryState(rubric, "p", 1, 1) { true }
        st.answer(1, true); st.answer(2, true); st.delegate(true)
        show(AppLanguage.EN) { VisitQueryScreen(st, "Shop One", onDone = { delegated = it }, onBack = {}) }
        compose.onNodeWithTag(MyCallTags.SUBMIT).performClick(); compose.waitForIdle()
        assertEquals(true, delegated)
    }

    @Test fun assignTaskOnARouteWithoutSrIsBlockedWithTheMessage() {
        val st = AssignTaskState(listOf(TaskType("irregular_visit", null, "Irregular Visit", 1)), PlanRoute(1, "R", "N", 10, null, null), 1, { true }, { "2026-10-17" })
        show(AppLanguage.EN) { AssignTaskScreen(st, onDone = {}) }
        compose.onNodeWithTag(MyCallTags.NO_SR).assertIsDisplayed()
    }

    @Test fun setPlanListsZonesRoutesAndOutletsWithPhoneOnlyWhenPresent() {
        val st = SetPlanState(Src(), { true }.let { f -> PlanQueue { f(); 1 } }, { "2026-10-17" }, { "2026-10-18" })
        show(AppLanguage.EN) { SetPlanScreen(st, onBack = {}) }
        compose.onNodeWithTag(MyCallTags.zone(10)).performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithTag(MyCallTags.route(1)).performScrollTo().performClick(); compose.waitForIdle()
        assertEquals(1L, st.ui.value.routeId)
        compose.onNodeWithTag(MyCallTags.SHOW).performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithTag(MyCallTags.outlet(1)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("DHK-344-011  ·  Karim  ·  01711000000  ·  Cluster A").assertExists()
        compose.onNodeWithText("DHK-344-012  ·  Salam").assertExists() // no phone without the pii claim
        compose.onNodeWithTag(MyCallTags.outlet(1)).performScrollTo().performClick(); compose.waitForIdle()
        assertTrue(1L in st.ui.value.selected)
    }
}
