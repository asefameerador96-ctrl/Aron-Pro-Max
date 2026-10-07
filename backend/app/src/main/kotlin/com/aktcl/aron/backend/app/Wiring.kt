package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.analytics.DashboardDeps
import com.aktcl.aron.backend.analytics.DashboardService
import com.aktcl.aron.backend.analytics.OpsDeps
import com.aktcl.aron.backend.analytics.OpsService
import com.aktcl.aron.backend.analytics.ReportDeps
import com.aktcl.aron.backend.analytics.ReportEngine
import com.aktcl.aron.backend.analytics.ReportHandlers
import com.aktcl.aron.backend.analytics.DailyTrackingDeps
import com.aktcl.aron.backend.analytics.DailyTrackingService
import com.aktcl.aron.backend.analytics.AppTeamDeps
import com.aktcl.aron.backend.analytics.TeamService
import com.aktcl.aron.backend.analytics.dailyTrackingRoutes
import com.aktcl.aron.backend.analytics.dashboardRoutes
import com.aktcl.aron.backend.analytics.appTeamRoutes
import com.aktcl.aron.backend.analytics.opsRoutes
import com.aktcl.aron.backend.analytics.reportRoutes
import com.aktcl.aron.backend.auth.AuthDeps
import com.aktcl.aron.backend.auth.HashLimiter
import com.aktcl.aron.backend.auth.JdbiLockoutStore
import com.aktcl.aron.backend.auth.JdbiRefreshStore
import com.aktcl.aron.backend.auth.JdbiUserStore
import com.aktcl.aron.backend.auth.LoginService
import com.aktcl.aron.backend.auth.JdbiDeviceStore
import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.auth.RefreshService
import com.aktcl.aron.backend.auth.TokenIssuer
import com.aktcl.aron.backend.auth.authRoutes
import com.aktcl.aron.backend.config.ConfigDeps
import com.aktcl.aron.backend.config.ConfigResolver
import com.aktcl.aron.backend.config.ConfigPermissions
import com.aktcl.aron.backend.config.ConfigPermissionsDeps
import com.aktcl.aron.backend.config.ConfigPublic
import com.aktcl.aron.backend.config.ConfigPublicDeps
import com.aktcl.aron.backend.config.configPermissionRoutes
import com.aktcl.aron.backend.config.configPublicRoutes
import com.aktcl.aron.backend.config.ConfigService
import com.aktcl.aron.backend.config.ConfigTools
import com.aktcl.aron.backend.config.ConfigToolsDeps
import com.aktcl.aron.backend.config.configToolRoutes
import com.aktcl.aron.backend.config.ConfigDelta
import com.aktcl.aron.backend.config.ConfigDeltaDeps
import com.aktcl.aron.backend.config.configAdminRoutes
import com.aktcl.aron.backend.config.configDeltaRoutes
import com.aktcl.aron.backend.masterdata.DeviceOtpDeps
import com.aktcl.aron.backend.masterdata.GeoRepository
import com.aktcl.aron.backend.masterdata.AdminPricesDeps
import com.aktcl.aron.backend.masterdata.AdminProductsDeps
import com.aktcl.aron.backend.masterdata.OtpCipher
import com.aktcl.aron.backend.masterdata.AdminMasterDeps
import com.aktcl.aron.backend.masterdata.adminMasterRoutes
import com.aktcl.aron.backend.masterdata.VisitPlanDeps
import com.aktcl.aron.backend.masterdata.visitPlanRoutes
import com.aktcl.aron.backend.masterdata.RouteAssignmentsDeps
import com.aktcl.aron.backend.masterdata.routeAssignmentRoutes
import com.aktcl.aron.backend.masterdata.LeaveDeps
import com.aktcl.aron.backend.masterdata.leaveRoutes
import com.aktcl.aron.backend.masterdata.TutorialsDeps
import com.aktcl.aron.backend.masterdata.tutorialRoutes
import com.aktcl.aron.backend.masterdata.SupportUploadDeps
import com.aktcl.aron.backend.masterdata.supportUploadRoutes
import com.aktcl.aron.backend.masterdata.FeedbackDeps
import com.aktcl.aron.backend.masterdata.feedbackRoutes
import com.aktcl.aron.backend.masterdata.AdminContentDeps
import com.aktcl.aron.backend.masterdata.adminContentRoutes
import com.aktcl.aron.backend.masterdata.BlobSasIssuer
import com.aktcl.aron.backend.masterdata.adminPricesRoutes
import com.aktcl.aron.backend.masterdata.adminProductsRoutes
import com.aktcl.aron.backend.masterdata.deviceReplaceRoutes
import com.aktcl.aron.backend.masterdata.deviceOtpRoutes
import com.aktcl.aron.backend.masterdata.OutletsDeps
import com.aktcl.aron.backend.masterdata.SqlReachResolver
import com.aktcl.aron.backend.masterdata.outletRoutes
import com.aktcl.aron.backend.platform.AccessTokenVerifier
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.DbServerConfig
import com.aktcl.aron.backend.platform.JwtKeys
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.Settings
import io.ktor.server.routing.Route
import com.aktcl.aron.backend.masterdata.SqlRoutePlanner
import com.aktcl.aron.backend.platform.RecordHandler
import com.aktcl.aron.backend.platform.RecordHandlers
import com.aktcl.aron.backend.sync.BundleService
import com.aktcl.aron.backend.sync.IngestService
import com.aktcl.aron.backend.sync.ServerGeneration
import com.aktcl.aron.backend.sync.SyncDeps
import com.aktcl.aron.backend.sync.syncRoutes

/** The object graph of the API process; tests build their own with throwaway keys and in-memory stores. */
class Wiring(
    val clock: AronClock,
    val config: ServerConfig,
    val database: Database?,
    val generation: () -> String,
    val build: String,
    val mount: Route.() -> Unit,
    val frontDoorId: String? = null,
    /** Admission control and backpressure (N-056); null in tests that do not exercise it. */
    val admission: com.aktcl.aron.backend.analytics.AdmissionControl? = null,
) {
    companion object {
        /**
         * The ingest extension point (docs/24 s4.2): type-specific checks and side effects of device records, one line
         * per handler, append-only (each module registers its own; see backend/platform RecordHandler.kt).
         */
        @Suppress("UNUSED_PARAMETER")
        fun recordHandlers(db: Database, clock: AronClock): List<RecordHandler> = listOf(
            com.aktcl.aron.backend.config.ConfigAckHandler(),
        )

        /** [extraRecordHandlers] are for tests only; production handlers are listed in [recordHandlers]. */
        fun production(s: Settings, clock: AronClock = AronClock.SYSTEM, extraRecordHandlers: List<RecordHandler> = emptyList()): Wiring {
            val db = Database.fromSettings(s)
            val config = DbServerConfig(db, RegistryDefaults(s.env), clock)
            val keys = JwtKeys.fromSettings(s)
            val users = JdbiUserStore(db, clock)
            val guard = AuthGuardDeps(AccessTokenVerifier(keys, clock), users, config, clock)
            val geo = GeoRepository(db, clock)
            val reach = SqlReachResolver(db, geo, clock)
            val issuer = TokenIssuer(keys, config, clock)
            val refresh = RefreshService(JdbiRefreshStore(db), config, keys.derivedSecret("aron-refresh-rotation-v1"), clock)
            val devices = JdbiDeviceStore(db)
            val login = LoginService(users, devices, PasswordHasher(), HashLimiter(s.hashConcurrency, s.hashQueueMax), JdbiLockoutStore(db), issuer, refresh, reach, config, clock)
            val auth = AuthDeps(login, refresh, issuer, users, devices, keys, reach, config, guard, clock, trustedFrontDoorId = s.frontDoorId)
            val outlets = OutletsDeps(db, geo, reach, guard, clock)
            val dashboardService = DashboardService(db, clock)
            val dashboards = DashboardDeps(dashboardService, reach, guard, clock)
            val ops = OpsDeps(OpsService(db, config, clock), dashboardService, reach, guard, clock)
            val tracking = DailyTrackingDeps(DailyTrackingService(db, config, clock), reach, guard, clock)
            val team = AppTeamDeps(TeamService(db, dashboardService, clock), reach, guard, clock)
            val reports = ReportDeps(db, ReportEngine(db, config, clock, ReportHandlers.all), reach, guard, clock)
            val configResolver = ConfigResolver(db, clock)
            val toolsReach = com.aktcl.aron.backend.config.NodeReach { p, z -> reach.reach(p.userId, p.role, p.scopeVersion, com.aktcl.aron.rules.BusinessDate.of(clock.now().toEpochMilli()).let { d -> java.time.LocalDate.of(d.year, d.monthNumber, d.dayOfMonth) }).coversZone(z) }
            val configService = ConfigService(db, configResolver, clock, toolsReach)
            val configDeps = ConfigDeps(configService, guard, clock)
            val toolsDeps = ConfigToolsDeps(ConfigTools(db, configService, configResolver, clock, toolsReach), guard, com.aktcl.aron.backend.config.ConfigGeoReports(db, configService, configResolver, clock))
            val permDeps = ConfigPermissionsDeps(ConfigPermissions(db, configService, clock), guard)
            val publicDeps = ConfigPublicDeps(ConfigPublic(db, configResolver, clock), guard)
            // Azure user-delegation SAS via the managed identity when ARON_BLOB_ACCOUNT is set (Azure); 503 elsewhere
            // (docs/requests/backend-admin-blob-sas.md).
            val blob: BlobSasIssuer = AzureBlobSasIssuer.fromEnvironment() ?: com.aktcl.aron.backend.masterdata.UnconfiguredBlobSasIssuer
            val otpDeps = DeviceOtpDeps(db, reach, OtpCipher(keys.derivedSecret("aron-device-otp-v1")), config, guard, clock)
            val deltaDeps = ConfigDeltaDeps(ConfigDelta(db, configResolver, clock), configService, guard)
            val generation = ServerGeneration(db)
            val sync = SyncDeps(BundleService(db, config, SqlRoutePlanner(db, geo, config), clock), guard, IngestService(db, config, reach, clock, generation::current, RecordHandlers(recordHandlers(db, clock) + extraRecordHandlers)), db, config, clock)
            val hikari = db.write as? com.zaxxer.hikari.HikariDataSource
            val admission = com.aktcl.aron.backend.analytics.AdmissionControl(
                ingestCapacity = runCatching { config.int("cfg.api.inflight_batches_per_replica") }.getOrDefault(64),
                dbWaiting = { hikari?.hikariPoolMXBean?.threadsAwaitingConnection ?: 0 },
            )
            return Wiring(clock, config, db, generation::current, s.build, mount = {
                authRoutes(auth)
                outletRoutes(outlets)
                dashboardRoutes(dashboards)
                appTeamRoutes(team)
                dailyTrackingRoutes(tracking)
                opsRoutes(ops)
                reportRoutes(reports)
                configAdminRoutes(configDeps)
                configDeltaRoutes(deltaDeps)
                deviceOtpRoutes(otpDeps)
                deviceReplaceRoutes(otpDeps)
                visitPlanRoutes(VisitPlanDeps(db, reach, config, guard, clock))
                routeAssignmentRoutes(RouteAssignmentsDeps(db, reach, guard, clock))
                leaveRoutes(LeaveDeps(db, reach, config, guard, clock))
                tutorialRoutes(TutorialsDeps(db, blob, guard))
                supportUploadRoutes(SupportUploadDeps(db, blob, config, guard, clock))
                feedbackRoutes(FeedbackDeps(db, reach, config, guard, clock))
                adminContentRoutes(AdminContentDeps(db, blob, config, guard, clock))
                adminMasterRoutes(AdminMasterDeps(db, geo, reach, guard, PasswordHasher()::hash, clock))
                adminProductsRoutes(AdminProductsDeps(db, guard, clock))
                adminPricesRoutes(AdminPricesDeps(db, config, guard, clock))
                configToolRoutes(toolsDeps)
                configPermissionRoutes(permDeps)
                configPublicRoutes(publicDeps)
                syncRoutes(sync)
            }, frontDoorId = s.frontDoorId, admission = admission)
        }
    }
}
