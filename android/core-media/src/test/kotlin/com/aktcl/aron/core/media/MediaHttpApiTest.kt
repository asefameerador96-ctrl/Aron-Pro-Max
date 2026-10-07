package com.aktcl.aron.core.media

import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.core.network.AccessTokenSource
import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.ClientIdentity
import com.aktcl.aron.core.network.Grant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The SAS call on the upload grant and the blob PUT without any Aron credential (F-SYS-010). */
class MediaHttpApiTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.close() }

    private val grants = mutableListOf<Grant>()
    private val tokens = object : AccessTokenSource {
        override fun currentAccessToken(grant: Grant): String { grants += grant; return "upload-token" }
        override suspend fun refreshAfterUnauthorized(grant: Grant, rejectedToken: String?, code: ProblemCode?) = false
    }

    private var uploadToken = "upload-1"
    private val refreshed = mutableListOf<String?>()
    private val uploadAuth = object : MediaUploadAuth {
        override suspend fun token() = uploadToken
        override suspend fun refresh(rejected: String?): Boolean { refreshed += rejected; uploadToken = "upload-2"; return true }
    }

    private fun api() = MediaHttpApi(
        AronApiClient(ApiOrigin.parse(server.url("/").toString(), allowCleartextLoopback = true), AronApiClient.defaultOkHttp(), ClientIdentity("1.0+1") { "dev-1" }, tokens),
        AronApiClient.defaultOkHttp(),
        uploadAuth,
    )

    private val id = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val path = "photos/2026-10-07/00000000-0000-4000-8000-000000000007/$id.jpg"

    @Test fun sasUsesTheUploadGrantAndTheContractShape() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("X-Aron-Api", "1").body(
                """{"items":[{"media_uuid":"$id","method":"PUT","upload_url":"https://acct.blob.core.windows.net/media/$path?sv=1&sig=abc","blob_path":"$path","required_headers":{"x-ms-blob-type":"BlockBlob","Content-Type":"image/jpeg"},"expires_at":"2026-10-07T06:15:00Z","already_uploaded":false}]}""",
            ).build(),
        )
        val r = api().sas(listOf(SasItem(id, "force_sale", "a".repeat(64), 1234, "2026-10-07"))) as SasResult.Ok
        assertEquals(path, r.targets.single().blobPath)
        val req = server.takeRequest()
        assertEquals("POST", req.method); assertEquals("/v1/media/sas", req.url.encodedPath)
        // The user's own upload-grant token (it works after logout); the signed-in session's token is never used.
        assertEquals("Bearer upload-1", req.headers["Authorization"])
        assertTrue(grants.isEmpty())
        val item = Json.parseToJsonElement(req.body!!.utf8()).jsonObject["items"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("media_uuid", "purpose", "sha256", "bytes", "business_date", "mime"), item.keys)
        assertEquals("image/jpeg", item["mime"]!!.jsonPrimitive.content)
        assertEquals(1234, item["bytes"]!!.jsonPrimitive.int)
    }

    @Test fun anExpiredUploadTokenIsRefreshedOnceAndTheCallRepeated() = runTest {
        server.enqueue(MockResponse.Builder().code(401).addHeader("X-Aron-Api", "1").body("""{"code":"ERR_TOKEN_EXPIRED"}""").build())
        server.enqueue(MockResponse.Builder().code(200).addHeader("X-Aron-Api", "1").body("""{"items":[]}""").build())
        assertTrue(api().sas(listOf(SasItem(id, "survey", "a".repeat(64), 1, "2026-10-07"))) is SasResult.Ok)
        assertEquals(listOf<String?>("upload-1"), refreshed)
        assertEquals("Bearer upload-1", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer upload-2", server.takeRequest().headers["Authorization"])
    }

    @Test fun noUploadTokenMeansRetryWithoutACall() = runTest {
        val noAuth = MediaHttpApi(
            AronApiClient(ApiOrigin.parse(server.url("/").toString(), allowCleartextLoopback = true), AronApiClient.defaultOkHttp(), ClientIdentity("1.0+1") { "dev-1" }),
            AronApiClient.defaultOkHttp(),
            object : MediaUploadAuth { override suspend fun token(): String? = null; override suspend fun refresh(rejected: String?) = false },
        )
        assertEquals(SasResult.Retry("no_upload_token"), noAuth.sas(listOf(SasItem(id, "survey", "a".repeat(64), 1, "2026-10-07"))))
        assertEquals(0, server.requestCount)
    }

    @Test fun aRedirectFromTheBlobServiceIsNotFollowed() = runTest {
        server.enqueue(MockResponse.Builder().code(307).addHeader("Location", "http://elsewhere.example/x").build())
        assertEquals(PutResult.REJECTED, api().put(target(), byteArrayOf(1)))
        assertEquals(1, server.requestCount)
    }

    @Test fun sasFailuresAreRetryOrRejected() = runTest {
        server.enqueue(MockResponse.Builder().code(503).addHeader("X-Aron-Api", "1").body("{}").build())
        assertTrue(api().sas(listOf(SasItem(id, "survey", "a".repeat(64), 1, "2026-10-07"))) is SasResult.Retry)
        server.enqueue(MockResponse.Builder().code(400).addHeader("X-Aron-Api", "1").body("""{"code":"ERR_VALIDATION"}""").build())
        assertEquals(SasResult.Rejected("ERR_VALIDATION"), api().sas(listOf(SasItem(id, "survey", "a".repeat(64), 1, "2026-10-07"))))
        // A captive-portal page is not the API.
        server.enqueue(MockResponse.Builder().code(200).body("<html/>").build())
        assertTrue(api().sas(listOf(SasItem(id, "survey", "a".repeat(64), 1, "2026-10-07"))) is SasResult.Retry)
    }

    private fun target() = SasTarget(id, server.url("/media/$path?sig=secret").toString(), path, mapOf("x-ms-blob-type" to "BlockBlob", "Content-Type" to "image/jpeg"), false)

    @Test fun thePutCarriesTheBlobHeadersAndNoAronCredential() = runTest {
        server.enqueue(MockResponse.Builder().code(201).build())
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        assertEquals(PutResult.STORED, api().put(target(), jpeg))
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("BlockBlob", req.headers["x-ms-blob-type"])
        assertTrue(req.headers["Content-Type"]!!.startsWith("image/jpeg"))
        assertNull(req.headers["Authorization"]); assertNull(req.headers["X-Device-Id"]); assertNull(req.headers["X-App-Version"])
        assertEquals(jpeg.size, req.body!!.size)
    }

    @Test fun putStatusesMapToRetryExpiredAndRejected() = runTest {
        for ((code, want) in listOf(403 to PutResult.EXPIRED, 503 to PutResult.RETRY, 429 to PutResult.RETRY, 400 to PutResult.REJECTED, 200 to PutResult.STORED)) {
            server.enqueue(MockResponse.Builder().code(code).build())
            assertEquals("HTTP $code", want, api().put(target(), byteArrayOf(1)))
        }
    }

    @Test fun aCleartextBlobUrlIsNeverUsed() = runTest {
        assertEquals(PutResult.REJECTED, api().put(target().copy(uploadUrl = "http://evil.example/x"), byteArrayOf(1)))
    }

    @Test fun anUnreachableBlobServiceIsARetry() = runTest {
        val dead = target().copy(uploadUrl = "http://127.0.0.1:1/x")
        assertEquals(PutResult.RETRY, api().put(dead, byteArrayOf(1)))
    }
}
