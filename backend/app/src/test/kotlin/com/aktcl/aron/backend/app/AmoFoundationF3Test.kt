package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.app.TsoWorld.Companion.json
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** D-AMO-FND-13 (L2, L7): the date windows of the new AMO reads and the two AMO config keys on the config pull. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AmoFoundationF3Test {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private lateinit var world: TsoWorld
    private lateinit var sr: TsoPhone
    private lateinit var amo: TsoPhone

    @BeforeAll
    fun setUp() {
        world = TsoWorld(repoRoot)
        sr = world.phone("sr1001", "sr")
        amo = world.phone("amo1001", "amo")
    }

    @AfterAll
    fun tearDown() = world.close()

    @Test
    fun teamJourneyIsBoundedToTheLast90DaysAndNeverTheFuture() = world.withApi {
        with(world) {
            val route = one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
            fun q(d: Any) = "/v1/team/journey?user_id=${sr.userId}&route_id=$route&business_date=$d"
            assertEquals(HttpStatusCode.OK, getAs(amo, q(today())).status)
            assertEquals(HttpStatusCode.OK, getAs(amo, q(today().minusDays(90))).also { it.bodyAsText() }.status, "day 90 is inside the window")
            for (d in listOf(today().minusDays(91), today().plusDays(1))) {
                val r = getAs(amo, q(d)); val body = r.bodyAsText()
                assertEquals(HttpStatusCode.BadRequest, r.status, body)
                assertTrue("ERR_VALIDATION" in body && "query.business_date" in body && "out_of_range" in body, body)
            }
            // order: role (SR 403) -> date window (400) -> reach/lookup (404/403): a bad date for an SR that does not exist is a 400, never a 404
            assertEquals(HttpStatusCode.Forbidden, getAs(sr, q(today().minusDays(91))).status)
            assertEquals(HttpStatusCode.BadRequest, getAs(amo, "/v1/team/journey?user_id=987654&route_id=$route&business_date=${today().minusDays(91)}").status)
        }
    }

    @Test
    fun callTargetsAreBoundedToTheCurrentAndPrevious12Months() = world.withApi {
        with(world) {
            val m = YearMonth.from(today())
            assertEquals(HttpStatusCode.OK, getAs(amo, "/v1/supervisor/call-targets?month=${m.minusMonths(12)}").status, "12 months back is allowed")
            for (url in listOf("/v1/supervisor/call-targets?month=$m", "/v1/supervisor/call-targets")) {
                val r = getAs(amo, url); val body = r.bodyAsText()
                assertEquals(HttpStatusCode.OK, r.status, body)
                assertEquals(m.toString(), json(body)["period"]!!.jsonPrimitive.content, "$url answers the current month")
            }
            for (bad in listOf(m.minusMonths(13), m.plusMonths(1))) {
                val r = getAs(amo, "/v1/supervisor/call-targets?month=$bad"); val body = r.bodyAsText()
                assertEquals(HttpStatusCode.BadRequest, r.status, body)
                assertTrue("ERR_VALIDATION" in body && "query.month" in body && "out_of_range" in body, body)
            }
        }
    }

    @Test
    fun theTwoAmoConfigKeysReachTheConfigPullWithTheirDefaults() = world.withApi {
        with(world) {
            val r = getAs(amo, "/v1/sync/bundle")
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            val values = json(r.bodyAsText())["config"]!!.jsonObject["values"]!!.jsonArray.map { it.jsonObject }.associateBy { it["key"]!!.jsonPrimitive.content }
            assertNotNull(values["cfg.amo.joint_call_default_star"], "delivery both reaches the phone"); assertNotNull(values["cfg.amo.price_compliance_tile"])
            assertEquals("1", values.getValue("cfg.amo.joint_call_default_star")["value"]!!.jsonPrimitive.content)
            assertEquals("false", values.getValue("cfg.amo.price_compliance_tile")["value"]!!.jsonPrimitive.content)
            for (k in listOf("cfg.amo.joint_call_default_star", "cfg.amo.price_compliance_tile")) assertEquals("default", values.getValue(k)["scope_type"]!!.jsonPrimitive.content, k)
        }
    }
}
