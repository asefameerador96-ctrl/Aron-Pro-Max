package com.aktcl.aron.feature.tso.leave

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronCard
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronErrorState
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronScreen
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.AronTopBar
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.feature.tso.R
import kotlinx.coroutines.launch

object LeaveTags {
    const val LIST = "tso_leave_list"
    const val EMPTY = "tso_leave_empty"
    const val ERROR = "tso_leave_error"
    const val OFFLINE = "tso_leave_offline"
    const val ASOF = "tso_leave_asof"
    const val APPLY = "tso_leave_apply"
    fun card(uuid: String) = "tso_leave_card_$uuid"
    fun badge(uuid: String) = "tso_leave_badge_$uuid"
    fun unsent(uuid: String) = "tso_leave_unsent_$uuid"
}

/** F-TSO-008: the leave list. Cards show the period, days, reason and a status badge that names the approver role. */
@Composable
fun LeaveListScreen(state: LeaveListState, onBack: () -> Unit, modifier: Modifier = Modifier, onApply: (() -> Unit)? = null) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val ui by state.ui.collectAsStateWithLifecycle()
    LaunchedEffect(state) { state.load() }
    AronScreen(
        modifier, topBar = { AronTopBar(stringResource(R.string.tso_leave_title), onBack = onBack) },
        bottomBar = { if (onApply != null) com.aktcl.aron.core.ui.AronBottomBar(primary = com.aktcl.aron.core.ui.AronBarAction(stringResource(R.string.tso_leave_apply), onApply), modifier = Modifier.testTag(LeaveTags.APPLY)) },
    ) {
        when (val u = ui) {
            LeaveListUi.Loading -> Column(Modifier.fillMaxSize()) { }
            LeaveListUi.Empty -> AronEmptyState(stringResource(R.string.tso_leave_empty), Modifier.testTag(LeaveTags.EMPTY), hint = stringResource(R.string.tso_leave_empty_hint), icon = AronIcons.Leave)
            LeaveListUi.Error -> AronErrorState(stringResource(R.string.tso_leave_error), stringResource(R.string.tso_retry), onRetry = { scope.launch { state.load() } }, modifier = Modifier.testTag(LeaveTags.ERROR))
            is LeaveListUi.Content -> LeaveContent(u)
        }
    }
}

@Composable
private fun LeaveContent(u: LeaveListUi.Content) {
    Column(Modifier.fillMaxSize()) {
        if (u.offlineCached) AronBanner(stringResource(R.string.tso_offline_cached), Modifier.testTag(LeaveTags.OFFLINE), kind = BannerKind.Offline)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag(LeaveTags.LIST),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(AronTokens.Space.Screen),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
        ) {
            items(u.items, key = { it.leaveUuid }) { LeaveCard(it) }
        }
        u.asOf?.takeIf { it.isNotBlank() }?.let {
            androidx.compose.material3.Text(
                localizedDigits(stringResource(R.string.tso_as_of, it)), Modifier.padding(AronTokens.Space.Screen).testTag(LeaveTags.ASOF),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun LeaveCard(item: LeaveItem) {
    val type = when (item.leaveTypeCode) {
        "casual" -> stringResource(R.string.tso_leave_type_casual)
        "sick" -> stringResource(R.string.tso_leave_type_sick)
        "earn" -> stringResource(R.string.tso_leave_type_earn)
        else -> item.leaveTypeCode
    }
    val period = localizedDigits(stringResource(R.string.tso_leave_period, item.fromDate, item.toDate))
    val days = if (item.days == 1) stringResource(R.string.tso_leave_days_one) else stringResource(R.string.tso_leave_days, localizedNumber(item.days.toLong()))
    val badge = when (item.status) {
        LeaveStatus.PENDING -> stringResource(R.string.tso_leave_status_pending, APPROVER_ROLE)
        LeaveStatus.APPROVED -> stringResource(R.string.tso_leave_status_approved, APPROVER_ROLE)
        LeaveStatus.REJECTED -> stringResource(R.string.tso_leave_status_rejected, APPROVER_ROLE)
    }
    val kind = when (item.status) { LeaveStatus.PENDING -> BannerKind.Warning; LeaveStatus.APPROVED -> BannerKind.Success; LeaveStatus.REJECTED -> BannerKind.Error }
    AronCard(Modifier.fillMaxWidth().testTag(LeaveTags.card(item.leaveUuid))) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            androidx.compose.material3.Text(type, style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
            androidx.compose.material3.Text("$period  ·  $days", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
            androidx.compose.material3.Text(item.reason, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
            AronBanner(badge, Modifier.testTag(LeaveTags.badge(item.leaveUuid)), kind = kind)
            if (!item.sent) AronBanner(stringResource(R.string.tso_leave_not_sent), Modifier.testTag(LeaveTags.unsent(item.leaveUuid)), kind = BannerKind.Offline)
        }
    }
}
