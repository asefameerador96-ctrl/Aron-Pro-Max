package com.aktcl.aron.feature.tickets

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.AronIcons
import kotlinx.coroutines.launch

/** The All Tickets state; the tab and the search text survive rotation and process death. */
@Composable
fun rememberTicketListState(deps: TicketDeps, initialTab: TicketTab = TicketTab.ALL): TicketListState {
    var tab by rememberSaveable { mutableStateOf(initialTab.name) }
    var query by rememberSaveable { mutableStateOf("") }
    val state = remember(deps) { TicketListState(deps, runCatching { TicketTab.valueOf(tab) }.getOrDefault(TicketTab.ALL), query) }
    LaunchedEffect(state) { snapshotFlow { state.ui.tab.name to state.ui.query }.collect { (t, q) -> tab = t; query = q } }
    return state
}

/** Screenshots 12 and 13: the search box, the status tabs (All also shows Cancelled) and the ticket cards. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TicketListScreen(state: TicketListState, frame: TicketFrame, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) { state.load() }
    val ui = state.ui
    val p = ticketPalette()
    val now = state.now()
    val shown = ui.shown
    frame(stringResource(R.string.tickets_title_all)) {
        TicketPage(modifier.testTag(TicketTags.LIST)) {
            PullToRefreshBox(isRefreshing = ui.loading && ui.asOfMs != null, onRefresh = { scope.launch { state.load() } }, modifier = Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item {
                        TicketField(
                            ui.query, state::setQuery, stringResource(R.string.tickets_search_hint), Modifier.testTag(TicketTags.SEARCH),
                            leading = AronIcons.Search, fill = p.field, border = p.fieldBorder,
                        )
                    }
                    item { TabRow(ui.tab, state::setTab) }
                    if (ui.offline) item { OfflineNote(ui.asOfMs, now) }
                    if (shown.isEmpty() && !ui.loading) {
                        item {
                            TicketNote(stringResource(if (ui.all.isEmpty()) R.string.tickets_empty else R.string.tickets_no_match), Modifier.testTag(TicketTags.EMPTY))
                        }
                    }
                    items(shown, key = { it.uuid }) { t -> TicketCard(t, now, onOpen) }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

/** The tabs scroll sideways when they do not fit (200 % text); the selected one is always scrolled into view (screenshot 13). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabRow(selected: TicketTab, onSelect: (TicketTab) -> Unit) {
    val p = ticketPalette()
    val inView = remember { TicketTab.entries.associateWith { BringIntoViewRequester() } }
    LaunchedEffect(selected) { inView.getValue(selected).bringIntoView() }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TicketTab.entries.forEach { t ->
            val on = t == selected
            Box(
                Modifier
                    .bringIntoViewRequester(inView.getValue(t))
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (on) p.accent else p.chip)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(t) })
                    .testTag(TicketTags.tab(t))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tabText(t), style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, color = if (on) p.onAccent else p.chipInk,
                )
            }
        }
    }
}

@Composable
private fun tabText(t: TicketTab): String = stringResource(
    when (t) {
        TicketTab.ALL -> R.string.tickets_tab_all
        TicketTab.NEW -> R.string.tickets_tab_new
        TicketTab.IN_PROGRESS -> R.string.tickets_tab_in_progress
        TicketTab.RESOLVED -> R.string.tickets_tab_resolved
        TicketTab.CLOSED -> R.string.tickets_tab_closed
    },
)
