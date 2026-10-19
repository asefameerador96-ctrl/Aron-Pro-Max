package com.aktcl.aron.feature.stock

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.database.entity.SkuEntity
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onAllNodesWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** F-SR-014 screen: stepped Issue, loaded total, category totals 400 and 400, Save. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp") // the field phone's design frame (screens-sr s0)
class StockScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun sku(id: Long, cat: String, unit: String, code: String = "S$id") = SkuEntity(id, code, id, cat, "n$id", "Aster$id", null, unit, 1, "base", null, "1.000", id.toInt(), "active", 1)
    private val rows = listOf(StockRow(sku(1, "lighter", "piece"), 400, 0), StockRow(sku(2, "match", "dozen"), 400, 0))
    /** The same rows with one Issue entered: Save is enabled only when an increment is non-zero (screens-sr s4). */
    private val rowsWithEntry = listOf(StockRow(sku(1, "lighter", "piece"), 400, 1), StockRow(sku(2, "match", "dozen"), 400, 0))
    private val totals = listOf(CategoryTotal("lighter", 400), CategoryTotal("match", 400))

    private fun show(language: AppLanguage, msg: StockMessage? = null, rows: List<StockRow> = this.rows, onIssue: (Long, Int) -> Unit = { _, _ -> }, onSave: () -> Unit = {}, onPrint: (() -> Unit)? = null) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { StockContent(rows, totals, msg, true, onIssue, onSave, onPrint = onPrint) } } }
        compose.waitForIdle()
    }

    @Test fun categoryTotalsShowFourHundredAndFourHundredInBanglaDigits() {
        show(AppLanguage.BN)
        // the reference panel: head "ক্যাটাগরি / মোট ইস্যু / মোট লোড", all four categories with their unit, zeros included
        compose.onNodeWithTag(StockTags.TOTALS).assertExists()
        compose.onNodeWithText("ক্যাটাগরি").assertExists(); compose.onNodeWithText("মোট ইস্যু").assertExists()
        listOf("সিগারেট", "বিড়ি", "লাইটার", "ম্যাচ").forEach { compose.onNodeWithText(it).assertExists() }
        // the unit is the caption under each category (two stick categories, one piece, one dozen)
        compose.onAllNodesWithText("স্টিক").assertCountEquals(2); compose.onNodeWithText("পিস").assertExists(); compose.onNodeWithText("ডজন").assertExists()
        // the issued and stock figures of the two panel lines at least (a second pack card may sit below the fold of the test window)
        assertTrue(compose.onAllNodesWithText("৪০০").fetchSemanticsNodes().size >= 4)
    }

    @Test fun steppingPlusReportsTheNewQuantityAndSaveCallsBack() {
        val got = mutableListOf<Pair<Long, Int>>(); var saved = 0
        show(AppLanguage.EN, rows = rowsWithEntry, onIssue = { id, q -> got += id to q }, onSave = { saved++ })
        // each control of a pack card has its own label with the SKU name: the first row's plus steps its Issue
        compose.onAllNodesWithContentDescription("More", substring = true)[0].performClick()
        assertEquals(listOf(1L to 2), got)
        compose.onNodeWithTag(StockTags.SAVE).performClick(); assertEquals(1, saved)
        compose.onNodeWithText("The stock slip is not printed yet. Print it before Sales Submit.").assertExists()
    }

    @Test fun saveIsDisabledWithItsReasonUntilAnIssueIsEntered() {
        var saved = 0
        show(AppLanguage.BN, onSave = { saved++ })
        compose.onNodeWithTag(StockTags.SAVE).assertIsNotEnabled()
        compose.onNodeWithText("কোনো পরিবর্তন নেই").assertExists()
        assertEquals(0, saved)
    }

    @Test fun keyFigureIsTheTotalLoadUntilTheHostSuppliesTheStockInTheBag() {
        show(AppLanguage.BN, rows = rowsWithEntry)
        // without an on-hand figure the numeral is loaded today plus the increment, and the column head says so (never "stock")
        compose.onNodeWithText("লোড").assertExists(); compose.onNodeWithText("মোট লোড").assertExists() // list head and panel head
        compose.onAllNodesWithText("৪০১").assertCountEquals(2)
        compose.onNodeWithText("স্টক").assertDoesNotExist()
    }

    @Test fun keyFigureIsTheStockInTheBagWhenSupplied() {
        show(AppLanguage.BN, rows = listOf(StockRow(sku(1, "lighter", "piece"), 400, 2, stockBase = 350), StockRow(sku(2, "match", "dozen"), 400, 0, stockBase = 400)))
        compose.onAllNodesWithText("স্টক").assertCountEquals(2); compose.onAllNodesWithText("৩৫২").assertCountEquals(2)
        compose.onNodeWithText("মোট লোড").assertDoesNotExist()
    }

    @Test fun refusedMessageShowsInBangla() {
        show(AppLanguage.BN, StockMessage.REFUSED_SAME_VALUES)
        compose.onNodeWithText("এই মানগুলো এইমাত্র সংরক্ষণ করা হয়েছে। মান বদলান বা কয়েক মিনিট অপেক্ষা করুন।").assertExists()
    }

    @Test fun printButtonOnlyAfterASaveAndNeverBlocksSave() {
        var saved = 0
        show(AppLanguage.BN, rows = rowsWithEntry, onSave = { saved++ })
        compose.onNodeWithTag(StockTags.PRINT).assertIsNotEnabled() // grey, as in the reference, until there is a slip and a printer
        compose.onNodeWithTag(StockTags.SAVE).assertIsEnabled().performClick(); assertEquals(1, saved)
    }

    @Test fun printButtonCallsBackWhenOffered() {
        var printed = 0
        show(AppLanguage.EN, onPrint = { printed++ })
        compose.onNodeWithTag(StockTags.PRINT).performClick(); assertEquals(1, printed)
    }

    @Test fun afterASaveTheButtonsShowTheStateAndNoBannerPushesTheList() {
        var printed = 0
        show(AppLanguage.BN, StockMessage.SAVED, onPrint = { printed++ })
        // no mint banner between the bar and the column heads (sr/stock.webp): the Save button says "Saved" and Print is active
        compose.onNodeWithTag(StockTags.MESSAGE).assertDoesNotExist()
        compose.onNodeWithText("সংরক্ষিত").assertExists()
        compose.onNodeWithTag(StockTags.PRINT).assertIsEnabled().performClick(); assertEquals(1, printed)
    }

    @Test fun theSkuLabelIsTheHyphenatedCodeOnOneLine() {
        show(AppLanguage.EN, rows = listOf(StockRow(sku(1, "cigarette", "stick", "MaxR-10S"), 400, 0)))
        compose.onNodeWithText("MaxR-10S").assertExists()
    }
}
