package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.contract.ProblemCode
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.toJavaLocalDate
import com.aktcl.aron.rules.BusinessDate
import org.jdbi.v3.core.statement.SqlStatement
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/** Shared helpers of the backend-admin rows (visit plans, leave, feedback, tutorials, support upload, admin content). */
internal object AdminSupport {
    val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    val CODE = Regex("^[a-z][a-z0-9_]{1,40}$")

    fun bad(pointer: String, code: String = "invalid_value"): Nothing =
        throw ApiProblem(ProblemCode.ERR_VALIDATION, "invalid $pointer", errors = listOf(FieldError(pointer, code)))

    fun outOfScope(what: String = "outside your reach"): ApiProblem = ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, what)

    fun forbidden(what: String): ApiProblem = ApiProblem(ProblemCode.ERR_FORBIDDEN, what)

    /** PostgreSQL text cannot hold U+0000; a body that carries one is a 400, never a 500 (platform-wide fix requested: docs/requests/backend-admin-nul-in-text.md). */
    fun noNul(pointer: String, vararg values: String?) { if (values.any { it != null && it.contains('\u0000') }) bad(pointer, "invalid_character") }

    fun uuid(value: String, pointer: String): UUID {
        if (!UUID_V4.matches(value)) bad(pointer)
        return UUID.fromString(value)
    }

    fun date(value: String, pointer: String): LocalDate =
        runCatching { LocalDate.parse(value) }.getOrNull()?.takeIf { it.year in 2000..2100 } ?: bad(pointer)

    fun today(clock: AronClock): LocalDate = BusinessDate.of(clock.now().toEpochMilli()).toJavaLocalDate()

    fun utc(clock: AronClock): OffsetDateTime = OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)

    fun queryDate(call: ApplicationCall, name: String): LocalDate? = call.request.queryParameters[name]?.let { date(it, "query.$name") }

    fun queryId(call: ApplicationCall, name: String): Long? = call.request.queryParameters[name]?.let {
        it.toLongOrNull()?.takeIf { v -> v >= 1 } ?: bad("query.$name")
    }

    fun limit(call: ApplicationCall, default: Int = 100, max: Int = 500): Int =
        call.request.queryParameters["limit"]?.let { it.toIntOrNull()?.takeIf { v -> v in 1..max } ?: bad("query.limit", "out_of_range") } ?: default

    /** `from`/`to` window: `to` at most 92 days after `from` (contract ToDate). */
    fun window(call: ApplicationCall): Pair<LocalDate?, LocalDate?> {
        val from = queryDate(call, "from"); val to = queryDate(call, "to")
        if (from != null && to != null && (to.isBefore(from) || to.isAfter(from.plusDays(92)))) bad("query.to", "out_of_range")
        return from to to
    }

    fun encodeCursor(vararg parts: Any): String = Base64.getUrlEncoder().withoutPadding().encodeToString(parts.joinToString("|").toByteArray())

    /** A malformed or tampered cursor is 400 ERR_VALIDATION, never a 500. */
    fun decodeCursor(call: ApplicationCall, parts: Int): List<String>? {
        val raw = call.request.queryParameters["cursor"] ?: return null
        return runCatching {
            if (raw.length > 512 || !raw.matches(Regex("^[A-Za-z0-9_-]+$"))) error("shape")
            String(Base64.getUrlDecoder().decode(raw)).split('|').also { require(it.size == parts) }
        }.getOrElse { bad("query.cursor") }
    }

    fun reach(r: ReachResolver, call: ApplicationCall, clock: AronClock): Reach {
        val p = call.principal
        return r.reach(p.userId, p.role, p.scopeVersion, today(clock))
    }

    /** Zone of a user: the home zone, else the zone of the primary route assignment valid today (same rule as the device-OTP panel). */
    fun userZone(alias: String) =
        "COALESCE($alias.home_zone_id, (SELECT r.zone_id FROM app.route_assignment a JOIN app.route r ON r.id = a.route_id WHERE a.user_id = $alias.id AND a.ended_at IS NULL " +
            "AND (a.valid_to IS NULL OR a.valid_to > :today) AND a.kind = 'primary' AND a.valid_from <= :today ORDER BY a.valid_from DESC LIMIT 1))"

    /** SQL predicate over the app_user alias [alias] (record owner): the owner's zone is inside the caller's reach. */
    fun ownerInReach(reach: Reach, alias: String): String = when {
        reach.national -> "TRUE"
        reach.zoneIds.isEmpty() -> "FALSE"
        else -> "${userZone(alias)} = ANY(:reach_zones)"
    }

    fun <T : SqlStatement<T>> T.bindOwnerReach(reach: Reach, today: LocalDate): T {
        var s = this.bind("today", today)
        if (!reach.national && reach.zoneIds.isNotEmpty()) s = s.bindArray("reach_zones", Long::class.javaObjectType, reach.zoneIds.toList())
        return s
    }

    /** Zone of one user (null when unknown); used to decide a record's owner against the caller's reach. */
    fun zoneOfUser(h: org.jdbi.v3.core.Handle, userId: Long, today: LocalDate): Pair<Boolean, Long?> =
        h.createQuery("SELECT u.role, ${userZone("u")} AS zone_id FROM app.app_user u WHERE u.id = :u").bind("u", userId).bind("today", today)
            .map { rs, _ -> true to (rs.getObject("zone_id") as Long?) }.findOne().orElse(false to null)

    fun ownerInReachOrThrow(reach: Reach, h: org.jdbi.v3.core.Handle, ownerId: Long, today: LocalDate) {
        if (reach.national) {
            return
        }
        val (exists, zone) = zoneOfUser(h, ownerId, today)
        if (!exists || zone == null || zone !in reach.zoneIds) throw outOfScope()
    }

    fun intConfig(c: ServerConfig, key: String, fallback: Int): Int = runCatching { c.int(key) }.getOrDefault(fallback)

    fun reason(value: String?, pointer: String = "body.change_reason"): String {
        val r = value?.trim().orEmpty()
        if (r.length !in 10..500) throw ApiProblem(ProblemCode.ERR_VALIDATION, "a reason of 10 to 500 characters is required", errors = listOf(FieldError(pointer, "length")))
        return r
    }

    /** `If-Match` as the quoted row version (contract IfMatch); missing or malformed is 400, a stale value 412 at the call site. */
    fun ifMatch(call: ApplicationCall): Int {
        val h = call.request.headers["If-Match"] ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "If-Match is required", errors = listOf(FieldError("header.If-Match", "required")))
        return Regex("^\"([0-9]{1,10})\"$").matchEntire(h)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it <= Int.MAX_VALUE }?.toInt()
            ?: bad("header.If-Match")
    }

    fun preconditionFailed(): ApiProblem = ApiProblem(ProblemCode.ERR_PRECONDITION_FAILED, "the row changed since you read it")
}
