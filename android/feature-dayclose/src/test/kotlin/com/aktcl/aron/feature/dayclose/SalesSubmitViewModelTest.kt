package com.aktcl.aron.feature.dayclose

import com.aktcl.aron.core.sync.SyncScheduler
import com.aktcl.aron.contract.SyncTrigger
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
class SalesSubmitViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private class Day : DaySource {
        var states = OutboxStates(0, 0, 10, 0, 0); var device = mapOf("memo" to 6); var submitted = false; var settled = false
        override suspend fun outboxStates(businessDate: String) = states
        override suspend fun deviceCounts(businessDate: String) = device
        override suspend fun dues(businessDate: String) = DuesAtSubmit(1, 50_000)
        override suspend fun alreadySubmitted(businessDate: String) = submitted
        override suspend fun submitSettled(businessDate: String) = settled
    }
    private class Sched : SyncScheduler { val calls = ArrayList<SyncTrigger>(); override fun requestSync(userId: Long, trigger: SyncTrigger) { calls += trigger } }

    private fun vm(day: Day, server: Map<String, Int>?, sched: Sched, queued: MutableList<SubmitGate> = ArrayList(), online: Boolean = true) =
        SalesSubmitViewModel(1, { "2026-10-05" }, day, { server }, { _, g, _ -> queued += g }, sched, { online })

    @Test fun submitQueuesOnceAsksForTheDaySubmitUploadAndShowsQueuedUntilSettled() {
        val day = Day(); val s = Sched(); val q = ArrayList<SubmitGate>()
        val v = vm(day, mapOf("memo" to 6), s, q)
        v.refresh(); assertTrue(v.state.value.gate.enabled)
        v.submit(); v.submit()
        assertEquals(1, q.size); assertEquals(listOf(SyncTrigger.DAY_SUBMIT), s.calls); assertEquals(SubmitProgress.Queued, v.state.value.progress)
        day.submitted = true; day.settled = true; v.refresh()
        assertEquals(SubmitProgress.Settled, v.state.value.progress)
    }

    @Test fun blankServerColumnBlocksSubmit() {
        val v = vm(Day(), null, Sched()); v.refresh()
        assertFalse(v.state.value.gate.enabled); assertEquals(null, v.state.value.counts.single().server)
        v.submit()
        assertEquals(SubmitProgress.Idle, v.state.value.progress)
    }

    @Test fun syncNeedsConnectionAndAsksForAManualUpload() {
        val s = Sched(); val offline = vm(Day(), mapOf("memo" to 6), s, online = false)
        offline.sync(); assertTrue(s.calls.isEmpty())
        val on = vm(Day(), mapOf("memo" to 6), s); on.sync()
        assertEquals(listOf(SyncTrigger.MANUAL), s.calls)
    }

    @Test fun anAlreadySubmittedDayCannotBeSubmittedAgain() {
        val day = Day().apply { submitted = true }; val q = ArrayList<SubmitGate>()
        val v = vm(day, mapOf("memo" to 6), Sched(), q); v.refresh(); v.submit()
        assertTrue(q.isEmpty())
    }
}
