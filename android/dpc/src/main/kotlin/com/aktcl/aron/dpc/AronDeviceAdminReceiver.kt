package com.aktcl.aron.dpc

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * The device-owner component of every Aron field app (docs/24 s10). On enable it re-applies the stored policy, if any;
 * enrolment (N-030) and app blocking (N-032) hook in here too.
 */
class AronDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        val pending = goAsync() // file and policy work off the main thread
        Thread { try { runCatching { DeviceOwnerPolicy.get(context).reapply() } } finally { pending.finish() } }.start()
    }

    companion object {
        /** Class name used in android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME (docs/24 s10.4). */
        const val CLASS_NAME: String = "com.aktcl.aron.dpc.AronDeviceAdminReceiver"
    }
}
