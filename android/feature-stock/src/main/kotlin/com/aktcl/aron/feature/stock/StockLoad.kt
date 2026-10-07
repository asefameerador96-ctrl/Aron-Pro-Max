package com.aktcl.aron.feature.stock

import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.SkuEntity
import com.aktcl.aron.core.database.entity.StockMovementEntity

/** One SKU row of the Stock screen (F-SR-014): [loadedBase] is today's loaded total, read-only. */
data class StockRow(val sku: SkuEntity, val loadedBase: Long, val entered: Long)

/** The purple totals panel: per category, total issued today in base units (sticks, pieces, dozens). */
data class CategoryTotal(val categoryCode: String, val issuedBase: Long)

/** Why a Save was refused. */
enum class SaveRefusal { NOTHING_ENTERED, SAME_VALUES_WITHIN_GUARD }

sealed interface SaveOutcome {
    data class Saved(val movements: List<StockMovementEntity>) : SaveOutcome
    data class Refused(val reason: SaveRefusal) : SaveOutcome
}

/**
 * Stock load logic (F-SR-014), pure and offline: the screen takes a stepped or typed Issue per SKU; Save posts only the
 * entered increment (never an overwrite), and a re-save of identical values inside the guard window is refused so a
 * double tap or a retry cannot load the same stock twice. Quantities are in the SKU's own unit (docs/24 s7).
 */
class StockLoad(
    private val skus: List<SkuEntity>,
    loadedBaseBySku: Map<Long, Long>,
    private val guardWindowMs: Long = DEFAULT_GUARD_MS,
    private val newUuid: () -> String = ClientIds::newUuid,
    /** Survives a kill and relaunch (the last saved signature and time); production: SharedPreferences. */
    private val guardStore: GuardStore = GuardStore.InMemory(),
    /** `cfg.stock.max_issue_qty`: a soft ceiling, warn and allow. */
    val softCeiling: Long = DEFAULT_SOFT_CEILING,
) {
    /** Last saved signature and instant. */
    interface GuardStore {
        fun load(): Pair<String, Long>?
        fun save(signature: String, atMs: Long)
        class InMemory : GuardStore {
            private var v: Pair<String, Long>? = null
            override fun load() = v
            override fun save(signature: String, atMs: Long) { v = signature to atMs }
        }
    }

    private val loaded = loadedBaseBySku.toMutableMap()
    private val entered = linkedMapOf<Long, Long>()
    private var inFlightSignature: String? = null

    val rows: List<StockRow>
        get() = skus.filter { it.status == "active" }.sortedBy { it.sort }
            .map { StockRow(it, loaded[it.skuId] ?: 0L, entered[it.skuId] ?: 0L) }

    /** Totals per category over what is already loaded today (the entered, unsaved quantities are not counted). */
    val totals: List<CategoryTotal>
        get() = rows.groupBy { it.sku.categoryCode }.map { (c, r) -> CategoryTotal(c, r.sumOf { it.loadedBase }) }

    /** Sets the typed Issue for a SKU in the entry unit; negative or absurd values are rejected, zero clears it. */
    fun setEntered(skuId: Long, qty: Long) {
        if (skus.none { it.skuId == skuId && it.status == "active" }) return // not on this screen: ignore, never crash
        val q = qty.coerceIn(0, MAX_ENTRY)
        if (q == 0L) entered.remove(skuId) else entered[skuId] = q
    }

    /** True when an entered quantity is above the soft ceiling: the screen warns, Save still works. */
    fun exceedsSoftCeiling(skuId: Long): Boolean = (entered[skuId] ?: 0L) > softCeiling

    fun step(skuId: Long, delta: Int) = setEntered(skuId, ((entered[skuId] ?: 0L) + delta).coerceIn(0, MAX_ENTRY))

    /** Entry unit of a SKU: `pack` multiplies by the pack factor, anything else is the base unit. */
    private fun factorOf(s: SkuEntity): Int = if (s.entryUnitDefault == "pack") s.basePerPack.coerceAtLeast(1) else 1

    fun save(nowMs: Long, meta: CaptureMeta, slipPrinted: Boolean = false): SaveOutcome {
        if (entered.isEmpty()) return SaveOutcome.Refused(SaveRefusal.NOTHING_ENTERED)
        val sig = entered.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }
        if (sig == inFlightSignature) return SaveOutcome.Refused(SaveRefusal.SAME_VALUES_WITHIN_GUARD) // double tap before commit
        guardStore.load()?.let { (s, at) -> if (s == sig && nowMs - at in 0 until guardWindowMs) return SaveOutcome.Refused(SaveRefusal.SAME_VALUES_WITHIN_GUARD) }
        inFlightSignature = sig
        val movements = entered.entries.map { (id, qty) ->
            val sku = skus.first { it.skuId == id }
            val f = factorOf(sku)
            StockMovementEntity(
                clientUuid = newUuid(), meta = meta, kind = "issue", skuId = id, qtyEntered = qty,
                unitEntered = if (f == 1) sku.baseUnit else "pack", packFactor = f, qtyBase = Math.multiplyExact(qty, f.toLong()),
                slipPrinted = slipPrinted,
            )
        }
        return SaveOutcome.Saved(movements)
    }

    /** Call when the committer failed: the same values may be saved again. */
    fun commitFailed() { inFlightSignature = null }

    /** Call after the committer succeeded: folds the increment into today's loaded totals and starts the guard window. */
    fun committed(saved: SaveOutcome.Saved, nowMs: Long) {
        saved.movements.forEach { loaded[it.skuId] = (loaded[it.skuId] ?: 0L) + it.qtyBase }
        guardStore.save(entered.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }, nowMs)
        inFlightSignature = null
        entered.clear()
    }

    companion object {
        /** `cfg.stock.resave_guard_window_min` = 5 (docs/24). */
        const val DEFAULT_GUARD_MS = 300_000L
        const val DEFAULT_SOFT_CEILING = 20_000L
        const val MAX_ENTRY = 100_000L
    }
}
