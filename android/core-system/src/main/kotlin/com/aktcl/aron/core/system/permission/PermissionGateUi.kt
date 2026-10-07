package com.aktcl.aron.core.system.permission

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.aktcl.aron.core.system.R
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind

object PermissionGateTags {
    const val BLOCKED = "perm_gate_blocked"
    const val ACTION = "perm_gate_action"
    const val BACK = "perm_gate_back"
}

/**
 * Shows [content] only while [feature] is allowed; otherwise the blocked screen with the rationale in the rep's language
 * and one action (ask again, the app's Settings page, or the location switch). The state is re-read on every resume, so
 * coming back from Settings opens the feature without another tap.
 */
@Composable
fun PermissionGate(feature: GatedFeature, onBack: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = context.findActivity()
    var snapshot by remember { mutableStateOf(activity?.let(AndroidPermissions::snapshot) ?: AndroidPermissions.snapshotOf(context)) }
    val refresh = { snapshot = activity?.let(AndroidPermissions::snapshot) ?: AndroidPermissions.snapshotOf(context) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        AndroidPermissions.markAsked(context, feature.needs)
        refresh()
    }
    when (val d = PermissionPolicy.decide(feature, snapshot)) {
        GateDecision.Allowed -> content()
        is GateDecision.Blocked -> PermissionBlockedContent(
            d,
            coarseOnly = snapshot.coarseOnly,
            onAction = {
                when (d.action) {
                    GateAction.ASK_AGAIN -> launcher.launch(AndroidPermissions.manifestNames(d.permission).toTypedArray())
                    GateAction.OPEN_APP_SETTINGS -> context.startSafely(AndroidPermissions.appSettingsIntent(context.packageName))
                    GateAction.OPEN_LOCATION_SETTINGS -> context.startSafely(AndroidPermissions.locationSettingsIntent())
                }
            },
            onBack = onBack,
            modifier = modifier,
        )
    }
}

/** The blocked screen of F-SYS-023: what is blocked, why the app needs the permission, and the one way forward. */
@Composable
fun PermissionBlockedContent(decision: GateDecision.Blocked, onAction: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier, coarseOnly: Boolean = false) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.L).testTag(PermissionGateTags.BLOCKED),
        verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        Text(stringResource(titleOf(decision.feature)), style = MaterialTheme.typography.headlineSmall)
        AronBanner(stringResource(rationaleOf(decision, coarseOnly)), kind = BannerKind.Warning)
        if (decision.action == GateAction.OPEN_APP_SETTINGS) Text(stringResource(settingsStepsOf(decision.permission)), style = MaterialTheme.typography.bodyLarge)
        AronPrimaryButton(stringResource(actionOf(decision.action)), onAction, Modifier.testTag(PermissionGateTags.ACTION))
        AronSecondaryButton(stringResource(R.string.perm_gate_back), onBack, Modifier.testTag(PermissionGateTags.BACK))
    }
}

internal fun titleOf(f: GatedFeature) = when (f) {
    GatedFeature.ATTENDANCE -> R.string.perm_gate_title_attendance
    GatedFeature.SALE -> R.string.perm_gate_title_sale
    GatedFeature.OUTLET_REQUEST -> R.string.perm_gate_title_outlet
    GatedFeature.PHOTO -> R.string.perm_gate_title_photo
    GatedFeature.PRINT -> R.string.perm_gate_title_print
}

internal fun settingsStepsOf(p: RuntimePermission) = when (p) {
    RuntimePermission.LOCATION -> R.string.perm_gate_settings_steps_location
    RuntimePermission.CAMERA -> R.string.perm_gate_settings_steps_camera
    RuntimePermission.BLUETOOTH -> R.string.perm_gate_settings_steps_bluetooth
}

internal fun rationaleOf(d: GateDecision.Blocked, coarseOnly: Boolean = false) = when {
    d.action == GateAction.OPEN_LOCATION_SETTINGS -> R.string.perm_gate_location_off
    d.permission == RuntimePermission.LOCATION && coarseOnly -> R.string.perm_gate_precise_needed
    d.permission == RuntimePermission.LOCATION -> R.string.perm_gate_location_why
    d.permission == RuntimePermission.CAMERA -> R.string.perm_gate_camera_why
    else -> R.string.perm_gate_bluetooth_why
}

internal fun actionOf(a: GateAction) = when (a) {
    GateAction.ASK_AGAIN -> R.string.perm_gate_allow
    GateAction.OPEN_APP_SETTINGS -> R.string.perm_gate_open_settings
    GateAction.OPEN_LOCATION_SETTINGS -> R.string.perm_gate_turn_on_location
}

private fun Context.startSafely(intent: android.content.Intent) {
    // A locked-down phone may have no Settings app, or the device owner may block it: the screen stays as it is.
    try { startActivity(intent) } catch (_: ActivityNotFoundException) { } catch (_: SecurityException) { }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
