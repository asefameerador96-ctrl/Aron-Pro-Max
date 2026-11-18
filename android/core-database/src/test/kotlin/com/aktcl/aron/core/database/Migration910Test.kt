package com.aktcl.aron.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Version 9 to 10 adds `outbox.last_message_key` and `outbox.seen_at` (D-AGATE-04), `outbox.parent_uuid` and the local `phone_mark` table (D-AGATE-18), and keeps every outbox row exactly
 * as it was. Version 10 has not shipped: these are part of it, not a version 11.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class Migration910Test {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AronDatabase::class.java)

    @Test fun version9To10KeepsEveryOutboxRowAndAddsTwoEmptyColumns() {
        val states = listOf("pending", "in_flight", "acked", "rejected", "quarantined")
        helper.createDatabase("migration-910", 9).use { db ->
            states.forEachIndexed { i, st ->
                db.execSQL(
                    """INSERT INTO outbox (seq, client_uuid, record_type, family_uuid, rank, business_date, payload_json, payload_sha256, state, attempts, last_code, created_at)
                       VALUES (${i + 1}, '00000000-0000-4000-8000-00000000000$i', 'memo_line', '00000000-0000-4000-8000-0000000000f0', 1, '2026-11-12', '{"payload":{"qty":$i}}', 'sha$i',
                       '$st', $i, ${if (st == "rejected") "'price_invalid'" else "null"}, '2026-11-12T04:00:00.000Z')""",
                )
            }
        }
        helper.runMigrationsAndValidate("migration-910", 10, true).use { db ->
            db.query("SELECT COUNT(*) FROM outbox").use { c -> assertTrue(c.moveToFirst()); assertEquals(5, c.getInt(0)) }
            db.query("SELECT state, attempts, payload_json, last_code, last_message_key, seen_at FROM outbox ORDER BY seq").use { c ->
                states.forEachIndexed { i, st ->
                    assertTrue(c.moveToNext())
                    assertEquals(st, c.getString(0)); assertEquals(i, c.getInt(1)); assertEquals("""{"payload":{"qty":$i}}""", c.getString(2))
                    assertEquals(if (st == "rejected") "price_invalid" else null, c.getString(3))
                    assertTrue("the new columns start empty", c.isNull(4) && c.isNull(5))
                }
            }
            // the new columns accept a value and a row with them still reads back
            // D-AGATE-18: the parent link starts empty (no parent known holds nothing back) and accepts a value; the local marker table exists, is empty and takes a row
            db.query("SELECT COUNT(*) FROM outbox WHERE parent_uuid IS NOT NULL").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
            db.execSQL("UPDATE outbox SET parent_uuid = 'p' WHERE seq = 2")
            db.query("SELECT parent_uuid FROM outbox WHERE seq = 2").use { c -> assertTrue(c.moveToFirst()); assertEquals("p", c.getString(0)) }
            db.query("SELECT COUNT(*) FROM phone_mark").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
            db.execSQL("INSERT INTO phone_mark (kind, business_date, outlet_id, created_at) VALUES ('call_ended', '2026-11-12', 7, 't')")
            db.query("SELECT kind, outlet_id FROM phone_mark").use { c -> assertTrue(c.moveToFirst()); assertEquals("call_ended", c.getString(0)); assertEquals(7, c.getInt(1)) }
            db.execSQL("UPDATE outbox SET last_message_key = 'k', seen_at = 't' WHERE seq = 4")
            db.query("SELECT last_message_key, seen_at FROM outbox WHERE seq = 4").use { c -> assertTrue(c.moveToFirst()); assertEquals("k", c.getString(0)); assertEquals("t", c.getString(1)) }
        }
    }
}
