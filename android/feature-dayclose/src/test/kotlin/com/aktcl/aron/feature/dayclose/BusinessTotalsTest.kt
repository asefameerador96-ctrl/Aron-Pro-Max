package com.aktcl.aron.feature.dayclose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SRM-B-25: the compare rule of the business table is the count table's (device ⊆ server) per category. */
class BusinessTotalsTest {
    private fun q(cat: String, base: Long, unit: String = "packet", entered: Long = base / 10) = CategoryQty(cat, base, entered, unit)
    private val device = BusinessTotals(3, listOf(q("cigarette", 300), q("match", 12, "dozen", 12)), listOf(q("cigarette", 1000)), emptyList(), 0)

    @Test fun noServerFiguresIsUnknownNotAMismatch() {
        BusinessKey.entries.forEach { assertNull(BusinessCompare.matches(it, device, null)) }
    }

    @Test fun serverAtLeastTheDeviceMatchesAndAnExtraPhoneNeverMismatches() {
        val server = BusinessTotals(5, listOf(q("cigarette", 400), q("match", 12, "dozen", 12)), listOf(q("cigarette", 1000)), listOf(q("cigarette", 20)), 500)
        BusinessKey.entries.forEach { assertEquals(it.name, true, BusinessCompare.matches(it, device, server)) }
    }

    @Test fun lessOnTheServerForOneCategoryMismatchesOnlyThatRow() {
        val server = BusinessTotals(3, listOf(q("cigarette", 300), q("match", 6, "dozen", 6)), listOf(q("cigarette", 1000)), emptyList(), 0)
        assertEquals(false, BusinessCompare.matches(BusinessKey.SALE, device, server))
        assertEquals(true, BusinessCompare.matches(BusinessKey.STOCK, device, server))
        assertEquals(true, BusinessCompare.matches(BusinessKey.OUTLET, device, server))
    }

    @Test fun categoriesAreNeverAddedAcrossEachOther() {
        // 300 cigarette sticks on the server do not cover 300 on the device split as 200 cigarette + 100 match
        val d = BusinessTotals(0, listOf(q("cigarette", 200), q("match", 100, "dozen", 100)), emptyList(), emptyList(), 0)
        val s = BusinessTotals(0, listOf(q("cigarette", 300)), emptyList(), emptyList(), 0)
        assertEquals(false, BusinessCompare.matches(BusinessKey.SALE, d, s))
    }
}
