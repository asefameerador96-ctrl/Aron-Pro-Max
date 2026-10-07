package com.aktcl.aron.feature.sale.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.rules.QtyUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Independent checker (T1): each test fails on a real defect against docs/24 s4/s7 and docs/15 F-SR-023..033. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class CheckerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AronDatabase
    private lateinit var repo: CaptureRepository
    private val numbers = FakeNumbers()
    private lateinit var visitUuid: String

    private fun committer(newUuid: () -> String = ClientIds::newUuid) = SaleCommitter(
        repo, numbers, { _, r -> Fx.meta().copy(routeId = r) }, { "2026-10-05T04:35:00.000Z" }, newUuid, findMemo = { db.captureDao().memo(it) },
    )

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
        repo = CaptureRepository(db) { "2026-10-05T04:36:00.000Z" }
        visitUuid = ClientIds.newUuid()
        val (v, f) = Fx.visit(visitUuid)
        repo.recordVisitOpen(v, f)
    }
    @After fun tearDown() = db.close()

    private fun editFix(memoUuid: String) = GeoFixEntity(
        ClientIds.newUuid(), memoUuid, "memo_edit", "ok", 23.79, 90.40, 10.0, provider = "fused",
        isMock = false, reused = false, deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false,
    )

    // docs/24 s7.5: "The counter is stored in Room and incremented in the same transaction as the memo."
    // SaleCommitter calls numbers.next() before (outside) recordSale's transaction, so a rolled-back commit burns a number.
    @Test fun aRolledBackCommitDoesNotConsumeAMemoNumber() = runTest {
        val d = SaleDraftOps.setQuantity(SaleDraftOps.setQuantity(Fx.draft(visitUuid), 100, 20, QtyUnit.STICK), 103, 3, QtyUnit.PIECE)
        val clash = ClientIds.newUuid()
        var calls = 0
        val bad = committer { calls++; if (calls <= 2) clash else ClientIds.newUuid() } // two lines share a uuid -> transaction aborts
        runCatching { runBlocking { bad.commit(d, Fx.catalog) } }
        assertEquals(0, db.captureDao().memosOn(Fx.DATE).size)
        val ok = committer().commit(d, Fx.catalog)
        assertEquals("sr334001-261005-001", ok.memoNo) // actual: -002, -001 was burned by the rolled-back commit
    }

    // docs/24 s4.5 `lines_exceed_max` (rejected, not retryable) and F-SR-023 "60-line memo": the phone must not commit a 61-line memo.
    @Test fun aMemoWithMoreThanSixtyLinesIsNotCommittable() {
        val skus = (1L..61L).map { SaleSku(1000 + it, "S$it", "cigarette", "S$it", null, "stick", 10, 8_000, 1, "2026-09-01") }
        var d = Fx.draft(visitUuid)
        skus.forEach { d = SaleDraftOps.setQuantity(d, it.skuId, 10, QtyUnit.STICK) }
        val r = SaleReviewCalculator.review(d, skus.associateBy { it.skuId })
        assertEquals(61, r.lines.size)
        assertFalse("61 lines must be refused on the phone (server rejects lines_exceed_max)", r.canCommit)
    }

    // docs/24 s7.2: unit_entered is the SKU base unit or `pack`. 2 "dozen" on a stick SKU is priced as 2 sticks and stored with unit_entered=dozen.
    @Test fun aUnitThatIsNeitherTheSkuBaseUnitNorPackIsRejected() {
        val d = SaleDraftOps.setQuantity(Fx.draft(visitUuid), Fx.maxr.skuId, 2, QtyUnit.DOZEN)
        val r = SaleReviewCalculator.review(d, Fx.catalog)
        assertFalse("dozen on a cigarette (stick) SKU must not commit", r.canCommit)
    }

    // F-SR-033 "no QC done at the outlet" and s4.5 `edit_not_allowed` (after QC lock): the phone accepts and commits an edit after QC completion.
    @Test fun anEditIsRefusedOnceQcIsCompleted() = runTest {
        val first = committer().commit(SaleDraftOps.setQuantity(Fx.draft(visitUuid), 100, 10, QtyUnit.STICK), Fx.catalog)
        val store = object : DraftStore {
            var d: SaleDraft? = SaleDraftOps.completeQc(SaleDraftOps.setQuantity(Fx.draft(visitUuid), 100, 8, QtyUnit.STICK))
            override fun load(visitUuid: String) = d
            override fun save(draft: SaleDraft) { d = draft }
            override fun clear(visitUuid: String) { d = null }
        }
        val flow = SaleFlow(store, { _, _ -> Fx.catalog.values.toList() }, committer())
        flow.start(SaleVisit(visitUuid, 50001, 10231, Fx.DATE, "outlet"))
        val outcome = runCatching {
            runBlocking {
                flow.withEdit(EditContext(first.memoUuid, "wrong_sku"))
                flow.commit(editFix(store.d!!.memoUuid))
            }
        }
        assertTrue("edit after QC completion must be refused, but it committed ${outcome.getOrNull()}", outcome.isFailure)
    }

    // F-SR-033: the reason is one of cfg.memo.edit_reasons; a blank reason passes both SaleCommitter and recordSale.
    @Test fun anEditWithABlankReasonIsRefused() = runTest {
        val first = committer().commit(SaleDraftOps.setQuantity(Fx.draft(visitUuid), 100, 10, QtyUnit.STICK), Fx.catalog)
        val d = SaleDraftOps.setQuantity(Fx.draft(visitUuid), 100, 8, QtyUnit.STICK).copy(edit = EditContext(first.memoUuid, ""))
        val outcome = runCatching { runBlocking { committer().commit(d, Fx.catalog, editFix(d.memoUuid)) } }
        assertTrue("blank edit reason committed", outcome.isFailure)
    }

    // F-SR-033: a new memo supersedes the OLD one; a memo superseding itself (or any uuid not on the phone) is accepted.
    @Test fun anEditCannotSupersedeItself() = runTest {
        val d0 = SaleDraftOps.setQuantity(Fx.draft(visitUuid), 100, 8, QtyUnit.STICK)
        val d = d0.copy(edit = EditContext(d0.memoUuid, "wrong_sku"))
        val outcome = runCatching { runBlocking { committer().commit(d, Fx.catalog, editFix(d.memoUuid)) } }
        assertTrue("self-superseding memo committed", outcome.isFailure)
    }

    // FileDraftStore promises "a corrupt or unreadable file reads as no draft instead of crashing the sale", but a parseable
    // draft with an unknown unit makes SaleFlow.start throw NoSuchElementException (QtyUnit.entries.first) on every relaunch.
    @Test fun aDraftWithAnUnknownUnitDoesNotBrickTheVisit() = runTest {
        val dir = File(context.cacheDir, "chk-${System.nanoTime()}")
        val store = FileDraftStore(dir)
        store.save(Fx.draft(visitUuid).copy(lines = listOf(DraftLine(100, 2, "carton"))))
        val flow = SaleFlow(store, { _, _ -> Fx.catalog.values.toList() }, committer())
        val outcome = runCatching { runBlocking { flow.start(SaleVisit(visitUuid, 50001, 10231, Fx.DATE, "outlet")) } }
        assertTrue("start threw ${outcome.exceptionOrNull()}", outcome.isSuccess)
    }
}
