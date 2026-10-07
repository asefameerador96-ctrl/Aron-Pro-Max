package com.aktcl.aron.backend.notify

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.DeviceProof
import com.aktcl.aron.backend.platform.Nudger
import com.aktcl.aron.backend.platform.Secret
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.contract.ProblemCode
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/** One data-only message to one FCM token; returns false when the token is no longer valid (unregistered). */
fun interface PushSender {
    fun send(token: String, data: Map<String, String>): Boolean
}

/**
 * FCM HTTP v1 through firebase-admin (docs/24 s2.2). Built only when the service account is configured; without it the
 * notifier logs once and sends nothing (tests and the dev database run without the secret).
 */
class FcmPushSender(serviceAccountJson: Secret) : PushSender {
    private val app: com.google.firebase.FirebaseApp =
        com.google.firebase.FirebaseApp.getApps().firstOrNull { it.name == APP } ?: com.google.firebase.FirebaseApp.initializeApp(
            com.google.firebase.FirebaseOptions.builder()
                .setCredentials(com.google.auth.oauth2.GoogleCredentials.fromStream(serviceAccountJson.reveal().byteInputStream()))
                // A slow FCM call must not hold the queue past the 30-second bound.
                .setConnectTimeout(5_000).setReadTimeout(5_000)
                .build(),
            APP,
        )

    override fun send(token: String, data: Map<String, String>): Boolean = try {
        val msg = com.google.firebase.messaging.Message.builder().setToken(token).putAllData(data)
            // Data-only, high priority so a dozing phone wakes to sync; never a visible notification body (D24-23).
            .setAndroidConfig(com.google.firebase.messaging.AndroidConfig.builder().setPriority(com.google.firebase.messaging.AndroidConfig.Priority.HIGH).setTtl(30 * 60 * 1000L).build())
            .build()
        com.google.firebase.messaging.FirebaseMessaging.getInstance(app).send(msg)
        true
    } catch (e: com.google.firebase.messaging.FirebaseMessagingException) {
        // Only UNREGISTERED proves the token dead; INVALID_ARGUMENT may be the message, so the token is kept.
        if (e.messagingErrorCode == com.google.firebase.messaging.MessagingErrorCode.UNREGISTERED) false else throw e
    }

    private companion object { const val APP = "aron-fcm" }
}

/**
 * Push nudges (N-037). `cfg.ops.push_enabled` and, for task pushes, `cfg.notify.task_push_enabled` resolved in the
 * assignee's scope ([enabledFor]; an unreadable switch means off) decide whether a nudge goes at all. It goes
 * asynchronously to every live token of the user whose phone is active, still bound to the user, and holds a live full
 * grant (a logged-out user, a revoked or replaced phone and a disabled user get nothing). It carries only `kind` and
 * `reason`, no business data, and a token FCM reports unregistered is revoked. Delivery is best effort: a small pool,
 * one pending nudge per user (bursts coalesce), a bounded queue, and FCM timeouts keep every nudge well inside 30
 * seconds. A lost nudge only delays the phone's next sync, which its own triggers make anyway.
 */
class PushNotifier(
    private val db: Database,
    private val config: ServerConfig,
    private val sender: PushSender?,
    private val clock: AronClock = AronClock.SYSTEM,
    /** Whether a switch is on for the user (scope-resolved by the wiring); default: the global value. */
    private val enabledFor: (userId: Long, key: String) -> Boolean = { _, key -> runCatching { config.bool(key) }.getOrDefault(false) },
) : Nudger {
    private val log = LoggerFactory.getLogger("aron.push")
    private val pool = java.util.concurrent.ThreadPoolExecutor(
        4, 4, 30, TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue(2_000),
        { r -> Thread(r, "push").apply { isDaemon = true } }, java.util.concurrent.ThreadPoolExecutor.DiscardPolicy(),
    )
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    @Volatile private var warned = false

    override fun nudge(userId: Long, reason: String) {
        if (!enabledFor(userId, "cfg.ops.push_enabled")) return
        if (reason == "task_assigned" && !enabledFor(userId, "cfg.notify.task_push_enabled")) return
        if (sender == null) {
            if (!warned) { warned = true; log.info("push is off: no FCM service account configured") }
            return
        }
        if (!pending.add(userId)) return // a nudge for this user is already queued: one is enough
        try {
            pool.execute {
                pending.remove(userId)
                runCatching { deliver(userId, reason) }.onFailure { log.warn("push failed user_id=$userId reason=$reason: ${it.javaClass.simpleName}") }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) { pending.remove(userId) }
    }

    /** Sends now (the executor's body; public for tests). Returns the number of tokens reached. */
    fun deliver(userId: Long, reason: String): Int {
        val s = sender ?: return 0
        val tokens = db.jdbi.withHandle<List<Pair<Long, String>>, Exception> { h ->
            h.createQuery(
                """
                SELECT t.id, t.token FROM app.push_token t
                JOIN app.app_user u ON u.id = t.user_id AND u.status = 'active'
                JOIN app.device d ON d.id = t.device_id AND d.status = 'active'
                WHERE t.user_id = :u AND t.revoked_at IS NULL
                  AND EXISTS (SELECT 1 FROM app.device_binding b WHERE b.user_id = t.user_id AND b.device_id = t.device_id AND b.status = 'active')
                  AND EXISTS (SELECT 1 FROM app.refresh_family f WHERE f.user_id = t.user_id AND f.device_id = t.device_id AND f.grant_kind = 'full'
                              AND f.revoked_at IS NULL AND f.sliding_expires_at > :now AND f.absolute_expires_at > :now)
                """.trimIndent(),
            ).bind("u", userId).bind("now", OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC))
                .map { rs, _ -> rs.getLong(1) to rs.getString(2) }.list()
        }
        var sent = 0
        for ((id, token) in tokens) {
            // One token's failure never skips the user's other phones.
            val ok = runCatching { s.send(token, mapOf("kind" to "sync_nudge", "reason" to reason)) }
                .onFailure { log.warn("push send failed push_token_id=$id: ${it.javaClass.simpleName}") }.getOrNull() ?: continue
            if (ok) sent++
            else db.jdbi.useHandle<Exception> { h ->
                h.createUpdate("UPDATE app.push_token SET revoked_at = :now, revoke_reason = 'unregistered' WHERE id = :id AND revoked_at IS NULL")
                    .bind("now", OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)).bind("id", id).execute()
            }
        }
        return sent
    }

    /** Waits for queued nudges (tests). */
    fun drain(timeoutMs: Long = 5_000) {
        val until = System.currentTimeMillis() + timeoutMs
        while ((pool.activeCount > 0 || pool.queue.isNotEmpty()) && System.currentTimeMillis() < until) Thread.sleep(10)
    }
}

@Serializable
data class PushTokenRegistration(val provider: String, val token: String, val app_flavour: String) {
    init {
        require(provider == "fcm") { "/provider: const" }
        require(token.length in 20..4096) { "/token: length" }
        require(app_flavour in setOf("sr", "amo", "tso")) { "/app_flavour: enum" }
    }

    override fun toString() = "PushTokenRegistration(provider=$provider, app_flavour=$app_flavour, token=***)"
}

class PushDeps(val db: Database, val config: ServerConfig, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM)

/**
 * `PUT /v1/devices/me/push-token`: the calling user's FCM token on this phone (X-Device-Id must be the token's phone and
 * X-Device-Proof its key, `device` proof over the body). A new token replaces the old one; the same token again only
 * refreshes `last_seen_at`.
 */
fun Route.pushRoutes(d: PushDeps) {
    authenticated(d.guard) {
        put("/devices/me/push-token") {
            val p = call.principal
            val raw = call.receiveChannel().readRemaining(8 * 1024 + 1).readByteArray()
            if (raw.size > 8 * 1024) throw ApiProblem(ProblemCode.ERR_PAYLOAD_TOO_LARGE, "body above 8 KiB")
            val req = com.aktcl.aron.backend.platform.decodeStrict(PushTokenRegistration.serializer(), raw.decodeToString())
            if (!p.isPhone || p.deviceId == null || p.deviceUuid == null) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "only an enrolled phone registers a push token")
            val status = withContext(Dispatchers.IO) {
                d.db.jdbi.withHandle<String?, Exception> { h -> h.createQuery("SELECT status FROM app.device WHERE id = :d").bind("d", p.deviceId).mapTo(String::class.java).findOne().orElse(null) }
            }
            if (status != "active") throw ApiProblem(ProblemCode.ERR_DEVICE_REVOKED, "this phone is not active")
            if (req.app_flavour != p.flavour) throw ApiProblem(ProblemCode.ERR_VALIDATION, "app_flavour differs from the token's app")
            withContext(Dispatchers.IO) {
                val key = d.db.jdbi.withHandle<String?, Exception> { h ->
                    h.createQuery("SELECT public_key_jwk::text FROM app.device WHERE id = :d").bind("d", p.deviceId).mapTo(String::class.java).findOne().orElse(null)
                }?.let(DeviceProof::publicKey)
                if (key != null) {
                    val proof = call.request.headers["X-Device-Proof"] ?: throw ApiProblem(ProblemCode.ERR_DEVICE_PROOF_INVALID, "X-Device-Proof is required")
                    val ok = DeviceProof.verifyBucketed(key, proof, d.clock.now().epochSecond) { b ->
                        listOf("aron-proof-v1", "device", p.deviceUuid, "PUT /v1/devices/me/push-token", DeviceProof.sha256Hex(raw), b.toString()).joinToString("\n")
                    }
                    if (!ok) throw ApiProblem(ProblemCode.ERR_DEVICE_PROOF_INVALID, "device proof does not verify")
                } else if (runCatching { d.config.bool("cfg.device.require_enrolled") }.getOrDefault(true)) {
                    throw ApiProblem(ProblemCode.ERR_DEVICE_PROOF_INVALID, "device key unknown")
                }
                store(d, p.userId, p.deviceId!!, req)
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun store(d: PushDeps, userId: Long, deviceId: Long, req: PushTokenRegistration) {
    val hash = MessageDigest.getInstance("SHA-256").digest(req.token.toByteArray())
    val now = OffsetDateTime.ofInstant(d.clock.now(), ZoneOffset.UTC)
    d.db.jdbi.useTransaction<Exception> { h ->
        h.createQuery("SELECT id FROM app.device WHERE id = :d FOR UPDATE").bind("d", deviceId).mapTo(Long::class.java).findOne()
        val same = h.createUpdate("UPDATE app.push_token SET last_seen_at = :now WHERE device_id = :d AND user_id = :u AND revoked_at IS NULL AND token_sha256 = :h")
            .bind("now", now).bind("d", deviceId).bind("u", userId).bind("h", hash).execute()
        if (same > 0) return@useTransaction
        h.createUpdate("UPDATE app.push_token SET revoked_at = :now, revoke_reason = 'replaced' WHERE device_id = :d AND user_id = :u AND revoked_at IS NULL")
            .bind("now", now).bind("d", deviceId).bind("u", userId).execute()
        // The same FCM token now belongs to this user only (a shared phone changes hands).
        h.createUpdate("UPDATE app.push_token SET revoked_at = :now, revoke_reason = 'replaced' WHERE token_sha256 = :h AND revoked_at IS NULL")
            .bind("now", now).bind("h", hash).execute()
        h.createUpdate("INSERT INTO app.push_token (device_id, user_id, app_flavour, token, token_sha256, registered_at, last_seen_at) VALUES (:d, :u, :f, :t, :h, :now, :now)")
            .bind("d", deviceId).bind("u", userId).bind("f", req.app_flavour).bind("t", req.token).bind("h", hash).bind("now", now).execute()
    }
}
