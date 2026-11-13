package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** V0173 to V0190 (sr-server trains 1 and 2): write-once visit columns, the NOT VALID then VALID constraints, the flag event kinds, the deterministic edit_reason codes. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SrManualSv1V0173Test {
    private lateinit var db: TestDatabase

    @BeforeAll
    fun setUp() {
        db = TestPostgres.createDatabase().migrated()
        db.connect().use { SeedLoader.load(it) }
    }

    @AfterAll
    fun tearDown() = db.close()

    private fun Connection.refused(sql: String): String {
        exec("SAVEPOINT r")
        val e = assertFailsWith<SQLException>(sql) { exec(sql) }
        exec("ROLLBACK TO SAVEPOINT r")
        return e.sqlState
    }

    private fun Connection.tx(block: Connection.() -> Unit) {
        autoCommit = false
        try { block() } finally { rollback(); autoCommit = true }
    }

    private fun visit(uuid: String, zero: Boolean) =
        "INSERT INTO app.visit (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, visit_kind, outlet_id, " +
            "opened_at, sequence_no, planned, verdict, radius_m_used, max_accuracy_m_used, location_basis, geo_action, is_zero_sale) " +
            "VALUES ('$uuid', '$uuid', DATE '2026-10-05', (SELECT id FROM app.app_user WHERE username = 'sr1001'), TIMESTAMPTZ '2026-10-05 10:00+06', 0, " + // date-pin-ok: a fixed day inside the migrated visit partition; the test reads no clock
            "'sr_call', (SELECT min(id) FROM app.outlet), TIMESTAMPTZ '2026-10-05 10:00+06', 1, true, 'in_range', 100, 50, 'master', 'sale_allowed', $zero)" // date-pin-ok: a fixed day inside the migrated visit partition; the test reads no clock

    @Test
    fun theConstraintsV0173AddedAreValidatedByV0174() = db.connect().use { c ->
        assertEquals("5", c.scalar("SELECT count(DISTINCT conname) FROM pg_constraint WHERE convalidated AND conname IN ('visit_zero_sale_reason_needs_zero_sale', 'visit_call_photo_skip_check', 'media_purpose_check', 'code_list_list_key_check', 'outlet_request_event_event_check')"))
        assertEquals("0", c.scalar("SELECT count(*) FROM pg_constraint WHERE NOT convalidated AND conrelid IN ('app.visit'::regclass, 'app.media'::regclass, 'app.code_list'::regclass, 'app.outlet_request_event'::regclass)"))
    }

    @Test
    fun theCloseColumnsAreWriteOnceAndTheirChecksHold() = db.connect().use { c ->
        c.tx {
            val v = "c0ffee00-0000-4000-8000-0000000d0001"
            exec(visit(v, zero = true))
            exec("UPDATE app.visit SET zero_sale_reason_code = 'stock_available', call_photo_skip_reason_code = 'other', reason_code_unlisted = true WHERE client_uuid = '$v'")
            // the first value stands: a second, different value is refused by the synced-row guard
            exec("SAVEPOINT g")
            assertFailsWith<SQLException> { exec("UPDATE app.visit SET zero_sale_reason_code = 'insufficient_cash' WHERE client_uuid = '$v'") }
            exec("ROLLBACK TO SAVEPOINT g")
            exec("SAVEPOINT g")
            assertFailsWith<SQLException> { exec("UPDATE app.visit SET reason_code_unlisted = NULL WHERE client_uuid = '$v'") }
            exec("ROLLBACK TO SAVEPOINT g")
            assertEquals("stock_available", scalar("SELECT zero_sale_reason_code FROM app.visit WHERE client_uuid = '$v'"))
            // a photo and a skip reason together, and a reason on a sale visit, are refused by the CHECKs
            val w = "c0ffee00-0000-4000-8000-0000000d0002"
            exec(visit(w, zero = false))
            assertEquals("23514", refused("UPDATE app.visit SET zero_sale_reason_code = 'stock_available' WHERE client_uuid = '$w'"))
            assertEquals("23514", refused("UPDATE app.visit SET call_photo_uuid = gen_random_uuid(), call_photo_skip_reason_code = 'other' WHERE client_uuid = '$w'"))
        }
    }

    @Test
    fun theServerFlagKindsOfAnOutletRequestAreAcceptedAndOthersAreNot() = db.connect().use { c ->
        c.tx {
            for (kind in listOf("flag_cluster_dropped", "flag_possible_duplicate")) {
                exec("INSERT INTO app.outlet_request_event (request_uuid, event, via, business_date, at) VALUES (gen_random_uuid(), '$kind', 'job', DATE '2026-10-05', now())") // date-pin-ok: a fixed day inside the migrated visit partition; the test reads no clock
            }
            assertEquals("23514", refused("INSERT INTO app.outlet_request_event (request_uuid, event, via, business_date, at) VALUES (gen_random_uuid(), 'flag_other', 'job', DATE '2026-10-05', now())")) // date-pin-ok: a fixed day inside the migrated visit partition; the test reads no clock
        }
    }

    @Test
    fun editReasonWrongQuantityIsActiveOnAFreshDatabaseAndAfterAnOldRetirement() = db.connect().use { c ->
        c.tx {
            // fresh database: both legacy reasons are active with the V0173 labels
            for (code in listOf("wrong_quantity", "wrong_price_type")) {
                assertEquals("null", scalar("SELECT coalesce(valid_to::text, 'null') FROM app.code_list_item WHERE list_key = 'edit_reason' AND code = '$code'"), code)
            }
            // the old dev state (retired, relabelled) is brought back by the V0176 statement
            exec("UPDATE app.code_list_item SET valid_to = DATE '2026-12-31', label_en = 'x' WHERE list_key = 'edit_reason' AND code = 'wrong_quantity'") // date-pin-ok: a fixed day inside the migrated visit partition; the test reads no clock
            val sql = java.io.File(System.getProperty("aron.migrations"), "V0176__edit_reason_codes_deterministic.sql").readText()
            exec(sql.lines().filter { !it.trimStart().startsWith("--") }.joinToString("\n"))
            assertEquals("null|Wrong quantity|ভুল পরিমাণ", scalar("SELECT coalesce(valid_to::text, 'null') || '|' || label_en || '|' || label_bn FROM app.code_list_item WHERE list_key = 'edit_reason' AND code = 'wrong_quantity'"))
            // running it twice changes nothing more
            exec(sql.lines().filter { !it.trimStart().startsWith("--") }.joinToString("\n"))
            assertEquals("1", scalar("SELECT count(*) FROM app.code_list_item WHERE list_key = 'edit_reason' AND code = 'wrong_quantity'"))
        }
    }

    @Test
    fun clusterCreateRequestsAndTheTrainTwoColumnsAreValidatedAndGuarded() = db.connect().use { c ->
        // V0186 / V0187 / V0189 / V0190: every constraint they add is validated, the new columns exist, created_cluster_id is write-once, cluster_create is a request type without an outlet
        assertEquals("0", c.scalar("SELECT count(*) FROM pg_constraint WHERE NOT convalidated AND conrelid IN ('app.route_day'::regclass, 'app.outlet_change_request'::regclass, 'app.sync_rejected'::regclass)"))
        assertEquals("2", c.scalar("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'app' AND ((table_name = 'route_day' AND column_name = 'late_rows_after_sales_submit') OR (table_name = 'outlet_change_request' AND column_name = 'created_cluster_id'))"))
        assertTrue(c.scalar("SELECT pg_get_triggerdef(oid) FROM pg_trigger WHERE tgname = 'outlet_change_request_immutable' AND NOT tgisinternal")!!.contains("'=created_cluster_id'"))
        val rule = c.scalar("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'outlet_change_request_outlet_rule'")!!
        assertTrue(rule.contains("cluster_create") && rule.contains("new"), rule)
        assertTrue(c.scalar("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'sync_rejected_code_check'")!!.contains("cluster_name_exists"))
        assertTrue(c.scalar("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'route_day_late_rows_sales_check'")!!.contains(">= 0"))
        assertEquals("false", c.scalar("SELECT default_value::text FROM app.cfg_key WHERE key = 'cfg.sale.journey_repeat_sale_enabled'"))
    }

    @Test
    fun v0191OneClusterNameNormalisationAUniqueIndexPerZoneAPersonalSurveyViewAndEveryRejectedCodeStillAllowed() = db.connect().use { c ->
        fun exec(sql: String) = c.createStatement().use { it.execute(sql) }
        // the one normalisation: case, Unicode spaces, NFC
        assertEquals("cafe\u0301 x".let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFC) }.lowercase().replace(Regex("\\s+"), " "), c.scalar("SELECT app.norm_cluster_name(E'  Cafe\u0301\u00a0\u2003X ')"))
        assertEquals(c.scalar("SELECT app.norm_cluster_name('Bazar 7')"), c.scalar("SELECT app.norm_cluster_name(E'bazar\u00a0\u00a07  ')"))
        assertEquals("true", c.scalar("SELECT (SELECT provolatile = 'i' FROM pg_proc WHERE proname = 'norm_cluster_name')::text"), "immutable: usable in the index")
        assertEquals(1, c.scalar("SELECT count(*) FROM pg_indexes WHERE schemaname = 'app' AND indexname = 'cluster_zone_name_norm_key' AND indexdef LIKE 'CREATE UNIQUE%'")!!.toInt())
        // the index refuses a variant in the same zone and allows it in another
        exec("INSERT INTO app.zone (code, name, territory_id) SELECT 'Z191A', 'Zone 191A', min(id) FROM app.territory")
        exec("INSERT INTO app.zone (code, name, territory_id) SELECT 'Z191B', 'Zone 191B', min(id) FROM app.territory")
        val za = c.scalar("SELECT id FROM app.zone WHERE code = 'Z191A'"); val zb = c.scalar("SELECT id FROM app.zone WHERE code = 'Z191B'")
        exec("INSERT INTO app.cluster (zone_id, name) VALUES ($za, 'Lane Seven')")
        assertTrue(runCatching { exec("INSERT INTO app.cluster (zone_id, name) VALUES ($za, E'  LANE\u00a0seven ')") }.isFailure, "same zone, same normalised name")
        exec("INSERT INTO app.cluster (zone_id, name) VALUES ($zb, 'LANE SEVEN')")
        // the free-text answer of a survey is personal and not read by bi_reader; the other columns are
        assertTrue(c.scalar("SELECT obj_description('dw.v_survey_response'::regclass)")!!.endsWith("pii: personal"))
        assertEquals("false", c.scalar("SELECT has_column_privilege('bi_reader', 'dw.v_survey_response', 'answer_text', 'SELECT')::text"))
        assertEquals("true", c.scalar("SELECT has_column_privilege('bi_reader', 'dw.v_survey_response', 'answer_option_code', 'SELECT')::text"))
        assertEquals("true", c.scalar("SELECT has_column_privilege('web_ro', 'dw.v_survey_response', 'answer_text', 'SELECT')::text"))
        // B4: the per-record late-row facts
        assertEquals("true", c.scalar("SELECT (to_regclass('app.late_row_fact') IS NOT NULL)::text"))
        assertTrue(c.scalar("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'app.late_row_fact'::regclass AND contype = 'c'")!!.contains("server"))
        // item e: the sync_rejected code check keeps EVERY code of V0135 and adds cluster_name_exists (the Loyalty lane's gift codes are added by whichever migration lands second)
        val def = c.scalar("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'sync_rejected_code_check'")!!
        for (code in listOf("scope_stale", "config_version_unknown", "parent_missing", "outlet_pending_approval", "price_list_unknown", "schema_invalid", "unknown_record_type", "unknown_sku", "unknown_outlet", "unknown_route",
            "memo_no_invalid", "edit_not_allowed", "chain_too_deep", "voided_by_admin", "lines_exceed_max", "qty_invalid", "price_invalid", "attendance_duplicate", "server_error", "retry_exhausted", "unknown_gift",
            "insufficient_points", "gift_photo_exists", "day_final_submitted", "ticket_state_invalid", "issue_type_unknown", "cluster_name_exists")) {
            assertTrue(def.contains("'$code'"), "sync_rejected_code_check lost $code")
        }
    }
}
