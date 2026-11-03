package com.aktcl.aron.tso

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.core.database.UserDatabases
import com.aktcl.aron.core.database.entity.BundleSectionEntity
import com.aktcl.aron.core.database.entity.OutboxState
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.sync.SyncScheduler
import com.aktcl.aron.feature.tso.leave.LeavePayload
import com.aktcl.aron.feature.tso.leave.LeaveReasonText
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * tso-manual X4b fix round (R-W-10 on the real data path): the leave card's reason for a refusal code without a leave text comes
 * from the stored bundle's `reason_texts`, read offline by [TsoReadModels.leave] `unsent()` on a real user database: the queued
 * application's mirror row, its outbox row as the sync engine leaves it after the server's answer (rejected or quarantined, with
 * the code), and the bundle section. A code the section does not word, or a section that cannot be read, gives no text (the
 * card then says the generic words) and never a crash; an application still waiting has no refusal at all.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class TsoLeaveUnsentTest {
    private val user = 7L
    private lateinit var databases: UserDatabases

    private val env = object : TsoEnv {
        override val client: AronApiClient get() = error("offline: no network on this phone")
        override fun nowMs() = 1_760_680_000_000L
        override fun elapsedRealtimeMs() = 5_000L
        override fun bootCount() = 3
        override fun clockOffsetMs(): Long? = 0L
        override fun businessDate() = "2026-10-17"
    }
    private val scheduler = object : SyncScheduler { override fun requestSync(userId: Long, trigger: SyncTrigger) = Unit }

    @Before fun open() { databases = UserDatabases(ApplicationProvider.getApplicationContext<Application>()) { null } }
    @After fun close() { databases.close(user) }

    private fun models() = TsoReadModels(env, databases, TsoOutbox(env, databases, scheduler) { false })

    private val refused = "6f1c0b9e-0000-4c4c-9a9a-0000000000a1"
    private val held = "6f1c0b9e-0000-4c4c-9a9a-0000000000a2"
    private val waiting = "6f1c0b9e-0000-4c4c-9a9a-0000000000a3"

    /** Three applications queued on the phone (outbox row and mirror row, one transaction each), then the server's answers. */
    private fun queueAndAnswer() = runBlocking {
        val queue = models().leaveQueue(user)
        assertTrue(queue.submit(refused, LeavePayload("casual", "2026-10-20", 1, "family")))
        assertTrue(queue.submit(held, LeavePayload("sick", "2026-10-21", 2, "fever")))
        assertTrue(queue.submit(waiting, LeavePayload("casual", "2026-10-24", 1, "wedding")))
        val box = databases.of(user).outboxDao()
        // what the sync engine writes for the server's acks: refused with payload_conflict, held for review with user_disabled
        assertEquals(1, box.applyAck(refused, OutboxState.REJECTED, "payload_conflict", null, "2026-10-17T05:00:00Z"))
        assertEquals(1, box.applyAck(held, OutboxState.QUARANTINED, "user_disabled", null, "2026-10-17T05:00:00Z"))
    }

    private fun section(json: String) = runBlocking {
        databases.of(user).referenceDao().insertSections(listOf(BundleSectionEntity("reason_texts", json)))
    }

    @Test fun aRefusalCodeIsWordedByTheStoredBundleReasonTexts() = runBlocking {
        section("""{"payload_conflict":{"bn":"একই আবেদন আগে পাঠানো হয়েছে।","en":"The same application was already sent."},"schema_invalid":{"bn":"ভুল তথ্য।","en":"Invalid data."}}""")
        queueAndAnswer()
        val items = models().leave(user).unsent().associateBy { it.leaveUuid }
        assertEquals(setOf(refused, held, waiting), items.keys)

        val r = items.getValue(refused)
        assertFalse(r.sent); assertEquals("payload_conflict", r.refusalCode); assertFalse(r.held)
        assertEquals(LeaveReasonText("একই আবেদন আগে পাঠানো হয়েছে।", "The same application was already sent."), r.refusalReason)

        // held for review with a code the section does not word: no text, the card says the generic words
        val h = items.getValue(held)
        assertEquals("user_disabled", h.refusalCode); assertTrue(h.held); assertNull(h.refusalReason)

        // still waiting for the server: no refusal at all
        val w = items.getValue(waiting)
        assertNull(w.refusalCode); assertFalse(w.held); assertNull(w.refusalReason)
    }

    @Test fun aCorruptOrMissingReasonTextsSectionGivesNoTextAndNoCrash() = runBlocking {
        queueAndAnswer()
        // no section yet (before the first bundle): the codes still show, without bundle words
        models().leave(user).unsent().associateBy { it.leaveUuid }.let { items ->
            assertEquals("payload_conflict", items.getValue(refused).refusalCode); assertNull(items.getValue(refused).refusalReason)
            assertEquals(3, items.size)
        }
        section("""{"payload_conflict":{"bn":"অর্ধেক""")
        val items = models().leave(user).unsent().associateBy { it.leaveUuid }
        assertEquals(3, items.size)
        assertEquals("payload_conflict", items.getValue(refused).refusalCode); assertNull(items.getValue(refused).refusalReason)
        assertEquals("user_disabled", items.getValue(held).refusalCode); assertTrue(items.getValue(held).held)
    }
}
