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

    @Test fun onlyStockAndVisitFramesAssist() {
        SrScreen.entries.forEach { assertEquals(it.name, it == SrScreen.STOCK || it == SrScreen.VISIT, printerAssistFor(it)) }
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
            scenario.onActivity { it.setContent { AronTheme(AppLanguage.BN) { SrFrame("memo", null, null, null, printer = m) {} /* the frame default, as every frame but STOCK and VISIT uses it (MEMO, SUMMARY, ...) */ } } }
            settle(m)
            assertEquals(0, ShadowToast.shownToastCount())
        }
    }

    /** The production call sites, read from source: SrApp passes `printerAssist` only through printerAssistFor (STOCK and VISIT frames); the SR sale host does NOT pass it to SaleRoute (one holder per screen). */
    @Test fun productionCallSitesAreWiredAsDocumented() {
        fun src(vararg candidates: String) = candidates.map { java.io.File(it) }.first { it.exists() }.readText()
        fun code(t: String) = t.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("//[^\\n]*"), "") // block comments, KDoc and inline // comments
        val srApp = code(src("src/main/kotlin/com/aktcl/aron/sr/SrApp.kt", "app-sr/src/main/kotlin/com/aktcl/aron/sr/SrApp.kt"))
        val assists = Regex("printerAssist = (printerAssistFor\\(SrScreen\\.\\w+\\)|\\w+)").findAll(srApp).map { it.groupValues[1] }.filter { it != "printerAssist" }.toList()
        assertEquals(listOf("printerAssistFor(SrScreen.STOCK)", "printerAssistFor(SrScreen.VISIT)"), assists)
        assertTrue(srApp.contains("HoldPrinter(printer, assist = printerAssist)"))
        val hosts = code(src("src/main/kotlin/com/aktcl/aron/sr/SrSaleHosts.kt", "app-sr/src/main/kotlin/com/aktcl/aron/sr/SrSaleHosts.kt"))
        assertFalse("the SR sale does not make a second holder", hosts.contains("printerAssist"))
        val route = code(src("../feature-sale/src/main/kotlin/com/aktcl/aron/feature/sale/ui/SaleRoute.kt", "feature-sale/src/main/kotlin/com/aktcl/aron/feature/sale/ui/SaleRoute.kt"))
        assertTrue(route.contains("HoldPrinter(printer, assist = printerAssist)"))
    }

    /** Two entries to a screen that asks (enter, leave, enter): the system prompt is launched once, not on every entry (composed, with a counting registry). */
    @Test fun theNearbyPermissionPromptIsLaunchedOnceAcrossTwoComposedEntries() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, ComponentActivity::class.java))
        val launches = mutableListOf<String>()
        val registry = object : androidx.activity.result.ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I, options: androidx.core.app.ActivityOptionsCompat?) {
                launches += input.toString()
            }
        }
        val owner = object : androidx.activity.result.ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val entered = androidx.compose.runtime.mutableStateOf(true)
        val m = manager()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    androidx.compose.runtime.CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides owner) {
                        AronTheme(AppLanguage.BN) { if (entered.value) HoldPrinter(m, assist = true) }
                    }
                }
            }
            repeat(10) { compose.mainClock.advanceTimeByFrame() }
            assertEquals("first entry asks once", listOf(android.Manifest.permission.BLUETOOTH_CONNECT), launches)
            entered.value = false; repeat(10) { compose.mainClock.advanceTimeByFrame() }
            entered.value = true; repeat(10) { compose.mainClock.advanceTimeByFrame() }
            assertEquals("the second entry does not ask again", 1, launches.size)
            assertTrue(com.aktcl.aron.core.printing.ui.PrinterAssistPrompt.asked(context))
        }
    }
}
