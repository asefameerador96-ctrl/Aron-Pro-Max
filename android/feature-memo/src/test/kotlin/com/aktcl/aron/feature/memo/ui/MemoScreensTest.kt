package com.aktcl.aron.feature.memo.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.feature.memo.domain.DaySummaryCalculator
import com.aktcl.aron.feature.memo.domain.MemoItem
import com.aktcl.aron.feature.memo.domain.MemoMenu
import com.aktcl.aron.feature.memo.domain.StoredMemo
import com.aktcl.aron.feature.memo.domain.SummaryLine
import com.aktcl.aron.feature.memo.domain.SummaryMemo
import com.aktcl.aron.feature.memo.domain.SummarySku
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MemoScreensTest {
    @get:Rule val compose = createComposeRule()
    private val memo = StoredMemo("m1", "sr1-261005-001", 1, "2026-10-05", "2026-10-05T04:00:00.000Z", null, listOf(MemoItem(105, 3, 28_000, 84_000)), emptyList(), emptyList(), 84_000, 0, 0, 0, 84_000, 34_000, 50_000)

    @Test fun menuListsTheMemoTotalAndOpensIt() {
        var opened: String? = null
        compose.setContent { AronTheme(AppLanguage.EN) { MemoMenuScreen(MemoMenu.rows(listOf(memo)), { "Rifat Store" }, { opened = it.memoUuid }) } }
        compose.onNodeWithText("84.00", substring = true).assertExists() // MoneyText joins the sign with a no-break space
        compose.onNodeWithText("Rifat Store").performClick()
        assertEquals("m1", opened)
    }

    @Test fun markPaidAsksFirstAndHidesWhenNothingIsOwed() {
        var paid = 0
        compose.setContent { AronTheme(AppLanguage.EN) { MemoDetailView(MemoMenu.detail(memo), true, 50_000, { "FB" }, {}, {}, { paid++ }, skuUnit = { "dozen" }) } }
        compose.onNodeWithText("3 dozen").assertExists() // the quantity carries its unit word (screens-sr s9)
        compose.onNodeWithText("Due: 50.00 ৳").assertExists()
        compose.onNodeWithText("Mark paid").performScrollTo().performClick() // the credit box sits under the tables
        assertEquals(0, paid)
        compose.onNodeWithText("Yes").performClick()
        assertEquals(1, paid)
    }

    @Test fun markPaidIsAbsentWhenSettled() {
        compose.setContent { AronTheme(AppLanguage.EN) { MemoDetailView(MemoMenu.detail(memo), false, 0, { "FB" }, {}, {}, {}) } }
        compose.onNodeWithText("Mark paid").assertDoesNotExist()
    }

    @Test fun summaryShowsTheSeededGrandTotal() {
        val m = SummaryMemo("a", null, listOf(SummaryLine(103, 375, 4_687_500)), emptyList(), 0, 4_391_000)
        val s = DaySummaryCalculator.compute(listOf(m), mapOf(103L to SummarySku(103, "lighter")), mapOf(103L to 400L))
        compose.setContent { AronTheme(AppLanguage.EN) { SummaryScreen(s, { "Aster" }, {}) } }
        compose.onNodeWithText("4,391.00", substring = true).assertExists() // the grand total in the bar summary slot
        compose.onNodeWithText("− 296.50 ৳").assertExists()
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HomeCardsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun kpiStripShowsSixOfSixtyAndTenPercent() {
        val k = com.aktcl.aron.feature.memo.domain.KpiStripBuilder.build(60, 6, 6, mapOf(103L to 400L), mapOf(103L to 25L), mapOf(103L to SummarySku(103, "lighter"))) { "piece" }
        compose.setContent { AronTheme(AppLanguage.EN) { KpiStripView(k, { it }, { it }) } }
        compose.onNodeWithText("Outlets visited").assertExists(); compose.onNodeWithText("6/60").assertExists()
        compose.onNodeWithText("Strike rate").assertExists(); compose.onNodeWithText("10.00%").assertExists()
        compose.onNodeWithText("Non-visit").assertExists(); compose.onNodeWithText("54").assertExists()
        compose.onNodeWithText("lighter").assertExists(); compose.onNodeWithText("Issue 400 piece · Stock 25 piece").assertExists() // the unit beside every quantity (screens-sr s0)
    }

    @Test fun moneyCardsShowTheSeededGrandTotal() {
        val m = com.aktcl.aron.feature.memo.domain.HomeMoney(listOf(com.aktcl.aron.feature.memo.domain.MoneyCategory("lighter", 4_687_500, 437_500, 0, 0, 4_250_000)), 4_828_500, 437_500, 0, 0, 4_391_000)
        compose.setContent { AronTheme(AppLanguage.EN) { MoneyCardsView(m, { it }) } }
        compose.onNodeWithText("4,828.50", substring = true).assertExists()
        compose.onNodeWithText("− 437.50 ৳").assertExists() // a true minus and the word, never colour alone
        compose.onNodeWithText("4,391.00", substring = true).assertExists()
    }

    @Test fun journeyShowsStatusPerPlannedOutlet() {
        val j = com.aktcl.aron.feature.memo.domain.JourneyBuilder.build(
            listOf(com.aktcl.aron.feature.memo.domain.PlannedOutlet(1, "Rifat Store", 1), com.aktcl.aron.feature.memo.domain.PlannedOutlet(2, "Banani Mart", 2)),
            listOf(com.aktcl.aron.feature.memo.domain.JourneyVisit(1, "sold")),
        )
        compose.setContent { AronTheme(AppLanguage.EN) { JourneyScreen(j) } }
        // the status word on the row and the same word as a stat-card label
        compose.onAllNodesWithText("Sold").assertCountEquals(2); compose.onAllNodesWithText("Not visited").assertCountEquals(2)
        compose.onNodeWithText("Visited").assertExists(); compose.onNodeWithText("Planned 2").assertExists()
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HistoryScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun memo(id: String, date: String, net: Long) = StoredMemo(id, "no-$id", 1, date, "${date}T04:00:00.000Z", null, emptyList(), emptyList(), emptyList(), net, 0, 0, 0, net, net, 0)

    @Test fun footerEqualsTheSumOfTheRowsAndFallbackShowsTheBanner() {
        val h = com.aktcl.aron.feature.memo.domain.SaleHistoryBuilder.build(listOf(memo("a", "2026-10-05", 10_010), memo("b", "2026-10-04", 20_020)), 1, fromServer = true)
        compose.setContent { AronTheme(AppLanguage.EN) { SaleHistoryScreen(h, false) } }
        compose.onNodeWithText("30.03", substring = true).assertExists()
        compose.onAllNodesWithText("10.01", substring = true).assertCountEquals(2) // the day subtotal and the memo row
        compose.onNodeWithText("No connection: showing the last 7 days from this phone, or the server list when it was loaded.").assertExists()
    }

    @Test fun emptyOutletShowsTheEmptyState() {
        compose.setContent { AronTheme(AppLanguage.EN) { SaleHistoryScreen(com.aktcl.aron.feature.memo.domain.SaleHistoryBuilder.build(emptyList(), 1), false) } }
        compose.onNodeWithText("No sales for this outlet.").assertExists()
    }
}
