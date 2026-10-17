package com.aktcl.aron.feature.amo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate

data class ReportCol(val key: String, val labelEn: String, val labelBn: String?, val type: String)
typealias ReportRow = Map<String, JsonPrimitive>
data class ReportData(val asOf: String, val columns: List<ReportCol>, val rows: List<ReportRow>, val totalRows: Int) {
    /** True when the phone holds fewer rows than the server has (the report is long; the first pages are shown). */
    val truncated: Boolean get() = rows.size < totalRows
}

/** A route card of the Sales Summary Up To Now (F-AMO-033): target is zero or unknown → [cprPct] null → a dash on screen. */
data class RouteCard(val code: String, val name: String, val targetOutlets: Long, val successfulCalls: Long, val cprPct: Double?, val memos: Long, val netMtk: Long, val brands: List<BrandRow>)
data class BrandRow(val name: String, val memos: Long, val bsrPct: Double?)

/** Per-route totals of the STD Memo Report (F-AMO-032). */
data class RouteStd(val code: String, val memos: Int, val grossMtk: Long, val netMtk: Long)

object Reports {
    /** The `ReportQuery` body: one period, JSON output, 500 rows a page. The scope is the caller's reach on the server; nothing is selected here. */
    fun body(from: String, to: String, page: Int): String = buildJsonObject {
        putJsonObject("period") { put("from", from); put("to", to) }
        putJsonObject("output") { put("format", "json"); put("page", page); put("page_size", 500) }
    }.toString()

    /** First of the month to yesterday (docs/07); on the 1st it is the previous month, so the range is never empty or inverted. */
    fun stdMemoRange(today: String): Pair<String, String> {
        val t = LocalDate.parse(today); val y = t.minusDays(1)
        return (if (t.dayOfMonth == 1) y.withDayOfMonth(1) else t.withDayOfMonth(1)).toString() to y.toString()
    }

    fun parse(json: String?): ReportData? {
        val o = runCatching { Json.parseToJsonElement(json ?: return null) as? JsonObject }.getOrNull() ?: return null
        val cols = (o["columns"] as? JsonArray).orEmpty().mapNotNull { e ->
            val c = e as? JsonObject ?: return@mapNotNull null
            ReportCol((c["key"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null, (c["label_en"] as? JsonPrimitive)?.contentOrNull.orEmpty(), (c["label_bn"] as? JsonPrimitive)?.contentOrNull, (c["type"] as? JsonPrimitive)?.contentOrNull ?: "string")
        }
        val rows = (o["rows"] as? JsonArray).orEmpty().mapNotNull { e -> (e as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.let { k to it } }?.toMap() }
        return ReportData((o["as_of"] as? JsonPrimitive)?.contentOrNull.orEmpty(), cols, rows, ((o["total_rows"] as? JsonPrimitive)?.longOrNull ?: rows.size.toLong()).toInt())
    }

    /** Joins the pages of one report into one document (columns of the first page, all rows). */
    fun combine(pages: List<String>, keep: Set<String>? = null): String {
        val parsed = pages.mapNotNull { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val first = parsed.firstOrNull() ?: return "{}"
        return buildJsonObject {
            put("as_of", first["as_of"] ?: JsonNull); put("columns", if (keep == null) first["columns"] ?: JsonArray(emptyList()) else JsonArray((first["columns"] as? JsonArray).orEmpty().filter { ((it as? JsonObject)?.get("key") as? JsonPrimitive)?.contentOrNull in keep })); put("total_rows", first["total_rows"] ?: JsonNull)
            // [keep] drops the columns the screens never show, so a long report still fits the kept copy and the phone's memory.
            put("rows", buildJsonArray { parsed.forEach { p -> (p["rows"] as? JsonArray)?.forEach { r -> add(if (keep == null || r !is JsonObject) r else JsonObject(r.filterKeys { it in keep })) } } })
        }.toString()
    }

    private fun ReportRow.l(k: String) = this[k]?.takeIf { it !is JsonNull }?.longOrNull
    private fun ReportRow.d(k: String) = this[k]?.takeIf { it !is JsonNull }?.doubleOrNull
    private fun ReportRow.s(k: String) = this[k]?.takeIf { it !is JsonNull }?.contentOrNull

    /** Route cards with their brand tables from the `sales-summary` rows (one row per route and brand). */
    fun routeCards(r: ReportData): List<RouteCard> = r.rows.groupBy { it.s("route_code").orEmpty() }.map { (code, rows) ->
        val h = rows.first()
        RouteCard(
            code, h.s("route_name").orEmpty(), h.l("target_outlets") ?: 0, h.l("successful_calls") ?: 0,
            // A zero target has no rate: the server sends null, and so does a missing value; both show a dash.
            h.d("cpr_pct")?.takeIf { (h.l("target_outlets") ?: 0) > 0 }, h.l("memos") ?: 0, h.l("net_mtk") ?: 0,
            rows.mapNotNull { b -> b.s("brand_name")?.let { BrandRow(it, b.l("brand_memos") ?: 0, b.d("bsr_pct")) } },
        )
    }.sortedBy { it.code }

    val STD_COLUMNS = listOf("memo_no", "business_date", "route_code", "outlet_name", "line_count", "gross_mtk", "net_mtk", "paid_mtk", "due_mtk")

    /** Memos, gross and net per route from the `std-memo` rows; only meaningful when the report is complete (the screen hides it when [ReportData.truncated]). */
    fun stdByRoute(r: ReportData): List<RouteStd> = r.rows.groupBy { it.s("route_code").orEmpty() }.map { (code, rows) ->
        RouteStd(code, rows.size, rows.sumOf { it.l("gross_mtk") ?: 0 }, rows.sumOf { it.l("net_mtk") ?: 0 })
    }.sortedBy { it.code }
}
