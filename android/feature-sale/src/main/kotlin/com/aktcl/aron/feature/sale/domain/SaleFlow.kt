package com.aktcl.aron.feature.sale.domain

import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.repo.OverStockException
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
    /** The one per-SKU QC cap (taka) the review, the commit and the QC screen all read; empty while the basis is unknown (MQ-03). */
    val qcCapMtkBySku: Map<Long, Long> = emptyMap(),
    /** Why the last save was refused INSIDE the transaction (stock moved, the memo to edit was replaced); the screen words it. Cleared by the next change. */
    val refusal: List<ReviewProblem> = emptyList(),
    /** D-AGATE-25: a replayed Save (the memo was already stored) answered with the STORED memo while the draft on screen differed from it: the later changes were not saved; the screen says so. */
    val laterChangesNotSaved: Boolean = false,
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
    /**
     * What a memo that an edit will supersede took out of the bag, per SKU in base units (its lines plus reward packs). The edit gets it
     * back: the old memo is still live in the stock figure until the new one is saved (D-OWNER-PREREQ-01 rule 2). Production: Room.
     */
    private val ownTaken: suspend (memoUuid: String) -> Map<Long, Long> = { emptyMap() },
    /**
     * D-AGATE-19: true when the call this draft belongs to already has its close (the phone ended it, or the rep did). Asked INSIDE the flow's lock, after the "already committed" answer, so a
     * NEW sale is refused ([ReviewProblem.CallClosed], nothing written) and a retry of a Save that already landed still returns its memo. An edit is never refused for a closed call.
     * Production: Room (`visit_close` of the draft's visit and date).
     */
    private val isCallClosed: suspend (SaleDraft) -> Boolean = { false },
) {
    private val lock = Mutex()
    private val ui = MutableStateFlow(SaleUiState())
    val state: StateFlow<SaleUiState> = ui.asStateFlow()

    /** The catalog as Room gave it (stock with every live memo subtracted) and the memo already given back into [ui]'s catalog. */
    private var rawCatalog: Map<Long, SaleSku> = emptyMap()
    private var givenBackFor: String? = null

    /** [editing] is the memo an edit will supersede (null for a new sale); a resumed edit draft names it itself. */
    suspend fun start(visit: SaleVisit, editing: String? = null) = lock.withLock {
        check(visit.canSell) { "this visit cannot sell" }
        // D-AGATE-19: an EDIT starts clean when the stored draft is a leftover SALE draft of a call the phone already closed (a kill between the close and the drop leaves the file): never loaded into the edit
        val stored = store.load(visit.visitUuid)?.takeUnless { editing != null && it.edit == null && isCallClosed(it) }
        if (stored == null) store.clear(visit.visitUuid)
        val loaded = stored ?: SaleDraft(
            visitUuid = visit.visitUuid, outletId = visit.outletId, routeId = visit.routeId, businessDate = visit.businessDate,
            priceType = visit.priceType, memoUuid = ClientIds.newUuid(),
        ).also(store::save)
        // the stock and prices are read for the DRAFT's own date (the memo's date, which the saving transaction reads too): a draft opened before
        // midnight and resumed after it still shows the figures the save will check
        rawCatalog = catalogSource.skus(committer.stockDate(loaded.businessDate, loaded.routeId), loaded.priceType).associateBy { it.skuId }
        val draft = SaleDraftOps.sanitizeQc(loaded).also { if (it != loaded) store.save(it) }
        val catalog = withGiveBack(editing ?: draft.edit?.supersedesMemoUuid)
        val landed = committer.existing(draft.memoUuid)
        if (landed != null) store.clear(visit.visitUuid) // the commit landed and the kill came before the draft was cleared
        publish(visit, draft, catalog, landed)
    }

    /**
     * Hard stop at line entry (rule 2): the quantity cannot go above what is held (less the SKU's reward packs). Returns the quantity
     * actually set, which is lower than [qty] when the stock stopped it; a SKU with no stock entry sets nothing.
     */
    suspend fun setQuantity(skuId: Long, qty: Long, unit: QtyUnit): Long = lock.withLock {
        val s = ui.value
        val sku = s.catalog[skuId]
        var set = qty
        if (sku != null && qty > 0) {
            val unitBase = runCatching { com.aktcl.aron.rules.Quantity.toBase(1, unit, sku.packFactor.toLong()) }.getOrNull()
            if (unitBase != null) {
                val free = (sku.stockBase - (SaleReviewCalculator.rewardBaseBySku(checkNotNull(s.draft) { "no sale in progress" }, s.catalog)[skuId] ?: 0L)).coerceAtLeast(0L)
                set = minOf(qty, free / unitBase)
            }
        }
        editLocked { SaleDraftOps.setQuantity(it, skuId, set, unit) }
        set
    }
    suspend fun setSlide(skuId: Long, emptyPackets: Long) = edit { SaleDraftOps.setSlide(it, skuId, emptyPackets) }
    /** A QC write on a draft whose QC is already submitted is ignored (the page is read-only then); the draft ops still refuse it, so nothing can throw into a coroutine of the screen. */
    suspend fun setQc(entry: QcEntry) = edit { if (it.qcCompleted) it else SaleDraftOps.setQc(it, entry) }
    suspend fun setPaid(paidMtk: Long?) = edit { SaleDraftOps.setPaid(it, paidMtk) }
    suspend fun confirmZeroSale(reasonCode: String? = null) = edit { SaleDraftOps.confirmZeroSale(it, reasonCode) }
    suspend fun startQcOnly() = edit { SaleDraftOps.startQcOnly(it) }
    suspend fun cancelQcOnly() = edit { if (it.qcCompleted) it else SaleDraftOps.cancelQcOnly(it) }
    suspend fun withEdit(edit: EditContext) = lock.withLock {
        if (givenBackFor != edit.supersedesMemoUuid) {
            val catalog = withGiveBack(edit.supersedesMemoUuid)
            val d = checkNotNull(ui.value.draft) { "no sale in progress" }
            ui.value = ui.value.copy(catalog = catalog, review = SaleReviewCalculator.review(d, catalog, qcCapMtkBySku()))
        }
        editLocked { check(!it.qcCompleted) { "a sale cannot be edited after QC" }; it.copy(edit = edit) }
    }

    /** [rawCatalog] with the stock of [memoUuid]'s own lines and reward packs added back (null: nothing to give back). */
    private suspend fun withGiveBack(memoUuid: String?): Map<Long, SaleSku> {
        givenBackFor = memoUuid
        val back = if (memoUuid == null) emptyMap() else ownTaken(memoUuid)
        return if (back.isEmpty()) rawCatalog else rawCatalog.mapValues { (id, sku) -> back[id]?.let { sku.copy(stockBase = sku.stockBase + it) } ?: sku }
    }
    /** Completing QC locks the sale at this outlet (F-SR-027). */
    suspend fun completeQc() = lock.withLock {
        val s = ui.value
        val d = checkNotNull(s.draft) { "no sale in progress" }
        // a QC the review would refuse (above the cap, or taking the sale below zero) is never locked in: the rep could not undo it
        val r = SaleReviewCalculator.review(d, s.catalog, qcCapMtkBySku())
        if (r.problems.any { it is ReviewProblem.QcAboveCap || it is ReviewProblem.NetNegative || it is ReviewProblem.TooManyQcRows }) return@withLock
        editLocked { SaleDraftOps.completeQc(it) }
    }
    suspend fun restoreQc(entries: List<QcEntry>) = edit { if (it.qcCompleted) it else SaleDraftOps.replaceQc(it, entries) }

    /** Commits the sale; [editFix] is required (and only allowed) for an edit. Returns the same result on a repeat call. */
    suspend fun commit(editFix: GeoFixEntity? = null): CommittedSale = lock.withLock {
        val s = ui.value
        s.committed?.let { return it }
        val draft = checkNotNull(s.draft) { "no sale in progress" }
        // D-AGATE-22 (item 3): a Save that landed in Room but whose coroutine was cancelled before the screen state was set is still a saved memo: look it up by the draft's own memo UUID
        // (as `start` does) BEFORE the closed-call refusal, so a retry after the call was closed in between answers with the stored memo, never "nothing was saved"
        committer.existing(draft.memoUuid)?.let { landed ->
            // D-AGATE-26: whether the screen's draft differs from the stored memo is worked out BEFORE the draft is cleared; a failed read of the stored lines counts as "differs" (never a silent "same")
            val differs = s.review?.let { r ->
                val shown = r.lines.map { it.memoLine.skuId to it.qtyBase }.sortedWith(compareBy({ it.first }, { it.second }))
                val stored = try { committer.storedSaleLines(draft.memoUuid) } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Throwable) { null }
                r.totals.netMtk != landed.netMtk || r.settlement.dueMtk != landed.dueMtk || stored == null || shown != stored
            } == true
            store.clear(draft.visitUuid)
            ui.value = s.copy(committed = landed, laterChangesNotSaved = differs)
            return landed
        }
        if (draft.edit == null && isCallClosed(draft)) {
            // D-AGATE-19: the call was closed while this sale was open: nothing is written; the rep is told so (never the generic "save failed")
            val problems = listOf<ReviewProblem>(ReviewProblem.CallClosed)
            ui.value = s.copy(refusal = problems)
            throw ReviewNotCommittable(problems)
        }
        val result = try {
            committer.commit(draft, s.catalog, editFix, qcCapMtkBySku())
        } catch (e: com.aktcl.aron.core.database.repo.EditTargetSupersededException) {
            // another edit already replaced the memo this one would replace: nothing was written, the rep is told what to do
            val problems = listOf<ReviewProblem>(ReviewProblem.EditTargetSuperseded)
            ui.value = s.copy(refusal = problems)
            throw ReviewNotCommittable(problems)
        } catch (e: com.aktcl.aron.core.database.repo.AttendanceRefusedException) {
            // the check-in gate refused the save inside the transaction: nothing was written; the rep is told what happened and what to do (not "save failed")
            val problems = listOf<ReviewProblem>(ReviewProblem.AttendanceRefused(e.why))
            ui.value = s.copy(refusal = problems)
            throw ReviewNotCommittable(problems)
        } catch (e: ClockMovedBackException) {
            val problems = listOf<ReviewProblem>(ReviewProblem.ClockMovedBack)
            ui.value = s.copy(refusal = problems)
            throw ReviewNotCommittable(problems)
        } catch (e: OverStockException) {
            // the stock moved since this sale opened (another memo, an edit): nothing was written. Re-read it, republish the
            // screen with the true figures, and report the shortage the TRANSACTION saw, never a guess from the stale catalog.
            rawCatalog = catalogSource.skus(e.businessDate ?: committer.stockDate(draft.businessDate, draft.routeId), draft.priceType).associateBy { it.skuId }
            val catalog = withGiveBack(givenBackFor)
            publish(checkNotNull(s.visit), draft, catalog, null)
            val problems = e.shortages.map { sh ->
                    val sku = catalog[sh.skuId]
                    ReviewProblem.OverStock(
                        sh.skuId, sku?.name ?: sh.skuId.toString(), sh.requestedBase, sh.heldBase,
                        sku?.entry?.fromBase(sh.requestedBase) ?: sh.requestedBase, sku?.entry?.fromBase(sh.heldBase) ?: sh.heldBase,
                        sku?.entryLabelKey.orEmpty(), sku?.nameBn,
                    )
                }
            ui.value = ui.value.copy(refusal = problems)
            throw ReviewNotCommittable(problems)
        }
        store.clear(draft.visitUuid)
        ui.value = s.copy(committed = result)
        result
    }

    /**
     * D-LEAD-PREREQ-04 ruling 3: the phone ends an abandoned call of an earlier day, so that call's UNSAVED draft is dropped. A memo already saved is never touched (it
     * is in Room; this only clears the draft file and the screen state). Returns true when a draft with something entered was dropped.
     */
    suspend fun dropUnsavedDraft(visitUuid: String): Boolean = dropUnsavedDraft(visitUuid) { }

    /**
     * [dropUnsavedDraft] with [tell] run BEFORE the draft is cleared and only when there is something to drop (D-AGATE-19): the rep's notice is stored first, so a kill between the two can
     * only show a notice for a draft that is dropped by the next start (never drop it without a word). A [tell] that fails is not allowed to keep the draft (the failure is swallowed, a
     * cancellation is not).
     */
    suspend fun dropUnsavedDraft(visitUuid: String, tell: suspend () -> Unit): Boolean = lock.withLock {
        val d = store.load(visitUuid)
        val saved = d != null && committer.existing(d.memoUuid) != null
        val had = d != null && !saved && (d.lines.isNotEmpty() || d.slide.isNotEmpty() || d.qc.isNotEmpty() || d.paidMtk != null || d.zeroSale)
        if (had) try { tell() } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Exception) { }
        if (d != null) store.clear(visitUuid)
        val s = ui.value
        if (s.visit?.visitUuid == visitUuid && s.committed == null) ui.value = SaleUiState()
        had
    }

    private suspend fun edit(change: (SaleDraft) -> SaleDraft) = lock.withLock { editLocked(change) }

    private suspend fun editLocked(change: (SaleDraft) -> SaleDraft) {
        val s = ui.value
        check(s.committed == null) { "sale already committed" }
        val next = change(checkNotNull(s.draft) { "no sale in progress" })
        val review = SaleReviewCalculator.review(next, s.catalog, qcCapMtkBySku()) // computed before saving: a failure is never persisted
        store.save(next)
        ui.value = SaleUiState(s.visit, next, s.catalog, review, null, qcCapMtkBySku())
    }

    private fun publish(visit: SaleVisit, draft: SaleDraft, catalog: Map<Long, SaleSku>, committed: CommittedSale?) {
        ui.value = SaleUiState(visit, draft, catalog, SaleReviewCalculator.review(draft, catalog, qcCapMtkBySku()), committed, qcCapMtkBySku())
    }
}
