package com.aktcl.aron.rules

import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Targets (build contract s4.1 to s4.3; D-TSO-MAN-05, D-TSO-MAN-40; T-05, T-06, T-07, T-09): the Target Status fixtures of
 * screenshots 39 to 42 (D = 2026-10-25, e = 25, n = 31, calendar basis), the bases, bands, capped and uncapped percent.
 */
class TargetsTest {
    private val en = UiLocale.EN
    private val cigaretteItems = listOf(1_000L, 1_000L, 10_000L, 10_000L, 5_000L, 6_000L, 2_999L, 5_000L)
    private val oct25 = LocalDate(2026, 10, 25) // date-pin-ok: the screenshots' business date (contract s4.3)

    @Test
    fun itemTillDateTargetsOfScreenshot40() {
        val expected = mapOf(1_000L to 806L, 10_000L to 8_065L, 5_000L to 4_032L, 6_000L to 4_839L, 2_999L to 2_419L,
            100L to 81L, 170L to 137L, 11_000L to 8_871L)
        for ((t, v) in expected) assertEquals(v, Targets.tillDate(t, 25, 31), "till-date of $t")
    }

    @Test
    fun cardTargetsAreSumsOfItemRows() {
        assertEquals(40_999L, cigaretteItems.sum())
        assertEquals(1_030L, listOf(350L, 330L, 20L, 200L, 30L, 30L, 40L, 30L).sum())
        val tillItems = cigaretteItems.map { Targets.tillDate(it, 25, 31)!! }
        assertEquals(listOf(806L, 806L, 8_065L, 8_065L, 4_032L, 4_839L, 2_419L, 4_032L), tillItems)
        assertEquals(33_064L, tillItems.sum())
    }

    @Test
    fun cardPercentIsWholeAndCappedAt100() {
        // Monthly tab: 1030/40999, 50/11000, 85/100, 72/170.
        val monthly = listOf(1_030L to 40_999L, 50L to 11_000L, 85L to 100L, 72L to 170L)
        assertEquals(listOf(3L, 0L, 85L, 42L), monthly.map { Targets.cardPct(it.first, it.second) })
        // Till-date tab: 1030/33064, 50/8871, 85/81 (104.94% printed as 100), 72/137.
        val tillDate = listOf(1_030L to 33_064L, 50L to 8_871L, 85L to 81L, 72L to 137L)
        assertEquals(listOf(3L, 1L, 100L, 53L), tillDate.map { Targets.cardPct(it.first, it.second) })
        assertEquals("100%", Percent.text(Targets.cardPct(85, 81), 0, en))
        assertNull(Targets.cardPct(5, 0))
        assertEquals("—", Percent.text(Targets.cardPct(5, 0), 0, en))
    }

    @Test
    fun detailsPercentIsTwoDecimalsAndUncapped() {
        assertEquals(10_494L, Targets.detailsBp(85, 81))
        assertEquals("104.94%", Percent.text(Targets.detailsBp(85, 81), 2, en))
        assertEquals("35.00%", Percent.text(Targets.detailsBp(350, 1_000), 2, en))
        assertEquals("43.42%", Percent.text(Targets.detailsBp(350, 806), 2, en))
        assertEquals("1.33%", Percent.text(Targets.detailsBp(40, 2_999), 2, en))
    }

    @Test
    fun barBandsOnThePrintedPercent() {
        for (p in listOf(0L, 1L, 3L)) assertEquals(Band.RED, Targets.band(p), "band of $p")
        for (p in listOf(42L, 53L)) assertEquals(Band.AMBER, Targets.band(p), "band of $p")
        for (p in listOf(85L, 100L)) assertEquals(Band.GREEN, Targets.band(p), "band of $p")
        // Edges: red < 40 <= amber < 80 <= green.
        assertEquals(Band.RED, Targets.band(39))
        assertEquals(Band.AMBER, Targets.band(40))
        assertEquals(Band.AMBER, Targets.band(79))
        assertEquals(Band.GREEN, Targets.band(80))
        assertEquals(Band.GREEN, Targets.band(250))
    }

    @Test
    fun bandsFromConfig() {
        assertEquals(Bands(40, 80), Bands.DEFAULT)
        assertEquals(Bands(30, 90), Bands.of(listOf(30L, 90L)))
        assertEquals(Band.RED, Targets.band(85, Bands.of(listOf(90L, 95L))))
        assertEquals(Band.GREEN, Targets.band(0, Bands(0, 0)))
        // Missing or malformed values fall back to the default rather than break a screen.
        assertEquals(Bands.DEFAULT, Bands.of(null))
        assertEquals(Bands.DEFAULT, Bands.of(listOf(80L, 40L)))
        assertEquals(Bands.DEFAULT, Bands.of(listOf(40L)))
        assertEquals(Bands.DEFAULT, Bands.of(listOf(-1L, 40L)))
        assertFailsWith<IllegalArgumentException> { Bands(80, 40) }
    }

    @Test
    fun remainingIsNeverNegative() {
        assertEquals(650L, Targets.remaining(1_000, 350))
        assertEquals(2_959L, Targets.remaining(2_999, 40))
        assertEquals(0L, Targets.remaining(81, 85))
        assertEquals(0L, Targets.remaining(81, 81))
        assertEquals(0L, Targets.remaining(0, 0))
    }

    @Test
    fun tillDateClampsElapsedAndNeedsAPeriod() {
        assertEquals(1_000L, Targets.tillDate(1_000, 40, 31)) // e above n: the whole month
        assertEquals(0L, Targets.tillDate(1_000, -3, 31)) // e below 0: nothing yet
        assertNull(Targets.tillDate(1_000, 0, 0)) // no working day in the month
        assertEquals(0L, Targets.tillDate(0, 25, 31))
        assertEquals(500L, Targets.tillDate(1_000, 1, 2)) // exact
        assertEquals(2L, Targets.tillDate(3, 1, 2)) // 1.5 rounds up
        assertFailsWith<IllegalArgumentException> { Targets.tillDate(-1, 1, 2) }
    }

    @Test
    fun calendarBasisOnTheScreenshotDate() {
        val days = Targets.days(TillDateBasis.CALENDAR_DAYS, YearMonth(2026, 10), oct25)
        assertEquals(TillDateDays(25, 31), days)
        assertEquals(806L, Targets.tillDate(1_000, days))
        // Past month complete, future month not started; February of a leap year has 29 days.
        assertEquals(TillDateDays(30, 30), Targets.days(TillDateBasis.CALENDAR_DAYS, YearMonth(2026, 9), oct25))
        assertEquals(TillDateDays(0, 30), Targets.days(TillDateBasis.CALENDAR_DAYS, YearMonth(2026, 11), oct25))
        assertEquals(TillDateDays(29, 29), Targets.days(TillDateBasis.CALENDAR_DAYS, YearMonth(2028, 2), LocalDate(2028, 3, 1)))
        assertEquals(TillDateDays(1, 31), Targets.days(TillDateBasis.CALENDAR_DAYS, YearMonth(2026, 10), LocalDate(2026, 10, 1)))
    }

    @Test
    fun workingBasisSwitchCheck() {
        val holiday21 = NodeCalendar(WorkingDays.DEFAULT_WEEKEND, setOf(LocalDate(2026, 10, 21)), emptySet())
        val noHoliday = NodeCalendar(WorkingDays.DEFAULT_WEEKEND, emptySet(), emptySet())
        val d20 = Targets.days(TillDateBasis.WORKING_DAYS, YearMonth(2026, 10), oct25, holiday21)
        val d21 = Targets.days(TillDateBasis.WORKING_DAYS, YearMonth(2026, 10), oct25, noHoliday)
        assertEquals(TillDateDays(20, 25), d20)
        assertEquals(TillDateDays(21, 26), d21)
        // The contract's switch check (s4.3, R-F-01): the Cigarette CARD is the sum of its item rows, rounded per item:
        // 32799 at 20/25 (holiday 21st) and 33114 at 21/26 (808+808+8077+8077+4038+4846+2422+4038).
        assertEquals(32_799L, cigaretteItems.sumOf { Targets.tillDate(it, d20)!! })
        val rows21 = cigaretteItems.map { Targets.tillDate(it, d21)!! }
        assertEquals(listOf(808L, 808L, 8_077L, 8_077L, 4_038L, 4_846L, 2_422L, 4_038L), rows21)
        assertEquals(33_114L, rows21.sum())
        // tillDate of the summed monthly total is a single till-date, never a card figure: 33115 at 21/26 (one more than
        // the card); at 20/25 it happens to equal the card.
        assertEquals(33_115L, Targets.tillDate(40_999, d21))
        assertEquals(32_799L, Targets.tillDate(40_999, d20))
        // Past and future months on the working basis; the basis needs the calendar.
        assertEquals(TillDateDays(26, 26), Targets.days(TillDateBasis.WORKING_DAYS, YearMonth(2026, 9), oct25, noHoliday))
        assertEquals(TillDateDays(0, 26), Targets.days(TillDateBasis.WORKING_DAYS, YearMonth(2026, 11), oct25, noHoliday))
        assertFailsWith<IllegalArgumentException> { Targets.days(TillDateBasis.WORKING_DAYS, YearMonth(2026, 10), oct25) }
    }

    @Test
    fun basisFromConfig() {
        assertEquals(TillDateBasis.CALENDAR_DAYS, TillDateBasis.fromWire("calendar_days"))
        assertEquals(TillDateBasis.WORKING_DAYS, TillDateBasis.fromWire("working_days"))
        assertEquals(TillDateBasis.CALENDAR_DAYS, TillDateBasis.fromWire(null))
        assertEquals(TillDateBasis.CALENDAR_DAYS, TillDateBasis.fromWire("fortnights"))
    }

    @Test
    fun dailyRingOfTheSalesCard() {
        assertEquals(24L, Targets.dailyRingTenths(40, 42_000, 25))
        assertEquals("2.4%", Percent.text(Targets.dailyRingTenths(40, 42_000, 25), 1, en))
        assertEquals("287.6%", Percent.text(Targets.dailyRingTenths(2_991, 26_000, 25), 1, en)) // uncapped
        assertEquals("0.0%", Percent.text(Targets.dailyRingTenths(0, 100, 25), 1, en))
        // Match of screenshot 6 (R-F-20): A = 0, T = 175, W = 25.
        assertEquals("0.0", Qty.text(0, en))
        assertEquals("7.0", Qty.text(175, 25, en))
        assertEquals("0.0%", Percent.text(Targets.dailyRingTenths(0, 175, 25), 1, en))
        assertNull(Targets.dailyRingTenths(40, 0, 25))
        assertNull(Targets.dailyRingTenths(40, 42_000, 0))
        // W = 0 (R-F-13): no daily target, so Total Target and the ring both print the dash, in both locales.
        for (loc in listOf(en, UiLocale.BN)) {
            assertEquals("—", Qty.text(42_000, 0, loc))
            assertEquals("—", Percent.text(Targets.dailyRingTenths(40, 42_000, 0), 1, loc))
        }
        assertEquals("৪০.০", Qty.text(40, UiLocale.BN))
        assertFailsWith<IllegalArgumentException> { Targets.dailyRingTenths(40, 42_000, -1) }
        // The contract bound A <= 10^10, W <= 31 fits.
        assertEquals(310_000_000_000_000L, Targets.dailyRingTenths(10_000_000_000L, 1, 31))
    }
}
