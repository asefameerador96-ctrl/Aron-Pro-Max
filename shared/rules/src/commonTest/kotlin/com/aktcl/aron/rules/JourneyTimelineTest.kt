package com.aktcl.aron.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SRM-B-82 to 85: the call timeline of screenshot 59, in one rule for the phone and the server. */
class JourneyTimelineTest {
    private val bn = JourneyWords("মিনিট", "সেকেন্ড", "মি", "কি.মি")
    private val en = JourneyWords("min", "s", "m", "km")
    private val day = 1_791_000_000_000L // an instant on a Dhaka day; only differences and the clock matter

    private fun call(id: String, outlet: Long, at: Long, end: Long?, lat: Double? = 23.78, lng: Double? = 90.40, sold: Boolean = true, edited: Boolean = false) =
        JourneyCallInput(id, outlet, day + at * 1000, end?.let { day + it * 1000 }, lat, lng, sold, edited)

    @Test fun ordersByOpenTimeAndComputesDurationAndGap() {
        val rows = JourneyTimeline.build(listOf(call("b", 2, 900, 1000), call("a", 1, 0, 327)))
        assertEquals(listOf("a", "b"), rows.map { it.input.visitUuid })
        assertEquals(327L, rows[0].durationS)
        assertEquals(573L, rows[0].gapToNextS) // 900 - 327
        assertEquals(100L, rows[1].durationS)
        assertNull(rows[1].gapToNextS)
    }

    @Test fun statusRule() {
        val rows = JourneyTimeline.build(listOf(
            call("1", 7, 0, 60),                         // first sale at outlet 7
            call("2", 8, 100, 160, sold = false),        // no sale
            call("3", 7, 200, 260),                      // second sale at 7 -> repeat
            call("4", 9, 300, 360, edited = true),       // edited
            call("5", 9, 400, 460, edited = true),       // edited wins over repeat
            call("6", 10, 500, null, sold = false),      // open, no memo yet
        ))
        assertEquals(
            listOf(JourneyCallStatus.NEW_SALE, JourneyCallStatus.NO_SALE, JourneyCallStatus.REPEAT_SALE, JourneyCallStatus.EDITED_SALE, JourneyCallStatus.EDITED_SALE, JourneyCallStatus.IN_PROGRESS),
            rows.map { it.status },
        )
    }

    @Test fun anEditedCallIsEditedEvenWithoutAnActiveSaleMemoAndLegsAreRoundedLikeTheServer() {
        val rows = JourneyTimeline.build(listOf(
            call("1", 1, 0, 10, 23.7800, 90.4000, edited = true, sold = false),
            call("2", 2, 20, 30, 23.78003, 90.4000),
            call("3", 3, 40, 50, 23.78006, 90.4000),
        ))
        assertEquals(JourneyCallStatus.EDITED_SALE, rows[0].status)
        val l2 = Geo.haversineM(23.7800, 90.4000, 23.78003, 90.4000); val l3 = Geo.haversineM(23.78003, 90.4000, 23.78006, 90.4000)
        fun r2(v: Double) = kotlin.math.floor(v * 100.0 + 0.5) / 100.0
        assertEquals(r2(l2), rows[1].distanceFromPrevM!!, 0.0)
        assertEquals(r2(r2(l2) + r2(l3)), rows[2].runningM, 0.0) // the running total adds the ROUNDED legs
    }

    @Test fun firstCallHasNoDistanceAndRunningTotalAddsKnownLegs() {
        val rows = JourneyTimeline.build(listOf(
            call("1", 1, 0, 10, 23.7800, 90.4000),
            call("2", 2, 20, 30, 23.7809, 90.4000),
            call("3", 3, 40, 50, null, null),
            call("4", 4, 60, 70, 23.7818, 90.4000),
        ))
        assertNull(rows[0].distanceFromPrevM)
        val leg = Geo.haversineM(23.7800, 90.4000, 23.7809, 90.4000)
        assertEquals(leg, rows[1].distanceFromPrevM!!, 1e-9)
        assertNull(rows[2].distanceFromPrevM) // no base point
        assertNull(rows[3].distanceFromPrevM) // previous has no point
        assertEquals(leg, rows[3].runningM, 1e-9)
    }

    @Test fun distanceSwitchesToKilometresAfterRounding() {
        assertEquals("৪১২.৭২ মি", JourneyFormat.distance(412.724, bn, UiLocale.BN))
        assertEquals("0.00 m", JourneyFormat.distance(0.0, en, UiLocale.EN))
        assertEquals("999.99 m", JourneyFormat.distance(999.994, en, UiLocale.EN))
        assertEquals("1.00 km", JourneyFormat.distance(999.996, en, UiLocale.EN))
        assertEquals("1.00 km", JourneyFormat.distance(1004.995, en, UiLocale.EN)) // rounded once from the raw metres, never 1.01
        assertEquals("1.01 km", JourneyFormat.distance(1005.0, en, UiLocale.EN))
        assertEquals("৭.৬০ কি.মি", JourneyFormat.distance(7_600.0, bn, UiLocale.BN))
        assertEquals("7.71 km", JourneyFormat.distance(7_714.9, en, UiLocale.EN))
        assertEquals("0.00 m", JourneyFormat.distance(Double.NaN, en, UiLocale.EN))
    }

    @Test fun durationAndClockMatchTheScreenshot() {
        assertEquals("৫ মিনিট ২৭ সেকেন্ড", JourneyFormat.duration(327, bn, UiLocale.BN))
        assertEquals("১০ সেকেন্ড", JourneyFormat.duration(10, bn, UiLocale.BN))
        assertEquals("21 min 58 s", JourneyFormat.duration(1318, en, UiLocale.EN))
        // 03:53:35 UTC = 9:53:35 AM in Dhaka
        val t = 1_700_000_000_000L - (1_700_000_000_000L % 86_400_000L) + (3 * 3600 + 53 * 60 + 35) * 1000L
        assertEquals("9:53:35 AM", JourneyFormat.clock12(t))
        assertEquals("9:53:35 AM - 9:59:02 AM", JourneyFormat.range(t, t + 327_000L))
        assertEquals("9:53:35 AM", JourneyFormat.range(t, null))
        // 12 AM and 12 PM
        val midnightDhaka = 1_700_000_000_000L - (1_700_000_000_000L % 86_400_000L) - 6 * 3_600_000L + 86_400_000L
        assertEquals("12:00:00 AM", JourneyFormat.clock12(midnightDhaka))
        assertEquals("12:00:00 PM", JourneyFormat.clock12(midnightDhaka + 12 * 3_600_000L))
        assertTrue(JourneyFormat.clock12(0L).endsWith("AM") || JourneyFormat.clock12(0L).endsWith("PM"))
    }
}
