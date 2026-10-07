package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.rules.BusinessDate
import kotlinx.datetime.toJavaLocalDate
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * F-SYS-063 (D-23, D-131, D-132; docs/16 s13.1, V0001 + V0051): the worker's partition and retention job.
 *
 * 1. `app.ensure_partitions()` creates the monthly RANGE partitions of every registered parent through
 *    `partition_policy.ahead_months` (3) ahead, and its DEFAULT partition; idempotent.
 * 2. `app.default_partition_rows()`: a row in a `<parent>_default` partition means a month was missing when it was
 *    written; logged as an error (the alert), never moved by this job.
 * 3. `app.archive_candidates(today)`: partitions past their class's hot window get one `planned` row in
 *    `app.archive_manifest`. This build never exports and never drops a partition: export, verification and the drop
 *    (only after a verified upload) are the archive job of a later release (before month 12). Photo tiering is Blob
 *    lifecycle policy (infra).
 *
 * Runs as `jobs_rw` (the worker container's login, infra/sql/runtime-logins.sql); each step in its own transaction.
 */
class RetentionJob(
    private val db: Database,
    private val clock: AronClock = AronClock.SYSTEM,
    private val everyHours: Long = 6,
) {
    private val log = LoggerFactory.getLogger("aron.retention")

    data class Report(val partitionsCreated: Int, val defaultRows: Map<String, Long>, val manifestsPlanned: Int)

    fun tick(): Report {
        val today: LocalDate = BusinessDate.of(clock.now().toEpochMilli()).toJavaLocalDate()
        val created = db.jdbi.withHandle<Int, Exception> { h -> h.createQuery("SELECT app.ensure_partitions()").mapTo(Int::class.java).one() }
        val defaults = db.jdbi.withHandle<Map<String, Long>, Exception> { h ->
            h.createQuery("SELECT parent, row_count FROM app.default_partition_rows()").map { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().toMap()
        }
        defaults.forEach { (parent, n) -> log.error("retention: {} row(s) in {}_default (a monthly partition was missing when they were written)", n, parent) }
        val planned = db.jdbi.inTransaction<Int, Exception> { h ->
            h.createUpdate(
                """
                INSERT INTO app.archive_manifest (parent, partition_name, month)
                SELECT c.parent, c.partition_name, c.month FROM app.archive_candidates(:today) c
                ON CONFLICT (partition_name) DO NOTHING
                """.trimIndent(),
            ).bind("today", today).execute()
        }
        return Report(created, defaults, planned)
    }

    fun start() {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "retention").apply { isDaemon = true } }.scheduleWithFixedDelay({
            runCatching { tick() }.onSuccess { r ->
                if (r.partitionsCreated > 0 || r.manifestsPlanned > 0) log.info("retention: {} partition(s) created, {} archive manifest(s) planned", r.partitionsCreated, r.manifestsPlanned)
            }.onFailure { log.error("retention job failed", it) }
        }, 1, everyHours * 60, TimeUnit.MINUTES)
    }
}
