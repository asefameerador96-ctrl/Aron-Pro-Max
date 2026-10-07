package com.aktcl.aron.sr

import android.content.Context
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.repo.ReconciliationRepository
import com.aktcl.aron.core.printing.doc.DaySummaryPrint
import com.aktcl.aron.core.printing.flow.PrintAttempt
import com.aktcl.aron.core.printing.flow.SaveAndPrint
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
import com.aktcl.aron.feature.memo.ui.RoomDueCollectionWriter
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
import com.aktcl.aron.rules.QtyUnit
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
    fun names() = PrintNames(::skuName, ::outletName, day.userDisplayName, day.dayData.value.route?.name.orEmpty())

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

    fun newSaleViewModel() = SaleViewModel(flow, SaveAndPrint(commitStep::invoke, day.printing))
    fun saleVisit(v: com.aktcl.aron.feature.outlet.OpenVisit) =
        SaleVisit(v.visitUuid, v.outletId, v.routeId, day.businessDate(), PriceType.OUTLET.wire, canSell = v.geoAction != "blocked")

    private val closer = VisitCloser(day.capture, CaptureMetaSource { _, rid -> day.metaProvider.meta(rid ?: 0L) }, { day.iso(day.currentMs()) })

    /** Writes the visit's one `visit_close` with [outcome] and closes the call; the sync is asked for afterwards (never awaited). */
    suspend fun closeVisit(outcome: VisitOutcomeCode, declined: Boolean = false) {
        val v = day.visitSession.current.value ?: return
        closer.close(VisitToClose(v.visitUuid, v.routeId, callStartedAtIso = callStarted.remove(v.visitUuid)), outcome, day.businessDate(), declined)
        day.visitSession.close(); runCatching { day.requestSync() }
    }

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
        dao.linesOf(memoUuid).forEach { l -> catalog[l.skuId]?.let { flow.setQuantity(l.skuId, l.qtyBase, QtyUnit.entries.first { u -> u.wire == it.baseUnit }) } }
        if (old.paidMtk > 0) flow.setPaid(old.paidMtk)
        flow.withEdit(EditContext(memoUuid, reason.wire))
    }

    /** Marks an outlet of the list as not reached: one `visit_skip` with its outbox record, no fix and no geo gate (F-SR-057). */
    suspend fun skipOutlet(outlet: com.aktcl.aron.core.database.entity.OutletEntity, reason: VisitOutcomeCode) {
        day.capture.recordVisitSkip(
            com.aktcl.aron.core.database.entity.VisitSkipEntity(ClientIds.newUuid(), day.metaProvider.meta(outlet.routeId), outlet.outletId, reason.wire),
        )
        runCatching { day.requestSync() }
    }

    fun newMemoViewModel() = MemoViewModel(
        store,
        RoomDueCollectionWriter(day.capture, { day.metaProvider.meta(0L) }, visitUuidOf = { o -> dao.visitsOn(day.businessDate()).lastOrNull { it.outletId == o }?.clientUuid }),
        MemoPrintingReprinter(day.printing), ::names, day::businessDate,
    )

    suspend fun history(): List<StoredMemo> = store.history(day.businessDate())

    suspend fun summary(): SummaryBundle { prepare(); return reads.summary(day.businessDate()) }
    suspend fun journey(): Journey = reads.journey(day.businessDate(), day.dayData.value.outlets)
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
        day.userId, day::businessDate, RoomDaySource(db), serverCounts,
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
