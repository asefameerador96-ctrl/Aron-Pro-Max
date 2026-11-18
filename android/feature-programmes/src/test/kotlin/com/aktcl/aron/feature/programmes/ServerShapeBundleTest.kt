package com.aktcl.aron.feature.programmes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The programme models read a bundle shaped EXACTLY as the server writes it (member names and types copied from backend BundleProgrammes.kt, BundleDtos.SkuDto,
 * CodeItem and AsthaBundleSectionTest): SKUs carry `id` (not `sku_id`), code-list items carry NO numeric id, ids and counts are numbers, STD and money are strings,
 * loyalty cards carry no tier until the server adds `tier_code`. A hand-made fixture hid a missing field twice; this one must stay a copy of the server's shape.
 */
class ServerShapeBundleTest {
    private val programmes = """{"programmes":[{"programme_id":5,"kind":"astha","code":"ASTHA-T","name_en":"Astha","name_bn":"আস্থা","period_label":"Q4","active_from":"2026-10-01","active_to":"2026-12-31","points_expire_on":null,"rules":{},"status":"active","version":1,"tiers":[]}],
      "loyalty":[{"outlet_id":10,"programme_id":5,"league_label":null,"balance_points":0,"overdrawn":false,"expiring_points":0,"expiry_date":null,"as_of_date":"2026-11-18"}],
      "gifts":[{"gift_id":1,"programme_id":5,"code":"gift_a","name_en":"Hotpot","name_bn":"হটপট","points_cost":null,"tier_codes":["gold"],"image_url":null,"image_asset_id":null,"status":"active","version":1}],
      "astha_targets":[
        {"route_id":7,"outlet_id":null,"brand_id":11,"month":"2026-11","std_target":"1000.000","std_achieved":"3600.000","memo_target":0,"memo_achieved":0,"value_target_mtk":"5000000","value_achieved_mtk":"1000000","by_pack":[{"pack_factor":20,"std_achieved":"3600.000"}]},
        {"route_id":7,"outlet_id":10,"brand_id":11,"month":"2026-11","std_target":"100.000","std_achieved":"36.500","memo_target":0,"memo_achieved":0,"value_target_mtk":"0","value_achieved_mtk":"0","by_pack":[{"pack_factor":1,"std_achieved":"36.500"}]},
        {"route_id":7,"outlet_id":10,"brand_id":null,"month":"2026-11","std_target":"0.000","std_achieved":"0.000","memo_target":5,"memo_achieved":1,"value_target_mtk":"0","value_achieved_mtk":"0","by_pack":[]}],
      "astha_as_of":"2026-11-18T05:00:00Z","astha_truncated":false,"truncated_sections":[],
      "gift_assignments":[{"gift_assignment_id":71,"quarter":"2026-Q4","outlet_id":10,"route_id":7,"gift_id":1,"tier_code":"gold","photo_captured":false,"photo_media_uuid":null,"locked":false,"chosen_by_user_id":null,"chosen_at":null}],
      "pending_gift_verifications":[]}"""
    private val products = """{"nodes":[{"id":1,"level":"category","name":"Cigarette","name_bn":null,"parent_id":null,"sort":1},{"id":11,"level":"brand","name":"Marise","name_bn":"মারিস","parent_id":1,"sort":1},{"id":21,"level":"variant","name":"V","name_bn":null,"parent_id":11,"sort":1}],
      "skus":[{"id":901,"code":"S1","variant_id":21,"category_code":"cigarette","name":"Marise 20s","short_name":"M20","name_bn":null,"base_unit":"stick","base_per_pack":20,"entry_unit_default":"pack","report_unit":null,"report_factor":"1.000","sort":1,"status":"active","thumbnail_media_uuid":null,"updated_at":"2026-11-01T00:00:00Z","version":1,"image_asset_id":null}]}"""
    private val codeLists = """[{"list_key":"sub_channel","items":[{"code":"gold","label_bn":null,"label_en":"Gold","sort":1,"attrs":{},"valid_from":"2026-01-01","valid_to":null}]}]"""

    @Test fun everyProgrammeModelReadsTheServersShape() {
        val astha = AsthaModel.parse(programmes, products)!!
        assertEquals(setOf(10L), astha.outletIds.toSet()); assertEquals(mapOf(901L to 11L), astha.skuBrand); assertEquals(mapOf(11L to "cigarette"), astha.brandCategory)
        assertEquals(mapOf(901L to 20), astha.skuPack); assertEquals("Marise", astha.brandName(11, com.aktcl.aron.rules.UiLocale.EN))
        assertEquals(PackKind.PACKET, astha.packLine(11, astha.rows.first().byPack.first()).kind)
        val loyalty = LoyaltyModel.parse(programmes)!!; assertEquals(listOf(10L), loyalty.outletIds); assertEquals(null, loyalty.cards.single().tierCode)
        val gifts = GiftChoiceModel.parse(programmes)!!; assertEquals(71L, gifts.assignments.single().id); assertEquals(listOf(1L), gifts.gifts.map { it.id })
        // the tier is the server's tier_code; the code list (no numeric id on its items) takes no part
        assertEquals(mapOf(10L to "gold"), ShopModel.tiers(programmes))
        val shops = ShopModel.build(listOf(ShopOutlet(10, "Milon (1)", "1", "M", null, 3)), astha, ShopModel.tiers(programmes))
        assertEquals("gold", shops.single().tierCode)
        assertEquals(listOf(1L), gifts.offeredTo(shops.single().tierCode).map { it.id })
        assertTrue(codeLists.isNotEmpty())
        // a local memo is added on top by the SKU's pack size
        val memo = LocalMemo("m", 10, "2026-11-18", null, "pending", null, listOf(LocalMemoLine(901, "sale", 40, 20, 400_000)), 7)
        assertEquals(3_640_000L, AsthaLocal.apply(astha, listOf(memo), "2026-11-18T01:00:00Z").routeRows.first { it.brandId == 11L }.stdAchievedMilli)
    }
}
