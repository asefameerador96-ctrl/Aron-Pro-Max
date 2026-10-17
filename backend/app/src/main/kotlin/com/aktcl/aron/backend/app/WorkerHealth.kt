package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.analytics.AggregationWorker
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.rules.BusinessDate
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.datetime.toJavaLocalDate
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * OPS-02 / OPS-03 (docs/18 s6.6; audit docs/audit/production-readiness-2026-10-15.md): the worker's heartbeat and
 * liveness, and the first application gauges.
 *
 * Every minute [beat] reads the gauges and logs one line on `aron.worker`:
 * `heartbeat outbox_depth=<events not yet projected> outbox_age_s=<age of the oldest> dirty_unclaimed=<keys waiting>
 * dirty_dead=<dead-lettered keys> registry_oldest_days=<oldest ingest_registry row> registry_window_days=<cfg>
 * bundle_coverage_pct=<today's snapshots over active field users>`.
 * The Container Apps liveness probe calls `GET /health/live` on the worker ([workerServer]; no ingress, the probe reaches
 * the container port): 200 while the last beat is younger than [staleAfter] (the scheduler thread runs and the database
 * answered), 503 otherwise, so a hung replica is restarted. The alerts (infra/modules/alerts.bicep) read the lines:
 * no heartbeat for 5 minutes, outbox age above 300 s, the registry pruning behind its window.
 */
class WorkerHealth(
    private val db: Database,
    private val clock: AronClock = AronClock.SYSTEM,
    private val staleAfter: Duration = Duration.ofMinutes(3),
) {
    private val log = LoggerFactory.getLogger("aron.worker")

    @Volatile var lastBeat: Instant? = null
        private set

    @Volatile var last: Gauges? = null
        private set

    data class Gauges(
        val outboxDepth: Long, val outboxAgeS: Long, val dirtyUnclaimed: Long, val dirtyDead: Long,
        val registryOldestDays: Long?, val registryWindowDays: Int, val bundleCoveragePct: Double,
    ) {
        /** The heartbeat line; every value an integer the alert queries extract with a regex. */
        fun line(): String = "heartbeat outbox_depth=$outboxDepth outbox_age_s=$outboxAgeS dirty_unclaimed=$dirtyUnclaimed dirty_dead=$dirtyDead " +
            "registry_oldest_days=${registryOldestDays ?: 0} registry_window_days=$registryWindowDays bundle_coverage_pct=${"%.0f".format(Locale.ROOT, bundleCoveragePct)}"
    }

    /** The gauges of now, in five short reads. */
    fun gauges(): Gauges = db.jdbi.withHandle<Gauges, Exception> { h ->
        val now = clock.now()
        val outbox = h.createQuery(
            "SELECT count(*), min(created_at) FROM app.domain_event WHERE id > COALESCE((SELECT last_event_id FROM app.event_consumer WHERE consumer = :c), 0)",
        ).bind("c", AggregationWorker.CONSUMER).map { rs, _ -> rs.getLong(1) to rs.getObject(2, OffsetDateTime::class.java)?.toInstant() }.one()
        val dirty = h.createQuery("SELECT count(*) FILTER (WHERE claimed_at IS NULL AND dead_at IS NULL), count(*) FILTER (WHERE dead_at IS NOT NULL) FROM app.dirty_key")
            .map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.one()
        val oldest = h.createQuery("SELECT min(received_at) FROM app.ingest_registry WHERE status <> 'voided'").mapTo(OffsetDateTime::class.java).findOne().orElse(null)
        val window = h.createQuery(
            """
            SELECT COALESCE(
              (SELECT (value #>> '{}')::int FROM app.cfg_value WHERE key = 'cfg.retention.ingest_registry_days' AND scope_type = 'global' AND effective_from <= now() AND (effective_to IS NULL OR effective_to > now()) ORDER BY effective_from DESC LIMIT 1),
              (SELECT (default_value #>> '{}')::int FROM app.cfg_key WHERE key = 'cfg.retention.ingest_registry_days'), 45)
            """.trimIndent(),
        ).mapTo(Int::class.java).one()
        val today = BusinessDate.of(now.toEpochMilli()).toJavaLocalDate()
        val cov = h.createQuery(
            "SELECT count(*), count(*) FILTER (WHERE EXISTS (SELECT 1 FROM app.bundle_snapshot s WHERE s.user_id = u.id AND s.business_date = :d)) FROM app.app_user u WHERE u.status = 'active' AND u.role IN ('SR', 'AMO', 'TSO')",
        ).bind("d", today).map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.one()
        Gauges(
            outboxDepth = outbox.first,
            outboxAgeS = outbox.second?.let { Duration.between(it, now).seconds.coerceAtLeast(0) } ?: 0L,
            dirtyUnclaimed = dirty.first, dirtyDead = dirty.second,
            registryOldestDays = oldest?.let { Duration.between(it.toInstant(), now).toDays().coerceAtLeast(0) },
            registryWindowDays = window,
            bundleCoveragePct = if (cov.first == 0L) 100.0 else cov.second * 100.0 / cov.first,
        )
    }

    /** One heartbeat: the gauges, the line, the instant the probe checks. A failed read is logged and leaves [lastBeat] as it was (the probe then goes stale). */
    fun beat() {
        val g = gauges()
        last = g
        lastBeat = clock.now()
        log.info(g.line())
    }

    /** True while the last beat is younger than [staleAfter]. */
    fun alive(now: Instant = clock.now()): Boolean = lastBeat?.let { !it.isBefore(now.minus(staleAfter)) } == true

    fun start(everySeconds: Long = 60): ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "worker-heartbeat").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({ runCatching { beat() }.onFailure { e -> log.error("heartbeat failed", e) } }, 0, everySeconds, TimeUnit.SECONDS)
        }
}

/** The worker's probe routes: `/health` (the process answers) and `/health/live` (the heartbeat is fresh). */
fun Application.workerHealthRoutes(health: WorkerHealth) {
    routing {
        get("/health") { call.respondText("ok") }
        get("/health/live") {
            val beat = health.lastBeat
            if (health.alive()) call.respondText("ok: last heartbeat $beat")
            else call.respondText("stale: last heartbeat ${beat ?: "none yet"}", status = HttpStatusCode.ServiceUnavailable)
        }
    }
}

/** The worker's tiny HTTP server for the probes (no ingress is configured for it). */
fun workerServer(health: WorkerHealth, port: Int) =
    embeddedServer(Netty, configure = { connectors.add(EngineConnectorBuilder().apply { this.port = port }) }) { workerHealthRoutes(health) }
