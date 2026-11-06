package com.aktcl.aron.feature.amo

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoFindTest {
    @Test fun localDuesAfterTheServerFigureAreAddedAndOlderOnesAreNotCountedTwice() {
        val local = listOf("2026-11-05" to 5_000_000L, "2026-11-06" to 11_642_000L)
        assertEquals(11_642_000L + 20_000_000L, MemoFind.outstandingMtk(20_000_000, "2026-11-05T10:00:00Z", local)) // 11-05 is already in the server figure
        assertEquals(20_000_000L + 5_000_000L + 11_642_000L, MemoFind.outstandingMtk(20_000_000, null, local))
        assertEquals(0L, MemoFind.outstandingMtk(0, "2026-11-06", local)) // as-of today: everything is already in
    }

    @Test fun aCreditIsNeverSubtracted() {
        assertEquals(10_000_000L, MemoFind.outstandingMtk(10_000_000, "2026-11-01", listOf("2026-11-06" to -3_000_000L)))
        assertEquals(0L, MemoFind.outstandingMtk(-5, null, emptyList()))
    }
}
