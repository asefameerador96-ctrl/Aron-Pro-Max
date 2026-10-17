package com.aktcl.aron.feature.amo

import org.junit.Assert.assertEquals
import org.junit.Test

class AmoCallOutcomeTest {
    @Test fun aMemoMakesTheCallSoldWhateverElseWasDone() {
        assertEquals(AmoCallOutcome.SOLD, AmoCallOutcome.of(hasMemo = true, hasAuditRecord = false))
        assertEquals(AmoCallOutcome.SOLD, AmoCallOutcome.of(hasMemo = true, hasAuditRecord = true))
    }

    @Test fun anAuditWithoutAMemoIsARealCallAndNothingAtAllIsAbandoned() {
        assertEquals("zero_sale_stock_ok", AmoCallOutcome.of(false, true).wire)
        assertEquals("abandoned", AmoCallOutcome.of(false, false).wire)
    }
}
