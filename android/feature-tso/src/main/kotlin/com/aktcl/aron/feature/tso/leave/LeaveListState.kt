package com.aktcl.aron.feature.tso.leave

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the leave list comes from. REQUEST: docs/requests/android-tso-read-models.md (the cache table and the network
 * port are implemented in the host once core-database offers them); [cached] never blocks, [refresh] never throws.
 */
interface LeaveSource {
    /** The last list on this phone with its "as of" Dhaka `HH:mm`, or null when none yet. */
    suspend fun cached(): Cached?

    /** Unsent applications waiting in the outbox (F-TSO-009), shown as "not sent yet". */
    suspend fun unsent(): List<LeaveItem>

    /** One online read of `GET /v1/leave`; null when offline or refused (the cached list stays). */
    suspend fun refresh(): Cached?

    /** The bundle code list `leave_type` (empty: the form offers the manual's three types). Offline: the stored bundle. */
    suspend fun leaveTypes(): List<LeaveType> = emptyList()

    /** `cfg.leave.allow_past_days` / `allow_ahead_days` from the stored bundle config (defaults 31 and 365). */
    suspend fun window(): LeaveWindow = LeaveWindow()

    data class Cached(val items: List<LeaveItem>, val asOf: String)
}

sealed interface LeaveListUi {
    data object Loading : LeaveListUi
    /** [refreshing]: the list is the cached one and the online read has not answered yet (its "as of" is the cache's, not now). */
    data class Content(val items: List<LeaveItem>, val asOf: String?, val offlineCached: Boolean, val refreshing: Boolean = false) : LeaveListUi
    data object Empty : LeaveListUi
    data object Error : LeaveListUi
}

/** Plain state holder (no Android types): cached first, then one refresh; no polling timer (F-TSO-027). */
class LeaveListState(private val source: LeaveSource) {
    private val _ui = MutableStateFlow<LeaveListUi>(LeaveListUi.Loading)
    val ui: StateFlow<LeaveListUi> = _ui.asStateFlow()

    suspend fun load() {
        val cached = runCatching { source.cached() }.getOrNull()
        val local = runCatching { source.unsent() }.getOrDefault(emptyList())
        if (cached != null) publish(cached, local, offlineCached = false, refreshing = true)
        val fresh = runCatching { source.refresh() }.getOrNull()
        when {
            fresh != null -> publish(fresh, local, offlineCached = false)
            cached != null -> publish(cached, local, offlineCached = true)
            local.isNotEmpty() -> _ui.value = LeaveListUi.Content(orderLeave(local), null, offlineCached = true)
            else -> _ui.value = LeaveListUi.Error
        }
    }

    private fun publish(c: LeaveSource.Cached, local: List<LeaveItem>, offlineCached: Boolean, refreshing: Boolean = false) {
        val merged = mergeLeave(c.items, local)
        _ui.value = if (merged.isEmpty()) LeaveListUi.Empty else LeaveListUi.Content(merged, c.asOf, offlineCached, refreshing)
    }
}
