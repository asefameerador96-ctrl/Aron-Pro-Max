package com.aktcl.aron.backend.auth

import com.aktcl.aron.backend.platform.FreshDb
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals

/** AUD-SEC-01 (backend-core part): the guard's gate refuses an API token whose phone was suspended or revoked. */
class DeviceGateTest {
    private val made = mutableListOf<AuthFixture>()

    @AfterEach
    fun close() { made.forEach { it.close() }; made.clear() }

    private fun AuthFixture.setStatus(status: String) =
        fresh!!.db.jdbi.useHandle<Exception> { h -> h.createUpdate("UPDATE app.device SET status = :s WHERE id = 501").bind("s", status).execute() }

    @Test
    fun aSuspendedOrRevokedPhoneLosesApiAccessBeforeItsTokenExpires() {
        val f = AuthFixture(fresh = FreshDb.create()).also { made += it }
        testApplication {
            application { f.application(this) }
            val at = json(client.login("sr334001", "correct horse 1", f.srDevice).bodyAsText())["access_token"]!!.jsonPrimitive.content
            suspend fun me() = client.get("/v1/me") { bearerAuth(at); header("X-Device-Id", f.srDevice) }
            assertEquals(HttpStatusCode.OK, me().status)
            f.setStatus("suspended")
            assertEquals("ERR_DEVICE_SUSPENDED", json(me().bodyAsText()).code)
            f.setStatus("revoked")
            assertEquals("ERR_DEVICE_REVOKED", json(me().bodyAsText()).code)
            f.setStatus("active")
            assertEquals(HttpStatusCode.OK, me().status)
        }
        assertEquals(2, f.securityLog.count { "\"security_event\":\"device_state_refused\"" in it })
    }
}
