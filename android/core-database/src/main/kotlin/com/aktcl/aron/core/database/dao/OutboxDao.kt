package com.aktcl.aron.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aktcl.aron.core.database.entity.OutboxEntity
import com.aktcl.aron.core.database.entity.OutboxState

/** Outbox access (docs/24 s4.5, s4.6), driven by core-sync's SyncEngine (F-SYS-008). */
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

    /**
     * The next rows to send (s4.6): commit order, except that a row already sent [skipAfter] times or more moves behind the
     * others (poison-row skip-ahead), and families in [excludedFamilies] (isolated by a bisect in this run) are left out.
     */
    @Query(
        """SELECT * FROM outbox WHERE state = 'pending' AND family_uuid NOT IN (:excludedFamilies)
           ORDER BY CASE WHEN attempts >= :skipAfter THEN 1 ELSE 0 END, seq LIMIT :limit""",
    )
    abstract suspend fun nextSendable(limit: Int, skipAfter: Int, excludedFamilies: List<String>): List<OutboxEntity>

    /**
     * Counts one definitive failure of a row (a retryable reject, or its family isolated as the cause of a batch failure).
     * `attempts` drives skip-ahead and `row_max_retries` (s4.6); re-batching after a split or a release never counts.
     */
    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE client_uuid = :clientUuid")
    abstract suspend fun countFailure(clientUuid: String): Int

    /** Rows waiting for an upload: pending or in a batch not yet answered (`X-Pending-Rows`, the periodic-work rule). */
    @Query("SELECT COUNT(*) FROM outbox WHERE state IN ('pending', 'in_flight')")
    abstract suspend fun unsentCount(): Int

    /**
     * Applies a server resolution of a quarantined record (s4.5): [state] is `acked` (accepted, accepted_with_fix) or
     * `rejected` (discarded). Only a row still quarantined moves.
     */
    @Query(
        """UPDATE outbox SET state = :state, last_code = :code,
           acked_at = CASE WHEN :state = 'acked' THEN :at ELSE acked_at END
           WHERE client_uuid = :clientUuid AND state = 'quarantined'""",
    )
    abstract suspend fun applyResolution(clientUuid: String, state: String, code: String, at: String): Int

    /** Rows of a persisted batch, resent first after a kill (s4.6). */
    @Query("SELECT * FROM outbox WHERE batch_uuid = :batchUuid AND state = 'in_flight' ORDER BY seq")
    abstract suspend fun inFlight(batchUuid: String): List<OutboxEntity>

    @Query("SELECT DISTINCT batch_uuid FROM outbox WHERE state = 'in_flight' AND batch_uuid IS NOT NULL")
    abstract suspend fun inFlightBatches(): List<String>

    /** Puts rows into a batch. Sending is not a failure, so `attempts` is not touched here (see [countFailure]). */
    @Query("UPDATE outbox SET state = 'in_flight', batch_uuid = :batchUuid WHERE seq IN (:seqs) AND state = 'pending'")
    abstract suspend fun markInFlight(batchUuid: String, seqs: List<Long>): Int

    /** F-SYS-072: stores a record's `sig` once; a row already signed keeps its first signature (retries must be identical). */
    @Query("UPDATE outbox SET sig = :sig WHERE seq = :seq AND sig IS NULL")
    abstract suspend fun setSig(seq: Long, sig: String): Int

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

    /**
     * F-SYS-072 release (BC-53; bounded rounds in SyncEngine): rows the server quarantined with [code] (`device_integrity_failed`, from the time
     * it quarantined every unsigned or badly signed header) go back to pending with the same client_uuid, payload and sig,
     * so the server's registry answers them by uuid (released, or duplicate) and nothing is stored twice. `last_code` is
     * kept, so the signer never signs a row that already went out. Returns the rows released.
     */
    @Query("UPDATE outbox SET state = 'pending', batch_uuid = NULL, attempts = 0 WHERE state = 'quarantined' AND last_code = :code")
    abstract suspend fun releaseQuarantined(code: String): Int

    /**
     * F-SYS-047: rows acked at or after [since] (phone time of the ack, RFC 3339 UTC) go back to pending with the same
     * client_uuid, payload and sig after a server restore, so the server's registry answers each by uuid (stored again, or
     * duplicate) and nothing is stored twice. `last_code` becomes `resync`: the signer never signs a row that already went
     * out, and every batch carrying such a row goes with trigger `resync` (the server's backdate allowance). Returns the rows put back.
     */
    @Query(
        """UPDATE outbox SET state = 'pending', batch_uuid = NULL, attempts = 0, last_code = 'resync'
           WHERE state = 'acked' AND acked_at IS NOT NULL AND acked_at >= :since""",
    )
    abstract suspend fun resendAckedSince(since: String): Int

    /**
     * F-SYS-080: the acked rows of business dates [from]..[to] (`YYYY-MM-DD`), the phone side of the sync digest. A row the
     * server answered `accepted` or `duplicate`, or a quarantine a reviewer released, is acked; the server counts the same
     * (registry `accepted` or `voided`).
     */
    @Query("SELECT business_date AS businessDate, record_type AS recordType, client_uuid AS clientUuid FROM outbox WHERE state = 'acked' AND business_date >= :from AND business_date <= :to")
    abstract suspend fun ackedForDigest(from: String, to: String): List<DigestRow>

    /**
     * F-SYS-080: the acked rows of ([businessDate], [recordType]) whose client_uuid starts with one of [digits] (lowercase
     * hex) go back to pending with the same uuid, payload and sig, as [resendAckedSince] does; `last_code` becomes
     * `digest_resend` (the batch trigger). Returns the rows put back.
     */
    @Query(
        """UPDATE outbox SET state = 'pending', batch_uuid = NULL, attempts = 0, last_code = 'digest_resend'
           WHERE state = 'acked' AND business_date = :businessDate AND record_type = :recordType AND substr(lower(client_uuid), 1, 1) IN (:digits)""",
    )
    abstract suspend fun resendDigestBuckets(businessDate: String, recordType: String, digits: List<String>): Int

    @Query("SELECT COUNT(*) FROM outbox WHERE state = 'quarantined' AND last_code = :code")
    abstract suspend fun countQuarantined(code: String): Int

    /** Rows carrying [code] in any state that is not final-acked: quarantined, or released and pending / in flight. */
    @Query("SELECT COUNT(*) FROM outbox WHERE state IN ('quarantined', 'pending', 'in_flight') AND last_code = :code")
    abstract suspend fun countWithCode(code: String): Int

    /** Committed rows per record type for a business date: the device side of reconciliation (s4.12). */
    @Query("SELECT record_type AS recordType, COUNT(*) AS count FROM outbox WHERE business_date = :businessDate GROUP BY record_type ORDER BY record_type")
    abstract suspend fun committedCounts(businessDate: String): List<TypeCount>

    /** Rows of a business date not yet answered by the server, per record type (the "not sent" reason of reconciliation). */
    @Query("SELECT record_type AS recordType, COUNT(*) AS count FROM outbox WHERE business_date = :businessDate AND state IN ('pending', 'in_flight') GROUP BY record_type ORDER BY record_type")
    abstract suspend fun unsentCounts(businessDate: String): List<TypeCount>

    /** Rows the server refused or holds for review: stored only in its sync_rejected / sync_quarantine, never in the day's tables. */
    @Query("SELECT client_uuid FROM outbox WHERE state IN ('rejected', 'quarantined') AND business_date >= :fromDate")
    abstract suspend fun refusedUuids(fromDate: String): List<String>

    /** When the server last answered a row of this type and date (null when none is answered). */
    @Query("SELECT MAX(acked_at) FROM outbox WHERE business_date = :businessDate AND record_type = :recordType AND acked_at IS NOT NULL")
    abstract suspend fun lastAckedAt(businessDate: String, recordType: String): String?

    /** Rows per state, for the support file (F-SYS-021 / F-SR-006). */
    @Query("SELECT state AS recordType, COUNT(*) AS count FROM outbox GROUP BY state")
    abstract suspend fun countsByState(): List<TypeCount>

    /** Payloads of rows the server has not accepted (pending, in flight, rejected, quarantined), oldest first, exactly as stored. */
    @Query("SELECT payload_json FROM outbox WHERE state <> 'acked' ORDER BY seq LIMIT :limit")
    abstract suspend fun unsentPayloads(limit: Int): List<String>

    /** Payloads of rows acked since [since] (ISO), newest first: the re-sync window of the support file. */
    @Query("SELECT payload_json FROM outbox WHERE state = 'acked' AND acked_at >= :since ORDER BY seq DESC LIMIT :limit")
    abstract suspend fun recentAckedPayloads(since: String, limit: Int): List<String>

    /** The newest ack of any row: "last sync" in the support file and the screen. */
    @Query("SELECT MAX(acked_at) FROM outbox WHERE acked_at IS NOT NULL")
    abstract suspend fun lastAckedAtAny(): String?

    private companion object {
        val ACK_TARGETS = setOf(OutboxState.ACKED, OutboxState.REJECTED, OutboxState.QUARANTINED, OutboxState.PENDING)
    }
}

data class TypeCount(val recordType: String, val count: Int)

/** One acked row as the sync digest counts it (F-SYS-080). */
data class DigestRow(val businessDate: String, val recordType: String, val clientUuid: String)
