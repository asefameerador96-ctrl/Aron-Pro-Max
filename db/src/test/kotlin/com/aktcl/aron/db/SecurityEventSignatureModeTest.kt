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
 * V0053: cfg.sec.record_signature_mode (docs/requests/backend-core-record-signature-mode-key.md).
 * V0054: app.security_event (docs/requests/backend-core-security-event-table.md).
 * V0055: three field-app keys (docs/requests/backend-core-app-cfg-keys.md).
 * V0056/V0057: ingest_registry.flags (docs/requests/backend-core-resync-late-flag.md); V0059/V0060 two more flags.
 * V0058: working-day window keys (docs/requests/backend-core-working-day-window-keys.md).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityEventSignatureModeTest {
    private lateinit var db: TestDatabase

    @BeforeAll
    fun setUp() { db = TestPostgres.createDatabase().migrated() }

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
    fun signatureModeIsRegisteredAsTheRequestAsks() = db.connect().use { c ->
        assertEquals(
            "sec|S|enum|\"record\"|{\"enum\": [\"off\", \"record\", \"enforce\"]}|{global}|3|both|cfg.edit.security|enum_order",
            c.scalar(
                "SELECT concat_ws('|', area, kind, value_type, default_value::text, bounds::text, scope_levels::text, risk_class, delivery, editor_permission, restrictive_dir) " +
                    "FROM app.cfg_key WHERE key = 'cfg.sec.record_signature_mode'",
            ),
        )
        // The backend test's own registration stays harmless once the row exists (ON CONFLICT DO NOTHING).
        c.exec(
            "INSERT INTO app.cfg_key (key, area, kind, value_type, default_value, bounds, bounds_rule, scope_levels, risk_class, risk_rule, effect, delivery, requires_ack, future_dated_only, editor_permission, description_en) " +
                "VALUES ('cfg.sec.record_signature_mode', 'device', 'S', 'enum', '\"record\"'::jsonb, '{\"enum\": [\"off\", \"record\", \"enforce\"]}'::jsonb, NULL, ARRAY['global']::text[], 3, NULL, 'B', 'both', false, false, 'cfg.edit.security', 'x') ON CONFLICT (key) DO NOTHING",
        )
        assertEquals("sec", c.scalar("SELECT area FROM app.cfg_key WHERE key = 'cfg.sec.record_signature_mode'"))
    }

    @Test
    fun securityEventsAreAppendOnlyAndWrittenByTheApiAndAuthRoles() = db.connect().use { c ->
        c.tx {
            exec("SET LOCAL ROLE auth_rw")
            exec("INSERT INTO app.security_event (at, kind, user_id, detail) VALUES ('2026-10-07T08:00:00Z', 'login_failure', 99999, '{\"username_hash\": \"ab12\", \"failures\": 3}')")
            exec("RESET ROLE")
            exec("SET LOCAL ROLE api_rw")
            exec("INSERT INTO app.security_event (at, kind, device_uuid, request_id) VALUES ('2026-10-07T08:01:00Z', 'refresh_reuse', gen_random_uuid(), gen_random_uuid())")
            assertEquals("2", scalar("SELECT count(*) FROM app.security_event"))
            assertEquals("42501", refused("UPDATE app.security_event SET kind = 'lockout'"))
            assertEquals("42501", refused("DELETE FROM app.security_event"))
            exec("RESET ROLE")
            exec("SET LOCAL ROLE worker_rw")
            assertEquals("1", scalar("SELECT count(*) FROM app.security_event WHERE kind = 'refresh_reuse'"))   // alerts
            assertEquals("42501", refused("INSERT INTO app.security_event (at, kind) VALUES ('2026-10-07T08:02:00Z', 'lockout')"))
            exec("RESET ROLE")
            // The owner is refused too: the trail is append-only for everyone.
            assertEquals("42501", refused("UPDATE app.security_event SET detail = '{}'"))
            assertEquals("23514", refused("INSERT INTO app.security_event (at, kind) VALUES ('2026-10-07T08:03:00Z', 'password_reset')"))
            assertEquals("23514", refused("INSERT INTO app.security_event (at, kind, detail) VALUES ('2026-10-07T08:03:00Z', 'lockout', '[1]')"))
        }
        assertEquals("f|f|f", c.scalar(
            "SELECT concat_ws('|', has_table_privilege('support_ro', 'app.security_event', 'SELECT'), has_table_privilege('web_ro', 'app.security_event', 'SELECT'), " +
                "has_table_privilege('auth_rw', 'app.security_event', 'UPDATE'))",
        )?.replace("false", "f")?.replace("true", "t"))
    }

    @Test
    fun fieldAppKeysFollowDocs19() = db.connect().use { c ->
        assertEquals(
            listOf(
                "cfg.app.image_cache_mb|40|{\"max\": 70, \"min\": 10}|{global}|device|cfg.edit.ops",
                "cfg.app.local_history_days|7|{\"max\": 30, \"min\": 1}|{global}|device|cfg.edit.ops",
                "cfg.app.outbox_keep_days|3|{\"max\": 14, \"min\": 1}|{global}|device|cfg.edit.ops",
            ),
            c.column(
                "SELECT concat_ws('|', key, default_value::text, bounds::text, scope_levels::text, delivery, editor_permission) FROM app.cfg_key " +
                    "WHERE key IN ('cfg.app.image_cache_mb', 'cfg.app.local_history_days', 'cfg.app.outbox_keep_days') ORDER BY key",
            ),
        )
        // The default pair satisfies docs/17's re-sync rule: keep_days x 24 >= resync_window_h + 24.
        assertEquals("t", c.scalar("SELECT ((SELECT default_value::int FROM app.cfg_key WHERE key = 'cfg.app.outbox_keep_days') * 24 >= " +
            "(SELECT default_value::int FROM app.cfg_key WHERE key = 'cfg.sync.resync_window_h') + 24)::text").let { if (it == "true") "t" else it })
    }

    @Test
    fun aRegistryRowCarriesOnlyKnownFlagsAndTheApiSetsThem() = db.connect().use { c ->
        c.tx {
            exec("INSERT INTO app.app_user (username, full_name, role) VALUES ('sr7001', 'SR', 'SR')")
            val ins = "INSERT INTO app.ingest_registry (client_uuid, record_type, payload_sha256, status, user_id, first_batch_uuid, business_date%s) " +
                "SELECT gen_random_uuid(), 'memo', decode(repeat('ab', 32), 'hex'), 'accepted', id, gen_random_uuid(), '2026-10-01'%s FROM app.app_user WHERE username = 'sr7001'"
            exec("SET LOCAL ROLE api_rw")
            exec(ins.format("", ""))
            assertEquals("{}", scalar("SELECT flags::text FROM app.ingest_registry"))
            exec("UPDATE app.ingest_registry SET flags = '{resync_late}'")
            exec(ins.format(", flags", ", '{resync_late}'"))
            assertEquals("2", scalar("SELECT count(*) FROM app.ingest_registry WHERE 'resync_late' = ANY (flags)"))
            exec("RESET ROLE")
            assertEquals("23514", refused(ins.format(", flags", ", '{late}'")))
            exec(ins.format(", flags", ", '{resync_late,config_stamp_regress,checkout_too_early}'"))      // V0059
            assertEquals("23514", refused(ins.format(", flags", ", '{resync_late,config_stamp_regress,checkout_too_early,resync_late}'")))
            assertEquals("23514", refused(ins.format(", flags", ", '{resync_late,resync_late}'")))
        }
    }

    @Test
    fun workingDayWindowKeysAreRegistered() = db.connect().use { c ->
        assertEquals(
            listOf(
                "cfg.bundle.stale_max_cal_days_ceiling|7|{\"max\": 14, \"min\": 3}|device|2",
                "cfg.calendar.break_overrides|[]|{\"max_items\": 20}|both|2",
                "cfg.calendar.prefetch_next_working_day|true|{}|both|1",
                "cfg.calendar.window_unit|\"calendar\"|{\"enum\": [\"calendar\", \"working_days\"]}|both|3",
            ),
            c.column(
                "SELECT concat_ws('|', key, default_value::text, bounds::text, delivery, risk_class) FROM app.cfg_key " +
                    "WHERE key IN ('cfg.calendar.window_unit', 'cfg.bundle.stale_max_cal_days_ceiling', 'cfg.calendar.break_overrides', 'cfg.calendar.prefetch_next_working_day') ORDER BY key",
            ),
        )
        // backend-core counts config_stamp_regress rows per device and business date through this partial index.
        assertEquals("1", c.scalar("SELECT count(*) FROM pg_indexes WHERE schemaname = 'app' AND indexname = 'ingest_registry_config_stamp_regress'"))
    }
}
