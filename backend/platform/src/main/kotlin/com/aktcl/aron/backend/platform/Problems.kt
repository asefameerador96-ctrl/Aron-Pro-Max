package com.aktcl.aron.backend.platform

import com.aktcl.aron.contract.ProblemCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** One `errors[]` entry of a problem (contract FieldError). */
@Serializable
data class FieldError(val pointer: String, val code: String, val message: String? = null)

/** RFC 9457 problem details with the Aron members (contract Problem, docs/24 s3.4). */
@Serializable
data class Problem(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String? = null,
    val instance: String? = null,
    val code: String,
    val request_id: String,
    val retryable: Boolean,
    val retry_after_s: Int? = null,
    val server_time: String,
    val message_key: String,
    val errors: List<FieldError>? = null,
    val context: Map<String, JsonElement>? = null,
)

/**
 * Thrown anywhere in a handler to answer with a problem. The HTTP status always comes from the code's catalogue
 * entry (shared:contract ProblemCode), so a code can never travel with the wrong status.
 */
class ApiProblem(
    val code: ProblemCode,
    val detail: String? = null,
    val errors: List<FieldError> = emptyList(),
    val retryAfterS: Int? = null,
    val context: Map<String, JsonElement> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    cause: Throwable? = null,
) : RuntimeException(code.wire + (detail?.let { ": $it" } ?: ""), cause) {
    val status: Int get() = code.httpStatus
}

object ProblemTexts {
    private val retryableCodes = setOf(
        ProblemCode.ERR_TOKEN_EXPIRED, ProblemCode.ERR_SCOPE_CHANGED, ProblemCode.ERR_RATE_LIMITED,
        ProblemCode.ERR_INTERNAL, ProblemCode.ERR_SERVICE_UNAVAILABLE, ProblemCode.ERR_READ_ONLY_MODE,
        ProblemCode.ERR_BUNDLE_NOT_READY, ProblemCode.ERR_SYNC_HOLD, ProblemCode.ERR_AUTH_ACCOUNT_LOCKED,
    )

    /** Retryable column of docs/24 s3.4 (ACCOUNT_LOCKED only after retry_after_s). */
    fun retryable(code: ProblemCode): Boolean = code in retryableCodes

    /** Localisation key: `problem.<code without ERR_ in lower case>`; texts live in the app and web catalogues. */
    fun messageKey(code: ProblemCode): String = "problem." + code.wire.removePrefix("ERR_").lowercase()

    fun type(code: ProblemCode): String = "urn:aron:problem:" + code.wire.lowercase()

    /** English title for logs and developers; clients branch on `code` only. */
    fun title(code: ProblemCode): String =
        code.wire.removePrefix("ERR_").lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}
