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

    // the memo total (291.50) and the REMAINING due (191.50, after 100.00 was collected) differ on purpose: Mark paid records the remaining
    @Test fun switcherNamesTheOutletWithItsTotalAndTheRemainingDueAndOpensAnotherMemo() {
        var picked: String? = null
        val sw = MemoSwitcher(listOf(MemoSwitchOption("m1", "Tasmin (1618415)", 191_500, 291_500), MemoSwitchOption("m2", "Milon Store (2663519)", 0)), "m1") { picked = it }
        show { MemoDetailView(MemoMenu.detail(memo), false, 0, { names.getValue(it) }, {}, {}, {}, switcher = sw) }
        compose.onNodeWithText("Tasmin (1618415)").assertExists()
        // P-SR2 (manual 38) kept, with the amount the rep will settle: ": <memo value> ৳", then "Due <remaining> ৳" in red, never a bare "Due"
        compose.onNodeWithText(": 291.50 ৳", substring = true).assertExists()
        compose.onNodeWithText("Due 191.50", substring = true).assertExists()
        compose.onNodeWithText("Due").assertDoesNotExist()
        compose.onNodeWithText("Tasmin (1618415)").performClick()
        compose.onNodeWithText("Milon Store (2663519)").performClick()
        assertEquals("m2", picked)
    }

    // total 291.50, 100.00 already collected: the memo's value and the remaining due are different numbers
    private val partlyPaid = StoredMemo("m1", "sr1-261112-001", 1, "2026-11-12", "2026-11-12T04:00:00.000Z", null, listOf(MemoItem(1, 291, 1_000, 291_500, qtyEntered = 291, unitEntered = "stick")), emptyList(), emptyList(), 291_500, 0, 0, 0, 291_500, 100_000, 191_500)

    private fun vendor(remainingMtk: Long, onPaid: () -> Unit) = show {
        MemoDetailView(MemoMenu.detail(partlyPaid), true, remainingMtk, { names.getValue(it) }, {}, {}, onPaid, skuCategory = { cats[it] }, outletName = "Tasmin (1618415)", vendorLayout = true, letterFilter = true, subtotalQuantities = true)
    }

    @Test fun vendorLayoutHasNoHeaderCardTheMinusLinesAndTheDueBoxWithItsOneTapButton() {
        vendor(191_500) {}
        compose.onNodeWithText("Tasmin (1618415)").assertDoesNotExist() // no selector and no header card without a switcher
        compose.onNodeWithText("Price").assertExists() // "দাম" without the taka sign
        compose.onNodeWithText("Total QC").assertExists()
        compose.onNodeWithText("Grand total").assertExists()
        compose.onNodeWithText("Total discount").assertDoesNotExist() // the zero discount line stays off the screen (deferred programme)
    }

    // finding 1 of the lead's gate: the due box names the REMAINING due, not the memo total, so the rep sees what Mark paid will record
    @Test fun vendorDueBoxShowsTheRemainingDueNotTheTotal() {
        vendor(191_500) {}
        compose.onNodeWithText("Due 191.50", substring = true).performScrollTo().assertExists()
    }

    // finding 2: confirm, then exactly one write; the confirm text carries the amount; a second tap on a dismissed dialog cannot write again
    @Test fun vendorMarkPaidNeedsTheConfirmNamesTheAmountAndWritesExactlyOnce() {
        var paid = 0
        vendor(191_500) { paid++ }
        compose.onNodeWithText("Mark paid").performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithText("Are you sure?").assertExists() // the confirm step comes before any write
        compose.onNodeWithText("Settle the whole remaining due of 191.50", substring = true).assertExists() // the amount the rep is about to record
        assertEquals(0, paid)
        compose.onNodeWithText("Yes").performClick(); compose.waitForIdle()
        assertEquals(1, paid)
        compose.onNodeWithText("Are you sure?").assertDoesNotExist()
    }

    @Test fun vendorMarkPaidDismissedWritesNothing() {
        var paid = 0
        vendor(191_500) { paid++ }
        compose.onNodeWithText("Mark paid").performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithText("No").performClick(); compose.waitForIdle()
        assertEquals(0, paid)
    }

    @Test fun bannerShowsOnTheDetailAndPrintAndEditShareARow() {
        var printed = 0; var edited = 0
        show { MemoDetailView(MemoMenu.detail(memo), false, 0, { names.getValue(it) }, { printed++ }, { edited++ }, {}, detailBanner = "You can also reprint the memo from here.", buttonsInRow = true) }
        compose.onNodeWithText("You can also reprint the memo from here.").assertExists()
        compose.onNodeWithText("Print").performScrollTo().performClick()
        compose.onNodeWithText("Edit").performScrollTo().performClick()
        assertEquals(1, printed); assertEquals(1, edited)
    }

    // D-PAR-AMO-12: the AMO's shop card replaces the title lines; the default keeps the SR/TSO header ("Route:" / "Retailer:" lines)
    @Test fun headerCardReplacesTheTitleLinesAndTheDefaultKeepsThem() {
        show { MemoDetailView(MemoMenu.detail(memo), false, 0, { names.getValue(it) }, {}, {}, {}, outletName = "Tasmin", routeName = "Savar", headerCard = { androidx.compose.material3.Text("SHOP-CARD") }) }
        compose.onNodeWithText("SHOP-CARD").assertExists()
        compose.onNodeWithText("Retailer: Tasmin").assertDoesNotExist()
    }
    @Test fun withoutAHeaderCardTheRouteAndRetailerLinesStay() {
        show { MemoDetailView(MemoMenu.detail(memo), false, 0, { names.getValue(it) }, {}, {}, {}, outletName = "Tasmin", routeName = "Savar") }
        compose.onNodeWithText("Retailer: Tasmin").assertExists()
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
