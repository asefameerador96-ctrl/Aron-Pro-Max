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
import com.aktcl.aron.core.database.repo.AttendanceRefusal
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.feature.attendance.CheckInFirstTags
import com.aktcl.aron.feature.outlet.OpenVisit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * D-AGATE-18 (B1): every way a call ends on the SR writes the outcome the call's CONTENT decides (saved memo with lines = sold; saved zero-sale or QC-only memo = zero sale; survey only or
 * nothing = abandoned), through the REAL buttons: the check-in gate's "Close the open call" ([SrCheckInGate]) and the back arrow of a restored call ([SaleHost]), on a real [SrDay] and Room.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp")
class SrCallCloseExitsTest {
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

    private val survey = com.aktcl.aron.core.database.entity.SurveyEntity(1, 1, "S", null)
    private val q = com.aktcl.aron.feature.outlet.SurveyQuestion(1, com.aktcl.aron.feature.outlet.AnswerType.BOOL, "q1", true, false)

    /** An open call holding [kind] of content, restored into the session as after a relaunch. */
    private fun callWith(kind: String): OpenVisit {
        val v = f.openCall()
        when (kind) {
            "sold" -> f.creditMemo(v)
            "zero" -> f.qcLineOn(v)
            "survey" -> runBlocking { f.day.saveSurveyResult(v.visitUuid, survey, listOf(com.aktcl.aron.feature.outlet.SurveyResponseRow(com.aktcl.aron.core.common.ClientIds.newUuid(), v.visitUuid, 1, 1, q, com.aktcl.aron.feature.outlet.SurveyAnswer(bool = true)))) }
        }
        f.day.visitSession.restore(v)
        return v
    }

    private fun close(v: OpenVisit) = runBlocking { f.db.captureDao().visitClosesOn(f.date) }.filter { it.visitClientUuid == v.visitUuid }

    /** kind -> (outcome, is_zero_sale) the classification must write, whatever the exit. */
    private val expected = mapOf("sold" to ("sold" to false), "zero" to ("zero_sale_stock_ok" to true), "survey" to ("abandoned" to true), "empty" to ("abandoned" to true))

    private fun gateClose(kind: String) {
        val v = callWith(kind)
        show { SrCheckInGate(f.day, AttendanceRefusal.EARLIER_CALL_OPEN, canCloseCall = true, earlierOutlet = null, onCheckIn = {}, onCallClosed = {}) }
        compose.onNodeWithTag(CheckInFirstTags.CLOSE_CALL).performClick()
        compose.waitUntil(20_000) { close(v).isNotEmpty() }
        val c = close(v).single()
        assertEquals("$kind: gate Close outcome", expected.getValue(kind).first, c.outcomeCode); assertEquals("$kind: is_zero_sale", expected.getValue(kind).second, c.isZeroSale)
        compose.waitUntil(10_000) { f.day.visitSession.current.value == null } // the session closes right after the close is stored
    }

    private fun backPress(kind: String) {
        val v = callWith(kind)
        show { SaleHost(f.day, sunlight = false, onSunlight = {}, onNoSale = {}, onDone = {}) }
        compose.onNodeWithText("Go back").performClick()
        compose.waitUntil(20_000) { close(v).isNotEmpty() }
        val c = close(v).single()
        assertEquals("$kind: back press outcome", expected.getValue(kind).first, c.outcomeCode); assertEquals("$kind: is_zero_sale", expected.getValue(kind).second, c.isZeroSale)
    }

    @Test fun gateCloseOfACallWithASavedSaleWritesSold() = gateClose("sold")
    @Test fun gateCloseOfACallWithAZeroSaleMemoWritesAZeroSale() = gateClose("zero")
    @Test fun gateCloseOfASurveyOnlyCallWritesAbandoned() = gateClose("survey")
    @Test fun gateCloseOfAnEmptyCallWritesAbandoned() = gateClose("empty")
    @Test fun backPressOfARestoredCallWithASavedSaleWritesSold() = backPress("sold")
    @Test fun backPressOfARestoredCallWithAZeroSaleMemoWritesAZeroSale() = backPress("zero")
    @Test fun backPressOfARestoredSurveyOnlyCallWritesAbandoned() = backPress("survey")
    @Test fun backPressOfARestoredEmptyCallWritesAbandoned() = backPress("empty")

    /** The phone's own 04:00 end writes exactly the same (the third way a call ends). */
    @Test fun thePhonesEndWritesTheSameForEveryKind() {
        val vs = listOf("sold", "zero", "survey", "empty").mapIndexed { i, k ->
            val v = f.openCall(outletId = 50001L + i, seq = i + 1)
            when (k) { "sold" -> f.creditMemo(v); "zero" -> f.qcLineOn(v); "survey" -> runBlocking { f.day.saveSurveyResult(v.visitUuid, survey, listOf(com.aktcl.aron.feature.outlet.SurveyResponseRow(com.aktcl.aron.core.common.ClientIds.newUuid(), v.visitUuid, 1, 1, q, com.aktcl.aron.feature.outlet.SurveyAnswer(bool = true)))) } }
            k to v
        }
        vs.forEach { (k, v) ->
            f.day.visitSession.restore(v)
            runBlocking { f.day.sale.closeCall(phoneEnd = true) }
            val c = close(v).single()
            assertEquals("$k: phone end", expected.getValue(k).first, c.outcomeCode); assertEquals(expected.getValue(k).second, c.isZeroSale)
        }
    }

    /** A close that cannot be written keeps the call and tells the rep: no crash. (The visit row is gone, so the close throws a plain error, not a day refusal.) */
    @Test fun aFailingCloseKeepsTheCallAndTellsTheRep() {
        val v = callWith("empty")
        runBlocking { f.db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = OFF"); f.db.openHelper.writableDatabase.execSQL("DELETE FROM visit WHERE client_uuid = '${v.visitUuid}'") }
        show { SrCheckInGate(f.day, AttendanceRefusal.EARLIER_CALL_OPEN, canCloseCall = true, earlierOutlet = null, onCheckIn = {}, onCallClosed = {}) }
        compose.onNodeWithTag(CheckInFirstTags.CLOSE_CALL).performClick()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("The call could not be closed").assertExists() }.isSuccess }
        assertNotNull("the call is kept", f.day.visitSession.current.value)
    }

    /** One lock for the rep's close and the phone's close: two closes racing write ONE visit_close. */
    @Test fun twoClosesOfOneCallWriteOneClose() {
        val v = callWith("sold")
        runBlocking {
            kotlinx.coroutines.coroutineScope {
                val a = async(Dispatchers.Default) { f.day.sale.closeCall() }
                val b = async(Dispatchers.Default) { f.day.sale.closeCall(phoneEnd = true) }
                a.await(); b.await()
            }
        }
        assertEquals(1, close(v).size)
    }

    // ---- D-AGATE-19 (step-5 closing round)

    /** Low 1: the phone ended the call (04:00) while the sale screen was open; the rep's Save then finds a closed call: refused inside the lock, NO memo stored, the close stays `abandoned`. */
    @Test fun aSaveAfterThePhoneClosedTheCallIsRefusedAndStoresNoMemo() {
        val v = f.openCall(); f.day.visitSession.restore(v)
        val vm = f.refusedSale(v) { runBlocking { f.day.sale.closeCall(phoneEnd = true) } } // the close lands between the draft and the Save
        assertEquals("the save stopped before the print step", com.aktcl.aron.core.printing.flow.SaveAndPrint.Step.SaveFailed, vm.saveAndPrint.step.value)
        assertTrue("no memo stored on a call the phone closed as abandoned", runBlocking { f.db.captureDao().memosOn(f.date) }.none { it.visitClientUuid == v.visitUuid })
        val c = close(v).single(); assertEquals("abandoned", c.outcomeCode); assertEquals(true, c.isZeroSale)
    }

    /** Low 1, the other order: the Save commits first, then the phone's end finds the memo and writes `sold`. */
    @Test fun aSaveThatWonTheLockIsClosedAsSold() {
        val v = f.openCall(); f.day.visitSession.restore(v)
        f.savedSale(v) { }
        runBlocking { f.day.sale.closeCall(phoneEnd = true) }
        val c = close(v).single(); assertEquals("sold", c.outcomeCode); assertEquals(false, c.isZeroSale)
    }

    /** Low 2: a persistent write error on a NoSale reason row is caught: the call is kept and the existing error text shows (no crash). */
    @Test fun aFailingNoSaleReasonKeepsTheCallAndShowsTheErrorNotACrash() {
        val v = callWith("empty")
        runBlocking { f.db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = OFF"); f.db.openHelper.writableDatabase.execSQL("DELETE FROM visit WHERE client_uuid = '${v.visitUuid}'") }
        var done = 0
        show { NoSaleHost(f.day, onDone = { done++ }) }
        compose.onNodeWithText("Shop closed").performClick()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("The call could not be closed").assertExists() }.isSuccess }
        assertEquals(0, done); assertNotNull("the call is kept", f.day.visitSession.current.value)
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("The call could not be closed").assertDoesNotExist()
    }

    /** Low 3: the route-day refuses the gate's Close (the rep's own Sales Submit stands): nothing is written, the call ends on the phone, and the rep is TOLD before Home. */
    @Test fun theGateCloseTellsTheRepWhenTheRouteDayRefusedTheClose() {
        val v = callWith("empty")
        f.salesSubmitted()
        var closed = 0
        show { SrCheckInGate(f.day, AttendanceRefusal.EARLIER_CALL_OPEN, canCloseCall = true, earlierOutlet = null, onCheckIn = {}, onCallClosed = { closed++ }) }
        compose.onNodeWithTag(CheckInFirstTags.CLOSE_CALL).performClick()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("The call was ended on the phone").assertExists() }.isSuccess }
        compose.onNodeWithText("Today's sales are already submitted", substring = true).assertExists()
        assertEquals("not sent Home in silence", 0, closed)
        assertTrue("nothing was written", close(v).isEmpty())
        compose.onNodeWithText("OK").performClick()
        compose.waitUntil(5_000) { closed == 1 }
        assertNull("the call ended on the phone", f.day.visitSession.current.value)
    }

    /** Item 6a: the sale screen's own FINISH exit (Save, print answered No, the saved message's OK) closes the call through the one classification: a saved sale is `sold`. */
    @Test fun theSaleScreensFinishExitWritesSoldThroughTheRealButtons() {
        val v = f.openCall(); f.day.visitSession.restore(v)
        f.stockedCatalog()
        runBlocking {
            f.day.sale.prepare()
            f.day.sale.flow.start(f.day.sale.saleVisit(v))
            f.day.sale.flow.setQuantity(105, 2, com.aktcl.aron.rules.QtyUnit.DOZEN)
        }
        var done = 0
        show { SaleHost(f.day, sunlight = false, onSunlight = {}, onNoSale = {}, onDone = { done++ }) }
        compose.onNodeWithText("Yes").performClick()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("Proceed").assertExists() }.isSuccess }
        compose.onNodeWithText("Proceed").performClick()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("Print").assertExists() }.isSuccess }
        compose.onNodeWithText("Print").performClick()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("The sale will be saved").assertExists() }.isSuccess }
        compose.onNodeWithText("Yes").performClick()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("Do you want to print this sale?").assertExists() }.isSuccess }
        compose.onNodeWithText("No").performClick()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("The sale was saved").assertExists() }.isSuccess }
        compose.onNodeWithText("OK").performClick()
        compose.waitUntil(20_000) { close(v).isNotEmpty() }
        val c = close(v).single(); assertEquals("sold", c.outcomeCode); assertEquals(false, c.isZeroSale)
        compose.waitUntil(10_000) { done == 1 }
    }
}
