package com.aktcl.aron.backend.masterdata

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** F-API-020b: who is assigned to a route; SR Not Set is normal for an AMO route; reach comes from the token only. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RouteAssignmentsTest {
    private lateinit var env: BackendAdminEnv

    @BeforeAll fun setUp() { env = BackendAdminEnv() }
    @AfterAll fun tearDown() = env.close()

    @Test
    fun anSrRouteNamesItsSrAndAnAmoRouteShowsSrNotSetAsNormal() = env.app {
        val sr = sendA3(HttpMethod.Get, "/v1/routes/${env.routeIds.getValue("MIR-SR-D")}/assignments", env.tok("tso1001")).objA3()
        assertEquals("sr", sr.strA3("route_kind")); assertEquals("false", sr.getValue("sr_not_set").jsonPrimitive.content)
        val a = sr.itemsA3().single()
        assertEquals("sr1001", a.strA3("username")); assertEquals("primary", a.strA3("kind")); assertEquals("SR", a.strA3("user_role")); assertEquals("2026-01-01", a.strA3("valid_from"))

        val amo = sendA3(HttpMethod.Get, "/v1/routes/${env.routeIds.getValue("MIR-AMO-1")}/assignments", env.tok("dmo1001")).objA3()
        assertEquals("amo", amo.strA3("route_kind")); assertEquals("true", amo.getValue("sr_not_set").jsonPrimitive.content)
        assertEquals(listOf("amo1001"), amo.itemsA3().map { it.strA3("username") })

        // Before the assignment started nothing is assigned; a repeat call is identical (a read).
        val early = sendA3(HttpMethod.Get, "/v1/routes/${env.routeIds.getValue("MIR-SR-D")}/assignments?valid_on=2025-06-01", env.tok("admin1001")).objA3()
        assertEquals(emptyList(), early.itemsA3()); assertEquals("true", early.getValue("sr_not_set").jsonPrimitive.content)
        assertEquals(sr.toString(), sendA3(HttpMethod.Get, "/v1/routes/${env.routeIds.getValue("MIR-SR-D")}/assignments", env.tok("tso1001")).objA3().toString())
    }

    @Test
    fun validationAndRolesAreEnforced() = env.app {
        val route = env.routeIds.getValue("MIR-SR-D")
        for (u in listOf("sr1001", "amo1001")) assertEquals(HttpStatusCode.Forbidden, sendA3(HttpMethod.Get, "/v1/routes/$route/assignments", env.tok(u)).status, u)
        assertEquals(HttpStatusCode.Unauthorized, sendA3(HttpMethod.Get, "/v1/routes/$route/assignments", null).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Get, "/v1/routes/abc/assignments", env.tok("admin1001")).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Get, "/v1/routes/$route/assignments?valid_on=2026-02-30", env.tok("admin1001")).status)
        val denied = sendA3(HttpMethod.Get, "/v1/routes/${env.routeIds.getValue("OTH-SR-1")}/assignments", env.tok("tso1001"))
        assertEquals(HttpStatusCode.Forbidden, denied.status); assertTrue(denied.bodyAsText().contains("ERR_OUT_OF_SCOPE"))
        // An unknown route answers exactly like one outside the reach (no existence leak).
        assertEquals(HttpStatusCode.Forbidden, sendA3(HttpMethod.Get, "/v1/routes/987654/assignments", env.tok("tso1001")).status)
    }

    @Test
    fun randomisedCallersNeverSeeARouteOutsideTheirReach() = env.app {
        val zoneOfRoute = mapOf("MIR-SR-D" to "MIR", "MIR-SR-3F" to "MIR", "MIR-SR-2F" to "MIR", "MIR-AMO-1" to "MIR", "OTH-SR-1" to "OTH", "FAR-SR-1" to "FAR")
        val reach = mapOf(
            "tso1001" to setOf("MIR"), "tso2001" to setOf("OTH"), "tso3001" to setOf("FAR"), "dmo1001" to setOf("MIR", "OTH"), "dmo3001" to setOf("FAR"),
            "admin1001" to setOf("MIR", "OTH", "FAR"), "support1001" to setOf("MIR", "OTH", "FAR"), "superadmin1001" to setOf("MIR", "OTH", "FAR"),
        )
        val rnd = Random(5)
        var refused = 0
        repeat(150) {
            val caller = reach.keys.random(rnd); val route = zoneOfRoute.keys.random(rnd)
            val r = sendA3(HttpMethod.Get, "/v1/routes/${env.routeIds.getValue(route)}/assignments?zone_ids=1,2,3&scope_ids=1", env.tok(caller))
            if (zoneOfRoute.getValue(route) in reach.getValue(caller)) {
                assertEquals(HttpStatusCode.OK, r.status, "$caller $route")
                val j = r.objA3()
                assertTrue(j.itemsA3().all { it.strA3("route_id") == env.routeIds.getValue(route).toString() })
            } else {
                refused++
                assertEquals(HttpStatusCode.Forbidden, r.status, "$caller must not see $route")
                assertTrue(!r.bodyAsText().contains("username"))
            }
        }
        assertTrue(refused > 0)
    }
}
