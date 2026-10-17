package com.aktcl.aron.feature.tso.leave

import org.json.JSONObject

/** Reads `GET /v1/leave` (contract `LeavePage`) and the cached copy of it; unknown members are ignored, bad rows are dropped, never a crash. */
object LeaveJson {
    data class Page(val items: List<LeaveItem>, val nextCursor: String?)

    fun parsePage(body: String): Page? = runCatching {
        val o = JSONObject(body)
        val arr = o.getJSONArray("items")
        val items = (0 until arr.length()).mapNotNull { i -> runCatching { item(arr.getJSONObject(i)) }.getOrNull() }
        Page(items, if (o.isNull("next_cursor")) null else o.optString("next_cursor").takeIf { it.isNotEmpty() })
    }.getOrNull()

    /** A string member that must be present and not JSON null (org.json would turn null into the text "null"). */
    private fun str(o: JSONObject, key: String): String {
        require(!o.isNull(key)) { "$key is null" }
        return o.getString(key)
    }

    /** A row with a missing member or a status the contract does not know is dropped: never shown as pending or approved by guess. */
    private fun item(o: JSONObject) = LeaveItem(
        leaveUuid = str(o, "leave_uuid"), leaveTypeCode = str(o, "leave_type_code"),
        fromDate = str(o, "from_date"), toDate = str(o, "to_date"), days = o.getInt("days"),
        reason = str(o, "reason"), status = checkNotNull(LeaveStatus.entries.firstOrNull { it.wire == str(o, "status") }) { "unknown status" },
    )

    /** The cache text of a list (the same shape as the page, so one parser reads both). */
    fun toCache(items: List<LeaveItem>): String = JSONObject().put(
        "items",
        org.json.JSONArray(items.filter { it.sent }.map {
            JSONObject().put("leave_uuid", it.leaveUuid).put("leave_type_code", it.leaveTypeCode).put("from_date", it.fromDate)
                .put("to_date", it.toDate).put("days", it.days).put("reason", it.reason).put("status", it.status.wire)
        }),
    ).put("next_cursor", JSONObject.NULL).toString()

    /** One local (unsent) application as stored in the mirror row; read back with [parseUnsent]. */
    fun unsentToJson(i: LeaveItem): String = JSONObject().put("leave_uuid", i.leaveUuid).put("leave_type_code", i.leaveTypeCode)
        .put("from_date", i.fromDate).put("to_date", i.toDate).put("days", i.days).put("reason", i.reason).put("status", "pending").toString()

    fun parseUnsent(text: String): LeaveItem? = runCatching { item(JSONObject(text)).copy(sent = false) }.getOrNull()
}
