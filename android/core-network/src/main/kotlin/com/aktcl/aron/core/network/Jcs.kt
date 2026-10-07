package com.aktcl.aron.core.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.security.MessageDigest

// The phone copy of backend/sync/Jcs.kt (F-SYS-072 record signatures). docs/24 s3.3 puts one canonicaliser in
// shared:contract (docs/requests/backend-jcs-canonicaliser.md); until then this file must stay identical in behaviour,
// proven by the same RFC 8785 vectors in JcsTest on both sides.

/**
 * RFC 8785 JSON Canonicalization Scheme: object members sorted by UTF-16 code units, no whitespace, strings with the
 * minimal JSON escapes, numbers in the ECMAScript Number-to-String form (integers without a fraction, `1e-7`,
 * `1e+21`). Integers up to 2^53 are printed exactly; anything else goes through IEEE 754 double as ECMAScript would.
 */
object Jcs {
    fun canonicalize(e: JsonElement): String = StringBuilder().also { write(e, it) }.toString()

    fun sha256(e: JsonElement): ByteArray = MessageDigest.getInstance("SHA-256").digest(canonicalize(e).toByteArray(Charsets.UTF_8))

    private fun write(e: JsonElement, out: StringBuilder) {
        when (e) {
            is JsonNull -> out.append("null")
            is JsonObject -> {
                out.append('{')
                e.keys.sortedWith(UTF16).forEachIndexed { i, k ->
                    if (i > 0) out.append(',')
                    string(k, out); out.append(':'); write(e.getValue(k), out)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                e.forEachIndexed { i, v -> if (i > 0) out.append(','); write(v, out) }
                out.append(']')
            }
            is JsonPrimitive -> when {
                e.isString -> string(e.content, out)
                e.content == "true" || e.content == "false" -> out.append(e.content)
                else -> out.append(number(e.content))
            }
        }
    }

    /** Kotlin compares strings by UTF-16 code units, which is the RFC 8785 member order. */
    private val UTF16 = Comparator<String> { a, b -> a.compareTo(b) }

    private fun string(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u").append(String.format(java.util.Locale.ROOT, "%04x", c.code)) else out.append(c)
            }
        }
        out.append('"')
    }

    private val SAFE_INT = Regex("^-?(0|[1-9][0-9]{0,15})$")
    private const val MAX_SAFE = 9_007_199_254_740_992L

    /** ECMAScript Number.prototype.toString of the JSON number literal [lit]. */
    fun number(lit: String): String {
        if (SAFE_INT.matches(lit)) {
            val v = lit.toLong()
            if (v in -MAX_SAFE..MAX_SAFE) return if (v == 0L) "0" else v.toString()
        }
        val d = lit.toDouble()
        require(d.isFinite()) { "non-finite number $lit" }
        if (d == 0.0) return "0"
        // JDK 19+ Double.toString gives the shortest round-tripping digits (Android's may give more; the loop below
        // shortens them the same way); re-lay them out the ECMAScript way.
        var bd = BigDecimal(java.lang.Double.toString(Math.abs(d))).stripTrailingZeros()
        // Double.toString keeps two digits where one would do (5e-324 prints as 4.9E-324): shorten while it round-trips.
        while (bd.precision() > 1) {
            val shorter = bd.round(java.math.MathContext(bd.precision() - 1, java.math.RoundingMode.HALF_EVEN)).stripTrailingZeros()
            if (shorter.toDouble() != Math.abs(d)) break
            bd = shorter
        }
        val digits = bd.unscaledValue().toString()
        val k = digits.length
        val n = k - bd.scale()
        val body = when {
            n in k..21 -> digits + "0".repeat(n - k)
            n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val exp = n - 1
                val sign = if (exp >= 0) "+" else "-"
                (if (k == 1) digits else digits[0] + "." + digits.substring(1)) + "e" + sign + Math.abs(exp)
            }
        }
        return if (d < 0) "-$body" else body
    }
}
