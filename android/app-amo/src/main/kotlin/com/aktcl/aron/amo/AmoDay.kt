package com.aktcl.aron.amo

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.ReferenceRepository
import com.aktcl.aron.core.geo.FixManager
import com.aktcl.aron.core.session.SessionComponents
import com.aktcl.aron.core.sync.SyncScheduler
import com.aktcl.aron.core.sync.device.DayConfig
import com.aktcl.aron.core.sync.device.DeviceRuntime
import com.aktcl.aron.dpc.DeviceOwnerPolicy
import com.aktcl.aron.feature.attendance.AttendanceFlow
import com.aktcl.aron.feature.outlet.CaptureMetaProvider
import com.aktcl.aron.feature.outlet.FixManagerSource
import com.aktcl.aron.rules.BusinessDate
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * What one logged-in AMO needs for the day, on the user's own database (the AMO twin of the SR's day object, thin on
 * purpose: it grows with the AMO rows and reuses the feature-module flows). The AMO has no route of its own, so records
 * carry no route id until a control or joint call names one. Nothing here waits for the network.
 */
class AmoDay(
    val userId: Long,
    private val context: Context,
    private val db: AronDatabase,
    components: SessionComponents,
    private val scheduler: SyncScheduler,
    fixManager: FixManager,
    private val deviceRuntime: DeviceRuntime? = null,
) {
    private val clock = components.trustedClock
    val capture = CaptureRepository(db) { iso(clock.nowMs()) }
    val reference = ReferenceRepository(db)

    private val isoMillis = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC)
    fun iso(ms: Long): String = isoMillis.format(Instant.ofEpochMilli(ms))
    fun businessDate(): String = BusinessDate.of(clock.nowMs()).toString()
    fun dhakaMinutesNow(): Int = ((clock.nowMs() + BusinessDate.DHAKA_OFFSET_MS) / 60_000L).mod(24 * 60L).toInt()
    fun requestSync() = scheduler.requestSync(userId, SyncTrigger.WRITE_DEBOUNCE)

    fun online(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Route a control or joint call is on, set by those screens; null otherwise. */
    @Volatile var routeId: Long? = null

    @Volatile private var bundleVersion: String? = null
    @Volatile private var configVersion: Long = 0

    /** Re-reads the bundle stamp the records carry; cheap, called when the day opens and after a bundle change. */
    suspend fun reload() {
        bundleVersion = reference.bundleVersion()
        configVersion = db.referenceDao().meta(ReferenceRepository.KEY_CONFIG_VERSION)?.toLongOrNull() ?: 0L
    }

    val metaProvider = CaptureMetaProvider { rid ->
        val now = clock.nowMs()
        CaptureMeta(
            businessDate = BusinessDate.of(now).toString(), capturedAt = iso(now), capturedElapsedMs = clock.elapsedRealtimeMs(),
            bootCount = clock.bootCountNow(), clockOffsetMs = clock.clockOffsetMs(), capturedOffline = !online(),
            routeId = rid.takeIf { it != 0L } ?: routeId, actingForUserId = null, bundleVersion = bundleVersion,
            bundleStale = false, configVersion = configVersion,
        )
    }

    /** Attendance and requests always take their own fix (no reuse). */
    val fixSource = FixManagerSource(fixManager)

    private val addressResolver = com.aktcl.aron.core.map.AddressResolver(
        online = ::online,
        backend = com.aktcl.aron.core.map.AndroidGeocodeBackend(context),
        language = { com.aktcl.aron.core.ui.AppLocale.current(context) },
        last = com.aktcl.aron.core.map.PrefsLastAddressStore(context, userId),
    )

    /** F-AMO-003: the SR's attendance flow, unchanged (check-in/out events, DPC hooks, integrity evidence, address offline as coordinates). */
    val attendance = AttendanceFlow(
        fixes = fixSource, metaProvider = metaProvider,
        committer = { e, f ->
            capture.recordAttendance(e, f)
            runCatching {
                val policy = DeviceOwnerPolicy.get(context)
                if (e.kind == "check_in") policy.onCheckInCommitted() else policy.onCheckOutCommitted()
            }
            if (e.kind == "check_in") runCatching { deviceRuntime?.wantEvidenceAtCheckIn() }
            runCatching { scheduler.requestSync(userId, if (e.kind == "check_in") SyncTrigger.WRITE_DEBOUNCE else SyncTrigger.CHECKOUT) }
        },
        routeIdOf = { routeId }, nowIso = { iso(clock.nowMs()) }, dhakaMinutesNow = ::dhakaMinutesNow,
        checkoutEarliestMinutes = { deviceRuntime?.dayConfig?.checkoutEarliestMinutes ?: DayConfig.DEFAULT_CHECKOUT_MINUTES },
        addressResolver = { lat, lng -> addressResolver.displayText(context, lat, lng) },
    )

    suspend fun attendanceToday() = db.captureDao().attendanceOn(businessDate())

    /** "HH:mm" of the configured earliest check-out, for the screen's hint. */
    fun checkoutAtLabel(): String {
        val m = deviceRuntime?.dayConfig?.checkoutEarliestMinutes ?: DayConfig.DEFAULT_CHECKOUT_MINUTES
        return "%02d:%02d".format(m / 60, m % 60)
    }
}
