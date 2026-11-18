package com.aktcl.aron.feature.programmes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phone rule B (docs/requests/sr-programmes-phone-rule-b.md): every interleaving of "the phone sends the bundle request" with the commits and acknowledgements of its records must show exactly the
 * true total, never less and never more. The simulator is the server's side of rule A: a figure that holds every memo committed (and acknowledged, single flight) when the bundle was read,
 * dated from the 1st of the month through `through_date`, with voided and superseded memos out.
 */
class FigureRuleBTest {
    private val month = "2026-11"
    private val sku = 5L

    private data class Rec(val uuid: String, val date: String, val qty: Long, val supersedes: String? = null, val voidOf: String? = null, val route: Long = 7L, val kind: String = "sale", val lineKind: String = "sale")

    /** The server: committed records in commit order; the figure through [through] as of a prefix of the commits. */
    private fun serverFigure(committed: List<Rec>, through: String): FigureRuleB.ServerFigure {
        val dead = committed.flatMap { listOfNotNull(it.supersedes, it.voidOf) }.toSet()
        val held = committed.filter { it.voidOf == null && it.uuid !in dead && it.kind == "sale" && it.date.take(7) == month && it.date <= through && it.route == 7L }
        return FigureRuleB.ServerFigure(held.sumOf { it.qty }, held.size.toLong(), held.sumOf { it.qty * 100 }, mapOf(20 to held.sumOf { it.qty }).filterValues { it != 0L }, "as-of text, never arithmetic")
    }

    private fun truth(all: List<Rec>): Pair<Long, Long> {
        val dead = all.flatMap { listOfNotNull(it.supersedes, it.voidOf) }.toSet()
        val live = all.filter { it.voidOf == null && it.uuid !in dead && it.kind == "sale" && it.date.take(7) == month && it.route == 7L }
        return live.sumOf { it.qty } to live.size.toLong()
    }

    private fun memoOf(r: Rec, state: String) = LocalMemo(r.uuid, 1L, r.date, r.supersedes, state, null, if (r.voidOf != null) emptyList() else listOf(LocalMemoLine(sku, r.lineKind, r.qty, 20, r.qty * 100)), r.route, r.kind, r.voidOf)

    /**
     * The script is the order in which records are written on the phone and committed (with their acknowledgement, atomic with respect to the request: single flight). Every
     * position of the request R (before the first commit, between any two, after the last) is tried; what the phone shows at the end must be the truth.
     */
    private fun everyInterleaving(script: List<Rec>, bundleDate: String, expect: Pair<Long, Long>? = null) {
        val through = java.time.LocalDate.parse(bundleDate).minusDays(1).toString()
        for (r in 0..script.size) {
            val atRequest = script.take(r)
            val figure = serverFigure(atRequest, through)
            val acked = atRequest.map { it.uuid }.toSet()
            // at display time every record has been written; the ones committed after the request are acknowledged after it
            val memos = script.mapIndexed { i, rec -> memoOf(rec, if (i < r) "acked" else if (i % 2 == 0) "acked" else "pending") }
            val shown = FigureRuleB.show(figure, memos, acked, month, through)!!
            val (q, n) = expect ?: truth(script)
            assertEquals("quantity with the request after $r commits", q, shown.qtyBase)
            assertEquals("memo count with the request after $r commits", n, shown.memoCount)
            assertEquals("value with the request after $r commits", q * 100, shown.grossMtk)
        }
    }

    @Test fun twoOfflineDaysSyncedInTwoBatchesBundlePulledAtEveryPoint() {
        everyInterleaving(listOf(Rec("a1", "2026-11-03", 10), Rec("a2", "2026-11-03", 20), Rec("b1", "2026-11-04", 30), Rec("b2", "2026-11-04", 40)), "2026-11-05")
    }

    @Test fun aMemoOfTheBundleDateItselfIsAddedAckedOrNotBecauseTheFigureIsCutAtThroughDate() {
        // today's memo (the bundle date) acknowledged BEFORE the request: the server figure is cut at through_date, so it is still added
        everyInterleaving(listOf(Rec("a1", "2026-11-03", 10), Rec("t1", "2026-11-05", 7), Rec("t2", "2026-11-05", 8)), "2026-11-05")
        val figure = serverFigure(listOf(Rec("t1", "2026-11-05", 7)), "2026-11-04")
        assertEquals(0L, figure.qtyBase)
        val shown = FigureRuleB.show(figure, listOf(memoOf(Rec("t1", "2026-11-05", 7), "acked")), setOf("t1"), month, "2026-11-04")!!
        assertEquals(7L, shown.qtyBase); assertEquals(1L, shown.memoCount)
    }

    @Test fun anEditWithIdenticalLinesAtEveryPointShowsTheOriginalTotalNeverTwice() {
        val script = listOf(Rec("m1", "2026-11-03", 10), Rec("m2", "2026-11-03", 20), Rec("e1", "2026-11-03", 10, supersedes = "m1"))
        everyInterleaving(script, "2026-11-05", expect = 30L to 2L)
        // and the edit of a memo of an earlier, already synced day, with the edit written on the bundle's own day
        everyInterleaving(listOf(Rec("m1", "2026-11-03", 10), Rec("e1", "2026-11-05", 10, supersedes = "m1")), "2026-11-05", expect = 10L to 1L)
    }

    @Test fun aChainOfEditsAtEveryPoint() {
        everyInterleaving(listOf(Rec("m1", "2026-11-03", 10), Rec("e1", "2026-11-03", 12, supersedes = "m1"), Rec("e2", "2026-11-03", 15, supersedes = "e1")), "2026-11-05", expect = 15L to 1L)
    }

    @Test fun aVoidFollowedByAReSaleAtEveryPoint() {
        val script = listOf(Rec("m1", "2026-11-03", 10), Rec("m2", "2026-11-04", 5), Rec("v1", "2026-11-04", 0, voidOf = "m1"), Rec("s1", "2026-11-04", 11))
        everyInterleaving(script, "2026-11-05", expect = 16L to 2L)
    }

    @Test fun aBundleRequestedRightAfterTheUploadAckHoldsTheMemoAndNothingIsAddedAgain() {
        val m = Rec("m1", "2026-11-03", 10)
        val shown = FigureRuleB.show(serverFigure(listOf(m), "2026-11-04"), listOf(memoOf(m, "acked")), setOf("m1"), month, "2026-11-04")!!
        assertEquals(10L, shown.qtyBase); assertEquals(1L, shown.memoCount)
        assertTrue(shown.localApplied)
    }

    @Test fun fortyMemosAckedBeforeTheRequestAreNeverAddedAgain() {
        val recs = (1..40).map { Rec("m$it", "2026-11-03", 3) }
        val shown = FigureRuleB.show(serverFigure(recs, "2026-11-04"), recs.map { memoOf(it, "acked") }, recs.map { it.uuid }.toSet(), month, "2026-11-04")!!
        assertEquals(120L, shown.qtyBase); assertEquals(40L, shown.memoCount)
    }

    @Test fun aMemoInTheSetButBackToPendingAfterARestoreIsStillInsideTheServerFigure() {
        val m = Rec("m1", "2026-11-03", 10)
        val shown = FigureRuleB.show(serverFigure(listOf(m), "2026-11-04"), listOf(memoOf(m, "pending")), setOf("m1"), month, "2026-11-04")!!
        assertEquals(10L, shown.qtyBase)
    }

    @Test fun editWhoseOwnRecordWasAckedBeforeTheRequestIsInsideTheFigureAndLeftAlone() {
        val m = Rec("m1", "2026-11-03", 10); val e = Rec("e1", "2026-11-03", 12, supersedes = "m1")
        val shown = FigureRuleB.show(serverFigure(listOf(m, e), "2026-11-04"), listOf(memoOf(m, "acked"), memoOf(e, "acked")), setOf("m1", "e1"), month, "2026-11-04")!!
        assertEquals(12L, shown.qtyBase); assertEquals(1L, shown.memoCount)
    }

    // ---- absence and guards ----

    @Test fun withoutAMonthTheServerFigureIsShownAsItIsWithItsAsOfAndWithoutAnOrderOnlyNeverSentMemosAreAdded() {
        val server = FigureRuleB.ServerFigure(100, 4, 9000, mapOf(20 to 100L), "2026-11-04T10:00:00Z")
        val local = listOf(memoOf(Rec("m1", "2026-11-05", 10), "pending"), memoOf(Rec("m2", "2026-11-05", 7), "acked"))
        val noMonth = FigureRuleB.show(server, local, emptySet(), null, "2026-11-04")!!
        assertEquals(100L, noMonth.qtyBase); assertEquals(4L, noMonth.memoCount); assertFalse(noMonth.localApplied); assertEquals("2026-11-04T10:00:00Z", noMonth.asOf)
        val noOrder = FigureRuleB.show(server, local, null, month, "2026-11-04")!!
        assertEquals(110L, noOrder.qtyBase); assertEquals(5L, noOrder.memoCount) // the never-sent memo is certainly not in the figure; the acknowledged one may be, so it is not added
        assertNull(FigureRuleB.show(null, local, emptySet(), month, null))
    }

    @Test fun withoutThroughDateEveryUnacknowledgedMemoOfTheMonthIsAddedAndNothingIsCutLate() {
        val shown = FigureRuleB.show(FigureRuleB.ServerFigure(10, 1), listOf(memoOf(Rec("m1", "2026-11-05", 5), "pending"), memoOf(Rec("m0", "2026-10-30", 9), "pending")), emptySet(), month, null)!!
        assertEquals(15L, shown.qtyBase) // the October memo is outside the bundle month: not touched
    }

    @Test fun aPhoneStillHoldingLastMonthsBundleAddsNothingDatedInTheNewMonth() {
        val shown = FigureRuleB.show(FigureRuleB.ServerFigure(10, 1), listOf(memoOf(Rec("m1", "2026-12-02", 5), "pending")), emptySet(), "2026-11", "2026-11-29")!!
        assertEquals(10L, shown.qtyBase); assertEquals(1L, shown.memoCount)
    }

    @Test fun aRefusedMemoCountsForNothingAndAZeroSaleMemoAddsNoMemoCount() {
        val rejected = memoOf(Rec("r1", "2026-11-05", 5), "rejected")
        val zero = memoOf(Rec("z1", "2026-11-05", 0, kind = "zero_sale"), "pending")
        val shown = FigureRuleB.show(FigureRuleB.ServerFigure(10, 1), listOf(rejected, zero), emptySet(), month, "2026-11-04")!!
        assertEquals(10L, shown.qtyBase); assertEquals(1L, shown.memoCount)
    }

    @Test fun aRefusedEditDoesNotRetireTheOriginal() {
        val m = Rec("m1", "2026-11-03", 10)
        val e = memoOf(Rec("e1", "2026-11-03", 12, supersedes = "m1"), "rejected")
        val shown = FigureRuleB.show(serverFigure(listOf(m), "2026-11-04"), listOf(memoOf(m, "acked"), e), setOf("m1"), month, "2026-11-04")!!
        assertEquals(10L, shown.qtyBase)
    }

    @Test fun aMemoSentButUnansweredIsNeitherAddedNorSubtractedAndIsCounted() {
        val m = memoOf(Rec("m1", "2026-11-03", 10), "uncertain")
        val shown = FigureRuleB.show(FigureRuleB.ServerFigure(0, 0), listOf(m), setOf(), month, "2026-11-04")!!
        assertEquals(0L, shown.qtyBase)
        assertEquals(1, FigureRuleB.awaiting(listOf(m)))
    }

    @Test fun onlyMemosOfThisRouteAndLinesOfThisFigureCount() {
        val here = memoOf(Rec("a", "2026-11-05", 5), "pending")
        val other = memoOf(Rec("b", "2026-11-05", 7, route = 9), "pending")
        val free = here.copy(uuid = "c", lines = listOf(LocalMemoLine(sku, "free", 3, 20, 0)))
        val shown = FigureRuleB.show(FigureRuleB.ServerFigure(0, 0), listOf(here, other, free), emptySet(), month, "2026-11-04", memoIn = { it.routeId == 7L })!!
        assertEquals(5L, shown.qtyBase); assertEquals(1L, shown.memoCount)
        val noSku = FigureRuleB.show(FigureRuleB.ServerFigure(0, 0), listOf(here), emptySet(), month, "2026-11-04", lineIn = { it.skuId == 99L })!!
        assertEquals(0L, noSku.memoCount)
    }

    @Test fun byPackFollowsEachPackSizeAndNeverGoesNegative() {
        val a = memoOf(Rec("a", "2026-11-05", 5), "pending").copy(lines = listOf(LocalMemoLine(sku, "sale", 5, 10, 500)))
        val shown = FigureRuleB.show(FigureRuleB.ServerFigure(100, 2, 0, mapOf(20 to 100L)), listOf(a), emptySet(), month, "2026-11-04")!!
        assertEquals(mapOf(20 to 100L, 10 to 5L), shown.byPack)
    }

    // ---- day counts ----

    @Test fun dayCountsIncludeTodayWhenItIsAWorkingDayAndAZeroDivisorGivesNoFigure() {
        val c = RouteCalendar(workingDays = 26, elapsedBeforeToday = 3, remainingAfterToday = 22, todayIsWorking = true)
        assertEquals(4, c.daysCovered)
        assertEquals(2_500L, c.perDayMilli(10_000))
        assertEquals(1_000L, c.requiredPerDayMilli(30_000, 8_000)) // (30 - 8) / 22 = 1.000
        val off = RouteCalendar(26, 3, 22, todayIsWorking = false)
        assertEquals(3, off.daysCovered)
        assertNull(RouteCalendar(26, 0, 25, false).perDayMilli(5_000))
        assertNull(RouteCalendar(26, 25, 0, true).requiredPerDayMilli(5_000, 1_000))
    }

    // ---- the server's definitions of a memo count (request file, "Definitions") ----

    @Test fun aBundleOnTheFirstOfTheMonthAddsEveryMemoOfTheMonthWhateverItsAckState() {
        // bundle date 2026-11-01: through_date is 2026-10-31, the server figure of November is empty
        val a = Rec("a", "2026-11-01", 4); val b = Rec("b", "2026-11-01", 6)
        val shown = FigureRuleB.show(FigureRuleB.ServerFigure(0, 0), listOf(memoOf(a, "acked"), memoOf(b, "pending")), setOf("a"), month, "2026-10-31")!!
        assertEquals(10L, shown.qtyBase); assertEquals(2L, shown.memoCount)
    }

    @Test fun twoRoutesAreKeptApartByTheMemoFilter() {
        val r7 = memoOf(Rec("a", "2026-11-05", 4), "pending"); val r9 = memoOf(Rec("b", "2026-11-05", 6, route = 9), "pending")
        assertEquals(4L, FigureRuleB.show(FigureRuleB.ServerFigure(0, 0), listOf(r7, r9), emptySet(), month, "2026-11-04", memoIn = { it.routeId == 7L })!!.qtyBase)
        assertEquals(6L, FigureRuleB.show(FigureRuleB.ServerFigure(0, 0), listOf(r7, r9), emptySet(), month, "2026-11-04", memoIn = { it.routeId == 9L })!!.qtyBase)
    }

    @Test fun aQuarantinedMemoIsNeverAddedInAnyFamily() {
        val q = memoOf(Rec("q", "2026-11-05", 9), "quarantined")
        assertEquals(0L, FigureRuleB.show(FigureRuleB.ServerFigure(0, 0), listOf(q), emptySet(), month, "2026-11-04")!!.qtyBase)
        assertEquals(0L, FigureRuleB.show(FigureRuleB.ServerFigure(0, 0), listOf(q), null, month, "2026-11-04")!!.qtyBase)
        assertEquals(1, FigureRuleB.heldForReview(listOf(q)))
    }
}
