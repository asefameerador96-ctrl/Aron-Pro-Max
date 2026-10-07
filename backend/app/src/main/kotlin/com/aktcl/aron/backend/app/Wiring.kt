package com.aktcl.aron.backend.app

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
import com.aktcl.aron.backend.masterdata.OtpCipher
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
import com.aktcl.aron.backend.platform.NIL_GENERATION
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.Settings
import io.ktor.server.routing.Route

/** The object graph of the API process; tests build their own with throwaway keys and in-memory stores. */
class Wiring(
    val clock: AronClock,
    val config: ServerConfig,
    val database: Database?,
    val generation: () -> String,
    val build: String,
    val mount: Route.() -> Unit,
    val frontDoorId: String? = null,
) {
    companion object {
        fun production(s: Settings, clock: AronClock = AronClock.SYSTEM): Wiring {
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
            val configResolver = ConfigResolver(db, clock)
            val configService = ConfigService(db, configResolver, clock) { p, z -> reach.reach(p.userId, p.role, p.scopeVersion, com.aktcl.aron.rules.BusinessDate.of(clock.now().toEpochMilli()).let { d -> java.time.LocalDate.of(d.year, d.monthNumber, d.dayOfMonth) }).coversZone(z) }
            val configDeps = ConfigDeps(configService, guard, clock)
            val toolsDeps = ConfigToolsDeps(ConfigTools(db, configService, configResolver, clock), guard)
            val permDeps = ConfigPermissionsDeps(ConfigPermissions(db, configService, clock), guard)
            val publicDeps = ConfigPublicDeps(ConfigPublic(db, configResolver, clock), guard)
            val otpDeps = DeviceOtpDeps(db, reach, OtpCipher(keys.derivedSecret("aron-device-otp-v1")), config, guard, clock)
            val deltaDeps = ConfigDeltaDeps(ConfigDelta(db, configResolver, clock), configService, guard)
            // The server generation table arrives with the sync schema (N-006); until then the nil generation is sent.
            return Wiring(clock, config, db, { NIL_GENERATION }, s.build, mount = {
                authRoutes(auth)
                outletRoutes(outlets)
                configAdminRoutes(configDeps)
                configDeltaRoutes(deltaDeps)
                deviceOtpRoutes(otpDeps)
                configToolRoutes(toolsDeps)
                configPermissionRoutes(permDeps)
                configPublicRoutes(publicDeps)
            }, frontDoorId = s.frontDoorId)
        }
    }
}
