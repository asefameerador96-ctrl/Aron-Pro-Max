package com.aktcl.aron.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.DaySubmitEntity
import com.aktcl.aron.core.database.entity.DueCollectionEntity
import com.aktcl.aron.core.database.entity.OutletChangeRequestEntity
import com.aktcl.aron.core.database.entity.TaskEventEntity
import com.aktcl.aron.core.database.entity.VisitSkipEntity
import com.aktcl.aron.core.database.reference.BundleReference
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.ReferenceRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Checker (refuter) for b7046aa: Room v3 records and F-SYS-027. Each test is a reproducible defect. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CheckerV3Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    private val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }

    @After fun tearDown() = db.close()

    private fun outletRequest(type: String, outletId: Long?, photos: List<String> = emptyList()): Pair<OutletChangeRequestEntity, com.aktcl.aron.core.database.entity.GeoFixEntity> {
        val uuid = ClientIds.newUuid()
        val fix = TestRows.fix(uuid, "outlet_request")
        return OutletChangeRequestEntity(
            uuid, TestRows.meta(routeId = null), type, outletId, """{"name":"Rahim Store","owner_name":"Rahim"}""",
            fix.clientUuid, photos.joinToString(",", "[", "]") { "\"$it\"" },
        ) to fix
    }

    // Contract OutletRequestType = new|close|info|cluster|location|route_add; the server rejects anything else as schema_invalid.
    @Test fun outletRequestTypeOutsideTheContractEnumIsRefused() = runTest {
        val (req, fix) = outletRequest("new_outlet", null)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.recordOutletRequest(req, fix) } }
    }

    // Contract: outlet_id is "Null only for request_type new".
    @Test fun aNonNewOutletRequestNeedsItsOutlet() = runTest {
        val (req, fix) = outletRequest("location", null)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.recordOutletRequest(req, fix) } }
    }

    // Contract: photo_uuids maxItems 4, items Uuid.
    @Test fun outletRequestPhotosAreAtMostFourUuids() = runTest {
        val (req, fix) = outletRequest("new", null, List(5) { ClientIds.newUuid() })
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.recordOutletRequest(req, fix) } }
        val (bad, fix2) = outletRequest("new", null, listOf("not-a-uuid"))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.recordOutletRequest(bad, fix2) } }
    }

    // s4.2 rule 2 and the method's own KDoc: nothing of the route-day may be committed after day_submit.
    @Test fun nothingOfTheRouteDayIsCommittedAfterDaySubmit() = runTest {
        repo.recordDaySubmit(submit())
        val skip = VisitSkipEntity(ClientIds.newUuid(), TestRows.meta(), 50002, "shop_closed")
        assertThrows(IllegalStateException::class.java) { runBlocking { repo.recordVisitSkip(skip) } }
    }

    // A second submit of the same cycle is not a new cycle (contract: n+1 only after the server voided cycle n).
    @Test fun aRouteDayCannotBeSubmittedTwiceInOneCycle() = runTest {
        repo.recordDaySubmit(submit())
        assertThrows(IllegalStateException::class.java) { runBlocking { repo.recordDaySubmit(submit()) } }
    }

    // Contract DaySubmitPayload: scope route_day|supervisor_day, submit_cycle 1..50, counts >= 0.
    @Test fun daySubmitValuesOutsideTheContractAreRefused() = runTest {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.recordDaySubmit(submit(scope = "route")) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.recordDaySubmit(submit(cycle = 0)) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.recordDaySubmit(submit(pending = -1)) } }
    }

    // Contract: payment_mode enum [cash]; VisitSkipPayload.reason_code ^[a-z][a-z0-9_]{1,40}$.
    @Test fun enumAndPatternMembersAreCheckedBeforeQueueing() = runTest {
        val due = DueCollectionEntity(ClientIds.newUuid(), TestRows.meta(), 50001, ClientIds.newUuid(), "sr334001-261004-003", "2026-10-04", 50_000, true, 50_000, paymentMode = "bkash")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.recordDueCollection(due) } }
        val skip = VisitSkipEntity(ClientIds.newUuid(), TestRows.meta(), 50002, "Shop Closed")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.recordVisitSkip(skip) } }
    }

    // A task the server cancelled (or reopened) after acking our resolved event stays "completed" forever on the phone:
    // reapplyLocalResolutions looks at every resolved task_event ever written, not only the ones the server has not seen.
    @Test fun aServerStatusAfterOurResolutionIsAckedWins() = runTest {
        val task = ClientIds.newUuid()
        val ref = ReferenceRepository(db)
        apply(ref, bundleWithTask(task, "ongoing", "2026-10-05:3"))
        val ev = TaskEventEntity(ClientIds.newUuid(), TestRows.meta(routeId = null), task, "resolved")
        repo.resolveTask(ev, "2026-10-05T05:00:00.000Z")
        db.outboxDao().applyAck(ev.clientUuid, "acked", null, 1L, "2026-10-05T05:01:00.000Z")
        apply(ref, bundleWithTask(task, "cancelled", "2026-10-05:4"))
        assertEquals("cancelled", ref.tasks().single().status)
    }

    // Resolving a task the phone does not hold queues a task_event the server can only park (parent task_uuid missing).
    @Test fun resolvingAnUnknownTaskIsRefused() = runTest {
        val ev = TaskEventEntity(ClientIds.newUuid(), TestRows.meta(routeId = null), ClientIds.newUuid(), "resolved")
        assertThrows(IllegalStateException::class.java) { runBlocking { repo.resolveTask(ev, "2026-10-05T05:00:00.000Z") } }
    }

    private fun submit(scope: String = "route_day", cycle: Int = 1, pending: Int = 0) = DaySubmitEntity(
        ClientIds.newUuid(), TestRows.meta(), scope, cycle, """{"memo":0}""", MONEY, 0, 0, pending, false, 0, 0, true,
    )

    private suspend fun apply(ref: ReferenceRepository, raw: JsonObject) =
        ref.apply(BundleReference.json.decodeFromJsonElement(BundleReference.serializer(), raw), raw)

    private fun bundleWithTask(task: String, status: String, version: String): JsonObject {
        val base = Json.parseToJsonElement(javaClass.getResourceAsStream("/fixtures/sr_day_bundle.json")!!.readBytes().decodeToString()).jsonObject
        val meta = JsonObject(base["meta"]!!.jsonObject + ("bundle_version" to JsonPrimitive(version)))
        val tasks = Json.parseToJsonElement(
            """[{"task_uuid":"$task","task_type_code":"collect_due","title":"t","assignee_user_id":7,"assigned_by_user_id":8,"description":null,"outlet_id":50001,"due_date":"2026-10-06","status":"$status","created_at":"2026-10-04T04:00:00.000Z","resolved_at":null,"source":"web"}]""",
        )
        return JsonObject(base + ("meta" to meta) + ("tasks" to tasks))
    }

    private companion object {
        const val MONEY = """{"active_memo_count":0,"gross_mtk":0,"offer_discount_mtk":0,"drp_discount_mtk":0,"qc_deduction_mtk":0,"net_mtk":0,"paid_mtk":0,"due_mtk":0,"due_collected_mtk":0,"net_by_category_mtk":{},"issued_qty_base_by_sku":{},"sold_qty_base_by_sku":{}}"""
    }
}
