package com.aktcl.aron.feature.programmes

import com.aktcl.aron.core.database.record.ProgrammePayloads
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** The Astha gift an outlet has for a quarter (`programmes.gift_assignments[]`): chosen, photographed (locked) or not. */
data class GiftAssignment(val outletId: Long, val quarter: String, val giftId: Long, val tierCode: String?, val photoCaptured: Boolean, val locked: Boolean)

/** A gift choice this phone holds (from the stored `gift_choice` record and its outbox state). */
data class LocalGiftChoice(
    val outletId: Long, val quarter: String, val giftId: Long, val state: String, val ackedAt: String? = null,
    /** The server's reason text of a refusal (the bundle's `reason_texts` of the ack's message key or code), Bangla and English; null when it has none. */
    val reasonBn: String? = null, val reasonEn: String? = null,
)

/** Where an outlet's Astha gift stands for the quarter, as the screen shows it. */
sealed interface GiftState {
    /** Nothing chosen yet. */
    data object None : GiftState
    /** The server holds the choice; the SR may change it until the hand-over photo locks it. */
    data class Chosen(val giftId: Long) : GiftState
    /** This phone saved a choice that has not reached the server yet (or that the server refused: [refused]). */
    data class OnThisPhone(val giftId: Long, val refused: Boolean, val reasonBn: String? = null, val reasonEn: String? = null) : GiftState
    /** The server acknowledged this phone's choice and the held bundle was requested before that: the choice is sent, the bundle does not show it yet. */
    data class Sent(val giftId: Long) : GiftState
    /** The hand-over photo locked the choice: shown, not changeable (the server refuses a later choice with `gift_locked`). */
    data class Locked(val giftId: Long) : GiftState
}

data class GiftChoiceData(val gifts: List<LoyaltyGift>, val assignments: List<GiftAssignment>) {
    /**
     * The gifts offered to an outlet: active gifts of the Astha programmes. A gift restricted to tiers (`tier_codes`) is offered only when the
     * outlet's tier is known and listed (the server refuses `gift_not_for_tier` otherwise); a gift with no tier list is offered to every tier.
     */
    fun offeredTo(tierCode: String?): List<LoyaltyGift> =
        gifts.filter { it.active && (it.tierCodes.isEmpty() || (tierCode != null && tierCode in it.tierCodes)) }

    fun assignment(outletId: Long, quarter: String): GiftAssignment? = assignments.firstOrNull { it.outletId == outletId && it.quarter == quarter }

    /**
     * The latest local choice wins until the server answers: still on the phone is "not sent yet", a refusal is shown with the server's reason, an
     * acknowledged one is "sent" while the held bundle ([bundleRequestedAt]) was requested before the ack, and once the bundle was requested after the ack the
     * bundle's value wins (a TSO's later change shows). A locked assignment always wins.
     */
    fun stateOf(outletId: Long, quarter: String, local: List<LocalGiftChoice>, bundleRequestedAt: String? = null): GiftState {
        val a = assignment(outletId, quarter)
        if (a != null && a.locked) return GiftState.Locked(a.giftId)
        val mine = local.lastOrNull { it.outletId == outletId && it.quarter == quarter }
        if (mine != null) {
            when (mine.state) {
                "rejected" -> return GiftState.OnThisPhone(mine.giftId, refused = true, mine.reasonBn, mine.reasonEn)
                "acked" -> {
                    val ack = DhakaTime.ms(mine.ackedAt); val req = DhakaTime.ms(bundleRequestedAt)
                    val bundleHasIt = a != null && ack != null && req != null && ack <= req
                    if (!bundleHasIt) return GiftState.Sent(mine.giftId)
                }
                else -> return GiftState.OnThisPhone(mine.giftId, refused = false)
            }
        }
        return if (a != null) GiftState.Chosen(a.giftId) else GiftState.None
    }
}

object GiftChoiceModel {
    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Parses the stored `programmes` section: the Astha gifts (programmes of kind astha) and the assignments; null when the section is absent. */
    fun parse(programmesJson: String?): GiftChoiceData? {
        val o = programmesJson?.let { runCatching { lenient.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return null
        val data = LoyaltyModel.parse(programmesJson) ?: return null
        val asthaIds = (o["programmes"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject) }
            .filter { (it["kind"] as? JsonPrimitive)?.contentOrNull == "astha" }.mapNotNull { (it["programme_id"] as? JsonPrimitive)?.longOrNull }.toSet()
        val assignments = (o["gift_assignments"] as? JsonArray).orEmpty().mapNotNull { e ->
            val a = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (a[k] as? JsonPrimitive)?.contentOrNull
            GiftAssignment(
                (a["outlet_id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null, s("quarter") ?: return@mapNotNull null,
                (a["gift_id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null, s("tier_code"),
                (a["photo_captured"] as? JsonPrimitive)?.booleanOrNull ?: false, (a["locked"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }
        return GiftChoiceData(data.gifts.filter { it.programmeId in asthaIds }, assignments)
    }

    /** The record the Submit button writes: the outlet's gift for the quarter, own family, rank 0. [uuid] is fixed when the page opens so a double tap is one record. */
    fun plan(outletId: Long, giftId: Long, quarter: String, uuid: String): PlannedRecord =
        PlannedRecord("gift_choice", uuid, uuid, 0, ProgrammePayloads.giftChoice(outletId, giftId, quarter))

    /** The Astha quarter label `YYYY-Qn` of a Dhaka business date `YYYY-MM-DD`, quarters starting at [startMonth] (cfg.astha.quarter_start_month, default 1). */
    fun quarterOf(businessDate: String, startMonth: Int = 1): String {
        val y = businessDate.take(4).toInt(); val m = businessDate.substring(5, 7).toInt()
        val shifted = (m - startMonth + 12) % 12
        val year = if (m < startMonth) y - 1 else y
        return "%04d-Q%d".format(year, shifted / 3 + 1)
    }
}
