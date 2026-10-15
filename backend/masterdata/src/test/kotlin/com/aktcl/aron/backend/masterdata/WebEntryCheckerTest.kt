package com.aktcl.aron.backend.masterdata

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.toJavaLocalDate
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** T1 checker (docs/29) for F-API-050 / F-API-052: defects found in WebEntry.kt. Each test fails against the code as reviewed. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebEntryCheckerTest {
    private lateinit var env: BackendAdminEnv

    @BeforeAll fun setUp() { env = BackendAdminEnv() }
    @AfterAll fun tearDown() = env.close()

    // wall-clock-ok: the app under test runs on AronClock.SYSTEM, so its Dhaka "today" is the real one
    private val today = com.aktcl.aron.rules.BusinessDate.of(System.currentTimeMillis()).toJavaLocalDate()
    private fun uuid() = UUID.randomUUID().toString()
    private val sku get() = env.scalar("SELECT min(id) FROM app.sku")!!.toLong()
    private val fault get() = env.scalar("SELECT code FROM app.code_list_item WHERE list_key = 'qc_fault_type' AND valid_to IS NULL ORDER BY id LIMIT 1")!!
    private fun zoneOf(route: Long) = env.scalar("SELECT zone_id FROM app.route WHERE id = $route")!!.toLong()

    private fun entry(cu: String, route: Long, calls: Int = 0, cls: String = "{}", why: String? = "Checker test entry reason") =
        """{"client_uuid":"$cu","route_id":$route,"business_date":"$today","lines":[{"sku_id":$sku,"issue_qty_base":50,"return_qty_base":10,"memo_count":8,"class_qty_base":$cls}],"successful_calls":$calls${why?.let { ""","change_reason":"$it"""" } ?: ""}}"""

    /** WebEntry.kt:142 `values.sum()` overflows: two Long.MAX class quantities wrap to -2 and pass "sum <= sale". */
    @Test
    fun classSplitThatOverflowsLongIsRefused() = env.app {
        val route = env.routeIds.getValue("MIR-SR-D")
        val r = sendA3(HttpMethod.Post, "/v1/web-entry/route-day", env.tok("admin1001"), entry(uuid(), route, cls = """{"1":9223372036854775807,"2":9223372036854775807}"""))
        assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
    }

    /**
     * WebEntry.kt:100-102: a planned route_day row (RouteDayPlanning creates it ahead, target_outlets NULL until the first
     * bundle - exactly the dead-phone day web entry exists for) maps NULL to 0, so the fallback to the outlet count never
     * runs and every successful call is refused `above_target`.
     */
    @Test
    fun plannedRouteDayWithoutFrozenTargetFallsBackToTheOutletCount() = env.app {
        val route = env.routeIds.getValue("MIR-SR-D")
        val outlets = env.count("SELECT count(*) FROM app.outlet WHERE route_id = $route AND status = 'active'")
        check(outlets >= 1) { "seed has no active outlets on MIR-SR-D" }
        env.exec("INSERT INTO app.route_day (route_id, business_date, planned) VALUES ($route, DATE '$today', true) ON CONFLICT DO NOTHING")
        val r = sendA3(HttpMethod.Post, "/v1/web-entry/route-day", env.tok("admin1001"), entry(uuid(), route, calls = 1))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
    }

    /** WebEntry.kt:234-236: the QC replay compares only the row count, so the same client_uuid with other rows is "replayed" and the new numbers are silently dropped. */
    @Test
    fun qcReplayWithOtherRowsIsAConflictNotAReplay() = env.app {
        val route = env.routeIds.getValue("MIR-SR-D"); val zone = zoneOf(route); val tso = env.tok("tso1001")
        val cu = uuid()
        fun qc(qty: Int) = """{"client_uuid":"$cu","source":"market","business_date":"$today","zone_id":$zone,"route_id":$route,"rows":[{"sku_id":$sku,"fault_type_code":"$fault","qty_base":$qty}]}"""
        assertEquals(HttpStatusCode.OK, sendA3(HttpMethod.Post, "/v1/web-entry/qc", tso, qc(3)).status)
        val second = sendA3(HttpMethod.Post, "/v1/web-entry/qc", tso, qc(999))
        assertEquals(HttpStatusCode.Conflict, second.status, second.bodyAsText())
    }

    /**
     * WebEntry.kt:163 locks per route-day, so the replay check (167) does not serialise two saves that carry one client_uuid
     * for different routes. The loser hits the client_uuid unique key (23505), which StatusPages answers 500 ERR_INTERNAL.
     * Made deterministic with an uncommitted competing insert.
     */
    @Test
    fun sameClientUuidRacingOnTwoRoutesIsNever500() = env.app {
        val routeA = env.routeIds.getValue("MIR-SR-D"); val routeB = env.routeIds.getValue("OTH-SR-1")
        val cu = uuid(); val admin = env.ids.getValue("admin1001")
        val c = env.seeded.fresh.dataSource.connection
        c.autoCommit = false
        c.createStatement().use { it.execute("INSERT INTO app.web_entry_route_day (client_uuid, route_id, business_date, successful_calls, target_outlets, entered_by) VALUES ('$cu', $routeB, DATE '$today', 0, 0, $admin)") }
        val committer = Thread { Thread.sleep(1500); c.commit(); c.close() }.apply { start() }
        val r = sendA3(HttpMethod.Post, "/v1/web-entry/route-day", env.tok("admin1001"), entry(cu, routeA))
        committer.join()
        assertNotEquals(HttpStatusCode.InternalServerError, r.status, r.bodyAsText())
        assertEquals(HttpStatusCode.Conflict, r.status, r.bodyAsText())
    }

    /**
     * WebEntry.kt:167-172: after a data void (scope web_entry) tombstones the entry, linesOf() skips voided lines, so a
     * browser retry of the very same save is answered 409 "client_uuid already stores a different entry" - false, and
     * not the idempotent replay the contract promises.
     */
    @Test
    fun replayOfAVoidedEntryIsNotCalledADifferentEntry() = env.app {
        val route = env.routeIds.getValue("FAR-SR-1"); val admin = env.tok("admin1001")
        val cu = uuid(); val body = entry(cu, route)
        assertEquals(HttpStatusCode.OK, sendA3(HttpMethod.Post, "/v1/web-entry/route-day", admin, body).status)
        env.exec("UPDATE app.web_entry_route_day SET voided_at = now() WHERE client_uuid = '$cu'")
        env.exec("UPDATE app.web_entry_line SET voided_at = now() WHERE entry_client_uuid = '$cu'")
        val r = sendA3(HttpMethod.Post, "/v1/web-entry/route-day", admin, body)
        assertNotEquals(HttpStatusCode.Conflict, r.status, r.bodyAsText())
    }

    /** Probes for 500s on hostile input (kept as regression guards). */
    @Test
    fun hostileInputNever500() = env.app {
        val route = env.routeIds.getValue("MIR-SR-D"); val zone = zoneOf(route); val admin = env.tok("admin1001")
        val bodies = listOf(
            "/v1/web-entry/route-day" to """{"client_uuid":"${uuid()}","route_id":99999999999999999999,"business_date":"$today","lines":[],"successful_calls":0}""",
            "/v1/web-entry/route-day" to """{"client_uuid":"${uuid()}","route_id":$route,"business_date":"$today","lines":[{"sku_id":$sku,"issue_qty_base":1e30,"return_qty_base":0,"memo_count":0}],"successful_calls":0}""",
            "/v1/web-entry/route-day" to """{"client_uuid":"${uuid()}","route_id":$route,"business_date":"$today","lines":[],"successful_calls":99999999999}""",
            "/v1/web-entry/route-day" to """{"client_uuid":"${uuid()}\u0000","route_id":$route,"business_date":"$today","lines":[],"successful_calls":0}""",
            "/v1/web-entry/route-day" to """{"client_uuid":"${uuid()}","route_id":$route,"business_date":"$today","lines":[{"sku_id":$sku,"issue_qty_base":5,"return_qty_base":0,"memo_count":0,"class_qty_base":{"1\u0000":1}}],"successful_calls":0}""",
            "/v1/web-entry/route-day" to """{"client_uuid":"not-a-uuid","route_id":$route,"business_date":"2026-02-30","lines":[],"successful_calls":0}""",
            "/v1/web-entry/qc" to """{"client_uuid":"${uuid()}","source":"market","business_date":"$today","zone_id":$zone,"route_id":$route,"rows":[{"sku_id":$sku,"fault_type_code":"a\u0000b","qty_base":1}]}""",
            "/v1/web-entry/qc" to """{"client_uuid":"${uuid()}","source":"warehouse","business_date":"$today","zone_id":$zone,"reason":"          x","rows":[{"sku_id":$sku,"fault_type_code":"$fault","qty_base":1}]}""",
            "/v1/web-entry/qc" to """{"client_uuid":"${uuid()}","source":"market","business_date":"$today","zone_id":99999999999,"route_id":$route,"rows":[{"sku_id":$sku,"fault_type_code":"$fault","qty_base":1}]}""",
        )
        for ((path, b) in bodies) {
            val r = sendA3(HttpMethod.Post, path, admin, b)
            assertNotEquals(HttpStatusCode.InternalServerError, r.status, "$b -> ${r.bodyAsText()}")
        }
        for (q in listOf("route_id=abc&business_date=$today", "route_id=$route&business_date=2026-13-01", "route_id=$route&business_date=%00")) {
            val r = sendA3(HttpMethod.Get, "/v1/web-entry/route-day?$q", admin)
            assertNotEquals(HttpStatusCode.InternalServerError, r.status, "$q -> ${r.bodyAsText()}")
        }
    }
}
