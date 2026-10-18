package com.aktcl.aron.uiscreenshots

import com.aktcl.aron.core.ui.LocalPackshotProvider
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
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
import com.aktcl.aron.feature.attendance.AttendanceContent
import com.aktcl.aron.feature.attendance.AttendanceState
import com.aktcl.aron.feature.auth.LoginContent
import com.aktcl.aron.feature.auth.LoginMessage
import com.aktcl.aron.feature.auth.LoginUiState
import com.aktcl.aron.feature.dayclose.CountRow
import com.aktcl.aron.feature.dayclose.DuesAtSubmit
import com.aktcl.aron.feature.dayclose.OutboxStates
import com.aktcl.aron.feature.dayclose.SalesSubmitRules
import com.aktcl.aron.feature.dayclose.SalesSubmitScreen
import com.aktcl.aron.feature.dayclose.SubmitProgress
import com.aktcl.aron.feature.home.BundleFreshness
import com.aktcl.aron.feature.home.BundleStatus
import com.aktcl.aron.feature.home.DeviceHealthModel
import com.aktcl.aron.feature.home.HomeContent
import com.aktcl.aron.feature.home.HomeDayState
import com.aktcl.aron.feature.home.HomeDock
import com.aktcl.aron.feature.home.HomeHero
import com.aktcl.aron.feature.home.HomeKpis
import com.aktcl.aron.feature.home.HomeModel
import com.aktcl.aron.feature.home.HomeMoneyLine
import com.aktcl.aron.feature.home.HomeMoneyLines
import com.aktcl.aron.feature.home.HomeStockLine
import com.aktcl.aron.feature.home.HomeTiles
import com.aktcl.aron.feature.home.HomeUser
import com.aktcl.aron.feature.home.RoleHero
import com.aktcl.aron.feature.home.RoleHomeContent
import com.aktcl.aron.feature.home.RoleHomes
import com.aktcl.aron.feature.memo.domain.DaySummaryCalculator
import com.aktcl.aron.feature.memo.domain.MemoItem
import com.aktcl.aron.feature.memo.domain.MemoMenu
import com.aktcl.aron.feature.memo.domain.StoredMemo
import com.aktcl.aron.feature.memo.domain.SummaryDiscount
import com.aktcl.aron.feature.memo.domain.SummaryLine
import com.aktcl.aron.feature.memo.domain.SummaryMemo
import com.aktcl.aron.feature.memo.domain.SummarySku
import com.aktcl.aron.feature.memo.ui.MemoDetailView
import com.aktcl.aron.feature.memo.ui.SummaryScreen
import com.aktcl.aron.feature.outlet.OpenVisit
import com.aktcl.aron.feature.outlet.OutletCard
import com.aktcl.aron.feature.outlet.OutletPicker
import com.aktcl.aron.feature.outlet.OutletPickerContent
import com.aktcl.aron.feature.outlet.PickerMark
import com.aktcl.aron.feature.outlet.VisitCheckContent
import com.aktcl.aron.feature.outlet.VisitOutlet
import com.aktcl.aron.feature.outlet.VisitUiState
import com.aktcl.aron.feature.sale.domain.DrpRule
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
import com.aktcl.aron.feature.tasks.TaskContent
import com.aktcl.aron.feature.tasks.TaskItem
import com.aktcl.aron.feature.tasks.TaskListState
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.GeoAction
import com.aktcl.aron.rules.GeoVerdict
import com.aktcl.aron.rules.GeoVerdictResult
import com.aktcl.aron.rules.QtyUnit
import com.aktcl.aron.rules.TextRules
import com.aktcl.aron.rules.UiLocale
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.aktcl.aron.feature.memo.domain.MemoMenuRow
import com.aktcl.aron.feature.sale.domain.QcEntry
import com.aktcl.aron.feature.sale.ui.QcScreen
import com.aktcl.aron.feature.memo.ui.MemoMenuScreen
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.aktcl.aron.feature.home.R as HomeR
import com.aktcl.aron.feature.sale.R as SaleR

/**
 * State-coverage goldens for the SR Home, Sale entry, Review and Memo screens (lane item 5, 2026-10-17): the states
 * `ScreenshotTests` does not hold: loading, no route and offline, expired bundle, day complete, empty catalog, empty cart,
 * empty and filled memo list, disabled reprint, long Bangla (font scale 2.0) and Sunlight. Same frame, tier B, 360 x 800 dp.
 * Record on Linux only: `-Daron.screenshots.recordTo=src/test/screenshots` (docs/status/android-core-ui.md).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "bn-w360dp-h800dp")
class SrStateScreenshotTests {
    @get:Rule val rule = createComposeRule()

    private val recordTo: String? = System.getProperty("aron.screenshots.recordTo")
    private val goldens: String = System.getProperty("aron.screenshots.goldens") ?: "src/test/screenshots"
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private fun shot(name: String, language: AppLanguage = AppLanguage.BN, sunlight: Boolean = false, fontScale: Float = 1.0f, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides ctx, LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale)) {
                AronTheme(language, dark = false, sunlight = sunlight, tier = GlassTier.B, reducedMotion = true) { CompositionLocalProvider(LocalPackshotProvider provides TestPackshots) { content() } }
            }
        }
        rule.waitForIdle()
        if (recordTo == null && System.getProperty("roborazzi.test.record") != "true") {
            assertTrue("missing golden $goldens/$name.png", java.io.File("$goldens/$name.png").exists())
        }
        rule.onRoot().captureRoboImage("${recordTo ?: goldens}/$name.png", roborazziOptions = options)
    }

    @Composable
    private fun Frame(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
        val time = localizedDigits("10:42")
        AronScreen(
            topBar = { AronTopBar(title = title, subtitle = subtitle, onBack = {}, trailing = { AronStatusChip(SyncChipState.SyncedAt(time), form = ChipForm.Compact, onClick = {}) }) },
        ) { content() }
    }

    private val header = HomeModel.header("SR", "Testing Banani", "sr334001", "Apsis RouteDaily", "daily", "2026-10-05")
    private val noRoute = HomeModel.header("SR", "Testing Banani", "sr334001", null, null, "2026-10-05")

    @Composable
    private fun FullHome(dayState: HomeDayState = HomeDayState.IN_FIELD, dock: HomeDock? = HomeDock.START_SELLING, freshness: BundleFreshness = BundleFreshness.Fresh) {
        val locale = UiLocale.BN
        val piece = stringResource(SaleR.string.sale_unit_piece)
        val dozen = stringResource(SaleR.string.sale_unit_dozen)
        val stick = stringResource(SaleR.string.sale_unit_stick)
        HomeContent(
            header, HomeTiles.resolve(emptySet(), 2), freshness, false, DeviceHealthModel.of(62, 1_200, 3, 12), onTile = {},
            onSettings = {}, hero = HomeHero(Formats.money(4_391_000, locale), 6, 60, "08:52", dayState),
            kpis = HomeKpis("10%", 54, 0, listOf(HomeStockLine("Cigarette", 12_000, 9_800, stick), HomeStockLine("Lighter", 400, 25, piece), HomeStockLine("Match", 400, 394, dozen))),
            money = HomeMoneyLines(
                listOf(HomeMoneyLine("Match", Formats.money(141_000, locale)), HomeMoneyLine("Lighter", Formats.money(4_687_500, locale))),
                Formats.money(4_828_500, locale), null, "−" + Formats.money(437_500, locale), Formats.money(4_391_000, locale),
            ),
            sync = SyncChipState.SyncedAt(Formats.digits("10:42", locale)), onSync = {}, sunlight = false, onSunlight = {},
            dock = dock, routeKindLabel = "Daily",
        )
    }

    @Composable
    private fun LoadingHome() = HomeContent(
        header, HomeTiles.resolve(emptySet(), 0), BundleFreshness.Fresh, false, null, onTile = {}, onSettings = {},
        loading = true, sync = SyncChipState.Syncing, onSync = {}, onSunlight = {}, routeKindLabel = "Daily",
    )

    @Composable
    private fun NoRouteHome() = HomeContent(
        noRoute, HomeTiles.resolve(emptySet(), 0), BundleFreshness.Fresh, true, null, onTile = {}, onSettings = {},
        hero = HomeHero(Formats.money(0, UiLocale.BN), 0, 0, null, HomeDayState.NOT_CHECKED_IN), noRoute = true,
        sync = SyncChipState.Offline, onSync = {}, onSunlight = {}, dock = HomeDock.CHECK_IN,
    )

    private val maxr = SaleSku(100, "MAXR10", "cigarette", "MaxR 10S", "ম্যাক্সআর ১০এস (লম্বা নামের একটি পণ্য যা দুই লাইনে ভেঙে যায়)", "stick", 10, 8_000, 1, "2026-09-01", stockBase = 400, drp = DrpRule(10))
    private val aster = SaleSku(103, "ASTER", "lighter", "Aster", null, "piece", 1, 12_500, 1, "2026-09-01", stockBase = 25)
    private val skus = listOf(maxr, aster)
    private val catalog = skus.associateBy { it.skuId }
    private fun draft() = SaleDraft("v1", 50001, 10231, "2026-10-05", memoUuid = "m1")
    private val filled = SaleDraftOps.setQuantity(SaleDraftOps.setQuantity(draft(), 100, 40, QtyUnit.STICK), 103, 3, QtyUnit.PIECE)
    private val filledReview = SaleReviewCalculator.review(filled, catalog)
    private val emptyReview = SaleReviewCalculator.review(draft(), catalog)

    @Composable private fun SaleFull() = Frame(stringResource(SaleR.string.sale_title), subtitle = "Banani Store") {
        SaleEntryScreen(skus, filledReview, { id -> filled.lines.firstOrNull { it.skuId == id }?.qtyEntered ?: 0 }, { _, _ -> }, {}, {}, recentSkuIds = setOf(100L), onPreviousSales = {})
    }
    @Composable private fun SaleEmptyCatalog() = Frame(stringResource(SaleR.string.sale_title), subtitle = "Banani Store") {
        SaleEntryScreen(emptyList(), null, { 0 }, { _, _ -> }, {}, {})
    }
    @Composable private fun SaleEmptyCart() = Frame(stringResource(SaleR.string.sale_title), subtitle = "Banani Store") {
        SaleEntryScreen(skus, emptyReview, { 0 }, { _, _ -> }, {}, {})
    }
    @Composable private fun ReviewEmpty() = Frame(stringResource(SaleR.string.review_title)) {
        ReviewScreen(emptyReview, {}, outletLine = "Banani Store (DHK-344-001) · Apsis RouteDaily · Apsis Cluster", onCreditToggle = {}, onQc = {})
    }
    @Composable private fun ReviewFull() = Frame(stringResource(SaleR.string.review_title)) {
        ReviewScreen(filledReview, {}, outletLine = "Banani Store (DHK-344-001) · Apsis RouteDaily · Apsis Cluster", onCreditToggle = {}, onQc = {})
    }

    private val memo = StoredMemo(
        "m1", "sr334001-261005-001", 50001, "2026-10-05", "2026-10-05T04:00:00.000Z", null,
        listOf(MemoItem(100, 40, 8_000, 320_000), MemoItem(103, 3, 12_500, 37_500)), emptyList(), emptyList(),
        357_500, 0, 0, 0, 357_500, 300_000, 57_500,
    )
    private fun skuName(id: Long) = if (id == 100L) "MaxR 10S" else "Aster"
    private val rows = listOf(
        MemoMenuRow("m1", "sr334001-261005-001", 1, "2026-10-05T04:00:00.000Z", 357_500, 57_500),
        MemoMenuRow("m2", "sr334001-261005-002", 2, "2026-10-05T05:10:00.000Z", 84_000, 0),
        MemoMenuRow("m3", "sr334001-261005-003", 6, "2026-10-05T06:20:00.000Z", 1_250_000, 0),
    )
    private fun outletName(id: Long) = when (id) { 1L -> "Banani Store"; 2L -> "Bashundhara Tea Stall"; else -> "করিম টি স্টল" }

    @Composable private fun MenuEmpty() = Frame(stringResource(HomeR.string.hm_tile_memo)) { MemoMenuScreen(emptyList(), ::outletName, {}) }
    @Composable private fun MenuList() = Frame(stringResource(HomeR.string.hm_tile_memo)) { MemoMenuScreen(rows, ::outletName, {}, bannerDismissed = true) }
    @Composable private fun DetailReprintLimit() = Frame(stringResource(HomeR.string.hm_tile_memo)) {
        MemoDetailView(
            MemoMenu.detail(memo), true, 57_500, ::skuName, {}, {}, {}, outletName = "Banani Store", onHistory = {},
            skuUnit = { id -> if (id == 100L) "stick" else "piece" }, printDisabledReason = "পুনরায় প্রিন্টের সীমা শেষ", editDisabledReason = "আউটলেটের এলাকার বাইরে থেকে সম্পাদনা করা যায় না",
        )
    }

    // ---- QC entry (manual p36-38: MaxR-10S, 5 + 5 sticks, settlement 80.00) ----
    private val qcDraft = SaleDraftOps.setQc(SaleDraftOps.setQc(SaleDraftOps.setQuantity(draft(), 100, 40, QtyUnit.STICK), QcEntry(100, "damaged_crushed", "MFC", 5)), QcEntry(100, "expired_stock", "MKT", 5))
    private val qcReview = SaleReviewCalculator.review(qcDraft, catalog)
    @Composable private fun Qc(cap: Long?) = Frame(stringResource(SaleR.string.qc_title)) {
        QcScreen(listOf(maxr), qcReview, { id, code -> qcDraft.qc.firstOrNull { it.skuId == id && it.faultTypeCode == code }?.defectQty ?: 0L }, { _, _, _ -> }, false, {}, onCancel = {}, capMtkOf = { cap })
    }

    // ---- QC on a zero sale (D-18): the whole catalog as closed cards, one open with a typed count, the credit Review ----
    private val qcOnlyDraft = SaleDraftOps.setQc(SaleDraftOps.setQc(SaleDraftOps.startQcOnly(draft()), QcEntry(100, "damaged_crushed", "MFC", 7)), QcEntry(100, "expired_stock", "MKT", 3))
    private val qcOnlyReview = SaleReviewCalculator.review(qcOnlyDraft, catalog)
    @Composable private fun QcOnly() = Frame(stringResource(SaleR.string.qc_title)) {
        QcScreen(listOf(maxr), qcOnlyReview, { id, code -> qcOnlyDraft.qc.firstOrNull { it.skuId == id && it.faultTypeCode == code }?.defectQty ?: 0L }, { _, _, _ -> }, false, {}, onCancel = {}, collapsible = true)
    }
    @Composable private fun ReviewCredit() = Frame(stringResource(SaleR.string.review_title)) { ReviewScreen(qcOnlyReview, onSave = {}, onQc = {}) }

    // ---- Sale history (manual SR-S-20: pick a date, per-SKU table, exact total) ----
    private fun histMemo(id: String, date: String, vararg l: com.aktcl.aron.feature.memo.domain.MemoItem) =
        StoredMemo(id, "sr334001-261005-$id", 1, date, "${date}T04:00:00.000Z", null, l.toList(), emptyList(), emptyList(), 0, 0, 0, 0, 0, 0, 0)
    private val history = com.aktcl.aron.feature.memo.domain.SaleHistoryBuilder.build(
        listOf(
            histMemo("001", "2026-10-05", MemoItem(100, 40, 8_000, 320_000), MemoItem(103, 3, 12_500, 37_500)),
            histMemo("002", "2026-10-05", MemoItem(100, 10, 8_000, 80_000)),
            histMemo("003", "2026-10-04", MemoItem(103, 20, 12_500, 250_000)),
        ), 1,
    )
    @Composable private fun History(offline: Boolean = false) = Frame(stringResource(HomeR.string.hm_tile_memo)) {
        com.aktcl.aron.feature.memo.ui.SaleHistoryScreen(history, offline, skuName = ::skuName, skuUnit = { id -> if (id == 100L) "স্টিক" else "পিস" })
    }

    // ---- Home ----
    @Test fun home_bn_loading() = shot("home_bn_loading") { LoadingHome() }
    @Test fun home_bn_no_route_offline() = shot("home_bn_no_route_offline") { NoRouteHome() }
    @Test fun home_bn_expired() = shot("home_bn_expired") { FullHome(freshness = BundleFreshness.Expired(3)) }
    @Test fun home_bn_complete() = shot("home_bn_complete") { FullHome(HomeDayState.COMPLETE, dock = null) }
    @Test fun home_bn_font20() = shot("home_bn_font20", fontScale = 2.0f) { FullHome() }
    @Test fun home_bn_no_route_sunlight() = shot("home_bn_no_route_sunlight", sunlight = true) { NoRouteHome() }

    // ---- Sale and Review ----
    @Test fun sale_bn_empty_catalog() = shot("sale_bn_empty_catalog") { SaleEmptyCatalog() }
    @Test fun sale_bn_empty_catalog_sunlight() = shot("sale_bn_empty_catalog_sunlight", sunlight = true) { SaleEmptyCatalog() }
    @Test fun sale_bn_empty_cart() = shot("sale_bn_empty_cart") { SaleEmptyCart() }
    @Test fun sale_bn_long_name() = shot("sale_bn_long_name") { SaleFull() }
    @Test fun sale_bn_font20() = shot("sale_bn_font20", fontScale = 2.0f) { SaleFull() }
    @Test fun review_bn_empty() = shot("review_bn_empty") { ReviewEmpty() }
    @Test fun review_bn_font20() = shot("review_bn_font20", fontScale = 2.0f) { ReviewFull() }

    // ---- QC ----
    @Test fun qc_bn_with_cap() = shot("qc_bn_with_cap") { Qc(594_500L) }
    @Test fun qc_bn_no_cap() = shot("qc_bn_no_cap") { Qc(null) }
    @Test fun qc_bn_sunlight() = shot("qc_bn_sunlight", sunlight = true) { Qc(594_500L) }
    @Test fun qc_bn_font20() = shot("qc_bn_font20", fontScale = 2.0f) { Qc(594_500L) }
    @Test fun qc_bn_only_summary() = shot("qc_bn_only_summary") { QcOnly() }
    @Test fun qc_bn_only_sunlight() = shot("qc_bn_only_sunlight", sunlight = true) { QcOnly() }
    @Test fun review_bn_qc_credit() = shot("review_bn_qc_credit") { ReviewCredit() }
    @Test fun qc_en_with_cap() = shot("qc_en_with_cap", AppLanguage.EN) { Qc(594_500L) }

    // ---- Sale history ----
    @Test fun history_bn_light() = shot("history_bn_light") { History() }
    @Test fun history_bn_offline() = shot("history_bn_offline") { History(offline = true) }
    @Test fun history_bn_sunlight() = shot("history_bn_sunlight", sunlight = true) { History() }
    @Test fun history_bn_font20() = shot("history_bn_font20", fontScale = 2.0f) { History() }
    @Test fun history_empty_bn() = shot("history_empty_bn") { Frame(stringResource(HomeR.string.hm_tile_memo)) { com.aktcl.aron.feature.memo.ui.SaleHistoryScreen(com.aktcl.aron.feature.memo.domain.SaleHistoryBuilder.build(emptyList(), 1), false) } }

    // ---- Memo ----
    @Test fun memo_bn_menu_empty() = shot("memo_bn_menu_empty") { MenuEmpty() }
    @Test fun memo_bn_menu_empty_sunlight() = shot("memo_bn_menu_empty_sunlight", sunlight = true) { MenuEmpty() }
    @Test fun memo_bn_menu_list() = shot("memo_bn_menu_list") { MenuList() }
    @Test fun memo_bn_menu_list_sunlight() = shot("memo_bn_menu_list_sunlight", sunlight = true) { MenuList() }
    @Test fun memo_bn_menu_list_font20() = shot("memo_bn_menu_list_font20", fontScale = 2.0f) { MenuList() }
    @Test fun memo_bn_detail_reprint_limit() = shot("memo_bn_detail_reprint_limit") { DetailReprintLimit() }
    @Test fun memo_bn_detail_font20() = shot("memo_bn_detail_font20", fontScale = 2.0f) { DetailReprintLimit() }
}
