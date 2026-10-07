package com.aktcl.aron.feature.home

/** A route planned for the date (F-SR-065). */
data class PlannedRoute(val routeId: Long, val name: String, val plannedToday: Boolean, val sequenceNo: Int?)

/** With several routes planned the SR picks one; one bundle serves all, and the header names the one in use. */
object RoutePicker {
    fun candidates(routes: List<PlannedRoute>): List<PlannedRoute> =
        routes.filter { it.plannedToday }.sortedWith(compareBy(nullsLast<Int>()) { it.sequenceNo }.thenBy { it.routeId })

    /** No prompt for zero or one candidate; the saved choice of the date wins when it is still a candidate. */
    fun needsChoice(routes: List<PlannedRoute>, savedRouteId: Long?): Boolean {
        val c = candidates(routes)
        return c.size > 1 && c.none { it.routeId == savedRouteId }
    }

    fun inUse(routes: List<PlannedRoute>, savedRouteId: Long?): PlannedRoute? {
        val c = candidates(routes)
        return c.firstOrNull { it.routeId == savedRouteId } ?: c.singleOrNull()
    }
}
