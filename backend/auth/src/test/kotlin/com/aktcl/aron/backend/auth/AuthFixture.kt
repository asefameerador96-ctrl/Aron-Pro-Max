package com.aktcl.aron.backend.auth

import com.aktcl.aron.backend.platform.AccessTokenVerifier
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.JwtKeys
import com.aktcl.aron.backend.platform.PlatformContext
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachNode
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.ScopeVersionLookup
import com.aktcl.aron.backend.platform.installAronPlatform
import com.aktcl.aron.contract.Role
import io.ktor.server.application.Application
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Throwaway ES256 key for tests only (never a real key; docs/24 s13.6). */
fun throwawayKeys(kid: String = "test-1"): JwtKeys {
    val g = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
    val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(g.generateKeyPair().private.encoded) + "\n-----END PRIVATE KEY-----\n"
    return JwtKeys(JwtKeys.parsePrivatePem(pem), kid)
}

class MutableClock(start: Instant = Instant.parse("2026-10-05T01:00:00Z")) : AronClock {
    private val t = AtomicReference(start)
    override fun now(): Instant = t.get()
    fun advance(seconds: Long) { t.updateAndGet { it.plusSeconds(seconds) } }
}

class FakeUsers : UserStore, ScopeVersionLookup {
    val byId = ConcurrentHashMap<Long, UserRecord>()
    override fun findByUsername(username: String) = byId.values.firstOrNull { it.username.equals(username, ignoreCase = true) }
    override fun findById(id: Long) = byId[id]
    override fun current(userId: Long) = byId[userId]?.scopeVersion
    fun add(u: UserRecord) { byId[u.id] = u }
}

class FakeDevices : DeviceStore {
    val byUuid = ConcurrentHashMap<String, DeviceRecord>()
    val bindings = ConcurrentHashMap<Pair<Long, Long>, Int>()
    override fun findByUuid(uuid: String) = byUuid[uuid]
    override fun bindOrdinal(userId: Long, deviceId: Long) = bindings[userId to deviceId]
}

class AuthFixture(
    overrides: Map<String, JsonElement> = emptyMap(),
    hashConcurrency: Int = 4,
    hashQueue: Int = 32,
) {
    val clock = MutableClock()
    // Dev-database behaviour (docs/24 s9.4) unless a test overrides it: unenrolled test phones may log in.
    val config = RegistryDefaults(overrides = mapOf<String, JsonElement>("cfg.device.require_enrolled" to JsonPrimitive(false)) + overrides)
    val keys = throwawayKeys()
    val hasher = PasswordHasher(memoryKiB = 19 * 1024)
    val limiter = HashLimiter(hashConcurrency, hashQueue)
    val users = FakeUsers()
    val devices = FakeDevices()
    val lockouts = InMemoryLockoutStore()
    val refreshStore = InMemoryRefreshStore()
    val issuer = TokenIssuer(keys, config, clock)
    val refresh = RefreshService(refreshStore, config, keys.derivedSecret("aron-refresh-rotation-v1"), clock)
    val reach = ReachResolver { userId, role, _, date ->
        Reach(userId, role, date, false, setOf(5012L), setOf(10231L), role == Role.SR, listOf(ReachNode("route", 10231, "R-334-01", "Banani Daily")))
    }
    val login = LoginService(users, devices, hasher, limiter, lockouts, issuer, refresh, reach, config, clock)
    val verifier = AccessTokenVerifier(keys, clock)
    val guard = AuthGuardDeps(verifier, users, config, clock)
    val deps = AuthDeps(login, refresh, issuer, users, devices, keys, reach, config, guard, clock)

    val passwordHash: String = hasher.hash("correct horse 1")

    val srDevice = "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10"

    init {
        users.add(user(1001, "sr334001", Role.SR))
        users.add(user(2001, "tso5012", Role.TSO))
        users.add(user(9001, "admin1", Role.ADMIN))
        devices.byUuid[srDevice] = DeviceRecord(501, srDevice, "active", "sr", null)
        devices.bindings[1001L to 501L] = 0
    }

    fun user(id: Long, username: String, role: Role, status: String = "active") =
        UserRecord(id, username, "Test $username", role, status, "bn", role.wire, passwordHash, 7, false)

    fun application(app: Application) {
        app.installAronPlatform(PlatformContext(clock, config, { "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10" }))
        app.routing { route("/v1") { authRoutes(deps) } }
    }

    companion object {
        fun withoutBinding() = mapOf<String, JsonElement>("cfg.auth.bind_otp_required" to JsonPrimitive(true))
    }
}
