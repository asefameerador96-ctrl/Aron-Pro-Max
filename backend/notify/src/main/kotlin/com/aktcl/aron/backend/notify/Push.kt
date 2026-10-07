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
import java.util.concurrent.Executors
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
    private val app: com.google.firebase.FirebaseApp = com.google.firebase.FirebaseApp.initializeApp(
        com.google.firebase.FirebaseOptions.builder()
            .setCredentials(com.google.auth.oauth2.GoogleCredentials.fromStream(serviceAccountJson.reveal().byteInputStream()))
            .build(),
        "aron-fcm",
    )

    override fun send(token: String, data: Map<String, String>): Boolean = try {
        val msg = com.google.firebase.messaging.Message.builder().setToken(token).putAllData(data)
            // Data-only, high priority so a dozing phone wakes to sync; never a visible notification body (D24-23).
            .setAndroidConfig(com.google.firebase.messaging.AndroidConfig.builder().setPriority(com.google.firebase.messaging.AndroidConfig.Priority.HIGH).setTtl(30 * 60 * 1000L).build())
            .build()
        com.google.firebase.messaging.FirebaseMessaging.getInstance(app).send(msg)
        true
    } catch (e: com.google.firebase.messaging.FirebaseMessagingException) {
        val code = e.messagingErrorCode
        if (code == com.google.firebase.messaging.MessagingErrorCode.UNREGISTERED || code == com.google.firebase.messaging.MessagingErrorCode.INVALID_ARGUMENT) false else throw e
    }
}

/**
 * Push nudges (N-037): `cfg.ops.push_enabled` and, for task pushes, `cfg.notify.task_push_enabled` switch them; each
 * nudge goes asynchronously (well inside 30 seconds) to every live token of the user, carries only `kind` and `reason`
 * (no business data), and a token FCM reports as unregistered is revoked. Best effort: a lost nudge only delays the
 * phone's next sync, which the regular triggers make anyway.
 */
class PushNotifier(
    private val db: Database,
    private val config: ServerConfig,
    private val sender: PushSender?,
    private val clock: AronClock = AronClock.SYSTEM,
) : Nudger {
    private val log = LoggerFactory.getLogger("aron.push")
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "push").apply { isDaemon = true } }
    @Volatile private var warned = false

    private fun enabled(key: String) = runCatching { config.bool(key) }.getOrDefault(true)

    override fun nudge(userId: Long, reason: String) {
        if (!enabled("cfg.ops.push_enabled")) return
        if (reason == "task_assigned" && !enabled("cfg.notify.task_push_enabled")) return
        if (sender == null) {
            if (!warned) { warned = true; log.info("push is off: no FCM service account configured") }
            return
        }
        pool.execute { runCatching { deliver(userId, reason) }.onFailure { log.warn("push failed user_id=$userId reason=$reason: ${it.javaClass.simpleName}") } }
    }

    /** Sends now (the executor's body; public for tests). Returns the number of tokens reached. */
    fun deliver(userId: Long, reason: String): Int {
        val s = sender ?: return 0
        val tokens = db.jdbi.withHandle<List<Pair<Long, String>>, Exception> { h ->
            h.createQuery("SELECT id, token FROM app.push_token WHERE user_id = :u AND revoked_at IS NULL").bind("u", userId)
                .map { rs, _ -> rs.getLong(1) to rs.getString(2) }.list()
        }
        var sent = 0
        for ((id, token) in tokens) {
            if (s.send(token, mapOf("kind" to "sync_nudge", "reason" to reason))) sent++
            else db.jdbi.useHandle<Exception> { h ->
                h.createUpdate("UPDATE app.push_token SET revoked_at = :now, revoke_reason = 'unregistered' WHERE id = :id AND revoked_at IS NULL")
                    .bind("now", OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)).bind("id", id).execute()
            }
        }
        return sent
    }

    /** Waits for queued nudges (tests). */
    fun drain(timeoutMs: Long = 5_000) { pool.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS) }
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
