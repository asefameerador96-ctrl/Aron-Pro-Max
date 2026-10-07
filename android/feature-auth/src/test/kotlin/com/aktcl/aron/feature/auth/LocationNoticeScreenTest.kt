package com.aktcl.aron.feature.auth

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** F-SYS-075 gate: the notice (Bangla and English) stands in front of the day until accepted. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class LocationNoticeScreenTest {
    @get:Rule val compose = createComposeRule()
    private val accepted = mutableListOf<Long>()
    private var failAccept = false
    private var logouts = 0

    private fun show(need: NoticeNeed, language: AppLanguage = AppLanguage.BN, failLoad: Boolean = false) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(language) {
                    LocationNoticeGate(7L, load = { if (failLoad) error("db") else need }, accept = { if (failAccept) error("db") else accepted += it }, nowMs = { 1_000L }, onLogout = { logouts++ }) { Text("DAY") }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun requiredNoticeShowsBothLanguagesAndNoWayPastButAccept() {
        show(NoticeNeed(needed = true, required = true))
        compose.onNodeWithText("Location notice").assertIsDisplayed()
        compose.onNodeWithText("অবস্থান তথ্য সংক্রান্ত নোটিশ").assertIsDisplayed()
        compose.onNodeWithTag(LocationNoticeTags.LATER).assertDoesNotExist()
        compose.onNodeWithText("DAY").assertDoesNotExist()
        compose.onNodeWithTag(LocationNoticeTags.ACCEPT).performClick()
        compose.waitForIdle()
        assertEquals(listOf(1_000L), accepted)
        compose.onNodeWithText("DAY").assertIsDisplayed()
    }

    @Test fun optionalNoticeOffersLater() {
        show(NoticeNeed(needed = true, required = false), AppLanguage.EN)
        compose.onNodeWithTag(LocationNoticeTags.LATER).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("DAY").assertIsDisplayed()
        assertEquals(emptyList<Long>(), accepted)
    }

    /** Handover item: a config delta that turns the notice required mid-session ends "Later" at the next resume. */
    @Test fun laterEndsAtTheNextResumeWhenTheNoticeTurnsRequired() {
        var need = NoticeNeed(needed = true, required = false)
        val owner = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry.createUnsafe(this)
            override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        }
        owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
        compose.setContent {
            CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                AronTheme(AppLanguage.EN) { LocationNoticeGate(7L, load = { need }, accept = { accepted += it }, nowMs = { 1_000L }) { Text("DAY") } }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(LocationNoticeTags.LATER).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("DAY").assertIsDisplayed()
        // Still optional at the next resume: "Later" holds.
        compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.STARTED; owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        compose.waitForIdle()
        compose.onNodeWithText("DAY").assertIsDisplayed()
        // The delta turned it required: the next resume shows the notice with no way past but Accept.
        need = NoticeNeed(needed = true, required = true)
        compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.STARTED; owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        compose.waitForIdle()
        compose.onNodeWithText("DAY").assertDoesNotExist()
        compose.onNodeWithTag(LocationNoticeTags.LATER).assertDoesNotExist()
        compose.onNodeWithTag(LocationNoticeTags.ACCEPT).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("DAY").assertIsDisplayed()
        assertEquals(listOf(1_000L), accepted)
    }

    @Test fun acceptedNoticeOpensTheDayAtOnce() {
        show(NoticeNeed(needed = false, required = true))
        compose.onNodeWithText("DAY").assertIsDisplayed()
    }

    @Test fun aReadErrorFailsClosedToTheRequiredNotice() {
        show(NoticeNeed(needed = false, required = false), failLoad = true)
        compose.onNodeWithTag(LocationNoticeTags.ACCEPT).assertIsDisplayed()
        compose.onNodeWithTag(LocationNoticeTags.LATER).assertDoesNotExist()
        compose.onNodeWithText("DAY").assertDoesNotExist()
    }

    @Test fun aFailedAcceptSaysSoAndKeepsTheNotice() {
        failAccept = true
        show(NoticeNeed(needed = true, required = true), AppLanguage.EN)
        compose.onNodeWithTag(LocationNoticeTags.ACCEPT).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(LocationNoticeTags.FAILED).assertIsDisplayed()
        compose.onNodeWithText("DAY").assertDoesNotExist()
        failAccept = false
        compose.onNodeWithTag(LocationNoticeTags.ACCEPT).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("DAY").assertIsDisplayed()
    }

    @Test fun logoutIsOfferedOnTheRequiredNotice() {
        show(NoticeNeed(needed = true, required = true))
        compose.onNodeWithTag(LocationNoticeTags.LOGOUT).performClick()
        assertEquals(1, logouts)
    }
}
