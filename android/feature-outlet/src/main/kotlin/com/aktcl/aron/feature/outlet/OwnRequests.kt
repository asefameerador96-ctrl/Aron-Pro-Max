package com.aktcl.aron.feature.outlet

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronLook
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
 * The "My requests" list (F-SR-040, screens-sr s15; PARITY-SPEC): one white card per request with the type in purple, the outlet,
 * the status as an icon and a word and, for a rejected one, the AMO's reason in red. Local data only.
 */
@Composable
fun OwnRequestsContent(rows: List<OwnRequestRow>, modifier: Modifier = Modifier, syncedAt: String? = null) {
    val look = LocalAronLook.current
    if (rows.isEmpty()) {
        Column(
            modifier.fillMaxSize().background(look.page).padding(AronTokens.Space.Screen).testTag(OwnRequestTags.EMPTY),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.out_own_empty), style = MaterialTheme.typography.bodyLarge, color = look.ink, textAlign = TextAlign.Center)
            syncedAt?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = look.inkMuted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = AronTokens.Space.S)) }
        }
        return
    }
    // the list ends above the navigation bar (edge-to-edge): the inset joins the content padding, not the viewport
    val navBottom = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    LazyColumn(
        modifier.fillMaxSize().background(look.page).testTag(OwnRequestTags.LIST),
        contentPadding = PaddingValues(start = AronTokens.Space.Screen, end = AronTokens.Space.Screen, top = AronTokens.Space.M, bottom = AronTokens.Space.M + navBottom),
        verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
    ) {
        rows.forEach { r ->
            item(key = r.requestUuid, contentType = "request") {
                FormCard(Modifier.fillMaxWidth().testTag(OwnRequestTags.row(r.requestUuid))) {
                    Row(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(typeLabel(r.requestType)), style = MaterialTheme.typography.titleSmall, color = look.purple)
                            r.outletName?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = look.inkMuted) }
                        }
                        StatusWordFor(r.status)
                    }
                    r.rejectionReason?.let {
                        Text(
                            stringResource(R.string.out_own_reason, it), Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.L).padding(bottom = AronTokens.Space.M).testTag(OwnRequestTags.reason(r.requestUuid)),
                            style = MaterialTheme.typography.bodySmall, color = look.red,
                        )
                    }
                }
            }
        }
    }
}

/** The status as an icon and a word (never colour alone). */
@Composable
private fun StatusWordFor(s: OwnRequestStatus) {
    val look = LocalAronLook.current
    val icon = when (s) {
        OwnRequestStatus.WAITING -> AronIcons.CloudUpload
        OwnRequestStatus.PENDING -> AronIcons.Clock
        OwnRequestStatus.VERIFIED -> AronIcons.Verified
        OwnRequestStatus.APPROVED -> AronIcons.CheckCircle
        OwnRequestStatus.REJECTED, OwnRequestStatus.LAPSED, OwnRequestStatus.DISCARDED -> AronIcons.Close
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
        Icon(icon, contentDescription = null, tint = look.inkMuted, modifier = Modifier.size(AronTokens.Size.IconSmall))
        Text(stringResource(statusLabel(s)), style = MaterialTheme.typography.labelMedium, color = look.inkMuted)
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
