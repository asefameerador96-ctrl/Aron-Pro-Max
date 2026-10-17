package com.aktcl.aron.feature.amo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmoReconciliationTest {
    @Test fun everyFixedRowIsThereWithZeroAndOtherTypesStay() {
        val d = AmoReconciliation.deviceCounts(mapOf("memo" to 3, "due_collection" to 1))
        assertEquals(3, d["memo"]); assertEquals(0, d["survey_response"]); assertEquals(1, d["due_collection"])
        assertTrue(AmoReconciliation.FIXED_TYPES.all { it in d })
    }

    @Test fun serverZerosMatchDeviceZerosButNoAnswerYetStaysNull() {
        assertNull(AmoReconciliation.serverCounts(null))
        val s = AmoReconciliation.serverCounts(mapOf("memo" to 3))!!
        assertEquals(0, s["price_compliance_check"]); assertEquals(3, s["memo"])
    }
}
