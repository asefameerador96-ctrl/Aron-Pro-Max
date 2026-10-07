package com.aktcl.aron.feature.outlet

import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.rules.TextRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** F-SR-049. */
class OutletCardTest {
    private fun o(phone: String?) = OutletEntity(
        outletId = 1, routeId = 1, code = "DHK-1", name = "Rahim Store", nameBn = "রহিম স্টোর", nameSortKey = TextRules.nameSortKey("Rahim Store"),
        ownerName = "Rahim", contactNumber = phone, lat = 1.0, lng = 1.0, locationConfirmed = true, provisionalLat = null, provisionalLng = null,
        clusterId = 3, clusterName = "Apsis Cluster", channel = "grocery", subChannelId = null, geoClass = null, status = "active", priceType = "outlet",
        outletKind = "regular", radiusM = 100, maxAccuracyM = 50, visitSequence = 1, openDueMtk = 125_000, openDueAsOf = "2026-10-06",
        programmeFlagsJson = "[]", pendingRequest = true,
    )

    @Test fun cardHasEveryFieldFromLocalDataAndNoLoyalty() {
        val c = OutletCard.of(o("1712345678"), "2026-10-06T08:00:00.000Z")
        assertEquals("Rahim Store", c.name); assertEquals("Rahim", c.ownerName); assertEquals("01712345678", c.phone)
        assertEquals("Apsis Cluster", c.clusterName); assertEquals("grocery", c.channel); assertEquals(125_000L, c.openDueMtk)
        assertEquals("2026-10-06", c.openDueAsOf); assertEquals("2026-10-06T08:00:00.000Z", c.lastVisitAt); assertEquals(true, c.pendingRequest)
        assertEquals(0, OutletCard::class.java.declaredFields.count { it.name.contains("loyalty", ignoreCase = true) })
    }

    @Test fun unusablePhoneAndNoVisitAreNull() {
        val c = OutletCard.of(o("n/a"), null); assertNull(c.phone); assertNull(c.lastVisitAt)
    }
}
