package com.aktcl.aron.feature.amo

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.rules.EntrySpec
import com.aktcl.aron.rules.QtyUnit
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** L2 (lead gate on f35466e0): the previous-sale local fallback never truncates a pack count, says it is the phone's copy even when empty, and counts unreadable server lines. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PreviousSalesFallbackTest {
    @get:Rule val compose = createComposeRule()

    private val skus = mapOf(
        11L to SkuInfo("MaxR-10S", null, "cigarette", EntrySpec(QtyUnit.PACK, 10)),
        12L to SkuInfo("Lighter", null, "lighter", EntrySpec(QtyUnit.PACK, 25)),
    )

    @Test fun aNonWholePackCountIsShownInBaseUnitsNotTruncatedAndAWholeOneInPackets() {
        val memos = listOf(LocalMemo("m1", null, listOf(PreviousSales.LocalMemoLine(11, 85, 1), PreviousSales.LocalMemoLine(12, 50, 1))))
        val h = PreviousSales.fromLocal("2026-11-06", memos, skus)
        val maxr = h.lines.single { it.skuId == 11L }; val lighter = h.lines.single { it.skuId == 12L }
        assertEquals(0, java.math.BigDecimal(85).compareTo(maxr.qtyReport)); assertEquals("stick", maxr.reportUnit) // 8.5 packets is not 8 packets
        assertEquals(0, java.math.BigDecimal(2).compareTo(lighter.qtyReport)); assertEquals("box", lighter.reportUnit) // 50 lighters = 2 boxes of 25
    }

    @Test fun malformedServerLinesAreCountedNotSilentlyDropped() {
        val body = """{"lines":[
          {"sku":{"sku_id":11,"short_name":"A"},"qty_report":"1.000","report_unit":"packet","qty_base":10,"value_mtk":5},
          {"sku":{"short_name":"no id"},"qty_report":"1.000","value_mtk":5},
          "junk",
          {"sku":{"sku_id":13,"short_name":"B"},"qty_report":"x","value_mtk":5}]}"""
        val h = PreviousSales.parseServer(body, "2026-11-06")!!
        assertEquals(1, h.lines.size); assertEquals(3, h.unreadable)
    }

    private fun show(language: AppLanguage, history: SaleHistory) {
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent { CompositionLocalProvider(LocalContext provides context) { AronTheme(language, amo = true) { PreviousSalesContent("2026-11-06", "2026-11-06", history, onPick = {}) } } }
    }

    @Test fun anEmptyOfflineDayStillShowsTheLocalCopyNoteInBothLanguages() {
        show(AppLanguage.EN, SaleHistory("2026-11-06", emptyList(), SaleHistorySource.LOCAL))
        compose.onNodeWithTag(PreviousSalesTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithText("Offline: only your own memos from this phone are shown.").assertIsDisplayed()
    }

    @Test fun theUnreadableNoteIsShownInBangla() {
        show(AppLanguage.BN, SaleHistory("2026-11-06", emptyList(), SaleHistorySource.SERVER, unreadable = 3))
        compose.onNodeWithTag(PreviousSalesTags.UNREADABLE).assertIsDisplayed()
        compose.onNodeWithText("৩ টি লাইন পড়া যায়নি, তাই দেখানো হয়নি।").assertIsDisplayed()
    }
}
