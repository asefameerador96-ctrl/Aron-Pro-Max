package com.aktcl.aron.core.database.repo

import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.OutboxState
import com.aktcl.aron.core.database.entity.SyncMetaEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Sales Submit closes a route-day (docs/24 s4.2 rule 2: `day_submit` is its last record); the server may reopen it (a
 * submit void), recorded with [reopen]. Shared by captures and the print ledger.
 *
 * tso-manual A3 (D-TSO-MAN-31, D-A3-22): the zone-day's Final Submit closes the route-day too, but only for the record
 * types the server refuses after the lock ([FinalSubmitLock.LOCKED_TYPES]); a `day_submit`, an attendance event, a print
 * or an AMO record still goes through, as the server accepts them. The lock applies only when the caller names its record
 * type ([isOpen] with `recordType`, [requireOpen]). A caller that does not (the print ledger) is not checked against Final
 * Submit. tso-manual X4a (R-W-11): CaptureRepository names the type for visit, memo, due_collection, visit_skip and
 * visit_close, and the SR and AMO screens catch [RouteDayFinalSubmittedException] and show the lock text. Follow-up
 * (R-W-22, R-W-23): stock_movement is named too (the Final Submit part only), and the screens catch
 * [RouteDaySubmittedException] as well ([DayRefusal]) and word it with the submit text.
 *
 * Owner decision 2026-11-04 (D-TSO-MAN-49): the Final Submit part applies only while the late-sale policy in force is
 * quarantine or reject ([FinalSubmitLock.lockApplies]); under accept_and_flag (the shipped default) a final-submitted
 * route-day takes every capture as before and the server accepts and flags it. [nowIso] is the trusted time the policy is
 * read at (null: the capture's own `captured_at`). The Sales Submit part is unchanged.
 */
internal object RouteDayLock {
    suspend fun isOpen(db: AronDatabase, meta: CaptureMeta, recordType: String? = null, nowIso: String? = null): Boolean {
        // tso-manual A3: a route-day closed by its zone's Final Submit takes no more captures of the locked types (D-TSO-MAN-31)
        if (recordType != null && FinalSubmitLock.refuses(db, meta, recordType, nowIso ?: meta.capturedAt)) return false
        if (!routeOpen(db, meta.businessDate, meta.routeId)) return false
        // An AMO's supervisor-day submit (no route) closes the whole day: records that carry a route are refused too.
        return meta.routeId == null || supervisorOpen(db, meta.businessDate)
    }

    /**
     * tso-manual A3: refuses a capture of [recordType] with [meta] when its route-day is closed, by the zone's Final Submit
     * ([RouteDayFinalSubmittedException], which a screen catches and words as the lock) or by a Sales Submit
     * ([RouteDaySubmittedException], an IllegalStateException as before). CaptureRepository's requireOpenRouteDay (tso-manual X4a).
     */
    suspend fun requireOpen(db: AronDatabase, meta: CaptureMeta, recordType: String?, nowIso: String? = null) {
        when (refusal(db, meta, recordType, nowIso)) {
            DayRefusal.FINAL_SUBMIT -> throw RouteDayFinalSubmittedException(meta.businessDate, checkNotNull(meta.routeId), checkNotNull(recordType))
            DayRefusal.SALES_SUBMIT -> throw RouteDaySubmittedException(meta.businessDate, meta.routeId)
            null -> Unit
        }
    }

    /**
     * tso-manual X4a (R-W-23): why [requireOpen] would refuse a capture of [recordType] with [meta], or null when it would
     * pass. Pure reads, no write; the one rule both use, so a screen's check and the write cannot disagree.
     */
    suspend fun refusal(db: AronDatabase, meta: CaptureMeta, recordType: String?, nowIso: String? = null): DayRefusal? = when {
        recordType != null && FinalSubmitLock.refuses(db, meta, recordType, nowIso ?: meta.capturedAt) -> DayRefusal.FINAL_SUBMIT
        !isOpen(db, meta) -> DayRefusal.SALES_SUBMIT
        else -> null
    }

    private suspend fun routeOpen(db: AronDatabase, businessDate: String, routeId: Long?): Boolean {
        val latest = db.captureDao().daySubmitsFor(businessDate, routeId).maxOfOrNull { it.submitCycle } ?: return true
        val reopened = db.referenceDao().meta(key(businessDate, routeId))?.toIntOrNull() ?: 0
        return latest <= reopened
    }

    private suspend fun supervisorOpen(db: AronDatabase, businessDate: String): Boolean {
        val latest = db.captureDao().daySubmitsFor(businessDate, null).filter { it.scope == "supervisor_day" }.maxOfOrNull { it.submitCycle } ?: return true
        val reopened = db.referenceDao().meta(key(businessDate, null))?.toIntOrNull() ?: 0
        return latest <= reopened
    }

    /** True while a submit of the date stands: some route-day (or the supervisor day) has a cycle the server has not voided. */
    suspend fun submitStands(db: AronDatabase, businessDate: String): Boolean =
        db.captureDao().daySubmitsOn(businessDate).groupBy { it.meta.routeId }.any { (route, rows) ->
            rows.maxOf { it.submitCycle } > (db.referenceDao().meta(key(businessDate, route))?.toIntOrNull() ?: 0)
        }

    suspend fun reopen(db: AronDatabase, businessDate: String, routeId: Long?, voidedCycle: Int) =
        db.referenceDao().putMeta(SyncMetaEntity(key(businessDate, routeId), voidedCycle.toString()))

    private fun key(businessDate: String, routeId: Long?) = "route_day.reopened.$businessDate.${routeId ?: "none"}"
}

/**
 * The Final Submit lock of a route-day on this phone (tso-manual A3, D-TSO-MAN-31). A route-day is final when the server's
 * `RouteDayState` for that route and business date says `final_submitted` (or carries `final_submitted_at`) in either
 * place the phone keeps one:
 *
 * - the held route section `route.<id>` (`day_state` of the bundle's `RouteSnapshot`, replaced by every delta's
 *   `day_states`; the push `day_final_submitted` is a `sync_nudge`, which pulls that delta), and
 * - the last sync answer's `day_states` (sync meta `sync.day_states`, written by the sync engine on every answered batch),
 *   so a lost push still locks the day at the next sync.
 *
 * A reopen (D-TSO-MAN-29) clears it when both sources have moved on (the next sync answer and the next delta). Pure reads,
 * no network, never throws: an unreadable source counts as "not final".
 *
 * Owner decision 2026-11-04 (D-TSO-MAN-49): whether the phone acts on a final route-day at all is ONE predicate,
 * [lockApplies]: the late-sale policy `cfg.day.late_sync_after_final_policy` in force (in the device bundle since V0142) is
 * quarantine or reject. Under accept_and_flag (the shipped default), with the key absent or unreadable (the server's own
 * fallback), nothing on the phone changes after a Final Submit. Every lock use goes through it: [refuses] (and so
 * RouteDayLock, CaptureRepository's checks, the screens' refusals, Mark Paid, the stock slip filter and the call guards),
 * [locks] (the banners, the check-out gate, the AMO's route check) and [lockedRoutes] (the AMO banner). [isFinal] and
 * [finalRoutes] stay the plain fact the phone learned and decide nothing on their own.
 */
object FinalSubmitLock {
    /** Sync meta key of the last sync answer's route-day states (the sync engine's `SyncEngine.KEY_DAY_STATES`). */
    const val KEY_SYNC_DAY_STATES = "sync.day_states"

    /** The late-sale policy key (V0006; delivered to the phones by V0142, owner decision 2026-11-04). */
    const val KEY_LATE_POLICY = "cfg.day.late_sync_after_final_policy"

    /**
     * The policies under which the server holds or refuses a late capture of a final route-day, so the phone locks it
     * (V0006 bounds: accept_and_flag, quarantine, reject). accept_and_flag is not among them.
     */
    val LOCKING_POLICIES: Set<String> = setOf("quarantine", "reject")

    /**
     * THE predicate of the phone's Final Submit lock (owner decision 2026-11-04, D-TSO-MAN-49): true only when the late-sale
     * policy in force at [nowIso] (trusted time; the bundle's scheduled value whose window holds it, else the resolved one
     * while its own window lasts) is quarantine or reject. False for accept_and_flag, and false when the key is absent or
     * its value unreadable, as the server falls back to accept_and_flag (IngestService). X4a fix round (checker medium): a
     * time-limited quarantine or reject stops locking at its `effective_to`, as the server stops applying it there
     * ([ReferenceRepository.configInForce]; the key is global-only, so what follows it is the registry default,
     * accept_and_flag, unless a scheduled value says otherwise). Pure reads, never throws (a failed read gives false; a CANCELLATION of the caller still propagates, D-AMO-CALLS-38).
     */
    suspend fun lockApplies(db: AronDatabase, nowIso: String): Boolean = com.aktcl.aron.core.common.orDefaultKeepingCancellation(false) {
        val text = ReferenceRepository(db).configInForce(KEY_LATE_POLICY, nowIso) ?: return false
        val policy = (json.parseToJsonElement(text) as? JsonPrimitive)?.takeIf { it.isString }?.content
        policy in LOCKING_POLICIES
    }

    /**
     * True when the phone locks the route-day [routeId] on [businessDate]: the lock applies ([lockApplies] at [nowIso]) and
     * the route-day is closed by its zone's Final Submit ([isFinal]). The SR banner, the SR check-out gate and the AMO's
     * route check read it. Pure reads, never throws.
     */
    suspend fun locks(db: AronDatabase, businessDate: String, routeId: Long, nowIso: String): Boolean =
        com.aktcl.aron.core.common.orDefaultKeepingCancellation(false) { lockApplies(db, nowIso) && isFinal(db, businessDate, routeId) }

    /** The routes among [routeIds] the phone locks on [businessDate] ([locks]); empty while the lock does not apply. */
    suspend fun lockedRoutes(db: AronDatabase, businessDate: String, routeIds: List<Long>, nowIso: String): List<Long> =
        if (!lockApplies(db, nowIso)) emptyList() else finalRoutes(db, businessDate, routeIds)

    /** Outcome codes of records that met the lock on the server (contract RecordOutcomeCode, 1.10.0). */
    const val HELD = "after_final_submit"
    const val REFUSED = "day_final_submitted"

    /**
     * The record types the server holds (`after_final_submit`, captured before the lock) or refuses (`day_final_submitted`,
     * captured at or after it) on a route-day closed by Final Submit: B3's IngestService.DAY_LOCKED_TYPES (the call, the sale
     * and its edits, voids, returns, collections and stock). State records stay accepted there and are never refused here:
     * `day_submit`, `day_open`, `attendance_event`, `print_event`, `amo_record` and telemetry.
     */
    val LOCKED_TYPES: Set<String> = setOf(
        "visit", "visit_close", "visit_skip", "sale_abort", "memo", "memo_line", "memo_discount", "qc_line", "memo_void", "due_collection",
        "stock_movement", "redemption", "redemption_line",
    )

    /**
     * True when a capture of [recordType] with [meta] meets the lock: the lock applies ([lockApplies] at [nowIso], by default
     * the capture's own trusted `captured_at`) and a locked type ([LOCKED_TYPES]) is captured on a route-day closed by its
     * zone's Final Submit. A routeless record never does. Pure reads, never throws.
     */
    suspend fun refuses(db: AronDatabase, meta: CaptureMeta, recordType: String, nowIso: String = meta.capturedAt): Boolean {
        val route = meta.routeId ?: return false
        if (recordType !in LOCKED_TYPES) return false
        // owner decision 2026-11-04: accept_and_flag (the default), an absent or unreadable policy: the phone never locks
        if (!lockApplies(db, nowIso)) return false
        return isFinal(db, meta.businessDate, route)
    }

    /**
     * Refuses a new call ([com.aktcl.aron.core.database.entity.VisitEntity] about to be opened with [meta]) on a route-day
     * closed by Final Submit: the SR and AMO days call it before the visit is stored, and their visit flow shows the refusal
     * as a failed commit (nothing is stored, nothing crashes).
     */
    suspend fun requireCallOpen(db: AronDatabase, meta: CaptureMeta) {
        if (refuses(db, meta, "visit")) throw RouteDayFinalSubmittedException(meta.businessDate, checkNotNull(meta.routeId), "visit")
    }

    /**
     * The call to restore after a relaunch: the last visit of [businessDate] without a close that [eligible] accepts, or
     * null. A call whose route-day is closed by Final Submit is not restored: it cannot go on (no sale, no close would be
     * accepted), so the rep is not taken back into it on every launch. A reopen of the day brings it back.
     */
    suspend fun callToRestore(
        db: AronDatabase, businessDate: String, nowIso: String? = null, eligible: (com.aktcl.aron.core.database.entity.VisitEntity) -> Boolean,
    ): com.aktcl.aron.core.database.entity.VisitEntity? {
        val dao = db.captureDao()
        val closed = dao.visitClosesOn(businessDate).map { it.visitClientUuid }.toSet()
        val last = dao.visitsOn(businessDate).filter { it.clientUuid !in closed && eligible(it) }.maxByOrNull { it.sequenceNo } ?: return null
        return last.takeUnless { refuses(db, it.meta, "visit_close", nowIso ?: it.meta.capturedAt) }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * True when the route-day [routeId] on [businessDate] is closed by its zone's Final Submit: the fact the phone learned,
     * whatever the policy. It decides no lock on its own: [locks] and [refuses] add [lockApplies].
     */
    suspend fun isFinal(db: AronDatabase, businessDate: String, routeId: Long): Boolean =
        sectionFinal(db, businessDate, routeId) || syncFinal(db, businessDate, routeId)

    /**
     * The routes among [routeIds] whose route-day on [businessDate] is closed by Final Submit (in the given order): the fact,
     * as [isFinal]; the AMO banner reads [lockedRoutes].
     */
    suspend fun finalRoutes(db: AronDatabase, businessDate: String, routeIds: List<Long>): List<Long> {
        val fromSync = syncStates(db).filter { isFinalState(it, businessDate) }.mapNotNull { it.long("route_id") }.toSet()
        return routeIds.distinct().filter { it in fromSync || sectionFinal(db, businessDate, it) }
    }

    /**
     * The records of [businessDate] that met the lock on the server: held for the admin (`after_final_submit`, captured
     * before the lock, counted only when accepted) and refused (`day_final_submitted`, captured after it). The SR and AMO
     * day banners show them, so a rep knows a sale was not lost and why it does not count yet.
     */
    suspend fun metLock(db: AronDatabase, businessDate: String): LockAcks = com.aktcl.aron.core.common.orDefaultKeepingCancellation(LockAcks(0, 0)) {
        val box = db.outboxDao()
        val rows = box.refusedUuids(businessDate).mapNotNull { box.byClientUuid(it) }.filter { it.businessDate == businessDate }
        LockAcks(
            held = rows.count { it.state == OutboxState.QUARANTINED && it.lastCode == HELD },
            refused = rows.count { it.state == OutboxState.REJECTED && it.lastCode == REFUSED },
        )
    }

    private suspend fun sectionFinal(db: AronDatabase, businessDate: String, routeId: Long): Boolean = com.aktcl.aron.core.common.orDefaultKeepingCancellation(false) {
        val text = ReferenceRepository(db).section("route.$routeId") ?: return false
        val day = (json.parseToJsonElement(text) as? JsonObject)?.get("day_state") as? JsonObject ?: return false
        day.long("route_id").let { it == null || it == routeId } && isFinalState(day, businessDate)
    }

    private suspend fun syncFinal(db: AronDatabase, businessDate: String, routeId: Long): Boolean =
        syncStates(db).any { it.long("route_id") == routeId && isFinalState(it, businessDate) }

    private suspend fun syncStates(db: AronDatabase): List<JsonObject> = com.aktcl.aron.core.common.orDefaultKeepingCancellation(emptyList()) {
        val text = db.referenceDao().meta(KEY_SYNC_DAY_STATES) ?: return emptyList()
        (json.parseToJsonElement(text) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    }

    private fun isFinalState(o: JsonObject, businessDate: String): Boolean =
        o.str("business_date") == businessDate && (o.str("state") == "final_submitted" || o.str("final_submitted_at") != null)

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toLongOrNull()
}

/** How many of a day's records met the Final Submit lock on the server ([FinalSubmitLock.metLock]). */
data class LockAcks(val held: Int, val refused: Int) {
    val any: Boolean get() = held > 0 || refused > 0
}

/**
 * A capture refused on this phone because its route-day is closed by the zone's Final Submit (tso-manual A3, D-TSO-MAN-31):
 * the server would refuse it `day_final_submitted`. It is an IllegalStateException, so a caller that already turns a failed
 * save into words (the visit flow, the sale) shows it as such; a caller that names the lock catches this type.
 */
class RouteDayFinalSubmittedException(val businessDate: String, val routeId: Long, val recordType: String) :
    IllegalStateException("route $routeId on $businessDate is closed by its zone's Final Submit; no $recordType is captured")

/**
 * A capture refused on this phone because its route-day (or the AMO's supervisor day) is closed by a Sales Submit the
 * server has not voided (docs/24 s4.2 rule 2). The same IllegalStateException and message as before; tso-manual X4a (fix
 * round) names the type so a screen that can meet it (Mark Paid after the rep's own Sales Submit) words it and never crashes.
 */
class RouteDaySubmittedException(val businessDate: String, val routeId: Long?) :
    IllegalStateException("route-day $businessDate is submitted; nothing more can be captured")

/**
 * tso-manual X4a (R-W-23): why this phone refused a capture of a route-day, so the screen that launched it words it and
 * never throws: the zone's Final Submit ([RouteDayFinalSubmittedException]; the lock text) or a Sales Submit that stands,
 * the rep's own or the AMO's supervisor day ([RouteDaySubmittedException]; feature-dayclose's `submit_blocked_done`).
 */
enum class DayRefusal {
    FINAL_SUBMIT,
    SALES_SUBMIT,
    ;

    companion object {
        /** The refusal [t] carries, or null for any other failure (and for none). */
        fun of(t: Throwable?): DayRefusal? = when (t) {
            is RouteDayFinalSubmittedException -> FINAL_SUBMIT
            is RouteDaySubmittedException -> SALES_SUBMIT
            else -> null
        }
    }
}
