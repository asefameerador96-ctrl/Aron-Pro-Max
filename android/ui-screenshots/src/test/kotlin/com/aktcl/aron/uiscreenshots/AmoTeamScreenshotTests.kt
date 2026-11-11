package com.aktcl.aron.uiscreenshots

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
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
import com.aktcl.aron.core.ui.aron.LocalPackshotProvider
import com.aktcl.aron.feature.amo.*
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens of the AMO team screens (lane A3, rows 33 to 40 and 71 to 74): English, Bangla, Bangla at 200 % text and Bangla in Sunlight.
 * The map of Team Location is a static frame (Robolectric draws no Google map; the live map is a device check).
 * Record on Linux only: `./gradlew :android:ui-screenshots:testDebugUnitTest -Proborazzi.record=true --tests '*AmoTeamScreenshotTests*'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class AmoTeamScreenshotTests {
    @get:Rule val rule = createComposeRule()

    private val recordTo: String? = System.getProperty("aron.screenshots.recordTo")
    private val goldens: String = System.getProperty("aron.screenshots.goldens") ?: "src/test/screenshots"
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private fun shot(name: String, language: AppLanguage, title: String, sunlight: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides ctx, LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale), LocalPackshotProvider provides TestPackshots) {
                AronTheme(language, dark = false, sunlight = sunlight, tier = GlassTier.B, reducedMotion = true, amo = true) {
                    AmoFrame(title, onBack = {}) { content() }
                }
            }
        }
        rule.waitForIdle()
        if (recordTo == null && System.getProperty("roborazzi.test.record") != "true") assertTrue("missing golden $goldens/$name.png", java.io.File("$goldens/$name.png").exists())
        rule.onRoot().captureRoboImage("${recordTo ?: goldens}/$name.png", roborazziOptions = options)
    }

    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_team_location_en() = shot("amo_team_location_en", AppLanguage.EN, "Team Location") { locContent() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_location_bn() = shot("amo_team_location_bn", AppLanguage.BN, "টিম লোকেশন") { locContent() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_location_bn_fs2() = shot("amo_team_location_bn_fs2", AppLanguage.BN, "টিম লোকেশন", fontScale = 2f) { locContent() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_location_sunlight_bn() = shot("amo_team_location_sunlight_bn", AppLanguage.BN, "টিম লোকেশন", sunlight = true) { locContent() }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_team_perf_monthly_en() = shot("amo_team_perf_monthly_en", AppLanguage.EN, "Team Performance") { perfList(PerfTab.MONTHLY) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_perf_monthly_bn() = shot("amo_team_perf_monthly_bn", AppLanguage.BN, "টিম পারফর্মেন্স") { perfList(PerfTab.MONTHLY) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_perf_monthly_bn_fs2() = shot("amo_team_perf_monthly_bn_fs2", AppLanguage.BN, "টিম পারফর্মেন্স", fontScale = 2f) { perfList(PerfTab.MONTHLY) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_perf_monthly_sunlight_bn() = shot("amo_team_perf_monthly_sunlight_bn", AppLanguage.BN, "টিম পারফর্মেন্স", sunlight = true) { perfList(PerfTab.MONTHLY) }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_team_perf_till_en() = shot("amo_team_perf_till_en", AppLanguage.EN, "Team Performance") { perfList(PerfTab.TILL_DATE) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_perf_till_bn() = shot("amo_team_perf_till_bn", AppLanguage.BN, "টিম পারফর্মেন্স") { perfList(PerfTab.TILL_DATE) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_perf_till_bn_fs2() = shot("amo_team_perf_till_bn_fs2", AppLanguage.BN, "টিম পারফর্মেন্স", fontScale = 2f) { perfList(PerfTab.TILL_DATE) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_perf_till_sunlight_bn() = shot("amo_team_perf_till_sunlight_bn", AppLanguage.BN, "টিম পারফর্মেন্স", sunlight = true) { perfList(PerfTab.TILL_DATE) }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_team_perf_details_en() = shot("amo_team_perf_details_en", AppLanguage.EN, "Agrabad") { perfDetails(PerfTab.MONTHLY, null) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_perf_details_bn() = shot("amo_team_perf_details_bn", AppLanguage.BN, "Agrabad") { perfDetails(PerfTab.MONTHLY, null) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_perf_details_bn_fs2() = shot("amo_team_perf_details_bn_fs2", AppLanguage.BN, "Agrabad", fontScale = 2f) { perfDetails(PerfTab.MONTHLY, null) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_perf_details_sunlight_bn() = shot("amo_team_perf_details_sunlight_bn", AppLanguage.BN, "Agrabad", sunlight = true) { perfDetails(PerfTab.MONTHLY, null) }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_team_perf_details_till_en() = shot("amo_team_perf_details_till_en", AppLanguage.EN, "Agrabad") { perfDetails(PerfTab.TILL_DATE, null) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_perf_details_till_bn() = shot("amo_team_perf_details_till_bn", AppLanguage.BN, "Agrabad") { perfDetails(PerfTab.TILL_DATE, null) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_perf_details_till_bn_fs2() = shot("amo_team_perf_details_till_bn_fs2", AppLanguage.BN, "Agrabad", fontScale = 2f) { perfDetails(PerfTab.TILL_DATE, null) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_perf_details_till_sunlight_bn() = shot("amo_team_perf_details_till_sunlight_bn", AppLanguage.BN, "Agrabad", sunlight = true) { perfDetails(PerfTab.TILL_DATE, null) }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_team_perf_category_en() = shot("amo_team_perf_category_en", AppLanguage.EN, "Agrabad") { perfDetails(PerfTab.TILL_DATE, "bidi") }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_perf_category_bn() = shot("amo_team_perf_category_bn", AppLanguage.BN, "Agrabad") { perfDetails(PerfTab.TILL_DATE, "bidi") }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_perf_category_bn_fs2() = shot("amo_team_perf_category_bn_fs2", AppLanguage.BN, "Agrabad", fontScale = 2f) { perfDetails(PerfTab.TILL_DATE, "bidi") }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_perf_category_sunlight_bn() = shot("amo_team_perf_category_sunlight_bn", AppLanguage.BN, "Agrabad", sunlight = true) { perfDetails(PerfTab.TILL_DATE, "bidi") }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_team_tasks_en() = shot("amo_team_tasks_en", AppLanguage.EN, "Task Delegation") { tasksList(false) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_tasks_bn() = shot("amo_team_tasks_bn", AppLanguage.BN, "টাস্ক ডেলিগেশন") { tasksList(false) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_tasks_bn_fs2() = shot("amo_team_tasks_bn_fs2", AppLanguage.BN, "টাস্ক ডেলিগেশন", fontScale = 2f) { tasksList(false) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_tasks_sunlight_bn() = shot("amo_team_tasks_sunlight_bn", AppLanguage.BN, "টাস্ক ডেলিগেশন", sunlight = true) { tasksList(false) }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_team_tasks_saved_en() = shot("amo_team_tasks_saved_en", AppLanguage.EN, "Task Delegation") { tasksList(true) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_tasks_saved_bn() = shot("amo_team_tasks_saved_bn", AppLanguage.BN, "টাস্ক ডেলিগেশন") { tasksList(true) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_tasks_saved_bn_fs2() = shot("amo_team_tasks_saved_bn_fs2", AppLanguage.BN, "টাস্ক ডেলিগেশন", fontScale = 2f) { tasksList(true) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_tasks_saved_sunlight_bn() = shot("amo_team_tasks_saved_sunlight_bn", AppLanguage.BN, "টাস্ক ডেলিগেশন", sunlight = true) { tasksList(true) }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_team_task_form_en() = shot("amo_team_task_form_en", AppLanguage.EN, "Assign a task") { taskForm() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_task_form_bn() = shot("amo_team_task_form_bn", AppLanguage.BN, "অ্যাসাইন্ড টাস্ক") { taskForm() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_task_form_bn_fs2() = shot("amo_team_task_form_bn_fs2", AppLanguage.BN, "অ্যাসাইন্ড টাস্ক", fontScale = 2f) { taskForm() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_task_form_sunlight_bn() = shot("amo_team_task_form_sunlight_bn", AppLanguage.BN, "অ্যাসাইন্ড টাস্ক", sunlight = true) { taskForm() }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_team_live_route_en() = shot("amo_team_live_route_en", AppLanguage.EN, "Live Data") { live(true) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_live_route_bn() = shot("amo_team_live_route_bn", AppLanguage.BN, "লাইভ ডেটা") { live(true) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_live_route_bn_fs2() = shot("amo_team_live_route_bn_fs2", AppLanguage.BN, "লাইভ ডেটা", fontScale = 2f) { live(true) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_live_route_sunlight_bn() = shot("amo_team_live_route_sunlight_bn", AppLanguage.BN, "লাইভ ডেটা", sunlight = true) { live(true) }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_team_live_total_en() = shot("amo_team_live_total_en", AppLanguage.EN, "Live Data") { live(false) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_live_total_bn() = shot("amo_team_live_total_bn", AppLanguage.BN, "লাইভ ডেটা") { live(false) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_live_total_bn_fs2() = shot("amo_team_live_total_bn_fs2", AppLanguage.BN, "লাইভ ডেটা", fontScale = 2f) { live(false) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_live_total_sunlight_bn() = shot("amo_team_live_total_sunlight_bn", AppLanguage.BN, "লাইভ ডেটা", sunlight = true) { live(false) }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_team_sr_list_en() = shot("amo_team_sr_list_en", AppLanguage.EN, "SR List") { srList() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_sr_list_bn() = shot("amo_team_sr_list_bn", AppLanguage.BN, "এসআর লিস্ট") { srList() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_sr_list_bn_fs2() = shot("amo_team_sr_list_bn_fs2", AppLanguage.BN, "এসআর লিস্ট", fontScale = 2f) { srList() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_sr_list_sunlight_bn() = shot("amo_team_sr_list_sunlight_bn", AppLanguage.BN, "এসআর লিস্ট", sunlight = true) { srList() }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_team_lifted_en() = shot("amo_team_lifted_en", AppLanguage.EN, "Lifted Stock") { lifted() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_lifted_bn() = shot("amo_team_lifted_bn", AppLanguage.BN, "লিফটেড স্টক") { lifted() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_lifted_bn_fs2() = shot("amo_team_lifted_bn_fs2", AppLanguage.BN, "লিফটেড স্টক", fontScale = 2f) { lifted() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_lifted_sunlight_bn() = shot("amo_team_lifted_sunlight_bn", AppLanguage.BN, "লিফটেড স্টক", sunlight = true) { lifted() }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_team_reports_menu_en() = shot("amo_team_reports_menu_en", AppLanguage.EN, "Reports") { reportsMenu() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_reports_menu_bn() = shot("amo_team_reports_menu_bn", AppLanguage.BN, "রিপোর্টসমূহ") { reportsMenu() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_reports_menu_bn_fs2() = shot("amo_team_reports_menu_bn_fs2", AppLanguage.BN, "রিপোর্টসমূহ", fontScale = 2f) { reportsMenu() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_reports_menu_sunlight_bn() = shot("amo_team_reports_menu_sunlight_bn", AppLanguage.BN, "রিপোর্টসমূহ", sunlight = true) { reportsMenu() }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_team_std_en() = shot("amo_team_std_en", AppLanguage.EN, "STD Memo Report") { stdReport() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_std_bn() = shot("amo_team_std_bn", AppLanguage.BN, "এসটিডি মেমো রিপোর্ট") { stdReport() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_std_bn_fs2() = shot("amo_team_std_bn_fs2", AppLanguage.BN, "এসটিডি মেমো রিপোর্ট", fontScale = 2f) { stdReport() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_std_sunlight_bn() = shot("amo_team_std_sunlight_bn", AppLanguage.BN, "এসটিডি মেমো রিপোর্ট", sunlight = true) { stdReport() }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_team_till_now_en() = shot("amo_team_till_now_en", AppLanguage.EN, "Sales till now summary") { tillNow() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_till_now_bn() = shot("amo_team_till_now_bn", AppLanguage.BN, "এখন পর্যন্ত বিক্রয় সারসংক্ষেপ") { tillNow() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_till_now_bn_fs2() = shot("amo_team_till_now_bn_fs2", AppLanguage.BN, "এখন পর্যন্ত বিক্রয় সারসংক্ষেপ", fontScale = 2f) { tillNow() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_till_now_sunlight_bn() = shot("amo_team_till_now_sunlight_bn", AppLanguage.BN, "এখন পর্যন্ত বিক্রয় সারসংক্ষেপ", sunlight = true) { tillNow() }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_team_till_route_en() = shot("amo_team_till_route_en", AppLanguage.EN, "Motiharpol") { tillRoute() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_till_route_bn() = shot("amo_team_till_route_bn", AppLanguage.BN, "Motiharpol") { tillRoute() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_till_route_bn_fs2() = shot("amo_team_till_route_bn_fs2", AppLanguage.BN, "Motiharpol", fontScale = 2f) { tillRoute() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_till_route_sunlight_bn() = shot("amo_team_till_route_sunlight_bn", AppLanguage.BN, "Motiharpol", sunlight = true) { tillRoute() }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_team_journey_cards_en() = shot("amo_team_journey_cards_en", AppLanguage.EN, "Sales Journey") { journeyCards() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_journey_cards_bn() = shot("amo_team_journey_cards_bn", AppLanguage.BN, "বিক্রয় যাত্রা") { journeyCards() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_journey_cards_bn_fs2() = shot("amo_team_journey_cards_bn_fs2", AppLanguage.BN, "বিক্রয় যাত্রা", fontScale = 2f) { journeyCards() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_journey_cards_sunlight_bn() = shot("amo_team_journey_cards_sunlight_bn", AppLanguage.BN, "বিক্রয় যাত্রা", sunlight = true) { journeyCards() }
    @Test @Config(qualifiers = "w360dp-h800dp") fun amo_team_journey_timeline_en() = shot("amo_team_journey_timeline_en", AppLanguage.EN, "Sales Journey") { journeyTimeline() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_journey_timeline_bn() = shot("amo_team_journey_timeline_bn", AppLanguage.BN, "বিক্রয় যাত্রা") { journeyTimeline() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_journey_timeline_bn_fs2() = shot("amo_team_journey_timeline_bn_fs2", AppLanguage.BN, "বিক্রয় যাত্রা", fontScale = 2f) { journeyTimeline() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_team_journey_timeline_sunlight_bn() = shot("amo_team_journey_timeline_sunlight_bn", AppLanguage.BN, "বিক্রয় যাত্রা", sunlight = true) { journeyTimeline() }

    // ---- fixtures (the numbers of the owner's screenshots where they are legible)
    private fun row(name: String, cat: String, t: Long, a: Long, td: Long?) = TeamTargetRow(name, cat, t, a, td)

    private val dariapur = TeamTargetNode("zone", 5, "Dariapur", 30, listOf(row("Maxim", "cigarette", 2_270_000, 1_598_680, 1_828_551), row("Aster", "lighter", 6_600, 2_570, 5_329), row("Salmon", "match", 2_640, 955, 2_117)))
    private val mathBazer = TeamTargetNode("route", 11, "Math Bazer", 30, listOf(row("Maxim", "cigarette", 246_000, 143_380, 204_998), row("Aster", "lighter", 700, 210, 583), row("Salmon", "match", 220, 11, 183)))
    private val dariapurRoute = TeamTargetNode("route", 12, "Dariapur", 30, listOf(row("Maxim", "cigarette", 347_600, 286_960, 288_324), row("Aster", "lighter", 850, 380, 685)))
    private val agrabad = TeamTargetNode(
        "zone", 6, "Agrabad", 30,
        listOf(
            row("Avon", "cigarette", 160_000, 323_980, 133_185), row("ARIS Apple", "cigarette", 16_800, 9_520, 13_981), row("Marise Special Blend", "cigarette", 2_280_000, 1_230_930, 1_898_489),
            row("Existing Abul Bidi/ 42 No. Abul Bidi", "bidi", 6_800, 7_250, 5_330), row("Abul Bidi Gold", "bidi", 57_000, 0, 48_014), row("Special Abul Bidi 25", "bidi", 39_000, 6_500, 31_917),
            row("Aster", "lighter", 11_200, 6_312, 9_325), row("Salmon", "match", 5_447, 5_276, 4_529),
        ),
    )

    @Composable private fun perfList(tab: PerfTab) = TeamPerformanceListContent(PerfCards(dariapur, listOf(mathBazer, dariapurRoute)), tab, {}, {}, false, false)
    @Composable private fun perfDetails(tab: PerfTab, cat: String?) = TeamPerformanceDetailsContent(agrabad, tab, {}, cat, {}, false, false)

    @Composable private fun locContent() = TeamLocationContent(
        listOf(LocationChoice(1, "Mahmud Hasan", "01712345678"), LocationChoice(2, "Shafikul Islam", "01812345678"), LocationChoice(3, "Md. Al Amin (Mitali)", "01912345678")),
        2L, {}, false, false,
    ) { m -> Box(m.background(Color(0xFFE6E8E2)), contentAlignment = Alignment.Center) { Text("Shafikul Islam · 01812345678") } }

    private val task = AssignedTask("t1", "general", "General Task", 1, 7, "ongoing", "2025-11-03", null, null, "2026-11-02T01:00:00Z", description = "Test task delegated to my SR")
    private val taskList = listOf(task.copy(uuid = "t0", description = "Improve the sales quality"), task, task.copy(uuid = "t2", status = "completed", description = "Display the new pack"), task.copy(uuid = "t3", status = "cancelled", description = "Cancelled by the office"), task.copy(uuid = "t4", dueDate = "2026-11-01", description = "Check the price list"))
    @Composable private fun tasksList(saved: Boolean) = AssignedTasksContent(taskList, mapOf(7L to "SR-Kakoli - 1"), mapOf(1L to "Afifa Stor"), { it }, false, false, {}, today = "2026-11-06", saved = saved)
    @Composable private fun taskForm() = TaskFormPageContent(
        listOf("1" to "AgrabadDaily"), "AgrabadDaily", {}, listOf("1" to "Sifat St (Diamond)"), "Sifat St (Diamond)", {}, listOf("A", "B", "D", "E", "G", "H", "S"), null, {},
        TaskOutletInfo("Sifat St", "Nurul Kobir", "SR-Kakoli - 1", "01712345678"), listOf(TaskType("general", "General Task", "সাধারণ টাস্ক")), com.aktcl.aron.core.ui.LocalAppLanguage.current == AppLanguage.BN, "general", {},
        "2026-11-06", "2026-11-06", {}, "Improve the sales quality", {}, emptySet(), false, false, {},
    )

    private val liveJson = """{"as_of":"2026-11-06T10:00:00Z","business_date":"2026-11-06","kpis":{"target_routes":1,"logged_in_routes":1,"sales_submitted_routes":0,"target_outlets":641,"successful_calls":259,"active_memo_count":38,"net_mtk":161500},
        "geo_valid_visits":288,"photo_valid_visits":1,"visits_total":290,"sales_by_category":[{"category_code":"cigarette","base_unit":"stick","qty_base":44310},{"category_code":"bidi","base_unit":"stick","qty_base":0},{"category_code":"lighter","base_unit":"piece","qty_base":80},{"category_code":"match","base_unit":"dozen","qty_base":103}],
        "not_logged_in":[],"logged_in_not_submitted":[{"route_id":1}],"submitted":[]}"""
    @Composable private fun live(perRoute: Boolean) = LiveDataContent(LiveDataParser.parse(liveJson), "AMO-GulshanAMO", "Gulshan-1 Daily", listOf("all" to "All routes", "1" to "Gulshan-1 Daily"), perRoute, {}, {}, false, false)

    private fun member(user: Long?, name: String?, route: Long, rn: String, days: List<Int>, phone: String?, outlets: Int) = TeamMember(user, name, route, rn, days, phone, null, outlets, user == null)
    private val members = listOf(
        member(1, "Md.Rasik Mia", 1, "Laxmipur", listOf(1, 2, 3, 4, 5, 6), "01842437077", 61), member(2, "MD. Reza Mia", 2, "Tempu Stand", listOf(1, 2, 3, 4, 5, 6), "01645632686", 57),
        member(3, "Md.Ziul Islam", 3, "Kuptola", listOf(6, 1, 3), "01768995958", 51), member(3, "Md.Ziul Islam", 4, "Molla Bazer", listOf(7, 2, 4), "01768995958", 47),
        member(null, null, 5, "Baridhara", listOf(1, 2, 3, 4, 5, 6), null, 58),
    )
    @Composable private fun srList() = SrListContent(members, false, false, {})
    @Composable private fun lifted() = LiftedStockContent(
        members[0], listOf(IssueLine("200", "MaxR-10S", 400, "packets"), IssueLine("201", "MaxR-20S", 400, "packets"), IssueLine("202", "MaxB-10S", 2_000, "packets"), IssueLine("203", "MaxB-20S", 16_000, "packets"), IssueLine("204", "MaxWB-10S", 1_000, "packets"), IssueLine("205", "MaxWB-20S", 100, "packets")),
    )

    @Composable private fun reportsMenu() = ReportsTilesContent({}, {})
    private val std = TeamReports.stdMatrix(
        "Laxipur",
        listOf(RouteCategory("Laxmipur", "cigarette", 202_000, "stick", 0), RouteCategory("Laxmipur", "lighter", 730, "piece", 0), RouteCategory("Tempu Stand", "cigarette", 206_680, "stick", 0), RouteCategory("Tempu Stand", "lighter", 840, "piece", 0), RouteCategory("Miar Bazer", "cigarette", 167_360, "stick", 0), RouteCategory("Miar Bazer", "lighter", 562, "piece", 0), RouteCategory("Kuptola", "cigarette", 61_280, "stick", 0), RouteCategory("Kuptola", "match", 30, "dozen", 0)),
    )
    @Composable private fun stdReport() = StdMatrixContent("2026-11-01", "2026-11-06", "2026-11-06", { _, _ -> }, std, false, false)
    private val tillCards = listOf(
        TeamReports.tillCard("Zone: Dariapur", dariapur, true, mapOf("cigarette" to 7_553L), com.aktcl.aron.rules.UiLocale.EN),
        TeamReports.tillCard("Route: Math Bazer", mathBazer, false, mapOf("cigarette" to 571L), com.aktcl.aron.rules.UiLocale.EN),
    )
    @Composable private fun tillNow() = TillNowContent(tillCards, {}, false, false)
    @Composable private fun tillRoute() = TillRouteDetailContent(
        "79.67", "572",
        listOf(BrandLine("Marise", "88,235.29", "67,120", "76.07%", "279"), BrandLine("Black Diamond", "12,864.71", "53,410", "415.17%", "268"), BrandLine("Existing Abul Bidi/ 42 No. Abul Bidi", "352.94", "500", "141.67%", "1")),
    )

    @Composable private fun journeyCards() = SrListContent(members, false, false, {}, openVacant = false)
    private val journey = TeamJourneys.journey(
        """{"user":{"user_id":7,"full_name":"Rahim"},"route":{"route_id":3,"name":"Gulshan-1"},"business_date":"2026-11-06","distance_m":391.54,"calls":[
        {"visit_uuid":"a","outlet":{"id":1,"name":"Unite Tea Store","code":"2672261","phone":"01856213762","cluster":"Fojle Rabbi Park"},"status":"new_sale","opened_at":"2026-11-06T03:43:04Z","closed_at":"2026-11-06T03:48:47Z","duration_s":342,"distance_from_prev_m":null,"distance_so_far_m":0.0,"gap_to_next_s":536},
        {"visit_uuid":"b","outlet":{"id":2,"name":"Rana Tea Store","code":"2645667","phone":"01609690515","cluster":"Kachabazar Er Samne"},"status":"edited_sale","opened_at":"2026-11-06T03:57:43Z","closed_at":"2026-11-06T04:05:30Z","duration_s":466,"distance_from_prev_m":277.32,"distance_so_far_m":277.32,"gap_to_next_s":45},
        {"visit_uuid":"c","outlet":{"id":3,"name":"Kalam Store","code":"2585581","phone":"01922630443","cluster":"Kachabazar Road"},"status":"no_sale","opened_at":"2026-11-06T04:06:15Z","closed_at":"2026-11-06T04:18:31Z","duration_s":735,"distance_from_prev_m":114.22,"distance_so_far_m":391.54,"gap_to_next_s":null}]}""",
    )
    @Composable private fun journeyTimeline() = JourneyTimelineContent(journey, false, false)
}
