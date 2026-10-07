package com.aktcl.aron.core.sync

import com.aktcl.aron.contract.RecordOutcomeCode
import com.aktcl.aron.core.database.entity.OutboxState
import kotlin.math.min
import kotlin.random.Random

/** Sync limits of the docs/24 s9.5 registry (`cfg.sync.*`) with their Phase 1 defaults; config delivery is F-SYS-053. */
data class SyncPolicy(
    /** `cfg.sync.batch_max_rows` (D24-30). */
    val batchMaxRows: Int = 200,
    /** `cfg.sync.batch_max_kb_raw`: uncompressed record bytes per batch. */
    val batchMaxRawBytes: Int = 256 * 1024,
    /** `cfg.sync.family_skip_after`: sends after which a row moves behind the others. */
    val familySkipAfter: Int = 5,
    /** `cfg.sync.row_max_retries`: sends after which a still-failing row is `rejected(retry_exhausted)` locally. */
    val rowMaxRetries: Int = 10,
    /** Batches per run: bounds one run (and its wake lock, `cfg.sync.wakelock_max_s`); the next trigger continues. */
    val maxBatchesPerRun: Int = 25,
    /** `cfg.sync.retry_backoff_s`. */
    val retryBackoffS: Int = 2,
    /** `cfg.sync.retry_cap_s`. */
    val retryCapS: Int = 300,
) {
    init {
        require(batchMaxRows in 1..500) { "batch_max_rows must be 1..500 (contract maxItems)" }
        require(batchMaxRawBytes > 0 && familySkipAfter > 0 && rowMaxRetries > 0 && maxBatchesPerRun > 0)
    }
}

/** What one ack does to its outbox row (docs/24 s4.5, s4.6). */
data class AckTarget(val state: String, val code: String?)

object AckRules {
    const val RETRY_EXHAUSTED = "retry_exhausted"

    /**
     * Maps the server's ack of a row that has been sent [attempts] times. `accepted` and `duplicate` are stored; a
     * retryable reject goes back to `pending` until [rowMaxRetries], then is `rejected(retry_exhausted)` locally (still
     * counted in reconciliation); a final reject and a quarantine are terminal for the phone. A status this build does
     * not know keeps the row pending: it is never marked stored on a guess, and it is never dropped.
     */
    fun target(ack: RecordAckDto, attempts: Int, rowMaxRetries: Int): AckTarget = when (ack.status) {
        "accepted", "duplicate" -> AckTarget(OutboxState.ACKED, null)
        "quarantined" -> AckTarget(OutboxState.QUARANTINED, ack.code)
        "rejected" -> if (isRetryable(ack)) retry(ack.code, attempts, rowMaxRetries) else AckTarget(OutboxState.REJECTED, ack.code)
        else -> retry("unknown_status:${ack.status}".take(60), attempts, rowMaxRetries)
    }

    /** `retryable` as sent; when absent, the code catalogue decides; an unknown code is retried (it is never lost). */
    fun isRetryable(ack: RecordAckDto): Boolean =
        ack.retryable ?: RecordOutcomeCode.entries.firstOrNull { it.wire == ack.code }?.retryable ?: true

    fun retry(code: String?, attempts: Int, rowMaxRetries: Int): AckTarget =
        if (attempts >= rowMaxRetries) AckTarget(OutboxState.REJECTED, RETRY_EXHAUSTED) else AckTarget(OutboxState.PENDING, code)

    /** A quarantine resolution (s4.5): accepted counts as stored, discarded as rejected; null for an unknown value. */
    fun resolution(value: String): AckTarget? = when (value) {
        "accepted", "accepted_with_fix" -> AckTarget(OutboxState.ACKED, "resolved_$value")
        "discarded" -> AckTarget(OutboxState.REJECTED, "discarded")
        else -> null
    }
}

/** Retry delays of docs/24 s4.7. */
object Backoff {
    /** min(cap, base × 2^(attempt−1)) × U(0.5, 1.0): full jitter on the upper half. */
    fun delayMs(attempt: Int, policy: SyncPolicy, random: Random = Random.Default): Long {
        val exp = min(30, (attempt - 1).coerceAtLeast(0))
        val raw = min(policy.retryCapS.toLong() * 1000, policy.retryBackoffS.toLong() * 1000 * (1L shl exp))
        return (raw * random.nextDouble(0.5, 1.0)).toLong()
    }

    /** A server `Retry-After` replaces the computed delay: × U(0.8, 1.2), capped at 900 s. */
    fun retryAfterMs(retryAfterS: Int, random: Random = Random.Default): Long =
        min(900_000L, (retryAfterS.coerceAtLeast(0) * 1000L * random.nextDouble(0.8, 1.2)).toLong())

    /** A batch response's `hold_s` pauses automatic triggers for hold_s × U(1.0, 1.2). */
    fun holdMs(holdS: Int, random: Random = Random.Default): Long =
        (holdS.coerceAtLeast(0) * 1000L * random.nextDouble(1.0, 1.2)).toLong()
}
