package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.analytics.AggregationWorker
import com.aktcl.aron.backend.analytics.installAdmissionControl
import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.Migrator
import com.aktcl.aron.backend.platform.PlatformContext
import com.aktcl.aron.backend.platform.ServerRole
import com.aktcl.aron.backend.platform.Settings
import com.aktcl.aron.backend.platform.SettingsException
import com.aktcl.aron.backend.platform.installAronPlatform
import com.aktcl.aron.backend.platform.installDrain
import com.aktcl.aron.backend.platform.installRequestIsolation
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ContractInfo
import com.aktcl.aron.contract.ProblemCode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            val code = Database.pool(settings.dbUrl, settings.dbUser, settings.dbPassword, 2, "aron-migrate", connectionTimeoutMs = Migrator.MIGRATE_CONNECTION_TIMEOUT_MS).use { ds ->
                runCatching { Migrator.migrate(ds) }
                    .onSuccess { log.info("migrate: {} migration(s) applied", it) }
                    .onFailure { log.error("migrate failed", it) }
                    .fold({ 0 }, { 1 })
            }
            exitProcess(code)
        }
        ServerRole.API -> {
            val wiring = Wiring.production(settings)
            apiServer(wiring, settings.port).start(wait = true)
        }
        ServerRole.WORKER -> {
            // Jobs registered so far: the aggregation worker (dirty keys into dw, F-SYS-015). Other lanes append theirs.
            val workerDb = Database.fromSettings(settings)
            AggregationWorker(workerDb).start()
            com.aktcl.aron.backend.masterdata.RiskSignalJob(com.aktcl.aron.backend.masterdata.RiskSignalEvaluator(workerDb, com.aktcl.aron.backend.platform.DbServerConfig(workerDb, com.aktcl.aron.backend.platform.RegistryDefaults(settings.env)))).start()
            com.aktcl.aron.backend.sync.RouteDayPlanningJob(workerDb, com.aktcl.aron.backend.platform.DbServerConfig(workerDb, com.aktcl.aron.backend.platform.RegistryDefaults(settings.env))).start()
            com.aktcl.aron.backend.sync.GeoRecheckSweepJob(workerDb).start()
            com.aktcl.aron.backend.sync.RetentionJob(workerDb).start()
            com.aktcl.aron.backend.masterdata.OutletRequestLapseJob(workerDb).start()
            log.info("aron worker started (aggregation, risk signals, route-day planning, geo re-check sweep, retention, outlet request lapse)")
            Thread.currentThread().join()
        }
    }
}

/**
 * The API engine. Ktor's shutdown hook (SIGTERM) stops it with a 3 s grace period and a 25 s hard timeout
 * (AUD-REL-07): readiness turns 503 at once, the calls in flight finish (Drain), then the pools close.
 * [callThreads] is for tests that pin Netty's call group to the production size (one per vCPU).
 */
fun apiServer(w: Wiring, port: Int, callThreads: Int? = null) =
    embeddedServer(
        Netty,
        configure = {
            connectors.add(io.ktor.server.engine.EngineConnectorBuilder().apply { this.port = port })
            shutdownGracePeriod = 3_000
            shutdownTimeout = 25_000
            callThreads?.let { callGroupSize = it }
        },
    ) {
        aronApi(w)
        monitor.subscribe(ApplicationStopped) { runCatching { w.securityStore?.close() }; w.database?.close() }
    }

@Serializable
data class Health(val status: String, val api: String, val server_time: String, val generation: String, val build: String)

/** The API: platform plugins, health, then every context's routes under /v1 (docs/24 s3, s6.2). */
fun Application.aronApi(w: Wiring) {
    installAronPlatform(PlatformContext(w.clock, w.config, w.generation, w.build, w.frontDoorId, cachedGeneration = w.cachedGeneration, securityEvents = w.securityEvents))
    w.admission?.let { installAdmissionControl(it) }
    installDrain(w.drain)
    w.isolation?.let { installRequestIsolation(it) }
    routing {
        route(ContractInfo.API_BASE_PATH) {
            healthRoutes(w)
            w.mount(this)
        }
    }
}

private fun Route.healthRoutes(w: Wiring) {
    // Liveness never touches PostgreSQL (contract getHealth).
    get("/health") { call.respond(Health("ok", ContractInfo.API_BASE_PATH, w.clock.now().wire(), w.cachedGeneration(), w.build)) }
    head("/health") { call.respond(HttpStatusCode.OK) }
    get("/health/ready") {
        if (w.drain.draining) {
            throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "shutting down", retryAfterS = 5, headers = mapOf("Retry-After" to "5"))
        }
        // The ping waits up to 2 s for a connection: on IO, never on the call thread liveness shares (AUD-PERF-02).
        if (withContext(Dispatchers.IO) { w.database?.ping() } != true) {
            throw ApiProblem(ProblemCode.ERR_SERVICE_UNAVAILABLE, "database unavailable", retryAfterS = 5, headers = mapOf("Retry-After" to "5"))
        }
        call.respond(Health("ok", ContractInfo.API_BASE_PATH, w.clock.now().wire(), w.generation(), w.build))
    }
}
