package com.aktcl.aron.backend.config

import com.aktcl.aron.backend.platform.AccessTokenVerifier
import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.PlatformContext
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.ScopeVersionLookup
import com.aktcl.aron.backend.platform.installAronPlatform
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Independent checker (F-API-037): tests that refute the config change workflow. Failing tests here are defects. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigRefuteTest {
    private lateinit var env: SeededConfigDb
    private val clock = TestClock()
    private lateinit var svc: ConfigService
    private val admin get() = env.principal("admin1001", Role.ADMIN)
    private val sa1 get() = env.principal("superadmin1001", Role.SUPERADMIN)
    private val sa2 get() = env.principal("superadmin1002", Role.SUPERADMIN)
    private var zones = listOf<Long>()
    private var outletZone = 0L
    private val outletsByZone = mutableMapOf<Long, MutableList<Long>>()
    private val dhaka = ZoneId.of("Asia/Dhaka")

    @BeforeAll
    fun setUp() {
        env = SeededConfigDb()
        svc = ConfigService(env.fresh.db, ConfigResolver(env.fresh.db, clock), clock)
        env.fresh.db.jdbi.useHandle<Exception> { h ->
            h.createQuery("SELECT zone_id, id FROM app.outlet ORDER BY zone_id, id").map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list()
                .forEach { (z, o) -> outletsByZone.getOrPut(z) { mutableListOf() } += o }
        }
        zones = env.fresh.db.jdbi.withHandle<List<Long>, Exception> { h -> h.createQuery("SELECT id FROM app.zone ORDER BY id").mapTo(Long::class.java).list() }.filter { it !in outletsByZone }
        outletZone = outletsByZone.maxByOrNull { it.value.size }!!.key
    }

    @AfterAll fun tearDown() = env.close()

    private var zi = 0
    private fun freshZone(): Long = zones[zi++]
    private fun outletOf(zone: Long): Long = outletsByZone.getValue(zone).removeAt(0)
    private val spare = mutableListOf<Long>()
    private fun freshOutlet(): Long = outletsByZone.getValue(outletZone).removeAt(outletsByZone.getValue(outletZone).size - 1)
    private fun routeOf(outlet: Long): Long? = env.one("SELECT route_id FROM app.outlet WHERE id = $outlet")?.toLong()

    private fun item(key: String, type: String, id: Long, value: JsonElement?, from: Instant? = null, to: Instant? = null) =
        ConfigChangeItemIn(key, type, id, value ?: JsonNull, from?.toString(), to?.toString())
    private fun req(vararg i: ConfigChangeItemIn) = ConfigChangeRequestIn("Refutation check of the config workflow", false, i.toList())

    /** A user-input mistake must surface as an ApiProblem (4xx), never as a raw database exception (500). */
    private fun assertNot500(block: () -> Unit) {
        val e = runCatching(block).exceptionOrNull() ?: return
        assertTrue(e is ApiProblem, "expected an ApiProblem (4xx) but got ${e::class.qualifiedName}: ${e.message?.take(300)}")
    }

    private fun approveC3(vararg i: ConfigChangeItemIn): ConfigChangeDto {
        val c = svc.create(sa1, req(*i), null, null)
        assertEquals("pending_approval", c.status)
        return svc.decide(sa2, c.change_id, ConfigDecisionIn("approve", "checked"), null).also { assertEquals("applied", it.status) }
    }

    // D1: effective_to after the requested effective_from but before "now" (the server clamps from to now) -> CHECK violation -> 500.
    @Test
    fun effectiveToInsideTheGraceMinuteIsA400NotA500() {
        val now = clock.now()
        assertNot500 { svc.create(admin, req(item("cfg.geo.radius_m", "outlet", freshOutlet(), JsonPrimitive(120), now.minusSeconds(30), now.minusSeconds(10))), null, null) }
    }

    // D2: a scheduled C2 change whose effective_to passes before apply_at blows up in applyDue, which every GET resolve/changes calls.
    @Test
    fun scheduledC2WhoseWindowEndsBeforeApplyDoesNotPoisonReads() {
        val route = env.one("SELECT max(id) FROM app.route")!!.toLong()
        // A window that ends before the change would apply is refused at create (400), so it can never poison the tick.
        val e = assertFailsWith<ApiProblem> { svc.create(admin, req(item("cfg.geo.radius_m", "route", route, JsonPrimitive(120), null, clock.now().plusSeconds(300))), null, null) }
        assertEquals(ProblemCode.ERR_VALIDATION, e.code)
        assertNot500 { svc.resolve("cfg.geo.radius_m", "route", route, clock.now()) }
    }

    // D3: removing an outlet override (C1) lets the outlet inherit a zone radius of 500 m: resolved value rises above 150 m -> must be C3.
    @Test
    fun removingAnOverrideThatRaisesTheResolvedRadiusAbove150IsC3() {
        val zone = outletZone; val outlet = outletOf(zone)
        approveC3(item("cfg.geo.radius_m", "zone", zone, JsonPrimitive(500)))
        assertEquals("applied", svc.create(admin, req(item("cfg.geo.radius_m", "outlet", outlet, JsonPrimitive(100))), null, null).status)
        assertEquals(100, svc.resolve("cfg.geo.radius_m", "outlet", outlet, clock.now()).value.jsonPrimitive.content.toInt())
        val r = runCatching { svc.create(admin, req(item("cfg.geo.radius_m", "outlet", outlet, null)), null, null) }
        assertTrue(r.isFailure || r.getOrThrow().risk_class == 3, "removal raised the resolved radius 100 -> 500 m as class ${r.getOrNull()?.risk_class}, status ${r.getOrNull()?.status}")
    }

    // D4: setting an outlet to 200 m where it currently resolves to 300 m (zone) decreases the resolved value: C1, not C3.
    @Test
    fun narrowingBelowTheResolvedRadiusIsNotEscalatedToC3() {
        // A route of its own (not the zone used by the removal test), set to 300 m at route level.
        val avoid = routeOf(outletsByZone.getValue(outletZone).first())
        val outlet = outletsByZone.getValue(outletZone).first { o -> routeOf(o).let { r -> r != null && r != avoid } }
        outletsByZone.getValue(outletZone).remove(outlet)
        approveC3(item("cfg.geo.radius_m", "route", routeOf(outlet)!!, JsonPrimitive(300)))
        val c = svc.create(admin, req(item("cfg.geo.radius_m", "outlet", outlet, JsonPrimitive(200))), null, null)
        assertEquals(1, c.risk_class)
    }

    // D5: cfg.sys.change_freeze_windows is a `list` checked only for size; elements are never validated. A bad value would make
    // every later C3 approval (including the one that fixes it) throw ClassCastException in inFreezeWindow.
    @Test
    fun malformedFreezeWindowsAreRefusedAtWrite() {
        val bad = svc.runCatching { create(sa1, req(item("cfg.sys.change_freeze_windows", "global", 0, JsonArray(listOf(JsonPrimitive("junk"))))), null, null) }
        assertTrue(bad.exceptionOrNull() is ApiProblem, "malformed freeze windows accepted with status ${bad.getOrNull()?.status}")
        val bad2 = svc.runCatching { create(sa1, req(item("cfg.sys.change_freeze_windows", "global", 0, Json.parseToJsonElement("""[{"from":"25:99"}]"""))), null, null) }
        assertTrue(bad2.exceptionOrNull() is ApiProblem, "freeze window without 'to' accepted with status ${bad2.getOrNull()?.status}")
    }

    // D6: the scope node is never checked; a value is stored and versioned for an outlet / zone / role that does not exist.
    @Test
    fun aValueOnANonexistentScopeNodeIsRefused() {
        val r = runCatching { svc.create(admin, req(item("cfg.geo.radius_m", "outlet", 987_654_321L, JsonPrimitive(120))), null, null) }
        assertTrue(r.exceptionOrNull() is ApiProblem, "value stored on a nonexistent outlet: status ${r.getOrNull()?.status}")
    }

    // D7: a C3 future-dated-only key approved after its requested Dhaka midnight is clamped to "now" and applies mid-day.
    @Test
    fun futureDatedKeyApprovedLateNeverAppliesMidDay() {
        val midnight = clock.now().atZone(dhaka).toLocalDate().plusDays(1).atStartOfDay(dhaka).toInstant()
        val c = svc.create(sa1, req(item("cfg.day.business_date_cutoff_time", "global", 0, JsonPrimitive("02:00"), midnight)), null, null)
        assertEquals("pending_approval", c.status)
        val saved = clock.at
        try {
            clock.advance(2 * 86_400)
            val done = svc.decide(sa2, c.change_id, ConfigDecisionIn("approve", "late"), null)
            assertEquals("expired", done.status) // never applied mid-day
            assertTrue(svc.values("cfg.day.business_date_cutoff_time", "global", 0, false, 10, null).items.none { it.value == JsonPrimitive("02:00") })
        } finally { clock.at = saved }
    }

    // D8: two concurrent creates with one Idempotency-Key: the loser must replay the first answer, not hit the unique index (500).
    @Test
    fun concurrentReplayOfOneIdempotencyKeyNeverFails() {
        repeat(5) {
            val key = UUID.randomUUID(); val outlet = freshOutlet()
            val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
            val fs = (1..2).map { pool.submit<Result<ConfigChangeDto>> { go.await(); runCatching { svc.create(admin, req(item("cfg.geo.radius_m", "outlet", outlet, JsonPrimitive(110))), key, null) } } }
            go.countDown()
            val rs = fs.map { it.get() }; pool.shutdown()
            rs.forEach { r -> assertTrue(r.isSuccess, "replay failed: ${r.exceptionOrNull()?.let { e -> e::class.simpleName + ": " + e.message?.take(200) }}") }
            assertEquals(1, rs.map { r -> r.getOrThrow().change_id }.toSet().size)
        }
    }

    // Probe: concurrent commits produce contiguous, unique versions (expected to pass).
    @Test
    fun concurrentCommitsStampContiguousVersions() {
        val before = svc.currentVersion()
        val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1)
        val fs = (1..8).map { val o = freshOutlet(); pool.submit<ConfigChangeDto> { go.await(); svc.create(admin, req(item("cfg.geo.radius_m", "outlet", o, JsonPrimitive(105))), null, null) } }
        go.countDown()
        val vs = fs.map { it.get().config_version!! }.sorted(); pool.shutdown()
        assertTrue(vs.first() > before); assertEquals(vs.toSet().size, vs.size)
        assertEquals(vs.size, env.one("SELECT count(*) FROM app.audit_log WHERE entity = 'cfg_version' AND entity_id::bigint BETWEEN ${vs.first()} AND ${vs.last()}")!!.toInt())
    }
}

/** Over HTTP: contract ConfigChangeItem requires `value`; an omitted value must not be read as "remove the override". */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigRefuteApiTest {
    private lateinit var env: SeededConfigDb
    @BeforeAll fun setUp() { env = SeededConfigDb() }
    @AfterAll fun tearDown() = env.close()

    @Test
    fun omittedValueIsA400NotASilentRemoval() = testApplication {
        val clock = TestClock()
        val cfg = RegistryDefaults(overrides = mapOf("cfg.api.rl.user_per_min" to JsonPrimitive(2000)))
        application {
            installAronPlatform(PlatformContext(clock, cfg, { "00000000-0000-0000-0000-000000000000" }))
            val guard = AuthGuardDeps(AccessTokenVerifier(TestTokens.keys), ScopeVersionLookup { 1 }, cfg)
            routing { route("/v1") { configAdminRoutes(ConfigDeps(ConfigService(env.fresh.db, ConfigResolver(env.fresh.db, clock), clock), guard, clock)) } }
        }
        val admin = TestTokens.web(env.ids.getValue("admin1001"), Role.ADMIN)
        val outlet = env.one("SELECT id FROM app.outlet ORDER BY id LIMIT 1")!!
        val ok = client.post("/v1/admin/config/changes") { bearerAuth(admin); contentType(ContentType.Application.Json); setBody("""{"reason":"Dense market, widen the radius","changes":[{"key":"cfg.geo.radius_m","scope_type":"outlet","scope_id":$outlet,"value":135}]}""") }
        assertEquals(HttpStatusCode.Created, ok.status)
        val noValue = client.post("/v1/admin/config/changes") { bearerAuth(admin); contentType(ContentType.Application.Json); setBody("""{"reason":"Forgot to send the value","changes":[{"key":"cfg.geo.radius_m","scope_type":"outlet","scope_id":$outlet}]}""") }
        val res = Json.parseToJsonElement(client.get("/v1/admin/config/resolve?key=cfg.geo.radius_m&node_type=outlet&node_id=$outlet") { bearerAuth(admin) }.bodyAsText()).jsonObject
        assertEquals(HttpStatusCode.BadRequest, noValue.status, "omitted value answered ${noValue.status}; outlet now resolves at ${res["scope_type"]} = ${res["value"]}")
        assertEquals("ERR_VALIDATION", Json.parseToJsonElement(noValue.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content)
    }
}
