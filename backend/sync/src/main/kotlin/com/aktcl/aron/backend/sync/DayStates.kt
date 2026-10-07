package com.aktcl.aron.backend.sync

import com.aktcl.aron.contract.Role
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.jdbi.v3.core.Handle
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Day state machine (F-SYS-016, docs/24 s4.9): `route_day.state` is derived from its event timestamps and only ever
 * moves forward (a late or repeated record never moves it back); submit void (rule 2) is the one way back and is not
 * done here.
 *
 * - `logged_in`: the first bundle (BundleService) or a `day_open` record with `offline_start`;
 * - `in_field`: the first check-in `attendance_event` or the first `visit` of the route-day;
 * - `synced`: a batch for the route-day was received and nothing is parked for it;
 * - `submit_pending_rows` then `sales_submitted`: a `day_submit` (scope route_day); sales are submitted once the
 *   server's outcomes reach the device counts in it and nothing is parked, or when `cfg.day.submit_settle_timeout_min`
 *   passes (then `submit_count_mismatch`).
 *
 * AMO and TSO days are `supervisor_day` rows (D24-54): their check-in, check-out and Sales Submit never touch a route-day.
 * One row per route and date, so an SR on two routes has two route-days.
 */
object DayStates {
    private val ORDER = listOf("not_started", "logged_in", "in_field", "synced", "submit_pending_rows", "sales_submitted", "final_submitted")
    private val SUPERVISORS = setOf(Role.AMO, Role.TSO)

    /** Route-days touched by one batch, settled once at its end. */
    class Touched {
        val routeDays = LinkedHashSet<Pair<Long, LocalDate>>()
    }

    private fun ts(i: Instant) = OffsetDateTime.ofInstant(i, ZoneOffset.UTC)

    /** A stored record's effect on its day; [planner] creates a missing route-day with the planner's `planned`. */
    fun afterStored(h: Handle, type: String, env: JsonObject, p: JsonObject, up: Uploader, now: Instant, planner: RouteDayPlanningJob, touched: Touched) {
        val date = env.str("business_date")?.let(LocalDate::parse) ?: return
        val captured = env.str("captured_at")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: now
        val route = env.long("route_id")
        val supervisor = up.role in SUPERVISORS
        if (supervisor && (type == "attendance_event" || (type == "day_submit" && p.str("scope") == "supervisor_day"))) {
            supervisorDay(h, type, p, up.userId, date, captured, now)
            return
        }
        if (type == "day_submit" && p.str("scope") == "supervisor_day") return // a field role has no supervisor-day
        val named: List<Long> = when {
            // day_open names the routes it opens; the envelope's route is one of them.
            type == "day_open" -> ((p["route_ids"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toLongOrNull() }.orEmpty() + listOfNotNull(route)).distinct()
            route != null -> listOf(route)
            // A check-in carries no route: it starts every route the user holds on the date (primary or cover).
            type == "attendance_event" -> h.createQuery("SELECT DISTINCT route_id FROM app.route_assignment WHERE user_id = :u AND valid_from <= :d AND (valid_to IS NULL OR valid_to > :d)")
                .bind("d", date).bind("u", up.userId).mapTo(Long::class.java).list()
            else -> emptyList()
        }
        // Only routes the uploader holds on the date (primary or cover): a device never moves another user's day.
        val routes = if (named.isEmpty()) named else h.createQuery(
            "SELECT DISTINCT route_id FROM app.route_assignment WHERE user_id = :u AND route_id = ANY(:r) AND valid_from <= :d AND (valid_to IS NULL OR valid_to > :d)",
        ).bind("u", up.userId).bindArray("r", Long::class.javaObjectType, named).bind("d", date).mapTo(Long::class.java).list()
        if (routes.isEmpty()) return
        planner.planIn(h, date, routes)
        routes.forEach { touched.routeDays += it to date }
        // Rule 4: a row stored after the zone-day's Final Submit is accepted and counted as late, never reopening the day
        // (cfg.day.late_sync_after_final_policy = accept_and_flag). Runs once per stored record, so a replay never counts twice.
        h.createUpdate(
            "UPDATE app.route_day SET late_rows_after_final = late_rows_after_final + 1 WHERE route_id = ANY(:r) AND business_date = :d AND final_submitted_at IS NOT NULL",
        ).bindArray("r", Long::class.javaObjectType, routes).bind("d", date).execute()
        h.createUpdate(
            "UPDATE app.final_submit f SET late_rows = late_rows + 1 FROM app.route r WHERE r.id = ANY(:r) AND f.zone_id = r.zone_id AND f.business_date = :d AND f.reopened_at IS NULL",
        ).bindArray("r", Long::class.javaObjectType, routes).bind("d", date).execute()
        val set = when (type) {
            "day_open" -> if (p.bool("offline_start") == true) "logged_in_at = COALESCE(logged_in_at, :at)" else null
            "visit" -> "in_field_at = COALESCE(in_field_at, :at)"
            "attendance_event" -> if (p.str("kind") == "check_in") "in_field_at = COALESCE(in_field_at, :at)" else null
            "day_submit" -> "submit_received_at = COALESCE(submit_received_at, :now), rows_awaited = :awaited, " +
                "settle_deadline_at = COALESCE(settle_deadline_at, :deadline), submit_cycle = GREATEST(submit_cycle, :cycle)"
            else -> null
        } ?: return
        // TypeCounts has no maximum: Long sums, clamped to the int column (never an overflow into the CHECK).
        val awaited = ((p["device_counts"] as? JsonObject)?.values?.sumOf { ((it as? JsonPrimitive)?.longOrNull ?: 0L).coerceAtLeast(0L) } ?: 0L)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        h.createUpdate("UPDATE app.route_day SET $set WHERE route_id = ANY(:r) AND business_date = :d")
            .bindArray("r", Long::class.javaObjectType, routes).bind("d", date).bind("at", ts(captured)).bind("now", ts(now))
            .bind("awaited", awaited).bind("cycle", p.int("submit_cycle") ?: 1)
            .bind("deadline", ts(now.plus(Duration.ofMinutes(settleMinutes))))
            .execute()
    }

    /** Settle timeout (`cfg.day.submit_settle_timeout_min`, 30); set by the ingest service from config. */
    @Volatile var settleMinutes: Long = 30

    private fun supervisorDay(h: Handle, type: String, p: JsonObject, userId: Long, date: LocalDate, captured: Instant, now: Instant) {
        h.createUpdate("INSERT INTO app.supervisor_day (user_id, business_date) VALUES (:u, :d) ON CONFLICT (user_id, business_date) DO NOTHING")
            .bind("u", userId).bind("d", date).execute()
        val set = when {
            type == "attendance_event" && p.str("kind") == "check_in" -> "checked_in_at = COALESCE(checked_in_at, :at)"
            type == "attendance_event" && p.str("kind") == "check_out" -> "checked_out_at = GREATEST(checked_out_at, :at)"
            type == "day_submit" -> "submit_received_at = COALESCE(submit_received_at, :now), sales_submitted_at = COALESCE(sales_submitted_at, :now), " +
                "submit_cycle = GREATEST(submit_cycle, :cycle)"
            else -> return
        }
        h.createUpdate("UPDATE app.supervisor_day SET $set WHERE user_id = :u AND business_date = :d")
            .bind("u", userId).bind("d", date).bind("at", ts(captured)).bind("now", ts(now)).bind("cycle", p.int("submit_cycle") ?: 1).execute()
    }

    /**
     * End of a batch: every touched route-day records the batch, becomes `synced` when nothing is parked for it, and a
     * submitted one settles; then each state moves forward to what its timestamps say.
     */
    fun afterBatch(h: Handle, userId: Long, touched: Touched, now: Instant) {
        for ((route, date) in touched.routeDays) {
            val parked = h.createQuery(
                "SELECT count(*) FROM app.sync_rejected WHERE user_id = :u AND route_id = :r AND business_date = :d AND retryable AND stored_at IS NULL",
            ).bind("u", userId).bind("r", route).bind("d", date).mapTo(Long::class.java).one()
            h.createUpdate(
                "UPDATE app.route_day SET last_batch_at = :now${if (parked == 0L) ", synced_at = COALESCE(synced_at, :now)" else ""} WHERE route_id = :r AND business_date = :d",
            ).bind("now", ts(now)).bind("r", route).bind("d", date).execute()
            settle(h, userId, route, date, now, parked)
            advance(h, route, date)
        }
    }

    /**
     * A submitted route-day becomes `sales_submitted` when nothing is parked and the server holds at least as many
     * outcomes per type as the device counted (the user's date: the registry has no route), or when the deadline passed
     * (then with `submit_count_mismatch`). Also run by the worker for deadlines that pass without a batch.
     */
    fun settle(h: Handle, userId: Long, route: Long, date: LocalDate, now: Instant, parked: Long) {
        val row = h.createQuery(
            "SELECT settle_deadline_at FROM app.route_day WHERE route_id = :r AND business_date = :d AND submit_received_at IS NOT NULL AND sales_submitted_at IS NULL AND NOT submit_voided",
        ).bind("r", route).bind("d", date).map { rs, _ -> rs.getObject(1, OffsetDateTime::class.java)?.toInstant() }.findOne().orElse(null) ?: return
        // The stored day_submit of the newest cycle holds the device counts (app.route_day_event, never memory).
        val counts = h.createQuery(
            "SELECT device_counts::text FROM app.route_day_event WHERE kind = 'day_submit' AND scope = 'route_day' AND route_id = :r AND business_date = :d AND voided_at IS NULL ORDER BY submit_cycle DESC, id DESC LIMIT 1",
        ).bind("r", route).bind("d", date).mapTo(String::class.java).findOne().orElse(null)
            ?.let { kotlinx.serialization.json.Json.parseToJsonElement(it) as? JsonObject }
        val reached = parked == 0L && counts != null && counts.all { (type, v) ->
            val want = (v as? JsonPrimitive)?.longOrNull ?: Long.MAX_VALUE // unreadable: never reached, the timeout decides
            val have = h.createQuery("SELECT count(*) FROM app.ingest_registry WHERE user_id = :u AND business_date = :d AND record_type = :t AND status IN ('accepted','voided','rejected','quarantined')")
                .bind("u", userId).bind("d", date).bind("t", type).mapTo(Long::class.java).one()
            have >= want
        }
        val timedOut = !now.isBefore(row)
        if (!reached && !timedOut) return
        h.createUpdate("UPDATE app.route_day SET sales_submitted_at = :now, submit_count_mismatch = :mm WHERE route_id = :r AND business_date = :d AND sales_submitted_at IS NULL")
            .bind("now", ts(now)).bind("mm", !reached).bind("r", route).bind("d", date).execute()
    }

    /**
     * Moves the state forward to the furthest one its timestamps reach; never back. A move marks the route-day dirty
     * (F-SYS-086), so a state change without a stored record (the worker's settle timeout) still reaches the tile.
     */
    fun advance(h: Handle, route: Long, date: LocalDate) {
        val moved = h.createUpdate(
            """
            UPDATE app.route_day SET state = s.next FROM (
              SELECT id, CASE WHEN final_submitted_at IS NOT NULL THEN 'final_submitted'
                              WHEN sales_submitted_at IS NOT NULL THEN 'sales_submitted'
                              WHEN submit_received_at IS NOT NULL AND NOT submit_voided THEN 'submit_pending_rows'
                              WHEN synced_at IS NOT NULL AND in_field_at IS NOT NULL THEN 'synced'
                              WHEN in_field_at IS NOT NULL THEN 'in_field'
                              WHEN logged_in_at IS NOT NULL THEN 'logged_in'
                              ELSE 'not_started' END AS next
              FROM app.route_day WHERE route_id = :r AND business_date = :d
            ) s
            WHERE app.route_day.id = s.id
              AND array_position(CAST(:o AS text[]), s.next) > array_position(CAST(:o AS text[]), app.route_day.state)
            """.trimIndent(),
        ).bind("r", route).bind("d", date).bindArray("o", String::class.java, ORDER).execute()
        if (moved > 0) h.execute("SELECT app.mark_dirty('route_day_agg', ?, ?, 'route_day_state')", route, date)
    }
}
