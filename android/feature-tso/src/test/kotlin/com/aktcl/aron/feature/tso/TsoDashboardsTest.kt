package com.aktcl.aron.feature.tso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TsoDashboardsTest {
    private val summary = """{"as_of":"2026-10-17T08:30:00Z","from":"2026-10-17","to":"2026-10-17","node":{"type":"territory","id":3,"name":"T3"},
      "kpis":{"target_routes":4,"logged_in_routes":1,"login_pct":25.0,"sales_submitted_routes":0,"submit_pct_of_logged_in":0.0,"day_completion_pct":null,"target_outlets":44,"visited_outlets":30,"successful_calls":2,"strike_rate_pct":6.67,"active_memo_count":2,"gross_mtk":900000,"net_mtk":850000,"geo_valid_pct":null,"force_sale_pct":null,"mock_visits":0,"suspicious_visits":0},
      "by_category":[{"category_code":"cigarette","base_unit":"stick","qty_base":36100000,"report_unit":"stick","qty_report":"36100000.000","net_mtk":700000},{"category_code":"match","base_unit":"dozen","qty_base":28067,"report_unit":null,"qty_report":null,"net_mtk":1000}],
      "by_channel":[{"code":"GT","name":"General Trade","net_mtk":600,"memo_count":1,"successful_calls":1},{"code":"DCC","net_mtk":400,"memo_count":1,"successful_calls":1}],
      "by_brand":[{"code":"B1","name":"Brand 1","net_mtk":1,"memo_count":1,"successful_calls":1,"memo_ratio_pct":50.0},{"code":"B2","net_mtk":1,"memo_count":0,"successful_calls":0}],
      "children":[{"node":{"type":"zone","id":11,"name":"Zone 11"},"kpis":{}},{"node":{"type":"route","id":99,"name":"R99"},"kpis":{}}]}"""

    @Test fun parsesTheTerritoryFigures() {
        val d = TsoDashboards.summary(summary)!!
        assertEquals(2, d.categories.size); assertEquals(36100000L, d.categories[0].qtyBase); assertEquals(36100000.0, d.categories[0].qtyReport!!, 0.0); assertNull(d.categories[1].qtyReport)
        assertEquals(listOf("GT", "DCC"), d.channels.map { it.code }); assertEquals("DCC", d.channels[1].name)
        assertEquals(44L, d.targetOutlets); assertEquals(listOf(ZoneRef(11, "Zone 11")), d.zones) // a route child is not a zone; assertEquals("2026-10-17T08:30:00Z", d.asOf)
    }

    @Test fun strikeRateIsOneDecimalFromCallsOnly() {
        assertEquals("4.5", TsoDashboards.oneDecimal(TsoDashboards.strikeRate(2, 44)!!)); assertNull(TsoDashboards.strikeRate(0, 0))
    }

    @Test fun donutSharesSumTo100AndANegativeOrZeroTotalIsSafe() {
        val s = TsoDashboards.shares(listOf(600, 400)); assertEquals(60.0, s[0], 0.0); assertEquals(100.0, s.sum(), 0.0001)
        assertEquals(listOf(0.0, 0.0), TsoDashboards.shares(listOf(0, -5)))
    }

    @Test fun compactUsesLakhAndCrore() {
        assertEquals(TsoDashboards.Compact("3.61", TsoDashboards.Scale.CRORE), TsoDashboards.compact(36_100_000))
        assertEquals(TsoDashboards.Compact("1.11", TsoDashboards.Scale.LAKH), TsoDashboards.compact(111_000))
        assertEquals(TsoDashboards.Compact("28067", TsoDashboards.Scale.NONE), TsoDashboards.compact(28_067))
    }

    @Test fun asOfIsDhakaTime() {
        assertEquals("14:30", TsoDashboards.asOfClock("2026-10-17T08:30:00Z")); assertNull(TsoDashboards.asOfClock("x"))
    }

    @Test fun brandRatioUsesTheServersOrDerivesItAndDashesWithoutMemos() {
        val d = TsoDashboards.summary(summary)!!
        assertEquals(50.0, TsoDashboards.brandRatio(d.brands[0], 2)!!, 0.0)
        assertEquals(0.0, TsoDashboards.brandRatio(d.brands[1], 2)!!, 0.0); assertNull(TsoDashboards.brandRatio(d.brands[1], 0))
    }

    @Test fun loginStatusAndTheDrillDownLists() {
        val j = """{"as_of":"x","business_date":"2026-10-17","kpis":{"target_routes":4,"logged_in_routes":1,"login_pct":25.0,"sales_submitted_routes":0,"submit_pct_of_logged_in":0.0},
          "not_logged_in":[{"route_id":2,"route_code":"R2","state":"not_started"},{"route_id":3,"state":"not_started"},{"route_id":4,"route_code":"R4","state":"not_started"}],
          "logged_in_not_submitted":[{"route_id":1,"route_code":"R1","state":"open"}],"submitted":[]}"""
        val ls = TsoDashboards.loginSubmit(j)!!
        assertEquals(25.0, ls.loginPct!!, 0.0); assertEquals(3, ls.notLoggedIn.size); assertEquals("R1", ls.loggedInNotSubmitted[0].routeCode); assertTrue(ls.submittedRoutes.isEmpty())
    }

    @Test fun malformedIsNull() { assertNull(TsoDashboards.summary("[")); assertNull(TsoDashboards.summary(null)); assertNull(TsoDashboards.loginSubmit("{}")) }

    @Test fun roundingUsesTheDecimalValueNotTheBinaryOne() {
        assertEquals("4.6", TsoDashboards.oneDecimal(4.55)); assertEquals("1.2", TsoDashboards.oneDecimal(1.15)); assertEquals("100.0", TsoDashboards.oneDecimal(100.0))
    }

    @Test fun anEmptyTileKeepsItsOwnUnit() {
        assertEquals("stick", TsoDashboards.defaultUnit("cigarette")); assertEquals("stick", TsoDashboards.defaultUnit("bidi")); assertEquals("piece", TsoDashboards.defaultUnit("lighter")); assertEquals("dozen", TsoDashboards.defaultUnit("match"))
    }

    @Test fun routeRowsKeepTheName() {
        val ls = TsoDashboards.loginSubmit("""{"as_of":"x","kpis":{},"not_logged_in":[{"route_id":2,"route_code":"R2","route_name":"North","state":"x"}],"logged_in_not_submitted":[],"submitted":[]}""")!!
        assertEquals("North", ls.notLoggedIn[0].routeName)
    }
}
