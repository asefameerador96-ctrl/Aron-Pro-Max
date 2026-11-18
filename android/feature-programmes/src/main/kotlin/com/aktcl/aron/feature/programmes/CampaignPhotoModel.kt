package com.aktcl.aron.feature.programmes

import com.aktcl.aron.core.database.record.ProgrammePayloads
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** A redeemed campaign gift still waiting for its Gift Verify photos (`programmes.pending_gift_verifications[]`): [qty] units, [photosCaptured] of them already photographed on the server. */
data class ServerPendingGift(val redemptionUuid: String, val outletId: Long, val giftId: Long, val qty: Int, val photosCaptured: Int)

/** A campaign redemption this phone holds that the server may not have yet: one redeemed gift line (a gift and its quantity) with its outbox state. */
data class LocalCampaignGift(val redemptionUuid: String, val outletId: Long, val giftId: Long, val qty: Int, val state: String)

/** One redeemed gift of one redemption with its unit slots: [doneUnits] have a photo (server or this phone), the rest still need one. */
data class CampaignGroup(val redemptionUuid: String, val outletId: Long, val giftId: Long, val qty: Int, val doneUnits: Set<Int>) {
    /** The unit numbers (1..qty) that still need a photo, ascending. */
    val openUnits: List<Int> get() = (1..qty).filter { it !in doneUnits }
}

/**
 * Campaign gift photos (manual 58, "Gift Verify"): one photo per redeemed unit, `unit_no` 1..qty of each redeemed gift line. The pending list is the bundle's
 * `pending_gift_verifications` plus this phone's own redemptions of a campaign programme that the bundle cannot hold yet; a unit counts as photographed when this
 * phone holds a photo record for it that the server did not refuse, or when the server's count says so. The first photo by `captured_at` wins on the server and a later one
 * is stored as a flagged duplicate (never refused), so a unit is photographed once on the phone.
 */
object CampaignPhotoModel {
    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    fun serverPending(programmesJson: String?): List<ServerPendingGift> {
        val o = programmesJson?.let { runCatching { lenient.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return emptyList()
        return (o["pending_gift_verifications"] as? JsonArray).orEmpty().mapNotNull { e ->
            val p = e as? JsonObject ?: return@mapNotNull null
            fun l(k: String) = (p[k] as? JsonPrimitive)?.longOrNull
            ServerPendingGift(
                (p["redemption_client_uuid"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null, l("outlet_id") ?: return@mapNotNull null, l("gift_id") ?: return@mapNotNull null,
                (l("qty") ?: return@mapNotNull null).toInt().takeIf { it in 1..50 } ?: return@mapNotNull null, (l("photos_captured") ?: 0L).toInt().coerceIn(0, 50),
            )
        }
    }

    /**
     * The groups that still need photos, or are partly done on this phone. A redemption the server already lists is taken from the server (its count of photos), with this phone's
     * own photo records on top; a redemption only this phone holds (not refused) gets every unit open except the ones photographed here. Which unit numbers the server's
     * count refers to is not on the wire: its count is taken as the lowest units not photographed on this phone (a limit; the phone's own records are exact).
     */
    fun groups(server: List<ServerPendingGift>, local: List<LocalCampaignGift>, photos: List<LocalGiftPhoto>): List<CampaignGroup> {
        fun mine(uuid: String, gift: Long): Set<Int> = photos.filter { it.programmeKind == "campaign" && it.redemptionUuid == uuid && it.state != "rejected" && it.giftId == gift }.mapNotNull { it.unitNo }.toSet()
        val fromServer = server.map { s ->
            val here = mine(s.redemptionUuid, s.giftId)
            val acked = photos.count { it.programmeKind == "campaign" && it.redemptionUuid == s.redemptionUuid && it.giftId == s.giftId && it.state == "acked" }
            val others = (1..s.qty).filter { it !in here }.take((s.photosCaptured - acked).coerceAtLeast(0)).toSet()
            CampaignGroup(s.redemptionUuid, s.outletId, s.giftId, s.qty, here + others)
        }
        val known = server.map { it.redemptionUuid to it.giftId }.toSet()
        val fromPhone = local.filter { it.state != "rejected" && (it.redemptionUuid to it.giftId) !in known }
            .map { CampaignGroup(it.redemptionUuid, it.outletId, it.giftId, it.qty, mine(it.redemptionUuid, it.giftId)) }
        return (fromServer + fromPhone).filter { it.openUnits.isNotEmpty() }
    }

    /** The record of one unit's photo: own family, rank 0 ([uuid] is fixed per unit so a double tap or a retry is one record). [fix] is the contract GeoFix of that shutter. */
    fun planUnit(group: CampaignGroup, unitNo: Int, photoUuid: String, fix: JsonObject, uuid: String): PlannedRecord =
        PlannedRecord("gift_photo", uuid, uuid, 0, ProgrammePayloads.giftPhoto("campaign", group.outletId, group.giftId, photoUuid, fix, redemptionUuid = group.redemptionUuid, unitNo = unitNo))
}
