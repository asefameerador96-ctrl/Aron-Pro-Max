package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.analytics.AggregationWorker
import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.Migrator
import com.aktcl.aron.backend.platform.PlatformContext
import com.aktcl.aron.backend.platform.ServerRole
import com.aktcl.aron.backend.platform.Settings
import com.aktcl.aron.backend.platform.SettingsException
import com.aktcl.aron.backend.platform.installAronPlatform
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ContractInfo
import com.aktcl.aron.contract.ProblemCode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("aron.main")

/** One image, three roles chosen by ARON_ROLE (docs/24 s6.1, D24-27): api, worker, migrate. */
fun main() {
    val settings = try {
        Settings.load()
    } catch (e: SettingsException) {
        log.error("configuration error: {}", e.message)
        exitProcess(2)
    }
    when (settings.role) {
        ServerRole.MIGRATE -> {
            val code = Database.pool(settings.dbUrl, settings.dbUser, settings.dbPassword, 2, "aron-migrate").use { ds ->
                runCatching { Migrator.migrate(ds) }
                    .onSuccess { log.info("migrate: {} migration(s) applied", it) }
                    .onFailure { log.error("migrate failed", it) }
                    .fold({ 0 }, { 1 })
            }
            exitProcess(code)
        }
        ServerRole.API -> {
            val wiring = Wiring.production(settings)
            embeddedServer(Netty, port = settings.port) { aronApi(wiring) }.start(wait = true)
        }
        ServerRole.WORKER -> {
            // Jobs registered so far: the aggregation worker (dirty keys into dw, F-SYS-015). Other lanes append theirs.
            AggregationWorker(Database.fromSettings(settings)).start()
            log.info("aron worker started (aggregation)")
            Thread.currentThread().join()
        }
    }
}

@Serializable
data class Health(val status: String, val api: String, val server_time: String, val generation: String, val build: String)

/** The API: platform plugins, health, then every context's routes under /v1 (docs/24 s3, s6.2). */
fun Application.aronApi(w: Wiring) {
    installAronPlatform(PlatformContext(w.clock, w.config, w.generation, w.build, w.frontDoorId))
    routing {
        route(ContractInfo.API_BASE_PATH) {
            healthRoutes(w)
            w.mount(this)
        }
    }
}

private fun Route.healthRoutes(w: Wiring) {
    // Liveness never touches PostgreSQL (contract getHealth).
    get("/health") { call.respond(Health("ok", ContractInfo.API_BASE_PATH, w.clock.now().wire(), w.generation(), w.build)) }
    head("/health") { call.respond(HttpStatusCode.OK) }
    get("/health/ready") {
        if (w.database?.ping() != true) {
            throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "database unavailable", retryAfterS = 5, headers = mapOf("Retry-After" to "5"))
        }
        call.respond(Health("ok", ContractInfo.API_BASE_PATH, w.clock.now().wire(), w.generation(), w.build))
    }
}
