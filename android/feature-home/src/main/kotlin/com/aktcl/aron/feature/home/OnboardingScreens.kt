package com.aktcl.aron.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind

object OnboardingTags {
    fun allow(p: AppPermission) = "perm_allow_${p.name}"
    const val SETTINGS = "perm_settings"
    fun route(id: Long) = "route_$id"
    const val TUTORIAL_EMPTY = "tut_empty"
    fun tutorial(id: String) = "tut_$id"
}

@Composable
private fun whyText(p: AppPermission): String = stringResource(
    when (p) {
        AppPermission.PRECISE_LOCATION -> R.string.perm_location_why
        AppPermission.CAMERA -> R.string.perm_camera_why
        AppPermission.BLUETOOTH -> R.string.perm_bluetooth_why
    },
)

/** Permissions onboarding (F-SR-003): the rationale in the rep's language before each system prompt; never the microphone. */
@Composable
fun PermissionOnboardingContent(state: PermissionState, onAllow: (AppPermission) -> Unit, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
        Text(stringResource(R.string.perm_title), style = MaterialTheme.typography.headlineSmall)
        AppPermission.entries.forEach { p ->
            Text(whyText(p), style = MaterialTheme.typography.bodyLarge)
            when (state.statuses[p] ?: PermissionStatus.NOT_ASKED) {
                PermissionStatus.GRANTED -> Text(stringResource(R.string.perm_granted), style = MaterialTheme.typography.labelLarge)
                PermissionStatus.DENIED_PERMANENTLY -> AronBanner(stringResource(R.string.perm_denied_forever), kind = BannerKind.Warning)
                else -> AronPrimaryButton(stringResource(R.string.perm_allow), { onAllow(p) }, Modifier.testTag(OnboardingTags.allow(p)))
            }
        }
        if (!state.canOpenVisit) AronBanner(stringResource(R.string.perm_location_needed), kind = BannerKind.Warning)
        if (state.needsSettings.isNotEmpty()) AronSecondaryButton(stringResource(R.string.perm_open_settings), onOpenSettings, Modifier.testTag(OnboardingTags.SETTINGS))
    }
}

/** Route picker for an SR with several routes (F-SR-065); the Home header names the route in use. */
@Composable
fun RoutePickerContent(routes: List<PlannedRoute>, inUse: PlannedRoute?, onPick: (PlannedRoute) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        Text(stringResource(R.string.route_pick_title), style = MaterialTheme.typography.headlineSmall)
        inUse?.let { Text(stringResource(R.string.route_in_use, it.name)) }
        LazyColumn { items(RoutePicker.candidates(routes), key = { it.routeId }) { r -> AronListRow(r.name, Modifier.testTag(OnboardingTags.route(r.routeId)), onClick = { onPick(r) }) } }
    }
}

/** A tutorial video entry from the cache (F-SR-048). */
data class TutorialItem(val id: String, val title: String)

/** Tutorial list (F-SR-048): shows from cache; playback is online only and never autoplays or downloads in the background. */
@Composable
fun TutorialListContent(items: List<TutorialItem>, online: Boolean, onPlay: (TutorialItem) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        Text(stringResource(R.string.tut_title), style = MaterialTheme.typography.headlineSmall)
        if (!online) AronBanner(stringResource(R.string.tut_online_only), kind = BannerKind.Info)
        if (items.isEmpty()) AronEmptyState(stringResource(R.string.tut_empty), Modifier.testTag(OnboardingTags.TUTORIAL_EMPTY))
        else LazyColumn {
            items(items, key = { it.id }) { t ->
                AronListRow(t.title, Modifier.testTag(OnboardingTags.tutorial(t.id)), trailing = stringResource(R.string.tut_play), onClick = if (online) ({ onPlay(t) }) else null)
            }
        }
    }
}
