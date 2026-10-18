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
import com.aktcl.aron.core.ui.aron.AronScaffold
import com.aktcl.aron.core.ui.aron.AronTopBar
import androidx.compose.ui.res.painterResource
import com.aktcl.aron.feature.attendance.AttendanceContent
import com.aktcl.aron.feature.attendance.AttendanceState
import com.aktcl.aron.feature.auth.LoginContent
import com.aktcl.aron.feature.auth.LoginMessage
import com.aktcl.aron.feature.auth.LoginUiState
import com.aktcl.aron.feature.auth.OtpContent
import com.aktcl.aron.feature.auth.OtpState
import com.aktcl.aron.feature.dayclose.CountRow
import com.aktcl.aron.feature.dayclose.DuesAtSubmit
import com.aktcl.aron.feature.dayclose.OutboxStates
import com.aktcl.aron.feature.dayclose.SalesSubmitRules
import com.aktcl.aron.feature.dayclose.SalesSubmitScreen
import com.aktcl.aron.feature.dayclose.SubmitProgress
import com.aktcl.aron.feature.memo.domain.DaySummaryCalculator
import com.aktcl.aron.feature.memo.domain.JourneyBuilder
import com.aktcl.aron.feature.memo.domain.JourneyVisit
import com.aktcl.aron.feature.memo.domain.MemoItem
import com.aktcl.aron.feature.memo.domain.MemoMenu
import com.aktcl.aron.feature.memo.domain.PlannedOutlet
import com.aktcl.aron.feature.memo.domain.StoredMemo
import com.aktcl.aron.feature.memo.domain.SummaryDiscount
import com.aktcl.aron.feature.memo.domain.SummaryLine
import com.aktcl.aron.feature.memo.domain.SummaryMemo
import com.aktcl.aron.feature.memo.domain.SummarySku
import com.aktcl.aron.feature.memo.ui.JourneyScreen
import com.aktcl.aron.feature.memo.ui.MemoDetailView
import com.aktcl.aron.feature.memo.ui.MemoMenuScreen
import com.aktcl.aron.feature.memo.ui.SummaryScreen
import com.aktcl.aron.feature.tasks.TaskContent
import com.aktcl.aron.feature.tasks.TaskItem
import com.aktcl.aron.feature.tasks.TaskListState
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
 * Look-and-fix captures (not goldens, never verified) of the day-close side of the SR app: Attendance (not checked in,
 * checked in), Memo menu and detail, Summary, Sales Submit, Tasks (with items and empty), Journey, Login and the OTP
 * step; Bangla light and Bangla sunlight (plus English light for Login, and font scale 2.0 for the Memo and Submit
 * tables), tier B, 360 x 800 dp. The PNGs go to
 * `-Daron.kitshot.dir` (default `build/kitshots`). Run: `./gradlew :android:ui-screenshots:testDebugUnitTest --tests '*CloseShot*'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "bn-w360dp-h800dp")
class CloseShot {
    @get:Rule val rule = createComposeRule()

    private val dir: File = File(System.getProperty("aron.kitshot.dir") ?: "build/kitshots").also { it.mkdirs() }
    private val options = RoborazziOptions(taskType = RoborazziTaskType.Record)

    private fun shot(name: String, language: AppLanguage = AppLanguage.BN, sunlight: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides ctx, LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale)) {
                AronTheme(language, dark = false, sunlight = sunlight, tier = GlassTier.B, reducedMotion = false) { content() }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(File(dir, "$name.png").path, roborazziOptions = options)
    }

    // ---- fixtures (synthetic: no real outlet names or phone numbers) ----
    private val now = { "2026-10-05T04:42:00.000Z" }
    private val notCheckedIn = AttendanceState(coordinatesText = "23.792580, 90.407830", addressText = "23.792580, 90.407830", fixAccuracyM = 18)
    private val checkedIn = AttendanceState(
        checkedInAt = "2026-10-05T02:52:00.000Z", showCheckInPrompt = false, checkInEnabled = false,
        coordinatesText = "23.792580, 90.407830", addressText = "RC38+J3Q, Gulshan, Dhaka", fixAccuracyM = 64,
    )
    private val memo = StoredMemo(
        "m1", "sr334001-261005-001", 50001, "2026-10-05", "2026-10-05T04:42:00.000Z", null,
        listOf(MemoItem(100, 40, 8_000, 320_000), MemoItem(103, 3, 12_500, 37_500)), emptyList(), emptyList(),
        357_500, 0, 0, 0, 357_500, 300_000, 57_500,
    )
    private val memo2 = memo.copy(memoUuid = "m2", memoNo = "sr334001-261005-002", outletId = 50002, committedAtIso = "2026-10-05T05:10:00.000Z", dueMtk = 0, paidMtk = 357_500)
    private fun skuName(id: Long) = if (id == 100L) "MaxR 10S" else "Aster"
    private fun outletName(id: Long) = if (id == 50001L) "Banani Store" else "Karim Tea Stall"
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
    private val cleanCounts = listOf(CountRow("visit", 6, 6), CountRow("memo", 6, 6), CountRow("stock_movement", 2, 2), CountRow("qc_line", 0, 0))
    private val cleanGate = SalesSubmitRules.gate(OutboxStates(0, 0, 40, 0, 0), cleanCounts, DuesAtSubmit(0, 0), false)
    private val labels = mapOf("visit" to "আউটলেট", "memo" to "মেমো", "stock_movement" to "স্টক", "qc_line" to "কিউসি")
    private val tasks = TaskListState(
        listOf(
            TaskItem("t1", "visit_outlet", "Babu Store", "Babu Store দোকানে Maxim এ OOS আছে। আজকের মধ্যে স্টক দিন।", 5, "Babu Store", "2026-11-30", "ongoing", null),
            TaskItem("t2", "collect_due", "Due collection", null, 6, "Karim Tea Stall", "2026-10-08", "ongoing", null),
            TaskItem("t3", "visit_outlet", "Banani Store", "POSM check", 7, "Banani Store", "2026-10-02", "completed", "2026-10-02T05:00:00.000Z"),
        ),
        2, "2026-10-05T04:42:00.000Z",
    )
    private val journey = JourneyBuilder.build(
        listOf(PlannedOutlet(1, "Banani Store", 1), PlannedOutlet(2, "Karim Tea Stall", 2), PlannedOutlet(3, "Janani Pharmacy", 3), PlannedOutlet(4, "Babu Store", 4)),
        listOf(JourneyVisit(1, "sold"), JourneyVisit(2, "owner_absent")),
    )

    @Composable private fun AttendanceA() = AttendanceContent(notCheckedIn, "17:00", {}, {}, onRefreshFix = {}, nowIso = now)
    @Composable private fun AttendanceC() = AttendanceContent(checkedIn, "17:00", {}, {}, onRefreshFix = {}, nowIso = now)
    private fun skuUnit(id: Long) = if (id == 100L) "স্টিক" else "পিস"
    @Composable private fun Memo() = MemoDetailView(MemoMenu.detail(memo), true, 57_500, ::skuName, {}, {}, {}, outletName = "Banani Store", onHistory = {}, skuUnit = ::skuUnit)
    @Composable private fun Menu() = MemoMenuScreen(MemoMenu.rows(listOf(memo, memo2)), ::outletName, {})
    @Composable private fun Summary() = SummaryScreen(summary, ::skuName, {}, categoryLabel = { cats[it] ?: it }, unitLabel = { units[it] ?: "" })
    @Composable private fun Submit() = SalesSubmitScreen(true, counts, gate, SubmitProgress.Idle, { labels[it] ?: it }, {}, {}, lastContactText = "১০:৪২")
    @Composable private fun SubmitClean() = SalesSubmitScreen(true, cleanCounts, cleanGate, SubmitProgress.Idle, { labels[it] ?: it }, {}, {}, lastContactText = "১০:৪২")
    @Composable private fun Tasks() = TaskContent(tasks, {})
    @Composable private fun TasksEmpty() = TaskContent(TaskListState(showEmptyState = true, syncedAt = "2026-10-05T04:42:00.000Z"), {})
    @Composable private fun Journey() = JourneyScreen(journey)
    @Composable private fun Login() = LoginContent(LoginUiState(username = "sr334001", password = "secret", message = LoginMessage.InvalidCredentials), "ARON SR", "1.0.25 (12)", {}, {}, {}, {}, {}, sunlight = false, onSunlight = {})
    @Composable private fun Otp() = OtpContent(OtpState("12"), {}, {}, onBack = {}, appTitle = "ARON SR", onLanguageSelect = {}, onSunlight = {})

    /** The host frame of the parity apps (app-sr wiring): the gradient top bar with the tile icon and the title, on the grey page. */
    @Composable
    private fun Parity(title: String, icon: Int, content: @Composable () -> Unit) {
        AronScaffold(topBar = { AronTopBar(title, icon = painterResource(icon), onBack = {}, backLabel = "back") }) { content() }
    }

    @Test fun parity_summary_bn() = shot("parity_summary_bn") { Parity("বিক্রয় সারসংক্ষেপ", com.aktcl.aron.core.ui.R.drawable.aron_ic_summary) { Summary() } }

    @Test fun attendance_a_bn_light() = shot("close_attendance_a_bn_light") { AttendanceA() }
    @Test fun attendance_a_bn_sunlight() = shot("close_attendance_a_bn_sunlight", sunlight = true) { AttendanceA() }
    @Test fun attendance_c_bn_light() = shot("close_attendance_c_bn_light") { AttendanceC() }
    @Test fun attendance_c_bn_sunlight() = shot("close_attendance_c_bn_sunlight", sunlight = true) { AttendanceC() }
    @Test fun memo_bn_light() = shot("close_memo_bn_light") { Memo() }
    @Test fun memo_bn_sunlight() = shot("close_memo_bn_sunlight", sunlight = true) { Memo() }
    @Test fun memo_bn_light_fs2() = shot("close_memo_bn_light_fs2", fontScale = 2f) { Memo() } // the lines table stacks its numbers under the name
    @Test fun memo_menu_bn_light() = shot("close_memo_menu_bn_light") { Menu() }
    @Test fun summary_bn_light() = shot("close_summary_bn_light") { Summary() }
    @Test fun summary_bn_sunlight() = shot("close_summary_bn_sunlight", sunlight = true) { Summary() }
    @Test fun submit_bn_light() = shot("close_submit_bn_light") { Submit() }
    @Test fun submit_bn_sunlight() = shot("close_submit_bn_sunlight", sunlight = true) { Submit() }
    @Test fun submit_bn_light_fs2() = shot("close_submit_bn_light_fs2", fontScale = 2f) { SubmitClean() } // the counts table (near the top: no block banners) as caption-over-value cells
    @Test fun tasks_bn_light() = shot("close_tasks_bn_light") { Tasks() }
    @Test fun tasks_bn_sunlight() = shot("close_tasks_bn_sunlight", sunlight = true) { Tasks() }
    @Test fun tasks_empty_bn_light() = shot("close_tasks_empty_bn_light") { TasksEmpty() }
    @Test fun journey_bn_light() = shot("close_journey_bn_light") { Journey() }
    @Test fun login_bn_light() = shot("close_login_bn_light") { Login() }
    @Test fun login_bn_sunlight() = shot("close_login_bn_sunlight", sunlight = true) { Login() }
    @Test @Config(qualifiers = "w360dp-h800dp") fun login_en_light() = shot("close_login_en_light", AppLanguage.EN) { Login() }
    @Test fun otp_bn_light() = shot("close_otp_bn_light") { Otp() }
}
