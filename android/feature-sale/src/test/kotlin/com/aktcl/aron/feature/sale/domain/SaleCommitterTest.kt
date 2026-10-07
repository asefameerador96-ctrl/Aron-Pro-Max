package com.aktcl.aron.feature.sale.domain

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.rules.MemoMath
import com.aktcl.aron.rules.QtyUnit
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SaleCommitterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AronDatabase
    private lateinit var repo: CaptureRepository
    private var seq = 0
    private var numberFailures = 0
    private val numbers = MemoNumbers { d -> if (numberFailures > 0) { numberFailures--; error("counter unavailable") }; "sr334001-${d.substring(2).replace("-", "")}-%03d".format(++seq) }
    private lateinit var committer: SaleCommitter
    private lateinit var visitUuid: String

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
        repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
        committer = SaleCommitter(repo, numbers, { _, route -> Fx.meta().copy(routeId = route) }, { "2026-10-05T04:35:00.000Z" })
        visitUuid = com.aktcl.aron.core.common.ClientIds.newUuid()
        val (v, f) = Fx.visit(visitUuid)
        repo.recordVisitOpen(v, f)
    }
    @After fun tearDown() = db.close()

    private fun count(table: String): Int = db.query("SELECT COUNT(*) FROM `$table`", null).use { it.moveToFirst(); it.getInt(0) }

    private fun fullDraft() = Fx.draft(visitUuid).let {
        var d = SaleDraftOps.setQuantity(it, 100, 20, QtyUnit.STICK)
        d = SaleDraftOps.setQuantity(d, 103, 3, QtyUnit.PIECE)
        d = SaleDraftOps.setSlide(d, 100, 10)
        SaleDraftOps.setQc(d, QcEntry(103, "torn_pack", "MFC", 1))
    }

    @Test fun commitWritesMemoLinesDiscountsQcAndOutboxInOneGoAndVerifiesAgainstSharedRules() = runTest {
        val out = committer.commit(fullDraft(), Fx.catalog)
        val memo = db.captureDao().memo(out.memoUuid)!!
        assertEquals(out.memoNo, memo.memoNo)
        assertEquals(2, count("memo_line")); assertEquals(1, count("memo_discount")); assertEquals(1, count("qc_line"))
        assertEquals(160_000 + 37_500, memo.grossMtk); assertEquals(80_000, memo.drpDiscountMtk); assertEquals(12_500, memo.qcDeductionMtk)
        assertEquals(memo.grossMtk - memo.drpDiscountMtk - memo.qcDeductionMtk, memo.netMtk)
        assertEquals(memo.netMtk, memo.paidMtk + memo.dueMtk)
        val types = db.outboxDao().nextPending(100).map { it.recordType }
        assertEquals(listOf("visit", "memo", "memo_line", "memo_line", "memo_discount", "qc_line"), types)
    }

    @Test fun creditMemoRecordsPaidDueAndIsCredit() = runTest {
        val d = SaleDraftOps.setPaid(SaleDraftOps.setQuantity(Fx.draft(visitUuid), 100, 10, QtyUnit.STICK), 30_000)
        val out = committer.commit(d, Fx.catalog)
        val m = db.captureDao().memo(out.memoUuid)!!
        assertTrue(m.isCredit); assertEquals(30_000, m.paidMtk); assertEquals(50_000, m.dueMtk); assertEquals(50_000, out.dueMtk)
    }

    @Test fun zeroSaleWritesALinelessMemoThatConsumesANumber() = runTest {
        val out = committer.commit(SaleDraftOps.confirmZeroSale(Fx.draft(visitUuid)), Fx.catalog)
        val m = db.captureDao().memo(out.memoUuid)!!
        assertEquals(0, m.lineCount); assertEquals(0, count("memo_line")); assertEquals(1, seq)
        assertEquals(0, m.netMtk)
    }

    @Test fun replayingTheSameDraftAfterACommitWritesNothingMore() = runTest {
        val d = fullDraft()
        committer.commit(d, Fx.catalog)
        val before = listOf("memo", "memo_line", "memo_discount", "qc_line", "outbox").map(::count)
        assertThrows(SQLiteConstraintException::class.java) { kotlinx.coroutines.runBlocking { committer.commit(d, Fx.catalog) } }
        assertEquals(before, listOf("memo", "memo_line", "memo_discount", "qc_line", "outbox").map(::count))
    }

    @Test fun aFailureInsideTheTransactionLeavesNothingAndARetryOfTheSameDraftSucceeds() = runTest {
        val d = fullDraft()
        // A line row with a clashing client uuid (as a corrupt retry would) aborts the whole transaction mid-way.
        var calls = 0
        val clash = com.aktcl.aron.core.common.ClientIds.newUuid()
        val bad = SaleCommitter(repo, numbers, { _, r -> Fx.meta().copy(routeId = r) }, { "2026-10-05T04:35:00.000Z" }, { calls++; if (calls <= 3) clash else com.aktcl.aron.core.common.ClientIds.newUuid() })
        assertThrows(SQLiteConstraintException::class.java) { kotlinx.coroutines.runBlocking { bad.commit(d, Fx.catalog) } }
        assertEquals(0, count("memo")); assertEquals(0, count("memo_line")); assertEquals(0, count("memo_discount")); assertEquals(0, count("qc_line"))
        assertEquals(1, count("outbox")) // only the visit
        committer.commit(d, Fx.catalog)
        assertEquals(1, count("memo")); assertEquals(2, count("memo_line"))
    }

    @Test fun anUncommittableReviewWritesNothing() = runTest {
        val d = SaleDraftOps.setPaid(SaleDraftOps.setQuantity(Fx.draft(visitUuid), 100, 10, QtyUnit.STICK), 80_000)
        assertThrows(ReviewNotCommittable::class.java) { kotlinx.coroutines.runBlocking { committer.commit(d, Fx.catalog) } }
        assertEquals(0, count("memo")); assertEquals(0, seq)
    }

    @Test fun editSupersedesTheOldMemoWithReasonAndFix() = runTest {
        val first = committer.commit(Fx.draft(visitUuid).let { SaleDraftOps.setQuantity(it, 100, 10, QtyUnit.STICK) }, Fx.catalog)
        val editMemo = SaleDraftOps.setQuantity(Fx.draft(visitUuid), 100, 8, QtyUnit.STICK).copy(edit = EditContext(first.memoUuid, "wrong_quantity"))
        val fix = GeoFixEntity(
            com.aktcl.aron.core.common.ClientIds.newUuid(), editMemo.memoUuid, "memo_edit", "ok", 23.79, 90.40, 10.0, provider = "fused",
            isMock = false, reused = false, deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false,
        )
        val out = committer.commit(editMemo, Fx.catalog, fix)
        val m = db.captureDao().memo(out.memoUuid)!!
        assertEquals(first.memoUuid, m.supersedesClientUuid); assertEquals("wrong_quantity", m.editReasonCode)
        assertTrue(m.memoNo != first.memoNo)
    }

    @Test fun draftSurvivesKillAndRelaunch() {
        val dir = File(context.cacheDir, "drafts-${System.nanoTime()}")
        val d = fullDraft()
        FileDraftStore(dir).save(d)
        val reopened = FileDraftStore(dir) // a new process: nothing in memory
        assertEquals(d, reopened.load(d.visitUuid))
        // a kill during a later save leaves the previous whole draft: simulate the stray temp file
        File(dir, "sale-draft-${d.visitUuid}.json.tmp").writeText("{broken")
        assertEquals(d, FileDraftStore(dir).load(d.visitUuid))
        // an unreadable draft reads as none instead of crashing the sale
        File(dir, "sale-draft-${d.visitUuid}.json").writeText("{broken")
        assertEquals(null, FileDraftStore(dir).load(d.visitUuid))
        reopened.clear(d.visitUuid)
    }

    @Test fun committedTotalsEqualSharedRulesOracle() = runTest {
        val d = fullDraft()
        val r = SaleReviewCalculator.review(d, Fx.catalog)
        val oracle = MemoMath.totals(r.lines.map { it.memoLine }, r.drp.map { com.aktcl.aron.rules.DiscountLine(it.skuId, it.rewardQtyBase, it.valueMtk, com.aktcl.aron.rules.DiscountKind.DRP) }, r.qc.map { com.aktcl.aron.rules.QcLine(it.settlementMtk, true) })
        assertEquals(oracle, r.totals)
    }
}
