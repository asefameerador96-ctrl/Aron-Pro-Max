package com.aktcl.aron.feature.tso.finalsubmit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronConfirmDialog
import com.aktcl.aron.core.ui.AronInfoDialog
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.feature.tso.R
import com.aktcl.aron.feature.tso.TsoBanner
import com.aktcl.aron.feature.tso.TsoBannerKind
import com.aktcl.aron.feature.tso.TsoButtonStyle
import com.aktcl.aron.feature.tso.TsoCard
import com.aktcl.aron.feature.tso.TsoDropdownField
import com.aktcl.aron.feature.tso.TsoFrame
import com.aktcl.aron.feature.tso.TsoGradientButton
import com.aktcl.aron.feature.tso.TsoPillTone
import com.aktcl.aron.feature.tso.TsoStatusPill
import com.aktcl.aron.feature.tso.leave.backOnly
import com.aktcl.aron.feature.tso.zoneName
import kotlinx.coroutines.launch

object FsTags {
    const val DATE = "tso_fs_date"
    const val GET = "tso_fs_get"
    const val ALERT = "tso_fs_alert"
    const val SUBMIT = "tso_fs_submit"
    const val DONE = "tso_fs_done"
    const val FAILED = "tso_fs_failed"
    const val ERRORS = "tso_fs_errors"
    const val UNAVAILABLE = "tso_fs_unavailable"
    const val NOTICE = "tso_fs_notice"
    const val ROUTES = "tso_fs_routes"
    const val FLAT_ZONE = "tso_fs_flat_zone"
    fun level(level: Int) = "tso_fs_level_$level"
    fun route(id: Long) = "tso_fs_route_$id"
    fun title(id: Long) = "tso_fs_title_$id"
    fun ff(id: Long) = "tso_fs_ff_$id"
    fun notDue(id: Long) = "tso_fs_not_due_$id"
    fun notUploaded(id: Long) = "tso_fs_not_uploaded_$id"
}

private val LEVEL_LABELS = listOf(R.string.tso_fs_level_wing, R.string.tso_fs_level_division, R.string.tso_fs_level_territory, R.string.tso_fs_level_house, R.string.tso_fs_level_zone)
private val LEVEL_HINTS = listOf(R.string.tso_fs_select_wing, R.string.tso_fs_select_division, R.string.tso_fs_select_territory, R.string.tso_fs_select_house, R.string.tso_fs_select_zone)

/** "<route name> <kind label>" (D-TSO-MAN-27; the space is an improvement over "Apsis RouteDaily"). */
@Composable
fun fsRouteTitle(r: FsRoute): String {
    val kind = when (r.kindLabel) {
        FsKindLabel.DAILY -> stringResource(R.string.tso_fs_kind_daily)
        FsKindLabel.F3 -> stringResource(R.string.tso_fs_kind_3f)
        FsKindLabel.F2 -> stringResource(R.string.tso_fs_kind_2f)
        FsKindLabel.AMO -> stringResource(R.string.tso_fs_kind_amo)
        null -> null
    }
    return if (kind == null) r.name else "${r.name} $kind"
}

/**
 * Final Submit (screenshots 23 to 26). The pickers: Wing, Division, Territory, House, Zone (each a dropdown; one option is
 * picked for the TSO) and the lime Get Sales Data, enabled once the zone is chosen. The routes: the Sales Date box, the
 * route cards ("<name> <kind>", the FF line, an amber "Not uploaded" chip on a due route that has not uploaded; not-due
 * routes greyed) and Submit. No Home button (s8 rows 23 to 26). Back from the routes returns to the pickers.
 */
@Composable
fun FinalSubmitScreen(state: FinalSubmitState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val ui by state.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // one read per screen state: a recreation (rotation, language) keeps the picks, the routes and the phase
    LaunchedEffect(state) { if (!state.ui.value.cascadeTried) { state.loadCascade(); if (!state.ui.value.cascade) state.loadZones() } }
    val phase = ui.phase
    val onRoutes = phase is FsPhase.Review || phase is FsPhase.Submitting || phase is FsPhase.Done
    // system back and the on-screen back do the same: from the routes to the pickers (Done: as OK), from the pickers out
    val back: () -> Unit = { if (phase is FsPhase.Review) state.retry() else if (phase is FsPhase.Done) state.nextZone() else onBack() }
    BackHandler(enabled = phase is FsPhase.Review || phase is FsPhase.Done) { back() }
    Dialogs(state, phase)
    TsoFrame(
        stringResource(R.string.tso_fs_title), backOnly(back), homeButton = false, modifier = modifier,
        bottomBar = if (!onRoutes) null else {
            {
                TsoGradientButton(
                    stringResource(R.string.tso_fs_submit), { scope.launch { state.submit() } }, Modifier.testTag(FsTags.SUBMIT),
                    style = TsoButtonStyle.Lime, enabled = phase is FsPhase.Review, busy = phase is FsPhase.Submitting,
                )
            }
        },
    ) {
        when {
            onRoutes -> Routes(ui, phase)
            else -> Pickers(state, ui, phase)
        }
    }
}

@Composable
private fun Dialogs(state: FinalSubmitState, phase: FsPhase) {
    val scope = rememberCoroutineScope()
    if (phase is FsPhase.AlreadySubmitted) {
        AronInfoDialog(stringResource(R.string.tso_fs_title), stringResource(R.string.tso_fs_already), stringResource(R.string.tso_fs_ok), onDismiss = { state.dismissAlready() })
    }
    if (phase is FsPhase.Done) {
        AronInfoDialog(stringResource(R.string.tso_fs_success_title), stringResource(R.string.tso_fs_done_short), stringResource(R.string.tso_fs_ok), onDismiss = { state.nextZone() })
    }
    if (phase is FsPhase.Review && phase.warning) {
        val names = phase.preview.warnRoutes.map { fsRouteTitle(it) }
        AronConfirmDialog(
            stringResource(R.string.tso_fs_warn_title), stringResource(R.string.tso_fs_warn_body, names.joinToString("\n") { "• $it" }),
            confirmLabel = stringResource(R.string.tso_fs_submit_anyway), dismissLabel = stringResource(R.string.tso_fs_cancel),
            onConfirm = { scope.launch { state.confirmSubmit() } }, onDismiss = { state.cancelConfirm() },
        )
    }
}

@Composable
private fun Pickers(state: FinalSubmitState, ui: FinalSubmitState.Ui, phase: FsPhase) {
    val scope = rememberCoroutineScope()
    val bangla = LocalAppLanguage.current == AppLanguage.BN
    val busy = phase is FsPhase.Loading
    val noHouse = stringResource(R.string.tso_fs_no_house)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
        if (phase is FsPhase.Failed) TsoBanner(stringResource(when (phase.why) {
            FsFailure.OFFLINE -> R.string.tso_fs_offline; FsFailure.FORBIDDEN -> R.string.tso_fs_forbidden; FsFailure.REFUSED -> R.string.tso_fs_refused; FsFailure.SERVER -> R.string.tso_fs_server }), Modifier.testTag(FsTags.FAILED), kind = TsoBannerKind.Error)
        if (ui.cascade) {
            FsGeo.LEVELS.indices.forEach { lv ->
                val options = ui.options[lv].orEmpty()
                TsoDropdownField(
                    options, options.firstOrNull { it.id == ui.picks[lv] }, { n -> state.pickLevel(lv, n.id) },
                    { n -> if (lv == 3 && n.id == FsGeo.NO_HOUSE) noHouse else n.label(bangla) },
                    Modifier.testTag(FsTags.level(lv)), label = stringResource(LEVEL_LABELS[lv]), placeholder = stringResource(LEVEL_HINTS[lv]),
                    enabled = options.isNotEmpty() && !busy,
                )
            }
        } else if (ui.cascadeTried) {
            // the filter options could not be read: the zones of the TSO's reach as one list (no names offline beyond the cache)
            if (!ui.zonesLoaded) TsoBanner(stringResource(R.string.tso_fs_unavailable), Modifier.testTag(FsTags.UNAVAILABLE), kind = TsoBannerKind.Offline)
            else if (ui.zones.isEmpty()) TsoBanner(stringResource(R.string.tso_fs_no_zones), kind = TsoBannerKind.Warning)
            val names = ui.zones.associateWith { zoneName(it) }
            TsoDropdownField(
                ui.zones, ui.zoneId, { z -> state.pickZone(z) }, { z -> names[z] ?: z.toString() }, Modifier.testTag(FsTags.FLAT_ZONE),
                label = stringResource(R.string.tso_fs_level_zone), placeholder = stringResource(R.string.tso_fs_select_zone), enabled = ui.zones.isNotEmpty() && !busy,
            )
        }
        ui.errors.forEach { e -> TsoBanner(stringResource(when (e) { FsError.NO_ZONE -> R.string.tso_fs_err_zone; FsError.DATE_FORMAT -> R.string.tso_leave_err_date_format; FsError.DATE_FUTURE -> R.string.tso_fs_err_future }), Modifier.testTag(FsTags.ERRORS), kind = TsoBannerKind.Error) }
        TsoGradientButton(
            stringResource(R.string.tso_fs_get), { scope.launch { state.getSalesData() } }, Modifier.padding(top = AronTokens.Space.L).testTag(FsTags.GET),
            style = TsoButtonStyle.Lime, enabled = ui.zoneId != null, busy = busy,
        )
    }
}

@Composable
private fun Routes(ui: FinalSubmitState.Ui, phase: FsPhase) {
    val look = LocalAronLook.current
    val preview = when (phase) { is FsPhase.Review -> phase.preview; is FsPhase.Submitting -> phase.preview; is FsPhase.Done -> phase.preview; else -> return }
    val done = phase is FsPhase.Done
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.Screen).testTag(FsTags.ROUTES),
        verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        // the Sales Date box: the day being submitted, in green (screenshot 25)
        Row(
            Modifier.fillMaxWidth().border(1.dp, look.goStart, RoundedCornerShape(12.dp)).padding(horizontal = 20.dp, vertical = 16.dp).testTag(FsTags.DATE),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.tso_fs_sales_date_colon), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = look.ink)
            Text(localizedDigits(preview.date), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = look.goStart)
        }
        if (phase is FsPhase.Review && phase.notice == FsNotice.REOPENED) TsoBanner(stringResource(R.string.tso_fs_reopened), Modifier.testTag(FsTags.NOTICE), kind = TsoBannerKind.Warning)
        Text(stringResource(R.string.tso_fs_routes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = look.ink)
        preview.routes.forEach { r ->
            TsoCard(Modifier.fillMaxWidth().testTag(FsTags.route(r.routeId)).alpha(if (r.notDue || done) 0.55f else 1f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
                        Text(fsRouteTitle(r), Modifier.testTag(FsTags.title(r.routeId)), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = look.ink)
                        Text(if (r.ffName != null) stringResource(R.string.tso_fs_ff, r.ffName) else stringResource(R.string.tso_fs_sr_not_set), Modifier.testTag(FsTags.ff(r.routeId)), style = MaterialTheme.typography.bodyMedium, color = look.inkMuted)
                        if (r.notDue) Text(stringResource(R.string.tso_fs_not_due), Modifier.testTag(FsTags.notDue(r.routeId)), style = MaterialTheme.typography.labelMedium, color = look.inkMuted)
                    }
                    if (r.notUploaded) TsoStatusPill(stringResource(R.string.tso_fs_not_uploaded), Modifier.testTag(FsTags.notUploaded(r.routeId)), tone = TsoPillTone.Warning)
                }
            }
        }
    }
}
