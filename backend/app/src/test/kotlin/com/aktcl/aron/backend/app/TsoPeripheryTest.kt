package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.app.TsoWorld.Companion.json
import com.aktcl.aron.backend.app.TsoWorld.Companion.status
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * tso-manual B3, PER-17 (D-TSO-MAN-34; s8 row 28 "mock chip"): My Team's `mock_fixes_today` on `GET /v1/team/locations`,
 * through the production wiring on the seed: sr1001 visits three outlets of MIR-SR-D from its phone, two of them with
 * a mock location. The TSO's team map shows the SR with `mock_fixes_today` 2 and the last non-mock fix (a mock fix is
 * never the last_fix); a user without mock fixes shows 0.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TsoPeripheryTest {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private lateinit var world: TsoWorld
    private lateinit var tso: TsoPhone
    private lateinit var sr: TsoPhone

    @BeforeAll
    fun setUp() {
        world = TsoWorld(repoRoot)
        tso = world.phone("tso1001", "tso")
        sr = world.phone("sr1001", "sr")
    }

    @AfterAll
    fun tearDown() = world.close()

    private fun visit(outlet: Long, seq: Int, mock: Boolean, lat: Double, route: Long, secondsAgo: Long): JsonObject = world.record(
        "visit",
        """{"visit_kind":"sr_call","outlet_id":$outlet,"opened_at":"${world.now.get().minusSeconds(secondsAgo)}","sequence_no":$seq,"planned":true,
            "fix":{"purpose":"visit_open","fix_status":"ok","lat":$lat,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":$mock,"reused":false,
                   "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":$mock}},
            "geo":{"verdict":"in_range","distance_m":38.4,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}}""",
        route, cu = UUID.randomUUID().toString(), secondsAgo = secondsAgo,
    )

    @Test
    fun theTeamMapCountsTodaysMockFixesAndNeverShowsOneAsTheLastFix() = world.withApi {
        with(world) {
            val route = one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
            val outlets = longs("SELECT id FROM app.outlet WHERE route_id = $route AND status = 'active' ORDER BY id LIMIT 3")
            // The real fix first, then two mock ones later: the newest usable fix stays the first.
            val answer = sync(sr, listOf(visit(outlets[0], 1, false, 23.801, route, 900), visit(outlets[1], 2, true, 23.802, route, 600), visit(outlets[2], 3, true, 23.803, route, 300)))
            assertEquals(List(3) { "accepted" }, answer["acks"]!!.jsonArray.map { it.jsonObject.status() }, answer.toString())
            fresh.db.jdbi.useHandle<Exception> { h -> com.aktcl.aron.backend.analytics.Aggregator.refreshDimensions(h) }
            val r = getWeb("tso1001", "/v1/team/locations")
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            val items = json(r.bodyAsText())["items"]!!.jsonArray.map { it.jsonObject }
            val me = items.single { it["user_id"]!!.jsonPrimitive.long == sr.userId }
            assertEquals(2, me["mock_fixes_today"]!!.jsonPrimitive.int, me.toString())
            val last = me["last_fix"]!!.jsonObject
            assertEquals(23.801, last["lat"]!!.jsonPrimitive.double); assertEquals("visit", last["source"]!!.jsonPrimitive.content)
            items.filter { it["user_id"]!!.jsonPrimitive.long != sr.userId }.forEach { assertEquals(0, it["mock_fixes_today"]!!.jsonPrimitive.int) }
        }
    }
}
