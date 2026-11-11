package com.aktcl.aron.feature.amo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-AMO-SHELL-10: the real call-targets source, on the contract answer (`contract/slices/schemas/CallTargets.yaml`, contract 1.11.1). */
class CachedCallTargetsTest {
    /** An answer exactly as the contract describes it: the seven required members, a null `target` where the programme is not set. */
    private val answer = """{"as_of":"2026-11-10T09:30:00.000Z","business_date":"2026-11-10","period":"2026-11",
        "today":{"done":3,"target":5},"month":{"done":42,"target":130},"control":{"done":12,"target":null},"joint":{"done":4,"target":20}}"""
    private val other = """{"as_of":"2026-11-10T10:00:00.000Z","business_date":"2026-11-10","period":"2026-11",
        "today":{"done":4,"target":5},"month":{"done":43,"target":130},"control":{"done":13,"target":null},"joint":{"done":4,"target":20}}"""

    private class MemStore(val rows: MutableMap<String, String> = mutableMapOf(), var failWrites: Boolean = false) : CallTargetsStore {
        override suspend fun read(month: String) = rows[month]
        override suspend fun write(month: String, json: String) { if (failWrites) error("disk full"); rows[month] = json }
        override suspend fun clear(month: String) { rows.remove(month) }
    }

    private fun feed(store: CallTargetsStore, vararg script: CallTargetsFetch, timeoutMs: Long = 8_000): CachedCallTargets {
        var i = 0
        return CachedCallTargets({ script[minOf(i++, script.size - 1)] }, store, timeoutMs)
    }

    @Test fun theContractAnswerParsesIntoFourCardsAndANullTargetIsADash() {
        val t = CallTargets.parse(answer)!!
        assertEquals(CallTarget(3, 5), t.today)
        assertEquals(CallTarget(42, 130), t.month)
        assertEquals(CallTarget(12, null), t.control)
        assertEquals("12/—", CallTargetMath.figure(t.control)) // done over a dash (D-AMO-SHELL-11)
        assertEquals("3/5", CallTargetMath.figure(t.today))
        assertEquals("4/20", CallTargetMath.figure(t.joint))
    }

    @Test fun aGoodReadIsFreshAndKeptPerMonth() = runBlocking {
        val store = MemStore()
        val r = feed(store, CallTargetsFetch.Fresh(answer)).load("2026-11")
        assertEquals(CallTargetsStatus.Fresh, r.status)
        assertEquals(CallTarget(42, 130), r.targets!!.month)
        assertEquals(answer, store.rows["2026-11"])
        assertNull(store.rows["2026-10"])
    }

    @Test fun aFailedReadShowsTheKeptCopyAsStaleNeverABlank() = runBlocking {
        val store = MemStore(mutableMapOf("2026-11" to answer))
        val r = feed(store, CallTargetsFetch.Unavailable).load("2026-11")
        assertEquals(CallTargetsStatus.Stale, r.status)
        assertEquals(CallTarget(3, 5), r.targets!!.today)
    }

    @Test fun afterAGoodReadALaterFailureKeepsTheLastGoodValuesEvenIfTheStoreIsGone() = runBlocking {
        val store = MemStore(failWrites = true) // the copy cannot be stored: the in-memory copy still holds
        val f = feed(store, CallTargetsFetch.Fresh(answer), CallTargetsFetch.Unavailable)
        assertEquals(CallTargetsStatus.Fresh, f.load("2026-11").status)
        val second = f.load("2026-11")
        assertEquals(CallTargetsStatus.Stale, second.status)
        assertEquals(CallTarget(42, 130), second.targets!!.month)
    }

    @Test fun aMalformedAnswerIsAFailureAndKeepsTheOldCopy() = runBlocking {
        val store = MemStore(mutableMapOf("2026-11" to answer))
        val r = feed(store, CallTargetsFetch.Fresh("""{"today":{"done":1}}""")).load("2026-11")
        assertEquals(CallTargetsStatus.Stale, r.status)
        assertEquals(answer, store.rows["2026-11"]) // the bad body never replaced the good copy
    }

    @Test fun noCopyAndNoAnswerIsFailedWithNoTargets() = runBlocking {
        val r = feed(MemStore(), CallTargetsFetch.Unavailable).load("2026-11")
        assertEquals(CallTargetsStatus.Failed, r.status)
        assertNull(r.targets)
    }

    @Test fun aRefusalDropsTheCopyBecauseTheServerNoLongerOffersTheCards() = runBlocking {
        val store = MemStore(mutableMapOf("2026-11" to answer))
        val f = feed(store, CallTargetsFetch.Fresh(answer), CallTargetsFetch.Gone)
        f.load("2026-11")
        val r = f.load("2026-11")
        assertNull(r.targets); assertEquals(CallTargetsStatus.Fresh, r.status) // nothing to nag about
        assertNull(store.rows["2026-11"])
    }

    @Test fun eachMonthKeepsItsOwnCopy() = runBlocking {
        val store = MemStore(mutableMapOf("2026-10" to other))
        val r = feed(store, CallTargetsFetch.Unavailable).load("2026-11")
        assertEquals(CallTargetsStatus.Failed, r.status) // October's copy is never shown for November
    }

    @Test fun aMonthRolloverNeverKeepsTheOldMonthsNumbers() = runBlocking {
        // the store cannot hold copies, so only the in-memory last good value exists: it belongs to October and must not be shown for November
        val store = MemStore(failWrites = true)
        val f = feed(store, CallTargetsFetch.Fresh(answer), CallTargetsFetch.Unavailable, CallTargetsFetch.Unavailable)
        assertEquals(CallTargetsStatus.Fresh, f.load("2026-10").status)
        val november = f.load("2026-11")
        assertEquals(CallTargetsStatus.Failed, november.status)
        assertNull(november.targets) // dashes, not October's figures
        assertEquals(CallTargetsStatus.Failed, f.load("2026-11").status)
    }

    @Test fun aMalformedAnswerWithNoCopyIsFailed() = runBlocking {
        val r = feed(MemStore(), CallTargetsFetch.Fresh("""{"today":{"done":1}}""")).load("2026-11")
        assertEquals(CallTargetsStatus.Failed, r.status)
        assertNull(r.targets)
    }

    @Test fun aRefusalForOneMonthLeavesAnotherMonthsCopy() = runBlocking {
        val store = MemStore(mutableMapOf("2026-10" to other, "2026-11" to answer))
        val f = feed(store, CallTargetsFetch.Gone)
        f.load("2026-11")
        assertNull(store.rows["2026-11"])
        assertEquals(other, store.rows["2026-10"])
    }

    @Test fun aSlowReadIsCutAndTheCopyIsShown() = runBlocking {
        val store = MemStore(mutableMapOf("2026-11" to answer))
        val slow = CachedCallTargets({ delay(60_000); CallTargetsFetch.Fresh(other) }, store, timeoutMs = 50)
        val r = slow.load("2026-11")
        assertEquals(CallTargetsStatus.Stale, r.status)
        assertEquals(CallTarget(3, 5), r.targets!!.today)
    }

    @Test fun aThrowingFetchIsAFailureButCancellationPropagates() {
        val store = MemStore(mutableMapOf("2026-11" to answer))
        val boom = CachedCallTargets({ error("boom") }, store)
        assertEquals(CallTargetsStatus.Stale, runBlocking { boom.load("2026-11") }.status)
        val cancelled = CachedCallTargets({ throw CancellationException("stop") }, store)
        assertTrue(runCatching { runBlocking { cancelled.load("2026-11") } }.exceptionOrNull() is CancellationException)
    }

    @Test fun theFixtureSourceStaysUnset() = runBlocking {
        assertNull(UnsetCallTargets.load("2026-11"))
        assertNotNull(CallTargetsStatus.valueOf("Fresh"))
    }
}
