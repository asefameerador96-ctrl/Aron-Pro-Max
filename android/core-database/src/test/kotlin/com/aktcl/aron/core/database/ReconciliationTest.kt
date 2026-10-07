package com.aktcl.aron.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.contract.ServerTotals
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.OutboxState
import com.aktcl.aron.core.database.entity.SyncMetaEntity
import com.aktcl.aron.core.database.reference.BundleReference
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.ReconReason
import com.aktcl.aron.core.database.repo.ReconState
import com.aktcl.aron.core.database.repo.ReconciliationRepository
import com.aktcl.aron.core.database.repo.ReferenceRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** F-SYS-009: device counts and money from Room, compared with the stored server_totals (docs/24 s4.12). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ReconciliationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    private val repo = CaptureRepository(db) { "2026-10-05T05:00:00.000Z" }
    private val recon = ReconciliationRepository(db) { "2026-10-05T05:00:00.000Z" }
    private val date = "2026-10-05"
    private lateinit var visitUuid: String
    private lateinit var originalMemo: String

    @Before fun setUp(): Unit = runBlocking {
        ReferenceRepository(db).apply(BundleReference.parse(javaClass.getResourceAsStream("/fixtures/sr_day_bundle.json")!!.reader().readText()))
        val (visit, fix) = TestRows.visit()
        visitUuid = visit.clientUuid
        repo.recordVisitOpen(visit, fix)
        val original = TestRows.sale(visit.clientUuid)
        originalMemo = original.memo.clientUuid
        repo.recordSale(original)
        // An edit supersedes the original: only the edit is active (as on the server).
        val editUuid = ClientIds.newUuid()
        val editFix = TestRows.fix(editUuid, "memo_edit")
        val base = TestRows.sale(visit.clientUuid, memoNo = "sr334001-261005-018", memoUuid = editUuid)
        repo.recordSale(base.copy(
            memo = base.memo.copy(supersedesClientUuid = original.memo.clientUuid, editReasonCode = "qty_wrong", editFixClientUuid = editFix.clientUuid),
            editFix = editFix,
        ))
        repo.recordStock(listOf(TestRows.stock(skuId = 100, qty = 10), TestRows.stock(skuId = 103, qty = 5)))
        Unit
    }

    private fun money() = runBlocking { recon.deviceMoney(date) }

    @Test
    fun deviceMoneyFollowsTheServersDefinitions() {
        val m = money()
        assertEquals(1, m["active_memo_count"]!!.jsonPrimitive.long.toInt())
        assertEquals(217_500, m["gross_mtk"]!!.jsonPrimitive.long)
        assertEquals(192_000, m["net_mtk"]!!.jsonPrimitive.long)
        assertEquals(0, m["due_collected_mtk"]!!.jsonPrimitive.long)
        assertEquals(180_000, m["net_by_category_mtk"]!!.jsonObject["cigarette"]!!.jsonPrimitive.long)
        assertEquals(37_500, m["net_by_category_mtk"]!!.jsonObject["lighter"]!!.jsonPrimitive.long)
        assertEquals(20, m["sold_qty_base_by_sku"]!!.jsonObject["100"]!!.jsonPrimitive.long)
        assertEquals(100, m["issued_qty_base_by_sku"]!!.jsonObject["100"]!!.jsonPrimitive.long)
        assertEquals(50, m["issued_qty_base_by_sku"]!!.jsonObject["103"]!!.jsonPrimitive.long)
        // Every MoneyTotals member is present (all are required by the contract).
        assertEquals(12, m.size)
    }

    @Test
    fun beforeTheFirstAnswerTheServerColumnIsBlankAndUnsentRowsSaySo() = runBlocking {
        val r = recon.reconcile(date, "SR")
        assertEquals(listOf("outlet", "sale", "stock", "qc", "promotion"), r.rows.map { it.key })
        val sale = r.rows.first { it.key == "sale" }
        assertEquals(2, sale.device) // both memo rows are committed records
        assertNull(sale.server)
        assertEquals(ReconState.NO_SERVER_YET, sale.state)
        assertEquals(ReconReason.NOT_SENT, sale.reason)
        assertNull(r.serverAsOf)
        assertEquals("2026-10-05T05:00:00.000Z", r.deviceAsOf)
        assertNull(r.money.matches)
    }

    private suspend fun answer(byType: String, money: JsonObject, asOf: String = "2026-10-05T05:30:00.000Z") {
        val totals = """{"business_date":"$date","as_of":"$asOf","by_type":$byType,"money":$money}"""
        Json.decodeFromString(ServerTotals.serializer(), totals) // the stored text is a contract ServerTotals
        db.referenceDao().putMeta(SyncMetaEntity(ReconciliationRepository.KEY_SERVER_TOTALS + date, totals))
    }

    private suspend fun ackAll(at: String) {
        for (row in db.outboxDao().nextPending(100)) db.outboxDao().applyAck(row.clientUuid, OutboxState.ACKED, "accepted", 1, at)
    }

    @Test
    fun aReconciledDayMatchesRowByRowAndToTheMilliTaka() = runBlocking {
        ackAll("2026-10-05T05:20:00.000Z")
        val serverMoney = JsonObject(money() + ("net_by_category_mtk" to Json.parseToJsonElement("""{"cigarette":180000,"lighter":37500,"bidi":0}""")))
        answer("""{"visit":{"accepted":1,"rejected":0,"quarantined":0},"memo":{"accepted":1,"rejected":0,"quarantined":1},
            "stock_movement":{"accepted":2,"rejected":0,"quarantined":0},"qc_line":{"accepted":2,"rejected":0,"quarantined":0},
            "memo_discount":{"accepted":1,"rejected":1,"quarantined":0}}""", serverMoney)
        val r = recon.reconcile(date, "SR")
        assertTrue(r.rows.joinToString { "${it.key}=${it.state}" }, r.rows.all { it.state == ReconState.MATCH })
        assertEquals(true, r.money.matches) // a zero category on the server equals an absent one
        assertEquals("2026-10-05T05:30:00.000Z", r.serverAsOf)
    }

    @Test
    fun aMismatchCarriesItsReason() = runBlocking {
        ackAll("2026-10-05T05:40:00.000Z")
        val serverMoney = JsonObject(money() + ("net_mtk" to JsonPrimitive(1)))
        answer("""{"visit":{"accepted":2,"rejected":0,"quarantined":0},"memo":{"accepted":1,"rejected":0,"quarantined":0}}""", serverMoney)
        val r = recon.reconcile(date, "SR")
        fun row(k: String) = r.rows.first { it.key == k }
        assertEquals(ReconReason.SERVER_HAS_MORE, row("outlet").reason)
        // Answered at 05:40, after the server's 05:30 figures: the next sync refreshes them.
        assertEquals(ReconReason.AWAITING_SERVER, row("sale").reason)
        assertEquals(1, row("sale").awaited)
        assertEquals(false, r.money.matches)
        assertEquals(listOf("net_mtk"), r.money.differingMembers)
        answer("""{"visit":{"accepted":1,"rejected":0,"quarantined":0},"memo":{"accepted":1,"rejected":0,"quarantined":0}}""", money(), asOf = "2026-10-05T06:00:00.000Z")
        assertEquals(ReconReason.SERVER_HAS_FEWER, recon.reconcile(date, "SR").rows.first { it.key == "sale" }.reason)
    }

    @Test
    fun rowsComeFromConfigInEitherShape() {
        val flat = ReconciliationRepository.rowTypes("""{"AMO.visits":["call_assessment"],"SR.sale":["memo"],"SR.dues":["due_collection"]}""", "SR")
        assertEquals(listOf("sale" to listOf("memo"), "dues" to listOf("due_collection")), flat)
        val nested = ReconciliationRepository.rowTypes("""{"SR":{"outlet":["visit"],"sale":["memo"]}}""", "SR")
        assertEquals(listOf("outlet", "sale"), nested.map { it.first })
        assertEquals(ReconciliationRepository.SR_DEFAULT, ReconciliationRepository.rowTypes(null, "SR"))
        assertEquals(emptyList<Pair<String, List<String>>>(), ReconciliationRepository.rowTypes("garbage", "AMO"))
        assertFalse(ReconciliationRepository.rowTypes("""{"SR.x":["memo"]}""", "SRX").isNotEmpty())
    }
}
