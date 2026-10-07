package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.masterdata.OtpCipher
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.DeviceProof
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.JwtKeys
import com.aktcl.aron.backend.platform.Settings
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent checker for F-SYS-003 and F-API-003 (bind OTP at login, POST /v1/auth/bind-device). Seeded database
 * (so tso1001 has the Dhaka North territory), with cfg.device.require_enrolled forced to true as in production.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BindDeviceCheckerTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private lateinit var cipher: OtpCipher
    private val now = AtomicReference(Instant.parse("2027-01-03T04:00:00Z"))
    private val clock = AronClock { now.get() }
    private val password = "field pass 2027"
    private val seq = AtomicInteger(0)
    private var inZone = 0L
    private var outZone = 0L

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE app.app_user SET password_hash = :h, must_change_password = false WHERE pilot").bind("h", PasswordHasher().hash(password)).execute()
            inZone = h.createQuery("SELECT id FROM app.zone WHERE code = 'Z-MIR'").mapTo(Long::class.java).one()
            h.execute("INSERT INTO app.territory (code, name, division_id) SELECT 'T-OTHER', 'Other', division_id FROM app.territory WHERE code = 'T-DHK-N'")
            h.execute("INSERT INTO app.zone (code, name, territory_id) SELECT 'Z-OTHER', 'Other zone', id FROM app.territory WHERE code = 'T-OTHER'")
            outZone = h.createQuery("SELECT id FROM app.zone WHERE code = 'Z-OTHER'").mapTo(Long::class.java).one()
            h.execute("INSERT INTO app.cfg_version (config_version, kind, committed_by, summary) SELECT max(config_version) + 1, 'change', (SELECT id FROM app.app_user WHERE username = 'aron.system'), 'checker' FROM app.cfg_version")
            for ((k, v) in listOf("cfg.api.rl.device_per_min" to "600", "cfg.api.rl.user_per_min" to "2000")) {
                h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT '$k', 'global', 0, '$v'::jsonb, now() - interval '1 day', max(config_version), 'test' FROM app.cfg_version")
            }
            // Production value: the dev seed turns enrolment off.
            h.useTransaction<Exception> { tx ->
                // One transaction, so now() is one instant: no gap in which the dev value or the env default applies.
                assertEquals(1, tx.execute("UPDATE app.cfg_value SET effective_to = now() WHERE key = 'cfg.device.require_enrolled' AND scope_type = 'global' AND effective_to IS NULL"))
                tx.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.device.require_enrolled', 'global', 0, 'true'::jsonb, now(), max(config_version), 'test' FROM app.cfg_version")
            }
        }
        val key = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        val s = Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to key.absolutePath))
        cipher = OtpCipher(JwtKeys.fromSettings(s).derivedSecret("aron-device-otp-v1"))
        wiring = Wiring.production(s, clock)
    }

    @AfterAll
    fun tearDown() { wiring.database?.close(); fresh.close() }

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun count(sql: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }
    private fun exec(sql: String) = fresh.db.jdbi.useHandle<Exception> { h -> h.execute(sql) }

    private class Phone(val uuid: String, val keys: KeyPair, var userId: Long, var username: String, val deviceId: Long)

    private fun newDevice(placeholderKey: Boolean = false): Pair<String, KeyPair> {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val jwk = ECKey.Builder(Curve.P_256, kp.public as ECPublicKey).build()
        val uuid = UUID.randomUUID().toString()
        val jwkJson = if (placeholderKey) """{"kty":"placeholder"}""" else jwk.toJSONString()
        val thumb = if (placeholderKey) "placeholder-$uuid" else jwk.computeThumbprint().toString()
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate(
                """
                INSERT INTO app.device (device_uuid, flavour, app_package, status, device_owner, lockdown_level, public_key_jwk, public_key_thumbprint, app_signing_cert_sha256)
                VALUES (CAST(:u AS uuid), 'sr', 'com.aktcl.aron.sr', 'active', true, 'prod', CAST(:k AS jsonb), :t, decode(repeat('00', 32), 'hex'))
                """.trimIndent(),
            ).bind("u", uuid).bind("k", jwkJson).bind("t", thumb).execute()
        }
        return uuid to kp
    }

    private fun deviceId(uuid: String) = count("SELECT id FROM app.device WHERE device_uuid = '$uuid'")

    private fun newUser(zone: Long): Pair<Long, String> {
        val name = "chkbind${seq.incrementAndGet()}"
        val id = fresh.db.jdbi.withHandle<Long, Exception> { h ->
            h.createQuery("INSERT INTO app.app_user (username, full_name, role, password_hash, must_change_password, home_zone_id) VALUES (:n, 'Bind SR', 'SR', :p, false, :z) RETURNING id")
                .bind("n", name).bind("p", PasswordHasher().hash(password)).bind("z", zone).mapTo(Long::class.java).one()
        }
        return id to name
    }

    private fun phone(zone: Long = inZone, placeholderKey: Boolean = false): Phone {
        val (uuid, kp) = newDevice(placeholderKey)
        val (uid, name) = newUser(zone)
        return Phone(uuid, kp, uid, name, deviceId(uuid))
    }

    /** Another phone of the same user. */
    private fun samePerson(p: Phone): Phone { val (u, kp) = newDevice(); return Phone(u, kp, p.userId, p.username, deviceId(u)) }

    private suspend fun HttpClient.loginRaw(p: Phone): HttpResponse = post("/v1/auth/login") {
        contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
        setBody("""{"username":"${p.username}","password":"$password","client":"app_sr","device_uuid":"${p.uuid}"}""")
    }

    private suspend fun HttpClient.login(p: Phone): JsonObject {
        val r = loginRaw(p)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText())
    }

    private suspend fun HttpClient.bindToken(p: Phone): String = login(p)["bind_token"]!!.jsonPrimitive.content

    private fun proof(p: Phone, otp: String, at: Instant = now.get(), uuid: String = p.uuid): String {
        val msg = listOf("aron-proof-v1", "bind", uuid, DeviceProof.sha256Hex(otp.toByteArray()), DeviceProof.bucket(at.epochSecond).toString()).joinToString("\n")
        val sig = Signature.getInstance("SHA256withECDSAinP1363Format").apply { initSign(p.keys.private); update(msg.toByteArray()) }.sign()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sig)
    }

    private suspend fun HttpClient.bind(
        p: Phone, token: String, otp: String, proof: String? = proof(p, otp), headerUuid: String = p.uuid, bodyUuid: String = p.uuid,
    ): HttpResponse = post("/v1/auth/bind-device") {
        bearerAuth(token); header("X-Device-Id", headerUuid); header("X-App-Version", "1.0.9+9")
        proof?.let { header("X-Device-Proof", it) }
        contentType(ContentType.Application.Json); setBody("""{"device_uuid":"$bodyUuid","otp":"$otp"}""")
    }

    private fun liveOtp(userId: Long): String = fresh.db.jdbi.withHandle<String, Exception> { h ->
        h.createQuery("SELECT otp_cipher FROM app.device_otp WHERE user_id = :u AND consumed_at IS NULL AND revoked_at IS NULL ORDER BY id DESC LIMIT 1")
            .bind("u", userId).map { rs, _ -> cipher.open(rs.getBytes(1), userId)!! }.one()
    }

    private fun attempts(userId: Long): Long = count("SELECT coalesce(max(attempts), 0) FROM app.device_otp WHERE user_id = $userId AND consumed_at IS NULL AND revoked_at IS NULL")
    private fun wrong(otp: String) = if (otp == "9999") "9998" else "9999"
    private suspend fun HttpResponse.code() = json(bodyAsText())["code"]?.jsonPrimitive?.content

    private suspend fun HttpClient.webToken(username: String): String {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"$password","client":"web"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val b = json(r.bodyAsText())
        assertEquals("ok", b["status"]!!.jsonPrimitive.content, b.toString())
        return b["access_token"]!!.jsonPrimitive.content
    }

    private fun <T> at(t: Instant, block: () -> T): T { val old = now.get(); now.set(t); try { return block() } finally { now.set(old) } }

    // ---------------------------------------------------------------- token, header, body binding

    @Test
    fun aBindTokenWorksOnlyForItsOwnPhoneAndUser() = testApplication {
        application { aronApi(wiring) }
        val a = phone(); val b = phone()
        val ta = client.bindToken(a)
        client.bindToken(b)
        val otpA = liveOtp(a.userId)
        val otpB = liveOtp(b.userId)
        // A's token with B's X-Device-Id (and B's signature), body uuid differing from the token, header differing from body.
        assertEquals("ERR_DEVICE_PROOF_INVALID", client.bind(b, ta, otpB, headerUuid = b.uuid, bodyUuid = b.uuid).code())
        assertEquals("ERR_DEVICE_PROOF_INVALID", client.bind(a, ta, otpA, bodyUuid = b.uuid).code())
        assertEquals("ERR_DEVICE_PROOF_INVALID", client.bind(a, ta, otpA, headerUuid = b.uuid).code())
        // A's token with B's OTP: B's OTP is not A's, counted as a wrong try on A's code.
        assertEquals("ERR_AUTH_OTP_INVALID", client.bind(a, ta, otpB.let { if (it == otpA) wrong(otpA) else it }).code())
        // The bind token is not an API token.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me") { bearerAuth(ta); header("X-Device-Id", a.uuid) }.status)
        assertEquals(0L, count("SELECT count(*) FROM app.device_binding WHERE user_id IN (${a.userId}, ${b.userId})"))
        assertEquals(HttpStatusCode.OK, client.bind(a, ta, otpA).status)
        assertEquals(0L, attempts(b.userId), "B's counter is untouched by A's attempts")
    }

    // ---------------------------------------------------------------- proof

    @Test
    fun theProofIsBoundToTheOtpTheDeviceAndAFreshBucket() = testApplication {
        application { aronApi(wiring) }
        val p = phone()
        val t = client.bindToken(p)
        val otp = liveOtp(p.userId)
        // A proof signed over another OTP, over another device uuid, or two buckets old, never verifies; none counts a try.
        assertEquals("ERR_DEVICE_PROOF_INVALID", client.bind(p, t, otp, proof = proof(p, wrong(otp))).code())
        assertEquals("ERR_DEVICE_PROOF_INVALID", client.bind(p, t, otp, proof = proof(p, otp, uuid = UUID.randomUUID().toString())).code())
        assertEquals("ERR_DEVICE_PROOF_INVALID", client.bind(p, t, otp, proof = proof(p, otp, at = now.get().minusSeconds(600))).code())
        assertEquals("ERR_DEVICE_PROOF_INVALID", client.bind(p, t, otp, proof = proof(samePerson(p), otp)).code())
        assertEquals(0L, attempts(p.userId))
        // The previous bucket is accepted.
        val ok = client.bind(p, t, otp, proof = proof(p, otp, at = now.get().minusSeconds(300)))
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        // Replaying the same request (same proof) is refused: the OTP is spent.
        assertEquals("ERR_AUTH_OTP_INVALID", client.bind(p, t, otp, proof = proof(p, otp, at = now.get().minusSeconds(300))).code())
    }

    @Test
    fun aDeviceWithNoUsableKeyCannotBindWhenEnrolmentIsRequired() = testApplication {
        application { aronApi(wiring) }
        val p = phone(placeholderKey = true)
        val first = client.login(p)
        assertEquals("bind_required", first["status"]!!.jsonPrimitive.content)
        val t = first["bind_token"]!!.jsonPrimitive.content
        val otp = liveOtp(p.userId)
        // Enrolment really is required in this database: an unknown phone is refused at login.
        val unknown = Phone(UUID.randomUUID().toString(), p.keys, p.userId, p.username, 0)
        assertEquals("ERR_DEVICE_NOT_ENROLLED", client.loginRaw(unknown).code())
        val r = client.bind(p, t, otp, proof = null)
        assertEquals("ERR_DEVICE_PROOF_INVALID", r.code(), "${r.status} ${r.bodyAsText().take(300)}")
        assertEquals(0L, count("SELECT count(*) FROM app.device_binding WHERE user_id = ${p.userId}"))
        assertEquals(0L, count("SELECT count(*) FROM app.device_otp WHERE user_id = ${p.userId} AND consumed_at IS NOT NULL"))
    }

    // ---------------------------------------------------------------- brute force

    @Test
    fun aNewLoginNeverResetsTheFiveTries() = testApplication {
        application { aronApi(wiring) }
        val p = phone()
        val t = client.bindToken(p)
        val otp = liveOtp(p.userId)
        repeat(5) { assertEquals("ERR_AUTH_OTP_INVALID", client.bind(p, t, wrong(otp)).code()) }
        // Re-login (same and another phone of the user): no new OTP, still locked.
        val t2 = client.bindToken(p)
        val q = samePerson(p)
        val t3 = client.bindToken(q)
        assertEquals(1L, count("SELECT count(*) FROM app.device_otp WHERE user_id = ${p.userId}"))
        assertEquals("ERR_AUTH_OTP_ATTEMPTS_EXCEEDED", client.bind(p, t2, otp).code())
        assertEquals("ERR_AUTH_OTP_ATTEMPTS_EXCEEDED", client.bind(q, t3, otp).code())
        assertEquals(5L, attempts(p.userId), "a refused try past the limit does not move the counter")
        // An invalid proof does not count either, and neither do error paths.
        assertEquals(0L, count("SELECT count(*) FROM app.device_binding WHERE user_id = ${p.userId}"))
    }

    @Test
    fun concurrentWrongTriesNeverExceedFiveAndConcurrentRightTriesBindOnce() = testApplication {
        application { aronApi(wiring) }
        val p = phone()
        val t = client.bindToken(p)
        val otp = liveOtp(p.userId)
        val codes = coroutineScope { (1..20).map { async { client.bind(p, t, wrong(otp)).code() } }.awaitAll() }
        assertEquals(5, codes.count { it == "ERR_AUTH_OTP_INVALID" }, codes.toString())
        assertEquals(15, codes.count { it == "ERR_AUTH_OTP_ATTEMPTS_EXCEEDED" }, codes.toString())
        assertEquals(5L, attempts(p.userId))

        // Right code, many at once, from four phones of one user: one binding, one ordinal, OTP consumed once.
        val r = phone()
        val phones = listOf(r) + (1..3).map { samePerson(r) }
        val tokens = phones.map { client.bindToken(it) }
        val rotp = liveOtp(r.userId)
        val res = coroutineScope { phones.indices.flatMap { i -> (1..3).map { async { client.bind(phones[i], tokens[i], rotp).status } } }.awaitAll() }
        assertEquals(1, res.count { it == HttpStatusCode.OK }, res.toString())
        assertEquals(1L, count("SELECT count(*) FROM app.device_binding WHERE user_id = ${r.userId}"))
        assertEquals(1L, count("SELECT count(*) FROM app.device_otp WHERE user_id = ${r.userId} AND consumed_at IS NOT NULL"))
    }

    // ---------------------------------------------------------------- ordinals

    @Test
    fun aFifthPhoneIsRefusedWith409DeviceLimitReachedAndTheOtpSurvives() = testApplication {
        application { aronApi(wiring) }
        val p = phone()
        val phones = listOf(p) + (1..4).map { samePerson(p) }
        for (i in 0 until 4) {
            val t = client.bindToken(phones[i])
            val r = client.bind(phones[i], t, liveOtp(p.userId))
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            assertEquals(i.toLong(), json(r.bodyAsText())["device"]!!.jsonObject["bind_ordinal"]!!.jsonPrimitive.long)
        }
        val fifth = phones[4]
        val t = client.bindToken(fifth)
        val otp = liveOtp(p.userId)
        val r = client.bind(fifth, t, otp)
        assertEquals(4L, count("SELECT count(*) FROM app.device_binding WHERE user_id = ${p.userId} AND status = 'active'"))
        // docs/24 s7.5: "A user may hold at most 4 active device bindings (409 ERR_DEVICE_LIMIT_REACHED)".
        assertEquals(HttpStatusCode.Conflict, r.status, r.bodyAsText())
        assertEquals("ERR_DEVICE_LIMIT_REACHED", r.code())
    }

    @Test
    fun aRefusedFifthPhoneDoesNotBurnTheOtp() = testApplication {
        application { aronApi(wiring) }
        val p = phone()
        val phones = listOf(p) + (1..4).map { samePerson(p) }
        for (i in 0 until 4) assertEquals(HttpStatusCode.OK, client.bind(phones[i], client.bindToken(phones[i]), liveOtp(p.userId)).status)
        val t = client.bindToken(phones[4])
        val otp = liveOtp(p.userId)
        client.bind(phones[4], t, otp)
        // The refusal is not a use: the TSO's code must still be live (otherwise it vanishes from the panel unused).
        assertEquals(0L, count("SELECT count(*) FROM app.device_otp WHERE user_id = ${p.userId} AND consumed_at IS NOT NULL AND id = (SELECT max(id) FROM app.device_otp WHERE user_id = ${p.userId})"))
    }

    // ---------------------------------------------------------------- device and user state

    @Test
    fun aSuspendedOrRevokedPhoneOrADisabledUserCannotBindAndTheOtpIsKept() = testApplication {
        application { aronApi(wiring) }
        val s = phone(); val ts = client.bindToken(s); val os = liveOtp(s.userId)
        exec("UPDATE app.device SET status = 'suspended' WHERE id = ${s.deviceId}")
        assertEquals("ERR_DEVICE_SUSPENDED", client.bind(s, ts, os).code())
        val r = phone(); val tr = client.bindToken(r); val or = liveOtp(r.userId)
        exec("UPDATE app.device SET status = 'revoked' WHERE id = ${r.deviceId}")
        assertEquals("ERR_DEVICE_REVOKED", client.bind(r, tr, or).code())
        val u = phone(); val tu = client.bindToken(u); val ou = liveOtp(u.userId)
        exec("UPDATE app.app_user SET status = 'disabled' WHERE id = ${u.userId}")
        val ru = client.bind(u, tu, ou)
        assertTrue(ru.status == HttpStatusCode.Forbidden || ru.status == HttpStatusCode.Unauthorized, ru.bodyAsText())
        for (x in listOf(s, r, u)) {
            assertEquals(0L, count("SELECT count(*) FROM app.device_binding WHERE user_id = ${x.userId}"))
            assertEquals(0L, count("SELECT count(*) FROM app.device_otp WHERE user_id = ${x.userId} AND (consumed_at IS NOT NULL OR attempts > 0)"))
        }
    }

    // ---------------------------------------------------------------- expiry

    @Test
    fun expiryIsExactlyAt120Minutes() = testApplication {
        application { aronApi(wiring) }
        val start = now.get()
        val a = phone(); val b = phone()
        client.bindToken(a); client.bindToken(b)
        val oa = liveOtp(a.userId); val ob = liveOtp(b.userId)
        val created = count("SELECT extract(epoch FROM created_at)::bigint FROM app.device_otp WHERE user_id = ${a.userId}")
        assertEquals(start.epochSecond, created)
        try {
            now.set(start.plus(Duration.ofMinutes(115)))
            val ta = client.bindToken(a); val tb = client.bindToken(b)
            now.set(start.plus(Duration.ofMinutes(120)).minusSeconds(1))
            assertEquals(HttpStatusCode.OK, client.bind(a, ta, oa).status, "one second before expiry the code binds")
            now.set(start.plus(Duration.ofMinutes(120)))
            assertEquals("ERR_AUTH_OTP_EXPIRED", client.bind(b, tb, ob).code())
        } finally { now.set(start) }
    }

    // ---------------------------------------------------------------- no OTP in responses

    @Test
    fun noResponseCarriesTheOtpOrItsVerifier() = testApplication {
        application { aronApi(wiring) }
        val p = phone()
        val loginBody = client.loginRaw(p).bodyAsText()
        val otp = liveOtp(p.userId)
        assertTrue("otp" !in json(loginBody).keys)
        val t = json(loginBody)["bind_token"]!!.jsonPrimitive.content
        // The bind token's claims hold no OTP.
        val claims = String(Base64.getUrlDecoder().decode(t.split('.')[1]))
        assertTrue("otp" !in claims.lowercase(), claims)
        val bad = client.bind(p, t, wrong(otp)).bodyAsText()
        assertTrue(otp !in bad && "attempt" !in json(bad).keys, bad)
        val ok = client.bind(p, t, otp).bodyAsText()
        assertTrue("otp" !in json(ok).keys, ok)
    }

    // ---------------------------------------------------------------- TSO panel

    @Test
    fun theTsoPanelShowsTheLoginCreatedOtpOnlyToATsoInScope() = testApplication {
        application { aronApi(wiring) }
        val inside = phone(inZone)
        val outside = phone(outZone)
        client.bindToken(inside); client.bindToken(outside)
        val otpIn = liveOtp(inside.userId)
        val tso = client.webToken("tso1001")
        val r = client.get("/v1/admin/device-otps?limit=200") { bearerAuth(tso) }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val items = json(r.bodyAsText())["items"]!!.jsonArray.map { it.jsonObject }
        val mine = items.firstOrNull { it["user_id"]!!.jsonPrimitive.long == inside.userId }
        assertNotNull(mine, "the in-scope SR is listed")
        assertEquals(otpIn, mine["otp"]?.jsonPrimitive?.contentOrNull)
        assertNull(items.firstOrNull { it["user_id"]!!.jsonPrimitive.long == outside.userId }, "the out-of-scope SR is not listed")
        // Explicit out-of-reach zone filter is refused.
        assertEquals("ERR_OUT_OF_SCOPE", client.get("/v1/admin/device-otps?zone_id=$outZone") { bearerAuth(tso) }.code())
        // An SR cannot read the panel (its own OTP included).
        val srTok = client.webToken("amo1001").let { it }
        val asAmo = client.get("/v1/admin/device-otps") { bearerAuth(srTok) }
        assertEquals(HttpStatusCode.Forbidden, asAmo.status, asAmo.bodyAsText())
        // After 5 wrong tries the code leaves the panel.
        val t = client.bindToken(inside)
        repeat(5) { client.bind(inside, t, wrong(otpIn)) }
        val after = json(client.get("/v1/admin/device-otps?limit=200") { bearerAuth(tso) }.bodyAsText())["items"]!!.jsonArray.map { it.jsonObject }
        assertNull(after.first { it["user_id"]!!.jsonPrimitive.long == inside.userId }["otp"]?.jsonPrimitive?.contentOrNull)
    }
}
