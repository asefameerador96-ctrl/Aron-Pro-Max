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
    @Volatile private var trustedNowMs: () -> Long,
    @Volatile private var isWorkingDay: (String) -> Boolean? = { null },
    /** Called with every outcome; the app reports those with `changed` (docs/24 s10.3). */
    private val onBlockingChange: (BlockingOutcome) -> Unit = {},
) {
    /** App blocking from check-in to check-out (N-032); the check-in and check-out commits call it. */
    val blocking = BlockingEngine(suspendGateway, blockingStore, { store.load() }, { trustedNowMs() }, { isWorkingDay(it) })

    /** True while the managed update (N-034) installs; keeps `no_install_apps` lifted on every apply meanwhile. */
    @Volatile var installingOwnUpdate: Boolean = false

    /**
     * The app wires its trusted clock (F-SYS-049) and the bundle's working-day calendar at start. They replace whatever
     * an earlier caller (a boot receiver) installed, so the singleton never keeps a weaker clock.
     */
    fun configure(trustedNowMs: () -> Long, isWorkingDay: (String) -> Boolean?) {
        this.trustedNowMs = trustedNowMs
        this.isWorkingDay = isWorkingDay
    }

    fun trustedNow(): Long = trustedNowMs()

    @Volatile var lastReport: ApplyReport? = null
        private set

    fun receive(policy: DevicePolicy): ApplyReport = synchronized(this) {
        val inForce = store.save(policy)
        applier.apply(inForce, installingOwnUpdate).also { lastReport = it; reevaluateBlocking() }
    }

    fun reapply(): ApplyReport? = synchronized(this) {
        val p = store.load()
        val report = p?.let { applier.apply(it, installingOwnUpdate) }?.also { lastReport = it }
        reevaluateBlocking() // also releases apps left suspended when no policy is stored
        report
    }

    /** Call when the check-in `attendance_event` has committed. */
    fun onCheckInCommitted(): BlockingOutcome = synchronized(this) { blocking.onCheckIn().also(onBlockingChange) }

    /** Call when the check-out `attendance_event` has committed. */
    fun onCheckOutCommitted(): BlockingOutcome = synchronized(this) { blocking.onCheckOut().also(onBlockingChange) }

    /** [onBlockingChange] runs after every evaluation (it re-arms the hard-end alarm, which a reboot clears); report only `changed` ones. */
    fun reevaluateBlocking(): BlockingOutcome = synchronized(this) { blocking.evaluate().also(onBlockingChange) }

    fun current(): DevicePolicy? = store.load()

    companion object {
        @Volatile private var instance: DeviceOwnerPolicy? = null

        /**
         * The process-wide instance. Until the app calls [configure] with the trusted clock (F-SYS-049), time is the wall
         * clock; prod phones enforce automatic time (no_config_date_time), so the wall clock is honest there.
         */
        fun get(context: Context): DeviceOwnerPolicy =
            instance ?: synchronized(this) {
                instance ?: run {
                    val app = context.applicationContext
                    val dir = File(app.noBackupFilesDir, "dpc")
                    val store = PolicyStore(dir)
                    lateinit var created: DeviceOwnerPolicy
                    created = DeviceOwnerPolicy(
                        store, PolicyApplier(AndroidDpmGateway(app)), BlockingStore(dir), AndroidSuspendGateway(app),
                        trustedNowMs = { WallClock.System.nowMs() },
                        onBlockingChange = { outcome ->
                            // Re-armed after every evaluation (a reboot clears alarms): the hard end, or midnight when
                            // the policy has none, so a forgotten check-out never blocks into the next day.
                            val now = created.trustedNow()
                            val today = com.aktcl.aron.rules.BusinessDate.of(now).toString()
                            val end = store.load()?.schedule?.hardEndTime?.let { BlockingEngine.hardEndMs(today, it) }
                                ?.takeIf { it > now } ?: BlockingEngine.nextBusinessDayStartMs(now)
                            runCatching { HardEndAlarm.schedule(app, if (outcome.active) end else null) }
                        },
                    )
                    created.also { instance = it }
                }
            }
    }
}
