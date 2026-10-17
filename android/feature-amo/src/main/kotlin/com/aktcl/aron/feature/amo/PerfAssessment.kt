package com.aktcl.aron.feature.amo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** A brand of the product tree, in the bundle's order. */
data class AmoBrand(val id: Long, val name: String, val nameBn: String?)

/**
 * SR Performance Assessment of a Control Call (F-AMO-007/008/009; docs/07): per brand, is it distributed, and if so is it
 * out of stock; and whether POSM (sticker or banner) is present, tri-state. Pure state: nothing here touches the network.
 */
data class PerfState(val present: Set<Long> = emptySet(), val oos: Set<Long> = emptySet(), val posm: Boolean? = null) {
    /** Ticking a brand as distributed; unticking it also clears its OOS (OOS implies present). */
    fun toggleDistributed(brandId: Long): PerfState =
        if (brandId in present) copy(present = present - brandId, oos = oos - brandId) else copy(present = present + brandId)

    /** OOS can be ticked only for a brand ticked as distributed; otherwise nothing changes (F-AMO-008). */
    fun toggleOos(brandId: Long): PerfState = when {
        brandId in oos -> copy(oos = oos - brandId)
        brandId in present -> copy(oos = oos + brandId)
        else -> this
    }

    fun canTickOos(brandId: Long): Boolean = brandId in present

    /** Unanswered -> yes -> no -> unanswered by tapping the same state; [answer] sets it directly. */
    fun withPosm(answer: Boolean?): PerfState = copy(posm = answer)
}

object PerfAssessment {
    /** Brand nodes (level `brand`) of the bundle's `products` section, ordered by `sort`; a malformed section is no brands. */
    fun brands(productsJson: String?): List<AmoBrand> {
        val nodes = runCatching { (Json.parseToJsonElement(productsJson ?: return emptyList()) as? JsonObject)?.get("nodes") as? JsonArray }.getOrNull() ?: return emptyList()
        return nodes.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            if ((o["level"] as? JsonPrimitive)?.contentOrNull != "brand") return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            Triple(((o["sort"] as? JsonPrimitive)?.longOrNull ?: Long.MAX_VALUE), id, AmoBrand(id, name, (o["name_bn"] as? JsonPrimitive)?.contentOrNull))
        }.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }.distinctBy { it.id }
    }

    /** One record of the assessment: [familyUuid] is the visit, [rank] 1 for the check and 2 for its lines (docs/24 s4.3). */
    data class Planned(val clientUuid: String, val type: String, val familyUuid: String, val rank: Int, val payload: JsonObject)

    /**
     * The records one Save writes: a `distribution_check` (POSM) and one `distribution_check_line` per brand. Client UUIDs come
     * from [uuid], so a retried Save with the same UUIDs changes nothing on the phone or the server.
     */
    fun records(visitUuid: String, brands: List<AmoBrand>, state: PerfState, uuid: () -> String): List<Planned> {
        val checkUuid = uuid()
        val check = Planned(checkUuid, "distribution_check", visitUuid, 1, com.aktcl.aron.core.database.record.AmoPayloads.distributionCheck(visitUuid, state.posm, null))
        val lines = brands.map { b ->
            val present = b.id in state.present
            Planned(uuid(), "distribution_check_line", visitUuid, 2, com.aktcl.aron.core.database.record.AmoPayloads.distributionLine(checkUuid, b.id, present, present && b.id in state.oos))
        }
        return listOf(check) + lines
    }
}
