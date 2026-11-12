package com.aktcl.aron.feature.tso.deviceotp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * One SR (or AMO) of the TSO's reach with the live bind code, if any (contract `DeviceOtp`, `GET /v1/admin/device-otps`). The code is
 * [otp] while it is unexpired, null when the rep has none (then the TSO can issue one). The server decides who is listed: the TSO's
 * zones from the token; the app names no scope. The code is a secret: it is never logged, never kept on disk, never in saved state.
 */
class DeviceOtpRow(
    val userId: Long,
    val username: String,
    val fullName: String,
    val employeeCode: String?,
    val zoneName: String?,
    val otp: String?,
    val deviceModel: String?,
    val expiresAt: String,
    val attempts: Int,
) {
    // no toString on purpose: a data class would print the code into any log line that holds the row
    override fun toString(): String = "DeviceOtpRow(user=$userId)"
}

/** One page of `DeviceOtpPage`: the rows and the cursor of the next page. */
class DeviceOtpPage(val rows: List<DeviceOtpRow>, val nextCursor: String?)

/** Parsing and the issue request of the TSO's Device OTP screen (contract `listDeviceOtps` and `issueDeviceOtp`). No network. */
object TsoDeviceOtps {
    /** The audit text stored with a code the TSO issues from the app (the contract needs 10 to 500 characters). */
    const val ISSUE_REASON = "Issued by the TSO in the TSO app so an SR can bind a new phone"

    private fun obj(json: String?): JsonObject? = runCatching { Json.parseToJsonElement(json ?: return null) as? JsonObject }.getOrNull()
    private fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun l(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull

    private fun row(o: JsonObject): DeviceOtpRow? {
        val id = l(o, "user_id") ?: return null
        val name = s(o, "username") ?: return null
        // a code is 4 to 8 digits; anything else is not shown
        val otp = s(o, "otp")?.takeIf { c -> c.length in 4..8 && c.all { it in '0'..'9' } }
        return DeviceOtpRow(
            id, name, s(o, "full_name") ?: name, s(o, "employee_code"), s(o, "zone_name") ?: s(o, "zone_code"),
            otp, s(o, "device_model"), s(o, "expires_at") ?: "", (o["attempts"] as? JsonPrimitive)?.intOrNull ?: 0,
        )
    }

    /** A `DeviceOtpPage`; null when unreadable. A malformed row is skipped. Reps with a live code come first, then by name. */
    fun parse(json: String?): DeviceOtpPage? {
        val o = obj(json) ?: return null
        val items = (o["items"] as? JsonArray) ?: return null
        val rows = items.mapNotNull { (it as? JsonObject)?.let(::row) }
        return DeviceOtpPage(rows.sortedWith(compareByDescending<DeviceOtpRow> { it.otp != null }.thenBy { it.fullName.lowercase() }), s(o, "next_cursor"))
    }

    /** A `DeviceOtp` answer of the issue call. */
    fun parseIssued(json: String?): DeviceOtpRow? = obj(json)?.let(::row)

    /** The body of `POST /v1/admin/device-otps`: the rep's id (from a row the server returned) and the audit reason. */
    fun issueBody(userId: Long): String =
        JsonObject(mapOf("user_id" to JsonPrimitive(userId), "reason" to JsonPrimitive(ISSUE_REASON))).toString()

    /** Merges a freshly issued code into the rows (same rep replaced) and keeps the order rule. */
    fun withIssued(rows: List<DeviceOtpRow>, issued: DeviceOtpRow): List<DeviceOtpRow> =
        (rows.filter { it.userId != issued.userId } + issued).sortedWith(compareByDescending<DeviceOtpRow> { it.otp != null }.thenBy { it.fullName.lowercase() })
}
