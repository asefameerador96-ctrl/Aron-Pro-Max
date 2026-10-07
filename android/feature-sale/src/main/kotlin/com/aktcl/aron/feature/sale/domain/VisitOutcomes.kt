package com.aktcl.aron.feature.sale.domain

import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.VisitCloseEntity
import com.aktcl.aron.core.database.repo.CaptureRepository

/** How a visit ended (contract `VisitOutcome`, F-SR-057). Wire names are the contract's. */
enum class VisitOutcomeCode(val wire: String, val countsAsVisited: Boolean, val isNoSale: Boolean) {
    SOLD("sold", true, false),
    ZERO_SALE("zero_sale_stock_ok", true, true),
    CLOSED("closed", true, true),
    OWNER_ABSENT("owner_absent", true, true),
    REFUSED("refused", true, true),
    COMPETITOR_EXCLUSIVE("competitor_exclusive", true, true),
    NOT_REACHED("not_reached", true, true),
    /** A blocked or dropped call: kept for the audit trail, never counted as a visit (F-SR-057). */
    ABANDONED("abandoned", false, true),
}

/** The visit being closed, as the visit session hands it over (see `OpenVisit` in feature-outlet). */
data class VisitToClose(val visitUuid: String, val routeId: Long, val callStartedAtIso: String?)

/**
 * Writes the one `visit_close` of a visit with its outcome, in one transaction with its outbox record, and leaves the
 * caller to close its `VisitSession`. Closing twice is refused by the unique visit index, so a retry cannot double.
 */
class VisitCloser(
    private val repo: CaptureRepository,
    private val metaSource: CaptureMetaSource,
    private val nowIso: () -> String,
    private val newUuid: () -> String = ClientIds::newUuid,
) {
    suspend fun close(visit: VisitToClose, outcome: VisitOutcomeCode, businessDate: String, callDeclined: Boolean = false) {
        val meta: CaptureMeta = metaSource.meta(businessDate, visit.routeId)
        repo.recordVisitClose(
            VisitCloseEntity(
                clientUuid = newUuid(), meta = meta, visitClientUuid = visit.visitUuid, outcomeCode = outcome.wire,
                callStartedAt = visit.callStartedAtIso, callDeclined = callDeclined, endedAt = nowIso(),
                isZeroSale = outcome == VisitOutcomeCode.ZERO_SALE,
            ),
        )
    }
}

/** A closed visit as recorded: used by the day counts and the closed-outlet streak. */
data class ClosedVisit(val outletId: Long, val businessDate: String, val outcome: String)

object VisitStats {
    /** Visits that count toward "outlets visited": every outcome except abandoned; an outlet is counted once. */
    fun visitedOutlets(visits: List<ClosedVisit>): Int =
        visits.filter { v -> VisitOutcomeCode.entries.firstOrNull { it.wire == v.outcome }?.countsAsVisited == true }.map { it.outletId }.toSet().size

    /** Outlets that were visited and did not buy (any no-sale outcome except abandoned). */
    fun noSaleOutlets(visits: List<ClosedVisit>): Int {
        val sold = visits.filter { it.outcome == VisitOutcomeCode.SOLD.wire }.map { it.outletId }.toSet()
        return visits.filter { v -> VisitOutcomeCode.entries.firstOrNull { it.wire == v.outcome }.let { it != null && it.countsAsVisited && it.isNoSale } }
            .map { it.outletId }.toSet().count { it !in sold }
    }

    /**
     * Outlets whose [threshold] most recent visits (by business date, newest first) were all `closed`: the AMO gets a task
     * (F-SR-057, default 3). Abandoned visits are skipped, they say nothing about the shop.
     */
    fun closedStreakOutlets(history: List<ClosedVisit>, threshold: Int = 3): Set<Long> =
        history.filter { it.outcome != VisitOutcomeCode.ABANDONED.wire }.groupBy { it.outletId }.filter { (_, vs) ->
            val recent = vs.sortedByDescending { it.businessDate }.take(threshold)
            recent.size == threshold && recent.all { it.outcome == VisitOutcomeCode.CLOSED.wire }
        }.keys
}
