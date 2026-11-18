package com.aktcl.aron.core.network

import com.aktcl.aron.contract.ProblemCode
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Contract postSyncFigures: the probe goes as a JSON POST with the phone's token; the raw answer comes back; a server without the endpoint is a failure the caller treats as "no answer". */
class FiguresApiTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.close() }
    private val tokens = object : AccessTokenSource {
        override fun currentAccessToken(grant: Grant): String = "t-full"
        override suspend fun refreshAfterUnauthorized(grant: Grant, rejectedToken: String?, code: ProblemCode?) = false
    }
    private fun api() = SyncApi(AronApiClient(ApiOrigin.parse(server.url("/").toString(), allowCleartextLoopback = true), AronApiClient.defaultOkHttp(), ClientIdentity("0.1.0+1") { "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10" }, tokens, null))

    @Test fun theProbeIsPostedAsJsonAndTheRawAnswerComesBack() = runTest {
        val answer = """{"business_date":"2026-11-18","through_date":"2026-11-17","routes":[],"figure_probe":{"in_figure":[],"voided":[]}}"""
        server.enqueue(MockResponse.Builder().code(200).addHeader("X-Aron-Api", "1").addHeader("Content-Type", "application/json").body(answer).build())
        val body = """{"probe":[{"memo_client_uuid":"11111111-1111-4111-8111-111111111111","business_date":"2026-11-10"}]}"""
        assertEquals(answer, (api().figures(body) as ApiResult.Success).value)
        val req = server.takeRequest()
        assertEquals("POST", req.method); assertEquals("/v1/sync/figures", req.url.encodedPath); assertEquals("Bearer t-full", req.headers["Authorization"])
        assertTrue(req.headers["Content-Type"]!!.startsWith("application/json")); assertEquals(body, req.body!!.utf8())
    }

    @Test fun aServerWithoutTheEndpointAnswers404WhichIsNotASuccess() = runTest {
        server.enqueue(MockResponse.Builder().code(404).addHeader("X-Aron-Api", "1").addHeader("Content-Type", "application/problem+json").body("""{"code":"ERR_NOT_FOUND"}""").build())
        assertTrue(api().figures("{}") !is ApiResult.Success)
    }
}
