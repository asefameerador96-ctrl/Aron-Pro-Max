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

/** Version 6 to 7 adds `purged_uuid` (the replay tombstones) and keeps every row. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class Migration67Test {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AronDatabase::class.java)

    @Test fun version6To7KeepsEveryRowAndAddsPurgedUuid() {
        helper.createDatabase("migration-67", 6).use { db ->
            db.execSQL(
                """INSERT INTO outbox (client_uuid, record_type, family_uuid, rank, business_date, payload_json, payload_sha256, state,
                   batch_uuid, attempts, created_at, sig) VALUES ('6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f', 'visit', 'f', 0, '2026-10-05', '{}', 'h',
                   'pending', null, 0, 't', 's1')""",
            )
        }
        helper.runMigrationsAndValidate("migration-67", 7, true).use { db ->
            db.query("SELECT COUNT(*) FROM outbox").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
            var pk: String? = null
            db.query("PRAGMA table_info(`purged_uuid`)").use { c ->
                while (c.moveToNext()) if (c.getInt(c.getColumnIndexOrThrow("pk")) == 1) pk = c.getString(c.getColumnIndexOrThrow("name"))
            }
            assertEquals("client_uuid", pk)
        }
    }
}
