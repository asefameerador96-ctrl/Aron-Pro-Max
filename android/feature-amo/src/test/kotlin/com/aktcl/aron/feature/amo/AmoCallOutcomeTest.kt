package com.aktcl.aron.feature.amo

import org.junit.Assert.assertEquals
import org.junit.Test

class AmoCallOutcomeTest {
    @Test fun aMemoWithLinesMakesTheCallSoldWhateverElseWasDone() {
        assertEquals(AmoCallOutcome.SOLD, AmoCallOutcome.of(memoLines = 3, hasAuditRecord = false))
        assertEquals(AmoCallOutcome.SOLD, AmoCallOutcome.of(memoLines = 1, hasAuditRecord = true))
    }

    @Test fun aZeroSaleMemoIsNotASale() {
        assertEquals(AmoCallOutcome.AUDIT_ONLY, AmoCallOutcome.of(memoLines = 0, hasAuditRecord = false))
    }

    @Test fun anAuditWithoutAMemoIsARealCallAndNothingAtAllIsAbandoned() {
        assertEquals("zero_sale_stock_ok", AmoCallOutcome.of(null, true).wire)
        assertEquals("abandoned", AmoCallOutcome.of(null, false).wire)
    }
}
