package com.aktcl.aron.feature.amo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportsTest {
    private fun page(vararg rows: String, total: Int = rows.size) =
        """{"report_key":"x","as_of":"2026-10-17T05:00:00Z","columns":[{"key":"route_code","label_en":"Route","type":"string"},{"key":"memos","label_en":"Memos","label_bn":"মেমো","type":"integer"}],"rows":[${rows.joinToString(",")}],"page":1,"page_size":500,"total_rows":$total}"""

    @Test fun rangeIsFirstOfMonthToYesterdayAndNeverInverted() {
        assertEquals("2026-10-01" to "2026-10-16", Reports.stdMemoRange("2026-10-17"))
        assertEquals("2026-10-01" to "2026-10-01", Reports.stdMemoRange("2026-10-02"))
        assertEquals("2026-09-01" to "2026-09-30", Reports.stdMemoRange("2026-10-01"))
    }

    @Test fun bodyHasPeriodAndJsonOutputOnlyNoScope() {
        val b = Reports.body("2026-10-01", "2026-10-16", 2)
        assertTrue("\"from\":\"2026-10-01\"" in b && "\"page\":2" in b && "\"format\":\"json\"" in b); assertFalse("geo" in b)
    }

    @Test fun parseAndCombineKeepAllRowsAndFlagTruncation() {
        val a = page("""{"route_code":"R1","memos":3}""", total = 2)
        val b = page("""{"route_code":"R2","memos":4}""", total = 2)
        val all = Reports.parse(Reports.combine(listOf(a, b)))!!
        assertEquals(2, all.rows.size); assertFalse(all.truncated); assertEquals("মেমো", all.columns[1].labelBn)
        assertTrue(Reports.parse(page("""{"route_code":"R1","memos":3}""", total = 900))!!.truncated)
        assertNull(Reports.parse("[")); assertNull(Reports.parse(null))
    }

    @Test fun salesSummaryCardsGroupBrandsAndAZeroTargetIsADash() {
        val rows = listOf(
            """{"route_code":"R1","route_name":"North","target_outlets":80,"successful_calls":20,"cpr_pct":25.0,"memos":18,"net_mtk":900000,"brand_name":"A","brand_memos":10,"bsr_pct":55.56}""",
            """{"route_code":"R1","route_name":"North","target_outlets":80,"successful_calls":20,"cpr_pct":25.0,"memos":18,"net_mtk":900000,"brand_name":"B","brand_memos":8,"bsr_pct":null}""",
            """{"route_code":"R2","route_name":"South","target_outlets":0,"successful_calls":0,"cpr_pct":50.0,"memos":0,"net_mtk":0,"brand_name":null,"brand_memos":null,"bsr_pct":null}""",
        )
        val cards = Reports.routeCards(Reports.parse(page(*rows.toTypedArray()))!!)
        assertEquals(listOf("R1", "R2"), cards.map { it.code }); assertEquals(25.0, cards[0].cprPct!!, 0.0)
        assertEquals(listOf(BrandRow("A", 10, 55.56), BrandRow("B", 8, null)), cards[0].brands)
        assertNull(cards[1].cprPct); assertTrue(cards[1].brands.isEmpty())
    }

    @Test fun stdMemoTotalsPerRoute() {
        val r = Reports.parse(page("""{"route_code":"R1","gross_mtk":100,"net_mtk":90}""", """{"route_code":"R1","gross_mtk":50,"net_mtk":45}""", """{"route_code":"R2","gross_mtk":10,"net_mtk":9}"""))!!
        assertEquals(listOf(RouteStd("R1", 2, 150, 135), RouteStd("R2", 1, 10, 9)), Reports.stdByRoute(r))
    }
}
