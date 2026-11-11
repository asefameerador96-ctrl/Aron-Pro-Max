package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.config.ConfigResolver
import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.WorkingCalendarRepo
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import com.aktcl.aron.rules.BusinessDate
import com.aktcl.aron.rules.CallTargets
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.time.YearMonth

/** Contract `CallTargetCard`: `target` is null when the config key is not set (the card prints a dash). */
@Serializable
data class CallTargetCardDto(val done: Int, val target: Int?)

/** Contract `CallTargets` (1.11.0, D-AMO-MAN-06). */
@Serializable
data class CallTargetsDto(
    val as_of: String, val business_date: String, val period: String,
    val today: CallTargetCardDto, val month: CallTargetCardDto, val control: CallTargetCardDto, val joint: CallTargetCardDto,
)

class CallTargetsDeps(
    val db: Database, val reach: ReachResolver, val config: ServerConfig, val resolver: ConfigResolver, val guard: AuthGuardDeps,
    val clock: AronClock = AronClock.SYSTEM,
)

private const val AMO_CALL_KEY = "cfg.amo.call_target_month"
private const val CONTROL_KEY = "cfg.amo.control_call_target_month"
private const val JOINT_KEY = "cfg.amo.joint_call_target_month"
private val PERIOD = Regex("^[0-9]{4}-(0[1-9]|1[0-2])$")

/**
 * `GET /v1/supervisor/call-targets?month=YYYY-MM` (contract getCallTargets, build contract s4.3). AMO only: the cards are the
 * caller's own work. The targets are the three config numbers resolved on the caller's chain (their zone, territory, role,
 * global); done = the caller's own closed visits of kind `amo_control_call` / `amo_joint_call` that are not voided and not
 * `abandoned` or `not_reached`, by Dhaka business date. Nothing is read from a client list.
 */
fun Route.callTargetsRoutes(d: CallTargetsDeps) {
    authenticated(d.guard) {
        get("/supervisor/call-targets") { call.respond(callTargets(call, d)) }
    }
}

private fun callTargets(call: ApplicationCall, d: CallTargetsDeps): CallTargetsDto {
    val p = call.principal
    if (p.role != Role.AMO) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "the call target cards are for an AMO")
    val now = d.clock.now()
    val today: LocalDate = BusinessDate.of(now.toEpochMilli()).toJavaLocalDate()
    val currentMonth = YearMonth.from(today)
    val month = when (val raw = call.request.queryParameters["month"]) {
        null -> currentMonth
        else -> if (PERIOD.matches(raw)) YearMonth.parse(raw) else throw ApiProblem(ProblemCode.ERR_VALIDATION, "month must be YYYY-MM", errors = listOf(FieldError("query.month", "invalid_value")))
    }
    if (month.isBefore(currentMonth.minusMonths(12))) throw ApiProblem(ProblemCode.ERR_VALIDATION, "month is more than 12 months back", errors = listOf(FieldError("query.month", "out_of_range")))
    if (month.isAfter(currentMonth)) throw ApiProblem(ProblemCode.ERR_VALIDATION, "month is in the future", errors = listOf(FieldError("query.month", "out_of_range")))

    val reach = d.reach.reach(p.userId, p.role, p.scopeVersion, today)
    val chain = d.resolver.subjectChain(p.userId, null, now)
    fun target(key: String): Long? = d.resolver.resolve(key, chain, now).value.takeIf { it !is JsonNull }
        ?.let { (it as? JsonPrimitive)?.intOrNull }?.takeIf { it >= 0 }?.toLong()
    val callMonthly = target(AMO_CALL_KEY); val controlMonthly = target(CONTROL_KEY); val jointMonthly = target(JOINT_KEY)

    // Today's share divides the CURRENT month's target by that month's working days at the caller's zone path (the common path of the zones).
    val workingDays = d.db.readJdbi.withHandle<Int, Exception> { h ->
        val cals = WorkingCalendarRepo.load(h, currentMonth, now, d.config)
        val paths = WorkingCalendarRepo.zonePaths(h, reach.zoneIds)
        cals.workingDays(WorkingCalendarRepo.commonPath(paths.values))
    }
    val from = month.atDay(1); val to = month.atEndOfMonth()
    val counts = d.db.readJdbi.withHandle<List<Int>, Exception> { h ->
        h.createQuery(
            """
            SELECT count(*) FILTER (WHERE v.business_date = :today) AS today_n,
                   count(*) FILTER (WHERE v.business_date BETWEEN :from AND :to) AS month_n,
                   count(*) FILTER (WHERE v.business_date BETWEEN :from AND :to AND v.visit_kind = 'amo_control_call') AS control_n,
                   count(*) FILTER (WHERE v.business_date BETWEEN :from AND :to AND v.visit_kind = 'amo_joint_call') AS joint_n
              FROM app.visit v
             WHERE v.user_id = :u AND v.voided_at IS NULL AND v.visit_kind IN ('amo_control_call', 'amo_joint_call')
               AND v.outcome_code IS NOT NULL AND v.outcome_code NOT IN ('abandoned', 'not_reached')
               AND (v.business_date = :today OR v.business_date BETWEEN :from AND :to)
            """,
        ).bind("u", p.userId).bind("today", today).bind("from", from).bind("to", to)
            .map { rs, _ -> listOf(rs.getInt("today_n"), rs.getInt("month_n"), rs.getInt("control_n"), rs.getInt("joint_n")) }.one()
    }
    fun card(done: Int, t: Long?) = CallTargetCardDto(done, t?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt())
    return CallTargetsDto(
        now.wire(), today.toString(), month.toString(),
        today = card(counts[0], CallTargets.todayTarget(callMonthly, workingDays)),
        month = card(counts[1], callMonthly),
        control = card(counts[2], controlMonthly),
        joint = card(counts[3], jointMonthly),
    )
}
