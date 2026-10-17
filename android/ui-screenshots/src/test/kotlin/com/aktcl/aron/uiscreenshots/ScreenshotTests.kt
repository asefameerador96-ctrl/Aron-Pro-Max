package com.aktcl.aron.uiscreenshots

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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.aktcl.aron.feature.home.R as HomeR
import com.aktcl.aron.feature.sale.R as SaleR

/**
 * N-023 / docs/32 s2a item 8(b): golden screenshots of every SR screen on the Calm Glass kit plus the AMO and TSO homes,
 * tier B, 360 x 800 dp (the field phone, screens-sr s0), Robolectric native graphics on SDK 36 (the SDK the feature
 * screen tests run on). Home, Sale entry, Review and Memo detail in Bangla light, English light and Bangla sunlight
 * (Home and Sale entry also in Bangla at font scale 1.3); Stock, the outlet picker, the visit check (in range and out of
 * range), Attendance, Summary, Sales Submit, Tasks, Login and the AMO and TSO homes in Bangla light and Bangla sunlight.
 * Every screen but Home and Login sits in the host frame (`SrFrame`: glass top bar with the title and the compact
 * synced chip), as on the phone. Fixtures are the sample data of the *Shot.kt look-and-fix captures: never a real
 * outlet, owner or phone number.
 *
 * Default = verify against src/test/screenshots (0.2 percent changed-pixel tolerance); see docs/status/android-core-ui.md to re-record.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp")
class ScreenshotTests {
    @get:Rule val rule = createComposeRule()

    private val recordTo: String? = System.getProperty("aron.screenshots.recordTo")
    private val goldens: String = System.getProperty("aron.screenshots.goldens") ?: "src/test/screenshots"
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private fun shot(name: String, language: AppLanguage, sunlight: Boolean = false, fontScale: Float = 1.0f, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        rule.setContent {
            CompositionLocalProvider(
                LocalContext provides ctx,
                LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale),
            ) { AronTheme(language, dark = false, sunlight = sunlight, tier = GlassTier.B, reducedMotion = true) { content() } }
        }
        rule.waitForIdle()
        if (recordTo == null && System.getProperty("roborazzi.test.record") != "true") {
            // a missing golden must fail the build, never pass silently or be recorded by a normal run
            assertTrue("missing golden $goldens/$name.png", java.io.File("$goldens/$name.png").exists())
        }
        rule.onRoot().captureRoboImage("${recordTo ?: goldens}/$name.png", roborazziOptions = options)
    }

    /** The host frame of every SR screen but Home (app-sr `SrFrame`): the glass top bar with the title and the compact synced chip. */
    @Composable
    private fun Frame(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
        val time = localizedDigits("10:42")
        AronScreen(
            topBar = { AronTopBar(title = title, subtitle = subtitle, onBack = {}, trailing = { AronStatusChip(SyncChipState.SyncedAt(time), form = ChipForm.Compact, onClick = {}) }) },
        ) { content() }
    }

    // ---- Home fixtures (HomeShot) ----
    private val header = HomeModel.header("SR", "Testing Banani", "sr334001", "Apsis RouteDaily", "daily", "2026-10-05")

    @Composable
    private fun Home(language: AppLanguage) {
        val locale = if (language == AppLanguage.BN) UiLocale.BN else UiLocale.EN
        val piece = stringResource(SaleR.string.sale_unit_piece)
        val dozen = stringResource(SaleR.string.sale_unit_dozen)
        val stick = stringResource(SaleR.string.sale_unit_stick)
        val lighter = "Lighter" // sample data: category names are bundle data, not resources
        val match = "Match"
        val cigarette = "Cigarette"
        HomeContent(
            header, HomeTiles.resolve(emptySet(), 2), BundleFreshness.Fresh, false, DeviceHealthModel.of(62, 1_200, 3, 12), onTile = {},
            onSettings = {}, hero = HomeHero(Formats.money(4_391_000, locale), 6, 60, "08:52", HomeDayState.IN_FIELD),
            kpis = HomeKpis("10%", 54, 0, listOf(HomeStockLine(cigarette, 12_000, 9_800, stick), HomeStockLine(lighter, 400, 25, piece), HomeStockLine(match, 400, 394, dozen))),
            money = HomeMoneyLines(
                listOf(HomeMoneyLine(match, Formats.money(141_000, locale)), HomeMoneyLine(lighter, Formats.money(4_687_500, locale))),
                Formats.money(4_828_500, locale), null, "−" + Formats.money(437_500, locale), Formats.money(4_391_000, locale),
            ),
            sync = SyncChipState.SyncedAt(Formats.digits("10:42", locale)), onSync = {}, sunlight = false, onSunlight = {},
            dock = HomeDock.START_SELLING, routeKindLabel = "Daily",
        )
    }

    // ---- selling fixtures (SellShot) ----
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

    private val maxr = SaleSku(100, "MAXR10", "cigarette", "MaxR 10S", null, "stick", 10, 8_000, 1, "2026-09-01", stockBase = 400, drp = DrpRule(10))
    private val prime = SaleSku(101, "PRIME10", "cigarette", "Prime Gold 10S", null, "stick", 10, 6_500, 1, "2026-09-01", stockBase = 610)
    private val aster = SaleSku(103, "ASTER", "lighter", "Aster", null, "piece", 1, 12_500, 1, "2026-09-01", stockBase = 25)
    private val match = SaleSku(105, "FB", "match", "Flame Box", null, "dozen", 1, 28_000, 1, "2026-09-01", stockBase = 40)
    private val skus = listOf(maxr, prime, aster, match)
    private val catalog = skus.associateBy { it.skuId }
    private fun draft() = SaleDraft("v1", 50001, 10231, "2026-10-05", memoUuid = "m1")
    private val saleDraft = SaleDraftOps.setQuantity(SaleDraftOps.setQuantity(draft(), 103, 26, QtyUnit.PIECE), 100, 40, QtyUnit.STICK) // stock warning on Aster
    private val saleReview = SaleReviewCalculator.review(saleDraft, catalog)
    private val reviewReview = SaleReviewCalculator.review(SaleDraftOps.setSlide(SaleDraftOps.setQuantity(SaleDraftOps.setQuantity(draft(), 100, 20, QtyUnit.STICK), 103, 3, QtyUnit.PIECE), 100, 10), catalog)

    // ---- closing fixtures (CloseShot) ----
    private val now = { "2026-10-05T04:42:00.000Z" }
    private val notCheckedIn = AttendanceState(coordinatesText = "23.792580, 90.407830", addressText = "23.792580, 90.407830", fixAccuracyM = 18)
    private val memo = StoredMemo(
        "m1", "sr334001-261005-001", 50001, "2026-10-05", "2026-10-05T04:00:00.000Z", null,
        listOf(MemoItem(100, 40, 8_000, 320_000), MemoItem(103, 3, 12_500, 37_500)), emptyList(), emptyList(),
        357_500, 0, 0, 0, 357_500, 300_000, 57_500,
    )
    private fun skuName(id: Long) = if (id == 100L) "MaxR 10S" else "Aster"
    private val summary = DaySummaryCalculator.compute(
        listOf(
            SummaryMemo("a", null, listOf(SummaryLine(103, 375, 4_687_500), SummaryLine(100, 40, 320_000)), listOf(SummaryDiscount(103, 437_500, "offer")), 0, 4_570_000),
            SummaryMemo("b", null, listOf(SummaryLine(100, 20, 160_000)), emptyList(), 0, 160_000),
        ),
        mapOf(103L to SummarySku(103, "lighter"), 100L to SummarySku(100, "cigarette")),
        mapOf(103L to 400L, 100L to 100L),
    )
    private val cats = mapOf("lighter" to "লাইটার", "cigarette" to "সিগারেট")
    private val units = mapOf("lighter" to "পিস", "cigarette" to "স্টিক")
    private val counts = listOf(CountRow("visit", 6, 6), CountRow("memo", 6, 5), CountRow("stock_movement", 2, null), CountRow("qc_line", 0, 0))
    private val gate = SalesSubmitRules.gate(OutboxStates(1, 0, 40, 0, 0), counts, DuesAtSubmit(1, 84_000), false)
    private val labels = mapOf("visit" to "আউটলেট", "memo" to "মেমো", "stock_movement" to "স্টক", "qc_line" to "কিউসি")
    private val tasks = TaskListState(
        listOf(
            TaskItem("t1", "visit_outlet", "Babu Store", "Babu Store দোকানে Maxim এ OOS আছে। আজকের মধ্যে স্টক দিন।", 5, "Babu Store", "2026-11-30", "ongoing", null),
            TaskItem("t2", "collect_due", "Due collection", null, 6, "Karim Tea Stall", "2026-10-08", "ongoing", null),
            TaskItem("t3", "visit_outlet", "Banani Store", "POSM check", 7, "Banani Store", "2026-10-02", "completed", "2026-10-02T05:00:00.000Z"),
        ),
        2, "2026-10-05T04:42:00.000Z",
    )

    // ---- role homes (RoleHomeShot) ----
    private val amo = HomeUser("Testing Banani", "amo5001", "AMO", offline = false, reauthRequired = false, updateRequired = false)
    private val tso = HomeUser("Testing Dhaka North", "tso1001", "TSO", offline = true, reauthRequired = false, updateRequired = false)
    private val health = DeviceHealthModel.of(62, 1200, 3, 15)

    // ---- screens ----
    @Composable private fun Stock() = Frame(stringResource(HomeR.string.hm_tile_stock)) {
        StockContent(stockRows, stockTotals, StockMessage.SAVED, slipNotPrinted = true, onIssue = { _, _ -> }, onSave = {}, onPrint = {})
    }

    @Composable private fun Picker() = Frame(stringResource(HomeR.string.hm_tile_sale)) {
        val rows = OutletPicker.rows(outlets)
        OutletPickerContent(rows, OutletPicker.chips(rows), OutletPicker.ALL_CHIP, {}, {}, marks = marks)
    }

    @Composable private fun VisitInRange() = Frame(stringResource(HomeR.string.hm_tile_sale)) {
        VisitCheckContent(
            VisitUiState.Open(OpenVisit("v1", 1, 1, "in_range", "sale_allowed", true, false, null, "2026-10-05T04:31:07Z")),
            onRefresh = {}, onForceSale = {}, onRetry = {}, onMap = null, outlet = card, onStartCall = {}, onPreviousSales = {}, onNoSale = {},
        )
    }

    @Composable private fun VisitOutOfRange() = Frame(stringResource(HomeR.string.hm_tile_sale)) {
        VisitCheckContent(
            VisitUiState.NeedsDecision(visitOutlet, GeoVerdictResult(GeoVerdict.OUT_OF_RANGE, GeoAction.REFRESH_OFFERED, 180.0), 1, true, 3, false, false, phoneLat = 23.7912, phoneLng = 90.4009),
            onRefresh = {}, onForceSale = {}, onRetry = {}, onMap = {}, outlet = card, onPreviousSales = {}, onNoSale = {},
        )
    }

    @Composable private fun Sale() = Frame(stringResource(SaleR.string.sale_title), subtitle = card.name) {
        SaleEntryScreen(skus, saleReview, { id -> saleDraft.lines.firstOrNull { it.skuId == id }?.qtyEntered ?: 0 }, { _, _ -> }, {}, {}, recentSkuIds = setOf(100L), onPreviousSales = {})
    }

    @Composable private fun Review() = Frame(stringResource(SaleR.string.review_title)) {
        ReviewScreen(reviewReview, {}, outletLine = "Banani Store (DHK-344-001) · Apsis RouteDaily · Apsis Cluster", onCreditToggle = {}, onQc = {})
    }

    @Composable private fun Memo() = Frame(stringResource(HomeR.string.hm_tile_memo)) {
        val stick = stringResource(SaleR.string.sale_unit_stick)
        val piece = stringResource(SaleR.string.sale_unit_piece)
        MemoDetailView(MemoMenu.detail(memo), true, 57_500, ::skuName, {}, {}, {}, outletName = "Banani Store", onHistory = {}, skuUnit = { id -> if (id == 100L) stick else piece })
    }

    @Composable private fun Attendance() = Frame(stringResource(HomeR.string.hm_tile_attendance)) {
        AttendanceContent(notCheckedIn, "17:00", {}, {}, onRefreshFix = {}, nowIso = now)
    }

    @Composable private fun Summary() = Frame(stringResource(HomeR.string.hm_tile_summary)) {
        SummaryScreen(summary, ::skuName, {}, categoryLabel = { cats[it] ?: it }, unitLabel = { units[it] ?: "" })
    }

    @Composable private fun Submit() = Frame(stringResource(HomeR.string.hm_tile_sales_submit)) {
        SalesSubmitScreen(true, counts, gate, SubmitProgress.Idle, { labels[it] ?: it }, {}, {}, lastContactText = localizedDigits("10:42"))
    }

    @Composable private fun Tasks() = Frame(stringResource(HomeR.string.hm_tile_tasks)) { TaskContent(tasks, {}) }

    @Composable private fun Login(sunlight: Boolean) =
        LoginContent(LoginUiState(username = "sr334001", password = "secret", message = LoginMessage.InvalidCredentials), "ARON SR", "1.0.25 (12)", {}, {}, {}, {}, {}, sunlight = sunlight, onSunlight = {})

    @Composable private fun AmoHome(sunlight: Boolean) = RoleHomeContent(
        RoleHomes.header(RoleHomes.AMO_PREFIX, amo, "2026-10-05"), amo, BundleStatus.Ready("b-17", "2026-10-05"),
        RoleHomes.amoTiles(mapOf(RoleHomes.AmoTile.OUTLET to 3)), SyncChipState.SyncedAt(localizedDigits("10:42")), onTile = {}, onSettings = {},
        hero = RoleHero.Amo(4_391_000, 6, 60), health = health, sunlight = sunlight,
    )

    @Composable private fun TsoHome(sunlight: Boolean) = RoleHomeContent(
        RoleHomes.header(RoleHomes.TSO_PREFIX, tso, "2026-10-05"), tso, BundleStatus.Offline,
        RoleHomes.tsoTiles(mapOf(RoleHomes.TsoTile.LEAVE to 1)), SyncChipState.Offline, onTile = {}, onSettings = {},
        hero = RoleHero.Tso(3, 12), health = health, sunlight = sunlight,
    )

    // ---- Home ----
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun home_bn_light() = shot("home_bn_light", AppLanguage.BN) { Home(AppLanguage.BN) }
    @Test fun home_en_light() = shot("home_en_light", AppLanguage.EN) { Home(AppLanguage.EN) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun home_bn_sunlight() = shot("home_bn_sunlight", AppLanguage.BN, sunlight = true) { Home(AppLanguage.BN) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun home_bn_light_font13() = shot("home_bn_light_font13", AppLanguage.BN, fontScale = 1.3f) { Home(AppLanguage.BN) }

    // ---- Sale entry ----
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun sale_bn_light() = shot("sale_bn_light", AppLanguage.BN) { Sale() }
    @Test fun sale_en_light() = shot("sale_en_light", AppLanguage.EN) { Sale() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun sale_bn_sunlight() = shot("sale_bn_sunlight", AppLanguage.BN, sunlight = true) { Sale() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun sale_bn_light_font13() = shot("sale_bn_light_font13", AppLanguage.BN, fontScale = 1.3f) { Sale() }

    // ---- Review ----
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun review_bn_light() = shot("review_bn_light", AppLanguage.BN) { Review() }
    @Test fun review_en_light() = shot("review_en_light", AppLanguage.EN) { Review() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun review_bn_sunlight() = shot("review_bn_sunlight", AppLanguage.BN, sunlight = true) { Review() }

    // ---- Memo detail ----
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun memo_bn_light() = shot("memo_bn_light", AppLanguage.BN) { Memo() }
    @Test fun memo_en_light() = shot("memo_en_light", AppLanguage.EN) { Memo() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun memo_bn_sunlight() = shot("memo_bn_sunlight", AppLanguage.BN, sunlight = true) { Memo() }

    // ---- Stock ----
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun stock_bn_light() = shot("stock_bn_light", AppLanguage.BN) { Stock() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun stock_bn_sunlight() = shot("stock_bn_sunlight", AppLanguage.BN, sunlight = true) { Stock() }

    // ---- Outlet picker ----
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun picker_bn_light() = shot("picker_bn_light", AppLanguage.BN) { Picker() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun picker_bn_sunlight() = shot("picker_bn_sunlight", AppLanguage.BN, sunlight = true) { Picker() }

    // ---- Visit check ----
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun visit_in_bn_light() = shot("visit_in_bn_light", AppLanguage.BN) { VisitInRange() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun visit_in_bn_sunlight() = shot("visit_in_bn_sunlight", AppLanguage.BN, sunlight = true) { VisitInRange() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun visit_out_bn_light() = shot("visit_out_bn_light", AppLanguage.BN) { VisitOutOfRange() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun visit_out_bn_sunlight() = shot("visit_out_bn_sunlight", AppLanguage.BN, sunlight = true) { VisitOutOfRange() }

    // ---- Attendance ----
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun attendance_bn_light() = shot("attendance_bn_light", AppLanguage.BN) { Attendance() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun attendance_bn_sunlight() = shot("attendance_bn_sunlight", AppLanguage.BN, sunlight = true) { Attendance() }

    // ---- Summary ----
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun summary_bn_light() = shot("summary_bn_light", AppLanguage.BN) { Summary() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun summary_bn_sunlight() = shot("summary_bn_sunlight", AppLanguage.BN, sunlight = true) { Summary() }

    // ---- Sales Submit ----
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun submit_bn_light() = shot("submit_bn_light", AppLanguage.BN) { Submit() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun submit_bn_sunlight() = shot("submit_bn_sunlight", AppLanguage.BN, sunlight = true) { Submit() }

    // ---- Tasks ----
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun tasks_bn_light() = shot("tasks_bn_light", AppLanguage.BN) { Tasks() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun tasks_bn_sunlight() = shot("tasks_bn_sunlight", AppLanguage.BN, sunlight = true) { Tasks() }

    // ---- Login ----
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun login_bn_light() = shot("login_bn_light", AppLanguage.BN) { Login(false) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun login_bn_sunlight() = shot("login_bn_sunlight", AppLanguage.BN, sunlight = true) { Login(true) }

    // ---- AMO and TSO homes ----
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_home_bn_light() = shot("amo_home_bn_light", AppLanguage.BN) { AmoHome(false) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_home_bn_sunlight() = shot("amo_home_bn_sunlight", AppLanguage.BN, sunlight = true) { AmoHome(true) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun tso_home_bn_light() = shot("tso_home_bn_light", AppLanguage.BN) { TsoHome(false) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun tso_home_bn_sunlight() = shot("tso_home_bn_sunlight", AppLanguage.BN, sunlight = true) { TsoHome(true) }

    /** Bootstrap guard: a run that only recorded images must not look green (CI uploads test results on failure only). */
    @Test fun recordingRunNeverPasses() {
        if (recordTo != null) fail("Goldens recorded to $recordTo: download the artifact, copy the PNGs into android/ui-screenshots/src/test/screenshots and turn the record switch off.")
    }
}
