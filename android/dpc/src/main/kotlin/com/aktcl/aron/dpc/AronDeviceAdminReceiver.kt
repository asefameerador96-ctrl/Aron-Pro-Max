package com.aktcl.aron.dpc

import android.app.admin.DeviceAdminReceiver

/**
 * The device-owner component of every Aron field app (docs/24 s10). Day-1 placeholder: the device-owner agent adds
 * provisioning completion, the policy engine and the attendance-driven app-blocking schedule.
 */
class AronDeviceAdminReceiver : DeviceAdminReceiver() {
    companion object {
        /** Class name used in android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME (docs/24 s10.4). */
        const val CLASS_NAME: String = "com.aktcl.aron.dpc.AronDeviceAdminReceiver"
    }
}
