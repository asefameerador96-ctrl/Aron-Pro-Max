package com.aktcl.aron.feature.tso

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronScreenTransition
import com.aktcl.aron.core.ui.SyncChipState
import com.aktcl.aron.feature.home.BundleStatus
import com.aktcl.aron.feature.home.DeviceHealth
import com.aktcl.aron.feature.home.HomeUser
import com.aktcl.aron.feature.home.R
import com.aktcl.aron.feature.home.RoleBanner
import com.aktcl.aron.feature.home.RoleDeviceOtpContent
import com.aktcl.aron.feature.home.RoleHero
import com.aktcl.aron.feature.home.RoleHomeContent
import com.aktcl.aron.feature.home.RoleHomes
import com.aktcl.aron.feature.home.RoleSectionPlaceholder
import com.aktcl.aron.feature.home.RoleSettingsContent
import com.aktcl.aron.feature.home.RoleTile

/** The TSO screens; every tile screen names its tile so the placeholder frame can show its label and icon. */
enum class TsoScreen(val tileId: String?) {
    HOME(null), SETTINGS(null),
    DASHBOARD(RoleHomes.TsoTile.DASHBOARD), LEAVE(RoleHomes.TsoTile.LEAVE), FINAL_SUBMIT(RoleHomes.TsoTile.FINAL_SUBMIT), PERIPHERY(RoleHomes.TsoTile.PERIPHERY),
    MY_CALL(RoleHomes.TsoTile.MY_CALL), FEEDBACK(RoleHomes.TsoTile.FEEDBACK), DEVICE_OTP(RoleHomes.TsoTile.DEVICE_OTP);

    companion object { fun of(tile: RoleTile): TsoScreen = entries.firstOrNull { it.tileId == tile.id } ?: HOME }
}

/**
 * The TSO day host (docs/08; the frame of SrApp): Home, Settings, the Device OTP page and one screen per tile. Screen
 * state is a saved enum with the system back returning to Home, and screens change through [AronScreenTransition].
 * A section whose lane has not landed shows [RoleSectionPlaceholder]: never plain text. Logout wipes this user's data
 * (F-SYS-022), so Settings says so before the confirm.
 */
@Composable
fun TsoApp(
    user: HomeUser,
    businessDate: String,
    bundle: BundleStatus,
    versionText: String,
    onLanguageSelect: (AppLanguage) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    sunlight: Boolean = false,
    onSunlight: (Boolean) -> Unit = {},
    hero: RoleHero? = null,
    health: DeviceHealth? = null,
    badges: Map<String, Int> = emptyMap(),
    banners: List<RoleBanner> = emptyList(),
    /** Settings > App update (F-SYS-020); null in previews and tests (row hidden). */
    onUpdate: (() -> Unit)? = null,
    updateAvailable: Boolean = false,
    /** Last successful sync as Dhaka `HH:mm`, for the chip and the placeholder captions; null when never. */
    syncedAt: String? = null,
    /** Opens the sync detail sheet from the Home chip; null until the TSO sheet lands (the chip is then not tappable). */
    onSync: (() -> Unit)? = null,
    /** The web dashboard origin (scheme and host, no path) for the Device OTP page; null shows the path alone. */
    webOrigin: String? = null,
    /** F-TSO-008: the leave list source; null shows the placeholder (previews and tests). */
    leave: com.aktcl.aron.feature.tso.leave.LeaveSource? = null,
    /** F-TSO-009: queues a leave application; null hides the Apply button. */
    leaveQueue: com.aktcl.aron.feature.tso.leave.LeaveQueue? = null,
    /** Dhaka business date `YYYY-MM-DD` from trusted time, for the apply form's date checks. */
    today: () -> String = { businessDate },
    /** F-TSO-018/029: the feedback source (categories from the login snapshot, own list), its queue, the photo port (null hides it) and trusted `now` as RFC 3339. */
    feedback: com.aktcl.aron.feature.tso.feedback.FeedbackSource? = null,
    feedbackQueue: com.aktcl.aron.feature.tso.feedback.FeedbackQueue? = null,
    feedbackPhotos: com.aktcl.aron.feature.tso.feedback.FeedbackPhotos? = null,
    nowIso: () -> String = { java.time.Instant.now().toString() },
    /** F-TSO-013..016: Set Plan, My Visit Plan, Visit Query and Assign Task; null shows the placeholder. */
    myCall: com.aktcl.aron.feature.tso.mycall.MyCallDeps? = null,
    /** F-TSO-010/021: Final Submit (online only); null shows the placeholder. */
    finalSubmit: com.aktcl.aron.feature.tso.finalsubmit.FinalSubmitApi? = null,
    userId: Long = 0L,
    /** Real screens by destination, supplied by the app module; a screen not listed shows the placeholder frame. */
    screens: Map<TsoScreen, @Composable (onBack: () -> Unit) -> Unit> = emptyMap(),
) {
    var screen by rememberSaveable { mutableStateOf(TsoScreen.HOME) }
    BackHandler(enabled = screen != TsoScreen.HOME) { screen = TsoScreen.HOME }
    val tiles = remember(badges) { RoleHomes.tsoTiles(badges) }
    val sync = RoleHomes.syncState(bundle, user.offline).let { if (it == SyncChipState.Synced && syncedAt != null) SyncChipState.SyncedAt(syncedAt) else it }
    val header = remember(user, businessDate) { RoleHomes.header(RoleHomes.TSO_PREFIX, user, businessDate) }
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    if (confirmLogout) {
        com.aktcl.aron.core.ui.AronConfirmDialog(
            stringResource(com.aktcl.aron.feature.tso.R.string.tso_chrome_logout), stringResource(R.string.rh_set_logout_wipe),
            confirmLabel = stringResource(com.aktcl.aron.feature.home.R.string.set_logout_confirm), dismissLabel = stringResource(com.aktcl.aron.feature.home.R.string.set_cancel),
            onConfirm = { confirmLogout = false; onLogout() }, onDismiss = { confirmLogout = false },
        )
    }
    androidx.compose.foundation.layout.Box(modifier) {
    AronScreenTransition(screen, forward = { _, to -> to != TsoScreen.HOME }, Modifier) { s ->
        when (s) {
            TsoScreen.HOME -> TsoDrawer(user.fullName, remember { tsoDrawerEntries() }, onEntry = { screen = it }) { open ->
                androidx.compose.foundation.layout.Column {
                    TsoChromeBar(user.fullName, onMenu = open, onLogout = { confirmLogout = true })
                    androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                        RoleHomeContent(
                            header, user, bundle, tiles, sync,
                            onTile = { screen = TsoScreen.of(it) }, onSettings = { screen = TsoScreen.SETTINGS },
                            hero = hero, health = health, sunlight = sunlight, onSunlight = onSunlight, banners = banners, onSync = onSync,
                        )
                    }
                }
            }
            TsoScreen.SETTINGS -> RoleSettingsContent(
                versionText, stringResource(R.string.rh_set_logout_wipe), onLanguageSelect, onLogout, onBack = { screen = TsoScreen.HOME },
                sunlight = sunlight, onSunlight = onSunlight, onUpdate = onUpdate, updateAvailable = updateAvailable,
            )
            TsoScreen.DEVICE_OTP -> RoleDeviceOtpContent(onBack = { screen = TsoScreen.HOME }, webOrigin = webOrigin)
            TsoScreen.LEAVE -> if (leave != null) {
                var applying by rememberSaveable { mutableStateOf(false) }
                // the epochs make a fresh list state / form after an application; the states themselves survive recreation
                val uk = user.username // every retained key carries the user: a shared phone never hands one rep's state to the next
                val counter = rememberRetained("tso-$uk-leave-epoch") { RetainedCounter() }
                val epoch = counter.n
                val state = rememberRetained("tso-$uk-leave-list-$epoch") { com.aktcl.aron.feature.tso.leave.LeaveListState(leave) }
                BackHandler(enabled = applying) { applying = false; counter.n++ }
                if (applying && leaveQueue != null) {
                    val apply = rememberRetained("tso-$uk-leave-apply-$epoch") { com.aktcl.aron.feature.tso.leave.LeaveApplyState(leaveQueue, today) }
                    com.aktcl.aron.feature.tso.leave.LeaveApplyScreen(apply, onBack = { applying = false; counter.n++ })
                } else {
                    com.aktcl.aron.feature.tso.leave.LeaveListScreen(state, onBack = { screen = TsoScreen.HOME }, onApply = if (leaveQueue != null) ({ applying = true }) else null)
                }
            } else TsoPlaceholderFor(s, tiles, syncedAt) { screen = TsoScreen.HOME }
            TsoScreen.FINAL_SUBMIT -> if (finalSubmit != null) {
                val counter = rememberRetained("tso-${user.username}-fs-epoch") { RetainedCounter() }
                val st = rememberRetained("tso-${user.username}-fs-${counter.n}") { com.aktcl.aron.feature.tso.finalsubmit.FinalSubmitState(finalSubmit, userId, today) }
                com.aktcl.aron.feature.tso.finalsubmit.FinalSubmitScreen(st, onBack = { screen = TsoScreen.HOME; counter.n++ })
            } else TsoPlaceholderFor(s, tiles, syncedAt) { screen = TsoScreen.HOME }
            TsoScreen.MY_CALL -> if (myCall != null) {
                MyCallFlow(myCall, user.username, today, onBack = { screen = TsoScreen.HOME })
            } else TsoPlaceholderFor(s, tiles, syncedAt) { screen = TsoScreen.HOME }
            TsoScreen.FEEDBACK -> if (feedback != null) {
                var writing by rememberSaveable { mutableStateOf(false) }
                val uk = user.username
                val counter = rememberRetained("tso-$uk-fb-epoch") { RetainedCounter() }
                val epoch = counter.n
                val cats = rememberRetained("tso-$uk-fb-cats-$epoch") { com.aktcl.aron.feature.tso.feedback.CategoryHolder() }
                androidx.compose.runtime.LaunchedEffect(epoch) { if (cats.list == null) cats.list = runCatching { feedback.categories() }.getOrDefault(emptyList()) }
                val list = rememberRetained("tso-$uk-fb-list-$epoch") { com.aktcl.aron.feature.tso.feedback.FeedbackListState(feedback) }
                BackHandler(enabled = writing) { writing = false; counter.n++ }
                val loaded = cats.list
                if (writing && feedbackQueue != null && loaded != null) {
                    val form = rememberRetained("tso-$uk-fb-form-$epoch") { com.aktcl.aron.feature.tso.feedback.FeedbackFormState(feedbackQueue, loaded, nowIso, feedbackPhotos) }
                    com.aktcl.aron.feature.tso.feedback.FeedbackFormScreen(form, onBack = { writing = false; counter.n++ }, photosEnabled = feedbackPhotos != null)
                } else {
                    com.aktcl.aron.feature.tso.feedback.FeedbackListScreen(list, loaded ?: emptyList(), onBack = { screen = TsoScreen.HOME }, onNew = if (feedbackQueue != null && loaded != null) ({ writing = true }) else null)
                }
            } else TsoPlaceholderFor(s, tiles, syncedAt) { screen = TsoScreen.HOME }
            else -> if (screens[s] != null) screens.getValue(s)({ screen = TsoScreen.HOME }) else {
                val tile = tiles.first { it.id == s.tileId }
                RoleSectionPlaceholder(stringResource(tile.label), tile.icon, onBack = { screen = TsoScreen.HOME }, syncedAt = syncedAt)
            }
        }
    }
    if (screen != TsoScreen.HOME) {
        // the blue Home button (bottom centre), above any screen's own bottom bar
        TsoHomeButton(
            { screen = TsoScreen.HOME },
            Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 96.dp)
                .background(com.aktcl.aron.core.ui.LocalAronColors.current.accentFill, androidx.compose.foundation.shape.RoundedCornerShape(28.dp)),
        )
    }
    }
}

@Composable
private fun TsoPlaceholderFor(s: TsoScreen, tiles: List<RoleTile>, syncedAt: String?, onBack: () -> Unit) {
    val tile = tiles.first { it.id == s.tileId }
    RoleSectionPlaceholder(stringResource(tile.label), tile.icon, onBack = onBack, syncedAt = syncedAt)
}

/** The My Call sub-navigation: hub, Set Plan, My Visit Plan, then Visit Query and Assign Task for one outlet. States survive recreation and are per user. */
@Composable
private fun MyCallFlow(deps: com.aktcl.aron.feature.tso.mycall.MyCallDeps, userKey: String, today: () -> String, onBack: () -> Unit) {
    var step by rememberSaveable { mutableStateOf("hub") }
    val counter = rememberRetained("tso-$userKey-mc-epoch") { RetainedCounter() }
    val sel = rememberRetained("tso-$userKey-mc-sel") { com.aktcl.aron.feature.tso.mycall.McSelection() }
    val e = counter.n
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    BackHandler(enabled = step != "hub") { step = if (step == "task") "plan" else "hub"; if (step == "hub") counter.n++ }
    when (step) {
        "set" -> {
            val st = rememberRetained("tso-$userKey-mc-set-$e") { com.aktcl.aron.feature.tso.mycall.SetPlanState(deps.source, deps.planQueue, today, { java.time.LocalDate.parse(today()).plusDays(1).toString() }) }
            com.aktcl.aron.feature.tso.mycall.SetPlanScreen(st, onBack = { step = "hub"; counter.n++ })
        }
        "plan" -> {
            val st = rememberRetained("tso-$userKey-mc-plan-$e") { com.aktcl.aron.feature.tso.mycall.VisitPlanState(deps.source, today) }
            androidx.compose.runtime.LaunchedEffect(Unit) { if (sel.outlet != null) st.refresh() }
            com.aktcl.aron.feature.tso.mycall.VisitPlanScreen(
                st, onBack = { step = "hub"; counter.n++ },
                onOpenOutlet = { planUuid, outlet, route ->
                    sel.planUuid = planUuid; sel.outlet = outlet; sel.route = route
                    scope.launch { sel.rubric = deps.loadRubric(); step = "query" }
                },
                onOpenTask = { planUuid, outlet, route ->
                    sel.planUuid = planUuid; sel.outlet = outlet; sel.route = route
                    scope.launch { sel.types = deps.loadTaskTypes(); step = "task" }
                },
            )
        }
        "query" -> {
            val outlet = sel.outlet; val route = sel.route; val rubric = sel.rubric
            if (outlet != null && route != null && rubric == null) {
                com.aktcl.aron.feature.tso.mycall.MyCallUnavailable(com.aktcl.aron.feature.tso.R.string.tso_mc_no_rubric, onBack = { step = "plan" })
            } else if (outlet == null || route == null || rubric == null) {
                com.aktcl.aron.feature.tso.mycall.MyCallHub({ step = "set" }, { step = "plan" }, onBack) // nothing selected after a restore: back to the hub
            } else {
                val st = rememberRetained("tso-$userKey-mc-query-${sel.planUuid}-${outlet.id}") { com.aktcl.aron.feature.tso.mycall.VisitQueryState(rubric, sel.planUuid, outlet.id, route.id, deps.queryQueue) }
                com.aktcl.aron.feature.tso.mycall.VisitQueryScreen(
                    st, outlet.name, onBack = { step = "plan" },
                    onDone = { delegate -> if (delegate) { scope.launch { sel.types = deps.loadTaskTypes(); step = "task" } } else step = "plan" },
                )
            }
        }
        "task" -> {
            val outlet = sel.outlet; val route = sel.route
            if (outlet == null) com.aktcl.aron.feature.tso.mycall.MyCallHub({ step = "set" }, { step = "plan" }, onBack) else {
                val st = rememberRetained("tso-$userKey-mc-task-${sel.planUuid}-${outlet.id}") { com.aktcl.aron.feature.tso.mycall.AssignTaskState(sel.types, route, outlet.id, deps.taskQueue, today, sel.planUuid, com.aktcl.aron.feature.tso.mycall.QueryIds.task(sel.planUuid, outlet.id)) }
                com.aktcl.aron.feature.tso.mycall.AssignTaskScreen(st, onDone = { step = "plan" })
            }
        }
        else -> com.aktcl.aron.feature.tso.mycall.MyCallHub({ step = "set" }, { step = "plan" }, onBack)
    }
}
