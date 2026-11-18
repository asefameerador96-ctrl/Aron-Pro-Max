package com.aktcl.aron.uiscreenshots

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronConfirmDialog
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.GlassTier
import com.aktcl.aron.feature.outlet.ContentStepView
import com.aktcl.aron.feature.outlet.SurveyRowUi
import com.aktcl.aron.feature.outlet.SurveyStepView
import com.aktcl.aron.feature.outlet.SurveyTexts
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
 * Lane P-SR1b goldens: the SR's call-content steps (manual 35): the KV step (a stand-in picture; the real file is whatever the content bundle holds), and the POSM survey (unanswered,
 * answered with the photo box, the submit confirmation). The AV step shares the KV layout (its item is a platform video view, which has no picture to record). Bangla, 360 x 800 dp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "bn-w360dp-h800dp")
class SrParity1bContentTests {
    @get:Rule val rule = createComposeRule()
    private val goldens: String = System.getProperty("aron.screenshots.goldens") ?: "src/test/screenshots"
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private fun shot(name: String, fontScale: Float = 1.0f, sunlight: Boolean = false, titleText: String? = null, icon: Int? = null, clicks: List<String> = emptyList(), content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.BN)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides ctx, LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale), com.aktcl.aron.core.ui.LocalSrManualLook provides true) {
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


    @Composable private fun Picture() {
        // a stand-in for the campaign picture of shot 35: yellow frame, red field, a flame-coloured disc
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFFFFF200)).padding(16.dp)) {
            Box(Modifier.fillMaxSize().background(Color(0xFFC62828)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(160.dp).background(Color(0xFFFF7043), CircleShape).border(6.dp, Color(0xFFFFCA28), CircleShape))
            }
        }
    }
    @Composable private fun Kv() = ContentStepView("KV", "× বন্ধ করুন", {}) { Picture() }
    @Test fun kv() = shot("srp1b_content_kv", titleText = "বিক্রয়") { Kv() }
    @Test fun kvSunlight() = shot("srp1b_content_kv_sunlight", sunlight = true, titleText = "বিক্রয়") { Kv() }
    @Test fun kvFont20() = shot("srp1b_content_kv_font20", fontScale = 2.0f, titleText = "বিক্রয়") { Kv() }

    private val texts = SurveyTexts(
        bandTitle = "সার্ভে", answerWord = "উত্তরঃ", pickHint = "নির্বাচন করুন", yes = "হ্যাঁ", no = "না",
        requiredInfo = "আপনার (*) মার্ক করা প্রশ্নের উত্তর দিতে হবে", photo = "ছবি তুলুন", photoRetake = "আবার ছবি তুলুন", photoTaken = "ছবি সংরক্ষিত",
        skip = "সার্ভে ছাড়া এগিয়ে যান", submit = "সাবমিট →",
    )
    private fun rows(answered: Boolean, photo: Boolean) = listOf(
        SurveyRowUi("posm", "1", true, "আপনার আউটলেটে POSM আছে?", true, if (answered) "হ্যাঁ" else null, false, false),
    ) + (if (answered) listOf(SurveyRowUi("posm_photo", "1.1", true, "POSM এর ছবি তুলুন", false, null, true, photo)) else emptyList())
    @Composable private fun Survey(answered: Boolean, photo: Boolean, enabled: Boolean) =
        SurveyStepView("POSM Availability", texts, rows(answered, photo), submitEnabled = enabled, onPick = { _, _ -> }, onPhoto = {}, onSkip = {}, onSubmit = {})
    @Test fun survey() = shot("srp1b_content_survey", titleText = "বিক্রয়") { Survey(false, false, false) }
    @Test fun surveyAnswered() = shot("srp1b_content_survey_answered", titleText = "বিক্রয়") { Survey(true, true, true) }
    @Test fun surveyPhotoAsked() = shot("srp1b_content_survey_photo_asked", titleText = "বিক্রয়") { Survey(true, false, false) }
    @Test fun surveySunlight() = shot("srp1b_content_survey_sunlight", sunlight = true, titleText = "বিক্রয়") { Survey(true, true, true) }
    @Test fun surveyFont20() = shot("srp1b_content_survey_font20", fontScale = 2.0f, titleText = "বিক্রয়") { Survey(true, true, true) }
    @Test fun surveyConfirm() = shot("srp1b_content_survey_confirm", titleText = "বিক্রয়") {
        Survey(true, true, true)
        com.aktcl.aron.feature.outlet.SurveyConfirmDialog("আপনি কি সার্ভে জমা দেওয়ার বিষয়ে নিশ্চিত?", "হ্যাঁ", "না", {}, {})
    }

    /** The opened drop-down of the manual (43, 44): the inside of the dialog (see `PickerSheet`), over the grey page. */
    private val clusters = listOf("গুলশান-১", "গুলশান-২", "বনানী", "বারিধারা", "নিকেতন").mapIndexed { i, l -> com.aktcl.aron.feature.outlet.PickItem("c$i", l) }
    @Composable private fun Sheet(query: String) = Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        com.aktcl.aron.feature.outlet.PickerSheet("ক্লাস্টার", "অনুসন্ধান", clusters, query, {}, "কিছু পাওয়া যায়নি", "মুছুন", {})
    }
    @Test fun clusterPickerOpen() = shot("srp1b_cluster_picker_open", titleText = "ক্লাস্টার") { Sheet("") }
    @Test fun clusterPickerFiltered() = shot("srp1b_cluster_picker_filtered", titleText = "ক্লাস্টার") { Sheet("গুলশান") }
    @Test fun clusterPickerNone() = shot("srp1b_cluster_picker_none", titleText = "ক্লাস্টার") { Sheet("zzz") }
}
