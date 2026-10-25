package com.aktcl.aron.feature.dayclose

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.core.ui.AppLocale
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** D-NIGHT-DAYCLOSE-01: the owner's two Sales Submit screenshots of 2026-10-24, rebuilt and fixed. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE) // real text measurement for the one-line header check
class SalesSubmitNightTest {
    @get:Rule val compose = createComposeRule()
    private val clean = OutboxStates(0, 0, 34, 5, 1)
    private val labels = mapOf("visit" to "Outlet", "memo" to "Sale", "stock_movement" to "Stock", "qc_line" to "QC", "memo_discount" to "Promotion")
    private val owner = listOf(CountRow("visit", 3, 9), CountRow("memo", 4, 10), CountRow("memo_line", 23, 29), CountRow("stock_movement", 4, 4))
    private val rejected = listOf(
        RejectedRow("u1", "memo", "2026-10-24", "memo_no_duplicate", RejectedOutcome.STORED_FLAGGED, memoNo = "sr1001-261024-003",
            reasonBn = "এই মেমো নম্বর আগেই ব্যবহার হয়েছে; পর্যালোচনার জন্য রাখা হয়েছে।", reasonEn = "This memo number was already used; held for review."),
        RejectedRow("u2", "memo_line", "2026-10-24", "price_invalid", RejectedOutcome.DROPPED, outletName = "Asef Home"),
    )

    /** The app's resources in [language] (as the app wraps its context), not only the theme. */
    @Composable
    private fun Lang(language: AppLanguage, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { content() } }
    }

    private fun show(language: AppLanguage, counts: List<CountRow>, rows: List<RejectedRow>, serverKnown: Boolean? = null) {
        val gate = SalesSubmitRules.gate(clean, counts, DuesAtSubmit(0, 0), false)
        compose.setContent {
            Lang(language) {
                SalesSubmitScreen(true, counts, gate, SubmitProgress.Idle, { labels[it] ?: it }, {}, {}, rejected = rows, serverKnown = serverKnown)
            }
        }
        compose.waitForIdle()
    }

    @Test fun theServerAboveThePhoneIsInformationAndSubmitIsOn() {
        show(AppLanguage.EN, owner, rejected)
        compose.onNodeWithText("Not matching").assertDoesNotExist()
        compose.onNodeWithText("Server has 6 more Outlet from other phones or earlier installs of this login today.", substring = true).assertExists()
        compose.onNodeWithText("Submit sales").performScrollTo().assertIsEnabled()
    }

    @Test fun theRejectedRowsAreListedWithReasonAndOutcomeNotCounted() {
        show(AppLanguage.EN, owner, rejected)
        compose.onNodeWithTag(SalesSubmitTags.REJECTED).assertExists()
        compose.onNodeWithText("Not accepted as sent (2)").assertExists()
        compose.onNodeWithText("Sale · sr1001-261024-003").assertExists()
        compose.onNodeWithText("This memo number was already used; held for review.").assertExists()
        compose.onNodeWithText("Stored on the server and flagged for review. Submit is not blocked.").assertExists()
        compose.onNodeWithText("Sale line · Asef Home").assertExists() // a type the host has no label for still reads in words
        compose.onNodeWithText("Reason code: price_invalid").assertExists()
        compose.onNodeWithText("Not stored on the server. Submit is not blocked.").assertExists()
        compose.onNodeWithText("5 records were rejected by the server.").assertDoesNotExist() // the bare count gave way to the list
    }

    @Test fun banglaFirstReasonAndTheListShowsBeforeAnyDigest() {
        // 12:04 screenshot: no digest yet; the list still shows, and "Nothing to compare yet" only because no digest has run
        show(AppLanguage.BN, emptyList(), rejected, serverKnown = false)
        compose.onNodeWithText("এই মেমো নম্বর আগেই ব্যবহার হয়েছে; পর্যালোচনার জন্য রাখা হয়েছে।").assertExists()
        compose.onNodeWithText("এখনো তুলনার কিছু নেই").assertExists()
    }

    @Test fun afterADigestAnEmptyPhoneDayIsNotNothingToCompare() {
        show(AppLanguage.EN, emptyList(), emptyList(), serverKnown = true)
        compose.onNodeWithText("Nothing to compare yet").assertDoesNotExist()
        compose.onNodeWithText("No records on this phone today").assertExists()
    }

    @Test fun aRetryRowBlocksUntilResynced() {
        val gate = SalesSubmitRules.gate(clean.copy(pending = 1), owner, DuesAtSubmit(0, 0), false)
        val retry = listOf(RejectedRow("u3", "visit", "2026-10-24", "scope_stale", RejectedOutcome.RETRY))
        compose.setContent { Lang(AppLanguage.EN) { SalesSubmitScreen(true, owner, gate, SubmitProgress.Idle, { labels[it] ?: it }, {}, {}, rejected = retry) } }
        compose.onNodeWithText("Will be sent again. Sync before Submit.").assertExists()
        compose.onNodeWithText("Submit sales").performScrollTo().assertIsNotEnabled()
    }

    private fun headerOnOneLine(lang: AppLanguage, item: String, phone: String, server: String, fontScale: Float = 1.3f) {
        compose.setContent { CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(androidx.compose.ui.platform.LocalDensity.current.density, fontScale)) { Lang(lang) { SalesSubmitScreen(true, owner, SalesSubmitRules.gate(clean, owner, DuesAtSubmit(0, 0), false), SubmitProgress.Idle, { labels[it] ?: it }, {}, {}) } } }
        compose.waitForIdle()
        val h = compose.onNodeWithText(item).getBoundsInRoot().let { it.bottom - it.top }
        for (t in listOf(phone, server)) {
            val b = compose.onNodeWithText(t).getBoundsInRoot()
            assertEquals("$t on one line ($lang)", h.value, (b.bottom - b.top).value, 0.5f)
        }
    }

    /**
     * (c): at 360 dp and the large text of the owner's phone (1.3, the table is not stacked below 1.5) the headers stay on one
     * line (the screenshot showed "Devic e / Serve r").
     */
    @Test fun theHeaderFitsAt360InEnglish() = headerOnOneLine(AppLanguage.EN, "Item", "Phone", "Server")

    @Test fun theHeaderFitsAt360InBangla() = headerOnOneLine(AppLanguage.BN, "বিষয়", "ফোন", "সার্ভার")
}
