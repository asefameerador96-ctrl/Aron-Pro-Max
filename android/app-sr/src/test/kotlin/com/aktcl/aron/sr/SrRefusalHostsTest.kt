package com.aktcl.aron.sr

import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * tso-manual X4a follow-up (R-W-22, R-W-23): the SR hosts composed on a real [SrDay]. The Stock screen shows the lock text for
 * a Save the zone's Final Submit refuses and queues nothing; after the SR's own Sales Submit the skip, the call, the no-sale
 * close, the sale and the edit show feature-dayclose's submit text ("Today's sales are already submitted.") and never throw.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp")
class SrRefusalHostsTest {
    @get:Rule val compose = hostComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var f: SrLockFixture

    @Before fun setUp() {
        Dispatchers.setMain(MainLooperImmediate.dispatcher)
        // owner decision 2026-11-04: the phone locks only under quarantine or reject (default accept_and_flag); this class tests the lock
        f = SrLockFixture(app, latePolicy = "quarantine")
    }

    @After fun tearDown() {
        // the screen goes first, then the day's work stops and the database closes (SrLockFixture.close), then Main resets
        try { f.close() } finally { Dispatchers.resetMain() }
    }

    /**
     * The Stock host composes its Save button only after its async load (the stock read and the slip-state reads, on Room's executor) returns,
     * and `waitForIdle` does not wait for Room. Under full parallel load the tap came first: "could not find any node (TestTag = 'stk_save')"
     * (D-QA-CI-14). Wait for the host's work to settle, then for the state the host exposes (the Save node), then tap.
     */
    private fun awaitStockLoaded() {
        assertTrue("the host's work did not settle", f.work.awaitSettled())
        compose.waitForIdle()
        compose.waitUntil(30_000) { runCatching { compose.onNodeWithTag(com.aktcl.aron.feature.stock.StockTags.SAVE).assertExists() }.isSuccess }
    }

    private fun show(content: @Composable () -> Unit) {
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        // the fixture owns the screen: its close destroys the activity (composition and view models) before the day's work
        // stops and the database closes (CI run 41750277921)
        f.own(ActivityScenario.launch(ComponentActivity::class.java)).onActivity { activity ->
            val ctx = AppLocale.wrap(activity, AppLanguage.EN)
            activity.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(AppLanguage.EN) { content() } } }
        }
        compose.waitForIdle()
    }

    private fun shows(text: String) = compose.waitUntil(5_000) { runCatching { compose.onNodeWithText(text).assertExists() }.isSuccess }

    private fun queued(type: String) = runBlocking { f.db.outboxDao().committedCounts(f.date) }.filter { it.recordType == type }.sumOf { it.count }

    @Test fun theStockScreenShowsTheLockTextForARefusedSaveAndQueuesNothing() {
        f.catalogOnly()
        runBlocking { f.day.stockLoad() }.setEntered(105, 3) // typed on the screen; the lock is already there
        f.finalSubmitted()
        show { StockHost(f.day) }
        awaitStockLoaded()
        compose.onNodeWithTag(com.aktcl.aron.feature.stock.StockTags.SAVE).performClick()
        shows(LOCK_TITLE)
        compose.onNodeWithText("Could not save", substring = true).assertDoesNotExist()
        assertTrue("nothing stored", runBlocking { f.db.captureDao().stockOn(f.date) }.isEmpty())
        assertEquals("nothing queued", 0, queued("stock_movement"))
        assertEquals("no slip printed", 0, queued("print_event"))
        assertNull(f.day.stockAttempt.value)
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText(LOCK_TITLE).assertDoesNotExist()
    }

    @Test fun theSkipHostWordsASkipTheSalesSubmitRefused() {
        f.salesSubmitted()
        var done = 0
        show { SkipHost(f.day, f.outlet(id = 50003), onDone = { done++ }) }
        compose.onNodeWithText("Shop closed").performClick()
        shows(SUBMITTED)
        compose.onNodeWithText(LOCK_TITLE).assertDoesNotExist()
        assertEquals("still on the skip until OK", 0, done)
        assertTrue(runBlocking { f.db.captureDao().visitSkipsOn(f.date) }.isEmpty())
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        assertEquals(1, done)
    }

    @Test fun aCallWhoseRouteDayWasSubmittedShowsTheSubmitTextAndOkEndsIt() {
        val v = f.openCall()
        f.day.visitSession.restore(v)
        f.salesSubmitted() // the SR went Home and submitted the day with this call still open
        var done = 0
        show { SaleHost(f.day, sunlight = false, onSunlight = {}, onNoSale = {}, onDone = { done++ }) }
        shows(SUBMITTED)
        compose.onNodeWithText("Start the call?").assertDoesNotExist()
        compose.onNodeWithText("OK").performClick()
        compose.waitUntil(5_000) { done == 1 }
        assertNull("the call ended on the phone", f.day.visitSession.current.value)
        assertTrue("no close written", runBlocking { f.db.captureDao().visitClosesOn(f.date) }.isEmpty())
    }

    @Test fun theSaleHostWordsASaleTheSalesSubmitRefused() {
        val v = f.openCall()
        f.day.visitSession.restore(v)
        f.refusedSale(v) { f.salesSubmitted() }
        var done = 0
        show { SaleHost(f.day, sunlight = false, onSunlight = {}, onNoSale = {}, onDone = { done++ }) }
        shows(SUBMITTED)
        compose.onNodeWithText("The sale could not be saved", substring = true).assertDoesNotExist()
        compose.onNodeWithText("OK").performClick()
        compose.waitUntil(5_000) { done == 1 }
        assertNull(f.day.visitSession.current.value)
        assertNull("cleared", f.day.sale.submitRefused.value)
        assertTrue(runBlocking { f.db.captureDao().memosOn(f.date) }.isEmpty())
    }

    @Test fun theNoSaleHostWordsACloseTheSalesSubmitRefused() {
        val v = f.openCall()
        f.day.visitSession.restore(v)
        f.salesSubmitted()
        var done = 0
        show { NoSaleHost(f.day, onDone = { done++ }) }
        compose.onNodeWithText("Shop closed").performClick()
        shows(SUBMITTED)
        assertEquals(0, done)
        compose.onNodeWithText("OK").performClick()
        compose.waitUntil(5_000) { done == 1 }
        assertNull("the call ended on the phone", f.day.visitSession.current.value)
        assertTrue("no close written", runBlocking { f.db.captureDao().visitClosesOn(f.date) }.isEmpty())
    }

    @Test fun theEditHostRefusesAnEditAfterTheSalesSubmitBeforeReadingAFix() {
        val v = f.openCall()
        val memo = f.creditMemo(v)
        f.salesSubmitted()
        var done = 0
        show { EditHost(f.day, memo, sunlight = false, onSunlight = {}, onDone = { done++ }) }
        shows(SUBMITTED)
        assertEquals("no fix read for an edit that cannot be saved", 0, f.fixReads)
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        assertEquals(1, done)
    }

    private companion object {
        const val LOCK_TITLE = "Day closed by Final Submit"
        const val SUBMITTED = "Today's sales are already submitted."
    }
}
