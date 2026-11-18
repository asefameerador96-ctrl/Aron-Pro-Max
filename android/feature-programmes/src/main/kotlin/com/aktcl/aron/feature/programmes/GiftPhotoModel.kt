package com.aktcl.aron.feature.programmes

import com.aktcl.aron.core.database.record.ProgrammePayloads
import kotlinx.serialization.json.JsonObject

/** A gift photo record this phone holds (from the stored `gift_photo` record and its outbox state). */
data class LocalGiftPhoto(val programmeKind: String, val outletId: Long, val assignmentId: Long?, val redemptionUuid: String?, val unitNo: Int?, val state: String, val giftId: Long = 0L)

/** Where the hand-over photo of an outlet's Astha gift stands. */
sealed interface PhotoStatus {
    /** The server holds no gift for the outlet and quarter yet: choose one and sync first. */
    data object NoGift : PhotoStatus
    /** A gift is held and no photo exists: the photo can be taken. */
    data class Ready(val giftId: Long, val assignmentId: Long) : PhotoStatus
    /** The server locked the choice with no photo of its own (for example by the TSO): no photo is needed from this phone. */
    data class Locked(val giftId: Long) : PhotoStatus
    /** A photo exists: the server's flag, a locked choice, or a photo this phone saved ([onThisPhone]: not necessarily sent yet). The first photo wins on the server. */
    data class Captured(val giftId: Long, val onThisPhone: Boolean) : PhotoStatus
}

object GiftPhotoModel {
    /** The status of an outlet's Astha gift photo for [quarter]; a photo the server refused does not count, so it can be taken again. */
    fun statusOf(data: GiftChoiceData, outletId: Long, quarter: String, local: List<LocalGiftPhoto>): PhotoStatus {
        val a = data.assignment(outletId, quarter)?.takeIf { it.id > 0 } ?: return PhotoStatus.NoGift
        if (a.photoCaptured) return PhotoStatus.Captured(a.giftId, onThisPhone = false)
        if (a.locked) return PhotoStatus.Locked(a.giftId)
        val mine = local.any { it.programmeKind == "astha" && it.assignmentId == a.id && it.state != "rejected" }
        return if (mine) PhotoStatus.Captured(a.giftId, onThisPhone = true) else PhotoStatus.Ready(a.giftId, a.id)
    }

    /** The client uuid of an Astha gift photo: fixed by its subject (the assignment and the quarter), so a retry, a kill or a second tap is the same record. */
    fun asthaUuid(assignmentId: Long, quarter: String, attempt: Int = 0): String =
        com.aktcl.aron.core.common.ClientIds.derivedUuid(*(listOf("gift_photo", "astha", assignmentId.toString(), quarter) + attemptPart(attempt)).toTypedArray())

    /** Attempt 0 adds nothing (the key shape of records already held stays); a retake after the server refused attempt n is attempt n+1 (see ProgrammeStore.giftPhotoAttempt). */
    internal fun attemptPart(attempt: Int): List<String> = if (attempt <= 0) emptyList() else listOf("attempt", attempt.toString())

    /** The Astha hand-over record the Submit button writes: own family, rank 0, [uuid] fixed when the page opens so a double tap is one record. [fix] is the contract GeoFix of the shutter. */
    fun planAstha(outletId: Long, giftId: Long, assignmentId: Long, photoUuid: String, fix: JsonObject, uuid: String): PlannedRecord =
        PlannedRecord("gift_photo", uuid, uuid, 0, ProgrammePayloads.giftPhoto("astha", outletId, giftId, photoUuid, fix, assignmentId = assignmentId))
}
