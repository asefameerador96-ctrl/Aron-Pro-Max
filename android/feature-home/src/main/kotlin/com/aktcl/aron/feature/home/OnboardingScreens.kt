package com.aktcl.aron.feature.home

import androidx.compose.foundation.lazy.items
import androidx.compose.ui.res.painterResource
import com.aktcl.aron.core.ui.AronListRow
import com.aktcl.aron.core.ui.aron.AronListRow as ParityRow
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.aron.AronScaffold as ParityScaffold
import com.aktcl.aron.core.ui.aron.AronTopBar as ParityTopBar
import com.aktcl.aron.core.ui.R as CoreR
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
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronScreen
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.AronTopBar
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.localizedDigits
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
data class TutorialItem(val id: String, val title: String, val url: String? = null, val manual: Boolean = false, val durationS: Int? = null)

/** Reads the `tutorials` section the bundle keeps (contract `TutorialItem[]`): sorted by `sort`, the Bangla title when the app is Bangla and there is one, bad rows dropped. Never fetches anything. */
object TutorialJson {
    fun parse(section: String?, bangla: Boolean): List<TutorialItem> = runCatching {
        val a = org.json.JSONArray(section ?: return emptyList())
        (0 until a.length()).mapNotNull { i ->
            runCatching {
                val o = a.getJSONObject(i)
                val url = o.getString("url").takeIf { it.startsWith("https://") } ?: return@runCatching null // https only: no cleartext, no other scheme
                val bn = if (o.isNull("title_bn")) null else o.optString("title_bn").ifBlank { null }
                Triple(o.optInt("sort", 0), o.getLong("tutorial_id"), TutorialItem(
                    o.getLong("tutorial_id").toString(), if (bangla && bn != null) bn else o.getString("title_en").also { t -> require(t.isNotBlank()) }, url,
                    o.optString("kind") == "manual", if (o.isNull("duration_s")) null else o.optInt("duration_s").takeIf { it > 0 },
                ))
            }.getOrNull()
        }.sortedWith(compareBy<Triple<Int, Long, TutorialItem>> { it.first }.thenBy { it.second }).map { it.third }
    }.getOrDefault(emptyList())
}

/**
 * Tutorial list (F-SR-048, screens-sr s16): rows with a play glyph from the cache; playback is online only and never
 * autoplays or downloads in the background (offline rows are shown but not tappable, with the info banner saying why).
 */
@Composable
fun TutorialListContent(items: List<TutorialItem>, online: Boolean, onPlay: (TutorialItem) -> Unit, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    ParityScaffold(
        modifier = modifier,
        topBar = {
            ParityTopBar(
                stringResource(R.string.tut_title), icon = painterResource(CoreR.drawable.aron_art_tutorial),
                onBack = onBack, backLabel = stringResource(CoreR.string.core_ui_back),
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            if (!online) AronBanner(stringResource(R.string.tut_online_only), Modifier.padding(horizontal = 12.dp, vertical = AronTokens.Space.S), BannerKind.Info)
            if (items.isEmpty()) AronEmptyState(stringResource(R.string.tut_empty), Modifier.testTag(OnboardingTags.TUTORIAL_EMPTY), icon = AronIcons.Tutorial)
            else LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = AronTokens.Space.S),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.id }) { t ->
                    ParityRow(
                        t.title, Modifier.testTag(OnboardingTags.tutorial(t.id)),
                        subtitle = tutorialSubtitle(t, online),
                        trailing = stringResource(if (t.manual) R.string.tut_open else R.string.tut_play),
                        leading = painterResource(CoreR.drawable.aron_art_tutorial),
                        onClick = if (online) ({ onPlay(t) }) else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun tutorialSubtitle(t: TutorialItem, online: Boolean): String? {
    val kind = stringResource(if (t.manual) R.string.tut_kind_manual else R.string.tut_kind_video)
    val minutes = t.durationS?.let { localizedDigits(stringResource(R.string.tut_minutes, (it + 59) / 60)) }
    val hint = if (online) stringResource(R.string.tut_play_hint) else null
    return listOfNotNull(kind, minutes, hint).joinToString("  ·  ")
}
