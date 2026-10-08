package com.aktcl.aron.backend.media

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.RateLimiter
import com.aktcl.aron.backend.platform.toProblem
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.rules.BusinessDate
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.Serializable
import java.sql.SQLException
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/** Contract `ClientErrorReport`. */
@Serializable
data class ClientErrorReport(
    val error_uuid: String, val source: String, val occurred_at: String, val page: String? = null, val message: String,
    val stack: String? = null, val build: String? = null,
)

/**
 * POST /v1/client-errors (contract reportClientError, F-SYS-032, D24-80): an error report from the web app; phones
 * send `app_error` records in the batch, so a phone token is 403. Answers 202 and stores the report once per
 * `error_uuid` (a repeat stores nothing). The browser scrubs the report; the server scrubs again (e-mail addresses,
 * Bangladeshi phone numbers, bearer tokens and JWTs, secret-looking query parameters such as a SAS `sig`, long digit
 * runs) before anything is stored, so a careless page cannot leak PII or a credential into the table. The user comes
 * from the token. A browser clock ahead of the server is clamped to the receive time. At most [PER_MIN] reports per
 * user per minute (429). Storage is `app.client_error` (docs/requests/backend-core-client-error-table.md); while the
 * table is missing the route answers 503, never 500.
 */
internal class ClientErrors(private val d: MediaDeps, private val limiter: RateLimiter) {
    fun report(p: AronPrincipal, r: ClientErrorReport) {
        if (p.isPhone) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "phones report errors as app_error records in the sync batch")
        if (!UUID_V4.matches(r.error_uuid)) throw invalid("/error_uuid", "a lowercase UUID v4")
        if (r.source != "web") throw invalid("/source", "web")
        val occurred = r.occurred_at.takeIf { TS_RE.matches(it) }?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: throw invalid("/occurred_at", "an RFC 3339 UTC instant with milliseconds")
        if (r.message.isBlank() || r.message.length > 500) throw invalid("/message", "1 to 500 characters")
        if ((r.page?.length ?: 0) > 200) throw invalid("/page", "at most 200 characters")
        if ((r.stack?.length ?: 0) > 16_000) throw invalid("/stack", "at most 16000 characters")
        if ((r.build?.length ?: 0) > 40) throw invalid("/build", "at most 40 characters")
        val decision = limiter.tryAcquire("ce:" + p.userId)
        if (!decision.allowed) throw decision.toProblem("too many error reports")

        val now = d.clock.now()
        try {
            d.db.jdbi.useHandle<Exception> { h ->
                h.createUpdate(
                    """
                    INSERT INTO app.client_error (error_uuid, user_id, source, occurred_at, received_at, business_date, page, message, stack, build)
                    VALUES (:u, :me, 'web', :occurred, :now, :bd, :page, :message, :stack, :build)
                    ON CONFLICT (error_uuid) DO NOTHING
                    """.trimIndent(),
                ).bind("u", UUID.fromString(r.error_uuid)).bind("me", p.userId).bind("occurred", minOf(occurred, now)).bind("now", now)
                    .bind("bd", BusinessDate.of(now.toEpochMilli()).toJavaLocalDate())
                    .bind("page", r.page?.let { scrub(it).take(200) }).bind("message", scrub(r.message).take(500))
                    .bind("stack", r.stack?.let { scrub(it).take(16_000) }).bind("build", r.build?.let { scrub(it).take(40) })
                    .execute()
            }
        } catch (e: Exception) {
            if (generateSequence<Throwable>(e) { it.cause }.any { it is SQLException && it.sqlState == "42P01" }) {
                throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "error reports are not stored in this environment yet")
            }
            throw e
        }
    }

    companion object {
        const val PER_MIN = 30
        private val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        private val TS_RE = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")
        private val CONTROL = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
        private val EMAIL = Regex("(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]{1,63}(?:\\.[A-Za-z0-9-]{1,63}){0,8}\\.[A-Za-z]{2,24}")
        // Every pattern is bounded or anchored on a literal, so a 16 KB stack scrubs in milliseconds (checker F1).
        private val SCRUBS = listOf(
            Regex("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*") to "[token]",
            Regex("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]+") to "$1 [token]",
            Regex("(?i)\\b((?:set-)?cookie:)[^\\n]*") to "$1 [redacted]",
            Regex("(?i)((?:^|[?&;\\s])(?:sig|signature|token|access_token|refresh_token|password|pwd|otp|code|secret|key)=)[^&#\\s\"']*") to "$1[redacted]",
            Regex("(?<![0-9])(?:(?:\\+?88)?0|\\+?880[\\s-]?)1[3-9](?:[\\s-]?[0-9]){8}(?![0-9])") to "[phone]",
            Regex("(?<![0-9])[0-9]{10,}(?![0-9])") to "[number]",
        )

        /** Removes PII and credentials (a placeholder can be longer than what it replaces: callers cut to the column limit). */
        fun scrub(s: String): String {
            // Control characters (U+0000 is refused by PostgreSQL text: a 500 otherwise, checker F2), then e-mails only when an @ exists.
            val clean = CONTROL.replace(s, "").let { if ('@' in it) EMAIL.replace(it, "[email]") else it }
            return SCRUBS.fold(clean) { acc, (re, to) -> re.replace(acc, to) }
        }

        private fun invalid(pointer: String, msg: String) = ApiProblem(ProblemCode.ERR_VALIDATION, msg, errors = listOf(FieldError(pointer, "invalid_value")))
    }
}
