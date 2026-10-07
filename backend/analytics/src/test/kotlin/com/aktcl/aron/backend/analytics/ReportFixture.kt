package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.AccessTokenVerifier
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.PlatformContext
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ReachNode
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.ScopeVersionLookup
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.installAronPlatform
import com.aktcl.aron.contract.Role
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.time.Instant
import java.time.LocalDate

/**
 * The seeded day (seed_day.sql, aggregated by the worker) behind the real report routes, with token-derived reach.
 * Users: 10 ANALYST (national), 11 TSO (zone 1 only), 12 AMO (zone 1), 13 ADMIN, 14 TSO (zone 2 only).
 */
abstract class ReportFixture {
    protected lateinit var fresh: FreshDb
    protected val day: LocalDate = LocalDate.parse("2026-10-04")
    protected var z1 = 0L
    protected var z2 = 0L
    protected val reaches = mutableMapOf<Long, Reach>()
    protected open val handlers: List<ReportHandler> = ReportHandlers.all
    /** Extra SQL run after the seed and before aggregation. */
    protected open val extraSql: String = ""
    /** Extra route groups mounted under /v1 next to the report routes. */
    protected open fun mount(r: io.ktor.server.routing.Route, clock: AronClock, reach: ReachResolver, guard: AuthGuardDeps) {}

    private val config = object : ServerConfig {
        private val d = RegistryDefaults()
        override fun value(key: String): JsonElement = if (key == "cfg.api.rl.user_per_min") JsonPrimitive(5000) else d.value(key)
        override fun configVersion() = 1L
    }

    @BeforeEach
    fun fixtureSetUp() {
        fresh = FreshDb.create()
        fresh.dataSource.connection.use { c ->
            c.createStatement().use { it.execute(javaClass.classLoader.getResourceAsStream("seed_day.sql")!!.bufferedReader().readText()) }
            if (extraSql.isNotBlank()) c.createStatement().use { it.execute(extraSql) }
            for (u in listOf("a1", "a2", "a4", "a5")) c.createStatement().use {
                it.execute("INSERT INTO app.domain_event (event_type, aggregate_type, aggregate_id, business_date, source_client_uuid) VALUES ('memo.created','memo','x','2026-10-04','00000000-0000-4000-8000-0000000000$u')")
            }
        }
        AggregationWorker(fresh.db).also { w -> fresh.db.jdbi.useHandle<Exception> { Aggregator.refreshDimensions(it) }; w.requestRebuild(day); w.runUntilIdle() }
        fresh.db.jdbi.useHandle<Exception> { h ->
            z1 = h.createQuery("SELECT id FROM app.zone WHERE code='Z1'").mapTo(Long::class.java).one(); z2 = h.createQuery("SELECT id FROM app.zone WHERE code='Z2'").mapTo(Long::class.java).one()
            for ((id, role) in listOf(10 to "ANALYST", 11 to "TSO", 12 to "AMO", 13 to "ADMIN", 14 to "TSO"))
                h.execute("INSERT INTO app.app_user (id, username, full_name, role, must_change_password) OVERRIDING SYSTEM VALUE VALUES ($id, 'user$id', 'User $id', '$role', false)")
        }
        reaches[10] = Reach(10, Role.ANALYST, day, true, emptySet(), emptySet(), false, emptyList())
        reaches[11] = Reach(11, Role.TSO, day, false, setOf(z1), emptySet(), false, listOf(ReachNode("zone", z1)))
        reaches[12] = Reach(12, Role.AMO, day, false, setOf(z1), emptySet(), false, listOf(ReachNode("zone", z1)))
        reaches[13] = Reach(13, Role.ADMIN, day, true, emptySet(), emptySet(), false, emptyList())
        reaches[14] = Reach(14, Role.TSO, day, false, setOf(z2), emptySet(), false, listOf(ReachNode("zone", z2)))
    }

    @AfterEach
    fun fixtureTearDown() = fresh.close()

    protected fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val clock = AronClock { Instant.parse("2026-10-04T12:00:00Z") }
        val reach = ReachResolver { uid, _, _, _ -> reaches.getValue(uid) }
        val deps = ReportDeps(fresh.db, ReportEngine(fresh.db, config, clock, handlers), reach, AuthGuardDeps(AccessTokenVerifier(TestTokens.keys), ScopeVersionLookup { 1 }, config), clock)
        application {
            installAronPlatform(PlatformContext(config = RegistryDefaults(), generation = { "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10" }))
            routing { route("/v1") { reportRoutes(deps); mount(this, clock, reach, deps.guard) } }
        }
        block()
    }

    protected fun body(extra: String = "", format: String = "json", size: Int = 500) =
        """{"period":{"date":"2026-10-04"},"output":{"format":"$format","page_size":$size}$extra}"""

    protected suspend fun ApplicationTestBuilder.run(uid: Long, role: Role, key: String, json: String = body(), pii: Boolean = false): HttpResponse =
        client.post("/v1/reports/$key/query") { bearerAuth(TestTokens.web(uid, role, pii = pii)); contentType(ContentType.Application.Json); setBody(json) }

    protected suspend fun HttpResponse.result(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    protected fun JsonObject.rows(): List<JsonObject> = this["rows"]!!.jsonArray.map { it.jsonObject }
    protected fun JsonObject.total(col: String): Long = this["totals"]!!.jsonObject[col]!!.jsonPrimitive.content.toDouble().toLong()
    protected fun JsonObject.str(col: String): String? = this[col]?.jsonPrimitive?.takeIf { it.content != "null" }?.content
    protected fun JsonObject.num(col: String): Double = this[col]!!.jsonPrimitive.content.toDouble()
    protected fun List<JsonObject>.by(col: String, v: String): JsonObject = single { it.str(col) == v }
}
