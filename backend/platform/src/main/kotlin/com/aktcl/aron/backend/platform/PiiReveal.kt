package com.aktcl.aron.backend.platform

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * PII reveal (docs/21 s4.5, D-AUTHZ-PII). A web user whose role holds [PERMISSION] in `app.role_grant_map` re-enters
 * the password (and TOTP for an MFA role) at `POST /v1/auth/reauth`; the access token it gets back carries `pii` and
 * `pii_until` (`cfg.pii.reauth_min`). The guard honours that claim only while the window is open, the token is a web
 * API token and the role still holds the grant (read per request, so a revoke holds within the gate cache), and only
 * once [log] recorded the request: no audit row, no reveal (masked, never an error). The audit rows name the route and
 * the window, never a personal value.
 */
object PiiReveal {
    const val PERMISSION = "pii.reveal"
    const val ENTITY = "pii_reveal"
}

/** Writes the reveal audit rows (`app.audit_log`, entity [PiiReveal.ENTITY]). A failure throws. */
interface PiiRevealLog {
    /** The step-up that opened a window ending at [until]. */
    fun stepUp(p: AronPrincipal, until: Instant, requestId: String?)

    /** One request served with personal columns unmasked. */
    fun read(p: AronPrincipal, method: String, path: String, requestId: String?)
}

class JdbiPiiRevealLog(private val db: Database) : PiiRevealLog {
    override fun stepUp(p: AronPrincipal, until: Instant, requestId: String?) = db.jdbi.useTransaction<Exception> { h ->
        AuditLog.write(h, p, PiiReveal.ENTITY, p.userId.toString(), "step_up", null, buildJsonObject { put("pii_until", until.wire()) }, null, requestId, via = "web")
    }

    override fun read(p: AronPrincipal, method: String, path: String, requestId: String?) = db.jdbi.useTransaction<Exception> { h ->
        // The path only (never the query string, which may carry a searched phone number).
        AuditLog.write(
            h, p, PiiReveal.ENTITY, p.userId.toString(), "read", null,
            buildJsonObject { put("method", method.take(10)); put("path", path.take(200)); p.piiUntil?.let { put("pii_until", it.wire()) } },
            null, requestId, via = "web",
        )
    }
}
