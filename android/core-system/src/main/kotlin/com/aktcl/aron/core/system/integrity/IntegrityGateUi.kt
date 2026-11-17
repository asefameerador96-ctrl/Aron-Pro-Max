package com.aktcl.aron.core.system.integrity

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.compose.ui.platform.LocalWindowInfo
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import com.aktcl.aron.core.system.R
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.aron.AronScaffold
import com.aktcl.aron.core.ui.aron.AronTopBar
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object IntegrityGateTags {
    const val SCREEN = "integrity_gate"
    const val CHECK_AGAIN = "integrity_gate_check_again"
    const val OPEN_SETTINGS = "integrity_gate_open_settings"
}

/**
 * Shows [content] only while the gate is open. The settings are re-read on first composition, on EVERY resume (return to
 * foreground, back from Settings) and on "check again". While gated, [content] is not composed at all (no login, no sale,
 * no sync screen) but its UI state is kept by a saveable-state holder, so a rep who switches the setting off returns to
 * the screen they were on. Nothing of the session, databases or outbox is touched (see [IntegrityGateController]).
 */
@Composable
fun IntegrityGate(controller: IntegrityGateController, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var state by remember { mutableStateOf(controller.check()) }
    val context = LocalContext.current
    // Every way the setting can change under a resumed app (split screen, a pop-up window, the settings shade):
    // a return to the foreground, the window regaining focus, and the settings themselves (event-driven, not polling).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state = controller.check() }
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { if (focused) state = controller.check() }
    LifecycleStartEffect(controller) {
        val resolver = context.applicationContext.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { state = controller.check() }
        }
        val uris = listOf(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, Settings.Global.ADB_ENABLED).map(Settings.Global::getUriFor)
        runCatching { uris.forEach { resolver.registerContentObserver(it, false, observer) } }
        state = controller.check() // a change while the observer was not registered
        onStopOrDispose { runCatching { resolver.unregisterContentObserver(observer) } }
    }
    LaunchedEffect(state) { withContext(Dispatchers.IO) { controller.noteOnset(state) } }
    val holder = rememberSaveableStateHolder()
    if (state.blocked) {
        IntegrityGateScreen(state.flags, onCheckAgain = { state = controller.check() }, modifier = modifier)
    } else {
        holder.SaveableStateProvider("gated-content") { content() }
    }
}

/**
 * The blocking screen: Bangla first, English below, why, the exact steps, and a "check again" button. The text comes
 * from the string resources of both languages whatever the app language is, so a rep who cannot read English still
 * gets the Bangla steps and the support person can read the English.
 */
@Composable
fun IntegrityGateScreen(flags: DevFlags, onCheckAgain: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bn = remember(context) { context.inLocale("bn") }
    val en = remember(context) { context.inLocale("en") }
    AronTheme(AppLocale.current(context)) {
        AronScaffold(
            topBar = { AronTopBar(title = bn.getString(R.string.integrity_gate_title) + " / " + en.getString(R.string.integrity_gate_title)) },
            modifier = modifier.testTag(IntegrityGateTags.SCREEN),
            // The one action stays on screen however long the steps are on a small phone.
            bottomButtons = {
                AronPrimaryButton(
                    bn.getString(R.string.integrity_gate_check_again) + " / " + en.getString(R.string.integrity_gate_check_again),
                    onCheckAgain, Modifier.weight(1f).testTag(IntegrityGateTags.CHECK_AGAIN),
                )
            },
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.L),
                verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
            ) {
                for (c in listOf(bn, en)) {
                    AronBanner(c.getString(R.string.integrity_gate_why), kind = BannerKind.Warning)
                    if (flags.devOptions) Text(c.getString(R.string.integrity_gate_flag_dev), style = MaterialTheme.typography.bodyLarge)
                    if (flags.adb) Text(c.getString(R.string.integrity_gate_flag_adb), style = MaterialTheme.typography.bodyLarge)
                    Text(c.getString(R.string.integrity_gate_steps), style = MaterialTheme.typography.bodyLarge)
                    Text(c.getString(R.string.integrity_gate_data_safe), style = MaterialTheme.typography.bodyMedium)
                }
                AronSecondaryButton(
                    bn.getString(R.string.integrity_gate_open_settings) + " / " + en.getString(R.string.integrity_gate_open_settings),
                    { context.openDeveloperSettings() }, Modifier.testTag(IntegrityGateTags.OPEN_SETTINGS),
                )
            }
        }
    }
}

private fun Context.inLocale(tag: String): Context {
    val cfg = Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
    return createConfigurationContext(cfg)
}

private fun Context.openDeveloperSettings() {
    // A phone may hide the page or have no Settings app: the steps on the screen still apply, nothing crashes.
    val tries = listOf(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, Settings.ACTION_SETTINGS)
    for (action in tries) {
        if (runCatching { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}

/**
 * Drop-in for `setContent` in every activity of the three apps: the whole UI sits behind the gate, so no activity, deep
 * link or recents entry reaches a screen while Developer options or USB debugging is on.
 */
fun ComponentActivity.setGatedContent(content: @Composable () -> Unit) {
    val controller = IntegrityGates.get(this)
    setContent { IntegrityGate(controller, content = content) }
}
