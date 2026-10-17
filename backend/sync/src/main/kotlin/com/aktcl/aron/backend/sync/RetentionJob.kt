package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.rules.BusinessDate
import kotlinx.datetime.toJavaLocalDate
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
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
 * 4. Client error reports past the telemetry window are deleted.
 * 5. DB-01 / HD-08 (V0086): the sync tables the archive never takes are pruned in the night window ([prune]).
 *
 * Needs `jobs_rw` (EXECUTE on ensure_partitions, the manifest INSERT): the worker's `app_jobs` login when infra turns on
 * per-app logins (`dbPerAppLogins`, infra/sql/runtime-logins.sql); until then the worker runs as the admin login, which
 * holds those rights too. Each step in its own short transaction (`RetentionJobTest` runs it as `jobs_rw`).
 */
class RetentionJob(
    private val db: Database,
    private val clock: AronClock = AronClock.SYSTEM,
    private val everyHours: Long = 6,
    /** Rows per pruning statement (D-23: short transactions, never a table lock for long). Tests use a small one. */
    private val chunk: Int = 1000,
    /** Chunks per table per run: the rest waits for the next night, and the report says so. */
    private val maxChunks: Int = 5000,
) {
    private val log = LoggerFactory.getLogger("aron.retention")

    data class Report(
        val partitionsCreated: Int, val failedParents: Map<String, String>, val defaultRows: Map<String, Long>, val manifestsPlanned: Int,
        val clientErrorsDeleted: Int = 0,
        val pruned: Pruned = Pruned(),
    )

    /** What [prune] did: rows deleted or bodies dropped per table, the registry's oldest row age (the alert), and the tables a chunk cap stopped early. */
    data class Pruned(
        val registryRows: Int = 0, val batchResponses: Int = 0, val batchRows: Int = 0, val nonces: Int = 0, val snapshotBodies: Int = 0, val snapshotRows: Int = 0,
        val oldestRegistryDays: Long? = null, val capped: List<String> = emptyList(), val ran: Boolean = false,
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
        // 5. DB-01 / HD-08: the night window (00:00 to 06:00 Dhaka) of a 6-hourly tick comes once a day.
        val pruned = step("prune", Pruned()) { if (inNightWindow(clock.now())) prune(today) else Pruned() }
        if (pruned.ran) {
            log.info("retention: pruned registry={} batch_responses={} batches={} nonces={} snapshot_bodies={} snapshots={} oldest_registry_days={}{}",
                pruned.registryRows, pruned.batchResponses, pruned.batchRows, pruned.nonces, pruned.snapshotBodies, pruned.snapshotRows, pruned.oldestRegistryDays,
                if (pruned.capped.isEmpty()) "" else " capped=${pruned.capped}")
        }
        return Report(created, failed, defaults, planned, clientErrors, pruned)
    }

    /** True between 00:00 and 06:00 Dhaka: the pruning runs once a night. */
    fun inNightWindow(now: Instant): Boolean = now.atZone(DHAKA).hour < 6

    /**
     * DB-01 / HD-08: prunes what the archive job never takes, each table in chunks of [chunk] rows and short
     * transactions, at most [maxChunks] chunks per table per run (the rest waits for the next night; the report lists the
     * tables that were capped):
     * - `ingest_registry` rows received before `cfg.retention.ingest_registry_days` (45; the backdate and resync windows
     *   stay well inside it), except `voided` rows, which keep answering a resend `voided_by_admin` (D-22);
     * - `sync_batch`: the stored response is dropped once `expires_at` passed (a replay past
     *   `cfg.retention.sync_batch_response_h` is processed afresh; every record is idempotent), and rows whose expiry is
     *   older than the registry window are deleted;
     * - `device_nonce` rows past `expires_at`;
     * - `bundle_snapshot`: bodies of past dates are dropped (a past date is never served, V0084), rows of dates older
     *   than 7 days are deleted (V0032); the seq rows of recent dates stay so versions keep growing.
     * Also measures the registry's oldest row: past the window plus two days the pruning is behind (logged as an error,
     * the alert; the worker heartbeat carries the number).
     */
    fun prune(today: LocalDate): Pruned {
        val now = clock.now()
        val registryDays = configInt("cfg.retention.ingest_registry_days", 45).coerceIn(30, 400)
        val cutoff = ts(now.minus(Duration.ofDays(registryDays.toLong())))
        val capped = ArrayList<String>()
        fun chunks(name: String, sql: String, bind: (org.jdbi.v3.core.statement.Update) -> org.jdbi.v3.core.statement.Update): Int {
            var total = 0
            repeat(maxChunks) {
                val n = db.jdbi.inTransaction<Int, Exception> { h -> bind(h.createUpdate(sql)).execute() }
                total += n
                if (n < chunk) return total
            }
            capped += name
            return total
        }
        val registry = chunks(
            "ingest_registry",
            "DELETE FROM app.ingest_registry WHERE client_uuid IN (SELECT client_uuid FROM app.ingest_registry WHERE received_at < :cutoff AND status <> 'voided' LIMIT $chunk)",
        ) { it.bind("cutoff", cutoff) }
        val responses = chunks(
            "sync_batch responses",
            "UPDATE app.sync_batch SET response_gz = NULL WHERE (device_id, batch_uuid) IN (SELECT device_id, batch_uuid FROM app.sync_batch WHERE expires_at < :now AND response_gz IS NOT NULL LIMIT $chunk)",
        ) { it.bind("now", ts(now)) }
        val batches = chunks(
            "sync_batch",
            "DELETE FROM app.sync_batch WHERE (device_id, batch_uuid) IN (SELECT device_id, batch_uuid FROM app.sync_batch WHERE expires_at < :cutoff LIMIT $chunk)",
        ) { it.bind("cutoff", cutoff) }
        val nonces = chunks(
            "device_nonce",
            "DELETE FROM app.device_nonce WHERE nonce_sha256 IN (SELECT nonce_sha256 FROM app.device_nonce WHERE expires_at < :now LIMIT $chunk)",
        ) { it.bind("now", ts(now)) }
        val bodies = chunks(
            "bundle_snapshot bodies",
            "UPDATE app.bundle_snapshot SET body_gz = NULL WHERE (user_id, business_date, snapshot_seq) IN (SELECT user_id, business_date, snapshot_seq FROM app.bundle_snapshot WHERE business_date < :d AND body_gz IS NOT NULL LIMIT $chunk)",
        ) { it.bind("d", today) }
        val snapshots = chunks(
            "bundle_snapshot",
            "DELETE FROM app.bundle_snapshot WHERE (user_id, business_date, snapshot_seq) IN (SELECT user_id, business_date, snapshot_seq FROM app.bundle_snapshot WHERE business_date < :d LIMIT $chunk)",
        ) { it.bind("d", today.minusDays(7)) }
        val oldest = db.jdbi.withHandle<OffsetDateTime?, Exception> { h ->
            h.createQuery("SELECT min(received_at) FROM app.ingest_registry WHERE status <> 'voided'").mapTo(OffsetDateTime::class.java).findOne().orElse(null)
        }?.let { Duration.between(it.toInstant(), now).toDays() }
        if (oldest != null && oldest > registryDays + 2) {
            log.error("retention: the oldest ingest_registry row is {} days old, the window is {} days: the pruning is behind (capped={})", oldest, registryDays, capped)
        }
        return Pruned(registry, responses, batches, nonces, bodies, snapshots, oldest, capped, ran = true)
    }

    /** A global `cfg.*` integer: the committed global value, else the registry default, else [default]. */
    private fun configInt(key: String, default: Int): Int = db.jdbi.withHandle<Int, Exception> { h ->
        h.createQuery(
            """
            SELECT COALESCE(
              (SELECT (value #>> '{}')::int FROM app.cfg_value WHERE key = :k AND scope_type = 'global' AND effective_from <= now() AND (effective_to IS NULL OR effective_to > now()) ORDER BY effective_from DESC LIMIT 1),
              (SELECT (default_value #>> '{}')::int FROM app.cfg_key WHERE key = :k),
              :d)
            """.trimIndent(),
        ).bind("k", key).bind("d", default).mapTo(Int::class.java).one()
    }

    private fun ts(i: Instant): OffsetDateTime = OffsetDateTime.ofInstant(i, ZoneOffset.UTC)

    private fun <T> step(name: String, fallback: T, body: () -> T): T = runCatching(body).getOrElse { e ->
        log.error("retention step '{}' failed", name, e)
        fallback
    }

    private companion object {
        val DHAKA: ZoneId = ZoneId.of("Asia/Dhaka")
    }

    fun start() {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "retention").apply { isDaemon = true } }.scheduleWithFixedDelay({
            runCatching { tick() }.onSuccess { r ->
                if (r.partitionsCreated > 0 || r.manifestsPlanned > 0) log.info("retention: {} partition(s) created, {} archive manifest(s) planned", r.partitionsCreated, r.manifestsPlanned)
            }.onFailure { log.error("retention job failed", it) }
        }, 1, everyHours * 60, TimeUnit.MINUTES)
    }
}
