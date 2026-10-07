package com.aktcl.aron.feature.sale.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditGateTest {
    @Test fun editOnlyInsideTheGeofenceBeforeQcOnTheLiveMemo() {
        assertNull(EditGate.check(true, false, true))
        assertEquals(EditDenied.OutsideGeofence, EditGate.check(false, false, true))
        assertEquals(EditDenied.QcDone, EditGate.check(true, true, true))
        assertEquals(EditDenied.AlreadySuperseded, EditGate.check(true, false, false))
        assertEquals(EditDenied.NoLiveMemo, EditGate.check(true, false, true, memoExists = false))
    }

    @Test fun threeReasonsAreOfferedWithListedWireCodes() {
        assertEquals(listOf("wrong_sku", "wrong_quantity", "wrong_price_type"), EditReason.entries.map { it.wire })
    }

    @Test fun prefillKeepsTheOutletAndLinesAndSupersedesTheOldMemo() {
        val old = SaleDraftOps.setQuantity(Fx.draft(), 100, 10, com.aktcl.aron.rules.QtyUnit.STICK)
        val d = EditGate.prefill(old, 30_000, "11111111-1111-4111-8111-111111111111", EditReason.WRONG_QUANTITY)
        assertEquals(old.outletId, d.outletId); assertEquals(old.lines, d.lines)
        assertEquals(EditContext(old.memoUuid, "wrong_quantity"), d.edit); assertEquals(30_000L, d.paidMtk)
        assertEquals("11111111-1111-4111-8111-111111111111", d.memoUuid)
        assertNull(EditGate.prefill(old, 0, "11111111-1111-4111-8111-111111111111", EditReason.WRONG_SKU).paidMtk)
    }
}
