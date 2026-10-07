package com.aktcl.aron.core.database

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.DaySubmitEntity
import com.aktcl.aron.core.database.entity.DueCollectionEntity
import com.aktcl.aron.core.database.entity.OutletChangeRequestEntity
import com.aktcl.aron.core.database.entity.StockMovementEntity
import com.aktcl.aron.core.database.entity.TaskEventEntity
import com.aktcl.aron.core.database.entity.VisitSkipEntity
import com.aktcl.aron.core.database.reference.BundleReference
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.MemoNumbering
import com.aktcl.aron.core.database.repo.MemoNumbers
import com.aktcl.aron.core.database.repo.MemoSeqExhausted
import com.aktcl.aron.core.database.repo.ReferenceRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Room v3 records (requests of android-sr-a and android-sr-b) and memo numbering (F-SYS-027). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class DayRecordsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    private val repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }

    @After fun tearDown() = db.close()

    private suspend fun outboxOf(uuid: String) = db.outboxDao().byClientUuid(uuid)!!
    private fun payload(json: String) = Json.parseToJsonElement(json).jsonObject["payload"]!!.jsonObject

    @Test fun aDueCollectedDuringAVisitJoinsItsFamilyAndOneOutsideIsItsOwn() = runTest {
        val (visit, fix) = TestRows.visit()
        repo.recordVisitOpen(visit, fix)
        val memo = ClientIds.newUuid()
        val inVisit = DueCollectionEntity(ClientIds.newUuid(), TestRows.meta(), 50001, memo, "sr334001-261004-003", "2026-10-04", 50_000, false, 120_000, visitClientUuid = visit.clientUuid)
        repo.recordDueCollection(inVisit)
        assertEquals(inVisit.clientUuid, outboxOf(inVisit.clientUuid).familyUuid) // its own family; the visit is a parent reference
        assertEquals(0, outboxOf(inVisit.clientUuid).rank)
        assertEquals(visit.clientUuid, payload(outboxOf(inVisit.clientUuid).payloadJson)["visit_client_uuid"]!!.jsonPrimitive.content)
        val p = payload(outboxOf(inVisit.clientUuid).payloadJson)
        assertEquals("cash", p["payment_mode"]!!.jsonPrimitive.content)
        assertTrue(!p.containsKey("fix"))

        val alone = inVisit.copy(clientUuid = ClientIds.newUuid(), visitClientUuid = null, amountMtk = 70_000, outstandingBeforeMtk = 70_000, isFullSettlement = true)
        repo.recordDueCollection(alone)
        assertEquals(alone.clientUuid, outboxOf(alone.clientUuid).familyUuid)
        assertEquals(2, db.captureDao().dueCollectionsOf(memo).size)
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.recordDueCollection(alone.copy(clientUuid = ClientIds.newUuid(), amountMtk = 9)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.recordDueCollection(alone.copy(clientUuid = ClientIds.newUuid(), amountMtk = 80_000)) }
        }
    }

    @Test fun aSkipHasNoFixAndDaySubmitIsTheLastRecordOfItsRouteDay() = runTest {
        val skip = VisitSkipEntity(ClientIds.newUuid(), TestRows.meta(), 50002, "shop_closed")
        repo.recordVisitSkip(skip)
        assertEquals(setOf("outlet_id", "reason_code"), payload(outboxOf(skip.clientUuid).payloadJson).keys)
        val submit = DaySubmitEntity(
            ClientIds.newUuid(), TestRows.meta(), "route_day", 1, """{"memo":12,"visit":30}""", MONEY, 0, 0, 0, true, 120_000, 2, true,
        )
        repo.recordDaySubmit(submit)
        val row = outboxOf(submit.clientUuid)
        assertEquals(0, row.rank)
        assertEquals(12, payload(row.payloadJson)["device_counts"]!!.jsonObject["memo"]!!.jsonPrimitive.content.toInt())
        assertEquals(row.seq, db.outboxDao().nextPending(100).last().seq)
    }

    @Test fun aSubmittedRouteDayRefusesNewVisitsUntilTheServerReopensIt() = runTest {
        repo.recordDaySubmit(DaySubmitEntity(ClientIds.newUuid(), TestRows.meta(), "route_day", 1, "{}", MONEY, 0, 0, 0, false, 0, 0, false))
        val (visit, fix) = TestRows.visit()
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { repo.recordVisitOpen(visit, fix) } }
        repo.reopenRouteDay("2026-10-05", TestRows.meta().routeId, voidedCycle = 1)
        repo.recordVisitOpen(visit, fix)
        repo.recordDaySubmit(DaySubmitEntity(ClientIds.newUuid(), TestRows.meta(), "route_day", 2, "{}", MONEY, 0, 0, 0, false, 0, 0, false))
        val (v2, f2) = TestRows.visit()
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { repo.recordVisitOpen(v2, f2) } }
    }

    @Test fun anOutletRequestCarriesItsFixPhotosAndProposal() = runTest {
        val uuid = ClientIds.newUuid()
        val fix = TestRows.fix(uuid, "outlet_request")
        val req = OutletChangeRequestEntity(
            uuid, TestRows.meta(routeId = null), "new", null, """{"name":"রহিম স্টোর","owner_name":"রহিম","lat":23.79,"lng":90.40}""",
            fix.clientUuid, """["${ClientIds.newUuid()}"]""",
        )
        repo.recordOutletRequest(req, fix)
        val p = payload(outboxOf(uuid).payloadJson)
        assertEquals("রহিম স্টোর", p["proposed"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(1, (p["photo_uuids"] as kotlinx.serialization.json.JsonArray).size)
        assertTrue(p["fix"] is JsonObject)
        assertThrows(SQLiteConstraintException::class.java) { kotlinx.coroutines.runBlocking { repo.recordOutletRequest(req, fix) } }
    }

    @Test fun resolvingATaskCompletesItLocallyAndSurvivesTheNextBundle() = runTest {
        val task = ClientIds.newUuid()
        val raw = bundleWithTask(task)
        val ref = ReferenceRepository(db)
        ref.apply(BundleReference.json.decodeFromJsonElement(BundleReference.serializer(), raw), raw)
        assertEquals("ongoing", ref.tasks().single().status)
        val ev = TaskEventEntity(ClientIds.newUuid(), TestRows.meta(routeId = null), task, "resolved", "done")
        repo.resolveTask(ev, "2026-10-05T05:00:00.000Z")
        assertEquals("completed", ref.tasks().single().status)
        assertEquals("task_event", outboxOf(ev.clientUuid).recordType)
        val again = bundleWithTask(task, version = "2026-10-05:4") // the server has not seen the event yet
        ref.apply(BundleReference.json.decodeFromJsonElement(BundleReference.serializer(), again), again)
        assertEquals("completed", ref.tasks().single().status)
        assertNull(ref.section("tasks")) // tasks live in their own table
    }

    @Test fun memoNumbersUseTheDeviceBlockThenOverflowThenStop() = runTest {
        val numbers = MemoNumbers(db)
        val n0 = MemoNumbering("sr334001", bindOrdinal = 0)
        assertEquals("sr334001-261005-001", numbers.reserve("2026-10-05", n0))
        assertEquals("sr334001-261005-002", numbers.reserve("2026-10-05", n0))
        assertEquals("sr334001-261006-001", numbers.reserve("2026-10-06", n0)) // per business date
        assertEquals(1001, MemoNumbers.seqOf(1, MemoNumbering("sr334001", 2)))
        assertEquals(1500, MemoNumbers.seqOf(500, MemoNumbering("sr334001", 2)))
        assertEquals(7001, MemoNumbers.seqOf(501, MemoNumbering("sr334001", 2)))
        assertEquals(7999, MemoNumbers.seqOf(1499, MemoNumbering("sr334001", 2)))
        assertNull(MemoNumbers.seqOf(1500, MemoNumbering("sr334001", 2)))
        db.referenceDao().putMemoCounter(com.aktcl.aron.core.database.entity.MemoCounterEntity("2026-10-07", 1499))
        assertThrows(MemoSeqExhausted::class.java) { kotlinx.coroutines.runBlocking { numbers.reserve("2026-10-07", n0) } }
        assertThrows(IllegalArgumentException::class.java) { MemoNumbering("SR-1", 0) }
    }

    @Test fun twoPhonesOfOneUserNeverShareANumber() {
        val a = (1..1499).map { MemoNumbers.seqOf(it, MemoNumbering("sr334001", 0))!! }.toSet()
        val b = (1..1499).map { MemoNumbers.seqOf(it, MemoNumbering("sr334001", 1))!! }.toSet()
        assertTrue((a intersect b).isEmpty())
    }

    @Test fun aFailedSaveBurnsItsNumberAndTheNextSaleTakesTheNext() = runTest {
        val (visit, fix) = TestRows.visit()
        repo.recordVisitOpen(visit, fix)
        val numbering = MemoNumbering("sr334001", 0)
        val good = TestRows.sale(visit.clientUuid)
        assertEquals("sr334001-261005-001", repo.recordNumberedSale(good, numbering))
        val bad = TestRows.sale(visit.clientUuid, memoUuid = good.memo.clientUuid) // duplicate uuid: the save fails
        assertThrows(SQLiteConstraintException::class.java) { kotlinx.coroutines.runBlocking { repo.recordNumberedSale(bad, numbering) } }
        assertEquals("sr334001-261005-003", repo.recordNumberedSale(TestRows.sale(visit.clientUuid), numbering))
        assertEquals(listOf("sr334001-261005-001", "sr334001-261005-003"), db.captureDao().memosOn("2026-10-05").map { it.memoNo }.sorted())
        val memoRow = db.outboxDao().nextPending(100).first { it.recordType == "memo" && it.clientUuid != good.memo.clientUuid }
        assertEquals("sr334001-261005-003", payload(memoRow.payloadJson)["memo_no"]!!.jsonPrimitive.content)
    }

    @Test fun qcReturnSubtractsFromTheStockBalance() = runTest {
        repo.recordStock(listOf(TestRows.stock(qty = 10)))
        repo.recordStock(listOf(StockMovementEntity(ClientIds.newUuid(), TestRows.meta(), "qc_return", 100, 2, "pack", 10, 20, null, false)))
        assertEquals(80L, db.captureDao().stockBalanceOn("2026-10-05").single().qtyBase)
    }

    private fun bundleWithTask(task: String, version: String = "2026-10-05:3"): JsonObject {
        val base = Json.parseToJsonElement(javaClass.getResourceAsStream("/fixtures/sr_day_bundle.json")!!.readBytes().decodeToString()).jsonObject
        val meta = JsonObject(base["meta"]!!.jsonObject + ("bundle_version" to kotlinx.serialization.json.JsonPrimitive(version)))
        val tasks = Json.parseToJsonElement(
            """[{"task_uuid":"$task","task_type_code":"collect_due","title":"বকেয়া আদায়","description":null,"outlet_id":50001,"due_date":"2026-10-06","status":"ongoing","resolved_at":null}]""",
        )
        return JsonObject(base + ("meta" to meta) + ("tasks" to tasks))
    }

    private companion object {
        const val MONEY = """{"active_memo_count":12,"gross_mtk":1,"offer_discount_mtk":0,"drp_discount_mtk":0,"qc_deduction_mtk":0,"net_mtk":1,"paid_mtk":1,"due_mtk":0,"due_collected_mtk":0,"net_by_category_mtk":{},"issued_qty_base_by_sku":{},"sold_qty_base_by_sku":{}}"""
    }
}
