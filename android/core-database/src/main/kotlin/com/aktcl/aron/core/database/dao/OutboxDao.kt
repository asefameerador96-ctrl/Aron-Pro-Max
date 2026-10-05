package com.aktcl.aron.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aktcl.aron.core.database.entity.OutboxEntity
import com.aktcl.aron.core.database.entity.OutboxState

/** Outbox access (docs/24 s4.5, s4.6). The sync worker loop that drives it is Day-2 work (F-SYS-008). */
@Dao
abstract class OutboxDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(rows: List<OutboxEntity>): List<Long>

    @Query("SELECT * FROM outbox WHERE client_uuid = :clientUuid")
    abstract suspend fun byClientUuid(clientUuid: String): OutboxEntity?

    @Query("SELECT COUNT(*) FROM outbox WHERE state = :state")
    abstract suspend fun countInState(state: String): Int

    /** The next rows to send in commit order (s4.2 rule 1). */
    @Query("SELECT * FROM outbox WHERE state = 'pending' ORDER BY seq LIMIT :limit")
    abstract suspend fun nextPending(limit: Int): List<OutboxEntity>

    /** Rows of a persisted batch, resent first after a kill (s4.6). */
    @Query("SELECT * FROM outbox WHERE batch_uuid = :batchUuid AND state = 'in_flight' ORDER BY seq")
    abstract suspend fun inFlight(batchUuid: String): List<OutboxEntity>

    @Query("SELECT DISTINCT batch_uuid FROM outbox WHERE state = 'in_flight' AND batch_uuid IS NOT NULL")
    abstract suspend fun inFlightBatches(): List<String>

    @Query("UPDATE outbox SET state = 'in_flight', batch_uuid = :batchUuid, attempts = attempts + 1 WHERE seq IN (:seqs) AND state = 'pending'")
    abstract suspend fun markInFlight(batchUuid: String, seqs: List<Long>): Int

    @Query("UPDATE outbox SET state = 'pending', batch_uuid = NULL, last_code = :lastCode WHERE batch_uuid = :batchUuid AND state = 'in_flight'")
    abstract suspend fun returnToPending(batchUuid: String, lastCode: String?): Int

    /**
     * Applies one ack (s4.5): `acked` (server accepted or duplicate), `rejected` or `quarantined` (terminal for the phone),
     * or `pending` for a retryable reject. Any other value (for example the server's `accepted`) is refused, so a row can
     * never be stranded outside the state machine. Rows already acked, rejected or quarantined never move back.
     */
    suspend fun applyAck(clientUuid: String, state: String, code: String?, serverId: Long?, at: String): Int {
        require(state in ACK_TARGETS) { "not an outbox state an ack may set: $state" }
        return applyAckUnchecked(clientUuid, state, code, serverId, at)
    }

    @Query(
        """UPDATE outbox SET state = :state, last_code = :code, server_id = COALESCE(:serverId, server_id),
           acked_at = CASE WHEN :state = 'acked' THEN :at ELSE acked_at END,
           batch_uuid = CASE WHEN :state = 'pending' THEN NULL ELSE batch_uuid END
           WHERE client_uuid = :clientUuid AND state IN ('in_flight', 'pending')""",
    )
    protected abstract suspend fun applyAckUnchecked(clientUuid: String, state: String, code: String?, serverId: Long?, at: String): Int

    /** Committed rows per record type for a business date: the device side of reconciliation (s4.12). */
    @Query("SELECT record_type AS recordType, COUNT(*) AS count FROM outbox WHERE business_date = :businessDate GROUP BY record_type ORDER BY record_type")
    abstract suspend fun committedCounts(businessDate: String): List<TypeCount>

    /** Acked rows are kept 7 days for reconciliation and reprint, then purged (s4.5); unacked rows are never deleted. */
    @Query("DELETE FROM outbox WHERE state = 'acked' AND acked_at IS NOT NULL AND acked_at < :before")
    abstract suspend fun purgeAckedBefore(before: String): Int

    private companion object {
        val ACK_TARGETS = setOf(OutboxState.ACKED, OutboxState.REJECTED, OutboxState.QUARANTINED, OutboxState.PENDING)
    }
}

data class TypeCount(val recordType: String, val count: Int)
