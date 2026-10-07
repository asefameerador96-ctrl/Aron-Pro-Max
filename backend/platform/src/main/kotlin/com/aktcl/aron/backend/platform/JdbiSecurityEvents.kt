package com.aktcl.aron.backend.platform

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * AUD-SEC-03: the durable sink, `app.security_event` (V0054), beside the `aron.security` log line. [record] only offers
 * the event to a bounded queue (never blocks, never touches the database on the request path); one daemon thread
 * drains it in batches. A full queue or a failed write drops events with a WARN and a count: the log line, written
 * first by [LogSecurityEvents], is the copy that always exists.
 *
 * The insert is a plain INSERT without RETURNING, so it works as `auth_rw` (INSERT only) as well as `api_rw`
 * (db-security-event-and-signature-mode-answer.md). `detail` is capped below the table's 2000-byte CHECK here, so an
 * over-long detail loses keys (marked `_truncated`) instead of the whole event.
 */
class JdbiSecurityEvents(
    private val db: Database,
    capacity: Int = 10_000,
    private val batchSize: Int = 100,
    /** False in tests that drain with [drainNow]. */
    startWriter: Boolean = true,
    /** How long [close] waits for the writer to store what is queued. */
    private val closeWaitMs: Long = 3_000,
) : SecurityEvents, AutoCloseable {
    private val queue = ArrayBlockingQueue<SecurityEvent>(capacity)
    private val log = LoggerFactory.getLogger("aron.security")

    @Volatile private var stopping = false

    /** Events not stored (queue full or write failed). */
    val dropped = AtomicLong()

    private val writer: Thread? = if (startWriter) Thread(::run, "aron-security-events").apply { isDaemon = true; start() } else null

    override fun record(e: SecurityEvent) {
        if (stopping) drop(1, "stopped") else if (!queue.offer(e)) drop(1, "queue_full")
    }

    /** Writes what is queued now on the calling thread; only for a sink built with `startWriter = false`. */
    fun drainNow(): Int {
        check(writer == null) { "the writer thread owns the queue" }
        var n = 0
        val buf = ArrayList<SecurityEvent>(batchSize)
        while (queue.drainTo(buf, batchSize) > 0) { n += buf.size; write(buf); buf.clear() }
        return n
    }

    /** Stops taking events and writes what is queued (bounded wait), before the pools close. */
    override fun close() {
        stopping = true
        // No interrupt: one landing inside a write would fail its connection borrow; the poll wakes within a second.
        writer?.join(closeWaitMs)
        // An offer that raced past the `stopping` check after the writer ended is counted, not left queued unseen.
        if (writer?.isAlive == false) { val left = queue.size; queue.clear(); if (left > 0) drop(left, "stopped") }
    }

    private fun run() {
        val buf = ArrayList<SecurityEvent>(batchSize)
        while (true) {
            val first = if (stopping) queue.poll() ?: return else queue.poll(1, TimeUnit.SECONDS) ?: continue
            buf.add(first)
            queue.drainTo(buf, batchSize - 1)
            write(buf)
            buf.clear()
        }
    }

    private fun write(batch: List<SecurityEvent>) {
        try {
            insert(batch)
        } catch (e: Exception) {
            // A constraint failure (class 23) is one bad row: store the others one by one. Anything else (the database
            // is down) drops the batch at once; trying each row would hold the writer for a timeout per row.
            if (sqlState(e)?.startsWith("23") == true && batch.size > 1) {
                batch.forEach { one -> runCatching { insert(listOf(one)) }.onFailure { drop(1, sqlState(it) ?: it.javaClass.simpleName) } }
            } else drop(batch.size, sqlState(e) ?: e.javaClass.simpleName)
        }
    }

    private fun insert(batch: List<SecurityEvent>) {
        db.jdbi.useHandle<Exception> { h ->
            val b = h.prepareBatch(
                "INSERT INTO app.security_event (at, kind, user_id, device_uuid, request_id, detail) " +
                    "VALUES (:at, :kind, :user_id, CAST(:device_uuid AS uuid), CAST(:request_id AS uuid), CAST(:detail AS jsonb))",
            )
            batch.forEach { e ->
                b.bind("at", e.at.atOffset(ZoneOffset.UTC)).bind("kind", e.kind.wire)
                    .bindByType("user_id", e.userId, Long::class.javaObjectType)
                    .bindByType("device_uuid", uuidOrNull(e.deviceUuid)?.toString(), String::class.java)
                    .bindByType("request_id", uuidOrNull(e.requestId)?.toString(), String::class.java)
                    .bind("detail", detailJson(e.detail)).add()
            }
            b.execute()
        }
    }

    private fun drop(n: Int, cause: String) {
        val before = dropped.getAndAdd(n.toLong())
        // One WARN per 1000 drops: a down database must not turn every login into a log line of its own.
        if (before / 1000 != (before + n) / 1000 || before == 0L) log.warn("security events not stored n={} total={} cause={}", n, before + n, cause)
    }

    companion object {
        /** Under the table's 2000-byte CHECK with room for jsonb's own spacing (", " and ": " per key). */
        const val DETAIL_MAX_BYTES = 1_900
        const val VALUE_MAX_CHARS = 200

        /** The detail as a JSON object, values cut to [VALUE_MAX_CHARS] without U+0000 (jsonb refuses it), keys (sorted) kept while under [DETAIL_MAX_BYTES]. */
        fun detailJson(detail: Map<String, String>): String {
            var size = 2 + TRUNCATED_COST
            var truncated = false
            val obj = buildJsonObject {
                for ((k, v) in detail.toSortedMap()) {
                    val cost = JsonPrimitive(k).toString().utf8Size() + JsonPrimitive(v.take(VALUE_MAX_CHARS).replace("\u0000", "")).toString().utf8Size() + 4
                    if (truncated || size + cost > DETAIL_MAX_BYTES) { truncated = true; continue }
                    size += cost
                    put(k, v.take(VALUE_MAX_CHARS).replace("\u0000", ""))
                }
                if (truncated) put("_truncated", "true")
            }
            return obj.toString()
        }

        private val TRUNCATED_COST = "\"_truncated\":\"true\"".length + 4

        private fun String.utf8Size() = toByteArray(Charsets.UTF_8).size

        private fun uuidOrNull(s: String?): UUID? = s?.let { runCatching { UUID.fromString(it) }.getOrNull() }

        private fun sqlState(t: Throwable): String? = generateSequence(t) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState
    }
}
