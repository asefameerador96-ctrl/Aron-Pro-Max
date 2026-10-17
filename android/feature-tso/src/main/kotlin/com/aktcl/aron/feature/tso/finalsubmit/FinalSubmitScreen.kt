package com.aktcl.aron.feature.tso.finalsubmit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronBarAction
import com.aktcl.aron.core.ui.AronBottomBar
import com.aktcl.aron.core.ui.AronCard
import com.aktcl.aron.core.ui.AronConfirmDialog
import com.aktcl.aron.core.ui.AronInfoDialog
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.AronScreen
import com.aktcl.aron.core.ui.AronSectionLabel
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.AronTopBar
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.feature.tso.R
import com.aktcl.aron.rules.Money
import kotlinx.coroutines.launch

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
    fun zone(id: Long) = "tso_fs_zone_$id"
    fun route(id: Long) = "tso_fs_route_$id"
    fun ff(id: Long) = "tso_fs_ff_$id"
}

/** F-TSO-010 / F-TSO-021 Final Submit. */
@Composable
fun FinalSubmitScreen(state: FinalSubmitState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val ui by state.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) { state.loadZones() }
    val phase = ui.phase
    val busy = phase is FsPhase.Loading || phase is FsPhase.Submitting
    if (phase is FsPhase.AlreadySubmitted) {
        AronInfoDialog(stringResource(R.string.tso_fs_title), stringResource(R.string.tso_fs_already), stringResource(R.string.tso_fs_ok), onDismiss = { state.dismissAlready() })
    }
    if (phase is FsPhase.Review && phase.confirming) {
        AronConfirmDialog(
            stringResource(R.string.tso_fs_confirm_title), stringResource(R.string.tso_fs_confirm_body, ui.date, localizedDigits(stringResource(R.string.tso_mc_zone, ui.zoneId.toString())), phase.preview.routes.size),
            confirmLabel = stringResource(R.string.tso_fs_submit), dismissLabel = stringResource(R.string.tso_fs_cancel),
            onConfirm = { scope.launch { state.confirmSubmit() } }, onDismiss = { state.cancelConfirm() },
        )
    }
    AronScreen(
        modifier, topBar = { AronTopBar(stringResource(R.string.tso_fs_title), onBack = onBack) },
        bottomBar = {
            AronBottomBar(
                primary = when (phase) {
                    is FsPhase.Review -> AronBarAction(stringResource(R.string.tso_fs_submit), { state.askConfirm() })
                    is FsPhase.Done -> AronBarAction(stringResource(R.string.tso_leave_done), onBack)
                    else -> AronBarAction(stringResource(R.string.tso_fs_get), { scope.launch { state.getSalesData() } }, enabled = ui.zoneId != null && !busy, busy = busy)
                },
                modifier = Modifier.testTag(if (phase is FsPhase.Review) FsTags.SUBMIT else FsTags.GET),
            )
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            when (phase) {
                is FsPhase.Done -> AronBanner(stringResource(R.string.tso_fs_done, phase.result.routeCount, phase.result.memoCount, Money.formatTaka(phase.result.netMtk)), Modifier.testTag(FsTags.DONE), kind = BannerKind.Success)
                is FsPhase.Failed -> AronBanner(stringResource(when (phase.why) {
                    FsFailure.OFFLINE -> R.string.tso_fs_offline; FsFailure.FORBIDDEN -> R.string.tso_fs_forbidden; FsFailure.REFUSED -> R.string.tso_fs_refused; FsFailure.SERVER -> R.string.tso_fs_server }), Modifier.testTag(FsTags.FAILED), kind = BannerKind.Error)
                else -> Unit
            }
            if (phase is FsPhase.Review) {
                Text(localizedDigits(stringResource(R.string.tso_fs_sales_date, ui.date)) + "  ·  " + localizedDigits(stringResource(R.string.tso_mc_zone, ui.zoneId.toString())), style = MaterialTheme.typography.titleSmall)
                if (phase.preview.warnings.isNotEmpty()) AronBanner(warnLines(phase.preview.warnings), Modifier.testTag(FsTags.WARNINGS), kind = BannerKind.Warning)
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
                if (ui.zonesLoaded && ui.zones.isEmpty()) AronBanner(stringResource(R.string.tso_fs_no_zones), kind = BannerKind.Warning)
                if (!ui.zonesLoaded) AronBanner(stringResource(R.string.tso_mc_unavailable), Modifier.testTag(FsTags.UNAVAILABLE), kind = BannerKind.Offline)
                AronSectionLabel(stringResource(R.string.tso_mc_zone_label))
                ui.zones.forEach { z -> AronListRow(localizedDigits(stringResource(R.string.tso_mc_zone, z.toString())), Modifier.testTag(FsTags.zone(z)), onClick = { state.pickZone(z) }, trailing = if (z == ui.zoneId) "✓" else null, flat = true) }
                AronSectionLabel(stringResource(R.string.tso_fs_sales_date_label))
                // read-only: the sales date is the Dhaka business date (docs/15 F-TSO-010)
                Text(localizedDigits(ui.date), Modifier.testTag(FsTags.DATE), style = MaterialTheme.typography.titleSmall)
                ui.errors.forEach { e -> AronBanner(stringResource(when (e) { FsError.NO_ZONE -> R.string.tso_fs_err_zone; FsError.DATE_FORMAT -> R.string.tso_leave_err_date_format; FsError.DATE_FUTURE -> R.string.tso_fs_err_future }), Modifier.testTag(FsTags.ERRORS), kind = BannerKind.Error) }
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
