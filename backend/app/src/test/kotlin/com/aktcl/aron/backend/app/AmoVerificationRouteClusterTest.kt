package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.app.TsoWorld.Companion.ack
import com.aktcl.aron.backend.app.TsoWorld.Companion.code
import com.aktcl.aron.backend.app.TsoWorld.Companion.status
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.UUID
import kotlin.test.Test
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

/**
 * D-AMO-FND-13 (M1, L1): the route and cluster an AMO corrected on a verification form (IngestService step 5a, RecordWriter),
 * through the production wiring and the seed (Mirpur zone with amo1001; LKX zone is outside their reach).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AmoVerificationRouteClusterTest {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private lateinit var world: TsoWorld
    private lateinit var amo: TsoPhone

    @BeforeAll
    fun setUp() {
        world = TsoWorld(repoRoot, mirrors = listOf("LKX"))
        amo = world.phone("amo1001", "amo")
    }

    @AfterAll
    fun tearDown() = world.close()

    private fun fix() = """{"purpose":"outlet_verification","fix_status":"ok","lat":23.8069,"lng":90.3687,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
        "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}}"""

    /** A pending outlet request of the Mirpur SR (a fixture row; ingest guards expect sync, so the replica role is set for the insert). */
    private fun pendingRequest(): String = with(world) {
        val req = UUID.randomUUID().toString()
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("SET session_replication_role = replica")
            try {
                val inserted = h.execute(
                    "INSERT INTO app.outlet_change_request (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, request_type, outlet_id, proposed) " +
                        "SELECT '$req', '$req', DATE '${today()}', u.id, o.route_id, now(), 1, 'info', o.id, '{\"name\":\"Verify me\"}'::jsonb " +
                        "FROM app.app_user u, app.outlet o WHERE u.username = 'sr1001' AND o.id = (SELECT min(id) FROM app.outlet WHERE code LIKE 'MIR-%' AND status = 'active')",
                )
                assertEquals(1, inserted, "the fixture request row was inserted")
            } finally {
                h.execute("SET session_replication_role = DEFAULT")
            }
        }
        req
    }

    private fun payload(req: String, extra: String = "") = """{"request_uuid":"$req","decision":"verified","fix":${fix()}$extra}"""

    private fun ids() = with(world) {
        object {
            val mirRoute = one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
            val mirZone = one("SELECT zone_id FROM app.route WHERE id = $mirRoute")
            val mirRoute2 = one("SELECT id FROM app.route WHERE zone_id = $mirZone AND id <> $mirRoute ORDER BY id LIMIT 1")
            val mirCluster = one("SELECT min(id) FROM app.cluster WHERE zone_id = $mirZone")
            val lkxRoute = one("SELECT id FROM app.route WHERE code LIKE 'LKX-SR-%' ORDER BY id LIMIT 1")
            val lkxCluster = one("SELECT min(id) FROM app.cluster WHERE zone_id = (SELECT zone_id FROM app.route WHERE id = $lkxRoute)")
        }
    }

    @Test
    fun aVerificationWithARouteAndClusterOfTheCallersZoneIsStoredOnceAndReplaysKeepOneEvent() = world.withApi {
        with(world) {
            val i = ids(); val req = pendingRequest(); val cu = UUID.randomUUID().toString()
            val rec = record("outlet_request_verification", payload(req, ""","route_id":${i.mirRoute2},"cluster_id":${i.mirCluster}"""), i.mirRoute, cu = cu) // envelope route differs from the payload route
            repeat(3) { assertEquals(if (it == 0) "accepted" else "duplicate", ack(sync(amo, listOf(rec)), cu).status(), "attempt $it") }
            val rows = rows("SELECT route_id, cluster_id, event, via FROM app.outlet_request_event WHERE client_uuid = CAST('$cu' AS uuid)")
            assertEquals(1, rows.size, "a replay of the same client_uuid keeps exactly one event")
            assertEquals(i.mirRoute2, (rows.single()["route_id"] as Number).toLong(), "the payload route is stored, not the envelope's"); assertEquals(i.mirCluster, (rows.single()["cluster_id"] as Number).toLong())
            assertEquals("verified", rows.single()["event"]); assertEquals("device", rows.single()["via"])
        }
    }

    @Test
    fun anUnknownRouteIsRefusedAsUnknownRouteAndAnUnknownClusterOrOneOutsideTheRoutesZoneIsSchemaInvalid() = world.withApi {
        with(world) {
            val i = ids()
            suspend fun send(extra: String): JsonObjectAck {
                val cu = UUID.randomUUID().toString()
                val a = ack(sync(amo, listOf(record("outlet_request_verification", payload(pendingRequest(), extra), i.mirRoute, cu = cu))), cu)
                return JsonObjectAck(cu, a.status(), a.code(), one("SELECT count(*) FROM app.outlet_request_event WHERE client_uuid = CAST('$cu' AS uuid)"))
            }
            val unknownRoute = send(""","route_id":999999999"""); assertEquals("rejected" to "unknown_route", unknownRoute.status to unknownRoute.code); assertEquals(0L, unknownRoute.stored)
            val unknownCluster = send(""","cluster_id":999999999"""); assertEquals(listOf("cluster 999999999"), rows("SELECT detail FROM app.sync_rejected WHERE client_uuid = CAST('${unknownCluster.cu}' AS uuid)").map { it["detail"] }, "step 5a, not the FK fallback"); assertEquals("rejected" to "schema_invalid", unknownCluster.status to unknownCluster.code); assertEquals(0L, unknownCluster.stored)
            val crossZone = send(""","route_id":${i.mirRoute},"cluster_id":${i.lkxCluster}"""); assertEquals("rejected" to "schema_invalid", crossZone.status to crossZone.code); assertEquals(0L, crossZone.stored)
        }
    }

    @Test
    fun anotherZonesRouteIsQuarantinedOutOfReachAndNothingIsStored() = world.withApi {
        with(world) {
            val i = ids(); val cu = UUID.randomUUID().toString(); val reqA = pendingRequest()
            val a = ack(sync(amo, listOf(record("outlet_request_verification", payload(reqA, ""","route_id":${i.lkxRoute}"""), i.mirRoute, cu = cu))), cu)
            assertEquals("quarantined", a.status(), "$a"); assertEquals("scope_out_of_reach", a.code())
            assertEquals(0L, one("SELECT count(*) FROM app.outlet_request_event WHERE client_uuid = CAST('$cu' AS uuid)"))
            val q = rows("SELECT code, detail FROM app.sync_quarantine WHERE client_uuid = CAST('$cu' AS uuid)").single()
            assertEquals("scope_out_of_reach", q["code"]); assertTrue(q["detail"].toString().startsWith("zone "), "the detail names the zone: ${q["detail"]}")
            // read-back as the caller: the request is still pending and shows no verified step
            val backRes = getAs(amo, "/v1/outlet-requests/$reqA"); val back = backRes.bodyAsText()
            assertEquals(io.ktor.http.HttpStatusCode.OK, backRes.status, back)
            assertEquals("pending", com.aktcl.aron.backend.app.TsoWorld.json(back)["status"]!!.jsonPrimitive.content, back)
            assertFalse(back.contains("\"verified\""), back)
            // a cluster alone, of the other zone, is the same
            val cu2 = UUID.randomUUID().toString()
            val b = ack(sync(amo, listOf(record("outlet_request_verification", payload(pendingRequest(), ""","cluster_id":${i.lkxCluster}"""), i.mirRoute, cu = cu2))), cu2)
            assertEquals("quarantined", b.status(), "$b"); assertEquals("scope_out_of_reach", b.code(), "$b")
            assertEquals(0L, one("SELECT count(*) FROM app.outlet_request_event WHERE client_uuid = CAST('$cu2' AS uuid)"))
            val q2 = rows("SELECT code, detail FROM app.sync_quarantine WHERE client_uuid = CAST('$cu2' AS uuid)").single()
            assertEquals("scope_out_of_reach", q2["code"]); assertTrue(q2["detail"].toString().startsWith("zone "), "the cluster-only detail names the zone: ${q2["detail"]}")
        }
    }

    @Test
    fun anEnvelopeLevelRouteIdNeverPopulatesTheEventsRouteColumn() = world.withApi {
        with(world) {
            val i = ids(); val cu = UUID.randomUUID().toString()
            val rec = record("outlet_request_verification", payload(pendingRequest()), i.mirRoute, cu = cu) // envelope route_id set, payload has none
            assertEquals("accepted", ack(sync(amo, listOf(rec)), cu).status())
            val row = rows("SELECT route_id, cluster_id FROM app.outlet_request_event WHERE client_uuid = CAST('$cu' AS uuid)").single()
            assertNull(row["route_id"], "the form changed nothing: the column stays null"); assertNull(row["cluster_id"])
        }
    }

    private data class JsonObjectAck(val cu: String, val status: String?, val code: String?, val stored: Long)
}
