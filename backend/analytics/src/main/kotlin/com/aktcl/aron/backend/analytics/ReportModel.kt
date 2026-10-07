package com.aktcl.aron.backend.analytics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Contract `ReportColumn`. `pii` columns are masked (null) unless the caller's token carries the `pii` claim. */
@Serializable
data class ReportColumn(val key: String, val label_en: String, val label_bn: String? = null, val type: String, val unit: String? = null, val pii: Boolean = false)

/** Contract `ReportDefinition` (docs/24 s12.3). */
@Serializable
data class ReportDefinition(
    val report_key: String, val title_en: String, val title_bn: String? = null, val area: String, val grain: String, val formats: List<String>,
    val columns: List<ReportColumn>, val filters: List<String>, val columns_known: Boolean = true,
)

@Serializable
data class ReportDefinitionList(val items: List<ReportDefinition>)

@Serializable
data class ReportResult(
    val report_key: String, val as_of: String, val columns: List<ReportColumn>, val rows: List<Map<String, JsonElement>>,
    val totals: Map<String, JsonElement>? = null, val page: Int, val page_size: Int, val total_rows: Int,
)

@Serializable
data class ExportJob(
    val export_id: String, val report_key: String, val status: String, val row_count: Int? = null, val created_at: String,
    val download_url: String? = null, val expires_at: String? = null, val error: String? = null,
)

@Serializable
data class ExportLogEntry(
    val export_id: String, val report_key: String, val user_id: Long, val username: String? = null, val format: String, val filters: Map<String, JsonElement> = emptyMap(),
    val rows: Int, val pii_included: Boolean, val created_at: String,
)

@Serializable
data class ExportLogPage(val items: List<ExportLogEntry>, val next_cursor: String? = null)

// ---- the request (contract ReportQuery; additionalProperties false everywhere, so a stray scope member is ERR_VALIDATION) ----

@Serializable
data class PeriodDto(val from: String? = null, val to: String? = null, val date: String? = null, val month: String? = null)

@Serializable
data class GeoDto(
    val wing: List<Long> = emptyList(), val division: List<Long> = emptyList(), val territory: List<Long> = emptyList(),
    val zone: List<Long> = emptyList(), val route: List<Long> = emptyList(),
)

@Serializable
data class CriteriaDto(val op: String, val value: Double)

@Serializable
data class SortDto(val col: String, val dir: String)

@Serializable
data class OutputDto(val format: String, val page: Int = 1, val page_size: Int = 50, val sort: List<SortDto> = emptyList())

@Serializable
data class ReportQuery(
    val period: PeriodDto,
    val output: OutputDto,
    val date_grouping: String = "total",
    val location: String? = null,
    val geo: GeoDto? = null,
    val category: List<Long> = emptyList(),
    val product_type: String? = null,
    val products: List<Long> = emptyList(),
    val active_status: String = "all",
    val sub_channels: List<Long> = emptyList(),
    val field_force_type: String = "all",
    val std_criteria: CriteriaDto? = null,
    val memo_criteria: CriteriaDto? = null,
    val outlet_code: String? = null,
)
