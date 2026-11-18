package com.aktcl.aron.feature.sale.ui

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.repo.RoomPrintLedger
import com.aktcl.aron.core.printing.bt.PrinterManager
import com.aktcl.aron.core.printing.bt.SavedPrinter
import com.aktcl.aron.core.printing.bt.SavedPrinterStore
import com.aktcl.aron.core.printing.flow.MemoPrinting
import com.aktcl.aron.core.printing.flow.SaveAndPrint
import com.aktcl.aron.core.printing.ui.SaveAndPrintDialogs
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.feature.sale.domain.Fx
import com.aktcl.aron.feature.sale.domain.ReviewProblem
import com.aktcl.aron.feature.sale.domain.SaleDraftOps
import com.aktcl.aron.feature.sale.domain.SaleReviewCalculator
import com.aktcl.aron.rules.QtyUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Round 2 of slice 1, the screens: ONE "add today's stock first" banner with a way to Stock and quiet rows (A7), a row whose stop and words
 * subtract the SKU's reward packs like SaleFlow.setQuantity does (A3), the Bangla SKU name on a Bangla screen (A6), and the specific message
 * a transaction refusal shows in place of "try again" (A5).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class StockGateUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    @After fun stop() { scope.cancel() }

    private val noStock = listOf(Fx.maxr.copy(stockBase = 0), Fx.aster.copy(stockBase = 0))

    @Test fun noStockAtAllShowsOneBannerWithTheWayToStockAndQuietRows() {
        val review = SaleReviewCalculator.review(Fx.draft(), Fx.catalog)
        var opened = 0
        compose.setContent { AronTheme(AppLanguage.EN) { SaleEntryScreen(noStock, review, { 0 }, { _, _ -> }, {}, {}, onOpenStock = { opened++ }) } }
        compose.onAllNodesWithTag(SaleTags.STOCK_FIRST).assertCountEquals(1)
        compose.onNodeWithText("No stock is entered for today. Add today's stock first, then sell.").assertExists()
        compose.onAllNodesWithText("No stock of", substring = true).assertCountEquals(0) // the rows say nothing
        compose.onAllNodesWithText("Only", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Open Stock").performClick()
        assertEquals(1, opened)
    }

    @Test fun someStockMeansNoBannerAndTheOtherRowsStayQuiet() {
        val review = SaleReviewCalculator.review(Fx.draft(), Fx.catalog)
        compose.setContent { AronTheme(AppLanguage.EN) { SaleEntryScreen(listOf(Fx.maxr, Fx.aster.copy(stockBase = 0)), review, { 0 }, { _, _ -> }, {}, {}) } }
        compose.onAllNodesWithTag(SaleTags.STOCK_FIRST).assertCountEquals(0)
        compose.onAllNodesWithText("No stock of", substring = true).assertCountEquals(0)
    }

    /** 100 sticks held, 9 packs (90 sticks) sold and 10 empty packets slid: one free pack of 10 sticks. The row's stop is 9 packs, net of the reward. */
    @Test fun theRowStopAndItsWordsSubtractTheRewardPacks() {
        val sku = Fx.maxr.copy(stockBase = 100)
        val catalog = mapOf(sku.skuId to sku)
        var d = SaleDraftOps.setQuantity(Fx.draft(), sku.skuId, 9, QtyUnit.PACK)
        d = SaleDraftOps.setSlide(d, sku.skuId, 10)
        val review = SaleReviewCalculator.review(d, catalog)
        assertEquals("the draft itself is within the stock", 10L, review.drp.single().rewardQtyBase)
        compose.setContent { AronTheme(AppLanguage.EN) { SaleEntryScreen(listOf(sku), review, { 9 }, { _, _ -> }, {}, {}) } }
        compose.onNodeWithText("Only 9", substring = true).assertExists() // 100 held less 10 free = 90 = 9 packs, not 10
        compose.onNodeWithContentDescription("More, ${sku.name}").assertIsNotEnabled() // at the net stop
        compose.onNodeWithContentDescription("Less, ${sku.name}").assertIsEnabled()
    }

    @Test fun aBanglaScreenWordsTheSkuInBanglaWhereTheMasterHasOne() {
        val sku = Fx.maxr.copy(stockBase = 100, nameBn = "ম্যাক্স আর ১০")
        val d = SaleDraftOps.setQuantity(Fx.draft(), sku.skuId, 10, QtyUnit.PACK) // all 10 packs: at the stop
        val review = SaleReviewCalculator.review(d, mapOf(sku.skuId to sku))
        val bn = AppLocale.wrap(context, AppLanguage.BN)
        compose.setContent { CompositionLocalProvider(LocalContext provides bn) { AronTheme(AppLanguage.BN) { SaleEntryScreen(listOf(sku), review, { 10 }, { _, _ -> }, {}, {}) } } }
        compose.onNodeWithText("ম্যাক্স আর ১০", substring = true).assertExists()
        compose.onAllNodesWithText("MaxR 10S in stock", substring = true).assertCountEquals(0)
        // the refusal text of a Bangla screen names the SKU in Bangla too
    }

    @Test fun theRefusalTextOfABanglaScreenNamesTheSkuInBangla() {
        val problem = ReviewProblem.OverStock(100, "MaxR 10S", 120, 100, 12, 10, "sale_unit_pack", "ম্যাক্স আর ১০")
        val bn = AppLocale.wrap(context, AppLanguage.BN)
        compose.setContent { CompositionLocalProvider(LocalContext provides bn) { AronTheme(AppLanguage.BN) { Text(problemText(problem)) } } }
        compose.onNodeWithText("ম্যাক্স আর ১০", substring = true).assertExists()
        compose.onAllNodesWithText("MaxR 10S", substring = true).assertCountEquals(0)
    }

    @Test fun theRefusalDialogSaysWhyAndNotJustTryAgain() {
        val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
        try {
            val meta = CaptureMeta("2026-10-05", "2026-10-05T04:31:07.120Z", 1, 1, 0, true, null, null, "b", false, 1)
            val printing = MemoPrinting(
                PrinterManager({ error("no printer here") }, object : SavedPrinterStore {
                    override fun load(): SavedPrinter? = null
                    override fun save(printer: SavedPrinter?) = Unit
                }, scope),
                { error("nothing renders") }, RoomPrintLedger(db, { it ?: meta }, { "2026-10-05T04:31:07.120Z" }), ClientIds::newUuid, { 0L },
            )
            val flow = SaveAndPrint({ error("the transaction refused") }, printing)
            runBlocking { flow.onPrintTapped(); flow.onSaveAnswer(true) }
            assertEquals(SaveAndPrint.Step.SaveFailed, flow.step.value)
            val message = "MaxR 10S: you hold 4 packs but the memo has 6. Lower the quantity, or add the stock first."
            compose.setContent { AronTheme(AppLanguage.EN) { SaveAndPrintDialogs(flow, scope, {}, saveFailedMessage = message) } }
            compose.onNodeWithText(message).assertExists()
            compose.onAllNodesWithText("could not be saved", substring = true).assertCountEquals(0)
        } finally { db.close() }
    }

    @Test fun withoutAReasonTheGenericSaveFailedTextStays() {
        val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
        try {
            val meta = CaptureMeta("2026-10-05", "2026-10-05T04:31:07.120Z", 1, 1, 0, true, null, null, "b", false, 1)
            val printing = MemoPrinting(
                PrinterManager({ error("no printer here") }, object : SavedPrinterStore {
                    override fun load(): SavedPrinter? = null
                    override fun save(printer: SavedPrinter?) = Unit
                }, scope),
                { error("nothing renders") }, RoomPrintLedger(db, { it ?: meta }, { "2026-10-05T04:31:07.120Z" }), ClientIds::newUuid, { 0L },
            )
            val flow = SaveAndPrint({ error("disk") }, printing)
            runBlocking { flow.onPrintTapped(); flow.onSaveAnswer(true) }
            compose.setContent { AronTheme(AppLanguage.EN) { SaveAndPrintDialogs(flow, scope, {}) } }
            compose.onNodeWithText("The sale could not be saved. Please try again.").assertExists()
        } finally { db.close() }
    }
}
