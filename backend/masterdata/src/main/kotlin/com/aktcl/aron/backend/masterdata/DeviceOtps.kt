package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.config.AuditWriter
import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.RequestJson
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.requestId
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import com.aktcl.aron.rules.BusinessDate
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM for the device-binding OTP the TSO reads on the web panel (docs/24 s8.1). Layout: 12-byte nonce, then
 * ciphertext and tag; the AAD binds the row to its user, so a cipher copied to another user's row does not open.
 * REQUEST (backend-core, F-SYS-003): the login path that creates an OTP at a bind attempt must use this class so the
 * panel can read it; the key is derived from the token signing secret (label `aron-device-otp-v1`).
 */
class OtpCipher(secret: ByteArray) {
    private val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret), "AES")
    private val rng = SecureRandom()

    fun seal(otp: String, userId: Long): ByteArray {
        val nonce = ByteArray(12).also(rng::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce)); updateAAD("device_otp:$userId".toByteArray()) }
        return nonce + c.doFinal(otp.toByteArray())
    }

    fun open(blob: ByteArray, userId: Long): String? = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(0, 12))); updateAAD("device_otp:$userId".toByteArray()) }
        String(c.doFinal(blob, 12, blob.size - 12))
    }.getOrNull()

    /** Keyed verifier stored next to the cipher (never a bare hash: four digits fall to a 10^4 search). The bind check recomputes it. */
    fun mac(otp: String, userId: Long): ByteArray = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal("otp-verify:$userId:$otp".toByteArray())

    fun digits(length: Int): String = (1..length).joinToString("") { rng.nextInt(10).toString() }
}

@Serializable
data class DeviceOtpDto(
    val user_id: Long, val username: String, val full_name: String, val zone_id: Long?, val otp: String?, val device_model: String?,
    val created_at: String, val expires_at: String, val attempts: Int,
)

@Serializable
data class DeviceOtpPage(val items: List<DeviceOtpDto>, val next_cursor: String?)

@Serializable
data class DeviceOtpIssueIn(val user_id: Long, val reason: String)

class DeviceOtpDeps(val db: Database, val reach: ReachResolver, val cipher: OtpCipher, val config: ServerConfig, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM)

private val OTP_VIEWERS = setOf(Role.TSO, Role.DMO, Role.WM, Role.SUPPORT, Role.ADMIN, Role.SUPERADMIN)
private val OTP_ISSUERS = setOf(Role.TSO, Role.SUPPORT, Role.ADMIN, Role.SUPERADMIN)
/** A repeated identical issue request inside this window returns the OTP already issued instead of replacing it. */
private const val REPLAY_WINDOW_S = 60L

/** `GET` and `POST /v1/admin/device-otps` (contract listDeviceOtps, issueDeviceOtp). */
fun Route.deviceOtpRoutes(d: DeviceOtpDeps) {
    authenticated(d.guard) {
        get("/admin/device-otps") { call.respond(listOtps(call, d)) }
        post("/admin/device-otps") { call.respond(HttpStatusCode.Created, issue(call, d)) }
    }
}

private fun bad(pointer: String, code: String = "invalid_value"): Nothing = throw ApiProblem(ProblemCode.ERR_VALIDATION, "invalid $pointer", errors = listOf(FieldError(pointer, code)))

private fun reachZones(d: DeviceOtpDeps, call: ApplicationCall): Pair<Boolean, Set<Long>> {
    val p = call.principal
    val r = d.reach.reach(p.userId, p.role, p.scopeVersion, BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate())
    return r.national to r.zoneIds
}

/** Zone of a user for the panel: the home zone, else the zone of the primary route assignment. */
private const val USER_ZONE = "COALESCE(u.home_zone_id, (SELECT r.zone_id FROM app.route_assignment a JOIN app.route r ON r.id = a.route_id WHERE a.user_id = u.id AND a.ended_at IS NULL AND (a.valid_to IS NULL OR a.valid_to > :today) AND a.kind = 'primary' AND a.valid_from <= :today ORDER BY a.valid_from DESC LIMIT 1))"

private fun listOtps(call: ApplicationCall, d: DeviceOtpDeps): DeviceOtpPage {
    val p = call.principal
    if (p.role !in OTP_VIEWERS) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "the device OTP panel is not available to this role")
    val q = call.request.queryParameters
    val limit = q["limit"]?.let { it.toIntOrNull()?.takeIf { v -> v in 1..200 } ?: bad("query.limit", "out_of_range") } ?: 50
    val zoneSel = q["zone_id"]?.let { it.toLongOrNull()?.takeIf { v -> v >= 1 } ?: bad("query.zone_id") }
    val search = q["q"]?.also { if (it.length !in 2..80) bad("query.q", "out_of_range") }
    val cursor = q["cursor"]?.let { it.toLongOrNull()?.takeIf { v -> v >= 0 } ?: bad("query.cursor") }
    val (national, zones) = reachZones(d, call)
    if (zoneSel != null && !national && zoneSel !in zones) throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "zone outside your reach")
    if (!national && zones.isEmpty()) return DeviceOtpPage(emptyList(), null)
    val now = OffsetDateTime.ofInstant(d.clock.now(), ZoneOffset.UTC)
    val today = BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate()
    val maxAttempts = d.config.int("cfg.auth.otp_max_attempts")
    val rows = d.db.jdbi.inTransaction<List<Pair<DeviceOtpDto, Long>>, Exception> { h ->
        val where = mutableListOf("u.role IN ('SR','AMO')", "u.status = 'active'")
        if (zoneSel != null) where += "$USER_ZONE = :zone" else if (!national) where += "$USER_ZONE IN (<zones>)"
        if (search != null) where += "(u.username ILIKE :s OR u.full_name ILIKE :s)"
        if (cursor != null) where += "u.id > :c"
        val sql = "SELECT u.id, u.username, u.full_name, $USER_ZONE AS zone_id, o.otp_cipher, o.device_model, o.created_at, o.expires_at, o.attempts FROM app.app_user u " +
            "LEFT JOIN LATERAL (SELECT * FROM app.device_otp x WHERE x.user_id = u.id AND x.consumed_at IS NULL AND x.revoked_at IS NULL AND x.expires_at > :now AND x.attempts < :maxAtt ORDER BY x.created_at DESC LIMIT 1) o ON TRUE " +
            "WHERE ${where.joinToString(" AND ")} ORDER BY u.id LIMIT :lim"
        val query = h.createQuery(sql).bind("now", now).bind("lim", limit + 1).bind("today", today).bind("maxAtt", maxAttempts)
        if (zoneSel != null) query.bind("zone", zoneSel) else if (!national) query.bindList("zones", zones.toList())
        if (search != null) query.bind("s", "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
        if (cursor != null) query.bind("c", cursor)
        val out = query.map { rs, _ ->
            val uid = rs.getLong("id")
            val cipher = rs.getBytes("otp_cipher")
            DeviceOtpDto(
                uid, rs.getString("username"), rs.getString("full_name"), rs.getObject("zone_id") as Long?, cipher?.let { d.cipher.open(it, uid) }, rs.getString("device_model"),
                (rs.getObject("created_at", OffsetDateTime::class.java)?.toInstant() ?: d.clock.now()).wire(),
                (rs.getObject("expires_at", OffsetDateTime::class.java)?.toInstant() ?: d.clock.now()).wire(), rs.getInt("attempts"),
            ) to uid
        }.list()
        // One aggregated audit event per page view; the OTP values themselves are never logged.
        AuditWriter.write(h, p, "device_otp", "page", "view", null, buildJsonObject { put("rows", minOf(out.size, limit)); put("live", out.take(limit).count { it.first.otp != null }) }, null, call.requestId)
        out
    }
    val page = rows.take(limit)
    return DeviceOtpPage(page.map { it.first }, if (rows.size > limit) page.last().second.toString() else null)
}

private suspend fun issue(call: ApplicationCall, d: DeviceOtpDeps): DeviceOtpDto {
    val p = call.principal
    if (p.role !in OTP_ISSUERS) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "not allowed to issue device OTPs")
    val req = try { RequestJson.decodeFromString<DeviceOtpIssueIn>(call.receiveText()) } catch (e: kotlinx.serialization.SerializationException) {
        throw ApiProblem(ProblemCode.ERR_VALIDATION, "malformed request body", errors = listOf(FieldError("body", "invalid_body")))
    }
    if (req.user_id < 1) bad("body.user_id")
    if (req.reason.length > 500 || req.reason.trim().length < 10) throw ApiProblem(ProblemCode.ERR_VALIDATION, "a reason of 10 to 500 characters is required", errors = listOf(FieldError("body.reason", "length")))
    val (national, zones) = reachZones(d, call)
    val length = d.config.int("cfg.auth.otp_length")
    val ttlMin = d.config.int("cfg.auth.otp_ttl_min")
    val now = d.clock.now()
    val today = BusinessDate.of(now.toEpochMilli()).toJavaLocalDate()
    val maxAttempts = d.config.int("cfg.auth.otp_max_attempts")
    return d.db.jdbi.inTransaction<DeviceOtpDto, Exception> { h ->
        val u = h.createQuery("SELECT u.id, u.username, u.full_name, u.role, u.status, $USER_ZONE AS zone_id FROM app.app_user u WHERE u.id = :u FOR UPDATE OF u").bind("u", req.user_id).bind("today", today)
            .map { rs, _ -> arrayOf<Any?>(rs.getString("username"), rs.getString("full_name"), rs.getString("role"), rs.getString("status"), rs.getObject("zone_id") as Long?) }.findOne().orElse(null)
        // An unknown user and one outside the caller's reach are the same answer (no existence leak).
        if (u == null || (!national && (u[4] as Long?) !in zones)) throw ApiProblem(ProblemCode.ERR_OUT_OF_SCOPE, "user outside your reach")
        if (u[2] !in setOf("SR", "AMO") || u[3] != "active") throw ApiProblem(ProblemCode.ERR_VALIDATION, "device OTPs are issued to active SR and AMO users", errors = listOf(FieldError("body.user_id", "not_a_field_user")))
        val replay = h.createQuery(
            "SELECT otp_cipher, device_model, created_at, expires_at, attempts FROM app.device_otp WHERE user_id = :u AND issued_by = :by AND reason = :r AND consumed_at IS NULL AND revoked_at IS NULL " +
                "AND expires_at > :now AND attempts < :maxAtt AND created_at > :since ORDER BY created_at DESC LIMIT 1",
        ).bind("u", req.user_id).bind("by", p.userId).bind("r", req.reason.trim()).bind("maxAtt", maxAttempts).bind("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).bind("since", OffsetDateTime.ofInstant(now.minusSeconds(REPLAY_WINDOW_S), ZoneOffset.UTC))
            .map { rs, _ -> dto(req.user_id, u, rs.getBytes(1), rs.getString(2), rs.getObject(3, OffsetDateTime::class.java), rs.getObject(4, OffsetDateTime::class.java), rs.getInt(5), d) }.findOne().orElse(null)
        if (replay != null) return@inTransaction replay
        val lastHour = h.createQuery("SELECT count(*) FROM app.device_otp WHERE user_id = :u AND issued_by IS NOT NULL AND created_at > :since").bind("u", req.user_id)
            .bind("since", OffsetDateTime.ofInstant(now.minusSeconds(3600), ZoneOffset.UTC)).mapTo(Int::class.java).one()
        if (lastHour >= 10) throw ApiProblem(ProblemCode.ERR_RATE_LIMITED, "at most 10 device OTPs per user per hour", retryAfterS = 600)
        h.createUpdate("UPDATE app.device_otp SET revoked_at = :now WHERE user_id = :u AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > :now")
            .bind("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).bind("u", req.user_id).execute()
        val otp = d.cipher.digits(length)
        val expires = now.plusSeconds(ttlMin * 60L)
        h.createUpdate("INSERT INTO app.device_otp (user_id, otp_cipher, otp_sha256, issued_by, reason, created_at, expires_at) VALUES (:u, :c, :h, :by, :r, :at, :exp)")
            .bind("u", req.user_id).bind("c", d.cipher.seal(otp, req.user_id)).bind("h", d.cipher.mac(otp, req.user_id))
            .bind("by", p.userId).bind("r", req.reason.trim()).bind("at", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).bind("exp", OffsetDateTime.ofInstant(expires, ZoneOffset.UTC)).execute()
        AuditWriter.write(h, p, "device_otp", req.user_id.toString(), "issue", null, buildJsonObject { put("expires_at", expires.wire()) }, req.reason, call.requestId)
        DeviceOtpDto(req.user_id, u[0] as String, u[1] as String, u[4] as Long?, otp, null, now.wire(), expires.wire(), 0)
    }
}

private fun dto(userId: Long, u: Array<Any?>, cipher: ByteArray, model: String?, created: OffsetDateTime, expires: OffsetDateTime, attempts: Int, d: DeviceOtpDeps) =
    DeviceOtpDto(userId, u[0] as String, u[1] as String, u[4] as Long?, d.cipher.open(cipher, userId), model, created.toInstant().wire(), expires.toInstant().wire(), attempts)
