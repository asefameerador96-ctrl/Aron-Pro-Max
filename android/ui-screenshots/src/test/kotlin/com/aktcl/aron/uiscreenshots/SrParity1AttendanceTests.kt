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
import com.aktcl.aron.feature.attendance.AttendanceContent
import com.aktcl.aron.feature.attendance.AttendanceState
import com.aktcl.aron.feature.attendance.AttendanceTags
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
 * Lane P-SR1 goldens (D-PAR-SR1-09): the SR Attendance screen as the SR host draws it (`statusBoxes = true`, the manual 11 to 13 look),
 * in every state the audit found unrecorded: before check-in, the check-in hold sheet, checked in (check-out waits for 17:00), checked in
 * with check-out active, the check-out hold sheet, the day done, a failed location reading and the long text at font scale 2.0.
 * Bangla light, 360 x 800 dp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "bn-w360dp-h800dp")
class SrParity1AttendanceTests {
    @get:Rule val rule = createComposeRule()
    private val goldens: String = System.getProperty("aron.screenshots.goldens") ?: "src/test/screenshots"
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))
    private val now = { "2026-10-05T10:50:00.000Z" } // 16:50 in Dhaka

    private val before = AttendanceState(coordinatesText = "23.792580, 90.407830", addressText = "23.792580, 90.407830", fixAccuracyM = 18)
    private val checkedIn = before.copy(checkedInAt = "2026-10-05T03:10:00.000Z", checkInEnabled = false)
    private val checkOutOpen = checkedIn.copy(checkOutEnabled = true)
    private val done = checkOutOpen.copy(checkedOutAt = "2026-10-05T11:05:00.000Z", checkOutEnabled = false)

    private fun shot(name: String, state: AttendanceState, fontScale: Float = 1.0f, click: String? = null, subtitle: String = "Apsis RouteDaily, 2026-10-05") {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), AppLanguage.BN)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides ctx, LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale)) {
                AronTheme(AppLanguage.BN, dark = false, sunlight = false, tier = GlassTier.B, reducedMotion = true) {
                    ParityFrame(androidx.compose.ui.res.stringResource(com.aktcl.aron.feature.home.R.string.hm_tile_attendance), com.aktcl.aron.core.ui.R.drawable.aron_art_attendance) {
                        AttendanceContent(
                            state, "17:00", {}, {}, onRefreshFix = {}, nowIso = now,
                            userTitle = "SR - Testing Banani (sr334001)", userSubtitle = subtitle, statusBoxes = true,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        if (click != null) { rule.onNodeWithTag(click).performClick(); rule.waitForIdle() }
        if (System.getProperty("roborazzi.test.record") != "true") assertTrue("missing golden $goldens/$name.png", java.io.File("$goldens/$name.png").exists())
        rule.onRoot().captureRoboImage("$goldens/$name.png", roborazziOptions = options)
    }

    @Test fun before() = shot("srp1_att_before", before)
    @Test fun checkInSheet() = shot("srp1_att_checkin_sheet", before, click = AttendanceTags.CHECK_IN)
    @Test fun checkedInWaiting() = shot("srp1_att_checkedin_waiting", checkedIn)
    @Test fun checkedInOutActive() = shot("srp1_att_checkedin_out_active", checkOutOpen)
    @Test fun checkOutSheet() = shot("srp1_att_checkout_sheet", checkOutOpen, click = AttendanceTags.CHECK_OUT)
    @Test fun dayDone() = shot("srp1_att_done", done)
    @Test fun fixFailed() = shot("srp1_att_fix_failed", before.copy(fixFailed = true, coordinatesText = null, addressText = null, fixAccuracyM = null))
    @Test fun longTextFont20() = shot("srp1_att_before_font20", before, fontScale = 2.0f, subtitle = "ভাটারা বাজার(শনি, সোম, বুধ), 2026-10-05")
}
