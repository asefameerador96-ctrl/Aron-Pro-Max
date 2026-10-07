package com.aktcl.aron.dpc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-applies the stored policy after a reboot or an app update, with no network (docs/24 s10.5). */
class DpcBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val pending = goAsync()
        Thread {
            try { runCatching { DeviceOwnerPolicy.get(context).reapply() } } finally { pending.finish() }
        }.start()
    }

    companion object {
        // Not LOCKED_BOOT_COMPLETED: the state files are in credential-encrypted storage, readable only after unlock.
        val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
