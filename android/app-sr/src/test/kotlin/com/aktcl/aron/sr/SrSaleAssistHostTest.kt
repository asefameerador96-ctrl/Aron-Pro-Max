package com.aktcl.aron.sr

import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.printing.bt.PrinterState
import com.aktcl.aron.core.printing.bt.PrinterTransport
import com.aktcl.aron.core.printing.bt.PrinterTransportFactory
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * D-SR-MAN-S3-04 (amends S3-02): the PRODUCTION sale wiring. The real `SaleHost` and the real `SaleRoute` it opens after "Start the call?", inside the real
 * `SrFrame` with the VISIT screen's own `printerAssistFor(VISIT)` (the one holder of the sale): one connect of a saved printer gives one toast, entering
 * the sale gives none, and a reconnect while the sale is up gives exactly one more. Also the checked-in label of `SrDay` with a configured minute.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp")
class SrSaleAssistHostTest {
    @get:Rule val compose = hostComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private var f: SrLockFixture? = null
    private var opened = 0
    private val dropFirst = kotlinx.coroutines.CompletableDeferred<Unit>()

    /** A live link; the first one drops (read returns -1, as a switched-off printer does) when [dropFirst] completes. */
    private class Live(private val drop: kotlinx.coroutines.CompletableDeferred<Unit>?) : PrinterTransport {
        override suspend fun connect() {}
        override suspend fun write(bytes: ByteArray) {}
        override suspend fun read(): Int { if (drop == null) awaitCancellation() else { drop.await(); return -1 } }
        override fun close() {}
    }

    @Before fun setUp() { Dispatchers.setMain(MainLooperImmediate.dispatcher) }
    @After fun tearDown() { try { f?.close() } finally { Dispatchers.resetMain() } }

    @Test fun theRealSaleHostInTheRealVisitFrameToastsOnce() {
        ShadowToast.reset()
        val fx = SrLockFixture(app, latePolicy = null, printerTransports = PrinterTransportFactory { if (opened++ == 0) Live(dropFirst) else Live(null) }).also { f = it }
        val v = fx.openCall()
        fx.day.visitSession.restore(v)
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        fx.own(ActivityScenario.launch(ComponentActivity::class.java)).onActivity { activity ->
            val ctx = AppLocale.wrap(activity, AppLanguage.EN)
            activity.setContent {
                CompositionLocalProvider(LocalContext provides ctx, androidx.activity.compose.LocalActivityResultRegistryOwner provides activity) {
                    AronTheme(AppLanguage.EN) {
                        SrFrame("sale", null, null, null, printer = fx.day.printerManager, printerAssist = printerAssistFor(SrScreen.VISIT)) {
                            SaleHost(fx.day, sunlight = false, onSunlight = {}, onNoSale = {}, onDone = {})
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.waitUntil(10_000) { fx.day.printerManager.state.value == PrinterState.Connected }
        repeat(20) { compose.mainClock.advanceTimeByFrame() }
        assertEquals("one connect, one toast at the VISIT frame", 1, ShadowToast.shownToastCount())
        // go on into the real sale (Yes, then the AV/KV/POSM steps are skipped without content): SaleRoute holds the printer too and must not toast again
        compose.onNodeWithText("Yes").performClick()
        // SaleRoute really composed (its entry screen's "Proceed" button), before the count is read
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("Proceed").assertExists() }.isSuccess }
        repeat(30) { compose.mainClock.advanceTimeByFrame() }
        assertEquals("the sale screen does not toast a second time", 1, ShadowToast.shownToastCount())
        // mid-sale: the printer is switched off (the link drops), then it comes back: exactly one more toast
        dropFirst.complete(Unit)
        compose.waitUntil(10_000) { fx.day.printerManager.state.value != PrinterState.Connected }
        compose.waitUntil(30_000) { fx.day.printerManager.state.value == PrinterState.Connected }
        repeat(30) { compose.mainClock.advanceTimeByFrame() }
        compose.onNodeWithText("Proceed").assertExists() // still in the sale
        assertEquals("a reconnect while the sale is up gives exactly one more toast", 2, ShadowToast.shownToastCount())
    }

    @Test fun theCheckoutLabelReadsTheConfiguredMinute() {
        val fx = SrLockFixture(app, latePolicy = null, checkoutMinutes = 18 * 60 + 30).also { f = it }
        assertEquals("18:30", fx.day.checkoutAtLabel())
    }

    @Test fun theCheckoutLabelDefaultsToSeventeenHundred() {
        val fx = SrLockFixture(app, latePolicy = null).also { f = it }
        assertEquals("17:00", fx.day.checkoutAtLabel())
    }
}
