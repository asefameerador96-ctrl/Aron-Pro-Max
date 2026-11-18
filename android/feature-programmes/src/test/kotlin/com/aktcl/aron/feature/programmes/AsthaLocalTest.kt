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
    private val req = "2026-11-17T10:00:00Z" // the instant the phone SENT the bundle request
    private fun memo(uuid: String, state: String, ackedAt: String? = null, supersedes: String? = null, outlet: Long = 501, qty: Long = 40, gross: Long = 400_000, kind: String = "sale", route: Long? = 7, memoKind: String = "sale") =
        LocalMemo(uuid, outlet, "2026-11-18", supersedes, state, ackedAt, listOf(LocalMemoLine(900, kind, qty, 20, gross)), route, memoKind)
    private fun brandRoute(x: AsthaData) = x.rows.first { it.outletId == null && it.brandId == 11L }
    private fun brandOutlet(x: AsthaData) = x.rows.first { it.outletId == 501L && it.brandId == 11L }
    private fun allRoute(x: AsthaData) = x.rows.first { it.outletId == null && it.brandId == null }

    @Test fun skuToBrandThroughTheProductTree() = assertEquals(mapOf(900L to 11L), d.skuBrand)

    @Test fun anUnacknowledgedMemoIsAddedToTheRouteAndTheOutletRowsAndTheMemoCount() {
        val x = AsthaLocal.apply(d, requestedAt = req, memos = listOf(memo("m1", "pending")))
        assertEquals(140_000L, brandRoute(x).stdAchievedMilli); assertEquals(1_400_000L, brandRoute(x).valueAchievedMtk)
        assertEquals(50_000L, brandOutlet(x).stdAchievedMilli)
        assertEquals(11L, allRoute(x).memoAchieved)
        assertEquals(listOf(AsthaPack(20, 140_000)), brandRoute(x).byPack)
    }

    @Test fun aMemoTheServerAlreadyHoldsIsNotCountedTwice() {
        val x = AsthaLocal.apply(d, requestedAt = req, memos = listOf(memo("m1", "acked", ackedAt = "2026-11-17T09:00:00Z"))) // acked before the request: inside the server figure
        assertEquals(d.rows, x.rows)
    }

    @Test fun aMemoAckedAfterTheRequestIsAdded() {
        val x = AsthaLocal.apply(d, requestedAt = req, memos = listOf(memo("m1", "acked", ackedAt = "2026-11-17T11:00:00Z")))
        assertEquals(140_000L, brandRoute(x).stdAchievedMilli)
    }

    @Test fun aSupersededLocalMemoIsNotCountedAndAnEditOfAServerHeldMemoTakesTheOldOneOut() {
        val old = memo("old", "acked", ackedAt = "2026-11-17T09:00:00Z") // the server holds it
        val edit = memo("new", "pending", supersedes = "old", qty = 60, gross = 600_000)
        val x = AsthaLocal.apply(d, requestedAt = req, memos = listOf(old, edit))
        assertEquals(100_000L + 60_000L - 40_000L, brandRoute(x).stdAchievedMilli) // server 100 + new 60 - old 40
        assertEquals(10L, allRoute(x).memoAchieved) // +1 for the edit, -1 for the old memo: one memo, not two
        // the same input twice gives the same answer: recomputed from scratch, never accumulated
        assertEquals(x.rows, AsthaLocal.apply(d, requestedAt = req, memos = listOf(old, edit)).rows)
    }

    @Test fun refusedMemosAndNonSaleLinesAndOtherOutletsAreHandled() {
        assertEquals(d.rows, AsthaLocal.apply(d, requestedAt = req, memos = listOf(memo("r", "rejected"))).rows)
        val free = AsthaLocal.apply(d, requestedAt = req, memos = listOf(memo("f", "pending", kind = "reward")))
        assertEquals(100_000L, brandRoute(free).stdAchievedMilli) // a free or reward line adds no achievement
        val other = AsthaLocal.apply(d, requestedAt = req, memos = listOf(memo("o", "pending", outlet = 999)))
        assertEquals(10_000L, brandOutlet(other).stdAchievedMilli) // another outlet leaves this outlet's row alone
        assertEquals(100_000L, brandRoute(other).stdAchievedMilli) // an outlet that is not enrolled in Astha adds nothing to the route row either
    }

    @Test fun fortyMemosSyncedBeforeTheRequestAreNotAddedAgainEvenWhenTheAckIsAfterAsthaAsOf() {
        // astha_as_of 10:00:00Z is the server receipt of the newest memo; the phone acked at 10:00:02Z but sent the request at 10:00:30Z
        val memos = (1..40).map { memo("m$it", "acked", ackedAt = "2026-11-17T10:00:02Z", qty = 1, gross = 10_000) }
        val x = AsthaLocal.apply(d, memos, "2026-11-17T10:00:30Z")
        assertEquals(d.rows, x.rows)
        // the same 40 acked AFTER a later request would only count if that request preceded the ack
        assertEquals(d.rows, AsthaLocal.apply(d, memos, "2026-11-17T10:00:02Z").rows)
        assertEquals(10L + 40L, allRoute(AsthaLocal.apply(d, memos, "2026-11-17T10:00:01Z")).memoAchieved)
    }

    @Test fun withoutARequestInstantOnlyMemosStillOnThePhoneCount() {
        val x = AsthaLocal.apply(d, listOf(memo("a", "acked", ackedAt = "2026-11-17T11:00:00Z"), memo("p", "pending")), null)
        assertEquals(140_000L, brandRoute(x).stdAchievedMilli) // only the pending one
    }

    @Test fun aZeroSaleMemoAddsNoMemoCountAndAMemoOfAnotherRouteOrAnOutletNotEnrolledIsLeftOut() {
        assertEquals(10L, allRoute(AsthaLocal.apply(d, listOf(memo("z", "pending", memoKind = "zero_sale")), req)).memoAchieved)
        assertEquals(d.rows, AsthaLocal.apply(d, listOf(memo("r8", "pending", route = 8)), req).rows)
        assertEquals(d.rows, AsthaLocal.apply(d, listOf(memo("o", "pending", outlet = 999)), req).rows) // not enrolled: no route row change either
    }

    @Test fun aLineEnteredInLooseUnitsStaysInTheSkuPackSize() {
        val loose = LocalMemo("l", 501, "2026-11-18", null, "pending", null, listOf(LocalMemoLine(900, "sale", 5, 1, 50_000)), 7)
        val x = AsthaLocal.apply(d, listOf(loose), req)
        assertEquals(listOf(AsthaPack(20, 105_000)), brandRoute(x).byPack) // no extra "1s" pack line
    }

    private val inside = "2026-11-17T09:00:00Z" // acknowledged before the request: inside the server figure
    private val after = "2026-11-17T11:00:00Z" // acknowledged after the request

    @Test fun aChainOfTwoEditsTakesOutTheServerHeldOriginalNotTheMiddleOne() {
        val m = memo("m", "acked", ackedAt = inside, qty = 10)                                   // server holds 10 more packets-worth
        val e1 = memo("e1", "pending", supersedes = "m", qty = 12)
        val e2 = memo("e2", "pending", supersedes = "e1", qty = 8)
        val x = AsthaLocal.apply(d, listOf(m, e1, e2), req)
        assertEquals(100_000L - 10_000L + 8_000L, brandRoute(x).stdAchievedMilli) // server 100 - M 10 + E2 8 (the old rule gave 108)
        assertEquals(10L, allRoute(x).memoAchieved) // one memo replaced by one memo, not two
    }

    @Test fun theMiddleEditAckedAfterTheRequestChangesNothing() {
        val m = memo("m", "acked", ackedAt = inside, qty = 10)
        val e1 = memo("e1", "acked", ackedAt = after, supersedes = "m", qty = 12)
        val e2 = memo("e2", "pending", supersedes = "e1", qty = 8)
        val x = AsthaLocal.apply(d, listOf(m, e1, e2), req)
        assertEquals(100_000L - 10_000L + 8_000L, brandRoute(x).stdAchievedMilli)
        assertEquals(10L, allRoute(x).memoAchieved)
    }

    @Test fun aChainOfThreeEditsAndOneWhoseMiddleIsInsideTheFigure() {
        val m = memo("m", "acked", ackedAt = "2026-11-17T08:00:00Z", qty = 10)
        val e1 = memo("e1", "acked", ackedAt = inside, supersedes = "m", qty = 12) // the server now holds E1 (M is superseded there)
        val e2 = memo("e2", "pending", supersedes = "e1", qty = 9)
        val e3 = memo("e3", "pending", supersedes = "e2", qty = 7)
        val x = AsthaLocal.apply(d, listOf(m, e1, e2, e3), req)
        assertEquals(100_000L - 12_000L + 7_000L, brandRoute(x).stdAchievedMilli) // take out E1 (the first memo inside the figure), add E3 only
        assertEquals(10L, allRoute(x).memoAchieved)
    }

    @Test fun aChainWhoseOriginalIsOnlyOnThePhoneAddsJustTheLastEdit() {
        val x = AsthaLocal.apply(d, listOf(memo("m", "pending", qty = 10), memo("e1", "pending", supersedes = "m", qty = 12), memo("e2", "pending", supersedes = "e1", qty = 8)), req)
        assertEquals(100_000L + 8_000L, brandRoute(x).stdAchievedMilli)
        assertEquals(11L, allRoute(x).memoAchieved)
    }

    @Test fun aMemoSentButUnansweredWhenTheRequestLeftIsNeitherAddedNorCountedAndAWaitingLineIsDue() {
        val lost = memo("lost", "uncertain")
        val x = AsthaLocal.apply(d, listOf(lost, memo("p", "pending", qty = 10)), req)
        assertEquals(100_000L + 10_000L, brandRoute(x).stdAchievedMilli) // only the memo that was never sent is added: the figure may already hold the lost one
        assertEquals(1, AsthaLocal.awaiting(listOf(lost, memo("p", "pending"))))
        assertEquals(0, AsthaLocal.awaiting(listOf(memo("z", "uncertain", memoKind = "zero_sale"))))
        // the late ack of the lost memo changes nothing until the refetch replaces the figure
        assertEquals(d.rows, AsthaLocal.apply(d, listOf(memo("lost", "uncertain", ackedAt = "2026-11-17T12:00:00Z")), req).rows)
    }
}
