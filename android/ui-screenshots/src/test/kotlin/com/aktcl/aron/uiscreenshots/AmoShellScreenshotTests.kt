package com.aktcl.aron.uiscreenshots

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.GlassTier
import com.aktcl.aron.feature.amo.AmoHomeContent
import com.aktcl.aron.feature.amo.AmoHomeHeader
import com.aktcl.aron.feature.amo.AmoMenu
import com.aktcl.aron.feature.amo.AmoReach
import com.aktcl.aron.feature.amo.CallTarget
import com.aktcl.aron.feature.amo.CallTargets
import com.aktcl.aron.feature.home.BundleStatus
import com.aktcl.aron.feature.home.DeviceHealthModel
import com.aktcl.aron.feature.home.HomeUser
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
 * Goldens of the AMO shell in the AMO look (lane android-amo-shell, D-AMO-MAN-03): Home in Bangla, English, 200 % font and Sunlight, with
 * the target cards set and unset ("—"). Record on Linux only: `./gradlew :android:ui-screenshots:testDebugUnitTest -Proborazzi.record=true --tests '*AmoShellScreenshotTests*'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class AmoShellScreenshotTests {
    @get:Rule val rule = createComposeRule()

    private val recordTo: String? = System.getProperty("aron.screenshots.recordTo")
    private val goldens: String = System.getProperty("aron.screenshots.goldens") ?: "src/test/screenshots"
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private fun shot(name: String, language: AppLanguage, sunlight: Boolean = false, fontScale: Float = 1f, amoLook: Boolean = true, after: () -> Unit = {}, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides ctx, LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale)) {
                AronTheme(language, dark = false, sunlight = sunlight, tier = GlassTier.B, reducedMotion = true, amo = amoLook) { content() }
            }
        }
        rule.waitForIdle()
        after(); rule.waitForIdle()
        if (recordTo == null && System.getProperty("roborazzi.test.record") != "true") assertTrue("missing golden $goldens/$name.png", java.io.File("$goldens/$name.png").exists())
        rule.onRoot().captureRoboImage("${recordTo ?: goldens}/$name.png", roborazziOptions = options)
    }

    private val amo = HomeUser("AMO-Dariapur", "amo5864", "AMO", offline = false, reauthRequired = false, updateRequired = false)
    private val reach = AmoReach(subordinateSrs = 4, pendingVerifications = 8, zoneName = "Dariapur")

    @Composable private fun Home(targets: CallTargets?, health: Boolean = false, status: com.aktcl.aron.feature.amo.CallTargetsStatus = com.aktcl.aron.feature.amo.CallTargetsStatus.Fresh) = AmoHomeContent(
        AmoHomeHeader.of(amo, "2026-10-26", reach.zoneName, androidx.compose.ui.res.stringResource(com.aktcl.aron.feature.amo.R.string.amo_shell_header_zone), androidx.compose.ui.res.stringResource(com.aktcl.aron.feature.amo.R.string.amo_shell_header_plain)), amo, BundleStatus.Ready("b-17", "2026-10-26"),
        AmoMenu.tiles(reach, AmoMenu.badges(reach)), onTile = {}, onSettings = {}, targets = targets, targetsStatus = status,
        health = if (health) DeviceHealthModel.of(62, 1200, 3, 15) else null,
    )

    private val set = CallTargets(CallTarget(3, 5), CallTarget(42, 130), CallTarget(12, 80), CallTarget(4, 20))

    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_home_bn_light() = shot("amo_home_bn_light", AppLanguage.BN) { Home(null) }
    @Test fun amo_home_en_light() = shot("amo_home_en_light", AppLanguage.EN) { Home(null) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_home_bn_fs2() = shot("amo_home_bn_fs2", AppLanguage.BN, fontScale = 2f) { Home(null, health = true) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_home_bn_sunlight() = shot("amo_home_bn_sunlight", AppLanguage.BN, sunlight = true) { Home(null) }
    // the whole page, so the four target cards under the grid are in the frame: values set, then unset ("—")
    @Test @Config(qualifiers = "bn-w360dp-h1500dp") fun amo_home_targets_bn() = shot("amo_home_targets_bn", AppLanguage.BN) { Home(set) }
    @Test @Config(qualifiers = "w360dp-h1500dp") fun amo_home_targets_unset_en() = shot("amo_home_targets_unset_en", AppLanguage.EN) { Home(null) }
    @Test @Config(qualifiers = "bn-w360dp-h1500dp") fun amo_home_targets_stale_bn() = shot("amo_home_targets_stale_bn", AppLanguage.BN) { Home(set, status = com.aktcl.aron.feature.amo.CallTargetsStatus.Stale) }
    @Test @Config(qualifiers = "bn-w360dp-h2400dp") fun amo_home_targets_failed_bn_fs2() = shot("amo_home_targets_failed_bn_fs2", AppLanguage.BN, fontScale = 2f) { Home(null, status = com.aktcl.aron.feature.amo.CallTargetsStatus.Failed) }
    @Test @Config(qualifiers = "bn-w360dp-h1500dp") fun amo_home_targets_sunlight_bn() = shot("amo_home_targets_sunlight_bn", AppLanguage.BN, sunlight = true) { Home(set) }

    // ---- push 2: Login (rows 3, R1), Attendance (14 to 16, R7), Stock (41), Settings (75, R16) ----
    @Composable private fun Login() = com.aktcl.aron.feature.auth.AmoLoginContent(
        com.aktcl.aron.feature.auth.LoginUiState(username = "amo5864", password = "secret"), "ARON AMO", "1.0.25 (12)", {}, {}, {}, {}, {}, footerText = "ARON AMO v1.0.25",
    )
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_login_bn() = shot("amo_login_bn", AppLanguage.BN, amoLook = false) { Login() }
    @Test fun amo_login_en() = shot("amo_login_en", AppLanguage.EN, amoLook = false) { Login() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_login_bn_fs2() = shot("amo_login_bn_fs2", AppLanguage.BN, fontScale = 2f, amoLook = false) { Login() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_login_bn_sunlight() = shot("amo_login_bn_sunlight", AppLanguage.BN, sunlight = true, amoLook = false) { Login() }

    private val now = { "2026-11-06T10:05:00.000Z" } // 04:05 PM in Dhaka
    private val fresh = com.aktcl.aron.feature.attendance.AttendanceState(coordinatesText = "23.792580, 90.407830", addressText = "Gulshan 1, Dhaka, Dhaka Division, 1212, Bangladesh", fixAccuracyM = 18)
    private val checkedIn = fresh.copy(checkedInAt = "2026-11-06T04:05:00.000Z", showCheckInPrompt = false, checkInEnabled = false)
    private val open = checkedIn.copy(checkOutEnabled = true, checkOutTimeReached = true)
    private val done = open.copy(checkedOutAt = "2026-11-06T11:30:00.000Z", checkOutEnabled = false)

    @Composable private fun Attendance(st: com.aktcl.aron.feature.attendance.AttendanceState) = ParityFrame(
        androidx.compose.ui.res.stringResource(com.aktcl.aron.feature.home.R.string.hm_tile_attendance), com.aktcl.aron.core.ui.R.drawable.aron_art_attendance,
    ) {
        com.aktcl.aron.feature.attendance.AttendanceContent(st, "17:00", {}, {}, onRefreshFix = {}, nowIso = now, userTitle = "AMO-Dariapur (amo5864)", userSubtitle = "Dariapur AMO, 2026-11-06", amoStyle = true)
    }
    private fun tap(tag: String): () -> Unit = { rule.onNodeWithTag(tag).performClick(); Unit }

    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_attendance_before_bn() = shot("amo_attendance_before_bn", AppLanguage.BN) { Attendance(fresh) }
    @Test fun amo_attendance_before_en() = shot("amo_attendance_before_en", AppLanguage.EN) { Attendance(fresh) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_attendance_before_bn_fs2() = shot("amo_attendance_before_bn_fs2", AppLanguage.BN, fontScale = 2f) { Attendance(fresh) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_attendance_before_bn_sunlight() = shot("amo_attendance_before_bn_sunlight", AppLanguage.BN, sunlight = true) { Attendance(fresh) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_attendance_checkin_sheet_bn() = shot("amo_attendance_checkin_sheet_bn", AppLanguage.BN, after = tap("att_check_in")) { Attendance(fresh) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_attendance_waiting_bn() = shot("amo_attendance_waiting_bn", AppLanguage.BN) { Attendance(checkedIn) }
    @Test fun amo_attendance_waiting_en() = shot("amo_attendance_waiting_en", AppLanguage.EN) { Attendance(checkedIn) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_attendance_waiting_bn_sunlight() = shot("amo_attendance_waiting_bn_sunlight", AppLanguage.BN, sunlight = true) { Attendance(checkedIn) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_attendance_waiting_bn_fs2() = shot("amo_attendance_waiting_bn_fs2", AppLanguage.BN, fontScale = 2f) { Attendance(checkedIn) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_attendance_open_bn() = shot("amo_attendance_open_bn", AppLanguage.BN) { Attendance(open) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_attendance_checkout_sheet_bn() = shot("amo_attendance_checkout_sheet_bn", AppLanguage.BN, after = tap("att_check_out")) { Attendance(open) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_attendance_done_bn() = shot("amo_attendance_done_bn", AppLanguage.BN) { Attendance(done) }
    @Test fun amo_attendance_done_en() = shot("amo_attendance_done_en", AppLanguage.EN) { Attendance(done) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_attendance_done_bn_sunlight() = shot("amo_attendance_done_bn_sunlight", AppLanguage.BN, sunlight = true) { Attendance(done) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_attendance_done_bn_fs2() = shot("amo_attendance_done_bn_fs2", AppLanguage.BN, fontScale = 2f) { Attendance(done) }

    private fun sku(id: Long, cat: String, name: String, unit: String, perPack: Int) =
        com.aktcl.aron.core.database.entity.SkuEntity(id, name.replace(' ', '-'), id, cat, name, name, null, unit, perPack, if (perPack > 1) "pack" else "base", null, "1.000", id.toInt(), "active", 1)
    private val stockRows = listOf(
        com.aktcl.aron.feature.stock.StockRow(sku(1, "cigarette", "MaxB-20S", "stick", 20), 0, 0, stockBase = 0),
        com.aktcl.aron.feature.stock.StockRow(sku(2, "cigarette", "MaxDB-20S 20HL", "stick", 20), 400, 20, stockBase = 380),
        com.aktcl.aron.feature.stock.StockRow(sku(3, "lighter", "Aster", "piece", 1), 400, 0, stockBase = 25),
        com.aktcl.aron.feature.stock.StockRow(sku(4, "match", "Flame Box", "dozen", 1), 400, 0, stockBase = 394),
    )
    private val stockTotals = listOf(
        com.aktcl.aron.feature.stock.CategoryTotal("cigarette", 600), com.aktcl.aron.feature.stock.CategoryTotal("lighter", 400), com.aktcl.aron.feature.stock.CategoryTotal("match", 400),
    )
    @Composable private fun Stock(connected: Boolean, saved: Boolean) = ParityFrame(
        androidx.compose.ui.res.stringResource(com.aktcl.aron.feature.home.R.string.hm_tile_stock), com.aktcl.aron.core.ui.R.drawable.aron_art_stock, printerConnected = connected,
    ) {
        com.aktcl.aron.feature.stock.StockContent(
            stockRows, stockTotals, if (saved) com.aktcl.aron.feature.stock.StockMessage.SAVED else null, slipNotPrinted = saved,
            onIssue = { _, _ -> }, onSave = {}, onPrint = if (saved && connected) ({}) else null,
        )
    }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_stock_notconnected_bn() = shot("amo_stock_notconnected_bn", AppLanguage.BN) { Stock(false, false) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_stock_connected_saved_bn() = shot("amo_stock_connected_saved_bn", AppLanguage.BN) { Stock(true, true) }
    @Test fun amo_stock_notconnected_en() = shot("amo_stock_notconnected_en", AppLanguage.EN) { Stock(false, false) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_stock_notconnected_bn_fs2() = shot("amo_stock_notconnected_bn_fs2", AppLanguage.BN, fontScale = 2f) { Stock(false, false) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_stock_connected_saved_bn_sunlight() = shot("amo_stock_connected_saved_bn_sunlight", AppLanguage.BN, sunlight = true) { Stock(true, true) }

    @Composable private fun Settings() = com.aktcl.aron.feature.amo.AmoSettingsContent(
        "1.0.25 (12)", androidx.compose.ui.res.stringResource(com.aktcl.aron.feature.home.R.string.set_logout_message), {}, {}, {}, sunlight = false, onSunlight = {}, onUpdate = {}, onSupport = {}, onPrinter = {}, onExceptions = {},
    )
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_settings_bn() = shot("amo_settings_bn", AppLanguage.BN) { Settings() }
    @Test fun amo_settings_en() = shot("amo_settings_en", AppLanguage.EN) { Settings() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_settings_bn_fs2() = shot("amo_settings_bn_fs2", AppLanguage.BN, fontScale = 2f) { Settings() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_settings_bn_sunlight() = shot("amo_settings_bn_sunlight", AppLanguage.BN, sunlight = true) { Settings() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_settings_language_menu_bn() = shot("amo_settings_language_menu_bn", AppLanguage.BN, after = tap("amo_set_language")) { Settings() }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_settings_logout_confirm_bn() = shot("amo_settings_logout_confirm_bn", AppLanguage.BN, after = tap("home_logout")) { Settings() }

    // ---- update flows (5 to 7, R6) ----
    private val release = com.aktcl.aron.core.system.update.ReleaseInfo("1.0.4", 12, "arm64-v8a", "a".repeat(64), 25_000_000, "https://x/y.apk", "c".repeat(64), null, null)
    @Composable private fun UpdateScreen(d: com.aktcl.aron.core.system.update.DownloadUi, required: Boolean = false) =
        com.aktcl.aron.core.system.update.UpdateContent(release, required, com.aktcl.aron.core.system.update.NetworkStatus.WIFI, d, {}, {}, {}, {}, amoStyle = true)
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_update_available_bn() = shot("amo_update_available_bn", AppLanguage.BN) { UpdateScreen(com.aktcl.aron.core.system.update.DownloadUi.Idle) }
    @Test fun amo_update_available_en() = shot("amo_update_available_en", AppLanguage.EN) { UpdateScreen(com.aktcl.aron.core.system.update.DownloadUi.Idle) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_update_progress_bn() = shot("amo_update_progress_bn", AppLanguage.BN) { UpdateScreen(com.aktcl.aron.core.system.update.DownloadUi.Downloading(42), required = true) }
    @Test @Config(qualifiers = "bn-w360dp-h800dp") fun amo_update_available_bn_sunlight() = shot("amo_update_available_bn_sunlight", AppLanguage.BN, sunlight = true) { UpdateScreen(com.aktcl.aron.core.system.update.DownloadUi.Idle) }
}
