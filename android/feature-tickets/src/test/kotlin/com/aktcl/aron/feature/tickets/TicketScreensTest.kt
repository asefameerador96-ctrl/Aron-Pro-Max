package com.aktcl.aron.feature.tickets

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.GlassTier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The screens wired to the states (English resources): what shows, and that a double tap on Submit queues one record. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w360dp-h800dp")
class TicketScreensTest {
    @get:Rule val rule = createComposeRule()

    private val server = FakeServer()
    private val device = FakeDevice(server)
    private val d = deps(device, FakePhotos())
    private val frame: TicketFrame = { _, content -> content() }

    /** The screen under the app theme in English (the theme sets LocalAppLanguage, whose default is Bangla). */
    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) =
        rule.setContent { AronTheme(AppLanguage.EN, dark = false, sunlight = false, tier = GlassTier.B, reducedMotion = true) { content() } }

    @Test fun theDashboardShowsTheCountsAndTheWholePercentages() {
        device.online = true
        runBlocking {
            repeat(3) { i ->
                val dr = TicketRules.newDraft("0000000$i-0000-4000-8000-000000000000", TicketOrigin.QUICK, "login_otp")
                device.ticket(dr.uuid, TicketRules.payload(dr, TYPES[0]), emptyList(), TicketRules.local(dr, TYPES[0], T0 - i * HOUR))
            }
            device.sync()
            server.resolve("00000000-0000-4000-8000-000000000000", "Checked.", T0)
        }
        var quick = 0
        val st = TicketDashboardState(d)
        show { TicketDashboardScreen(st, frame, {}, {}, { quick++ }) }
        rule.waitForIdle()
        rule.onNodeWithTag(TicketTags.RESOLVED_PCT).assertIsDisplayed().assertTextEquals("33%") // 1 of 3 resolved
        rule.onNodeWithTag(TicketTags.OPEN_PCT).assertTextEquals("67%") // 2 of 3 open
        rule.onNodeWithTag(TicketTags.stat(0)).assertIsDisplayed()
        rule.onNodeWithTag(TicketTags.HELP).performClick()
        assertEquals(1, quick)
        rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(TicketTags.card("00000000-0000-4000-8000-000000000000")))
        rule.onNodeWithTag(TicketTags.card("00000000-0000-4000-8000-000000000000")).assertIsDisplayed()
        rule.onNodeWithText("INC100001 · Login OTP issue").assertIsDisplayed()
    }

    @Test fun aQuickTicketTileOpensTheSheetAndSubmitSavesOneTicketAndClosesIt() {
        var submitted: String? = null
        show { QuickTicketScreen(d, frame, {}, { submitted = it }) }
        rule.waitForIdle()
        rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(TicketTags.tile("outlet_related")))
        rule.onNodeWithTag(TicketTags.tile("outlet_related")).performClick()
        rule.onNodeWithTag(TicketTags.SHEET).assertIsDisplayed()
        rule.onNodeWithTag(TicketTags.DESCRIPTION).performTextInput("DHK-344-011 no sales")
        val submit = rule.onNodeWithTag(TicketTags.SUBMIT)
        submit.performClick()
        runCatching { submit.performClick() } // a second tap: the sheet is already gone or the form is saved
        rule.waitForIdle()
        assertEquals(1, device.outboxRows.size)
        assertEquals(device.outboxRows.keys.single(), submitted)
        assertEquals("DHK-344-011 no sales", device.outboxRows.values.single().ticket!!.description)
        rule.onNodeWithTag(TicketTags.SHEET).assertDoesNotExist()
    }

    @Test fun anUnsentTicketSaysNotSentYetInTheList() {
        runBlocking {
            val dr = TicketRules.newDraft("00000000-0000-4000-8000-0000000000dd", TicketOrigin.QUICK, "outlet_related")
            device.ticket(dr.uuid, TicketRules.payload(dr, TYPES[1]), emptyList(), TicketRules.local(dr, TYPES[1], T0))
        }
        val st = TicketListState(d)
        show { TicketListScreen(st, frame, {}) }
        rule.waitForIdle()
        rule.onNodeWithText("Not sent yet · Outlet Related Issue").assertIsDisplayed()
        rule.onNodeWithTag(TicketTags.OFFLINE).assertIsDisplayed()
    }

    @Test fun createShowsTheErrorsThenSavesWithTheChosenIssue() {
        var submitted: String? = null
        show { CreateTicketScreen(d, frame, { submitted = it }) }
        rule.waitForIdle()
        rule.onNodeWithTag(TicketTags.SUBMIT).performScrollTo().performClick()
        rule.onNodeWithText("Pick the issue type.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Write a title.").assertExists()
        assertEquals(0, device.outboxRows.size)
        rule.onNodeWithTag(TicketTags.ISSUE_FIELD).performScrollTo().performClick()
        rule.onNodeWithTag(TicketTags.ISSUE_LIST).assertIsDisplayed()
        rule.onNodeWithTag(TicketTags.issue("final_submission")).performClick()
        rule.onNodeWithText("Final Submission").assertIsDisplayed()
        rule.onNodeWithTag(TicketTags.TITLE).performTextInput("Zone 7 not submitted")
        rule.onNodeWithTag(TicketTags.DESCRIPTION).performTextInput("Get Sales Data spins")
        rule.onNodeWithTag(TicketTags.SUBMIT).performScrollTo().performClick()
        rule.waitForIdle()
        val rec = device.outboxRows.values.single()
        assertEquals("final_submission", rec.ticket!!.issueTypeCode)
        assertEquals("manual", rec.ticket.source)
        assertEquals(rec.uuid, submitted)
    }

    @Test fun aResolvedTicketOffersCloseOnceAndNoComposer() {
        device.online = true
        val id = "00000000-0000-4000-8000-0000000000aa"
        runBlocking {
            val dr = TicketRules.newDraft(id, TicketOrigin.QUICK, "login_otp")
            device.ticket(id, TicketRules.payload(dr, TYPES[0]), emptyList(), TicketRules.local(dr, TYPES[0], T0 - 4 * DAY))
            device.sync()
            server.resolve(id, "Checked.", T0)
        }
        val st = TicketDetailState(d, id, mutableStateOf(CommentDraft("00000000-0000-4000-8000-0000000000c1")))
        show { TicketDetailScreen(st, frame) }
        rule.waitForIdle()
        rule.onNodeWithText("This ticket is resolved. No further comments can be added.").assertIsDisplayed()
        rule.onNodeWithTag(TicketTags.COMPOSER).assertDoesNotExist()
        rule.onNodeWithText("Support").assertIsDisplayed()
        rule.onNodeWithTag(TicketTags.CLOSE).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TicketTags.CLOSE).assertDoesNotExist()
        rule.onNodeWithTag(TicketTags.CLOSING).assertIsDisplayed()
        rule.onNodeWithText("This ticket is closed. No further comments can be added.").assertIsDisplayed()
        assertEquals(1, device.outboxRows.values.count { it.event?.kind == EventKind.CLOSE_REVIEW })
    }

    @Test fun anOpenTicketHasTheComposerAndNoClose() {
        device.online = true
        val open = "00000000-0000-4000-8000-0000000000bb"
        runBlocking {
            val dr = TicketRules.newDraft(open, TicketOrigin.QUICK, "login_otp")
            device.ticket(open, TicketRules.payload(dr, TYPES[0]), emptyList(), TicketRules.local(dr, TYPES[0], T0))
            device.sync()
        }
        val st = TicketDetailState(d, open, mutableStateOf(CommentDraft("00000000-0000-4000-8000-0000000000c2")))
        show { TicketDetailScreen(st, frame) }
        rule.waitForIdle()
        rule.onNodeWithTag(TicketTags.COMPOSER).assertIsDisplayed()
        rule.onNodeWithTag(TicketTags.CLOSE).assertDoesNotExist()
        rule.onNodeWithTag(TicketTags.COMMENT).performTextInput("Still no OTP")
        rule.onNodeWithTag(TicketTags.SEND).performClick()
        rule.waitForIdle()
        assertTrue(device.outboxRows.containsKey("00000000-0000-4000-8000-0000000000c2"))
        rule.onNodeWithText("Still no OTP").assertIsDisplayed()
    }
}
