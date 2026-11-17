package com.aktcl.aron.feature.dayclose

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.OutboxEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Device defect D1 (2026-11-13) and the lead's round-2 findings D1-1..D1-7: Sales Submit says what was refused, why and what to do, from ONE
 * truth. These tests drive the real Room source and the real view model.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RefusedRecordsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    private val source = RoomDaySource(db)
    private val today = "2026-11-13"

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() { Dispatchers.resetMain(); db.close() }

    /** Room answers on its own executor, so the view model finishes off the test thread: wait in REAL time for the condition. */
    private fun await(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond()) { check(System.currentTimeMillis() < end) { "timed out waiting for $what" }; Thread.sleep(10) }
    }

    private suspend fun row(date: String, state: String, code: String?, type: String = "memo_line", key: String? = null, family: String = ClientIds.newUuid(), rank: Int = 1): String {
        val uuid = ClientIds.newUuid()
        db.outboxDao().insert(listOf(OutboxEntity(
            clientUuid = uuid, recordType = type, familyUuid = family, rank = rank, businessDate = date, payloadJson = "{\"payload\":{\"qty\":1}}", payloadSha256 = "x",
            state = state, lastCode = code, createdAt = "$date" + "T05:00:00.000Z", lastMessageKey = key,
        )))
        return uuid
    }

    // ---- the count and the list are one truth

    @Test fun theCountAndTheListAreTheSameQueryOverEveryDate() = runTest {
        row(today, "rejected", "price_invalid")
        row("2026-10-01", "rejected", "unknown_sku")          // six weeks older than the old 14-day list reached
        row("2026-10-02", "quarantined", "memo_no_duplicate")
        row(today, "acked", null)
        val states = source.outboxStates(today)
        val listed = source.rejectedRows(today)
        assertEquals(2, states.rejected); assertEquals(1, states.quarantined)
        assertEquals("every counted record is listed", states.rejected + states.quarantined, listed.sumOf { it.familySize })
        assertEquals(setOf("2026-10-01", "2026-10-02", today), listed.map { it.businessDate }.toSet())
    }

    // ---- D1-1: "seen" is a screen matter only; the day_submit and the gates read the TRUE states

    @Test fun markingRecordsSeenNeverChangesWhatTheServerIsTold() = runTest {
        val a = row(today, "rejected", "price_invalid"); val b = row(today, "rejected", "unknown_sku"); val c = row(today, "quarantined", "memo_no_duplicate")
        listOf(a, b, c).forEach { source.markSeen(it) }
        assertEquals("the gates still see all three", 2, source.outboxStates(today).rejected)
        assertEquals(1, source.outboxStates(today).quarantined)
        // end to end: the view model's screen gate drops them, the upload gate and the day_submit keep them
        val repo = CaptureRepository(db) { "2026-11-13T11:30:00.000Z" }
        val meta = CaptureMeta(today, "2026-11-13T04:31:07.120Z", 1, 1, 0, true, 10231, null, "b", false, 1)
        val writer = RoomDaySubmitWriter(db, repo, { meta }, { "cigarette" })
        val vm = SalesSubmitViewModel(1, { today }, source, { source.deviceCounts(today) }, writer, SyncScheduler.None, { true })
        vm.refresh(); await("the first read") { vm.state.value.counts.isNotEmpty() || vm.state.value.uploadGate.notes.isNotEmpty() }
        val st = vm.state.value
        assertTrue("the screen's count excludes the seen ones", st.gate.notes.none { it is SubmitBlock.Rejected || it is SubmitBlock.Quarantined })
        assertEquals("the upload gate keeps the true counts", 2, st.uploadGate.notes.filterIsInstance<SubmitBlock.Rejected>().single().count)
        vm.submit(); await("the day_submit") { db.query("SELECT COUNT(*) FROM day_submit", null).use { it.moveToFirst(); it.getInt(0) } > 0 }
        val submit = db.captureDao().daySubmitsOn(today).single()
        assertEquals("the day_submit still says what really happened", 2, submit.rejectedCount); assertEquals(1, submit.quarantinedCount)
    }

    // ---- D1-2: a record the phone gave up on is not "seen"; its only action is Send again

    @Test fun aRecordThePhoneGaveUpOnCannotBeMarkedSeen() = runTest {
        val u = row(today, "rejected", "retry_exhausted")
        assertEquals(0, db.outboxDao().markRefusedSeen(u, "t"))
        assertNull(db.outboxDao().byClientUuid(u)!!.seenAt)
        val entry = source.rejectedRows(today).single()
        assertEquals(RejectedOutcome.PHONE_GAVE_UP, entry.outcome)
        assertFalse(entry.canMarkSeen); assertTrue(entry.canSendAgain)
    }

    // ---- D1-3: seen belongs to ONE outcome; a new outcome shows again

    @Test fun seenIsClearedWhenTheOutcomeChanges() = runTest {
        val q = row(today, "quarantined", "memo_no_duplicate")
        source.markSeen(q); assertNotNull(db.outboxDao().byClientUuid(q)!!.seenAt)
        // the same outcome answered again keeps it
        db.outboxDao().applyAck(q, "quarantined", "memo_no_duplicate", null, "t")  // not in flight/pending: no change at all
        assertNotNull(db.outboxDao().byClientUuid(q)!!.seenAt)
        // a reviewer discards it: a NEW outcome, shown again
        assertEquals(1, db.outboxDao().applyResolution(q, "rejected", "discarded", "t"))
        assertNull("a new outcome clears seen", db.outboxDao().byClientUuid(q)!!.seenAt)
        assertEquals(0, source.seenRefused().rejected)
        // released from quarantine (device_integrity_failed) also clears it
        val r = row(today, "quarantined", "device_integrity_failed")
        source.markSeen(r); db.outboxDao().releaseQuarantined("device_integrity_failed")
        assertNull(db.outboxDao().byClientUuid(r)!!.seenAt)
    }

    @Test fun aRefusalOfADifferentCodeClearsSeenToo() = runTest {
        val u = row(today, "in_flight", null)
        db.outboxDao().applyAck(u, "rejected", "price_invalid", null, "t")
        source.markSeen(u)
        // the same row is put back and refused again for ANOTHER reason (a resend after a restore)
        db.openHelper.writableDatabase.execSQL("UPDATE outbox SET state = 'in_flight' WHERE client_uuid = '$u'")
        db.outboxDao().applyAck(u, "rejected", "unknown_sku", null, "t")
        assertNull(db.outboxDao().byClientUuid(u)!!.seenAt)
    }

    @Test fun seenCanBeUndone() = runTest {
        val u = row(today, "rejected", "price_invalid")
        source.markSeen(u); assertEquals(1, source.seenRefused().rejected)
        source.markUnseen(u)
        assertEquals(0, source.seenRefused().rejected); assertFalse(source.rejectedRows(today).single().seen)
    }

    // ---- D1-4: one guarded read; the gates fail closed

    @Test fun aDamagedReadIsFlaggedAndSubmitIsNotOffered() = runTest {
        val broken = object : DaySource by source {
            override suspend fun outboxStates(businessDate: String): OutboxStates = throw IllegalStateException("database disk image is malformed")
        }
        val queued = ArrayList<SubmitGate>()
        val vm = SalesSubmitViewModel(1, { today }, broken, { mapOf("memo" to 1) }, { _, g, _ -> queued += g }, SyncScheduler.None, { true })
        vm.refresh(); await("the failed read") { vm.state.value.rejectedReadFailed }
        assertTrue(vm.state.value.rejectedReadFailed)
        assertFalse("Submit is not offered on data the phone could not read", vm.state.value.gate.enabled)
        assertFalse(vm.state.value.uploadGate.enabled)
        assertTrue(vm.state.value.gate.blocks.single() is SubmitBlock.Unreadable)
        vm.submit(); assertTrue(queued.isEmpty())
    }

    // ---- D1-5: Send again acts on the whole family, with the same ids

    @Test fun sendAgainRequeuesTheWholeFamilyWithTheSameUuids() = runTest {
        val family = ClientIds.newUuid()
        val memo = row(today, "rejected", "retry_exhausted", "memo", family = family, rank = 1)
        val l1 = row(today, "rejected", "retry_exhausted", "memo_line", family = family, rank = 2)
        val l2 = row(today, "rejected", "retry_exhausted", "memo_line", family = family, rank = 2)
        val other = row(today, "rejected", "price_invalid", "memo_line", family = ClientIds.newUuid())
        val entries = source.rejectedRows(today)
        val e = entries.first { it.clientUuid == memo }
        assertEquals("the family is ONE entry led by its parent", 3, e.familySize)
        assertEquals(2, entries.size)
        assertTrue(source.sendAgain(l1)) // from ANY member of the family
        for (u in listOf(memo, l1, l2)) {
            val back = db.outboxDao().byClientUuid(u)!!
            assertEquals("pending", back.state); assertEquals(0, back.attempts); assertEquals(u, back.clientUuid)
        }
        assertEquals("a server refusal is never resent from here", "rejected", db.outboxDao().byClientUuid(other)!!.state)
        assertEquals(4, db.query("SELECT COUNT(*) FROM outbox", null).use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun markingAFamilySeenMarksItsRefusedRowsTogether() = runTest {
        val family = ClientIds.newUuid()
        val a = row(today, "rejected", "price_invalid", "memo", family = family, rank = 1); val b = row(today, "rejected", "price_invalid", "memo_line", family = family, rank = 2)
        source.markSeen(b)
        assertNotNull(db.outboxDao().byClientUuid(a)!!.seenAt); assertNotNull(db.outboxDao().byClientUuid(b)!!.seenAt)
        assertEquals(2, source.seenRefused().rejected)
    }

    // ---- the ack's message_key, kept beside the code

    @Test fun theAckMessageKeyIsKeptWithTheCode() = runTest {
        val u = row(today, "in_flight", null)
        db.outboxDao().applyAck(u, "rejected", "unknown_sku", null, "2026-11-13T05:00:00.000Z", "sync.reject.unknown_sku")
        val r = db.outboxDao().byClientUuid(u)!!
        assertEquals("unknown_sku", r.lastCode); assertEquals("sync.reject.unknown_sku", r.lastMessageKey)
        assertEquals("sync.reject.unknown_sku", source.rejectedRows(today).single().messageKey)
        assertEquals("unknown_sku", db.outboxDao().codeSummaries(10).single().lastCode)
    }

    // ---- the list is a bounded read

    @Test fun theListReadsOnlyAPageOfRowsAndTheCountStaysTrue() = runTest {
        repeat(250) { row(today, "rejected", "price_invalid") }
        val page = source.rejectedPage(today, 100)
        assertEquals(100, page.size)
        assertEquals("a COUNT, not the list, says how many exist", 250, source.refusedTotal())
        assertEquals(250, source.outboxStates(today).rejected)
    }

    // ---- the words

    @Test fun everyOutcomeCodeHasBanglaAndEnglishTextFromTheStaticTable() {
        val codes = com.aktcl.aron.contract.RecordOutcomeCode.entries.map { it.wire } +
            listOf("retry_exhausted", "discarded", "resolved_accepted", "resolved_accepted_with_fix", "resync", "digest_resend", "ack_mismatch", "unknown_status:x", "http_503",
                "transport_offline", "transport_timeout", "transport_edge_response", "transport_malformed", "assembly_race")
        val bn = com.aktcl.aron.core.ui.AppLocale.wrap(context, com.aktcl.aron.core.common.AppLanguage.BN)
        codes.forEach { c ->
            val id = OutcomeCodeTexts.of(c)
            assertNotEquals("no text for $c", com.aktcl.aron.feature.dayclose.R.string.submit_code_other, id)
            val e = context.getString(id); val b = bn.getString(id)
            assertTrue("english empty for $c", e.isNotBlank()); assertTrue("bangla empty for $c", b.isNotBlank())
            assertTrue("bangla of $c is the English text", b != e)
            if ('_' in c) assertFalse("raw code leaks into the text of $c", e.contains(c))
        }
        assertEquals("an unknown code reads as a sentence, never a raw code", R.string.submit_code_other, OutcomeCodeTexts.of("something_new"))
    }

    @Test fun theDroppedTextSaysTheRecordIsNotOnTheServerAndWhatToDo() {
        val bn = com.aktcl.aron.core.ui.AppLocale.wrap(context, com.aktcl.aron.core.common.AppLanguage.BN)
        val en = context.getString(R.string.submit_rejected_dropped)
        assertTrue(en.contains("NOT on the server")); assertTrue(en.contains("TSO or AMO")); assertTrue(en.contains("Memo screen"))
        assertTrue(bn.getString(R.string.submit_rejected_dropped).contains("সার্ভারে নেই"))
        val again = context.getString(R.string.submit_rejected_gave_up)
        assertFalse("no unprovable claim", again.contains("cannot be doubled")); assertTrue(again.contains("keeps it once"))
        assertTrue("a phone clock AHEAD is named, not only 'too old'", context.getString(R.string.submit_code_business_date_out_of_window).contains("clock is ahead"))
        assertTrue(context.getString(R.string.submit_code_memo_no_invalid).contains("number block"))
    }
}
