package com.aktcl.aron.backend.app

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthTest {
    @Test
    fun healthAnswersWithTheApiMarker() = testApplication {
        application { aronApi() }
        val response = client.get("/v1/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("1", response.headers["X-Aron-Api"])
        assertTrue(response.bodyAsText().contains("\"status\":\"ok\""))
    }
}
