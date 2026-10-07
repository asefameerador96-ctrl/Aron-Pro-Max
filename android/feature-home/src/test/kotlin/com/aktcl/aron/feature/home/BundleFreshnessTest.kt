package com.aktcl.aron.feature.home

import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-SR-063. */
class BundleFreshnessTest {
    private val today = LocalDate(2026, 10, 7)

    @Test fun todaysBundleIsFresh() {
        val f = BundleFreshness.of(today, today)
        assertEquals(BundleFreshness.Fresh, f); assertTrue(f.canSell); assertFalse(f.showBanner)
    }

    @Test fun oneDayOldSellsWithBannerAndFlagsRowsStale() {
        val f = BundleFreshness.of(LocalDate(2026, 10, 6), today)
        assertTrue(f is BundleFreshness.Stale); assertTrue(f.canSell); assertTrue(f.showBanner); assertTrue(f.markRecordsStale); assertFalse(f.readOnly)
    }

    @Test fun twoDaysOldStillSellsAtTheDefaultLimit() = assertTrue(BundleFreshness.of(LocalDate(2026, 10, 5), today).canSell)

    @Test fun threeDaysOldIsReadOnlyAndCannotSell() {
        val f = BundleFreshness.of(LocalDate(2026, 10, 4), today)
        assertTrue(f is BundleFreshness.Expired); assertFalse(f.canSell); assertTrue(f.readOnly); assertEquals(3, f.ageDays)
    }

    @Test fun checkInWorksInEveryCase() {
        listOf(today, LocalDate(2026, 10, 6), LocalDate(2026, 9, 1), null).forEach { assertTrue(BundleFreshness.of(it, today).checkInAllowed) }
    }

    @Test fun limitIsConfigurable() {
        assertFalse(BundleFreshness.of(LocalDate(2026, 10, 5), today, staleMaxDays = 1).canSell)
        assertTrue(BundleFreshness.of(LocalDate(2026, 10, 4), today, staleMaxDays = 3).canSell)
    }

    @Test fun missingBundleCannotSell() { assertFalse(BundleFreshness.of(null, today).canSell) }

    @Test fun aPrefetchedFutureBundleIsNotStale() = assertEquals(BundleFreshness.Fresh, BundleFreshness.of(LocalDate(2026, 10, 8), today))
}
