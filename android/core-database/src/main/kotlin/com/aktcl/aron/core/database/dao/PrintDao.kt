package com.aktcl.aron.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aktcl.aron.core.database.entity.PrintEventEntity
import com.aktcl.aron.core.database.entity.PrintJobEntity

/** Print events, pending print jobs and the printed flags they set (docs/17 s9.4; android-print request s1). */
@Dao
interface PrintDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertEvent(row: PrintEventEntity)
    @Query("SELECT COUNT(*) FROM print_event WHERE client_uuid = :uuid") suspend fun eventCount(uuid: String): Int

    @Query("SELECT * FROM print_event WHERE memo_client_uuid = :doc OR ref_client_uuid = :doc ORDER BY at_ms, rowid")
    suspend fun eventsOf(doc: String): List<PrintEventEntity>

    @Query("SELECT COUNT(*) FROM print_event WHERE (memo_client_uuid = :doc OR ref_client_uuid = :doc) AND outcome = 'printed'")
    suspend fun printedCount(doc: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertJob(row: PrintJobEntity)
    @Query("DELETE FROM print_job WHERE event_client_uuid = :uuid") suspend fun deleteJob(uuid: String)
    @Query("SELECT * FROM print_job ORDER BY at_ms, rowid") suspend fun jobs(): List<PrintJobEntity>

    @Query("SELECT COUNT(*) FROM print_job WHERE (memo_client_uuid = :doc OR ref_client_uuid = :doc) AND paper_out = 1 AND event_client_uuid != :except")
    suspend fun otherPaperOutJobs(doc: String, except: String): Int

    @Query("SELECT COUNT(*) FROM memo WHERE client_uuid = :uuid") suspend fun memoCount(uuid: String): Int
    @Query("UPDATE memo SET printed_at = :at WHERE client_uuid = :uuid AND printed_at IS NULL") suspend fun markMemoPrinted(uuid: String, at: String): Int
    @Query("UPDATE memo SET print_count = :count WHERE client_uuid = :uuid") suspend fun setMemoPrintCount(uuid: String, count: Int): Int
    @Query("UPDATE memo SET printed_at = NULL WHERE client_uuid = :uuid") suspend fun clearMemoPrinted(uuid: String): Int
    @Query("SELECT printed_at FROM memo WHERE client_uuid = :uuid") suspend fun memoPrintedAt(uuid: String): String?
    @Query("UPDATE stock_movement SET slip_printed = :printed WHERE client_uuid = :uuid") suspend fun setSlipPrinted(uuid: String, printed: Boolean): Int
}
