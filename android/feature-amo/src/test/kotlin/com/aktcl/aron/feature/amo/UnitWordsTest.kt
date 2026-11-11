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
        assertEquals("packet", UnitWords.key("pack")); assertEquals("packet", UnitWords.key("pack", "cigarette")); assertEquals("packet", UnitWords.key("pack", "bidi"))
        assertEquals("box", UnitWords.key("pack", "lighter")); assertEquals("dozen", UnitWords.key("pack", "match")) // the server's pack is per category
        assertEquals("box", UnitWords.key("box")); assertEquals("dozen", UnitWords.key("dozen"))
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

    @Test fun aServerPreviousSaleLineWithReportUnitPackShowsTheCategorysWordAndNoneInventedWhenMissing() {
        // the server sends `pack` for cigarettes, bidi AND lighters (AmoTeamReads: pack else base unit); the phone's SKU list gives the category
        val body = """{"lines":[{"sku":{"sku_id":11,"short_name":"MaxR-10S"},"qty_report":"10.000","report_unit":"pack","qty_base":100,"value_mtk":80000000},
          {"sku":{"sku_id":12,"short_name":"SL"},"qty_report":"3.000","report_unit":"pack","qty_base":75,"value_mtk":1},
          {"sku":{"sku_id":13,"short_name":"FB"},"qty_report":"4.000","report_unit":"dozen","qty_base":4,"value_mtk":1},
          {"sku":{"sku_id":14,"short_name":"NoUnit"},"qty_report":"7.000","qty_base":7,"value_mtk":1},
          {"sku":{"sku_id":15,"short_name":"One"},"qty_report":"1.000","report_unit":"pack","qty_base":20,"value_mtk":1}]}"""
        val skus = mapOf(11L to "cigarette", 12L to "lighter", 13L to "match", 14L to "cigarette", 15L to "cigarette")
        val h = PreviousSales.withCategories(PreviousSales.parseServer(body, "2026-11-10")!!, skus)
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.EN)
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(AppLanguage.EN) { PreviousSalesContent("2026-11-10", "2026-11-10", h, onShift = {}) } } }
        compose.onNodeWithText("10 packets").assertExists()
        compose.onNodeWithText("3 boxes").assertExists() // a lighter is a box of 25, not packets
        compose.onNodeWithText("4 dozen").assertExists()
        compose.onNodeWithText("7").assertExists() // no unit sent: the quantity alone, no trailing space, none invented
        compose.onNodeWithText("7 stick", substring = true).assertDoesNotExist()
        compose.onNodeWithText("1 packet").assertExists() // English singular for exactly one
    }
}
