package com.aktcl.aron.core.database

import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aktcl.aron.core.database.record.RecordMapping
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Room 9 -> 10 (D-AGATE-04, -18, -19): the automatic part adds `outbox.last_message_key`, `outbox.seen_at`, `outbox.parent_uuid` and the `phone_mark` table. This spec fills the one new column
 * that has a deterministic source for rows queued BEFORE the upgrade: an AMO child's parent, named in its own payload (a distribution line's check, an assessment answer's assessment),
 * read from the `amo_record` row of the same client uuid by the SAME function that sets it for a new row ([RecordMapping.amoParent]). Without it such a line would lose the
 * Send-again guard (a refused check would leave the line offering Send again for ever). Only `outbox.parent_uuid` is written: never `payload_json`, `payload_sha256` or `sig`. A row whose
 * payload cannot be read keeps NULL (no guard, never a wrong parent), and nothing here can fail the upgrade.
 */
class Migration9To10 : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        val json = Json
        val updates = ArrayList<Pair<String, String>>()
        try {
            db.query(
                "SELECT o.client_uuid, a.record_type, a.payload_json FROM outbox o JOIN amo_record a ON a.client_uuid = o.client_uuid " +
                    "WHERE o.parent_uuid IS NULL AND a.record_type IN ('distribution_check_line', 'call_assessment_answer')",
            ).use { c ->
                while (c.moveToNext()) {
                    val parent = runCatching { RecordMapping.amoParent(c.getString(1), json.parseToJsonElement(c.getString(2)) as JsonObject) }.getOrNull()
                    if (parent != null) updates += c.getString(0) to parent
                }
            }
            for ((uuid, parent) in updates) db.execSQL("UPDATE outbox SET parent_uuid = ? WHERE client_uuid = ? AND parent_uuid IS NULL", arrayOf(parent, uuid))
        } catch (_: Exception) {
            // local metadata only: a failure leaves the NULLs, which never withhold or loop wrongly beyond the documented limit; the upgrade itself must not fail
        }
    }
}
