package com.aktcl.aron.feature.tso.finalsubmit

import androidx.compose.foundation.layout.Arrangement
import com.aktcl.aron.feature.tso.zoneName
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aktcl.aron.core.ui.AronConfirmDialog
import com.aktcl.aron.core.ui.AronInfoDialog
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.feature.tso.R
import com.aktcl.aron.rules.Money
import kotlinx.coroutines.launch
import com.aktcl.aron.core.ui.aron.AronCard
import com.aktcl.aron.core.ui.aron.AronListRow
import com.aktcl.aron.core.ui.aron.AronSecondaryButton
import com.aktcl.aron.core.ui.aron.AronTabs
import com.aktcl.aron.feature.tso.TsoAction
import com.aktcl.aron.feature.tso.TsoBanner
import com.aktcl.aron.feature.tso.TsoBannerKind
import com.aktcl.aron.feature.tso.TsoBottomBar
import com.aktcl.aron.feature.tso.TsoEmpty
import com.aktcl.aron.feature.tso.TsoError
import com.aktcl.aron.feature.tso.TsoFrame
import com.aktcl.aron.feature.tso.TsoSectionTitle
import com.aktcl.aron.feature.tso.TsoStepper

object FsTags {
    const val DATE = "tso_fs_date"
    const val GET = "tso_fs_get"
    const val ALERT = "tso_fs_alert"
    const val CONFIRM = "tso_fs_confirm"
    const val SUBMIT = "tso_fs_submit"
    const val DONE = "tso_fs_done"
    const val FAILED = "tso_fs_failed"
    const val ERRORS = "tso_fs_errors"
    const val UNAVAILABLE = "tso_fs_unavailable"
    const val WARNINGS = "tso_fs_warnings"
    fun level(level: Int, id: Long) = "tso_fs_level_${level}_$id"
    fun zone(id: Long) = "tso_fs_zone_$id"
    fun route(id: Long) = "tso_fs_route_$id"
    fun ff(id: Long) = "tso_fs_ff_$id"
}

/** F-TSO-010 / F-TSO-021 Final Submit. */
@Composable
fun FinalSubmitScreen(state: FinalSubmitState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val ui by state.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) { state.loadZones(); state.loadCascade() }
    val bangla = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "bn"
    val phase = ui.phase
    val busy = phase is FsPhase.Loading || phase is FsPhase.Submitting
    if (phase is FsPhase.AlreadySubmitted) {
        AronInfoDialog(stringResource(R.string.tso_fs_title), stringResource(R.string.tso_fs_already), stringResource(R.string.tso_fs_ok), onDismiss = { state.dismissAlready() })
    }
    // the manual: a "Success" dialog with OK (the banner and the Done button stay as the lasting record)
    var successSeen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (phase is FsPhase.Done && !successSeen) {
        AronInfoDialog(stringResource(R.string.tso_fs_success_title), stringResource(R.string.tso_fs_done, phase.result.routeCount, phase.result.memoCount, Money.formatTaka(phase.result.netMtk)), stringResource(R.string.tso_fs_ok), onDismiss = { successSeen = true })
    }
    if (phase is FsPhase.Review && phase.confirming) {
        AronConfirmDialog(
            stringResource(R.string.tso_fs_confirm_title), stringResource(R.string.tso_fs_confirm_body, ui.date, (ui.zoneId?.let { zoneName(it) }.orEmpty()), phase.preview.routes.size),
            confirmLabel = stringResource(R.string.tso_fs_submit), dismissLabel = stringResource(R.string.tso_fs_cancel),
            onConfirm = { scope.launch { state.confirmSubmit() } }, onDismiss = { state.cancelConfirm() },
        )
    }
    TsoFrame(stringResource(R.string.tso_fs_title), onBack, modifier, tile = com.aktcl.aron.feature.home.RoleHomes.TsoTile.FINAL_SUBMIT, bottomBar = {
            TsoBottomBar(
                primary = when (phase) {
                    is FsPhase.Review -> TsoAction(stringResource(R.string.tso_fs_submit), { state.askConfirm() })
                    is FsPhase.Done -> TsoAction(stringResource(R.string.tso_leave_done), onBack)
                    else -> TsoAction(stringResource(R.string.tso_fs_get), { scope.launch { state.getSalesData() } }, enabled = ui.zoneId != null && !busy, busy = busy)
                },
                modifier = Modifier.testTag(if (phase is FsPhase.Review) FsTags.SUBMIT else FsTags.GET),
            )
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            when (phase) {
                is FsPhase.Done -> TsoBanner(stringResource(R.string.tso_fs_done, phase.result.routeCount, phase.result.memoCount, Money.formatTaka(phase.result.netMtk)), Modifier.testTag(FsTags.DONE), kind = TsoBannerKind.Success)
                is FsPhase.Failed -> TsoBanner(stringResource(when (phase.why) {
                    FsFailure.OFFLINE -> R.string.tso_fs_offline; FsFailure.FORBIDDEN -> R.string.tso_fs_forbidden; FsFailure.REFUSED -> R.string.tso_fs_refused; FsFailure.SERVER -> R.string.tso_fs_server }), Modifier.testTag(FsTags.FAILED), kind = TsoBannerKind.Error)
                else -> Unit
            }
            if (phase is FsPhase.Review) {
                Text(localizedDigits(stringResource(R.string.tso_fs_sales_date, ui.date)) + "  ·  " + (ui.zoneId?.let { zoneName(it) }.orEmpty()), style = MaterialTheme.typography.titleSmall)
                TsoSectionTitle(stringResource(R.string.tso_fs_routes))
                if (phase.preview.warnings.isNotEmpty()) TsoBanner(warnLines(phase.preview.warnings), Modifier.testTag(FsTags.WARNINGS), kind = TsoBannerKind.Warning)
                phase.preview.routes.forEach { r ->
                    AronCard(Modifier.fillMaxWidth().testTag(FsTags.route(r.routeId))) {
                        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
                            Text(r.name + "  ·  " + r.code, style = MaterialTheme.typography.titleSmall)
                            Text(if (r.ffName != null) stringResource(R.string.tso_fs_ff, r.ffName) else stringResource(R.string.tso_fs_sr_not_set), Modifier.testTag(FsTags.ff(r.routeId)), style = MaterialTheme.typography.bodyMedium)
                            Text(localizedDigits(stringResource(R.string.tso_fs_route_totals, r.memoCount, Money.formatTaka(r.netMtk))), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else if (phase !is FsPhase.Done) {
                if (ui.zonesLoaded && ui.zones.isEmpty()) TsoBanner(stringResource(R.string.tso_fs_no_zones), kind = TsoBannerKind.Warning)
                if (!ui.zonesLoaded) TsoBanner(stringResource(R.string.tso_mc_unavailable), Modifier.testTag(FsTags.UNAVAILABLE), kind = TsoBannerKind.Offline)
                if (ui.cascade) {
                    val labels = listOf(R.string.tso_fs_level_wing, R.string.tso_fs_level_division, R.string.tso_fs_level_territory, R.string.tso_fs_level_house, R.string.tso_mc_zone_label)
                    ui.options.toSortedMap().forEach { (lv, nodes) ->
                        TsoSectionTitle(stringResource(labels[lv]))
                        nodes.forEach { n -> AronListRow(n.label(bangla), Modifier.testTag(FsTags.level(lv, n.id)), onClick = { scope.launch { state.pickLevel(lv, n.id) } }, trailing = if (ui.picks[lv] == n.id) "✓" else null) }
                    }
                } else {
                TsoSectionTitle(stringResource(R.string.tso_mc_zone_label))
                ui.zones.forEach { z -> AronListRow(zoneName(z), Modifier.testTag(FsTags.zone(z)), onClick = { state.pickZone(z) }, trailing = if (z == ui.zoneId) "✓" else null) }
                }
                TsoSectionTitle(stringResource(R.string.tso_fs_sales_date_label))
                // read-only: the sales date is the Dhaka business date (docs/15 F-TSO-010)
                Text(localizedDigits(ui.date), Modifier.testTag(FsTags.DATE), style = MaterialTheme.typography.titleSmall)
                ui.errors.forEach { e -> TsoBanner(stringResource(when (e) { FsError.NO_ZONE -> R.string.tso_fs_err_zone; FsError.DATE_FORMAT -> R.string.tso_leave_err_date_format; FsError.DATE_FUTURE -> R.string.tso_fs_err_future }), Modifier.testTag(FsTags.ERRORS), kind = TsoBannerKind.Error) }
            }
        }
    }
}

@Composable
private fun warnLines(codes: List<String>): String {
    val notSubmitted = stringResource(R.string.tso_fs_warn_not_submitted)
    val pending = stringResource(R.string.tso_fs_warn_pending)
    val notLoggedIn = stringResource(R.string.tso_fs_warn_not_logged_in)
    return codes.joinToString("\n") { when (it) { "routes_not_sales_submitted" -> notSubmitted; "routes_with_pending_rows" -> pending; else -> notLoggedIn } }
}
