package com.aktcl.aron.feature.programmes

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.rules.UiLocale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class KpiScreensTest {
    @get:Rule val compose = createComposeRule()

    private fun brand(id: Int, vt: Long, va: Long, mt: Long, ma: Long, today: String = "null") =
        """{"brand_id":$id,"volume_target":$vt,"volume_achieved":$va,"volume_bp":${if (vt > 0) va * 10000 / vt else "null"},"memo_target":$mt,"memo_achieved":$ma,"memo_bp":${if (mt > 0) ma * 10000 / mt else "null"},"kpi_alloc_mtk":100000,"kpi_today_mtk":$today,"by_pack":[{"pack_factor":20,"target":$vt,"achieved":$va}]}"""
    private val cats = """[{"category_code":"match","volume_target":30,"volume_achieved":12,"volume_bp":4000,"by_pack":[{"pack_factor":1,"target":30,"achieved":12}]},{"category_code":"lighter","volume_target":100,"volume_achieved":0,"volume_bp":null,"by_pack":[{"pack_factor":25,"target":100,"achieved":0}]}]"""
    private val data = KpiModel.build(listOf(RouteFigures.parse(
        """{"route_id":7,"kpi":{"month":"2026-11","through_date":"2026-11-17","as_of":"2026-11-18T01:00:00Z","allocated_mtk":5700000,"achieved_mtk":null,"achievement_bp":null,
          "volume":{"target":2000,"achieved":600,"bp":3000},"memo":{"target":100,"achieved":30,"bp":3000},"brands":[${brand(1, 1000, 300, 50, 20, "420000")},${brand(2, 1000, 300, 50, 10)}],
          "brands_truncated":false,"categories":$cats,"cpr":{"target_bp":5000,"alloc_mtk":600000,"achieved_bp":null}},
          "calendar":{"month":"2026-11","working_days":30,"elapsed_working_days_before_today":10,"remaining_working_days_after_today":20,"today_is_working":true,"last_month_working_days":26,"through_date":"2026-11-17","as_of":"x"}}""")!!),
        mapOf(1L to BrandName("ARIS", "অ্যারিস"), 2L to BrandName("Avon", null)), mapOf(1L to "cigarette", 2L to "cigarette"))

    private fun show(language: AppLanguage, content: @androidx.compose.runtime.Composable (UiLocale) -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        val loc = if (language == AppLanguage.BN) UiLocale.BN else UiLocale.EN
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { content(loc) } } }
        compose.waitForIdle()
    }

    @Test fun theMonthCardShowsAllocatedKpiAndDashesForTheFiguresTheOwnerHasNotDefined() {
        show(AppLanguage.EN) { KpiDashboardScreen(data, it) }
        compose.onNodeWithTag(KpiTags.MONTH_CARD).assertIsDisplayed()
        compose.onNodeWithText("November 2026").assertExists(); compose.onNodeWithText("10 / 30").assertExists()
        compose.onNodeWithText("৳5,700").assertExists()
        compose.onNodeWithTag(KpiTags.ACHIEVED).assert(hasAnyDescendant(hasText("—"))) // the owner has not defined achieved KPI: a dash, never a number
        compose.onNodeWithTag(KpiTags.ACHIEVEMENT).assert(hasAnyDescendant(hasText("—")))
        compose.onNodeWithText("Server figures through 2026-11-17").assertExists()
    }

    @Test fun banglaShowsTheManualsBanglaLabelsAndDigits() {
        show(AppLanguage.BN) { KpiDashboardScreen(data, it) }
        compose.onNodeWithText("নভেম্বর ২০২৬").assertExists(); compose.onNodeWithText("১০ / ৩০").assertExists(); compose.onNodeWithText("৳৫,৭০০").assertExists()
        compose.onNodeWithTag(KpiTags.ALLOCATED).assert(hasAnyDescendant(hasText("বরাদ্দকৃত কেপিআই"))); compose.onNodeWithTag(KpiTags.ACHIEVEMENT).assert(hasAnyDescendant(hasText("অর্জন")))
        compose.onAllNodes(hasText("অ্যারিস")).fetchSemanticsNodes().isNotEmpty().let { assertEquals(true, it) }
    }

    @Test fun volumeAndMemoSectionsShowTotalsAndTheSeeAllButtonsOpenTheLists() {
        var opened: KpiKind? = null
        show(AppLanguage.EN) { KpiDashboardScreen(data, it, onSeeAll = { k -> opened = k }) }
        compose.onNodeWithTag(KpiTags.section(KpiKind.VOLUME)).assertExists(); compose.onNodeWithTag(KpiTags.section(KpiKind.MEMO)).assertExists(); compose.onNodeWithTag(KpiTags.section(KpiKind.BLM)).assertExists()
        compose.onNodeWithTag(KpiTags.seeAll(KpiKind.MEMO)).performScrollTo().performClick(); assertEquals(KpiKind.MEMO, opened)
        compose.onNodeWithTag(KpiTags.seeAll(KpiKind.BLM)).performScrollTo().performClick(); assertEquals(KpiKind.BLM, opened)
    }

    @Test fun todayOnThisPhoneAndTheUnconfirmedCountAreSeparateLinesNeverInATotal() {
        show(AppLanguage.EN) { KpiDashboardScreen(data, it, volumeToday = KpiToday(FieldQty(5_000, "packet"), 2), memoToday = KpiToday(null, 2), unconfirmedEarlier = 3) }
        compose.onNodeWithText("Today on this phone: 5 packet (not in the figures above)").assertExists()
        compose.onNodeWithText("Today on this phone: 2 memo (not in the figures above)").assertExists()
        compose.onNodeWithTag(KpiTags.UNCONFIRMED).assertExists()
    }

    @Test fun noKpiIsTheEmptyState() {
        show(AppLanguage.EN) { KpiDashboardScreen(null, it, offline = true) }
        compose.onNodeWithTag(KpiTags.EMPTY).assertIsDisplayed(); compose.onNodeWithTag(KpiTags.OFFLINE).assertExists()
    }

    @Test fun brandWiseKpiWithoutServerKpiSaysTheServerHasNotSentItNeverNoKpiIsSet() {
        show(AppLanguage.EN) { BrandKpiScreen(null, KpiKind.VOLUME, it) }
        compose.onNodeWithTag(KpiTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithText("The server has not sent the KPI figures yet.", substring = true).assertExists()
        assertEquals(0, compose.onAllNodesWithText("No KPI is set for this month.").fetchSemanticsNodes().size)
    }

    @Test fun theVolumeListNamesAnOmittedMatchOrLighterBrandAndTheMemoListDoesNot() {
        val left = data!!.copy(brands = data.brands + KpiBrandRow(77, BrandName("Safe Match", null), null, null, null, 5, null, null, 0, "match"))
        show(AppLanguage.EN) { BrandKpiScreen(left, KpiKind.VOLUME, it) }
        compose.onNodeWithTag(KpiTags.LEFT_OUT).assertExists()
        compose.onNodeWithText("A match or lighter brand whose figures the phone cannot match is not listed here; see it under Memo.").assertExists()
    }

    @Test fun theMemoListHasNoLeftOutNote() {
        val left = data!!.copy(brands = data.brands + KpiBrandRow(77, BrandName("Safe Match", null), null, null, null, 5, null, null, 0, "match"))
        show(AppLanguage.EN) { BrandKpiScreen(left, KpiKind.MEMO, it) }
        assertEquals(0, compose.onAllNodesWithTag(KpiTags.LEFT_OUT).fetchSemanticsNodes().size)
    }

    @Test fun brandWiseKpiShowsTheSummaryCardTheBrandsAndADashForKpiTodayUntilDefined() {
        show(AppLanguage.EN) { BrandKpiScreen(data, KpiKind.VOLUME, it) }
        compose.onNodeWithText("KPI on - Cigarette Sales").assertExists(); compose.onNodeWithText("FMC BRANDS").assertExists()
        compose.onNodeWithTag(KpiTags.brand(1)).assertIsDisplayed()
        compose.onNodeWithText("Target 50 packet - KPI Today ৳420").assertExists() // the volume target in packets, the unit printed
        compose.onNodeWithText("Target 50 packet - KPI Today —").assertExists() // brand 2: the owner has not defined it
    }

    @Test fun brandWiseKpiOnMemoShowsTheMemoTargetAndTitle() {
        show(AppLanguage.EN) { BrandKpiScreen(data, KpiKind.MEMO, it) }
        compose.onNodeWithText("KPI on - Memo").assertExists()
        compose.onNodeWithText("Target 50 memo - KPI Today ৳420").assertExists() // the memo target (50 memos), not the volume target
    }

    @Test fun brandWiseKpiOnBlmListsTheCategories() {
        show(AppLanguage.EN) { BrandKpiScreen(data, KpiKind.BLM, it) }
        compose.onNodeWithText("KPI on - BLM Sales").assertExists(); compose.onNodeWithTag(KpiTags.category("match")).assertExists(); compose.onNodeWithTag(KpiTags.category("lighter")).assertExists()
    }

    @Test fun anIncompleteSeveralRouteKpiSaysSoOnBothScreens() {
        val inc = data!!.copy(incomplete = true)
        show(AppLanguage.EN) { KpiDashboardScreen(inc, it) }
        compose.onNodeWithTag(KpiTags.INCOMPLETE).assertExists()
    }

    @Test fun aMixedBrandOfA125ServerPrintsEachCategoryInItsOwnUnitNotUnitNotClear() {
        val mixed = KpiBrandRow(77, BrandName("Mixed Co", null), null, null, null, 5, null, null, 0, null,
            listOf(KpiCategoryVolume("cigarette", FieldQty(100_000, "packet")), KpiCategoryVolume("match", FieldQty(30_000, "dozen"))))
        show(AppLanguage.EN) { BrandKpiScreen(data!!.copy(brands = data.brands + mixed), KpiKind.VOLUME, it) }
        compose.onNodeWithText("Target 100 packet · 30 dozen - KPI Today —").assertExists()
        assertEquals(0, compose.onAllNodesWithTag(KpiTags.LEFT_OUT).fetchSemanticsNodes().size)
    }
}
