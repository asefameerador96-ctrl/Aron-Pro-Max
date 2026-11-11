package com.aktcl.aron.amo

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
 * tso-manual X4a follow-up (R-W-22, R-W-23): the AMO hosts composed on a real [AmoDay]. The Stock screen in a call on a route
 * closed by Final Submit shows the lock text and queues nothing; after the AMO's own supervisor-day Sales Submit the sale
 * and the edit show feature-dayclose's submit text ("Today's sales are already submitted.") and never throw.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp")
class AmoRefusalHostsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var f: AmoLockFixture

    @Before fun setUp() {
        Dispatchers.setMain(MainLooperImmediate.dispatcher)
        // owner decision 2026-11-04: the phone locks only under quarantine or reject (default accept_and_flag); this class tests the lock
        f = AmoLockFixture(app, latePolicy = "quarantine")
    }

    @After fun tearDown() {
        // the screen goes first, then the day's work stops and the database closes (AmoLockFixture.close), then Main resets
        try { f.close() } finally { Dispatchers.resetMain() }
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

    private fun shows(text: String) = compose.waitUntil(5_000) { runCatching { compose.onNodeWithText(text).assertExists() }.isSuccess }

    private fun queued(type: String) = runBlocking { f.db.outboxDao().committedCounts(f.date) }.filter { it.recordType == type }.sumOf { it.count }

    @Test fun theStockScreenInACallOnAFinalRouteShowsTheLockTextAndQueuesNothing() {
        f.catalogOnly()
        val v = f.controlCall()
        f.day.visitSession.restore(v); f.day.routeId = v.routeId
        runBlocking { f.day.stockLoad() }.setEntered(105, 3)
        f.finalSubmitted()
        show { AmoStockHost(f.day) }
        awaitStockLoaded()
        compose.onNodeWithTag(com.aktcl.aron.feature.stock.StockTags.SAVE).performClick()
        shows(LOCK_TITLE)
        compose.onNodeWithText("Could not save", substring = true).assertDoesNotExist()
        assertTrue(runBlocking { f.db.captureDao().stockOn(f.date) }.isEmpty())
        assertEquals(0, queued("stock_movement")); assertEquals(0, queued("print_event"))
        assertNull(f.day.stockAttempt.value)
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText(LOCK_TITLE).assertDoesNotExist()
    }

    @Test fun theSaleHostWordsASaleTheSupervisorDaySubmitRefused() {
        val v = f.controlCall()
        f.day.visitSession.restore(v); f.day.routeId = v.routeId
        f.refusedSale(v) { f.supervisorDaySubmitted() }
        var ended = 0
        var menu = 0
        show { AmoSaleHost(f.day, onDone = { menu++ }, onCallEnded = { ended++ }) }
        shows(SUBMITTED)
        compose.onNodeWithText(LOCK_TITLE).assertDoesNotExist()
        compose.onNodeWithText("OK").performClick()
        compose.waitUntil(5_000) { ended == 1 }
        assertEquals("back to the outlet list, not the call menu", 0, menu)
        assertNull(f.day.visitSession.current.value)
        assertNull(f.day.sale.submitRefused.value)
        assertTrue(runBlocking { f.db.captureDao().visitClosesOn(f.date) }.isEmpty())
    }

    @Test fun theEditHostRefusesAnEditAfterTheSupervisorDaySubmitBeforeReadingAFix() {
        val memo = f.creditMemo(f.controlCall())
        f.supervisorDaySubmitted()
        var done = 0
        show { AmoEditHost(f.day, memo, onDone = { done++ }) }
        shows(SUBMITTED)
        assertEquals("no fix read", 0, f.fixReads)
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        assertEquals(1, done)
    }

    private companion object {
        const val LOCK_TITLE = "Routes closed by Final Submit"
        const val SUBMITTED = "Today's sales are already submitted."
    }
}
