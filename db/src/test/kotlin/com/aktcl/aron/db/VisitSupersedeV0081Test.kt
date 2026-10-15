package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * V0081 (docs/requests/db-backend-core-visit-supersede.md): a paper web_entry visit superseded by the phone's visit
 * for the same memo is tombstoned with voided_at, exactly as backend-core's matchBackfill writes it; voided visits
 * never count, and the tombstone is write-once.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VisitSupersedeV0081Test {
    private lateinit var db: TestDatabase

    @BeforeAll
    fun setUp() {
        db = TestPostgres.createDatabase().migrated()
        db.connect().use { SeedLoader.load(it) }
    }

    @AfterAll
    fun tearDown() = db.close()

    private fun Connection.tx(block: Connection.() -> Unit) {
        autoCommit = false
        try { block() } finally { rollback(); autoCommit = true }
    }

    private fun Connection.refused(sql: String): String {
        exec("SAVEPOINT r")
        val e = assertFailsWith<SQLException>(sql) { exec(sql) }
        exec("ROLLBACK TO SAVEPOINT r")
        return e.sqlState
    }

    private fun visit(uuid: String, kind: String, verdict: String, source: String, enteredBy: String, seq: Int) =
        "INSERT INTO app.visit (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, visit_kind, outlet_id, " +
            "opened_at, sequence_no, planned, verdict, radius_m_used, max_accuracy_m_used, location_basis, geo_action, entry_source, entered_by_user_id) " +
            "VALUES ('$uuid', '$uuid', DATE '2026-10-05', (SELECT id FROM app.app_user WHERE username = 'sr1001'), TIMESTAMPTZ '2026-10-05 10:00+06', 0, " +
            "'$kind', (SELECT min(id) FROM app.outlet), TIMESTAMPTZ '2026-10-05 10:00+06', $seq, true, '$verdict', 100, 50, 'master', 'sale_allowed', '$source', $enteredBy)"

    private val paper = "c0ffee00-0000-4000-8000-000000000001"
    private val phone = "c0ffee00-0000-4000-8000-000000000002"

    // The statement docs/requests/db-backend-core-visit-supersede.md gives backend-core.
    private val supersede = "UPDATE app.visit SET voided_at = now() WHERE client_uuid = '$paper' AND business_date = DATE '2026-10-05' " +
        "AND visit_kind = 'web_entry' AND voided_at IS NULL"

    @Test
    fun thePaperVisitIsTombstonedOnceAndOnlyThePhoneVisitStaysLive() = db.connect().use { c ->
        c.tx {
            exec(visit(paper, "web_entry", "no_fix", "manual", "(SELECT id FROM app.app_user WHERE username = 'admin1001')", 1))
            exec(visit(phone, "sr_call", "in_range", "device", "NULL", 2))
            val live = "SELECT string_agg(visit_kind, ',' ORDER BY visit_kind) FROM app.visit WHERE business_date = DATE '2026-10-05' " +
                "AND client_uuid IN ('$paper', '$phone') AND voided_at IS NULL"
            assertEquals("sr_call,web_entry", scalar(live))
            assertEquals(1, prepareStatement(supersede).use { it.executeUpdate() })
            assertEquals("sr_call", scalar(live))
            // A replay of the action changes nothing.
            assertEquals(0, prepareStatement(supersede).use { it.executeUpdate() })
            // The tombstone is write-once: it can be neither cleared nor moved.
            assertEquals(DENIED, refused("UPDATE app.visit SET voided_at = NULL WHERE client_uuid = '$paper'"))
            assertEquals(DENIED, refused("UPDATE app.visit SET voided_at = now() + interval '1 hour' WHERE client_uuid = '$paper'"))
            // Nothing else on the synced row may change with it.
            assertEquals(DENIED, refused("UPDATE app.visit SET outlet_id = outlet_id + 1 WHERE client_uuid = '$phone'"))
        }
        assertEquals("true", c.scalar("SELECT has_column_privilege('api_rw', 'app.visit', 'voided_at', 'UPDATE')::text"))
        assertEquals(
            "t",
            c.scalar("SELECT (col_description('app.visit'::regclass, attnum) LIKE '%superseded by the phone%')::text::char FROM pg_attribute WHERE attrelid = 'app.visit'::regclass AND attname = 'voided_at'"),
        )
    }

    @Test
    fun aVoidedPaperVisitDropsOutOfTheVisitViews() = db.connect().use { c ->
        c.tx {
            val sr = "(SELECT id FROM app.app_user WHERE username = 'sr1001')"
            val fact = "INSERT INTO dw.fact_visit (business_date, visit_client_uuid, user_id, outlet_id, visit_kind, opened_at, planned, voided, last_event_id, " +
                "geo_action, device_verdict) VALUES (DATE '2026-10-05', '%s', $sr, (SELECT min(id) FROM app.outlet), '%s', now(), true, %s, 1, 'sale_allowed', '%s')"
            exec(fact.format(phone, "sr_call", "false", "in_range"))
            exec(fact.format(paper, "web_entry", "false", "no_fix"))
            val geo = "SELECT visits || '|' || no_fix FROM dw.v_geo_integrity WHERE business_date = DATE '2026-10-05' AND user_id = $sr"
            assertEquals("2|1", scalar(geo), "an unmatched paper visit counts in the geo view")
            exec("UPDATE dw.fact_visit SET voided = true WHERE visit_client_uuid = '$paper'")   // the projector after the match
            assertEquals("1|0", scalar(geo))
            assertEquals("1", scalar("SELECT visits FROM dw.v_daily_sr WHERE business_date = DATE '2026-10-05' AND user_id = $sr"))
        }
    }

    private companion object {
        const val DENIED = "42501"
    }
}
