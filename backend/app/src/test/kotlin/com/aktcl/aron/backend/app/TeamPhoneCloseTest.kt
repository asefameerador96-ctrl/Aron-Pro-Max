package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.app.TsoWorld.Companion.json
import com.aktcl.aron.backend.app.TsoWorld.Companion.status
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
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
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Round 1.20.0 (lead reviews of ee82e904 and c3ec4317): My Team (`GET /v1/team/locations`) ignores a phone-made check-out (`closed_by = 'phone'`): neither its fix (the user's newest
 * location) nor its mock flag (`mock_fixes_today`) counts, while the rep's own attendance fixes still do. Own world, because the rows are written straight into attendance_event.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TeamPhoneCloseTest {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private lateinit var world: TsoWorld
    private lateinit var sr: TsoPhone

    @BeforeAll
    fun setUp() {
        world = TsoWorld(repoRoot)
        world.phone("tso1001", "tso")
        sr = world.phone("sr1001", "sr")
    }

    @AfterAll
    fun tearDown() = world.close()

    private fun visit(outlet: Long, route: Long, secondsAgo: Long) = world.record(
        "visit",
        """{"visit_kind":"sr_call","outlet_id":$outlet,"opened_at":"${world.now.get().minusSeconds(secondsAgo)}","sequence_no":1,"planned":true,
            "fix":{"purpose":"visit_open","fix_status":"ok","lat":23.801,"lng":90.36,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
                   "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}},
            "geo":{"verdict":"in_range","distance_m":38.4,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}}""",
        route, cu = java.util.UUID.randomUUID().toString(), secondsAgo = secondsAgo,
    )

    private fun attendance(closedBy: String, lat: Double, mock: Boolean, secondsAgo: Long) = world.exec(
        "INSERT INTO app.attendance_event (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, kind, fix_status, fix_lat, fix_lng, fix_accuracy_m, fix_is_mock, closed_by) " +
            "VALUES (gen_random_uuid(), gen_random_uuid(), DATE '${world.today()}', ${sr.userId}, TIMESTAMPTZ '${world.now.get().minusSeconds(secondsAgo)}', 1, 'check_out', 'ok', $lat, 90.36, 10, $mock, '$closedBy')",
    )

    @Test
    fun aPhoneClosesFixIsNeitherTheNewestLocationNorAMockCount() = world.withApi {
        with(world) {
            val route = one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
            val outlet = one("SELECT id FROM app.outlet WHERE route_id = $route AND status = 'active' ORDER BY id LIMIT 1")
            // The SR's real visit fix (15 minutes ago) puts the SR on the team map with a usable last location.
            val answer = sync(sr, listOf(visit(outlet, route, 900)))
            assertEquals(listOf("accepted"), answer["acks"]!!.jsonArray.map { it.jsonObject.status() }, answer.toString())
            suspend fun me(): kotlinx.serialization.json.JsonObject {
                fresh.db.jdbi.useHandle<Exception> { h -> com.aktcl.aron.backend.analytics.Aggregator.refreshDimensions(h) }
                val r = getWeb("tso1001", "/v1/team/locations")
                assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
                return json(r.bodyAsText())["items"]!!.jsonArray.map { it.jsonObject }.single { it["user_id"]!!.jsonPrimitive.long == sr.userId }
            }
            // A phone close with a NEWER usable fix that is flagged mock: ignored by the last location and by the mock count.
            attendance("phone", 23.999, mock = true, secondsAgo = 300)
            val a = me()
            assertEquals(0, a["mock_fixes_today"]!!.jsonPrimitive.int, a.toString())
            assertEquals(23.801, a["last_fix"]!!.jsonObject["lat"]!!.jsonPrimitive.double, "the phone close's newer fix is not the last location")
            // Control: the same kind of fix as the REP's own check-out counts as a mock fix (and a mock fix is never shown as the last fix).
            attendance("rep", 23.998, mock = true, secondsAgo = 240)
            val b = me()
            assertEquals(1, b["mock_fixes_today"]!!.jsonPrimitive.int, b.toString())
            assertEquals(23.801, b["last_fix"]!!.jsonObject["lat"]!!.jsonPrimitive.double)
        }
    }
}
