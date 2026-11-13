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
    @Test fun amo_sales_pick_empty_bn() = shot("amo_sales_pick_empty_bn", AppLanguage.BN, "বিক্রয়") {
        AmoCallPickContent(null, null, false, emptyList(), "", false, CallRange.Idle, false, false, {}, {}, {}, {}, {})
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
        com.aktcl.aron.feature.memo.ui.SummaryScreen(emptySummary, { "" }, {}, emptyText = "কিছু পাওয়া যায়নি", printWhenEmpty = true)
    }
    // row 66's two modals are separate windows (Dialog): a root capture cannot show them, so they are proven by SalesSubmitScreenTest (vendorDialogs...)

    // ---- rows 42, 43 (memo detail: Route / Retailer lines, dashed due box with Mark paid) ----
    private val dueMemo = com.aktcl.aron.feature.memo.domain.StoredMemo("m1", "amo-261110-001", 1, "2026-11-10", "2026-11-10T04:00:00.000Z", null,
        listOf(com.aktcl.aron.feature.memo.domain.MemoItem(105, 3, 28_000, 84_000)), emptyList(), emptyList(), 84_000, 0, 0, 0, 84_000, 34_000, 50_000)
    @Test fun amo_memo_detail_due_bn() = shot("amo_memo_detail_due_bn", AppLanguage.BN, "মেমো") {
        com.aktcl.aron.feature.memo.ui.MemoDetailView(
            com.aktcl.aron.feature.memo.domain.MemoMenu.detail(dueMemo), true, 50_000, { "Aster" }, {}, {}, {},
            outletName = "Siyam (1618410-1832714554-Madrasa Road)", routeName = "Savar BazarDaily", dashedDueBox = true,
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
        )
    }

    @Test fun amo_review_bn() = shot("amo_review_bn", AppLanguage.BN, "নিরীক্ষণ") {
        com.aktcl.aron.feature.sale.ui.ReviewScreen(
            enteredReview, {}, outletLine = "$outletPill\nরুট: Savar Bazar(Sat, Mon, Wed)\nক্লাস্টার: Savar Metro", onCreditToggle = {}, onQc = {},
        )
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
        com.aktcl.aron.feature.dayclose.SalesSubmitScreen(true, counts, gate, com.aktcl.aron.feature.dayclose.SubmitProgress.Idle, { t -> labels[t]?.let { ctx.getString(it) } ?: t }, {}, {}, rowGroups = groups, vendorDialogs = true)
    }
}
