package com.aktcl.aron.amo

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Contract 1.11.1 offline path (lead, train 85), on the Live Data screen of the AMO team build (A3 replaced the old Live Dashboard host): it reads
 * `GET /v1/dashboards/login-submit`. The server is out of reach here (api.aron.test does not answer), so the screen shows the last answer kept on this
 * phone and says so; the counts come from that kept copy. With no kept copy it says there is nothing, instead of inventing figures. (The field-unit
 * figures of the route variant are proved by `LiveDataTest` and the `amo_team_live_route_*` goldens.)
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp")
class AmoLiveDashboardHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var f: AmoLockFixture

    @Before fun setUp() { f = AmoLockFixture(app, latePolicy = "accept_and_flag") }
    @After fun tearDown() { f.close() }

    private val kept = """{"as_of":"2026-11-10T05:00:00Z","kpis":{"target_routes":2,"logged_in_routes":2,"login_pct":100.0,"sales_submitted_routes":0,"submit_pct_of_logged_in":0.0,"target_outlets":81,"visited_outlets":3,"successful_calls":1,"strike_rate_pct":1.23,"gross_mtk":125000000,"net_mtk":120000000,"geo_valid_pct":66.67,"force_sale_pct":0.0,"mock_visits":0,"suspicious_visits":0},
      "not_logged_in":[],"logged_in_not_submitted":[{"route_id":3}],"submitted":[],"geo_valid_visits":2,"photo_valid_visits":1,"visits_total":3,
      "sales_by_category":[{"category_code":"cigarette","base_unit":"stick","qty_base":1250,"net_mtk":80000000,"entry_unit":"pack","qty_entered":"62.500","memos":4}]}"""

    private fun show() {
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        f.own(ActivityScenario.launch(ComponentActivity::class.java)).onActivity { activity ->
            val ctx = AppLocale.wrap(activity, AppLanguage.EN)
            activity.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(AppLanguage.EN) { AmoLiveDataHost(f.day, onBack = {}) } } }
        }
        compose.waitForIdle()
    }

    private fun keep(json: String) {
        val dir = java.io.File(app.cacheDir, "amo_api_${f.day.userId}").apply { mkdirs() }
        java.io.File(dir, "live_0_${f.date}").writeText(json) // the key AmoApi.liveDashboard keeps its last answer under
    }

    private fun shown(text: String) = runCatching { compose.onNodeWithText(text, substring = true).assertExists() }.isSuccess

    @Test fun offlineTheKeptAnswerShowsItsCountsAndSaysItIsAKeptCopy() {
        keep(kept)
        show()
        compose.waitUntil(8_000) { shown("List from the last time you were online.") }
        compose.onNodeWithText("List from the last time you were online.").assertExists() // the kept-copy notice
        compose.onAllNodesWithText("81")[0].assertExists() // target outlets of the kept answer
        compose.onNodeWithText("No figures for this scope yet.").assertDoesNotExist()
        // A2 #3's field-unit and bare-count assertions (62.5 packets, Cigarette, Geo/Photo validation, Total outlet visits) belong to `LiveDashboardContent`,
        // which no host uses any more: this Live Data screen shows the day's category columns in BASE units as screenshot 39 does, and they are
        // asserted by `LiveDashboardCategoriesTest` (content level, EN and BN) and `LiveDataTest`; moved, not dropped (D-AMO-CALLS-25).
    }

    @Test fun offlineWithNoKeptAnswerShowsNothingInsteadOfInventedFigures() {
        show()
        compose.waitUntil(8_000) { shown("No figures for this scope yet.") }
        compose.onNodeWithText("List from the last time you were online.").assertDoesNotExist() // no copy, no kept-copy note
    }

    /** Lead (train 87): a previous-sale line of a WITHDRAWN lighter SKU still reads "box" (the category comes from every SKU, not only the active ones). */
    @Test fun aWithdrawnLighterSkuInAKeptSalesAnswerStillShowsBoxes() {
        kotlinx.coroutines.runBlocking {
            f.db.referenceDao().insertSkus(listOf(
                com.aktcl.aron.core.database.entity.SkuEntity(999, "S999", 999, "lighter", "SL old", "SL old", null, "piece", 25, "pack", null, "1.000", 999, "withdrawn", 1),
            ))
            val dir = java.io.File(app.cacheDir, "amo_api_${f.day.userId}").apply { mkdirs() }
            java.io.File(dir, "sales_50001_${f.date}").writeText("""{"lines":[{"sku":{"sku_id":999,"short_name":"SL old"},"qty_report":"3.000","report_unit":"pack","qty_base":75,"value_mtk":1}]}""")
            assertEquals("not among the active SKUs", false, f.db.referenceDao().activeSkus().any { it.skuId == 999L })
            val h = f.day.saleHistory(50001, f.date)
            assertEquals(com.aktcl.aron.feature.amo.SaleHistorySource.KEPT_COPY, h.source)
            assertEquals("lighter", h.lines.single().categoryCode)
            assertEquals("box", com.aktcl.aron.feature.amo.UnitWords.key(h.lines.single().reportUnit, h.lines.single().categoryCode))
        }
    }
}
