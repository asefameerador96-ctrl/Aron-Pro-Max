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
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.GlassTier
import com.aktcl.aron.feature.home.TutorialItem
import com.aktcl.aron.feature.home.TutorialListContent
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** F-SR-048 goldens: the tutorial list online, offline (rows not tappable), empty, a long Bangla title and Sunlight. Linux only (`-Proborazzi.record=true`). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class TutorialScreenshotTests {
    @get:Rule val rule = createComposeRule()
    private val recordTo: String? = System.getProperty("aron.screenshots.recordTo")
    private val goldens: String = System.getProperty("aron.screenshots.goldens") ?: "src/test/screenshots"
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private fun shot(name: String, language: AppLanguage, sunlight: Boolean = false, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        rule.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language, dark = false, sunlight = sunlight, tier = GlassTier.B, reducedMotion = true) { content() } } }
        rule.waitForIdle()
        if (recordTo == null && System.getProperty("roborazzi.test.record") != "true") assertTrue("missing golden $goldens/$name.png", java.io.File("$goldens/$name.png").exists())
        rule.onRoot().captureRoboImage("${recordTo ?: goldens}/$name.png", roborazziOptions = options)
    }

    private val items = listOf(
        TutorialItem("1", "কীভাবে বিক্রি করবেন", "https://x.example.com/1", false, 125),
        TutorialItem("2", "মূল্য তালিকা ম্যানুয়াল: দাম পরিবর্তনের পর কীভাবে নতুন মূল্য দেখবেন এবং মেমোতে সঠিক মূল্য নিশ্চিত করবেন", "https://x.example.com/2", true, null),
        TutorialItem("3", "Printing a memo on the MP-58N", "https://x.example.com/3", false, 300),
    )

    @Test fun online_bn() = shot("sr_tutorial_online_bn", AppLanguage.BN) { TutorialListContent(items, true, {}, onBack = {}) }
    @Test fun offline_en() = shot("sr_tutorial_offline_en", AppLanguage.EN) { TutorialListContent(items, false, {}, onBack = {}) }
    @Test fun empty_bn() = shot("sr_tutorial_empty_bn", AppLanguage.BN) { TutorialListContent(emptyList(), true, {}, onBack = {}) }
    @Test fun sunlight_en() = shot("sr_tutorial_sunlight_en", AppLanguage.EN, sunlight = true) { TutorialListContent(items, true, {}, onBack = {}) }
}
