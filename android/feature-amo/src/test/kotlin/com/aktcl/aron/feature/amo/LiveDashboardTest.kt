package com.aktcl.aron.feature.amo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveDashboardTest {
    private val summary = """{"as_of":"2026-10-17T05:00:00Z","kpis":{"target_routes":10,"logged_in_routes":8,"login_pct":80.0,"sales_submitted_routes":3,"submit_pct_of_logged_in":37.5,"day_completion_pct":null,
      "target_outlets":200,"visited_outlets":81,"successful_calls":1,"strike_rate_pct":1.23,"active_memo_count":1,"gross_mtk":125000,"net_mtk":120000,"geo_valid_pct":null,"force_sale_pct":5.0,"mock_visits":2,"suspicious_visits":1}}"""
    private val login = """{"kpis":{"target_routes":10},"not_logged_in":[{"route_id":1},{"route_id":2}],"logged_in_not_submitted":[{"route_id":3}],"submitted":[{"route_id":4}]}"""

    @Test fun readsTheZoneFiguresAndKeepsNullAsADash() {
        val f = LiveDashboard.parse(summary, login)!!
        assertEquals(1.23, f.strikeRatePct!!, 0.0); assertEquals("1.23", LiveDashboard.pct(f.strikeRatePct)); assertNull(f.geoValidPct); assertNull(LiveDashboard.pct(null))
        assertEquals(125000L, f.grossMtk); assertEquals(8L, f.loggedInRoutes); assertEquals(listOf(1L, 2L), f.notLoggedIn)
    }

    @Test fun aRouteFilterNarrowsTheLoginAndSubmitListsOnly() {
        val f = LiveDashboard.parse(summary, login, routeId = 3)!!
        assertEquals(listOf(3L), f.loggedInNotSubmitted); assertEquals(emptyList<Long>(), f.notLoggedIn); assertEquals(1L, f.targetRoutes); assertEquals(1L, f.loggedInRoutes)
        assertNull(f.loginPct); assertEquals(81L, f.visitedOutlets)
    }

    @Test fun strikeRateIsTwoDecimalsAndDashWithoutCalls() {
        assertEquals("1.23", LiveDashboard.pct(LiveDashboard.strikeRate(1, 81))); assertNull(LiveDashboard.strikeRate(0, 0))
    }

    @Test fun malformedIsNull() { assertNull(LiveDashboard.parse("[", null)); assertNull(LiveDashboard.parse(null, null)) }
}
