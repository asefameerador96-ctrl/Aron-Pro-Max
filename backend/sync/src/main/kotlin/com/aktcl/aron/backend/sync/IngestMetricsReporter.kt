package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.Database
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * OPS-03 (docs/18 s6.6): once a minute the api logs what its ingest did since the previous line, on `aron.sync.metrics`:
 * `ingest_minute batches=.. records=.. accepted=.. duplicate=.. rejected=.. quarantined=.. parked_server_error=..
 * savepoints=.. bisections=.. retries=.. db_statements=..` (the [IngestMetrics] counters and `Database.statements` as
 * deltas). The alerts (infra/modules/alerts.bicep) sum the lines of every replica over their window: sync success
 * (accepted plus duplicate over records) below 97 %, rejected plus quarantined above 5 %.
 */
class IngestMetricsReporter(private val db: Database? = null) {
    private val log = LoggerFactory.getLogger("aron.sync.metrics")
    private var previous: Map<String, Long> = snapshot()

    private fun snapshot(): Map<String, Long> = IngestMetrics.snapshot() + ("db_statements" to (db?.statements?.sum() ?: 0L))

    /** Logs and returns the line of the deltas since the previous call (or since construction). */
    @Synchronized
    fun report(): String {
        val now = snapshot()
        val line = "ingest_minute " + now.entries.joinToString(" ") { (k, v) -> "$k=${v - (previous[k] ?: 0L)}" }
        previous = now
        log.info(line)
        return line
    }

    fun start(everySeconds: Long = 60): ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ingest-metrics").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({ runCatching { report() }.onFailure { e -> log.error("ingest metrics line failed", e) } }, everySeconds, everySeconds, TimeUnit.SECONDS)
        }
}
