package com.aktcl.aron.feature.outlet

import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.rules.TextRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-SR-016 and F-SR-074. */
class OutletPickerTest {
    private fun outlet(id: Long, name: String, phone: String? = "01712345678", status: String = "active", seq: Int? = null, lat: Double? = 23.79, lng: Double? = 90.40) =
        OutletEntity(
            outletId = id, routeId = 1, code = "DHK-$id", name = name, nameBn = null, nameSortKey = TextRules.nameSortKey(name),
            ownerName = "x", contactNumber = phone, lat = lat, lng = lng, locationConfirmed = true, provisionalLat = null,
            provisionalLng = null, clusterId = 1, clusterName = "Apsis Cluster", channel = "grocery", subChannelId = null, geoClass = null,
            status = status, priceType = "outlet", outletKind = "regular", radiusM = 100, maxAccuracyM = 50, visitSequence = seq,
            openDueMtk = 0, openDueAsOf = null, programmeFlagsJson = "[]", pendingRequest = false,
        )

    @Test fun labelIsNameWithCodePhoneCluster() {
        assertEquals("Rahim Store (DHK-1-01712345678-Apsis Cluster)", OutletPicker.label(outlet(1, "Rahim Store")))
    }

    @Test fun tenDigitAndBengaliPhonesShowAsElevenDigits() {
        assertTrue(OutletPicker.label(outlet(1, "A", phone = "1712345678")).contains("-01712345678-"))
        assertTrue(OutletPicker.label(outlet(1, "A", phone = "০১৭১২৩৪৫৬৭৮")).contains("-01712345678-"))
    }

    @Test fun unusablePhoneIsLeftOut() {
        assertEquals("A (DHK-1-Apsis Cluster)", OutletPicker.label(outlet(1, "A", phone = "n/a")))
        assertEquals("A (DHK-1-Apsis Cluster)", OutletPicker.label(outlet(1, "A", phone = null)))
    }

    @Test fun closedMergedArchivedAreHidden() {
        val rows = OutletPicker.rows(listOf(outlet(1, "A"), outlet(2, "B", status = "closed"), outlet(3, "C", status = "merged"), outlet(4, "D", status = "archived")))
        assertEquals(listOf(1L), rows.map { it.outlet.outletId })
    }

    @Test fun chipsAreCaseInsensitiveLatinThenBangla() {
        val os = listOf(outlet(1, "Sumon"), outlet(2, "shahin"), outlet(3, "Tania"), outlet(4, "রহিম স্টোর"), outlet(5, "আলম"), outlet(6, "7 Star"))
        val rows = OutletPicker.rows(os)
        assertEquals(listOf("S", "T", "আ", "র", "#"), OutletPicker.chips(rows))
        assertEquals(listOf(1L, 2L), OutletPicker.rows(os, chip = "S").map { it.outlet.outletId }.sorted())
    }

    @Test fun banglaFilterChipSelectsBanglaNames() {
        val os = listOf(outlet(4, "রহিম স্টোর"), outlet(5, "রফিক"), outlet(6, "Rahim"))
        assertEquals(listOf(4L, 5L), OutletPicker.rows(os, chip = "র").map { it.outlet.outletId }.sorted())
    }

    @Test fun plannedOrderIsKeptWithoutDistanceSort() {
        val os = listOf(outlet(1, "Z", seq = 2), outlet(2, "A", seq = 1), outlet(3, "M", seq = null))
        assertEquals(listOf(2L, 1L, 3L), OutletPicker.rows(os).map { it.outlet.outletId })
    }

    @Test fun distanceSortOnlyWhenSettingOnAndFixPresent() {
        val os = listOf(outlet(1, "Far", seq = 1, lat = 23.80), outlet(2, "Near", seq = 2, lat = 23.7901), outlet(3, "NoGeo", seq = 3, lat = null, lng = null))
        assertEquals(listOf(1L, 2L, 3L), OutletPicker.rows(os, sortByDistance = true).map { it.outlet.outletId }) // no fix
        assertEquals(listOf(1L, 2L, 3L), OutletPicker.rows(os, sortByDistance = false, fixLat = 23.79, fixLng = 90.40).map { it.outlet.outletId }) // setting off
        val sorted = OutletPicker.rows(os, sortByDistance = true, fixLat = 23.79, fixLng = 90.40)
        assertEquals(listOf(2L, 1L, 3L), sorted.map { it.outlet.outletId })
        assertFalse(sorted.last().distanceM != null)
    }

    @Test fun invalidFixIsIgnored() {
        val os = listOf(outlet(1, "A", seq = 1), outlet(2, "B", seq = 2))
        assertEquals(listOf(1L, 2L), OutletPicker.rows(os, sortByDistance = true, fixLat = 200.0, fixLng = 0.0).map { it.outlet.outletId })
    }
}
