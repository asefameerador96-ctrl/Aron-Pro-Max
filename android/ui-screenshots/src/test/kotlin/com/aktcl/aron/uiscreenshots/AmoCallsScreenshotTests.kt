package com.aktcl.aron.uiscreenshots

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.GlassTier
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.feature.amo.*

/**
 * Goldens of the AMO call screens (lane android-amo-calls, screenshots 17-32): the route and retailer picker with the range area, the
 * route and retailer panels, the force-sale reason, Perf Assessment, the saved dialog, Joint Call v2 and previous sale data, in Bangla,
 * English, 200 % font and Sunlight. Record on Linux only: `-Proborazzi.record=true --tests '*AmoCallsScreenshotTests*'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class AmoCallsScreenshotTests {
    @get:Rule val rule = createComposeRule()

    private val recordTo: String? = System.getProperty("aron.screenshots.recordTo")
    private val goldens: String = System.getProperty("aron.screenshots.goldens") ?: "src/test/screenshots"
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private fun shot(name: String, language: AppLanguage, title: String, sunlight: Boolean = false, fontScale: Float = 1f, act: () -> Unit = {}, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides ctx, LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale)) {
                AronTheme(language, dark = false, sunlight = sunlight, tier = GlassTier.B, reducedMotion = true, amo = true) { AmoFrame(title, onBack = {}) { content() } }
            }
        }
        rule.waitForIdle(); act(); rule.waitForIdle()
        if (recordTo == null && System.getProperty("roborazzi.test.record") != "true") assertTrue("missing golden $goldens/$name.png", java.io.File("$goldens/$name.png").exists())
        rule.onRoot().captureRoboImage("${recordTo ?: goldens}/$name.png", roborazziOptions = options)
    }

    private fun outlet(id: Long, code: String, name: String, lat: Double? = 23.78) = OutletEntity(
        outletId = id, routeId = 1, code = code, name = name, nameBn = null, nameSortKey = name.lowercase(), ownerName = "o", contactNumber = "01711111111",
        lat = lat, lng = lat?.let { 90.41 }, locationConfirmed = true, provisionalLat = null, provisionalLng = null, clusterId = 1, clusterName = "Kacha Bazar", channel = "gt",
        subChannelId = null, geoClass = null, status = "active", priceType = "std", outletKind = "retail", radiusM = 50, maxAccuracyM = 50, visitSequence = null,
        openDueMtk = 0, openDueAsOf = null, programmeFlagsJson = "{}", pendingRequest = false,
    )

    private val outlets = listOf(
        outlet(1, "2654199", "Basahar Store"), outlet(2, "2647524", "Tota Store"), outlet(3, "2542617", "Belal Store"), outlet(4, "1750844", "Default_Dariapur", lat = null),
        outlet(5, "1456474", "Sujon Store"), outlet(6, "1421252", "Jannati Store2"),
    )
    private val rows = CallPick.rows(outlets, calledToday = setOf(2L, 5L))
    private val letters = CallPick.letters(outlets)

    private val tiles = listOf(
        MenuTile(com.aktcl.aron.feature.amo.R.drawable.amo_art_cc_sale, com.aktcl.aron.feature.amo.R.string.amo_cc_menu_sale, "t1") {},
        MenuTile(com.aktcl.aron.feature.amo.R.drawable.amo_art_cc_assess, com.aktcl.aron.feature.amo.R.string.amo_cc_menu_assessment, "t2") {},
        MenuTile(com.aktcl.aron.feature.amo.R.drawable.amo_art_cc_survey, com.aktcl.aron.feature.amo.R.string.amo_cc_menu_survey, "t3") {},
    )

    @Composable private fun pick(range: CallRange, outlet: String? = "Basahar Store (2654199-01711111111-Kacha Bazar)") =
        AmoCallPickContent("DariapurDaily", outlet, true, letters, "", live = outlet != null, range = range, enabledOutlet = true, locked = false, {}, {}, {}, {}, {}, below = { AmoMenuTiles(tiles) })

    @Test fun amo_control_call_pick_out_of_range_bn() = shot("amo_control_call_pick_bn", AppLanguage.BN, "কন্ট্রোল কল অ্যাক্টিভিটি") { pick(CallRange.Out(true, true)) }
    @Test fun amo_control_call_pick_out_of_range_en() = shot("amo_control_call_pick_en", AppLanguage.EN, "Control Call Activity") { pick(CallRange.Out(true, true)) }
    @Test fun amo_control_call_pick_out_of_range_fs2() = shot("amo_control_call_pick_bn_fs2", AppLanguage.BN, "কন্ট্রোল কল অ্যাক্টিভিটি", fontScale = 2f) { pick(CallRange.Out(true, true)) }
    @Test fun amo_control_call_pick_out_of_range_sunlight() = shot("amo_control_call_pick_sunlight_bn", AppLanguage.BN, "কন্ট্রোল কল অ্যাক্টিভিটি", sunlight = true) { pick(CallRange.Out(true, true)) }
    @Test fun amo_control_call_menu_bn() = shot("amo_cc_tiles_bn", AppLanguage.BN, "কন্ট্রোল কল অ্যাক্টিভিটি") { pick(CallRange.In) }
    @Test fun amo_control_call_menu_sunlight() = shot("amo_cc_tiles_sunlight_bn", AppLanguage.BN, "কন্ট্রোল কল অ্যাক্টিভিটি", sunlight = true) { pick(CallRange.In) }
    // ---- Joint Call pick states (screens 29, 30): the title and the SR call assessment tile; D-PAR-AMO-17
    private val jointTiles = listOf(MenuTile(com.aktcl.aron.core.ui.R.drawable.aron_ic_joint_call, com.aktcl.aron.feature.amo.R.string.amo_cp_joint_tile, "jt", {}))
    @Composable private fun jointPick(range: CallRange, route: String? = "Gulshan-1Daily", outlet: String? = "Masba Store (2689479-01865967033-Jobbar Tower Lake Par)") =
        AmoCallPickContent(route, outlet, outlet != null, if (outlet != null) letters else emptyList(), "", live = outlet != null, range = range, enabledOutlet = route != null, locked = false, {}, {}, {}, {}, {},
            below = { AmoMenuTiles(jointTiles) }, idleHint = androidx.compose.ui.res.stringResource(com.aktcl.aron.feature.amo.R.string.amo_cp_route_placeholder))
    @Test fun amo_joint_pick_empty_bn() = shot("amo_joint_pick_empty_bn", AppLanguage.BN, "জয়েন্ট কল অ্যাক্টিভিটি") { jointPick(CallRange.Idle, route = null, outlet = null) }
    @Test fun amo_joint_pick_in_range_bn() = shot("amo_joint_pick_in_range_bn", AppLanguage.BN, "জয়েন্ট কল অ্যাক্টিভিটি") { jointPick(CallRange.In) }
    @Test fun amo_joint_pick_out_of_range_bn() = shot("amo_joint_pick_out_of_range_bn", AppLanguage.BN, "জয়েন্ট কল অ্যাক্টিভিটি") { jointPick(CallRange.Out(true, true)) }
    @Test fun amo_joint_pick_empty_sunlight_bn() = shot("amo_joint_pick_empty_sunlight_bn", AppLanguage.BN, "জয়েন্ট কল অ্যাক্টিভিটি", sunlight = true) { jointPick(CallRange.Idle, route = null, outlet = null) }
    @Test fun amo_sales_pick_empty_bn() = shot("amo_sales_pick_empty_bn", AppLanguage.BN, "বিক্রয়") {
        // D-PAR-AMO-05 (screenshot 21): the Sales tile is on the empty state
        AmoCallPickContent(null, null, false, emptyList(), "", false, CallRange.Idle, false, false, {}, {}, {}, {}, {}, belowWhenIdle = true,
            below = { com.aktcl.aron.feature.amo.AmoMenuTiles(listOf(com.aktcl.aron.feature.amo.MenuTile(com.aktcl.aron.feature.amo.R.drawable.amo_art_cc_sale, com.aktcl.aron.feature.amo.R.string.amo_cc_menu_sale, com.aktcl.aron.feature.amo.AmoCallTags.MENU_SALE, {}))) })
    }

    @Test fun amo_outlet_list_bn() = shot("amo_outlet_list_bn", AppLanguage.BN, "কন্ট্রোল কল অ্যাক্টিভিটি") { AmoOutletPanel(rows, 1L, {}) }
    @Test fun amo_outlet_list_fs2() = shot("amo_outlet_list_bn_fs2", AppLanguage.BN, "কন্ট্রোল কল অ্যাক্টিভিটি", fontScale = 2f) { AmoOutletPanel(rows, 1L, {}) }
    @Test fun amo_route_list_bn() = shot("amo_route_list_bn", AppLanguage.BN, "বিক্রয়") {
        AmoRoutePanel(listOf("LaxmipurDaily", "Tempu StandDaily", "Kuptola(Sat, Mon, Wed)", "Miar BazerDaily").mapIndexed { i, t -> AmoRouteRow(i + 1L, t, 5) }, 1L, {})
    }

    @Test fun amo_force_sale_reason_bn() = shot("amo_force_sale_reason_bn", AppLanguage.BN, "বিক্রয়") {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().padding(24.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { AmoForceReasonCard({}) }
    }
    @Test fun amo_data_saved_bn() = shot("amo_data_saved_bn", AppLanguage.BN, "এসআর পারফ. অ্যাসেসমেন্ট") {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().padding(24.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { AmoSavedCard("ডেটা সফলভাবে সংরক্ষিত হয়েছে।", "ওকে", {}) }
    }

    private val brands = listOf("Maxim", "Avon", "ARIS", "Marise", "Black Diamond", "Sunmoon", "Supreme", "Special Abul Bidi", "Existing Abul Bidi/ 42 No.", "Ananda Bidi", "Abul Bidi Gold", "Abul Bidi Style", "Aster", "Flame Box", "Salmon", "ESSE", "MAX", "Maxim - Platinum Series")
        .mapIndexed { i, n -> AmoBrand(i + 1L, n, null) }

    @Composable private fun perf() = PerfAssessmentContent(
        brands, PerfState(present = setOf(1, 2, 3), oos = setOf(1, 2, 3), posm = false), saved = false, saving = false, {}, {}, {}, {},
        outletLabel = "Masba Store (2689479-01865967033-Jobbar Tower Lake Par)", routeName = "Gulshan-1Daily",
    )
    @Test fun amo_perf_assessment_calls_bn() = shot("amo_perf_assessment_calls_bn", AppLanguage.BN, "এসআর পারফ. অ্যাসেসমেন্ট") { perf() }
    @Test fun amo_perf_assessment_calls_en() = shot("amo_perf_assessment_calls_en", AppLanguage.EN, "SR Perf. Assessment") { perf() }
    @Test fun amo_perf_assessment_calls_fs2() = shot("amo_perf_assessment_calls_bn_fs2", AppLanguage.BN, "এসআর পারফ. অ্যাসেসমেন্ট", fontScale = 2f) { perf() }
    @Test fun amo_perf_assessment_calls_sunlight() = shot("amo_perf_assessment_calls_sunlight_bn", AppLanguage.BN, "এসআর পারফ. অ্যাসেসমেন্ট", sunlight = true) { perf() }

    private val rubric = JointCall.rubric(
        """[{"rubric_id":9,"version":2,"kind":"joint_call","criteria":[
        {"criterion_id":1,"label_en":"Summarise the situation","label_bn":"পরিস্থিতি সংক্ষেপে বলুন","answer_type":"score_1_5","enabled":true,"section":"5-step sales call assessment","section_bn":"৫ ধাপ সেলস কল অ্যাসেসমেন্ট","guidance_en":"(What to look for: OHS count, OOS, product quality check)","guidance_bn":"(যা দেখতে হবে: OHS কাউন্ট, OOS, Product quality চেক)"},
        {"criterion_id":2,"label_en":"Describe the concept","label_bn":"ধারণাটি বর্ণনা করুন","answer_type":"score_1_5","enabled":true,"section":"5-step sales call assessment","section_bn":"৫ ধাপ সেলস কল অ্যাসেসমেন্ট","guidance_en":"(Explain SOQ requirement, present the offer)","guidance_bn":"(যা দেখতে হবে: SOQ রিকুয়ারমেন্ট ঠিক মতন বুঝানো)"},
        {"criterion_id":3,"label_en":"Relationship","label_bn":"সম্পর্ক","answer_type":"score_1_5","enabled":true,"section":"Relationship assessment","section_bn":"রিলেসনশিপ এসেসম্যান্ট","guidance_en":"(Greeting, knowing the shopkeeper's name)","guidance_bn":"(যা দেখতে হবে: সম্ভাষণ দেওয়া, দোকানদারের নাম জানা)"},
        {"criterion_id":4,"label_en":"Service quality","label_bn":"সার্ভিস কোয়ালিটি","answer_type":"score_1_5","enabled":true,"section":"Service quality assessment","section_bn":"সার্ভিস কোয়ালিটি এসেসম্যান্ট","guidance_en":"(Visiting on time, correct memo)","guidance_bn":"(যা দেখতে হবে: টাইমলি দোকানে আসা, মেমো সঠিক ভাবে করা)"}]}]""",
    )!!
    @Composable private fun joint() = JointCallContent(rubric, null, JointState().star(1, 3), saved = false, saving = false, { _, _ -> }, {}, outletLabel = "Masba Store (2689479-01865967033-Jobbar Tower Lake Par)", routeName = "Gulshan-1Daily")
    @Test fun amo_joint_call_v2_bn() = shot("amo_joint_call_v2_bn", AppLanguage.BN, "এসআর কল অ্যাসেসমেন্ট") { joint() }
    @Test fun amo_joint_call_v2_en() = shot("amo_joint_call_v2_en", AppLanguage.EN, "SR Call Assessment") { joint() }
    @Test fun amo_joint_call_v2_fs2() = shot("amo_joint_call_v2_bn_fs2", AppLanguage.BN, "এসআর কল অ্যাসেসমেন্ট", fontScale = 2f) { joint() }
    @Test fun amo_joint_call_v2_sunlight() = shot("amo_joint_call_v2_sunlight_bn", AppLanguage.BN, "এসআর কল অ্যাসেসমেন্ট", sunlight = true) { joint() }

    private val history = PreviousSales.parseServer(
        """{"lines":[{"sku":{"sku_id":11,"sku_code":"MAXR-10S","short_name":"MaxR-10S"},"qty_report":"10.000","report_unit":"packet","qty_base":100,"value_mtk":80000000},
        {"sku":{"sku_id":12,"sku_code":"ABS","short_name":"ABS"},"qty_report":"25.000","report_unit":"packet","qty_base":250,"value_mtk":20000000},
        {"sku":{"sku_id":13,"sku_code":"ASTER","short_name":"Aster"},"qty_report":"1.000","report_unit":"box","qty_base":25,"value_mtk":12500000}]}""", "2026-11-05",
    )
    @Test fun amo_previous_sales_bn() = shot("amo_previous_sales_bn", AppLanguage.BN, "সেল ডাটা") { PreviousSalesContent("2026-11-05", "2026-11-06", history, {}, outletLabel = "Siyam (1618410-1832714554-Madrasa Road)") }
    @Test fun amo_previous_sales_empty_en() = shot("amo_previous_sales_empty_en", AppLanguage.EN, "Sale data") { PreviousSalesContent("2026-11-06", "2026-11-06", PreviousSales.parseServer("""{"lines":[]}""", "2026-11-06"), {}) }

    // ---- rows 45 and 66 (Summary empty state; Sales Submit due warning and success dialogs) ----
    private val emptySummary = com.aktcl.aron.feature.memo.domain.DaySummary(emptyList(), emptyList(), 0L, 0L, 0L, 0)
    @Test fun amo_summary_empty_bn() = shot("amo_summary_empty_bn", AppLanguage.BN, "বিক্রয় সারসংক্ষেপ") {
        com.aktcl.aron.feature.memo.ui.SummaryScreen(emptySummary, { "" }, {}, emptyText = "কিছু পাওয়া যায়নি", printWhenEmpty = true, salesBlock = true)
    }
    // screenshot 45 / manual p46 with sales: the SKU table, the category dock, then the "Total sales" block above Print
    private val daySummary = com.aktcl.aron.feature.memo.domain.DaySummary(
        listOf(
            com.aktcl.aron.feature.memo.domain.SkuSummaryRow(100, "cigarette", 2, 300, 240_000, 0, 240_000, 100, qtyEntered = 15, returnEntered = 5, unitKey = "packet"),
            com.aktcl.aron.feature.memo.domain.SkuSummaryRow(105, "match", 1, 3, 84_000, 0, 84_000, 37, qtyEntered = 3, returnEntered = 37, unitKey = "dozen"),
        ),
        listOf(
            com.aktcl.aron.feature.memo.domain.CategorySummaryRow("cigarette", 300, 240_000, 0, 240_000, 100, qtyEntered = 15, returnEntered = 5, unitKey = "packet"),
            com.aktcl.aron.feature.memo.domain.CategorySummaryRow("match", 3, 84_000, 0, 84_000, 37, qtyEntered = 3, returnEntered = 37, unitKey = "dozen"),
        ),
        324_000, 0, 324_000, 3,
    )
    @Test @Config(qualifiers = "w360dp-h1000dp") fun amo_summary_data_bn() = shot("amo_summary_data_bn", AppLanguage.BN, "বিক্রয় সারসংক্ষেপ") {
        com.aktcl.aron.feature.memo.ui.SummaryScreen(daySummary, { saleCatalog[it]?.code ?: "SKU" }, {}, categoryLabel = { if (it == "match") "ম্যাচ" else "সিগারেট" }, printWhenEmpty = true, salesBlock = true)
    }
    // a QC deduction (a return of 125.00 taka) in the day: the row is "discount and others (-)" and gross minus it is the grand total (D-PAR-AMO-16)
    private val qcSummary = daySummary.copy(discountAndOthersMtk = 125_000, grandTotalMtk = 324_000 - 125_000)
    @Test @Config(qualifiers = "w360dp-h1000dp") fun amo_summary_qc_bn() = shot("amo_summary_qc_bn", AppLanguage.BN, "বিক্রয় সারসংক্ষেপ") {
        com.aktcl.aron.feature.memo.ui.SummaryScreen(qcSummary, { saleCatalog[it]?.code ?: "SKU" }, {}, categoryLabel = { if (it == "match") "ম্যাচ" else "সিগারেট" }, printWhenEmpty = true, salesBlock = true)
    }
    @Test @Config(qualifiers = "w360dp-h1000dp") fun amo_summary_data_bn_sunlight() = shot("amo_summary_data_bn_sunlight", AppLanguage.BN, "বিক্রয় সারসংক্ষেপ", sunlight = true) {
        com.aktcl.aron.feature.memo.ui.SummaryScreen(daySummary, { saleCatalog[it]?.code ?: "SKU" }, {}, categoryLabel = { if (it == "match") "ম্যাচ" else "সিগারেট" }, printWhenEmpty = true, salesBlock = true)
    }
    // row 66's two modals are separate windows (Dialog): a root capture cannot show them, so they are proven by SalesSubmitScreenTest (vendorDialogs...)

    // ---- rows 42, 43 (memo detail: Route / Retailer lines, dashed due box with Mark paid) ----
    private val dueMemo = com.aktcl.aron.feature.memo.domain.StoredMemo("m1", "amo-261110-001", 1, "2026-11-10", "2026-11-10T04:00:00.000Z", null,
        listOf(com.aktcl.aron.feature.memo.domain.MemoItem(105, 3, 28_000, 84_000)), emptyList(), emptyList(), 84_000, 0, 0, 0, 84_000, 34_000, 50_000)
    @Test fun amo_memo_detail_due_bn() = shot("amo_memo_detail_due_bn", AppLanguage.BN, "মেমো") {
        com.aktcl.aron.feature.memo.ui.MemoDetailView(
            com.aktcl.aron.feature.memo.domain.MemoMenu.detail(dueMemo), true, 50_000, { "Aster" }, {}, {}, {},
            outletName = "Siyam (1618410-1832714554-Madrasa Road)", routeName = "Savar BazarDaily", dashedDueBox = true,
            detailBanner = androidx.compose.ui.res.stringResource(com.aktcl.aron.feature.memo.R.string.memo_banner_manual), buttonsInRow = true, gradientHeader = true,
            headerCard = { com.aktcl.aron.feature.amo.AmoSaleHeaderCard("Savar BazarDaily", "Siyam (1618410-1832714554-Madrasa Road)", routeLabel = androidx.compose.ui.res.stringResource(com.aktcl.aron.feature.amo.R.string.amo_memo_hdr_section), padded = false) },
        )
    }
    // screenshot 42 right: a paid memo (no due box), four lines, the table and totals
    private val paidMemo = com.aktcl.aron.feature.memo.domain.StoredMemo("m2", "amo-261110-002", 1, "2026-11-10", "2026-11-10T05:00:00.000Z", null,
        listOf(com.aktcl.aron.feature.memo.domain.MemoItem(100, 20, 8_000, 160_000), com.aktcl.aron.feature.memo.domain.MemoItem(101, 20, 7_000, 140_000), com.aktcl.aron.feature.memo.domain.MemoItem(103, 1, 12_500, 12_500)),
        emptyList(), emptyList(), 312_500, 0, 0, 0, 312_500, 312_500, 0)
    @Test @Config(qualifiers = "w360dp-h1000dp") fun amo_memo_detail_paid_bn() = shot("amo_memo_detail_paid_bn", AppLanguage.BN, "মেমো") {
        com.aktcl.aron.feature.memo.ui.MemoDetailView(
            com.aktcl.aron.feature.memo.domain.MemoMenu.detail(paidMemo), false, 0, { saleCatalog[it]?.code ?: "SKU" },
            {}, {}, {}, outletName = "Ebraj St (Diamond)", routeName = "AgrabadDaily", dashedDueBox = true, detailBanner = androidx.compose.ui.res.stringResource(com.aktcl.aron.feature.memo.R.string.memo_banner_manual),
            buttonsInRow = true, gradientHeader = true,
            headerCard = { com.aktcl.aron.feature.amo.AmoSaleHeaderCard("AgrabadDaily", "Ebraj St (Diamond)", routeLabel = androidx.compose.ui.res.stringResource(com.aktcl.aron.feature.amo.R.string.amo_memo_hdr_section), padded = false) },
        )
    }

    // ---- rows 24, 25, 42, 65 under the AMO look (the shared SR screens, AMO header lines and dashed/vendor variants) ----
    private fun sku(id: Long, cat: String, code: String, name: String, unit: String, perPack: Int, price: Long, stock: Long) =
        com.aktcl.aron.feature.sale.domain.SaleSku(id, code, cat, name, null, unit, perPack, price, 1, "2026-09-01", stockBase = stock)
    private val saleSkus = listOf(
        sku(100, "cigarette", "MAXR10", "MaxR-10S", "stick", 10, 8_000, 394), sku(101, "cigarette", "PRIME10", "Prime Gold 10S", "stick", 10, 6_500, 610),
        sku(103, "lighter", "ASTER", "Aster", "piece", 25, 12_500, 25), sku(105, "match", "FB", "FB", "dozen", 1, 28_000, 40),
    )
    private val saleCatalog = saleSkus.associateBy { it.skuId }
    private val baseDraft = com.aktcl.aron.feature.sale.domain.SaleDraft("v1", 50001, 10231, "2026-11-10", memoUuid = "m1")
    private val enteredDraft = com.aktcl.aron.feature.sale.domain.SaleDraftOps.setQuantity(
        com.aktcl.aron.feature.sale.domain.SaleDraftOps.setQuantity(baseDraft, 100, 10, com.aktcl.aron.rules.QtyUnit.PACK), 103, 1, com.aktcl.aron.rules.QtyUnit.PACK,
    )
    private val enteredReview = com.aktcl.aron.feature.sale.domain.SaleReviewCalculator.review(enteredDraft, saleCatalog)
    private val outletPill = "Babu Store (1618411-01711111111-Savar Metro)"

    @Test fun amo_sale_entry_bn() = shot("amo_sale_entry_bn", AppLanguage.BN, "বিক্রয়") {
        com.aktcl.aron.feature.sale.ui.SaleEntryScreen(
            saleSkus, enteredReview, { id -> enteredDraft.lines.firstOrNull { it.skuId == id }?.qtyEntered ?: 0 }, { _, _ -> }, {}, {},
            recentSkuIds = setOf(100L), onPreviousSales = {}, retailerLabel = outletPill,
            amoSkuCard = true, headerCard = { com.aktcl.aron.feature.amo.AmoSaleHeaderCard("Savar Bazar(Sat, Mon, Wed)", outletPill) }, // D-PAR-AMO-07 (screenshots 23, 24)
        )
    }

    // lows 4 and 5 of the lead's check: a tall frame so the print-later card, the quantity line and the QC button are all in view
    @Test @Config(qualifiers = "w360dp-h1500dp") fun amo_review_bn() = shot("amo_review_bn", AppLanguage.BN, "নিরীক্ষণ") {
        com.aktcl.aron.feature.sale.ui.ReviewScreen(
            enteredReview, {}, outletLine = "$outletPill\nরুট: Savar Bazar(Sat, Mon, Wed)\nক্লাস্টার: Savar Metro", onCreditToggle = {}, onQc = {}, printLaterCard = true, totalsQuantity = true,
        )
    }

    // Audit (AMO high, "Paid-amount dialog: not recorded"): the shared paid-amount prompt (screenshot 25) as the AMO shows it, recorded for review
    @Test fun amo_paid_amount_bn() = shot("amo_paid_amount_bn", AppLanguage.BN, "নিরীক্ষণ") {
        // the dialog's card on the dimmed review (a dialog window is not in the frame): amount typed, live due inside the field (D-PAR-AMO-08)
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0x99000000)).padding(16.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            com.aktcl.aron.feature.sale.ui.CreditDialogCard(enteredReview, "100", {}, 100_000L, true, false, {}, {})
        }
    }

    // four unit kinds on a 360 dp phone (packets, box, dozen, sticks): the per-unit-kind quantity line must wrap, not overflow
    private val looseSku = com.aktcl.aron.feature.sale.domain.SaleSku(106, "LOOSE", "cigarette", "Loose", null, "stick", 10, 800, 1, "2026-09-01", stockBase = 500, entryUnitDefault = "stick", allowLoose = true)
    @Test @Config(qualifiers = "w360dp-h1500dp") fun amo_review_units_bn() = shot("amo_review_units_bn", AppLanguage.BN, "নিরীক্ষণ") {
        val catalog = saleCatalog + (106L to looseSku)
        var d = enteredDraft
        d = com.aktcl.aron.feature.sale.domain.SaleDraftOps.setQuantity(d, 105, 2, com.aktcl.aron.rules.QtyUnit.DOZEN)
        d = com.aktcl.aron.feature.sale.domain.SaleDraftOps.setQuantity(d, 106, 30, com.aktcl.aron.rules.QtyUnit.STICK)
        val review = com.aktcl.aron.feature.sale.domain.SaleReviewCalculator.review(d, catalog)
        com.aktcl.aron.feature.sale.ui.ReviewScreen(review, {}, outletLine = "$outletPill\nরুট: Savar Bazar(Sat, Mon, Wed)", onCreditToggle = {}, onQc = {}, printLaterCard = true, totalsQuantity = true)
    }

    @Test fun amo_memo_menu_bn() = shot("amo_memo_menu_bn", AppLanguage.BN, "মেমো") {
        com.aktcl.aron.feature.memo.ui.MemoMenuScreen(
            listOf(
                com.aktcl.aron.feature.memo.domain.MemoMenuRow("m1", "amo-261110-001", 1, "2026-11-10T04:00:00.000Z", 84_000, 50_000),
                com.aktcl.aron.feature.memo.domain.MemoMenuRow("m2", "amo-261109-004", 1, "2026-11-09T05:30:00.000Z", 120_500, 0),
            ),
            { "Babu Store" }, {},
        )
    }

    private val submitLabels = mapOf(
        "visit" to com.aktcl.aron.feature.amo.R.string.amo_rec_visit, "memo" to com.aktcl.aron.feature.amo.R.string.amo_rec_memo, "stock_movement" to com.aktcl.aron.feature.amo.R.string.amo_rec_stock_movement, "qc_line" to com.aktcl.aron.feature.amo.R.string.amo_rec_qc_line,
        "memo_discount" to com.aktcl.aron.feature.amo.R.string.amo_rec_memo_discount, "distribution_check" to com.aktcl.aron.feature.amo.R.string.amo_rec_distribution_check, "call_assessment" to com.aktcl.aron.feature.amo.R.string.amo_rec_call_assessment,
        "survey_response" to com.aktcl.aron.feature.amo.R.string.amo_rec_survey_response,
    )
    @Test fun amo_sales_submit_bn() = shot("amo_sales_submit_bn", AppLanguage.BN, "বিক্রয় জমা") {
        // the AMO host's table: the nine manual rows (price row hidden, cfg off), the AMO record labels, the day's counts equal on phone and server
        val groups = com.aktcl.aron.feature.amo.AmoReconciliation.rowGroups(null, priceCompliance = false)
        val counts = groups.flatten().distinct().mapIndexed { i, t -> com.aktcl.aron.feature.dayclose.CountRow(t, if (i == 0) 1 else if (i == 1) 25 else 0, if (i == 0) 1 else if (i == 1) 25 else 0) }
        val labels = mapOf(
            "visit" to com.aktcl.aron.feature.amo.R.string.amo_rec_visit, "memo" to com.aktcl.aron.feature.amo.R.string.amo_rec_memo, "stock_movement" to com.aktcl.aron.feature.amo.R.string.amo_rec_stock_movement, "qc_line" to com.aktcl.aron.feature.amo.R.string.amo_rec_qc_line,
            "memo_discount" to com.aktcl.aron.feature.amo.R.string.amo_rec_memo_discount, "distribution_check" to com.aktcl.aron.feature.amo.R.string.amo_rec_distribution_check, "call_assessment" to com.aktcl.aron.feature.amo.R.string.amo_rec_call_assessment,
            "survey_response" to com.aktcl.aron.feature.amo.R.string.amo_rec_survey_response,
        )
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val gate = com.aktcl.aron.feature.dayclose.SalesSubmitRules.gate(com.aktcl.aron.feature.dayclose.OutboxStates(0, 0, 40, 0, 0), counts, com.aktcl.aron.feature.dayclose.DuesAtSubmit(0, 0), false)
        com.aktcl.aron.feature.dayclose.SalesSubmitScreen(true, counts, gate, com.aktcl.aron.feature.dayclose.SubmitProgress.Idle, { t -> labels[t]?.let { ctx.getString(it) } ?: t }, {}, {}, rowGroups = groups, vendorDialogs = true, blueSubmit = true)
    }

    // the same screen before the sync (screenshot 65 left): records still waiting on the phone, so the submit button is grey; D-PAR-AMO-18
    @Test @Config(qualifiers = "w360dp-h1300dp") fun amo_sales_submit_before_sync_bn() = shot("amo_sales_submit_before_sync_bn", AppLanguage.BN, "বিক্রয় জমা") {
        val groups = com.aktcl.aron.feature.amo.AmoReconciliation.rowGroups(null, priceCompliance = false)
        val counts = groups.flatten().distinct().mapIndexed { i, t -> com.aktcl.aron.feature.dayclose.CountRow(t, if (i == 0) 1 else if (i == 1) 25 else 0, if (i == 0) 1 else if (i == 1) 3 else 0) }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val gate = com.aktcl.aron.feature.dayclose.SalesSubmitRules.gate(com.aktcl.aron.feature.dayclose.OutboxStates(22, 0, 18, 0, 0), counts, com.aktcl.aron.feature.dayclose.DuesAtSubmit(0, 0), false)
        com.aktcl.aron.feature.dayclose.SalesSubmitScreen(true, counts, gate, com.aktcl.aron.feature.dayclose.SubmitProgress.Idle, { t -> submitLabels[t]?.let { ctx.getString(it) } ?: t }, {}, {}, rowGroups = groups, vendorDialogs = true, blueSubmit = true)
    }
}
