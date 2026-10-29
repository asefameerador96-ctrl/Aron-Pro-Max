package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.AronClock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * D-TSO-MAN-30 (s5 B2): `DashboardSummaries.autoSubmit`, the dw-only midnight stamp, is gone; every final-submit percentage comes
 * from the live `app.final_submit` rows with one formula, zones submitted (the midnight job's `via = 'job'` rows included) / service
 * zones (a route-day of the national route set). Both reads take a zone-day's state from its projection
 * `dw.agg_daily_zone.final_submitted_at`, which the worker now rebuilds from the live rows only (a submit, a reopen or the job dirties
 * the zone-day through its route-days; here [rebuild] stands in for that pass). The national Final Submit card and the summary's
 * KPIs agree on every change. On tso_dashboard_day.sql the screenshot TSO's service zones are Banani - Test and Gulshan - Test
 * (Exact Zone plans no route).
 */
class FinalSubmitPctUnifiedTest : TsoDayFixture() {

    private fun card(uid: Long): Pair<Int, Int> = national().finalSubmit(caller(uid), all).let { it.service_zones to it.final_submitted }
    private fun summaryOf(uid: Long): Pair<Int?, Int?> = summary(uid).kpis.let { it.zones_with_target_routes to it.zones_final_submitted }

    private fun submit(zone: String, uuid: String, via: String = "web", reopened: Boolean = false) {
        sql(
            """
            INSERT INTO app.final_submit (client_uuid, zone_id, business_date, submitted_by, submitted_at, via, reopened_at, reopened_by, reopen_reason)
            SELECT '$uuid', ${id(zone)}, DATE '2026-10-25', ${if (via == "job") "NULL" else "u.id"}, TIMESTAMPTZ '2026-10-25 12:00Z', '$via',
                   ${if (reopened) "TIMESTAMPTZ '2026-10-25 13:00Z', u.id, 'B2 test reopen'" else "NULL, NULL, NULL"}
              FROM app.app_user u WHERE u.username = 'tso9100'
            """.trimIndent(),
        )
        rebuild()
    }

    private fun assertBoth(uid: Long, service: Int, submitted: Int, pctBp: Int?, why: String) {
        assertEquals(service to submitted, card(uid), "national card: $why")
        assertEquals(service to submitted, summaryOf(uid), "summary: $why")
        assertEquals(pctBp, national().finalSubmit(caller(uid), all).submit_pct_bp, "national %: $why")
        assertEquals(pctBp?.toLong(), bp(summary(uid).kpis.final_submit_pct), "summary %: $why")
    }

    private fun stamped(): Long = long("SELECT count(*) FROM dw.agg_daily_zone WHERE business_date = '2026-10-25' AND final_submitted_at IS NOT NULL")

    @Test
    fun oneFormulaFromTheLiveRowsForTheCardAndTheSummary() {
        assertBoth(32, 2, 0, 0, "nothing submitted")
        submit("Z2", "00000000-0000-4000-8000-0000000b2f01")
        assertBoth(32, 2, 1, 5000, "a TSO submit of Gulshan - Test")
        submit("Z1", "00000000-0000-4000-8000-0000000b2f02", reopened = true)
        assertBoth(32, 2, 1, 5000, "a reopened submit no longer counts")
        submit("Z1", "00000000-0000-4000-8000-0000000b2f03", via = "job")
        assertBoth(32, 2, 2, 10000, "the midnight job's row counts like any other")
        assertBoth(33, 1, 1, 10000, "Banani - Test alone")
        assertBoth(35, 1, 1, 10000, "the route-only TSO: the zone of its routes")
        assertBoth(37, 0, 0, null, "Exact Zone plans no route: no service zone")
        // The route-day states (the summary's old rule) say nothing: no route-day of the fixture is final_submitted.
        assertEquals(0L, long("SELECT count(*) FROM app.route_day WHERE business_date = '2026-10-25' AND state = 'final_submitted'"))
    }

    @Test
    fun noDwOnlyStampSurvivesAndTheWorkerNeverWritesOne() {
        // The old midnight stamp lived in dw.agg_daily_zone only. A stamp without a live row (left from before) is cleared by the next
        // rebuild of its zone-day, and is not a Final Submit after it.
        sql("UPDATE dw.agg_daily_zone SET final_submitted_at = TIMESTAMPTZ '2026-10-25 18:00Z', auto_submitted = true WHERE business_date = '2026-10-25' AND zone_id = ${id("Z1")}")
        rebuild()
        assertEquals(0L, stamped()); assertEquals(0L, long("SELECT count(*) FROM dw.agg_daily_zone WHERE auto_submitted"))
        assertBoth(32, 2, 0, 0, "a stamp without a live row")
        // The next morning the worker runs again: it stamps nothing (AutoFinalSubmitJob, lane B3, writes the app.final_submit row).
        AggregationWorker(fresh.db, AronClock { Instant.parse("2026-10-26T01:00:00Z") }).also { w -> w.requestRebuild(day); w.runUntilIdle() }
        assertEquals(0L, stamped())
        assertBoth(32, 2, 0, 0, "the day after, before any job row")
        // The job's row: the zone-day's time is the first live submit.
        submit("Z2", "00000000-0000-4000-8000-0000000b2f04", via = "job")
        assertEquals(1L, stamped())
        assertEquals(1L, long("SELECT count(*) FROM dw.agg_daily_zone z JOIN app.final_submit f ON f.zone_id = z.zone_id AND f.business_date = z.business_date AND f.submitted_at = z.final_submitted_at WHERE z.business_date = '2026-10-25'"))
        assertBoth(32, 2, 1, 5000, "the job's row")
    }
}
