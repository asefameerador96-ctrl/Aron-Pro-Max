package com.aktcl.aron.uiscreenshots

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronScreen
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.AronTopBar
import com.aktcl.aron.core.ui.GlassTier
import com.aktcl.aron.feature.amo.AmoBrand
import com.aktcl.aron.feature.amo.AmoRouteRow
import com.aktcl.aron.feature.amo.AmoRoutePick
import com.aktcl.aron.feature.amo.AssignedSr
import com.aktcl.aron.feature.amo.ControlCallMenu
import com.aktcl.aron.feature.amo.CorrectionRow
import com.aktcl.aron.feature.amo.JointCallContent
import com.aktcl.aron.feature.amo.JointRubric
import com.aktcl.aron.feature.amo.JointState
import com.aktcl.aron.feature.amo.PerfAssessmentContent
import com.aktcl.aron.feature.amo.PerfState
import com.aktcl.aron.feature.amo.PriceComplianceContent
import com.aktcl.aron.feature.amo.PriceRow
import com.aktcl.aron.feature.amo.RubricCriterion
import com.aktcl.aron.feature.amo.StockCorrectionContent
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Goldens of the AMO control-call screens (route pick, menu, joint call, assessment, price compliance, stock correction) in normal, empty and long-Bangla states. Linux only. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class AmoCoreScreenshotTests {
    @get:Rule val rule = createComposeRule()

    private val recordTo: String? = System.getProperty("aron.screenshots.recordTo")
    private val goldens: String = System.getProperty("aron.screenshots.goldens") ?: "src/test/screenshots"
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private fun shot(name: String, language: AppLanguage, title: String, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(language, dark = false, sunlight = false, tier = GlassTier.B, reducedMotion = true) {
                    AronScreen(topBar = { AronTopBar(title = title, onBack = {}) }) { content() }
                }
            }
        }
        rule.waitForIdle()
        if (recordTo == null && System.getProperty("roborazzi.test.record") != "true") assertTrue("missing golden $goldens/$name.png", java.io.File("$goldens/$name.png").exists())
        rule.onRoot().captureRoboImage("${recordTo ?: goldens}/$name.png", roborazziOptions = options)
    }

    private val rubric = JointRubric(1, 1, listOf(
        RubricCriterion(1, "Product knowledge", "পণ্য সম্পর্কে জ্ঞান এবং বিক্রয় কৌশল প্রয়োগের দক্ষতা", "stars", true),
        RubricCriterion(2, "Courtesy", "সৌজন্য", "stars", true), RubricCriterion(3, "Display", "প্রদর্শন", "stars", false),
    ))
    private val brands = listOf(AmoBrand(1, "Gold Leaf", "গোল্ড লিফ"), AmoBrand(2, "Navy", "নেভি"), AmoBrand(3, "Marlboro Advance Gold Special", "মার্লবরো অ্যাডভান্স গোল্ড স্পেশাল"))
    private val prices = listOf(PriceRow(1, "Gold Leaf 10s", "10", "retail", 120000), PriceRow(2, "Navy 20s", "20", "retail", 240000))

    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_route_pick_en() = shot("amo_route_pick_en", AppLanguage.EN, "Control Call") { AmoRoutePick(listOf(AmoRouteRow(1, "R-1 North", 42), AmoRouteRow(2, "R-2 South", 37)), {}) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_route_pick_empty_bn() = shot("amo_route_pick_empty_bn", AppLanguage.BN, "কন্ট্রোল কল") { AmoRoutePick(emptyList(), {}) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_control_call_menu_bn() = shot("amo_control_call_menu_bn", AppLanguage.BN, "কন্ট্রোল কল") { ControlCallMenu({}, {}, {}, {}, onPrice = {}) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_joint_call_bn_long() = shot("amo_joint_call_bn", AppLanguage.BN, "জয়েন্ট কল") { JointCallContent(rubric, AssignedSr(7, "রহিম উদ্দিন"), JointState(mapOf(1L to 4)), false, false, { _, _ -> }, {}) }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_perf_assessment_en() = shot("amo_perf_assessment_en", AppLanguage.EN, "SR Perf. Assessment") { PerfAssessmentContent(brands, PerfState(present = setOf(1L, 3L), oos = setOf(3L), posm = true), false, false, {}, {}, {}, {}) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_perf_assessment_empty_bn() = shot("amo_perf_assessment_empty_bn", AppLanguage.BN, "এসআর মূল্যায়ন") { PerfAssessmentContent(emptyList(), PerfState(), false, false, {}, {}, {}, {}) }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_price_compliance_en() = shot("amo_price_compliance_en", AppLanguage.EN, "Price compliance") { PriceComplianceContent(prices, mapOf(1L to "120", 2L to "260"), 500, false, false, { _, _ -> }, {}) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_price_compliance_empty_bn() = shot("amo_price_compliance_empty_bn", AppLanguage.BN, "দামের সামঞ্জস্য") { PriceComplianceContent(emptyList(), emptyMap(), 500, false, false, { _, _ -> }, {}) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_stock_correction_bn() = shot("amo_stock_correction_bn", AppLanguage.BN, "স্টক সংশোধন") { StockCorrectionContent(listOf(CorrectionRow(1, "গোল্ড লিফ ১০", "stick", 1500), CorrectionRow(2, "লাইটার", "piece", 40)), 1L, "1400", null, false, false, {}, {}, {}, {}) }
}
