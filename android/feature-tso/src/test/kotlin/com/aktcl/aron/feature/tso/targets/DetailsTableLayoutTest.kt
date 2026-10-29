package com.aktcl.aron.feature.tso.targets

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.feature.tso.CardLoad
import com.aktcl.aron.feature.tso.TsoLoginTheme
import com.aktcl.aron.rules.Bands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Target Details at 360 dp and the default font (screenshot 41), tso-manual A1 fix round: an item name wraps only between words
 * ("Black / Diamond / Advanced"), never inside one ("Advance" / "d"). Real text measurement (native graphics), as on a phone.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h1600dp")
class DetailsTableLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val stamp = "\"month\":\"2026-10\",\"as_of\":\"2026-10-25T09:35:00Z\",\"day\":\"2026-10-25\",\"tilldate_basis\":\"calendar_days\",\"last_updated_at\":null"
    private fun item(id: Long, name: String, t: Long, a: Long) =
        """{"product_level":"variant","product_id":$id,"name":"$name","unit":"stick","target_qty":"$t.000","achieved_qty":"$a.000","tilldate_target_qty":"0.000","remaining_qty":"0.000","category_code":"cigarette","target_qty_base":$t,"achieved_qty_base":$a,"tilldate_target_qty_base":null,"remaining_qty_base":0,"remaining_tilldate_qty_base":null,"achievement_pct_bp":null,"tilldate_achievement_pct_bp":null}"""

    /** The eight Cigarette items of screenshot 41. */
    private val names = listOf(
        "Maxim Regular" to (1000L to 350L), "Maxim Bolt" to (1000L to 330L), "Maxim Double Burst" to (10000L to 20L),
        "Marise Special Blend" to (10000L to 200L), "Black Diamond Double Filter" to (5000L to 30L), "Black Diamond Advanced" to (6000L to 30L),
        "Sunmoon American blend" to (2999L to 40L), "Supreme Signature" to (5000L to 30L),
    )

    @Test fun itemNamesWrapOnlyBetweenWordsAt360dp() {
        val json = """{$stamp,"node":{"type":"territory","id":3,"name":"Apsis"},"elapsed_days":25,"period_days":31,"rows":[""" +
            names.mapIndexed { i, (name, ta) -> item(100L + i, name, ta.first, ta.second) }.joinToString(",") + "]}"
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                TsoLoginTheme(AppLanguage.EN, loggedOut = false) {
                    TargetDetailsContent(CardLoad(TargetStatus.parse(json)), TargetTab.MONTHLY, Bands.DEFAULT, null, false, {}, {}, {})
                }
            }
        }
        compose.waitForIdle()
        var wrapped = 0
        names.forEach { (name, _) ->
            val node = compose.onNodeWithText(name, useUnmergedTree = true).fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
            assertNotNull("$name has a text layout", action)
            action!!.invoke(layouts)
            val layout = layouts.single()
            for (line in 0 until layout.lineCount - 1) {
                val end = layout.getLineEnd(line)
                assertEquals("'$name' breaks inside a word after line $line ('${name.substring(0, end)}')", ' ', name[end - 1])
            }
            if (layout.lineCount > 1) wrapped++
        }
        assertTrue("the long names still wrap (by words), as on screenshot 41", wrapped > 0)
    }
}
