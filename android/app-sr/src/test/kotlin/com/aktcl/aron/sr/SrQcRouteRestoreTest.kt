package com.aktcl.aron.sr

import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
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
 * P-SR1b (lead's QC check, B2): the route-level proof. The REAL `SaleHost` and `SaleRoute` in the real `SrFrame`; the rep opens QC, taps a SKU (step 2) and the activity is recreated (the saved
 * instance state is restored). The sale is back on its entry list (the stage is not saved) and the next time QC opens it shows STEP 1: no open page is restored, so
 * বাতিল can never write quantities the rep did not enter in this session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp")
class SrQcRouteRestoreTest {
    @get:Rule val compose = hostComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private var f: SrLockFixture? = null

    @Before fun setUp() { Dispatchers.setMain(MainLooperImmediate.dispatcher) }
    @After fun tearDown() { try { f?.close() } finally { Dispatchers.resetMain() } }

    private fun openQc() {
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("Proceed").assertExists() }.isSuccess }
        compose.onNodeWithText("Proceed").performClick()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("Product QC").assertExists() }.isSuccess }
        compose.onNodeWithText("Product QC").performClick()
        compose.waitUntil(10_000) { runCatching { compose.onAllNodesWithTag(com.aktcl.aron.feature.sale.ui.QcTags.CARD)[0].assertExists() }.isSuccess }
    }

    private fun show(fx: SrLockFixture, scenario: ActivityScenario<ComponentActivity>) {
        scenario.onActivity { activity ->
            val ctx = AppLocale.wrap(activity, AppLanguage.EN)
            activity.setContent {
                CompositionLocalProvider(LocalContext provides ctx, androidx.activity.compose.LocalActivityResultRegistryOwner provides activity) {
                    AronTheme(AppLanguage.EN) { SrFrame("sale", null, null, null) { SaleHost(fx.day, sunlight = false, onSunlight = {}, onNoSale = {}, onDone = {}) } }
                }
            }
        }
    }

    @Test fun anOpenQcPageIsNotRestoredAfterARestoration() {
        val fx = SrLockFixture(app, latePolicy = null).also { f = it }
        fx.stockedCatalog()
        val v = fx.openCall()
        fx.day.visitSession.restore(v)
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        val scenario = fx.own(ActivityScenario.launch(ComponentActivity::class.java))
        show(fx, scenario)
        compose.waitForIdle()
        if (compose.onAllNodesWithText("Yes").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Yes").performClick()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("Proceed").assertExists() }.isSuccess }
        // one SKU in the sale, then Review and the QC button
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("More", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithContentDescription("More", substring = true)[0].performClick()
        compose.waitForIdle()
        openQc()
        compose.onAllNodesWithTag(com.aktcl.aron.feature.sale.ui.QcTags.CARD)[0].performClick()
        compose.waitUntil(10_000) { runCatching { compose.onAllNodesWithTag(com.aktcl.aron.feature.sale.ui.QcTags.QTY_FIELD)[0].assertExists() }.isSuccess }
        compose.onAllNodesWithTag(com.aktcl.aron.feature.sale.ui.QcTags.QTY_FIELD).assertCountEquals(6) // step 2 is open
        // the restoration
        scenario.recreate()
        show(fx, scenario)
        compose.waitForIdle()
        if (compose.onAllNodesWithText("Yes").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Yes").performClick()
        openQc()
        compose.onAllNodesWithTag(com.aktcl.aron.feature.sale.ui.QcTags.QTY_FIELD).assertCountEquals(0) // step 1, not the old page
    }
}
