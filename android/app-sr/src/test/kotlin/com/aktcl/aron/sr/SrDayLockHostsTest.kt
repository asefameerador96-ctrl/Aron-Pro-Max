package com.aktcl.aron.sr

import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
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
 * tso-manual X4a fix round (checker medium "wiring", low "lockRefused shared"): the SR hosts where the Final Submit lock is
 * met, composed on a real [SrDay]: the sale's host shows the lock text only for its own refused call and OK ends that call
 * and goes Home; the edit host refuses a memo on a closed route before any fix is read; the skip host words a refused skip
 * and stores nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp")
class SrDayLockHostsTest {
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
     * Waits for the state the host reaches, not for a fixed time (D-QA-CI-03, CI run 42008323027: a 5 s wall-clock wait timed out
     * on a loaded runner): first the host's own work (the lock read on Room's executor) settles, then the composition settles,
     * then the dialog is asserted. The compose wait below is a hang guard after the work is known to be done.
     */
    private fun awaitRefusalDialog() {
        assertTrue("the host's work did not settle", f.work.awaitSettled())
        compose.waitForIdle()
        compose.waitUntil(30_000) { runCatching { compose.onNodeWithText("Day closed by Final Submit").assertExists() }.isSuccess }
    }

    @Test fun theSaleHostShowsItsOwnRefusalAndOkEndsTheCallAndGoesHome() {
        val v = f.openCall()
        f.day.visitSession.restore(v)
        f.refusedSale(v)
        var done = 0
        show { SaleHost(f.day, sunlight = false, onSunlight = {}, onNoSale = {}, onDone = { done++ }) }
        compose.onNodeWithText("Day closed by Final Submit").assertExists()
        compose.onNodeWithText("The sale could not be saved", substring = true).assertDoesNotExist()
        compose.onNodeWithText("OK").performClick()
        compose.waitUntil(5_000) { done == 1 }
        assertEquals("Home", 1, done)
        assertNull("the call ended on the phone", f.day.visitSession.current.value)
        assertNull(f.day.sale.lockRefused.value)
        assertTrue("no close the server would refuse", runBlocking { f.db.captureDao().visitClosesOn(f.date) }.isEmpty())
    }

    @Test fun anotherCallsRefusalIsNotShownOnThisCall() {
        val refusedCall = f.openCall()
        f.day.visitSession.restore(refusedCall)
        f.refusedSale(refusedCall) // the rep left while it was saving: the refusal of that call still stands
        f.day.visitSession.close()
        val next = f.openCall(outletId = 50002, routeId = f.other, seq = 2) // a valid call on an open route
        f.day.visitSession.restore(next)
        var done = 0
        show { SaleHost(f.day, sunlight = false, onSunlight = {}, onNoSale = {}, onDone = { done++ }) }
        compose.onNodeWithText("Day closed by Final Submit").assertDoesNotExist()
        compose.onNodeWithText("Do you want to start the call?").assertExists()
        assertEquals("the rep stays in the valid call", 0, done)
        assertEquals(next.visitUuid, f.day.visitSession.current.value?.visitUuid)
    }

    @Test fun theEditHostRefusesAMemoOnAClosedRouteBeforeReadingAFix() {
        val v = f.openCall()
        val memo = f.creditMemo(v)
        f.finalSubmitted()
        var done = 0
        show { EditHost(f.day, memo, sunlight = false, onSunlight = {}, onDone = { done++ }) }
        awaitRefusalDialog()
        assertEquals("no fix read for an edit that cannot be saved", 0, f.fixReads)
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        assertEquals(1, done)
    }

    @Test fun theSkipHostWordsARefusedSkipAndStoresNothing() {
        f.finalSubmitted()
        var done = 0
        show { SkipHost(f.day, f.outlet(id = 50003), onDone = { done++ }) }
        compose.onNodeWithText("Shop closed").performClick()
        awaitRefusalDialog()
        assertEquals("still on the skip until OK", 0, done)
        assertTrue(runBlocking { f.db.captureDao().visitSkipsOn(f.date) }.isEmpty())
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        assertEquals(1, done)
    }
}
