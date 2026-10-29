package com.aktcl.aron.feature.tickets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.LocalAppLanguage
import kotlinx.coroutines.launch

/** The Ticket Dashboard state for one host screen (kept while the screen is in the composition). */
@Composable
fun rememberTicketDashboardState(deps: TicketDeps): TicketDashboardState = remember(deps) { TicketDashboardState(deps) }

/**
 * Screenshot 11: Total / New / In Progress / Resolved, the Ticket Resolution Status card (whole percent from the counts,
 * TicketMath, R-F-14), "Need help?" (opens Quick Ticket), and the 10 most recent tickets with "See all". [refreshOnOpen]
 * reads online even when the kept copy is fresh (the screen opened from a `ticket_updated` push).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TicketDashboardScreen(
    state: TicketDashboardState,
    frame: TicketFrame,
    onOpen: (String) -> Unit,
    onSeeAll: () -> Unit,
    onQuick: () -> Unit,
    modifier: Modifier = Modifier,
    refreshOnOpen: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) { state.load(refresh = refreshOnOpen) }
    val ui = state.ui
    val p = ticketPalette()
    val now = state.now()
    frame(stringResource(R.string.tickets_title_dashboard)) {
        TicketPage(modifier.testTag(TicketTags.DASHBOARD)) {
            PullToRefreshBox(isRefreshing = ui.loading && ui.asOfMs != null, onRefresh = { scope.launch { state.load(refresh = true) } }, modifier = Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (ui.offline) item { OfflineNote(ui.asOfMs, now) }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                StatCard(TicketGlyphs.Inbox, TicketHues.total, ui.counts.total, stringResource(R.string.tickets_stat_total), 0, Modifier.weight(1f))
                                StatCard(AronIcons.Clock, TicketHues.new, ui.counts.new, stringResource(R.string.tickets_stat_new), 1, Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                StatCard(TicketGlyphs.Alert, TicketHues.progress, ui.counts.inProgress, stringResource(R.string.tickets_stat_in_progress), 2, Modifier.weight(1f))
                                StatCard(TicketGlyphs.CheckOutline, TicketHues.resolved, ui.counts.resolved, stringResource(R.string.tickets_stat_resolved), 3, Modifier.weight(1f))
                            }
                        }
                    }
                    item { ResolutionCard(ui) }
                    item { HelpCard(onQuick) }
                    item {
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.tickets_recent), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = p.ink)
                            Box(
                                Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onSeeAll).padding(horizontal = 8.dp).testTag(TicketTags.SEE_ALL),
                                contentAlignment = Alignment.Center,
                            ) { Text(stringResource(R.string.tickets_see_all), style = MaterialTheme.typography.titleSmall, color = p.accentInk) }
                        }
                    }
                    if (ui.recent.isEmpty() && !ui.loading) {
                        item {
                            TicketSurface(Modifier.fillMaxWidth().testTag(TicketTags.EMPTY)) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(stringResource(R.string.tickets_empty), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = p.ink)
                                    Text(stringResource(R.string.tickets_empty_hint), style = MaterialTheme.typography.bodyMedium, color = p.inkMuted)
                                }
                            }
                        }
                    }
                    items(ui.recent, key = { it.uuid }) { t -> TicketCard(t, now, onOpen) }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

/** "Offline. Showing the copy from 10:42." or, with no copy at all, that tickets come when online. */
@Composable
internal fun OfflineNote(asOfMs: Long?, nowMs: Long, modifier: Modifier = Modifier) {
    val p = ticketPalette()
    val text = if (asOfMs != null) stringResource(R.string.tickets_offline_copy, timeText(asOfMs, nowMs)) else stringResource(R.string.tickets_offline_none)
    TicketSurface(modifier.fillMaxWidth().testTag(TicketTags.OFFLINE), fill = p.raised) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(AronIcons.CloudOff, contentDescription = null, tint = p.warning, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = p.ink)
        }
    }
}

@Composable
private fun StatCard(icon: ImageVector, hue: Color, value: Long, label: String, index: Int, modifier: Modifier = Modifier) {
    val p = ticketPalette()
    TicketSurface(modifier.testTag(TicketTags.stat(index)), fill = p.stat, radius = 16) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(hueFill(p.stat, hue, if (p.night) 0.32f else 0.16f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = if (p.night) hue else p.ink, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(digits(value), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = p.ink)
            Text(label, style = MaterialTheme.typography.bodyLarge, color = p.inkMuted)
        }
    }
}

@Composable
private fun ResolutionCard(ui: TicketDashboardUi) {
    val p = ticketPalette()
    val locale = LocalAppLanguage.current.ui
    val c = ui.counts
    TicketSurface(Modifier.fillMaxWidth(), fill = p.panel, radius = 22) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.tickets_resolution_title), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = p.ink, textAlign = TextAlign.Center)
            ResolutionBlock(
                TicketGlyphs.CheckOutline, stringResource(R.string.tickets_res_resolved), c.resolvedPctText(locale), c.resolvedPct, TicketTags.RESOLVED_PCT,
                c.total, stringResource(R.string.tickets_res_total), c.resolved, stringResource(R.string.tickets_res_resolved_caption),
            )
            Spacer(Modifier.height(8.dp))
            ResolutionBlock(
                AronIcons.Clock, stringResource(R.string.tickets_res_open), c.openPctText(locale), c.openPct, TicketTags.OPEN_PCT,
                c.total, stringResource(R.string.tickets_res_total), c.open, stringResource(R.string.tickets_res_open_caption),
            )
        }
    }
}

@Composable
private fun ResolutionBlock(icon: ImageVector, label: String, pctText: String, pct: Long, pctTag: String, left: Long, leftCaption: String, right: Long, rightCaption: String) {
    val p = ticketPalette()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = p.success, modifier = Modifier.size(20.dp))
            Text(label, Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = p.ink)
            Text(pctText, Modifier.testTag(pctTag), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = p.success)
        }
        TicketBar(pct)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(digits(left), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = p.figure)
                Text(leftCaption, style = MaterialTheme.typography.bodySmall, color = p.inkFaint)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(digits(right), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = p.figure)
                Text(rightCaption, style = MaterialTheme.typography.bodySmall, color = p.inkFaint)
            }
        }
    }
}

@Composable
private fun HelpCard(onQuick: () -> Unit) {
    val p = ticketPalette()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(helpBrush(p))
            .clickable(role = Role.Button, onClick = onQuick)
            .testTag(TicketTags.HELP)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.tickets_help_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = p.onHelp)
            Text(stringResource(R.string.tickets_help_body), style = MaterialTheme.typography.bodyMedium, color = p.onHelp, fontSize = 14.sp)
        }
        Box(Modifier.size(42.dp).clip(CircleShape).background(p.onHelp.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
            Icon(AronIcons.Add, contentDescription = null, tint = p.onHelp)
        }
    }
}
