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

    private fun item(o: JSONObject) = LeaveItem(
        leaveUuid = o.getString("leave_uuid"), leaveTypeCode = o.getString("leave_type_code"),
        fromDate = o.getString("from_date"), toDate = o.getString("to_date"), days = o.getInt("days"),
        reason = o.getString("reason"), status = LeaveStatus.of(o.getString("status")),
    )

    /** The cache text of a list (the same shape as the page, so one parser reads both). */
    fun toCache(items: List<LeaveItem>): String = JSONObject().put(
        "items",
        org.json.JSONArray(items.filter { it.sent }.map {
            JSONObject().put("leave_uuid", it.leaveUuid).put("leave_type_code", it.leaveTypeCode).put("from_date", it.fromDate)
                .put("to_date", it.toDate).put("days", it.days).put("reason", it.reason).put("status", it.status.wire)
        }),
    ).put("next_cursor", JSONObject.NULL).toString()
}
