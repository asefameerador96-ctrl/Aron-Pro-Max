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

/** Version 9 to 10 adds `geo_fix.signals_json` (D-OWNER-MDM-02) and keeps every existing fix and outbox row. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class Migration910Test {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AronDatabase::class.java)

    @Test fun version9To10KeepsFixesAndAddsAnEmptySignalsColumn() {
        helper.createDatabase("migration-910", 9).use { db ->
            db.execSQL(
                """INSERT INTO outbox (seq, client_uuid, record_type, family_uuid, rank, business_date, payload_json, payload_sha256, state, attempts, created_at)
                   VALUES (1, '00000000-0000-4000-8000-0000000000c1', 'attendance', '00000000-0000-4000-8000-0000000000c1', 0, '2026-11-12', '{}', 'sha', 'pending', 0, '2026-11-12T04:00:00.000Z')""",
            )
            db.execSQL(
                """INSERT INTO geo_fix (client_uuid, owner_client_uuid, purpose, fix_status, provider, is_mock, reused, device_owner, dev_options_enabled, adb_enabled, auto_time_enabled, mock_app_present)
                   VALUES ('00000000-0000-4000-8000-0000000000d1', '00000000-0000-4000-8000-0000000000c1', 'attendance', 'ok', 'fused', 1, 0, 0, 0, 0, 1, 0)""",
            )
        }
        helper.runMigrationsAndValidate("migration-910", 10, true).use { db ->
            db.query("SELECT state FROM outbox WHERE client_uuid = '00000000-0000-4000-8000-0000000000c1'").use { c -> assertTrue(c.moveToFirst()); assertEquals("pending", c.getString(0)) }
            db.query("SELECT fix_status, is_mock, signals_json FROM geo_fix WHERE client_uuid = '00000000-0000-4000-8000-0000000000d1'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("ok", c.getString(0)); assertEquals(1, c.getInt(1)); assertTrue(c.isNull(2))
            }
            db.execSQL("UPDATE geo_fix SET signals_json = '[\"mock_flag\"]' WHERE client_uuid = '00000000-0000-4000-8000-0000000000d1'")
            db.query("SELECT signals_json FROM geo_fix").use { c -> assertTrue(c.moveToFirst()); assertEquals("[\"mock_flag\"]", c.getString(0)) }
        }
    }
}
