package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.config.AuditWriter
import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.receiveStrict
import com.aktcl.aron.backend.platform.requestId
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import com.aktcl.aron.rules.BusinessDate
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.statement.SqlStatement
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64

/**
 * Dependencies of the generic `/v1/admin` master-data surface (F-API-035, 035b, 045). One instance serves every
 * `admin*Routes` function. [hashPassword] is the Argon2id hasher of backend:auth (`PasswordHasher::hash`), injected
 * so this module does not depend on the auth module. [requireReason] makes `change_reason` mandatory on every write
 * (the contract leaves it optional on patches and creates; the lead may switch it on).
 */
class AdminMasterDeps(
    val db: Database,
    val geo: GeoRepository,
    val reach: ReachResolver,
    val guard: AuthGuardDeps,
    val hashPassword: (String) -> String,
    val clock: AronClock = AronClock.SYSTEM,
    val requireReason: Boolean = false,
    /** Server config (`cfg.auth.temp_password_ttl_h`); null in tests that do not need it (default 24 h). */
    val config: com.aktcl.aron.backend.platform.ServerConfig? = null,
)

/** Hours a temporary password stays valid: `cfg.auth.temp_password_ttl_h`, default 24 (docs/19, docs/21) when the key is not registered yet. */
internal fun AdminMasterDeps.tempPasswordTtlH(): Long = runCatching { config?.int("cfg.auth.temp_password_ttl_h")?.toLong() }.getOrNull()?.takeIf { it in 1..720 } ?: 24L

/** Roles that read master data on the web (docs/24 s8.5). */
internal val MASTER_READERS = setOf(Role.TSO, Role.DMO, Role.WM, Role.TOP, Role.ANALYST, Role.SUPPORT, Role.ADMIN, Role.SUPERADMIN)
/** Master data writers (docs/24 s8.5: W for ADMIN and SUPERADMIN; a TSO only proposes radii, not in this surface). */
internal val MASTER_WRITERS = setOf(Role.ADMIN, Role.SUPERADMIN)
/** Users and scope: readers (ANALYST has none). */
internal val USER_READERS = setOf(Role.TSO, Role.DMO, Role.WM, Role.TOP, Role.SUPPORT, Role.ADMIN, Role.SUPERADMIN)

/** Per-request context handed to the entity hooks. */
internal class AdminCtx(val d: AdminMasterDeps, val p: AronPrincipal, val reach: Reach, val today: LocalDate, val requestId: String, val now: Instant)

internal fun admBad(pointer: String, code: String = "invalid_value", detail: String = "invalid $pointer"): Nothing =
    throw ApiProblem(ProblemCode.ERR_VALIDATION, detail, errors = listOf(FieldError(pointer, code)))

internal fun admNotFound(what: String = "not found"): Nothing = throw ApiProblem(ProblemCode.ERR_NOT_FOUND, what)

internal fun ApplicationCall.admPrincipal(allowed: Set<Role>, what: String): AronPrincipal =
    principal.also {
        if (it.role !in allowed) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "$what is not available to this role")
    }

internal fun AdminMasterDeps.ctx(call: ApplicationCall, p: AronPrincipal): AdminCtx {
    val now = clock.now()
    val today = BusinessDate.of(now.toEpochMilli()).toJavaLocalDate()
    return AdminCtx(this, p, reach.reach(p.userId, p.role, p.scopeVersion, today), today, call.requestId, now)
}

internal fun AdminCtx.audit(h: Handle, entity: String, id: String, action: String, before: JsonElement?, after: JsonElement?, reason: String?) =
    AuditWriter.write(h, p, entity, id, action, redactPii(before), redactPii(after), reason, requestId)

/** Personal data never goes into the audit trail in clear: the audit says that a field changed, not what it held. */
private val PII_KEYS = setOf("phone", "email", "contact_number", "nid", "tin", "trade_license", "password", "password_hash", "temporary_password")
internal fun redactPii(e: JsonElement?): JsonElement? = when (e) {
    is JsonObject -> JsonObject(e.mapValues { (k, v) -> if (k in PII_KEYS && v !is JsonNull) JsonPrimitive("[redacted]") else redactPii(v)!! })
    is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(e.map { redactPii(it)!! })
    else -> e
}

internal fun odt(i: Instant): OffsetDateTime = OffsetDateTime.ofInstant(i, ZoneOffset.UTC)

// ---- request parsing -------------------------------------------------------------------------------------------

internal enum class Kind { TEXT, LONG, INT, DOUBLE, BOOL, ENUM }

/**
 * One writable column of a master entity. [name] is the JSON member, [sqlName] the column (they differ for `parent_id`).
 * [digits] normalises Bengali digits before validation (phone numbers).
 */
internal class Col(
    val name: String, val kind: Kind, val sqlName: String = name, val required: Boolean = false, val nullable: Boolean = false,
    val onCreate: Boolean = true, val onPatch: Boolean = true, val min: Long = 0, val max: Long = Long.MAX_VALUE,
    val pattern: Regex? = null, val values: Set<String>? = null, val digits: Boolean = false, val minD: Double = -1e18, val maxD: Double = 1e18,
)

private val BN_DIGITS = "০১২৩৪৫৬৭৮৯"
internal fun normDigits(s: String): String = s.map { c -> BN_DIGITS.indexOf(c).let { if (it >= 0) ('0' + it) else c } }.joinToString("")

/** A strictly validated JSON body: unknown members are refused, every value is checked against its column. */
internal class Fields(val obj: JsonObject, allowed: Set<String>) {
    init { obj.keys.firstOrNull { it !in allowed }?.let { admBad("body.$it", "unknown_member", "unknown member $it") } }

    fun has(name: String) = name in obj

    /** The validated value of [c] (null for JSON null); call only when [has]. */
    fun value(c: Col): Any? {
        val el = obj.getValue(c.name)
        val ptr = "body.${c.name}"
        if (el is JsonNull) { if (!c.nullable) admBad(ptr, "required", "$ptr must not be null"); return null }
        if (el !is JsonPrimitive) admBad(ptr, "invalid_type")
        return when (c.kind) {
            Kind.TEXT -> {
                if (!el.isString) admBad(ptr, "invalid_type")
                val s = if (c.digits) normDigits(el.content) else el.content
                if (s.contains('\u0000')) admBad(ptr, "invalid_character")
                if (s.length < c.min || s.length > c.max) admBad(ptr, "length")
                if (c.pattern != null && !c.pattern.matches(s)) admBad(ptr, "pattern")
                s
            }
            Kind.ENUM -> el.takeIf { it.isString }?.content?.takeIf { it in c.values!! } ?: admBad(ptr, "invalid_value")
            Kind.LONG -> (if (el.isString) null else el.longOrNull)?.takeIf { it in c.min..c.max } ?: admBad(ptr, "out_of_range")
            Kind.INT -> (if (el.isString) null else el.longOrNull)?.takeIf { it in c.min..c.max }?.toInt() ?: admBad(ptr, "out_of_range")
            Kind.DOUBLE -> (if (el.isString) null else el.doubleOrNull)?.takeIf { it.isFinite() && it in c.minD..c.maxD } ?: admBad(ptr, "out_of_range")
            Kind.BOOL -> (if (el.isString) null else el.booleanOrNull) ?: admBad(ptr, "invalid_type")
        }
    }

    /** Values of the columns present in the body, keyed by column name; on create a missing required column is a 400. */
    fun read(cols: List<Col>, create: Boolean): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (c in cols) {
            if (create && !c.onCreate || !create && !c.onPatch) { if (has(c.name)) admBad("body.${c.name}", "unknown_member", "${c.name} cannot be set here"); continue }
            if (has(c.name)) out[c.sqlName] = value(c)
            else if (create && c.required) admBad("body.${c.name}", "required", "${c.name} is required")
        }
        return out
    }

    /** `change_reason` (10 to 500 characters), or null; required when [required]. */
    fun reason(required: Boolean, name: String = "change_reason"): String? {
        val el = obj[name]
        if (el == null || el is JsonNull) { if (required) admBad("body.$name", "required", "a reason of 10 to 500 characters is required"); return null }
        val s = (el as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: admBad("body.$name", "invalid_type")
        if (s.length !in 10..500) admBad("body.$name", "length", "a reason of 10 to 500 characters is required")
        return s
    }
}

internal suspend fun ApplicationCall.admBody(allowed: Set<String>): Fields = Fields(receiveStrict(JsonObject.serializer()), allowed)

/** `If-Match: "<version>"` (contract IfMatch, pattern `^"[0-9]{1,10}"$`); a missing or malformed header is a 400. */
internal fun ApplicationCall.admIfMatch(): Int {
    val h = request.headers["If-Match"] ?: admBad("header.If-Match", "required", "If-Match is required")
    return Regex("^\"([0-9]{1,10})\"$").matchEntire(h.trim())?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it <= Int.MAX_VALUE }?.toInt()
        ?: admBad("header.If-Match", "invalid_value", "If-Match must be a quoted version")
}

internal fun ApplicationCall.admId(name: String = "id"): Long = parameters[name]?.toLongOrNull()?.takeIf { it >= 1 } ?: admBad("path.$name")

internal fun ApplicationCall.admQueryLong(name: String): Long? = request.queryParameters[name]?.let { it.toLongOrNull()?.takeIf { v -> v >= 1 } ?: admBad("query.$name") }

internal fun ApplicationCall.admDate(name: String, source: String = "query"): LocalDate? =
    request.queryParameters[name]?.let { runCatching { LocalDate.parse(it) }.getOrNull()?.takeIf { d -> d.year in 1900..2200 } ?: admBad("$source.$name") }

internal fun parseDate(pointer: String, el: JsonElement?): LocalDate? =
    (el as? JsonPrimitive)?.takeIf { it.isString }?.let { runCatching { LocalDate.parse(it.content) }.getOrNull()?.takeIf { d -> d.year in 1900..2200 } ?: admBad(pointer) }

// ---- responses ---------------------------------------------------------------------------------------------------

internal suspend fun ApplicationCall.admRespond(status: HttpStatusCode, body: JsonElement, version: Int? = null) {
    if (version != null) response.header("ETag", "\"$version\"")
    respondText(body.toString(), ContentType.Application.Json, status)
}

internal fun jv(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is java.sql.Timestamp -> JsonPrimitive(v.toInstant().wire())
    is OffsetDateTime -> JsonPrimitive(v.toInstant().wire())
    is Instant -> JsonPrimitive(v.wire())
    is java.sql.Date -> JsonPrimitive(v.toLocalDate().toString())
    is LocalDate -> JsonPrimitive(v.toString())
    else -> JsonPrimitive(v.toString())
}

// ---- paging ------------------------------------------------------------------------------------------------------

private val MIN_TS: Instant = Instant.parse("1970-01-01T00:00:00Z")
private val MAX_TS: Instant = Instant.parse("9999-12-31T00:00:00Z")

/** Keyset cursor: `id` alone, or `(updated_at, id)` when the list is read with `updated_since`. */
internal data class AdminCursor(val at: Instant?, val id: Long) {
    fun encode(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(((at?.let { "${it.epochSecond}.${it.nano}" } ?: "") + "|" + id).toByteArray())

    companion object {
        fun decode(c: String, keyed: Boolean): AdminCursor = runCatching {
            val parts = String(Base64.getUrlDecoder().decode(c)).split('|')
            require(parts.size == 2)
            val id = parts[1].toLong()
            require(id >= 0 && keyed == parts[0].isNotEmpty())
            val at = if (parts[0].isEmpty()) null else parts[0].split('.').let { p ->
                require(p.size == 2)
                Instant.ofEpochSecond(p[0].toLong(), p[1].toLong()).also { require(it.isAfter(MIN_TS) && it.isBefore(MAX_TS)) }
            }
            AdminCursor(at, id)
        }.getOrElse { admBad("query.cursor") }
    }
}

internal fun ApplicationCall.admLimit(default: Int = 100, max: Int = 500): Int =
    request.queryParameters["limit"]?.let { it.toIntOrNull()?.takeIf { v -> v in 1..max } ?: admBad("query.limit", "out_of_range") } ?: default

internal fun ApplicationCall.admUpdatedSince(): Instant? = request.queryParameters["updated_since"]?.let {
    runCatching { Instant.parse(it) }.getOrNull()?.takeIf { t -> t.isAfter(MIN_TS) && t.isBefore(MAX_TS) } ?: admBad("query.updated_since")
}

internal fun ApplicationCall.admSearch(): String? = request.queryParameters["q"]?.also { if (it.length !in 2..80) admBad("query.q", "out_of_range") }
    ?.let { "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" }

internal fun ApplicationCall.admStatus(allowed: Set<String>): String? = request.queryParameters["status"]?.also { if (it !in allowed) admBad("query.status") }

// ---- SQL helpers -------------------------------------------------------------------------------------------------

internal fun <T : SqlStatement<T>> T.bindAny(name: String, v: Any?): T = if (v == null) bindNull(name, java.sql.Types.NULL) else bind(name, v)

/** A reach predicate on table alias `t`; list values are bound with `bindList` under [lists]. */
internal class Pred(val sql: String, val lists: Map<String, List<Long>> = emptyMap()) {
    companion object { val NONE = Pred("FALSE") }
}

internal fun idsPred(column: String, key: String, ids: Collection<Long>): Pred = if (ids.isEmpty()) Pred.NONE else Pred("$column IN (<$key>)", mapOf(key to ids.toList()))

internal fun <T : SqlStatement<T>> T.bindPred(p: Pred?): T { p?.lists?.forEach { (k, v) -> bindList(k, v) }; return this }

internal fun Handle.row(sql: String, vararg binds: Pair<String, Any?>): Map<String, Any?>? =
    createQuery(sql).also { q -> binds.forEach { q.bindAny(it.first, it.second) } }.mapToMap().findOne().orElse(null)

internal fun Handle.scalarLong(sql: String, vararg binds: Pair<String, Any?>): Long =
    createQuery(sql).also { q -> binds.forEach { q.bindAny(it.first, it.second) } }.mapTo(Long::class.java).one()

/** Translates a database constraint failure of a write into the contract's problem (the transaction has rolled back). */
internal fun <T> admWrite(block: () -> T): T = try {
    block()
} catch (e: ApiProblem) {
    throw e
} catch (e: Exception) {
    var c: Throwable? = e
    while (c != null) {
        if (c is java.sql.SQLException) {
            val msg = c.message.orEmpty()
            when (c.sqlState) {
                "23505" -> throw ApiProblem(ProblemCode.ERR_MASTER_DUPLICATE_CODE, "a row with this code or name already exists", errors = listOf(FieldError(when {
                    msg.contains("username") -> "body.username"; msg.contains("external_ref") -> "body.external_ref"; msg.contains("zone_id_name") -> "body.name"; else -> "body.code"
                }, "duplicate")))
                "22021", "22008", "22003", "22007", "22P02", "22P05" -> throw ApiProblem(ProblemCode.ERR_VALIDATION, "a value is not acceptable", errors = listOf(FieldError("body", "invalid_value")))
                "23P01" -> throw ApiProblem(ProblemCode.ERR_MASTER_OVERLAP, "the dates overlap an existing row")
                "23514" -> throw ApiProblem(ProblemCode.ERR_VALIDATION, "a value violates a data rule", errors = listOf(FieldError("body", "check_failed", Regex("\"([a-z_]+)\"").find(msg)?.groupValues?.get(1))))
                "23503" -> throw ApiProblem(ProblemCode.ERR_VALIDATION, "a referenced row does not exist", errors = listOf(FieldError("body", "unknown_reference")))
            }
        }
        c = c.cause?.takeIf { it !== c }
    }
    throw e
}

internal fun jsonOf(vararg kv: Pair<String, Any?>): JsonObject = JsonObject(kv.associate { it.first to jv(it.second) })
