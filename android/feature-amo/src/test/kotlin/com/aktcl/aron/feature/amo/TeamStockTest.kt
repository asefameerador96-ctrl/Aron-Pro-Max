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
