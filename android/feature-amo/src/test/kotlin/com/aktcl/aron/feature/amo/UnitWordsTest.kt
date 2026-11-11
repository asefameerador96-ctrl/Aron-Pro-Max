package com.aktcl.aron.feature.amo

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Lead (foundation checker): the server's `report_unit`/`entry_unit` codes (pack, box, dozen, piece) show the right word; an unknown code shows itself, never "stick". */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UnitWordsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theServersCodesMapToTheFieldWordsAndAnUnknownCodeIsNull() {
        assertEquals("packet", UnitWords.key("pack")); assertEquals("box", UnitWords.key("box")); assertEquals("dozen", UnitWords.key("dozen"))
        assertEquals("piece", UnitWords.key("piece")); assertEquals("stick", UnitWords.key("stick")); assertEquals("packet", UnitWords.key("packet"))
        assertEquals("packet", UnitWords.key(" Pack ")); assertNull(UnitWords.key("carton")); assertNull(UnitWords.key("")); assertNull(UnitWords.key(null))
    }

    private fun words(language: AppLanguage, vararg codes: String) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(language) { androidx.compose.foundation.layout.Column { codes.forEach { androidx.compose.material3.Text("[" + fieldUnitWord(it) + "]") } } }
            }
        }
    }

    @Test fun englishShowsPacketBoxDozenPieceAndTheRawCodeWhenUnknown() {
        words(AppLanguage.EN, "pack", "box", "dozen", "piece", "carton")
        listOf("[packets]", "[boxes]", "[dozen]", "[pieces]", "[carton]").forEach { compose.onNodeWithText(it).assertExists() } // pack is packets, not sticks; carton stays carton
        compose.onNodeWithText("[stick]", substring = true).assertDoesNotExist(); compose.onNodeWithText("[sticks]", substring = true).assertDoesNotExist()
    }

    @Test fun banglaPackIsPacketNotStick() {
        words(AppLanguage.BN, "pack")
        compose.onNodeWithText("[প্যাকেট]").assertExists()
    }

    @Test fun aServerPreviousSaleLineWithReportUnitPackShowsPackets() {
        val body = """{"lines":[{"sku":{"sku_id":11,"short_name":"MaxR-10S"},"qty_report":"10.000","report_unit":"pack","qty_base":100,"value_mtk":80000000},{"sku":{"sku_id":12,"short_name":"SL"},"qty_report":"1.000","report_unit":"box","qty_base":25,"value_mtk":1}]}"""
        val h = PreviousSales.parseServer(body, "2026-11-10")!!
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(AppLanguage.EN) { PreviousSalesContent("2026-11-10", "2026-11-10", h, onShift = {}) } } }
        compose.onNodeWithText("10 packets").assertExists(); compose.onNodeWithText("1 boxes").assertExists()
    }
}
