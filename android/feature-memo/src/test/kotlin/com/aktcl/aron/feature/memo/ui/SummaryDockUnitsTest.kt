package com.aktcl.aron.feature.memo.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.feature.memo.domain.DaySummaryCalculator
import com.aktcl.aron.feature.memo.domain.SummaryLine
import com.aktcl.aron.feature.memo.domain.SummaryMemo
import com.aktcl.aron.feature.memo.domain.SummarySku
import com.aktcl.aron.rules.EntrySpec
import com.aktcl.aron.rules.QtyUnit
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Checker (android-units): the Summary dock shows `3 packets + 5 sticks (65 sticks)` whole, never cut at the column edge. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SummaryDockUnitsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun categoryQuantityWithRemainderAndBaseBracketIsComplete() {
        val memo = SummaryMemo("m", null, listOf(SummaryLine(1, 65, 100_000)), emptyList(), 0, 100_000)
        val s = DaySummaryCalculator.compute(listOf(memo), mapOf(1L to SummarySku(1, "cigarette")), emptyMap()) { EntrySpec(QtyUnit.PACK, 20) }
        compose.setContent { AronTheme(AppLanguage.EN) { SummaryScreen(s, { "MaxR 20S" }, {}, categoryLabel = { "Cigarette" }) } }
        val text = "3 packets + 5 sticks (65 sticks)"
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, substring = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertFalse("the dock quantity is cut", results.first().didOverflowHeight || results.first().didOverflowWidth)
        compose.onNodeWithText(text, substring = true)
    }
}
