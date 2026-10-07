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
import kotlin.test.assertTrue

/** V0049/V0050 (AUD-DA-05): NID, TIN and trade licence as envelope-encrypted bytea; app.pii_key; grants; the no-data-loss guard. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutletPiiTest {
    private lateinit var db: TestDatabase

    private val geo = """
        INSERT INTO app.wing (code, name) VALUES ('W1', 'Wing 1');
        INSERT INTO app.division (code, name, wing_id) SELECT 'D1', 'Division 1', id FROM app.wing;
        INSERT INTO app.territory (code, name, division_id) SELECT 'T1', 'Territory 1', id FROM app.division;
        INSERT INTO app.zone (code, name, territory_id) SELECT 'Z1', 'Zone 1', id FROM app.territory;
        INSERT INTO app.cluster (zone_id, name) SELECT id, 'Bazar' FROM app.zone;
    """.trimIndent()

    private fun outlet(code: String, extra: String = "", values: String = "") =
        "INSERT INTO app.outlet (code, name, owner_name, zone_id, cluster_id, channel$extra) " +
            "SELECT '$code', 'Shop', 'Owner', z.id, c.id, 'GT'$values FROM app.zone z, app.cluster c"

    @BeforeAll
    fun setUp() {
        db = TestPostgres.createDatabase().migrated()
        db.connect().use { it.exec(geo) }
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

    // key_id 1 + 12-byte nonce + 1 byte ciphertext + 16-byte tag = 31 bytes
    private val ct = "'\\x0001' || decode(repeat('ab', 29), 'hex')"
    private val dek = "decode(repeat('cd', 40), 'hex')"

    @Test
    fun outletCarriesOnlyTheEncryptedColumns() = db.connect().use { c ->
        assertEquals(
            listOf("nid_enc|bytea", "pii_key_id|smallint", "tin_enc|bytea", "trade_license_enc|bytea"),
            c.column(
                "SELECT column_name || '|' || data_type FROM information_schema.columns WHERE table_schema = 'app' AND table_name = 'outlet' " +
                    "AND column_name IN ('nid', 'tin', 'trade_license', 'nid_enc', 'tin_enc', 'trade_license_enc', 'pii_key_id') ORDER BY 1",
            ),
        )
        assertEquals(
            """{"SR": {"exclude": ["nid_enc", "tin_enc", "trade_license_enc", "pii_key_id"]}}""",
            c.scalar("SELECT default_value::text FROM app.cfg_key WHERE key = 'cfg.bundle.outlet_fields'"),
        )
    }

    @Test
    fun ciphertextNeedsItsKeyAndAPlausibleLength() = db.connect().use { c ->
        c.tx {
            exec("INSERT INTO app.pii_key (key_id, wrapped_dek, kv_key_name, kv_key_version) VALUES (1, $dek, 'aron-pii', 'v1')")
            exec(outlet("O-1", ", nid_enc, pii_key_id", ", $ct, 1"))
            assertEquals("23514", refused(outlet("O-2", ", nid_enc", ", $ct")))                                  // ciphertext without key
            assertEquals("23514", refused(outlet("O-3", ", pii_key_id", ", 1")))                                  // key without ciphertext
            assertEquals("23514", refused(outlet("O-4", ", tin_enc, pii_key_id", ", '\\x0001'::bytea, 1")))     // too short for GCM
            assertEquals("23503", refused(outlet("O-5", ", tin_enc, pii_key_id", ", $ct, 9")))                    // unknown key
        }
    }

    @Test
    fun keysAreNeverDeletedOneIsActiveAndRetiringIsOneWay() = db.connect().use { c ->
        c.tx {
            exec("INSERT INTO app.pii_key (key_id, wrapped_dek, kv_key_name, kv_key_version) VALUES (1, $dek, 'aron-pii', 'v1')")
            assertEquals("23505", refused("INSERT INTO app.pii_key (key_id, wrapped_dek, kv_key_name, kv_key_version) VALUES (2, $dek, 'aron-pii', 'v1')"))
            exec("UPDATE app.pii_key SET wrapped_dek = $dek, kv_key_version = 'v2', rewrapped_at = now() WHERE key_id = 1")   // rotation re-wraps
            assertEquals("42501", refused("UPDATE app.pii_key SET key_id = 3"))
            assertEquals("42501", refused("DELETE FROM app.pii_key"))
            exec("UPDATE app.pii_key SET retired_at = now() WHERE key_id = 1")
            exec("INSERT INTO app.pii_key (key_id, wrapped_dek, kv_key_name, kv_key_version) VALUES (2, $dek, 'aron-pii', 'v2')")
            assertEquals("42501", refused("UPDATE app.pii_key SET retired_at = NULL WHERE key_id = 1"))
            assertEquals("23514", refused("INSERT INTO app.pii_key (key_id, wrapped_dek, kv_key_name, kv_key_version, algorithm) VALUES (3, $dek, 'k', 'v', 'AES-128-CBC')"))
        }
    }

    @Test
    fun onlyTheApiSeesTheWrappedKeys() = db.connect().use { c ->
        fun can(role: String, priv: String) = c.scalar("SELECT has_table_privilege('$role', 'app.pii_key', '$priv')::text")
        assertEquals(listOf("true", "true", "true", "false"), listOf("SELECT", "INSERT", "UPDATE", "DELETE").map { can("api_rw", it) })
        for (r in listOf("worker_rw", "jobs_rw", "web_ro", "bi_reader", "pii_reader", "support_ro", "auth_rw")) assertEquals("false", can(r, "SELECT"), r)
        assertEquals("false", c.scalar("SELECT has_column_privilege('pii_reader', 'app.outlet', 'nid_enc', 'SELECT')::text"))
    }

    @Test
    fun theMigrationRefusesToDropAPlaintextValue() {
        val old = TestPostgres.createDatabase()
        try {
            Flyway.configure().configuration(old.flyway().configuration).target("48").load().migrate()
            old.connect().use { it.exec(geo); it.exec(outlet("O-9", ", nid", ", '1234567890'")) }
            val e = assertFailsWith<Exception> { old.flyway().migrate() }
            assertTrue(generateSequence<Throwable>(e) { it.cause }.any { "holds NID, TIN or trade licence values" in (it.message ?: "") }, e.toString())
            old.connect().use { assertEquals("1234567890", it.scalar("SELECT nid FROM app.outlet")) }
        } finally { old.close() }
    }
}
