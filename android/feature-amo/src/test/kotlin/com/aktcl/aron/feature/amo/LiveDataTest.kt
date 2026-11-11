package com.aktcl.aron.feature.amo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveDataTest {
    private val json = """{"as_of":"2026-11-06T10:00:00Z","business_date":"2026-11-06",
      "kpis":{"target_routes":2,"logged_in_routes":1,"sales_submitted_routes":0,"target_outlets":641,"successful_calls":259,"active_memo_count":38,"net_mtk":161500,"gross_mtk":1700000},
      "geo_valid_visits":288,"photo_valid_visits":1,"visits_total":290,
      "sales_by_category":[{"category_code":"match","base_unit":"dozen","qty_base":103},{"category_code":"cigarette","base_unit":"stick","qty_base":44310},{"category_code":"lighter","base_unit":"piece","qty_base":80}],
      "not_logged_in":[{"route_id":2}],"logged_in_not_submitted":[{"route_id":1}],"submitted":[]}"""

    @Test fun ratesAreComputedOnceFromIntegers() {
        val d = LiveDataParser.parse(json)!!
        assertEquals("40.41%", d.strikeText()); assertEquals("99.31%", d.geoText()) // 259/641 and 288/290 as screenshot 39
        assertEquals("50%", d.loginText()); assertEquals("0%", d.submitText())
        assertEquals("161.50", d.totalTaka()); assertEquals(38L, d.memoCount)
    }

    @Test fun columnsAreTheFourCategoriesInScreenOrderMissingIsZero() {
        val c = LiveDataParser.parse(json)!!.columns()
        assertEquals(listOf("cigarette", "bidi", "lighter", "match"), c.map { it.code }); assertEquals(listOf(44310L, 0L, 80L, 103L), c.map { it.qtyBase })
    }

    @Test fun oneRouteKeepsItsOwnLoginRows() {
        val d = LiveDataParser.parse(json, 1L)!!
        assertEquals(1L, d.targetRoutes); assertEquals(1L, d.loggedInRoutes); assertEquals(0L, d.submittedRoutes); assertEquals("100%", d.loginText())
    }

    @Test fun zeroBasesAreDashesAndGarbageIsNull() {
        val d = LiveDataParser.parse("""{"kpis":{}}""")!!
        assertEquals("—", d.strikeText()); assertEquals("—", d.geoText()); assertNull(d.loginPct)
        assertNull(d.geoValid); assertNull(d.totalCalls) // a null count from a non-AMO caller is a dash, never 0
        assertNull(LiveDataParser.parse(null)); assertNull(LiveDataParser.parse("[]"))
    }
}
