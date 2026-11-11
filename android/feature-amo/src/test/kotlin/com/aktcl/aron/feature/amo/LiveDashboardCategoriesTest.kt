package com.aktcl.aron.feature.amo

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.UiLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Contract 1.11.1: the AMO members of `GET /v1/dashboards/login-submit` (geo/photo valid visits, `sales_by_category`) in the field units, exact, online and from the kept copy. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LiveDashboardCategoriesTest {
    @get:Rule val compose = createComposeRule()

    private val summary = """{"as_of":"2026-11-10T05:00:00Z","kpis":{"target_routes":2,"logged_in_routes":2,"login_pct":100.0,"sales_submitted_routes":0,"submit_pct_of_logged_in":0.0,"target_outlets":81,"visited_outlets":3,"successful_calls":1,"strike_rate_pct":1.23,"gross_mtk":125000,"net_mtk":120000,"geo_valid_pct":50.0,"force_sale_pct":0.0,"mock_visits":0,"suspicious_visits":0}}"""
    private val login = """{"kpis":{"target_routes":2,"logged_in_routes":2,"login_pct":100.0,"sales_submitted_routes":0,"submit_pct_of_logged_in":0.0},"not_logged_in":[],"logged_in_not_submitted":[{"route_id":3}],"submitted":[],
      "geo_valid_visits":2,"photo_valid_visits":1,"visits_total":3,
      "sales_by_category":[
        {"category_code":"cigarette","base_unit":"stick","qty_base":1000,"net_mtk":80000000,"report_unit":"pack","qty_report":"100.000","entry_unit":"pack","qty_entered":"62.500","memos":4},
        {"category_code":"lighter","base_unit":"piece","qty_base":75,"net_mtk":12500000,"qty_report":null,"entry_unit":"pack","qty_entered":"3.000","memos":null},
        {"category_code":"match","base_unit":"dozen","qty_base":9,"net_mtk":2800000,"entry_unit":"dozen","qty_entered":"9.000","memos":1},
        {"category_code":"bidi","base_unit":"stick","qty_base":40,"net_mtk":100000},
        {"base_unit":"stick","qty_base":1,"net_mtk":1},
        "junk"]}"""

    @Test fun categoriesAreExactTextInTheFieldUnitNeverAFloat() {
        val f = LiveDashboard.parse(summary, login)!!
        assertEquals(2L, f.geoValidVisits); assertEquals(1L, f.photoValidVisits); assertEquals(3L, f.visitsTotal)
        val by = f.categories.associateBy { it.code }
        assertEquals(4, f.categories.size) // the row without a category_code and the junk entry are skipped
        assertEquals("62.5", by.getValue("cigarette").qtyText); assertEquals("packet", by.getValue("cigarette").unitKey); assertEquals(4L, by.getValue("cigarette").memos)
        assertEquals("3", by.getValue("lighter").qtyText); assertEquals("box", by.getValue("lighter").unitKey); assertNull(by.getValue("lighter").memos) // Daily Tracking: null memos
        assertEquals("9", by.getValue("match").qtyText); assertEquals("dozen", by.getValue("match").unitKey)
        // no entry quantity from the server: the exact base quantity with its base unit, nothing approximated
        assertEquals("40", by.getValue("bidi").qtyText); assertEquals("stick", by.getValue("bidi").unitKey)
        assertEquals(80_000_000L, by.getValue("cigarette").netMtk)
    }

    @Test fun aMissingBaseUnitUsesTheCategorysAndAnUnknownEntryUnitFallsBackToTheExactBaseQuantity() {
        val j = """{"sales_by_category":[{"category_code":"lighter","qty_base":75,"net_mtk":1},{"category_code":"cigarette","base_unit":"stick","qty_base":30,"net_mtk":1,"entry_unit":"box","qty_entered":"3.000"}]}"""
        val by = LiveDashboard.categories(kotlinx.serialization.json.Json.parseToJsonElement(j) as kotlinx.serialization.json.JsonObject).associateBy { it.code }
        assertEquals("75", by.getValue("lighter").qtyText); assertEquals("piece", by.getValue("lighter").unitKey)
        assertEquals("30", by.getValue("cigarette").qtyText); assertEquals("stick", by.getValue("cigarette").unitKey)
    }

    @Test fun anOlderServerWithoutTheMembersGivesNullsAndNoCategories() {
        val f = LiveDashboard.parse(summary, """{"kpis":{"target_routes":2},"not_logged_in":[],"logged_in_not_submitted":[],"submitted":[]}""")!!
        assertNull(f.geoValidVisits); assertNull(f.photoValidVisits); assertNull(f.visitsTotal); assertTrue(f.categories.isEmpty())
    }

    @Test fun decimalTextKeepsTheDigitsExact() {
        assertEquals("10", LiveDashboard.decimalText("10.000")); assertEquals("0.125", LiveDashboard.decimalText("0.125")); assertEquals("100", LiveDashboard.decimalText("100.000"))
        assertEquals("0", LiveDashboard.decimalText("0.000")); assertNull(LiveDashboard.decimalText("abc"))
    }

    private fun show(language: AppLanguage, stale: Boolean, failed: Boolean = false) {
        val figures = LiveDashboard.parse(summary, login)
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        val loc = if (language == AppLanguage.BN) UiLocale.BN else UiLocale.EN
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(language, amo = true) { LiveDashboardContent(figures, "10/11 11:00", "Zone", false, stale, failed, { Formats.money(it, loc) }, {}) }
            }
        }
    }

    @Test fun theSalesCardShowsEveryCategoryInEnglishWithMoneyAndMemos() {
        show(AppLanguage.EN, stale = false)
        compose.onNodeWithText("62.5 packets").assertIsDisplayed()
        compose.onNodeWithText("${Formats.money(80_000_000, UiLocale.EN)} · 4 memos").assertIsDisplayed()
        compose.onNodeWithText("3 boxes").assertExists()
        compose.onNodeWithText(Formats.money(12_500_000, UiLocale.EN)).assertExists() // no memo count when the server sends null
        compose.onNodeWithText("2 of 3").assertExists(); compose.onNodeWithText("1 of 3").assertExists() // geo-valid and photo-valid visits (below the fold)
    }

    @Test fun theBanglaScreenUsesBanglaDigitsAndWordsAndTheKeptCopyKeepsTheFigures() {
        show(AppLanguage.BN, stale = true) // offline: the last answer kept on the phone, said so
        compose.onNodeWithText("সিগারেট").assertIsDisplayed()
        compose.onNodeWithText("৬২.৫", substring = true).assertIsDisplayed()
        compose.onNodeWithText("ছবি ভ্যালিডেশন").assertExists(); compose.onNodeWithText("৩ এর মধ্যে ২").assertExists()
    }
}
