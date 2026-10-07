package com.aktcl.aron.backend.analytics

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.dhatim.fastexcel.Workbook
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** xlsx and print renderers of one report result. The rows are exactly the rows of the screen (same engine query, same masking). */
object ReportOutputs {
    /**
     * Formula-injection guard (docs/24 s12.3): a text cell that a spreadsheet would read as a formula (leading `=`, `+`, `-`,
     * `@`, tab or CR, also after blanks) is stored with a leading apostrophe-free quote character so it shows as text.
     */
    fun sanitize(s: String): String {
        val first = s.trimStart(' ').firstOrNull() ?: return s
        return if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r' || first == '\n') "'$s" else s
    }

    fun xlsx(out: OutputStream, def: ReportDefinition, columns: List<ReportColumn>, rows: Iterator<Map<String, JsonElement>>, watermark: String): Int {
        var n = 0
        Workbook(out, "Aron", "1.0").use { wb ->
            val ws = wb.newWorksheet("Report")
            columns.forEachIndexed { c, col -> ws.value(0, c, sanitize(col.label_en)) }
            while (rows.hasNext()) {
                val row = rows.next(); n++
                columns.forEachIndexed { c, col -> cell(ws, n, c, row[col.key], col.type) }
            }
            val info = wb.newWorksheet("Info")
            info.value(0, 0, sanitize(def.title_en)); info.value(1, 0, sanitize(watermark)); info.value(2, 0, "rows: $n")
        }
        return n
    }

    private fun cell(ws: org.dhatim.fastexcel.Worksheet, r: Int, c: Int, v: JsonElement?, type: String) {
        val p = v as? JsonPrimitive
        when {
            v == null || v is JsonNull || p == null -> Unit
            p.isString -> ws.value(r, c, sanitize(p.content))
            p.content == "true" || p.content == "false" -> ws.value(r, c, p.content.toBoolean())
            type == "integer" || type == "mtk" -> p.content.toLongOrNull()?.let { ws.value(r, c, it) } ?: ws.value(r, c, p.content.toDouble())
            else -> ws.value(r, c, p.content.toDouble())
        }
    }

    fun xlsxBytes(def: ReportDefinition, columns: List<ReportColumn>, rows: List<Map<String, JsonElement>>, watermark: String): ByteArray =
        ByteArrayOutputStream().also { xlsx(it, def, columns, rows.iterator(), watermark) }.toByteArray()

    private fun esc(s: String) = buildString { for (ch in s) when (ch) { '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); '\'' -> append("&#39;"); else -> append(ch) } }

    /** Server-rendered print view: A4-friendly table, Bangla title when present, watermark footer. Every value is HTML-escaped. */
    fun printHtml(def: ReportDefinition, columns: List<ReportColumn>, rows: Iterator<Map<String, JsonElement>>, watermark: String): Pair<String, Int> {
        var n = 0
        val sb = StringBuilder(8192)
        sb.append("<!doctype html><html lang=\"bn\"><head><meta charset=\"utf-8\"><title>").append(esc(def.title_en)).append("</title>")
            .append("<style>body{font-family:'Noto Sans Bengali','Noto Sans',sans-serif;font-size:11px}table{border-collapse:collapse;width:100%}")
            .append("th,td{border:1px solid #888;padding:2px 4px;text-align:left}td.n{text-align:right}th{background:#eee}@page{size:A4 landscape;margin:10mm}</style></head><body>")
            .append("<h2>").append(esc(def.title_en)).append(def.title_bn?.let { " / " + esc(it) } ?: "").append("</h2><table><thead><tr>")
        for (c in columns) sb.append("<th>").append(esc(c.label_en)).append("</th>")
        sb.append("</tr></thead><tbody>")
        while (rows.hasNext()) {
            val row = rows.next(); n++
            sb.append("<tr>")
            for (c in columns) {
                val p = row[c.key] as? JsonPrimitive
                val numeric = p != null && !p.isString && p.content != "null" && p.content != "true" && p.content != "false"
                sb.append(if (numeric) "<td class=\"n\">" else "<td>").append(if (p == null || row[c.key] is JsonNull) "" else esc(p.content)).append("</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</tbody></table><p>").append(esc(watermark)).append(" &middot; rows: ").append(n).append("</p></body></html>")
        return sb.toString() to n
    }
}
