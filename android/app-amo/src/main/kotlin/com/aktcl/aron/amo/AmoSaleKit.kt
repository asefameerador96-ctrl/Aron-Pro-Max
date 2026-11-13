package com.aktcl.aron.amo

import android.content.Context
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.repo.ReconciliationRepository
import com.aktcl.aron.core.printing.doc.DaySummaryPrint
import com.aktcl.aron.core.printing.flow.PrintAttempt
import com.aktcl.aron.feature.dayclose.RoomDaySource
import com.aktcl.aron.feature.dayclose.RoomDaySubmitWriter
import com.aktcl.aron.feature.dayclose.SalesSubmitViewModel
import com.aktcl.aron.feature.dayclose.ServerCounts
import com.aktcl.aron.feature.memo.domain.DaySummary
import com.aktcl.aron.feature.memo.domain.HomeMoney
import com.aktcl.aron.feature.memo.domain.HomeMoneyBuilder
import com.aktcl.aron.feature.memo.domain.Journey
import com.aktcl.aron.feature.memo.domain.JourneyBuilder
import com.aktcl.aron.feature.memo.domain.JourneyVisit
import com.aktcl.aron.feature.memo.domain.KpiStrip
import com.aktcl.aron.feature.memo.domain.KpiStripBuilder
import com.aktcl.aron.feature.memo.domain.PlannedOutlet
import com.aktcl.aron.feature.memo.domain.DaySummaryCalculator
import com.aktcl.aron.feature.memo.domain.PrintMapping
import com.aktcl.aron.feature.memo.domain.PrintNames
import com.aktcl.aron.feature.memo.domain.StoredMemo
import com.aktcl.aron.feature.memo.domain.SummaryDiscount
import com.aktcl.aron.feature.memo.domain.SummaryLine
import com.aktcl.aron.feature.memo.domain.SummaryMemo
import com.aktcl.aron.feature.memo.domain.SummarySku
import com.aktcl.aron.feature.memo.ui.MemoPrintingReprinter
import com.aktcl.aron.feature.memo.ui.MemoViewModel
import com.aktcl.aron.feature.memo.ui.VisitDueCollectionWriter
import com.aktcl.aron.feature.memo.ui.RoomMemoStore
import com.aktcl.aron.feature.outlet.FixReading
import com.aktcl.aron.feature.outlet.GeoSettings
import com.aktcl.aron.feature.sale.domain.CaptureMetaSource
import com.aktcl.aron.feature.sale.domain.EditContext
import com.aktcl.aron.feature.sale.domain.EditDenied
import com.aktcl.aron.feature.sale.domain.EditGate
import com.aktcl.aron.feature.sale.domain.EditReason
import com.aktcl.aron.feature.sale.domain.FileDraftStore
import com.aktcl.aron.feature.sale.domain.MemoPrintSource
import com.aktcl.aron.feature.sale.domain.SaleCatalog
import com.aktcl.aron.feature.sale.domain.SaleCommitStep
import com.aktcl.aron.feature.sale.domain.SaleCommitter
import com.aktcl.aron.feature.sale.domain.SaleFlow
import com.aktcl.aron.feature.sale.domain.SaleSku
import com.aktcl.aron.feature.sale.domain.SaleVisit
import com.aktcl.aron.feature.sale.domain.VisitCloser
import com.aktcl.aron.feature.sale.domain.VisitOutcomeCode
import com.aktcl.aron.feature.sale.domain.VisitToClose
import com.aktcl.aron.feature.sale.ui.SaleViewModel
import com.aktcl.aron.rules.FixInput
import com.aktcl.aron.rules.GeoVerdict
import com.aktcl.aron.rules.GeoVerdicts
import com.aktcl.aron.rules.OutletGeo
import com.aktcl.aron.rules.PriceType
import com.aktcl.aron.rules.EntryUnits
import java.io.File

/** What the Summary screen shows and prints: the day's aggregate with the QC and due figures the print needs. */
data class SummaryBundle(val summary: DaySummary, val qcMtk: Long, val dueMtk: Long)

/**
 * The production assembly of android-sr-b's modules on the user's database (docs/status/android-sr-b.md "Production
 * adapters"): the sale catalog over bundle prices and the stock tracker, the committer with its memo numbering, the memo,
 * summary and Sales Submit models. One kit per [AmoDay]; nothing here waits for the network.
 */
class AmoSaleKit(private val day: AmoDay, private val context: Context, private val syncScheduler: com.aktcl.aron.core.sync.SyncScheduler, private val online: () -> Boolean) {
    private val db get() = day.database
    private val dao get() = db.captureDao()
    private val store = RoomMemoStore(db)
    private val reads by lazy { RoomSaleReads(db, day.reference) }

    @Volatile private var skuNames: Map<Long, String> = emptyMap()
    @Volatile private var skuCategory: Map<Long, String> = emptyMap()

    /** Loads the SKU names once per screen entry; the print and name lookups below are synchronous reads of it. */
    suspend fun prepare() {
        day.refreshZone()
        val skus = day.reference.skus()
        skuNames = skus.associate { it.skuId to it.shortName }
        skuCategory = skus.associate { it.skuId to it.categoryCode }
    }

    fun skuName(id: Long): String = skuNames[id] ?: id.toString()
    fun outletName(id: Long): String = day.zoneOutlets.firstOrNull { it.outletId == id }?.let { it.name + " (" + it.code + ")" } ?: id.toString()
    fun skuCategoryCode(id: Long): String? = skuCategory[id]
    fun names() = PrintNames(::skuName, ::outletName, day.userDisplayName, day.routeName(), category = { id -> com.aktcl.aron.feature.memo.ui.MemoCategories.label(context, skuCategory[id]) }, categoryCode = { id -> skuCategory[id].orEmpty() })

    /** Prices of the bundle and the day's stock (docs/24 s7.2, F-SYS-045). */
    val catalog = SaleCatalog { date, priceType -> reads.catalog(date, priceType) }

    private val committer = SaleCommitter(
        day.capture, numbers = { _, _ -> error("memo numbering is wired through AmoDay.memoNumbering") },
        metaSource = CaptureMetaSource { _, rid -> day.metaProvider.meta(rid ?: 0L) }, nowIso = { day.iso(day.currentMs()) },
        findMemo = { dao.memo(it) }, numbering = day.memoNumbering,
    )
    val flow = SaleFlow(
        FileDraftStore(File(context.filesDir, "sale-drafts-" + day.userId)), catalog, committer,
        ownTaken = { memoUuid ->
            // a memo's lines plus its reward packs: what an edit of it gets back into the stock figure
            val taken = HashMap<Long, Long>()
            dao.linesOf(memoUuid).forEach { taken.merge(it.skuId, it.qtyBase, Long::plus) }
            dao.discountsOf(memoUuid).filter { it.kind == "drp" && it.skuId != null }.forEach { taken.merge(it.skuId!!, it.qtyBase ?: 0L, Long::plus) }
            taken
        },
    )

    /** When each call was started (the SR tapped Yes on the start prompt); read when the visit closes. */
    val callStarted = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The fresh geofence fix of the edit in progress (F-SR-033): taken by [prepareEdit], consumed by the commit, never reused. */
    @Volatile private var editReading: FixReading? = null
    private val editFixUuid get() = editFixId ?: ClientIds.newUuid().also { editFixId = it }
    @Volatile private var editFixId: String? = null
    private fun editFix(): GeoFixEntity? = editReading?.toEntity(editFixUuid, flow.state.value.draft?.memoUuid.orEmpty(), "memo_edit", 0)

    private val printSource = MemoPrintSource { uuid ->
        prepare()
        val date = dao.memo(uuid)!!.meta.businessDate
        PrintMapping.memo(store.memos(date).first { it.memoUuid == uuid }, names())
    }
    private val commitStep = SaleCommitStep(flow, printSource) { editFix() }

    // tso-manual X4a (R-W-11; fix round): the sale or edit whose save the Final Submit lock refused (the lock arrived while
    // it was open), by its draft key, so only that sale's or edit's host shows the lock text
    private val _lockRefused = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /**
     * The draft key of the sale or memo edit the Final Submit lock refused (nothing was stored or printed), or null: the
     * call's visit uuid for a sale, the edited memo's uuid for an edit ([refusalKey]). The host whose key it is shows the lock text.
     */
    val lockRefused: kotlinx.coroutines.flow.StateFlow<String?> = _lockRefused

    // tso-manual X4a follow-up (R-W-23): the same for a save refused because the supervisor day's Sales Submit stands
    private val _submitRefused = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /**
     * The draft key ([refusalKey]) of the sale or memo edit refused because the day's sales are already submitted (the AMO's
     * supervisor-day Sales Submit stands; nothing was stored or printed), or null. The host whose key it is shows the submit text.
     */
    val submitRefused: kotlinx.coroutines.flow.StateFlow<String?> = _submitRefused

    /** The host has shown the text of a refused save. */
    fun clearLockRefusal() { _lockRefused.value = null; _submitRefused.value = null }

    fun newSaleViewModel() = SaleViewModel(flow, AmoLockGate.saveAndPrint(commitStep::invoke, day.printing) { why ->
        val key = flow.state.value.draft?.let(::refusalKey)
        when (why) {
            com.aktcl.aron.core.database.repo.DayRefusal.FINAL_SUBMIT -> _lockRefused.value = key
            com.aktcl.aron.core.database.repo.DayRefusal.SALES_SUBMIT -> _submitRefused.value = key
        }
    })

    /**
     * tso-manual X4a (fix round): OK on the text of a refused sale. When the call's route-day still refuses it (the lock, or
     * since the follow-up the supervisor day's Sales Submit) the call ends on the phone and [onCallEnded] runs (back to the
     * outlet list), else [onDone] (back to the call menu); the refusal is cleared last, so the sale's generic "save failed"
     * never shows in between.
     */
    suspend fun afterSaleRefusal(onCallEnded: () -> Unit, onDone: () -> Unit) {
        if (endCallIfClosed() != null) onCallEnded() else onDone()
        clearLockRefusal()
    }
    fun saleVisit(v: com.aktcl.aron.feature.outlet.OpenVisit) =
        SaleVisit(v.visitUuid, v.outletId, v.routeId, day.businessDate(), PriceType.OUTLET.wire, canSell = v.geoAction != "blocked")

    private val closer = VisitCloser(day.capture, CaptureMetaSource { _, rid -> day.metaProvider.meta(rid ?: 0L) }, { day.iso(day.currentMs()) })

    /**
     * Writes the visit's one `visit_close` with [outcome] and closes the call; the sync is asked for afterwards (never awaited).
     * tso-manual X4a (R-W-11): [AmoDay.closeVisit] has already ended a call on a closed route on the phone; a lock that lands
     * during the write ends the call the same way (no close queued, nothing thrown).
     */
    suspend fun closeVisit(outcome: VisitOutcomeCode, declined: Boolean = false): com.aktcl.aron.core.database.repo.DayRefusal? {
        var why: com.aktcl.aron.core.database.repo.DayRefusal? = null
        val queued = AmoLockGate.closeCall(day.visitSession, { why = it }) { v ->
            closer.close(VisitToClose(v.visitUuid, v.routeId, callStartedAtIso = callStarted.remove(v.visitUuid)), outcome, day.businessDate(), declined)
        }
        if (queued) runCatching { day.requestSync() }
        return why
    }

    /**
     * tso-manual X4a (R-W-11, A3 ask 11; follow-up R-W-23): ends the open call on the phone when its close would be refused
     * (the zone's Final Submit closed its route, or the supervisor day's Sales Submit stands; no close is written) and says
     * why; null leaves the call as it was.
     */
    suspend fun endCallIfClosed(): com.aktcl.aron.core.database.repo.DayRefusal? {
        val v = day.visitSession.current.value ?: return null
        val why = AmoCallLock.endedBy(day.capture, day.visitSession, day.metaProvider.meta(v.routeId)) ?: return null
        callStarted.remove(v.visitUuid); day.routeId = null
        return why
    }

    /**
     * tso-manual X4a (R-W-11; follow-up R-W-23): why an edit of [memoUuid] would be refused (the edit is a new `memo` on the
     * old memo's route, today), or null: the Final Submit lock, or the supervisor day's Sales Submit. The memo screen disables
     * Edit with the matching text and the edit host starts nothing; reprints stay allowed.
     */
    suspend fun editRefusal(memoUuid: String): com.aktcl.aron.core.database.repo.DayRefusal? {
        val memo = dao.memo(memoUuid) ?: return null
        // not runCatching: it would turn the cancellation of a superseded LaunchedEffect run into null, which that run then writes over the newer answer (D-AMO-CALLS-35)
        return com.aktcl.aron.core.common.orNullKeepingCancellation { day.capture.dayRefusal(day.metaProvider.meta(memo.meta.routeId ?: 0L), "memo") }
    }

    /** True when [editRefusal] is the Final Submit lock. */
    suspend fun editLocked(memoUuid: String): Boolean = editRefusal(memoUuid) == com.aktcl.aron.core.database.repo.DayRefusal.FINAL_SUBMIT

    /**
     * Edit gate of F-SR-033 for a stored memo: still live, before QC, and inside the outlet geofence on a fresh fix. Null when
     * the edit may start (the fix is kept for the commit); the SR never types a location.
     */
    suspend fun prepareEdit(memoUuid: String): EditDenied? {
        val memo = dao.memo(memoUuid)
        val date = day.businessDate()
        val live = memo != null && dao.memosOn(date).none { it.supersedesClientUuid == memoUuid }
        val qcDone = memo != null && dao.qcLinesOn(date).any { it.visitClientUuid == memo.visitClientUuid }
        EditGate.check(inGeofenceNow = true, qcCompleted = qcDone, memoIsLive = live, memoExists = memo != null)?.let { return it }
        val o = day.zoneOutlets.firstOrNull { it.outletId == memo!!.outletId }?.let(day::visitOutlet) ?: return EditDenied.OutsideGeofence
        val reading = day.fixSource.readFix("memo_edit")
        val verdict = GeoVerdicts.verdict(
            FixInput(reading.isOk, reading.lat ?: Double.NaN, reading.lng ?: Double.NaN, reading.accuracyM, reading.isMock),
            OutletGeo(o.locationUsable, o.lat ?: Double.NaN, o.lng ?: Double.NaN), o.radiusM, o.maxAccuracyM, GeoSettings.DEFAULT.policyBase,
        )
        if (verdict.verdict != GeoVerdict.IN_RANGE) return EditDenied.OutsideGeofence
        editReading = reading; editFixId = null
        return null
    }

    /** Starts the edit draft: the old memo's lines and payment pre-filled, the outlet read-only, the reason recorded. */
    suspend fun beginEdit(memoUuid: String, reason: EditReason) {
        val old = checkNotNull(dao.memo(memoUuid)) { "memo not on this phone" }
        flow.start(SaleVisit(old.visitClientUuid, old.outletId, old.meta.routeId, old.meta.businessDate, PriceType.OUTLET.wire), editing = memoUuid) // the edit gets its own memo's quantity back
        val catalog = flow.state.value.catalog
        dao.linesOf(memoUuid).forEach { l -> catalog[l.skuId]?.let { sku -> EntryUnits.restore(sku.entry, sku.baseUnit, l.qtyBase).let { (q, u) -> flow.setQuantity(l.skuId, q, u) } } }
        if (old.paidMtk > 0) flow.setPaid(old.paidMtk)
        flow.withEdit(EditContext(memoUuid, reason.wire))
    }

    /**
     * Marks an outlet of the list as not reached: one `visit_skip` with its outbox record, no fix and no geo gate (F-SR-057).
     * tso-manual X4a (R-W-11): false when the Final Submit lock refused it (nothing stored); follow-up (R-W-23): or the
     * supervisor day's Sales Submit. [onRefused] says which; nothing throws.
     */
    suspend fun skipOutlet(
        outlet: com.aktcl.aron.core.database.entity.OutletEntity, reason: VisitOutcomeCode, onRefused: (com.aktcl.aron.core.database.repo.DayRefusal) -> Unit = {},
    ): Boolean {
        val queued = AmoLockGate.skip(onRefused) {
            day.capture.recordVisitSkip(
                com.aktcl.aron.core.database.entity.VisitSkipEntity(ClientIds.newUuid(), day.metaProvider.meta(outlet.routeId), outlet.outletId, reason.wire),
            )
        }
        if (queued) runCatching { day.requestSync() }
        return queued
    }

    /**
     * The memo screen. tso-manual X4a (R-W-11 (3), fix round): Mark Paid writes through [VisitDueCollectionWriter], so a
     * collection outside a call (the AMO has no route of its own then) carries the route of the control or sale call it
     * attaches to, never a visit with no route; it is drawn only when that write would be accepted (no Final Submit lock and
     * no supervisor-day submit on that route-day), and a tap the lock beats is refused with words, never a crash.
     */
    fun newMemoViewModel() = MemoViewModel(
        store,
        VisitDueCollectionWriter(db, day.capture, { rid -> day.metaProvider.meta(rid ?: 0L) }, day::businessDate),
        MemoPrintingReprinter(day.printing), ::names, day::businessDate,
    )

    suspend fun history(): List<StoredMemo> = store.history(day.businessDate())

    suspend fun summary(): SummaryBundle { prepare(); return reads.summary(day.businessDate()) }
    suspend fun journey(): Journey = reads.journey(day.businessDate(), day.zoneOutlets)
    suspend fun kpi(): Pair<KpiStrip, HomeMoney> { prepare(); return reads.kpi(day.businessDate(), day.zoneOutlets) }

    /** One stable v4 UUID per user and business date, so every print of the day is one document (docs/requests/android-print-integration.md). */
    private fun summaryUuid(date: String): String {
        val prefs = context.getSharedPreferences("aron-summary-" + day.userId, Context.MODE_PRIVATE)
        return prefs.getString("uuid-$date", null) ?: ClientIds.newUuid().also { prefs.edit().putString("uuid-$date", it).apply() }
    }

    suspend fun printSummary(b: SummaryBundle): PrintAttempt {
        val date = day.businessDate()
        return day.printing.printDaySummary(summaryUuid(date), PrintMapping.daySummary(b.summary, b.qcMtk, b.dueMtk, day.currentMs(), names())).also { runCatching { day.requestSync() } }
    }


    private suspend fun reconcileConfig(): String? =
        com.aktcl.aron.core.common.orNullKeepingCancellation { day.reference.config(com.aktcl.aron.core.database.repo.ReconciliationRepository.CONFIG_KEY, day.iso(day.currentMs())) }

    private suspend fun reconcileTypes(): List<String> = com.aktcl.aron.feature.amo.AmoReconciliation.types(reconcileConfig())

    /** The Sales Submit table's row groups (display only), from the same configuration as [reconcileTypes]. */
    suspend fun submitRowGroups(priceCounted: Boolean = false): List<List<String>> = com.aktcl.aron.feature.amo.AmoReconciliation.rowGroups(reconcileConfig(), com.aktcl.aron.core.common.orDefaultKeepingCancellation(false) { day.priceComplianceTile() }, priceCounted)

    /** F-AMO-030: the extended reconciliation (fixed rows, zero when the type has no records) and a `supervisor_day` submit. */
    fun newSubmitViewModel() = SalesSubmitViewModel(
        day.userId, day::businessDate, AmoDaySource(RoomDaySource(db), ::reconcileTypes), com.aktcl.aron.feature.dayclose.DigestServerCounts(AmoServerCounts(serverCounts, ::reconcileTypes), day.submitDigests, db),
        submitWriter(),
        syncScheduler, online,
    )

    /**
     * The `supervisor_day` submit's writer. The supervisor day has no route, whatever call happens to be open (the lock and
     * the cycle key on date + route), so it is queued after the zone's Final Submit too (X4a fix round: tested on its own).
     */
    internal fun submitWriter() =
        RoomDaySubmitWriter(db, day.capture, { day.metaProvider.meta(0L).copy(routeId = null) }, { id -> skuCategory[id] ?: "other" }, stockSlipPrinted = { !day.slipNotPrinted() }, scope = "supervisor_day")

    /** The sync response's `server_totals` as accepted + rejected + quarantined per record type; null before the first answer. */
    private val serverCounts = ServerCounts { date ->
        ReconciliationRepository(db) { day.iso(day.currentMs()) }.serverTotals(date)?.byType?.mapValues { (_, v) ->
            runCatching {
                val o = v as kotlinx.serialization.json.JsonObject
                listOf("accepted", "rejected", "quarantined").sumOf { (o[it] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0 }
            }.getOrDefault(0)
        }
    }
}
