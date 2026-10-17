package com.aktcl.aron.feature.stock

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** F-SR-014 screen: stepped Issue, loaded total, category totals 400 and 400, Save. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class StockScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun sku(id: Long, cat: String, unit: String) = SkuEntity(id, "S$id", id, cat, "n$id", "Aster$id", null, unit, 1, "base", null, "1.000", id.toInt(), "active", 1)
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
        compose.onNodeWithText("লাইটার ৪০০ পিস").assertExists(); compose.onNodeWithText("ম্যাচ ৪০০ ডজন").assertExists()
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun steppingPlusReportsTheNewQuantityAndSaveCallsBack() {
        val got = mutableListOf<Pair<Long, Int>>(); var saved = 0
        show(AppLanguage.EN, rows = rowsWithEntry, onIssue = { id, q -> got += id to q }, onSave = { saved++ })
        // the row is one TalkBack node (android-glass s6.3): its plus action steps the Issue; the buttons themselves are touch only
        compose.onNodeWithTag(StockTags.row(1)).performCustomAccessibilityActionWithLabel("More")
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
        // without an on-hand figure the numeral is loaded today plus the increment, and says so (never "stock")
        compose.onNodeWithText("মোট লোড · পিস").assertExists(); compose.onNodeWithText("৪০১").assertExists()
        compose.onNodeWithText("স্টক · পিস").assertDoesNotExist()
    }

    @Test fun keyFigureIsTheStockInTheBagWhenSupplied() {
        show(AppLanguage.BN, rows = listOf(StockRow(sku(1, "lighter", "piece"), 400, 2, stockBase = 350), StockRow(sku(2, "match", "dozen"), 400, 0, stockBase = 400)))
        compose.onNodeWithText("স্টক · পিস").assertExists(); compose.onNodeWithText("৩৫২").assertExists()
        compose.onNodeWithText("মোট লোড · পিস").assertDoesNotExist()
    }

    @Test fun refusedMessageShowsInBangla() {
        show(AppLanguage.BN, StockMessage.REFUSED_SAME_VALUES)
        compose.onNodeWithText("এই মানগুলো এইমাত্র সংরক্ষণ করা হয়েছে। মান বদলান বা কয়েক মিনিট অপেক্ষা করুন।").assertExists()
    }

    @Test fun printButtonOnlyAfterASaveAndNeverBlocksSave() {
        var saved = 0
        show(AppLanguage.BN, rows = rowsWithEntry, onSave = { saved++ })
        compose.onNodeWithTag(StockTags.PRINT).assertDoesNotExist()
        compose.onNodeWithTag(StockTags.SAVE).assertIsEnabled().performClick(); assertEquals(1, saved)
    }

    @Test fun printButtonCallsBackWhenOffered() {
        var printed = 0
        show(AppLanguage.EN, onPrint = { printed++ })
        compose.onNodeWithTag(StockTags.PRINT).performClick(); assertEquals(1, printed)
    }

    @Test fun savedBannerOffersPrintAndRowsShowLoadedTodayWithTheUnit() {
        var printed = 0
        show(AppLanguage.BN, StockMessage.SAVED, onPrint = { printed++ })
        compose.onNodeWithText("আজ লোড ৪০০ পিস").assertExists()
        compose.onNodeWithTag(StockTags.MESSAGE).assertExists()
        // the banner's text action and the bar's secondary both say Print; the first is the banner's
        compose.onAllNodesWithText("প্রিন্ট")[0].performClick(); assertEquals(1, printed)
    }
}
