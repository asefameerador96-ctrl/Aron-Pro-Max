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
 * Version 10 to 11 adds `geo_fix.signals_json` (D-OWNER-MDM-02; version 10 is the gates round's `outbox` columns) and keeps every existing fix and queued outbox row
 * (a queued payload and its signature never change). The direct 9 to 11 path is tested too: a phone that skipped a release must still arrive.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class Migration1011Test {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AronDatabase::class.java)

    private fun seed(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            """INSERT INTO outbox (seq, client_uuid, record_type, family_uuid, rank, business_date, payload_json, payload_sha256, state, attempts, created_at)
               VALUES (1, '00000000-0000-4000-8000-0000000000c1', 'attendance', '00000000-0000-4000-8000-0000000000c1', 0, '2026-11-12', '{"k":1}', 'sha', 'pending', 0, '2026-11-12T04:00:00.000Z')""",
        )
        db.execSQL(
            """INSERT INTO geo_fix (client_uuid, owner_client_uuid, purpose, fix_status, provider, is_mock, reused, device_owner, dev_options_enabled, adb_enabled, auto_time_enabled, mock_app_present)
               VALUES ('00000000-0000-4000-8000-0000000000d1', '00000000-0000-4000-8000-0000000000c1', 'attendance', 'ok', 'fused', 1, 0, 0, 0, 0, 1, 0)""",
        )
    }

    private fun check(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.query("SELECT state, payload_json FROM outbox WHERE client_uuid = '00000000-0000-4000-8000-0000000000c1'").use { c -> assertTrue(c.moveToFirst()); assertEquals("pending", c.getString(0)); assertEquals("{\"k\":1}", c.getString(1)) }
        db.query("SELECT fix_status, is_mock, signals_json FROM geo_fix WHERE client_uuid = '00000000-0000-4000-8000-0000000000d1'").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("ok", c.getString(0)); assertEquals(1, c.getInt(1)); assertTrue("an old fix has no codes: unknown, not clean", c.isNull(2))
        }
        db.execSQL("UPDATE geo_fix SET signals_json = '[]' WHERE client_uuid = '00000000-0000-4000-8000-0000000000d1'")
        db.query("SELECT signals_json FROM geo_fix").use { c -> assertTrue(c.moveToFirst()); assertEquals("[]", c.getString(0)) }
    }

    @Test fun version10To11KeepsQueuedRecordsAndFixesAndAddsAnEmptySignalsColumn() {
        helper.createDatabase("migration-1011", 10).use { seed(it) }
        helper.runMigrationsAndValidate("migration-1011", 11, true).use { check(it) }
    }

    @Test fun version9To11InOneStepKeepsThemToo() {
        helper.createDatabase("migration-911", 9).use { seed(it) }
        helper.runMigrationsAndValidate("migration-911", 11, true).use { check(it) }
    }
}
