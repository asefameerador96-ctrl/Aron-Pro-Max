package com.aktcl.aron.feature.dayclose

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.OutboxEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Device defect D1 (2026-11-13): Sales Submit said "6 records were rejected by the server." and nothing else. The count came from
 * the whole outbox of any date, the list from 14 days, and rejected rows were never cleared. These tests drive the real Room source.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RefusedRecordsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    @After fun tearDown() = db.close()
    private val source = RoomDaySource(db)
    private val today = "2026-11-13"

    private suspend fun row(date: String, state: String, code: String?, type: String = "memo_line", key: String? = null): String {
        val uuid = ClientIds.newUuid()
        db.outboxDao().insert(listOf(OutboxEntity(
            clientUuid = uuid, recordType = type, familyUuid = uuid, rank = 1, businessDate = date, payloadJson = "{\"payload\":{\"qty\":1}}", payloadSha256 = "x",
            state = state, lastCode = code, createdAt = "$date" + "T05:00:00.000Z", lastMessageKey = key,
        )))
        return uuid
    }

    /** The banner count and the list are ONE read: a record of an old date is counted AND listed, never counted and missing. */
    @Test fun theCountAndTheListAreTheSameQueryOverEveryDate() = runTest {
        row(today, "rejected", "price_invalid")
        row("2026-10-01", "rejected", "unknown_sku")          // six weeks older than the old 14-day list reached
        row("2026-10-02", "quarantined", "memo_no_duplicate")
        row(today, "acked", null)
        val states = source.outboxStates(today)
        val listed = source.rejectedRows(today)
        assertEquals(2, states.rejected); assertEquals(1, states.quarantined)
        assertEquals("every counted record is listed", states.rejected + states.quarantined, listed.size)
        assertEquals(setOf("2026-10-01", "2026-10-02", today), listed.map { it.businessDate }.toSet())
    }

    /** "Seen" takes a record out of the count and out of the main list; the ROW AND ITS PAYLOAD stay, untouched. */
    @Test fun markingARecordSeenLeavesTheCountAndNeverDeletesTheRow() = runTest {
        val u = row(today, "rejected", "price_invalid")
        val before = db.outboxDao().byClientUuid(u)!!
        source.markSeen(u)
        assertEquals(0, source.outboxStates(today).rejected)
        val listed = source.rejectedRows(today)
        assertTrue(listed.single().seen)                      // still listed, as seen
        val after = db.outboxDao().byClientUuid(u)!!
        assertNotNull(after.seenAt)
        assertEquals(before.copy(seenAt = after.seenAt), after) // nothing else about the row changed
        assertEquals(before.payloadJson, after.payloadJson); assertEquals("rejected", after.state)
        // a record that is not refused cannot be marked: nothing happens to an acked or pending row
        val acked = row(today, "acked", null); val pending = row(today, "pending", null)
        source.markSeen(acked); source.markSeen(pending)
        assertNull(db.outboxDao().byClientUuid(acked)!!.seenAt); assertNull(db.outboxDao().byClientUuid(pending)!!.seenAt)
    }

    /** "Send again" re-queues the SAME client uuid with the same payload and signature, and only for a retry_exhausted row. */
    @Test fun sendAgainRequeuesTheSameUuidAndCreatesNoSecondRecord() = runTest {
        val gaveUp = row(today, "rejected", "retry_exhausted")
        val serverRefused = row(today, "rejected", "price_invalid")
        val rowsBefore = db.outboxDao().refusedRows().size
        assertEquals(RejectedOutcome.PHONE_GAVE_UP, source.rejectedRows(today).first { it.clientUuid == gaveUp }.outcome)
        assertTrue(source.rejectedRows(today).first { it.clientUuid == gaveUp }.canSendAgain)
        assertFalse(source.rejectedRows(today).first { it.clientUuid == serverRefused }.canSendAgain)
        assertTrue(source.sendAgain(gaveUp))
        val back = db.outboxDao().byClientUuid(gaveUp)!!
        assertEquals("pending", back.state); assertEquals(0, back.attempts); assertNull(back.seenAt)
        assertEquals(gaveUp, back.clientUuid)
        assertEquals("exactly one row with that uuid, and the total is unchanged", rowsBefore, db.outboxDao().refusedRows().size + 1)
        assertEquals(1, db.query("SELECT COUNT(*) FROM outbox WHERE client_uuid = '$gaveUp'", null).use { it.moveToFirst(); it.getInt(0) })
        assertFalse("a server refusal is never resent from here", source.sendAgain(serverRefused))
        assertEquals("rejected", db.outboxDao().byClientUuid(serverRefused)!!.state)
    }

    /** The ack's message_key is kept beside the code (it used to be dropped). */
    @Test fun theAckMessageKeyIsKeptWithTheCode() = runTest {
        val u = row(today, "in_flight", null)
        db.outboxDao().applyAck(u, "rejected", "unknown_sku", null, "2026-11-13T05:00:00.000Z", "sync.reject.unknown_sku")
        val r = db.outboxDao().byClientUuid(u)!!
        assertEquals("unknown_sku", r.lastCode); assertEquals("sync.reject.unknown_sku", r.lastMessageKey)
        assertEquals("sync.reject.unknown_sku", source.rejectedRows(today).single().messageKey)
        assertEquals("unknown_sku", db.outboxDao().codeSummaries(10).single().lastCode)
    }

    /** Every code the contract can send, and the phone's own, has a Bangla and an English text; none falls back to a raw code. */
    @Test fun everyOutcomeCodeHasBanglaAndEnglishText() {
        val codes = com.aktcl.aron.contract.RecordOutcomeCode.entries.map { it.wire } +
            listOf("retry_exhausted", "discarded", "resolved_accepted", "resolved_accepted_with_fix", "resync", "digest_resend", "ack_mismatch", "unknown_status")
        val en = context
        val bn = com.aktcl.aron.core.ui.AppLocale.wrap(context, com.aktcl.aron.core.common.AppLanguage.BN)
        codes.forEach { c ->
            val id = en.resources.getIdentifier("submit_code_$c", "string", en.packageName)
            assertTrue("no text for $c", id != 0)
            val e = en.getString(id); val b = bn.getString(id)
            assertTrue("english empty for $c", e.isNotBlank()); assertTrue("bangla empty for $c", b.isNotBlank())
            assertTrue("bangla of $c is the English text", b != e)
            if ('_' in c) assertFalse("raw code leaks into the text of $c", e.contains(c))
        }
    }
}
