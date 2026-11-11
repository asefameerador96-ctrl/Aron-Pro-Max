package com.aktcl.aron.feature.amo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.PackshotImage
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.UiLocale

object SrTags { const val CARD = "sr_card_"; const val STEP = "journey_call_"; const val GAP = "journey_gap_"; const val ISSUE = "sr_issue_" }

@Composable
private fun routeLabelOf(m: TeamMember): String {
    val names = listOf(1 to R.string.amo_team_day_mon, 2 to R.string.amo_team_day_tue, 3 to R.string.amo_team_day_wed, 4 to R.string.amo_team_day_thu, 5 to R.string.amo_team_day_fri, 6 to R.string.amo_team_day_sat, 7 to R.string.amo_team_day_sun)
        .associate { (d, r) -> d to stringResource(r) }
    return TeamJourneys.routeLabel(m.routeName, m.routeDays, stringResource(R.string.amo_team_daily)) { names.getValue(it) }
}

/** The SR card of the SR list and the Sales Journey (screenshots 40, 73): avatar, name, route + days, phone, the outlet count, a chevron when it opens. */
@Composable
fun SrCard(m: TeamMember, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(14.dp)
    val l = teamLocale()
    Row(
        modifier.fillMaxWidth().shadow(2.dp, shape).clip(shape).background(look.card).let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .padding(12.dp).testTag(SrTags.CARD + m.routeId),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(look.silhouette), contentAlignment = Alignment.Center) { TeamIcon(R.drawable.amo_team_ic_person, tint = look.silhouetteInk, size = 28.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (m.vacant) stringResource(R.string.amo_team_vacant) else m.name.orEmpty().uppercase(), style = amoText(16, bold = true))
            Text(routeLabelOf(m), style = amoText(14, color = look.inkMuted))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TeamIcon(R.drawable.amo_team_ic_phone, tint = look.ink, size = 16.dp)
                Text(TeamJourneys.phoneText(m.phone ?: m.phoneMasked), style = amoText(14, color = look.purple))
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(stringResource(R.string.amo_team_total_outlets), style = amoText(11, color = look.inkMuted))
            Text(Formats.digits(m.totalOutlets.toString(), UiLocale.EN), style = amoText(17, bold = true, color = look.purple))
        }
        if (onClick != null) Text("›", style = amoText(26, color = look.inkMuted))
    }
}

/** SR List (screenshot 40 left) and the SR cards of the Sales Journey (73): one card per SR per route; a vacant route is not tappable on the journey. */
@Composable
fun SrListContent(members: List<TeamMember>, stale: Boolean, failed: Boolean, onOpen: (TeamMember) -> Unit, modifier: Modifier = Modifier, openVacant: Boolean = true, refused: Boolean = false) {
    AmoPage(modifier) {
        AmoFetchBanner(failed, stale, refused)
        if (members.isEmpty()) AmoEmpty(stringResource(R.string.amo_stock_empty), hint = stringResource(R.string.amo_stock_empty_hint))
        members.forEach { m -> SrCard(m, if (m.vacant && !openVacant) null else ({ onOpen(m) })) }
    }
}

/** One line of lifted stock: the SKU, its issue in field units (rule 5) and the unit word under the name. */
data class IssueLine(val skuId: String, val name: String, val issue: Long, val unitWord: String?, val categoryIsLighter: Boolean = false)

/** Lifted Stock (screenshot 40 right): the SR card, the purple header bar "SKU / Issue", zebra rows with the packshot and the issue in the user's digits. */
@Composable
fun LiftedStockContent(member: TeamMember, lines: List<IssueLine>, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    val l = teamLocale()
    AmoPage(modifier) {
        SrCard(member, null)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))) {
            Row(Modifier.fillMaxWidth().background(look.purple).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(stringResource(R.string.amo_team_sku), style = amoText(15, bold = true, color = look.onBar), modifier = Modifier.weight(1f))
                Text(stringResource(R.string.amo_team_issue), style = amoText(15, bold = true, color = look.onBar), textAlign = TextAlign.End)
            }
            if (lines.isEmpty()) Box(Modifier.fillMaxWidth().background(look.card).padding(16.dp)) { Text(stringResource(R.string.amo_stock_none_today), style = amoText(15, color = look.inkMuted)) }
            lines.forEachIndexed { i, ln ->
                Row(Modifier.fillMaxWidth().background(if (i % 2 == 0) look.card else look.rowAlt).padding(horizontal = 10.dp, vertical = 6.dp).testTag(SrTags.ISSUE + ln.skuId), verticalAlignment = Alignment.CenterVertically) {
                    PackshotImage(ln.skuId, Modifier.size(40.dp, 64.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(ln.name, style = amoText(16))
                        ln.unitWord?.let { Text(it, style = amoText(12, color = look.inkMuted)) }
                    }
                    Text(Formats.integer(ln.issue, l), style = amoText(17, bold = true))
                }
            }
        }
    }
}

/** The Sales Journey of one SR and route (screenshot 74): the timeline, a store node per call, the walking gap between calls. */
@Composable
fun JourneyTimelineContent(j: TeamJourney?, stale: Boolean, failed: Boolean, refused: Boolean = false, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    val l = teamLocale()
    val minW = stringResource(R.string.amo_team_min); val secW = stringResource(R.string.amo_team_sec)
    AmoPage(modifier) {
        AmoFetchBanner(failed, stale, refused)
        if (j == null || j.calls.isEmpty()) { AmoEmpty(stringResource(R.string.amo_team_journey_empty), hint = stringResource(R.string.amo_team_journey_empty_hint)); return@AmoPage }
        j.calls.forEachIndexed { i, c ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(40.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(look.purple.copy(alpha = 0.55f)))
                    Box(Modifier.padding(top = 18.dp).size(34.dp).clip(CircleShape).background(look.card), contentAlignment = Alignment.Center) { TeamIcon(R.drawable.amo_team_ic_store, tint = look.purple, size = 20.dp) }
                }
                CallCard(c, i > 0, l, minW, secW, Modifier.weight(1f).padding(bottom = if (c.gapToNextS != null && i < j.calls.lastIndex) 0.dp else 8.dp))
            }
            val gap = c.gapToNextS
            if (gap != null && i < j.calls.lastIndex) Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag(SrTags.GAP + i), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(40.dp).height(IntrinsicSize.Min), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(look.purple.copy(alpha = 0.55f)))
                    Box(Modifier.background(look.page)) { TeamIcon(R.drawable.amo_team_ic_walk, size = 22.dp) }
                }
                Text(stringResource(R.string.amo_team_gap_after, TeamJourneys.duration(gap, minW, secW, l)), style = amoText(14, color = look.purple))
            }
        }
    }
}

@Composable
private fun CallCard(c: JourneyCall, showDistance: Boolean, l: UiLocale, minW: String, secW: String, modifier: Modifier) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(14.dp)
    val teal = look.purple
    val status = when (c.status) { CallStatus.NEW_SALE -> R.string.amo_team_status_new; CallStatus.EDITED_SALE -> R.string.amo_team_status_edited; CallStatus.NO_SALE -> R.string.amo_team_status_none }
    val statusColor = when (c.status) { CallStatus.NEW_SALE -> look.ink; CallStatus.EDITED_SALE -> if (look.sunlight) look.red else androidx.compose.ui.graphics.Color(0xFFD81B60); CallStatus.NO_SALE -> look.inkMuted }
    Column(modifier.shadow(2.dp, shape).clip(shape).background(look.card).padding(14.dp).testTag(SrTags.STEP + c.visitUuid), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(look.track)); Text(stringResource(status), style = amoText(15, color = statusColor, bold = c.status != CallStatus.NEW_SALE))
        }
        Text(TeamJourneys.outletLine(c.outlet), style = amoText(16, bold = true, color = look.purple))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { TeamIcon(R.drawable.amo_team_ic_clock, tint = look.inkMuted); Text(TeamJourneys.range(c), style = amoText(14, color = look.ink)) }
        c.durationS?.let { s -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { TeamIcon(R.drawable.amo_team_ic_timer, tint = look.inkMuted); Text(TeamJourneys.duration(s, minW, secW, l), style = amoText(14, color = look.ink)) } }
        val d = c.distanceFromPrevMetres
        if (showDistance && d != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { TeamIcon(R.drawable.amo_team_ic_store, tint = teal, size = 16.dp); Text(stringResource(R.string.amo_team_distance, TeamJourneys.metresText(d, l)), style = amoText(13, color = teal)) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { TeamIcon(R.drawable.amo_team_ic_route, tint = teal, size = 16.dp); Text(stringResource(R.string.amo_team_total, TeamJourneys.metresText(c.runningMetres, l)), style = amoText(13, color = teal)) }
        }
    }
}
