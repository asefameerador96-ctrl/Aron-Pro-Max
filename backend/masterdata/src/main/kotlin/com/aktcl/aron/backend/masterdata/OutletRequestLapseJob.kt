package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.rules.BusinessDate
import kotlinx.datetime.toJavaLocalDate
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * docs/24 s12.2 (BC-84): an outlet change request still `pending` or `verified` 30 days after it was captured lapses.
 * One guarded UPDATE per run (only those two states move, so a decision that races it wins or loses cleanly and the
 * job never touches a decided request), plus a `lapsed` event (via `job`, no actor) for each request it moved, in the
 * same transaction. Idempotent and safe on several worker replicas. Runs hourly in the worker.
 */
class OutletRequestLapseJob(private val db: Database, private val clock: AronClock = AronClock.SYSTEM, private val after: Duration = Duration.ofDays(30)) {
    private val log = LoggerFactory.getLogger("aron.outlet-request-lapse")

    /** Lapses the overdue requests; returns how many moved now. */
    fun runOnce(): Int = db.jdbi.inTransaction<Int, Exception> { h ->
        val now = clock.now()
        val at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
        h.createQuery(
            """
            WITH moved AS (
              UPDATE app.outlet_change_request SET status = 'lapsed', status_changed_at = :at
               WHERE status IN ('pending', 'verified') AND voided_at IS NULL AND captured_at < :cutoff
              RETURNING client_uuid)
            INSERT INTO app.outlet_request_event (request_uuid, event, actor_user_id, via, business_date, at, note)
            SELECT client_uuid, 'lapsed', NULL, 'job', :bd, :at, 'no decision within 30 days' FROM moved
            RETURNING request_uuid
            """.trimIndent(),
        ).bind("at", at).bind("cutoff", OffsetDateTime.ofInstant(now.minus(after), ZoneOffset.UTC))
            .bind("bd", BusinessDate.of(now.toEpochMilli()).toJavaLocalDate())
            .mapTo(String::class.java).list().size
    }

    fun start() {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "outlet-request-lapse").apply { isDaemon = true } }.scheduleWithFixedDelay({
            runCatching { runOnce() }.onSuccess { if (it > 0) log.info("outlet requests lapsed: {}", it) }.onFailure { log.error("outlet request lapse failed", it) }
        }, 7, 60, TimeUnit.MINUTES)
    }
}
