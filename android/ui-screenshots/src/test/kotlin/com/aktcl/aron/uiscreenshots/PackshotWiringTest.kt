package com.aktcl.aron.uiscreenshots

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.database.entity.SkuEntity
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.aron.BitmapPackshotProvider
import com.aktcl.aron.core.ui.aron.LocalPackshotProvider
import com.aktcl.aron.feature.sale.domain.SaleDraft
import com.aktcl.aron.feature.sale.domain.SaleReviewCalculator
import com.aktcl.aron.feature.sale.domain.SaleSku
import com.aktcl.aron.feature.sale.ui.SaleEntryScreen
import com.aktcl.aron.feature.stock.StockContent
import com.aktcl.aron.feature.stock.StockRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.ConcurrentHashMap

/**
 * PARITY-SPEC s2.6, lead ruling 2026-10-18: the kit PackCard rows of the new Stock and Sale screens (lane ui-sr-a) ask
 * the app's provider for each row's picture by the server SKU id, the key `SkuPackshots` resolves in the local catalogue;
 * a picture it returns is drawn (memory hit on the first frame), and a row without one keeps the silhouette.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "bn-w360dp-h800dp")
class PackshotWiringTest {
    @get:Rule val rule = createComposeRule()
    private val asked = ConcurrentHashMap.newKeySet<String>()
    private val served = ConcurrentHashMap.newKeySet<String>()
    private val picture = Bitmap.createBitmap(146, 237, Bitmap.Config.RGB_565)
    private val provider = BitmapPackshotProvider(
        load = { id -> asked += id; if (id == "1" || id == "100") picture.also { served += id } else null },
        cached = { id -> if (id == "1" || id == "100") picture.also { served += id } else null },
    )

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.BN)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(AppLanguage.BN) { CompositionLocalProvider(LocalPackshotProvider provides provider) { content() } }
            }
        }
        rule.waitForIdle()
    }

    private fun sku(id: Long, cat: String, name: String, unit: String, perPack: Int) =
        SkuEntity(id, "S$id", id, cat, name, name, null, unit, perPack, if (perPack > 1) "pack" else "base", null, "1.000", id.toInt(), "active", 1)

    @Test fun everyStockRowAsksTheProviderByTheServerSkuId() {
        val rows = listOf(StockRow(sku(1, "cigarette", "MaxR 10S", "stick", 10), 400, 0), StockRow(sku(3, "lighter", "Aster", "piece", 1), 25, 0))
        show { StockContent(rows, emptyList(), null, slipNotPrinted = false, onIssue = { _, _ -> }, onSave = {}, onPrint = {}) }
        assertEquals(setOf("1"), served)
        assertTrue("the row without a picture asked too and keeps the silhouette: $asked", "3" in asked)
    }

    @Test fun everySaleRowAsksTheProviderByTheServerSkuId() {
        val skus = listOf(
            SaleSku(100, "MAXR10", "cigarette", "MaxR 10S", null, "stick", 10, 8_000, 1, "2026-09-01", stockBase = 400),
            SaleSku(103, "ASTER", "lighter", "Aster", null, "piece", 25, 12_500, 1, "2026-09-01", stockBase = 25),
        )
        val review = SaleReviewCalculator.review(SaleDraft("v1", 50001, 10231, "2026-10-05", memoUuid = "m1"), skus.associateBy { it.skuId })
        show { SaleEntryScreen(skus, review, { 0 }, { _, _ -> }, {}, {}, onPreviousSales = {}) }
        assertEquals(setOf("100"), served)
        assertTrue("the row without a picture asked too and keeps the silhouette: $asked", "103" in asked)
    }
}
