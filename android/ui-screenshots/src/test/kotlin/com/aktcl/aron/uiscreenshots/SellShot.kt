package com.aktcl.aron.uiscreenshots

import com.aktcl.aron.core.ui.aron.LocalPackshotProvider
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.database.entity.SkuEntity
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronScreen
import com.aktcl.aron.core.ui.AronStatusChip
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.AronTopBar
import com.aktcl.aron.core.ui.ChipForm
import com.aktcl.aron.core.ui.GlassTier
import com.aktcl.aron.core.ui.SyncChipState
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.feature.outlet.OpenVisit
import com.aktcl.aron.feature.outlet.OutletCard
import com.aktcl.aron.feature.outlet.OutletPicker
import com.aktcl.aron.feature.outlet.OutletPickerContent
import com.aktcl.aron.feature.outlet.PickerMark
import com.aktcl.aron.feature.outlet.VisitCheckContent
import com.aktcl.aron.feature.outlet.VisitOutlet
import com.aktcl.aron.feature.outlet.VisitUiState
import com.aktcl.aron.feature.sale.domain.SaleDraft
import com.aktcl.aron.feature.sale.domain.SaleDraftOps
import com.aktcl.aron.feature.sale.domain.SaleReviewCalculator
import com.aktcl.aron.feature.sale.domain.SaleSku
import com.aktcl.aron.feature.sale.ui.ReviewScreen
import com.aktcl.aron.feature.sale.ui.SaleEntryScreen
import com.aktcl.aron.feature.stock.CategoryTotal
import com.aktcl.aron.feature.stock.StockContent
import com.aktcl.aron.feature.stock.StockMessage
import com.aktcl.aron.feature.stock.StockRow
import com.aktcl.aron.rules.GeoAction
import com.aktcl.aron.rules.GeoVerdict
import com.aktcl.aron.rules.GeoVerdictResult
import com.aktcl.aron.rules.QtyUnit
import com.aktcl.aron.rules.TextRules
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Look-and-fix captures of the SR selling screens (not goldens, never verified): Stock, the route outlet picker, the
 * visit check in range and out of range, Sale entry with one expanded row, and Review, each inside the shared frame
 * (top bar, content, the screen's own bar), Bangla light and Bangla sunlight, tier B, 360 x 800 dp. The PNGs go to
 * `-Daron.kitshot.dir` (default `build/kitshots`). Names and numbers are sample data, never a real outlet.
 * Run: `./gradlew :android:ui-screenshots:testDebugUnitTest --tests '*SellShot*'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "bn-w360dp-h800dp")
class SellShot {
    @get:Rule val rule = createComposeRule()

    private val dir: File = File(System.getProperty("aron.kitshot.dir") ?: "build/kitshots").also { it.mkdirs() }
    private val options = RoborazziOptions(taskType = RoborazziTaskType.Record)

    private fun shot(name: String, sunlight: Boolean = false, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.BN)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                AronTheme(AppLanguage.BN, dark = false, sunlight = sunlight, tier = GlassTier.B, reducedMotion = false) { CompositionLocalProvider(LocalPackshotProvider provides TestPackshots) { content() } }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(File(dir, "$name.png").path, roborazziOptions = options)
    }

    /** The shared frame the host provides: the top bar with the quiet synced chip; the content renders its own bar. */
    @Composable
    private fun Frame(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
        val time = localizedDigits("10:42")
        AronScreen(
            topBar = { AronTopBar(title = title, subtitle = subtitle, onBack = {}, trailing = { AronStatusChip(SyncChipState.SyncedAt(time), form = ChipForm.Compact, onClick = {}) }) },
        ) { content() }
    }

    // ---- fixtures (sample data) ----
    private fun sku(id: Long, cat: String, name: String, unit: String, perPack: Int) =
        SkuEntity(id, "S$id", id, cat, name, name, null, unit, perPack, if (perPack > 1) "pack" else "base", null, "1.000", id.toInt(), "active", 1)
    private val stockRows = listOf(
        StockRow(sku(1, "cigarette", "MaxR 10S", "stick", 10), 400, 20),
        StockRow(sku(2, "cigarette", "Prime Gold 10S", "stick", 10), 200, 0),
        StockRow(sku(3, "lighter", "Aster", "piece", 1), 400, 0),
        StockRow(sku(4, "match", "Flame Box", "dozen", 1), 400, 0),
    )
    private val stockTotals = listOf(CategoryTotal("cigarette", 600), CategoryTotal("lighter", 400), CategoryTotal("match", 400))

    private fun outlet(id: Long, name: String, due: Long = 0, seq: Int = id.toInt()) = OutletEntity(
        outletId = id, routeId = 1, code = "DHK-344-00$id", name = name, nameBn = null, nameSortKey = TextRules.nameSortKey(name), ownerName = "Sample Owner",
        contactNumber = "01700000000", lat = 23.79, lng = 90.40, locationConfirmed = true, provisionalLat = null, provisionalLng = null,
        clusterId = 1, clusterName = "Apsis Cluster", channel = "GT", subChannelId = null, geoClass = null, status = "active", priceType = "outlet",
        outletKind = "regular", radiusM = 100, maxAccuracyM = 50, visitSequence = seq, openDueMtk = due, openDueAsOf = null,
        programmeFlagsJson = "[]", pendingRequest = false,
    )
    private val outlets = listOf(
        outlet(1, "Banani Store", due = 291_500), outlet(2, "Bashundhara Tea Stall"), outlet(3, "Emon Enterprise"),
        outlet(4, "Jamal Variety Store", due = 1_250_000), outlet(5, "Mim Enterprise"), outlet(6, "করিম টি স্টল"),
    )
    private val marks = mapOf(
        1L to PickerMark(visited = true, memoTotalMtk = 84_000),
        3L to PickerMark(visited = true, outcomeCode = "closed"),
    )
    private val visitOutlet = VisitOutlet(1, 1, "Banani Store", "DHK-344-001", 23.79, 90.40, "master", 100, 50)
    private val card = OutletCard("Banani Store", null, "Sample Owner", "01700000000", "Apsis Cluster", "GT", 291_500, "2026-10-04", "2026-10-03T05:00:00Z", false, code = "DHK-344-001")

    private val maxr = SaleSku(100, "MAXR10", "cigarette", "MaxR 10S", null, "stick", 10, 8_000, 1, "2026-09-01", stockBase = 394)
    private val prime = SaleSku(101, "PRIME10", "cigarette", "Prime Gold 10S", null, "stick", 10, 6_500, 1, "2026-09-01", stockBase = 610)
    private val aster = SaleSku(103, "ASTER", "lighter", "Aster", null, "piece", 1, 12_500, 1, "2026-09-01", stockBase = 25)
    private val match = SaleSku(105, "FB", "match", "Flame Box", null, "dozen", 1, 28_000, 1, "2026-09-01", stockBase = 40)
    private val skus = listOf(maxr, prime, aster, match)
    private val catalog = skus.associateBy { it.skuId }
    private fun draft() = SaleDraft("v1", 50001, 10231, "2026-10-05", memoUuid = "m1")
    private val saleDraft = SaleDraftOps.setQuantity(draft(), 100, 20, QtyUnit.STICK)
    private val saleReview = SaleReviewCalculator.review(saleDraft, catalog)
    private val reviewDraft = SaleDraftOps.setQuantity(SaleDraftOps.setQuantity(draft(), 100, 20, QtyUnit.STICK), 103, 3, QtyUnit.PIECE)
    private val reviewReview = SaleReviewCalculator.review(reviewDraft, catalog)

    // ---- screens ----
    @Composable private fun Stock() = Frame(stringResource(com.aktcl.aron.feature.stock.R.string.stk_title)) {
        StockContent(stockRows, stockTotals, StockMessage.SAVED, slipNotPrinted = true, onIssue = { _, _ -> }, onSave = {}, onPrint = {})
    }

    @Composable private fun Picker() = Frame(stringResource(com.aktcl.aron.feature.outlet.R.string.out_picker_title)) {
        val rows = OutletPicker.rows(outlets)
        OutletPickerContent(rows, OutletPicker.chips(rows), OutletPicker.ALL_CHIP, {}, {}, marks = marks)
    }

    @Composable private fun VisitInRange() = Frame(card.name) {
        VisitCheckContent(
            VisitUiState.Open(OpenVisit("v1", 1, 1, "in_range", "sale_allowed", true, false, null, "2026-10-05T04:31:07Z")),
            onRefresh = {}, onForceSale = {}, onRetry = {}, onMap = null, outlet = card, onStartCall = {}, onPreviousSales = {}, onNoSale = {},
        )
    }

    @Composable private fun VisitOutOfRange() = Frame(card.name) {
        VisitCheckContent(
            VisitUiState.NeedsDecision(visitOutlet, GeoVerdictResult(GeoVerdict.OUT_OF_RANGE, GeoAction.REFRESH_OFFERED, 180.0), 1, true, 3, false, false, phoneLat = 23.7912, phoneLng = 90.4009),
            onRefresh = {}, onForceSale = {}, onRetry = {}, onMap = {}, outlet = card, onPreviousSales = {}, onNoSale = {},
        )
    }

    /** The outlet has no coordinates: the card says so and the bar's primary is Force Sale, no refresh (screens-sr s6 table). */
    @Composable private fun VisitNoCoordinates() = Frame(card.name) {
        VisitCheckContent(
            VisitUiState.NeedsDecision(visitOutlet.copy(lat = null, lng = null, locationBasis = "none"), GeoVerdictResult(GeoVerdict.NO_OUTLET_LOCATION, GeoAction.FORCE_SALE, null), 0, true, 3, true, false),
            onRefresh = {}, onForceSale = {}, onRetry = {}, onMap = null, outlet = card, onPreviousSales = {}, onNoSale = {},
        )
    }

    @Composable private fun Sale() = Frame(stringResource(com.aktcl.aron.feature.sale.R.string.sale_title), subtitle = card.name) {
        SaleEntryScreen(skus, saleReview, { id -> saleDraft.lines.firstOrNull { it.skuId == id }?.qtyEntered ?: 0 }, { _, _ -> }, {}, {}, recentSkuIds = setOf(100L), onPreviousSales = {})
    }

    @Composable private fun Review() = Frame(stringResource(com.aktcl.aron.feature.sale.R.string.review_title)) {
        ReviewScreen(reviewReview, {}, outletLine = "Banani Store (DHK-344-001) · Apsis RouteDaily · Apsis Cluster", onCreditToggle = {}, onQc = {})
    }

    @Test fun stock_bn_light() = shot("sell_stock_bn_light") { Stock() }
    @Test fun stock_bn_sunlight() = shot("sell_stock_bn_sunlight", sunlight = true) { Stock() }
    @Test fun picker_bn_light() = shot("sell_picker_bn_light") { Picker() }
    @Test fun picker_bn_sunlight() = shot("sell_picker_bn_sunlight", sunlight = true) { Picker() }
    @Test fun visit_in_bn_light() = shot("sell_visit_in_bn_light") { VisitInRange() }
    @Test fun visit_in_bn_sunlight() = shot("sell_visit_in_bn_sunlight", sunlight = true) { VisitInRange() }
    @Test fun visit_out_bn_light() = shot("sell_visit_out_bn_light") { VisitOutOfRange() }
    @Test fun visit_out_bn_sunlight() = shot("sell_visit_out_bn_sunlight", sunlight = true) { VisitOutOfRange() }
    @Test fun visit_nocoords_bn_light() = shot("sell_visit_nocoords_bn_light") { VisitNoCoordinates() }
    @Test fun sale_bn_light() = shot("sell_sale_bn_light") { Sale() }
    @Test fun sale_bn_sunlight() = shot("sell_sale_bn_sunlight", sunlight = true) { Sale() }
    @Test fun review_bn_light() = shot("sell_review_bn_light") { Review() }
    @Test fun review_bn_sunlight() = shot("sell_review_bn_sunlight", sunlight = true) { Review() }
}
