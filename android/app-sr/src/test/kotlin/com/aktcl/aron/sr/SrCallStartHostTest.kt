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
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.MemoEntity
import com.aktcl.aron.core.database.entity.MemoLineEntity
import com.aktcl.aron.core.database.repo.SaleCapture
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.feature.outlet.CallQuestionTags
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
import java.time.LocalDate

/**
 * SR manual s8 (D-SR-MAN-S4-01/04/05), composed on a real [SrDay] and the real Room store: the call-start screen names the outlet (also after a
 * relaunch, when no fix is held), "সেল ডাটা" reports itself to the frame, and the quantity popup's SDLW reads a memo exactly seven days old.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp")
class SrCallStartHostTest {
    @get:Rule val compose = hostComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var f: SrLockFixture

    @Before fun setUp() { Dispatchers.setMain(MainLooperImmediate.dispatcher); f = SrLockFixture(app, latePolicy = null) }
    @After fun tearDown() { try { f.close() } finally { Dispatchers.resetMain() } }

    private fun show(content: @Composable () -> Unit) {
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        f.own(ActivityScenario.launch(ComponentActivity::class.java)).onActivity { activity ->
            val ctx = AppLocale.wrap(activity, AppLanguage.EN)
            activity.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(AppLanguage.EN) { content() } } }
        }
        compose.waitForIdle()
    }

    @Test fun theCallStartScreenNamesTheOutletAfterARelaunchToo() {
        val v = f.openCall()
        f.day.visitSession.restore(v) // a relaunch: the session holds the visit and no fix
        show { SaleHost(f.day, sunlight = false, onSunlight = {}, onNoSale = {}, onDone = {}) }
        compose.onNodeWithTag(CallQuestionTags.OUTLET).assertExists()
        // the fixture's day holds no outlet row, so this is the fallback (the id); the label path is asserted in the next test
        compose.onNodeWithText("50001").assertExists()
        compose.onNodeWithText("Rahim Store 50001", substring = true).assertDoesNotExist()
        compose.onNodeWithTag(CallQuestionTags.QUESTION).assertExists()
        compose.onNodeWithTag(CallQuestionTags.PROCEED).performClick()
        compose.onNodeWithTag(CallQuestionTags.FIRST_BOX).assertExists()
    }

    @Test fun saleDataReportsItselfToTheFrameAndLeavesWithTheHost() {
        val v = f.openCall()
        f.day.visitSession.restore(v)
        val seen = mutableListOf<Boolean>()
        val on = androidx.compose.runtime.mutableStateOf(true)
        show { if (on.value) SaleHost(f.day, sunlight = false, onSunlight = {}, onNoSale = {}, onDone = {}, onSaleData = { seen += it }) }
        compose.onNodeWithTag(CallQuestionTags.HISTORY).performClick()
        compose.waitUntil(5_000) { seen.lastOrNull() == true }
        assertTrue(seen.last())
        // the host leaving composition (the sale moves on, or the rep goes back) reports false, so the frame title returns to বিক্রয়
        compose.runOnUiThread { on.value = false }
        compose.waitForIdle()
        compose.waitUntil(5_000) { seen.lastOrNull() == false }
        assertEquals("reports: $seen", false, seen.last())
    }

    @Test fun theCallOutletLabelIsThePickerLabelWhenTheDayHoldsTheOutletElseTheFallback() {
        assertEquals("Rahim Store 50001 (DHK-50001-01712345678-C)", f.day.sale.callOutletLabel(f.outlet(), 50001))
        assertEquals("50001", f.day.sale.callOutletLabel(null, 50001))
    }

    @Test fun sdlwAndPreviousDayReadThroughTheRoomStoreOverEightDays() {
        val v = f.openCall()
        val today = LocalDate.parse(f.date)
        // one memo exactly seven days back (outside Sale History's 7-day window, inside the 8-day stats window) and one three days back
        runBlocking {
            for ((back, qty) in listOf(7L to 3L, 3L to 5L)) {
                val m = f.meta(v.routeId).let { it.copy(businessDate = today.minusDays(back).toString()) }
                val memo = ClientIds.newUuid()
                f.day.capture.recordSale(SaleCapture(
                    MemoEntity(memo, m, v.visitUuid, v.outletId, "sr334001-s4-" + memo.take(4), "sale", m.capturedAt, m.businessDate, "outlet", 28_000L * qty, 0, 0, 0, 0, 28_000L * qty, 28_000L * qty, 0, false, null, 1, 0, 0),
                    listOf(MemoLineEntity(ClientIds.newUuid(), m, memo, 1, 105, "sale", qty, "dozen", 1, qty, "outlet", "2026-09-01", 28_000, 1, 28_000L * qty)),
                ))
            }
        }
        val stats = runBlocking { f.day.sale.skuStats(v.outletId) }[105]!!
        assertEquals("SDLW is the memo seven days back", 3L, stats.sdlw)
        assertEquals("the previous day is the latest earlier sale day", 5L, stats.previousDay)
        assertNull("ADS stays unknown until the bundle carries it", stats.adsHundredths)
        // Sale History keeps its seven days: the memo seven days back is not in it
        val dates = runBlocking { f.day.sale.saleHistory(v.outletId) }.days.map { it.businessDate }
        assertEquals(listOf(today.minusDays(3).toString()), dates)
    }
}
