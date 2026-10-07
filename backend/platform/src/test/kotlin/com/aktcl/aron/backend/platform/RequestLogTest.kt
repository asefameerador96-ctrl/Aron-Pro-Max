package com.aktcl.aron.backend.platform

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** AUD-REL-08: request log lines carry the route template and pseudonyms, never raw paths or ids (D-136). */
class RequestLogTest {
    @Test
    fun theRequestLineLogsTheRouteTemplateAndNoIdFromThePath() {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger("aron.request") as Logger
        logger.addAppender(appender)
        try {
            testApplication {
                application {
                    installAronPlatform(PlatformContext(config = RegistryDefaults(), generation = { "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10" }, build = "rev-abc123"))
                    routing { route("/v1/admin/users/{id}") { get { call.respondText("ok") } } }
                }
                client.get("/v1/admin/users/334001?username=sr334001") { header("X-App-Version", "1.0.9+9") }
                client.get("/v1/nowhere/334001")
            }
        } finally {
            logger.detachAppender(appender)
        }
        val lines = appender.list.map { Json.parseToJsonElement(it.formattedMessage).jsonObject }
        assertEquals("/v1/admin/users/{id}", lines[0]["route"]!!.jsonPrimitive.content)
        assertEquals("1.0.9+9", lines[0]["app_version"]!!.jsonPrimitive.content)
        assertEquals("rev-abc123", lines[0]["revision"]!!.jsonPrimitive.content)
        assertEquals("(unmatched)", lines[1]["route"]!!.jsonPrimitive.content)
        appender.list.forEach { e -> assertTrue("334001" !in e.formattedMessage, e.formattedMessage) }
    }

    @Test
    fun usersAndPhonesArePseudonymsInTheLine() {
        val line = requestLogLine("r", "GET", "/v1/me", 200, 5, 1001L, "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10", null, "evil\nline", "rev").toString()
        assertTrue("1001" !in line && "6f1c2b0e" !in line, line)
        assertTrue(logPseudonym("user", "1001") in line)
        assertTrue("evil" !in line, "an app version outside the pattern is dropped")
    }

    @Test
    fun theServerLogsOneJsonObjectPerLine() {
        val xml = File(System.getProperty("aron.repoRoot") ?: "../..", "backend/app/src/main/resources/logback.xml").readText()
        assertTrue("ch.qos.logback.classic.encoder.JsonEncoder" in xml)
        assertTrue("<pattern>" !in xml)
    }
}
