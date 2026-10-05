package com.aktcl.aron.core.network

import com.aktcl.aron.contract.ProblemCode
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class AronApiClientTest {
    private lateinit var server: MockWebServer
    private val deviceUuid = "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10"

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.close() }

    private fun origin() = ApiOrigin.parse(server.url("/").toString(), allowCleartextLoopback = true)

    private fun api(tokens: AccessTokenSource? = null, listener: ApiResponseListener? = null, ok: OkHttpClient = AronApiClient.defaultOkHttp()) =
        AronApiClient(origin(), ok, ClientIdentity("0.1.0+1") { deviceUuid }, tokens, listener)

    private fun apiResponse(code: Int, body: String, vararg headers: Pair<String, String>): MockResponse {
        val b = MockResponse.Builder().code(code).body(body).addHeader("X-Aron-Api", "1")
            .addHeader("X-Server-Time", "2026-10-05T02:12:44.120Z").addHeader("X-Config-Version", "318")
            .addHeader("Content-Type", if (code >= 400) "application/problem+json" else "application/json")
        headers.forEach { (k, v) -> b.addHeader(k, v) }
        return b.build()
    }

    private fun problem(status: Int, code: String) =
        """{"type":"urn:aron:problem:${code.lowercase()}","title":"t","status":$status,"code":"$code","request_id":"$deviceUuid","retryable":false,"brand_new_member":1}"""

    private suspend fun AronApiClient.getText(auth: CallAuth = CallAuth.None) =
        call("/v1/health", auth, build = { get() }, decode = { body, _ -> body })

    @Test
    fun sendsPhoneHeadersAndANewRequestIdPerAttempt() = runTest {
        server.enqueue(apiResponse(200, "{}"))
        server.enqueue(apiResponse(200, "{}"))
        val client = api()
        client.getText()
        client.getText()
        val a = server.takeRequest()
        val b = server.takeRequest()
        assertEquals("0.1.0+1", a.headers["X-App-Version"])
        assertEquals(deviceUuid, a.headers["X-Device-Id"])
        assertNull(a.headers["Authorization"])
        assertTrue(a.headers["Accept-Encoding"]!!.contains("gzip"))
        assertNotEquals(a.headers["X-Request-Id"], b.headers["X-Request-Id"])
    }

    @Test
    fun aResponseWithoutTheMarkerHeaderIsAnEdgeFailureNeverABusinessAnswer() = runTest {
        // A captive portal or WAF page answers 200 with HTML (D24-10).
        server.enqueue(MockResponse.Builder().code(200).body("<html>login to wifi</html>").build())
        server.enqueue(MockResponse.Builder().code(401).body("""{"code":"ERR_AUTH_INVALID_CREDENTIALS"}""").build())
        val client = api()
        assertEquals(ApiResult.Transport(TransportFailure.EDGE_RESPONSE), client.getText())
        assertEquals(ApiResult.Transport(TransportFailure.EDGE_RESPONSE), client.getText())
    }

    @Test
    fun problemsAreParsedLenientlyAndMetaIsReported() = runTest {
        val seen = mutableListOf<ResponseMeta>()
        server.enqueue(apiResponse(403, problem(403, "ERR_AUTH_ACCOUNT_LOCKED"), "Retry-After" to "900"))
        val r = api(listener = { seen += it }).getText() as ApiResult.Failure
        assertEquals(403, r.httpStatus)
        assertEquals(ProblemCode.ERR_AUTH_ACCOUNT_LOCKED, r.problem.problemCode)
        assertEquals(900, r.meta.retryAfterS)
        assertEquals("2026-10-05T02:12:44.120Z", seen.single().serverTime)
        assertEquals(318L, seen.single().configVersion)
    }

    @Test
    fun anUnknownProblemCodeOrABrokenBodyStillGivesAFailure() = runTest {
        server.enqueue(apiResponse(409, """{"code":"ERR_FROM_THE_FUTURE","status":409}"""))
        server.enqueue(apiResponse(500, "not json"))
        val client = api()
        val a = client.getText() as ApiResult.Failure
        assertNull(a.problem.problemCode)
        assertEquals("ERR_FROM_THE_FUTURE", a.problem.code)
        val b = client.getText() as ApiResult.Failure
        assertEquals(500, b.httpStatus)
    }

    @Test
    fun malformedSuccessBodyIsATransportFailure() = runTest {
        server.enqueue(apiResponse(200, "{\"meta\":1}"))
        val r = api().call("/v1/sync/bundle", CallAuth.None, build = { get() }, decode = { b, _ -> WireJson.responses.decodeFromString(BundleHead.serializer(), b) })
        assertEquals(TransportFailure.MALFORMED, (r as ApiResult.Transport).failure)
    }

    @Test
    fun notModifiedIsItsOwnOutcome() = runTest {
        server.enqueue(apiResponse(304, "", "ETag" to "\"2026-10-05:3\""))
        val r = api().getText()
        assertTrue(r is ApiResult.NotModified)
    }

    @Test
    fun expiredTokenIsRefreshedOnceAndTheRequestRepeatedOnce() = runTest {
        val tokens = FakeTokens(refreshTo = "access-2")
        server.enqueue(apiResponse(401, problem(401, "ERR_TOKEN_EXPIRED")))
        server.enqueue(apiResponse(200, "ok"))
        val r = api(tokens).getText(CallAuth.Grant(Grant.FULL))
        assertEquals("ok", (r as ApiResult.Success).value)
        assertEquals("Bearer access-1", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer access-2", server.takeRequest().headers["Authorization"])
        assertEquals(listOf("access-1"), tokens.refreshedFrom)
    }

    @Test
    fun aSecond401AfterRefreshIsReturnedWithoutLooping() = runTest {
        val tokens = FakeTokens(refreshTo = "access-2")
        repeat(3) { server.enqueue(apiResponse(401, problem(401, "ERR_SCOPE_CHANGED"))) }
        val r = api(tokens).getText(CallAuth.Grant(Grant.FULL)) as ApiResult.Failure
        assertEquals(401, r.httpStatus)
        assertEquals(2, server.requestCount)
        assertEquals(1, tokens.refreshedFrom.size)
    }

    @Test
    fun aFailedRefreshReturnsThe401AndOtherCodesNeverRefresh() = runTest {
        val tokens = FakeTokens(refreshTo = null)
        server.enqueue(apiResponse(401, problem(401, "ERR_TOKEN_EXPIRED")))
        server.enqueue(apiResponse(401, problem(401, "ERR_AUTH_REFRESH_REUSED")))
        val client = api(tokens)
        assertEquals(401, (client.getText(CallAuth.Grant(Grant.FULL)) as ApiResult.Failure).httpStatus)
        assertEquals(401, (client.getText(CallAuth.Grant(Grant.FULL)) as ApiResult.Failure).httpStatus)
        assertEquals(1, tokens.refreshedFrom.size)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun bearerTokensAreSentButNeverRefreshed() = runTest {
        val tokens = FakeTokens(refreshTo = "x")
        server.enqueue(apiResponse(401, problem(401, "ERR_TOKEN_EXPIRED")))
        api(tokens).getText(CallAuth.Bearer("bind-1"))
        assertEquals("Bearer bind-1", server.takeRequest().headers["Authorization"])
        assertTrue(tokens.refreshedFrom.isEmpty())
    }

    @Test
    fun offlineAndTimeoutAreClassified() = runTest {
        val port = ServerSocket(0).use { it.localPort }
        val offline = AronApiClient(ApiOrigin.parse("http://127.0.0.1:$port", true), AronApiClient.defaultOkHttp(), ClientIdentity("0.1.0+1") { null })
        assertEquals(TransportFailure.OFFLINE, (offline.getText() as ApiResult.Transport).failure)

        server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.Stall).build())
        val quick = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build()
        assertEquals(TransportFailure.TIMEOUT, (api(ok = quick).getText() as ApiResult.Transport).failure)
    }

    private class FakeTokens(private val refreshTo: String?) : AccessTokenSource {
        var current = "access-1"
        val refreshedFrom = mutableListOf<String?>()
        override fun currentAccessToken(grant: Grant) = current
        override suspend fun refreshAfterUnauthorized(grant: Grant, rejectedToken: String?, code: ProblemCode?): Boolean {
            refreshedFrom += rejectedToken
            return if (refreshTo != null) { current = refreshTo; true } else false
        }
    }
}
