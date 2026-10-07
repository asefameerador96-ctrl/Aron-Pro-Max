package com.aktcl.aron.core.sync.push

import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.network.ApiResult
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.ClientIdentity
import com.aktcl.aron.core.network.Problem
import com.aktcl.aron.core.network.ProofStrings
import com.aktcl.aron.core.network.ResponseMeta
import com.aktcl.aron.core.network.TransportFailure
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class PushTokenRegistrarTest {
    private val token = "fcm-token-" + "x".repeat(40)
    private val sent = mutableListOf<String>()
    private val answers = ArrayDeque<ApiResult<Unit>>()
    private val ok: ApiResult<Unit> = ApiResult.Success(Unit, meta())
    private var now = 1_791_000_000_000L
    private val sender = PushTokenSender { t -> sent += t; answers.removeFirstOrNull() ?: ok }
    private val store = PushTokenRegistrar.Store.Memory()
    private val registrar = PushTokenRegistrar(sender, store, { now })

    private fun meta() = ResponseMeta(204, null, null, null, null, null, null, null)
    private fun failure(status: Int) = ApiResult.Failure(status, Problem(status = status, code = "ERR_X"), meta())

    @Test fun oneRegistrationPerUserAndTokenNotOnEveryResume() = runTest {
        assertEquals(TokenOutcome.REGISTERED, registrar.ensure(7, token))
        assertEquals(TokenOutcome.ALREADY, registrar.ensure(7, token))
        assertEquals(TokenOutcome.REGISTERED, registrar.ensure(7, token + "2")) // FCM rotated the token
        assertEquals(2, sent.size)
        assertEquals(TokenOutcome.NO_TOKEN, registrar.ensure(7, null))
    }

    @Test fun aSharedPhoneMovesTheTokenToWhoeverSignedInAndBack() = runTest {
        registrar.ensure(7, token)
        assertEquals(TokenOutcome.REGISTERED, registrar.ensure(8, token))
        // The server gave the token to user 8; user 7 coming back must take it again.
        assertEquals(TokenOutcome.REGISTERED, registrar.ensure(7, token))
        assertEquals(3, sent.size)
    }

    @Test fun offlineAndServerTroubleAreTriedAgainAtTheNextTrigger() = runTest {
        answers += ApiResult.Transport(TransportFailure.OFFLINE)
        answers += failure(503)
        answers += failure(401)
        assertEquals(TokenOutcome.OFFLINE, registrar.ensure(7, token))
        assertEquals(TokenOutcome.FAILED, registrar.ensure(7, token))
        assertEquals(TokenOutcome.FAILED, registrar.ensure(7, token))
        assertEquals(TokenOutcome.REGISTERED, registrar.ensure(7, token))
    }

    @Test fun aRefusalIsNotRepeatedForTwelveHoursOfTrustedTime() = runTest {
        answers += failure(403)
        assertEquals(TokenOutcome.REFUSED, registrar.ensure(7, token))
        now += 11 * 3_600_000L
        assertEquals(TokenOutcome.BACKED_OFF, registrar.ensure(7, token))
        assertEquals(1, sent.size)
        now += 2 * 3_600_000L
        assertEquals(TokenOutcome.REGISTERED, registrar.ensure(7, token))
        // Another user is not held back by the first user's refusal.
        answers += failure(403)
        registrar.ensure(8, token)
        assertEquals(TokenOutcome.REGISTERED, registrar.ensure(9, token))
    }

    @Test fun theApiSendsTheContractBodyWithTheDeviceProofOverItsExactBytes() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().code(204).addHeader("X-Aron-Api", "1").build())
        server.start()
        try {
            val client = AronApiClient(ApiOrigin.parse(server.url("/").toString().trimEnd('/'), allowCleartextLoopback = true), OkHttpClient(), ClientIdentity("1.0.3+10003") { DEVICE })
            val signed = mutableListOf<String>()
            val api = PushTokenApi(client, { s -> signed += s; "p".repeat(86) }, { DEVICE }, "sr", { now })
            val r = api.register(token)
            assertEquals(true, r is ApiResult.Success)
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/v1/devices/me/push-token", request.url.encodedPath)
            val body = request.body!!.toByteArray()
            val json = Json.parseToJsonElement(body.decodeToString()).jsonObject
            assertEquals("fcm", json["provider"]!!.jsonPrimitive.content)
            assertEquals(token, json["token"]!!.jsonPrimitive.content)
            assertEquals("sr", json["app_flavour"]!!.jsonPrimitive.content)
            assertEquals(3, json.size)
            assertEquals("p".repeat(86), request.headers["X-Device-Proof"])
            // docs/24 s8.3: aron-proof-v1, device, <device_uuid>, <METHOD> <path>, <sha256(body)>, <nonce_bucket>.
            assertEquals(
                listOf("aron-proof-v1", "device", DEVICE, "PUT /v1/devices/me/push-token", ProofStrings.sha256Hex(body), (now / 1000 / 300).toString()).joinToString("\n"),
                signed.single(),
            )
        } finally {
            server.close()
        }
    }

    private companion object {
        const val DEVICE = "6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f"
    }
}
