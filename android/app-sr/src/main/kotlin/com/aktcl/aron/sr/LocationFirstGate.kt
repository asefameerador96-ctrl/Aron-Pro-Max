package com.aktcl.aron.sr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.aktcl.aron.feature.home.AppPermission

/**
 * Runs the precise-location system prompt right after login, once, and only then shows [content] (SR manual 03: login, then the
 * "Allow ARON SR to access this device's location?" sheet with Precise / Approximate). Whatever the answer, [content] follows: a denial
 * never locks the app (offline-first; support and memo reprint need it), the gates for attendance, sale and outlet capture explain it.
 * Both location permissions are requested together (Android 12+ ignores a fine-only request). Nothing is logged.
 */
@Composable
fun LocationFirstGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val asked = remember(context) { alreadyAsked(context) }
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    var done by rememberSaveable { mutableStateOf(!FirstLocationAsk.firstAskNeeded(granted, asked)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        SrPermissions.markAsked(context, AppPermission.PRECISE_LOCATION)
        done = true
    }
    LaunchedEffect(done) { if (!done) launcher.launch(SrPermissions.manifestNames(AppPermission.PRECISE_LOCATION).toTypedArray()) }
    if (done) content()
}

private fun alreadyAsked(context: Context): Boolean =
    context.getSharedPreferences("aron-permissions", Context.MODE_PRIVATE).getBoolean(AppPermission.PRECISE_LOCATION.name, false)
