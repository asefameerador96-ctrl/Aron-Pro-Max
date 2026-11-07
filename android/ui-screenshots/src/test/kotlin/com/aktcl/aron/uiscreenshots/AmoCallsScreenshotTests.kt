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

    private fun shot(name: String, language: AppLanguage, title: String, sunlight: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides ctx, LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale)) {
                AronTheme(language, dark = false, sunlight = sunlight, tier = GlassTier.B, reducedMotion = true, amo = true) { AmoFrame(title, onBack = {}) { content() } }
            }
        }
        rule.waitForIdle()
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
        MenuTile(com.aktcl.aron.core.ui.R.drawable.aron_ic_sale, com.aktcl.aron.feature.amo.R.string.amo_cc_menu_sale, "t1") {},
        MenuTile(com.aktcl.aron.core.ui.R.drawable.aron_ic_assessment, com.aktcl.aron.feature.amo.R.string.amo_cc_menu_assessment, "t2") {},
        MenuTile(com.aktcl.aron.core.ui.R.drawable.aron_ic_survey, com.aktcl.aron.feature.amo.R.string.amo_cc_menu_survey, "t3") {},
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
    @Test fun amo_previous_sales_bn() = shot("amo_previous_sales_bn", AppLanguage.BN, "বিক্রয় ইতিহাস") { PreviousSalesContent("2026-11-05", "2026-11-06", history, {}, outletLabel = "Siyam (1618410-1832714554-Madrasa Road)") }
    @Test fun amo_previous_sales_empty_en() = shot("amo_previous_sales_empty_en", AppLanguage.EN, "Sale History") { PreviousSalesContent("2026-11-06", "2026-11-06", PreviousSales.parseServer("""{"lines":[]}""", "2026-11-06"), {}) }
}
