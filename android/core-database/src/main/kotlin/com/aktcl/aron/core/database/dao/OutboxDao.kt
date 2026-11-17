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

    /** True when any of [uuids] was acked and purged from this phone (a replay of it must not be captured again). */
    @Query("SELECT EXISTS(SELECT 1 FROM purged_uuid WHERE client_uuid IN (:uuids))")
    abstract suspend fun anyPurged(uuids: List<String>): Boolean

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
     *
     * D-AGATE-06 (owner rule 3, check-out submits the sales): the day's `day_submit` is HELD until every EARLIER record of its business
     * date is in a final state (acked, rejected or quarantined: nothing earlier is still pending or in flight), and the `attendance_event`
     * record (the check-out) is HELD until the date's `day_submit` is final. So the server never sees a day's own records as late, nor a
     * check-out without its submit, whatever order retries, a bisect-isolated family or a slow batch put the rows in. A row that is held
     * stays pending and is picked again as soon as what it waits for is final.
     */
    @Query(
        """SELECT * FROM outbox o WHERE o.state = 'pending' AND o.family_uuid NOT IN (:excludedFamilies)
           AND NOT (o.record_type = 'day_submit' AND EXISTS (
               SELECT 1 FROM outbox e WHERE e.business_date = o.business_date AND e.seq < o.seq AND e.state IN ('pending', 'in_flight')))
           AND NOT (o.record_type = 'attendance_event' AND EXISTS (
               SELECT 1 FROM outbox d WHERE d.record_type = 'day_submit' AND d.business_date = o.business_date AND d.seq < o.seq AND d.state IN ('pending', 'in_flight')))
           ORDER BY CASE WHEN o.attempts >= :skipAfter THEN 1 ELSE 0 END, o.seq LIMIT :limit""",
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
        """UPDATE outbox SET state = :state, last_code = :code, seen_at = NULL,
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
    suspend fun applyAck(clientUuid: String, state: String, code: String?, serverId: Long?, at: String, messageKey: String? = null): Int {
        require(state in ACK_TARGETS) { "not an outbox state an ack may set: $state" }
        // D-AGATE-04: the ack's `message_key` is kept with the code; it is the server's own words for this row
        return applyAckUnchecked(clientUuid, state, code, messageKey, serverId, at)
    }

    @Query(
        """UPDATE outbox SET state = :state, last_code = :code, last_message_key = :messageKey, server_id = COALESCE(:serverId, server_id),
           seen_at = CASE WHEN state = :state AND IFNULL(last_code, '') = IFNULL(:code, '') THEN seen_at ELSE NULL END,
           acked_at = CASE WHEN :state = 'acked' THEN :at ELSE acked_at END,
           batch_uuid = CASE WHEN :state = 'pending' THEN NULL ELSE batch_uuid END
           WHERE client_uuid = :clientUuid AND state IN ('in_flight', 'pending')""",
    )
    protected abstract suspend fun applyAckUnchecked(clientUuid: String, state: String, code: String?, messageKey: String?, serverId: Long?, at: String): Int

    /**
     * F-SYS-072 release (BC-53; bounded rounds in SyncEngine): rows the server quarantined with [code] (`device_integrity_failed`, from the time
     * it quarantined every unsigned or badly signed header) go back to pending with the same client_uuid, payload and sig,
     * so the server's registry answers them by uuid (released, or duplicate) and nothing is stored twice. `last_code` is
     * kept, so the signer never signs a row that already went out. Returns the rows released.
     */
    @Query("UPDATE outbox SET state = 'pending', batch_uuid = NULL, attempts = 0, seen_at = NULL WHERE state = 'quarantined' AND last_code = :code")
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

    @Query("SELECT MIN(business_date) FROM outbox")
    abstract suspend fun earliestBusinessDate(): String?

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

    /** D-AGATE-06: rows of [businessDate] still waiting to be sent (pending or in flight): the day_submit's real `pending_count`. */
    @Query("SELECT COUNT(*) FROM outbox WHERE business_date = :businessDate AND state IN ('pending', 'in_flight')")
    abstract suspend fun unsentOn(businessDate: String): Int

    /** D-AGATE-04 round 2: the rows of [businessDate] per state, for the Sales Submit status line (scoped to the date on screen, not the whole outbox). */
    @Query("SELECT state, COUNT(*) AS count FROM outbox WHERE business_date = :businessDate GROUP BY state")
    abstract suspend fun stateCountsOn(businessDate: String): List<StateCount>

    /** D-AGATE-06: rows of [businessDate] in [state] (rejected, quarantined): the day_submit's real counts. */
    @Query("SELECT COUNT(*) FROM outbox WHERE business_date = :businessDate AND state = :state")
    abstract suspend fun countOnInState(businessDate: String, state: String): Int

    /** Rows of a business date not yet answered by the server, per record type (the "not sent" reason of reconciliation). */
    @Query("SELECT record_type AS recordType, COUNT(*) AS count FROM outbox WHERE business_date = :businessDate AND state IN ('pending', 'in_flight') GROUP BY record_type ORDER BY record_type")
    abstract suspend fun unsentCounts(businessDate: String): List<TypeCount>

    /** Rows the server refused or holds for review: stored only in its sync_rejected / sync_quarantine, never in the day's tables. */
    @Query("SELECT client_uuid FROM outbox WHERE state IN ('rejected', 'quarantined') AND business_date >= :fromDate")
    abstract suspend fun refusedUuids(fromDate: String): List<String>

    /**
     * D-AGATE-04 (device defect D1) round 2: the refused or held rows the Sales Submit list shows, NEWEST first, only the columns the
     * list needs (never the payloads: the rows are not purged, so a `SELECT *` over all of them would grow without bound), at most
     * [limit] of them. The banner counts are COUNT queries ([countRefused], [countSeenRefused]); the list is paged by the caller.
     * Every business date is included (not only the last 14 days). The outbox purge removes ACKED rows only; refused rows stay until
     * the support file has taken them or a later build cleans them, so this read must stay cheap whatever their number.
     */
    @Query(
        """$REFUSED_SELECT WHERE o.state IN ('rejected', 'quarantined') ORDER BY o.seq DESC LIMIT :limit""",
    )
    abstract suspend fun refusedList(limit: Int): List<RefusedRowLite>

    /** Queued rows the server answered with a retryable code, light (no payload), oldest first, bounded: the "will be sent again" rows. */
    @Query("$REFUSED_SELECT WHERE o.state = 'pending' AND o.last_code IN (:codes) ORDER BY o.seq LIMIT :limit")
    abstract suspend fun queuedWithCodes(codes: List<String>, limit: Int): List<RefusedRowLite>

    /** Refused or held rows of [state], seen or not: the true figure the day_submit and the gates read (never filtered by "seen"). */
    @Query("SELECT COUNT(*) FROM outbox WHERE state = :state")
    abstract suspend fun countRefused(state: String): Int

    /** Refused or held rows of [state] the rep marked as seen: a SCREEN matter only, subtracted from the banner and never from the gates. */
    @Query("SELECT COUNT(*) FROM outbox WHERE state = :state AND seen_at IS NOT NULL")
    abstract suspend fun countSeenRefused(state: String): Int

    /**
     * D-AGATE-04 round 2 (D1-3): "seen" applies to ONE outcome, the (state, last_code) it was marked at. Every path that changes either
     * clears it, so a new outcome (a later discard, a re-refusal, a release) is shown again.
     */
    @Query("UPDATE outbox SET seen_at = NULL WHERE client_uuid = :clientUuid")
    abstract suspend fun clearSeen(clientUuid: String): Int

    /**
     * D-AGATE-04: marks a refused record as seen by the rep, and the rest of its FAMILY that is refused (the list shows a family as one
     * entry). It only ever SETS `seen_at` on rows that are rejected or quarantined: the row, its payload and its signature stay exactly as
     * they are, and nothing is deleted. A record the PHONE gave up on (`retry_exhausted`) never reached the server and cannot be marked
     * seen (D1-2): its only action is Send again. Returns the rows marked.
     */
    @Query(
        """UPDATE outbox SET seen_at = :at WHERE state IN ('rejected', 'quarantined') AND seen_at IS NULL AND IFNULL(last_code, '') != 'retry_exhausted'
           AND family_uuid = (SELECT family_uuid FROM outbox WHERE client_uuid = :clientUuid)""",
    )
    abstract suspend fun markRefusedSeen(clientUuid: String, at: String): Int

    /** Undo of [markRefusedSeen] for the record's family: the entry returns to the count and the main list (a mis-tap is not permanent). */
    @Query("UPDATE outbox SET seen_at = NULL WHERE seen_at IS NOT NULL AND family_uuid = (SELECT family_uuid FROM outbox WHERE client_uuid = :clientUuid)")
    abstract suspend fun markRefusedUnseen(clientUuid: String): Int

    /**
     * D-AGATE-04 (ruling 7, round 2 D1-5): "send again" for a record the phone itself gave up on (`rejected(retry_exhausted)`) acts on its
     * whole FAMILY (the parent and every child that was exhausted), so a memo never goes up without its lines nor lines before their memo.
     * Each goes back to pending with the SAME client_uuid, payload and sig and `attempts` reset, in the engine's normal family order
     * (by seq), exactly as [resendAckedSince] does: the server's registry answers each by uuid (stored, or duplicate), so it keeps it once.
     * Only rows rejected with that code move; a server rejection is never resent from here. Returns the rows re-queued.
     */
    @Query(
        """UPDATE outbox SET state = 'pending', batch_uuid = NULL, attempts = 0, seen_at = NULL
           WHERE state = 'rejected' AND last_code = 'retry_exhausted'
           AND family_uuid = (SELECT family_uuid FROM outbox WHERE client_uuid = :clientUuid)""",
    )
    abstract suspend fun requeueRetryExhausted(clientUuid: String): Int

    /**
     * D-AGATE-04: the outcome code of every refused, held or answered-and-requeued row (state, `last_code`, `message_key`, attempts, date),
     * for the support file. No payload and no personal data: ids, a type, a state and a code.
     */
    @Query(
        """SELECT client_uuid AS clientUuid, record_type AS recordType, business_date AS businessDate, state, last_code AS lastCode,
           last_message_key AS lastMessageKey, attempts FROM outbox WHERE state IN ('rejected', 'quarantined') OR last_code IS NOT NULL ORDER BY seq DESC LIMIT :limit""",
    )
    abstract suspend fun codeSummaries(limit: Int): List<OutboxCodeRow>

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

/** One outbox row's outcome code for the support file (D-AGATE-04). */
data class OutboxCodeRow(
    val clientUuid: String, val recordType: String, val businessDate: String, val state: String,
    val lastCode: String?, val lastMessageKey: String?, val attempts: Int,
)

/** One refused or held row as the Sales Submit list needs it: no payload (D-AGATE-04 round 2). */
data class RefusedRowLite(
    val seq: Long, val clientUuid: String, val recordType: String, val familyUuid: String, val rank: Int, val businessDate: String,
    val state: String, val lastCode: String?, val lastMessageKey: String?, val seenAt: String?, val attempts: Int,
    /** From the domain tables by uuid (a memo, a memo line, a memo discount, a visit), never from `payload_json`. */
    val memoNo: String? = null, val outletId: Long? = null,
)

private const val REFUSED_SELECT =
    """SELECT o.seq AS seq, o.client_uuid AS clientUuid, o.record_type AS recordType, o.family_uuid AS familyUuid, o.`rank` AS `rank`, o.business_date AS businessDate,
       o.state AS state, o.last_code AS lastCode, o.last_message_key AS lastMessageKey, o.seen_at AS seenAt, o.attempts AS attempts,
       COALESCE(m.memo_no, lm.memo_no, dm.memo_no) AS memoNo, COALESCE(m.outlet_id, lm.outlet_id, dm.outlet_id, v.outlet_id) AS outletId
       FROM outbox o
       LEFT JOIN memo m ON o.record_type = 'memo' AND m.client_uuid = o.client_uuid
       LEFT JOIN memo_line l ON o.record_type = 'memo_line' AND l.client_uuid = o.client_uuid
       LEFT JOIN memo lm ON lm.client_uuid = l.memo_client_uuid
       LEFT JOIN memo_discount d ON o.record_type = 'memo_discount' AND d.client_uuid = o.client_uuid
       LEFT JOIN memo dm ON dm.client_uuid = d.memo_client_uuid
       LEFT JOIN visit v ON o.record_type = 'visit' AND v.client_uuid = o.client_uuid"""

/** Rows per outbox state. */
data class StateCount(val state: String, val count: Int)
