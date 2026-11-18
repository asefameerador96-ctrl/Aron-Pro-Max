package com.aktcl.aron.backend.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * The shape of the phone's own anti-spoofing evidence (contract 1.19.0): `GeoFix.signals` on every fix-bearing record and `DeviceStatusReport.integrity_events`
 * on `device_status`. The generic shape check only looks at member NAMES, so without this a tampered phone could store any JSON here and the readers of the
 * stored rows (the risk evaluator) would have to survive it. A malformed value makes the record `schema_invalid`; a code the server does not know (a newer
 * phone) is KEPT as an opaque, length-bounded string and never trusted as structure: only the known codes count anywhere downstream.
 */
internal object PhoneEvidenceShape {
    const val MAX_SIGNALS = 16
    const val MAX_EVENTS = 50
    const val MAX_FLAGS = 8
    const val MAX_CODE_LEN = 32
    private val EVENT_MEMBERS = setOf("at", "flags", "exempt", "app_version")

    /** The first problem of [payload] (of record type [type] with [shape]), or null. */
    fun error(type: String, shape: PayloadShape, payload: JsonObject): String? {
        for ((k, nested) in shape.nested) {
            if ("signals" !in nested.allowed) continue
            val v = (payload[k] as? JsonObject)?.get("signals") ?: continue
            if (v is JsonNull) continue
            val a = v as? JsonArray ?: return "$k.signals must be an array"
            if (a.size > MAX_SIGNALS) return "$k.signals has more than $MAX_SIGNALS codes"
            if (a.any { !isCode(it) }) return "$k.signals holds a value that is not a code (a string of 1..$MAX_CODE_LEN characters)"
        }
        if (type == "device_status") events(payload["integrity_events"])?.let { return it }
        return null
    }

    private val CODE = Regex("^[a-z0-9_]{1,$MAX_CODE_LEN}$")

    /** A code: lower-case letters, digits and underscore only, 1..32 characters (it is stored, shown in the portal and put into evidence). */
    private fun isCode(e: kotlinx.serialization.json.JsonElement) = e is JsonPrimitive && e.isString && CODE.matches(e.content)

    private fun events(v: kotlinx.serialization.json.JsonElement?): String? {
        if (v == null || v is JsonNull) return null
        val a = v as? JsonArray ?: return "integrity_events must be an array"
        if (a.size > MAX_EVENTS) return "integrity_events has more than $MAX_EVENTS entries"
        for ((i, e) in a.withIndex()) {
            val o = e as? JsonObject ?: return "integrity_events[$i] must be an object"
            (o.keys - EVENT_MEMBERS).firstOrNull()?.let { return "unknown member integrity_events[$i].$it" }
            val at = o["at"] as? JsonPrimitive
            if (at == null || !at.isString || runCatching { Instant.parse(at.content) }.isFailure) return "integrity_events[$i].at must be a UTC timestamp"
            val flags = o["flags"] as? JsonArray ?: return "integrity_events[$i].flags must be an array"
            if (flags.size > MAX_FLAGS || flags.any { !isCode(it) }) return "integrity_events[$i].flags must hold at most $MAX_FLAGS codes (strings of 1..$MAX_CODE_LEN characters)"
            if (!isCode(o["exempt"] ?: return "integrity_events[$i].exempt is required")) return "integrity_events[$i].exempt must be a code"
            val ver = o["app_version"]
            if (ver != null && ver !is JsonNull && (ver !is JsonPrimitive || !ver.isString || ver.content.length > 40)) return "integrity_events[$i].app_version must be a string of at most 40 characters"
        }
        return null
    }
}
