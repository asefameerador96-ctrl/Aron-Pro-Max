package com.aktcl.aron.dpc

import android.app.admin.DeviceAdminReceiver

/**
 * Empty on purpose (D-OWNER-MDM-02, D-AINT-01). Aron no longer is a device owner and enrols no phone as one. This
 * component stays for one release only so that a phone enrolled by an older build still has a resolvable admin
 * component while [com.aktcl.aron.dpc.release.DeviceOwnerRelease] releases device-owner mode on the first start. It has
 * no provisioning intent filter. Delete it, its manifest entry and `res/xml/aron_device_admin.xml` in the release after
 * every pilot phone has upgraded (DECISIONS.md D-AINT-01).
 */
class AronDeviceAdminReceiver : DeviceAdminReceiver()
