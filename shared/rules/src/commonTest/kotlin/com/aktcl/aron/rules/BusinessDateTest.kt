package com.aktcl.aron.rules

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-SYS-017: business date from UTC using Asia/Dhaka (UTC+6) and the s3.8 trusted-time rules. */
class BusinessDateTest {
    // 2026-10-05T17:55:00Z = 23:55 Dhaka on 2026-10-05; 18:05Z = 00:05 Dhaka on 2026-10-06
    private fun ms(utc: String): Long = kotlinx.datetime.LocalDateTime.parse(utc.removeSuffix("Z")).let { dt ->
        dt.date.toEpochDays() * 86_400_000L + (dt.hour * 3600L + dt.minute * 60L + dt.second) * 1000L
    }

    @Test
    fun dhakaMidnightBoundary() {
        assertEquals(LocalDate(2026, 10, 5), BusinessDate.of(ms("2026-10-05T17:55:00Z")))   // 23:55 Dhaka
        assertEquals(LocalDate(2026, 10, 6), BusinessDate.of(ms("2026-10-05T18:05:00Z")))   // 00:05 Dhaka
        assertEquals(LocalDate(2026, 10, 5), BusinessDate.of(ms("2026-10-05T17:59:59Z")))
        assertEquals(LocalDate(2026, 10, 6), BusinessDate.of(ms("2026-10-05T18:00:00Z")))
        assertEquals(LocalDate(2026, 10, 5), BusinessDate.of(ms("2026-10-04T18:00:00Z")))
        assertEquals(LocalDate(2026, 10, 5), BusinessDate.of(ms("2026-10-05T00:00:00Z")))   // 06:00 Dhaka
    }

    @Test
    fun yearEndLeapDayAndEpochNegatives() {
        assertEquals(LocalDate(2027, 1, 1), BusinessDate.of(ms("2026-12-31T18:00:00Z")))
        assertEquals(LocalDate(2028, 2, 29), BusinessDate.of(ms("2028-02-28T18:30:00Z")))
        assertEquals(LocalDate(1969, 12, 31), BusinessDate.of(-7 * 3_600_000L))   // 1969-12-31T17:00Z = 23:00 Dhaka
        assertEquals(LocalDate(1970, 1, 1), BusinessDate.of(-6 * 3_600_000L))
        assertEquals(LocalDate(1969, 12, 31), BusinessDate.of(-6 * 3_600_000L - 1))
    }

    @Test
    fun cutoffShiftsTheDate() {
        assertEquals(LocalDate(2026, 10, 5), BusinessDate.of(ms("2026-10-05T18:30:00Z"), cutoffMinutes = 60))  // 00:30 Dhaka, cutoff 01:00
        assertEquals(LocalDate(2026, 10, 6), BusinessDate.of(ms("2026-10-05T19:00:00Z"), cutoffMinutes = 60))
    }

    @Test
    fun memoNumberDate() { assertEquals("261005", BusinessDate.yyMMdd(LocalDate(2026, 10, 5))); assertEquals("270101", BusinessDate.yyMMdd(LocalDate(2027, 1, 1))) }

    @Test
    fun trustedTimeFromAnchors() {
        val a1 = TimeAnchor(3, 1_000_000, 10_000)
        val a2 = TimeAnchor(3, 2_000_000, 500_000)
        val other = TimeAnchor(2, 9_999_999, 1)
        assertEquals(2_100_000L, TrustedClock.deriveMs(listOf(a1, a2, other), 3, 600_000))
        assertEquals(1_090_000L, TrustedClock.deriveMs(listOf(a1, a2), 3, 100_000))   // a2 is in the future of this reading: use a1
        assertNull(TrustedClock.deriveMs(listOf(other), 3, 600_000))                   // reboot, no new anchor
        assertNull(TrustedClock.deriveMs(emptyList(), 0, 0))
        val t = TrustedClock.trustedNow(listOf(a2), 3, 600_000, wallClockMs = 2_000_000)
        assertEquals(TrustedTime(2_100_000, 100_000), t)
        assertEquals(TrustedTime(5_000, null), TrustedClock.trustedNow(emptyList(), 4, 1, 5_000))
    }

    @Test
    fun wrongWallClockDoesNotMoveTheBusinessDate() {
        // phone wall clock says 2026-10-05 23:55 Dhaka but the server anchor says it is already 00:05 next day
        val anchor = TimeAnchor(1, ms("2026-10-05T18:00:00Z"), 0)
        val t = TrustedClock.trustedNow(listOf(anchor), 1, 5 * 60_000, wallClockMs = ms("2026-10-05T17:55:00Z"))
        assertEquals(LocalDate(2026, 10, 6), BusinessDate.of(t.epochMs))
        assertEquals(LocalDate(2026, 10, 5), BusinessDate.of(ms("2026-10-05T17:55:00Z")))
    }

    @Test
    fun skewAndReconcile() {
        val derived = ms("2026-10-05T18:00:00Z")
        assertFalse(TrustedClock.exceedsSkew(derived + 600_000, derived))       // exactly 10 min: allowed
        assertTrue(TrustedClock.exceedsSkew(derived + 600_001, derived))
        assertTrue(TrustedClock.exceedsSkew(derived - 601_000, derived))
        val d5 = LocalDate(2026, 10, 5); val d6 = LocalDate(2026, 10, 6)
        assertEquals(BusinessDateDecision(d6, null, false), BusinessDateRules.reconcile(d6, derived + 1000, derived))
        assertEquals(BusinessDateDecision(d6, d5, true), BusinessDateRules.reconcile(d5, derived - 3_600_000 , derived))
        assertEquals(BusinessDateDecision(d6, null, true), BusinessDateRules.reconcile(d6, derived + 3_600_000, derived))
    }

    @Test
    fun windowClassification() {
        val now = ms("2026-10-05T06:00:00Z")   // 12:00 Dhaka
        val today = LocalDate(2026, 10, 5)
        assertEquals(DateWindow.OK, BusinessDateRules.classify(today, now, now))
        assertEquals(DateWindow.OK, BusinessDateRules.classify(LocalDate(2026, 9, 28), now - 7 * 86_400_000L, now))
        assertEquals(DateWindow.OUT_OF_WINDOW_PAST, BusinessDateRules.classify(LocalDate(2026, 9, 27), now - 8 * 86_400_000L, now))
        assertEquals(DateWindow.OK, BusinessDateRules.classify(today, now + 600_000, now))
        assertEquals(DateWindow.FUTURE, BusinessDateRules.classify(today, now + 600_001, now))
    }
}
