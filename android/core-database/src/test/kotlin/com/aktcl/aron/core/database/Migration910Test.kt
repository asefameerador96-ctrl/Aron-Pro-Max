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

    @Test fun version9To10KeepsEveryRowAndAddsTheNewColumnsAndTable() {
        val states = listOf("pending", "in_flight", "acked", "rejected", "quarantined")
        helper.createDatabase("migration-910", 9).use { db ->
            states.forEachIndexed { i, st ->
                db.execSQL(
                    """INSERT INTO outbox (seq, client_uuid, record_type, family_uuid, rank, business_date, payload_json, payload_sha256, state, attempts, last_code, created_at)
                       VALUES (${i + 1}, '00000000-0000-4000-8000-00000000000$i', 'memo_line', '00000000-0000-4000-8000-0000000000f0', 1, '2026-11-12', '{"payload":{"qty":$i}}', 'sha$i',
                       '$st', $i, ${if (st == "rejected") "'price_invalid'" else "null"}, '2026-11-12T04:00:00.000Z')""",
                )
            }
            // D-AGATE-19: rows of the other tables a real version-9 phone holds survive untouched, and two queued AMO children get their parent from their own payload
            val meta = "'2026-11-12', '2026-11-12T04:00:00.000Z', 1, 1, null, 0, null, null, null, 0, 1"
            db.execSQL("INSERT INTO amo_record (client_uuid, record_type, family_uuid, rank, payload_json, business_date, captured_at, captured_elapsed_ms, boot_count, clock_offset_ms, captured_offline, route_id, acting_for_user_id, bundle_version, bundle_stale, config_version) VALUES ('$LINE', 'distribution_check_line', '$FAM', 2, '{\"check_client_uuid\":\"$CHECK\",\"sku_id\":5}', $meta)")
            db.execSQL("INSERT INTO amo_record (client_uuid, record_type, family_uuid, rank, payload_json, business_date, captured_at, captured_elapsed_ms, boot_count, clock_offset_ms, captured_offline, route_id, acting_for_user_id, bundle_version, bundle_stale, config_version) VALUES ('$ANSWER', 'call_assessment_answer', '$FAM', 2, '{\"assessment_client_uuid\":\"$ASSESS\",\"q\":1}', $meta)")
            db.execSQL("INSERT INTO amo_record (client_uuid, record_type, family_uuid, rank, payload_json, business_date, captured_at, captured_elapsed_ms, boot_count, clock_offset_ms, captured_offline, route_id, acting_for_user_id, bundle_version, bundle_stale, config_version) VALUES ('$OTHER', 'task', '$OTHER', 0, '{\"check_client_uuid\":\"not-a-parent\"}', $meta)")
            db.execSQL("INSERT INTO print_event (client_uuid, document_kind, memo_client_uuid, print_count, outcome, template_version, at_ms, business_date, captured_at, captured_elapsed_ms, boot_count, captured_offline, bundle_stale, config_version) VALUES ('$PRINT', 'memo', '$MEMO', 1, 'printed', 1, 5, '2026-11-12', 't', 1, 1, 0, 0, 1)")
            db.execSQL("INSERT INTO memo (client_uuid, visit_client_uuid, outlet_id, memo_no, memo_kind, committed_at, price_list_date, price_type, gross_mtk, offer_discount_mtk, drp_discount_mtk, qc_deduction_mtk, round_adj_mtk, net_mtk, paid_mtk, due_mtk, is_credit, line_count, discount_line_count, qc_line_count, offer_version_ids_json, rounding_mode, print_count, business_date, captured_at, captured_elapsed_ms, boot_count, captured_offline, bundle_stale, config_version) VALUES ('$MEMO', '$FAM', 9, 'M-1', 'sale', 't', '2026-11-12', 'outlet', 1000, 0, 0, 0, 0, 1000, 1000, 0, 0, 1, 0, 0, '[]', 'half_up', 1, '2026-11-12', 't', 1, 1, 0, 0, 1)")
            for ((uuid, type) in listOf(LINE to "distribution_check_line", ANSWER to "call_assessment_answer", OTHER to "task")) {
                db.execSQL("INSERT INTO outbox (seq, client_uuid, record_type, family_uuid, rank, business_date, payload_json, payload_sha256, state, attempts, created_at) VALUES (${100 + uuid.takeLast(1).toInt()}, '$uuid', '$type', '$FAM', 2, '2026-11-12', '{\"payload\":{}}', 'sha', 'pending', 0, 't')")
            }
        }
        helper.runMigrationsAndValidate("migration-910", 10, true).use { db ->
            db.query("SELECT COUNT(*) FROM outbox").use { c -> assertTrue(c.moveToFirst()); assertEquals(8, c.getInt(0)) }
            db.query("SELECT state, attempts, payload_json, last_code, last_message_key, seen_at FROM outbox WHERE seq < 100 ORDER BY seq").use { c ->
                states.forEachIndexed { i, st ->
                    assertTrue(c.moveToNext())
                    assertEquals(st, c.getString(0)); assertEquals(i, c.getInt(1)); assertEquals("""{"payload":{"qty":$i}}""", c.getString(2))
                    assertEquals(if (st == "rejected") "price_invalid" else null, c.getString(3))
                    assertTrue("the new columns start empty", c.isNull(4) && c.isNull(5))
                }
            }
            // D-AGATE-19: the AMO children queued before the upgrade have their parent (from their own payload); a record that names none keeps NULL; the payload bytes are unchanged
            db.query("SELECT client_uuid, parent_uuid, payload_json, sig FROM outbox WHERE seq >= 100 ORDER BY seq").use { c ->
                val got = HashMap<String, String?>()
                while (c.moveToNext()) { got[c.getString(0)] = c.getString(1); assertEquals("""{"payload":{}}""", c.getString(2)); assertTrue(c.isNull(3)) }
                assertEquals(CHECK, got[LINE]); assertEquals(ASSESS, got[ANSWER]); assertNull("a record that names no parent keeps NULL", got[OTHER])
            }
            db.query("SELECT memo_client_uuid, print_count FROM print_event WHERE client_uuid = '$PRINT'").use { c -> assertTrue(c.moveToFirst()); assertEquals(MEMO, c.getString(0)); assertEquals(1, c.getInt(1)) }
            db.query("SELECT memo_no, net_mtk FROM memo WHERE client_uuid = '$MEMO'").use { c -> assertTrue(c.moveToFirst()); assertEquals("M-1", c.getString(0)); assertEquals(1000, c.getInt(1)) }
            db.query("SELECT payload_json FROM amo_record WHERE client_uuid = '$LINE'").use { c -> assertTrue(c.moveToFirst()); assertTrue(c.getString(0).contains(CHECK)) }
            db.execSQL("UPDATE outbox SET parent_uuid = NULL WHERE seq >= 100")
            // the new columns accept a value and a row with them still reads back
            // D-AGATE-18: the parent link starts empty (no parent known holds nothing back) and accepts a value; the local marker table exists, is empty and takes a row
            db.query("SELECT COUNT(*) FROM outbox WHERE parent_uuid IS NOT NULL AND seq < 100").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
            db.execSQL("UPDATE outbox SET parent_uuid = 'p' WHERE seq = 2")
            db.query("SELECT parent_uuid FROM outbox WHERE seq = 2").use { c -> assertTrue(c.moveToFirst()); assertEquals("p", c.getString(0)) }
            db.query("SELECT COUNT(*) FROM phone_mark").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
            db.execSQL("INSERT INTO phone_mark (kind, business_date, outlet_id, created_at) VALUES ('call_ended', '2026-11-12', 7, 't')")
            db.query("SELECT kind, outlet_id FROM phone_mark").use { c -> assertTrue(c.moveToFirst()); assertEquals("call_ended", c.getString(0)); assertEquals(7, c.getInt(1)) }
            db.execSQL("UPDATE outbox SET last_message_key = 'k', seen_at = 't' WHERE seq = 4")
            db.query("SELECT last_message_key, seen_at FROM outbox WHERE seq = 4").use { c -> assertTrue(c.moveToFirst()); assertEquals("k", c.getString(0)); assertEquals("t", c.getString(1)) }
        }
    }

    private companion object {
        const val FAM = "00000000-0000-4000-8000-0000000000f0"
        const val CHECK = "00000000-0000-4000-8000-0000000000c1"
        const val ASSESS = "00000000-0000-4000-8000-0000000000a1"
        const val LINE = "00000000-0000-4000-8000-000000000071"
        const val ANSWER = "00000000-0000-4000-8000-000000000072"
        const val OTHER = "00000000-0000-4000-8000-000000000073"
        const val MEMO = "00000000-0000-4000-8000-0000000000e1"
        const val PRINT = "00000000-0000-4000-8000-0000000000b1"
    }
}
