package com.aktcl.aron.backend.notify

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuditLog
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.requestId
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import com.aktcl.aron.rules.BusinessDate
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.toJavaLocalDate
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import java.time.LocalDate
import java.util.UUID
import kotlin.random.Random

@Serializable
data class NotificationRequest(
    val kind: String,
    val scope_type: String,
    val scope_id: Long,
    val title_en: String,
    val title_bn: String? = null,
    val body_en: String,
    val body_bn: String? = null,
    val urgent: Boolean = false,
)

@Serializable
data class NotificationAccepted(val notification_id: String, val devices_targeted: Int)

class NotificationDeps(
    val db: Database, val config: ServerConfig, val reach: ReachResolver, val push: PushNotifier, val guard: AuthGuardDeps,
    val clock: AronClock = AronClock.SYSTEM, val random: Random = Random.Default,
)

/** Web roles that may send an admin notification; each reaches only the phones inside its own scope. */
private val SENDERS = setOf(Role.ADMIN, Role.SUPERADMIN, Role.TOP, Role.WM, Role.DMO, Role.TSO)
private val KINDS = setOf("announcement", "config_pull", "bundle_pull")
private val SCOPES = setOf("global", "wing", "division", "territory", "zone", "user", "device")

/**
 * `POST /v1/admin/notifications` (sendNotification, N-037): an FCM data message to the phones of the users inside a
 * scope node. The node must lie inside the caller's reach (derived on the server from the token; the request names
 * only the target): `global` needs a national reach, a wing, division or territory every one of its zones, a zone that
 * zone, a user or device a user in reach. The phones are those [PushNotifier.liveTokens] allows (active user, active
 * bound phone, live full grant). Refused 409 `ERR_PUSH_DISABLED` while `cfg.ops.push_enabled` is false. Every send is
 * audited. The message is data only: `kind`, `notification_id`, `pull_after_s` (jitter U(0, 20) when urgent, else
 * U(0, `cfg.ops.push_jitter_s`)), and for an announcement its title and body; never business or personal data.
 */
fun Route.notificationRoutes(d: NotificationDeps) {
    authenticated(d.guard) {
        post("/admin/notifications") {
            val p = call.principal
            if (p.isPhone || p.role !in SENDERS) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "notifications are sent from the web by a supervisor or admin")
            val raw = call.receiveChannel().readRemaining(16 * 1024 + 1).readByteArray()
            if (raw.size > 16 * 1024) throw ApiProblem(ProblemCode.ERR_PAYLOAD_TOO_LARGE, "body above 16 KiB")
            val req = com.aktcl.aron.backend.platform.decodeStrict(NotificationRequest.serializer(), raw.decodeToString())
            validate(req)
            if (!runCatching { d.config.bool("cfg.ops.push_enabled") }.getOrDefault(false)) throw ApiProblem(ProblemCode.ERR_PUSH_DISABLED, "push is switched off (cfg.ops.push_enabled)")
            val today = BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate()
            val id = UUID.randomUUID().toString()
            val urgentMax = 20
            val normalMax = runCatching { d.config.int("cfg.ops.push_jitter_s") }.getOrDefault(120).coerceIn(0, 600)
            val tokens = withContext(Dispatchers.IO) {
                val reach = d.reach.reach(p.userId, p.role, p.scopeVersion, today)
                d.db.jdbi.inTransaction<List<Pair<Long, String>>, Exception> { h ->
                    val users = targetUsers(h, reach, req.scope_type, req.scope_id, today)
                    val t = if (req.scope_type == "device") d.push.liveTokens(h, users).filter { (tid, _) -> tokenOnDevice(h, tid, req.scope_id) } else d.push.liveTokens(h, users)
                    AuditLog.write(
                        h, p, "notification", id, "send", null,
                        buildJsonObject {
                            put("kind", req.kind); put("scope_type", req.scope_type); put("scope_id", req.scope_id); put("urgent", req.urgent)
                            put("title_en", req.title_en); put("devices_targeted", t.size)
                        },
                        null, call.requestId, via = "web",
                    )
                    t
                }
            }
            d.push.broadcast(tokens) { _ ->
                val jitter = d.random.nextInt(0, (if (req.urgent) urgentMax else normalMax) + 1)
                buildMap {
                    put("kind", req.kind); put("notification_id", id); put("pull_after_s", jitter.toString())
                    if (req.kind == "announcement") {
                        put("title_en", req.title_en); put("body_en", req.body_en)
                        req.title_bn?.let { put("title_bn", it) }; req.body_bn?.let { put("body_bn", it) }
                    }
                }
            }
            call.respond(HttpStatusCode.Accepted, NotificationAccepted(id, tokens.size))
        }
    }
}

private fun validate(r: NotificationRequest) {
    val errors = buildList {
        if (r.kind !in KINDS) add(FieldError("/kind", "invalid_value"))
        if (r.scope_type !in SCOPES) add(FieldError("/scope_type", "invalid_value"))
        if (r.scope_id < 0 || (r.scope_type == "global") != (r.scope_id == 0L)) add(FieldError("/scope_id", "invalid_value"))
        if (r.title_en.isBlank() || r.title_en.length > 80) add(FieldError("/title_en", "invalid_value"))
        if (r.title_bn != null && r.title_bn.length > 80) add(FieldError("/title_bn", "too_long"))
        if (r.body_en.isBlank() || r.body_en.length > 300) add(FieldError("/body_en", "invalid_value"))
        if (r.body_bn != null && r.body_bn.length > 300) add(FieldError("/body_bn", "too_long"))
        listOf("/title_en" to r.title_en, "/title_bn" to r.title_bn, "/body_en" to r.body_en, "/body_bn" to r.body_bn).forEach { (f, v) ->
            if (v != null && v.any { it == '\u0000' }) add(FieldError(f, "invalid_character"))
        }
    }
    if (errors.isNotEmpty()) throw ApiProblem(ProblemCode.ERR_VALIDATION, "invalid notification", errors = errors)
}

/** Zones of a geography node (wing, division, territory, zone). */
private fun zonesOf(h: Handle, type: String, id: Long): List<Long> {
    val where = when (type) {
        "zone" -> "z.id = :id"
        "territory" -> "z.territory_id = :id"
        "division" -> "t.division_id = :id"
        "wing" -> "dv.wing_id = :id"
        else -> error(type)
    }
    return h.createQuery("SELECT z.id FROM app.zone z JOIN app.territory t ON t.id = z.territory_id JOIN app.division dv ON dv.id = t.division_id WHERE $where")
        .bind("id", id).mapTo(Long::class.java).list()
}

/**
 * Users inside the caller's reach at the target node. A user is in a zone when it is the home zone or one of the user's
 * routes on [today] (primary or cover) lies in it. Outside the reach: 403 for a geography node, 404 for a user or device.
 */
private fun targetUsers(h: Handle, reach: Reach, type: String, id: Long, today: LocalDate): List<Long> {
    val zones: List<Long>? = when (type) {
        "global" -> if (reach.national) null else throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "a global notification needs a national scope")
        "user" -> {
            if (!reach.national && !userInReach(h, reach, id, today)) throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no such user")
            return listOf(id)
        }
        "device" -> {
            // A shared phone has several active bindings: every bound user inside the caller's reach.
            val bound = h.createQuery("SELECT user_id FROM app.device_binding WHERE device_id = :d AND status = 'active'").bind("d", id).mapTo(Long::class.java).list()
            return bound.filter { reach.national || userInReach(h, reach, it, today) }.ifEmpty { throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no such device") }
        }
        else -> zonesOf(h, type, id).also { z ->
            if (z.isEmpty()) throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no such $type")
            if (!z.all(reach::coversZone)) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "the $type is not inside your scope")
        }
    }
    val q = if (zones == null) h.createQuery("SELECT id FROM app.app_user WHERE status = 'active'") else h.createQuery(
        """
        SELECT u.id FROM app.app_user u WHERE u.status = 'active' AND (u.home_zone_id = ANY(:z) OR EXISTS (
          SELECT 1 FROM app.route_assignment a JOIN app.route r ON r.id = a.route_id
          WHERE a.user_id = u.id AND r.zone_id = ANY(:z) AND a.valid_from <= :d AND (a.valid_to IS NULL OR a.valid_to > :d)))
        """.trimIndent(),
    ).bindArray("z", Long::class.javaObjectType, zones).bind("d", today)
    return q.mapTo(Long::class.java).list()
}

private fun userInReach(h: Handle, reach: Reach, userId: Long, today: LocalDate): Boolean {
    val home = h.createQuery("SELECT home_zone_id FROM app.app_user WHERE id = :u").bind("u", userId).mapTo(Long::class.javaObjectType).findOne().orElse(null)
    if (home != null && reach.coversZone(home)) return true
    return h.createQuery(
        """
        SELECT r.id, r.zone_id FROM app.route_assignment a JOIN app.route r ON r.id = a.route_id
        WHERE a.user_id = :u AND a.valid_from <= :d AND (a.valid_to IS NULL OR a.valid_to > :d)
        """.trimIndent(),
    ).bind("u", userId).bind("d", today).map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list().any { (r, z) -> reach.coversRoute(r, z) }
}

private fun tokenOnDevice(h: Handle, tokenId: Long, deviceId: Long): Boolean =
    h.createQuery("SELECT 1 FROM app.push_token WHERE id = :t AND device_id = :d").bind("t", tokenId).bind("d", deviceId).mapTo(Int::class.java).findOne().isPresent
