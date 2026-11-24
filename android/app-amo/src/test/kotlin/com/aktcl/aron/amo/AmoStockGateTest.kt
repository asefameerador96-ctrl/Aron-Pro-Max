package com.aktcl.aron.amo

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.StockMovementEntity
import com.aktcl.aron.core.database.entity.VisitEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.MemoNumbering
import com.aktcl.aron.core.database.repo.OverStockException
import com.aktcl.aron.feature.sale.domain.SaleCommitter
import com.aktcl.aron.feature.sale.domain.SaleDraft
import com.aktcl.aron.feature.sale.domain.SaleDraftOps
import com.aktcl.aron.feature.sale.domain.SaleSku
import com.aktcl.aron.rules.QtyUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * D-OWNER-PREREQ-01 rule 2 for the AMO (D-AGATE-07, lead's round 2): a sale on an AMO CONTROL call and on a JOINT call both reach the stock gate
 * through the same committer the AMO kit builds (numbered, `enforceStock`): no stock entry means zero, a refused sale takes no memo number and
 * writes nothing, and a sale within the stock saves.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AmoStockGateTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val work = TestWork()
    private val db = Room.inMemoryDatabaseBuilder(app, AronDatabase::class.java).allowMainThreadQueries()
        .setQueryExecutor(work.roomExecutor).setTransactionExecutor(work.roomExecutor).build()
    private val date = "2026-10-26"
    private val route = 10231L
    private val now = "2026-10-26T12:00:00.000Z"
    private val repo = CaptureRepository(db) { now }
    private val sku = SaleSku(1, "MAXR20", "cigarette", "MaxR 20S", null, "stick", 20, 7_935, 1, "2026-09-01", entryUnitDefault = "pack", stockBase = 200)

    @After fun tearDown() { try { work.stop(); db.close() } finally { work.roomExecutor.shutdown() } }

    private fun meta() = CaptureMeta(date, now, 1_000, 3, 0L, true, route, null, "$date:1", false, 1)

    private fun call(kind: String): String = runBlocking {
        val u = ClientIds.newUuid()
        val f = GeoFixEntity(
            ClientIds.newUuid(), u, "visit_open", "ok", 23.79, 90.40, 8.0, provider = "fused", fixTime = now, fixElapsedRealtimeMs = 900, fixAgeMs = 10,
            timeToFixMs = 1000, requestPriority = "balanced", isMock = false, reused = false, refreshCount = 0, deviceOwner = true, devOptionsEnabled = false,
            adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false,
        )
        repo.recordVisitOpen(
            VisitEntity(
                clientUuid = u, meta = meta(), visitKind = kind, outletId = 50001, openedAt = now, sequenceNo = 1, planned = true, fixClientUuid = f.clientUuid,
                geoVerdict = "in_range", geoDistanceM = 10.0, geoRadiusMUsed = 100, geoMaxAccuracyMUsed = 50, geoLocationBasis = "master", geoOutletLat = 23.79,
                geoOutletLng = 90.40, geoAction = "sale_allowed",
            ),
            f,
        )
        u
    }

    private fun committer() = SaleCommitter(
        repo, numbers = { _, _ -> error("numbered through MemoNumbering") }, metaSource = { _, _ -> meta() }, nowIso = { now },
        findMemo = { db.captureDao().memo(it) }, numbering = MemoNumbering("amo334001", 0, 500),
    )

    private fun draft(visit: String, packs: Long): SaleDraft =
        SaleDraftOps.setQuantity(SaleDraft(visitUuid = visit, outletId = 50001, routeId = route, businessDate = date, memoUuid = ClientIds.newUuid()), sku.skuId, packs, QtyUnit.PACK)

    private fun memoCount() = db.query("SELECT COUNT(*) FROM memo", null).use { it.moveToFirst(); it.getInt(0) }

    private fun issue(packs: Long) = runBlocking {
        repo.recordStock(listOf(StockMovementEntity(ClientIds.newUuid(), meta(), "issue", sku.skuId, packs, "pack", 20, packs * 20, slipPrinted = false)))
    }

    @Test fun aControlCallAndAJointCallBothMeetTheGate() {
        for (kind in listOf("amo_control_call", "amo_joint_call")) {
            val visit = call(kind)
            // no stock entry: zero. The catalog is made to lie (200 held) so only the transaction can stop it.
            val e = assertThrows(OverStockException::class.java) { runBlocking { committer().commit(draft(visit, 2), mapOf(sku.skuId to sku)) } }
            assertEquals("$kind: no stock entry reads as zero", 0L, e.shortages.single().heldBase)
        }
        assertEquals("nothing was saved by either refused sale", 0, memoCount())
        runBlocking { assertNull("and no memo number was taken", db.referenceDao().memoCounter(date)) }
    }

    @Test fun withTheDaysStockIssuedAnAmoSaleSavesAndTheNextOneIsStopped() {
        issue(10) // 10 packs = 200 sticks
        val control = call("amo_control_call")
        runBlocking { committer().commit(draft(control, 6), mapOf(sku.skuId to sku)) } // 120 of 200
        val joint = call("amo_joint_call")
        val e = assertThrows(OverStockException::class.java) { runBlocking { committer().commit(draft(joint, 6), mapOf(sku.skuId to sku)) } }
        assertEquals(80L, e.shortages.single().heldBase) // the figure the transaction read: the first memo is already drawn
        assertEquals(1, memoCount())
    }
}
