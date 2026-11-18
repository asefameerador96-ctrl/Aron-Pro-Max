package com.aktcl.aron.core.network

import com.aktcl.aron.contract.ProblemCode
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Contract listAsthaTargets: `GET /v1/programmes/astha/targets` carries the quarter, the route and the cursor, with the phone's token; the body is returned raw. */
class AsthaTargetsApiTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.close() }

    private val tokens = object : AccessTokenSource {
        override fun currentAccessToken(grant: Grant): String = "t-full"
        override suspend fun refreshAfterUnauthorized(grant: Grant, rejectedToken: String?, code: ProblemCode?) = false
    }

    private fun api() = SyncApi(
        AronApiClient(ApiOrigin.parse(server.url("/").toString(), allowCleartextLoopback = true), AronApiClient.defaultOkHttp(), ClientIdentity("0.1.0+1") { "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10" }, tokens, null),
    )

    @Test fun theQuarterRouteAndCursorGoInTheQueryAndTheBodyComesBackRaw() = runTest {
        val body = """{"quarter":"2026-Q4","as_of":"2026-11-18T06:00:00Z","items":[],"next_cursor":null}"""
        server.enqueue(MockResponse.Builder().code(200).addHeader("X-Aron-Api", "1").addHeader("Content-Type", "application/json").body(body).build())
        val r = api().listAsthaTargets("2026-Q4", 7L, cursor = "c2")
        assertEquals(body, (r as ApiResult.Success).value)
        val req = server.takeRequest()
        assertEquals("/v1/programmes/astha/targets", req.url.encodedPath)
        assertEquals("2026-Q4", req.url.queryParameter("quarter"))
        assertEquals("7", req.url.queryParameter("route_id"))
        assertEquals("c2", req.url.queryParameter("cursor"))
        assertEquals("Bearer t-full", req.headers["Authorization"])
    }

    @Test fun noCursorOnTheFirstPage() = runTest {
        server.enqueue(MockResponse.Builder().code(200).addHeader("X-Aron-Api", "1").body("{}").build())
        api().listAsthaTargets("2026-Q4", 7L)
        assertNull(server.takeRequest().url.queryParameter("cursor"))
    }

    @Test fun aServerRefusalIsNotASuccess() = runTest {
        server.enqueue(MockResponse.Builder().code(403).addHeader("X-Aron-Api", "1").addHeader("Content-Type", "application/problem+json").body("""{"code":"ERR_OUT_OF_SCOPE"}""").build())
        assertTrue(api().listAsthaTargets("2026-Q4", 99L) !is ApiResult.Success)
    }
}
