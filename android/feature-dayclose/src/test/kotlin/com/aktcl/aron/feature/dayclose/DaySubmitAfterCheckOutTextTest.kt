package com.aktcl.aron.feature.dayclose

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.AttendanceEventEntity
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.MemoEntity
import com.aktcl.aron.core.database.entity.MemoLineEntity
import com.aktcl.aron.core.database.entity.VisitEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.SaleCapture
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * D-AGATE-15 (T3): ONE text per real state of the day's own submit AFTER the check-out, each built from the real outbox rows through the real [RoomDaySource] and shown on the
 * real Sales Submit screen: retried (held, nothing to press), given up by the phone (Send again), refused for good (released, tell the supervisor), held for review.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp")
class DaySubmitAfterCheckOutTextTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    @After fun tearDown() = db.close()
    private val date = "2026-10-05"
    private fun meta() = CaptureMeta(date, "2026-10-05T04:31:07.120Z", 1, 1, 0, true, 10231, null, "b", false, 1)
    private val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }

    /** A sold call, its day submit queued, and the rep's check-out: the submit's outbox row is returned. */
    private fun submittedAndCheckedOut(): String = runBlocking {
        val visit = ClientIds.newUuid(); val fixId = ClientIds.newUuid()
        val fix = GeoFixEntity(fixId, visit, "visit_open", "ok", 23.79, 90.40, 11.5, provider = "fused", isMock = false, reused = false, deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false)
        repo.recordVisitOpen(VisitEntity(visit, meta(), "sr_call", 1, "2026-10-05T04:31:07.120Z", 1, true, null, fixId, "in_range", 18.4, 100, 50, "master", 23.7, 90.4, "sale_allowed"), fix)
        val memo = ClientIds.newUuid()
        repo.recordSale(SaleCapture(
            MemoEntity(memo, meta(), visit, 1, "sr334001-261005-001", "sale", "2026-10-05T04:35:00.000Z", date, "outlet", 84_000, 0, 0, 0, 0, 84_000, 84_000, 0, false, null, 1, 0, 0),
            listOf(MemoLineEntity(ClientIds.newUuid(), meta(), memo, 1, 105, "sale", 3, "dozen", 1, 3, "outlet", "2026-09-01", 28_000, 1, 84_000)),
        ))
        val gate = SalesSubmitRules.gate(RoomDaySource(db).outboxStates(date), listOf(CountRow("memo", 1, 1)), RoomDaySource(db).dues(date), false)
        RoomDaySubmitWriter(db, repo, { meta() }, { "match" }).queue(date, gate, listOf(CountRow("memo", 1, 1)))
        val ev = ClientIds.newUuid(); val outFix = ClientIds.newUuid()
        repo.recordAttendance(
            AttendanceEventEntity(ev, meta().copy(capturedAt = "2026-10-05T11:00:00.000Z"), "check_out", outFix, null),
            GeoFixEntity(outFix, ev, "attendance_out", "ok", 23.79, 90.40, 11.5, provider = "fused", isMock = false, reused = false, deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false),
        )
        db.outboxDao().nextPending(100).first { it.recordType == "day_submit" }.clientUuid
    }

    @Composable private fun Lang(language: AppLanguage, content: @Composable () -> Unit) {
        val ctx = AppLocale.wrap(context, language)
        CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { content() } }
    }

    private fun screenOf(rows: List<RejectedRow>, language: AppLanguage = AppLanguage.EN) {
        val counts = listOf(CountRow("memo", 1, 1))
        val gate = SalesSubmitRules.gate(OutboxStates(0, 0, 0, 0, 0), counts, DuesAtSubmit(0, 0), false)
        compose.setContent { Lang(language) { SalesSubmitScreen(true, counts, gate, SubmitProgress.Idle, { it }, {}, {}, rejected = rows) } }
        compose.waitForIdle()
    }

    private fun daySubmitRow() = runBlocking { RoomDaySource(db).rejectedRows(date).single { it.recordType == "day_submit" } }

    /** The missing 4th state: the server answered a retryable code, the row is pending again (attempts counted), the check-out is HELD. It is not "refused". */
    @Test fun aDaySubmitBeingRetriedAfterCheckOutSaysItIsHeldAndNothingIsRefused() {
        val u = submittedAndCheckedOut()
        runBlocking { db.outboxDao().applyAck(u, "pending", "scope_stale", null, "2026-10-05T11:05:00.000Z"); db.outboxDao().countFailure(u) }
        val row = daySubmitRow()
        assertEquals(RejectedOutcome.RETRY, row.outcome); assertTrue("after the check-out", row.afterCheckOut)
        screenOf(listOf(row))
        compose.onNodeWithText("trying again by itself", substring = true).assertExists()
        compose.onNodeWithText("was refused by the server", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Contact your supervisor", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Send again", substring = true).assertDoesNotExist()
    }

    @Test fun theRetryTextReadsInBanglaToo() {
        val u = submittedAndCheckedOut()
        runBlocking { db.outboxDao().applyAck(u, "pending", "scope_stale", null, "2026-10-05T11:05:00.000Z") }
        screenOf(listOf(daySubmitRow()), AppLanguage.BN)
        compose.onNodeWithText("নিজে থেকেই আবার চেষ্টা করছে", substring = true).assertExists()
        compose.onNodeWithText("প্রত্যাখ্যান করেছে", substring = true).assertDoesNotExist()
    }

    /** Refused for good by the server: released, the check-out goes on its own, tell the supervisor (and no Send again). */
    @Test fun aDaySubmitRefusedForGoodAfterCheckOutIsReleasedAndSaysSo() {
        val u = submittedAndCheckedOut()
        runBlocking { db.outboxDao().applyAck(u, "rejected", "schema_invalid", null, "2026-10-05T11:05:00.000Z") }
        val row = daySubmitRow()
        assertEquals(RejectedOutcome.DROPPED, row.outcome)
        screenOf(listOf(row))
        compose.onNodeWithText("refused by the server", substring = true).assertExists()
        compose.onNodeWithText("check-out is sent to the server on its own", substring = true).assertExists()
    }

    /** Given up by the phone: Send again exists and is named. */
    @Test fun aDaySubmitThePhoneGaveUpOnAfterCheckOutOffersSendAgain() {
        val u = submittedAndCheckedOut()
        runBlocking { db.outboxDao().applyAck(u, "rejected", "retry_exhausted", null, "2026-10-05T11:05:00.000Z") }
        val row = daySubmitRow()
        assertEquals(RejectedOutcome.PHONE_GAVE_UP, row.outcome)
        screenOf(listOf(row))
        compose.onNodeWithText("Tap Send again on this record", substring = true).assertExists()
    }
}
