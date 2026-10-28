package com.aktcl.aron.tso

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.feature.tso.CardLoad
import com.aktcl.aron.feature.tso.ChannelMode
import com.aktcl.aron.feature.tso.LoginSubmitTab
import com.aktcl.aron.feature.tso.LoginSubmitUsers
import com.aktcl.aron.feature.tso.TsoDashCard
import com.aktcl.aron.feature.tso.TsoDashboardContent
import com.aktcl.aron.feature.tso.TsoDashboardState
import com.aktcl.aron.feature.tso.TsoDashboards
import com.aktcl.aron.feature.tso.TsoFab
import com.aktcl.aron.feature.tso.TsoFailure
import com.aktcl.aron.feature.tso.TsoFrame
import com.aktcl.aron.feature.tso.TsoLoginSubmitStatusContent
import com.aktcl.aron.feature.tso.TsoNav
import com.aktcl.aron.feature.tso.TsoScreen
import com.aktcl.aron.feature.tso.TsoScreenContent
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.aktcl.aron.feature.tso.R as TsoR

/** A read as a card answer: fresh, a kept copy (offline), offline with no copy, or refused. */
fun <T> TsoFetch.toLoad(parse: (String?) -> T?): CardLoad<T> = when (this) {
    is TsoFetch.Fresh -> CardLoad(parse(json))
    is TsoFetch.Cached -> CardLoad(parse(json), kept = true, failure = TsoFailure.OFFLINE)
    TsoFetch.Unavailable -> CardLoad(null, failure = TsoFailure.OFFLINE)
    TsoFetch.Gone -> CardLoad(null, failure = TsoFailure.REFUSED)
}

/** The Dashboard state from one read of every card (pure: [dashboardStateOf] is what TsoApiTest checks). */
fun dashboardStateOf(fetches: Map<TsoDashCard, TsoFetch>, businessDate: String): TsoDashboardState {
    fun f(c: TsoDashCard) = fetches[c] ?: TsoFetch.Unavailable
    return TsoDashboardState(
        sales = f(TsoDashCard.SALES).toLoad(TsoDashboards::sales),
        geo = f(TsoDashCard.GEO).toLoad(TsoDashboards::geo),
        channelStd = f(TsoDashCard.CHANNEL_STD).toLoad(TsoDashboards::channels),
        channelCalls = f(TsoDashCard.CHANNEL_CALLS).toLoad(TsoDashboards::channels),
        cpr = f(TsoDashCard.CPR).toLoad(TsoDashboards::cpr),
        brand = f(TsoDashCard.BRAND_CPR).toLoad(TsoDashboards::brandCpr),
        login = f(TsoDashCard.LOGIN_SUBMIT).toLoad(TsoDashboards::loginSubmit),
        segment = f(TsoDashCard.SEGMENT).toLoad(TsoDashboards::segments),
        businessDate = businessDate,
    )
}

/**
 * The Dashboard and Login & Bikroy Joma Status of one signed-in TSO (D-TSO-MAN-09, 12, 14, 41). Kept for the whole signed-in day
 * (one per [TsoHost]), so coming back to the Dashboard shows the last figures while it reads again. Reads happen only when a screen
 * opens and on pull-to-refresh, never on a timer; the cards are read in parallel, each with its own cache key. The ▸ choice of the
 * channel card is kept in this user's `aron_ui_<id>` preferences, which logout wipes (SH-17).
 */
class TsoDashboardModel(private val api: TsoApi, private val today: () -> String, private val prefs: SharedPreferences?) {
    var state: TsoDashboardState? by mutableStateOf(null)
        private set
    var refreshing: Boolean by mutableStateOf(false)
        private set
    var channelMode: ChannelMode by mutableStateOf(runCatching { ChannelMode.valueOf(prefs?.getString(KEY_CHANNEL, null) ?: "") }.getOrDefault(ChannelMode.STD))
        private set

    var usersTab: LoginSubmitTab by mutableStateOf(LoginSubmitTab.NOT_LOGGED_IN)
    val users = mutableStateMapOf<LoginSubmitTab, CardLoad<LoginSubmitUsers>>()
    var usersRefreshing: Boolean by mutableStateOf(false)
        private set

    fun toggleChannel() {
        channelMode = if (channelMode == ChannelMode.STD) ChannelMode.CALLS else ChannelMode.STD
        prefs?.edit()?.putString(KEY_CHANNEL, channelMode.name)?.apply()
    }

    /** Reads every card once (a second call while one runs is ignored: a double pull never doubles the reads). */
    suspend fun load() {
        if (refreshing) return
        refreshing = true
        try {
            val day = today()
            val fetches = coroutineScope { TsoDashCard.entries.map { c -> c to async { api.card(c, day) } }.associate { (c, d) -> c to d.await() } }
            state = dashboardStateOf(fetches, day)
        } finally {
            refreshing = false
        }
    }

    suspend fun loadUsers(tab: LoginSubmitTab) {
        if (usersRefreshing) return
        usersRefreshing = true
        try {
            users[tab] = api.loginSubmitUsers(tab, today()).toLoad(TsoDashboards::users)
        } finally {
            usersRefreshing = false
        }
    }

    companion object {
        const val KEY_CHANNEL = "tso_dash_channel_mode"

        /** This user's UI preferences (`aron_ui_<id>.xml`, wiped at logout with the rest of the user's files). */
        fun prefs(context: Context, userId: Long): SharedPreferences = context.getSharedPreferences("aron_ui_$userId", Context.MODE_PRIVATE)
    }
}

/** The Dashboard (root) on [model]: reads when shown and on pull-to-refresh. */
@Composable
fun TsoDashboardHost(model: TsoDashboardModel, nav: TsoNav) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(model) { model.load() }
    TsoDashboardContent(
        model.state, model.refreshing, model.channelMode,
        onRefresh = { scope.launch { model.load() } },
        onToggleChannel = model::toggleChannel,
        onLoginStatus = { nav.open(TsoScreen.LOGIN_SUBMIT_STATUS) },
    )
}

/** Login & Bikroy Joma Status (screenshot 8): reads the selected tab when it opens and on pull-to-refresh. */
@Composable
fun TsoLoginStatusHost(model: TsoDashboardModel, nav: TsoNav) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(model.usersTab) { model.loadUsers(model.usersTab) }
    TsoFrame(stringResource(TsoR.string.tso_dash_login_submit), nav, homeButton = TsoFab.of(TsoScreen.LOGIN_SUBMIT_STATUS)) {
        TsoLoginSubmitStatusContent(
            model.usersTab, model.users[model.usersTab], model.usersRefreshing,
            onTab = { model.usersTab = it }, onRefresh = { scope.launch { model.loadUsers(model.usersTab) } },
        )
    }
}

/** A1's host map: the Dashboard and Login & Bikroy Joma Status, sharing one model for the signed-in user. */
fun dashboardScreens(host: TsoHost): Map<TsoScreen, TsoScreenContent> {
    val model = TsoDashboardModel(TsoApi(host.components.apiClient, host.context, host.userId), host.today, TsoDashboardModel.prefs(host.context, host.userId))
    return mapOf(
        TsoScreen.DASHBOARD to { nav, _ -> TsoDashboardHost(model, nav) },
        TsoScreen.LOGIN_SUBMIT_STATUS to { nav, _ -> TsoLoginStatusHost(model, nav) },
    )
}
