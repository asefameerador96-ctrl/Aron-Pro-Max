package com.aktcl.aron.feature.memo.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
@Config(sdk = [36])
class MemoScreensTest {
    @get:Rule val compose = createComposeRule()
    private val memo = StoredMemo("m1", "sr1-261005-001", 1, "2026-10-05", "2026-10-05T04:00:00.000Z", null, listOf(MemoItem(105, 3, 28_000, 84_000)), emptyList(), emptyList(), 84_000, 0, 0, 0, 84_000, 34_000, 50_000)

    @Test fun menuListsTheMemoTotalAndOpensIt() {
        var opened: String? = null
        compose.setContent { AronTheme(AppLanguage.EN) { MemoMenuScreen(MemoMenu.rows(listOf(memo)), { "Rifat Store" }, { opened = it.memoUuid }) } }
        compose.onNodeWithText("84.00 ৳").assertExists()
        compose.onNodeWithText("Rifat Store").performClick()
        assertEquals("m1", opened)
    }

    @Test fun markPaidAsksFirstAndHidesWhenNothingIsOwed() {
        var paid = 0
        compose.setContent { AronTheme(AppLanguage.EN) { MemoDetailView(MemoMenu.detail(memo), true, 50_000, { "FB" }, {}, {}, { paid++ }) } }
        compose.onNodeWithText("Due: 50.00 ৳").assertExists()
        compose.onNodeWithText("Mark paid").performClick()
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
        compose.onNodeWithText("4,391.00 ৳").assertExists()
        compose.onNodeWithText("− 296.50 ৳").assertExists()
    }
}
