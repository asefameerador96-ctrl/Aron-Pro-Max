package com.aktcl.aron.sr

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.feature.attendance.AttendanceContent
import com.aktcl.aron.feature.home.DeviceHealth
import com.aktcl.aron.feature.home.HomeContent
import com.aktcl.aron.feature.home.HomeModel
import com.aktcl.aron.feature.home.HomeTile
import com.aktcl.aron.feature.home.HomeTiles
import com.aktcl.aron.feature.home.HomeUser
import com.aktcl.aron.feature.outlet.OutletPicker
import com.aktcl.aron.feature.outlet.OutletPickerContent
import com.aktcl.aron.feature.outlet.VisitCheckContent
import com.aktcl.aron.feature.outlet.VisitUiState
import com.aktcl.aron.feature.stock.SaveOutcome
import com.aktcl.aron.feature.stock.SaveRefusal
import com.aktcl.aron.feature.stock.StockContent
import com.aktcl.aron.feature.stock.StockLoad
import com.aktcl.aron.feature.stock.StockMessage
import com.aktcl.aron.feature.tasks.TaskContent
import kotlinx.coroutines.launch

enum class SrScreen { HOME, ATTENDANCE, STOCK, PICKER, VISIT, TASKS, SETTINGS }

/**
 * The SR day host (F-SR-008/009/011/014/016/017/019/046): Home, Attendance, Stock, the Sale picker with the geo check,
 * and Tasks. Every screen reads the local database; nothing waits for the network. Memo, Summary, Sales Submit and the
 * sale screens belong to android-sr-b and plug in through [onOtherTile] and the open [com.aktcl.aron.feature.outlet.VisitSession].
 */
@Composable
fun SrApp(
    day: SrDay, user: HomeUser, health: DeviceHealth?, versionText: String,
    onLanguageSelect: (com.aktcl.aron.core.common.AppLanguage) -> Unit, onLogout: () -> Unit, onOtherTile: (HomeTile) -> Unit,
) {
    var screen by rememberSaveable { mutableStateOf(SrScreen.HOME) }
    val scope = rememberCoroutineScope()
    var outlets by remember { mutableStateOf<List<OutletEntity>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        day.refreshStamp(); outlets = day.todaysOutlets(); day.nextSequenceFromStore()
        day.attendance.restore(day.attendanceToday())
        permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA))
    }
    LaunchedEffect(tick) { day.attendance.tick() }
    BackHandler(enabled = screen != SrScreen.HOME) { screen = SrScreen.HOME }

    when (screen) {
        SrScreen.HOME -> {
            val header = HomeModel.header(
                "SR", user.fullName, user.username, day.route?.name, day.route?.visitKind, day.businessDate(),
            )
            HomeContent(header, HomeTiles.resolve(emptySet(), day.taskBoard.state.value.openCount), day.freshness, user.offline, health, onTile = { t ->
                when (t) {
                    HomeTile.ATTENDANCE -> screen = SrScreen.ATTENDANCE
                    HomeTile.STOCK -> screen = SrScreen.STOCK
                    HomeTile.SALE -> screen = if (day.visitSession.current.value != null) SrScreen.VISIT else SrScreen.PICKER
                    HomeTile.TASKS -> screen = SrScreen.TASKS
                    else -> onOtherTile(t)
                }
            }, onSettings = { screen = SrScreen.SETTINGS })
        }
        SrScreen.ATTENDANCE -> {
            val st by day.attendance.state.collectAsState()
            AttendanceContent(st, "17:00", onCheckIn = { scope.launch { day.attendance.checkIn() } }, onCheckOut = { scope.launch { day.attendance.checkOut() } })
        }
        SrScreen.SETTINGS -> com.aktcl.aron.feature.home.SettingsContent(versionText, onLanguageSelect, onLogout)
        SrScreen.STOCK -> StockHost(day)
        SrScreen.PICKER -> {
            var chip by rememberSaveable { mutableStateOf(OutletPicker.ALL_CHIP) }
            val all = OutletPicker.rows(outlets)
            val rows = OutletPicker.rows(outlets, chip)
            OutletPickerContent(rows, OutletPicker.chips(all), chip, { chip = it }, { r ->
                scope.launch { day.visitFlow.open(day.visitOutlet(r.outlet)); screen = SrScreen.VISIT }
            })
        }
        SrScreen.VISIT -> {
            val st by day.visitFlow.state.collectAsState()
            val open by day.visitSession.current.collectAsState()
            if (open != null || st is VisitUiState.Open) {
                Column(Modifier.padding(AronTokens.Space.L)) {
                    AronBanner(stringResource(R.string.sr_visit_open), kind = BannerKind.Info)
                    AronPrimaryButton(stringResource(R.string.sr_visit_close), { day.visitSession.close(); screen = SrScreen.HOME })
                }
            } else {
                VisitCheckContent(
                    st, onRefresh = { scope.launch { day.visitFlow.refresh() } },
                    onForceSale = { /* F-SR-018 Force Sale screen: reason and photo capture, wired when F-SYS-010 lands */ },
                    onRetry = { scope.launch { day.visitFlow.retryCommit() } }, onMap = null,
                )
            }
        }
        SrScreen.TASKS -> {
            val st by day.taskBoard.state.collectAsState()
            LaunchedEffect(Unit) { day.taskBoard.load() }
            TaskContent(st, onResolve = { id -> scope.launch { day.taskBoard.resolve(id) } })
        }
    }
}

@Composable
private fun StockHost(day: SrDay) {
    val scope = rememberCoroutineScope()
    var load by remember { mutableStateOf<StockLoad?>(null) }
    var version by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<StockMessage?>(null) }
    LaunchedEffect(Unit) { load = day.stockLoad() }
    val l = load ?: return
    version.let {}
    StockContent(
        rows = l.rows, totals = l.totals, message = message, slipNotPrinted = true,
        onIssue = { id, q -> l.setEntered(id, q.toLong()); version++ },
        onSave = {
            scope.launch {
                when (val out = l.save(day.currentMs(), day.metaProvider.meta(0L))) {
                    is SaveOutcome.Saved -> runCatching { day.capture.recordStock(out.movements) }
                        .onSuccess { l.committed(out, day.currentMs()); message = StockMessage.SAVED; day.requestSync() }
                        .onFailure { l.commitFailed() }
                    is SaveOutcome.Refused -> message = if (out.reason == SaveRefusal.NOTHING_ENTERED) StockMessage.NOTHING_ENTERED else StockMessage.REFUSED_SAME_VALUES
                }
                version++
            }
        },
    )
}
