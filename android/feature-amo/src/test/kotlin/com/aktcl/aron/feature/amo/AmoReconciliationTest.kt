package com.aktcl.aron.feature.amo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmoReconciliationTest {
    @Test fun withoutConfigTheNineAmoRowsStandIn() {
        val t = AmoReconciliation.types(null)
        assertEquals(listOf("visit", "memo", "stock_movement", "qc_line", "memo_discount", "distribution_check", "distribution_check_line", "price_compliance_check", "call_assessment", "call_assessment_answer", "survey_response"), t)
    }

    @Test fun anAmoConfigWinsOverTheDefault() {
        val cfg = """{"AMO.sale": ["memo"], "AMO.survey": ["survey_response"], "SR.sale": ["memo"]}"""
        assertEquals(listOf("memo", "survey_response"), AmoReconciliation.types(cfg))
        // a config that only has SR rows leaves the AMO on its default
        assertTrue("visit" in AmoReconciliation.types("""{"SR.sale": ["memo"]}"""))
    }

    @Test fun everyCoveredTypeIsThereWithZeroAndOtherTypesStay() {
        val t = AmoReconciliation.types(null)
        val d = AmoReconciliation.deviceCounts(t, mapOf("memo" to 3, "due_collection" to 1))
        assertEquals(3, d["memo"]); assertEquals(0, d["survey_response"]); assertEquals(1, d["due_collection"])
        assertTrue(t.all { it in d })
    }

    @Test fun serverZerosMatchDeviceZerosButNoAnswerYetStaysNull() {
        val t = AmoReconciliation.types(null)
        assertNull(AmoReconciliation.serverCounts(t, null))
        val s = AmoReconciliation.serverCounts(t, mapOf("memo" to 3))!!
        assertEquals(0, s["price_compliance_check"]); assertEquals(3, s["memo"])
    }
}
