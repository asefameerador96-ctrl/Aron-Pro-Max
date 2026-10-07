package com.aktcl.aron.core.database.repo

import com.aktcl.aron.core.database.AronDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Local working-data purge (F-SYS-028, D-83, docs/17 s194): by business-date age, whole families only. A family (the
 * outbox `family_uuid`: a visit with its memo, lines and close, an attendance event, ...) is purged when every one of its
 * outbox rows is acked and its newest business date is older than the cutoff; one unsynced, rejected or quarantined row
 * keeps the whole family, so a pending line never loses its acked header. The family's capture rows, their fixes and
 * its outbox rows go in one transaction; reference data, `memo_counter`, `sync_meta` and print jobs are never touched.
 * Capture rows whose outbox row is already gone belong to a family that was purged whole (every capture row is written
 * with its outbox row in one transaction), so they go too once old enough. Never "on final submit".
 */
class LocalPurge(private val db: AronDatabase) {

    /** Rows deleted per table (for the support file and the tests). */
    data class Result(val byTable: Map<String, Int>) {
        val total: Int get() = byTable.values.sum()
    }

    /**
     * Purges what is older than [historyDays] business days before [today] (`YYYY-MM-DD`, the Dhaka business date from
     * trusted time): a row of [today] minus 8 days goes with the default 7; [today] minus 7 stays. Below 1 counts as 1.
     * [nowIso] is trusted time (RFC 3339 UTC, the format of `acked_at`).
     */
    suspend fun purge(today: String, nowIso: String, historyDays: Int = DEFAULT_HISTORY_DAYS, keepDays: Int = DEFAULT_KEEP_DAYS): Result = withContext(Dispatchers.IO) {
        val cutoff = LocalDate.parse(today).minusDays(historyDays.coerceAtLeast(1).toLong()).toString()
        // Acked rows stay [keepDays] after their ack (cfg.app.outbox_keep_days, docs/17 s194), whatever their business date:
        // a server restore flips rows acked since the restore point back to pending (docs/17 s4.13), so a phone back from a
        // week offline keeps what it just uploaded.
        val ackCutoff = java.time.Instant.parse(nowIso).minus(java.time.Duration.ofDays(keepDays.coerceAtLeast(1).toLong())).toString()
        val counts = LinkedHashMap<String, Int>()
        db.runInTransaction {
            val w = db.openHelper.writableDatabase
            w.execSQL("CREATE TEMP TABLE IF NOT EXISTS purge_uuid (client_uuid TEXT PRIMARY KEY)")
            w.execSQL("DELETE FROM purge_uuid")
            // Outbox rows of settled families.
            w.execSQL(
                """INSERT OR IGNORE INTO purge_uuid SELECT client_uuid FROM outbox WHERE family_uuid IN (
                     SELECT family_uuid FROM outbox GROUP BY family_uuid
                     HAVING SUM(CASE WHEN state = 'acked' THEN 0 ELSE 1 END) = 0 AND MAX(business_date) < ?
                       AND SUM(CASE WHEN acked_at IS NULL OR acked_at >= ? THEN 1 ELSE 0 END) = 0)""",
                arrayOf<Any>(cutoff, ackCutoff),
            )
            w.execSQL("CREATE TEMP TABLE IF NOT EXISTS purge_owner (client_uuid TEXT PRIMARY KEY)")
            w.execSQL("DELETE FROM purge_owner")
            for (t in CAPTURE_TABLES) {
                w.execSQL(
                    """INSERT OR IGNORE INTO purge_owner SELECT client_uuid FROM $t WHERE business_date < ? AND (
                         client_uuid IN (SELECT client_uuid FROM purge_uuid)
                         OR NOT EXISTS (SELECT 1 FROM outbox o WHERE o.client_uuid = $t.client_uuid))""",
                    arrayOf<Any>(cutoff),
                )
                val n = w.compileStatement("DELETE FROM $t WHERE client_uuid IN (SELECT client_uuid FROM purge_owner)").use { it.executeUpdateDelete() }
                if (n > 0) counts[t] = n
            }
            // Fixes go with the capture rows purged here, never on their own (a photo's fix has no capture-row owner).
            val fixes = w.compileStatement("DELETE FROM geo_fix WHERE owner_client_uuid IN (SELECT client_uuid FROM purge_owner)").use { it.executeUpdateDelete() }
            if (fixes > 0) counts["geo_fix"] = fixes
            val outbox = w.compileStatement("DELETE FROM outbox WHERE client_uuid IN (SELECT client_uuid FROM purge_uuid)").use { it.executeUpdateDelete() }
            if (outbox > 0) counts["outbox"] = outbox
            w.execSQL("DELETE FROM purge_uuid")
            w.execSQL("DELETE FROM purge_owner")
        }
        // Returns the freed pages when the file was created with auto_vacuum = INCREMENTAL; a no-op otherwise.
        runCatching { db.openHelper.writableDatabase.query("PRAGMA incremental_vacuum").use { c -> while (c.moveToNext()) Unit } }
        if (counts.isNotEmpty()) db.invalidationTracker.refreshVersionsAsync()
        Result(counts)
    }

    companion object {
        /** `cfg.app.local_history_days` default (docs/15 F-SYS-028). */
        const val DEFAULT_HISTORY_DAYS = 7
        const val CFG_HISTORY_DAYS = "cfg.app.local_history_days"
        /** `cfg.app.outbox_keep_days` default: an acked row stays this long after its ack. */
        const val DEFAULT_KEEP_DAYS = 7
        const val CFG_KEEP_DAYS = "cfg.app.outbox_keep_days"

        /** Capture tables with `client_uuid` and the capture envelope's `business_date`. */
        val CAPTURE_TABLES = listOf(
            "attendance_event", "stock_movement", "visit", "visit_close", "memo", "memo_line", "memo_discount", "qc_line",
            "due_collection", "visit_skip", "day_submit", "outlet_change_request", "task_event", "print_event",
        )
    }
}
