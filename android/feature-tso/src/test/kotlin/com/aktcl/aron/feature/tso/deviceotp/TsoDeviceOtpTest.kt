package com.aktcl.aron.feature.tso.deviceotp

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextEquals
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** SR manual 04 (SRM-A-06): the TSO's Device OTP screen over the existing `listDeviceOtps` / `issueDeviceOtp` operations. Fakes only, no real code. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp")
class TsoDeviceOtpTest {
    @get:Rule val compose = createComposeRule()

    private val page = """{"items":[
      {"user_id":12,"username":"sr0012","full_name":"Rahim","zone_id":3,"otp":null,"device_model":null,"created_at":"2026-11-12T03:00:00Z","expires_at":"2026-11-12T05:00:00Z","attempts":0,"employee_code":"E12","zone_code":"Z3","zone_name":"Banani"},
      {"user_id":11,"username":"sr0011","full_name":"Abdul","zone_id":3,"otp":"3560","device_model":"Galaxy A04","created_at":"2026-11-12T03:00:00Z","expires_at":"2026-11-12T05:30:00Z","attempts":1,"employee_code":"E11","zone_code":"Z3","zone_name":"Banani"},
      {"user_id":13,"username":"sr0013","full_name":"Bad","otp":"12ab","expires_at":"2026-11-12T05:00:00Z","attempts":0},
      {"full_name":"no id"}
    ],"next_cursor":"c2"}"""

    @Test fun parseKeepsGoodRowsDropsABadCodeAndPutsLiveCodesFirst() {
        val p = TsoDeviceOtps.parse(page)!!
        assertEquals(3, p.rows.size) // the row with no id is skipped
        assertEquals(11L, p.rows.first().userId) // a live code first
        assertEquals("3560", p.rows.first().otp)
        assertNull(p.rows.single { it.userId == 13L }.otp) // "12ab" is not a code
        assertEquals("c2", p.nextCursor)
    }

    @Test fun anUnreadableAnswerIsNullAndTheRowNeverPrintsItsCode() {
        assertNull(TsoDeviceOtps.parse("not json"))
        assertNull(TsoDeviceOtps.parse("""{"nope":1}"""))
        val r = TsoDeviceOtps.parse(page)!!.rows.first()
        assertFalse(r.toString().contains("3560"))
    }

    @Test fun theIssueBodyCarriesOnlyTheUserIdAndALongEnoughReason() {
        val b = kotlinx.serialization.json.Json.parseToJsonElement(TsoDeviceOtps.issueBody(12)) as kotlinx.serialization.json.JsonObject
        assertEquals(setOf("user_id", "reason"), b.keys)
        assertTrue(TsoDeviceOtps.ISSUE_REASON.length in 10..500)
    }

    private fun model(read: DeviceOtpFetch = DeviceOtpFetch.Fresh(page), issue: DeviceOtpIssue = DeviceOtpIssue.Offline, issued: MutableList<Long> = ArrayList()) =
        TsoDeviceOtpModel({ read }, { id -> issued += id; issue })

    @Test fun refreshLoadsTheRowsAndAFailedRefreshKeepsThemAndSaysWhy() = runBlocking {
        var next: DeviceOtpFetch = DeviceOtpFetch.Fresh(page)
        val m = TsoDeviceOtpModel({ next }, { DeviceOtpIssue.Offline })
        m.refresh()
        assertEquals(3, m.rows!!.size)
        assertNull(m.problem)
        next = DeviceOtpFetch.Unavailable
        m.refresh()
        assertEquals(3, m.rows!!.size) // still on screen
        assertEquals(DeviceOtpProblem.OFFLINE, m.problem)
        next = DeviceOtpFetch.Refused
        m.refresh()
        assertTrue(m.rows!!.isEmpty())
        assertEquals(DeviceOtpProblem.REFUSED, m.problem)
    }

    @Test fun issuingIsOnlyForARepTheServerListedAndMergesTheNewCode() = runBlocking {
        val issued = ArrayList<Long>()
        val m = model(issue = DeviceOtpIssue.Issued("""{"user_id":12,"username":"sr0012","full_name":"Rahim","otp":"7788","expires_at":"2026-11-12T06:00:00Z","attempts":0,"created_at":"2026-11-12T04:00:00Z"}"""), issued = issued)
        m.refresh()
        m.issueFor(999) // not listed: never sent
        assertTrue(issued.isEmpty())
        m.issueFor(12)
        assertEquals(listOf(12L), issued)
        assertEquals("7788", m.rows!!.single { it.userId == 12L }.otp)
        assertEquals(3, m.rows!!.size)
        assertNull(m.issuing)
    }

    @Test fun offlineAndRefusedIssuesSayWhyAndChangeNothing() = runBlocking {
        val m = model(issue = DeviceOtpIssue.Offline)
        m.refresh()
        m.issueFor(12)
        assertEquals(DeviceOtpProblem.ISSUE_OFFLINE, m.problem)
        assertNull(m.rows!!.single { it.userId == 12L }.otp)
        val r = model(issue = DeviceOtpIssue.Refused)
        r.refresh(); r.issueFor(12)
        assertEquals(DeviceOtpProblem.ISSUE_REFUSED, r.problem)
    }

    @Test fun showMoreAppendsTheNextPageWithoutDuplicates() = runBlocking {
        val second = """{"items":[{"user_id":20,"username":"sr0020","full_name":"Zed","expires_at":"2026-11-12T05:00:00Z","attempts":0},{"user_id":12,"username":"sr0012","full_name":"Rahim","expires_at":"2026-11-12T05:00:00Z","attempts":0}],"next_cursor":null}"""
        val seen = ArrayList<String?>()
        val m = TsoDeviceOtpModel({ c -> seen += c; if (c == null) DeviceOtpFetch.Fresh(page) else DeviceOtpFetch.Fresh(second) }, { DeviceOtpIssue.Offline })
        m.refresh(); m.more()
        assertEquals(listOf(null, "c2"), seen)
        assertEquals(setOf(11L, 12L, 13L, 20L), m.rows!!.map { it.userId }.toSet())
        assertEquals(4, m.rows!!.size)
        assertNull(m.nextCursor)
    }

    private fun show(language: AppLanguage, m: TsoDeviceOtpModel, onIssue: (Long) -> Unit = {}) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext(), language)
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language, tso = true) { TsoDeviceOtpContent(m, {}, onIssue, {}) } } }
        compose.waitForIdle()
    }

    @Test fun theScreenShowsTheCodeInLatinDigitsAndAnIssueButtonForARepWithoutOne() {
        val m = model(); runBlocking { m.refresh() }
        val issued = ArrayList<Long>()
        show(AppLanguage.BN, m) { issued += it }
        compose.onNodeWithTag(TsoDeviceOtpTags.code(11), useUnmergedTree = true).assertTextEquals("3560")
        compose.onNodeWithTag(TsoDeviceOtpTags.issue(11), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(TsoDeviceOtpTags.issue(12), useUnmergedTree = true).performClick()
        assertEquals(listOf(12L), issued)
        compose.onAllNodesWithText("OTP দিন", useUnmergedTree = true).assertCountEquals(2)
    }

    @Test fun theEnglishScreenHasTheOnlineOnlyNote() {
        val m = model(); runBlocking { m.refresh() }
        show(AppLanguage.EN, m)
        compose.onNodeWithTag(TsoDeviceOtpTags.NOTE).assertIsDisplayed()
        compose.onAllNodesWithText("Issue OTP", useUnmergedTree = true).assertCountEquals(2) // reps 12 and 13 have no live code
    }

    @Test fun offlineWithNothingShownSaysCodesAreNeverKept() {
        val m = model(read = DeviceOtpFetch.Unavailable); runBlocking { m.refresh() }
        show(AppLanguage.EN, m)
        compose.onNodeWithTag(TsoDeviceOtpTags.PROBLEM).assertIsDisplayed()
        compose.onNodeWithText("Codes are never kept on the phone", substring = true).assertExists()
    }
}
