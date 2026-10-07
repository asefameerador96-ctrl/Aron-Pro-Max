package com.aktcl.aron.dpc.blocking

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aktcl.aron.dpc.DeviceOwnerPolicy

/**
 * Wakes the engine at the hard end time so apps are released at 20:00 even if the rep never checks out. Inexact
 * (`setAndAllowWhileIdle`, within minutes) so no exact-alarm permission is needed; every app start and boot also
 * re-evaluates.
 */
object HardEndAlarm {
    fun schedule(context: Context, atMs: Long?) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(context, HardEndReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        am.cancel(pi)
        if (atMs != null) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
    }
}

class HardEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread { try { DeviceOwnerPolicy.get(context).blocking.evaluate() } finally { pending.finish() } }.start()
    }
}
