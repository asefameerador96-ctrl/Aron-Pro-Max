package com.aktcl.aron.db

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V0141, the integration round of the TSO manual build (docs/requests/tso-manual-build-2026-10.md s10, lead ruling R-W-07,
 * lane X1): the six TSO domain events are live, `dw.agg_daily_zone.auto_submitted` is retired (its comments say so), and
 * the one-off clean-up gives every old dw-only midnight stamp the projection the rebuild writes (lane B2 ask 10).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TsoIntegrationV0141Test {
    private lateinit var db: TestDatabase

    @BeforeAll
    fun setUp() {
        db = TestPostgres.createDatabase().migrated()
    }

    @AfterAll
    fun tearDown() = db.close()

    private val six = listOf(
        "leave.decided", "support_ticket.commented", "support_ticket.created", "support_ticket.status_changed",
        "zone_day.final_submitted", "zone_day.reopened",
    )

    private fun Connection.statusOf(types: List<String>): List<String?> =
        column("SELECT event_type || '=' || status FROM app.domain_event_type WHERE event_type IN (${types.joinToString { "'$it'" }}) ORDER BY event_type")

    @Test
    fun theSixTsoEventsAreLiveAndTheirSchemasAreUnchanged() = db.connect().use { c ->
        assertEquals(six.map { "$it=live" }, c.statusOf(six))
        // only the status moved: one version each, as V0134 wrote it, required keys still not enforced
        assertEquals(six.map { "$it|1|false" }, c.column(
            "SELECT event_type || '|' || payload_version || '|' || enforce_required FROM app.domain_event_type " +
                "WHERE event_type IN (${six.joinToString { "'$it'" }}) ORDER BY event_type, payload_version",
        ))
    }

    @Test
    fun autoSubmittedIsRetiredInTheComments() = db.connect().use { c ->
        fun comment(rel: String, col: String) =
            c.scalar("SELECT col_description('$rel'::regclass, attnum) FROM pg_attribute WHERE attrelid = '$rel'::regclass AND attname = '$col'")!!
        val table = comment("dw.agg_daily_zone", "auto_submitted")
        assertTrue(table.startsWith("Retired (V0141, D-TSO-MAN-30): always false."), table)
        assertTrue(table.contains("app.final_submit is the only source of a zone-day's Final Submit"), table)
        val view = comment("dw.v_route_day", "zone_auto_submitted")
        assertTrue(view.startsWith("Retired (V0141): always false"), view)
        // the column stays, so the stable view keeps its column contract (DwViewContractTest)
        assertEquals("boolean", c.scalar("SELECT data_type FROM information_schema.columns WHERE table_schema = 'dw' AND table_name = 'v_route_day' AND column_name = 'zone_auto_submitted'"))
        // the sibling comments no longer describe the old model: the midnight job closes the zone-day like the TSO
        // (DayService.closeLocked), so the zone flag is set by it and the stamps are the first live app.final_submit row
        val siblings = listOf(
            comment("dw.agg_daily_zone", "final_submitted_at"), comment("dw.v_route_day", "zone_final_submitted"),
            comment("dw.v_route_day", "zone_final_submitted_at"), comment("dw.v_final_submit_log", "first_via"),
        )
        for (text in siblings) {
            assertFalse(text.contains("auto-submit"), "no separate midnight auto-submit any more: $text")
            assertFalse(text.contains("see zone_auto_submitted"), "does not point at the retired column: $text")
        }
        assertTrue(siblings[0].startsWith("UTC time of the zone-day's first live app.final_submit row (not reopened), whoever made it"), siblings[0])
        assertTrue(siblings[1].contains("whoever closed it, the midnight job included"), siblings[1])
        assertTrue(siblings[2].startsWith("UTC time of that zone-day's first live app.final_submit row (via app, web or job"), siblings[2])
        assertEquals("Channel of the first Final Submit: app, web or job (the midnight AutoFinalSubmitJob, V0135).", siblings[3])
    }

    /**
     * The clean-up on a database that holds old stamps: migrate to V0140, write the rows the removed worker left (and the
     * live and reopened Final Submits beside them), then apply V0141. Each stamped zone-day ends as the rebuild writes it
     * (DashboardSummaries.rebuildZoneDay: the first live submit's time, else null; auto_submitted false); a row that was
     * not stamped is not touched.
     */
    @Test
    fun theCleanUpGivesEveryOldStampTheProjectionOfTheLiveRows() {
        val old = TestPostgres.createDatabase()
        try {
            Flyway.configure().configuration(old.flyway().configuration).target("140").load().migrate()
            val zoneDay = "z.zone_id = (SELECT id FROM app.zone WHERE code = 'Z-MIR')"
            // days far behind the seed clock (the seed starts today), relative so the test never depends on the run date
            fun day(n: Int) = "(app.dhaka_date(now()) - ${40 + n})"
            fun uuid(n: Int) = "0a141000-0000-4000-8000-00000000000$n"
            fun atOf(c: Connection, n: Int) = c.scalar("SELECT final_submitted_at::text || '|' || auto_submitted FROM dw.agg_daily_zone z WHERE $zoneDay AND z.business_date = ${day(n)}")
            fun submitAt(c: Connection, u: Int) = c.scalar("SELECT submitted_at::text FROM app.final_submit WHERE client_uuid = '${uuid(u)}'")
            val untouched: String?
            old.connect().use { c ->
                SeedLoader.load(c)
                assertEquals(six.map { "$it=planned" }, c.statusOf(six), "V0134 catalogued them planned")
                fun stamp(n: Int, auto: Boolean, at: String) =
                    c.exec("INSERT INTO dw.agg_daily_zone (business_date, zone_id, territory_id, final_submitted_at, auto_submitted) " +
                        "SELECT ${day(n)}, id, territory_id, $at, $auto FROM app.zone WHERE code = 'Z-MIR'")
                fun submit(n: Int, u: Int, at: String, reopened: Boolean = false) =
                    c.exec("INSERT INTO app.final_submit (client_uuid, zone_id, business_date, submitted_by, submitted_at, via, reopened_at, reopened_by, reopen_reason) " +
                        "VALUES ('${uuid(u)}', (SELECT id FROM app.zone WHERE code = 'Z-MIR'), ${day(n)}, NULL, $at, 'job', " +
                        (if (reopened) "$at + interval '1 hour', (SELECT min(id) FROM app.app_user), 'qa reopen of the zone-day'" else "NULL, NULL, NULL") + ")")
                val t = "(now() - interval '45 days')"
                // 1: an old stamp (the worker's own time) with a live submit -> the live submit's time
                stamp(1, true, "$t - interval '9 hours'"); submit(1, 1, t)
                // 2: an old stamp and no submit at all -> nothing submitted
                stamp(2, true, t)
                // 3: an old stamp, a reopened submit, then a live one -> the live one, never the reopened one
                stamp(3, true, t); submit(3, 3, "$t - interval '2 hours'", reopened = true); submit(3, 4, "$t + interval '3 hours'")
                // 4: an old stamp with only a reopened submit -> nothing submitted
                stamp(4, true, t); submit(4, 5, t, reopened = true)
                // 5: a rebuilt row (not stamped) keeps what it holds, even with no live submit behind it
                stamp(5, false, "$t + interval '5 hours'")
                assertEquals("4", c.scalar("SELECT count(*) FROM dw.agg_daily_zone WHERE auto_submitted"))
                untouched = atOf(c, 5)
            }
            old.flyway().migrate()
            old.connect().use { c ->
                assertEquals(six.map { "$it=live" }, c.statusOf(six))
                assertEquals("0", c.scalar("SELECT count(*) FROM dw.agg_daily_zone WHERE auto_submitted"), "no stamp is left")
                assertEquals("${submitAt(c, 1)}|false", atOf(c, 1), "1: the live submit's time, not the worker's stamp")
                assertEquals(null, c.scalar("SELECT final_submitted_at::text FROM dw.agg_daily_zone z WHERE $zoneDay AND z.business_date = ${day(2)}"), "2: no submit")
                assertEquals("${submitAt(c, 4)}|false", atOf(c, 3), "3: the live submit, not the reopened one")
                assertTrue(submitAt(c, 3) != submitAt(c, 4))
                assertEquals(null, c.scalar("SELECT final_submitted_at::text FROM dw.agg_daily_zone z WHERE $zoneDay AND z.business_date = ${day(4)}"), "4: only a reopened submit")
                assertEquals("false", c.scalar("SELECT bool_or(auto_submitted)::text FROM dw.agg_daily_zone z WHERE $zoneDay AND z.business_date IN (${day(2)}, ${day(4)})"))
                assertNotNull(untouched)
                assertEquals(untouched, atOf(c, 5), "5: a row that was not stamped is not touched")
            }
        } finally {
            old.close()
        }
    }
}
