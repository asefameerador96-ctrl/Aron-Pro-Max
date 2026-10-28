package com.aktcl.aron.rules

import kotlin.test.Test
import kotlin.test.assertEquals

/** TicketMath (build contract s4.4; D-TSO-MAN-25; TK-06, TK-07): dashboard counts, the offline merge, the age text. */
class TicketMathTest {
    private val en = UiLocale.EN
    private val bn = UiLocale.BN
    private val now = 1_793_000_000_000L // an instant; only differences matter
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun ticketDashboardOfScreenshot11() {
        // 723 tickets: 0 New, 0 In Progress, 18 Resolved; the other 705 are closed (Total counts every status).
        val c = TicketMath.counts(List(18) { TicketMath.RESOLVED } + List(705) { TicketMath.CLOSED })
        assertEquals(TicketCounts(total = 723, new = 0, inProgress = 0, resolved = 18, closed = 705, cancelled = 0), c)
        assertEquals(0L, c.open)
        assertEquals(2L, c.resolvedPct)
        assertEquals("2%", c.resolvedPctText(en))
        assertEquals("0%", c.openPctText(en))
        assertEquals("২%", c.resolvedPctText(bn))
    }

    @Test
    fun noTicketsShowsZeroPercentNotADash() {
        val c = TicketMath.counts(emptyList())
        assertEquals(0L, c.total)
        assertEquals("0%", c.resolvedPctText(en))
        assertEquals("0%", c.openPctText(en))
        assertEquals(0L, TicketMath.pct(0, 0))
    }

    @Test
    fun openIsNewPlusInProgressAndTotalIsEveryStatus() {
        val c = TicketMath.counts(listOf("new", "new", "in_progress", "resolved", "closed", "cancelled", "on_hold"))
        assertEquals(7L, c.total) // a status this version does not know still counts as a ticket
        assertEquals(3L, c.open)
        assertEquals(43L, c.openPct) // 3/7 = 42.86%
        assertEquals(14L, c.resolvedPct) // 1/7 = 14.29%
        assertEquals(1L, c.cancelled)
    }

    @Test
    fun offlineCountsMergeTheOutboxByUuid() {
        val cached = listOf(TicketRow("A1", "resolved"), TicketRow("b2", "in_progress"), TicketRow("b2", "closed"))
        // c3 and d4 are unsent: New. a1 is unsent too but the cache already holds it (lost ack): counted once, as cached.
        val c = TicketMath.counts(cached, listOf("c3", "a1", "d4", "D4"))
        assertEquals(4L, c.total)
        assertEquals(2L, c.new)
        assertEquals(1L, c.inProgress) // the first cached row of a repeated UUID
        assertEquals(1L, c.resolved)
        assertEquals(0L, c.closed)
        assertEquals("75%", Percent.text(c.openPct, 0, en))
    }

    @Test
    fun ageFixtures() {
        assertEquals("4d ago", TicketMath.ageText(now, now - 4 * day, en))
        assertEquals("23h ago", TicketMath.ageText(now, now - (23 * hour + 59 * minute), en))
        assertEquals("just now", TicketMath.ageText(now, now - 59_000, en))
    }

    @Test
    fun ageBoundariesRoundDown() {
        assertEquals(TicketAge(AgeUnit.JUST_NOW, 0), TicketMath.age(now, now))
        assertEquals(TicketAge(AgeUnit.JUST_NOW, 0), TicketMath.age(now, now - minute + 1))
        assertEquals(TicketAge(AgeUnit.MINUTES, 1), TicketMath.age(now, now - minute))
        assertEquals(TicketAge(AgeUnit.MINUTES, 59), TicketMath.age(now, now - hour + 1))
        assertEquals(TicketAge(AgeUnit.HOURS, 1), TicketMath.age(now, now - hour))
        assertEquals(TicketAge(AgeUnit.HOURS, 23), TicketMath.age(now, now - day + 1))
        assertEquals(TicketAge(AgeUnit.DAYS, 1), TicketMath.age(now, now - day))
        assertEquals(TicketAge(AgeUnit.DAYS, 42), TicketMath.age(now, now - 42 * day - 23 * hour))
        assertEquals("11d ago", TicketMath.ageText(now, now - 11 * day, en))
    }

    @Test
    fun captureAfterNowIsJustNowAndExtremesDoNotWrap() {
        assertEquals("just now", TicketMath.ageText(now, now + 5 * minute, en))
        assertEquals(AgeUnit.DAYS, TicketMath.age(Long.MAX_VALUE, Long.MIN_VALUE).unit)
        assertEquals(Long.MAX_VALUE / day, TicketMath.age(Long.MAX_VALUE, Long.MIN_VALUE).value)
    }

    @Test
    fun banglaAgeText() {
        assertEquals("এইমাত্র", TicketMath.ageText(now, now - 59_000, bn))
        assertEquals("৫ মিনিট আগে", TicketMath.ageText(now, now - 5 * minute, bn))
        assertEquals("২৩ ঘণ্টা আগে", TicketMath.ageText(now, now - 23 * hour, bn))
        assertEquals("৪ দিন আগে", TicketMath.ageText(now, now - 4 * day, bn))
        assertEquals("৪২ দিন আগে", TicketMath.ageText(now, now - 42 * day, bn))
    }
}
