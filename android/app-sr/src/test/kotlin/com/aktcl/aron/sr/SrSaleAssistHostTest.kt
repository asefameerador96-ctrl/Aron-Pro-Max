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
 * D-SR-MAN-S3-02 (second review, low 1): the PRODUCTION sale wiring. The real `SaleHost` (which calls the real `SaleRoute(printerAssist = true)`)
 * inside the real `SrFrame` with the VISIT screen's own `printerAssistFor(VISIT)`: one connect of a saved printer gives one toast.
 * And the checked-in label of `SrDay` with a configured minute (low 5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp")
class SrSaleAssistHostTest {
    @get:Rule val compose = hostComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private var f: SrLockFixture? = null

    private class Live : PrinterTransport {
        override suspend fun connect() {}
        override suspend fun write(bytes: ByteArray) {}
        override suspend fun read(): Int = awaitCancellation()
        override fun close() {}
    }

    @Before fun setUp() { Dispatchers.setMain(MainLooperImmediate.dispatcher) }
    @After fun tearDown() { try { f?.close() } finally { Dispatchers.resetMain() } }

    @Test fun theRealSaleHostInTheRealVisitFrameToastsOnce() {
        ShadowToast.reset()
        val fx = SrLockFixture(app, latePolicy = null, printerTransports = PrinterTransportFactory { Live() }).also { f = it }
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
        repeat(30) { compose.mainClock.advanceTimeByFrame() }
        assertEquals("the sale screen does not toast a second time", 1, ShadowToast.shownToastCount())
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
