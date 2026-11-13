package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.DayPlan
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure rules that map the field's master data (docs/reference/master-data-mapping.md). Synthetic values only. */
class ImportRulesTest {
    @Test fun phonesAsTheFieldStoresThemAreRepairedAndTheRestIsStoredEmpty() {
        for (raw in listOf("01711000001", "1711000001", "8801711000001", "+880 1711-000001", "880 01711000001", "00880 1711 000001", "(017) 11000001", "১৭১১০০০০০১"))
            assertEquals("01711000001", ImportRules.phone(raw).value, raw)
        assertTrue(ImportRules.phone("1711000001").repaired); assertFalse(ImportRules.phone("01711000001").repaired)
        for (junk in listOf("123", "99", "01011000001", "abc", "017110000019999")) { val p = ImportRules.phone(junk); assertNull(p.value, junk); assertTrue(p.unusable, junk) }
        for (blank in listOf(null, "", "   ")) { val p = ImportRules.phone(blank); assertNull(p.value); assertFalse(p.unusable) }
    }

    @Test fun channelLabelsOfTheFieldMapToOurSixCodes() {
        val want = mapOf("GT Channel" to "GT", "Astha Channel" to "Astha", "DCC Channel" to "DCC", "MT Channel" to "MT", "RCC" to "RCC", "HoReCa" to "HoReCa", "gt" to "GT", " horeca " to "HoReCa", "Ho-Re-Ca Channel" to "HoReCa")
        want.forEach { (raw, code) -> assertEquals(ImportRules.ChannelParse.Known(code), ImportRules.channel(raw), raw) }
        for (e in listOf(null, "", "  ", "--")) assertEquals(ImportRules.ChannelParse.Empty, ImportRules.channel(e))
        assertTrue(ImportRules.channel("Retail") is ImportRules.ChannelParse.Unknown); assertTrue(ImportRules.channel("Channel") is ImportRules.ChannelParse.Unknown)
    }

    @Test fun coordinatesAreRoundedToEightDecimalsAndBangladeshIsABox() {
        assertEquals(23.78061235, ImportRules.round8(23.7806123456789)); assertEquals(90.4, ImportRules.round8(90.4))
        assertTrue(ImportRules.inBangladesh(23.8, 90.4)); assertFalse(ImportRules.inBangladesh(51.5, -0.12)); assertFalse(ImportRules.inBangladesh(0.0, 0.0)); assertFalse(ImportRules.inBangladesh(23.8, 77.4))
    }

    @Test fun geoClassSemiUrbanMapsToSemiUrban() {
        val known = listOf("Rural", "Urban", "SemiUrban", "Hill")
        assertEquals("SemiUrban", ImportRules.geoClass("Semi Urban", known)); assertEquals("Hill", ImportRules.geoClass("hill", known))
        assertNull(ImportRules.geoClass("", known)); assertNull(ImportRules.geoClass("Marsh", known))
    }

    @Test fun timestampsAreDhakaLocalAndAFutureOrJunkValueIsNull() {
        val now = Instant.parse("2026-11-13T00:00:00Z")
        assertEquals(Instant.parse("2024-03-05T04:15:00Z"), ImportRules.timestamp("2024-03-05 10:15:00", now))
        assertEquals(Instant.parse("2024-03-04T18:00:00Z"), ImportRules.timestamp("2024-03-05", now))
        assertEquals(Instant.parse("2024-03-05T04:15:00Z"), ImportRules.timestamp("05/03/2024 10:15", now))
        assertEquals(Instant.parse("2024-03-05T10:15:00Z"), ImportRules.timestamp("2024-03-05T10:15:00Z", now))
        assertNull(ImportRules.timestamp("2031-01-01", now)); assertNull(ImportRules.timestamp("yesterday", now)); assertNull(ImportRules.timestamp("", now))
    }

    @Test fun visitPlanOfTheFieldsPatterns() {
        fun plan(f: String?, d: String?) = ImportRules.visitPlan(f, d)
        plan("6", "Daily").let { assertEquals("daily", it.kind); assertEquals(127, it.mask); assertEquals(emptyList(), it.warnings) }
        plan("6", null).let { assertEquals("daily", it.kind); assertEquals(127, it.mask) }
        plan("3", "(Sat, Mon, Wed)").let { assertEquals("3f", it.kind); assertEquals(DayPlan.mask(DayOfWeek.SATURDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), it.mask); assertEquals(emptyList(), it.warnings) }
        plan("3", "(Sun, Tue, Thu)").let { assertEquals(42, it.mask) }
        plan("2", "Sat, Tue").let { assertEquals("2f", it.kind); assertEquals(DayPlan.mask(DayOfWeek.SATURDAY, DayOfWeek.TUESDAY), it.mask) }
        // M19: a pattern with a weekend day loads as it is and is flagged
        plan("3", "(Mon, Thu, Fri)").let { assertEquals("3f", it.kind); assertEquals(DayPlan.mask(DayOfWeek.MONDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY), it.mask); assertEquals(listOf("weekend_day_in_pattern"), it.warnings) }
        assertEquals(emptyList(), ImportRules.visitPlan("3", "(Mon, Thu, Fri)", weekendIsoDays = setOf(7)).warnings)
        // M03: nothing given = the explicit "no visit plan", stored as it is (no invented plan)
        plan(null, null).let { assertEquals("none", it.kind); assertEquals(0, it.mask); assertEquals(emptyList(), it.warnings) }
        plan("", "  ").let { assertEquals("none", it.kind) }
        plan("3", null).let { assertEquals("none", it.kind); assertEquals(listOf("days_missing"), it.warnings) }
        plan("3", "(Sat, Mon)").let { assertEquals("2f", it.kind); assertEquals(listOf("frequency_mismatch"), it.warnings) }
        plan("3", "Funday").let { assertEquals("none", it.kind); assertEquals(listOf("unreadable_days"), it.warnings) }
    }

    /** M02: "Daily" is every SELLING day. Friday is off by the weekend rule; a make-up Friday in the calendar plans the daily routes on that Friday, nothing else does. */
    @Test fun dailyMeansEverySellingDayAndAMakeUpFridayIsDecidedByTheCalendarOnly() {
        val zone = DayPlan.ZoneChain(1, 1, 1, 1)
        val daily = DayPlan.PlanRoute(1, ImportRules.visitPlan("6", "Daily").mask, zone)
        val noPlan = DayPlan.PlanRoute(2, ImportRules.visitPlan(null, null).mask, zone)
        val friday = LocalDate.of(2026, 11, 13); val saturday = friday.plusDays(1)
        assertFalse(DayPlan.isPlanned(daily, friday, emptyList()), "Friday is the weekend: a daily route is not planned")
        assertTrue(DayPlan.isPlanned(daily, saturday, emptyList()))
        val makeUp = listOf(DayPlan.CalendarEntry(friday, DayPlan.CalendarScope.GLOBAL, 0, "make_up", true))
        assertTrue(DayPlan.isPlanned(daily, friday, makeUp), "the calendar says Friday is a selling day: the daily routes are planned")
        val other = listOf(DayPlan.CalendarEntry(friday.plusDays(7), DayPlan.CalendarScope.GLOBAL, 0, "make_up", true))
        assertFalse(DayPlan.isPlanned(daily, friday, other), "a make-up day elsewhere does not plan this Friday")
        for (d in 0L..13L) assertFalse(DayPlan.isPlanned(noPlan, saturday.plusDays(d), makeUp), "a route with no visit plan is never planned")
        assertEquals(DayPlan.VisitKind.NONE, DayPlan.kindOf(0)); assertTrue(DayPlan.consistent(DayPlan.VisitKind.NONE, 0)); assertFalse(DayPlan.consistent(DayPlan.VisitKind.NONE, 5))
        assertEquals(0, DayPlan.targetOutlets(emptyList(), mapOf(1L to 2L), setOf(1L)))
    }
}
