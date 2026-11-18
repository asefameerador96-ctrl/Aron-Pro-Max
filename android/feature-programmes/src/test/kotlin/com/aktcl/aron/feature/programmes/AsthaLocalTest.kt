package com.aktcl.aron.feature.programmes

import org.junit.Assert.assertEquals
import org.junit.Test

class AsthaLocalTest {
    private val programmes = """{"astha_as_of":"2026-11-17T10:00:00Z","astha_targets":[
      {"route_id":7,"outlet_id":null,"brand_id":11,"month":"2026-11","std_target":"1000","std_achieved":"100","memo_target":0,"memo_achieved":0,"value_target_mtk":"5000000","value_achieved_mtk":"1000000","by_pack":[{"pack_factor":20,"std_achieved":"100"}]},
      {"route_id":7,"outlet_id":null,"brand_id":null,"month":"2026-11","std_target":"0","std_achieved":"0","memo_target":50,"memo_achieved":10,"value_target_mtk":"0","value_achieved_mtk":"0","by_pack":[]},
      {"route_id":7,"outlet_id":501,"brand_id":11,"month":"2026-11","std_target":"100","std_achieved":"10","memo_target":0,"memo_achieved":0,"value_target_mtk":"0","value_achieved_mtk":"0","by_pack":[]},
      {"route_id":7,"outlet_id":501,"brand_id":null,"month":"2026-11","std_target":"0","std_achieved":"0","memo_target":5,"memo_achieved":1,"value_target_mtk":"0","value_achieved_mtk":"0","by_pack":[]}]}"""
    private val products = """{"nodes":[{"id":1,"level":"category","name":"C","code":"cigarette","sort":1},{"id":11,"level":"brand","name":"Marise","parent_id":1,"sort":1},{"id":21,"level":"variant","name":"V","parent_id":11,"sort":1}],
      "skus":[{"sku_id":900,"variant_id":21}]}"""
    private val d = AsthaModel.parse(programmes, products)!!
    private fun memo(uuid: String, state: String, ackedAt: String? = null, supersedes: String? = null, outlet: Long = 501, qty: Long = 40, gross: Long = 400_000, kind: String = "sale") =
        LocalMemo(uuid, outlet, "2026-11-18", supersedes, state, ackedAt, listOf(LocalMemoLine(900, kind, qty, 20, gross)))
    private fun brandRoute(x: AsthaData) = x.rows.first { it.outletId == null && it.brandId == 11L }
    private fun brandOutlet(x: AsthaData) = x.rows.first { it.outletId == 501L && it.brandId == 11L }
    private fun allRoute(x: AsthaData) = x.rows.first { it.outletId == null && it.brandId == null }

    @Test fun skuToBrandThroughTheProductTree() = assertEquals(mapOf(900L to 11L), d.skuBrand)

    @Test fun anUnacknowledgedMemoIsAddedToTheRouteAndTheOutletRowsAndTheMemoCount() {
        val x = AsthaLocal.apply(d, listOf(memo("m1", "pending")))
        assertEquals(140_000L, brandRoute(x).stdAchievedMilli); assertEquals(1_400_000L, brandRoute(x).valueAchievedMtk)
        assertEquals(50_000L, brandOutlet(x).stdAchievedMilli)
        assertEquals(11L, allRoute(x).memoAchieved)
        assertEquals(listOf(AsthaPack(20, 140_000)), brandRoute(x).byPack)
    }

    @Test fun aMemoTheServerAlreadyHoldsIsNotCountedTwice() {
        val x = AsthaLocal.apply(d, listOf(memo("m1", "acked", ackedAt = "2026-11-17T09:00:00Z"))) // acked before astha_as_of: inside the server figure
        assertEquals(d.rows, x.rows)
    }

    @Test fun aMemoAckedAfterTheServerFigureIsStillAdded() {
        val x = AsthaLocal.apply(d, listOf(memo("m1", "acked", ackedAt = "2026-11-17T11:00:00Z")))
        assertEquals(140_000L, brandRoute(x).stdAchievedMilli)
    }

    @Test fun aSupersededLocalMemoIsNotCountedAndAnEditOfAServerHeldMemoTakesTheOldOneOut() {
        val old = memo("old", "acked", ackedAt = "2026-11-17T09:00:00Z") // the server holds it
        val edit = memo("new", "pending", supersedes = "old", qty = 60, gross = 600_000)
        val x = AsthaLocal.apply(d, listOf(old, edit))
        assertEquals(100_000L + 60_000L - 40_000L, brandRoute(x).stdAchievedMilli) // server 100 + new 60 - old 40
        assertEquals(10L, allRoute(x).memoAchieved) // +1 for the edit, -1 for the old memo: one memo, not two
        // the same input twice gives the same answer: recomputed from scratch, never accumulated
        assertEquals(x.rows, AsthaLocal.apply(d, listOf(old, edit)).rows)
    }

    @Test fun refusedMemosAndNonSaleLinesAndOtherOutletsAreHandled() {
        assertEquals(d.rows, AsthaLocal.apply(d, listOf(memo("r", "rejected"))).rows)
        val free = AsthaLocal.apply(d, listOf(memo("f", "pending", kind = "reward")))
        assertEquals(100_000L, brandRoute(free).stdAchievedMilli) // a free or reward line adds no achievement
        val other = AsthaLocal.apply(d, listOf(memo("o", "pending", outlet = 999)))
        assertEquals(10_000L, brandOutlet(other).stdAchievedMilli) // another outlet leaves this outlet's row alone
        assertEquals(140_000L, brandRoute(other).stdAchievedMilli) // but the route row counts it
    }
}
