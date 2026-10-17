package com.aktcl.aron.backend.platform

import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import org.jdbi.v3.core.Handle
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * The hourly PII read budget (F-SYS-058, docs/21 s4.5): rows with unmasked personal columns a user may read per clock hour across list endpoints
 * (`cfg.pii.list_rows_per_hour`, a role value for the TSO). Every list endpoint that returns personal columns calls [charge] with the rows it is about to
 * return; a read that would pass the limit is refused with 429 and Retry-After and is NOT counted, so a refused read does not eat the budget. Atomic in SQL.
 */
object PiiReadBudget {
    /** The limit in force for [role] at [now]: the role's value that is effective now (closed rows and future-dated ones respected), else the global value. */
    fun limit(h: Handle, config: ServerConfig, role: Role, now: Instant): Int =
        h.createQuery(
            """
            SELECT (v.value #>> '{}')::int FROM app.cfg_value v JOIN app.role_def r ON r.ordinal = v.scope_id
             WHERE v.key = 'cfg.pii.list_rows_per_hour' AND v.scope_type = 'role' AND r.role = :role
               AND v.effective_from <= :now AND (v.effective_to IS NULL OR v.effective_to > :now)
             ORDER BY v.effective_from DESC, v.config_version DESC LIMIT 1
            """,
        ).bind("role", role.wire).bind("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).mapTo(Int::class.java).findOne().orElse(null)
            ?: runCatching { config.int("cfg.pii.list_rows_per_hour") }.getOrDefault(2000)

    fun charge(db: Database, config: ServerConfig, clock: AronClock, userId: Long, role: Role, rows: Int) {
        if (rows <= 0) return
        val now = clock.now(); val hour = OffsetDateTime.ofInstant(now.truncatedTo(ChronoUnit.HOURS), ZoneOffset.UTC)
        val ok = db.jdbi.inTransaction<Boolean, Exception> { h ->
            val limit = limit(h, config, role, now)
            rows <= limit && h.createQuery(
                """
                INSERT INTO app.pii_read_budget (user_id, hour_start, rows_read) VALUES (:u, :h, :n)
                ON CONFLICT (user_id, hour_start) DO UPDATE SET rows_read = app.pii_read_budget.rows_read + :n WHERE app.pii_read_budget.rows_read + :n <= :lim
                RETURNING rows_read
                """,
            ).bind("u", userId).bind("h", hour).bind("n", rows).bind("lim", limit).mapTo(Int::class.java).findOne().isPresent
        }
        if (!ok) {
            val retry = Duration.between(now, hour.toInstant().plusSeconds(3600)).seconds.coerceIn(1, 3600).toInt()
            throw ApiProblem(ProblemCode.ERR_RATE_LIMITED, "hourly limit of personal-data rows reached", retryAfterS = retry, headers = mapOf("Retry-After" to retry.toString()))
        }
    }
}
