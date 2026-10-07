package com.aktcl.aron.feature.sale.domain

import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.rules.QtyUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The open visit as the sale needs it. The app shell builds it from feature-outlet's `OpenVisit` (a feature module never
 * depends on another feature module, docs/24 s2.2).
 */
data class SaleVisit(
    val visitUuid: String,
    val outletId: Long,
    val routeId: Long?,
    val businessDate: String,
    val priceType: String,
    /** True for a geo-validated or force-sale visit; a blocked visit cannot sell. */
    val canSell: Boolean = true,
)

/** Source of the day's priced SKUs with current stock; production reads Room (bundle prices, stock tracker). */
fun interface SaleCatalog { suspend fun skus(businessDate: String, priceType: String): List<SaleSku> }

data class SaleUiState(
    val visit: SaleVisit? = null,
    val draft: SaleDraft? = null,
    val catalog: Map<Long, SaleSku> = emptyMap(),
    val review: SaleReview? = null,
    val committed: CommittedSale? = null,
)

/**
 * State holder of one sale (quantity entry, slide, QC, credit, review, commit). Every change is persisted to the
 * [DraftStore] before the state is published, so a kill and relaunch resumes the same draft (F-SR-025). The commit is
 * serialised by a mutex and remembers its result: a double tap, or a retry after a kill that landed after the Room
 * transaction, never writes a second memo (the memo uuid is fixed in the draft and `memo.client_uuid` is unique).
 */
class SaleFlow(
    private val store: DraftStore,
    private val catalogSource: SaleCatalog,
    private val committer: SaleCommitter,
    private val qcCapMtkBySku: () -> Map<Long, Long> = { emptyMap() },
) {
    private val lock = Mutex()
    private val ui = MutableStateFlow(SaleUiState())
    val state: StateFlow<SaleUiState> = ui.asStateFlow()

    suspend fun start(visit: SaleVisit) = lock.withLock {
        check(visit.canSell) { "this visit cannot sell" }
        val catalog = catalogSource.skus(visit.businessDate, visit.priceType).associateBy { it.skuId }
        val draft = store.load(visit.visitUuid) ?: SaleDraft(
            visitUuid = visit.visitUuid, outletId = visit.outletId, routeId = visit.routeId, businessDate = visit.businessDate,
            priceType = visit.priceType, memoUuid = ClientIds.newUuid(),
        ).also(store::save)
        publish(visit, draft, catalog, null)
    }

    suspend fun setQuantity(skuId: Long, qty: Long, unit: QtyUnit) = edit { SaleDraftOps.setQuantity(it, skuId, qty, unit) }
    suspend fun setSlide(skuId: Long, emptyPackets: Long) = edit { SaleDraftOps.setSlide(it, skuId, emptyPackets) }
    suspend fun setQc(entry: QcEntry) = edit { SaleDraftOps.setQc(it, entry) }
    suspend fun setPaid(paidMtk: Long?) = edit { SaleDraftOps.setPaid(it, paidMtk) }
    suspend fun confirmZeroSale() = edit { SaleDraftOps.confirmZeroSale(it) }
    suspend fun withEdit(edit: EditContext) = edit { it.copy(edit = edit) }

    /** Commits the sale; [editFix] is required (and only allowed) for an edit. Returns the same result on a repeat call. */
    suspend fun commit(editFix: GeoFixEntity? = null): CommittedSale = lock.withLock {
        val s = ui.value
        s.committed?.let { return it }
        val draft = checkNotNull(s.draft) { "no sale in progress" }
        val result = committer.commit(draft, s.catalog, editFix, qcCapMtkBySku())
        store.clear(draft.visitUuid)
        ui.value = s.copy(committed = result)
        result
    }

    private suspend fun edit(change: (SaleDraft) -> SaleDraft) = lock.withLock {
        val s = ui.value
        check(s.committed == null) { "sale already committed" }
        val next = change(checkNotNull(s.draft) { "no sale in progress" })
        store.save(next)
        publish(s.visit!!, next, s.catalog, null)
    }

    private fun publish(visit: SaleVisit, draft: SaleDraft, catalog: Map<Long, SaleSku>, committed: CommittedSale?) {
        ui.value = SaleUiState(visit, draft, catalog, SaleReviewCalculator.review(draft, catalog, qcCapMtkBySku()), committed)
    }
}
