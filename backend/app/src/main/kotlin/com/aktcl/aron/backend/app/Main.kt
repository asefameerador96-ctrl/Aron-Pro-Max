package com.aktcl.aron.backend.app

import com.aktcl.aron.contract.ContractInfo
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlin.time.Clock

fun main() {
    when (val role = System.getenv("ARON_ROLE") ?: "api") {
        "api" -> embeddedServer(Netty, port = System.getenv("PORT")?.toInt() ?: 8080, module = Application::aronApi)
            .start(wait = true)
        "worker" -> println("aron worker: placeholder (docs/24 s6.1); the analytics lane adds the job loop")
        else -> error("ARON_ROLE must be api or worker, was $role")
    }
}

@Serializable
data class Health(val status: String, val api: String, val server_time: String)

/** Day-1 skeleton of the API: only GET/HEAD /v1/health. Every lane mounts its routes here (docs/24 s3, s6.1). */
fun Application.aronApi() {
    install(ContentNegotiation) { json() }
    routing {
        route(ContractInfo.API_BASE_PATH) {
            get("/health") {
                call.response.header("X-Aron-Api", "1")
                call.respond(HttpStatusCode.OK, Health("ok", ContractInfo.API_BASE_PATH, Clock.System.now().toString()))
            }
            head("/health") {
                call.response.header("X-Aron-Api", "1")
                call.respond(HttpStatusCode.OK)
            }
        }
    }
}

internal val jsonType: ContentType = ContentType.Application.Json
