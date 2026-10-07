package com.aktcl.aron.core.sync.device

import android.content.Context
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.geo.AndroidDeviceStateReader
import com.aktcl.aron.core.geo.AndroidGnssObserver
import com.aktcl.aron.core.geo.AndroidLocationAccess
import com.aktcl.aron.core.geo.FallbackLocationSource
import com.aktcl.aron.core.geo.FixManager
import com.aktcl.aron.core.geo.PrefsFixLedger
import com.aktcl.aron.core.geo.integrity.AndroidDeviceKeyStore
import com.aktcl.aron.core.geo.integrity.IntegrityEvidenceService
import com.aktcl.aron.core.geo.integrity.IntegritySignalsReader
import com.aktcl.aron.core.geo.integrity.IntegritySignalsTracker
import com.aktcl.aron.core.geo.integrity.PlayIntegritySource
import com.aktcl.aron.core.network.DeviceProofSigner
import com.aktcl.aron.core.session.SessionComponents
import com.aktcl.aron.core.session.SessionState
import com.aktcl.aron.core.sync.SyncEngine
import com.aktcl.aron.dpc.DeviceOwnerPolicy
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.core.sync.SyncScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * The geo, integrity and device-owner call sites the app shells owe android-geo-dpc (docs/requests/android-geo-dpc-wiring.md),
 * built once per process in the app's DI and shared by SR, AMO and TSO:
 * - [fixManager]: the one on-demand fix manager, with `cfg.geo.*` from the active user's bundle and `integrity_ref`;
 * - [onAppCreate]: `DeviceOwnerPolicy.configure(trusted clock, bundle calendar)` then `reapply()` (offline, no network);
 * - [beforeBatch]: run by the sync worker before each batch: integrity signals and Play Integrity evidence as `device_status`;
 * - [start]/[wantEvidence]: login and check-in ask for fresh evidence, collected by the next sync run, never awaited.
 * Nothing here is in the path of a sale.
 */
class DeviceRuntime(
    context: Context,
    private val components: SessionComponents,
    playIntegrityProjectNumber: Long,
) {
    private val app = context.applicationContext
    val state: IntegrityState = PrefsIntegrityState(app)
    val dayConfig = DayConfig()
    val deviceStateReader = AndroidDeviceStateReader(app, components.clock, integrityRef = { state.integrityRef })

    val fixManager: FixManager by lazy {
        FixManager(
            FallbackLocationSource.of(app), AndroidLocationAccess(app), deviceStateReader, components.clock, PrefsFixLedger(app),
            AndroidGnssObserver(app, components.clock), settings = { dayConfig.fixSettings },
        )
    }

    private val policy: () -> DeviceOwnerPolicy = { DeviceOwnerPolicy.get(app) }

    val reporter: DeviceStatusReporter by lazy {
        val evidence = IntegrityEvidenceService(
            DeviceNonceApi(components.apiClient), PlayIntegritySource(app, playIntegrityProjectNumber), { components.deviceIdentity.deviceUuid },
        )
        DeviceStatusReporter(
            facts = AndroidDeviceFacts(app, policy),
            signals = IntegritySignalsReader(app, deviceStateReader)::read,
            tracker = IntegritySignalsTracker(File(app.noBackupFilesDir, "aron/integrity")),
            evidence = evidence::collect,
            state = state,
            clock = components.trustedClock,
            appVersion = components.appVersion,
        )
    }

    /** Application.onCreate: the DPC gets the trusted clock and the calendar, then re-applies the stored policy (no network). */
    fun onAppCreate() {
        runCatching {
            val p = policy()
            p.configure(components.trustedClock::nowMs, dayConfig::isWorkingDay)
            p.reapply()
        }
    }

    /**
     * Application.onCreate: [onAppCreate], then every completed online login asks for fresh evidence and one upload (the
     * report rides it). The login itself has already returned; nothing waits for this.
     */
    fun start(scope: CoroutineScope, scheduler: SyncScheduler) {
        scope.launch { onAppCreate() }
        scope.launch {
            components.session.onlineLogins.collect { userId ->
                wantEvidence()
                runCatching { scheduler.requestSync(userId, SyncTrigger.WRITE_DEBOUNCE) }
            }
        }
    }

    /** Login and check-in: the next sync run collects Play Integrity evidence (never awaited by the caller). */
    fun wantEvidence() = reporter.wantEvidence()

    /** Reloads the settings device code reads synchronously; for the active user only (their bundle is the one in force). */
    suspend fun refreshDayConfig(userId: Long, db: AronDatabase) {
        val active = (components.session.state.value as? SessionState.Active)?.user?.userId
        if (active != null && active != userId) return
        try {
            dayConfig.refresh(db, SyncEngine.iso(components.trustedClock.nowMs()))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // keep the previous settings
        }
    }

    /** The sync worker's hook before a batch: never throws, never fails the upload. */
    suspend fun beforeBatch(userId: Long, db: AronDatabase) {
        refreshDayConfig(userId, db)
        reporter.beforeBatch(db)
    }

    companion object {
        /** `X-Device-Proof` over the enrolled Keystore key; null signatures until enrolment (a dev phone). */
        fun proofSigner(context: Context): DeviceProofSigner =
            KeystoreProofSigner(AndroidDeviceKeyStore(context.applicationContext), KeystoreProofSigner.enrolledAlias(context))
    }
}
