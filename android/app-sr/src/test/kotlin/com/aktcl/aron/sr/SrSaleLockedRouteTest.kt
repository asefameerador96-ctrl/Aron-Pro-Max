package com.aktcl.aron.sr

import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import org.robolectric.Shadows.shadowOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.feature.sale.domain.QcEntry
import com.aktcl.aron.feature.sale.domain.SaleNotice
import com.aktcl.aron.feature.sale.ui.QcTags
import com.aktcl.aron.feature.sale.ui.SaleRoute
import com.aktcl.aron.feature.sale.ui.SaleTags
import com.aktcl.aron.feature.sale.ui.SaleViewModel
import com.aktcl.aron.rules.QtyUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * P-SR1b (lead, locked-sale crash and QC restoration): the REAL `SaleRoute` with the real draft, the real flow and a real `SaleViewModel`, inside a
 * `StateRestorationTester` (the saved state is dropped and restored as after a rotation or a process death; the view model survives, as in the app).
 *  1. QC submitted, restoration: the route reopens on REVIEW (its stage is not saved and starts on Entry), and a stale quantity or slide change from a screen that
 *     was on the entry list does not crash: the draft is unchanged and the locked line shows.
 *  2. An OPEN QC page is not restored: after the restoration QC opens on step 1 (this fails on a `rememberSaveable` open SKU: the saved key is consumed by the next QC screen).
 *  3. Typed QC that was never submitted is dropped when the sale is started again, and the rep is told in one line.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp")
class SrSaleLockedRouteTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    // the rule starts its activity before any @Before runs, so the activity is registered while the test class is built
    init { shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java)) }
    @get:Rule val compose = createComposeRule(StandardTestDispatcher())
    private var f: SrLockFixture? = null

    @Before fun setUp() { Dispatchers.setMain(MainLooperImmediate.dispatcher) }
    @After fun tearDown() { try { f?.close() } finally { Dispatchers.resetMain() } }

    private class Sale(val fx: SrLockFixture, val vm: SaleViewModel, val visit: com.aktcl.aron.feature.sale.domain.SaleVisit)

    private fun sale(): Sale {
        val fx = SrLockFixture(app, latePolicy = null).also { f = it }
        fx.stockedCatalog()
        val v = fx.openCall()
        fx.day.visitSession.restore(v)
        val vm = fx.saleViewModel()
        runBlocking { fx.day.sale.prepare() }
        val visit = fx.day.sale.saleVisit(v)
        vm.start(visit)
        until { vm.state.value.draft != null }
        return Sale(fx, vm, visit)
    }

    /** Polls like `waitUntil` but also runs the main looper: the view model's coroutines (Room reads resumed from another thread) are posted there. */
    private fun until(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "condition still not satisfied after 10000 ms" }
            shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeByFrame()
            Thread.sleep(10)
        }
    }

    private fun show(s: Sale): StateRestorationTester {
        val tester = StateRestorationTester(compose)
        tester.setContent {
            val ctx = AppLocale.wrap(LocalContext.current, AppLanguage.EN)
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(AppLanguage.EN) {
                    SaleRoute(s.vm, s.fx.day.printerManager, s.fx.day.sale::skuName, onFinished = {}, zeroSaleReasonPicker = true, quantityPopup = true)
                }
            }
        }
        compose.waitForIdle()
        return tester
    }

    private fun openQc() {
        until { compose.onAllNodesWithText("Proceed").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Proceed").performClick()
        until { compose.onAllNodesWithText("Product QC").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Product QC").performClick()
        // step 1 (cards) or an old page (boxes): whichever shows, the assertions below read it
        until { compose.onAllNodesWithTag(QcTags.CARD).fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithTag(QcTags.QTY_FIELD).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun aLockedSaleReopensOnReviewAfterARestorationAndAStaleChangeDoesNotCrash() {
        val s = sale()
        s.vm.setQuantity(105, 2, QtyUnit.DOZEN); until { s.vm.state.value.draft?.lines?.size == 1 }
        s.vm.setQc(QcEntry(105, "damaged_crushed", "MFC", 1)); until { s.vm.state.value.draft?.qc?.size == 1 }
        s.vm.completeQc(); until { s.vm.state.value.draft?.qcCompleted == true }
        val tester = show(s)
        until { compose.onAllNodesWithText("Print").fetchSemanticsNodes().isNotEmpty() } // Review, not the entry list
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        until { compose.onAllNodesWithText("Print").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Proceed").assertCountEquals(0)
        // a change from a screen that was on the entry list: refused, nothing thrown, the draft unchanged
        val before = s.vm.state.value.draft
        s.vm.setQuantity(105, 5, QtyUnit.DOZEN); s.vm.setSlide(105, 1)
        until { s.vm.state.value.notice == SaleNotice.Locked }
        assertEquals(before, s.vm.state.value.draft)
        until { compose.onAllNodesWithTag(SaleTags.NOTICE).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(SaleTags.NOTICE).assertExists()
    }

    @Test fun anOpenQcPageIsNotRestored() {
        val s = sale()
        s.vm.setQuantity(105, 2, QtyUnit.DOZEN); until { s.vm.state.value.draft?.lines?.size == 1 }
        val tester = show(s)
        openQc()
        compose.onAllNodesWithTag(QcTags.CARD)[0].performClick()
        until { compose.onAllNodesWithTag(QcTags.QTY_FIELD).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag(QcTags.QTY_FIELD).assertCountEquals(6) // step 2 is open
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        openQc()
        compose.onAllNodesWithTag(QcTags.QTY_FIELD).assertCountEquals(0) // step 1, not the saved page
    }

    @Test fun typedQcThatWasNeverSubmittedIsDroppedOnStartWithAOneLineNotice() {
        val s = sale()
        s.vm.setQuantity(105, 2, QtyUnit.DOZEN); until { s.vm.state.value.draft?.lines?.size == 1 }
        s.vm.setQc(QcEntry(105, "damaged_crushed", "MFC", 1)); until { s.vm.state.value.draft?.qc?.size == 1 }
        s.vm.start(s.visit) // the screen is opened again (a rotation): the typed QC was never submitted
        until { s.vm.state.value.notice == SaleNotice.QcDropped }
        assertEquals(emptyList<QcEntry>(), s.vm.state.value.draft!!.qc)
        show(s)
        until { compose.onAllNodesWithTag(SaleTags.NOTICE).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("QC you typed was not submitted, so it was cleared. Open QC again to enter it.").assertExists()
    }
}
