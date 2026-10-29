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
 * type ([isOpen] with `recordType`, [requireOpen]). A caller that does not (CaptureRepository's requireOpenRouteDay until it
 * passes the type, the print ledger) is not checked against Final Submit, so nothing throws for the lock in a screen that
 * does not catch it; the SR and AMO days refuse a new call on a closed route-day themselves (SrDay, AmoDay).
 */
internal object RouteDayLock {
    suspend fun isOpen(db: AronDatabase, meta: CaptureMeta, recordType: String? = null): Boolean {
        // tso-manual A3: a route-day closed by its zone's Final Submit takes no more captures of the locked types (D-TSO-MAN-31)
        if (recordType != null && FinalSubmitLock.refuses(db, meta, recordType)) return false
        if (!routeOpen(db, meta.businessDate, meta.routeId)) return false
        // An AMO's supervisor-day submit (no route) closes the whole day: records that carry a route are refused too.
        return meta.routeId == null || supervisorOpen(db, meta.businessDate)
    }

    /**
     * tso-manual A3: refuses a capture of [recordType] with [meta] when its route-day is closed, by the zone's Final Submit
     * ([RouteDayFinalSubmittedException], which a screen catches and words as the lock) or by a Sales Submit
     * (IllegalStateException, as before). The drop-in for CaptureRepository's requireOpenRouteDay once it passes the type.
     */
    suspend fun requireOpen(db: AronDatabase, meta: CaptureMeta, recordType: String?) {
        if (recordType != null && FinalSubmitLock.refuses(db, meta, recordType)) {
            throw RouteDayFinalSubmittedException(meta.businessDate, checkNotNull(meta.routeId), recordType)
        }
        check(isOpen(db, meta)) { "route-day ${meta.businessDate} is submitted; nothing more can be captured" }
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
 */
object FinalSubmitLock {
    /** Sync meta key of the last sync answer's route-day states (the sync engine's `SyncEngine.KEY_DAY_STATES`). */
    const val KEY_SYNC_DAY_STATES = "sync.day_states"

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
     * True when a capture of [recordType] with [meta] meets the lock: a locked type ([LOCKED_TYPES]) on a route-day closed by
     * its zone's Final Submit. A routeless record never does. Pure reads, never throws.
     */
    suspend fun refuses(db: AronDatabase, meta: CaptureMeta, recordType: String): Boolean {
        val route = meta.routeId ?: return false
        return recordType in LOCKED_TYPES && isFinal(db, meta.businessDate, route)
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
    suspend fun callToRestore(db: AronDatabase, businessDate: String, eligible: (com.aktcl.aron.core.database.entity.VisitEntity) -> Boolean): com.aktcl.aron.core.database.entity.VisitEntity? {
        val dao = db.captureDao()
        val closed = dao.visitClosesOn(businessDate).map { it.visitClientUuid }.toSet()
        val last = dao.visitsOn(businessDate).filter { it.clientUuid !in closed && eligible(it) }.maxByOrNull { it.sequenceNo } ?: return null
        return last.takeUnless { refuses(db, it.meta, "visit_close") }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** True when the route-day [routeId] on [businessDate] is closed by its zone's Final Submit. */
    suspend fun isFinal(db: AronDatabase, businessDate: String, routeId: Long): Boolean =
        sectionFinal(db, businessDate, routeId) || syncFinal(db, businessDate, routeId)

    /** The routes among [routeIds] whose route-day on [businessDate] is closed by Final Submit (in the given order). */
    suspend fun finalRoutes(db: AronDatabase, businessDate: String, routeIds: List<Long>): List<Long> {
        val fromSync = syncStates(db).filter { isFinalState(it, businessDate) }.mapNotNull { it.long("route_id") }.toSet()
        return routeIds.distinct().filter { it in fromSync || sectionFinal(db, businessDate, it) }
    }

    /**
     * The records of [businessDate] that met the lock on the server: held for the admin (`after_final_submit`, captured
     * before the lock, counted only when accepted) and refused (`day_final_submitted`, captured after it). The SR and AMO
     * day banners show them, so a rep knows a sale was not lost and why it does not count yet.
     */
    suspend fun metLock(db: AronDatabase, businessDate: String): LockAcks = runCatching {
        val box = db.outboxDao()
        val rows = box.refusedUuids(businessDate).mapNotNull { box.byClientUuid(it) }.filter { it.businessDate == businessDate }
        LockAcks(
            held = rows.count { it.state == OutboxState.QUARANTINED && it.lastCode == HELD },
            refused = rows.count { it.state == OutboxState.REJECTED && it.lastCode == REFUSED },
        )
    }.getOrDefault(LockAcks(0, 0))

    private suspend fun sectionFinal(db: AronDatabase, businessDate: String, routeId: Long): Boolean = runCatching {
        val text = ReferenceRepository(db).section("route.$routeId") ?: return false
        val day = (json.parseToJsonElement(text) as? JsonObject)?.get("day_state") as? JsonObject ?: return false
        day.long("route_id").let { it == null || it == routeId } && isFinalState(day, businessDate)
    }.getOrDefault(false)

    private suspend fun syncFinal(db: AronDatabase, businessDate: String, routeId: Long): Boolean =
        syncStates(db).any { it.long("route_id") == routeId && isFinalState(it, businessDate) }

    private suspend fun syncStates(db: AronDatabase): List<JsonObject> = runCatching {
        val text = db.referenceDao().meta(KEY_SYNC_DAY_STATES) ?: return emptyList()
        (json.parseToJsonElement(text) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    }.getOrDefault(emptyList())

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
