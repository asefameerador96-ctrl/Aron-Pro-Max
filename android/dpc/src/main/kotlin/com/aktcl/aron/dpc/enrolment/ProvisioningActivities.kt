package com.aktcl.aron.dpc.enrolment

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle

/**
 * Android 12+ provisioning (docs/24 s10.1): asked for the mode, the DPC answers fully managed. Exported and protected by
 * BIND_DEVICE_ADMIN (manifest entry: REQUEST docs/requests/android-geo-dpc-provisioning-activities.md).
 */
class GetProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_OK, Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE))
        finish()
    }
}

/**
 * After provisioning, Android hands the admin extras to the DPC here: they are validated and stored as a pending
 * enrolment; the app's start-up then runs [EnrolmentCoordinator.run] (network) before showing login.
 */
class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptProvisioningExtras(this, intent)
        setResult(RESULT_OK)
        finish()
    }
}

/** Shared by the compliance activity and the pre-Android-12 `onProfileProvisioningComplete` path. */
fun acceptProvisioningExtras(context: android.content.Context, intent: Intent?): Boolean {
    @Suppress("DEPRECATION")
    val bundle = intent?.getParcelableExtra<PersistableBundle>(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE) ?: return false
    val extras = EnrolmentExtras.parse({ bundle.getString(it) }, context.packageName) ?: return false
    // Without an installed coordinator, the same store and the same same-token rule apply; a replaced pending key is
    // cleared by the coordinator's next successful enrolment (deleteAllExcept).
    Enrolment.coordinator(context)?.accept(extras)
        ?: Enrolment.pendingStore(context).acceptPending(extras) { java.util.UUID.randomUUID().toString() }
    return true
}

/** Process-wide wiring: the app installs its coordinator (transport, keys, facts) at start. */
object Enrolment {
    @Volatile private var installed: EnrolmentCoordinator? = null

    fun install(coordinator: EnrolmentCoordinator) { installed = coordinator }
    fun coordinator(context: android.content.Context): EnrolmentCoordinator? = installed
    fun pendingStore(context: android.content.Context) = EnrolmentStore(java.io.File(context.applicationContext.noBackupFilesDir, "dpc"))
}
