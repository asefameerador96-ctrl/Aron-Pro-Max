package com.aktcl.aron.amo

import com.aktcl.aron.core.database.entity.OutboxState
import com.aktcl.aron.feature.outlet.FixReading

/** What the AMO chose on a verification form. [routeId] and [clusterId] are only what they picked (null = unchanged, as the contract says); a reject sends neither. */
data class VerifyChoice(
    val verify: Boolean, val subChannelId: Long?, val geoClass: String?, val photoUuid: String?, val fix: FixReading?, val routeId: Long?, val clusterId: Long?, val newOutlet: Boolean,
)

/** The outcome of a form save: [ok], the record uuid the decision was queued under, and whether the request is held for review (nothing queued). */
data class VerifyOutcome(val ok: Boolean, val recordUuid: String, val held: Boolean = false)

/**
 * The verification form's save (contract 1.11.1). A decision the server held (`quarantined`) blocks a new one; one it refused (`rejected`) leaves the
 * request decidable and the corrected save takes a NEW record uuid (the draft key is cleared); otherwise [currentRecordUuid] is kept, so a replay
 * queues one row. A reject sends no sub-channel, geo, photo, route or cluster.
 */
suspend fun AmoRecordWriter.decideFromForm(drafts: FormDrafts, draftKey: String, requestUuid: String, currentRecordUuid: String, c: VerifyChoice): VerifyOutcome {
    var uuid = currentRecordUuid
    when (refusedDecision(requestUuid)?.first) {
        OutboxState.QUARANTINED -> return VerifyOutcome(false, uuid, held = true)
        OutboxState.REJECTED -> { drafts.clear(draftKey); uuid = drafts.uuid(draftKey) }
        else -> Unit
    }
    val v = c.verify
    val ok = decideRequest(
        uuid, requestUuid, v, if (v && c.newOutlet) c.subChannelId else null, if (v && c.newOutlet) c.geoClass else null, null,
        if (v && c.newOutlet) c.photoUuid else null, if (v && c.newOutlet) c.fix else null, if (v) c.routeId else null, if (v) c.clusterId else null,
    )
    return VerifyOutcome(ok, uuid)
}
