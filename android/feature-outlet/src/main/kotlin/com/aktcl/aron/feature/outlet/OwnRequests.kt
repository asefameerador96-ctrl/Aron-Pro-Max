package com.aktcl.aron.feature.outlet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronCard
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
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

/** The "My requests" list: type, outlet, status and, for a rejected one, the reason. Solid cards (docs/32 s2a). */
@Composable
fun OwnRequestsContent(rows: List<OwnRequestRow>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
        Text(stringResource(R.string.out_own_title), style = MaterialTheme.typography.headlineSmall)
        if (rows.isEmpty()) {
            AronBanner(stringResource(R.string.out_own_empty), Modifier.testTag(OwnRequestTags.EMPTY), kind = BannerKind.Info)
            return@Column
        }
        LazyColumn(Modifier.testTag(OwnRequestTags.LIST), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            items(rows, key = { it.requestUuid }) { r ->
                AronCard(Modifier.fillMaxWidth().testTag(OwnRequestTags.row(r.requestUuid))) {
                    Column(Modifier.padding(AronTokens.Space.M), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
                        Text(stringResource(typeLabel(r.requestType)), style = MaterialTheme.typography.titleMedium)
                        r.outletName?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                        Text(stringResource(statusLabel(r.status)), style = MaterialTheme.typography.labelLarge)
                        r.rejectionReason?.let { Text(stringResource(R.string.out_own_reason, it), Modifier.testTag(OwnRequestTags.reason(r.requestUuid)), style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
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
