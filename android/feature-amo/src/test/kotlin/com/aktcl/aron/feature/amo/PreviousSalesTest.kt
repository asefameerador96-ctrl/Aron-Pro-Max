package com.aktcl.aron.feature.amo

import com.aktcl.aron.rules.EntrySpec
import com.aktcl.aron.rules.QtyUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Screenshots 24 and 26: previous sale data for a date, from the server (F2 shape) or from this phone's own memos when offline. */
class PreviousSalesTest {
    private val server = """{"business_date":"2026-11-05","lines":[
        {"sku":{"sku_id":11,"sku_code":"MAXR-10S","short_name":"MaxR-10S"},"qty_report":"10.000","report_unit":"packet","qty_base":100,"value_mtk":80000000},
        {"sku":{"sku_id":12,"sku_code":"ABS","short_name":"ABS"},"qty_report":"25.500","report_unit":"packet","qty_base":250,"value_mtk":20005000}],
        "totals":{"value_mtk":999}}"""

    @Test fun serverLinesAreReadAndTheTotalIsTheTrueSumNotTheServersTotals() {
        val h = PreviousSales.parseServer(server, "2026-11-05")!!
        assertEquals(listOf("MaxR-10S", "ABS"), h.lines.map { it.name })
        assertEquals(listOf("10.000" to "packet", "25.500" to "packet"), h.lines.map { it.qtyReport.toPlainString() to it.reportUnit })
        assertEquals(100_005_000L, h.totalMtk) // R3: the vendor total did not equal its lines; ours sums integer milli-taka exactly
        assertEquals(SaleHistorySource.SERVER, h.source)
    }

    @Test fun aBodyThatIsNotTheShapeIsNull() {
        assertNull(PreviousSales.parseServer(null, "d")); assertNull(PreviousSales.parseServer("nope", "d")); assertNull(PreviousSales.parseServer("""{"x":1}""", "d"))
        assertEquals(0, PreviousSales.parseServer("""{"lines":[]}""", "d")!!.lines.size)
    }

    @Test fun offlineFallbackSumsThisPhonesLiveMemosAndSkipsAnEditedOriginal() {
        val skus = mapOf(11L to SkuInfo("MaxR-10S", null, "cigarette", EntrySpec(QtyUnit.PACK, 10)))
        val original = LocalMemo("m1", null, listOf(PreviousSales.LocalMemoLine(11, 100, 80_000_000)))
        val edited = LocalMemo("m2", "m1", listOf(PreviousSales.LocalMemoLine(11, 50, 40_000_000)))
        val other = LocalMemo("m3", null, listOf(PreviousSales.LocalMemoLine(11, 30, 24_000_000)))
        val h = PreviousSales.fromLocal("2026-11-06", listOf(original, edited, other), skus)
        assertEquals(1, h.lines.size)
        assertEquals(80, h.lines[0].qtyBase.toInt()) // 50 (edit replaces the original) + 30
        assertEquals(0, java.math.BigDecimal(8).compareTo(h.lines[0].qtyReport)) // 80 sticks = 8 packets of 10
        assertEquals("packet", h.lines[0].reportUnit)
        assertEquals(64_000_000L, h.totalMtk)
        assertEquals(SaleHistorySource.LOCAL, h.source)
    }

    @Test fun theDateStepsStayInsideTheNinetyDayWindow() {
        val today = "2026-11-06"
        assertEquals("2026-11-05", PreviousSales.shift(today, -1, today))
        assertEquals(today, PreviousSales.shift(today, 1, today)) // never past today
        assertEquals("2026-08-09", PreviousSales.earliest(today)); assertEquals("2026-08-09", PreviousSales.shift("2026-08-09", -1, today))
        assertTrue(PreviousSales.inWindow("2026-08-09", today)); assertFalse(PreviousSales.inWindow("2026-08-08", today)); assertFalse(PreviousSales.inWindow("junk", today))
    }
}
