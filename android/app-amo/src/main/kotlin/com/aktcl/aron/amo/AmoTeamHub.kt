package com.aktcl.aron.amo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.AronScreen
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.AronTopBar
import com.aktcl.aron.core.ui.RowPosition
import com.aktcl.aron.feature.amo.AmoLeaf
import com.aktcl.aron.feature.amo.AmoMenu
import com.aktcl.aron.feature.amo.AmoReach
import com.aktcl.aron.feature.amo.ExceptionDetailContent
import com.aktcl.aron.feature.amo.ExceptionsListContent
import com.aktcl.aron.feature.amo.OutletVerify
import com.aktcl.aron.feature.amo.RiskSignalRow
import com.aktcl.aron.feature.amo.R as AmoR
import com.aktcl.aron.feature.home.R as HomeR
import com.aktcl.aron.feature.home.RoleHomes
import com.aktcl.aron.feature.home.RoleSectionPlaceholder
import kotlinx.coroutines.launch

private enum class TeamLeaf { HUB, EXCEPTIONS, EXCEPTION_DETAIL, LEAF }

/**
 * The AMO Team hub (docs/07): the team destinations of the menu plus Exceptions. A destination whose row has not landed shows the
 * section placeholder; rows plug in here as they are built.
 */
@Composable
fun AmoTeamHub(day: AmoDay, reach: AmoReach, onBack: () -> Unit) {
    var leaf by rememberSaveable { mutableStateOf(TeamLeaf.HUB) }
    var open by rememberSaveable { mutableStateOf<AmoLeaf?>(null) }
    var signalId by rememberSaveable { mutableStateOf<Long?>(null) }
    val back: () -> Unit = {
        when (leaf) {
            TeamLeaf.HUB -> onBack()
            TeamLeaf.EXCEPTION_DETAIL -> leaf = TeamLeaf.EXCEPTIONS
            else -> leaf = TeamLeaf.HUB
        }
    }
    BackHandler(onBack = back)
    val leaves = AmoMenu.hub(RoleHomes.AmoTile.TEAM, reach)
    when (leaf) {
        TeamLeaf.HUB -> AronScreen(topBar = { AronTopBar(stringResource(HomeR.string.rh_amo_team), onBack = back) }) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen)) {
                leaves.forEachIndexed { i, l ->
                    AronListRow(stringResource(leafLabel(l)), onClick = { open = l; leaf = TeamLeaf.LEAF }, position = if (i == 0) RowPosition.First else RowPosition.Middle, divider = true, flat = true)
                }
                AronListRow(stringResource(AmoR.string.amo_exc_title), onClick = { leaf = TeamLeaf.EXCEPTIONS }, position = if (leaves.isEmpty()) RowPosition.Only else RowPosition.Last, divider = false, flat = true)
            }
        }
        TeamLeaf.LEAF -> RoleSectionPlaceholder(stringResource(open?.let { leafLabel(it) } ?: HomeR.string.rh_amo_team), AronIcons.Info, onBack = back)
        TeamLeaf.EXCEPTIONS, TeamLeaf.EXCEPTION_DETAIL -> AronScreen(topBar = { AronTopBar(stringResource(AmoR.string.amo_exc_title), onBack = back) }) {
            AmoExceptions(day, leaf == TeamLeaf.EXCEPTION_DETAIL, signalId, onOpen = { signalId = it.id; leaf = TeamLeaf.EXCEPTION_DETAIL }, onDone = { leaf = TeamLeaf.EXCEPTIONS })
        }
    }
}

private fun leafLabel(l: AmoLeaf): Int = when (l) {
    AmoLeaf.TEAM_LOCATION -> AmoR.string.amo_leaf_team_location
    AmoLeaf.TEAM_PERFORMANCE -> AmoR.string.amo_leaf_team_performance
    AmoLeaf.TASK_DELEGATION -> AmoR.string.amo_leaf_task_delegation
    AmoLeaf.LIVE_DASHBOARD -> AmoR.string.amo_leaf_live_dashboard
    else -> AmoR.string.amo_leaf_sr_stock
}

@Composable
private fun AmoExceptions(day: AmoDay, detail: Boolean, signalId: Long?, onOpen: (RiskSignalRow) -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<RiskSignalRow>?>(null) }
    var names by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(day, reload) {
        rows = runCatching { day.openSignals() }.getOrDefault(emptyList())
        names = OutletVerify.teamNames(runCatching { day.reference.section("supervisor") }.getOrNull())
    }
    val list = rows ?: run { Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }; return }
    val row = if (detail) list.firstOrNull { it.id == signalId } else null
    if (detail && row == null) { LaunchedEffect(Unit) { onDone() }; return }
    if (row == null) { ExceptionsListContent(list, names, onOpen); return }
    // Fixed when the detail opens: a double tap queues one event.
    val recordUuid = rememberSaveable(row.id) { com.aktcl.aron.core.common.ClientIds.newUuid() }
    var note by rememberSaveable(row.id) { mutableStateOf("") }
    var busy by remember(row.id) { mutableStateOf(false) }
    var failed by remember(row.id) { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val savedText = stringResource(AmoR.string.amo_exc_saved)
    ExceptionDetailContent(row, row.userId?.let { names[it] }, note.take(500), busy, failed, { note = it.take(500) }, onAction = { action ->
        if (busy) return@ExceptionDetailContent
        busy = true; failed = false
        scope.launch {
            val ok = try {
                day.reviewSignal(recordUuid, row.id, action, note.trim().ifEmpty { null }) || row.id !in day.openSignals().map { it.id }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { false }
            busy = false
            if (ok) { android.widget.Toast.makeText(context, savedText, android.widget.Toast.LENGTH_SHORT).show(); reload++; onDone() } else failed = true
        }
    })
}
