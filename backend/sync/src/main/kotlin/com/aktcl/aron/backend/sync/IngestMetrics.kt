package com.aktcl.aron.backend.sync

import java.util.concurrent.atomic.LongAdder

/**
 * Process-wide ingest counters (OPS-03, docs/18 s6.6): what `POST /v1/sync/batch` did since start, read by the metrics
 * endpoint and, as deltas, by the tests that bound a batch (SCALE-02). Monotonic; never reset. Savepoints are counted
 * here because they are JDBC calls, not statements (see `Database.statements`).
 */
object IngestMetrics {
    val batches = LongAdder()
    val records = LongAdder()
    val accepted = LongAdder()
    val duplicate = LongAdder()
    val rejected = LongAdder()
    val quarantined = LongAdder()
    /** Records parked `server_error` (a poison row isolated by bisection, or a batch that failed past its tries). */
    val parkedServerError = LongAdder()
    /** Savepoints opened by the ingest (D-521: at most `cfg.sync.max_savepoints_per_tx` per batch transaction). */
    val savepoints = LongAdder()
    /** Units split in two after a failure (docs/18 s4.2 step 7). */
    val bisections = LongAdder()
    /** Batch transactions retried after a transient database failure (AUD-REL-02). */
    val retries = LongAdder()

    fun snapshot(): Map<String, Long> = linkedMapOf(
        "batches" to batches.sum(), "records" to records.sum(), "accepted" to accepted.sum(), "duplicate" to duplicate.sum(),
        "rejected" to rejected.sum(), "quarantined" to quarantined.sum(), "parked_server_error" to parkedServerError.sum(),
        "savepoints" to savepoints.sum(), "bisections" to bisections.sum(), "retries" to retries.sum(),
    )
}
