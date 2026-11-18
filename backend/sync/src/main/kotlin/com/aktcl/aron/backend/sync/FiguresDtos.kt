package com.aktcl.aron.backend.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * Wire shapes of POST /v1/sync/figures (contract `FiguresRequest`, `FiguresResponse`; D-TKPI-14). The SR programme figures of the bundle's route snapshots, built by the
 * same loaders, plus the identity answer for the phone's probe, all read in ONE repeatable-read transaction.
 */

@Serializable
data class FigureProbeEntry(
    val memo_client_uuid: String,
    /** The printed memo number: lets the server answer for the phone's copy of a memo the office keyed from paper (the twin carries the same number and date). */
    val memo_no: String? = null,
    val business_date: String,
    /** True when the phone has voided or superseded the memo locally: the answer then lists the stored lines the phone must subtract. */
    val removed_locally: Boolean? = null,
    /** With `removed_locally`: the uuid of the removing record (the memo_void, or the edit memo that supersedes it). When that record is REFUSED the answer lists it in `refused` and the phone stops subtracting the old memo. */
    val removal_client_uuid: String? = null,
)

@Serializable
data class FiguresRequest(
    @SerialName("for") val forDate: String? = null,
    val probe: List<FigureProbeEntry> = emptyList(),
    /** Asks for the Astha section built in the same transaction (the programmes loader; its identity predicate is the loyalty lane's). */
    val include_astha: Boolean? = null,
)

@Serializable data class FigureStoredLine(val sku_id: Long, val qty_base: Long, val line_kind: String)

@Serializable
data class FigureInMemo(
    val memo_client_uuid: String,
    /** The number of non-voided lines the server holds for the memo (the phone sees when a line was refused for good). */
    val lines: Int,
    /** Only for a probe with `removed_locally`: the stored lines, so the phone subtracts exactly what the figure counted. */
    val stored_lines: List<FigureStoredLine>? = null,
)

@Serializable
data class FigureProbeAnswer(
    /** The memos that ARE in the figures. */
    val in_figure: List<FigureInMemo>,
    /** The memos the server holds that are gone for good (voided, superseded, admin data void, every line voided). */
    val voided: List<String>,
    /** An unknown uuid whose memo number matched more than one own paper memo: answered NOT IN, never a guess. Absent when none. */
    val twin_ambiguous: List<String>? = null,
    /** REFUSED: the caller's OWN records the server will never store (a final reject, a reviewer's discard) and the removing records named in the probe that are refused. Absent when none. */
    val refused: List<String>? = null,
)

@Serializable
data class FigureRoute(
    val route_id: Long,
    val targets: List<JsonObject>,
    val achievement_mtd: List<JsonObject>,
    val calendar: RouteCalendarDto? = null,
    val kpi: JsonObject? = null,
    val star: JsonObject? = null,
    val campaigns: List<JsonObject>? = null,
    val campaigns_truncated: Boolean? = null,
    val targets_truncated: Boolean? = null,
    val star_truncated: Boolean? = null,
)

@Serializable
data class FiguresResponse(
    val business_date: String,
    val through_date: String,
    val routes: List<FigureRoute>,
    val figure_probe: FigureProbeAnswer,
    /** The Astha section of the bundle, built in the same transaction when asked for; absent otherwise. */
    val astha: JsonObject? = null,
)
