package com.aktcl.aron.backend.config

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.contract.ProblemCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Typed value check against the registry (docs/24 s9.1): type, bounds (static and `dynamic_min` / `dynamic_max`
 * read from the current global value of another key), enum, list size and the `bounds_rule` time range. A failure
 * is 400 `ERR_CFG_OUT_OF_BOUNDS` (a well-typed value outside its bounds) or `ERR_VALIDATION` (wrong type).
 */
object ConfigValidator {
    private val TIME = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
    private val RANGE = Regex("^([0-2][0-9]:[0-5][0-9])\\.\\.([0-2][0-9]:[0-5][0-9])$")

    fun check(def: KeyDef, value: JsonElement, pointer: String, globalNumber: (String) -> Double?) {
        fun bad(code: String, msg: String): Nothing = throw ApiProblem(ProblemCode.ERR_VALIDATION, "${def.key}: $msg", errors = listOf(FieldError(pointer, code, msg)))
        fun out(msg: String): Nothing = throw ApiProblem(ProblemCode.ERR_CFG_OUT_OF_BOUNDS, "${def.key}: $msg", errors = listOf(FieldError(pointer, "out_of_bounds", msg)))
        val p = value as? JsonPrimitive
        when (def.valueType) {
            "int", "money_mtk" -> {
                if (p == null || p.isString || p.longOrNull == null || p.content.contains('.') || p.content.contains('e', true)) bad("invalid_type", "an integer is required")
                numeric(def, p.longOrNull!!.toDouble(), globalNumber) { out(it) }
                if (def.valueType == "money_mtk" && p.longOrNull!! < 0) out("money cannot be negative")
            }
            "number", "pct" -> {
                if (p == null || p.isString || p.doubleOrNull == null) bad("invalid_type", "a number is required")
                numeric(def, p.doubleOrNull!!, globalNumber) { out(it) }
                if (def.valueType == "pct" && p.doubleOrNull!! !in 0.0..100.0) out("a percentage is 0 to 100")
            }
            "bool" -> if (p == null || p.isString || (p.content != "true" && p.content != "false")) bad("invalid_type", "true or false is required")
            "time" -> {
                if (p == null || !p.isString || !TIME.matches(p.content)) bad("invalid_type", "a time HH:MM is required")
                RANGE.matchEntire(def.boundsRule ?: "")?.let { m ->
                    if (p.content < m.groupValues[1] || p.content > m.groupValues[2]) out("time must be ${m.groupValues[1]} to ${m.groupValues[2]}")
                }
            }
            "text" -> if (p == null || !p.isString || p.content.length > 4000) bad("invalid_type", "text of at most 4000 characters is required")
            "url" -> if (p == null || !p.isString || !(p.content.startsWith("https://") || p.content.startsWith("http://")) || p.content.length > 2000) bad("invalid_type", "an http(s) URL is required")
            "enum" -> {
                if (p == null || !p.isString) bad("invalid_type", "an enum value is required")
                val allowed = (def.bounds["enum"] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()
                if (p.content !in allowed) out("value must be one of ${allowed.joinToString()}")
            }
            "list" -> {
                if (value !is JsonArray) bad("invalid_type", "a list is required")
                val max = (def.bounds["max_items"] as? JsonPrimitive)?.longOrNull
                if (max != null && value.size > max) out("at most $max items")
            }
            "json" -> if (value !is JsonObject && value !is JsonArray) bad("invalid_type", "a JSON object or array is required")
            else -> bad("invalid_type", "unsupported value type ${def.valueType}")
        }
    }

    private fun numeric(def: KeyDef, v: Double, globalNumber: (String) -> Double?, out: (String) -> Nothing) {
        var min = (def.bounds["min"] as? JsonPrimitive)?.doubleOrNull
        var max = (def.bounds["max"] as? JsonPrimitive)?.doubleOrNull
        (def.bounds["dynamic_min"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.let { k -> globalNumber(k)?.let { min = it } }
        (def.bounds["dynamic_max"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.let { k -> globalNumber(k)?.let { max = it } }
        if (min != null && v < min!!) out("must be at least ${fmt(min!!)}")
        if (max != null && v > max!!) out("must be at most ${fmt(max!!)}")
    }

    private fun fmt(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else d.toString()
}

/**
 * Risk class of one change item (docs/24 s9.4, s9.5). The registry's `risk_class` is the lowest class named for the
 * key; this raises it for the escalation rules written in `risk_rule` / `bounds_rule`: the radius per level, a radius
 * that ends above 150 m and grows the resolved value, "C3 at D and wider", and the value-triggered C3 keys.
 */
object RiskClassifier {
    fun classify(def: KeyDef, scopeType: String, newValue: JsonElement?, currentValue: JsonElement?): Int {
        var c = def.riskClass
        val num = (newValue as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        when (def.key) {
            "cfg.geo.radius_m" -> {
                c = maxOf(c, when (scopeType) { "outlet" -> 1; "route", "zone", "geo_class", "territory" -> 2; else -> 3 })
                val cur = (currentValue as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                if (num != null && num > 150 && (cur == null || num > cur)) c = 3
            }
            "cfg.geo.fix_timeout_s" -> if (num != null && num > 15) c = 3
            "cfg.media.photo_max_kb" -> if (num != null && num > 150) c = 3
            "cfg.geo.fix_accuracy_mode" -> if ((newValue as? JsonPrimitive)?.content == "high") c = 3
        }
        if (def.riskRule?.contains("C3 at D and wider") == true && scopeType in Precedence.wideLevels) c = 3
        return c
    }
}
