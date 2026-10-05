package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.RegistryDefaults
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthReviewTest {
    private val gen = "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10"
    private fun wiring(db: Database?) = Wiring(AronClock.SYSTEM, RegistryDefaults(), db, { gen }, "test-build", {})

    @Test
    fun healthWithANonJsonAcceptStillAnswersOrIsAProblem() = testApplication {
        application { aronApi(wiring(null)) }
        val r = client.get("/v1/health") { header(HttpHeaders.Accept, "text/plain") }
        assertTrue(
            r.status == HttpStatusCode.OK || r.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/problem+json"),
            "status ${r.status}, content-type ${r.headers[HttpHeaders.ContentType]}",
        )
    }

    @Test
    fun headHealthCarriesServerTimeAndRequestId() = testApplication {
        application { aronApi(wiring(null)) }
        val h = client.head("/v1/health")
        assertTrue(h.headers["X-Server-Time"] != null && h.headers["X-Request-Id"] != null)
    }

    @Test
    fun postToHealthIsAProblemWithMarkers() = testApplication {
        application { aronApi(wiring(null)) }
        val r = client.post("/v1/health")
        assertEquals("1", r.headers["X-Aron-Api"])
        assertTrue(r.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/problem+json"))
    }
}
