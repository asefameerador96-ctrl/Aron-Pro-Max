package com.aktcl.aron.dpc

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Obtains the battery-optimisation exemption (docs/24 s10.2 `battery_optimisation_exempt`). Android has no public
 * device-owner call that grants it silently, so the DPC opens the system's one-tap prompt; the policy applier then reports
 * whether it is held (`self:battery_optimisation_exempt` in `policy_apply_errors` until it is). Call [requestIfNeeded]
 * from an activity at enrolment and at login.
 */
object BatteryExemption {
    fun isExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    @SuppressLint("BatteryLife") // Aron is a device-owner work app whose background sync must not be killed (docs/24 s5.4).
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    /** Opens the prompt when the app is not yet exempt; returns true when it was opened. */
    fun requestIfNeeded(activityContext: Context, policyWantsIt: Boolean): Boolean {
        if (!policyWantsIt || isExempt(activityContext)) return false
        val intent = requestIntent(activityContext)
        if (activityContext !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { activityContext.startActivity(intent); true }.getOrDefault(false)
    }
}
