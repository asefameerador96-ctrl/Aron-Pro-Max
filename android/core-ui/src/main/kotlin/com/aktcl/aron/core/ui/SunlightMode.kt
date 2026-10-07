package com.aktcl.aron.core.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
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
    DisposableEffect(owner, sunlightOn) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) visible = !sunlightOn && SunlightSuggestion.shouldSuggest(SunlightSuggestion.read(context))
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return visible to { visible = false }
}

/** The top-bar sun control: one tap, 48 dp, announced as a toggle with its state (not by colour alone). */
@Composable
fun SunlightToggle(on: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalAronColors.current
    val label = stringResource(R.string.core_ui_sunlight_toggle)
    Box(
        modifier.sizeIn(minWidth = AronTokens.Touch.Min, minHeight = AronTokens.Touch.Min)
            .clickable(role = Role.Switch) { onToggle(!on) }
            .semantics(mergeDescendants = true) { contentDescription = label; selected = on },
        contentAlignment = Alignment.Center,
    ) {
        val ink = if (on) c.accent else c.textPrimary
        Canvas(Modifier.size(AronTokens.Space.Xl)) {
            val r = size.minDimension * 0.22f
            val stroke = Stroke(width = (if (on) 3f else 2f) * density, cap = StrokeCap.Round)
            drawCircle(ink, radius = r, style = if (on) androidx.compose.ui.graphics.drawscope.Fill else stroke)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val from = Offset(center.x + (r * 1.5f * Math.cos(a)).toFloat(), center.y + (r * 1.5f * Math.sin(a)).toFloat())
                val to = Offset(center.x + (r * 2.2f * Math.cos(a)).toFloat(), center.y + (r * 2.2f * Math.sin(a)).toFloat())
                drawLine(ink, from, to, strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
        }
    }
}

/** The one-time suggestion chip: opaque, with a switch action and a dismiss. */
@Composable
fun SunlightSuggestionChip(onSwitch: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalAronColors.current
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(AronTokens.Radius.Chip), color = c.accentContainer) {
        Row(Modifier.padding(AronTokens.Space.M), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            Text(stringResource(R.string.core_ui_sunlight_suggest), color = c.accentOnContainer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onSwitch, modifier = Modifier.sizeIn(minHeight = AronTokens.Touch.Min)) { Text(stringResource(R.string.core_ui_sunlight_switch), color = c.accentOnContainer) }
            TextButton(onClick = onDismiss, modifier = Modifier.sizeIn(minHeight = AronTokens.Touch.Min)) { Text(stringResource(R.string.core_ui_sunlight_not_now), color = c.accentOnContainer) }
        }
    }
}
