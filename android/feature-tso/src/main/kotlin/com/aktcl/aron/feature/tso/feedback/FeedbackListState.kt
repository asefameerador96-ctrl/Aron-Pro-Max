package com.aktcl.aron.feature.tso.feedback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface FeedbackListUi {
    data object Loading : FeedbackListUi
    data class Content(val items: List<FeedbackItem>, val asOf: String?, val offlineCached: Boolean, val refreshing: Boolean = false) : FeedbackListUi
    data object Empty : FeedbackListUi
    data object Error : FeedbackListUi
}

/** F-TSO-029: cached first, then one refresh; offline keeps the cache and says so; no polling. */
class FeedbackListState(private val source: FeedbackSource) {
    private val _ui = MutableStateFlow<FeedbackListUi>(FeedbackListUi.Loading)
    val ui: StateFlow<FeedbackListUi> = _ui.asStateFlow()

    suspend fun load() {
        val cached = runCatching { source.cached() }.getOrNull()
        val local = runCatching { source.unsent() }.getOrDefault(emptyList())
        if (cached != null) publish(cached, local, offline = false, refreshing = true)
        val fresh = runCatching { source.refresh() }.getOrNull()
        when {
            fresh != null -> publish(fresh, local, false)
            cached != null -> publish(cached, local, true)
            local.isNotEmpty() -> _ui.value = FeedbackListUi.Content(mergeFeedback(emptyList(), local), null, true)
            else -> _ui.value = FeedbackListUi.Error
        }
    }

    private fun publish(c: FeedbackSource.Cached, local: List<FeedbackItem>, offline: Boolean, refreshing: Boolean = false) {
        val m = mergeFeedback(c.items, local)
        _ui.value = if (m.isEmpty()) FeedbackListUi.Empty else FeedbackListUi.Content(m, c.asOf, offline, refreshing)
    }
}
