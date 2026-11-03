package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachNode
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.contract.Role
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Daily Tracking's `tilldate_target_achievement_pct` (tso-manual B2; D-TSO-MAN-05, 08; R-F-01, R-F-15) over target_status.sql:
 * the route's cigarette sticks of business dates 1st..the tracked date against the sum of its cigarette items' till-date
 * targets (Targets.tillDate per variant, rounded once), one Percent.bp rounding, two decimals. Expectations hand computed
 * with an independent Python-integer oracle (round half up; bp = (20000 a + t) div 2t).
 * R1 (Banani): cigarette items Maxim Regular 600, Maxim Bolt 1000, Marise 10000 (7000 + 3000), BD Advanced 6000, Sunmoon Lights
 * 5000; R2 (Banani): Maxim Regular 400, Maxim Double Burst 10000, BD Double Filter 5000, Sunmoon American 2999; R3 (Gulshan): no
 * October target.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DailyTrackingTillDateTest {
    private lateinit var fresh: FreshDb
    private val clock = AronClock { Instant.parse("2026-10-25T06:00:00Z") }
    private val ids = mutableMapOf<String, Long>()
    private lateinit var tso: Reach

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        fresh.dataSource.connection.use { c -> c.createStatement().use { it.execute(javaClass.classLoader.getResourceAsStream("target_status.sql")!!.bufferedReader().readText()) } }
        fresh.db.jdbi.useHandle<Exception> { h ->
            Aggregator.refreshDimensions(h)
            for (t in listOf("zone", "territory", "route")) h.createQuery("SELECT code, id FROM app.$t").map { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().forEach { (k, v) -> ids[k] = v }
            // The tracked route-days (planned, logged in) on the 10th and the 25th.
            h.execute(
                """
                INSERT INTO dw.agg_daily_route (business_date, route_id, zone_id, planned, day_state, target_outlets, successful_calls)
                SELECT d.bd::date, r.id, r.zone_id, true, 'logged_in', 10, 9 FROM app.route r, (VALUES ('2026-10-10'), ('2026-10-25')) d(bd) WHERE r.code IN ('R1', 'R2', 'R3')
                """,
            )
        }
        tso = Reach(21, Role.TSO, LocalDate.parse("2026-10-25"), false, setOf(ids.getValue("Z1"), ids.getValue("Z2")), emptySet(), false,
            listOf(ReachNode("territory", ids.getValue("T1"), "T1", "Apsis")))
    }

    @AfterAll
    fun tearDown() = fresh.close()

    private fun config(basis: String?) = object : ServerConfig {
        private val d = RegistryDefaults()
        override fun value(key: String): JsonElement =
            if (key == TargetAchievementService.BASIS_TSO && basis != null) JsonPrimitive(basis) else if (key == "cfg.day.take_action_after") JsonPrimitive("17:00") else d.value(key)
        override fun configVersion() = 1L
    }

    private fun pcts(date: String, basis: String? = null): Map<String, Double?> {
        val page = DailyTrackingService(fresh.db, config(basis), clock).page(tso, LocalDate.parse(date), null, null, 100, null)
        val code = listOf("R1", "R2", "R3", "R4").associateBy { ids.getValue(it) }   // ids of different tables may repeat: map the routes only
        return page.items.associate { code.getValue(it.route_id) to it.tilldate_target_achievement_pct }
    }

    private fun sql(s: String) = fresh.db.jdbi.useHandle<Exception> { it.execute(s) }

    @Test
    fun theTillDatePercentIsTheRoutesCigaretteSticksAgainstTheSumOfItsItemTillDateTargets() {
        // 25 Oct, calendar 25/31. R1: T' = 484+806+8065+4839+4032 = 18226 (Marise's two SKUs summed before rounding), A = 200+330+120+80+30+30 = 790
        // (the bidi and match sales and the September sale are not cigarette or not this month) -> 433 bp. R2: T' = 14839, A = 240 -> 162 bp.
        val p = pcts("2026-10-25")
        assertEquals(4.33, p["R1"]); assertEquals(1.62, p["R2"])
        assertNull(p.getValue("R3"), "no cigarette target that month: no till-date target, no percent")
        // 10 Oct, calendar 10/31: only the sales of 1..10 count (R1: 200 + 330 + 30 + 30 = 590), T' = 7291 -> 809 bp.
        assertEquals(8.09, pcts("2026-10-10")["R1"])
    }

    @Test
    fun theWorkingDaysBasisCountsAtTheRoutesZone() {
        try {
            sql("INSERT INTO app.calendar_holiday (date, scope_type, scope_id, kind, selling_day, name_en) VALUES ('2026-10-21', 'global', 0, 'holiday', false, 'B2'), ('2026-10-12', 'zone', ${ids.getValue("Z1")}, 'holiday', false, 'B2')")
            // Banani on 10 Oct: e = 8 (Fridays 2 and 9 off), n = 24 (5 Fridays, the 21st and Banani's 12th off): R1 T' = 7533, A 590 -> 783 bp.
            assertEquals(7.83, pcts("2026-10-10", "working_days")["R1"])
            // The calendar basis ignores the calendar: still 809 bp.
            assertEquals(8.09, pcts("2026-10-10", "calendar_days")["R1"])
        } finally { sql("DELETE FROM app.calendar_holiday") }
    }

    @Test
    fun aMonthWithNoWorkingDayAtTheZoneGivesNoPercent() {
        try {
            sql("INSERT INTO app.calendar_holiday (date, scope_type, scope_id, kind, selling_day, name_en) SELECT d::date, 'zone', ${ids.getValue("Z1")}, 'emergency_off', false, 'B2' FROM generate_series(DATE '2026-10-01', DATE '2026-10-31', interval '1 day') d")
            val p = pcts("2026-10-25", "working_days")
            assertNull(p.getValue("R1")); assertNull(p.getValue("R2"))   // n = 0 in Banani (R-F-15)
        } finally { sql("DELETE FROM app.calendar_holiday") }
    }
}
