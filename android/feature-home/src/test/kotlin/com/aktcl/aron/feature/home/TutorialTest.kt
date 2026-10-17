package com.aktcl.aron.feature.home

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** F-SR-048: the list is read from the bundle section on the phone; playback is a tap, online only, never automatic. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class TutorialTest {
    @get:Rule val compose = createComposeRule()

    private val section = """[
      {"tutorial_id":2,"kind":"manual","title_en":"Price list manual","title_bn":null,"url":"https://cdn.example.com/m.pdf","bytes":1000,"duration_s":null,"sort":2},
      {"tutorial_id":1,"kind":"video","title_en":"How to sell","title_bn":"কীভাবে বিক্রি করবেন","url":"https://cdn.example.com/v.mp4","bytes":9000,"duration_s":125,"sort":1},
      {"tutorial_id":3,"kind":"video","title_en":"Bad scheme","url":"javascript:alert(1)","sort":3},
      {"tutorial_id":4,"kind":"video","title_en":"","url":"https://x.example.com/a.mp4","sort":4},
      {"kind":"video"}
    ]"""

    private fun show(language: AppLanguage, content: @androidx.compose.runtime.Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { content() } } }
        compose.waitForIdle()
    }

    @Test fun jsonIsSortedUsesTheBanglaTitleAndDropsBadRowsAndNonHttpLinks() {
        val en = TutorialJson.parse(section, bangla = false)
        assertEquals(listOf("1", "2"), en.map { it.id }); assertEquals("How to sell", en[0].title)
        val bn = TutorialJson.parse(section, bangla = true)
        assertEquals("কীভাবে বিক্রি করবেন", bn[0].title); assertEquals("Price list manual", bn[1].title) // no Bangla title: English, never blank
        assertTrue(en[1].manual); assertEquals(125, en[0].durationS); assertNull(en[1].durationS)
        assertTrue(TutorialJson.parse(null, false).isEmpty()); assertTrue(TutorialJson.parse("{", false).isEmpty()); assertTrue(TutorialJson.parse("[]", true).isEmpty())
    }

    @Test fun rowsShowKindAndDurationAndPlayOnTapOnlineWithTheLink() {
        var played: TutorialItem? = null
        show(AppLanguage.EN) { TutorialListContent(TutorialJson.parse(section, false), true, { played = it }) }
        compose.onNodeWithText("Video  ·  3 min  ·  Play online").assertExists(); compose.onNodeWithText("Manual  ·  Play online").assertExists()
        assertNull("nothing plays by itself", played)
        compose.onNodeWithTag(OnboardingTags.tutorial("1")).performClick()
        assertEquals("https://cdn.example.com/v.mp4", played?.url)
    }

    @Test fun offlineRowsAreShownButNotTappableAndTheBannerSaysWhy() {
        var played: TutorialItem? = null
        show(AppLanguage.BN) { TutorialListContent(TutorialJson.parse(section, true), false, { played = it }) }
        compose.onNodeWithText("কীভাবে বিক্রি করবেন").assertExists()
        compose.onNodeWithTag(OnboardingTags.tutorial("1")).performClick(); assertNull(played)
    }

    @Test fun banglaDurationUsesBengaliDigits() {
        show(AppLanguage.BN) { TutorialListContent(TutorialJson.parse(section, true), true, {}) }
        compose.onNodeWithText("ভিডিও  ·  ৩ মিনিট  ·  অনলাইনে চালান", substring = true).assertExists()
    }
}
