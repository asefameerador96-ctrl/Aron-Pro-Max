package com.aktcl.aron.feature.stock

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
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
    private val totals = listOf(CategoryTotal("lighter", 400), CategoryTotal("match", 400))

    private fun show(language: AppLanguage, msg: StockMessage? = null, onIssue: (Long, Int) -> Unit = { _, _ -> }, onSave: () -> Unit = {}, onPrint: (() -> Unit)? = null) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { StockContent(rows, totals, msg, true, onIssue, onSave, onPrint = onPrint) } } }
        compose.waitForIdle()
    }

    @Test fun categoryTotalsShowFourHundredAndFourHundredInBanglaDigits() {
        show(AppLanguage.BN)
        compose.onNodeWithText("লাইটার: ৪০০").assertExists(); compose.onNodeWithText("ম্যাচ: ৪০০").assertExists()
    }

    @Test fun steppingPlusReportsTheNewQuantityAndSaveCallsBack() {
        val got = mutableListOf<Pair<Long, Int>>(); var saved = 0
        show(AppLanguage.EN, onIssue = { id, q -> got += id to q }, onSave = { saved++ })
        compose.onAllNodesWithContentDescription("More")[0].performClick()
        assertEquals(listOf(1L to 1), got)
        compose.onNodeWithTag(StockTags.SAVE).performClick(); assertEquals(1, saved)
        compose.onNodeWithText("The stock slip is not printed yet. Print it before Sales Submit.").assertExists()
    }

    @Test fun refusedMessageShowsInBangla() {
        show(AppLanguage.BN, StockMessage.REFUSED_SAME_VALUES)
        compose.onNodeWithText("এই মানগুলো এইমাত্র সংরক্ষণ করা হয়েছে। মান বদলান বা কয়েক মিনিট অপেক্ষা করুন।").assertExists()
    }

    @Test fun printButtonOnlyAfterASaveAndNeverBlocksSave() {
        var printed = 0; var saved = 0
        show(AppLanguage.BN, onSave = { saved++ })
        compose.onNodeWithTag(StockTags.PRINT).assertDoesNotExist()
        compose.onNodeWithTag(StockTags.SAVE).performClick(); assertEquals(1, saved)
    }

    @Test fun printButtonCallsBackWhenOffered() {
        var printed = 0
        show(AppLanguage.EN, onPrint = { printed++ })
        compose.onNodeWithTag(StockTags.PRINT).performClick(); assertEquals(1, printed)
    }
}
