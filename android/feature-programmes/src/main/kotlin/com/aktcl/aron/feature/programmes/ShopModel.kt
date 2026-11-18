package com.aktcl.aron.feature.programmes

import com.aktcl.aron.rules.UiLocale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** What the shop-wise list needs of a stored outlet (the host maps its rows to this, so this module holds no database types). */
data class ShopOutlet(val outletId: Long, val nameLine: String, val code: String, val owner: String, val contact: String?, val subChannelId: Long?, val chip: String = "", val routeId: Long = 0L, val routeName: String = "", val clusterName: String = "")

/**
 * One card of the shop-wise list. [tier] is the Astha tier CODE the server sent for the outlet (`loyalty[].tier_code`, else `gift_assignments[].tier_code`), null while
 * the server has not sent it. [subChannel] is only its display text (first letter capital) and a dash is shown when it is null. It is never taken from a label.
 */
data class ShopInfo(
    val outletId: Long, val nameLine: String, val code: String, val owner: String, val contact: String?, val subChannel: String?,
    val chip: String = "", val routeId: Long = 0L, val routeName: String = "", val clusterName: String = "",
    val tier: String? = null,
) {
    /** The tier code the gift lists use (gold, diamond, platinum, titanium); null while the server has not sent it. */
    val tierCode: String? get() = tier?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
}

object ShopModel {
    /**
     * outlet id -> tier code from the stored `programmes` section: the outlet's own gift assignment first, else a loyalty card that carries a `tier_code`.
     * Empty until the server sends either (the bundle's sub-channel code list has no id, so no tier is derived from it).
     */
    fun tiers(programmesJson: String?): Map<Long, String> {
        val out = HashMap<Long, String>()
        LoyaltyModel.parse(programmesJson)?.cards?.forEach { c -> c.tierCode?.trim()?.takeIf { it.isNotEmpty() }?.let { out.putIfAbsent(c.outletId, it.lowercase()) } }
        GiftChoiceModel.parse(programmesJson)?.assignments?.forEach { a -> a.tierCode?.trim()?.takeIf { it.isNotEmpty() }?.let { out[a.outletId] = it.lowercase() } }
        return out
    }

    /** The outlets that carry an Astha outlet row, by name; the tier from [tiers]. Empty when there is no programme data. */
    fun build(outlets: List<ShopOutlet>, data: AsthaData?, tiers: Map<Long, String>): List<ShopInfo> {
        val ids = data?.outletIds?.toSet() ?: return emptyList()
        return buildFor(outlets, ids, tiers)
    }

    /** The cards of exactly the outlets in [ids] (the gift page adds the outlets that already have a gift assignment): the route being worked first, then by name. */
    fun buildFor(outlets: List<ShopOutlet>, ids: Set<Long>, tiers: Map<Long, String>, preferredRouteId: Long? = null): List<ShopInfo> {
        return outlets.filter { it.outletId in ids }.sortedWith(compareBy<ShopOutlet> { if (preferredRouteId != null && it.routeId == preferredRouteId) 0 else 1 }.thenBy { it.nameLine.lowercase() })
            .map { o -> val t = tiers[o.outletId]; ShopInfo(o.outletId, o.nameLine, o.code, o.owner, o.contact, t?.replaceFirstChar { c -> c.uppercase() }, o.chip, o.routeId, o.routeName, o.clusterName, t) }
    }

    /** Search by name, code or phone (case-insensitive, any part, digits compared as typed in Latin or Bangla); blank shows everyone. */
    fun filter(shops: List<ShopInfo>, query: String): List<ShopInfo> {
        val q = normal(query)
        if (q.isEmpty()) return shops
        return shops.filter { q in normal(it.nameLine) || q in normal(it.code) || q in normal(it.contact.orEmpty()) }
    }

    private fun normal(s: String): String = s.trim().lowercase().map { if (it in '\u09E6'..'\u09EF') '0' + (it - '\u09E6') else it }.joinToString("")
}
