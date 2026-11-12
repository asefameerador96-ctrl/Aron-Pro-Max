package com.aktcl.aron.feature.memo.ui

import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.feature.memo.domain.MemoItem
import com.aktcl.aron.feature.memo.domain.MemoMenu
import com.aktcl.aron.feature.memo.domain.StoredMemo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** SRM-B-09..14, 19, 20 (manual shots 36 to 38): the memo screen's manual features, each opt-in so the AMO detail keeps its look. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class MemoManualScreensTest {
    @get:Rule val compose = createComposeRule()

    // two cigarette lines in packets (20s: 2 packets = 40 sticks, 10s: 1 packet = 10 sticks), one match line in dozens
    private val items = listOf(
        MemoItem(1, 40, 1_000, 40_000, qtyEntered = 2, unitEntered = "pack"),
        MemoItem(2, 10, 1_000, 10_000, qtyEntered = 1, unitEntered = "pack"),
        MemoItem(3, 4, 2_000, 8_000, qtyEntered = 4, unitEntered = "dozen"),
    )
    private val memo = StoredMemo("m1", "sr1-261112-001", 1, "2026-11-12", "2026-11-12T04:00:00.000Z", null, items, emptyList(), emptyList(), 58_000, 0, 0, 0, 58_000, 58_000, 0)
    private val names = mapOf(1L to "Avon-20S", 2L to "Boss-10S", 3L to "Zam Match")
    private val cats = mapOf(1L to "cigarette", 2L to "cigarette", 3L to "match")

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent { AronTheme(AppLanguage.EN) { content() } }

    @Test fun categoryRowCarriesItsQuantityInTheFieldUnitAndNeverAddsAcrossCategories() {
        show { MemoDetailView(MemoMenu.detail(memo), false, 0, { names.getValue(it) }, {}, {}, {}, skuCategory = { cats[it] }, subtotalQuantities = true) }
        compose.onNodeWithText("Total Cigarette").assertExists()
        compose.onNodeWithText("3 packets").assertExists() // 2 + 1 packets of the same unit are added
        compose.onNodeWithText("Total Match").assertExists()
        compose.onAllNodesWithText("4 dozen").assertCountEquals(2) // the line and its category row, each in dozens
    }

    // the default path (AMO, TSO): the category row keeps its two columns and carries no quantity; only the SR opts in
    @Test fun defaultCategoryRowHasNoQuantityColumn() {
        show { MemoDetailView(MemoMenu.detail(memo), false, 0, { names.getValue(it) }, {}, {}, {}, skuCategory = { cats[it] }) }
        compose.onNodeWithText("Total Cigarette").assertExists()
        compose.onAllNodesWithText("3 packets").assertCountEquals(0)
        // the AMO/TSO category row is the two-column row (name, amount), not the SR's three-column one
        compose.onNode(hasTestTag(TWO_COLUMN_ROW) and hasAnyDescendant(hasText("Total Cigarette"))).assertExists()
    }

    @Test fun srCategoryRowIsNotTheTwoColumnRow() {
        show { MemoDetailView(MemoMenu.detail(memo), false, 0, { names.getValue(it) }, {}, {}, {}, skuCategory = { cats[it] }, subtotalQuantities = true) }
        compose.onNode(hasTestTag(TWO_COLUMN_ROW) and hasAnyDescendant(hasText("Total Cigarette"))).assertDoesNotExist()
    }

    // a letter filter never makes a "Total" partial: the cigarette row still adds both cigarette lines
    @Test fun categoryTotalStaysWholeWhileALetterIsSelected() {
        show { MemoDetailView(MemoMenu.detail(memo), false, 0, { names.getValue(it) }, {}, {}, {}, skuCategory = { cats[it] }, subtotalQuantities = true, letterFilter = true) }
        compose.onNodeWithText("B").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Avon-20S").assertDoesNotExist()
        compose.onNodeWithText("Total Cigarette").assertExists()
        compose.onNodeWithText("Total Match").assertDoesNotExist() // the hidden category has no row
        compose.onNodeWithText("3 packets").assertExists()
        compose.onNodeWithText("50.00", substring = true).assertExists() // 40.00 + 10.00
    }

    @Test fun letterBarFiltersTheLinesAndAllBringsThemBack() {
        show { MemoDetailView(MemoMenu.detail(memo), false, 0, { names.getValue(it) }, {}, {}, {}, skuCategory = { cats[it] }, letterFilter = true) }
        compose.onNodeWithText("Avon-20S").assertExists(); compose.onNodeWithText("Boss-10S").assertExists()
        compose.onNodeWithText("B").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Boss-10S").assertExists()
        compose.onNodeWithText("Avon-20S").assertDoesNotExist()
        compose.onNodeWithText("All").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Avon-20S").assertExists()
    }

    @Test fun switcherNamesTheOutletWithItsDueAndOpensAnotherMemo() {
        var picked: String? = null
        val sw = MemoSwitcher(listOf(MemoSwitchOption("m1", "Tasmin (1618415)", 29_150_000), MemoSwitchOption("m2", "Milon Store (2663519)", 0)), "m1") { picked = it }
        show { MemoDetailView(MemoMenu.detail(memo), false, 0, { names.getValue(it) }, {}, {}, {}, switcher = sw) }
        compose.onNodeWithText("Tasmin (1618415)").assertExists()
        compose.onNodeWithText("Due 29,150.00 ৳", substring = true).assertExists()
        compose.onNodeWithText("Tasmin (1618415)").performClick()
        compose.onNodeWithText("Milon Store (2663519)").performClick()
        assertEquals("m2", picked)
    }

    @Test fun bannerShowsOnTheDetailAndPrintAndEditShareARow() {
        var printed = 0; var edited = 0
        show { MemoDetailView(MemoMenu.detail(memo), false, 0, { names.getValue(it) }, { printed++ }, { edited++ }, {}, detailBanner = "You can also reprint the memo from here.", buttonsInRow = true) }
        compose.onNodeWithText("You can also reprint the memo from here.").assertExists()
        compose.onNodeWithText("Print").performScrollTo().performClick()
        compose.onNodeWithText("Edit").performScrollTo().performClick()
        assertEquals(1, printed); assertEquals(1, edited)
    }

    @Test fun dueBoxShowsTheButtonBesideTheSentenceAndTheConfirmStillAsks() {
        var paid = 0
        show { MemoDetailView(MemoMenu.detail(memo), true, 50_000, { names.getValue(it) }, {}, {}, { paid++ }, dashedDueBox = true, dueSideBySide = true) }
        compose.onNodeWithText("Mark paid").performScrollTo().performClick()
        compose.onNodeWithText("Are you sure?").assertExists()
        assertEquals(0, paid)
        compose.onNodeWithText("Yes").performClick()
        assertEquals(1, paid)
    }

    @Test fun paidDialogStaysUntilOk() {
        var ok = 0
        val shown = androidx.compose.runtime.mutableStateOf(true)
        show { MemoDetailView(MemoMenu.detail(memo), false, 0, { names.getValue(it) }, {}, {}, {}, paidDialog = shown.value, onPaidDialogOk = { ok++; shown.value = false }) }
        compose.onNodeWithText("This memo is marked as paid.").assertExists()
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        assertEquals(1, ok)
        compose.onNodeWithText("This memo is marked as paid.").assertDoesNotExist()
    }

    // SRM-B-22: the summary's category rows read in the app language, never the raw code "lighter"
    @Test fun summaryCategoryRowsUseTheLocalizedNamesInBangla() {
        val m = com.aktcl.aron.feature.memo.domain.SummaryMemo("a", null, listOf(com.aktcl.aron.feature.memo.domain.SummaryLine(103, 375, 4_687_500)), emptyList(), 0, 4_391_000)
        val sum = com.aktcl.aron.feature.memo.domain.DaySummaryCalculator.compute(listOf(m), mapOf(103L to com.aktcl.aron.feature.memo.domain.SummarySku(103, "lighter")), mapOf(103L to 400L))
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val cfg = android.content.res.Configuration(ctx.resources.configuration).apply { setLocale(java.util.Locale("bn")) }
        val bnCtx = ctx.createConfigurationContext(cfg)
        compose.setContent { AronTheme(AppLanguage.BN) { SummaryScreen(sum, { "Aster" }, {}, categoryLabel = { MemoCategories.label(bnCtx, it).ifBlank { it } }) } }
        compose.onAllNodesWithText("লাইটার", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("lighter", substring = true).assertCountEquals(0)
    }
}
