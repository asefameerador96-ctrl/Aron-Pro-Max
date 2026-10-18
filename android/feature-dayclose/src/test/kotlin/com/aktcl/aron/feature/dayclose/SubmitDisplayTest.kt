package com.aktcl.aron.feature.dayclose

import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.core.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The grouped Sales Submit table is DISPLAY ONLY: the proof is (1) the grouping math, (2) it never changes its input, and (3) the submit gate
 * and the rows handed to the day_submit writer are the per-type rows, identical with and without a grouping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubmitDisplayTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val nine = listOf(
        listOf("visit"), listOf("memo"), listOf("stock_movement"), listOf("qc_line"), listOf("memo_discount"),
        listOf("distribution_check", "distribution_check_line"), listOf("price_compliance_check"),
        listOf("call_assessment", "call_assessment_answer"), listOf("survey_response"),
    )

    @Test fun withoutGroupsEveryRowIsOneViewInOrder() {
        val counts = listOf(CountRow("memo", 6, 6), CountRow("visit", 4, null))
        assertEquals(listOf(CountView("memo", 6, 6, true), CountView("visit", 4, null, false)), SubmitDisplay.views(counts, null))
    }

    @Test fun membersAreSummedAndNamedByTheFirstTypeOfTheGroupInTheGroupsOrder() {
        val counts = listOf(CountRow("memo", 6, 6), CountRow("distribution_check_line", 9, 9), CountRow("distribution_check", 3, 3), CountRow("visit", 4, 4))
        assertEquals(
            listOf(CountView("visit", 4, 4, true), CountView("memo", 6, 6, true), CountView("distribution_check", 12, 12, true)),
            SubmitDisplay.views(counts, nine),
        )
    }

    @Test fun aGroupIsWaitingWhenAnyMemberServerCountIsUnknownAndMatchedOnlyWhenEveryMemberMatches() {
        val waiting = SubmitDisplay.views(listOf(CountRow("distribution_check", 3, 3), CountRow("distribution_check_line", 9, null)), nine).single()
        assertNull(waiting.server); assertFalse(waiting.matches); assertEquals(12, waiting.device)
        // +1 on one member and -1 on the other: the sums are equal (6 = 6) but the group must NOT read as matched
        val cancelling = SubmitDisplay.views(listOf(CountRow("call_assessment", 4, 5), CountRow("call_assessment_answer", 2, 1)), nine).single()
        assertEquals(6, cancelling.device); assertEquals(6, cancelling.server); assertFalse(cancelling.matches)
    }

    @Test fun typesOutsideTheNineStayAsTheirOwnRowsAfterThemSoNoCountIsHidden() {
        val counts = listOf(CountRow("attendance_event", 2, 2), CountRow("memo", 6, 6), CountRow("visit_close", 5, 4))
        assertEquals(listOf("memo", "attendance_event", "visit_close"), SubmitDisplay.views(counts, nine).map { it.key })
        assertFalse(SubmitDisplay.views(counts, nine).last().matches)
    }

    @Test fun groupingNeverChangesItsInput() {
        val counts = listOf(CountRow("distribution_check", 3, 3), CountRow("distribution_check_line", 9, 8), CountRow("memo", 6, 6))
        val copy = counts.toList()
        SubmitDisplay.views(counts, nine)
        assertEquals(copy, counts)
        assertSame(counts.size, copy.size)
    }

    // ---- the gate and the day_submit writer see the per-type rows, with or without a grouping ----

    private class Day : DaySource {
        override suspend fun outboxStates(businessDate: String) = OutboxStates(0, 0, 10, 0, 0)
        override suspend fun deviceCounts(businessDate: String) = mapOf("memo" to 6, "distribution_check" to 3, "distribution_check_line" to 9, "visit" to 4)
        override suspend fun dues(businessDate: String) = DuesAtSubmit(0, 0)
        override suspend fun alreadySubmitted(businessDate: String) = false
        override suspend fun submitSettled(businessDate: String) = false
    }
    private class Sched : SyncScheduler { override fun requestSync(userId: Long, trigger: SyncTrigger) {} }

    @Test fun theGateAndTheWriterGetTheSamePerTypeRowsWhateverTheGrouping() {
        val server = mapOf("memo" to 6, "distribution_check" to 3, "distribution_check_line" to 8, "visit" to 4) // one line short on the server
        val queued = ArrayList<Pair<SubmitGate, List<CountRow>>>()
        val vm = SalesSubmitViewModel(1, { "2026-10-05" }, Day(), { server }, { _, g, c -> queued += g to c }, Sched(), { true })
        vm.refresh()
        val perType = vm.state.value.counts
        val gateBefore = vm.state.value.gate
        // the grouped display is computed from the very same list; it must leave the list, the gate and what the writer gets untouched
        val shown = SubmitDisplay.views(perType, nine)
        assertEquals(4, perType.size); assertEquals(3, shown.size)
        assertEquals(gateBefore, SalesSubmitRules.gate(OutboxStates(0, 0, 10, 0, 0), perType, DuesAtSubmit(0, 0), false))
        assertFalse(vm.state.value.gate.enabled) // the mismatch of ONE member blocks the submit, as it does without grouping
        assertTrue(gateBefore.blocks.any { it is SubmitBlock.CountMismatch && it.recordType == "distribution_check_line" })
        // fix the server count: the submit goes out with the per-type rows, byte for byte what the phone counted
        val synced = SalesSubmitViewModel(1, { "2026-10-05" }, Day(), { server + ("distribution_check_line" to 9) }, { _, g, c -> queued += g to c }, Sched(), { true })
        synced.refresh(); synced.submit()
        val (gate, rows) = queued.single()
        assertTrue(gate.enabled)
        assertEquals(synced.state.value.counts, rows)
        assertEquals(listOf("distribution_check", "distribution_check_line", "memo", "visit"), rows.map { it.recordType })
        assertEquals(listOf(3, 9, 6, 4), rows.map { it.device }); assertEquals(listOf(3, 9, 6, 4), rows.map { it.server })
    }
}
