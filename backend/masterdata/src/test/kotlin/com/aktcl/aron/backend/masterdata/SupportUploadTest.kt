package com.aktcl.aron.backend.masterdata

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** F-API-030: a write-only SAS for one size-capped file; idempotent by upload_uuid; no Azure SDK, no secret. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SupportUploadTest {
    private lateinit var env: BackendAdminEnv
    private val device = "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10"
    private val sha = "a".repeat(64)

    @BeforeAll fun setUp() { env = BackendAdminEnv() }
    @AfterAll fun tearDown() = env.close()

    private fun uuid() = UUID.randomUUID().toString()
    private fun req(id: String, bytes: Long = 4096, hash: String = sha, extra: String = "") =
        """{"upload_uuid":"$id","bytes":$bytes,"sha256":"$hash","app_version":"1.4.2","last_sync_at":"2026-10-07T04:00:00.000Z","pending_rows":3$extra}"""

    @Test
    fun issuesAWriteOnlySasAndAReplayReturnsTheSameBlobPathWithAFreshSas() = env.app {
        val id = uuid()
        val h = mapOf("X-Device-Id" to device)
        val r1 = sendA3(HttpMethod.Post, "/v1/support/pda-upload", env.phone("sr1001", device), req(id), h)
        assertEquals(HttpStatusCode.OK, r1.status)
        val j1 = r1.objA3()
        assertEquals(id, j1.strA3("upload_uuid"))
        assertTrue(j1.strA3("blob_path").endsWith("/${env.ids.getValue("sr1001")}/$id.zip.enc"), j1.strA3("blob_path"))
        assertTrue(j1.strA3("upload_url").startsWith("https://blob.test/${j1.strA3("blob_path")}?sp=w&max=4096"))
        val before = env.blob.writes.size
        val j2 = sendA3(HttpMethod.Post, "/v1/support/pda-upload", env.phone("sr1001", device), req(id), h).objA3()
        assertEquals(j1.strA3("blob_path"), j2.strA3("blob_path"))
        assertNotEquals(j1.strA3("upload_url"), j2.strA3("upload_url"))
        assertEquals(before + 1, env.blob.writes.size)
        assertEquals(1, env.count("SELECT count(*) FROM app.support_upload WHERE upload_uuid = '$id'"))
        // The same uuid for another file, or from another user, is a conflict; the stored blob path never changes.
        assertEquals(HttpStatusCode.Conflict, sendA3(HttpMethod.Post, "/v1/support/pda-upload", env.phone("sr1001", device), req(id, hash = "b".repeat(64)), h).status)
        assertEquals(HttpStatusCode.Conflict, sendA3(HttpMethod.Post, "/v1/support/pda-upload", env.phone("amo1001", device), req(id), h).status)
        assertEquals(1, env.count("SELECT count(*) FROM app.support_upload WHERE upload_uuid = '$id'"))
    }

    @Test
    fun theCapComesFromConfigAndAnOversizedFileIs413() = env.app {
        val h = mapOf("X-Device-Id" to device)
        val over = sendA3(HttpMethod.Post, "/v1/support/pda-upload", env.phone("sr1001", device), req(uuid(), bytes = 21L * 1_048_576), h)
        assertEquals(HttpStatusCode.PayloadTooLarge, over.status); assertTrue(over.bodyAsText().contains("ERR_PAYLOAD_TOO_LARGE"))
        assertEquals(HttpStatusCode.OK, sendA3(HttpMethod.Post, "/v1/support/pda-upload", env.phone("sr1001", device), req(uuid(), bytes = 20L * 1_048_576), h).status)
    }

    @Test
    fun aLowerConfiguredCapApplies() = env.app(mapOf("cfg.support.max_upload_mb" to JsonPrimitive(5))) {
        val h = mapOf("X-Device-Id" to device)
        assertEquals(HttpStatusCode.PayloadTooLarge, sendA3(HttpMethod.Post, "/v1/support/pda-upload", env.phone("sr1001", device), req(uuid(), bytes = 5L * 1_048_576 + 1), h).status)
        assertEquals(HttpStatusCode.OK, sendA3(HttpMethod.Post, "/v1/support/pda-upload", env.phone("sr1001", device), req(uuid(), bytes = 5L * 1_048_576), h).status)
    }

    @Test
    fun validationAndTheCallerKind() = env.app {
        val h = mapOf("X-Device-Id" to device)
        val tok = env.phone("tso1001", device)
        suspend fun post(b: String) = sendA3(HttpMethod.Post, "/v1/support/pda-upload", tok, b, h)
        assertEquals(HttpStatusCode.BadRequest, post(req("nope")).status)
        assertEquals(HttpStatusCode.BadRequest, post(req(uuid(), bytes = 0)).status)
        assertEquals(HttpStatusCode.BadRequest, post(req(uuid(), bytes = 104_857_601)).status)
        assertEquals(HttpStatusCode.BadRequest, post(req(uuid(), hash = "xyz")).status)
        assertEquals(HttpStatusCode.BadRequest, post(req(uuid(), extra = ""","unknown":1""")).status)
        assertEquals(HttpStatusCode.BadRequest, post("""{"upload_uuid":"${uuid()}","bytes":10}""").status)
        // A web caller has no device: forbidden. A phone whose X-Device-Id does not match its token is refused before the handler.
        assertEquals(HttpStatusCode.Forbidden, sendA3(HttpMethod.Post, "/v1/support/pda-upload", env.tok("admin1001"), req(uuid())).status)
        assertNotEquals(HttpStatusCode.OK, sendA3(HttpMethod.Post, "/v1/support/pda-upload", tok, req(uuid()), mapOf("X-Device-Id" to uuid())).status)
        assertEquals(HttpStatusCode.Unauthorized, sendA3(HttpMethod.Post, "/v1/support/pda-upload", null, req(uuid()), h).status)
    }
}
