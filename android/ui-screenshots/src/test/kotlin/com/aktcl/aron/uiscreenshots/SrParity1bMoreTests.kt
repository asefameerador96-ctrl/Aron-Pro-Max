package com.aktcl.aron.uiscreenshots

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.aktcl.aron.feature.memo.ui.SalesJourneyCall
import com.aktcl.aron.feature.memo.ui.SalesJourneyScreen
import com.aktcl.aron.rules.JourneyCallInput
import com.aktcl.aron.rules.JourneyCallRow
import com.aktcl.aron.rules.JourneyCallStatus
import com.aktcl.aron.rules.UiLocale

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.rules.TextRules
import com.aktcl.aron.feature.outlet.ClusterOption
import com.aktcl.aron.feature.outlet.ClustersContent
import com.aktcl.aron.feature.outlet.GeoPhotoState
import com.aktcl.aron.feature.outlet.OutletMenuContent
import com.aktcl.aron.feature.outlet.OutletRequestForm
import com.aktcl.aron.feature.outlet.OutletRequestKind
import com.aktcl.aron.feature.outlet.RequestError
import com.aktcl.aron.feature.outlet.SrCluster
import com.aktcl.aron.feature.outlet.SrOutletRequestContent
import com.aktcl.aron.feature.outlet.SrRequestTags

import androidx.compose.ui.test.performTextInput
import com.aktcl.aron.core.printing.flow.SaveAndPrint
import com.aktcl.aron.core.printing.ui.SaveAndPrintStepDialog
import com.aktcl.aron.feature.sale.domain.SaleDraftOps
import com.aktcl.aron.feature.sale.ui.CreditDialog
import com.aktcl.aron.feature.sale.ui.CreditTags
import com.aktcl.aron.rules.QtyUnit
import com.aktcl.aron.feature.sale.domain.DrpRule
import com.aktcl.aron.feature.sale.domain.SaleDraft
import com.aktcl.aron.feature.sale.domain.SaleReviewCalculator
import com.aktcl.aron.feature.sale.domain.SaleSku
import com.aktcl.aron.feature.sale.domain.SkuStats
import com.aktcl.aron.feature.sale.ui.QuantityPopup
import com.aktcl.aron.feature.sale.ui.ReviewScreen
import com.aktcl.aron.feature.sale.ui.SaleEntryScreen
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.GlassTier
import com.aktcl.aron.core.system.permission.GateAction as A
import com.aktcl.aron.core.system.permission.GatedFeature as G
import com.aktcl.aron.core.system.permission.RuntimePermission as P
import com.aktcl.aron.feature.outlet.CallQuestion
import com.aktcl.aron.feature.outlet.OpenVisit
import com.aktcl.aron.feature.outlet.VisitCheckContent
import com.aktcl.aron.feature.outlet.VisitGeoInfo
import com.aktcl.aron.feature.outlet.VisitOutlet
import com.aktcl.aron.feature.outlet.VisitUiState
import com.aktcl.aron.rules.GeoAction
import com.aktcl.aron.rules.GeoVerdict
import com.aktcl.aron.rules.GeoVerdictResult
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Lane P-SR1b goldens: the SR Sale page as SrApp draws it (manual 19, 30, 31, 32): the inline start-call question
 * (relaunch path and with the fix), outside range (ফোর্স সেল and রিফ্রেশ), the force reason. Bangla light, 360 x 800 dp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "bn-w360dp-h800dp")
class SrParity1bMoreTests {
    @get:Rule val rule = createComposeRule()
    private val goldens: String = System.getProperty("aron.screenshots.goldens") ?: "src/test/screenshots"
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private fun shot(name: String, fontScale: Float = 1.0f, sunlight: Boolean = false, titleText: String? = null, icon: Int? = null, clicks: List<String> = emptyList(), content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.BN)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides ctx, LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale)) {
                AronTheme(AppLanguage.BN, dark = false, sunlight = sunlight, tier = GlassTier.B, reducedMotion = true) {
                    if (titleText != null) ParityFrame(titleText, icon, printerConnected = false) { content() } else content()
                }
            }
        }
        rule.waitForIdle()
        clicks.forEach { if (it.startsWith("#")) rule.onNodeWithTag(it.drop(1)).performClick() else rule.onNodeWithText(it).performClick(); rule.waitForIdle() }
        if (System.getProperty("roborazzi.test.record") != "true") assertTrue("missing golden $goldens/$name.png", java.io.File("$goldens/$name.png").exists())
        rule.onRoot().captureRoboImage("$goldens/$name.png", roborazziOptions = options)
    }

    // ---- Settings and language (manual 63, 65) with the flags SrApp passes ----
    @Composable private fun Settings() = com.aktcl.aron.feature.home.SettingsContent(
        "1.0.25 (12)", {}, {}, onUpdate = {}, updateAvailable = false, photosWifiOnly = true, onPhotosWifiOnly = {}, onSupport = {}, onPrinter = {},
        onUpdateConfig = { com.aktcl.aron.feature.home.ConfigUpdate.UPDATED }, onBack = {}, sunlight = false, onSunlight = {},
        submitPending = { true }, onSalesSubmit = {},
    )
    @Test fun settings() = shot("srp1b_settings") { Settings() }
    @Test fun settingsSunlight() = shot("srp1b_settings_sunlight", sunlight = true) { Settings() }
    @Test fun settingsFont20() = shot("srp1b_settings_font20", fontScale = 2.0f) { Settings() }
    @Test fun settingsLogoutAsk() = shot("srp1b_settings_logout_ask", clicks = listOf("লগ আউট")) { Settings() }

    // ---- Sales Journey (manual 59) ----
    private fun call(id: String, outlet: Long, open: Long, close: Long?, sold: Boolean, edited: Boolean, status: JourneyCallStatus, dur: Long?, dist: Double?, run: Double, gap: Long?) =
        SalesJourneyCall(
            JourneyCallRow(JourneyCallInput(id, outlet, open, close, 23.79, 90.40, sold, edited), status, dur, dist, run, gap),
            "Store $outlet (26${outlet}0-0177855368$outlet-Agora Road)",
        )
    private val t0 = java.time.Instant.parse("2026-11-18T03:53:35Z").toEpochMilli()
    private val journey = listOf(
        call("a", 1, t0, t0 + 327_000, true, false, JourneyCallStatus.NEW_SALE, 327, null, 0.0, 473),
        call("b", 2, t0 + 800_000, t0 + 2_118_000, true, true, JourneyCallStatus.EDITED_SALE, 1318, 412.72, 412.72, 10),
        call("c", 3, t0 + 2_130_000, t0 + 2_442_000, true, false, JourneyCallStatus.REPEAT_SALE, 312, 0.0, 412.72, 19),
        call("d", 4, t0 + 2_461_000, t0 + 2_500_000, false, false, JourneyCallStatus.NO_SALE, 39, 51.08, 463.8, null),
    )
    @Test fun journey() = shot("srp1b_journey", titleText = "বিক্রয় যাত্রা", icon = com.aktcl.aron.core.ui.R.drawable.aron_art_sales_journey) { SalesJourneyScreen(journey, UiLocale.BN) }
    @Test fun journeyFont20() = shot("srp1b_journey_font20", fontScale = 2.0f, titleText = "বিক্রয় যাত্রা", icon = com.aktcl.aron.core.ui.R.drawable.aron_art_sales_journey) { SalesJourneyScreen(journey, UiLocale.BN) }
    @Test fun journeyEmpty() = shot("srp1b_journey_empty", titleText = "বিক্রয় যাত্রা", icon = com.aktcl.aron.core.ui.R.drawable.aron_art_sales_journey) { SalesJourneyScreen(emptyList(), UiLocale.BN) }
}
