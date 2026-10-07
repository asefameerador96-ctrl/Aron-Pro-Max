package com.aktcl.aron.backend.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The data path end to end on a real PostgreSQL 16 (docs/24 s2.5): Hikari + the Flyway runner of the `migrate` role
 * over the db module's migrations + JDBI. A second migrate is a no-op.
 */
class DatabaseSmokeTest {
    @Test
    fun migratesAndQueries() {
        TestPg.dataSource().use { ds ->
            Migrator.migrate(ds)
            assertEquals(0, Migrator.migrate(ds), "second run applies nothing")
            val db = Database(ds)
            assertTrue(db.ping())
            val d = db.jdbi.withHandle<String, Exception> { h ->
                h.createQuery("select (timestamptz '2026-10-04 18:30:00+00' at time zone 'Asia/Dhaka')::date::text").mapTo(String::class.java).one()
            }
            assertEquals("2026-10-05", d)
        }
    }

    /** docs/requests/backend-migrate-connect-retries.md: the migrate role waits for its first connection, then fails. */
    @Test
    fun migrateRetriesTheFirstConnectionBeforeFailing() {
        Database.pool("jdbc:postgresql://127.0.0.1:1/none", null, null, 1, "aron-migrate", connectionTimeoutMs = 250).use { ds ->
            val started = System.nanoTime()
            val e = runCatching { Migrator.migrate(ds, connectRetries = 2, connectRetriesIntervalS = 1) }.exceptionOrNull()
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(e is org.flywaydb.core.internal.exception.FlywaySqlException, "a connect failure after the retries: $e")
            assertTrue(tookMs >= 1_000, "two retries at least 1 s apart took ${tookMs} ms")
        }
        assertEquals(30_000L, Migrator.MIGRATE_CONNECTION_TIMEOUT_MS)
    }

    @Test
    fun pingIsFalseWhenTheDatabaseIsDown() {
        Database.pool("jdbc:postgresql://127.0.0.1:1/none", null, null, 1, "down").use { ds -> assertFalse(Database(ds).ping()) }
    }
}
