package com.aktcl.aron.feature.outlet

import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.VisitEntity
import com.aktcl.aron.rules.FixInput
import com.aktcl.aron.rules.GeoAction
import com.aktcl.aron.rules.GeoVerdict
import com.aktcl.aron.rules.GeoVerdictResult
import com.aktcl.aron.rules.GeoVerdicts
import com.aktcl.aron.rules.OutletGeo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the visit screen shows (F-SR-017). */
sealed interface VisitUiState {
    data object Idle : VisitUiState
    data object ReadingFix : VisitUiState

    /** The geo check is not final: show the Bangla out-of-range message with Refresh (and Force Sale once allowed). */
    data class NeedsDecision(
        val outlet: VisitOutlet,
        val result: GeoVerdictResult,
        val refreshCount: Int,
        val refreshLeft: Boolean,
        val refreshMax: Int,
        val forceSaleAvailable: Boolean,
        val mockWarning: Boolean,
    ) : VisitUiState

    /** The visit row is committed; the sale may continue. */
    data class Open(val visit: OpenVisit) : VisitUiState

    /** Location is off or precise location is denied: the visit screen is blocked until fixed; no Force Sale (docs/24 s11.2). */
    data class LocationBlocked(val reason: String) : VisitUiState

    /** The commit failed (database error): nothing was written; the rep may try again. */
    data class CommitFailed(val outlet: VisitOutlet) : VisitUiState

    /** Mock policy `block_sale`: the visit is recorded and the rep sees "mock location detected". */
    data class Blocked(val visit: OpenVisit) : VisitUiState
}

/** The committed visit that sale screens (android-sr-b) build on. */
data class OpenVisit(
    val visitUuid: String,
    val outletId: Long,
    val routeId: Long,
    val geoVerdict: String,
    val geoAction: String,
    val geoValidated: Boolean,
    val photoValidated: Boolean,
    val forceReasonCode: String?,
    val openedAtIso: String,
)

/** The one place that holds the visit in progress; sale, review, QC and dues screens read it and never open a visit. */
class VisitSession {
    private val state = MutableStateFlow<OpenVisit?>(null)
    val current: StateFlow<OpenVisit?> = state.asStateFlow()
    internal fun set(v: OpenVisit?) { state.value = v }
    fun close() { state.value = null }
}

/**
 * Open visit and geo check (F-SR-017) plus Refresh GPS (F-SR-019) and the Force Sale hand-off (F-SR-018).
 *
 * Decision recorded in docs/status/android-sr-a.md: a visit record carries exactly one fix and one final verdict
 * (contract `DeviceGeoVerdict.action` is sale_allowed, force_sale or blocked), so the row is committed as soon as the
 * verdict is final: at once when in range, after Force Sale or after a block otherwise. A refresh re-reads one fix and
 * re-evaluates in memory; nothing waits on the network, and a kill during a refresh restarts the check.
 */
class VisitFlow(
    private val fixes: LocationFixSource,
    private val metaProvider: CaptureMetaProvider,
    private val committer: VisitCommitter,
    private val session: VisitSession,
    private val settings: () -> GeoSettings = { GeoSettings.DEFAULT },
    private val newUuid: () -> String = ClientIds::newUuid,
    private val nowIso: () -> String,
    /** The visit's order of the day (1-based), supplied by the sale picker. */
    private val nextSequenceNo: () -> Int,
    /** Called after a `blocked` visit is committed: the shell writes its `visit_close` with outcome `abandoned` (s11.2). */
    private val onBlocked: suspend (OpenVisit) -> Unit = {},
    private val openStore: OpenVisitStore = OpenVisitStore.None,
    private val configCheck: ConfigCheck = ConfigCheck.None,
) {
    private val lock = Mutex()
    private var visitUuid: String = ""

    private val ui = MutableStateFlow<VisitUiState>(VisitUiState.Idle)
    val state: StateFlow<VisitUiState> = ui.asStateFlow()

    private var outlet: VisitOutlet? = null
    private var refreshCount = 0
    private var lastFix: FixReading? = null
    private var fixUuid: String? = null

    /** Selecting an outlet: one fix, one verdict, and a committed visit when the verdict is final. */
    suspend fun open(selected: VisitOutlet): VisitUiState = lock.withLock {
        check(session.current.value == null) { "a visit is already open: close it first" }
        outlet = selected
        refreshCount = 0
        fixUuid = null
        visitUuid = newUuid() // minted once per open(): a retried commit reuses it and cannot create a second visit
        // R8: the local OPEN row exists at once, so a kill and relaunch resumes the visit; no outbox record yet.
        runCatching { openStore.begin(visitUuid, selected.outletId, selected.routeId, metaProvider.meta(selected.routeId).capturedAt) }
        ui.value = VisitUiState.ReadingFix
        evaluate(fixes.readFix(PURPOSE_VISIT))
    }

    /** After a relaunch: the OPEN row of today, so the screen can continue the check for the same visit uuid. */
    suspend fun pendingOpenRow(businessDate: String): OpenVisitRow? = openStore.findOpen(businessDate)

    /** Resumes a visit found by [pendingOpenRow]: same visit uuid, a fresh fix, a fresh count of refreshes. */
    suspend fun resume(row: OpenVisitRow, outlet: VisitOutlet): VisitUiState = lock.withLock {
        check(session.current.value == null) { "a visit is already open: close it first" }
        this.outlet = outlet; refreshCount = 0; fixUuid = null; visitUuid = row.visitUuid
        ui.value = VisitUiState.ReadingFix
        evaluate(fixes.readFix(PURPOSE_VISIT))
    }

    /** Refresh GPS: re-read one fix and re-evaluate, capped by `cfg.geo.refresh_max` per outlet. */
    suspend fun refresh(): VisitUiState = lock.withLock {
        val cur = ui.value
        check(cur is VisitUiState.NeedsDecision) { "refresh only applies while the geo check is undecided" }
        if (!cur.refreshLeft) return@withLock cur
        refreshCount += 1
        runCatching { configCheck.checkOnResume() } // F-SYS-092: never blocks, never throws into the flow
        ui.value = VisitUiState.ReadingFix
        evaluate(fixes.readFix(PURPOSE_VISIT, refreshCount))
    }

    /**
     * Force Sale: allowed only when the geo check says so. [reasonCode] is a `force_reason` code (internet_problem or
     * location_change), [photoUuid] the outlet photo (required when `cfg.sale.force_requires_photo`).
     */
    suspend fun forceSale(reasonCode: String, photoUuid: String?): VisitUiState = lock.withLock {
        val cur = ui.value
        check(cur is VisitUiState.NeedsDecision && cur.forceSaleAvailable) { "force sale is not available" }
        require(reasonCode.matches(REASON)) { "reason code shape" }
        require(!settings().forceRequiresPhoto || photoUuid != null) { "an outlet photo is required for a force sale" }
        require(photoUuid == null || ClientIds.isUuidV4(photoUuid)) { "photo id must be a client uuid" }
        commit(cur.outlet, cur.result, GeoAction.FORCE_SALE, reasonCode, photoUuid)
    }

    private suspend fun evaluate(fix: FixReading): VisitUiState {
        val o = checkNotNull(outlet)
        lastFix = fix
        if (fix.status == "permission_denied" || fix.status == "location_off") {
            return VisitUiState.LocationBlocked(fix.status).also { ui.value = it }
        }
        val policy = settings().policyBase.copy(refreshCount = refreshCount)
        val result = GeoVerdicts.verdict(
            fix = FixInput(fix.isOk, fix.lat ?: Double.NaN, fix.lng ?: Double.NaN, fix.accuracyM, fix.isMock),
            outlet = OutletGeo(o.locationUsable, o.lat ?: Double.NaN, o.lng ?: Double.NaN),
            radiusM = o.radiusM, maxAccuracyM = o.maxAccuracyM, policy = policy,
        )
        return when (result.action) {
            GeoAction.SALE_ALLOWED -> commit(o, result, GeoAction.SALE_ALLOWED, null, null)
            GeoAction.BLOCKED -> commit(o, result, GeoAction.BLOCKED, null, null)
            GeoAction.REFRESH_OFFERED, GeoAction.FORCE_SALE -> {
                val st = VisitUiState.NeedsDecision(
                    outlet = o, result = result, refreshCount = refreshCount,
                    refreshLeft = refreshCount < policy.refreshMax, refreshMax = policy.refreshMax,
                    forceSaleAvailable = result.action == GeoAction.FORCE_SALE,
                    mockWarning = result.warnRep,
                )
                ui.value = st
                st
            }
        }
    }

    /** After [VisitUiState.CommitFailed]: tries the same commit again with the same visit and fix ids. */
    suspend fun retryCommit(): VisitUiState = lock.withLock {
        check(ui.value is VisitUiState.CommitFailed) { "nothing to retry" }
        val a = checkNotNull(pending)
        commit(a.o, a.result, a.action, a.reason, a.photoUuid)
    }

    private class Pending(val o: VisitOutlet, val result: GeoVerdictResult, val action: GeoAction, val reason: String?, val photoUuid: String?)
    private var pending: Pending? = null

    private suspend fun commit(o: VisitOutlet, result: GeoVerdictResult, action: GeoAction, reason: String?, photoUuid: String?): VisitUiState {
        pending = Pending(o, result, action, reason, photoUuid)
        val fix = checkNotNull(lastFix)
        val purpose = when {
            action == GeoAction.FORCE_SALE -> "force_sale"
            refreshCount > 0 -> PURPOSE_REFRESH
            else -> PURPOSE_VISIT
        }
        val fixEntity = fix.toEntity(fixUuid ?: newUuid().also { fixUuid = it }, visitUuid, purpose, refreshCount)
        val meta = metaProvider.meta(o.routeId)
        val opened = meta.capturedAt
        val visit = VisitEntity(
            clientUuid = visitUuid, meta = meta, visitKind = o.visitKind, outletId = o.outletId,
            openedAt = opened, sequenceNo = nextSequenceNo(), planned = o.planned, fixClientUuid = fixEntity.clientUuid,
            geoVerdict = result.verdict.wire, geoDistanceM = result.distanceM, geoRadiusMUsed = o.radiusM,
            geoMaxAccuracyMUsed = o.maxAccuracyM, geoLocationBasis = o.locationBasis, geoOutletLat = o.lat, geoOutletLng = o.lng,
            geoAction = action.wire, geoForceReasonCode = reason, geoForcePhotoUuid = photoUuid,
        )
        try {
            committer.commit(visit, fixEntity)
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            val st = VisitUiState.CommitFailed(o)
            ui.value = st
            return st
        }
        val open = OpenVisit(
            visitUuid = visitUuid, outletId = o.outletId, routeId = o.routeId, geoVerdict = result.verdict.wire,
            geoAction = action.wire, geoValidated = result.verdict == GeoVerdict.IN_RANGE,
            photoValidated = photoUuid != null, forceReasonCode = reason, openedAtIso = opened,
        )
        runCatching { openStore.clear(visitUuid) }
        val st = if (action == GeoAction.BLOCKED) VisitUiState.Blocked(open) else VisitUiState.Open(open)
        if (action != GeoAction.BLOCKED) session.set(open)
        ui.value = st
        if (action == GeoAction.BLOCKED) onBlocked(open)
        return st
    }

    companion object {
        const val PURPOSE_VISIT = "visit_open"
        const val PURPOSE_REFRESH = "refresh"
        private val REASON = Regex("^[a-z][a-z0-9_]{1,40}$")
    }
}
