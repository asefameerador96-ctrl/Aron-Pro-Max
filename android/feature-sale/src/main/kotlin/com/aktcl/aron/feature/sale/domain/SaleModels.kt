package com.aktcl.aron.feature.sale.domain

import com.aktcl.aron.rules.PriceType
import com.aktcl.aron.rules.QtyUnit
import kotlinx.serialization.Serializable

/** One sellable SKU as the sale screen needs it: unit, pack factor and the price snapshot source (docs/24 s7.2, s7.3). */
data class SaleSku(
    val skuId: Long,
    val code: String,
    val categoryCode: String,
    val name: String,
    val nameBn: String?,
    /** `stick`, `piece` or `dozen` (docs/24 s7.2). */
    val baseUnit: String,
    val packFactor: Int,
    val unitPriceMtk: Long,
    val pricePerQty: Int = 1,
    /** `valid_from` of the price row used (stored on the line, F-SYS-045). */
    val priceValidFrom: String,
    /** Current stock in base units from the local tracker; null when unknown (no warning then). */
    val stockBase: Long? = null,
    /** Active offer for the Slide screen: empty packets that earn one reward pack; null when no active offer (docs/27: offers deferred). */
    val drp: DrpRule? = null,
) {
    init {
        require(packFactor >= 1) { "packFactor must be >= 1" }
        require(pricePerQty >= 1) { "pricePerQty must be >= 1" }
        require(unitPriceMtk >= 0) { "unitPriceMtk must be >= 0" }
    }
}

/** `emptyPerReward` empty packets give one reward pack of the SKU, valued at the SKU price like a sale line (docs/24 s7.3). */
data class DrpRule(val emptyPerReward: Int) {
    init { require(emptyPerReward >= 1) { "emptyPerReward must be >= 1" } }
}

/** What the SR typed for one SKU: the entered quantity and the unit it was typed in. */
@Serializable
data class DraftLine(val skuId: Long, val qtyEntered: Long, val unit: String = QtyUnit.STICK.wire)

/** Empty packets collected on the Slide screen for a SKU. */
@Serializable
data class SlideEntry(val skuId: Long, val emptyPackets: Long)

/** Product QC capture for a SKU: faulty sticks by fault type (F-SR-027). */
@Serializable
data class QcEntry(val skuId: Long, val faultTypeCode: String, val faultGroup: String, val defectQty: Long)

/** Edit context: the memo this draft supersedes and why (F-SR-033). */
@Serializable
data class EditContext(val supersedesMemoUuid: String, val reasonCode: String)

/**
 * The in-progress sale of one outlet visit. Pure data, persisted after every change so a kill and relaunch loses nothing
 * (F-SR-025). [paidMtk] null means paid in full; a value below the net is a credit sale with that partial payment (F-SR-026).
 */
@Serializable
data class SaleDraft(
    val visitUuid: String,
    val outletId: Long,
    val routeId: Long?,
    val businessDate: String,
    val priceType: String = PriceType.OUTLET.wire,
    val lines: List<DraftLine> = emptyList(),
    val slide: List<SlideEntry> = emptyList(),
    val qc: List<QcEntry> = emptyList(),
    val paidMtk: Long? = null,
    val zeroSale: Boolean = false,
    val edit: EditContext? = null,
    /** True once Product QC was completed at this outlet: sale edits are locked (F-SR-027). */
    val qcCompleted: Boolean = false,
    val memoUuid: String,
)
