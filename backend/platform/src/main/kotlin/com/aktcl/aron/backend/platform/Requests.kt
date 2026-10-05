package com.aktcl.aron.backend.platform

import com.aktcl.aron.contract.ProblemCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationException
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/** Default JSON body cap (docs/24 s3.1 item 4: any JSON body other than the batch is at most 256 KiB). */
const val MAX_JSON_BODY_BYTES: Long = 256L * 1024

/**
 * Reads a strict JSON request body (docs/24 s3.1 item 2): size-capped before decoding, gzip accepted, malformed JSON
 * is `ERR_MALFORMED_JSON`, and a well-formed body that does not fit the schema (unknown member, wrong type, missing
 * required member, bad enum value) is `ERR_VALIDATION` with a JSON Pointer per problem.
 */
suspend fun <T> ApplicationCall.receiveStrict(serializer: KSerializer<T>, maxBytes: Long = MAX_JSON_BODY_BYTES): T {
    val ct = request.contentType()
    if (ct.contentType != "application" || ct.contentSubtype != "json") {
        throw ApiProblem(ProblemCode.ERR_UNSUPPORTED_MEDIA_TYPE, "Content-Type must be application/json")
    }
    request.header("Content-Length")?.toLongOrNull()?.let {
        if (it > maxBytes) throw ApiProblem(ProblemCode.ERR_PAYLOAD_TOO_LARGE, "body above $maxBytes bytes")
    }
    val raw = receiveChannel().readRemaining(maxBytes + 1).readByteArray()
    if (raw.size > maxBytes) throw ApiProblem(ProblemCode.ERR_PAYLOAD_TOO_LARGE, "body above $maxBytes bytes")
    val bytes = when (request.header("Content-Encoding")?.lowercase()) {
        null, "", "identity" -> raw
        "gzip" -> gunzipCapped(raw, maxBytes)
        else -> throw ApiProblem(ProblemCode.ERR_UNSUPPORTED_MEDIA_TYPE, "unsupported Content-Encoding")
    }
    return decodeStrict(serializer, bytes.decodeToString())
}

private fun gunzipCapped(raw: ByteArray, maxBytes: Long): ByteArray = try {
    GZIPInputStream(ByteArrayInputStream(raw)).use { gz ->
        val out = gz.readNBytes((maxBytes + 1).toInt())
        if (out.size > maxBytes) throw ApiProblem(ProblemCode.ERR_PAYLOAD_TOO_LARGE, "decompressed body above $maxBytes bytes")
        out
    }
} catch (e: java.io.IOException) {
    throw ApiProblem(ProblemCode.ERR_MALFORMED_JSON, "body is not valid gzip")
}

/** Decodes [text] strictly; public so tests and the batch path share one error mapping. */
fun <T> decodeStrict(serializer: KSerializer<T>, text: String): T {
    try {
        RequestJson.parseToJsonElement(text)
    } catch (e: SerializationException) {
        throw ApiProblem(ProblemCode.ERR_MALFORMED_JSON, "request body is not valid JSON")
    }
    try {
        return RequestJson.decodeFromString(serializer, text)
    } catch (e: MissingFieldException) {
        val pointer = jsonPointer(e.message)
        throw ApiProblem(
            ProblemCode.ERR_VALIDATION, "a required member is missing",
            errors = e.missingFields.map { FieldError(pointer.trimEnd('/') + "/" + it, "required") },
        )
    } catch (e: SerializationException) {
        val msg = e.message.orEmpty()
        val code = when {
            msg.contains("unknown key", ignoreCase = true) -> "unknown_member"
            msg.contains("does not contain element") -> "invalid_value"
            else -> "invalid_type"
        }
        val pointer = jsonPointer(msg) + (Regex("unknown key '([^']+)'").find(msg)?.groupValues?.get(1)?.let { "/$it" } ?: "")
        throw ApiProblem(ProblemCode.ERR_VALIDATION, "request body does not match the schema", errors = listOf(FieldError(pointer, code)))
    } catch (e: IllegalArgumentException) {
        // DTO init blocks (require) report schema rules kotlinx cannot express (patterns, ranges).
        throw ApiProblem(ProblemCode.ERR_VALIDATION, e.message, errors = listOf(FieldError(e.message?.substringBefore(':')?.takeIf { it.startsWith("/") } ?: "", "invalid_value")))
    }
}

/** `... at path: $.records[3].payload` → `/records/3/payload` (RFC 6901). */
internal fun jsonPointer(message: String?): String {
    val path = message?.let { Regex("""at path:? \$([^\s]*)""").find(it)?.groupValues?.get(1) } ?: return ""
    return path.replace(Regex("""\[(\d+)]"""), ".$1").split('.').filter { it.isNotEmpty() }
        .joinToString("") { "/" + it.replace("~", "~0").replace("/", "~1") }
}
