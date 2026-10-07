package com.aktcl.aron.feature.memo.ui

import com.aktcl.aron.core.printing.flow.PrintAttempt
import com.aktcl.aron.feature.memo.domain.DueCollectionDraft
import com.aktcl.aron.feature.memo.domain.MemoItem
import com.aktcl.aron.feature.memo.domain.PrintNames
import com.aktcl.aron.feature.memo.domain.StoredCollection
import com.aktcl.aron.feature.memo.domain.StoredMemo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MemoViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val credit = StoredMemo("m1", "sr1-261005-001", 1, "2026-10-05", "2026-10-05T04:00:00.000Z", null, listOf(MemoItem(105, 3, 28_000, 84_000)), emptyList(), emptyList(), 84_000, 0, 0, 0, 84_000, 34_000, 50_000)

    private class Fake(val memo: StoredMemo) : MemoStore, DueCollectionWriter {
        val collected = ArrayList<StoredCollection>(); val writes = ArrayList<DueCollectionDraft>()
        override suspend fun memos(businessDate: String) = listOf(memo)
        override suspend fun collections(businessDate: String) = collected.toList()
        override suspend fun write(draft: DueCollectionDraft) { writes += draft; collected += StoredCollection(draft.againstMemoUuid, draft.amountMtk, draft.againstMemoBusinessDate) }
    }

    private fun vm(f: Fake, prints: MutableList<String> = ArrayList()) = MemoViewModel(f, f, { id, p -> prints += "$id:${p.kind}"; PrintAttempt.Done }, { PrintNames({ "S$it" }, { "O$it" }, "sr", "r") }, { "2026-10-05" })

    @Test fun markPaidSettlesOnceAndTheButtonGoes() {
        val f = Fake(credit); val v = vm(f)
        v.open("m1")
        assertTrue(v.state.value.canMarkPaid); assertEquals(50_000, v.state.value.remainingDueMtk)
        v.markPaid(); v.markPaid() // a double tap
        assertEquals(1, f.writes.size); assertEquals(50_000, f.writes.single().amountMtk)
        assertFalse(v.state.value.canMarkPaid); assertEquals(0, v.state.value.outletDueMtk)
    }

    @Test fun reprintGoesThroughThePrinterPortWithTheStoredMemo() {
        val f = Fake(credit); val prints = ArrayList<String>(); val v = vm(f, prints)
        v.open("m1"); v.reprint()
        assertEquals(listOf("m1:credit_memo"), prints); assertEquals(PrintAttempt.Done, v.state.value.lastPrint)
    }

    @Test fun nothingSelectedMeansNoMarkPaidAndNoPrint() {
        val f = Fake(credit); val v = vm(f); v.refresh()
        assertEquals(1, v.state.value.rows.size); assertFalse(v.state.value.canMarkPaid)
        v.markPaid(); v.reprint()
        assertEquals(0, f.writes.size)
    }
}
