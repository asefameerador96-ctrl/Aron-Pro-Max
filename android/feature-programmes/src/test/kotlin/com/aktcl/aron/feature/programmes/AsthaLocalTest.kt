package com.aktcl.aron.feature.programmes

import org.junit.Assert.assertEquals
import org.junit.Test

class AsthaLocalTest {
    private val programmes = """{"astha_as_of":"2026-11-17T10:00:00Z","astha_targets":[
      {"route_id":7,"outlet_id":null,"brand_id":11,"month":"2026-11","std_target":"1000","std_achieved":"100","memo_target":0,"memo_achieved":0,"value_target_mtk":"5000000","value_achieved_mtk":"1000000","by_pack":[{"pack_factor":20,"std_achieved":"100"}]},
      {"route_id":7,"outlet_id":null,"brand_id":null,"month":"2026-11","std_target":"0","std_achieved":"0","memo_target":50,"memo_achieved":10,"value_target_mtk":"0","value_achieved_mtk":"0","by_pack":[]},
      {"route_id":7,"outlet_id":501,"brand_id":11,"month":"2026-11","std_target":"100","std_achieved":"10","memo_target":0,"memo_achieved":0,"value_target_mtk":"0","value_achieved_mtk":"0","by_pack":[]},
      {"route_id":7,"outlet_id":501,"brand_id":null,"month":"2026-11","std_target":"0","std_achieved":"0","memo_target":5,"memo_achieved":1,"value_target_mtk":"0","value_achieved_mtk":"0","by_pack":[]}]}"""
    private val products = """{"nodes":[{"id":1,"level":"category","name":"C","sort":1},{"id":11,"level":"brand","name":"Marise","parent_id":1,"sort":1},{"id":21,"level":"variant","name":"V","parent_id":11,"sort":1}],
      "skus":[{"sku_id":900,"variant_id":21,"category_code":"cigarette","base_per_pack":20}]}"""
    private val d = AsthaModel.parse(programmes, products)!!
    /** The order is the phone's own: the uuids acknowledged when the request was SENT; there is no time in any of these tests. */
    private fun apply(memos: List<LocalMemo>, acked: Set<String>? = emptySet()) = AsthaLocal.apply(d, memos, acked, "2026-11")
    private fun memo(uuid: String, state: String, ackedAt: String? = null, supersedes: String? = null, outlet: Long = 501, qty: Long = 40, gross: Long = 400_000, kind: String = "sale", route: Long? = 7, memoKind: String = "sale") =
        LocalMemo(uuid, outlet, "2026-11-18", supersedes, state, ackedAt, listOf(LocalMemoLine(900, kind, qty, 20, gross)), route, memoKind)
    private fun brandRoute(x: AsthaData) = x.rows.first { it.outletId == null && it.brandId == 11L }
    private fun brandOutlet(x: AsthaData) = x.rows.first { it.outletId == 501L && it.brandId == 11L }
    private fun allRoute(x: AsthaData) = x.rows.first { it.outletId == null && it.brandId == null }

    @Test fun skuToBrandThroughTheProductTree() = assertEquals(mapOf(900L to 11L), d.skuBrand)

    @Test fun anUnacknowledgedMemoIsAddedToTheRouteAndTheOutletRowsAndTheMemoCount() {
        val x = apply(listOf(memo("m1", "pending")))
        assertEquals(140_000L, brandRoute(x).stdAchievedMilli); assertEquals(1_400_000L, brandRoute(x).valueAchievedMtk)
        assertEquals(50_000L, brandOutlet(x).stdAchievedMilli)
        assertEquals(11L, allRoute(x).memoAchieved)
        assertEquals(listOf(AsthaPack(20, 140_000)), brandRoute(x).byPack)
    }

    @Test fun aMemoAcknowledgedBeforeTheRequestIsInsideTheServerFigureAndNotCountedTwice() {
        assertEquals(d.rows, apply(listOf(memo("m1", "acked")), setOf("m1")).rows)
    }

    @Test fun aMemoAcknowledgedAfterTheRequestIsAdded() {
        assertEquals(140_000L, brandRoute(apply(listOf(memo("m1", "acked")), emptySet())).stdAchievedMilli)
    }

    @Test fun aMemoReleasedBackToPendingAfterAServerErrorCountsAsNotAcknowledged() {
        assertEquals(140_000L, brandRoute(apply(listOf(memo("m1", "pending")))).stdAchievedMilli)
    }

    @Test fun withoutTheRecordedOrderAMemoThatNeverLeftThePhoneIsStillAddedAndAnAcknowledgedOneIsNot() {
        // a promoted prefetch or a bundle stored before the order was recorded: a never-sent memo is certainly not in the server figure
        val x = apply(listOf(memo("p", "pending"), memo("a", "acked")), null)
        assertEquals(140_000L, brandRoute(x).stdAchievedMilli) // only the pending one, as before the order existed
        assertEquals(1, AsthaLocal.unknownOrder(listOf(memo("p", "pending"), memo("a", "acked")), orderKnown = false)) // and the acknowledged one is named, not silently dropped
        assertEquals(0, AsthaLocal.unknownOrder(listOf(memo("a", "acked")), orderKnown = true))
        assertEquals(d.rows, AsthaLocal.apply(d, listOf(memo("p", "pending")), emptySet(), null).rows) // no month: nothing
    }

    @Test fun withoutTheRecordedOrderAnEditIsNotAddedAndNothingIsSubtracted() {
        val x = apply(listOf(memo("m", "acked", qty = 10), memo("e", "pending", supersedes = "m", qty = 12)), null)
        assertEquals(d.rows, x.rows) // the original may or may not be in the figure: the edit is left out too (never a double count)
        assertEquals(2, AsthaLocal.unknownOrder(listOf(memo("m", "acked"), memo("e", "pending", supersedes = "m")), orderKnown = false))
    }

    @Test fun anEditChainWithAnUncertainPredecessorIsWholeUncertainAndNeverTheNewestOnTop() {
        val m = memo("m", "acked", qty = 10); val e1 = memo("e1", "uncertain", supersedes = "m", qty = 12); val e2 = memo("e2", "pending", supersedes = "e1", qty = 8)
        val x = apply(listOf(m, e1, e2), setOf("m"))
        assertEquals(d.rows, x.rows) // the figure stays as the server gave it
        assertEquals(2, AsthaLocal.awaiting(listOf(m, e1, e2))) // E1 and the E2 that depends on it
        // an edit of an uncertain ORIGINAL
        val e = memo("e", "pending", supersedes = "u", qty = 5)
        assertEquals(d.rows, apply(listOf(memo("u", "uncertain"), e)).rows)
        assertEquals(2, AsthaLocal.awaiting(listOf(memo("u", "uncertain"), e)))
    }

    @Test fun aSupersededLocalMemoIsNotCountedAndAnEditOfAServerHeldMemoTakesTheOldOneOut() {
        val old = memo("old", "acked", qty = 40)
        val edit = memo("new", "pending", supersedes = "old", qty = 60, gross = 600_000)
        val x = apply(listOf(old, edit), setOf("old"))
        assertEquals(100_000L + 60_000L - 40_000L, brandRoute(x).stdAchievedMilli) // server 100 + new 60 - old 40
        assertEquals(10L, allRoute(x).memoAchieved) // +1 for the edit, -1 for the old memo: one memo, not two
        assertEquals(x.rows, apply(listOf(old, edit), setOf("old")).rows) // recomputed from scratch, never accumulated
    }

    @Test fun refusedMemosAndNonSaleLinesAndOtherOutletsAreHandled() {
        assertEquals(d.rows, apply(listOf(memo("r", "rejected"))).rows)
        assertEquals(100_000L, brandRoute(apply(listOf(memo("f", "pending", kind = "reward")))).stdAchievedMilli) // a free or reward line adds no achievement
        val other = apply(listOf(memo("o", "pending", outlet = 999)))
        assertEquals(10_000L, brandOutlet(other).stdAchievedMilli)
        assertEquals(100_000L, brandRoute(other).stdAchievedMilli) // an outlet not enrolled in Astha adds nothing to the route row either
    }

    @Test fun fortyMemosAcknowledgedBeforeTheRequestAreNotAddedAgain() {
        val memos = (1..40).map { memo("m$it", "acked", qty = 1, gross = 10_000) }
        assertEquals(d.rows, apply(memos, memos.map { it.uuid }.toSet()).rows)
        assertEquals(10L + 40L, allRoute(apply(memos, emptySet())).memoAchieved) // the same 40, none acknowledged before the request
    }

    @Test fun aZeroSaleMemoAddsNoMemoCountAndAMemoOfAnotherRouteOrAnOutletNotEnrolledIsLeftOut() {
        assertEquals(10L, allRoute(apply(listOf(memo("z", "pending", memoKind = "zero_sale")))).memoAchieved)
        assertEquals(d.rows, apply(listOf(memo("r8", "pending", route = 8))).rows)
        assertEquals(d.rows, apply(listOf(memo("o", "pending", outlet = 999))).rows)
    }

    @Test fun aLineEnteredInLooseUnitsStaysInTheSkuPackSize() {
        val loose = LocalMemo("l", 501, "2026-11-18", null, "pending", null, listOf(LocalMemoLine(900, "sale", 5, 1, 50_000)), 7)
        assertEquals(listOf(AsthaPack(20, 105_000)), brandRoute(apply(listOf(loose))).byPack) // no extra "1s" pack line
    }

    @Test fun aChainOfTwoEditsTakesOutTheServerHeldOriginalNotTheMiddleOne() {
        val m = memo("m", "acked", qty = 10)
        val e1 = memo("e1", "pending", supersedes = "m", qty = 12)
        val e2 = memo("e2", "pending", supersedes = "e1", qty = 8)
        val x = apply(listOf(m, e1, e2), setOf("m"))
        assertEquals(100_000L - 10_000L + 8_000L, brandRoute(x).stdAchievedMilli)
        assertEquals(10L, allRoute(x).memoAchieved)
    }

    @Test fun theMiddleEditAckedAfterTheRequestChangesNothing() {
        val m = memo("m", "acked", qty = 10); val e1 = memo("e1", "acked", supersedes = "m", qty = 12); val e2 = memo("e2", "pending", supersedes = "e1", qty = 8)
        val x = apply(listOf(m, e1, e2), setOf("m"))
        assertEquals(100_000L - 10_000L + 8_000L, brandRoute(x).stdAchievedMilli)
        assertEquals(10L, allRoute(x).memoAchieved)
    }

    @Test fun aChainOfThreeEditsAndOneWhoseMiddleIsInsideTheFigure() {
        val m = memo("m", "acked", qty = 10); val e1 = memo("e1", "acked", supersedes = "m", qty = 12) // the server holds E1 (M is superseded there)
        val e2 = memo("e2", "pending", supersedes = "e1", qty = 9); val e3 = memo("e3", "pending", supersedes = "e2", qty = 7)
        val x = apply(listOf(m, e1, e2, e3), setOf("m", "e1"))
        assertEquals(100_000L - 12_000L + 7_000L, brandRoute(x).stdAchievedMilli) // take out E1, add E3 only
        assertEquals(10L, allRoute(x).memoAchieved)
    }

    @Test fun aChainWhoseOriginalIsOnlyOnThePhoneAddsJustTheLastEdit() {
        val x = apply(listOf(memo("m", "pending", qty = 10), memo("e1", "pending", supersedes = "m", qty = 12), memo("e2", "pending", supersedes = "e1", qty = 8)))
        assertEquals(100_000L + 8_000L, brandRoute(x).stdAchievedMilli)
        assertEquals(11L, allRoute(x).memoAchieved)
    }

    @Test fun aMemoSentButUnansweredWhenTheRequestLeftIsNeitherAddedNorCountedAndAWaitingLineIsDue() {
        val lost = memo("lost", "uncertain")
        val x = apply(listOf(lost, memo("p", "pending", qty = 10)))
        assertEquals(100_000L + 10_000L, brandRoute(x).stdAchievedMilli)
        assertEquals(1, AsthaLocal.awaiting(listOf(lost, memo("p", "pending"))))
        assertEquals(0, AsthaLocal.awaiting(listOf(memo("z", "uncertain", memoKind = "zero_sale"))))
        assertEquals(d.rows, apply(listOf(memo("lost", "uncertain"))).rows)
    }
}
