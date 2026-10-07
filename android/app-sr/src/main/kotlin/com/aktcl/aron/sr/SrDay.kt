package com.aktcl.aron.sr

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.ReferenceRepository
import com.aktcl.aron.core.geo.FixManager
import com.aktcl.aron.core.session.SessionComponents
import com.aktcl.aron.core.sync.SyncScheduler
import com.aktcl.aron.dpc.DeviceOwnerPolicy
import com.aktcl.aron.feature.attendance.AttendanceFlow
import com.aktcl.aron.feature.home.BundleFreshness
import com.aktcl.aron.feature.outlet.CaptureMetaProvider
import com.aktcl.aron.feature.outlet.FixManagerSource
import com.aktcl.aron.feature.outlet.GeoSettings
import com.aktcl.aron.feature.outlet.VisitFlow
import com.aktcl.aron.feature.outlet.VisitOutlet
import com.aktcl.aron.feature.outlet.VisitSession
import com.aktcl.aron.feature.stock.StockLoad
import com.aktcl.aron.feature.tasks.TaskBoard
import com.aktcl.aron.feature.tasks.TaskEventWrite
import com.aktcl.aron.feature.tasks.TaskItem
import com.aktcl.aron.feature.tasks.TaskStore
import com.aktcl.aron.rules.BusinessDate
import kotlinx.datetime.LocalDate
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Everything one logged-in SR needs for the day, built on the user's own database. No screen waits for the network. */
class SrDay(
    val userId: Long,
    private val context: Context,
    private val db: AronDatabase,
    private val components: SessionComponents,
    private val scheduler: SyncScheduler,
    fixManager: FixManager,
) {
    private val clock = components.trustedClock
    val capture = CaptureRepository(db) { iso(clock.nowMs()) }
    val reference = ReferenceRepository(db)
    val visitSession = VisitSession()

    private val isoMillis = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC)
    fun iso(ms: Long): String = isoMillis.format(Instant.ofEpochMilli(ms))
    fun currentMs(): Long = clock.nowMs()
    fun requestSync() = scheduler.requestSync(userId, SyncTrigger.WRITE_DEBOUNCE)
    fun businessDate(): String = BusinessDate.of(clock.nowMs()).toString()
    fun dhakaMinutesNow(): Int = ((clock.nowMs() + BusinessDate.DHAKA_OFFSET_MS) / 60_000L).mod(24 * 60L).toInt()

    @Volatile var routeId: Long? = null
    @Volatile var route: com.aktcl.aron.core.database.entity.RouteEntity? = null
    @Volatile var actingForUserId: Long? = null
    @Volatile var freshness: BundleFreshness = BundleFreshness.Missing
    @Volatile private var bundleVersion: String? = null
    @Volatile private var configVersion: Long = 0
    @Volatile var bundleDate: String? = null

    /** Reads the bundle stamp and freshness from Room once per screen load; the meta provider uses the cached values. */
    suspend fun refreshStamp() {
        bundleVersion = reference.bundleVersion()
        bundleDate = reference.businessDate()
        configVersion = db.referenceDao().meta(ReferenceRepository.KEY_CONFIG_VERSION)?.toLongOrNull() ?: 0L
        freshness = BundleFreshness.of(bundleDate?.let(LocalDate::parse), LocalDate.parse(businessDate()))
    }

    private fun online(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    val metaProvider = CaptureMetaProvider { rid ->
        val now = clock.nowMs()
        CaptureMeta(
            businessDate = BusinessDate.of(now).toString(), capturedAt = iso(now), capturedElapsedMs = clock.elapsedRealtimeMs(),
            bootCount = clock.bootCountNow(), clockOffsetMs = clock.clockOffsetMs(), capturedOffline = !online(),
            routeId = rid.takeIf { it != 0L } ?: routeId, actingForUserId = actingForUserId, bundleVersion = bundleVersion,
            bundleStale = freshness.markRecordsStale, configVersion = configVersion,
        )
    }

    private val fixSource = FixManagerSource(fixManager) { "outlets-" + businessDate() }

    val attendance = AttendanceFlow(
        fixes = fixSource, metaProvider = metaProvider,
        committer = { e, f ->
            capture.recordAttendance(e, f)
            val policy = DeviceOwnerPolicy.get(context)
            if (e.kind == "check_in") policy.onCheckInCommitted() else policy.onCheckOutCommitted()
            scheduler.requestSync(userId, if (e.kind == "check_in") SyncTrigger.WRITE_DEBOUNCE else SyncTrigger.CHECKOUT)
        },
        routeIdOf = { routeId }, nowIso = { iso(clock.nowMs()) }, dhakaMinutesNow = ::dhakaMinutesNow,
    )

    val visitFlow: VisitFlow = VisitFlow(
        fixes = fixSource, metaProvider = metaProvider,
        committer = { v, f -> capture.recordVisitOpen(v, f); scheduler.requestSync(userId, SyncTrigger.WRITE_DEBOUNCE) },
        session = visitSession, settings = { GeoSettings.DEFAULT }, nowIso = { iso(clock.nowMs()) },
        nextSequenceNo = { nextSequence++ },
    )
    @Volatile private var nextSequence = 1

    suspend fun attendanceToday() = db.captureDao().attendanceOn(businessDate())

    suspend fun nextSequenceFromStore() { nextSequence = db.captureDao().visitsOn(businessDate()).size + 1 }

    suspend fun todaysOutlets(): List<OutletEntity> {
        val date = businessDate()
        val routes = reference.routesOfDay(date).ifEmpty { bundleDate?.let { reference.routesOfDay(it) }.orEmpty() }
        val route = routes.firstOrNull { it.route.plannedToday } ?: routes.firstOrNull()
        routeId = route?.route?.routeId
        this.route = route?.route
        return routes.flatMap { it.outlets }
    }

    fun visitOutlet(o: OutletEntity) = VisitOutlet(
        outletId = o.outletId, routeId = o.routeId, name = o.name, code = o.code, lat = o.lat, lng = o.lng,
        locationBasis = if (o.lat == null || o.lng == null) "none" else if (o.locationConfirmed) "master" else "provisional",
        radiusM = o.radiusM, maxAccuracyM = o.maxAccuracyM,
    )

    suspend fun stockLoad(): StockLoad {
        val loaded = db.captureDao().stockBalanceOn(businessDate()).associate { it.skuId to it.qtyBase }
        return StockLoad(reference.skus(), loaded)
    }

    /** Task store until the Room task table lands (docs/requests/android-sr-a-task-tables.md): an empty list, never an error. */
    val taskBoard = TaskBoard(object : TaskStore {
        override suspend fun tasks(): List<TaskItem> = emptyList()
        override suspend fun resolve(taskUuid: String, resolvedAt: String, write: TaskEventWrite) = Unit
        override suspend fun syncedAt(): String? = null
    }, { iso(clock.nowMs()) })
}
