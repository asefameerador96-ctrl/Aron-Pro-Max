package com.aktcl.aron.dpc

import android.content.Context
import com.aktcl.aron.core.common.WallClock
import com.aktcl.aron.dpc.blocking.AndroidSuspendGateway
import com.aktcl.aron.dpc.blocking.BlockingEngine
import com.aktcl.aron.dpc.blocking.BlockingOutcome
import com.aktcl.aron.dpc.blocking.BlockingStore
import com.aktcl.aron.dpc.blocking.HardEndAlarm
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
    blockingStore: BlockingStore,
    suspendGateway: com.aktcl.aron.dpc.blocking.SuspendGateway,
    trustedNowMs: () -> Long,
    isWorkingDay: (String) -> Boolean? = { null },
    /** Called with every outcome; the app reports those with `changed` (docs/24 s10.3). */
    private val onBlockingChange: (BlockingOutcome) -> Unit = {},
) {
    /** App blocking from check-in to check-out (N-032); the check-in and check-out commits call it. */
    val blocking = BlockingEngine(suspendGateway, blockingStore, { store.load() }, trustedNowMs, isWorkingDay)

    @Volatile var lastReport: ApplyReport? = null
        private set

    fun receive(policy: DevicePolicy): ApplyReport = synchronized(this) {
        val inForce = store.save(policy)
        applier.apply(inForce).also { lastReport = it; reevaluateBlocking() }
    }

    fun reapply(installingOwnUpdate: Boolean = false): ApplyReport? = synchronized(this) {
        val p = store.load()
        val report = p?.let { applier.apply(it, installingOwnUpdate) }?.also { lastReport = it }
        reevaluateBlocking() // also releases apps left suspended when no policy is stored
        report
    }

    /** Call when the check-in `attendance_event` has committed. */
    fun onCheckInCommitted(): BlockingOutcome = blocking.onCheckIn().also(onBlockingChange)

    /** Call when the check-out `attendance_event` has committed. */
    fun onCheckOutCommitted(): BlockingOutcome = blocking.onCheckOut().also(onBlockingChange)

    /** [onBlockingChange] runs after every evaluation (it re-arms the hard-end alarm, which a reboot clears); report only `changed` ones. */
    fun reevaluateBlocking(): BlockingOutcome = blocking.evaluate().also(onBlockingChange)

    fun current(): DevicePolicy? = store.load()

    companion object {
        @Volatile private var instance: DeviceOwnerPolicy? = null

        /**
         * The process-wide instance. [trustedNowMs] defaults to the wall clock until the trusted clock (F-SYS-049) is wired
         * by the app; prod phones enforce automatic time (no_config_date_time), so the wall clock is honest there.
         */
        fun get(context: Context, trustedNowMs: () -> Long = { WallClock.System.nowMs() }): DeviceOwnerPolicy =
            instance ?: synchronized(this) {
                val app = context.applicationContext
                val dir = File(app.noBackupFilesDir, "dpc")
                val store = PolicyStore(dir)
                instance ?: DeviceOwnerPolicy(
                    store, PolicyApplier(AndroidDpmGateway(app)), BlockingStore(dir), AndroidSuspendGateway(app), trustedNowMs,
                    onBlockingChange = { outcome ->
                        val today = com.aktcl.aron.rules.BusinessDate.of(trustedNowMs()).toString()
                        val end = store.load()?.schedule?.hardEndTime?.let { BlockingEngine.hardEndMs(today, it) }
                        HardEndAlarm.schedule(app, if (outcome.active) end else null)
                    },
                ).also { instance = it }
            }
    }
}
