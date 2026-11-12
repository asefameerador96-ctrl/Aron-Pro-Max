package com.aktcl.aron.sr

import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.printing.bt.PrinterManager
import com.aktcl.aron.core.printing.bt.PrinterState
import com.aktcl.aron.core.printing.bt.PrinterTransport
import com.aktcl.aron.core.printing.bt.PrinterTransportFactory
import com.aktcl.aron.core.printing.bt.SavedPrinter
import com.aktcl.aron.core.printing.bt.SavedPrinterStore
import com.aktcl.aron.core.printing.ui.HoldPrinter
import com.aktcl.aron.core.ui.AronTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** D-SR-MAN-S3-02 (lead review of be15cd1a8): the permission prompt and the "printer connected" toast come from exactly one place per SR screen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SrPrinterAssistTest {
    @get:Rule val compose = hostComposeRule()

    private class Store : SavedPrinterStore {
        var p: SavedPrinter? = SavedPrinter("00:11:22:33:44:55", "MP-58N")
        override fun load() = p
        override fun save(printer: SavedPrinter?) { p = printer }
    }
    private class OkTransport : PrinterTransport {
        override suspend fun connect() {}
        override suspend fun write(bytes: ByteArray) {}
        override suspend fun read(): Int = kotlinx.coroutines.awaitCancellation() // a live link: -1 would read as a dropped one
        override fun close() {}
    }

    private fun manager() = PrinterManager(PrinterTransportFactory { OkTransport() }, Store(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

    /** The host rule runs queued work one frame per poll (HostComposeRule.kt): wait for the link, then advance frames so the effects ran. */
    private fun settle(m: PrinterManager) {
        compose.waitForIdle()
        compose.waitUntil(5_000) { m.state.value == PrinterState.Connected }
        repeat(10) { compose.mainClock.advanceTimeByFrame() }
    }

    @Test fun onlyStockFrameAssists() {
        SrScreen.entries.forEach { assertEquals(it.name, it == SrScreen.STOCK, printerAssistFor(it)) }
    }

    @Test fun theSalePathToastsOnceWhenTheVisitFrameAndSaleRouteBothHoldThePrinter() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, ComponentActivity::class.java))
        ShadowToast.reset()
        val m = manager()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    AronTheme(AppLanguage.BN) {
                        // the VISIT frame (printerAssistFor(VISIT) is false) around SaleRoute's own HoldPrinter(assist = true)
                        SrFrame("sale", null, null, null, printer = m, printerAssist = printerAssistFor(SrScreen.VISIT)) { HoldPrinter(m, assist = true) }
                    }
                }
            }
            settle(m)
            assertEquals("one connect, one toast", 1, ShadowToast.shownToastCount())
            // the test activity runs in English; the Bangla text of the same resource is pinned below
            assertEquals("Printer connected", ShadowToast.getTextOfLatestToast())
            assertEquals("প্রিন্টার কানেক্ট করা হয়েছে", com.aktcl.aron.core.ui.AppLocale.wrap(context, AppLanguage.BN).getString(com.aktcl.aron.core.printing.R.string.printer_connected))
        }
    }

    @Test fun theStockFrameAloneToastsOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, ComponentActivity::class.java))
        ShadowToast.reset()
        val m = manager()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { AronTheme(AppLanguage.BN) { SrFrame("stock", null, null, null, printer = m, printerAssist = printerAssistFor(SrScreen.STOCK)) {} } } }
            settle(m)
            assertEquals(1, ShadowToast.shownToastCount())
        }
    }

    @Test fun theMemoFrameNeverToasts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, ComponentActivity::class.java))
        ShadowToast.reset()
        val m = manager()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { AronTheme(AppLanguage.BN) { SrFrame("memo", null, null, null, printer = m, printerAssist = printerAssistFor(SrScreen.MEMO)) {} } } }
            settle(m)
            assertEquals(0, ShadowToast.shownToastCount())
        }
    }

    @Test fun theNearbyPermissionPromptIsRememberedAcrossVisits() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(com.aktcl.aron.core.printing.ui.PrinterAssistPrompt.asked(context))
        com.aktcl.aron.core.printing.ui.PrinterAssistPrompt.markAsked(context)
        assertTrue(com.aktcl.aron.core.printing.ui.PrinterAssistPrompt.asked(context)) // a new screen entry (new composition) reads this, not saved state
    }
}
