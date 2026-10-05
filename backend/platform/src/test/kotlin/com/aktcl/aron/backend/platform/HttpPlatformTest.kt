package com.aktcl.aron.backend.platform

import com.aktcl.aron.contract.ProblemCode
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@Serializable
data class Echo(val name: String, val count: Int, val note: String? = null)

class HttpPlatformTest {
    private val uuidV4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    private fun Application.testApp() {
        installAronPlatform(PlatformContext(config = RegistryDefaults(), generation = { "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10" }))
        routing {
            get("/v1/ok") { call.respondText("ok") }
            get("/v1/boom") { error("secret internal detail") }
            get("/v1/big") { call.respond(mapOf("x" to "a".repeat(5000))) }
            post("/v1/echo") { call.respond(call.receiveStrict(Echo.serializer())) }
        }
    }

    private fun run(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication { application { testApp() }; block() }

    @Test
    fun unknownRouteIsAProblemWithTheRequestId() = run {
        val r = client.get("/v1/no-such-route")
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertTrue(r.headers[HttpHeaders.ContentType]!!.startsWith("application/problem+json"))
        assertEquals("1", r.headers["X-Aron-Api"])
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals("ERR_NOT_FOUND", body["code"]!!.jsonPrimitive.content)
        assertEquals("urn:aron:problem:err_not_found", body["type"]!!.jsonPrimitive.content)
        assertEquals(404, body["status"]!!.jsonPrimitive.int)
        assertEquals("/v1/no-such-route", body["instance"]!!.jsonPrimitive.content)
        val rid = body["request_id"]!!.jsonPrimitive.content
        assertTrue(uuidV4.matches(rid))
        assertEquals(rid, r.headers["X-Request-Id"])
        assertEquals("problem.not_found", body["message_key"]!!.jsonPrimitive.content)
        assertFalse(body["retryable"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun everyResponseCarriesTheMarkerHeaders() = run {
        for (path in listOf("/v1/ok", "/v1/nope", "/v1/boom")) {
            val r = client.get(path)
            assertEquals("1", r.headers["X-Aron-Api"], path)
            assertTrue(r.headers["X-Server-Time"]!!.matches(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z""")), path)
            assertEquals("0", r.headers["X-Config-Version"], path)
            assertEquals("6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10", r.headers["X-Server-Generation"], path)
            assertTrue(uuidV4.matches(r.headers["X-Request-Id"]!!), path)
        }
    }

    @Test
    fun clientRequestIdIsEchoedAndAnInvalidOneReplaced() = run {
        val mine = "9b0c1d2e-3f40-4b5c-8d6e-7f8091a2b3c4"
        assertEquals(mine, client.get("/v1/ok") { header("X-Request-Id", mine) }.headers["X-Request-Id"])
        val replaced = client.get("/v1/ok") { header("X-Request-Id", "not-a-uuid") }.headers["X-Request-Id"]!!
        assertNotEquals("not-a-uuid", replaced)
        assertTrue(uuidV4.matches(replaced))
    }

    @Test
    fun unexpectedErrorsAre500WithoutLeakingDetail() = run {
        val r = client.get("/v1/boom")
        assertEquals(HttpStatusCode.InternalServerError, r.status)
        val text = r.bodyAsText()
        assertTrue(text.contains("\"code\":\"ERR_INTERNAL\""))
        assertTrue(text.contains("\"retryable\":true"))
        assertFalse(text.contains("secret internal detail"))
    }

    @Test
    fun strictJsonBodies() = run {
        suspend fun post(body: String, ct: ContentType = ContentType.Application.Json) =
            client.post("/v1/echo") { contentType(ct); setBody(body) }

        val ok = post("""{"name":"a","count":2}""")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertTrue(ok.bodyAsText().contains("\"note\":null"), "required-nullable members are present as null")

        val malformed = post("""{"name":"a",""")
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        assertTrue(malformed.bodyAsText().contains("ERR_MALFORMED_JSON"))

        val unknown = Json.parseToJsonElement(post("""{"name":"a","count":2,"zone_ids":[1,2]}""").bodyAsText()).jsonObject
        assertEquals("ERR_VALIDATION", unknown["code"]!!.jsonPrimitive.content)
        val err = unknown["errors"]!!.jsonArray.single().jsonObject
        assertEquals("/zone_ids", err["pointer"]!!.jsonPrimitive.content)
        assertEquals("unknown_member", err["code"]!!.jsonPrimitive.content)

        val missing = Json.parseToJsonElement(post("""{"name":"a"}""").bodyAsText()).jsonObject
        assertEquals("ERR_VALIDATION", missing["code"]!!.jsonPrimitive.content)
        assertEquals("/count", missing["errors"]!!.jsonArray.single().jsonObject["pointer"]!!.jsonPrimitive.content)

        val wrongType = post("""{"name":"a","count":"two"}""")
        assertEquals(HttpStatusCode.BadRequest, wrongType.status)
        assertTrue(wrongType.bodyAsText().contains("ERR_VALIDATION"))

        assertEquals(HttpStatusCode.UnsupportedMediaType, post("name=a", ContentType.Application.FormUrlEncoded).status)

        val huge = post("""{"name":"${"x".repeat(300 * 1024)}","count":1}""")
        assertEquals(HttpStatusCode.PayloadTooLarge, huge.status)
        assertTrue(huge.bodyAsText().contains("ERR_PAYLOAD_TOO_LARGE"))
    }

    @Test
    fun largeResponsesAreGzipped() = run {
        val r = client.get("/v1/big") { header(HttpHeaders.AcceptEncoding, "gzip") }
        assertEquals("gzip", r.headers[HttpHeaders.ContentEncoding])
        val small = client.get("/v1/ok") { header(HttpHeaders.AcceptEncoding, "gzip") }
        assertEquals(null, small.headers[HttpHeaders.ContentEncoding])
    }

    @Test
    fun everyProblemCodeKeepsItsCatalogueStatus() {
        for (c in ProblemCode.entries) assertEquals(c.httpStatus, ApiProblem(c).status, c.wire)
    }
}
