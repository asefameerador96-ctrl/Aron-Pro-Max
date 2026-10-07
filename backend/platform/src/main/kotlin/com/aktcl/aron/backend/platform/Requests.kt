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
    val text = bytes.decodeToString()
    // U+0000 cannot be stored in PostgreSQL text: refused here, once, for every JSON endpoint (the sync batch decodes
    // with decodeStrict and refuses it per record instead, so one bad record never fails the batch).
    if ("\\u0000" in text || '\u0000' in text) nulPointer(text)?.let { pointer ->
        throw ApiProblem(ProblemCode.ERR_VALIDATION, "a text value holds a NUL character", errors = listOf(FieldError(pointer, "invalid_character")))
    }
    return decodeStrict(serializer, text)
}

/** JSON Pointer of the first member name or string value that holds U+0000, or null (also null for invalid JSON). */
internal fun nulPointer(text: String): String? {
    val root = try { RequestJson.parseToJsonElement(text) } catch (e: SerializationException) { return null }
    fun esc(k: String) = k.replace("~", "~0").replace("/", "~1")
    fun walk(e: kotlinx.serialization.json.JsonElement, path: String): String? = when (e) {
        is kotlinx.serialization.json.JsonObject -> e.entries.firstNotNullOfOrNull { (k, v) -> if ('\u0000' in k) "$path/${esc(k)}" else walk(v, "$path/${esc(k)}") }
        is kotlinx.serialization.json.JsonArray -> e.withIndex().firstNotNullOfOrNull { (i, v) -> walk(v, "$path/$i") }
        is kotlinx.serialization.json.JsonPrimitive -> if (e.isString && '\u0000' in e.content) path else null
    }
    return walk(root, "")
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
    duplicateMember(text)?.let { pointer ->
        // Strict means unambiguous: two parsers must never read different values from one body (s3.1).
        throw ApiProblem(ProblemCode.ERR_VALIDATION, "a member appears twice", errors = listOf(FieldError(pointer, "duplicate_member")))
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

/**
 * JSON Pointer of the first member that appears twice in one object, or null. [text] is already known to be valid
 * JSON; keys are compared after unescaping (`"a"` and `"\u0061"` are the same member).
 */
internal fun duplicateMember(text: String): String? {
    class Obj(val path: String) { val keys = HashSet<String>(); var expectKey = true; var lastKey: String? = null }
    class Arr(val path: String) { var index = 0 }
    val stack = ArrayDeque<Any>()
    fun childPath(): String = when (val top = stack.lastOrNull()) {
        is Obj -> top.path + "/" + (top.lastKey ?: "").replace("~", "~0").replace("/", "~1")
        is Arr -> top.path + "/" + top.index
        else -> ""
    }
    var i = 0
    while (i < text.length) {
        when (val c = text[i]) {
            '{' -> { stack.addLast(Obj(childPath())) }
            '[' -> { stack.addLast(Arr(childPath())) }
            '}', ']' -> stack.removeLast()
            ',' -> when (val top = stack.lastOrNull()) { is Obj -> top.expectKey = true; is Arr -> top.index++ }
            ':' -> (stack.lastOrNull() as? Obj)?.expectKey = false
            '"' -> {
                val start = i
                i++
                while (text[i] != '"') { if (text[i] == '\\') i++; i++ }
                val top = stack.lastOrNull()
                if (top is Obj && top.expectKey) {
                    val key = RequestJson.decodeFromString(kotlinx.serialization.serializer<String>(), text.substring(start, i + 1))
                    top.lastKey = key
                    if (!top.keys.add(key)) return top.path + "/" + key.replace("~", "~0").replace("/", "~1")
                }
            }
            else -> if (c.isWhitespace()) Unit
        }
        i++
    }
    return null
}
