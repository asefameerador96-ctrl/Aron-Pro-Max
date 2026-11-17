package com.aktcl.aron.feature.stock

import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.SkuEntity
import com.aktcl.aron.core.database.entity.StockMovementEntity
import com.aktcl.aron.rules.EntrySpec
import com.aktcl.aron.rules.EntryUnits

/**
 * One SKU row of the Stock screen (F-SR-014): [loadedBase] is today's loaded total, read-only. [stockBase] is the stock
 * in the bag now (`StockTracker.StockBalance.current`, in the SKU's base unit) when the host supplies it; without it the
 * row's key figure is the total load, never called stock.
 */
data class StockRow(val sku: SkuEntity, val loadedBase: Long, val entered: Long, val stockBase: Long? = null) {
    /** How this SKU is counted (docs/34 s4.2): packets, boxes or dozens; [entered] is in this unit, the `*Base` figures in base units. */
    val entry: EntrySpec get() = StockUnits.of(sku)

    /** Wire label key (`packet`, `box`, `dozen`, ...) of the entry unit. */
    val labelKey: String get() = EntryUnits.labelKey(sku.categoryCode, entry.unit)

    /** Today's load in whole entry units (a base remainder only exists for stock loaded before the rule). */
    val loadedEntry: Long get() = entry.fromBase(loadedBase)

    /** The stock figure of the row in entry units, plus the quantity being entered. */
    val figureEntry: Long get() = entry.fromBase(stockBase ?: loadedBase) + entered

    /** The load's base units left over after whole entry units. */
    val loadedRemainderBase: Long get() = entry.remainderBase(loadedBase)

    /** Base units left over after whole entry units (QC returns are counted in sticks); shown as `+n`, never dropped. */
    val figureRemainderBase: Long get() = entry.remainderBase(stockBase ?: loadedBase)
}

/** Entry unit of a stock row, from the SKU master only (no 20, 10 or 25 in code). */
object StockUnits {
    fun of(sku: SkuEntity): EntrySpec = EntryUnits.resolve(sku.baseUnit, sku.basePerPack.coerceAtLeast(1), sku.entryUnitDefault)
}

/** The purple totals panel: per category, total issued today in base units ([issuedBase]) and in the entry unit ([issuedEntry]). */
data class CategoryTotal(val categoryCode: String, val issuedBase: Long, val issuedEntry: Long = issuedBase)

/** Why a Save was refused. */
enum class SaveRefusal { NOTHING_ENTERED, SAME_VALUES_WITHIN_GUARD }

sealed interface SaveOutcome {
    data class Saved(val movements: List<StockMovementEntity>) : SaveOutcome
    data class Refused(val reason: SaveRefusal) : SaveOutcome
}

/**
 * Stock load logic (F-SR-014), pure and offline: the screen takes a stepped or typed Issue per SKU; Save posts only the
 * entered increment (never an overwrite). A double tap cannot load the same stock twice: the second tap meets the Save still in
 * flight, and a retry of a failed commit reuses the SAME client UUIDs, which the repository stores once. The old 5-minute
 * same-values refusal is gone (D-AGATE-01): with the stock gate a second real issue of the same quantity is a legitimate top-up.
 * Quantities are in the SKU's own unit (docs/24 s7).
 */
class StockLoad(
    private val skus: List<SkuEntity>,
    loadedBaseBySku: Map<Long, Long>,
    private val newUuid: () -> String = ClientIds::newUuid,
    /** `cfg.stock.max_issue_qty`: a soft ceiling, warn and allow. */
    val softCeiling: Long = DEFAULT_SOFT_CEILING,
    /**
     * The stock in the bag now per SKU in base units (`StockTracker`: issued + adjusted - QC returned - sold), display only: it
     * never enters a Save. Null = the host has no on-hand figure and the screen says "total load" instead of "stock".
     */
    currentBaseBySku: Map<Long, Long>? = null,
) {
    private val loaded = loadedBaseBySku.toMutableMap()
    private val current = currentBaseBySku?.toMutableMap()
    private val entered = linkedMapOf<Long, Long>()
    private var inFlightSignature: String? = null
    /** The client UUID of each (SKU, quantity) of the Save in progress: a retry of the same Save posts the same UUIDs, a changed quantity a new one. */
    private val attemptUuids = HashMap<String, String>()

    val rows: List<StockRow>
        get() = skus.filter { it.status == "active" }.sortedBy { it.sort }
            .map { StockRow(it, loaded[it.skuId] ?: 0L, entered[it.skuId] ?: 0L, stockBase = current?.let { c -> c[it.skuId] ?: 0L }) }

    /** Totals per category over what is already loaded today (the entered, unsaved quantities are not counted). */
    val totals: List<CategoryTotal>
        get() = rows.groupBy { it.sku.categoryCode }.map { (c, r) -> CategoryTotal(c, r.sumOf { it.loadedBase }, r.sumOf { it.loadedEntry }) }

    /** Sets the typed Issue for a SKU in the entry unit; negative or absurd values are rejected, zero clears it. */
    fun setEntered(skuId: Long, qty: Long) {
        if (skus.none { it.skuId == skuId && it.status == "active" }) return // not on this screen: ignore, never crash
        val q = qty.coerceIn(0, MAX_ENTRY)
        if (q == 0L) entered.remove(skuId) else entered[skuId] = q
    }

    /** True when an entered quantity is above the soft ceiling: the screen warns, Save still works. */
    fun exceedsSoftCeiling(skuId: Long): Boolean = (entered[skuId] ?: 0L) > softCeiling

    fun step(skuId: Long, delta: Int) = setEntered(skuId, ((entered[skuId] ?: 0L) + delta).coerceIn(0, MAX_ENTRY))

    fun save(nowMs: Long, meta: CaptureMeta, slipPrinted: Boolean = false): SaveOutcome {
        if (entered.isEmpty()) return SaveOutcome.Refused(SaveRefusal.NOTHING_ENTERED)
        val sig = entered.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }
        if (sig == inFlightSignature) return SaveOutcome.Refused(SaveRefusal.SAME_VALUES_WITHIN_GUARD) // double tap before commit
        inFlightSignature = sig
        val movements = entered.entries.map { (id, qty) ->
            val sku = skus.first { it.skuId == id }
            val spec = StockUnits.of(sku)
            StockMovementEntity(
                clientUuid = attemptUuids.getOrPut("$id=$qty", newUuid), meta = meta, kind = "issue", skuId = id, qtyEntered = qty,
                unitEntered = spec.unit.wire, packFactor = sku.basePerPack.coerceAtLeast(1), qtyBase = spec.toBase(qty),
                slipPrinted = slipPrinted,
            )
        }
        return SaveOutcome.Saved(movements)
    }

    /** Call when the committer failed: the same values may be saved again. */
    fun commitFailed() {
        // the same values may go again, with the same client UUIDs (the repository stores a UUID once)
        inFlightSignature = null
    }

    /** Call after the committer succeeded: folds the increment into today's loaded totals. */
    fun committed(saved: SaveOutcome.Saved, nowMs: Long) {
        saved.movements.forEach {
            loaded[it.skuId] = (loaded[it.skuId] ?: 0L) + it.qtyBase
            current?.let { c -> c[it.skuId] = (c[it.skuId] ?: 0L) + it.qtyBase }
        }
        inFlightSignature = null
        attemptUuids.clear() // a later Save, even of the same quantities, is a new issue
        entered.clear()
    }

    companion object {
        const val DEFAULT_SOFT_CEILING = 20_000L
        const val MAX_ENTRY = 100_000L
    }
}
