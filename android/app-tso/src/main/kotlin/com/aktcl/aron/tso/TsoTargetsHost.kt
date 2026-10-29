package com.aktcl.aron.tso

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.feature.tso.CardLoad
import com.aktcl.aron.feature.tso.TsoFab
import com.aktcl.aron.feature.tso.TsoFrame
import com.aktcl.aron.feature.tso.TsoNav
import com.aktcl.aron.feature.tso.TsoScreen
import com.aktcl.aron.feature.tso.TsoScreenContent
import com.aktcl.aron.feature.tso.targets.TargetAnswer
import com.aktcl.aron.feature.tso.targets.TargetDetailsContent
import com.aktcl.aron.feature.tso.targets.TargetNodeKey
import com.aktcl.aron.feature.tso.targets.TargetStatus
import com.aktcl.aron.feature.tso.targets.TargetStatusContent
import com.aktcl.aron.feature.tso.targets.TargetTab
import com.aktcl.aron.rules.Bands
import kotlinx.coroutines.launch
import com.aktcl.aron.feature.tso.R as TsoR

/**
 * Target Status and Target Details of one signed-in TSO (D-TSO-MAN-39, 40; screenshots 39 to 42). One model per [TsoHost]: the tab is
 * shared by both screens (Details opens on the tab Status shows, and switching it keeps the node and the filter), the filter belongs
 * to Details. Reads only when a screen is shown and on pull-to-refresh, never on a timer: a screen shown again within [freshForMs]
 * of its last read (back from Details) keeps that read. Each answer is cached per month by [TsoApi]. `cfg.kpi.bar_bands` comes from the
 * bundle ([readBands]); a missing value is [40, 80].
 */
class TsoTargetsModel(
    private val api: TsoApi,
    private val today: () -> String,
    private val nowMs: () -> Long,
    private val readBands: suspend () -> Bands = { Bands.DEFAULT },
    private val freshForMs: Long = 60_000L,
) {
    var tab: TargetTab by mutableStateOf(TargetTab.MONTHLY)
    var filter: String? by mutableStateOf(null)
    var bands: Bands by mutableStateOf(Bands.DEFAULT)
        private set
    var status: CardLoad<TargetAnswer>? by mutableStateOf(null)
        private set
    var statusRefreshing: Boolean by mutableStateOf(false)
        private set
    val details = mutableStateMapOf<String, CardLoad<TargetAnswer>>()
    var detailsRefreshing: Boolean by mutableStateOf(false)
        private set

    private var statusRead: Pair<String, Long>? = null
    private val detailsRead = mutableMapOf<String, Pair<String, Long>>()

    /** The month the screens show: the month of the Dhaka business date. */
    fun month(): String = today().take(7)

    private fun recent(read: Pair<String, Long>?): Boolean = read != null && read.first == month() && nowMs() - read.second in 0 until freshForMs

    /** Shown: reads unless the last read of this month is under a minute old (back from Details). */
    suspend fun showStatus() { if (status == null || !recent(statusRead)) loadStatus() }

    /** Pull-to-refresh (and the first show): one read; a second call while one runs is ignored. */
    suspend fun loadStatus() {
        if (statusRefreshing) return
        statusRefreshing = true
        try {
            val m = month()
            bands = runCatching { readBands() }.getOrDefault(Bands.DEFAULT)
            status = api.targetStatus(m).toLoad(TargetStatus::parse)
            statusRead = m to nowMs()
        } finally {
            statusRefreshing = false
        }
    }

    suspend fun showDetails(node: TargetNodeKey) { if (details[node.wire] == null || !recent(detailsRead[node.wire])) loadDetails(node) }

    suspend fun loadDetails(node: TargetNodeKey) {
        if (detailsRefreshing) return
        detailsRefreshing = true
        try {
            val m = month()
            details[node.wire] = api.targetDetails(node, m).toLoad(TargetStatus::parse)
            detailsRead[node.wire] = m to nowMs()
        } finally {
            detailsRefreshing = false
        }
    }

    /** Status: "Details →" of a card opens that node with no filter. */
    fun openDetails(node: TargetNodeKey, nav: TsoNav) {
        filter = null
        nav.open(TsoScreen.TARGET_DETAILS, node.wire)
    }

    /** The Details title (screenshot 41: the node's name, "Apsis"): from its own answer, else from the Status card that opened it. */
    fun titleOf(node: TargetNodeKey): String? =
        details[node.wire]?.value?.node?.ref?.name ?: status?.value?.let { a -> a.cards.firstOrNull { TargetNodeKey.of(a, it) == node }?.ref?.name }
}

@Composable
fun TsoTargetStatusHost(model: TsoTargetsModel, nav: TsoNav) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(model) { model.showStatus() }
    TsoFrame(stringResource(TsoR.string.tso_drawer_target_status), nav, homeButton = TsoFab.of(TsoScreen.TARGET_STATUS)) {
        TargetStatusContent(
            model.status, model.tab, model.bands, model.statusRefreshing,
            onTab = { model.tab = it }, onDetails = { model.openDetails(it, nav) }, onRefresh = { scope.launch { model.loadStatus() } },
        )
    }
}

@Composable
fun TsoTargetDetailsHost(model: TsoTargetsModel, nav: TsoNav, arg: String?) {
    val scope = rememberCoroutineScope()
    val node = remember(arg) { TargetNodeKey.parse(arg) }
    LaunchedEffect(node) { model.showDetails(node) }
    TsoFrame(model.titleOf(node) ?: stringResource(TsoR.string.tso_drawer_target_status), nav, homeButton = TsoFab.of(TsoScreen.TARGET_DETAILS)) {
        TargetDetailsContent(
            model.details[node.wire], model.tab, model.bands, model.filter, model.detailsRefreshing,
            onTab = { model.tab = it }, onFilter = { model.filter = it }, onRefresh = { scope.launch { model.loadDetails(node) } },
        )
    }
}

/** A1's Target Status map: Status and Details sharing one model for the signed-in user; the bands from this user's bundle. */
fun targetScreens(host: TsoHost, api: TsoApi): Map<TsoScreen, TsoScreenContent> {
    val model = TsoTargetsModel(api, host.today, host.nowMs, readBands = {
        val db = host.databases.of(host.userId)
        TargetStatus.bandsOf(com.aktcl.aron.core.database.repo.ReferenceRepository(db).config(BAR_BANDS, host.nowIso()))
    })
    return mapOf(
        TsoScreen.TARGET_STATUS to { nav, _ -> TsoTargetStatusHost(model, nav) },
        TsoScreen.TARGET_DETAILS to { nav, arg -> TsoTargetDetailsHost(model, nav, arg) },
    )
}

/** `cfg.kpi.bar_bands` (V0134, delivery both): the bar colours of Target Status. */
const val BAR_BANDS = "cfg.kpi.bar_bands"
