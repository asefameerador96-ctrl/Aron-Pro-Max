package com.aktcl.aron.feature.dayclose

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.MemoEntity
import com.aktcl.aron.core.database.entity.MemoLineEntity
import com.aktcl.aron.core.database.entity.VisitEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.SaleCapture
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RoomDayAdaptersTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    @After fun tearDown() = db.close()
    private fun meta() = CaptureMeta("2026-10-05", "2026-10-05T04:31:07.120Z", 1, 1, 0, true, 10231, null, "b", false, 1)
    private val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }

    private suspend fun sell(due: Long) {
        val visit = ClientIds.newUuid(); val fixId = ClientIds.newUuid()
        val fix = GeoFixEntity(fixId, visit, "visit_open", "ok", 23.79, 90.40, 11.5, provider = "fused", isMock = false, reused = false, deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false)
        repo.recordVisitOpen(VisitEntity(visit, meta(), "sr_call", 1, "2026-10-05T04:31:07.120Z", 1, true, null, fixId, "in_range", 18.4, 100, 50, "master", 23.7, 90.4, "sale_allowed"), fix)
        val memo = ClientIds.newUuid()
        repo.recordSale(SaleCapture(
            MemoEntity(memo, meta(), visit, 1, "sr334001-261005-001", "sale", "2026-10-05T04:35:00.000Z", "2026-10-05", "outlet", 84_000, 0, 0, 0, 0, 84_000, 84_000 - due, due, due > 0, null, 1, 0, 0),
            listOf(MemoLineEntity(ClientIds.newUuid(), meta(), memo, 1, 105, "sale", 3, "dozen", 1, 3, "outlet", "2026-09-01", 28_000, 1, 84_000)),
        ))
    }

    @Test fun submitIsTheLastRecordCountsDuesAndMoneyAreRealAndTheDayLocks() = runTest {
        sell(due = 50_000)
        val source = RoomDaySource(db)
        assertEquals(DuesAtSubmit(1, 50_000), source.dues("2026-10-05"))
        assertEquals(1, source.deviceCounts("2026-10-05")["memo"])
        assertFalse(source.alreadySubmitted("2026-10-05"))

        val gate = SalesSubmitRules.gate(source.outboxStates("2026-10-05"), listOf(CountRow("memo", 1, 1)), source.dues("2026-10-05"), false)
        RoomDaySubmitWriter(db, repo, { meta() }, { "match" }).queue("2026-10-05", gate, listOf(CountRow("memo", 1, 1)))
        assertTrue(source.alreadySubmitted("2026-10-05"))
        assertFalse(source.submitSettled("2026-10-05"))
        val submit = db.captureDao().daySubmitsOn("2026-10-05").single()
        assertTrue(submit.submittedWithDues); assertEquals(50_000, submit.duesOutstandingMtk); assertEquals(1, submit.submitCycle)
        assertTrue(submit.deviceMoneyJson.contains("\"net_mtk\":84000")); assertTrue(submit.deviceMoneyJson.contains("\"sold_qty_base_by_sku\":{\"105\":3}"))
        assertEquals("day_submit", db.outboxDao().nextPending(100).last().recordType)
        // nothing more is captured after the submit
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { sell(0) } }
    }

    /** D-NIGHT-DAYCLOSE-01 (b): the refused rows are listed from the phone's own outbox, with memo number, outlet, reason and outcome. */
    @Test fun rejectedRowsAreListedFromTheAckRegistry() = runTest {
        db.referenceDao().insertSections(listOf(com.aktcl.aron.core.database.entity.BundleSectionEntity("reason_texts",
            "{\"memo_no_duplicate\": {\"bn\": \"এই মেমো নম্বর আগেই ব্যবহার হয়েছে\", \"en\": \"This memo number was already used\"}, \"price_invalid\": {\"bn\": \"দাম মেলেনি\", \"en\": \"The price did not match\"}}")))
        sell(due = 0)
        val source = RoomDaySource(db)
        assertTrue(source.rejectedRows("2026-10-05").isEmpty())
        val rows = db.outboxDao().nextPending(100)
        val memo = rows.first { it.recordType == "memo" }
        val visit = rows.first { it.recordType == "visit" }
        val line = rows.first { it.recordType == "memo_line" }
        db.outboxDao().applyAck(memo.clientUuid, "quarantined", "memo_no_duplicate", null, "2026-10-05T05:00:00.000Z")
        db.outboxDao().applyAck(line.clientUuid, "rejected", "price_invalid", null, "2026-10-05T05:00:00.000Z")
        db.outboxDao().applyAck(visit.clientUuid, "pending", "resync", null, "2026-10-05T05:00:00.000Z")
        // the phone's own markers on queued rows (resync, digest_resend) are not rejections
        // the memo and its refused line are ONE entry (a family), led by the parent
        source.rejectedRows("2026-10-05").single().let { assertEquals("memo", it.recordType); assertEquals(2, it.familySize) }
        db.outboxDao().applyAck(visit.clientUuid, "pending", "scope_stale", null, "2026-10-05T05:00:00.000Z")
        val listed = source.rejectedRows("2026-10-05").associateBy { it.recordType }
        assertEquals("the family is one entry; the visit's resync-marker row is its own", 2, listed.size)
        listed.getValue("memo").let {
            // round-2 review (family outcome hides a rejected child): the memo is stored and flagged but its LINE was dropped, so the entry says the
            // not-stored thing (tell the TSO) and names the line's reason, never "stored and flagged" alone
            assertEquals(RejectedOutcome.DROPPED, it.outcome); assertEquals("sr334001-261005-001", it.memoNo)
            assertEquals("price_invalid", it.code)
            assertEquals("দাম মেলেনি", it.reasonBn); assertEquals("The price did not match", it.reasonEn)
        }
        // the refused line is inside the memo's family, and is still counted
        assertEquals(2, listed.getValue("memo").familySize)
        assertEquals(RejectedOutcome.RETRY, listed.getValue("visit").outcome)
    }

    /**
     * D-AGATE-15 (T5): REAL parentage, not rank. The visit and the memo are accepted, a survey answer of the same visit (a rank-1 SIBLING of the memo) is refused for good, and
     * a memo line the phone gave up on. The line's ancestors (memo, visit) are fine, so it can be sent again: the entry says Send again and the SQL re-queues it.
     * A refused MEMO (its real parent) still withholds it.
     */
    @Test fun aRefusedSiblingNeverWithholdsSendAgainButARefusedParentDoes() = runTest {
        sell(due = 0)
        val visitUuid = db.captureDao().visitsOn("2026-10-05").single().clientUuid
        repo.recordSurveyResponse(com.aktcl.aron.core.database.entity.SurveyResponseEntity(ClientIds.newUuid(), meta(), visitUuid, 1, 1, 1, "bool", true))
        val rows = db.outboxDao().nextPending(100)
        val visit = rows.first { it.recordType == "visit" }; val memo = rows.first { it.recordType == "memo" }
        val line = rows.first { it.recordType == "memo_line" }; val survey = rows.first { it.recordType == "survey_response" }
        db.outboxDao().applyAck(visit.clientUuid, "acked", null, 1, "2026-10-05T05:00:00.000Z")
        db.outboxDao().applyAck(memo.clientUuid, "acked", null, 2, "2026-10-05T05:00:00.000Z")
        db.outboxDao().applyAck(survey.clientUuid, "rejected", "schema_invalid", null, "2026-10-05T05:00:00.000Z")
        db.outboxDao().applyAck(line.clientUuid, "rejected", "retry_exhausted", null, "2026-10-05T05:00:00.000Z")
        val source = RoomDaySource(db)
        val entries = source.rejectedRows("2026-10-05")
        val lineEntry = entries.single { it.code == "retry_exhausted" }
        assertEquals("the survey is a sibling: the line can be sent again", RejectedOutcome.PHONE_GAVE_UP, lineEntry.outcome)
        assertTrue(lineEntry.canSendAgain)
        assertEquals("and the SQL really re-queues it", 1, db.outboxDao().requeueRetryExhausted(line.clientUuid))
    }

    /** D-AGATE-15 (T5): the line's REAL parent (its memo) refused for good withholds Send again, in the reader and in the SQL. */
    @Test fun aRefusedRealParentOfAnExhaustedLineWithholdsSendAgain() = runTest {
        sell(due = 0)
        val rows = db.outboxDao().nextPending(100)
        val visit = rows.first { it.recordType == "visit" }; val memo = rows.first { it.recordType == "memo" }; val line = rows.first { it.recordType == "memo_line" }
        db.outboxDao().applyAck(visit.clientUuid, "acked", null, 1, "2026-10-05T05:00:00.000Z")
        db.outboxDao().applyAck(memo.clientUuid, "rejected", "price_invalid", null, "2026-10-05T05:10:00.000Z")
        db.outboxDao().applyAck(line.clientUuid, "rejected", "retry_exhausted", null, "2026-10-05T05:10:00.000Z")
        assertEquals(0, db.outboxDao().requeueRetryExhausted(line.clientUuid))
        assertFalse(RoomDaySource(db).rejectedRows("2026-10-05").any { it.canSendAgain })
    }

    /**
     * D-AGATE-18 (B2): a memo PRINT (rank 3, its parent is its memo by `memo_client_uuid`) is not held back by a refused survey of the same visit; only its memo refused for good withholds it.
     */
    @Test fun aMemoPrintIsNotWithheldByARefusedSiblingButIsByItsRefusedMemo() = runTest {
        sell(due = 0)
        val visitUuid = db.captureDao().visitsOn("2026-10-05").single().clientUuid
        repo.recordSurveyResponse(com.aktcl.aron.core.database.entity.SurveyResponseEntity(ClientIds.newUuid(), meta(), visitUuid, 1, 1, 1, "bool", true))
        val memoUuid = db.captureDao().memosOn("2026-10-05").single().clientUuid
        val pe = com.aktcl.aron.core.database.entity.PrintEventEntity(ClientIds.newUuid(), meta(), "memo", memoUuid, null, 1, "printed", true, 1, "RPP02N", 1L)
        db.printDao().insertEvent(pe)
        db.outboxDao().insert(listOf(com.aktcl.aron.core.database.record.RecordMapping.printEvent(pe, visitUuid, 3, kotlinx.serialization.json.buildJsonObject {}, "2026-10-05T04:40:00.000Z")))
        val rows = db.outboxDao().nextPending(100)
        val visit = rows.first { it.recordType == "visit" }; val memo = rows.first { it.recordType == "memo" }; val survey = rows.first { it.recordType == "survey_response" }
        db.outboxDao().applyAck(visit.clientUuid, "acked", null, 1, "2026-10-05T05:00:00.000Z")
        db.outboxDao().applyAck(memo.clientUuid, "acked", null, 2, "2026-10-05T05:00:00.000Z")
        db.outboxDao().applyAck(survey.clientUuid, "rejected", "schema_invalid", null, "2026-10-05T05:00:00.000Z")
        db.outboxDao().applyAck(pe.clientUuid, "rejected", "retry_exhausted", null, "2026-10-05T05:00:00.000Z")
        val entry = RoomDaySource(db).rejectedRows("2026-10-05").single { it.code == "retry_exhausted" }
        assertEquals("the survey is a sibling of the print: it can be sent again", RejectedOutcome.PHONE_GAVE_UP, entry.outcome); assertTrue(entry.canSendAgain)
        assertEquals("and the SQL really re-queues it", 1, db.outboxDao().requeueRetryExhausted(pe.clientUuid))
        // its REAL parent, the memo, refused for good, withholds it
        db.outboxDao().applyAck(pe.clientUuid, "rejected", "retry_exhausted", null, "2026-10-05T05:20:00.000Z")
        db.openHelper.writableDatabase.execSQL("UPDATE outbox SET state = 'rejected', last_code = 'price_invalid' WHERE client_uuid = '${memo.clientUuid}'")
        assertEquals(0, db.outboxDao().requeueRetryExhausted(pe.clientUuid))
        assertFalse(RoomDaySource(db).rejectedRows("2026-10-05").any { it.canSendAgain })
    }

    /**
     * D-AGATE-18 (B2): an AMO rank-2 record whose parent is named only in its payload (a distribution line's check) is not withheld by a refused rank-1 SIBLING (a call assessment); the parent
     * is stored at capture (`outbox.parent_uuid`), and only the check itself refused for good withholds the line. The same for an assessment answer.
     */
    @Test fun anAmoLineIsNotWithheldByARefusedSiblingButIsByItsRefusedCheck() = runTest {
        sell(due = 0)
        val visitUuid = db.captureDao().visitsOn("2026-10-05").single().clientUuid
        val amo = com.aktcl.aron.core.database.record.AmoPayloads
        val check = ClientIds.newUuid(); val line = ClientIds.newUuid(); val assessment = ClientIds.newUuid()
        repo.recordAmo(com.aktcl.aron.core.database.entity.AmoRecordEntity(check, meta(), "distribution_check", visitUuid, 1, amo.distributionCheck(visitUuid, true, null).toString()))
        repo.recordAmo(com.aktcl.aron.core.database.entity.AmoRecordEntity(line, meta(), "distribution_check_line", visitUuid, 2, amo.distributionLine(check, 7, true, false).toString()))
        repo.recordAmo(com.aktcl.aron.core.database.entity.AmoRecordEntity(assessment, meta(), "call_assessment", visitUuid, 1, amo.callAssessment("joint_call", visitUuid, 1, 1, 5, 10, 20).toString()))
        assertEquals("the parent is stored at capture", check, db.outboxDao().byClientUuid(line)!!.parentUuid)
        val rows = db.outboxDao().nextPending(100)
        listOf("visit", "memo", "distribution_check").forEach { t -> db.outboxDao().applyAck(rows.first { it.recordType == t }.clientUuid, "acked", null, 1, "2026-10-05T05:00:00.000Z") }
        db.outboxDao().applyAck(assessment, "rejected", "schema_invalid", null, "2026-10-05T05:00:00.000Z")
        db.outboxDao().applyAck(line, "rejected", "retry_exhausted", null, "2026-10-05T05:00:00.000Z")
        val entry = RoomDaySource(db).rejectedRows("2026-10-05").single { it.code == "retry_exhausted" }
        assertEquals(RejectedOutcome.PHONE_GAVE_UP, entry.outcome); assertTrue(entry.canSendAgain)
        assertEquals(1, db.outboxDao().requeueRetryExhausted(line))
        db.outboxDao().applyAck(line, "rejected", "retry_exhausted", null, "2026-10-05T05:20:00.000Z")
        db.openHelper.writableDatabase.execSQL("UPDATE outbox SET state = 'rejected', last_code = 'schema_invalid' WHERE client_uuid = '$check'")
        assertEquals("its refused check withholds it", 0, db.outboxDao().requeueRetryExhausted(line))
        assertFalse(RoomDaySource(db).rejectedRows("2026-10-05").any { it.canSendAgain })
    }

    /**
     * D-AGATE-19 (step-5 low 8): a parent in ANOTHER family still withholds Send again. A memo REPRINTED after its route-day was submitted is a rank-0 print_event in its OWN family
     * (RoomPrintLedger), and a due collection is its own family too: when the memo they name was refused for good they could only answer parent_missing, in the reader and in the SQL guard.
     */
    @Test fun aParentInAnotherFamilyWithholdsSendAgainOfAReprintAndOfADueCollection() = runTest {
        sell(due = 84_000)
        val memoRow = db.captureDao().memosOn("2026-10-05").single()
        val memoUuid = memoRow.clientUuid
        val reprint = com.aktcl.aron.core.database.entity.PrintEventEntity(ClientIds.newUuid(), meta(), "memo", memoUuid, null, 2, "printed", true, 1, "RPP02N", 1L)
        db.printDao().insertEvent(reprint)
        db.outboxDao().insert(listOf(com.aktcl.aron.core.database.record.RecordMapping.printEvent(reprint, reprint.clientUuid, 0, kotlinx.serialization.json.buildJsonObject {}, "2026-10-05T04:50:00.000Z")))
        val due = com.aktcl.aron.core.database.entity.DueCollectionEntity(ClientIds.newUuid(), meta(), memoRow.outletId, memoUuid, memoRow.memoNo, "2026-10-05", 10_000, false, 84_000)
        repo.recordDueCollection(due)
        val rows = db.outboxDao().nextPending(100)
        val memo = rows.first { it.recordType == "memo" }
        db.outboxDao().applyAck(memo.clientUuid, "acked", null, 1, "2026-10-05T05:00:00.000Z")
        db.outboxDao().applyAck(reprint.clientUuid, "rejected", "retry_exhausted", null, "2026-10-05T05:00:00.000Z")
        db.outboxDao().applyAck(due.clientUuid, "rejected", "retry_exhausted", null, "2026-10-05T05:00:00.000Z")
        assertTrue("memo stored: both can be sent again", RoomDaySource(db).rejectedRows("2026-10-05").all { it.canSendAgain })
        assertEquals(1, db.outboxDao().requeueRetryExhausted(reprint.clientUuid)); assertEquals(1, db.outboxDao().requeueRetryExhausted(due.clientUuid))
        db.outboxDao().applyAck(reprint.clientUuid, "rejected", "retry_exhausted", null, "2026-10-05T05:20:00.000Z")
        db.outboxDao().applyAck(due.clientUuid, "rejected", "retry_exhausted", null, "2026-10-05T05:20:00.000Z")
        // the memo they depend on is refused for good (a different family from both)
        db.openHelper.writableDatabase.execSQL("UPDATE outbox SET state = 'rejected', last_code = 'price_invalid' WHERE client_uuid = '${memo.clientUuid}'")
        assertEquals("the reprint is withheld", 0, db.outboxDao().requeueRetryExhausted(reprint.clientUuid))
        assertEquals("the due collection is withheld", 0, db.outboxDao().requeueRetryExhausted(due.clientUuid))
        val entries = RoomDaySource(db).rejectedRows("2026-10-05")
        assertTrue("and the screen offers no Send again for them", entries.filter { it.code == "retry_exhausted" }.none { it.canSendAgain })
    }

    /** Contract 1.9 row 4: the digest adds this phone's received counts and the server's rows this install does not hold. */
    @Test fun theDigestFeedsThePerDeviceCountsAndTheServerList() = runTest {
        db.referenceDao().insertSections(listOf(com.aktcl.aron.core.database.entity.BundleSectionEntity("reason_texts",
            "{\"price_invalid\": {\"bn\": \"দাম শূন্য\", \"en\": \"Priced at zero\"}}")))
        val digest = com.aktcl.aron.core.sync.SubmitDigest(
            mapOf("memo" to 4),
            listOf(
                com.aktcl.aron.core.sync.SubmitDigest.Rejected("a", "memo_line", "2026-10-05", "rejected", "price_invalid", true, false, "x-1", null),
                com.aktcl.aron.core.sync.SubmitDigest.Rejected("b", "visit", "2026-10-05", "parked", "scope_stale", false, false, null, null),
                com.aktcl.aron.core.sync.SubmitDigest.Rejected("c", "memo", "2026-10-05", "quarantined", "memo_no_duplicate", false, true, "x-2", null),
            ),
            false,
        )
        val src = com.aktcl.aron.core.sync.SubmitDigestSource.answering(digest)
        val counts = DigestServerCounts({ mapOf("memo" to 10) }, src, db)
        assertEquals(mapOf("memo" to 10), counts.counts("2026-10-05"))
        assertEquals(mapOf("memo" to 4), counts.thisDeviceCounts("2026-10-05"))
        val rows = counts.serverRejected("2026-10-05").associateBy { it.clientUuid }
        rows.getValue("a").let { assertEquals(RejectedOutcome.DROPPED, it.outcome); assertEquals("Priced at zero", it.reasonEn); assertFalse(it.fromThisPhone) }
        assertEquals(RejectedOutcome.WAITING_ELSEWHERE, rows.getValue("b").outcome) // another install's parked row never blocks this phone
        assertEquals(RejectedOutcome.STORED_FLAGGED, rows.getValue("c").outcome)
    }

    @Test fun anAmoSubmitIsASupervisorDayWithNoRoute() = runTest {
        val source = RoomDaySource(db)
        val noRoute = { meta().copy(routeId = null) }
        val gate = SalesSubmitRules.gate(source.outboxStates("2026-10-05"), emptyList(), source.dues("2026-10-05"), false)
        RoomDaySubmitWriter(db, repo, noRoute, { "match" }, scope = "supervisor_day").queue("2026-10-05", gate, emptyList())
        val submit = db.captureDao().daySubmitsOn("2026-10-05").single()
        assertEquals("supervisor_day", submit.scope); assertEquals(null, submit.meta.routeId); assertEquals(1, submit.submitCycle)
        assertTrue(source.alreadySubmitted("2026-10-05"))
    }
}
