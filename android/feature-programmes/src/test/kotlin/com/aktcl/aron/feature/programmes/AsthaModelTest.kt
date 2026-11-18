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
}
