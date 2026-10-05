package com.aktcl.aron.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aktcl.aron.core.database.entity.OutboxEntity

/** Outbox access (docs/24 s4.5, s4.6). The sync worker loop that drives it is Day-2 work (F-SYS-008). */
@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(rows: List<OutboxEntity>): List<Long>

    @Query("SELECT * FROM outbox WHERE client_uuid = :clientUuid")
    suspend fun byClientUuid(clientUuid: String): OutboxEntity?

    @Query("SELECT COUNT(*) FROM outbox WHERE state = :state")
    suspend fun countInState(state: String): Int

    /** The next rows to send in commit order (s4.2 rule 1). */
    @Query("SELECT * FROM outbox WHERE state = 'pending' ORDER BY seq LIMIT :limit")
    suspend fun nextPending(limit: Int): List<OutboxEntity>

    /** Rows of a persisted batch, resent first after a kill (s4.6). */
    @Query("SELECT * FROM outbox WHERE batch_uuid = :batchUuid AND state = 'in_flight' ORDER BY seq")
    suspend fun inFlight(batchUuid: String): List<OutboxEntity>

    @Query("SELECT DISTINCT batch_uuid FROM outbox WHERE state = 'in_flight' AND batch_uuid IS NOT NULL")
    suspend fun inFlightBatches(): List<String>

    @Query("UPDATE outbox SET state = 'in_flight', batch_uuid = :batchUuid, attempts = attempts + 1 WHERE seq IN (:seqs) AND state = 'pending'")
    suspend fun markInFlight(batchUuid: String, seqs: List<Long>): Int

    @Query("UPDATE outbox SET state = 'pending', batch_uuid = NULL, last_code = :lastCode WHERE batch_uuid = :batchUuid AND state = 'in_flight'")
    suspend fun returnToPending(batchUuid: String, lastCode: String?): Int

    /** Applies one ack: acked, rejected or quarantined (terminal for the phone), or back to pending when retryable. */
    @Query(
        """UPDATE outbox SET state = :state, last_code = :code, server_id = COALESCE(:serverId, server_id),
           acked_at = CASE WHEN :state = 'acked' THEN :at ELSE acked_at END,
           batch_uuid = CASE WHEN :state = 'pending' THEN NULL ELSE batch_uuid END
           WHERE client_uuid = :clientUuid AND state IN ('in_flight', 'pending')""",
    )
    suspend fun applyAck(clientUuid: String, state: String, code: String?, serverId: Long?, at: String): Int

    /** Committed rows per record type for a business date: the device side of reconciliation (s4.12). */
    @Query("SELECT record_type AS recordType, COUNT(*) AS count FROM outbox WHERE business_date = :businessDate GROUP BY record_type ORDER BY record_type")
    suspend fun committedCounts(businessDate: String): List<TypeCount>

    /** Acked rows are kept 7 days for reconciliation and reprint, then purged (s4.5); unacked rows are never deleted. */
    @Query("DELETE FROM outbox WHERE state = 'acked' AND acked_at IS NOT NULL AND acked_at < :before")
    suspend fun purgeAckedBefore(before: String): Int
}

data class TypeCount(val recordType: String, val count: Int)
