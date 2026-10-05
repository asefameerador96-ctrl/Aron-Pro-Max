package com.aktcl.aron.backend.platform

import com.aktcl.aron.contract.Role
import java.time.LocalDate

/** A top reach node for the login and /me scope summary (contract NodeRef; `id` 0 for national). */
data class ReachNode(val type: String, val id: Long, val code: String? = null, val name: String? = null)

/**
 * Server-side reach of a user on a business date (docs/24 s8.4), computed from role, `user_scope` and
 * `route_assignment`. The client never sends scope ids. Implemented in backend:masterdata.
 */
data class Reach(
    val userId: Long,
    val role: Role,
    val businessDate: LocalDate,
    /** National reach (DMO..SUPERADMIN at the top of the tree, ANALYST, SUPPORT, ADMIN, SUPERADMIN, TOP). */
    val national: Boolean,
    val zoneIds: Set<Long>,
    val routeIds: Set<Long>,
    /** SR: only own records and the outlets of assigned routes. */
    val ownRecordsOnly: Boolean,
    val topNodes: List<ReachNode>,
) {
    fun coversZone(zoneId: Long): Boolean = national || zoneId in zoneIds
    fun coversRoute(routeId: Long, routeZoneId: Long): Boolean =
        if (ownRecordsOnly) routeId in routeIds else national || routeZoneId in zoneIds || routeId in routeIds
}

fun interface ReachResolver {
    fun reach(userId: Long, role: Role, scopeVersion: Long, businessDate: LocalDate): Reach
}
