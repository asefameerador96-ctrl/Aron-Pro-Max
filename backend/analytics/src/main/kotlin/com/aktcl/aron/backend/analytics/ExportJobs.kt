package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.Role
import com.aktcl.aron.rules.BusinessDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jdbi.v3.core.Handle
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Where finished export files live (Azure Blob in production, a map in tests). The link handed to the user is short-lived and read-only. */
interface ExportStore {
    fun put(path: String, bytes: ByteArray)
    fun readUrl(path: String, expiresAt: Instant): String

    /** No store configured: jobs finish as failed with a clear error instead of pretending (infra: ARON_BLOB_* environment). */
    object Unconfigured : ExportStore {
        override fun put(path: String, bytes: ByteArray) = error("export storage is not configured")
        override fun readUrl(path: String, expiresAt: Instant): String = error("export storage is not configured")
    }
}

/**
 * Asynchronous exports (F-SYS-064, F-API-053): the API inserts a `queued` row of `app.report_export`; [run] claims it with a lease, re-derives the
 * caller's scope on the server (the user's reach today, never anything stored from the client), streams the same engine query the screen uses into an xlsx
 * and stores it. A lapsed lease puts a `running` job back in the queue. pdf is not produced here (no PDF library with Bangla shaping is chosen yet:
 * docs/requests/backend-reports-pdf-export.md).
 */
class ExportJobService(
    private val db: Database, private val engine: ReportEngine, private val reach: ReachResolver, private val store: ExportStore,
    private val clock: AronClock = AronClock.SYSTEM, private val workerId: String = "export-" + UUID.randomUUID().toString().take(8),
    private val leaseS: Long = 900, private val linkTtl: Duration = Duration.ofHours(24),
) : ExportJobs {

    override fun enqueue(key: String, format: String, ctx: ReportContext, rows: Int): ExportJob {
        val id = UUID.randomUUID().toString()
        db.jdbi.useHandle<Exception> { h -> engine.recordExport(h, id, key, format, ctx, rows, "queued") }
        return ExportJob(id, key, "queued", null, clock.now().wire())
    }

    /** Runs one queued job; false when none was waiting. */
    fun runNext(): Boolean {
        val now = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)
        val job = db.jdbi.inTransaction<Claimed?, Exception> { h ->
            // A job whose worker vanished (lease over) goes back to the queue before the claim.
            h.createUpdate("UPDATE app.report_export SET status = 'queued', claimed_by = NULL, claimed_at = NULL WHERE status = 'running' AND claimed_at < :t")
                .bind("t", now.minusSeconds(leaseS)).execute()
            val c = h.createQuery(
                """
                SELECT export_id::text, report_key, user_id, format, filters::text, pii_included FROM app.report_export
                 WHERE status = 'queued' ORDER BY created_at, export_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """,
            ).map { rs, _ -> Claimed(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getBoolean(6)) }.findOne().orElse(null) ?: return@inTransaction null
            h.createUpdate("UPDATE app.report_export SET status = 'running', claimed_by = :w, claimed_at = :n, started_at = coalesce(started_at, :n) WHERE export_id = CAST(:id AS uuid)")
                .bind("w", workerId).bind("n", now).bind("id", c.id).execute()
            c
        } ?: return false
        try {
            val out = produce(job)
            val expires = clock.now().plus(linkTtl)
            store.put(out.path, out.bytes)
            db.jdbi.useHandle<Exception> { h ->
                h.createUpdate("UPDATE app.report_export SET status = 'done', row_count = :n, blob_path = :p, finished_at = :f, expires_at = :e WHERE export_id = CAST(:id AS uuid)")
                    .bind("n", out.rows).bind("p", out.path).bind("f", OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)).bind("e", OffsetDateTime.ofInstant(expires, ZoneOffset.UTC)).bind("id", job.id).execute()
            }
        } catch (e: Exception) {
            // A short, personal-data-free reason; the details stay in the log.
            val why = (if (e is com.aktcl.aron.backend.platform.ApiProblem) e.message else e.javaClass.simpleName + ": " + e.message).orEmpty().take(280)
            org.slf4j.LoggerFactory.getLogger(ExportJobService::class.java).warn("export {} failed: {}", job.id, e.javaClass.simpleName)
            db.jdbi.useHandle<Exception> { h ->
                h.createUpdate("UPDATE app.report_export SET status = 'failed', error = :err, finished_at = :f WHERE export_id = CAST(:id AS uuid)")
                    .bind("err", why).bind("f", OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)).bind("id", job.id).execute()
            }
        }
        return true
    }

    fun runUntilIdle(max: Int = 100): Int { var n = 0; while (n < max && runNext()) n++; return n }

    fun start(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO), pollMs: Long = 5_000) = scope.launch {
        while (isActive) {
            val worked = runCatching { runNext() }.getOrDefault(false)
            if (!worked) delay(pollMs)
        }
    }

    private class Claimed(val id: String, val key: String, val userId: Long, val format: String, val filters: String, val pii: Boolean)
    private class Produced(val bytes: ByteArray, val path: String, val rows: Int)

    private fun produce(job: Claimed): Produced {
        if (job.format != "xlsx") throw IllegalStateException("format ${job.format} is not produced by the export job yet")
        val today = BusinessDate.of(clock.now().toEpochMilli()).toJavaLocalDate()
        val query = Json.decodeFromString(ReportQuery.serializer(), job.filters)
        return db.readJdbi.withHandle<Produced, Exception> { h ->
            val (username, role, sv) = h.createQuery("SELECT username, role, scope_version FROM app.app_user WHERE id = :u AND status = 'active'").bind("u", job.userId)
                .map { rs, _ -> Triple(rs.getString(1), Role.entries.first { it.wire == rs.getString(2) }, rs.getLong(3)) }.findOne().orElse(null) ?: error("the requesting user is no longer active")
            val principal = AronPrincipal(job.userId, username, role, sv, "aron-api", null, null, "web", emptyList(), job.pii, listOf("job"), "export-" + job.id, clock.now().plusSeconds(3600))
            val ctx = engine.context(h, job.key, query, reach.reach(job.userId, role, sv, today), principal, today)
            val def = engine.handler(job.key).definition
            val rows = engine.count(h, job.key, ctx)
            if (rows > engine.exportMaxRows()) error("the report has $rows rows; narrow the filters")
            val cols = engine.visibleColumns(def, ctx.pii)
            val watermark = "Exported by $username (${role.wire}) at ${clock.now().wire()} · export ${job.id}"
            val all = ArrayList<Map<String, JsonElement>>(rows)
            engine.stream(h, job.key, ctx) { all += it }
            val bytes = ByteArrayOutputStream().also { ReportOutputs.xlsx(it, def, cols, all.iterator(), watermark) }.toByteArray()
            Produced(bytes, "exports/$today/${job.id}.xlsx", all.size)
        }
    }

    /** The job as its owner (or an admin viewer) sees it; the download link exists only while the file does. */
    fun job(exportId: String, viewer: AronPrincipal, adminViewer: Boolean): ExportJob? {
        if (runCatching { UUID.fromString(exportId) }.getOrNull()?.toString() != exportId) return null
        val row = db.readJdbi.withHandle<Row?, Exception> { h ->
            h.createQuery("SELECT report_key, user_id, status, row_count, created_at, blob_path, expires_at, error FROM app.report_export WHERE export_id = CAST(:id AS uuid)").bind("id", exportId)
                .map { rs, _ -> Row(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getObject(4) as Int?, rs.getObject(5, OffsetDateTime::class.java).toInstant(), rs.getString(6), rs.getObject(7, OffsetDateTime::class.java)?.toInstant(), rs.getString(8)) }.findOne().orElse(null)
        } ?: return null
        if (row.userId != viewer.userId && !adminViewer) return null   // someone else's export looks like none
        val live = row.status == "done" && row.path != null && row.expires != null && clock.now().isBefore(row.expires)
        return ExportJob(exportId, row.key, row.status, row.rows, row.created.wire(), if (live) store.readUrl(row.path!!, minOf(row.expires!!, clock.now().plus(Duration.ofMinutes(15)))) else null, row.expires?.wire(), row.error)
    }

    private class Row(val key: String, val userId: Long, val status: String, val rows: Int?, val created: Instant, val path: String?, val expires: Instant?, val error: String?)
}
