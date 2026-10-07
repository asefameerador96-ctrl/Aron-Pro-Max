package com.aktcl.aron.dpc

import android.content.Context
import com.aktcl.aron.dpc.policy.AndroidDpmGateway
import com.aktcl.aron.dpc.policy.ApplyReport
import com.aktcl.aron.dpc.policy.DevicePolicy
import com.aktcl.aron.dpc.policy.PolicyApplier
import com.aktcl.aron.dpc.policy.PolicyStore
import java.io.File

/**
 * Entry point for the apps (docs/24 s10.5): [receive] stores and applies a policy from the server (enrolment, login, a
 * config delta with `policy_changed`); [reapply] applies the stored policy on boot and app start, offline. The last
 * [ApplyReport] is kept for the status report.
 */
class DeviceOwnerPolicy(
    private val store: PolicyStore,
    private val applier: PolicyApplier,
) {
    @Volatile var lastReport: ApplyReport? = null
        private set

    fun receive(policy: DevicePolicy): ApplyReport = synchronized(this) {
        val inForce = store.save(policy)
        applier.apply(inForce).also { lastReport = it }
    }

    fun reapply(installingOwnUpdate: Boolean = false): ApplyReport? = synchronized(this) {
        val p = store.load() ?: return null
        applier.apply(p, installingOwnUpdate).also { lastReport = it }
    }

    fun current(): DevicePolicy? = store.load()

    companion object {
        @Volatile private var instance: DeviceOwnerPolicy? = null

        fun get(context: Context): DeviceOwnerPolicy = instance ?: synchronized(this) {
            instance ?: DeviceOwnerPolicy(
                PolicyStore(File(context.applicationContext.noBackupFilesDir, "dpc")),
                PolicyApplier(AndroidDpmGateway(context)),
            ).also { instance = it }
        }
    }
}
