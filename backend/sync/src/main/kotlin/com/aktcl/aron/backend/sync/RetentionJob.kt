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
 * Needs `jobs_rw` (EXECUTE on ensure_partitions, the manifest INSERT): the worker's `app_jobs` login when infra turns on
 * per-app logins (`dbPerAppLogins`, infra/sql/runtime-logins.sql); until then the worker runs as the admin login, which
 * holds those rights too. Each step in its own short transaction (`RetentionJobTest` runs it as `jobs_rw`).
 */
class RetentionJob(
    private val db: Database,
    private val clock: AronClock = AronClock.SYSTEM,
    private val everyHours: Long = 6,
) {
    private val log = LoggerFactory.getLogger("aron.retention")

    data class Report(
        val partitionsCreated: Int, val failedParents: Map<String, String>, val defaultRows: Map<String, Long>, val manifestsPlanned: Int,
        val clientErrorsDeleted: Int = 0,
    )

    /** Each step on its own: a failure in one is logged and never skips the others. */
    fun tick(): Report {
        val today: LocalDate = BusinessDate.of(clock.now().toEpochMilli()).toJavaLocalDate()
        val created = step("ensure partitions", 0) {
            db.jdbi.inTransaction<Int, Exception> { h ->
                // CREATE ... PARTITION OF takes an ACCESS EXCLUSIVE lock on the parent: never queue behind a long read
                // (and make every ingest insert queue behind us). The function's per-parent handler records a timeout in
                // partition_policy.last_error; the next run retries, with three months of slack.
                h.execute("SET LOCAL lock_timeout = '2s'")
                h.createQuery("SELECT app.ensure_partitions()").mapTo(Int::class.java).one()
            }
        }
        // ensure_partitions turns a parent's failure into last_error and a WARNING only: surface it as the alert.
        val failed = step("partition errors", emptyMap()) {
            db.jdbi.withHandle<Map<String, String>, Exception> { h ->
                h.createQuery("SELECT parent, last_error FROM app.partition_policy WHERE last_error IS NOT NULL").map { rs, _ -> rs.getString(1) to rs.getString(2) }.list().toMap()
            }
        }
        failed.forEach { (parent, err) -> log.error("retention: partitions of {} not created: {}", parent, err) }
        val defaults = step("default partition rows", emptyMap()) {
            db.jdbi.withHandle<Map<String, Long>, Exception> { h ->
                h.createQuery("SELECT parent, row_count FROM app.default_partition_rows()").map { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().toMap()
            }
        }
        defaults.forEach { (parent, n) -> log.error("retention: {} row(s) in {}_default (a monthly partition was missing when they were written)", n, parent) }
        val planned = step("archive manifests", 0) {
            db.jdbi.inTransaction<Int, Exception> { h ->
                h.createUpdate(
                    """
                    INSERT INTO app.archive_manifest (parent, partition_name, month)
                    SELECT c.parent, c.partition_name, c.month FROM app.archive_candidates(:today) c
                    ON CONFLICT (partition_name) DO NOTHING
                    """.trimIndent(),
                ).bind("today", today).execute()
            }
        }
        // 4. Web error reports (app.client_error, db V0069, retention class telemetry): not partitioned and never archived,
        // so rows past the class's keep window are deleted, at most 10 000 per run. A no-op while the table is missing.
        // (app.app_error refuses DELETE by trigger since V0007: its retention waits on the archive job.)
        val clientErrors = step("client errors", 0) {
            db.jdbi.inTransaction<Int, Exception> { h ->
                val exists = h.createQuery("SELECT to_regclass('app.client_error') IS NOT NULL").mapTo(Boolean::class.java).one()
                if (!exists) return@inTransaction 0
                val keep = h.createQuery("SELECT keep_months FROM app.retention_policy WHERE retention_class = 'telemetry'").mapTo(Int::class.javaObjectType).findOne().orElse(null)
                    ?: return@inTransaction 0
                h.createUpdate(
                    "DELETE FROM app.client_error WHERE id IN (SELECT id FROM app.client_error WHERE business_date < :cutoff ORDER BY id LIMIT 10000)",
                ).bind("cutoff", today.minusMonths(keep.toLong())).execute()
            }
        }
        if (clientErrors > 0) log.info("retention: {} client error report(s) past the telemetry window deleted", clientErrors)
        return Report(created, failed, defaults, planned, clientErrors)
    }

    private fun <T> step(name: String, fallback: T, body: () -> T): T = runCatching(body).getOrElse { e ->
        log.error("retention step '{}' failed", name, e)
        fallback
    }

    fun start() {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "retention").apply { isDaemon = true } }.scheduleWithFixedDelay({
            runCatching { tick() }.onSuccess { r ->
                if (r.partitionsCreated > 0 || r.manifestsPlanned > 0) log.info("retention: {} partition(s) created, {} archive manifest(s) planned", r.partitionsCreated, r.manifestsPlanned)
            }.onFailure { log.error("retention job failed", it) }
        }, 1, everyHours * 60, TimeUnit.MINUTES)
    }
}
