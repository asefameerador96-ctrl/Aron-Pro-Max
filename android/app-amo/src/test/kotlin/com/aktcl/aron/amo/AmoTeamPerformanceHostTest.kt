package com.aktcl.aron.amo

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Contract 1.11.2 on the Team Performance screen, composed on a real [AmoDay]: the zone card reads the server's field-unit members (packets, boxes, dozens,
 * Decimal3 as given) from the answer kept on this phone, and a zone answer WITHOUT the members (an older server) shows the base figure with the base word.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp")
class AmoTeamPerformanceHostTest {
    @get:Rule val compose = hostComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var f: AmoLockFixture

    @Before fun setUp() { f = AmoLockFixture(app, latePolicy = "accept_and_flag") }
    @After fun tearDown() { f.close() }

    private fun zone(rows: String) = """{"node":{"type":"zone","id":5,"name":"Gulshan"},"period_days":6,"rows":[$rows]}"""
    private fun base(name: String, cat: String, t: Long, a: Long) = """"name":"$name","category_code":"$cat","target_qty_base":$t,"achieved_qty_base":$a,"tilldate_target_qty_base":$t"""
    private val withMembers = zone(
        """{${base("Marise", "cigarette", 50_000, 40_999)},"report_unit":"pack","target_qty_report":"2500.000","achieved_qty_report":"2199.950","tilldate_target_qty_report":"2500.000","remaining_qty_report":"300.050","remaining_tilldate_qty_report":"300.050"},
           {${base("Aster", "lighter", 1_000, 810)},"report_unit":"box","target_qty_report":"40.000","achieved_qty_report":"32.400","tilldate_target_qty_report":"40.000","remaining_qty_report":"7.600","remaining_tilldate_qty_report":"7.600"},
           {${base("Salmon", "match", 30, 12)},"report_unit":"dozen","target_qty_report":"30.000","achieved_qty_report":"12.000","tilldate_target_qty_report":"30.000","remaining_qty_report":"18.000","remaining_tilldate_qty_report":"18.000"}""",
    )
    private val withoutMembers = zone("""{${base("Marise", "cigarette", 50_000, 40_999)}},{${base("Aster", "lighter", 1_000, 810)}}""")

    private fun keep(json: String) {
        val dir = java.io.File(app.cacheDir, "amo_api_${f.day.userId}").apply { mkdirs() }
        java.io.File(dir, "targets_brand_0_${f.date.take(7)}").writeText(json) // the key AmoApi.brandTargets keeps the zone answer under
    }

    private fun show() {
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        f.own(ActivityScenario.launch(ComponentActivity::class.java)).onActivity { activity ->
            val ctx = AppLocale.wrap(activity, AppLanguage.EN)
            activity.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(AppLanguage.EN) { AmoTeamPerformanceHost(f.day, onBack = {}) } } }
        }
        compose.waitForIdle()
    }

    private fun shown(text: String) = runCatching { compose.onNodeWithText(text, substring = true).assertExists() }.isSuccess

    @Test fun theZoneCardShowsPacketsBoxesAndDozensFromTheServersMembersAsGiven() {
        keep(withMembers)
        show()
        compose.waitUntil(10_000) { shown("2,199.95/2,500") }
        compose.onNodeWithText("2,199.95/2,500", substring = true).assertExists() // cigarette: packets as sent, not 40,999 sticks
        compose.onNodeWithText("32.4/40", substring = true).assertExists() // lighter: boxes
        compose.onNodeWithText("12/30", substring = true).assertExists() // match: dozens
        compose.onNodeWithText("40,999", substring = true).assertDoesNotExist()
    }

    @Test fun anAnswerWithoutTheMembersShowsTheBaseFigureWithItsBaseWord() {
        keep(withoutMembers)
        show()
        compose.waitUntil(10_000) { shown("40,999/50,000") }
        compose.onNodeWithText("40,999/50,000", substring = true).assertExists()
        compose.onNodeWithText("810/1,000", substring = true).assertExists()
    }
}
