package com.aktcl.aron.feature.tso

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.feature.home.BundleStatus
import com.aktcl.aron.feature.home.HomeUser
import com.aktcl.aron.feature.home.RoleHomeTags
import com.aktcl.aron.feature.home.RoleHomes
import com.aktcl.aron.feature.tso.feedback.*
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowToast
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TsoFeedbackFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val cats = listOf(FeedbackCategory("suggestion", null, "Suggestion", 1))
    private val sent = mutableListOf<String>()
    private val src = object : FeedbackSource {
        override suspend fun categories() = cats
        override suspend fun cached(): FeedbackSource.Cached? = null
        override suspend fun unsent() = emptyList<FeedbackItem>()
        override suspend fun refresh() = FeedbackSource.Cached(emptyList(), "10:00")
    }
    private val q = FeedbackQueue { u, _, _ -> sent += u; true }

    /** My Feedback as the app's AreasHost wires it (FEEDBACK_LIST, the former Home tile's destination). */
    private fun feedbackScreen(source: FeedbackSource, userKey: String) = mapOf<TsoScreen, TsoScreenContent>(
        TsoScreen.FEEDBACK_LIST to { nav, _ -> FeedbackFlow(source, q, null, userKey, { java.time.Instant.now().toString() }, { nav.back() }) },
    )

    private fun start() {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(AppLanguage.EN) {
                    TsoApp(HomeUser("T", "tso1", "TSO", false, false, false), "2026-10-05", BundleStatus.Ready("b", "2026-10-05"), "0", {}, {}, screens = feedbackScreen(src, "tso1"))
                }
            }
        }
    }

    /** My Feedback > My feedback from the drawer (A1 shell step: the tile Home is retired, D-TSO-MAN-14). */
    private fun openMyFeedback() {
        compose.onNodeWithTag(TsoShellTags.MENU).performClick(); compose.waitForIdle()
        // the drawer keeps the last open group: expand My Feedback only when its rows are not shown yet
        if (compose.onAllNodesWithTag(TsoShellTags.row("feedback_list"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag(TsoShellTags.row("feedback"), useUnmergedTree = true).performScrollTo().performClick(); compose.waitForIdle()
        }
        compose.onNodeWithTag(TsoShellTags.row("feedback_list"), useUnmergedTree = true).performScrollTo().performClick(); compose.waitForIdle()
    }

    private fun sendOne() {
        openMyFeedback()
        compose.onNodeWithTag(FeedbackTags.NEW).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(FeedbackTags.TITLE).performTextInput("t")
        compose.onNodeWithTag(FeedbackTags.DESCRIPTION).performTextInput("d")
    }

    @Test fun reopenAfterSendShowsAFreshForm() {
        start(); sendOne()
        compose.onNode(androidx.compose.ui.test.hasText("Suggestion")).performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithTag(FeedbackTags.SEND).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(FeedbackTags.QUEUED).assertExists()
        compose.onNodeWithTag(FeedbackTags.SEND).performClick(); compose.waitForIdle() // Done -> list
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() // list -> Dashboard
        openMyFeedback()
        compose.onNodeWithTag(FeedbackTags.NEW).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(FeedbackTags.QUEUED).assertDoesNotExist()
    }

    @Test fun nextUserOnTheSamePhoneNeverSeesTheLastUsersFeedback() {
        fun source(tag: String) = object : FeedbackSource {
            override suspend fun categories() = cats
            override suspend fun cached(): FeedbackSource.Cached? = null
            override suspend fun unsent() = emptyList<FeedbackItem>()
            override suspend fun refresh() = FeedbackSource.Cached(listOf(FeedbackItem("u-$tag", "suggestion", "title-$tag", "d", "2026-10-01T00:00:00Z", FeedbackStatus.NEW)), "10:00")
        }
        val who = androidx.compose.runtime.mutableStateOf<String?>("A")
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(AppLanguage.EN) {
                    who.value?.let { w -> TsoApp(HomeUser("T", w, "TSO", false, false, false), "2026-10-05", BundleStatus.Ready("b", "2026-10-05"), "0", {}, {}, screens = feedbackScreen(androidx.compose.runtime.remember(w) { source(w) }, w)) }
                }
            }
        }
        openMyFeedback()
        compose.onNodeWithTag(FeedbackTags.card("u-A")).assertExists()
        compose.runOnIdle { who.value = null }; compose.waitForIdle()   // logout: the TSO host leaves composition, the activity stays
        compose.runOnIdle { who.value = "B" }; compose.waitForIdle()    // next rep logs in
        openMyFeedback()
        compose.onNodeWithTag(FeedbackTags.card("u-A")).assertDoesNotExist()
        compose.onNodeWithTag(FeedbackTags.card("u-B")).assertExists()
    }
}

private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onNodeWithText0(t: String) = onNodeWithText(t).performClick()
private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onNodeWithText(t: String) = this.onNode(androidx.compose.ui.test.hasText(t))