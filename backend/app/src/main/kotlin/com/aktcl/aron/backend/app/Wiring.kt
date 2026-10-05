package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.AuthDeps
import com.aktcl.aron.backend.auth.HashLimiter
import com.aktcl.aron.backend.auth.JdbiLockoutStore
import com.aktcl.aron.backend.auth.JdbiRefreshStore
import com.aktcl.aron.backend.auth.JdbiUserStore
import com.aktcl.aron.backend.auth.LoginService
import com.aktcl.aron.backend.auth.NoDevices
import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.auth.RefreshService
import com.aktcl.aron.backend.auth.TokenIssuer
import com.aktcl.aron.backend.auth.authRoutes
import com.aktcl.aron.backend.masterdata.GeoRepository
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
            // Device tables arrive with N-007 (V0010); until then no phone is known and cfg.device.require_enrolled decides.
            val devices = NoDevices
            val login = LoginService(users, devices, PasswordHasher(), HashLimiter(s.hashConcurrency, s.hashQueueMax), JdbiLockoutStore(db), issuer, refresh, reach, config, clock)
            val auth = AuthDeps(login, refresh, issuer, users, devices, keys, reach, config, guard, clock, trustedFrontDoorId = s.frontDoorId)
            val outlets = OutletsDeps(db, geo, reach, guard, clock)
            // The server generation table arrives with the sync schema (N-006); until then the nil generation is sent.
            return Wiring(clock, config, db, { NIL_GENERATION }, s.build) {
                authRoutes(auth)
                outletRoutes(outlets)
            }
        }
    }
}
