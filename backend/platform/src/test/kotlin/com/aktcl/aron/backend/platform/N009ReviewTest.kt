package com.aktcl.aron.backend.platform

import com.aktcl.aron.contract.ProblemCode
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.server.application.Application
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Date
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Serializable
data class ReviewEcho(val name: String, val count: Int, val inner: ReviewInner? = null)

@Serializable
data class ReviewInner(val a: Int)

class N009ReviewTest {
    private fun Application.testApp() {
        installAronPlatform(PlatformContext(config = RegistryDefaults(), generation = { "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10" }))
        routing {
            get("/v1/ok") { call.respondText("ok") }
            get("/v1/bad-request") { throw BadRequestException("missing query parameter") }
            get("/v1/batch-too-large") { throw ApiProblem(ProblemCode.ERR_SYNC_BATCH_TOO_LARGE, "too many rows") }
            get("/v1/decomp") { throw ApiProblem(ProblemCode.ERR_SYNC_DECOMPRESSION_LIMIT, "ratio") }
            get("/v1/direct-413") { call.respondProblem(ApiProblem(ProblemCode.ERR_REPORT_TOO_LARGE, "use the export job")) }
            get("/v1/direct-404") { call.respondProblem(ApiProblem(ProblemCode.ERR_NOT_FOUND, "outlet 7 not found")) }
            post("/v1/echo") { call.respond(call.receiveStrict(ReviewEcho.serializer())) }
            get("/v1/json") { call.respond(mapOf("a" to "b")) }
        }
    }

    private fun run(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication { application { testApp() }; block() }

    private fun code(text: String) = Json.parseToJsonElement(text).jsonObject["code"]!!.jsonPrimitive.content

    // ---- problem mapping ---------------------------------------------------------------------------------------

    @Test
    fun ktorBadRequestExceptionIsA400NotARetryable500() = run {
        val r = client.get("/v1/bad-request")
        assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
    }

    @Test
    fun thrownSyncBatchTooLargeKeepsItsCode() = run {
        val r = client.get("/v1/batch-too-large")
        assertEquals(HttpStatusCode.PayloadTooLarge, r.status)
        assertEquals("ERR_SYNC_BATCH_TOO_LARGE", code(r.bodyAsText()))
    }

    @Test
    fun thrownDecompressionLimitKeepsItsCode() = run {
        val r = client.get("/v1/decomp")
        assertEquals("ERR_SYNC_DECOMPRESSION_LIMIT", code(r.bodyAsText()))
    }

    @Test
    fun directlyRespondedReportTooLargeKeepsItsCode() = run {
        val r = client.get("/v1/direct-413")
        assertEquals("ERR_REPORT_TOO_LARGE", code(r.bodyAsText()))
    }

    @Test
    fun directlyRespondedNotFoundKeepsItsDetail() = run {
        val b = Json.parseToJsonElement(client.get("/v1/direct-404").bodyAsText()).jsonObject
        assertEquals("outlet 7 not found", b["detail"]?.jsonPrimitive?.content)
    }

    @Test
    fun notAcceptableIsAProblemEnvelope() = run {
        val r = client.get("/v1/json") { header(HttpHeaders.Accept, "text/html") }
        if (!r.status.isSuccess()) {
            assertTrue(r.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/problem+json"), "status ${r.status} without problem body")
        }
    }

    @Test
    fun methodNotAllowedIsAProblemWithMarkers() = run {
        val r = client.post("/v1/ok")
        assertTrue(r.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/problem+json"))
        assertEquals("1", r.headers["X-Aron-Api"])
    }

    // ---- request id --------------------------------------------------------------------------------------------

    @Test
    fun upperCaseRequestIdIsEchoedLowerCaseAndNonV4Replaced() = run {
        val v1 = "9b0c1d2e-3f40-1b5c-8d6e-7f8091a2b3c4" // version 1, not v4
        val r = client.get("/v1/ok") { header("X-Request-Id", v1) }
        assertTrue(r.headers["X-Request-Id"] != v1)
        val inj = client.get("/v1/ok") { header("X-Request-Id", "9b0c1d2e-3f40-4b5c-8d6e-7f8091a2b3c4 x") }
        assertTrue(inj.headers["X-Request-Id"] != "9b0c1d2e-3f40-4b5c-8d6e-7f8091a2b3c4 x")
    }

    // ---- JSON strictness and size caps -------------------------------------------------------------------------

    private suspend fun ApplicationTestBuilder.postEcho(body: ByteArray, gzip: Boolean = false) = client.post("/v1/echo") {
        contentType(ContentType.Application.Json)
        if (gzip) header(HttpHeaders.ContentEncoding, "gzip")
        setBody(body)
    }

    @Test
    fun nestedUnknownMemberPointer() = run {
        val r = postEcho("""{"name":"a","count":1,"inner":{"a":1,"zz":2}}""".toByteArray())
        val e = Json.parseToJsonElement(r.bodyAsText()).jsonObject["errors"]!!.jsonArray.single().jsonObject
        assertEquals("/inner/zz", e["pointer"]!!.jsonPrimitive.content)
        assertEquals("unknown_member", e["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun nestedWrongTypePointer() = run {
        val r = postEcho("""{"name":"a","count":1,"inner":{"a":"x"}}""".toByteArray())
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val e = Json.parseToJsonElement(r.bodyAsText()).jsonObject["errors"]!!.jsonArray.single().jsonObject
        assertEquals("/inner/a", e["pointer"]!!.jsonPrimitive.content)
    }

    @Test
    fun nestedMissingMemberPointer() = run {
        val r = postEcho("""{"name":"a","count":1,"inner":{}}""".toByteArray())
        val e = Json.parseToJsonElement(r.bodyAsText()).jsonObject["errors"]!!.jsonArray.single().jsonObject
        assertEquals("/inner/a", e["pointer"]!!.jsonPrimitive.content)
    }

    @Test
    fun duplicateMemberIsRejected() = run {
        val r = postEcho("""{"name":"a","count":1,"count":2}""".toByteArray())
        assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
    }

    @Test
    fun trailingGarbageIsMalformed() = run {
        val r = postEcho("""{"name":"a","count":1} xyz""".toByteArray())
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun nullForRequiredMemberIsValidation() = run {
        val r = postEcho("""{"name":null,"count":1}""".toByteArray())
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertEquals("ERR_VALIDATION", code(r.bodyAsText()))
    }

    @Test
    fun aGzipBodyIsDecodedExactlyOnce() = run {
        // The Compression plugin compresses responses only; a request body is gunzipped by receiveStrict alone.
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write("""{"name":"gz","count":2}""".toByteArray()) }
        val r = postEcho(bos.toByteArray(), gzip = true)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
    }

    @Test
    fun gzipBombIsCapped() = run {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gz -> gz.write("""{"name":"""".toByteArray()); gz.write(ByteArray(50 * 1024 * 1024) { 'a'.code.toByte() }); gz.write("\",\"count\":1}".toByteArray()) }
        val r = postEcho(bos.toByteArray(), gzip = true)
        assertEquals(HttpStatusCode.PayloadTooLarge, r.status)
        assertEquals("ERR_PAYLOAD_TOO_LARGE", code(r.bodyAsText()))
    }

    @Test
    fun oversizeChunkedBodyIsCapped() = run {
        val r = client.post("/v1/echo") {
            contentType(ContentType.Application.Json)
            setBody(io.ktor.utils.io.ByteReadChannel(("""{"name":"""" + "x".repeat(400 * 1024) + "\",\"count\":1}").toByteArray()))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, r.status)
        assertEquals("1", r.headers["X-Aron-Api"])
    }

    @Test
    fun tooLargeProblemCarriesMarkers() = run {
        val r = postEcho(("""{"name":"""" + "x".repeat(300 * 1024) + "\",\"count\":1}").toByteArray())
        assertEquals("1", r.headers["X-Aron-Api"])
        assertTrue(r.headers["X-Server-Time"] != null && r.headers["X-Request-Id"] != null)
    }

    // ---- rate limiter --------------------------------------------------------------------------------------------

    @Test
    fun limiterMemoryIsBounded() {
        val rl = RateLimiter(10, 60, maxKeys = 1000)
        repeat(10_000) { rl.tryAcquire("d:$it") }
        assertTrue(rl.size() <= 1000, "size ${rl.size()}")
    }

    @Test
    fun evictionDoesNotResetAHotKeyMidWindow() {
        // A key that is already limited must stay limited even when a flood of other keys forces eviction.
        val clock = AronClock { Instant.parse("2026-10-05T04:00:10Z") }
        val rl = RateLimiter(5, 60, clock, maxKeys = 100)
        repeat(6) { rl.tryAcquire("d:hot") }
        assertEquals(false, rl.tryAcquire("d:hot").allowed)
        repeat(500) { rl.tryAcquire("d:flood-$it") }
        assertEquals(false, rl.tryAcquire("d:hot").allowed, "hot key reset by eviction")
    }

    // ---- JWT -------------------------------------------------------------------------------------------------------

    private fun ecPair(): Pair<ECPrivateKey, ECPublicKey> {
        val g = KeyPairGenerator.getInstance("EC"); g.initialize(ECGenParameterSpec("secp256r1"))
        val kp = g.generateKeyPair(); return kp.private as ECPrivateKey to kp.public as ECPublicKey
    }

    @Test
    fun derivedPublicKeyMatchesTheGeneratedPair() {
        repeat(50) {
            val (priv, pub) = ecPair()
            val d = JwtKeys.derivePublic(priv)
            assertEquals(pub.w, d.w)
        }
    }

    private fun token(
        keys: Pair<ECPrivateKey, ECPublicKey>, kid: String = "sig-1", alg: JWSAlgorithm = JWSAlgorithm.ES256,
        iss: String = "aron", aud: List<String> = listOf("aron-api"), exp: Instant = Instant.now().plusSeconds(600),
        flv: String = "sr", dvu: String? = "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10",
    ): String {
        val c = JWTClaimsSet.Builder().issuer(iss).audience(aud).subject("42").expirationTime(Date.from(exp))
            .jwtID("j1").claim("uname", "sr1").claim("role", "SR").claim("sv", 1L).claim("flv", flv)
            .apply { if (dvu != null) claim("did", 7L).claim("dvu", dvu) }.build()
        val jwt = SignedJWT(JWSHeader.Builder(alg).keyID(kid).build(), c)
        jwt.sign(ECDSASigner(keys.first)); return jwt.serialize()
    }

    @Test
    fun verifierRejectsBadTokens() {
        val pair = ecPair()
        val v = AccessTokenVerifier(JwtKeys(pair.first, "sig-1"))
        v.verify(token(pair), setOf(Audience.API)) // sanity
        val other = ecPair()
        val bad = mapOf(
            "foreign key" to token(other),
            "unknown kid" to token(pair, kid = "sig-9"),
            "wrong iss" to token(pair, iss = "evil"),
            "wrong aud" to token(pair, aud = listOf("aron-upload")),
            "two auds" to token(pair, aud = listOf("aron-api", "aron-upload")),
            "expired" to token(pair, exp = Instant.now().minusSeconds(1)),
        )
        for ((why, t) in bad) assertFailsWith<ApiProblem>(why) { v.verify(t, setOf(Audience.API)) }
        assertFailsWith<ApiProblem>("alg none") { v.verify("eyJhbGciOiJub25lIn0.eyJpc3MiOiJhcm9uIn0.", setOf(Audience.API)) }
    }

    @Test
    fun phoneFlavourTokenWithoutDeviceUuidIsRejected() {
        // docs/24 s3.2: phones must send X-Device-Id equal to dvu. A phone token without dvu skips device binding.
        val pair = ecPair()
        val v = AccessTokenVerifier(JwtKeys(pair.first, "sig-1"))
        assertFailsWith<ApiProblem> { v.verify(token(pair, flv = "sr", dvu = null), setOf(Audience.API)) }
    }

    @Test
    fun guardRejectsMismatchedDeviceId() = testApplication {
        val pair = ecPair()
        val deps = AuthGuardDeps(AccessTokenVerifier(JwtKeys(pair.first, "sig-1")), { 1L }, RegistryDefaults())
        application {
            installAronPlatform(PlatformContext(config = RegistryDefaults(), generation = { NIL_GENERATION }))
            routing { authenticated(deps) { get("/v1/me") { call.respondText("me") } } }
        }
        val t = token(pair)
        val ok = client.get("/v1/me") { header("Authorization", "Bearer $t"); header("X-Device-Id", "6F1C2B0E-8D1A-4C5E-9F3A-2B7D4E6A8C10") }
        assertEquals(HttpStatusCode.OK, ok.status)
        val wrong = client.get("/v1/me") { header("Authorization", "Bearer $t"); header("X-Device-Id", "00000000-0000-4000-8000-000000000001") }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals("ERR_DEVICE_PROOF_INVALID", code(wrong.bodyAsText()))
        val none = client.get("/v1/me") { header("Authorization", "Bearer $t") }
        assertEquals(HttpStatusCode.Unauthorized, none.status)
    }

    // ---- secrets -----------------------------------------------------------------------------------------------

    @Test
    fun settingsToStringDoesNotLeakADatabasePasswordInTheUrl() {
        val s = Settings.load(mapOf("ARON_ROLE" to "migrate", "ARON_DB_URL" to "jdbc:postgresql://h/aron?user=a&password=hunter2", "ARON_DB_PASSWORD" to "pw-secret"))
        assertTrue(!s.toString().contains("pw-secret"))
        assertTrue(!s.toString().contains("hunter2"), s.toString())
    }
}
