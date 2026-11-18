package com.aktcl.aron.feature.programmes

import com.aktcl.aron.rules.UiLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShopModelTest {
    private val codeLists = """[{"list_key":"sub_channel","items":[{"id":3,"label_en":"Gold","label_bn":"গোল্ড"},{"id":4,"label_en":"Diamond"},{"label_en":"NoId"}]}]"""
    private val astha = AsthaModel.parse("""{"astha_targets":[{"route_id":1,"outlet_id":10,"brand_id":1,"month":"2026-11","std_target":"1","std_achieved":"0","memo_target":0,"memo_achieved":0,"value_target_mtk":"0","value_achieved_mtk":"0","by_pack":[]}]}""", null)
    private val outlets = listOf(
        ShopOutlet(10, "Zed Store (111-01711111111-X)", "111", "A", "01711111111", 3),
        ShopOutlet(11, "Not Astha (222-01722222222-X)", "222", "B", null, 3),
        ShopOutlet(12, "Alpha Store (333-01733333333-X)", "333", "C", "01733333333", null),
    )

    @Test fun subChannelsKeepOnlyItemsWithAnId() = assertEquals(setOf(3L, 4L), ShopModel.subChannels(codeLists).keys)

    @Test fun onlyOutletsWithAnAsthaRowAreListedWithTheirTierLabel() {
        val s = ShopModel.build(outlets, astha, ShopModel.subChannels(codeLists), UiLocale.BN)
        assertEquals(listOf(10L), s.map { it.outletId })
        assertEquals("গোল্ড", s.single().subChannel)
        assertTrue(ShopModel.build(outlets, null, emptyMap(), UiLocale.EN).isEmpty())
    }

    @Test fun searchMatchesNameCodeAndPhoneInLatinOrBanglaDigits() {
        val shops = ShopModel.build(outlets + ShopOutlet(13, "Alpha Two (444-01744444444-X)", "444", "D", "01744444444", null), AsthaModel.parse("""{"astha_targets":[
            {"route_id":1,"outlet_id":10,"brand_id":1,"month":"2026-11","std_target":"1","std_achieved":"0","memo_target":0,"memo_achieved":0,"value_target_mtk":"0","value_achieved_mtk":"0","by_pack":[]},
            {"route_id":1,"outlet_id":13,"brand_id":1,"month":"2026-11","std_target":"1","std_achieved":"0","memo_target":0,"memo_achieved":0,"value_target_mtk":"0","value_achieved_mtk":"0","by_pack":[]}]}""", null), emptyMap(), UiLocale.EN)
        assertEquals(2, ShopModel.filter(shops, "").size)
        assertEquals(listOf(13L), ShopModel.filter(shops, "alpha").map { it.outletId })
        assertEquals(listOf(10L), ShopModel.filter(shops, "111").map { it.outletId })
        assertEquals(listOf(13L), ShopModel.filter(shops, "০১৭৪৪").map { it.outletId })
        assertTrue(ShopModel.filter(shops, "nothing").isEmpty())
    }
}
