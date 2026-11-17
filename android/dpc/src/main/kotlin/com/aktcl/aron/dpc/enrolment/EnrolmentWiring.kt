package com.aktcl.aron.dpc.enrolment

/**
 * Process-wide wiring of device registration (key and device id): the app installs its coordinator (transport, keys,
 * facts) at start. The registration code and its store stay; provisioning (QR, device-owner activities) is gone
 * (D-OWNER-MDM-02).
 */
object Enrolment {
    @Volatile private var installed: EnrolmentCoordinator? = null
    @Volatile private var onAccepted: () -> Unit = {}

    /** [accepted] runs after a registration code is stored (the app starts the registration in the same process). */
    fun install(coordinator: EnrolmentCoordinator, accepted: () -> Unit = {}) { installed = coordinator; onAccepted = accepted }
    fun accepted() { runCatching(onAccepted) }
    fun coordinator(context: android.content.Context): EnrolmentCoordinator? = installed
    fun pendingStore(context: android.content.Context) = EnrolmentStore(java.io.File(context.applicationContext.noBackupFilesDir, "dpc"))
}
