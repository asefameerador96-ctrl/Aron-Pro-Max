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
 * V0124 (D-DB-GEO-VERSION-01, backend-reports request db-geo-version): app.geo_version goes up by one per statement that
 * changes a geography table, in the same transaction, never on a statement that changed nothing; only the trigger writes it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeoVersionTest {
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

    private fun Connection.version() = scalar("SELECT version FROM app.geo_version")!!.toLong()

    @Test
    fun everyChangingStatementOnAGeographyTableBumpsOnce() = db.connect().use { c ->
        c.tx {
            exec("SET LOCAL ROLE api_rw")                                          // the admin API writes the geography
            val v0 = version()
            exec("UPDATE app.zone SET name = name || ' x' WHERE code = 'Z-MIR'")
            assertEquals(v0 + 1, version())
            exec("UPDATE app.route SET name = name || ' x'")                       // many rows, one statement
            assertEquals(v0 + 2, version())
            exec("UPDATE app.route SET name = name WHERE false")                   // no row
            assertEquals(v0 + 2, version())
            exec("INSERT INTO app.territory (code, name, division_id) SELECT 'T-GV', 'Geo version', min(id) FROM app.division")
            assertEquals(v0 + 3, version())
            exec("RESET ROLE")                                                      // the owner deletes (api_rw has no DELETE)
            exec("DELETE FROM app.territory WHERE code = 'no such territory'")
            assertEquals(v0 + 3, version())
            exec("DELETE FROM app.territory WHERE code = 'T-GV'")
            assertEquals(v0 + 4, version())
            exec("SET LOCAL ROLE api_rw")
            for (t in listOf("wing", "division", "house")) {
                val before = version()
                exec("UPDATE app.$t SET name = name || ' y' WHERE id = (SELECT min(id) FROM app.$t)")
                assertEquals(before + 1, version(), t)
            }
            exec("UPDATE app.zone SET house_id = house_id WHERE code = 'Z-MIR'")   // touch_master moves updated_at: a write
            assertEquals(v0 + 8, version())
        }
        c.tx {
            val v0 = version()
            exec("UPDATE app.outlet SET name = name WHERE id = (SELECT min(id) FROM app.outlet)")   // not geography
            assertEquals(v0, version())
        }
    }

    @Test
    fun theApiAndTheWorkerReadItAndOnlyTheTriggerWritesIt() = db.connect().use { c ->
        c.tx {
            for (r in listOf("api_rw", "worker_rw")) {
                exec("SET LOCAL ROLE $r")
                assertEquals("1", scalar("SELECT count(*) FROM app.geo_version"), r)
                exec("RESET ROLE")
            }
            exec("SET LOCAL ROLE api_rw")
            assertEquals("42501", refused("UPDATE app.geo_version SET version = 1"))
            assertEquals("42501", refused("DELETE FROM app.geo_version"))
            assertEquals("42501", refused("INSERT INTO app.geo_version (id, version) VALUES (true, 1) ON CONFLICT DO NOTHING"))
            exec("RESET ROLE")
            exec("SET LOCAL ROLE worker_rw")
            assertEquals("42501", refused("UPDATE app.geo_version SET version = version + 1"))
            exec("RESET ROLE")
            exec("SET LOCAL ROLE bi_reader")
            assertEquals("42501", refused("SELECT version FROM app.geo_version"))
            exec("RESET ROLE")
            assertEquals("23514", refused("INSERT INTO app.geo_version (id) VALUES (false)"))     // one row only
        }
    }
}
