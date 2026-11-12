package com.aktcl.aron.feature.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.rules.JourneyCallRow
import com.aktcl.aron.rules.JourneyCallStatus
import com.aktcl.aron.rules.JourneyFormat
import com.aktcl.aron.rules.JourneyWords
import com.aktcl.aron.rules.UiLocale
import com.aktcl.aron.core.ui.R as CoreR
import com.aktcl.aron.feature.memo.R

object SalesJourneyTags {
    const val LIST = "sj_list"
    const val EMPTY = "sj_empty"
    fun card(visitUuid: String) = "sj_card_$visitUuid"
    fun gap(visitUuid: String) = "sj_gap_$visitUuid"
}

/** A call of the journey with its outlet line already built ("Name (code-phone-cluster)"). */
data class SalesJourneyCall(val row: JourneyCallRow, val outletLine: String)

private val EditedPurple = Color(0xFF7B1FA2)
private val RepeatBlue = Color(0xFF2196F3)
private val NoSaleGrey = Color(0xFF6B7280)
private val OutletRed = Color(0xFFC8423F)
private val RouteBlueGrey = Color(0xFF4A6A8A)
private val TimelinePink = Color(0xFFE45A6B)

/**
 * The Sales Journey of the manual (SRM-B-82 to 90, screenshot 59): today's calls in time order on a pink timeline, each a white card with the
 * status (New Sale hollow dot, Edited Sale purple, Repeat Sale blue, No Sale grey), the outlet in red, the start - end time (12-hour, Latin
 * digits), the duration, then `দূরত্ব` from the previous outlet and `মোট` for the day so far, and between cards the walking gap `... পরে`.
 * View-only and offline: every figure is computed on the phone by the rule the server also uses (shared rules `JourneyTimeline`).
 */
@Composable
fun SalesJourneyScreen(calls: List<SalesJourneyCall>, locale: UiLocale, modifier: Modifier = Modifier) {
    val words = JourneyWords(stringResource(R.string.journey_w_min), stringResource(R.string.journey_w_sec), stringResource(R.string.journey_w_m), stringResource(R.string.journey_w_km))
    val bg = Brush.verticalGradient(listOf(Color(0xFFD9D0E8), Color(0xFFBFB2D8)))
    Box(modifier.fillMaxSize().background(bg)) {
        if (calls.isEmpty()) {
            Box(Modifier.fillMaxSize().testTag(SalesJourneyTags.EMPTY), contentAlignment = Alignment.Center) {
                AronEmptyState(stringResource(R.string.journey_calls_empty), icon = AronIcons.Journey)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().testTag(SalesJourneyTags.LIST), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
                items(calls, key = { it.row.input.visitUuid }) { c -> CallItem(c, words, locale, first = c === calls.first(), last = c === calls.last()) }
            }
        }
    }
}

@Composable
private fun CallItem(c: SalesJourneyCall, w: JourneyWords, locale: UiLocale, first: Boolean, last: Boolean) {
    val r = c.row
    val tone = when (r.status) {
        JourneyCallStatus.EDITED_SALE -> EditedPurple
        JourneyCallStatus.REPEAT_SALE -> RepeatBlue
        JourneyCallStatus.NO_SALE, JourneyCallStatus.IN_PROGRESS -> NoSaleGrey
        JourneyCallStatus.NEW_SALE -> null
    }
    val statusText = stringResource(
        when (r.status) {
            JourneyCallStatus.NEW_SALE -> R.string.journey_s_new
            JourneyCallStatus.EDITED_SALE -> R.string.journey_s_edited
            JourneyCallStatus.REPEAT_SALE -> R.string.journey_s_repeat
            JourneyCallStatus.NO_SALE -> R.string.journey_s_none
            JourneyCallStatus.IN_PROGRESS -> R.string.journey_s_open
        },
    )
    val look = LocalAronLook.current
    val ink = look.ink
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // the timeline: a pink line behind a round node with the shop icon
        Box(Modifier.width(40.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.width(2.dp).fillMaxHeight().padding(top = if (first) 20.dp else 0.dp).background(TimelinePink))
            Box(
                Modifier.padding(top = 4.dp).size(32.dp).clip(CircleShape).background(tone ?: Color.White).border(2.dp, tone ?: EditedPurple, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(CoreR.drawable.aron_ic_outlet), contentDescription = null, tint = if (tone != null) Color.White else EditedPurple, modifier = Modifier.size(18.dp))
            }
        }
        Column(Modifier.padding(start = 8.dp, bottom = if (last) 8.dp else 0.dp).weight(1f)) {
            Column(
                Modifier.fillMaxWidth().padding(top = 4.dp).clip(shape).background(Color.White).let { if (tone != null) it.border(2.dp, tone, shape) else it }
                    .padding(horizontal = 14.dp, vertical = 12.dp).testTag(SalesJourneyTags.card(r.input.visitUuid)),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).let { m -> if (tone == null) m.border(2.dp, ink, CircleShape) else m.background(tone) })
                    Text(statusText, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium.copy(color = ink, fontSize = 15.sp))
                }
                Text(c.outletLine, style = MaterialTheme.typography.titleSmall.copy(color = OutletRed, fontSize = 16.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(JourneyFormat.range(r.input.openedAtMs, r.input.closedAtMs), style = MaterialTheme.typography.bodyMedium.copy(color = look.secondaryText, fontSize = 14.sp))
                r.durationS?.let {
                    Text(JourneyFormat.duration(it, w, locale), style = MaterialTheme.typography.bodyMedium.copy(color = look.secondaryText, fontSize = 14.sp))
                }
                // the first call has no distance row (SRM-B-84); a call without a known base point shows none either
                r.distanceFromPrevM?.let { leg ->
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.journey_distance, JourneyFormat.distance(leg, w, locale)), style = MaterialTheme.typography.bodySmall.copy(color = RouteBlueGrey, fontSize = 13.sp), modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.journey_total, JourneyFormat.distance(r.runningM, w, locale)), style = MaterialTheme.typography.bodySmall.copy(color = RouteBlueGrey, fontSize = 13.sp))
                    }
                }
            }
            r.gapToNextS?.let { gap ->
                val text = stringResource(R.string.journey_gap, JourneyFormat.gap(gap, w, locale))
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(SalesJourneyTags.gap(r.input.visitUuid)).semantics { contentDescription = text },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.journey_ic_walk), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(22.dp))
                    Text(text, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall.copy(color = RouteBlueGrey, fontSize = 13.sp))
                }
            }
        }
    }
}
