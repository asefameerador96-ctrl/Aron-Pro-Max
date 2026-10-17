package com.aktcl.aron.feature.amo

import com.aktcl.aron.core.database.entity.PriceEntity
import com.aktcl.aron.core.database.entity.SkuEntity
import kotlinx.serialization.json.JsonObject

/** One SKU the AMO can check: [referenceMtk] is the outlet list price of one pack (milli-taka). */
data class PriceRow(val skuId: Long, val name: String, val packLabel: String, val priceType: String, val referenceMtk: Long)

/**
 * F-AMO-035 (ASSUMED, G-feat-01 / D-334): at a Control Call the AMO types the shelf price of a pack for the SKUs seen; it is
 * compliant when it equals the outlet list price within a tolerance. All money is integer milli-taka; the taka text is parsed
 * digit by digit, never through a float.
 */
object PriceCompliance {
    /** Tolerance in basis points of the list price; `cfg.amo.price_tolerance_bp` when the registry carries it (ASSUMED 100 = 1%). */
    const val DEFAULT_TOLERANCE_BP = 100

    /** List price of one pack from the price row (`amount_mtk` is per [PriceEntity.perBaseQty] base units), rounded half up to a whole milli-taka. */
    fun packReferenceMtk(price: PriceEntity, sku: SkuEntity): Long {
        val per = price.perBaseQty.coerceAtLeast(1).toLong()
        return (price.amountMtk * sku.basePerPack.coerceAtLeast(1) + per / 2) / per
    }

    /** SKUs that have a list price of [priceType] today, in the bundle's SKU order. */
    fun rows(skus: List<SkuEntity>, priceOf: (SkuEntity) -> PriceEntity?, priceType: String, bn: Boolean): List<PriceRow> =
        skus.mapNotNull { s ->
            val p = priceOf(s) ?: return@mapNotNull null
            PriceRow(s.skuId, if (bn) s.nameBn?.takeIf { it.isNotBlank() } ?: s.name else s.name, s.basePerPack.toString() + " " + s.baseUnit, priceType, packReferenceMtk(p, s))
        }

    /** "125", "125.5", "125.50" or "125.125" taka to milli-taka; null for anything else (empty, a sign, more than 3 decimals, too long). */
    fun parseTakaToMtk(text: String): Long? {
        val t = text.trim().replace(',', '.')
        val m = Regex("^(\\d{1,9})(?:\\.(\\d{1,3}))?$").matchEntire(t) ?: return null
        val whole = m.groupValues[1].toLong()
        val frac = m.groupValues[2].padEnd(3, '0').toLong()
        return whole * 1000 + frac
    }

    /** Milli-taka as taka text without trailing zeros ("125", "125.5"), for the reference column and the typed value. */
    fun formatTaka(mtk: Long): String {
        val whole = mtk / 1000; val frac = (mtk % 1000).toString().padStart(3, '0').trimEnd('0')
        return if (frac.isEmpty()) whole.toString() else "$whole.$frac"
    }

    /** |observed - reference| <= reference x tolerance, in integers. A free list price (0) is compliant only at 0. */
    fun compliant(referenceMtk: Long, observedMtk: Long, toleranceBp: Int = DEFAULT_TOLERANCE_BP): Boolean =
        Math.abs(observedMtk - referenceMtk) * 10_000L <= referenceMtk * toleranceBp

    data class Planned(val clientUuid: String, val familyUuid: String, val rank: Int, val payload: JsonObject)

    /** One `price_compliance_check` per typed SKU (rank 2, family = the visit). Entries that do not parse are left out. */
    fun records(visitUuid: String, rows: List<PriceRow>, typed: Map<Long, String>, toleranceBp: Int, uuid: () -> String): List<Planned> =
        rows.mapNotNull { r ->
            val observed = typed[r.skuId]?.let(::parseTakaToMtk) ?: return@mapNotNull null
            Planned(
                uuid(), visitUuid, 2,
                com.aktcl.aron.core.database.record.AmoPayloads.priceCompliance(visitUuid, r.skuId, r.priceType, r.referenceMtk, observed, compliant(r.referenceMtk, observed, toleranceBp), null),
            )
        }

    /** Save needs at least one parsed price, and no typed text may be unparsable (a typo is shown, not dropped). */
    fun canSave(rows: List<PriceRow>, typed: Map<Long, String>): Boolean {
        val texts = rows.mapNotNull { r -> typed[r.skuId]?.takeIf { it.isNotBlank() } }
        return texts.isNotEmpty() && texts.all { parseTakaToMtk(it) != null }
    }
}
