package com.aktcl.aron.uiscreenshots

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.GlassTier
import com.aktcl.aron.core.ui.SyncChipState
import com.aktcl.aron.feature.home.BundleFreshness
import com.aktcl.aron.feature.home.DeviceHealthModel
import com.aktcl.aron.feature.home.HomeContent
import com.aktcl.aron.feature.home.HomeDayState
import com.aktcl.aron.feature.home.HomeDock
import com.aktcl.aron.feature.home.HomeHero
import com.aktcl.aron.feature.home.HomeKpis
import com.aktcl.aron.feature.home.HomeModel
import com.aktcl.aron.feature.home.HomeMoneyLine
import com.aktcl.aron.feature.home.HomeMoneyLines
import com.aktcl.aron.feature.home.HomeStockLine
import com.aktcl.aron.feature.home.HomeTiles
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.UiLocale
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Look-and-fix captures of the SR Home (screens-sr s2), not goldens, never verified: the phone frame (360 x 800) with the
 * hero, KPI row, money card and dock in Bangla light, Bangla sunlight and English; a tall frame so the part below the fold
 * is visible; the loading (skeleton) and the no-route states; and font scale 1.3. The PNGs go to `-Daron.kitshot.dir`
 * (default `build/kitshots`). Run: `./gradlew :android:ui-screenshots:testDebugUnitTest --tests '*HomeShot*'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class HomeShot {
    @get:Rule val rule = createComposeRule()

    private val dir: File = File(System.getProperty("aron.kitshot.dir") ?: "build/kitshots").also { it.mkdirs() }
    private val options = RoborazziOptions(taskType = RoborazziTaskType.Record)

    private val header = HomeModel.header("SR", "Testing Banani", "sr334001", "Apsis RouteDaily", "daily", "2026-10-05")

    private fun shot(name: String, language: AppLanguage, sunlight: Boolean = false, dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides ctx, LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale)) {
                AronTheme(language, dark = dark, sunlight = sunlight, tier = GlassTier.B, reducedMotion = true) { content() }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(File(dir, "$name.png").path, roborazziOptions = options)
    }

    @Composable
    private fun FullHome(language: AppLanguage, dayState: HomeDayState = HomeDayState.IN_FIELD, dock: HomeDock? = HomeDock.START_SELLING, freshness: BundleFreshness = BundleFreshness.Fresh) {
        val locale = if (language == AppLanguage.BN) UiLocale.BN else UiLocale.EN
        val piece = stringResource(com.aktcl.aron.feature.sale.R.string.sale_unit_piece)
        val dozen = stringResource(com.aktcl.aron.feature.sale.R.string.sale_unit_dozen)
        val stick = stringResource(com.aktcl.aron.feature.sale.R.string.sale_unit_stick)
        val lighter = "Lighter" // sample data: category names are bundle data, not resources
        val match = "Match"
        val cigarette = "Cigarette"
        HomeContent(
            header, HomeTiles.resolve(emptySet(), 2), freshness, false, DeviceHealthModel.of(62, 1_200, 3, 12), onTile = {},
            onSettings = {}, hero = HomeHero(Formats.money(4_391_000, locale), 6, 60, "08:52", dayState),
            kpis = HomeKpis("10%", 54, 0, listOf(HomeStockLine(cigarette, 12_000, 9_800, stick), HomeStockLine(lighter, 400, 25, piece), HomeStockLine(match, 400, 394, dozen))),
            money = HomeMoneyLines(
                listOf(HomeMoneyLine(match, Formats.money(141_000, locale), bare(141_000, locale)), HomeMoneyLine(lighter, Formats.money(4_687_500, locale), bare(4_687_500, locale))),
                Formats.money(4_828_500, locale), "−" + Formats.money(437_500, locale), null, Formats.money(4_391_000, locale),
                sticksSold = 0, grossBare = bare(4_828_500, locale), offerDiscountBare = bare(437_500, locale), qcBare = bare(0, locale), netBare = bare(4_391_000, locale),
                totalDiscountBare = bare(437_500, locale), drpDiscountBare = bare(0, locale), totalQcBare = bare(0, locale),
            ),
            sync = SyncChipState.SyncedAt(Formats.digits("10:42", locale)), onSync = {}, sunlight = false, onSunlight = {},
            dock = dock, routeKindLabel = "Daily",
        )
    }

    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun home_bn_light() = shot("home_bn_light", AppLanguage.BN) { FullHome(AppLanguage.BN) }
    @Test @Config(qualifiers = "bn-w360dp-h1500dp") fun home_bn_light_tall() = shot("home_bn_light_tall", AppLanguage.BN) { FullHome(AppLanguage.BN) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun home_bn_sunlight() = shot("home_bn_sunlight", AppLanguage.BN, sunlight = true) { FullHome(AppLanguage.BN) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun home_bn_dark() = shot("home_bn_dark", AppLanguage.BN, dark = true) { FullHome(AppLanguage.BN) }
    @Test fun home_en_light() = shot("home_en_light", AppLanguage.EN) { FullHome(AppLanguage.EN) }
    @Test @Config(qualifiers = "w360dp-h1500dp") fun home_en_light_tall() = shot("home_en_light_tall", AppLanguage.EN) { FullHome(AppLanguage.EN) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun home_bn_font13() = shot("home_bn_font13", AppLanguage.BN, fontScale = 1.3f) { FullHome(AppLanguage.BN) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun home_bn_complete() = shot("home_bn_complete", AppLanguage.BN) { FullHome(AppLanguage.BN, HomeDayState.COMPLETE, dock = null) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun home_bn_expired() = shot("home_bn_expired", AppLanguage.BN) { FullHome(AppLanguage.BN, freshness = BundleFreshness.Expired(3)) }
    @Test @Config(qualifiers = "bn-w360dp-h640dp") fun home_bn_compact() = shot("home_bn_compact", AppLanguage.BN) { FullHome(AppLanguage.BN) }
    @Test @Config(qualifiers = "bn-w360dp-h1500dp") fun home_bn_font13_tall() = shot("home_bn_font13_tall", AppLanguage.BN, fontScale = 1.3f) { FullHome(AppLanguage.BN) }

    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun home_bn_loading() = shot("home_bn_loading", AppLanguage.BN) {
        HomeContent(
            header, HomeTiles.resolve(emptySet(), 0), BundleFreshness.Fresh, false, null, onTile = {}, onSettings = {},
            loading = true, sync = SyncChipState.Syncing, onSync = {}, onSunlight = {}, routeKindLabel = "Daily",
        )
    }

    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun home_bn_no_route() = shot("home_bn_no_route", AppLanguage.BN) {
        val noRoute = HomeModel.header("SR", "Testing Banani", "sr334001", null, null, "2026-10-05")
        HomeContent(
            noRoute, HomeTiles.resolve(emptySet(), 0), BundleFreshness.Fresh, true, null, onTile = {}, onSettings = {},
            hero = HomeHero(Formats.money(0, UiLocale.BN), 0, 0, null, HomeDayState.NOT_CHECKED_IN), noRoute = true,
            sync = SyncChipState.Offline, onSync = {}, onSunlight = {}, dock = HomeDock.CHECK_IN,
        )
    }
    private fun bare(mtk: Long, locale: UiLocale) = Formats.digits(com.aktcl.aron.rules.Money.formatTaka(mtk, 2), locale)
}
