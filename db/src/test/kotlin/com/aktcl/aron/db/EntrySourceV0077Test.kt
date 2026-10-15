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

/** V0077/V0078: entry_source and entered_by_user_id on memo and visit (docs/requests/backend-core-memo-source.md). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EntrySourceV0077Test {
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

    @Test
    fun theColumnsDefaultToDeviceAndTheCheckTiesManualToItsWebUser() = db.connect().use { c ->
        for (t in listOf("memo", "visit")) {
            assertEquals(
                "entry_source|text|NO|'device'::text;entered_by_user_id|bigint|YES|",
                c.column(
                    "SELECT concat_ws('|', column_name, data_type, is_nullable, coalesce(column_default, '')) FROM information_schema.columns " +
                        "WHERE table_schema = 'app' AND table_name = '$t' AND column_name IN ('entry_source', 'entered_by_user_id') ORDER BY column_name DESC",
                ).joinToString(";"),
            )
            val def = c.scalar("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = '${t}_entry_source_known'")!!
            assertTrue("'manual'" in def && "entered_by_user_id IS NOT NULL" in def, def)
            assertEquals("t", c.scalar("SELECT convalidated FROM pg_constraint WHERE conname = '${t}_entry_source_known'"))
            assertEquals("app_user", c.scalar("SELECT confrelid::regclass::text FROM pg_constraint WHERE conname = '${t}_entered_by_user_id_fkey'")!!.removePrefix("app."))
        }
    }

    private fun memo(no: String, source: String, enteredBy: String) =
        "INSERT INTO app.memo (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, visit_client_uuid, " +
            "outlet_id, memo_no, memo_kind, committed_at, price_list_date, price_type, gross_mtk, offer_discount_mtk, drp_discount_mtk, " +
            "qc_deduction_mtk, round_adj_mtk, net_mtk, paid_mtk, due_mtk, is_credit, line_count, discount_line_count, qc_line_count, entry_source, entered_by_user_id) " +
            "VALUES (gen_random_uuid(), gen_random_uuid(), '2026-10-05', (SELECT id FROM app.app_user WHERE username = 'sr1001'), now(), 0, gen_random_uuid(), " +
            "(SELECT min(id) FROM app.outlet), 'sr1001-261005-$no', 'sale', now(), '2026-10-05', 'outlet', 10000, 0, 0, 0, 0, 10000, 10000, 0, false, 1, 0, 0, " +
            "'$source', $enteredBy)"

    @Test
    fun aManualMemoNamesItsWebUserAndADeviceMemoNone() = db.connect().use { c ->
        val admin = "(SELECT id FROM app.app_user WHERE username = 'admin1001')"
        c.tx {
            exec(memo("001", "device", "NULL"))
            exec(memo("002", "manual", admin))
            assertEquals("device:|manual:t", column("SELECT entry_source || ':' || coalesce((entered_by_user_id IS NOT NULL)::text::char, '') FROM app.memo ORDER BY memo_no").joinToString("|"))
            exec("INSERT INTO app.memo (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, visit_client_uuid, outlet_id, memo_no, memo_kind, committed_at, price_list_date, price_type, gross_mtk, offer_discount_mtk, drp_discount_mtk, qc_deduction_mtk, round_adj_mtk, net_mtk, paid_mtk, due_mtk, is_credit, line_count, discount_line_count, qc_line_count) SELECT gen_random_uuid(), gen_random_uuid(), business_date, user_id, captured_at, config_version, gen_random_uuid(), outlet_id, 'sr1001-261005-003', memo_kind, committed_at, price_list_date, price_type, gross_mtk, offer_discount_mtk, drp_discount_mtk, qc_deduction_mtk, round_adj_mtk, net_mtk, paid_mtk, due_mtk, is_credit, line_count, discount_line_count, qc_line_count FROM app.memo WHERE memo_no = 'sr1001-261005-001'")
            assertEquals("device", scalar("SELECT entry_source FROM app.memo WHERE memo_no = 'sr1001-261005-003'"), "a phone insert that names neither column is a device row")
            assertEquals("23514", refused(memo("004", "manual", "NULL")))
            assertEquals("23514", refused(memo("005", "device", admin)))
            assertEquals("23514", refused(memo("006", "web", "NULL")))
            assertEquals("23503", refused(memo("007", "manual", "-1")))
            // Written once: the V0007 guard keeps the source after insert.
            assertTrue(refused("UPDATE app.memo SET entry_source = 'device', entered_by_user_id = NULL WHERE memo_no = 'sr1001-261005-002'").isNotEmpty())
        }
    }

    @Test
    fun theRegistryTakesManualEntryOnce() = db.connect().use { c ->
        val def = c.scalar("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ingest_registry_flags_known' AND conrelid = 'app.ingest_registry'::regclass")!!
        assertTrue("manual_entry" in def && "resync_late" in def && "checkout_too_early" in def, def)
        c.tx {
            assertEquals("true", scalar("SELECT (ARRAY['manual_entry']::text[] <@ ARRAY['resync_late', 'config_stamp_regress', 'checkout_too_early', 'manual_entry']::text[])::text"))
        }
        assertEquals("t", c.scalar("SELECT convalidated FROM pg_constraint WHERE conname = 'ingest_registry_flags_known' AND conrelid = 'app.ingest_registry'::regclass"))
    }

    @Test
    fun theDwFactsAndTheDailySrViewCarryTheSource() = db.connect().use { c ->
        c.tx {
            for (t in listOf("fact_memo", "fact_visit")) {
                assertEquals("'device'::text", scalar("SELECT column_default FROM information_schema.columns WHERE table_schema = 'dw' AND table_name = '$t' AND column_name = 'entry_source'"))
            }
            exec(
                "INSERT INTO dw.fact_memo (business_date, memo_client_uuid, memo_no, user_id, outlet_id, status, committed_at, line_count, gross_mtk, offer_discount_mtk, " +
                    "drp_discount_mtk, qc_deduction_mtk, net_mtk, paid_mtk, due_mtk, is_credit, captured_offline, received_at, last_event_id, entry_source) " +
                    "SELECT DATE '2026-10-08', gen_random_uuid(), 'M-' || g, u.id, (SELECT min(id) FROM app.outlet), 'active', now(), 1, 1000, 0, 0, 0, 1000, 1000, 0, false, false, now(), 1, " +
                    "CASE WHEN g = 1 THEN 'manual' ELSE 'device' END FROM app.app_user u, generate_series(1, 3) g WHERE u.username = 'sr1001'",
            )
            assertEquals(
                "3|1",
                scalar("SELECT active_memo_count || '|' || memos_entered_manually FROM dw.v_daily_sr WHERE business_date = DATE '2026-10-08' AND user_id = (SELECT id FROM app.app_user WHERE username = 'sr1001')"),
            )
            // The appended column is last, so readers of the V0015 columns see them unchanged.
            assertEquals("memos_entered_manually", scalar("SELECT column_name FROM information_schema.columns WHERE table_schema = 'dw' AND table_name = 'v_daily_sr' ORDER BY ordinal_position DESC LIMIT 1"))
        }
        assertEquals("true", c.scalar("SELECT has_table_privilege('web_ro', 'dw.v_daily_sr', 'SELECT')::text"))
    }
}
