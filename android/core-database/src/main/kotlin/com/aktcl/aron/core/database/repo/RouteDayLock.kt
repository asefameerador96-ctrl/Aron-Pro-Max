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
 * tso-manual A3 (D-TSO-MAN-31): the zone-day's Final Submit also closes the route-day. The phone learns it from the
 * route-day state the server sends ([FinalSubmitLock]); from then on a capture of that route-day is refused here (the server
 * would reject it `day_final_submitted`), while a print still goes through: the print ledger puts the print of a closed
 * route-day in its own family without a route, as it does after a Sales Submit.
 */
internal object RouteDayLock {
    suspend fun isOpen(db: AronDatabase, meta: CaptureMeta): Boolean {
        // tso-manual A3: a route-day closed by its zone's Final Submit takes no more captures (D-TSO-MAN-31)
        if (meta.routeId != null && FinalSubmitLock.isFinal(db, meta.businessDate, meta.routeId)) return false
        if (!routeOpen(db, meta.businessDate, meta.routeId)) return false
        // An AMO's supervisor-day submit (no route) closes the whole day: records that carry a route are refused too.
        return meta.routeId == null || supervisorOpen(db, meta.businessDate)
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
