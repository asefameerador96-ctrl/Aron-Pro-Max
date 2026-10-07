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
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-SYS-003 and F-API-003 through the production wiring: a login from an unbound, enrolled phone answers
 * bind_required and creates one 4-digit OTP, sealed with OtpCipher and verified by OtpCipher.mac (never a bare hash);
 * POST /v1/auth/bind-device with the bind token, the OTP and a valid X-Device-Proof binds once; the OTP is single use,
 * expires after 120 minutes and after 5 wrong tries even the right code is refused.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BindDeviceTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private lateinit var cipher: OtpCipher
    private val now = AtomicReference(Instant.parse("2027-01-03T04:00:00Z"))
    private val clock = AronClock { now.get() }
    private val password = "field pass 2027"
    private val seq = AtomicInteger(0)

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
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

    private class Phone(val uuid: String, val keys: KeyPair, val userId: Long, val username: String)

    /** A new SR with no binding and a new enrolled, active phone with a real P-256 key. */
    private fun phone(): Phone {
        val n = seq.incrementAndGet()
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val jwk = ECKey.Builder(Curve.P_256, kp.public as ECPublicKey).build()
        val uuid = UUID.randomUUID().toString()
        val name = "srbind$n"
        val userId = fresh.db.jdbi.withHandle<Long, Exception> { h ->
            h.createUpdate(
                """
                INSERT INTO app.device (device_uuid, flavour, app_package, status, device_owner, lockdown_level, public_key_jwk, public_key_thumbprint, app_signing_cert_sha256)
                VALUES (CAST(:u AS uuid), 'sr', 'com.aktcl.aron.sr', 'active', true, 'prod', CAST(:k AS jsonb), :t, decode(repeat('00', 32), 'hex'))
                """.trimIndent(),
            ).bind("u", uuid).bind("k", jwk.toJSONString()).bind("t", jwk.computeThumbprint().toString()).execute()
            h.createQuery("INSERT INTO app.app_user (username, full_name, role, password_hash, must_change_password) VALUES (:n, 'Bind SR', 'SR', :p, false) RETURNING id")
                .bind("n", name).bind("p", PasswordHasher().hash(password)).mapTo(Long::class.java).one()
        }
        return Phone(uuid, kp, userId, name)
    }

    private suspend fun HttpClient.login(p: Phone): JsonObject {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", "1.0.9+9")
            setBody("""{"username":"${p.username}","password":"$password","client":"app_sr","device_uuid":"${p.uuid}"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return json(r.bodyAsText())
    }

    private fun proof(p: Phone, otp: String, at: Instant = now.get()): String {
        val msg = listOf("aron-proof-v1", "bind", p.uuid, DeviceProof.sha256Hex(otp.toByteArray()), DeviceProof.bucket(at.epochSecond).toString()).joinToString("\n")
        val sig = Signature.getInstance("SHA256withECDSAinP1363Format").apply { initSign(p.keys.private); update(msg.toByteArray()) }.sign()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sig)
    }

    private suspend fun HttpClient.bind(p: Phone, token: String, otp: String, proof: String? = proof(p, otp)): HttpResponse = post("/v1/auth/bind-device") {
        bearerAuth(token); header("X-Device-Id", p.uuid); header("X-App-Version", "1.0.9+9")
        proof?.let { header("X-Device-Proof", it) }
        contentType(ContentType.Application.Json); setBody("""{"device_uuid":"${p.uuid}","otp":"$otp"}""")
    }

    /** The OTP as the TSO panel reads it (the seal opens with the user's AAD only). */
    private fun liveOtp(userId: Long): Pair<String, ByteArray> = fresh.db.jdbi.withHandle<Pair<String, ByteArray>, Exception> { h ->
        h.createQuery("SELECT otp_cipher, otp_sha256 FROM app.device_otp WHERE user_id = :u AND consumed_at IS NULL AND revoked_at IS NULL ORDER BY id DESC LIMIT 1")
            .bind("u", userId).map { rs, _ -> cipher.open(rs.getBytes(1), userId)!! to rs.getBytes(2) }.one()
    }

    private suspend fun HttpResponse.code() = json(bodyAsText())["code"]!!.jsonPrimitive.content

    @Test
    fun anUnboundPhoneBindsOnceWithTheTsoOtp() = testApplication {
        application { aronApi(wiring) }
        val p = phone()
        val first = client.login(p)
        assertEquals("bind_required", first["status"]!!.jsonPrimitive.content)
        val bindToken = first["bind_token"]!!.jsonPrimitive.content
        // One OTP however often the SR retries the login; 4 digits, sealed, keyed verifier (not a bare SHA-256).
        client.login(p)
        assertEquals(1, count("SELECT count(*) FROM app.device_otp WHERE user_id = ${p.userId}"))
        val (otp, verifier) = liveOtp(p.userId)
        assertTrue(Regex("^[0-9]{4}$").matches(otp), otp)
        assertContentEquals(cipher.mac(otp, p.userId), verifier)
        assertTrue(!verifier.contentEquals(java.security.MessageDigest.getInstance("SHA-256").digest(otp.toByteArray())))
        assertEquals(1, count("SELECT count(*) FROM app.device_otp WHERE user_id = ${p.userId} AND expires_at = created_at + interval '120 minutes'"))

        // Only the bind token, the token's own phone and a valid proof.
        assertEquals("ERR_DEVICE_PROOF_INVALID", client.bind(p, bindToken, otp, proof = null).code())
        assertEquals("ERR_DEVICE_PROOF_INVALID", client.bind(p, bindToken, otp, proof = proof(p, "0000")).code())
        assertEquals(HttpStatusCode.Unauthorized, client.bind(p, first["bind_token"]!!.jsonPrimitive.content.dropLast(2) + "xx", otp).status)

        val ok = client.bind(p, bindToken, otp)
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        val b = json(ok.bodyAsText())
        assertEquals("ok", b["status"]!!.jsonPrimitive.content)
        assertEquals(0, b["device"]!!.jsonObject["bind_ordinal"]!!.jsonPrimitive.int)
        assertTrue(b["refresh_token"]!!.jsonPrimitive.content.isNotEmpty())
        assertEquals(1, count("SELECT count(*) FROM app.device_binding WHERE user_id = ${p.userId} AND status = 'active' AND bound_via = 'otp'"))
        // Single use: the same OTP again is refused; the next login is an ordinary one.
        assertEquals("ERR_AUTH_OTP_INVALID", client.bind(p, bindToken, otp).code())
        assertEquals("ok", client.login(p)["status"]!!.jsonPrimitive.content)
        assertEquals(1, count("SELECT count(*) FROM app.device_otp WHERE user_id = ${p.userId}"), "a bound phone creates no new OTP")
    }

    @Test
    fun theSixthTryIsRefusedEvenWithTheRightCode() = testApplication {
        application { aronApi(wiring) }
        val p = phone()
        val token = client.login(p)["bind_token"]!!.jsonPrimitive.content
        val (otp, _) = liveOtp(p.userId)
        val wrong = if (otp == "9999") "9998" else "9999"
        repeat(5) { assertEquals("ERR_AUTH_OTP_INVALID", client.bind(p, token, wrong).code()) }
        assertEquals("ERR_AUTH_OTP_ATTEMPTS_EXCEEDED", client.bind(p, token, otp).code())
        assertEquals(0, count("SELECT count(*) FROM app.device_binding WHERE user_id = ${p.userId}"))
    }

    @Test
    fun anOtpExpiresAfter120Minutes() = testApplication {
        application { aronApi(wiring) }
        val p = phone()
        client.login(p)
        val (otp, _) = liveOtp(p.userId)
        val start = now.get()
        try {
            now.set(start.plus(Duration.ofMinutes(115)))
            val token = client.login(p)["bind_token"]!!.jsonPrimitive.content // the OTP is still live: no new one
            assertEquals(1, count("SELECT count(*) FROM app.device_otp WHERE user_id = ${p.userId}"))
            now.set(start.plus(Duration.ofMinutes(121)))
            assertEquals("ERR_AUTH_OTP_EXPIRED", client.bind(p, token, otp).code())
            // The next login creates a new OTP, which binds.
            val token2 = client.login(p)["bind_token"]!!.jsonPrimitive.content
            assertEquals(2, count("SELECT count(*) FROM app.device_otp WHERE user_id = ${p.userId}"))
            assertEquals(HttpStatusCode.OK, client.bind(p, token2, liveOtp(p.userId).first).status)
        } finally {
            now.set(start)
        }
    }
}
