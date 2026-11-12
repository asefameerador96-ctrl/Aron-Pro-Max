package com.aktcl.aron.core.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * While this is in the composition the window is `FLAG_SECURE`: the screen is blank in the recents list and cannot be captured or
 * screen-shared. Put it on screens that show or take a device OTP (the TSO's code list, the SR's code entry). It clears the flag on
 * leaving only if it set it, so a screen that is secure for another reason stays secure.
 */
@Composable
fun SecureScreen() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = context.findActivity()?.window
        if (window != null) SecureHolders.acquire(window)
        onDispose { if (window != null) SecureHolders.release(window) }
    }
}

/** Reference count per window: two screens that overlap (a transition) keep the flag until the last one leaves. Main thread only. */
private object SecureHolders {
    private class Hold(var count: Int, val wasSecure: Boolean)
    private val holds = java.util.WeakHashMap<android.view.Window, Hold>()

    fun acquire(window: android.view.Window) {
        val hold = holds[window]
        if (hold != null) { hold.count++; return }
        holds[window] = Hold(1, (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(window: android.view.Window) {
        val hold = holds[window] ?: return
        if (--hold.count > 0) return
        holds.remove(window)
        if (!hold.wasSecure) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
