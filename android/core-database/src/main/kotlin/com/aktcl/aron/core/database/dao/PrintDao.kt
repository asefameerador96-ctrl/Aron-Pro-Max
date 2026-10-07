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

    @Query("SELECT * FROM print_event WHERE memo_client_uuid = :doc OR ref_client_uuid = :doc ORDER BY rowid")
    suspend fun eventsOf(doc: String): List<PrintEventEntity>

    /** Printed copies of a document; a void slip or a due receipt that names it is not a copy (ReprintPolicy.NOT_COPIES). */
    @Query(
        """SELECT COUNT(*) FROM print_event WHERE (memo_client_uuid = :doc OR ref_client_uuid = :doc) AND outcome = 'printed'
           AND document_kind NOT IN ('void_slip', 'due_receipt')""",
    )
    suspend fun printedCount(doc: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertJob(row: PrintJobEntity)
    @Query("DELETE FROM print_job WHERE event_client_uuid = :uuid") suspend fun deleteJob(uuid: String)
    @Query("SELECT * FROM print_job ORDER BY rowid") suspend fun jobs(): List<PrintJobEntity>
    @Query("SELECT paper_out FROM print_job WHERE event_client_uuid = :uuid") suspend fun jobPaperOut(uuid: String): Boolean?

    @Query(
        """SELECT COUNT(*) FROM print_job WHERE (memo_client_uuid = :doc OR ref_client_uuid = :doc) AND paper_out = 1
           AND event_client_uuid != :except AND document_kind NOT IN ('void_slip', 'due_receipt')""",
    )
    suspend fun otherPaperOutJobs(doc: String, except: String): Int

    @Query("SELECT COUNT(*) FROM memo WHERE client_uuid = :uuid") suspend fun memoCount(uuid: String): Int
    @Query("UPDATE memo SET printed_at = :at WHERE client_uuid = :uuid AND printed_at IS NULL") suspend fun markMemoPrinted(uuid: String, at: String): Int
    @Query("UPDATE memo SET print_count = :count WHERE client_uuid = :uuid") suspend fun setMemoPrintCount(uuid: String, count: Int): Int
    @Query("UPDATE memo SET printed_at = NULL WHERE client_uuid = :uuid") suspend fun clearMemoPrinted(uuid: String): Int
    @Query("SELECT printed_at FROM memo WHERE client_uuid = :uuid") suspend fun memoPrintedAt(uuid: String): String?
    /** A stock slip covers one Save: every movement committed with the named row (same date, capture time and kind). */
    @Query(
        """UPDATE stock_movement SET slip_printed = :printed WHERE
           business_date = (SELECT business_date FROM stock_movement WHERE client_uuid = :uuid)
           AND captured_at = (SELECT captured_at FROM stock_movement WHERE client_uuid = :uuid)
           AND kind = (SELECT kind FROM stock_movement WHERE client_uuid = :uuid)""",
    )
    suspend fun setSlipPrinted(uuid: String, printed: Boolean): Int
}
