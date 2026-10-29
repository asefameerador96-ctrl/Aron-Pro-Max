package com.aktcl.aron.tso

import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.database.entity.RouteEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The TSO bundle's outlets and routes (D-TSO-BUNDLE-OUTLETS) become the plan pickers' rows with the AMO outlet fields. */
class TsoBundleReadsTest {
    private fun outlet(id: Long, name: String, status: String = "active", phone: String? = "01712345678", cluster: String = "Banani", owner: String = "Rahim") = OutletEntity(
        outletId = id, routeId = 9, code = "DHK-$id", name = name, nameBn = "বাংলা $id", nameSortKey = name, ownerName = owner, contactNumber = phone,
        lat = 23.79, lng = 90.40, locationConfirmed = true, provisionalLat = null, provisionalLng = null, clusterId = 1, clusterName = cluster,
        channel = "grocery", subChannelId = null, geoClass = null, status = status, priceType = "outlet", outletKind = "regular", radiusM = 100,
        maxAccuracyM = 50, visitSequence = null, openDueMtk = 125_000, openDueAsOf = null, programmeFlagsJson = "[]", pendingRequest = false,
    )

    private fun route(id: Long, status: String = "active", label: String? = null) = RouteEntity(
        routeId = id, code = "R$id", name = "Route $id", displayLabel = label, zoneId = 10, kind = "sr", visitKind = null, visitDaysMask = 127, sequenceNo = null,
        status = status, assignmentKind = "primary", actingForUserId = null, plannedToday = true, targetOutlets = 3, routeSnapshotVersion = 1,
        businessDate = "2026-10-18", bundleVersion = "b1",
    )

    @Test fun outletsKeepTheAmoFieldsOwnerAndPhoneAndLeaveOutInactiveOnes() {
        val rows = TsoBundleReads.outlets(listOf(outlet(1, "Rahim Store"), outlet(2, "Closed", status = "closed"), outlet(3, "No Phone", phone = null, cluster = " ")))
        assertEquals(listOf(1L, 3L), rows.map { it.id })
        assertEquals("Rahim", rows[0].owner); assertEquals("01712345678", rows[0].phone); assertEquals("Banani", rows[0].cluster); assertEquals("DHK-1", rows[0].code)
        assertNull(rows[1].phone); assertNull("a blank cluster is no cluster", rows[1].cluster)
        assertEquals("বাংলা 1", rows[0].title(true)); assertEquals("Rahim Store", rows[0].title(false))
    }

    @Test fun routesKeepZoneAndLabelAndNameNoSr() {
        val rows = TsoBundleReads.routes(listOf(route(1, label = "North"), route(2, status = "inactive")))
        assertEquals(listOf(1L), rows.map { it.id }); assertEquals(10L, rows[0].zoneId); assertEquals("North", rows[0].label); assertNull(rows[0].sr)
    }

    // ---- tso-manual X4b (R-W-12 (9)): the route's SR from the bundle (R-W-06 `sr_user_id` / `sr_name`)

    @Test fun theSrOfARouteSnapshotIsReadAndAVacantRouteIsCarriedWithoutSr() {
        assertEquals(TsoBundleReads.SrInBundle(true, com.aktcl.aron.feature.tso.mycall.RouteSr(7, "Rahim")), TsoBundleReads.srOf("""{"route_id":1,"sr_user_id":7,"sr_name":" Rahim "}"""))
        assertEquals(TsoBundleReads.SrInBundle(true, null), TsoBundleReads.srOf("""{"route_id":1,"sr_user_id":null,"sr_name":null}"""))
        // not carried: an older server, an unreadable section, a bad id, a named id without its name
        for (raw in listOf(null, "", "not json", "[1]", """{"route_id":1}""", """{"sr_user_id":"7","sr_name":"R"}""", """{"sr_user_id":0,"sr_name":"R"}""", """{"sr_user_id":7}""", """{"sr_user_id":7,"sr_name":"  "}""")) {
            assertEquals("$raw", TsoBundleReads.SrInBundle(false, null), TsoBundleReads.srOf(raw))
        }
    }

    @Test fun routesWithSrNameEveryRouteOrAreNullForAnOlderBundle() {
        val extras = mapOf(1L to """{"sr_user_id":7,"sr_name":"Rahim"}""", 2L to """{"sr_user_id":null,"sr_name":null}""", 3L to """{"planned_today":true}""")
        val rows = TsoBundleReads.routesWithSr(listOf(route(1, label = "North"), route(2), route(3), route(4, status = "inactive"))) { extras[it] }!!
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.id })
        assertEquals(com.aktcl.aron.feature.tso.mycall.RouteSr(7, "Rahim"), rows[0].sr); assertEquals("North", rows[0].label)
        assertNull(rows[1].sr); assertNull("a route without the members beside routes that carry them is vacant", rows[2].sr)
        assertNull(TsoBundleReads.routesWithSr(listOf(route(1), route(2))) { """{"planned_today":true}""" })
        assertNull(TsoBundleReads.routesWithSr(emptyList()) { null })
    }
}
