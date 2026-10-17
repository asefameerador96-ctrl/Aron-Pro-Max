package com.aktcl.aron.feature.amo

import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.database.entity.RouteEntity
import com.aktcl.aron.core.database.repo.RouteDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCallTest {
    private fun route(id: Long, code: String, status: String = "active", label: String? = null) = RouteEntity(
        id, code, "Route $id", label, 1, "regular", null, 127, null, status, "primary", null, true, 0, 1, "2026-10-17", "v1",
    )

    private fun outlet(id: Long, route: Long, lat: Double? = 23.78, lng: Double? = 90.4, confirmed: Boolean = true, status: String = "active") = OutletEntity(
        id, route, "C$id", "Shop $id", null, "shop $id", "Owner", null, lat, lng, confirmed, null, null, 1, "K", "gt", null, null, status, "retail", "shop",
        50, 30, null, 0, null, "[]", false,
    )

    @Test fun routesListActiveRoutesByCodeWithActiveOutletCounts() {
        val days = listOf(
            RouteDay(route(2, "B-02", label = "Banani 2"), listOf(outlet(1, 2), outlet(2, 2, status = "closed"))),
            RouteDay(route(1, "A-01"), listOf(outlet(3, 1))),
            RouteDay(route(3, "C-03", status = "inactive"), listOf(outlet(4, 3))),
        )
        val rows = ControlCall.routes(days)
        assertEquals(listOf("Route 1 (A-01)", "Banani 2 (B-02)"), rows.map { it.title })
        assertEquals(listOf(1, 1), rows.map { it.outletCount })
        assertEquals(emptyList<AmoRouteRow>(), ControlCall.routes(emptyList()))
    }

    @Test fun outletOpenIsTaggedControlCallAndKeepsTheOutletsOwnRadius() {
        val v = ControlCall.visitOutlet(outlet(7, 2))
        assertEquals("amo_control_call", v.visitKind)
        assertEquals(50, v.radiusM); assertEquals(30, v.maxAccuracyM); assertEquals("master", v.locationBasis)
        assertEquals("provisional", ControlCall.visitOutlet(outlet(8, 2, confirmed = false)).locationBasis)
        assertEquals("none", ControlCall.visitOutlet(outlet(9, 2, lat = null, lng = null)).locationBasis)
        assertEquals("amo_joint_call", ControlCall.visitOutlet(outlet(7, 2), AmoVisitKind.JOINT_CALL).visitKind)
    }

    @Test fun outletsOfAnUnknownRouteAreEmpty() {
        assertTrue(ControlCall.outlets(listOf(RouteDay(route(1, "A"), listOf(outlet(1, 1)))), 99).isEmpty())
    }

    @Test fun manualOverrideCountsOnlyForcedAmoCallsAndStopsAtTheLimit() {
        val visits = List(9) { AmoVisitKind.CONTROL_CALL to "force_sale" } + listOf("sr_call" to "force_sale", AmoVisitKind.CONTROL_CALL to "validated")
        assertEquals(9, ManualOverrideCap.usedOf(visits))
        assertFalse(ManualOverrideCap.usedOf(visits) >= ManualOverrideCap.DEFAULT_MAX)
        val ten = visits + (AmoVisitKind.JOINT_CALL to "force_sale")
        assertEquals(10, ManualOverrideCap.usedOf(ten))
        assertTrue(ManualOverrideCap.usedOf(ten) >= ManualOverrideCap.DEFAULT_MAX)
    }
}
