package com.aktcl.aron.amo

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
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
 * tso-manual X4a fix round (checker medium "wiring", low "lockRefused shared"): the AMO sale and edit hosts composed on a
 * real [AmoDay]. The sale's host shows the lock text only for its own refused call, and OK ends that call and goes back to
 * the outlet list; another call's refusal never shows on a valid call. The edit host refuses a memo on a closed route
 * before any fix is read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp")
class AmoDayLockHostsTest {
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

    @Test fun theSaleHostShowsItsOwnRefusalAndOkEndsTheCall() {
        val v = f.controlCall()
        f.day.visitSession.restore(v); f.day.routeId = v.routeId
        f.refusedSale(v)
        var ended = 0
        var menu = 0
        show { AmoSaleHost(f.day, onDone = { menu++ }, onCallEnded = { ended++ }) }
        compose.onNodeWithText("Routes closed by Final Submit").assertExists()
        compose.onNodeWithText("OK").performClick()
        compose.waitUntil(5_000) { ended == 1 }
        assertEquals("back to the outlet list, not the call menu", 0, menu)
        assertNull(f.day.visitSession.current.value)
        assertNull(f.day.sale.lockRefused.value)
        assertTrue(runBlocking { f.db.captureDao().visitClosesOn(f.date) }.isEmpty())
    }

    @Test fun anotherCallsRefusalIsNotShownOnThisCall() {
        val refusedCall = f.controlCall()
        f.day.visitSession.restore(refusedCall); f.day.routeId = refusedCall.routeId
        f.refusedSale(refusedCall) // the AMO left while it was saving: that call's refusal still stands
        f.day.visitSession.close()
        val next = f.controlCall(outletId = 50002, routeId = f.other, seq = 2) // a valid call on an open route
        f.day.visitSession.restore(next); f.day.routeId = next.routeId
        var ended = 0
        show { AmoSaleHost(f.day, onDone = {}, onCallEnded = { ended++ }) }
        compose.onNodeWithText("Routes closed by Final Submit").assertDoesNotExist()
        assertEquals(0, ended)
        assertEquals(next.visitUuid, f.day.visitSession.current.value?.visitUuid)
    }

    private fun shown(text: String) = runCatching { compose.onNodeWithText(text, substring = true).assertExists() }.isSuccess

    /**
     * Fix round (checker low, D-X4a-06 on the AMO): the memo detail's Edit follows the lock when it lands while the memo is
     * on screen. The banner's refresh of the day lock (it runs on every applied push pull) re-checks Edit; before the fix
     * the check was keyed on Mark Paid's own state, which never changed on the AMO outside a call.
     */
    @Test fun editOnTheMemoDetailFollowsTheLockThatLandsWhileItIsOnScreen() {
        // test-only race (CI run 42902438170): under the class's UnconfinedTestDispatcher a Main-dispatched resume can run on the Room IO thread that
        // finished a query, and the Compose test Recomposer then applied a frame there (CalledFromWrongThreadException). This test needs no eager Main
        // (it polls with waitUntil), so it uses the Looper-backed Main that never leaves the UI thread (D-AMO-CALLS-16).
        Dispatchers.setMain(MainLooperImmediate.dispatcher)
        f.zone() // the banner counts the zone's closed routes
        f.creditMemo(f.controlCall())
        show { AmoMemoHost(f.day, onEdit = {}) }
        // Memo find (screenshot 42): route, retailer, then the Memo tile lists the retailer's memos
        fun tagged(prefix: String) = androidx.compose.ui.test.SemanticsMatcher("tag starts with $prefix") { n -> n.config.contains(androidx.compose.ui.semantics.SemanticsProperties.TestTag) && n.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag].startsWith(prefix) }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("amo_cp_route").assertExists() }.isSuccess }
        compose.onNodeWithTag("amo_cp_route").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(tagged("amo_cp_route_")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("amo_cp_route_" + f.route).performClick()
        compose.onNodeWithTag("amo_cp_outlet").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(tagged("amo_cp_outlet_")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("amo_cp_outlet_50001").performClick()
        compose.onNodeWithTag("amo_mf_tile").performClick()
        compose.waitUntil(5_000) { shown("Rahim Store 50001") }
        compose.onNodeWithText("Rahim Store 50001", substring = true).performClick()
        compose.waitUntil(5_000) { shown("Mark paid") }
        compose.onNodeWithText("Final Submit is done today", substring = true).assertDoesNotExist()
        f.finalSubmitted()
        runBlocking { f.day.refreshLock() } // what amoDayLockBanners does on the push's pull
        compose.waitUntil(5_000) { shown("Final Submit is done today") }
    }

    @Test fun theEditHostRefusesAMemoOnAClosedRouteBeforeReadingAFix() {
        val memo = f.creditMemo(f.controlCall())
        f.finalSubmitted()
        var done = 0
        show { AmoEditHost(f.day, memo, onDone = { done++ }) }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Routes closed by Final Submit").assertExists() }.isSuccess }
        assertEquals("no fix read for an edit that cannot be saved", 0, f.fixReads)
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        assertEquals(1, done)
    }
}
