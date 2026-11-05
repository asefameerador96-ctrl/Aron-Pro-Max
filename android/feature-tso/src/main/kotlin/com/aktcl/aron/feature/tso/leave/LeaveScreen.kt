package com.aktcl.aron.feature.tso.leave

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.feature.tso.R
import com.aktcl.aron.feature.tso.TsoBanner
import com.aktcl.aron.feature.tso.TsoBannerKind
import com.aktcl.aron.feature.tso.TsoButtonStyle
import com.aktcl.aron.feature.tso.TsoCard
import com.aktcl.aron.feature.tso.TsoEmpty
import com.aktcl.aron.feature.tso.TsoError
import com.aktcl.aron.core.ui.aron.NightPageGlow
import com.aktcl.aron.feature.tso.TsoFrame
import com.aktcl.aron.feature.tso.TsoGradientButton
import com.aktcl.aron.feature.tso.TsoLoading
import com.aktcl.aron.feature.tso.TsoNav
import com.aktcl.aron.feature.tso.TsoOrangeBadge
import com.aktcl.aron.core.ui.aron.nightRoles
import com.aktcl.aron.feature.tso.TsoPillTone
import com.aktcl.aron.feature.tso.TsoScreen
import com.aktcl.aron.feature.tso.TsoStatusPill
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
    fun refused(uuid: String) = "tso_leave_refused_$uuid"
    fun held(uuid: String) = "tso_leave_held_$uuid"
    fun days(uuid: String) = "tso_leave_days_$uuid"
}

/**
 * A [TsoNav] for a screen that only goes back (Leave and Final Submit have no Home button, s8 FAB column N): back and
 * home both leave the screen through [onBack].
 */
internal fun backOnly(onBack: () -> Unit): TsoNav = object : TsoNav {
    override fun open(screen: TsoScreen, arg: String?) = Unit
    override fun back() = onBack()
    override fun home() = onBack()
}

/**
 * Leave Applications (screenshot 19, D-TSO-MAN-33): oldest first; each card shows the orange status pill top right, the
 * calendar icon with "from to to", "Reason: ...", and the day count in a circle ("Day" or "Days"); no leave type. Apply
 * For Leave sits at the bottom (manual s7). No Home button (s8 row 19).
 */
@Composable
fun LeaveListScreen(state: LeaveListState, onBack: () -> Unit, modifier: Modifier = Modifier, onApply: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val ui by state.ui.collectAsStateWithLifecycle()
    LaunchedEffect(state) { state.load() }
    TsoFrame(
        stringResource(R.string.tso_leave_title), backOnly(onBack), homeButton = false, modifier = modifier, glow = NightPageGlow.Top,
        bottomBar = if (onApply == null) null else {
            { TsoGradientButton(stringResource(R.string.tso_leave_apply), onApply, Modifier.testTag(LeaveTags.APPLY), style = TsoButtonStyle.Lime) }
        },
    ) {
        when (val u = ui) {
            LeaveListUi.Loading -> TsoLoading()
            LeaveListUi.Empty -> TsoEmpty(stringResource(R.string.tso_leave_empty), Modifier.testTag(LeaveTags.EMPTY), hint = stringResource(R.string.tso_leave_empty_hint))
            LeaveListUi.Error -> TsoError(stringResource(R.string.tso_leave_error), stringResource(R.string.tso_retry), onRetry = { scope.launch { state.load() } }, modifier = Modifier.testTag(LeaveTags.ERROR))
            is LeaveListUi.Content -> LeaveContent(u)
        }
    }
}

@Composable
private fun LeaveContent(u: LeaveListUi.Content) {
    Column(Modifier.fillMaxSize()) {
        if (u.offlineCached) TsoBanner(stringResource(R.string.tso_offline_cached), Modifier.padding(horizontal = AronTokens.Space.Screen).testTag(LeaveTags.OFFLINE), kind = TsoBannerKind.Offline)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag(LeaveTags.LIST),
            contentPadding = PaddingValues(AronTokens.Space.Screen),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
        ) {
            items(u.items, key = { it.leaveUuid }) { LeaveCard(it) }
        }
        u.asOf?.takeIf { it.isNotBlank() }?.let {
            Text(
                localizedDigits(stringResource(R.string.tso_as_of, it)), Modifier.padding(AronTokens.Space.Screen).testTag(LeaveTags.ASOF),
                style = MaterialTheme.typography.bodySmall, color = LocalAronLook.current.inkMuted,
            )
        }
    }
}

/** The pill's words and tone: the decision of the DMO, "Not accepted" when the server refused the record, "Held for review" when it holds it. */
@Composable
private fun pill(item: LeaveItem): Pair<String, TsoPillTone> {
    val dmo = stringResource(R.string.tso_role_dmo)
    return when {
        item.refusalCode != null && item.held -> stringResource(R.string.tso_leave_held) to TsoPillTone.Warning
        item.refusalCode != null -> stringResource(R.string.tso_leave_not_accepted) to TsoPillTone.Danger
        item.status == LeaveStatus.PENDING -> stringResource(R.string.tso_leave_status_pending, dmo) to TsoPillTone.Warning
        item.status == LeaveStatus.APPROVED -> stringResource(R.string.tso_leave_status_approved, dmo) to TsoPillTone.Success
        else -> stringResource(R.string.tso_leave_status_rejected, dmo) to TsoPillTone.Danger
    }
}

/** The reason after "Not accepted:" or "Held for review:": a leave text for the four leave codes, else the bundle's text of the code (R-W-10). */
@Composable
private fun refusalText(item: LeaveItem): String {
    val bangla = LocalAppLanguage.current == AppLanguage.BN
    return when (LeaveRefusal.of(item.refusalCode)) {
        LeaveRefusal.OUT_OF_WINDOW -> stringResource(R.string.tso_leave_refused_window)
        LeaveRefusal.OVERLAP -> stringResource(R.string.tso_leave_refused_overlap)
        LeaveRefusal.NOT_TSO -> stringResource(R.string.tso_leave_refused_not_tso)
        LeaveRefusal.UNKNOWN_TYPE -> stringResource(R.string.tso_leave_refused_unknown_type)
        LeaveRefusal.OTHER -> item.refusalReason?.of(bangla) ?: stringResource(R.string.tso_leave_refused_other)
    }
}

@Composable
private fun LeaveCard(item: LeaveItem) {
    val look = LocalAronLook.current
    val (pillText, tone) = pill(item)
    val period = localizedDigits(stringResource(R.string.tso_leave_period, item.fromDate, item.toDate))
    val dayWord = stringResource(if (item.days == 1) R.string.tso_leave_day_word_one else R.string.tso_leave_day_word)
    val daysNumber = localizedNumber(item.days.toLong())
    val n = nightRoles()
    // night: the owner's teal-to-blue gradient card with white ink (the muted ink is under 4.5:1 on teal), orange pending badge
    val onTeal = if (n.solid) look.inkMuted else look.ink
    TsoCard(
        Modifier.fillMaxWidth().testTag(LeaveTags.card(item.leaveUuid)),
        fillBrush = Brush.linearGradient(listOf(n.leaveStart, n.leaveEnd)), edge = n.leaveEdge,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (tone == TsoPillTone.Warning && !n.solid) TsoOrangeBadge(pillText, Modifier.testTag(LeaveTags.badge(item.leaveUuid)))
            else TsoStatusPill(pillText, Modifier.testTag(LeaveTags.badge(item.leaveUuid)), tone = tone)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(AronIcons.Calendar, contentDescription = null, tint = onTeal, modifier = Modifier.size(20.dp))
                    Text(period, style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic, color = look.ink)
                }
                Text(stringResource(R.string.tso_leave_reason_line, item.reason), style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic, color = look.ink)
                when {
                    item.refusalCode != null && item.held -> Text(
                        stringResource(R.string.tso_leave_held_reason, refusalText(item)), Modifier.testTag(LeaveTags.held(item.leaveUuid)),
                        style = MaterialTheme.typography.bodyMedium, color = onTeal,
                    )
                    item.refusalCode != null -> Text(
                        stringResource(R.string.tso_leave_not_accepted_reason, refusalText(item)), Modifier.testTag(LeaveTags.refused(item.leaveUuid)),
                        style = MaterialTheme.typography.bodyMedium, color = if (n.solid) look.red else look.ink,
                    )
                    !item.sent -> Text(stringResource(R.string.tso_leave_not_sent), Modifier.testTag(LeaveTags.unsent(item.leaveUuid)), style = MaterialTheme.typography.bodySmall, color = onTeal)
                }
            }
            // the day count in a circle: the number over "Day" or "Days" (read as one phrase by TalkBack)
            val spoken = "$daysNumber $dayWord"
            Box(
                // a disc that grows with the font (200 % Bangla "দিন" was clipped at a fixed 64 dp)
                Modifier.defaultMinSize(64.dp, 64.dp).clip(RoundedCornerShape(50)).background(if (n.solid) look.page else n.leaveDisc).padding(horizontal = 6.dp, vertical = 4.dp).testTag(LeaveTags.days(item.leaveUuid)).clearAndSetSemantics { contentDescription = spoken },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(daysNumber, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = look.ink)
                    Text(dayWord, style = MaterialTheme.typography.labelMedium, color = onTeal)
                }
            }
        }
    }
}
