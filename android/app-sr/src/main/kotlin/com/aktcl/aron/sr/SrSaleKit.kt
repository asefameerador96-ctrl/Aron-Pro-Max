package com.aktcl.aron.sr

import com.aktcl.aron.feature.memo.domain.SaleHistoryBuilder
import android.content.Context
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.repo.DayRefusal
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
 * summary and Sales Submit models. One kit per [SrDay]; nothing here waits for the network.
 */
class SrSaleKit(private val day: SrDay, private val context: Context, private val syncScheduler: com.aktcl.aron.core.sync.SyncScheduler, private val online: () -> Boolean) {
    private val db get() = day.database
    private val dao get() = db.captureDao()
    private val store = RoomMemoStore(db)
    private val reads by lazy { RoomSaleReads(db, day.reference) }

    @Volatile private var skuNames: Map<Long, String> = emptyMap()
    @Volatile private var skuCategory: Map<Long, String> = emptyMap()

    /** Loads the SKU names once per screen entry; the print and name lookups below are synchronous reads of it. */
    suspend fun prepare() {
        val skus = day.reference.skus()
        skuNames = skus.associate { it.skuId to it.shortName }
        skuCategory = skus.associate { it.skuId to it.categoryCode }
    }

    fun skuName(id: Long): String = skuNames[id] ?: id.toString()
    fun outletName(id: Long): String = day.dayData.value.outlets.firstOrNull { it.outletId == id }?.let { it.name + " (" + it.code + ")" } ?: id.toString()
    fun skuCategoryCode(id: Long): String? = skuCategory[id]
    fun names() = PrintNames(::skuName, ::outletName, day.userDisplayName, day.dayData.value.route?.name.orEmpty(), category = { id -> com.aktcl.aron.feature.memo.ui.MemoCategories.label(context, skuCategory[id]) }, categoryCode = { id -> skuCategory[id].orEmpty() })

    /** Prices of the bundle and the day's stock (docs/24 s7.2, F-SYS-045). */
    val catalog = SaleCatalog { date, priceType -> reads.catalog(date, priceType) }

    private val committer = SaleCommitter(
        day.capture, numbers = { _, _ -> error("memo numbering is wired through SrDay.memoNumbering") },
        metaSource = CaptureMetaSource { _, rid -> day.metaProvider.meta(rid ?: 0L) }, nowIso = { day.iso(day.currentMs()) },
        findMemo = { dao.memo(it) }, numbering = day.memoNumbering,
    )
    val flow = SaleFlow(FileDraftStore(File(context.filesDir, "sale-drafts-" + day.userId)), catalog, committer)

    /** When each call was started (the SR tapped Yes on the start prompt); read when the visit closes. */
    val callStarted = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * `cfg.sale.call_photo_required` (SR manual s8 / 20, owner Q8 default true): the outlet photo after হ্যাঁ. OFF in TRAIN 1 on purpose: a photo with no
     * `call_photo_uuid` on the visit record and no media purpose for it cannot be linked on the server, so asking the rep for it would cost a
     * minute per call for nothing. TRAIN 2 adds the contract members, reads the config and turns this on (D-SR-MAN-S4-07).
     */
    val callPhotoRequired: Boolean = false

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

    // tso-manual X4a follow-up (R-W-23): the same for a save refused because the SR's own Sales Submit stands
    private val _submitRefused = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /**
     * The draft key ([refusalKey]) of the sale or memo edit refused because today's sales are already submitted (the SR's
     * own Sales Submit stands; nothing was stored or printed), or null. The host whose key it is shows the submit text.
     */
    val submitRefused: kotlinx.coroutines.flow.StateFlow<String?> = _submitRefused

    /** Why the save of the draft keyed [key] was refused, or null ([lockRefused], [submitRefused]). */
    fun saveRefusal(key: String): DayRefusal? = when (key) {
        _lockRefused.value -> DayRefusal.FINAL_SUBMIT
        _submitRefused.value -> DayRefusal.SALES_SUBMIT
        else -> null
    }

    /** The host has shown the text of a refused save. */
    fun clearLockRefusal() { _lockRefused.value = null; _submitRefused.value = null }

    fun newSaleViewModel() = SaleViewModel(flow, SrLockGate.saveAndPrint(commitStep::invoke, day.printing) { why ->
        val key = flow.state.value.draft?.let(::refusalKey)
        when (why) {
            DayRefusal.FINAL_SUBMIT -> _lockRefused.value = key
            DayRefusal.SALES_SUBMIT -> _submitRefused.value = key
        }
    })

    /**
     * tso-manual X4a (fix round): OK on the text of a refused sale. The call ends on the phone when its route-day still
     * refuses it (the Final Submit lock, or since the follow-up the SR's own Sales Submit; no close is written), then
     * [onDone] (Home with the banner); the refusal is cleared last, so the sale's generic "save failed" never shows in between.
     */
    suspend fun afterSaleRefusal(onDone: () -> Unit) {
        endRefusedCall()
        onDone()
        clearLockRefusal()
    }

    /**
     * tso-manual X4a follow-up (R-W-23): OK on the text of a call that can no longer take its records: the call ends on the
     * phone when its route-day still refuses it (no close is written) and its start time is forgotten; returns why, or null
     * when the day was reopened meanwhile (the call stays open and goes on).
     */
    suspend fun endRefusedCall(): DayRefusal? {
        val v = day.visitSession.current.value ?: return null
        return day.endCallIfClosed()?.also { callStarted.remove(v.visitUuid) }
    }
    fun saleVisit(v: com.aktcl.aron.feature.outlet.OpenVisit) =
        SaleVisit(v.visitUuid, v.outletId, v.routeId, day.businessDate(), PriceType.OUTLET.wire, canSell = v.geoAction != "blocked")

    private val closer = VisitCloser(day.capture, CaptureMetaSource { _, rid -> day.metaProvider.meta(rid ?: 0L) }, { day.iso(day.currentMs()) })

    /**
     * Writes the visit's one `visit_close` with [outcome] and closes the call; the sync is asked for afterwards (never awaited).
     * tso-manual X4a (R-W-11, A3 ask 11): on a route-day closed by its zone's Final Submit the call ends on the phone first and
     * no close is written (the server would refuse it); a lock that lands during the write ends it the same way. Follow-up
     * (R-W-23): the same for a route-day the SR's own Sales Submit closed. Never throws for either; returns why the call ended
     * on the phone without a close, or null (a close was queued, or no call was open). The hosts ask [openCallRefusal] first
     * and show the text before calling it.
     */
    suspend fun closeVisit(outcome: VisitOutcomeCode, declined: Boolean = false): DayRefusal? {
        val open = day.visitSession.current.value ?: return null
        day.endCallIfClosed()?.let { callStarted.remove(open.visitUuid); return it }
        var why: DayRefusal? = null
        val queued = SrLockGate.closeCall(day.visitSession, { why = it }) { v ->
            closer.close(VisitToClose(v.visitUuid, v.routeId, callStartedAtIso = callStarted.remove(v.visitUuid)), outcome, day.businessDate(), declined)
        }
        if (queued) runCatching { day.requestSync() }
        return why
    }

    /**
     * tso-manual X4a follow-up (R-W-23): why the open call can no longer take its records (no sale, no close), or null. The
     * call screens show the lock text or the submit text, and OK ends the call on the phone ([closeVisit] or
     * `SrDay.closeVisitAbandoned`, which write nothing then). Pure reads.
     */
    suspend fun openCallRefusal(): DayRefusal? = day.openCallRefusal()

    /**
     * tso-manual X4a (R-W-11; follow-up R-W-23): why a new call on [routeId] (the outlet's route, as the visit records it) would
     * be refused today, or null: the Final Submit lock, or the SR's own Sales Submit of that route-day. The outlet list shows
     * the matching text instead of reading a fix.
     */
    suspend fun newCallRefusal(routeId: Long): DayRefusal? =
        runCatching { day.capture.dayRefusal(day.metaProvider.meta(routeId), "visit") }.getOrNull()

    /**
     * tso-manual X4a (R-W-11, A3 ask 11): true when a new call on [routeId] would be refused by the Final Submit lock today;
     * it is `SrDay.finalLocked()` for the day's route.
     */
    suspend fun callLocked(routeId: Long): Boolean = newCallRefusal(routeId) == DayRefusal.FINAL_SUBMIT

    /**
     * tso-manual X4a (R-W-11, fix round; follow-up R-W-23): the outlet picker's tap. False when a new call on [outlet]'s route
     * would be refused ([newCallRefusal]; [onRefused] says why): no fix is read and nothing is stored, the picker shows the
     * matching text. Otherwise the visit flow opens the call (it reads the fix and commits; its state says how that went)
     * and the picker moves to the visit screen.
     */
    suspend fun startCall(outlet: com.aktcl.aron.core.database.entity.OutletEntity, onRefused: (DayRefusal) -> Unit = {}): Boolean {
        newCallRefusal(outlet.routeId)?.let { onRefused(it); return false }
        runCatching { day.visitFlow.open(day.visitOutlet(outlet)) }
        return true
    }

    /**
     * tso-manual X4a (R-W-11, fix round; follow-up R-W-23): why [state] is a call commit the route-day refused (the lock, or
     * the SR's own Sales Submit, landed while the fix was read; nothing was stored), or null. The visit screen shows the
     * matching text, not the generic retry.
     */
    suspend fun commitRefusal(state: com.aktcl.aron.feature.outlet.VisitUiState?): DayRefusal? =
        (state as? com.aktcl.aron.feature.outlet.VisitUiState.CommitFailed)?.let { newCallRefusal(it.outlet.routeId) }

    /** True when [commitRefusal] is the Final Submit lock. */
    suspend fun refusedByLock(state: com.aktcl.aron.feature.outlet.VisitUiState?): Boolean = commitRefusal(state) == DayRefusal.FINAL_SUBMIT

    /**
     * tso-manual X4a (R-W-11; follow-up R-W-23): why an edit of [memoUuid] would be refused (the edit is a new `memo` on the
     * old memo's route, today), or null: the Final Submit lock, or the SR's own Sales Submit of that route-day. The memo
     * screen disables Edit with the matching text and the edit host starts nothing; reprints stay allowed.
     */
    suspend fun editRefusal(memoUuid: String): DayRefusal? {
        val memo = dao.memo(memoUuid) ?: return null
        // not runCatching: it would turn the cancellation of a superseded LaunchedEffect run into null, which that run then writes over the newer answer (D-AMO-CALLS-35)
        return com.aktcl.aron.core.common.orNullKeepingCancellation { day.capture.dayRefusal(day.metaProvider.meta(memo.meta.routeId ?: 0L), "memo") }
    }

    /** True when [editRefusal] is the Final Submit lock. */
    suspend fun editLocked(memoUuid: String): Boolean = editRefusal(memoUuid) == DayRefusal.FINAL_SUBMIT

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
        val o = day.dayData.value.outlets.firstOrNull { it.outletId == memo!!.outletId }?.let(day::visitOutlet) ?: return EditDenied.OutsideGeofence
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
        flow.start(SaleVisit(old.visitClientUuid, old.outletId, old.meta.routeId, old.meta.businessDate, PriceType.OUTLET.wire))
        val catalog = flow.state.value.catalog
        dao.linesOf(memoUuid).forEach { l -> catalog[l.skuId]?.let { sku -> EntryUnits.restore(sku.entry, sku.baseUnit, l.qtyBase).let { (q, u) -> flow.setQuantity(l.skuId, q, u) } } }
        if (old.paidMtk > 0) flow.setPaid(old.paidMtk)
        flow.withEdit(EditContext(memoUuid, reason.wire))
    }

    /**
     * Marks an outlet of the list as not reached: one `visit_skip` with its outbox record, no fix and no geo gate (F-SR-057).
     * tso-manual X4a (R-W-11): false when the Final Submit lock refused it (nothing stored; the host shows the lock text);
     * follow-up (R-W-23, ask 4): or the SR's own Sales Submit refused it (the host shows the submit text). [onRefused] says
     * which; nothing throws.
     */
    suspend fun skipOutlet(outlet: com.aktcl.aron.core.database.entity.OutletEntity, reason: VisitOutcomeCode, onRefused: (DayRefusal) -> Unit = {}): Boolean {
        val queued = SrLockGate.skip(onRefused) {
            day.capture.recordVisitSkip(
                com.aktcl.aron.core.database.entity.VisitSkipEntity(ClientIds.newUuid(), day.metaProvider.meta(outlet.routeId), outlet.outletId, reason.wire),
            )
        }
        if (queued) runCatching { day.requestSync() }
        return queued
    }

    /**
     * The memo screen. tso-manual X4a (R-W-11 (3), fix round): Mark Paid writes through [VisitDueCollectionWriter] (the
     * collection carries the route of the visit it attaches to) and is drawn only when that write would be accepted, so it
     * is hidden after the Final Submit lock and after the SR's own Sales Submit; a tap the lock beats is refused with words.
     */
    fun newMemoViewModel() = MemoViewModel(
        store,
        VisitDueCollectionWriter(db, day.capture, { rid -> day.metaProvider.meta(rid ?: 0L) }, day::businessDate),
        MemoPrintingReprinter(day.printing), ::names, day::businessDate,
    )

    /** [days] local days ending today; Sale History keeps the 7-day window. */
    suspend fun history(days: Int = LOCAL_HISTORY_DAYS): List<StoredMemo> = store.history(day.businessDate(), days)

    /** An outlet's sales on the phone for Sale History / "সেল ডাটা"; cancellation propagates, any other failure reads as no sales. */
    suspend fun saleHistory(outletId: Long): com.aktcl.aron.feature.memo.domain.SaleHistory =
        com.aktcl.aron.core.common.orDefaultKeepingCancellation(com.aktcl.aron.feature.memo.domain.SaleHistory(emptyList(), 0L, false)) {
            SaleHistoryBuilder.build(history(), outletId)
        }

    /** The oldest date the local window holds (today and the six days before it): older dates cannot be shown offline. */
    fun historyOldest(): String = java.time.LocalDate.parse(day.businessDate()).minusDays((LOCAL_HISTORY_DAYS - 1).toLong()).toString()

    /**
     * The quantity popup's আগের দিন and SDLW for one outlet (D-SR-MAN-S4-05). SDLW is exactly seven days back, so the window is eight local
     * days (today and the seven before it); Sale History keeps its seven. The read keeps cancellation; any other failure shows no figures.
     */
    suspend fun skuStats(outletId: Long): Map<Long, com.aktcl.aron.feature.sale.domain.SkuStats> {
        val rows = com.aktcl.aron.core.common.orDefaultKeepingCancellation(emptyList()) {
            SaleHistoryBuilder.build(history(STATS_DAYS), outletId).days.flatMap { d ->
                d.skuRows.map { r -> com.aktcl.aron.feature.sale.domain.StatRow(d.businessDate, r.skuId, r.qtyEntered, r.unitEntered) }
            }
        }
        return com.aktcl.aron.feature.sale.domain.SkuStatsBuilder.build(rows, day.businessDate())
    }

    companion object {
        const val LOCAL_HISTORY_DAYS = 7
        const val STATS_DAYS = 8
    }

    suspend fun summary(): SummaryBundle { prepare(); return reads.summary(day.businessDate()) }
    suspend fun journey(): Journey = reads.journey(day.businessDate(), day.dayData.value.outlets)
    suspend fun packetsSold(): Long { prepare(); return reads.packetsSold(day.businessDate()) }
    suspend fun kpi(): Pair<KpiStrip, HomeMoney> { prepare(); return reads.kpi(day.businessDate(), day.dayData.value.outlets) }

    /** One stable v4 UUID per user and business date, so every print of the day is one document (docs/requests/android-print-integration.md). */
    private fun summaryUuid(date: String): String {
        val prefs = context.getSharedPreferences("aron-summary-" + day.userId, Context.MODE_PRIVATE)
        return prefs.getString("uuid-$date", null) ?: ClientIds.newUuid().also { prefs.edit().putString("uuid-$date", it).apply() }
    }

    suspend fun printSummary(b: SummaryBundle): PrintAttempt {
        val date = day.businessDate()
        return day.printing.printDaySummary(summaryUuid(date), PrintMapping.daySummary(b.summary, b.qcMtk, b.dueMtk, day.currentMs(), names())).also { runCatching { day.requestSync() } }
    }


    fun newSubmitViewModel() = SalesSubmitViewModel(
        day.userId, day::businessDate, RoomDaySource(db), com.aktcl.aron.feature.dayclose.DigestServerCounts(serverCounts, day.submitDigests, db),
        RoomDaySubmitWriter(db, day.capture, { day.metaProvider.meta(0L) }, { id -> skuCategory[id] ?: "other" }, stockSlipPrinted = { !day.slipNotPrinted() }),
        syncScheduler, online,
    )

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
