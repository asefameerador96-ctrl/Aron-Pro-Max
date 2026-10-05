package com.aktcl.aron.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aktcl.aron.core.network.ApiResult
import com.aktcl.aron.core.network.BundleDownload
import com.aktcl.aron.rules.BusinessDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Day-1 home placeholder knows about today's bundle. Nothing on the screen waits for it. */
sealed interface BundleStatus {
    data object Checking : BundleStatus
    data class Ready(val bundleVersion: String, val validFor: String) : BundleStatus
    data object Unchanged : BundleStatus
    /** No network or the server could not answer: the day goes on offline. */
    data object Offline : BundleStatus
    data class Refused(val httpStatus: Int, val code: String?) : BundleStatus
}

data class HomeUiState(val businessDate: String, val bundle: BundleStatus)

/**
 * Day-1 home placeholder (N-001): shows who is logged in, the Dhaka business date and whether today's bundle head
 * could be read. The bundle is applied to Room by F-SYS-006 (Day 2); this only proves the authenticated call.
 */
class HomePlaceholderViewModel(
    nowMs: () -> Long,
    private val fetchBundle: suspend () -> ApiResult<BundleDownload>,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState(BusinessDate.of(nowMs()).toString(), BundleStatus.Checking))
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(bundle = BundleStatus.Checking) }
        viewModelScope.launch {
            val status = when (val r = runCatching { fetchBundle() }.getOrNull()) {
                is ApiResult.Success -> BundleStatus.Ready(r.value.head.meta.bundleVersion, r.value.head.meta.validForBusinessDate)
                is ApiResult.NotModified -> BundleStatus.Unchanged
                is ApiResult.Failure -> if (r.httpStatus >= 500 || r.httpStatus == 429) BundleStatus.Offline else BundleStatus.Refused(r.httpStatus, r.problem.code)
                is ApiResult.Transport, null -> BundleStatus.Offline
            }
            _state.update { it.copy(bundle = status) }
        }
    }
}
