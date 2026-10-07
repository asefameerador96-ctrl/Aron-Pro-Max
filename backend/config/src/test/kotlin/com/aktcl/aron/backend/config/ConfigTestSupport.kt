package com.aktcl.aron.backend.config

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.JwtKeys
import com.aktcl.aron.contract.Role
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import java.util.Date
import java.util.UUID

/** Throwaway ES256 keys and web tokens for config tests (tests only). */
object TestTokens {
    val keys: JwtKeys by lazy {
        val g = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val pem = "-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(g.generateKeyPair().private.encoded) + "\n-----END PRIVATE KEY-----"
        JwtKeys(JwtKeys.parsePrivatePem(pem), "t")
    }

    fun web(userId: Long, role: Role, sv: Long = 1): String {
        val now = Instant.now()
        val c = JWTClaimsSet.Builder().issuer("aron").audience("aron-api").subject(userId.toString()).claim("uname", "u$userId")
            .claim("role", role.wire).claim("sv", sv).claim("flv", "web").claim("perm", emptyList<String>()).claim("pii", false)
            .claim("amr", listOf("pwd")).issueTime(Date.from(now)).notBeforeTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(900)))
            .jwtID(UUID.randomUUID().toString()).build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID("t").build(), c).apply { sign(keys.signer) }.serialize()
    }
}

/** A settable clock that starts at the next 12:00 Asia/Dhaka (outside the C3 freeze windows). */
class TestClock : AronClock {
    @Volatile var at: Instant = Instant.now().atZone(ZoneId.of("Asia/Dhaka")).let { z ->
        val noon = z.toLocalDate().atTime(12, 0).atZone(z.zone)
        (if (noon.toInstant().isAfter(Instant.now())) noon else noon.plusDays(1)).toInstant()
    }
    override fun now(): Instant = at
    fun advance(seconds: Long) { at = at.plusSeconds(seconds) }
    fun setDhaka(hour: Int, minute: Int) { at = at.atZone(ZoneId.of("Asia/Dhaka")).toLocalDate().atTime(hour, minute).atZone(ZoneId.of("Asia/Dhaka")).toInstant() }
}

/** The db lane's dev seed plus a second SUPERADMIN, loaded into a fresh migrated database. */
class SeededConfigDb : AutoCloseable {
    val fresh: FreshDb = FreshDb.create()
    val ids: Map<String, Long>

    init {
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.app_user (username, full_name, role, designation, employee_code, locale, home_zone_id, pilot, must_change_password) SELECT 'superadmin1002', 'Second Super Admin', 'SUPERADMIN', 'SA', 'T-SAD-1002', 'en', home_zone_id, true, false FROM app.app_user WHERE username = 'superadmin1001'")
        }
        ids = fresh.db.jdbi.withHandle<Map<String, Long>, Exception> { h -> h.createQuery("SELECT username, id FROM app.app_user").map { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().toMap() }
    }

    fun principal(username: String, role: Role) = AronPrincipal(ids.getValue(username), username, role, 1, "aron-api", null, null, "web", emptyList(), false, listOf("pwd"), UUID.randomUUID().toString(), Instant.now().plusSeconds(900))

    fun one(sql: String): String? = fresh.db.jdbi.withHandle<String?, Exception> { h -> h.createQuery(sql).mapTo(String::class.java).findOne().orElse(null) }

    override fun close() = fresh.close()
}
