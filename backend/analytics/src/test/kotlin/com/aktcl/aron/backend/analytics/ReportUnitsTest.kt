package com.aktcl.aron.backend.analytics

import com.aktcl.aron.contract.Role
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * QC finding backend-reports-qa-1 (docs/16 P8, D-49): base units differ per category (stick, piece, dozen) and are never added across categories.
 * Memo a1 (outlet O1, route R1) sold 20 cigarette sticks in the seed; it also sold 50 lighter pieces and 5 match dozens here, so the unit-less sum
 * 20 + 50 + 5 = 75 (and, over the whole day, 75 + 50 + 5 = 130) must appear nowhere.
 */
class ReportUnitsTest : ReportFixture() {
    override val extraSql = """
        INSERT INTO app.sku (code, variant_id, category_code, name, short_name, base_unit, base_per_pack, entry_unit_default)
          SELECT 'SKU3', id, 'lighter', 'Lighter', 'L1', 'piece', 1, 'pack' FROM app.product_node WHERE code = 'V1'
          UNION ALL SELECT 'SKU4', id, 'match', 'Matches', 'M1', 'dozen', 1, 'dozen' FROM app.product_node WHERE code = 'V1';
        SELECT pg_temp.line('00000000-0000-4000-8000-0000000000a1', 2, 'SKU3', 50, 800, 'sale');
        SELECT pg_temp.line('00000000-0000-4000-8000-0000000000a1', 3, 'SKU4', 5, 800, 'sale');
    """.trimIndent()

    @Test
    fun byOutletKeepsEachCategoryInItsOwnUnit() = app {
        for (key in listOf("by-outlet", "by-outlet-by-day")) {
            val o = run(10, Role.ANALYST, key).result()
            val o1 = o.rows().by("outlet_code", "O1")
            assertEquals(20, o1.num("sold_cigarette_base").toInt()); assertEquals(0, o1.num("sold_bidi_base").toInt())
            assertEquals(50, o1.num("sold_lighter_base").toInt()); assertEquals(5, o1.num("sold_match_base").toInt())
            assertEquals(75, o.total("sold_cigarette_base")); assertEquals(50, o.total("sold_lighter_base")); assertEquals(5, o.total("sold_match_base"))
            assertFalse(o.rows().first().containsKey("sold_qty_base"), "$key: no unit-less quantity column")
            assertFalse(o["totals"]!!.jsonObject.containsKey("sold_qty_base"), "$key: no unit-less quantity total")
            val units = o["columns"]!!.let { c -> (c as kotlinx.serialization.json.JsonArray).associate { it.jsonObject["key"]!!.jsonPrimitive.content to it.jsonObject["unit"]?.takeIf { u -> u !is JsonNull }?.jsonPrimitive?.content } }
            assertEquals(mapOf("sold_cigarette_base" to "stick", "sold_bidi_base" to "stick", "sold_lighter_base" to "piece", "sold_match_base" to "dozen"),
                units.filterKeys { it.startsWith("sold_") })
            // The cross-category sums never appear as a quantity: 20 + 50 + 5 for the outlet, 75 + 50 + 5 for the day.
            val qtyKeys = listOf("sold_cigarette_base", "sold_bidi_base", "sold_lighter_base", "sold_match_base")
            assertFalse(qtyKeys.any { o1.num(it).toInt() == 75 }, key)
            assertFalse(qtyKeys.any { o.total(it).toInt() == 130 }, key)
        }
    }

    @Test
    fun routeStdAndDsRrsTotalsNeverAddQuantities() = app {
        val std = run(10, Role.ANALYST, "route-std").result()
        val keys = std["totals"]!!.jsonObject.keys
        assertTrue("gross_mtk" in keys); assertFalse(keys.any { it.endsWith("qty_base") }, "route-std totals: $keys")
        // Rows are per SKU, each in its own unit.
        assertEquals(50, std.rows().by("sku_code", "SKU3").num("sold_qty_base").toInt()); assertEquals("piece", std.rows().by("sku_code", "SKU3").str("base_unit"))
        assertEquals(5, std.rows().by("sku_code", "SKU4").num("sold_qty_base").toInt()); assertEquals("dozen", std.rows().by("sku_code", "SKU4").str("base_unit"))
        val rrs = run(10, Role.ANALYST, "ds-rrs").result()
        assertTrue(rrs["totals"] == null || rrs["totals"] is JsonNull, "ds-rrs has no totals row")
        assertEquals(50, rrs.rows().by("sku_code", "SKU3").num("sold_qty_base").toInt())
    }
}
