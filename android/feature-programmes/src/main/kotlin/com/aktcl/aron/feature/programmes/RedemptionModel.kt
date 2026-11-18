package com.aktcl.aron.feature.programmes

import com.aktcl.aron.core.database.record.ProgrammePayloads
import kotlinx.serialization.json.JsonObject

/** A redemption this phone already holds (from the stored `redemption` record and its outbox state). */
data class LocalRedemption(val outletId: Long, val programmeId: Long, val points: Long, val confirmedAt: String, val state: String)

/** One gift of the basket with its quantity. */
data class BasketLine(val gift: LoyaltyGift, val qty: Int)

/** A record the confirm step will write: the wire type, its client UUID, family and rank, and the contract payload. */
data class PlannedRecord(val type: String, val clientUuid: String, val familyUuid: String, val rank: Int, val payload: JsonObject)

/**
 * The redemption basket of one outlet and programme (manual 53, 54). The balance is the server's, as of the bundle, minus what this phone
 * has redeemed since (see [LocalRedeemed.points]). The server decides whether an overdraw is allowed (today it accepts and flags it), so the
 * phone never blocks on the balance: it shows the remaining points, says plainly when the basket takes them below zero, and the SR still confirms.
 */
data class Basket(val outletId: Long, val programmeId: Long, val balance: Long, val qty: Map<Long, Int> = emptyMap()) {
    fun qtyOf(gift: LoyaltyGift): Int = qty[gift.id] ?: 0

    /** One more of [gift]: at most 50 of a gift and 20 different gifts (the contract's bounds); a gift with no cost is not redeemable. */
    fun plus(gift: LoyaltyGift): Basket {
        if (gift.pointsCost == null || gift.pointsCost < 1) return this
        val n = qtyOf(gift)
        if (n >= MAX_QTY || (n == 0 && qty.size >= MAX_LINES)) return this
        return copy(qty = qty + (gift.id to n + 1))
    }

    fun minus(gift: LoyaltyGift): Basket {
        val n = qtyOf(gift)
        return when { n <= 1 -> copy(qty = qty - gift.id); else -> copy(qty = qty + (gift.id to n - 1)) }
    }

    fun lines(gifts: List<LoyaltyGift>): List<BasketLine> = gifts.filter { qtyOf(it) > 0 }.map { BasketLine(it, qtyOf(it)) }

    fun totalPoints(gifts: List<LoyaltyGift>): Long = lines(gifts).sumOf { it.qty * (it.gift.pointsCost ?: 0L) }

    /** What is left after the basket; negative means the basket goes beyond the balance (overdrawn). */
    fun remaining(gifts: List<LoyaltyGift>): Long = balance - totalPoints(gifts)

    fun overdraws(gifts: List<LoyaltyGift>): Boolean = remaining(gifts) < 0

    fun isEmpty(): Boolean = qty.isEmpty()

    /**
     * The records of the confirm step: the redemption and one line per gift. [redemptionUuid] is fixed when the basket opens, so a double tap
     * or a replay after a kill and relaunch is one redemption. Cash-back is 0 until the owner names the cash-back gift.
     */
    fun plan(gifts: List<LoyaltyGift>, redemptionUuid: String, newUuid: () -> String, confirmedAt: String, cashRateMtkPerPoint: Long = 0L): List<PlannedRecord> {
        val ls = lines(gifts)
        require(ls.isNotEmpty()) { "an empty basket is not a redemption" }
        val parent = PlannedRecord(
            "redemption", redemptionUuid, redemptionUuid, 0,
            ProgrammePayloads.redemption(outletId, programmeId, null, balance, totalPoints(gifts), 0L, cashRateMtkPerPoint, ls.size, confirmedAt),
        )
        return listOf(parent) + ls.map { PlannedRecord("redemption_line", newUuid(), redemptionUuid, 1, ProgrammePayloads.redemptionLine(redemptionUuid, it.gift.id, it.qty, it.gift.pointsCost ?: 0L)) }
    }

    companion object { const val MAX_QTY = 50; const val MAX_LINES = 20 }
}

object LocalRedeemed {
    private val STILL_LOCAL = setOf("pending", "in_flight", "quarantined")

    /**
     * Points this phone redeemed for the outlet and programme that the bundle's balance cannot hold: every redemption not refused by the server
     * that is either still on the phone (pending, in flight, held) or was confirmed after the bundle was built ([bundleBuiltAt], ISO UTC; null =
     * unknown, then every non-refused redemption counts). A redemption the server acked before the bundle was built is already inside the balance.
     */
    fun points(records: List<LocalRedemption>, outletId: Long, programmeId: Long, bundleBuiltAt: String?): Long =
        records.filter { it.outletId == outletId && it.programmeId == programmeId && it.state != "rejected" }
            .filter { bundleBuiltAt == null || it.state in STILL_LOCAL || it.confirmedAt > bundleBuiltAt }
            .sumOf { it.points }
}
