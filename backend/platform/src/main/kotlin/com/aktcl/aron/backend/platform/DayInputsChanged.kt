package com.aktcl.aron.backend.platform

import org.jdbi.v3.core.Handle
import java.time.LocalDate

/**
 * An event that changes what a route-day's derived counters are computed from (a data void of its rows, a Final Submit reopen) tells the
 * owner of the counters, in the SAME transaction, to recount them (review B4, R1). Declared here so a module that voids or reopens does not depend on
 * the sync module's internals; the sync module implements it (`DayStates.recountLateAfterSubmit`) and the wiring passes it in.
 */
fun interface DayInputsChanged {
    fun changed(h: Handle, routeIds: List<Long>, date: LocalDate)
}
