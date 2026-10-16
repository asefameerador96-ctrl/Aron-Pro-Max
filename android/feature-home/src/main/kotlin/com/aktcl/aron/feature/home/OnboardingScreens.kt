package com.aktcl.aron.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronCard
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronScreen
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.AronTopBar
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.RowPosition

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
        AppPermission.NOTIFICATIONS -> R.string.perm_notifications_why
    },
)

private fun permissionIcon(p: AppPermission): ImageVector = when (p) {
    AppPermission.PRECISE_LOCATION -> AronIcons.Place
    AppPermission.CAMERA -> AronIcons.Camera
    AppPermission.BLUETOOTH -> AronIcons.Print
    AppPermission.NOTIFICATIONS -> AronIcons.Notifications
}

/** The 44 dp squircle well of a list row or card (the tile's well, reused for leading icons). */
@Composable
private fun IconWell(icon: ImageVector, modifier: Modifier = Modifier) {
    val c = LocalAronColors.current
    val fill = c.accentContainer
    Box(modifier.size(AronTokens.Size.Well).drawBehind { drawRoundRect(fill, cornerRadius = CornerRadius(AronTokens.Radius.Well.toPx())) }, contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = c.accentOnContainer, modifier = Modifier.size(AronTokens.Size.Icon))
    }
}

/**
 * Permissions onboarding (F-SR-003): one field card per permission with its glyph, the rationale in the rep's language
 * before each system prompt, and its state (allowed, blocked, or the Allow primary); never the microphone.
 */
@Composable
fun PermissionOnboardingContent(state: PermissionState, onAllow: (AppPermission) -> Unit, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalAronColors.current
    AronScreen(modifier = modifier, topBar = { AronTopBar(stringResource(R.string.perm_title)) }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
        ) {
            AppPermission.entries.forEach { p ->
                AronCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                            IconWell(permissionIcon(p))
                            Text(whyText(p), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, modifier = Modifier.weight(1f))
                        }
                        when (state.statuses[p] ?: PermissionStatus.NOT_ASKED) {
                            PermissionStatus.GRANTED -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                                Icon(AronIcons.CheckCircle, contentDescription = null, tint = c.success, modifier = Modifier.size(AronTokens.Size.IconSmall))
                                Text(stringResource(R.string.perm_granted), style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
                            }
                            PermissionStatus.DENIED_PERMANENTLY -> AronBanner(stringResource(R.string.perm_denied_forever), kind = BannerKind.Warning)
                            else -> AronPrimaryButton(stringResource(R.string.perm_allow), { onAllow(p) }, Modifier.testTag(OnboardingTags.allow(p)))
                        }
                    }
                }
            }
            if (!state.canOpenVisit) AronBanner(stringResource(R.string.perm_location_needed), kind = BannerKind.Warning)
            if (state.needsSettings.isNotEmpty()) AronSecondaryButton(stringResource(R.string.perm_open_settings), onOpenSettings, Modifier.testTag(OnboardingTags.SETTINGS), icon = AronIcons.Settings)
        }
    }
}

/** Route picker for an SR with several routes (F-SR-065): radio rows in one flat group; the Home header names the route in use. */
@Composable
fun RoutePickerContent(routes: List<PlannedRoute>, inUse: PlannedRoute?, onPick: (PlannedRoute) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalAronColors.current
    val candidates = RoutePicker.candidates(routes)
    AronScreen(modifier = modifier, topBar = { AronTopBar(stringResource(R.string.route_pick_title), subtitle = inUse?.let { stringResource(R.string.route_in_use, it.name) }) }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S)) {
            itemsIndexed(candidates, key = { _, r -> r.routeId }) { i, r ->
                val selected = inUse?.routeId == r.routeId
                AronListRow(
                    r.name, Modifier.testTag(OnboardingTags.route(r.routeId)), onClick = { onPick(r) },
                    leading = { IconWell(AronIcons.Journey) },
                    trailingContent = {
                        Icon(
                            if (selected) AronIcons.CheckCircle else AronIcons.ChevronRight, contentDescription = null,
                            tint = if (selected) c.accent else c.textSecondary, modifier = Modifier.size(AronTokens.Size.Icon),
                        )
                    },
                    position = positionOf(i, candidates.size), divider = i < candidates.size - 1, flat = true,
                )
            }
        }
    }
}

private fun positionOf(i: Int, n: Int): RowPosition = when {
    n == 1 -> RowPosition.Only
    i == 0 -> RowPosition.First
    i == n - 1 -> RowPosition.Last
    else -> RowPosition.Middle
}

/** A tutorial video entry from the cache (F-SR-048). */
data class TutorialItem(val id: String, val title: String)

/**
 * Tutorial list (F-SR-048, screens-sr s16): rows with a play glyph from the cache; playback is online only and never
 * autoplays or downloads in the background (offline rows are shown but not tappable, with the info banner saying why).
 */
@Composable
fun TutorialListContent(items: List<TutorialItem>, online: Boolean, onPlay: (TutorialItem) -> Unit, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    val c = LocalAronColors.current
    AronScreen(modifier = modifier, topBar = { AronTopBar(stringResource(R.string.tut_title), onBack = onBack) }) {
        Column(Modifier.fillMaxSize()) {
            if (!online) AronBanner(stringResource(R.string.tut_online_only), Modifier.padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S), BannerKind.Info)
            if (items.isEmpty()) AronEmptyState(stringResource(R.string.tut_empty), Modifier.testTag(OnboardingTags.TUTORIAL_EMPTY), icon = AronIcons.Tutorial)
            else LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S)) {
                itemsIndexed(items, key = { _, t -> t.id }) { i, t ->
                    AronListRow(
                        t.title, Modifier.testTag(OnboardingTags.tutorial(t.id)),
                        subtitle = if (online) stringResource(R.string.tut_play_hint) else null,
                        trailing = stringResource(R.string.tut_play), onClick = if (online) ({ onPlay(t) }) else null,
                        leading = { IconWell(AronIcons.Tutorial) },
                        trailingContent = { Icon(AronIcons.ChevronRight, contentDescription = null, tint = if (online) c.textSecondary else c.textDisabled, modifier = Modifier.size(AronTokens.Size.Icon)) },
                        position = positionOf(i, items.size), divider = i < items.size - 1, flat = true,
                    )
                }
            }
        }
    }
}
