package com.aktcl.aron.tso

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.contract.RecordType
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.core.database.UserDatabases
import com.aktcl.aron.core.database.entity.OutboxEntity
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.sync.SyncScheduler
import com.aktcl.aron.feature.tso.feedback.FeedbackPayload
import com.aktcl.aron.feature.tso.leave.LeavePayload
import com.aktcl.aron.feature.tso.mycall.Criterion
import com.aktcl.aron.feature.tso.mycall.PlanIds
import com.aktcl.aron.feature.tso.mycall.PlanSave
import com.aktcl.aron.feature.tso.mycall.QueryIds
import com.aktcl.aron.feature.tso.mycall.QuerySubmit
import com.aktcl.aron.feature.tso.mycall.Rubric
import com.aktcl.aron.feature.tso.mycall.TaskSubmit
import com.aktcl.aron.feature.tso.StableIds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * DEFINITION OF DONE for every TSO write (CLAUDE.md): start the write, DROP the process state (the open database is closed and
 * reopened from disk, every host object is rebuilt), finish or replay the same client uuid, and prove exactly ONE outbox row,
 * ONE payload (byte-identical), the mirror row unchanged and one sync request per real change. A real file database is used, so
 * "kill and relaunch" really goes through the disk.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class TsoWriteReplayTest {
    private val user = 7L
    private lateinit var databases: UserDatabases
    private var nowMs = 1_760_000_000_000L
    private var syncs = 0

    private val env = object : TsoEnv {
        override val client: AronApiClient get() = error("no network in a write test")
        override fun nowMs() = nowMs
        override fun elapsedRealtimeMs() = 5_000L
        override fun bootCount() = 3
        override fun clockOffsetMs(): Long? = 0L
        override fun businessDate() = "2026-10-17"
    }
    private val scheduler = object : SyncScheduler { override fun requestSync(userId: Long, trigger: SyncTrigger) { syncs++ } }

    @Before fun open() { databases = UserDatabases(ApplicationProvider.getApplicationContext<Application>()) { null } }
    @After fun close() { databases.close(user) }

    /** The process dies: the database handle and every host object are gone; the next launch opens the same file again. */
    private fun relaunch() { databases.close(user); databases.allowOpen(user); databases = UserDatabases(ApplicationProvider.getApplicationContext<Application>()) { null } }

    private fun outbox() = TsoOutbox(env, databases, scheduler) { false }
    private fun models() = TsoReadModels(env, databases, outbox())

    private fun rows(): List<OutboxEntity> = runBlocking { databases.of(user).outboxDao().nextPending(10_000) }
    private fun rowsOf(type: RecordType) = rows().filter { it.recordType == type.wire }
    private fun mirror(prefix: String): Map<String, String> = runBlocking { databases.of(user).referenceDao().metaWithPrefix(prefix).associate { it.key to it.value } }

    private val uuid = StableIds.v4("test-leave-1")

    // ---------- leave ----------
    @Test fun leaveSurvivesRelaunchAndReplayIsOneRowOnePayload() = runBlocking {
        val p = LeavePayload("sick", "2026-10-20", 2, "fever")
        assertTrue(models().leaveQueue(user).submit(uuid, p))
        val before = rowsOf(RecordType.LEAVE_APPLICATION).single(); val mirrorBefore = mirror("tso.unsent.leave.")
        assertEquals(1, mirrorBefore.size); assertEquals(1, syncs)

        relaunch()
        // after the kill: the row and its mirror are on disk exactly as they were
        assertEquals(before.payloadJson, rowsOf(RecordType.LEAVE_APPLICATION).single().payloadJson)
        assertEquals(mirrorBefore, mirror("tso.unsent.leave."))

        // the screen is rebuilt and the same form is sent again with the same client uuid (retry after the kill)
        assertFalse("a replay adds nothing", models().leaveQueue(user).submit(uuid, p))
        assertEquals(1, rowsOf(RecordType.LEAVE_APPLICATION).size)
        assertEquals(before.payloadJson, rowsOf(RecordType.LEAVE_APPLICATION).single().payloadJson); assertEquals(before.payloadSha256, rowsOf(RecordType.LEAVE_APPLICATION).single().payloadSha256)
        assertEquals(mirrorBefore, mirror("tso.unsent.leave.")); assertEquals("no second sync request for a no-op", 1, syncs)
        val rec = JSONObject(before.payloadJson)
        assertEquals("leave_application", rec.getString("type")); assertEquals(uuid, rec.getString("client_uuid")); assertEquals(uuid, rec.getString("family_uuid")); assertEquals(0, rec.getInt("rank"))
        assertEquals("2026-10-17", rec.getString("business_date")); assertFalse(rec.getJSONObject("payload").has("to_date"))
    }

    // ---------- feedback ----------
    @Test fun feedbackSurvivesRelaunchAndReplay() = runBlocking {
        val id = StableIds.v4("test-fb-1"); val p = FeedbackPayload("suggestion", "Faster", "Sync is slow", null)
        assertTrue(models().feedbackQueue(user).submit(id, "2026-10-17T10:00:00Z", p))
        val before = rowsOf(RecordType.FEEDBACK).single(); val mirrorBefore = mirror("tso.unsent.feedback.")
        relaunch()
        assertFalse(models().feedbackQueue(user).submit(id, "2026-10-17T10:05:00Z", p)) // a later createdAt must not change anything
        assertEquals(before.payloadJson, rowsOf(RecordType.FEEDBACK).single().payloadJson); assertEquals(1, rowsOf(RecordType.FEEDBACK).size); assertEquals(mirrorBefore, mirror("tso.unsent.feedback."))
        assertTrue(JSONObject(before.payloadJson).getJSONObject("payload").isNull("photo_uuid"))
    }

    // ---------- visit plan (a union) ----------
    @Test fun planUnionAcrossRelaunchesNeverDuplicatesAndDoesNotRewriteOldRows() = runBlocking {
        val src = object : com.aktcl.aron.feature.tso.mycall.PlanSource {
            override suspend fun routes() = null
            override suspend fun outlets(routeId: Long) = null
            override suspend fun plans(date: String) = models().plan(user).plans(date)
            override suspend fun locallyDone(planUuid: String) = emptySet<Long>()
            override suspend fun taskPending(planUuid: String) = emptySet<Long>()
        }
        val date = "2026-10-18"; val planUuid = PlanIds.plan(user, date, 1)
        assertEquals(2, models().planQueue(user, src).save(PlanSave(date, 1, setOf(1, 2))))
        val first = rows().associateBy { it.clientUuid }
        assertEquals(1, rowsOf(RecordType.VISIT_PLAN).size); assertEquals(2, rowsOf(RecordType.VISIT_PLAN_OUTLET).size)

        relaunch()
        // a second save adds only the new outlet 3; outlets 1 and 2 and the plan row are NOT written again
        assertEquals(1, models().planQueue(user, src).save(PlanSave(date, 1, setOf(2, 3)))) // only outlet 3 is new (the screen shows this count)
        assertEquals(1, rowsOf(RecordType.VISIT_PLAN).size); assertEquals(3, rowsOf(RecordType.VISIT_PLAN_OUTLET).size)
        for ((k, v) in first) assertEquals("old row untouched", v.payloadJson, rows().first { it.clientUuid == k }.payloadJson)

        // the exact same save again, after another relaunch: nothing at all
        relaunch(); val count = rows().size; val s = syncs
        models().planQueue(user, src).save(PlanSave(date, 1, setOf(1, 2, 3)))
        assertEquals(count, rows().size); assertEquals("a pure replay asks for no sync", s, syncs)
        val outletRows = rowsOf(RecordType.VISIT_PLAN_OUTLET)
        assertTrue(outletRows.all { it.familyUuid == planUuid && it.rank == 1 }); assertEquals(setOf(1L, 2L, 3L), outletRows.map { JSONObject(it.payloadJson).getJSONObject("payload").getLong("outlet_id") }.toSet())
        assertEquals(0, rowsOf(RecordType.VISIT_PLAN).single().rank)
    }

    // ---------- visit query ----------
    @Test fun queryIsOneAssessmentWithItsAnswersAndReplayChangesNothing() = runBlocking {
        val rubric = Rubric(5, 1, listOf(Criterion(1, null, "Q1"), Criterion(2, null, "Q2")))
        val q = QuerySubmit("p-1", 9, 1, rubric, mapOf(1L to true, 2L to false), delegate = true)
        assertTrue(models().queryQueue(user).submit(q))
        assertEquals(1, rowsOf(RecordType.CALL_ASSESSMENT).size); assertEquals(2, rowsOf(RecordType.CALL_ASSESSMENT_ANSWER).size)
        val before = rows().associateBy { it.clientUuid }; val done = mirror("tso.visit.done."); val pending = mirror("tso.task.pending.")
        assertEquals(1, done.size); assertEquals(1, pending.size)

        relaunch()
        assertFalse(models().queryQueue(user).submit(q))
        assertEquals(before.size, rows().size); for ((k, v) in before) assertEquals(v.payloadJson, rows().first { it.clientUuid == k }.payloadJson)
        assertEquals(done, mirror("tso.visit.done.")); assertEquals(pending, mirror("tso.task.pending."))
        val a = rowsOf(RecordType.CALL_ASSESSMENT).single()
        assertTrue(rowsOf(RecordType.CALL_ASSESSMENT_ANSWER).all { it.familyUuid == a.clientUuid && it.rank == 1 })
        assertEquals(PlanIds.planOutlet("p-1", 9), JSONObject(a.payloadJson).getJSONObject("payload").getString("visit_plan_outlet_client_uuid"))
        assertEquals(QueryIds.assessment("p-1", 9), a.clientUuid)
    }

    // ---------- assigned task ----------
    @Test fun taskIsOneRowAndTheDelegatePendingFlagClearsOnceNotTwice() = runBlocking {
        // the Visit Query that asked for it
        models().queryQueue(user).submit(QuerySubmit("p-1", 9, 1, Rubric(5, 1, listOf(Criterion(1, null, "Q1"))), mapOf(1L to true), delegate = true))
        assertEquals(1, mirror("tso.task.pending.").size)
        val taskUuid = QueryIds.task("p-1", 9)
        val t = TaskSubmit("p-1", taskUuid, "irregular_visit", "Irregular Visit", 77, 1, 9, "2026-10-20", "go")
        assertTrue(models().taskQueue(user).create(t))
        val before = rowsOf(RecordType.TASK).single(); assertEquals(0, mirror("tso.task.pending.").size)

        relaunch()
        models().taskQueue(user).create(t) // the rep opened Assign Task again after the kill: same deterministic uuid
        assertEquals(1, rowsOf(RecordType.TASK).size); assertEquals(before.payloadJson, rowsOf(RecordType.TASK).single().payloadJson)
        assertEquals(77L, JSONObject(before.payloadJson).getJSONObject("payload").getLong("assignee_user_id"))
    }

    // ---------- password change: online only, nothing is queued, nothing is replayed after Done ----------
    @Test fun passwordChangeNeverTouchesTheOutboxAndIsSentOnceThenSealed() = runBlocking {
        var calls = 0
        val api = com.aktcl.aron.feature.tso.password.ChangePasswordApi { _, _ -> calls++; com.aktcl.aron.feature.tso.password.CpOutcome.Ok }
        val s = com.aktcl.aron.feature.tso.password.ChangePasswordState(api)
        s.setCurrent("old-pass-1"); s.setNew("new-pass-22"); s.setConfirm("new-pass-22"); s.submit(); s.submit()
        assertEquals(1, calls); assertTrue(rows().isEmpty())
        // the process dies: the new screen starts empty (no secret was kept) and sends nothing by itself
        val s2 = com.aktcl.aron.feature.tso.password.ChangePasswordState(api)
        assertEquals("", s2.ui.value.current); assertEquals("", s2.ui.value.new); assertEquals(1, calls)
    }

    // ---------- TsoOutbox itself: duplicate batch, out of order, atomicity ----------
    @Test fun aDuplicateBatchAddsNothingAndKeepsCommitOrder() = runBlocking {
        val ob = outbox()
        val recs = (1..3).map { TsoOutbox.Rec(RecordType.FEEDBACK, StableIds.v4("b$it"), buildJsonObject { put("category_code", JsonPrimitive("suggestion")); put("title", JsonPrimitive("t$it")); put("description", JsonPrimitive("d")) }) }
        assertEquals(3, ob.enqueueAll(user, recs)); val seqs = rows().map { it.seq }
        assertEquals(0, ob.enqueueAll(user, recs)); assertEquals(seqs, rows().map { it.seq }); assertEquals(1, syncs)
        relaunch(); assertEquals(0, outbox().enqueueAll(user, recs)); assertEquals(3, rows().size)
        assertEquals(seqs.sorted(), seqs)
    }

    @Test fun outOfOrderChildBeforeParentIsStoredWithItsRankAndFamilyAndTheParentCanArriveLater() = runBlocking {
        val ob = outbox(); val parent = StableIds.v4("parent"); val child = StableIds.v4("child")
        val childRec = TsoOutbox.Rec(RecordType.VISIT_PLAN_OUTLET, child, buildJsonObject { put("plan_client_uuid", JsonPrimitive(parent)); put("outlet_id", JsonPrimitive(5)) }, familyUuid = parent, rank = 1)
        val parentRec = TsoOutbox.Rec(RecordType.VISIT_PLAN, parent, buildJsonObject { put("plan_date", JsonPrimitive("2026-10-18")) })
        assertEquals(1, ob.enqueueAll(user, listOf(childRec)))
        relaunch()
        assertEquals(1, outbox().enqueueAll(user, listOf(parentRec, childRec))) // the child is a duplicate, only the parent is new
        val all = rows(); assertEquals(2, all.size)
        val c = all.first { it.clientUuid == child }; val p = all.first { it.clientUuid == parent }
        assertEquals(parent, c.familyUuid); assertEquals(1, c.rank); assertEquals(parent, p.familyUuid); assertEquals(0, p.rank)
        assertTrue("commit order is kept: the child was committed first", c.seq < p.seq) // the sync engine orders by family and rank, not by seq (docs/24 s4.2)
    }

    @Test fun aBatchThatFailsHalfWayLeavesNothingBehind() = runBlocking {
        val ob = outbox(); val ok = TsoOutbox.Rec(RecordType.FEEDBACK, StableIds.v4("ok"), buildJsonObject { put("title", JsonPrimitive("x")) })
        // an envelope that cannot be built (a business date the clock refuses) aborts the whole transaction
        val bad = object : TsoEnv by env { var n = 0; override fun businessDate(): String { if (++n == 2) error("clock refused"); return "2026-10-17" } }
        val failing = TsoOutbox(bad, databases, scheduler) { false }
        val second = TsoOutbox.Rec(RecordType.FEEDBACK, StableIds.v4("second"), buildJsonObject { put("title", JsonPrimitive("y")) })
        val failed = runCatching { failing.enqueueAll(user, listOf(ok, second), listOf("tso.test.mirror" to "1")) }
        assertTrue(failed.isFailure); assertTrue("neither record is stored", rows().isEmpty()); assertTrue("no mirror either", mirror("tso.test.").isEmpty()); assertEquals(0, syncs)
        // and the same batch goes through afterwards, once
        assertEquals(2, outbox().enqueueAll(user, listOf(ok, second), listOf("tso.test.mirror" to "1"))); assertEquals(1, mirror("tso.test.").size)
    }

    @Test fun everyStoredRecordIsAnEnvelopeThatCarriesUuidV4AndTheTrustedClock() {
        runBlocking { models().leaveQueue(user).submit(uuid, LeavePayload("casual", "2026-10-20", 1, "x")) }
        val o = JSONObject(rows().single().payloadJson)
        assertTrue(StableIds.isV4(o.getString("client_uuid"))); assertEquals(5_000L, o.getLong("captured_elapsed_ms")); assertEquals(3, o.getInt("boot_count")); assertTrue(o.getBoolean("captured_offline"))
        assertNotNull(o.opt("config_version")); assertNull(o.opt("route_id"))
    }
}
