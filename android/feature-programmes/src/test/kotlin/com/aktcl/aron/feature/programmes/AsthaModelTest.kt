package com.aktcl.aron.feature.programmes

import com.aktcl.aron.rules.UiLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AsthaModelTest {
    private val programmes = """
        {"programmes":[],"loyalty":[],"gifts":[],"astha_as_of":"2026-11-17T10:00:00Z","astha_truncated":false,"truncated_sections":[],
         "gift_assignments":[],"pending_gift_verifications":[],
         "astha_targets":[
          {"route_id":7,"outlet_id":null,"brand_id":11,"month":"2026-11","std_target":"2240.000","std_achieved":"390.5","memo_target":18,"memo_achieved":7,
           "value_target_mtk":"1000000","value_achieved_mtk":"250000","by_pack":[{"pack_factor":20,"std_achieved":"300"},{"pack_factor":10,"std_achieved":"90.5"}]},
          {"route_id":7,"outlet_id":null,"brand_id":null,"month":"2026-11","std_target":"0","std_achieved":"0","memo_target":884,"memo_achieved":787,
           "value_target_mtk":"0","value_achieved_mtk":"0","by_pack":[]},
          {"route_id":7,"outlet_id":501,"brand_id":11,"month":"2026-11","std_target":"100","std_achieved":"10","memo_target":2,"memo_achieved":1,
           "value_target_mtk":"0","value_achieved_mtk":"0","by_pack":[]},
          {"route_id":7,"outlet_id":null,"brand_id":12,"month":"bad","std_target":"1","std_achieved":"0","memo_target":0,"memo_achieved":0,"value_target_mtk":"0","value_achieved_mtk":"0","by_pack":[]}
         ]}
    """.trimIndent()
    private val products = """{"nodes":[{"id":11,"level":"brand","name":"Marise","name_bn":"মারিস","sort":1},{"id":3,"level":"category","name":"Cigarette","sort":1}],"skus":[]}"""

    @Test fun parsesRowsBrandsAndAsOf() {
        val d = AsthaModel.parse(programmes, products)!!
        assertEquals(3, d.rows.size) // the row with a bad month is skipped, never a crash
        assertEquals("2026-11-17T10:00:00Z", d.asOf)
        assertEquals("মারিস", d.brandName(11, UiLocale.BN))
        assertEquals("Marise", d.brandName(11, UiLocale.EN))
        assertEquals(listOf("2026-11"), d.months())
        assertEquals(listOf(501L), d.outletIds)
    }

    @Test fun routeAndOutletRowsAreSeparate() {
        val d = AsthaModel.parse(programmes, products)!!
        assertEquals(1, d.brandRows("2026-11").size)
        assertEquals(1, d.brandRows("2026-11", 501).size)
        assertEquals(884L, d.allBrandRow("2026-11")!!.memoTarget)
        assertNull(d.allBrandRow("2026-11", 501))
    }

    @Test fun std_is_exact_thousandths_with_no_float() {
        val r = AsthaModel.parse(programmes, products)!!.brandRows("2026-11").single()
        assertEquals(2_240_000L, r.stdTargetMilli)
        assertEquals(390_500L, r.stdAchievedMilli)
        assertEquals(listOf(10, 20), r.byPack.map { it.packFactor }) // ordered by pack factor, never summed
        assertEquals("390.5", AsthaModel.stdText(r.stdAchievedMilli, UiLocale.EN))
        assertEquals("২,২৪০", AsthaModel.stdText(r.stdTargetMilli, UiLocale.BN))
    }

    @Test fun percentIsHalfUpTwoDecimalsAndZeroWhenNoTarget() {
        assertEquals("89.03%", AsthaModel.pctText(787, 884, UiLocale.EN))
        assertEquals("৮৯.০৩%", AsthaModel.pctText(787, 884, UiLocale.BN))
        assertEquals("0.00%", AsthaModel.pctText(5, 0, UiLocale.EN)) // the contract's rule: 0.00% when the target is 0
        assertEquals("104.94%", AsthaModel.pctText(10494, 10000, UiLocale.EN)) // may exceed 100
        assertEquals(97L, AsthaModel.remaining(884, 787))
        assertEquals(0L, AsthaModel.remaining(10, 12))
    }

    @Test fun moneyIsMilliTaka() {
        assertEquals("250 ৳", AsthaModel.valueText(250_000, UiLocale.EN)) // whole taka: no decimals
        assertEquals("250.50 ৳", AsthaModel.valueText(250_500, UiLocale.EN)) // a fraction is never rounded away
        assertEquals("২৫০.৫০ ৳", AsthaModel.valueText(250_500, UiLocale.BN))
    }

    @Test fun quarterMath() {
        assertEquals(1, AsthaModel.quarterOf("2026-01")); assertEquals(4, AsthaModel.quarterOf("2026-11"))
        assertEquals(listOf("2026-01", "2026-02", "2026-03"), AsthaModel.monthsOfQuarter(2026, 1))
        assertEquals(listOf("2026-10", "2026-11", "2026-12"), AsthaModel.monthsOfQuarter(2026, 4))
    }

    @Test fun missingOrBrokenSectionIsNullNotACrash() {
        assertNull(AsthaModel.parse(null, null))
        assertNull(AsthaModel.parse("[1,2]", null))
        assertNull(AsthaModel.parse("not json", null))
        assertNotNull(AsthaModel.parse("""{"astha_targets":[]}""", null))
        assertTrue(AsthaModel.parse("""{"astha_targets":[]}""", null)!!.rows.isEmpty())
    }

    @Test fun decimal3Rejects() {
        assertNull(AsthaModel.decimal3(kotlinx.serialization.json.JsonPrimitive("1.2345")))
        assertNull(AsthaModel.decimal3(kotlinx.serialization.json.JsonPrimitive("abc")))
        assertEquals(1_500L, AsthaModel.decimal3(kotlinx.serialization.json.JsonPrimitive("1.5")))
    }

    // shaped like the seed: the category and segment nodes carry NO code, the category is on the SKU (docs: products.skus[].category_code)
    private val tree = """{"nodes":[
      {"id":1,"level":"category","name":"Cigarette","sort":1},{"id":2,"level":"segment","name":"Low","parent_id":1,"sort":1},{"id":11,"level":"brand","name":"Marise","parent_id":2,"sort":1},{"id":31,"level":"variant","name":"V1","parent_id":11,"sort":1},
      {"id":5,"level":"category","name":"Match","sort":2},{"id":15,"level":"brand","name":"Box Match","parent_id":5,"sort":1},{"id":35,"level":"variant","name":"V5","parent_id":15,"sort":1},
      {"id":6,"level":"category","name":"Lighter","sort":3},{"id":16,"level":"brand","name":"Gas Lighter","parent_id":6,"sort":1},{"id":36,"level":"variant","name":"V6","parent_id":16,"sort":1},
      {"id":17,"level":"brand","name":"Mixed","sort":4},{"id":37,"level":"variant","name":"V7","parent_id":17,"sort":1}],
      "skus":[{"sku_id":901,"variant_id":31,"category_code":"cigarette","base_per_pack":20},{"sku_id":905,"variant_id":35,"category_code":"match","base_per_pack":1},
              {"sku_id":906,"variant_id":36,"category_code":"lighter","base_per_pack":25},{"sku_id":907,"variant_id":37,"category_code":"match"},{"sku_id":908,"variant_id":37,"category_code":"lighter"}]}"""

    @Test fun theCategoryComesFromTheSkusNotFromNodeCodes() {
        val d = AsthaModel.parse(programmes, tree)!!
        assertEquals(mapOf(11L to "cigarette", 15L to "match", 16L to "lighter"), d.brandCategory) // 17 has SKUs of two categories: left out, not guessed
        assertEquals(20, d.skuPack[901L]); assertEquals(1, d.skuPack[905L])
    }

    @Test fun packLinesFollowTheCategoryUnits() {
        val d = AsthaModel.parse(programmes, tree)!!
        val cig = d.packLine(11, AsthaPack(20, 300_500)) // 300.5 sticks in packets of 20
        assertEquals(15L, cig.packs); assertEquals(500L, cig.looseMilli); assertEquals(PackKind.PACKET, cig.kind)
        assertEquals(0L, d.packLine(11, AsthaPack(20, 40_000)).looseMilli) // exact packets: nothing loose
        val m = d.packLine(15, AsthaPack(1, 36_000)) // matches: the dozen is the base unit, pack_factor 1
        assertEquals(PackKind.DOZEN, m.kind); assertEquals(36L, m.packs); assertEquals(0L, m.looseMilli)
        assertEquals(500L, d.packLine(15, AsthaPack(1, 36_500)).looseMilli) // half a DOZEN, never "pieces"
        val l = d.packLine(16, AsthaPack(25, 60_000)) // lighters: boxes of 25 plus loose pieces
        assertEquals(PackKind.BOX, l.kind); assertEquals(2L, l.packs); assertEquals(10_000L, l.looseMilli)
    }

    @Test fun anUnknownCategoryPrintsNoUnitWord() {
        val d = AsthaModel.parse(programmes, tree)!!
        assertEquals(PackKind.UNKNOWN, d.packLine(99, AsthaPack(10, 5_000)).kind)
        assertEquals(PackKind.UNKNOWN, d.packLine(17, AsthaPack(1, 5_000)).kind) // mixed categories
        assertEquals(PackKind.UNKNOWN, d.packLine(null, AsthaPack(1, 5_000)).kind)
    }

    // the unit comes from the SHARED rule (EntryUnits.resolve), SKU by SKU, not from the category alone
    private fun withSkus(skus: String) = AsthaModel.parse(programmes, """{"nodes":[{"id":2,"level":"segment","name":"S","sort":1},{"id":15,"level":"brand","name":"B","parent_id":2,"sort":1},{"id":35,"level":"variant","name":"V","parent_id":15,"sort":1}],"skus":[$skus]}""")!!

    @Test fun aLighterWithOnePieceInItsPackIsCountedInPiecesNotBoxes() {
        val d = withSkus("""{"id":906,"variant_id":35,"category_code":"lighter","base_unit":"piece","base_per_pack":1,"entry_unit_default":"pack"}""")
        val l = d.packLine(15, AsthaPack(1, 7_000)); assertEquals(PackKind.BASE, l.kind); assertEquals("piece", l.baseKey); assertEquals(7L, l.packs)
        assertEquals("piece", d.baseUnitKey(15))
    }

    @Test fun aLighterBoxOf25AndAMatchDozenAndACigarettePacketKeepTheirUnits() {
        assertEquals(PackKind.BOX, withSkus("""{"id":906,"variant_id":35,"category_code":"lighter","base_unit":"piece","base_per_pack":25,"entry_unit_default":"pack"}""").packLine(15, AsthaPack(25, 60_000)).kind)
        assertEquals(PackKind.DOZEN, withSkus("""{"id":905,"variant_id":35,"category_code":"match","base_unit":"dozen","base_per_pack":1,"entry_unit_default":"dozen"}""").packLine(15, AsthaPack(1, 36_500)).kind)
        val c = withSkus("""{"id":901,"variant_id":35,"category_code":"cigarette","base_unit":"stick","base_per_pack":10,"entry_unit_default":"pack"}""").packLine(15, AsthaPack(10, 105_000))
        assertEquals(PackKind.PACKET, c.kind); assertEquals(10L, c.packs); assertEquals(5_000L, c.looseMilli)
    }

    @Test fun aMatchSetUpInPacksOfTwelveIsNeutralNeverAWrongWordAndSkusOfOneBrandThatDisagreeAreUnknown() {
        assertEquals(PackKind.UNKNOWN, withSkus("""{"id":905,"variant_id":35,"category_code":"match","base_unit":"piece","base_per_pack":12,"entry_unit_default":"pack"}""").packLine(15, AsthaPack(12, 36_000)).kind)
        val mixed = withSkus("""{"id":901,"variant_id":35,"category_code":"cigarette","base_unit":"stick","base_per_pack":20,"entry_unit_default":"pack"},{"id":902,"variant_id":35,"category_code":"cigarette","base_unit":"stick","base_per_pack":20,"entry_unit_default":"stick"}""")
        assertEquals(PackKind.PACKET, mixed.packLine(15, AsthaPack(20, 40_000)).kind) // both resolve to packets of 20: the same unit, so not unknown
    }
}
