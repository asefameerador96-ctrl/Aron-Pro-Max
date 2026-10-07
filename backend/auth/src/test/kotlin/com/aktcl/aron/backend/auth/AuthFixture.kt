package com.aktcl.aron.backend.auth

import com.aktcl.aron.backend.platform.AccessTokenVerifier
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.FreshDb
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
    val pem = "-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(g.generateKeyPair().private.encoded) + "\n-----END PRIVATE KEY-----\n"
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
    override fun mustChangePassword(userId: Long) = byId[userId]?.mustChangePassword ?: false
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
    /** With a database, the JDBI stores of backend:auth are used instead of the in-memory ones. */
    val fresh: FreshDb? = null,
) {
    val clock = MutableClock()
    // Dev-database behaviour (docs/24 s9.4) unless a test overrides it: unenrolled test phones may log in.
    val config = RegistryDefaults(overrides = mapOf<String, JsonElement>("cfg.device.require_enrolled" to JsonPrimitive(false)) + overrides)
    val keys = throwawayKeys()
    val hasher = PasswordHasher(memoryKiB = 19 * 1024)
    val limiter = HashLimiter(hashConcurrency, hashQueue)
    /** Web logins hash here, apart from the phones' [limiter] (AUD-SEC-02). */
    val webLimiter = HashLimiter(maxOf(1, hashConcurrency / 4), hashQueue / 4)
    private val fakeUsers = FakeUsers()
    private val jdbiUsers = fresh?.let { JdbiUserStore(it.db, clock, svCacheMs = 0) }
    val users: UserStore = jdbiUsers ?: fakeUsers
    private val scopeVersions: ScopeVersionLookup = jdbiUsers ?: fakeUsers
    private val fakeDevices = FakeDevices()
    val devices: DeviceStore = fresh?.let { JdbiDeviceStore(it.db) } ?: fakeDevices
    val lockouts: LockoutStore = fresh?.let { JdbiLockoutStore(it.db) } ?: InMemoryLockoutStore()
    val refreshStore: RefreshStore = fresh?.let { JdbiRefreshStore(it.db) } ?: InMemoryRefreshStore()
    val issuer = TokenIssuer(keys, config, clock)
    val refresh = RefreshService(refreshStore, config, keys.derivedSecret("aron-refresh-rotation-v1"), clock)
    val reach = ReachResolver { userId, role, _, date ->
        Reach(userId, role, date, false, setOf(5012L), setOf(10231L), role == Role.SR, listOf(ReachNode("route", 10231, "R-334-01", "Banani Daily")))
    }
    val login = LoginService(users, devices, hasher, limiter, lockouts, issuer, refresh, reach, config, clock, webLimiter = webLimiter)
    val verifier = AccessTokenVerifier(keys, clock)
    val guard = AuthGuardDeps(verifier, scopeVersions, config, clock)
    val deps = AuthDeps(login, refresh, issuer, users, devices, keys, reach, config, guard, clock, trustedFrontDoorId = "fd-test")

    val passwordHash: String = hasher.hash("correct horse 1")

    val srDevice = "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10"

    init {
        addUser(user(1001, "sr334001", Role.SR))
        addUser(user(2001, "tso5012", Role.TSO))
        addUser(user(9001, "admin1", Role.ADMIN))
        addDevice(DeviceRecord(501, srDevice, "active", "sr", null))
        bind(1001, 501, 0)
    }

    /** Inserts or replaces a user (status and scope_version included) in whichever store is in use. */
    fun addUser(u: UserRecord) {
        if (fresh == null) { fakeUsers.add(u); return }
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate(
                """INSERT INTO app.app_user (id, username, full_name, role, status, locale, designation, password_hash, scope_version, must_change_password)
                   OVERRIDING SYSTEM VALUE VALUES (:id, :u, :n, :r, :s, :l, :d, :p, :sv, :m)
                   ON CONFLICT (id) DO UPDATE SET status = excluded.status, scope_version = excluded.scope_version, password_hash = excluded.password_hash, must_change_password = excluded.must_change_password""",
            ).bind("id", u.id).bind("u", u.username).bind("n", u.fullName).bind("r", u.role.wire).bind("s", u.status).bind("l", u.locale)
                .bind("d", u.designation).bind("p", u.passwordHash).bind("sv", u.scopeVersion).bind("m", u.mustChangePassword).execute()
        }
    }

    /** Adds a phone (a real app.device row on PostgreSQL; a null key becomes the seed-style unusable placeholder). */
    fun addDevice(d: DeviceRecord) {
        if (fresh == null) { fakeDevices.byUuid[d.uuid] = d; return }
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate(
                """INSERT INTO app.device (id, device_uuid, flavour, app_package, status, device_owner, lockdown_level, public_key_jwk,
                                         public_key_thumbprint, app_signing_cert_sha256)
                   OVERRIDING SYSTEM VALUE VALUES (:id, CAST(:u AS uuid), :f, :pkg, :s, false, 'dev', CAST(:jwk AS jsonb), :tp, decode(repeat('00', 32), 'hex'))
                   ON CONFLICT (id) DO UPDATE SET status = excluded.status, public_key_jwk = excluded.public_key_jwk""",
            ).bind("id", d.id).bind("u", d.uuid).bind("f", d.flavour ?: "sr").bind("pkg", "com.aktcl.aron." + (d.flavour ?: "sr")).bind("s", d.state)
                .bind("jwk", d.publicKeyJwk ?: """{"kty":"EC","crv":"P-256","x":"placeholder","y":"placeholder"}""").bind("tp", "test-" + d.id).execute()
        }
    }

    fun bind(userId: Long, deviceId: Long, ordinal: Int) {
        if (fresh == null) { fakeDevices.bindings[userId to deviceId] = ordinal; return }
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("INSERT INTO app.device_binding (device_id, user_id, bind_ordinal, bound_via) VALUES (:d, :u, :o, 'support')")
                .bind("d", deviceId).bind("u", userId).bind("o", ordinal).execute()
        }
    }

    fun unbindAll() {
        if (fresh == null) { fakeDevices.bindings.clear(); return }
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.device_binding SET status = 'revoked', unbound_at = now() WHERE status = 'active'") }
    }

    fun close() { limiter.close(); webLimiter.close(); fresh?.close() }

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
