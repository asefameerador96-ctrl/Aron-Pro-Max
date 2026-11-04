package com.aktcl.aron.uiscreenshots

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.map.LiteMapScreen
import com.aktcl.aron.core.map.MapPoint
import com.aktcl.aron.core.map.MapPointKind
import com.aktcl.aron.core.map.MapSettings
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.GlassTier
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
 * The offline fallback list of the shared map screen in the DEFAULT look (the AMO Team Location screen draws it with
 * `AronTheme(language)`): pins the SR and AMO colours so a change made for the TSO look (explicit ink on navy, conformance rows 28 and
 * 29) can never alter them silently. The TSO look's own lists are `tso_pe_s28_*` and `tso_pe_s29_*`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class MapFallbackListScreenshotTests {
    @get:Rule val rule = createComposeRule()
    private val recordTo: String? = System.getProperty("aron.screenshots.recordTo")
    private val goldens: String = System.getProperty("aron.screenshots.goldens") ?: "src/test/screenshots"
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    @Test fun amoTeamLocationFallbackListDefaultLook() {
        val name = "map_fallback_list_default_en"
        val now = 1_792_928_040_000L
        val points = listOf(
            MapPoint("1", "SR - Testing Banani (Apsis Route)", 23.79, 90.40, now - 18 * 60_000L, "visit", MapPointKind.PERSON),
            MapPoint("2", "SR - Testing Banani 2 (Apsis Route 2)", 23.80, 90.41, now - 150 * 60_000L, "check_in", MapPointKind.PERSON, note = "Mock GPS today: 2"),
            MapPoint("3", "AKT Management (akt-mgmt)", null, null),
        )
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(AppLanguage.EN, dark = false, sunlight = false, tier = GlassTier.B, reducedMotion = true) {
                    LiteMapScreen(points, MapSettings(), online = false, keyPresent = false, nowMs = now, cache = null, cacheKey = "k")
                }
            }
        }
        rule.waitForIdle()
        if (recordTo == null && System.getProperty("roborazzi.test.record") != "true") assertTrue("missing golden $goldens/$name.png", java.io.File("$goldens/$name.png").exists())
        rule.onRoot().captureRoboImage("${recordTo ?: goldens}/$name.png", roborazziOptions = options)
    }
}
