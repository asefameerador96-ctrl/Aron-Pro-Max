package com.aktcl.aron.core.system.update

import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.ClientIdentity
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdateApiTest {
    private lateinit var server: MockWebServer
    @Before fun up() { server = MockWebServer(); server.start() }
    @After fun down() { server.close() }
    private fun api() = UpdateApi(AronApiClient(ApiOrigin.parse(server.url("/").toString(), allowCleartextLoopback = true), AronApiClient.defaultOkHttp(), ClientIdentity("1.2.2+11") { "dev" }))

    private fun body(status: String) = """{"update_available":true,"blocked":false,"min_version_code":3,"latest":{"release_id":7,"flavour":"sr","version_name":"1.2.3","version_code":12,"abi":"arm64-v8a","sha256":"${"A".repeat(64)}","size_bytes":25000000,"download_url":"https://cdn.example/sr-12.apk","signing_cert_sha256":"${"b".repeat(64)}","status":"$status","rollout_pct":100,"notes_en":"Fixes","notes_bn":"সমাধান","created_at":"2026-10-07T00:00:00Z","version":1},"prompt_policy":"prompt","wifi_only":true}"""

    @Test fun sendsFlavourCodeAndAbiAndParsesTheRelease() = runTest {
        server.enqueue(MockResponse.Builder().code(200).addHeader("X-Aron-Api", "1").body(body("published")).build())
        val r = api().check("sr", 11, "arm64-v8a") as UpdateCheckResult.Ok
        val req = server.takeRequest()
        assertEquals("/v1/app/update-check", req.url.encodedPath)
        assertEquals("sr", req.url.queryParameter("flavour")); assertEquals("11", req.url.queryParameter("version_code")); assertEquals("arm64-v8a", req.url.queryParameter("abi"))
        assertEquals(12, r.info.latest!!.versionCode)
        assertEquals("a".repeat(64), r.info.latest!!.sha256) // normalised to lower case
        assertEquals(3, r.info.minVersionCode)
    }

    @Test fun aReleaseThatIsNotPublishedIsNeverOffered() = runTest {
        server.enqueue(MockResponse.Builder().code(200).addHeader("X-Aron-Api", "1").body(body("blocked")).build())
        assertNull((api().check("sr", 11, "arm64-v8a") as UpdateCheckResult.Ok).info.latest)
    }

    @Test fun offlineIsUnavailableNotAnError() = runTest {
        server.close()
        assertTrue(api().check("sr", 11, "arm64-v8a") is UpdateCheckResult.Unavailable)
    }
}
