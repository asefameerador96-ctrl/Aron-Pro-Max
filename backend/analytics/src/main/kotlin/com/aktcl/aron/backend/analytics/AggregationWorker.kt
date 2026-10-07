package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import org.jdbi.v3.core.Handle
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The aggregation worker (F-SYS-015, docs/24 s6.3, s12.4). Two steps, both safe to run in any number of replicas:
 *  1. [project]: reads `app.domain_event` after the consumer's position and marks the affected route-days dirty
 *     (a late, voided or resolved record dirties its own business date, never today's).
 *  2. [drain]: claims dirty keys with FOR UPDATE SKIP LOCKED, rebuilds the route-day (then dirties its zone-day),
 *     and deletes a key only when its dirty_count is unchanged since the claim, so a key dirtied again meanwhile stays queued.
 * A claim older than [lease] is taken over, which covers a crashed worker.
 */
class AggregationWorker(
    private val db: Database,
    private val clock: AronClock = AronClock.SYSTEM,
    private val workerId: String = "worker-" + java.util.UUID.randomUUID().toString().take(8),
    private val lease: Duration = Duration.ofMinutes(2),
    private val config: com.aktcl.aron.backend.platform.ServerConfig? = null,
    private val gapGrace: Duration = Duration.ofSeconds(60),
) {
    /** Test seam: runs inside the rebuild transaction, just before the key is released. */
    internal var beforeRelease: (kind: String, subject: Long, date: LocalDate) -> Unit = { _, _, _ -> }
    private val log = LoggerFactory.getLogger("aron.analytics.worker")

    /**
     * Outbox ids are taken before commit, so a transaction that took id 6 can commit after one that took id 7. Reading `id > position`
     * and moving the position to the highest id seen would lose id 6 for ever. Instead the worker remembers the ids it has not seen
     * ([missing], with the time it noticed the gap), re-reads them each pass, and moves the durable position only up to the first
     * id still missing. A gap older than [gapGrace] is a rolled-back transaction (ids are never reused) and is dropped.
     */
    private val missing = java.util.TreeMap<Long, Instant>()
    private var scanned = 0L

    /** Reads up to [limit] new outbox events (and any previously missing ids that have since committed) and dirties route-days. */
    fun project(limit: Int = 1000): Int = db.jdbi.inTransaction<Int, Exception> { h ->
        h.execute("INSERT INTO app.event_consumer (consumer) VALUES (?) ON CONFLICT DO NOTHING", CONSUMER)
        val pos = h.createQuery("SELECT last_event_id FROM app.event_consumer WHERE consumer = :c FOR UPDATE").bind("c", CONSUMER).mapTo(Long::class.java).one()
        if (scanned < pos) { scanned = pos; missing.headMap(pos, true).clear() }
        val now = clock.now()
        val fresh = h.createQuery(
            "SELECT id, event_type, aggregate_id, business_date, payload->>'route_id' AS route_id, payload->>'user_id' AS user_id, source_client_uuid FROM app.domain_event WHERE id > :p ORDER BY id LIMIT :l",
        ).bind("p", scanned).bind("l", limit).map(::event).list()
        val holes = if (missing.isEmpty()) emptyList() else h.createQuery(
            "SELECT id, event_type, aggregate_id, business_date, payload->>'route_id' AS route_id, payload->>'user_id' AS user_id, source_client_uuid FROM app.domain_event WHERE id = ANY(:ids) ORDER BY id",
        ).bindArray("ids", Long::class.javaObjectType, missing.keys.toList()).map(::event).list()
        for (e in holes + fresh) {
            for (route in routesOf(h, e)) h.execute("SELECT app.mark_dirty('route_day_agg', ?, ?, ?)", route, e.date, e.type)
        }
        holes.forEach { missing.remove(it.id) }
        var prev = scanned
        for (e in fresh) {
            if (e.id - prev in 2..MAX_TRACKED_GAP) for (g in prev + 1 until e.id) missing[g] = now
            prev = e.id
        }
        scanned = prev
        missing.entries.removeIf { Duration.between(it.value, now) >= gapGrace }
        val safe = if (missing.isEmpty()) scanned else missing.firstKey() - 1
        if (safe > pos) h.createUpdate("UPDATE app.event_consumer SET last_event_id = :i, updated_at = now() WHERE consumer = :c").bind("i", safe).bind("c", CONSUMER).execute()
        holes.size + fresh.size
    }

    private fun event(rs: java.sql.ResultSet, @Suppress("UNUSED_PARAMETER") n: org.jdbi.v3.core.statement.StatementContext) =
        Ev(rs.getLong("id"), rs.getString("event_type"), rs.getString("aggregate_id"), rs.getObject("business_date", LocalDate::class.java),
            rs.getString("route_id")?.toLongOrNull(), rs.getString("user_id")?.toLongOrNull(), rs.getObject("source_client_uuid", java.util.UUID::class.java))

    private data class Ev(val id: Long, val type: String, val aggregateId: String, val date: LocalDate, val routeId: Long?, val userId: Long?, val source: java.util.UUID?)

    /**
     * The route-days an event touches: the payload's route; else the route of the record named by `source_client_uuid` (the record
     * itself, or the memo/visit a void, close or payment record points at); else, for a user-level event (risk signal reviewed),
     * every route that user visited that day.
     */
    private fun routesOf(h: Handle, e: Ev): List<Long> {
        e.routeId?.let { return listOf(it) }
        e.source?.let { src ->
            val sql = """
                SELECT route_id FROM app.memo WHERE business_date = :d AND route_id IS NOT NULL AND (client_uuid = :u OR voided_by_client_uuid = :u OR superseded_by_client_uuid = :u)
                UNION SELECT route_id FROM app.visit WHERE business_date = :d AND route_id IS NOT NULL AND (client_uuid = :u OR close_client_uuid = :u)
                UNION SELECT route_id FROM app.due_collection WHERE business_date = :d AND route_id IS NOT NULL AND client_uuid = :u
                UNION SELECT route_id FROM app.stock_movement WHERE business_date = :d AND route_id IS NOT NULL AND client_uuid = :u
            """
            val r = h.createQuery(sql).bind("u", src).bind("d", e.date).mapTo(Long::class.java).list()
            if (r.isNotEmpty()) return r
        }
        if (e.type.startsWith("route_day.")) e.aggregateId.toLongOrNull()?.let { id ->
            return h.createQuery("SELECT route_id FROM app.route_day WHERE id = :i").bind("i", id).mapTo(Long::class.java).list()
        }
        e.userId?.let { u ->
            return h.createQuery("SELECT DISTINCT route_id FROM app.visit WHERE user_id = :u AND business_date = :d AND route_id IS NOT NULL").bind("u", u).bind("d", e.date).mapTo(Long::class.java).list()
        }
        return emptyList()
    }

    /** Processes up to [limit] claimed keys of one pass. Returns the number rebuilt. */
    fun drain(limit: Int = 200): Int {
        var done = 0
        for (kind in listOf("route_day_agg", "zone_day_agg")) {
            val claimed = claim(kind, limit)
            for (k in claimed) {
                try {
                    db.jdbi.useTransaction<Exception> { h ->
                        when (kind) {
                            "route_day_agg" -> Aggregator.rebuildRouteDay(h, k.subject, k.date, threshold()).forEach { z -> h.execute("SELECT app.mark_dirty('zone_day_agg', ?, ?, 'route_day_rebuilt')", z, k.date) }
                            else -> Aggregator.rebuildZoneDay(h, k.subject, k.date, threshold())
                        }
                        beforeRelease(kind, k.subject, k.date)
                        h.createUpdate("DELETE FROM app.dirty_key WHERE kind = :k AND subject_id = :s AND business_date = :d AND dirty_count = :c")
                            .bind("k", kind).bind("s", k.subject).bind("d", k.date).bind("c", k.count).execute()
                    }
                    done++
                } catch (e: Exception) {
                    log.error("aggregation of {} {} {} failed; key stays queued", kind, k.subject, k.date, e)
                    // Release the claim so the next pass retries now, not after the lease.
                    runCatching { db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.dirty_key SET claimed_at = NULL, claimed_by = NULL WHERE kind = ? AND subject_id = ? AND business_date = ? AND claimed_by = ?", kind, k.subject, k.date, workerId) } }
                }
            }
        }
        return done
    }

    private data class Key(val subject: Long, val date: LocalDate, val count: Int)

    private fun claim(kind: String, limit: Int): List<Key> = db.jdbi.inTransaction<List<Key>, Exception> { h ->
        h.createQuery(
            """
            UPDATE app.dirty_key d SET claimed_at = :now, claimed_by = :w
             WHERE (d.kind, d.subject_id, d.business_date) IN (
               SELECT kind, subject_id, business_date FROM app.dirty_key
                WHERE kind = :k AND (claimed_at IS NULL OR claimed_at < :stale) ORDER BY last_dirtied_at LIMIT :l FOR UPDATE SKIP LOCKED)
            RETURNING d.subject_id, d.business_date, d.dirty_count
            """,
        ).bind("now", clock.now()).bind("w", workerId).bind("k", kind).bind("stale", clock.now().minus(lease)).bind("l", limit)
            .map { rs, _ -> Key(rs.getLong(1), rs.getObject(2, LocalDate::class.java), rs.getInt(3)) }.list()
    }

    /** Runs project and drain until both are idle: the synchronous form tests and the nightly rebuild use. */
    fun runUntilIdle(): Int {
        var total = 0
        while (true) {
            val p = project()
            val d = drain()
            total += d
            if (p == 0 && d == 0) return total
        }
    }

    /** Queues a full rebuild of one business date (every route that has a route-day or any record that day): the repair tool. */
    fun requestRebuild(date: LocalDate): Int = db.jdbi.inTransaction<Int, Exception> { h ->
        h.createQuery(
            """
            SELECT route_id FROM app.route_day WHERE business_date = :d
            UNION SELECT route_id FROM app.visit WHERE business_date = :d AND route_id IS NOT NULL
            UNION SELECT route_id FROM app.memo WHERE business_date = :d AND route_id IS NOT NULL
            UNION SELECT route_id FROM app.due_collection WHERE business_date = :d AND route_id IS NOT NULL
            """,
        ).bind("d", date).mapTo(Long::class.java).list().onEach { h.execute("SELECT app.mark_dirty('route_day_agg', ?, ?, 'rebuild')", it, date) }.size
    }

    /** Background loop for the `worker` role: poll every [interval] (default 5 s keeps the 60 s freshness goal with room). */
    fun start(interval: Duration = Duration.ofSeconds(5)): ScheduledExecutorService {
        val ex = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "aggregation-worker").apply { isDaemon = true } }
        ex.scheduleWithFixedDelay({
            runCatching { refreshDimensionsIfDue(); runUntilIdle() }
                .onFailure { log.error("aggregation pass failed", it) }
        }, 0, interval.toMillis(), TimeUnit.MILLISECONDS)
        return ex
    }

    private var dimsAt = java.time.Instant.EPOCH

    /** Dimensions change rarely: refreshed at start and then every ten minutes, not on every poll. */
    private fun refreshDimensionsIfDue() {
        if (clock.now().isBefore(dimsAt.plus(Duration.ofMinutes(10)))) return
        db.jdbi.useHandle<Exception> { Aggregator.refreshDimensions(it) }
        dimsAt = clock.now()
    }

    private fun threshold(): Int = runCatching { config?.int("cfg.geo.suspicious_score_threshold") }.getOrNull() ?: Aggregator.DEFAULT_SUSPICIOUS_THRESHOLD

    companion object { const val CONSUMER = "dw-projector"; private const val MAX_TRACKED_GAP = 10_000L }
}
