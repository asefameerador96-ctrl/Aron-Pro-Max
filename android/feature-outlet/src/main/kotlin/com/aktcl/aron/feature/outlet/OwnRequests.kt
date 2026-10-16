package com.aktcl.aron.feature.outlet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.RowPosition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** What an own request shows (F-SR-040): the server's statuses plus [WAITING], a request saved on this phone that the server has not answered yet. */
enum class OwnRequestStatus { WAITING, PENDING, VERIFIED, APPROVED, REJECTED, LAPSED, DISCARDED }

data class OwnRequestRow(
    val requestUuid: String,
    val requestType: String,
    val outletName: String?,
    val status: OwnRequestStatus,
    val rejectionReason: String?,
    /** ISO time used for ordering, newest first. */
    val at: String,
)

/** A request saved on this phone (an `outlet_change_request` row), reduced to what the list needs. */
data class LocalRequest(
    val requestUuid: String, val requestType: String, val outletName: String?, val capturedAt: String,
    /** The outbox state of the request's record (pending, in_flight, acked, rejected, quarantined); null when unknown. */
    val outboxState: String? = null,
    val lastCode: String? = null,
)

/**
 * The SR's own requests (F-SR-040): the bundle's `my_outlet_requests` (last 30 days, status and rejection reason) merged
 * with what is still only on this phone. The server's answer wins for a request it knows; a request only on the phone shows
 * as waiting. Works with no network: both sources are local.
 */
object OwnRequests {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * [sinceIso]: local requests captured before it are dropped (the server list covers 30 days; an old local row must not
     * show as waiting forever). A local-only request shows: waiting while its record is unsent; with the office (pending) once
     * the server has it but the bundle list is older; rejected with the server's code when the record was refused.
     */
    fun rows(serverSectionJson: String?, local: List<LocalRequest>, sinceIso: String = ""): List<OwnRequestRow> {
        val server = parse(serverSectionJson)
        val known = server.map { it.requestUuid }.toSet()
        val fromPhone = local.filter { it.requestUuid !in known && it.capturedAt >= sinceIso }.map {
            val (status, reason) = when (it.outboxState) {
                "acked" -> OwnRequestStatus.PENDING to null
                "rejected", "quarantined" -> OwnRequestStatus.REJECTED to it.lastCode
                else -> OwnRequestStatus.WAITING to null
            }
            OwnRequestRow(it.requestUuid, it.requestType, it.outletName, status, reason, it.capturedAt)
        }
        return (server + fromPhone).sortedByDescending { it.at }
    }

    fun parse(sectionJson: String?): List<OwnRequestRow> {
        val arr = runCatching { sectionJson?.let { json.parseToJsonElement(it) } as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val uuid = s("request_uuid") ?: return@mapNotNull null
            val status = when (s("status")) {
                "pending" -> OwnRequestStatus.PENDING
                "verified" -> OwnRequestStatus.VERIFIED
                "approved" -> OwnRequestStatus.APPROVED
                "rejected" -> OwnRequestStatus.REJECTED
                "lapsed" -> OwnRequestStatus.LAPSED
                "discarded" -> OwnRequestStatus.DISCARDED
                else -> OwnRequestStatus.PENDING // an unknown future status reads as "with the office"
            }
            OwnRequestRow(uuid, s("request_type") ?: "", s("outlet_name"), status, s("rejection_reason")?.takeIf { status == OwnRequestStatus.REJECTED && it.isNotBlank() }, s("requested_at") ?: "")
        }
    }
}

object OwnRequestTags {
    const val LIST = "own_list"
    const val EMPTY = "own_empty"
    fun row(uuid: String) = "own_row_$uuid"
    fun reason(uuid: String) = "own_reason_$uuid"
}

/**
 * The "My requests" list (F-SR-040, screens-sr s15): flat rows with the request type, the outlet, a status pill (word
 * plus icon, in its container colour) and, for a rejected one, the AMO's reason. Local data only.
 */
@Composable
fun OwnRequestsContent(rows: List<OwnRequestRow>, modifier: Modifier = Modifier, syncedAt: String? = null) {
    val c = LocalAronColors.current
    if (rows.isEmpty()) {
        Box(modifier.fillMaxSize().testTag(OwnRequestTags.EMPTY), contentAlignment = Alignment.Center) {
            AronEmptyState(stringResource(R.string.out_own_empty), icon = AronIcons.Document, syncedAt = syncedAt)
        }
        return
    }
    // the list ends above the navigation bar (edge-to-edge): the inset joins the content padding, not the viewport
    val navBottom = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    val dividerColor = c.borderDivider
    val dividerWidth = if (c.sunlight) AronTokens.Stroke.SunlightHairline else AronTokens.Stroke.Hairline
    LazyColumn(
        modifier.fillMaxSize().testTag(OwnRequestTags.LIST),
        contentPadding = PaddingValues(start = AronTokens.Space.Screen, end = AronTokens.Space.Screen, top = AronTokens.Space.S, bottom = AronTokens.Space.S + navBottom),
    ) {
        rows.forEachIndexed { i, r ->
            item(key = r.requestUuid, contentType = "request") {
                val position = when { rows.size == 1 -> RowPosition.Only; i == 0 -> RowPosition.First; i == rows.lastIndex -> RowPosition.Last; else -> RowPosition.Middle }
                // the row and its reason share one flat-row paint, so the rejected row stays inside the rounded group
                val divider = i != rows.lastIndex
                Column(Modifier.fillMaxWidth().flatRow(position).testTag(OwnRequestTags.row(r.requestUuid))) {
                    AronListRow(
                        title = stringResource(typeLabel(r.requestType)), subtitle = r.outletName, divider = divider && r.rejectionReason == null,
                        trailingContent = { StatusPillFor(r.status) },
                    )
                    r.rejectionReason?.let {
                        Text(
                            stringResource(R.string.out_own_reason, it), Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.L).padding(bottom = AronTokens.Space.M).testTag(OwnRequestTags.reason(r.requestUuid)),
                            style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
                        )
                        if (divider) {
                            Spacer(Modifier.fillMaxWidth().padding(start = AronTokens.Space.L).height(dividerWidth).drawBehind { drawRect(dividerColor) })
                        }
                    }
                }
            }
        }
    }
}

/** Status chips of screens-sr s15: submitted (accent), with the AMO (warning), approved (success), the rest neutral. */
@Composable
private fun StatusPillFor(s: OwnRequestStatus) {
    val c = LocalAronColors.current
    val label = stringResource(statusLabel(s))
    when (s) {
        OwnRequestStatus.WAITING -> StatusPill(label, AronIcons.CloudUpload, c.offlineContainer, c.offlineOnContainer)
        OwnRequestStatus.PENDING -> StatusPill(label, AronIcons.Clock, c.accentContainer, c.accentOnContainer)
        OwnRequestStatus.VERIFIED -> StatusPill(label, AronIcons.Verified, c.warningContainer, c.warningOnContainer)
        OwnRequestStatus.APPROVED -> StatusPill(label, AronIcons.CheckCircle, c.successContainer, c.successOnContainer)
        OwnRequestStatus.REJECTED, OwnRequestStatus.LAPSED, OwnRequestStatus.DISCARDED -> StatusPill(label, AronIcons.Close, c.offlineContainer, c.offlineOnContainer)
    }
}

private fun statusLabel(s: OwnRequestStatus) = when (s) {
    OwnRequestStatus.WAITING -> R.string.out_own_waiting
    OwnRequestStatus.PENDING -> R.string.out_own_pending
    OwnRequestStatus.VERIFIED -> R.string.out_own_verified
    OwnRequestStatus.APPROVED -> R.string.out_own_approved
    OwnRequestStatus.REJECTED -> R.string.out_own_rejected
    OwnRequestStatus.LAPSED -> R.string.out_own_lapsed
    OwnRequestStatus.DISCARDED -> R.string.out_own_discarded
}

private fun typeLabel(type: String) = when (type) {
    "new" -> R.string.out_req_new
    "close" -> R.string.out_req_close
    "info" -> R.string.out_req_info
    "cluster" -> R.string.out_req_cluster
    "route_add" -> R.string.out_req_route_add
    "location" -> R.string.out_own_type_location
    else -> R.string.out_own_type_other
}
