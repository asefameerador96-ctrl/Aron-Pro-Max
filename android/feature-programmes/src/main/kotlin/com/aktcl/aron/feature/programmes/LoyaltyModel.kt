package com.aktcl.aron.feature.programmes

import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.UiLocale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** A loyalty programme of the stored bundle (`programmes.programmes[]`), the parts the points screens show. */
data class LoyaltyProgramme(val id: Long, val kind: String, val nameEn: String, val nameBn: String?, val periodLabel: String?, val activeTo: String?)

/**
 * One outlet's balance in one programme (`programmes.loyalty[]`, contract LoyaltyBalance): integer points as the server held them when the
 * bundle was built, `overdrawn` when the balance is below zero (the server accepts a redemption at balance 0, so a negative balance is real
 * and is shown plainly, never hidden or clamped). [tierCode] is null until the server sends it (request N-7).
 */
data class LoyaltyCard(
    val outletId: Long, val programmeId: Long, val leagueLabel: String?, val balancePoints: Long, val overdrawn: Boolean,
    val expiringPoints: Long, val expiryDate: String?, val asOfDate: String?, val tierCode: String?,
)

data class LoyaltyData(val programmes: List<LoyaltyProgramme>, val cards: List<LoyaltyCard>, val gifts: List<LoyaltyGift>) {
    fun programme(id: Long): LoyaltyProgramme? = programmes.firstOrNull { it.id == id }

    /** The outlet's cards, one per programme, programmes in id order. */
    fun cardsOf(outletId: Long): List<LoyaltyCard> = cards.filter { it.outletId == outletId }.sortedBy { it.programmeId }

    /** Outlets that have at least one loyalty entry. */
    val outletIds: List<Long> get() = cards.map { it.outletId }.distinct()

    /** The title of a card: the server's league label ("Diamond League (April)"), else the programme name with its period. */
    fun title(card: LoyaltyCard, locale: UiLocale): String {
        card.leagueLabel?.takeIf { it.isNotBlank() }?.let { return it }
        val p = programme(card.programmeId) ?: return "#${card.programmeId}"
        val name = if (locale == UiLocale.BN) p.nameBn?.takeIf { it.isNotBlank() } ?: p.nameEn else p.nameEn
        return if (p.periodLabel.isNullOrBlank()) name else "$name (${p.periodLabel})"
    }
}

/** A gift of the stored bundle (`programmes.gifts[]`); `pointsCost` null for Astha gifts (chosen, not redeemed). */
data class LoyaltyGift(
    val id: Long, val programmeId: Long, val nameEn: String, val nameBn: String?, val pointsCost: Long?, val tierCodes: List<String>, val imageAssetId: String?, val active: Boolean,
) {
    fun name(locale: UiLocale): String = if (locale == UiLocale.BN) nameBn?.takeIf { it.isNotBlank() } ?: nameEn else nameEn
}

object LoyaltyModel {
    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Parses the stored `programmes` section; null when absent or not an object. A malformed row is skipped, never a crash. */
    fun parse(programmesJson: String?): LoyaltyData? {
        val o = programmesJson?.let { runCatching { lenient.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return null
        val programmes = (o["programmes"] as? JsonArray).orEmpty().mapNotNull { e ->
            val p = e as? JsonObject ?: return@mapNotNull null
            LoyaltyProgramme(p.long("programme_id") ?: return@mapNotNull null, p.str("kind") ?: return@mapNotNull null, p.str("name_en") ?: return@mapNotNull null,
                p.str("name_bn"), p.str("period_label"), p.str("active_to"))
        }
        val cards = (o["loyalty"] as? JsonArray).orEmpty().mapNotNull { e ->
            val c = e as? JsonObject ?: return@mapNotNull null
            val balance = c.long("balance_points") ?: return@mapNotNull null
            LoyaltyCard(
                c.long("outlet_id") ?: return@mapNotNull null, c.long("programme_id") ?: return@mapNotNull null, c.str("league_label"), balance,
                (c["overdrawn"] as? JsonPrimitive)?.booleanOrNull ?: (balance < 0), c.long("expiring_points") ?: 0L, c.str("expiry_date"), c.str("as_of_date"), c.str("tier_code"),
            )
        }
        val gifts = (o["gifts"] as? JsonArray).orEmpty().mapNotNull { e ->
            val g = e as? JsonObject ?: return@mapNotNull null
            LoyaltyGift(
                g.long("gift_id") ?: return@mapNotNull null, g.long("programme_id") ?: return@mapNotNull null, g.str("name_en") ?: return@mapNotNull null, g.str("name_bn"),
                g.long("points_cost"), (g["tier_codes"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }, g.str("image_asset_id"),
                g.str("status")?.let { it == "active" } ?: true,
            )
        }
        return LoyaltyData(programmes, cards, gifts)
    }

    /** "110 Pts" in the language's digits; a negative balance keeps its minus sign ("-20 Pts"). */
    fun pointsText(points: Long, locale: UiLocale, unit: String): String = Formats.integer(points, locale) + " " + unit

    /** The expiry date as yyyy-MM-dd in the language's digits; null or blank is a dash. */
    fun dateText(date: String?, locale: UiLocale): String = date?.takeIf { it.isNotBlank() }?.let { Formats.digits(it.take(10), locale) } ?: "—"

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
}
