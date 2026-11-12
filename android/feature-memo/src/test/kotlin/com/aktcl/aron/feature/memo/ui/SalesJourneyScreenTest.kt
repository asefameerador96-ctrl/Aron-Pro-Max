package com.aktcl.aron.feature.memo.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.rules.JourneyCallInput
import com.aktcl.aron.rules.JourneyTimeline
import com.aktcl.aron.rules.UiLocale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** SRM-B-82 to 85 (screenshot 59): the call timeline, in both languages. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h1600dp")
class SalesJourneyScreenTest {
    @get:Rule val compose = createComposeRule()

    // 2026-11-11 03:53:35 UTC = 9:53:35 AM in Dhaka
    private val t0 = 1_794_000_000_000L - (1_794_000_000_000L % 86_400_000L) + (3 * 3600 + 53 * 60 + 35) * 1000L
    private fun input(id: String, outlet: Long, at: Long, end: Long?, lat: Double, sold: Boolean = true, edited: Boolean = false) =
        JourneyCallInput(id, outlet, t0 + at * 1000, end?.let { t0 + it * 1000 }, lat, 90.40, sold, edited)

    private fun show(language: AppLanguage, calls: List<SalesJourneyCall>) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { SalesJourneyScreen(calls, if (language == AppLanguage.BN) UiLocale.BN else UiLocale.EN) } } }
        compose.waitForIdle()
    }

    private val rows = JourneyTimeline.build(listOf(
        input("a", 1, 0, 327, 23.7800),
        input("b", 2, 800, 1138, 23.7810, edited = true),
        input("c", 1, 1200, 1290, 23.7800),
        input("d", 3, 1500, 1560, 23.7810, sold = false),
    ))
    private fun calls() = rows.map { SalesJourneyCall(it, "Store ${it.input.outletId} (code-017-Agora Road)") }

    @Test fun banglaTimelineShowsStatusTimesDurationsGapAndDistanceRows() {
        show(AppLanguage.BN, calls())
        compose.onNodeWithText("New Sale").assertExists(); compose.onNodeWithText("Edited Sale").assertExists(); compose.onNodeWithText("Repeat Sale").assertExists()
        compose.onNodeWithText("বিক্রি নেই").assertExists()
        compose.onNodeWithText("9:53:35 AM - 9:59:02 AM").assertExists()
        compose.onNodeWithText("৫ মিনিট ২৭ সেকেন্ড").assertExists()
        compose.onNodeWithTag(SalesJourneyTags.gap("a")).assertExists() // "... পরে" between the calls
        compose.onNodeWithText("৭ মিনিট ৫৩ সেকেন্ড পরে").assertExists()
        // the first card has no distance row; the second has দূরত্ব and মোট
        compose.onNodeWithTag(SalesJourneyTags.card("a")).assertExists()
        // calls b, c and d each have a leg of 111.20 m; the first call has no distance row at all
        compose.onAllNodesWithText("দূরত্ব", substring = true).assertCountEquals(3)
        compose.onAllNodesWithText("দূরত্ব: ১১১.২০ মি", substring = true).assertCountEquals(3)
        compose.onNodeWithText("মোট: ৩৩৩.৫৯ মি", substring = true).assertExists() // the running total after the third leg (3 x 111.1951)
    }

    @Test fun englishUsesLatinDigitsAndTheLastCallHasNoGapRow() {
        show(AppLanguage.EN, calls())
        compose.onNodeWithText("5 min 27 s").assertExists()
        compose.onNodeWithText("after 7 min 53 s").assertExists()
        compose.onNodeWithText("No Sale").assertExists()
        compose.onNodeWithTag(SalesJourneyTags.gap("d")).assertDoesNotExist()
    }

    @Test fun anEmptyDayShowsTheEmptyStateNotACrash() {
        show(AppLanguage.EN, emptyList())
        compose.onNodeWithTag(SalesJourneyTags.EMPTY).assertExists()
        compose.onNodeWithText("No calls yet today").assertExists()
    }
}
