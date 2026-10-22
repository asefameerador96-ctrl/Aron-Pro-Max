package com.aktcl.aron.feature.amo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamStockTest {
    private val json = """{"as_of":"x","business_date":"2026-10-17","items":[
      {"user_id":2,"full_name":"Zed","by_sku":[
        {"sku_id":1,"base_unit":"stick","issued_qty_base":200,"sold_qty_base":50,"returned_qty_base":0,"current_qty_base":150},
        {"sku_id":2,"base_unit":"stick","issued_qty_base":100,"sold_qty_base":0,"returned_qty_base":0,"current_qty_base":100},
        {"sku_id":3,"base_unit":"piece","issued_qty_base":12,"sold_qty_base":2,"returned_qty_base":0,"current_qty_base":10}]},
      {"user_id":1,"full_name":"Amin","by_sku":[]}]}"""

    @Test fun parsesSrsInNameOrderWithLiftedAndCurrent() {
        val s = TeamStock.parse(json)
        assertEquals(listOf("Amin", "Zed"), s.map { it.name })
        assertEquals(200L, s[1].lines[0].lifted); assertEquals(150L, s[1].lines[0].current)
    }

    @Test fun totalsPerCategoryNeverMixUnits() {
        val skus = mapOf(1L to SkuInfo("A", null, "cig"), 2L to SkuInfo("B", null, "cig"), 3L to SkuInfo("L", null, "lighter"))
        val t = TeamStock.totals(TeamStock.parse(json)[1].lines, skus)
        assertEquals(listOf(CategoryTotal("cig", "stick", 300), CategoryTotal("lighter", "piece", 12)), t)
        assertEquals(listOf(CategoryTotal("", "stick", 300), CategoryTotal("", "piece", 12)).sortedBy { it.unit }, TeamStock.totals(TeamStock.parse(json)[1].lines, emptyMap()))
    }

    @Test fun phonesAndMalformed() {
        assertEquals(mapOf(2L to "017****678"), TeamStock.phones("""{"team":[{"user_id":2,"phone_masked":"017****678"},{"user_id":3,"phone_masked":null}]}"""))
        assertTrue(TeamStock.parse("[").isEmpty())
    }
}

/** D-UNITS-02: the AMO's SR stock shows packets, boxes and dozens (the server still sends base quantities). */
class TeamStockUnitsTest {
    private val pack10 = com.aktcl.aron.rules.EntrySpec(com.aktcl.aron.rules.QtyUnit.PACK, 10)
    private val pack25 = com.aktcl.aron.rules.EntrySpec(com.aktcl.aron.rules.QtyUnit.PACK, 25)
    private val dozen = com.aktcl.aron.rules.EntrySpec(com.aktcl.aron.rules.QtyUnit.DOZEN, 1)
    private val skus = mapOf(
        1L to SkuInfo("A", null, "cigarette", pack10), 2L to SkuInfo("B", null, "cigarette", com.aktcl.aron.rules.EntrySpec(com.aktcl.aron.rules.QtyUnit.PACK, 20)),
        3L to SkuInfo("L", null, "lighter", pack25), 4L to SkuInfo("M", null, "match", dozen),
    )
    @org.junit.Test fun totalsAddEntryUnitsPerCategoryNeverBaseSticksOfDifferentPackSizes() {
        val lines = listOf(StockLine(1, "stick", 100, 30, 0, 70), StockLine(2, "stick", 200, 0, 0, 200), StockLine(3, "piece", 75, 25, 0, 50), StockLine(4, "dozen", 9, 3, 0, 6))
        val t = TeamStock.totals(lines, skus).associateBy { it.categoryCode }
        org.junit.Assert.assertEquals(20L, t.getValue("cigarette").lifted) // 10 packets of 10s + 10 packets of 20s
        org.junit.Assert.assertEquals("packet", t.getValue("cigarette").unitKey)
        org.junit.Assert.assertEquals(3L, t.getValue("lighter").lifted); org.junit.Assert.assertEquals("box", t.getValue("lighter").unitKey)
        org.junit.Assert.assertEquals(9L, t.getValue("match").lifted); org.junit.Assert.assertEquals("dozen", t.getValue("match").unitKey)
    }
}
