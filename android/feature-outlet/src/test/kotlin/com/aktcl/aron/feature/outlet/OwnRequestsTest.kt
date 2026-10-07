package com.aktcl.aron.feature.outlet

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** F-SR-040: own-request status. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class OwnRequestsTest {
    @get:Rule val compose = createComposeRule()

    private val section = """[
      {"request_uuid":"a1","request_type":"close","status":"approved","outlet_name":"Rahim Store","requested_by_user_id":1,"requested_at":"2026-10-06T05:00:00Z"},
      {"request_uuid":"b2","request_type":"info","status":"rejected","outlet_name":"Karim Store","requested_by_user_id":1,"requested_at":"2026-10-07T05:00:00Z","rejection_reason":"Photo unclear"},
      {"request_uuid":"c3","request_type":"new","status":"verified","requested_by_user_id":1,"requested_at":"2026-10-05T05:00:00Z"},
      {"request_uuid":"d4","request_type":"cluster","status":"pending","outlet_name":"X","requested_by_user_id":1,"requested_at":"2026-10-04T05:00:00Z","rejection_reason":"stale"}
    ]"""

    @Test fun parsesStatusesNewestFirstAndKeepsReasonOnlyForRejected() {
        val rows = OwnRequests.rows(section, emptyList())
        assertEquals(listOf("b2", "a1", "c3", "d4"), rows.map { it.requestUuid })
        assertEquals(OwnRequestStatus.REJECTED, rows[0].status); assertEquals("Photo unclear", rows[0].rejectionReason)
        assertNull(rows.single { it.requestUuid == "d4" }.rejectionReason)
    }

    @Test fun serverAnswerWinsAndPhoneOnlyRequestsWait() {
        val local = listOf(LocalRequest("a1", "close", "Rahim Store", "2026-10-06T04:59:00Z"), LocalRequest("z9", "new", "New Shop", "2026-10-07T09:00:00Z"))
        val rows = OwnRequests.rows(section, local)
        assertEquals(OwnRequestStatus.APPROVED, rows.single { it.requestUuid == "a1" }.status)
        assertEquals(OwnRequestStatus.WAITING, rows.first().status); assertEquals("z9", rows.first().requestUuid)
    }

    @Test fun phoneOnlyRowsFollowTheirOutboxStateAndAge() {
        val local = listOf(
            LocalRequest("w", "new", null, "2026-10-07T09:00:00Z", "pending"),
            LocalRequest("f", "new", null, "2026-10-07T08:00:00Z", "in_flight"),
            LocalRequest("s", "close", "S", "2026-10-07T07:00:00Z", "acked"),
            LocalRequest("r", "info", "R", "2026-10-07T06:00:00Z", "rejected", "ERR_X"),
            LocalRequest("old", "new", null, "2026-08-01T06:00:00Z", "pending"),
        )
        val rows = OwnRequests.rows(null, local, sinceIso = "2026-09-07T00:00:00Z").associateBy { it.requestUuid }
        assertEquals(OwnRequestStatus.WAITING, rows.getValue("w").status)
        assertEquals(OwnRequestStatus.WAITING, rows.getValue("f").status)
        assertEquals(OwnRequestStatus.PENDING, rows.getValue("s").status)
        assertEquals(OwnRequestStatus.REJECTED, rows.getValue("r").status); assertEquals("ERR_X", rows.getValue("r").rejectionReason)
        assertEquals(false, rows.containsKey("old"))
    }

    @Test fun noBundleSectionOrGarbageShowsOnlyLocalRows() {
        assertEquals(emptyList<OwnRequestRow>(), OwnRequests.rows(null, emptyList()))
        assertEquals(emptyList<OwnRequestRow>(), OwnRequests.rows("{not json", emptyList()))
        assertEquals(1, OwnRequests.rows(null, listOf(LocalRequest("z", "new", null, "t"))).size)
    }

    @Test fun unknownFutureStatusReadsAsPending() {
        val rows = OwnRequests.parse("""[{"request_uuid":"q","request_type":"new","status":"on_hold","requested_at":"t"}]""")
        assertEquals(OwnRequestStatus.PENDING, rows.single().status)
    }

    private fun show(language: AppLanguage, rows: List<OwnRequestRow>) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { OwnRequestsContent(rows) } } }
        compose.waitForIdle()
    }

    @Test fun rejectedShowsItsReasonInBangla() {
        show(AppLanguage.BN, OwnRequests.rows(section, emptyList()))
        compose.onNodeWithText("প্রত্যাখ্যাত").assertIsDisplayed()
        compose.onNodeWithText("কারণ: Photo unclear").assertIsDisplayed()
        compose.onNodeWithTag(OwnRequestTags.reason("a1")).assertDoesNotExist()
    }

    @Test fun approvedAndEmptyStatesInEnglish() {
        show(AppLanguage.EN, OwnRequests.rows(section, emptyList()))
        compose.onNodeWithText("Approved").assertIsDisplayed()
    }

    @Test fun emptyStateInEnglish() {
        show(AppLanguage.EN, emptyList())
        compose.onNodeWithTag(OwnRequestTags.EMPTY).assertIsDisplayed()
    }
}
