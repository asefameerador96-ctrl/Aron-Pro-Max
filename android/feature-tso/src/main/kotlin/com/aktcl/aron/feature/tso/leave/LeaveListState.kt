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

    data class Cached(val items: List<LeaveItem>, val asOf: String)
}

sealed interface LeaveListUi {
    data object Loading : LeaveListUi
    data class Content(val items: List<LeaveItem>, val asOf: String?, val offlineCached: Boolean) : LeaveListUi
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
        if (cached != null) publish(cached, local, offlineCached = false)
        val fresh = runCatching { source.refresh() }.getOrNull()
        when {
            fresh != null -> publish(fresh, local, offlineCached = false)
            cached != null -> publish(cached, local, offlineCached = true)
            local.isNotEmpty() -> _ui.value = LeaveListUi.Content(orderLeave(local), null, offlineCached = true)
            else -> _ui.value = LeaveListUi.Error
        }
    }

    private fun publish(c: LeaveSource.Cached, local: List<LeaveItem>, offlineCached: Boolean) {
        val merged = mergeLeave(c.items, local)
        _ui.value = if (merged.isEmpty()) LeaveListUi.Empty else LeaveListUi.Content(merged, c.asOf, offlineCached)
    }
}
