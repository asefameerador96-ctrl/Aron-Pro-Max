package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthTest {
    private val gen = "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10"
    private fun wiring(db: Database?) = Wiring(AronClock.SYSTEM, RegistryDefaults(), db, { gen }, "test-build", {})

    @Test
    fun healthAnswersWithTheContractShapeAndNeverTouchesTheDatabase() = testApplication {
        application { aronApi(wiring(null)) }
        val response = client.get("/v1/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("1", response.headers["X-Aron-Api"])
        val b = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(setOf("status", "api", "server_time", "generation", "build"), b.keys)
        assertEquals("ok", b["status"]!!.jsonPrimitive.content)
        assertEquals("/v1", b["api"]!!.jsonPrimitive.content)
        assertEquals(gen, b["generation"]!!.jsonPrimitive.content)
        val h = client.head("/v1/health")
        assertEquals(HttpStatusCode.OK, h.status)
        assertEquals("1", h.headers["X-Aron-Api"])
    }

    @Test
    fun readinessIs503WithoutADatabase() = testApplication {
        application { aronApi(wiring(null)) }
        val r = client.get("/v1/health/ready")
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
        assertEquals("5", r.headers["Retry-After"])
        assertTrue(r.bodyAsText().contains("ERR_SERVICE_UNAVAILABLE"))
    }

    @Test
    fun readinessIs200WithTheDatabase() {
        val url = System.getenv("ARON_TEST_PG_URL").orEmpty().ifBlank { error("ARON_TEST_PG_URL is not set") }
        HikariDataSource(HikariConfig().apply { jdbcUrl = url; maximumPoolSize = 2 }).use { ds ->
            testApplication {
                application { aronApi(wiring(Database(ds))) }
                assertEquals(HttpStatusCode.OK, client.get("/v1/health/ready").status)
            }
        }
    }

    @Test
    fun unknownRouteUnderV1IsTheProblemEnvelope() = testApplication {
        application { aronApi(wiring(null)) }
        val r = client.get("/v1/does-not-exist")
        assertEquals(HttpStatusCode.NotFound, r.status)
        val b = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals("ERR_NOT_FOUND", b["code"]!!.jsonPrimitive.content)
        assertEquals(r.headers["X-Request-Id"], b["request_id"]!!.jsonPrimitive.content)
    }
}
