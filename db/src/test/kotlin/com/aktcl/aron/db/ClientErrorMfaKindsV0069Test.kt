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
 * V0069: app.client_error (docs/requests/backend-core-client-error-table.md, F-SYS-032).
 * V0070/V0071: security_event kinds mfa_enrol and mfa_verify_failure (docs/requests/backend-core-mfa-security-events.md).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClientErrorMfaKindsV0069Test {
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

    private fun Connection.report(uuid: String, message: String = "TypeError: x is undefined", page: String? = "/reports/sales"): Int =
        prepareStatement(
            "INSERT INTO app.client_error (error_uuid, user_id, source, occurred_at, business_date, page, message, stack, build) " +
                "SELECT ?::uuid, id, 'web', TIMESTAMPTZ '2026-10-08 10:00+06', DATE '2026-10-08', ?, ?, 'at f (main.js:1:2)', 'web-2026.10.08' " +
                "FROM app.app_user WHERE username = 'sr1001' ON CONFLICT (error_uuid) DO NOTHING",
        ).use {
            it.setString(1, uuid)
            it.setString(2, page)
            it.setString(3, message)
            it.executeUpdate()
        }

    @Test
    fun clientErrorIsStoredOncePerErrorUuidAndNeverChanges() = db.connect().use { c ->
        c.tx {
            val uuid = "0b7e4a1c-5d2f-4c3a-9e8b-7a6f5e4d3c01"
            assertEquals(1, report(uuid))
            assertEquals(0, report(uuid), "a repeat stores nothing")
            assertEquals("1", scalar("SELECT count(*) FROM app.client_error WHERE error_uuid = '$uuid'"))
            assertEquals("23514", refused("INSERT INTO app.client_error (error_uuid, user_id, source, occurred_at, business_date, message) SELECT gen_random_uuid(), user_id, 'phone', occurred_at, business_date, message FROM app.client_error LIMIT 1"))
            assertEquals("23514", refused("INSERT INTO app.client_error (error_uuid, user_id, source, occurred_at, business_date, message) SELECT gen_random_uuid(), user_id, 'web', occurred_at, business_date, repeat('m', 501) FROM app.client_error LIMIT 1"))
            assertEquals("23514", refused("INSERT INTO app.client_error (error_uuid, user_id, source, occurred_at, business_date, message, page) SELECT gen_random_uuid(), user_id, 'web', occurred_at, business_date, message, repeat('p', 201) FROM app.client_error LIMIT 1"))
            assertEquals("23502", refused("INSERT INTO app.client_error (error_uuid, source, occurred_at, business_date, message) SELECT gen_random_uuid(), 'web', occurred_at, business_date, message FROM app.client_error LIMIT 1"))
            assertEquals(DENIED, refused("UPDATE app.client_error SET message = 'changed' WHERE error_uuid = '$uuid'"))
            assertEquals(DENIED, refused("TRUNCATE app.client_error"))
            // The API login cannot delete; retention deletes old rows by business date as the worker login.
            exec("SET LOCAL ROLE api_rw")
            assertEquals(DENIED, refused("DELETE FROM app.client_error WHERE business_date < DATE '2026-10-09'"))
            exec("SET LOCAL ROLE worker_rw")
            exec("DELETE FROM app.client_error WHERE business_date < DATE '2026-10-09'")
            exec("RESET ROLE")
            assertEquals("0", scalar("SELECT count(*) FROM app.client_error"))
        }
    }

    @Test
    fun onlyTheApiInsertsAndOnlyTheWorkerDeletes() = db.connect().use { c ->
        fun can(role: String, priv: String) = c.scalar("SELECT has_table_privilege('$role', 'app.client_error', '$priv')::text")
        assertEquals("true|true|false|false", listOf("SELECT", "INSERT", "UPDATE", "DELETE").joinToString("|") { can("api_rw", it)!! })
        assertEquals("true|false|false|true", listOf("SELECT", "INSERT", "UPDATE", "DELETE").joinToString("|") { can("worker_rw", it)!! })
        val outsiders = listOf("web_ro", "bi_reader", "auth_rw", "pii_reader", "support_ro")
        assertEquals(outsiders.joinToString("|") { "false|false" }, outsiders.joinToString("|") { can(it, "SELECT") + "|" + can(it, "INSERT") })
    }

    @Test
    fun securityEventTakesTheMfaKinds() = db.connect().use { c ->
        c.tx {
            for (kind in listOf("mfa_enrol", "mfa_verify_failure", "login_failure", "otp_view")) {
                exec("INSERT INTO app.security_event (at, kind) VALUES (TIMESTAMPTZ '2026-10-08 10:00+06', '$kind')")
            }
            assertEquals("4", scalar("SELECT count(*) FROM app.security_event"))
            assertEquals("23514", refused("INSERT INTO app.security_event (at, kind) VALUES (TIMESTAMPTZ '2026-10-08 10:00+06', 'mfa_other')"))
        }
        assertEquals("t", c.scalar("SELECT convalidated::text::char FROM pg_constraint WHERE conname = 'security_event_kind_check'"))
    }

    private companion object {
        const val DENIED = "42501"
    }
}
