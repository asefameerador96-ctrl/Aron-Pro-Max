package com.aktcl.aron.core.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** The outdoor switch, remembered per user on this phone (docs/32 s2a item 5). The user id keeps shared phones apart. */
class SunlightPreference(context: Context, userId: String) {
    private val prefs = context.applicationContext.getSharedPreferences("aron_ui_$userId", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ON, false)
        set(value) { prefs.edit().putBoolean(KEY_ON, value).apply() }

    private companion object { const val KEY_ON = "sunlight_on" }
}

/** Brightness at or above 85 percent of the maximum suggests sunlight mode. A pure rule so it is testable. */
object SunlightSuggestion {
    const val THRESHOLD_PERCENT = 85
    const val SYSTEM_MAX = 255

    fun shouldSuggest(brightness: Int, max: Int = SYSTEM_MAX): Boolean = max > 0 && brightness * 100 >= THRESHOLD_PERCENT * max

    /** One cheap settings read; no light sensor, no observer, no polling. */
    fun read(context: Context): Int = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 0)
}

/**
 * True once each time the screen resumes with the system brightness at 85 percent or more, while sunlight mode is off
 * and the user has not dismissed the suggestion on this resume. Reads the setting once per resume, never polls.
 */
@Composable
fun rememberSunlightSuggestion(sunlightOn: Boolean): Pair<Boolean, () -> Unit> {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var visible by remember { mutableStateOf(false) }
    // keyed on the owner only: turning sunlight off must not re-run the effect (that replays ON_RESUME and re-suggests)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) visible = SunlightSuggestion.shouldSuggest(SunlightSuggestion.read(context))
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return (visible && !sunlightOn) to { visible = false }
}

/** The top-bar sun control: one tap, 48 dp, [AronIcons.Sun] in `accent` when on, announced as a toggle with its state (not by colour alone). */
@Composable
fun SunlightToggle(on: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalAronColors.current
    val label = stringResource(R.string.core_ui_sunlight_toggle)
    Box(
        modifier.size(AronTokens.Touch.Min)
            .aronFocusRing(androidx.compose.foundation.shape.CircleShape)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .toggleable(value = on, role = Role.Switch, onValueChange = onToggle)
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val wellColor = c.accentContainer
        if (on) Box(Modifier.size(AronTokens.Size.IconLarge + AronTokens.Space.S).drawBehind { drawCircle(wellColor) })
        androidx.compose.material3.Icon(AronIcons.Sun, contentDescription = null, tint = if (on) c.accentOnContainer else c.textPrimary, modifier = Modifier.size(AronTokens.Size.Icon))
    }
}

/** The one-time suggestion chip: opaque, with a switch action and a dismiss. */
@Composable
fun SunlightSuggestionChip(onSwitch: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalAronColors.current
    AronBanner(
        stringResource(R.string.core_ui_sunlight_suggest), modifier, BannerKind.Info,
        actionLabel = stringResource(R.string.core_ui_sunlight_switch), onAction = onSwitch,
        dismissLabel = stringResource(R.string.core_ui_sunlight_not_now), onDismiss = onDismiss,
    )
}
