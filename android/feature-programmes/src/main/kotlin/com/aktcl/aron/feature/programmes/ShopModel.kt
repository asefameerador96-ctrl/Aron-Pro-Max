package com.aktcl.aron.feature.programmes

import com.aktcl.aron.rules.UiLocale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** What the shop-wise list needs of a stored outlet (the host maps its rows to this, so this module holds no database types). */
data class ShopOutlet(val outletId: Long, val nameLine: String, val code: String, val owner: String, val contact: String?, val subChannelId: Long?)

/** One card of the shop-wise list. [subChannel] is the label of the outlet's sub-channel (the Astha tier), null when unknown. */
data class ShopInfo(val outletId: Long, val nameLine: String, val code: String, val owner: String, val contact: String?, val subChannel: String?)

object ShopModel {
    /** The `sub_channel` code list of the stored `code_lists` section, id to label (Gold, Diamond, Platinum, Titanium; Silver is retired). */
    fun subChannels(codeListsJson: String?): Map<Long, BrandName> {
        val a = runCatching { Json.parseToJsonElement(codeListsJson ?: return emptyMap()) as? JsonArray }.getOrNull() ?: return emptyMap()
        val list = a.firstOrNull { ((it as? JsonObject)?.get("list_key") as? JsonPrimitive)?.contentOrNull == "sub_channel" } as? JsonObject ?: return emptyMap()
        return (list["items"] as? JsonArray).orEmpty().mapNotNull { e ->
            val i = e as? JsonObject ?: return@mapNotNull null
            val id = (i["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val en = (i["label_en"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            id to BrandName(en, (i["label_bn"] as? JsonPrimitive)?.contentOrNull)
        }.toMap()
    }

    /** The outlets that carry an Astha outlet row, by name; the tier label from [subs]. Empty when there is no programme data. */
    fun build(outlets: List<ShopOutlet>, data: AsthaData?, subs: Map<Long, BrandName>, locale: UiLocale): List<ShopInfo> {
        val ids = data?.outletIds?.toSet() ?: return emptyList()
        return outlets.filter { it.outletId in ids }.sortedBy { it.nameLine.lowercase() }
            .map { ShopInfo(it.outletId, it.nameLine, it.code, it.owner, it.contact, it.subChannelId?.let { id -> subs[id]?.text(locale) }) }
    }

    /** Search by name, code or phone (case-insensitive, any part, digits compared as typed in Latin or Bangla); blank shows everyone. */
    fun filter(shops: List<ShopInfo>, query: String): List<ShopInfo> {
        val q = normal(query)
        if (q.isEmpty()) return shops
        return shops.filter { q in normal(it.nameLine) || q in normal(it.code) || q in normal(it.contact.orEmpty()) }
    }

    private fun normal(s: String): String = s.trim().lowercase().map { if (it in '\u09E6'..'\u09EF') '0' + (it - '\u09E6') else it }.joinToString("")
}
