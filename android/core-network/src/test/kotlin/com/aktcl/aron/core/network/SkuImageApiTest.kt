package com.aktcl.aron.core.network

import com.aktcl.aron.contract.ProblemCode
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** PARITY-SPEC s2.6: `GET /v1/sku-images/{asset_id}` carries the phone's token, returns the bytes, and never more than the cap. */
class SkuImageApiTest {
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

    private fun image(bytes: ByteArray) = MockResponse.Builder().code(200).addHeader("X-Aron-Api", "1").addHeader("Content-Type", "image/png")
        .addHeader("ETag", "\"a7\"").body(Buffer().write(bytes)).build()

    @Test fun theBytesComeWithThePhonesTokenFromTheStablePath() = runTest {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
        server.enqueue(image(png))
        val r = api().skuImage("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        assertArrayEquals(png, (r as ApiResult.Success).value)
        val req = server.takeRequest()
        assertEquals("/v1/sku-images/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", req.url.encodedPath)
        assertEquals("Bearer t-full", req.headers["Authorization"])
    }

    @Test fun aBodyOverTheCapIsAFailureNeverAPartialPicture() = runTest {
        server.enqueue(image(ByteArray(2048)))
        assertTrue(api().skuImage("a7", maxBytes = 1024) !is ApiResult.Success)
    }
}
