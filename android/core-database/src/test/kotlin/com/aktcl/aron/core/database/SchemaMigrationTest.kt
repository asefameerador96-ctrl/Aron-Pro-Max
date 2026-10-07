package com.aktcl.aron.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * N-016 migration test: version 1 is created from the exported schema JSON (android/core-database/schemas) and every
 * table exists; every device-originated table is keyed by a unique client_uuid; the outbox has a unique client_uuid.
 * Later versions add a Migration and a migrateAndValidate step here (docs/24 s5.2: destructive migration forbidden).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SchemaMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AronDatabase::class.java)

    private val expectedTables = setOf(
        "route", "outlet", "sku", "geo_fix", "attendance_event", "stock_movement", "visit", "visit_close", "memo", "memo_line",
        "memo_discount", "qc_line", "outbox", "sync_meta",
    )

    @Test
    fun version1To2AddsTheReferenceTablesAndKeepsEveryRow() {
        helper.createDatabase("migration-12", 1).use { db ->
            db.execSQL(
                """INSERT INTO outbox (client_uuid, record_type, family_uuid, rank, business_date, payload_json, payload_sha256, state,
                   attempts, created_at) VALUES ('6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f', 'memo', 'f', 1, '2026-10-05', '{}', 'h', 'pending', 0, 't')""",
            )
            db.execSQL("INSERT INTO sync_meta (`key`, value) VALUES ('bundle_version', '2026-10-05:3')")
        }
        helper.runMigrationsAndValidate("migration-12", 2, true).use { db ->
            db.query("SELECT state FROM outbox").use { c -> assertTrue(c.moveToFirst()); assertEquals("pending", c.getString(0)) }
            db.query("SELECT value FROM sync_meta WHERE `key` = 'bundle_version'").use { c -> assertTrue(c.moveToFirst()) }
            for (t in listOf("price", "config_value", "bundle_section")) db.query("SELECT COUNT(*) FROM `$t`").use { c -> assertTrue(c.moveToFirst()) }
        }
    }

    @Test
    fun version1CreatesEveryTableWithAUniqueClientUuid() {
        helper.createDatabase("migration-test", 1).use { db ->
            val tables = mutableSetOf<String>()
            db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c -> while (c.moveToNext()) tables += c.getString(0) }
            assertTrue("missing tables: ${expectedTables - tables}", tables.containsAll(expectedTables))

            for (table in AronDatabase.DEVICE_TABLES) {
                var pk: String? = null
                var notNull = false
                db.query("PRAGMA table_info(`$table`)").use { c ->
                    while (c.moveToNext()) if (c.getInt(c.getColumnIndexOrThrow("pk")) == 1) {
                        pk = c.getString(c.getColumnIndexOrThrow("name"))
                        notNull = c.getInt(c.getColumnIndexOrThrow("notnull")) == 1
                    }
                }
                assertEquals("$table must be keyed by client_uuid", "client_uuid", pk)
                assertTrue("$table.client_uuid must be NOT NULL", notNull)
            }
            assertTrue("outbox.client_uuid must be unique", uniqueIndexColumns(db, "outbox").contains(listOf("client_uuid")))
            assertTrue("memo_no must be unique", uniqueIndexColumns(db, "memo").contains(listOf("memo_no")))
            assertTrue("one close per visit", uniqueIndexColumns(db, "visit_close").contains(listOf("visit_client_uuid")))
        }
    }

    private fun uniqueIndexColumns(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): List<List<String>> {
        val names = mutableListOf<String>()
        db.query("PRAGMA index_list(`$table`)").use { c ->
            while (c.moveToNext()) if (c.getInt(c.getColumnIndexOrThrow("unique")) == 1) names += c.getString(c.getColumnIndexOrThrow("name"))
        }
        return names.map { idx ->
            val cols = mutableListOf<String>()
            db.query("PRAGMA index_info(`$idx`)").use { c -> while (c.moveToNext()) cols += c.getString(c.getColumnIndexOrThrow("name")) }
            cols
        }
    }

    @Test
    fun theExportedSchemaMatchesTheCompiledEntities() {
        // The file is created from the exported JSON; opening it with the compiled Room database runs Room's identity-hash
        // check, which throws when the entities and the exported schema differ.
        helper.createDatabase("identity-test", 2).close()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = androidx.room.Room.databaseBuilder(context, AronDatabase::class.java, "identity-test").build()
        db.openHelper.writableDatabase
        db.close()
    }
}
