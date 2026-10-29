package com.aktcl.aron.feature.tickets

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aktcl.aron.core.ui.AppLocale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
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

/**
 * The screens wired to the states (English resources): what shows, that a double tap on Submit queues one record, that a
 * closed sheet stays closed, that leaving a screen drops its staged photos (and a recreation does not), and the parity
 * details of screenshots 12, 13 and 15.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w360dp-h800dp")
class TicketScreensTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val server = FakeServer()
    private val device = FakeDevice(server)
    private val photos = FakePhotos()
    private val d = deps(device, photos)
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

    // ---- the Quick sheet closes for good (screenshot 16)

    private fun openOutletSheetAndTakeAPhoto(): String {
        rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(TicketTags.tile("outlet_related")))
        rule.onNodeWithTag(TicketTags.tile("outlet_related")).performClick()
        rule.onNodeWithTag(TicketTags.SHEET).assertIsDisplayed()
        rule.onNodeWithTag(TicketTags.CAMERA).performClick()
        rule.waitForIdle()
        val staged = photos.staged.last()
        rule.onNodeWithTag(TicketTags.photo(staged), useUnmergedTree = true).assertExists()
        return staged
    }

    @Test fun theQuickSheetClosesForGoodOnTheXAndOnBackAndDropsItsPhoto() {
        show { QuickTicketScreen(d, frame, {}, {}) }
        rule.waitForIdle()

        val first = openOutletSheetAndTakeAPhoto()
        rule.onNodeWithTag(TicketTags.SHEET_CLOSE).performClick()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        rule.onNodeWithTag(TicketTags.SHEET).assertDoesNotExist() // and it does not come back
        assertEquals(listOf(first), photos.discarded)

        // system back with the sheet open closes the sheet only (the sheet's BackHandler), and drops its photo
        val second = openOutletSheetAndTakeAPhoto()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        rule.onNodeWithTag(TicketTags.SHEET).assertDoesNotExist()
        rule.onNodeWithTag(TicketTags.tile("outlet_related")).assertExists()
        assertEquals(listOf(first, second), photos.discarded)
        assertEquals("nothing was queued", 0, device.outboxRows.size)

        // a new tile is a new form with a new uuid, and it saves normally
        rule.onNodeWithTag(TicketTags.tile("outlet_related")).performClick()
        rule.onNodeWithTag(TicketTags.SUBMIT).performClick()
        rule.waitForIdle()
        val rec = device.outboxRows.values.single()
        assertTrue(rec.photos.isEmpty())
        rule.onNodeWithTag(TicketTags.SHEET).assertDoesNotExist()
    }

    // ---- leaving a screen drops its staged photos; an activity recreation keeps them

    /** The activity's lifecycle as the screens see it: RESUMED while on screen, below STARTED while being recreated. */
    private class TestOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    @Test fun leavingAScreenDropsItsStagedPhotosButARecreationKeepsThem() {
        val owner = TestOwner()
        val open = "00000000-0000-4000-8000-0000000000ee"
        runBlocking {
            val dr = TicketRules.newDraft(open, TicketOrigin.QUICK, "login_otp")
            device.ticket(open, TicketRules.payload(dr, TYPES[0]), emptyList(), TicketRules.local(dr, TYPES[0], T0))
        }
        val detail = TicketDetailState(d, open, mutableStateOf(CommentDraft("00000000-0000-4000-8000-0000000000c3", "see the photo", listOf("p-d"))))
        var screen by mutableStateOf("create")
        show {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                when (screen) {
                    "create" -> CreateTicketScreen(d, frame, {}, initial = TicketRules.newDraft("00000000-0000-4000-8000-0000000000e1", TicketOrigin.MANUAL).copy(photoUuids = listOf("p-c")))
                    "quick" -> QuickTicketScreen(d, frame, {}, {}, initialSheet = TicketRules.newDraft("00000000-0000-4000-8000-0000000000e2", TicketOrigin.QUICK, "login_otp").copy(photoUuids = listOf("p-q")))
                    "detail" -> TicketDetailScreen(detail, frame)
                    "create again" -> CreateTicketScreen(d, frame, {}, initial = TicketRules.newDraft("00000000-0000-4000-8000-0000000000e3", TicketOrigin.MANUAL).copy(photoUuids = listOf("p-r")))
                    else -> Unit
                }
            }
        }
        rule.waitForIdle()
        assertTrue(photos.discarded.isEmpty())
        screen = "quick"
        rule.waitForIdle()
        assertEquals("back from Create", listOf("p-c"), photos.discarded)
        screen = "detail"
        rule.waitForIdle()
        assertEquals("back from Quick with the sheet open", listOf("p-c", "p-q"), photos.discarded)
        screen = "create again"
        rule.waitForIdle()
        assertEquals("back from Details with an unsent comment", listOf("p-c", "p-q", "p-d"), photos.discarded)
        // the activity is recreated (rotation, process death): its lifecycle is already down when the screen goes, and the
        // saved draft brings the photo back, so it is kept
        owner.registry.currentState = Lifecycle.State.CREATED
        screen = "none"
        rule.waitForIdle()
        assertEquals(listOf("p-c", "p-q", "p-d"), photos.discarded)
        assertEquals("nothing was queued by leaving", 1, device.outboxRows.size)
    }

    // ---- parity: screenshots 12, 13 (no chevron on All Tickets) and 15 (one tile height, caption)

    @Test fun allTicketsCardsHaveNoChevronAndTheAgeCentredWhileTheDashboardCardsHaveOne() {
        val id = "00000000-0000-4000-8000-0000000000ab"
        runBlocking {
            val dr = TicketRules.newDraft(id, TicketOrigin.QUICK, "login_otp")
            device.ticket(id, TicketRules.payload(dr, TYPES[0]), emptyList(), TicketRules.local(dr, TYPES[0], T0 - 4 * DAY))
        }
        var screen by mutableStateOf("list")
        val list = TicketListState(d)
        val dash = TicketDashboardState(d)
        show { if (screen == "list") TicketListScreen(list, frame, {}) else TicketDashboardScreen(dash, frame, {}, {}, {}) }
        rule.waitForIdle()
        rule.onNodeWithTag(TicketTags.chevron(id), useUnmergedTree = true).assertDoesNotExist()
        val card = rule.onNodeWithTag(TicketTags.card(id)).getBoundsInRoot()
        val age = rule.onNodeWithTag(TicketTags.age(id), useUnmergedTree = true).assertExists().getBoundsInRoot()
        val cardMid = (card.top.value + card.bottom.value) / 2
        val ageMid = (age.top.value + age.bottom.value) / 2
        assertEquals("the age sits at the card's middle", cardMid, ageMid, 1.5f)

        screen = "dashboard"
        rule.waitForIdle()
        rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(TicketTags.card(id)))
        rule.onAllNodesWithTag(TicketTags.chevron(id), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun quickTilesAreOneHeightAndTheLabelsOfARowEndOnOneLine() {
        show { QuickTicketScreen(d, frame, {}, {}) }
        rule.waitForIdle()
        val heights = TYPES.map { rule.onNodeWithTag(TicketTags.tile(it.code)).fetchSemanticsNode().size.height }
        assertEquals("every tile one height: $heights", 1, heights.distinct().size)
        // row 1 is "Login OTP issue" beside "Outlet Related Issue": the labels end on the same line
        val a = rule.onNodeWithTag(TicketTags.tileLabel("login_otp"), useUnmergedTree = true).getBoundsInRoot()
        val b = rule.onNodeWithTag(TicketTags.tileLabel("outlet_related"), useUnmergedTree = true).getBoundsInRoot()
        assertEquals(a.bottom.value, b.bottom.value, 1f)
        val tile = rule.onNodeWithTag(TicketTags.tile("login_otp")).getBoundsInRoot()
        assertTrue("the label sits in the lower half of the tile", a.top.value > (tile.top.value + tile.bottom.value) / 2 - 24f)
    }

    @Test fun theTileCaptionIsTheCategoryAndTheUrgencyInEnglishAndBangla() {
        val high = IssueType("login_otp", "Login OTP issue", category = "tickets", urgency = "high")
        val other = IssueType("x_new", "New one", category = "app", urgency = "urgent")
        val caption = mutableMapOf<String, String>()
        show {
            caption["en"] = tileCaption(high)
            caption["en medium"] = tileCaption(high.copy(urgency = "medium"))
            caption["en unknown"] = tileCaption(other)
            CompositionLocalProvider(LocalContext provides AppLocale.wrap(LocalContext.current, AppLanguage.BN)) {
                caption["bn"] = tileCaption(high)
                caption["bn low"] = tileCaption(high.copy(urgency = "low"))
                caption["bn unknown"] = tileCaption(other)
            }
        }
        rule.waitForIdle()
        assertEquals("Tickets · High", caption["en"]) // screenshot 15
        assertEquals("Tickets · Medium", caption["en medium"])
        assertEquals("টিকিট · উচ্চ", caption["bn"])
        assertEquals("টিকিট · নিম্ন", caption["bn low"])
        // a category or urgency code the app does not know is shown as sent, never dropped
        assertEquals("app · urgent", caption["en unknown"])
        assertEquals("app · urgent", caption["bn unknown"])
    }
}
