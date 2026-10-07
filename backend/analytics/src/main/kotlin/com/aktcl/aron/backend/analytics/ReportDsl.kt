package com.aktcl.aron.backend.analytics

/** Small helpers so handler files read as a column list and one SELECT. */
internal fun col(key: String, label: String, type: String, bn: String? = null, pii: Boolean = false, unit: String? = null) =
    ReportColumn(key, label, bn, type, unit, pii)

internal fun definition(
    key: String, title: String, area: String, grain: String, columns: List<ReportColumn>, filters: List<String>,
    bn: String? = null, formats: List<String> = listOf("json", "xlsx", "pdf", "print"), known: Boolean = true,
) = ReportDefinition(key, title, bn, area, grain, formats, columns, filters, known)

/** The period column of a report: one value per `date_grouping` bucket of [dateExpr] (a date column). */
internal fun periodExpr(ctx: ReportContext, dateExpr: String): String = when (ctx.query.date_grouping) {
    "day" -> "to_char($dateExpr, 'YYYY-MM-DD')"
    "week" -> "to_char(date_trunc('week', $dateExpr), 'YYYY-MM-DD')"
    "month" -> "to_char($dateExpr, 'YYYY-MM')"
    else -> "'total'"
}
