package com.aktcl.aron.uiscreenshots

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.GlassTier
import com.aktcl.aron.feature.home.BundleFreshness
import com.aktcl.aron.feature.home.DeviceHealthModel
import com.aktcl.aron.feature.home.HomeContent
import com.aktcl.aron.feature.home.HomeModel
import com.aktcl.aron.feature.home.HomeTiles
import com.aktcl.aron.feature.memo.domain.MemoItem
import com.aktcl.aron.feature.memo.domain.MemoMenu
import com.aktcl.aron.feature.memo.domain.StoredMemo
import com.aktcl.aron.feature.memo.ui.MemoDetailView
import com.aktcl.aron.feature.sale.domain.DrpRule
import com.aktcl.aron.feature.sale.domain.SaleDraft
import com.aktcl.aron.feature.sale.domain.SaleDraftOps
import com.aktcl.aron.feature.sale.domain.SaleReviewCalculator
import com.aktcl.aron.feature.sale.domain.SaleSku
import com.aktcl.aron.feature.sale.ui.ReviewScreen
import com.aktcl.aron.feature.sale.ui.SaleEntryScreen
import com.aktcl.aron.rules.QtyUnit
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * N-023 / docs/32 s2a item 8(b): golden screenshots of Home, Sale entry, Review and Memo detail, tier B, 360 x 640 dp.
 * Default = verify against src/test/screenshots (0.2 percent changed-pixel tolerance); see docs/status/android-core-ui.md to re-record.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class ScreenshotTests {
    @get:Rule val rule = createComposeRule()

    private val recordTo: String? = System.getProperty("aron.screenshots.recordTo")
    private val goldens: String = System.getProperty("aron.screenshots.goldens") ?: "src/test/screenshots"
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private fun shot(name: String, language: AppLanguage, sunlight: Boolean = false, fontScale: Float = 1.0f, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        rule.setContent {
            CompositionLocalProvider(
                LocalContext provides ctx,
                LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale),
            ) { AronTheme(language, dark = false, sunlight = sunlight, tier = GlassTier.B) { content() } }
        }
        rule.waitForIdle()
        if (recordTo == null && System.getProperty("roborazzi.test.record") != "true") {
            // a missing golden must fail the build, never pass silently or be recorded by a normal run
            assertTrue("missing golden $goldens/$name.png", java.io.File("$goldens/$name.png").exists())
        }
        rule.onRoot().captureRoboImage("${recordTo ?: goldens}/$name.png", roborazziOptions = options)
    }

    // ---- inputs ----
    private val header = HomeModel.header("SR", "Testing Banani", "sr334001", "Apsis RouteDaily", "daily", "2026-10-05")
    private val maxr = SaleSku(100, "MAXR10", "cigarette", "MaxR 10S", null, "stick", 10, 8_000, 1, "2026-09-01", stockBase = 400, drp = DrpRule(10))
    private val aster = SaleSku(103, "ASTER", "lighter", "Aster", null, "piece", 1, 12_500, 1, "2026-09-01", stockBase = 25)
    private val skus = listOf(maxr, aster)
    private val catalog = skus.associateBy { it.skuId }
    private fun draft() = SaleDraft("v1", 50001, 10231, "2026-10-05", memoUuid = "m1")
    private val saleDraft = SaleDraftOps.setQuantity(SaleDraftOps.setQuantity(draft(), 103, 26, QtyUnit.PIECE), 100, 40, QtyUnit.STICK) // stock warning on Aster
    private val saleReview = SaleReviewCalculator.review(saleDraft, catalog)
    private val reviewReview = SaleReviewCalculator.review(SaleDraftOps.setSlide(SaleDraftOps.setQuantity(draft(), 100, 20, QtyUnit.STICK), 100, 10), catalog)
    private val memo = StoredMemo(
        "m1", "sr334001-261005-001", 50001, "2026-10-05", "2026-10-05T04:00:00.000Z", null,
        listOf(MemoItem(100, 40, 8_000, 320_000), MemoItem(103, 3, 12_500, 37_500)), emptyList(), emptyList(),
        357_500, 0, 0, 0, 357_500, 300_000, 57_500,
    )
    private fun skuName(id: Long) = if (id == 100L) "MaxR 10S" else "Aster"

    @Composable private fun Home() = HomeContent(header, HomeTiles.resolve(emptySet(), 2), BundleFreshness.Fresh, false, DeviceHealthModel.of(72, 900, 2, 15), {})
    @Composable private fun Sale() = SaleEntryScreen(skus, saleReview, { id -> saleDraft.lines.firstOrNull { it.skuId == id }?.qtyEntered ?: 0 }, { _, _ -> }, {}, {})
    @Composable private fun Review() = ReviewScreen(reviewReview, {})
    @Composable private fun Memo() = MemoDetailView(MemoMenu.detail(memo), true, 57_500, ::skuName, {}, {}, {})


    // ---- Home ----
    @Test @Config(qualifiers = "bn-w360dp-h640dp") fun home_bn_light() = shot("home_bn_light", AppLanguage.BN) { Home() }
    @Test fun home_en_light() = shot("home_en_light", AppLanguage.EN) { Home() }
    @Test @Config(qualifiers = "bn-w360dp-h640dp") fun home_bn_sunlight() = shot("home_bn_sunlight", AppLanguage.BN, sunlight = true) { Home() }

    // ---- Sale entry ----
    @Test @Config(qualifiers = "bn-w360dp-h640dp") fun sale_bn_light() = shot("sale_bn_light", AppLanguage.BN) { Sale() }
    @Test fun sale_en_light() = shot("sale_en_light", AppLanguage.EN) { Sale() }
    @Test @Config(qualifiers = "bn-w360dp-h640dp") fun sale_bn_sunlight() = shot("sale_bn_sunlight", AppLanguage.BN, sunlight = true) { Sale() }
    @Test @Config(qualifiers = "bn-w360dp-h640dp") fun sale_bn_light_font13() = shot("sale_bn_light_font13", AppLanguage.BN, fontScale = 1.3f) { Sale() }

    // ---- Review ----
    @Test @Config(qualifiers = "bn-w360dp-h640dp") fun review_bn_light() = shot("review_bn_light", AppLanguage.BN) { Review() }
    @Test fun review_en_light() = shot("review_en_light", AppLanguage.EN) { Review() }
    @Test @Config(qualifiers = "bn-w360dp-h640dp") fun review_bn_sunlight() = shot("review_bn_sunlight", AppLanguage.BN, sunlight = true) { Review() }

    // ---- Memo detail ----
    @Test @Config(qualifiers = "bn-w360dp-h640dp") fun memo_bn_light() = shot("memo_bn_light", AppLanguage.BN) { Memo() }
    @Test fun memo_en_light() = shot("memo_en_light", AppLanguage.EN) { Memo() }
    @Test @Config(qualifiers = "bn-w360dp-h640dp") fun memo_bn_sunlight() = shot("memo_bn_sunlight", AppLanguage.BN, sunlight = true) { Memo() }

    /** Bootstrap guard: a run that only recorded images must not look green (CI uploads test results on failure only). */
    @Test fun recordingRunNeverPasses() {
        if (recordTo != null) fail("Goldens recorded to $recordTo: download the artifact, copy the PNGs into android/ui-screenshots/src/test/screenshots and turn the record switch off.")
    }
}
