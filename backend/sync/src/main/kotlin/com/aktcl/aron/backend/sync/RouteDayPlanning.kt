package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.DayPlan
import com.aktcl.aron.backend.platform.ServerConfig
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Route-day planning job (F-SYS-056, docs/24 s4.9, D24-26): from 00:05 Asia/Dhaka, one `route_day` per route held on
 * that business date (an assignment, primary or cover), `planned` by its visit kind and days (the effective
 * `route_planned` row, else the route's own) and the calendar (scoped holidays and make-up days, weekend), the same rules
 * as the bundle's planner (`DayPlan`). The job never fixes the target: `target_outlets` is frozen by the first bundle of
 * the day (s12.4). Idempotent and safe on several worker replicas: a row that exists (the job ran, or a bundle came
 * first) is never changed.
 */
class RouteDayPlanningJob(
    private val db: Database,
    private val config: ServerConfig,
    private val clock: AronClock = AronClock.SYSTEM,
    private val everyMin: Long = 5,
) {
    private val log = LoggerFactory.getLogger("aron.route-day")

    /** Plans [date]; returns the number of route-days created now. */
    fun planDay(date: LocalDate): Int = db.jdbi.inTransaction<Int, Exception> { h -> planIn(h, date, null) }

    /**
     * Plans [date] on [h] for [routeIds] only (null: every held route). Ingest calls it for a route-day it receives
     * records for before the job or a bundle created it, so the row always carries the planner's `planned`.
     */
    fun planIn(h: org.jdbi.v3.core.Handle, date: LocalDate, routeIds: Collection<Long>?): Int {
        val weekend = runCatching { config.value("cfg.calendar.weekend_days").jsonArray.mapNotNull { it.jsonPrimitive.intOrNull }.toSet() }
            .getOrDefault(DayPlan.DEFAULT_WEEKEND)
        return run {
            data class Row(
                val route: Long, val mask: Int, val active: Boolean, val override: Boolean, val chain: DayPlan.ZoneChain,
                val primary: Long?, val cover: Long?,
            )
            val rows = h.createQuery(
                """
                SELECT r.id, r.status = 'active' AS active,
                       COALESCE(CASE WHEN p.id IS NOT NULL THEN p.visit_days_mask ELSE r.visit_days_mask END, 0) AS mask,
                       p.id IS NOT NULL AS override,
                       z.id AS zone, t.id AS territory, d.id AS division, d.wing_id AS wing,
                       (SELECT a.user_id FROM app.route_assignment a WHERE a.route_id = r.id AND a.kind = 'primary' AND a.ended_at IS NULL
                          AND a.valid_from <= :d AND (a.valid_to IS NULL OR a.valid_to > :d) ORDER BY a.valid_from DESC, a.id DESC LIMIT 1) AS primary_user,
                       (SELECT a.user_id FROM app.route_assignment a WHERE a.route_id = r.id AND a.kind = 'cover' AND a.ended_at IS NULL
                          AND a.valid_from <= :d AND (a.valid_to IS NULL OR a.valid_to > :d) ORDER BY a.valid_from DESC, a.id DESC LIMIT 1) AS cover_user
                FROM app.route r
                JOIN app.zone z ON z.id = r.zone_id
                JOIN app.territory t ON t.id = z.territory_id
                JOIN app.division d ON d.id = t.division_id
                LEFT JOIN app.route_planned p ON p.route_id = r.id AND p.valid_from <= :d AND (p.valid_to IS NULL OR p.valid_to > :d)
                WHERE EXISTS (SELECT 1 FROM app.route_assignment a WHERE a.route_id = r.id AND a.ended_at IS NULL
                              AND a.valid_from <= :d AND (a.valid_to IS NULL OR a.valid_to > :d))
                  AND NOT EXISTS (SELECT 1 FROM app.route_day rd WHERE rd.route_id = r.id AND rd.business_date = :d)
                  ${if (routeIds == null) "" else "AND r.id = ANY(CAST(:only AS bigint[]))"}
                ORDER BY r.id
                """.trimIndent(),
            ).bind("d", date).also { q -> routeIds?.let { q.bindArray("only", Long::class.javaObjectType, it.toList()) } }.map { rs, _ ->
                Row(
                    rs.getLong("id"), rs.getInt("mask"), rs.getBoolean("active"), rs.getBoolean("override"),
                    DayPlan.ZoneChain(rs.getLong("zone"), rs.getLong("territory"), rs.getLong("division"), rs.getLong("wing")),
                    rs.getObject("primary_user") as Long?, rs.getObject("cover_user") as Long?,
                )
            }.list()
            if (rows.isEmpty()) return@run 0
            val calendar = h.createQuery("SELECT date, scope_type, scope_id, kind, selling_day FROM app.calendar_holiday WHERE date = :d AND revoked_at IS NULL")
                .bind("d", date).map { rs, _ ->
                    DayPlan.CalendarEntry(rs.getObject(1, LocalDate::class.java), DayPlan.CalendarScope.valueOf(rs.getString(2).uppercase()), rs.getLong(3), rs.getString(4), rs.getBoolean(5))
                }.list()
            var created = 0
            for (r in rows) {
                val planned = DayPlan.isPlanned(DayPlan.PlanRoute(r.route, r.mask, r.chain, r.active), date, calendar, weekend)
                // A cover with no primary is the route's acting holder; with a primary, the bundle of whoever fetches records it.
                val source = when { r.primary == null && r.cover != null -> "cover"; r.override -> "override"; else -> "schedule" }
                created += h.createUpdate(
                    """
                    INSERT INTO app.route_day (route_id, business_date, planned, planned_source, assigned_user_id, acting_user_id)
                    VALUES (:r, :d, :planned, :src, :assigned, :acting)
                    ON CONFLICT (route_id, business_date) DO NOTHING
                    """.trimIndent(),
                ).bind("r", r.route).bind("d", date).bind("planned", planned).bind("src", source)
                    .bind("assigned", r.primary).bind("acting", if (r.primary == null) r.cover else null).execute()
            }
            created
        }
    }

    /** Submitted route-days whose settle deadline passed without a batch become `sales_submitted` with a mismatch (s4.9). */
    fun settleExpired(): Int = db.jdbi.inTransaction<Int, Exception> { h ->
        val now = clock.now()
        val due = h.createQuery(
            """
            SELECT route_id, business_date, COALESCE(acting_user_id, assigned_user_id) FROM app.route_day
            WHERE submit_received_at IS NOT NULL AND sales_submitted_at IS NULL AND NOT submit_voided AND settle_deadline_at <= :now
              AND COALESCE(acting_user_id, assigned_user_id) IS NOT NULL
            ORDER BY settle_deadline_at LIMIT 500 FOR UPDATE SKIP LOCKED
            """.trimIndent(),
        ).bind("now", java.time.OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC))
            .map { rs, _ -> Triple(rs.getLong(1), rs.getObject(2, LocalDate::class.java), rs.getLong(3)) }.list()
        for ((route, date, user) in due) {
            DayStates.settle(h, user, route, date, now, parked = 0)
            DayStates.advance(h, route, date)
        }
        due.size
    }

    /** One tick: plans today once the Dhaka clock is past 00:05 (every tick is idempotent, so a restart catches up). */
    fun tick(): Int {
        runCatching { settleExpired() }.onFailure { log.error("settle of submitted route-days failed", it) }
        val local = clock.now().atZone(DHAKA)
        if (local.toLocalTime().isBefore(START)) return 0
        return planDay(local.toLocalDate())
    }

    fun start() {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "route-day-planning").apply { isDaemon = true } }.scheduleWithFixedDelay({
            runCatching { tick() }.onSuccess { if (it > 0) log.info("route-day planning created {} route-day(s)", it) }
                .onFailure { log.error("route-day planning failed", it) }
        }, 1, everyMin, TimeUnit.MINUTES)
    }

    companion object {
        private val DHAKA: ZoneId = ZoneId.of("Asia/Dhaka")
        val START: LocalTime = LocalTime.of(0, 5)
    }
}
