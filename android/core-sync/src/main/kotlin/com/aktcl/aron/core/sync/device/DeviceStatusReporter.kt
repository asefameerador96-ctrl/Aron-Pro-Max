package com.aktcl.aron.core.sync.device

import com.aktcl.aron.contract.DeviceInfo
import com.aktcl.aron.contract.DeviceStatusReport
import com.aktcl.aron.contract.DeviceStatusReportPolicyApplyErrors
import com.aktcl.aron.contract.PlayIntegrityUnavailable
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.ReferenceRepository
import com.aktcl.aron.core.geo.integrity.IntegrityEvidenceService
import com.aktcl.aron.core.geo.integrity.IntegrityResult
import com.aktcl.aron.core.geo.integrity.IntegritySignals
import com.aktcl.aron.core.geo.integrity.IntegritySignalsTracker
import com.aktcl.aron.core.geo.integrity.IntegrityUnavailable
import com.aktcl.aron.core.session.TrustedClockSource
import com.aktcl.aron.core.sync.SyncEngine
import com.aktcl.aron.rules.BusinessDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** The parts of a status report that are not integrity signals, read when a report is built ([AndroidDeviceFacts]). */
data class DeviceFactsSnapshot(
    val deviceInfo: DeviceInfo,
    val deviceOwner: Boolean,
    /** `LockdownLevel`: the applied policy's level, `dev` when no policy was ever applied (a dev phone). */
    val lockdownLevelApplied: String,
    val policyVersionApplied: Long?,
    val policyApplyErrors: List<String>,
    val restrictionsApplied: Map<String, Boolean>?,
    val batteryOptimisationIgnored: Boolean?,
    val blockingActive: Boolean,
    val blockingSinceMs: Long?,
    val suspendedPackages: List<String>?,
    val locationEnabled: Boolean,
    val locationMode: String?,
    val timeZone: String?,
    val playServicesVersion: Int?,
    val batteryPct: Int,
    val charging: Boolean?,
    val freeStorageMb: Int?,
)

fun interface DeviceFacts {
    fun read(): DeviceFactsSnapshot
}

/** Device-wide integrity bookkeeping (one phone, any user): when the last token was got and the last report that carried one. */
interface IntegrityState {
    var lastTokenAtMs: Long?
    var lastAttemptAtMs: Long?
    /** `client_uuid` of the latest `device_status` that carried a Play Integrity token: `GeoFix.device.integrity_ref`. */
    var integrityRef: String?
    /**
     * Set at login (`periodic`) and check-in (`check_in`): the report trigger of the evidence the next sync run collects even
     * when the refresh interval has not passed; null when nothing is asked for.
     */
    var evidenceWanted: String?

    class Memory : IntegrityState {
        override var lastTokenAtMs: Long? = null
        override var lastAttemptAtMs: Long? = null
        override var integrityRef: String? = null
        override var evidenceWanted: String? = null
    }
}

/**
 * Queues `device_status` records from the sync worker (F-SYS-031, N-026; docs/24 s10.3, s8.7; rulings R12, R13, R18):
 * - an `integrity_change` report when the integrity signals (developer options, ADB, auto time, owner, mock apps,
 *   `root_hints`) differ from the last one queued;
 * - Play Integrity evidence at login, at check-in and every `cfg.device.integrity_refresh_h` (24 h), as `play_integrity`
 *   or, when the phone tried and got none, the `play_integrity_unavailable` marker (exactly one of the two).
 *
 * It runs before a batch is built, so the report rides that batch. It never throws, never blocks a sale and is never
 * awaited before login completes (the worker runs it). The record is the outbox row (no domain table); a kill between the
 * commit and [IntegritySignalsTracker.recordSent] reports the change once more, never misses it.
 */
class DeviceStatusReporter(
    private val facts: DeviceFacts,
    private val signals: () -> IntegritySignals,
    private val tracker: IntegritySignalsTracker,
    private val evidence: suspend () -> IntegrityResult,
    private val state: IntegrityState,
    private val clock: TrustedClockSource,
    private val appVersion: String,
    /** False when the build has no Play Integrity project: the marker `not_configured` is sent without any network call. */
    private val evidenceConfigured: Boolean = true,
    /** A failed attempt (offline, Play error) is retried at most this often, so a flaky phone does not spend data. */
    private val retryAfterFailureMs: Long = 60 * 60_000L,
    /** Hard deadline of one evidence attempt (nonce + Play Integrity); a slow link never holds the upload longer. */
    private val evidenceDeadlineMs: Long = 25_000L,
) {
    private val lock = Mutex()

    /** Login (`periodic`) and check-in (`check_in`) ask for fresh evidence; the next sync run collects it (never awaited). */
    fun wantEvidence(trigger: String = "periodic") { state.evidenceWanted = trigger }

    /**
     * Returns the `client_uuid` of the queued report, or null when nothing was due. With [allowEvidence] false (Sales
     * Submit, the Sync button, check-out: uploads somebody waits for) only a local signals report is made; evidence waits
     * for the next background run. One run at a time per phone, so two workers never ask Play twice.
     */
    suspend fun beforeBatch(db: AronDatabase, allowEvidence: Boolean = true): String? = try {
        lock.withLock { report(db, allowEvidence) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null // evidence only: a failure here must never stop the upload of the day's records
    }

    private suspend fun report(db: AronDatabase, allowEvidence: Boolean): String? {
        val now = clock.nowMs()
        val reference = ReferenceRepository(db)
        // Without readable signals the report's required booleans would be guesses: nothing is sent and nothing is asked.
        val current = runCatching(signals).getOrNull() ?: return null
        val changed = tracker.changed(current)
        val refreshH = reference.config("cfg.device.integrity_refresh_h", SyncEngine.iso(now))?.trim()?.toIntOrNull() ?: 24
        val lastAttempt = state.lastAttemptAtMs
        val cooled = lastAttempt == null || now < lastAttempt || now - lastAttempt >= retryAfterFailureMs
        val asked = state.evidenceWanted
        val wantEvidence = allowEvidence && (asked != null || (IntegrityEvidenceService.due(state.lastTokenAtMs, now, refreshH) && cooled))
        if (!changed && !wantEvidence) return null

        val result = if (wantEvidence) {
            state.lastAttemptAtMs = now
            // Tried means exactly one of play_integrity and play_integrity_unavailable (R12).
            if (!evidenceConfigured) {
                IntegrityResult.Unavailable(IntegrityUnavailable.NOT_CONFIGURED)
            } else {
                try {
                    withTimeoutOrNull(evidenceDeadlineMs) { evidence() } ?: IntegrityResult.Unavailable(IntegrityUnavailable.TIMEOUT)
                } catch (e: CancellationException) {
                    if (e is TimeoutCancellationException) IntegrityResult.Unavailable(IntegrityUnavailable.TIMEOUT) else throw e
                } catch (e: Exception) {
                    IntegrityResult.Unavailable(IntegrityUnavailable.API_ERROR, e.javaClass.simpleName)
                }
            }
        } else {
            null
        }
        val f = facts.read()
        val s = current
        val pending = db.outboxDao().unsentCount()
        val report = DeviceStatusReport(
            reportedAt = SyncEngine.iso(now),
            trigger = if (changed) "integrity_change" else asked ?: "periodic",
            appVersion = appVersion,
            deviceInfo = f.deviceInfo,
            deviceOwner = s.deviceOwner,
            lockdownLevelApplied = f.lockdownLevelApplied,
            policyVersionApplied = f.policyVersionApplied,
            policyApplyErrors = f.policyApplyErrors.take(30).map { DeviceStatusReportPolicyApplyErrors(it.take(80), "failed") }.ifEmpty { null },
            restrictionsApplied = f.restrictionsApplied,
            blockingActive = f.blockingActive,
            blockingSince = f.blockingSinceMs?.let(SyncEngine::iso),
            suspendedPackages = f.suspendedPackages?.take(300),
            batteryOptimisationIgnored = f.batteryOptimisationIgnored,
            locationEnabled = f.locationEnabled,
            locationMode = f.locationMode,
            devOptionsEnabled = s.devOptionsEnabled,
            adbEnabled = s.adbEnabled,
            autoTimeEnabled = s.autoTimeEnabled,
            timeZone = f.timeZone?.take(40),
            mockLocationApps = s.mockLocationApps.distinct().take(50),
            playServicesVersion = f.playServicesVersion,
            playIntegrity = (result as? IntegrityResult.Evidence)?.let {
                buildJsonObject { put("token", JsonPrimitive(it.token)); put("nonce", JsonPrimitive(it.nonce)) }
            },
            playIntegrityUnavailable = (result as? IntegrityResult.Unavailable)?.let { PlayIntegrityUnavailable(it.reason.wire, it.wireDetail) },
            // A clean phone sends an empty list (R13); the member is never left out once signals were read (R18).
            rootHints = s.rootHints.distinct().take(16),
            pendingRows = pending,
            batteryPct = f.batteryPct.coerceIn(0, 100),
            charging = f.charging,
            freeStorageMb = f.freeStorageMb?.coerceAtLeast(0),
        )
        val uuid = ClientIds.newUuid()
        CaptureRepository(db) { SyncEngine.iso(clock.nowMs()) }.recordDeviceStatus(uuid, meta(reference, now), report)
        // Only once the report is safely in the outbox: a kill before this point reports again.
        if (changed) tracker.recordSent(current)
        if (wantEvidence) state.evidenceWanted = null
        if (result is IntegrityResult.Evidence) {
            state.lastTokenAtMs = now
            state.integrityRef = uuid
        }
        return uuid
    }

    private suspend fun meta(reference: ReferenceRepository, now: Long) = CaptureMeta(
        businessDate = BusinessDate.of(now).toString(),
        capturedAt = SyncEngine.iso(now),
        capturedElapsedMs = clock.elapsedRealtimeMs(),
        bootCount = clock.bootCountNow(),
        clockOffsetMs = clock.clockOffsetMs(),
        capturedOffline = false,
        routeId = null,
        bundleVersion = reference.bundleVersion(),
        configVersion = reference.configVersionHeld(),
    )
}
