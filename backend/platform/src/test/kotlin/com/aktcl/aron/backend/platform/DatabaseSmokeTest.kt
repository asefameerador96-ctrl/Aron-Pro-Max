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

    @Test
    fun pingIsFalseWhenTheDatabaseIsDown() {
        Database.pool("jdbc:postgresql://127.0.0.1:1/none", null, null, 1, "down").use { ds -> assertFalse(Database(ds).ping()) }
    }
}
