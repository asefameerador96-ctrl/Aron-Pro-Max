package com.aktcl.aron.feature.amo

import com.aktcl.aron.rules.EntrySpec
import com.aktcl.aron.rules.EntryUnits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** [entry] is how the SKU is counted in the field (packets, boxes, dozens; docs/34 s4.2); null shows the server's base quantity as it is. */
data class SkuInfo(val name: String, val nameBn: String?, val categoryCode: String, val entry: EntrySpec? = null)
data class StockLine(val skuId: Long, val unit: String, val lifted: Long, val sold: Long, val returned: Long, val current: Long)
data class SrStock(val userId: Long, val name: String, val lines: List<StockLine>)
/** The lifted quantity of one category in one unit (units are never added across each other: sticks, pieces and dozens stay apart). */
data class CategoryTotal(val categoryCode: String, val unit: String, val lifted: Long, val unitKey: String = "")

/** Pure model of SR Stock (F-AMO-021): `GET /v1/team/stock` for the day, the SRs' phones masked by the server. */
object TeamStock {
    fun parse(json: String?): List<SrStock> {
        val o = runCatching { Json.parseToJsonElement(json ?: return emptyList()) as? JsonObject }.getOrNull() ?: return emptyList()
        return (o["items"] as? JsonArray).orEmpty().mapNotNull { e ->
            val m = e as? JsonObject ?: return@mapNotNull null
            fun l(x: JsonObject, k: String) = (x[k] as? JsonPrimitive)?.longOrNull
            SrStock(
                l(m, "user_id") ?: return@mapNotNull null, (m["full_name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
                (m["by_sku"] as? JsonArray).orEmpty().mapNotNull { s ->
                    val x = s as? JsonObject ?: return@mapNotNull null
                    StockLine(
                        l(x, "sku_id") ?: return@mapNotNull null, (x["base_unit"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                        l(x, "issued_qty_base") ?: 0, l(x, "sold_qty_base") ?: 0, l(x, "returned_qty_base") ?: 0, l(x, "current_qty_base") ?: 0,
                    )
                },
            )
        }.sortedBy { it.name }
    }

    /** Phones masked by the server, by user id, from the bundle's team list. */
    fun phones(supervisorJson: String?): Map<Long, String> {
        val o = runCatching { Json.parseToJsonElement(supervisorJson ?: return emptyMap()) as? JsonObject }.getOrNull() ?: return emptyMap()
        return (o["team"] as? JsonArray).orEmpty().mapNotNull { e ->
            val m = e as? JsonObject ?: return@mapNotNull null
            val id = (m["user_id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            (m["phone_masked"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { id to it }
        }.toMap()
    }

    /** Lifted stock per category and unit, categories in code order; a SKU the phone does not know falls under `""`. */
    fun totals(lines: List<StockLine>, skus: Map<Long, SkuInfo>): List<CategoryTotal> =
        lines.groupBy { (skus[it.skuId]?.categoryCode.orEmpty()) to unitKeyOf(it, skus).ifEmpty { it.unit } }
            .map { (k, v) -> CategoryTotal(k.first, v.first().unit, v.sumOf { entered(it.lifted, it, skus) }, unitKeyOf(v.first(), skus)) }
            .sortedWith(compareBy({ it.categoryCode }, { it.unit }))

    /** The unit word key of a line: the SKU's entry unit when known (`packet`, `box`, `dozen`), else blank (the server's base unit is shown). */
    fun unitKeyOf(l: StockLine, skus: Map<Long, SkuInfo>): String =
        skus[l.skuId]?.let { s -> s.entry?.let { EntryUnits.labelKey(s.categoryCode, it.unit) } }.orEmpty()

    /** A server base quantity in the SKU's entry unit (whole units; the base figure when the entry unit is unknown). */
    fun entered(base: Long, l: StockLine, skus: Map<Long, SkuInfo>): Long = skus[l.skuId]?.entry?.fromBase(base.coerceAtLeast(0L)) ?: base
}
