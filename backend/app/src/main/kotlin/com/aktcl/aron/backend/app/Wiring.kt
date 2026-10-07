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
import com.aktcl.aron.backend.analytics.devices.AttestationTrust
import com.aktcl.aron.backend.analytics.devices.DeviceDeps
import com.aktcl.aron.backend.analytics.devices.DeviceService
import com.aktcl.aron.backend.analytics.devices.EnrolmentSettings
import com.aktcl.aron.backend.analytics.devices.deviceRoutes
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
import com.aktcl.aron.backend.masterdata.DataVoidDeps
import com.aktcl.aron.backend.masterdata.dataVoidRoutes
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
import com.aktcl.aron.backend.sync.taskRoutes
import com.aktcl.aron.backend.notify.notificationRoutes
import com.aktcl.aron.backend.notify.pushRoutes

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
    /** [generation] without I/O, for the health probes (AUD-REL-01). */
    val cachedGeneration: () -> String = generation,
) {
    companion object {
        /**
         * The ingest extension point (docs/24 s4.2): type-specific checks and side effects of device records, one line
         * per handler, append-only (each module registers its own; see backend/platform RecordHandler.kt).
         */
        @Suppress("UNUSED_PARAMETER")
        fun recordHandlers(db: Database, clock: AronClock): List<RecordHandler> = listOf(
            com.aktcl.aron.backend.config.ConfigAckHandler(),
            com.aktcl.aron.backend.masterdata.DomainEventProducer(),
            com.aktcl.aron.backend.masterdata.DataVoidBarrierHandler(com.aktcl.aron.backend.sync.TypeRules.BY_TYPE.keys),
            com.aktcl.aron.backend.sync.GeoRecheckHandler(),
        )

        /** [extraRecordHandlers] and [pushSender] are for tests only; production handlers are listed in [recordHandlers]. */
        fun production(
            s: Settings, clock: AronClock = AronClock.SYSTEM, extraRecordHandlers: List<RecordHandler> = emptyList(),
            /** Tests only: a push sender in place of FCM. */
            pushSender: com.aktcl.aron.backend.notify.PushSender? = null,
        ): Wiring {
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
            val outlets = OutletsDeps(db, geo, reach, guard, clock)
            val dashboardService = DashboardService(db, clock)
            val dashboards = DashboardDeps(dashboardService, reach, guard, clock)
            // Enrolment (N-031): the public API URL the QR carries and the SHA-256 of the Google attestation roots to trust in production (new environment values, docs/requests/backend-reports-device-env.md).
            val enrolment = EnrolmentSettings(
                System.getenv("ARON_PUBLIC_API_URL") ?: "https://localhost:8080", s.env.name.lowercase(),
                AttestationTrust(System.getenv("ARON_ATTESTATION_ROOTS").orEmpty().split(',').map { it.trim().lowercase() }.filter { it.length == 64 }.toSet()),
            )
            val deviceEnrolment = DeviceDeps(DeviceService(db, config, keys, enrolment, clock), reach, guard, clock)
            val ops = OpsDeps(OpsService(db, config, clock), dashboardService, reach, guard, clock)
            val tracking = DailyTrackingDeps(DailyTrackingService(db, config, clock), reach, guard, clock)
            val team = AppTeamDeps(TeamService(db, dashboardService, clock), reach, guard, clock)
            val reports = ReportDeps(db, ReportEngine(db, config, clock, ReportHandlers.all), reach, guard, clock)
            val configResolver = ConfigResolver(db, clock)
            val toolsReach = com.aktcl.aron.backend.config.NodeReach { p, z -> reach.reach(p.userId, p.role, p.scopeVersion, com.aktcl.aron.rules.BusinessDate.of(clock.now().toEpochMilli()).let { d -> java.time.LocalDate.of(d.year, d.monthNumber, d.dayOfMonth) }).coversZone(z) }
            val configService = ConfigService(db, configResolver, clock, toolsReach)
            val configDeps = ConfigDeps(configService, guard, clock)
            val toolsDeps = ConfigToolsDeps(ConfigTools(db, configService, configResolver, clock, toolsReach), guard, com.aktcl.aron.backend.config.ConfigGeoReports(db, configService, configResolver, clock))
            val permissions = ConfigPermissions(db, configService, clock)
            // One cipher for the bind OTP: login creates it sealed with a keyed verifier, the TSO panel opens it (s8.1).
            val otpCipher = OtpCipher(keys.derivedSecret("aron-device-otp-v1"))
            val otpSealer = object : com.aktcl.aron.backend.auth.OtpSealer {
                override fun seal(otp: String, userId: Long) = otpCipher.seal(otp, userId)
                override fun mac(otp: String, userId: Long) = otpCipher.mac(otp, userId)
                override fun digits(length: Int) = otpCipher.digits(length)
            }
            // cfg.auth.password_min_len is role-scoped (12 for web roles, s9.5): resolved by the role's ordinal.
            val minPasswordLen: (com.aktcl.aron.contract.Role) -> Int = { role ->
                val ordinal = db.jdbi.withHandle<Long?, Exception> { h -> h.createQuery("SELECT ordinal FROM app.role_def WHERE role = :r").bind("r", role.wire).mapTo(Long::class.java).findOne().orElse(null) }
                val chain = listOfNotNull(ordinal?.let { com.aktcl.aron.backend.config.ScopeNode("role", it) }, com.aktcl.aron.backend.config.ScopeNode("global", 0))
                (configResolver.resolve("cfg.auth.password_min_len", chain, clock.now()).value as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 12
            }
            val login = LoginService(
                users, devices, PasswordHasher(), HashLimiter(s.hashConcurrency, s.hashQueueMax), JdbiLockoutStore(db), issuer, refresh, reach, config, clock,
                passwords = com.aktcl.aron.backend.auth.JdbiPasswordStore(db), minPasswordLen = minPasswordLen,
                binds = com.aktcl.aron.backend.auth.JdbiBindStore(db), otpSealer = otpSealer,
            )
            val permDeps = ConfigPermissionsDeps(permissions, guard)
            val auth = AuthDeps(
                login, refresh, issuer, users, devices, keys, reach, config, guard, clock, trustedFrontDoorId = s.frontDoorId,
                menusForRole = { role -> permissions.menusForRole(role).map { kotlinx.serialization.json.Json.encodeToJsonElement(com.aktcl.aron.backend.config.MenuPermissionDto.serializer(), it) } },
            )
            val publicDeps = ConfigPublicDeps(ConfigPublic(db, configResolver, clock), guard)
            // Azure user-delegation SAS via the managed identity when ARON_BLOB_ACCOUNT is set (Azure); 503 elsewhere
            // (docs/requests/backend-admin-blob-sas.md).
            val blob: BlobSasIssuer = AzureBlobSasIssuer.fromEnvironment() ?: com.aktcl.aron.backend.masterdata.UnconfiguredBlobSasIssuer
            val otpDeps = DeviceOtpDeps(db, reach, otpCipher, config, guard, clock)
            val deltaDeps = ConfigDeltaDeps(ConfigDelta(db, configResolver, clock), configService, guard)
            val generation = ServerGeneration(db)
            // N-037: FCM nudges; off without the service account (dev, tests), and switched by cfg.ops/cfg.notify.
            val push = com.aktcl.aron.backend.notify.PushNotifier(db, config, pushSender ?: s.fcmServiceAccountJson?.let { com.aktcl.aron.backend.notify.FcmPushSender(it) }, clock) { userId, key ->
                // The switch as it applies to the user: zone, territory, division, wing, then global (s9.5 scopes G W D T Z).
                runCatching {
                    val chain = db.jdbi.withHandle<List<com.aktcl.aron.backend.config.ScopeNode>, Exception> { h ->
                        h.createQuery(
                            """
                            SELECT z.id, t.id, dv.id, dv.wing_id FROM app.app_user u
                            JOIN app.zone z ON z.id = COALESCE(u.home_zone_id, (SELECT r.zone_id FROM app.route_assignment a JOIN app.route r ON r.id = a.route_id
                                WHERE a.user_id = u.id AND a.kind = 'primary' AND a.valid_from <= current_date AND (a.valid_to IS NULL OR a.valid_to > current_date) ORDER BY a.valid_from DESC LIMIT 1))
                            JOIN app.territory t ON t.id = z.territory_id JOIN app.division dv ON dv.id = t.division_id
                            WHERE u.id = :u
                            """.trimIndent(),
                        ).bind("u", userId).map { rs, _ ->
                            listOf(com.aktcl.aron.backend.config.ScopeNode("zone", rs.getLong(1)), com.aktcl.aron.backend.config.ScopeNode("territory", rs.getLong(2)),
                                com.aktcl.aron.backend.config.ScopeNode("division", rs.getLong(3)), com.aktcl.aron.backend.config.ScopeNode("wing", rs.getLong(4)))
                        }.findOne().orElse(emptyList())
                    } + com.aktcl.aron.backend.config.ScopeNode("global", 0)
                    (configResolver.resolve(key, chain, clock.now()).value as kotlinx.serialization.json.JsonPrimitive).content == "true"
                }.getOrDefault(false)
            }
            val sync = SyncDeps(BundleService(db, config, SqlRoutePlanner(db, geo, config), clock), guard, IngestService(db, config, reach, clock, generation::current, RecordHandlers(recordHandlers(db, clock) + com.aktcl.aron.backend.sync.TaskRecords(reach, push) + extraRecordHandlers)), db, config, clock)
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
                deviceRoutes(deviceEnrolment)
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
                dataVoidRoutes(DataVoidDeps(db, reach, guard, clock))
                adminContentRoutes(AdminContentDeps(db, blob, config, guard, clock))
                adminMasterRoutes(AdminMasterDeps(db, geo, reach, guard, PasswordHasher()::hash, clock, config = config))
                adminProductsRoutes(AdminProductsDeps(db, guard, clock))
                adminPricesRoutes(AdminPricesDeps(db, config, guard, clock))
                configToolRoutes(toolsDeps)
                configPermissionRoutes(permDeps)
                configPublicRoutes(publicDeps)
                syncRoutes(sync)
                taskRoutes(com.aktcl.aron.backend.sync.TaskDeps(com.aktcl.aron.backend.sync.TaskService(db, reach, clock, push), guard))
                pushRoutes(com.aktcl.aron.backend.notify.PushDeps(db, config, guard, clock))
                notificationRoutes(com.aktcl.aron.backend.notify.NotificationDeps(db, config, reach, push, guard, clock))
            }, frontDoorId = s.frontDoorId, admission = admission, cachedGeneration = generation::cached)
        }
    }
}
