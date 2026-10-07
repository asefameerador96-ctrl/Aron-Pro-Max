package com.aktcl.aron.backend.platform

import com.aktcl.aron.contract.ProblemCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Security event kinds (docs/21 s8.1, AUD-SEC-03); the wire names are the future `app.security_event.kind` values. */
enum class SecurityEventKind(val wire: String) {
    LOGIN_FAILURE("login_failure"),
    LOCKOUT("lockout"),
    REFRESH_REUSE("refresh_reuse"),
    DEVICE_PROOF_INVALID("device_proof_invalid"),
    DEVICE_STATE_REFUSED("device_state_refused"),
    SCOPE_CHANGED("scope_changed"),
    PASSWORD_CHANGE("password_change"),
    FORCE_LOGOUT("force_logout"),
}

class SecurityEvent(
    val kind: SecurityEventKind,
    val at: Instant,
    val userId: Long? = null,
    val deviceUuid: String? = null,
    val requestId: String? = null,
    /** Short, non-secret facts (a route, a reason code, a hashed username); never a password, token or OTP. */
    val detail: Map<String, String> = emptyMap(),
)

/**
 * AUD-SEC-03: the security-event port. Recording is best effort and never fails the request that triggered it.
 * Production writes one structured `aron.security` log line per event, which Application Insights alerts can match
 * (refresh_reuse > 0, login_failure spikes), and then queues the row for `app.security_event` ([JdbiSecurityEvents]). User and device ids are hashed like every other log line (D-136).
 */
fun interface SecurityEvents {
    fun record(e: SecurityEvent)

    companion object {
        /** The default sink: the structured log line. */
        val LOG: SecurityEvents = LogSecurityEvents()

        /** Hashes a username for the event detail (failures name accounts that may not exist; keep the log PII-free). */
        fun usernameHash(username: String): String = sha256Hex("aron-sec-user|" + username.trim().lowercase()).take(16)

        internal fun sha256Hex(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** Records [e] through [this], swallowing any failure: a security event must never fail a login or a sync. */
fun SecurityEvents.safely(e: SecurityEvent) {
    runCatching { record(e) }.onFailure { LoggerFactory.getLogger("aron.security").warn("security event dropped kind={} cause={}", e.kind.wire, it.javaClass.simpleName) }
}

/**
 * Writes `aron.security` JSON lines, then hands the event to [then]. `login_failure` is deduplicated per dedupe key
 * (username hash and device) per minute, for the line and the table alike (docs/21 s8.1): a password-spraying burst is one line per account and device per minute.
 */
class LogSecurityEvents(
    /** Where every event the dedupe keeps goes next (the `app.security_event` writer in production); null for the log only. */
    private val then: SecurityEvents? = null,
    private val sink: (String) -> Unit = { LoggerFactory.getLogger("aron.security").info(it) },
) : SecurityEvents {
    private val lastMinute = ConcurrentHashMap<String, Long>()

    override fun record(e: SecurityEvent) {
        if (e.kind == SecurityEventKind.LOGIN_FAILURE) {
            val key = e.detail["username_hash"] + "|" + e.deviceUuid
            val minute = e.at.epochSecond / 60
            if (lastMinute.put(key, minute) == minute) return
            if (lastMinute.size > MAX_KEYS) lastMinute.entries.removeIf { it.value < minute }
        }
        val line = buildJsonObject {
            put("security_event", e.kind.wire)
            put("at", e.at.toString())
            put("user", e.userId?.let { logPseudonym("user", it.toString()) })
            put("device", e.deviceUuid?.let { logPseudonym("device", it) })
            put("request_id", e.requestId)
            e.detail.toSortedMap().forEach { (k, v) -> put(k, v.take(200)) }
        }
        sink(line.toString())
        then?.safely(e)
    }

    private companion object {
        const val MAX_KEYS = 50_000
    }
}

/** The kinds the platform derives from a problem answered to the client (one place for every route, AUD-SEC-03). */
fun securityKindOf(code: ProblemCode): SecurityEventKind? = when (code) {
    ProblemCode.ERR_DEVICE_PROOF_INVALID -> SecurityEventKind.DEVICE_PROOF_INVALID
    ProblemCode.ERR_DEVICE_SUSPENDED, ProblemCode.ERR_DEVICE_REVOKED -> SecurityEventKind.DEVICE_STATE_REFUSED
    ProblemCode.ERR_SCOPE_CHANGED -> SecurityEventKind.SCOPE_CHANGED
    else -> null
}
