package com.aktcl.aron.feature.sale.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.EditTargetSupersededException
import com.aktcl.aron.core.database.repo.MemoNumbering
import com.aktcl.aron.core.database.repo.OverStockException
import com.aktcl.aron.core.database.repo.StockHeld
import com.aktcl.aron.rules.QtyUnit
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Round 2 of the lead's review of slice 1 (D-AGATE-07): the gate across midnight, a replaced edit target, a refusal the transaction itself
 * raises (the flow's catch really reached, with a stale catalog), an edit that changes SKU or unit, and a stock row of another business date.
 * The real Room database, so these prove stored rows.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class StockGateLowsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AronDatabase
    private lateinit var repo: CaptureRepository
    private lateinit var visitUuid: String
    private val yesterday = "2026-10-04"

    private val sku = Fx.maxr.copy(stockBase = 100) // 10 sticks a pack
    private val other = Fx.maxr.copy(skuId = 101, code = "MAXR20", name = "MaxR 20S", nameBn = "ম্যাক্স আর ২০", stockBase = 100)
    private val catalog = mapOf(sku.skuId to sku)

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
        repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
        visitUuid = ClientIds.newUuid()
        val (v, f) = Fx.visit(visitUuid)
        repo.recordVisitOpen(v, f)
    }
    @After fun tearDown() = db.close()

    /** The memo takes the date of the draft, as the production meta source does. */
    private fun committer(numbering: MemoNumbering? = null) = SaleCommitter(
        repo, FakeNumbers(), { date, r -> Fx.meta().copy(businessDate = date, routeId = r) }, { "2026-10-05T04:35:00.000Z" },
        findMemo = { db.captureDao().memo(it) }, numbering = numbering,
    )
    private fun draft(qty: Long, unit: QtyUnit = QtyUnit.STICK, s: SaleSku = sku, date: String = Fx.DATE) =
        SaleDraftOps.setQuantity(Fx.draft(visitUuid).copy(businessDate = date), s.skuId, qty, unit)
    private fun memoCount() = db.query("SELECT COUNT(*) FROM memo", null).use { it.moveToFirst(); it.getInt(0) }
    private fun editFix(owner: String) = GeoFixEntity(
        ClientIds.newUuid(), owner, "memo_edit", "ok", 23.79, 90.40, 10.0, provider = "fused", isMock = false, reused = false,
        deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false,
    )
    private fun store() = object : DraftStore {
        val m = HashMap<String, SaleDraft>()
        override fun load(visitUuid: String) = m[visitUuid]
        override fun save(draft: SaleDraft) { m[draft.visitUuid] = draft }
        override fun clear(visitUuid: String) { m.remove(visitUuid) }
    }

    // ---- A1: a draft opened before midnight and saved after: the screen and the transaction read the MEMO's date

    @Test fun aDraftResumedAfterMidnightReadsTheStockOfItsOwnDateOnScreenAndInTheSave() = runTest {
        Fx.seedStock(repo, catalog, date = yesterday) // yesterday's issue only: today has none
        val st = store()
        st.save(draft(60, date = yesterday)) // the draft was made yesterday evening
        val asked = ArrayList<String>()
        val flow = SaleFlow(st, { d, _ -> asked += d; listOf(sku.copy(stockBase = StockHeld.read(db.captureDao(), d)[sku.skuId] ?: 0L)) }, committer())
        flow.start(SaleVisit(visitUuid, 50001, 10231, Fx.DATE, "outlet")) // resumed today: the visit says today
        assertEquals("the catalog is read for the draft's date, not for the clock's", listOf(yesterday), asked)
        assertEquals("the row shows yesterday's 100, the figure the save will check", 100L, flow.state.value.catalog.getValue(sku.skuId).stockBase)
        assertTrue(flow.state.value.review!!.canCommit)
        flow.commit() // the transaction reads the same date: it saves
        assertEquals(1, memoCount())
        assertEquals(yesterday, db.query("SELECT business_date FROM memo", null).use { it.moveToFirst(); it.getString(0) })
        assertEquals(40L, StockHeld.read(db.captureDao(), yesterday)[sku.skuId])
        assertEquals("today's stock is a different, empty day", null, StockHeld.read(db.captureDao(), Fx.DATE)[sku.skuId])
    }

    // ---- A2: an edit whose target memo was already replaced is refused, with nothing written and no number burned

    @Test fun anEditOfAMemoThatAnotherEditAlreadyReplacedIsRefusedAndTakesNoNumber() = runTest {
        Fx.seedStock(repo, catalog)
        val numbered = committer(MemoNumbering("sr334001", 0, 500))
        val first = numbered.commit(draft(10), catalog)
        val counterAfterFirst = db.referenceDao().memoCounter(Fx.DATE)
        val e1 = draft(10).copy(edit = EditContext(first.memoUuid, "wrong_quantity"))
        numbered.commit(e1, catalog, editFix(e1.memoUuid))
        val counterAfterEdit = db.referenceDao().memoCounter(Fx.DATE)
        assertNotEquals("the first edit took a number", counterAfterFirst, counterAfterEdit)
        val again = draft(10).copy(edit = EditContext(first.memoUuid, "wrong_quantity")) // a second edit of the SAME, already replaced memo
        assertThrows(EditTargetSupersededException::class.java) { kotlinx.coroutines.runBlocking { numbered.commit(again, catalog, editFix(again.memoUuid)) } }
        assertEquals("nothing was written", 2, memoCount())
        assertEquals("and no memo number was taken", counterAfterEdit, db.referenceDao().memoCounter(Fx.DATE))
    }

    @Test fun theFlowWordsAReplacedEditTargetAndKeepsTheDraft() = runTest {
        Fx.seedStock(repo, catalog)
        val c = committer()
        val first = c.commit(draft(10), catalog)
        val e1 = draft(10).copy(edit = EditContext(first.memoUuid, "wrong_quantity"))
        c.commit(e1, catalog, editFix(e1.memoUuid))
        val st = store(); st.save(draft(10))
        val flow = SaleFlow(st, { _, _ -> listOf(sku.copy(stockBase = StockHeld.read(db.captureDao(), Fx.DATE)[sku.skuId] ?: 0L)) }, c)
        flow.start(SaleVisit(visitUuid, 50001, 10231, Fx.DATE, "outlet"))
        flow.withEdit(EditContext(first.memoUuid, "wrong_quantity"))
        val thrown = assertThrows(ReviewNotCommittable::class.java) { kotlinx.coroutines.runBlocking { flow.commit(editFix(flow.state.value.draft!!.memoUuid)) } }
        assertEquals(listOf<ReviewProblem>(ReviewProblem.EditTargetSuperseded), thrown.problems)
        assertEquals("the screen is told too (the dialog reads it)", thrown.problems, flow.state.value.refusal)
        assertTrue("the draft is kept", st.m.containsKey(visitUuid))
        assertEquals(2, memoCount())
    }

    // ---- A5: the transaction's own refusal reaches the flow's catch (a STALE catalog), and the rep gets the specific figures

    @Test fun aSaveRefusedByTheTransactionGivesTheSpecificOverStockMessageFromTheStoredFigures() = runTest {
        Fx.seedStock(repo, catalog)
        val c = committer()
        var stale = true
        val st = store()
        val flow = SaleFlow(st, { _, _ -> listOf(sku.copy(stockBase = if (stale) 100L else StockHeld.read(db.captureDao(), Fx.DATE)[sku.skuId] ?: 0L)) }, c)
        flow.start(SaleVisit(visitUuid, 50001, 10231, Fx.DATE, "outlet"))
        assertEquals(60L, flow.setQuantity(sku.skuId, 60, QtyUnit.STICK)) // fits the 100 the screen was opened with
        // another memo takes 60 behind the screen's back
        c.commit(SaleDraftOps.setQuantity(Fx.draft(visitUuid), sku.skuId, 60, QtyUnit.STICK), catalog)
        stale = false
        // the committer's own review still passes (the screen's catalog says 100): only the TRANSACTION can refuse this
        assertTrue(flow.state.value.review!!.canCommit)
        val thrown = assertThrows(ReviewNotCommittable::class.java) { kotlinx.coroutines.runBlocking { flow.commit() } }
        val over = thrown.problems.filterIsInstance<ReviewProblem.OverStock>().single()
        assertEquals("the figure the transaction read, not the stale 100", 40L, over.heldBase); assertEquals(60L, over.requestedBase)
        assertEquals(sku.name, over.skuName)
        assertEquals("the screen's dialog has the same problems", thrown.problems, flow.state.value.refusal)
        assertEquals("the screen was republished with the true stock", 40L, flow.state.value.catalog.getValue(sku.skuId).stockBase)
        assertFalse(flow.state.value.review!!.canCommit)
        assertEquals(1, memoCount())
    }

    // ---- A10: an edit that changes the SKU or the unit

    @Test fun anEditThatSwitchesToAnotherSkuGivesTheOldQuantityBackAndChecksTheNewSkuAgainstItsOwnStock() = runTest {
        val both = mapOf(sku.skuId to sku, other.skuId to other)
        Fx.seedStock(repo, both)
        val c = committer()
        val first = c.commit(draft(100), both) // all of SKU 100
        fun switched(qty: Long, s: SaleSku): SaleDraft = SaleDraftOps.setQuantity(Fx.draft(visitUuid), s.skuId, qty, QtyUnit.STICK).copy(edit = EditContext(first.memoUuid, "wrong_sku"))
        // 50 of the other SKU fits its own 100; the old SKU's 100 come back
        val ok = switched(50, other)
        c.commit(ok, both, editFix(ok.memoUuid))
        assertEquals(100L, StockHeld.read(db.captureDao(), Fx.DATE)[sku.skuId]); assertEquals(50L, StockHeld.read(db.captureDao(), Fx.DATE)[other.skuId])
        // asking the NEW sku for more than it holds is refused: the give-back of the OLD sku does not pay for it
        val first2 = c.commit(draft(100), both)
        val tooMuch = SaleDraftOps.setQuantity(Fx.draft(visitUuid), other.skuId, 60, QtyUnit.STICK).copy(edit = EditContext(first2.memoUuid, "wrong_sku"))
        val e = assertThrows(OverStockException::class.java) { kotlinx.coroutines.runBlocking { c.commit(tooMuch, both.mapValues { it.value.copy(stockBase = 1_000) }, editFix(tooMuch.memoUuid)) } }
        assertEquals(other.skuId, e.shortages.single().skuId); assertEquals(50L, e.shortages.single().heldBase)
    }

    @Test fun anEditThatChangesTheUnitIsCheckedInBaseUnits() = runTest {
        Fx.seedStock(repo, catalog)
        val c = committer()
        val first = c.commit(draft(30), catalog) // 30 sticks; 70 left
        // the same 30 sticks as 3 packs: fits (70 + its own 30 given back)
        val same = draft(3, QtyUnit.PACK).copy(edit = EditContext(first.memoUuid, "wrong_unit"))
        c.commit(same, catalog, editFix(same.memoUuid))
        assertEquals(70L, StockHeld.read(db.captureDao(), Fx.DATE)[sku.skuId])
        // 11 packs = 110 sticks: above 100 even with its own memo's 30 back
        val big = draft(11, QtyUnit.PACK).copy(edit = EditContext(same.memoUuid, "wrong_unit"))
        val e = assertThrows(OverStockException::class.java) { kotlinx.coroutines.runBlocking { c.commit(big, mapOf(sku.skuId to sku.copy(stockBase = 1_000)), editFix(big.memoUuid)) } }
        assertEquals(110L, e.shortages.single().requestedBase); assertEquals(100L, e.shortages.single().heldBase)
    }

    // ---- A10: a stock row of ANOTHER business date never pays for today's sale

    @Test fun aStockRowOfAnotherBusinessDateDoesNotCount() = runTest {
        Fx.seedStock(repo, catalog)                                          // today: 100
        Fx.seedStock(repo, mapOf(sku.skuId to sku.copy(stockBase = 500)), date = yesterday) // yesterday: 500 more
        val e = assertThrows(OverStockException::class.java) { kotlinx.coroutines.runBlocking { committer().commit(draft(101), mapOf(sku.skuId to sku.copy(stockBase = 1_000))) } }
        assertEquals("today's 100 only", 100L, e.shortages.single().heldBase)
        assertEquals(0, memoCount())
    }

    // ---- lead 2026-11-13: a three-character username, end to end through the saved memo and its outbox record

    @Test fun aThreeCharacterUsernameNumbersASavedMemoAndItsOutboxRecord() = runTest {
        Fx.seedStock(repo, catalog)
        val done = committer(MemoNumbering("abc", 0, 500)).commit(draft(10), catalog)
        assertEquals("abc-261005-001", done.memoNo)
        assertEquals("abc-261005-001", db.captureDao().memo(done.memoUuid)!!.memoNo)
        val payload = db.query("SELECT payload_json FROM outbox WHERE record_type = 'memo'", null).use { it.moveToFirst(); it.getString(0) }
        assertTrue("the queued record carries the number", payload.contains("abc-261005-001"))
    }
}
