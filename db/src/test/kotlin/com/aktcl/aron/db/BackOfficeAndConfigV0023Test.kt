package com.aktcl.aron.db

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * V0023: back-office content and price-batch tables, the leave-decision repair.
 * V0024: flat cfg.sync.reconcile_types (R17) with stored values reshaped, server-only outlet_fields, three device keys.
 * V0025/V0026: v1.2 device integrity columns with NULL = unknown. V0028: restrictive directions.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackOfficeAndConfigV0023Test {
    private lateinit var db: TestDatabase

    @BeforeAll
    fun setUp() {
        db = TestPostgres.createDatabase().migrated()
        db.connect().use {
            it.exec("INSERT INTO app.app_user (username, full_name, role) VALUES ('tso9001', 'TSO', 'TSO'), ('dmo9001', 'DMO', 'DMO'), ('adm9001', 'Admin', 'ADMIN'), ('adm9002', 'Admin 2', 'ADMIN')")
        }
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

    private fun uid(name: String) = "(SELECT id FROM app.app_user WHERE username = '$name')"

    @Test
    fun aLeaveDecisionIsSavedAndTheLeaveItselfStaysImmutable() = db.connect().use { c ->
        c.tx {
            exec(
                "INSERT INTO app.leave_application (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, leave_type_code, from_date, days, reason) " +
                    "VALUES (gen_random_uuid(), gen_random_uuid(), '2026-10-07', ${uid("tso9001")}, now(), 0, 'casual', '2026-10-08', 2, 'family')",
            )
            exec("UPDATE app.leave_application SET status = 'approved', decided_by = ${uid("dmo9001")}, decided_at = now(), decision_note = 'ok'")
            assertEquals("approved|2026-10-09", scalar("SELECT status || '|' || to_date FROM app.leave_application"))
            assertEquals("42501", refused("UPDATE app.leave_application SET days = 3"))
            assertEquals("42501", refused("UPDATE app.leave_application SET from_date = '2026-10-10'"))
            assertEquals("42501", refused("UPDATE app.leave_application SET decision_note = 'changed'"))
            assertEquals("42501", refused("UPDATE app.leave_application SET status = 'rejected'"))
        }
    }

    @Test
    fun priceBatchesMoveOnlyForwardAndTheCheckerIsNeverTheMaker() = db.connect().use { c ->
        c.tx {
            val ins = "INSERT INTO app.price_batch (batch_uuid, status, valid_from, fingerprint, price_rows, row_count, submitted_by) VALUES "
            exec("$ins ('00000000-0000-4000-8000-000000000001', 'pending_approval', '2026-10-08', repeat('a', 64), '[{\"sku_id\":1,\"amount_mtk\":5000}]', 1, ${uid("adm9001")})")
            assertEquals("23514", refused("UPDATE app.price_batch SET status = 'published', decided_by = ${uid("adm9001")}"))
            exec("UPDATE app.price_batch SET status = 'published', decided_by = ${uid("adm9002")}, decided_at = now(), price_list_version = 7")
            assertEquals("42501", refused("UPDATE app.price_batch SET status = 'pending_approval'"))
            assertEquals("42501", refused("UPDATE app.price_batch SET status = 'rejected'"))
            exec("UPDATE app.price_batch SET updated_at = now()")                        // a terminal batch may still be touched...
            assertEquals("42501", refused("UPDATE app.price_batch SET price_rows = '[]'"))       // ...but not changed
            assertEquals("42501", refused("UPDATE app.price_batch SET price_list_version = 8"))  // written once
            assertEquals("42501", refused("DELETE FROM app.price_batch"))
            exec("$ins ('00000000-0000-4000-8000-000000000004', 'previewed', '2026-10-08', repeat('b', 64), '[]', 1, ${uid("adm9001")})")
            exec("UPDATE app.price_batch SET status = 'published', price_list_version = NULL WHERE batch_uuid = '00000000-0000-4000-8000-000000000004'")
            exec("UPDATE app.price_batch SET price_list_version = 9 WHERE batch_uuid = '00000000-0000-4000-8000-000000000004'")  // publish writes it after the move
            assertEquals("23514", refused("$ins ('00000000-0000-4000-8000-000000000002', 'previewed', '2026-10-08', 'short', '[]', 1, NULL)"))
            assertEquals("23514", refused("$ins ('00000000-0000-4000-8000-000000000003', 'previewed', '2026-10-08', repeat('a', 64), '{}', 1, NULL)"))
        }
    }

    @Test
    fun publishedVersionsAndAssetsAreAppendOnly() = db.connect().use { c ->
        c.tx {
            exec("INSERT INTO app.admin_asset (asset_id, purpose, mime, bytes, sha256, blob_path) VALUES ('00000000-0000-4000-8000-0000000000a1', 'tutorial_manual', 'application/pdf', 10, decode(repeat('ab', 32), 'hex'), 'a/b.pdf')")
            exec("INSERT INTO app.survey (kind, valid_from) VALUES ('posm', '2026-10-01')")
            exec("INSERT INTO app.survey_version (survey_id, version, title_en, questions) SELECT id, 1, 'POSM', '[]' FROM app.survey")
            exec("INSERT INTO app.rubric (kind) VALUES ('joint_call')")
            exec("INSERT INTO app.rubric_version (rubric_id, version, criteria) SELECT id, 1, '[]' FROM app.rubric")
            exec("INSERT INTO app.print_template (kind, version, font_columns, template_json, effective_from) VALUES ('cash_memo', 1, 32, '{}', '2026-10-08')")
            exec("UPDATE app.survey SET version = 2, updated_at = now()")                  // the head row is mutable
            for (t in listOf("admin_asset", "survey_version", "rubric_version", "print_template")) {
                assertEquals("42501", refused("UPDATE app.$t SET created_at = now()"), t)
                assertEquals("42501", refused("DELETE FROM app.$t"), t)
            }
            assertEquals("23514", refused("INSERT INTO app.print_template (kind, version, font_columns, template_json, effective_from) VALUES ('cash_memo', 2, 40, '{}', '2026-10-08')"))
            assertEquals("23505", refused("INSERT INTO app.print_template (kind, version, font_columns, template_json, effective_from) VALUES ('cash_memo', 1, 42, '{}', '2026-10-09')"))
            exec("UPDATE app.content_item SET asset_id = asset_id")                        // FK and check exist and are valid
            assertEquals("23503", refused("INSERT INTO app.feedback_status (feedback_client_uuid, status) VALUES (gen_random_uuid(), 'resolved')"))
        }
        assertEquals(
            listOf("t", "t"),
            c.column("SELECT convalidated FROM pg_constraint WHERE conname IN ('content_item_asset_id_fkey', 'content_item_assigned_scope_array') ORDER BY conname"),
        )
    }

    @Test
    fun restrictiveDirectionsAreSetWhereBreakGlassCanCompare() = db.connect().use { c ->
        assertEquals(
            listOf(
                "cfg.auth.lockout_attempts=down", "cfg.auth.lockout_min=up", "cfg.device.lockdown_level=enum_order", "cfg.geo.max_accuracy_m=down",
                "cfg.geo.mock_policy=enum_order", "cfg.geo.radius_m=down", "cfg.sale.stock_check=enum_order",
            ),
            c.column("SELECT key || '=' || restrictive_dir FROM app.cfg_key WHERE restrictive_dir <> 'none' ORDER BY key"),
        )
        // enum_order means "later in bounds.enum is more restrictive": the last value must be the strictest.
        assertEquals(
            listOf("cfg.device.lockdown_level=prod", "cfg.geo.mock_policy=block_sale", "cfg.sale.stock_check=block"),
            c.column("SELECT key || '=' || (bounds -> 'enum' ->> (jsonb_array_length(bounds -> 'enum') - 1)) FROM app.cfg_key WHERE restrictive_dir = 'enum_order' ORDER BY key"),
        )
    }

    @Test
    fun reconcileTypesIsFlatAndOutletFieldsIsServerOnly() = db.connect().use { c ->
        assertEquals(
            """{"SR.qc": ["qc_line"], "SR.sale": ["memo"], "SR.stock": ["stock_movement"], "SR.outlet": ["visit"], "SR.promotion": ["memo_discount"]}""",
            c.scalar("SELECT default_value::text FROM app.cfg_key WHERE key = 'cfg.sync.reconcile_types'"),
        )
        assertEquals("server", c.scalar("SELECT delivery FROM app.cfg_key WHERE key = 'cfg.bundle.outlet_fields'"))
        assertEquals(
            listOf(
                "cfg.memo.reprint_watermark|memo|bool|true|{global}|device|cfg.edit.field|1|B",
                "cfg.print.confirm_after_print|print|bool|true|{global}|device|cfg.edit.field|1|B",
                "cfg.sale.require_printer_before_sale|sale|bool|false|{global}|device|cfg.edit.field|1|B",
            ),
            c.column(
                "SELECT concat_ws('|', key, area, value_type, default_value::text, scope_levels::text, delivery, editor_permission, risk_class, effect) FROM app.cfg_key " +
                    "WHERE key IN ('cfg.print.confirm_after_print', 'cfg.memo.reprint_watermark', 'cfg.sale.require_printer_before_sale') ORDER BY key",
            ),
        )
        assertEquals(listOf("cfg.auth.password_min_len"), c.column("SELECT key FROM app.cfg_key WHERE key LIKE '%password_min_len%'"))
    }

    @Test
    fun storedNestedReconcileValuesAreReshapedGapFree() = TestPostgres.createDatabase().use { old ->
        Flyway.configure().configuration(old.flyway().configuration).target("23").load().migrate()
        old.connect().use { c ->
            c.exec("INSERT INTO app.cfg_version (config_version, kind, committed_by, summary) SELECT 2, 'change', id, 'test' FROM app.app_user WHERE username = 'aron.system'")
            val ins = "INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, effective_to, config_version, created_by, reason) SELECT 'cfg.sync.reconcile_types', "
            c.exec("$ins 'global', 0, '{\"SR\": {\"sale\": [\"memo\"]}, \"AMO\": {\"outlet\": [\"visit\", \"call_assessment\"]}}', now() - interval '2 days', now() - interval '1 day', 2, id, 'closed' FROM app.app_user WHERE username = 'aron.system'")
            c.exec("$ins 'global', 0, '{\"SR\": {\"sale\": [\"memo\"], \"qc\": [\"qc_line\"]}, \"AMO\": {\"outlet\": [\"visit\"]}}', now() - interval '1 day', now() + interval '3 days', 2, id, 'current' FROM app.app_user WHERE username = 'aron.system'")
            c.exec("$ins 'global', 0, '{\"SR\": {\"sale\": [\"memo\", \"memo_line\"]}}', (SELECT effective_to FROM app.cfg_value WHERE reason = 'current'), NULL, 2, id, 'future' FROM app.app_user WHERE username = 'aron.system'")
            c.exec("$ins 'role', r.ordinal, '{\"stock\": [\"stock_movement\"]}', now() - interval '1 day', NULL, 2, u.id, 'role bare' FROM app.app_user u, app.role_def r WHERE u.username = 'aron.system' AND r.role = 'SR'")
            c.exec("$ins 'role', r.ordinal, '{\"AMO.outlet\": [\"visit\"]}', now() - interval '1 day', NULL, 2, u.id, 'already flat' FROM app.app_user u, app.role_def r WHERE u.username = 'aron.system' AND r.role = 'AMO'")
            c.exec(
                "INSERT INTO app.cfg_change (status, items, reason, risk_class, requested_by) SELECT s, " +
                    "jsonb_build_array('{\"key\": \"cfg.geo.radius_m\", \"value\": 90}'::jsonb, '{\"key\": \"cfg.sync.reconcile_types\", \"scope_type\": \"global\", \"scope_id\": 0, \"value\": {\"SR\": {\"sale\": [\"memo\"]}}}'::jsonb, " +
                    "jsonb_build_object('key', 'cfg.sync.reconcile_types', 'scope_type', 'role', 'scope_id', (SELECT ordinal FROM app.role_def WHERE role = 'AMO'), 'value', '{\"outlet\": [\"visit\"]}'::jsonb)), " +
                    "'reconcile change ' || s, 1, id FROM app.app_user, (VALUES ('scheduled'), ('applied')) v(s) WHERE username = 'aron.system'",
            )
        }
        old.flyway().migrate()
        old.connect().use { c ->
            val open = "SELECT value::text FROM app.cfg_value WHERE key = 'cfg.sync.reconcile_types' AND config_version = 3 AND reason LIKE 'V0024%' "
            assertEquals("""{"SR.qc": ["qc_line"], "SR.sale": ["memo"], "AMO.outlet": ["visit"]}""", c.scalar("$open AND reason LIKE '%current' "))
            assertEquals("""{"SR.sale": ["memo", "memo_line"]}""", c.scalar("$open AND reason LIKE '%future'"))
            assertEquals("""{"SR.stock": ["stock_movement"]}""", c.scalar("$open AND reason LIKE '%role bare'"))
            assertEquals("0", c.scalar("$open AND reason LIKE '%already flat'".replace("SELECT value::text", "SELECT count(*)")))
            assertEquals("0", c.scalar("$open AND reason LIKE '%closed'".replace("SELECT value::text", "SELECT count(*)")))
            // No value of the key valid now or later still has the nested shape (a not-yet-valid row is rewritten, not
            // left as a closed stub the scheduled list would still show); the history keeps its own shape.
            assertEquals(emptyList(), c.column(
                "SELECT v.reason FROM app.cfg_value v WHERE v.key = 'cfg.sync.reconcile_types' " +
                    "AND EXISTS (SELECT 1 FROM jsonb_each(v.value) e WHERE jsonb_typeof(e.value) = 'object') AND (v.effective_to IS NULL OR v.effective_to > now())",
            ))
            assertEquals("t", c.scalar("SELECT count(*) = 1 AND bool_and(effective_to IS NULL AND superseded_in_version IS NULL) FROM app.cfg_value WHERE reason LIKE '%future'"))
            assertEquals("t", c.scalar("SELECT tgenabled = 'O' FROM pg_trigger WHERE tgname = 'cfg_value_immutable'"))      // guard back on
            assertEquals("2", c.scalar("SELECT count(*) FROM app.cfg_value v, jsonb_each(v.value) e WHERE v.reason = 'closed' AND jsonb_typeof(e.value) = 'object'"))
            // The reshaped global row starts exactly where the replaced one ends and keeps its end.
            assertEquals("t", c.scalar(
                "SELECT o.effective_to = n.effective_from AND o.superseded_in_version = 3 AND n.effective_to = (SELECT effective_from FROM app.cfg_value WHERE reason LIKE '%future') " +
                    "FROM app.cfg_value o, app.cfg_value n WHERE o.reason = 'current' AND n.reason LIKE 'V0024%current'",
            ))
            assertEquals("V0024: cfg.sync.reconcile_types reshaped to the flat ROLE.row shape (docs/24 s14a R17)", c.scalar("SELECT summary FROM app.cfg_version WHERE config_version = 3"))
            assertEquals(
                listOf("applied|{\"SR\": {\"sale\": [\"memo\"]}}", "scheduled|{\"SR.sale\": [\"memo\"]}"),
                c.column("SELECT status || '|' || (items -> 1 -> 'value')::text FROM app.cfg_change ORDER BY status"),
            )
            assertEquals("90", c.scalar("SELECT items -> 0 ->> 'value' FROM app.cfg_change WHERE status = 'scheduled'"))
            assertEquals("""{"AMO.outlet": ["visit"]}""", c.scalar("SELECT (items -> 2 -> 'value')::text FROM app.cfg_change WHERE status = 'scheduled'"))
            assertEquals("""{"outlet": ["visit"]}""", c.scalar("SELECT (items -> 2 -> 'value')::text FROM app.cfg_change WHERE status = 'applied'"))
        }
    }

    private val device = "INSERT INTO app.device (device_uuid, flavour, app_package, device_owner, lockdown_level, public_key_jwk, public_key_thumbprint, app_signing_cert_sha256) " +
        "VALUES (gen_random_uuid(), 'sr', 'com.aktcl.aron.sr', true, 'dev', '{\"kty\": \"EC\"}', '%s', decode(repeat('ab', 32), 'hex'))"

    @Test
    fun anOlderPhoneHasUnknownIntegrityAndAReportedOneIsChecked() = TestPostgres.createDatabase().use { old ->
        Flyway.configure().configuration(old.flyway().configuration).target("24").load().migrate()
        old.connect().use { it.exec(device.format("tp-old")) }
        old.flyway().migrate()
        old.connect().use { c ->
            assertEquals("t|t|t|t", c.scalar(
                "SELECT concat_ws('|', root_hints IS NULL, root_hints_at IS NULL, integrity_unavailable_reason IS NULL, integrity_unavailable_at IS NULL) FROM app.device",
            ))
            c.tx {
                exec("UPDATE app.device SET root_hints = '{}', root_hints_at = now()")
                assertEquals("0", scalar("SELECT cardinality(root_hints) FROM app.device"))      // clean, not unknown
                exec("UPDATE app.device SET root_hints = '{su_binary,hook_framework}', integrity_unavailable_reason = 'offline', integrity_unavailable_at = now()")
                assertEquals("23514", refused("UPDATE app.device SET root_hints = '{magisk}'"))
                assertEquals("23514", refused("UPDATE app.device SET root_hints = ARRAY(SELECT 'su_binary' FROM generate_series(1, 17))"))
                assertEquals("23514", refused("UPDATE app.device SET root_hints_at = NULL"))
                assertEquals("23514", refused("UPDATE app.device SET integrity_unavailable_reason = 'busy'"))
                assertEquals("23514", refused("UPDATE app.device SET integrity_unavailable_at = NULL"))
                exec(device.format("tp-new"))
                assertEquals("23514", refused("UPDATE app.device SET integrity_unavailable_reason = 'timeout' WHERE public_key_thumbprint = 'tp-new'"))
            }
            assertEquals(
                listOf("t", "t", "t", "t"),
                c.column("SELECT convalidated FROM pg_constraint WHERE conrelid = 'app.device'::regclass AND conname IN " +
                    "('device_root_hints_check', 'device_integrity_unavailable_reason_check', 'device_integrity_unavailable_pair', 'device_root_hints_pair')"),
            )
        }
    }
}
