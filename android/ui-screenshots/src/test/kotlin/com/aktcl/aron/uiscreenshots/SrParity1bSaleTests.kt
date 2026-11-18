package com.aktcl.aron.uiscreenshots

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aktcl.aron.feature.sale.domain.DrpRule
import com.aktcl.aron.feature.sale.domain.SaleDraft
import com.aktcl.aron.feature.sale.domain.SaleReviewCalculator
import com.aktcl.aron.feature.sale.domain.SaleSku
import com.aktcl.aron.feature.sale.ui.ReviewScreen
import com.aktcl.aron.feature.sale.ui.SaleEntryScreen
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.GlassTier
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
class SrParity1bSaleTests {
    @get:Rule val rule = createComposeRule()
    private val goldens: String = System.getProperty("aron.screenshots.goldens") ?: "src/test/screenshots"
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private val outlet = VisitOutlet(1, 1, "Ruku Store", "2681268", 23.7925, 90.4078, "master", 100, 50)
    private val label = "Ruku Store (2681268-01778553683-Agora Goli, Gulshan-2)"
    private val open = VisitUiState.Open(OpenVisit("v1", 1, 1, "in_range", "sale_allowed", true, false, null, "2026-10-05T04:31:07Z"))
    private val q = CallQuestion(label, {}, {}, {})

    private fun shot(name: String, fontScale: Float = 1.0f, sunlight: Boolean = false, clicks: List<String> = emptyList(), icon: Int? = com.aktcl.aron.core.ui.R.drawable.aron_art_sale, title: Int = com.aktcl.aron.feature.home.R.string.hm_tile_sale, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.BN)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides ctx, LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale)) {
                AronTheme(AppLanguage.BN, dark = false, sunlight = sunlight, tier = GlassTier.B, reducedMotion = true) {
                    ParityFrame(stringResource(title), icon, printerConnected = false) { content() }
                }
            }
        }
        rule.waitForIdle()
        clicks.forEach { rule.onNodeWithText(it).performClick(); rule.waitForIdle() }
        if (System.getProperty("roborazzi.test.record") != "true") assertTrue("missing golden $goldens/$name.png", java.io.File("$goldens/$name.png").exists())
        rule.onRoot().captureRoboImage("$goldens/$name.png", roborazziOptions = options)
    }

    private val geoIn = VisitGeoInfo("Ruku Store", 23.7925, 90.4078, 100, 23.79255, 90.40785, 12.0, GeoVerdict.IN_RANGE)

    @Test fun callQuestionRelaunch() = shot("srp1b_call_question_relaunch") {
        VisitCheckContent(open, onRefresh = {}, onForceSale = {}, onRetry = {}, onMap = null, onStartCall = {}, onNoSale = {}, onBack = {}, callQuestion = q)
    }

    @Test fun callQuestionGeo() = shot("srp1b_call_question_geo") {
        VisitCheckContent(open, onRefresh = {}, onForceSale = {}, onRetry = {}, onMap = {}, onStartCall = {}, onNoSale = {}, geo = geoIn, geoPanel = true, onBack = {}, callQuestion = q)
    }

    @Test fun outOfRange() = shot("srp1b_out_of_range") {
        VisitCheckContent(
            VisitUiState.NeedsDecision(outlet, GeoVerdictResult(GeoVerdict.OUT_OF_RANGE, GeoAction.REFRESH_OFFERED, 180.0), 1, true, 3, true, false, phoneLat = 23.7912, phoneLng = 90.4009),
            onRefresh = {}, onForceSale = {}, onRetry = {}, onMap = {}, geoPanel = true,
        )
    }

    @Test fun outOfRangeSunlight() = shot("srp1b_out_of_range_sunlight", sunlight = true) {
        VisitCheckContent(
            VisitUiState.NeedsDecision(outlet, GeoVerdictResult(GeoVerdict.OUT_OF_RANGE, GeoAction.REFRESH_OFFERED, 180.0), 1, true, 3, true, false, phoneLat = 23.7912, phoneLng = 90.4009),
            onRefresh = {}, onForceSale = {}, onRetry = {}, onMap = {}, geoPanel = true,
        )
    }

    @Test fun outOfRangeNoForceYet() = shot("srp1b_out_of_range_refresh_only") {
        VisitCheckContent(
            VisitUiState.NeedsDecision(outlet, GeoVerdictResult(GeoVerdict.OUT_OF_RANGE, GeoAction.REFRESH_OFFERED, 180.0), 1, true, 3, false, false, phoneLat = 23.7912, phoneLng = 90.4009),
            onRefresh = {}, onForceSale = {}, onRetry = {}, onMap = {}, geoPanel = true,
        )
    }

    @Test fun outOfRangeNoRefreshLeft() = shot("srp1b_out_of_range_no_refresh") {
        VisitCheckContent(
            VisitUiState.NeedsDecision(outlet, GeoVerdictResult(GeoVerdict.OUT_OF_RANGE, GeoAction.FORCE_SALE, 180.0), 3, false, 3, true, false, phoneLat = 23.7912, phoneLng = 90.4009),
            onRefresh = {}, onForceSale = {}, onRetry = {}, onMap = {}, geoPanel = true,
        )
    }

    @Test fun forceReason() = shot("srp1b_force_reason") {
        com.aktcl.aron.feature.outlet.ForceSaleContent(null, com.aktcl.aron.feature.outlet.GeoPhotoState(), emptySet(), false, {}, {}, {}, manual = true)
    }

    @Test fun forceReasonChosenMissingPhoto() = shot("srp1b_force_reason_missing_photo") {
        com.aktcl.aron.feature.outlet.ForceSaleContent(
            com.aktcl.aron.feature.outlet.ForceReason.INTERNET_PROBLEM, com.aktcl.aron.feature.outlet.GeoPhotoState(),
            setOf(com.aktcl.aron.feature.outlet.ForceSaleMissing.PHOTO), false, {}, {}, {}, manual = true,
        )
    }

    @Test fun callQuestionFont20() = shot("srp1b_call_question_font20", fontScale = 2.0f) {
        VisitCheckContent(open, onRefresh = {}, onForceSale = {}, onRetry = {}, onMap = null, onStartCall = {}, onNoSale = {}, onBack = {}, callQuestion = q)
    }

    // ---- zero sale (manual 32): the question, the four reasons, the Review with a zero total ----
    private val maxr = SaleSku(100, "MAXR10", "cigarette", "MaxR 10S", "ম্যাক্সআর ১০এস", "stick", 10, 8_000, 1, "2026-09-01", stockBase = 400, drp = DrpRule(10))
    private val aster = SaleSku(103, "ASTER", "lighter", "Aster", null, "piece", 25, 12_500, 1, "2026-09-01", stockBase = 25)
    private val skus = listOf(maxr, aster)
    private val emptyDraft = SaleDraft("v1", 50001, 10231, "2026-10-05", memoUuid = "m1")
    private val emptyReview = SaleReviewCalculator.review(emptyDraft, skus.associateBy { it.skuId })
    private val zeroReview = SaleReviewCalculator.review(com.aktcl.aron.feature.sale.domain.SaleDraftOps.confirmZeroSale(emptyDraft, "outlet_closed_during_visit"), skus.associateBy { it.skuId })
    private val retailer = "Ruku Store (2681268-01778553683-Agora Goli, Gulshan-2)"
    private val saleTitle = com.aktcl.aron.feature.sale.R.string.sale_title

    @Composable private fun EmptyEntry() = SaleEntryScreen(
        skus, emptyReview, { 0 }, { _, _ -> }, {}, {}, retailerLabel = retailer, onZeroSaleReason = {}, quantityPopup = true,
    )

    @Test fun zeroSaleQuestion() = shot("srp1b_zero_question", clicks = listOf("এগিয়ে যান"), title = saleTitle) { EmptyEntry() }
    @Test fun zeroSaleReasons() = shot("srp1b_zero_reasons", clicks = listOf("এগিয়ে যান", "হ্যাঁ"), title = saleTitle) { EmptyEntry() }
    @Test fun zeroSaleReview() = shot("srp1b_zero_review", title = com.aktcl.aron.feature.sale.R.string.review_title) {
        ReviewScreen(zeroReview, {}, outletLine = "Ruku Store (2681268)\nরুট: Apsis RouteDaily\nক্লাস্টার: Apsis Cluster", onCreditToggle = {}, onQc = {})
    }
}
