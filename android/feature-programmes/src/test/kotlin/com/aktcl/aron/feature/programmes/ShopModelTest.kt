package com.aktcl.aron.feature.programmes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShopModelTest {
    private val astha = AsthaModel.parse("""{"astha_targets":[{"route_id":1,"outlet_id":10,"brand_id":1,"month":"2026-11","std_target":"1","std_achieved":"0","memo_target":0,"memo_achieved":0,"value_target_mtk":"0","value_achieved_mtk":"0","by_pack":[]}]}""", null)
    private val outlets = listOf(
        ShopOutlet(10, "Zed Store (111-01711111111-X)", "111", "A", "01711111111", 3),
        ShopOutlet(11, "Not Astha (222-01722222222-X)", "222", "B", null, 3),
        ShopOutlet(12, "Alpha Store (333-01733333333-X)", "333", "C", "01733333333", null),
    )

    @Test fun onlyOutletsWithAnAsthaRowAreListedAndTheTierComesFromTheServersTierCodeNeverALabel() {
        val prog = """{"loyalty":[{"outlet_id":"10","programme_id":3,"balance_points":0,"tier_code":"gold"}],"gift_assignments":[{"gift_assignment_id":1,"quarter":"2026-Q4","outlet_id":10,"gift_id":1,"tier_code":"diamond","photo_captured":false,"locked":false}]}"""
        val tiers = ShopModel.tiers(prog)
        assertEquals(mapOf(10L to "diamond"), tiers) // the assignment's tier wins over the card's
        val s = ShopModel.build(outlets, astha, tiers)
        assertEquals(listOf(10L), s.map { it.outletId })
        assertEquals("diamond", s.single().tierCode); assertEquals("Diamond", s.single().subChannel)
        assertTrue(ShopModel.build(outlets, null, emptyMap()).isEmpty())
    }

    @Test fun withoutATierCodeFromTheServerTheTierIsUnknownEvenWhenTheOutletHasASubChannelId() {
        val s = ShopModel.build(outlets, astha, ShopModel.tiers("""{"loyalty":[],"gift_assignments":[]}""")) // outlet 10 has sub_channel_id 3, which is no tier
        assertEquals(null, s.single().tierCode); assertEquals(null, s.single().subChannel)
    }

    @Test fun searchMatchesNameCodeAndPhoneInLatinOrBanglaDigits() {
        val shops = ShopModel.build(outlets + ShopOutlet(13, "Alpha Two (444-01744444444-X)", "444", "D", "01744444444", null), AsthaModel.parse("""{"astha_targets":[
            {"route_id":1,"outlet_id":10,"brand_id":1,"month":"2026-11","std_target":"1","std_achieved":"0","memo_target":0,"memo_achieved":0,"value_target_mtk":"0","value_achieved_mtk":"0","by_pack":[]},
            {"route_id":1,"outlet_id":13,"brand_id":1,"month":"2026-11","std_target":"1","std_achieved":"0","memo_target":0,"memo_achieved":0,"value_target_mtk":"0","value_achieved_mtk":"0","by_pack":[]}]}""", null), emptyMap())
        assertEquals(2, ShopModel.filter(shops, "").size)
        assertEquals(listOf(13L), ShopModel.filter(shops, "alpha").map { it.outletId })
        assertEquals(listOf(10L), ShopModel.filter(shops, "111").map { it.outletId })
        assertEquals(listOf(13L), ShopModel.filter(shops, "০১৭৪৪").map { it.outletId })
        assertTrue(ShopModel.filter(shops, "nothing").isEmpty())
    }
}
