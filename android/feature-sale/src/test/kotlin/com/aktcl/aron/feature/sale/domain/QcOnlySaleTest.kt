package com.aktcl.aron.feature.sale.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.rules.Money
import com.aktcl.aron.rules.QtyUnit
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.random.Random

private class QcMemStore : DraftStore {
    val map = HashMap<String, SaleDraft>()
    override fun load(visitUuid: String) = map[visitUuid]
    override fun save(draft: SaleDraft) { map[draft.visitUuid] = draft }
    override fun clear(visitUuid: String) { map.remove(visitUuid) }
}

/**
 * QC on a zero sale (owner ruling D-18, 2026-10-18): the memo has no sale line, the QC rows are its content, the net is the
 * negative of the QC (a credit) and is rounded to the paisa once. Money: the property test recomputes the net with an oracle
 * that shares no code with `shared:rules`; the flow tests prove the stored memo and that a replay writes nothing twice.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class QcOnlySaleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    @After fun tearDown() = db.close()

    private val numbers = FakeNumbers()
    private fun committer() = SaleCommitter(CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }, numbers, { _, r -> Fx.meta().copy(routeId = r) }, { "2026-10-05T04:35:00.000Z" }, findMemo = { db.captureDao().memo(it) })
    private fun flow(store: DraftStore) = SaleFlow(store, { _, _ -> Fx.catalog.values.toList() }, committer())
    private suspend fun visit(): SaleVisit {
        val u = ClientIds.newUuid(); val (v, f) = Fx.visit(u)
        CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }.recordVisitOpen(v, f)
        return SaleVisit(u, 50001, 10231, Fx.DATE, "outlet")
    }

    /** Half away from zero to a whole paisa (10 mtk), written out so it does not call the code under test. */
    private fun paisaHalfAway(mtk: Long): Long { val a = Math.abs(mtk); val r = (a + 5) / 10 * 10; return if (mtk < 0) -r else r }
    private fun lineGross(qty: Long, price: Long, perQty: Long): Long = (qty * price * 2 + perQty) / (perQty * 2) // half up for positives

    @Test fun netIsMinusTheQcRoundedOnceToThePaisaForRandomQcOnlySales() {
        val rnd = Random(20261018)
        val skus = Fx.catalog.values.toList()
        repeat(500) {
            val picks = skus.shuffled(rnd).take(1 + rnd.nextInt(skus.size))
            val qc = picks.flatMap { s -> QcEntryModel.APP_TYPES.shuffled(rnd).take(1 + rnd.nextInt(3)).map { t -> QcEntry(s.skuId, t.code, t.group, 1L + rnd.nextInt(500)) } }
            val draft = Fx.draft().copy(zeroSale = true, qc = qc)
            val r = SaleReviewCalculator.review(draft, Fx.catalog)
            val settlements = qc.map { q -> val s = Fx.catalog.getValue(q.skuId); lineGross(q.defectQty, s.unitPriceMtk, s.pricePerQty.toLong()) }
            val raw = -settlements.sum()
            assertEquals("gross stays zero", 0L, r.totals.grossMtk)
            assertEquals("QC total is the sum of the row settlements", settlements.sum(), r.totals.qcDeductionMtk)
            assertEquals("net = gross - discounts - QC, rounded once", paisaHalfAway(raw), r.totals.netMtk)
            assertEquals("whole paisa", 0L, r.totals.netMtk % Money.MTK_PER_PAISA)
            assertEquals("round adj closes the gap", r.totals.netMtk - raw, r.totals.roundAdjMtk)
            assertEquals("a credit pays nothing and owes the negative net", 0L to r.totals.netMtk, r.settlement.paidMtk to r.settlement.dueMtk)
            assertTrue("a QC-only credit is committable: ${r.problems}", r.canCommit)
        }
    }

    @Test fun aSaleWithLinesStillRefusesQcAboveItsGross() {
        val draft = Fx.draft().copy(lines = listOf(DraftLine(100, 1, "stick")), qc = listOf(QcEntry(100, "damaged_crushed", "MFC", 2)))
        val r = SaleReviewCalculator.review(draft, Fx.catalog)
        assertTrue(r.problems.any { it is ReviewProblem.NetNegative })
    }

    @Test fun memoRoomHasNoCeilingOnlyWhileTheGrossIsZero() {
        assertNull(QcEntryModel.memoRoomMtk(0, 0, 0))
        assertEquals(800L, QcEntryModel.memoRoomMtk(1_000, 100, 100))
        assertEquals(0L, QcEntryModel.memoRoomMtk(1_000, 600, 600))
    }

    @Test fun qcOnlyFlowStoresAZeroLineMemoWithANegativeNetAndOneNumber() = runTest {
        val store = QcMemStore(); val v = visit(); val f = flow(store); f.start(v)
        f.confirmZeroSale()
        f.setQc(QcEntry(103, "taste", "MKT", 3)); f.setQc(QcEntry(100, "damaged_crushed", "MFC", 10)) // 3 x 12.50 + 10 x 8.00
        f.completeQc()
        assertTrue(f.state.value.draft!!.qcCompleted)
        val r1 = f.commit(); val r2 = f.commit()
        assertSame("a double tap is the same memo", r1, r2)
        assertEquals(1, numbers.consumed)
        val m = db.captureDao().memo(r1.memoUuid)!!
        assertEquals(0, m.lineCount); assertEquals(2, m.qcLineCount); assertEquals("zero_sale", m.memoKind)
        assertEquals(0L, m.grossMtk); assertEquals(117_500L, m.qcDeductionMtk); assertEquals(-117_500L, m.netMtk)
        assertEquals(0L, m.paidMtk); assertEquals(-117_500L, m.dueMtk); assertEquals(false, m.isCredit)
        assertNull(store.load(v.visitUuid))
    }

    @Test fun aKillAfterQcWasSubmittedResumesTheSameQcOnlyMemoAndCommitsItOnce() = runTest {
        val store = QcMemStore(); val v = visit(); val a = flow(store); a.start(v)
        a.confirmZeroSale(); a.setQc(QcEntry(105, "expired_stock", "MKT", 2)); a.completeQc()
        val b = flow(store); b.start(v) // killed and relaunched before the commit
        assertEquals(a.state.value.draft, b.state.value.draft)
        val r = b.commit()
        assertEquals(r.memoUuid, a.state.value.draft!!.memoUuid)
        val again = flow(store).also { store.save(a.state.value.draft!!); it.start(v) } // the draft came back after the commit landed
        assertEquals(r.memoNo, again.state.value.committed!!.memoNo)
        assertEquals(1, numbers.consumed)
    }

    @Test fun qcThatWasNeverSubmittedIsDroppedOnRelaunchButTheZeroSaleStays() = runTest {
        val store = QcMemStore(); val v = visit(); val a = flow(store); a.start(v)
        a.confirmZeroSale(); a.setQc(QcEntry(105, "taste", "MKT", 2))
        val b = flow(store); b.start(v)
        assertEquals(emptyList<QcEntry>(), b.state.value.draft!!.qc)
        assertEquals(true, b.state.value.draft!!.zeroSale)
    }

    @Test fun sellingALineAfterQcOnlyDropsTheQcRowsOfSkusThatHaveNoLine() = runTest {
        val store = QcMemStore(); val v = visit(); val f = flow(store); f.start(v)
        f.confirmZeroSale(); f.setQc(QcEntry(105, "taste", "MKT", 2)); f.setQc(QcEntry(100, "damaged_crushed", "MFC", 1))
        f.setQuantity(100, 5, QtyUnit.STICK)
        assertEquals(listOf(100L), f.state.value.draft!!.qc.map { it.skuId })
        assertEquals(false, f.state.value.draft!!.zeroSale)
    }

    @Test fun summaryHasOneRowPerSkuAndNothingIsLostOrCountedTwice() {
        val rnd = Random(7)
        repeat(200) {
            val entries = (1..rnd.nextInt(1, 12)).map { QcEntry(100L + rnd.nextInt(4), QcEntryModel.APP_TYPES.random(rnd).let { t -> t.code }, "", 1L + rnd.nextInt(50)) }
                .map { it.copy(faultGroup = QcEntryModel.groupOf(it.faultTypeCode)!!) }
                .distinctBy { it.skuId to it.faultTypeCode }
            val rows = QcEntryModel.summary(entries)
            assertEquals(entries.map { it.skuId }.distinct(), rows.map { it.skuId })
            assertEquals(entries.sumOf { it.defectQty }, rows.sumOf { it.totalQty })
            assertEquals(entries.filter { it.faultGroup == "MFC" }.sumOf { it.defectQty }, rows.sumOf { it.mfcQty })
            assertEquals(entries.filter { it.faultGroup == "MKT" }.sumOf { it.defectQty }, rows.sumOf { it.mktQty })
        }
    }

    @Test fun cancelOnTheQcOnlyPathLeavesNoUnconfirmedZeroSale() = runTest {
        val store = QcMemStore(); val v = visit(); val f = flow(store); f.start(v)
        f.startQcOnly(); f.setQc(QcEntry(105, "taste", "MKT", 2)); f.cancelQcOnly()
        assertEquals(false, f.state.value.draft!!.zeroSale); assertEquals(emptyList<QcEntry>(), f.state.value.draft!!.qc)
        assertTrue("nothing to sell again", f.state.value.review!!.problems.any { it is ReviewProblem.NothingToSell })
    }

    @Test fun moreQcRowsThanTheDatabaseKeepsIsAProblemAndQcIsNeverLockedIn() = runTest {
        val store = QcMemStore(); val v = visit(); val f = flow(store); f.start(v)
        f.startQcOnly()
        var n = 0
        for (s in Fx.catalog.keys) for (t in QcEntryModel.APP_TYPES) if (n < 61) { f.setQc(QcEntry(s, t.code, t.group, 1)); n++ }
        assertTrue(n >= 24)
        val big = SaleReviewCalculator.review(Fx.draft().copy(zeroSale = true, qc = List(61) { QcEntry(100, "damaged_crushed", "MFC", 1L + it) }), Fx.catalog)
        assertTrue(big.problems.any { it is ReviewProblem.TooManyQcRows }); assertEquals(false, big.canCommit)
    }
}
