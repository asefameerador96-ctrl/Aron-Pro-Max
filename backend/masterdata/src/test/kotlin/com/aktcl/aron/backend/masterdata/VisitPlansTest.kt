package com.aktcl.aron.backend.masterdata

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** F-API-020: TSO visit plans as a union by outlet, idempotent by plan_uuid; reach from the token; completion from the Visit Query. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VisitPlansTest {
    private lateinit var env: BackendAdminEnv

    @BeforeAll fun setUp() { env = BackendAdminEnv() }
    @AfterAll fun tearDown() = env.close()

    private fun body(plan: String, date: String, route: String, outlets: List<Long>) =
        """{"plan_uuid":"$plan","plan_date":"$date","route_id":${env.routeIds.getValue(route)},"outlet_ids":[${outlets.joinToString(",")}]}"""

    private fun uuid() = UUID.randomUUID().toString()

    @Test
    fun savesAUnionByOutletReplayChangesNothingAndTheListEqualsTheSeededData() = env.app {
        val outlets = env.outletIds("MIR-SR-D", 6)
        val plan = uuid()
        val r1 = sendA3(HttpMethod.Post, "/v1/visit-plans", env.tok("tso1001"), body(plan, "2026-11-01", "MIR-SR-D", outlets.take(3)))
        assertEquals(HttpStatusCode.Created, r1.status)
        val b1 = r1.bodyAsText()
        // Replay: same status family, same body, one plan row and three outlet rows.
        val r2 = sendA3(HttpMethod.Post, "/v1/visit-plans", env.tok("tso1001"), body(plan, "2026-11-01", "MIR-SR-D", outlets.take(3)))
        assertEquals(HttpStatusCode.OK, r2.status)
        assertEquals(b1, r2.bodyAsText())
        assertEquals(1, env.count("SELECT count(*) FROM app.visit_plan WHERE client_uuid = '$plan'"))
        assertEquals(3, env.count("SELECT count(*) FROM app.visit_plan_outlet WHERE plan_client_uuid = '$plan'"))
        // A second plan uuid for the same TSO, date and route unions into the first plan: 3 + 3 outlets with 1 shared = 5.
        val other = uuid()
        val r3 = sendA3(HttpMethod.Post, "/v1/visit-plans", env.tok("tso1001"), body(other, "2026-11-01", "MIR-SR-D", outlets.drop(2).take(3)))
        assertEquals(HttpStatusCode.Created, r3.status)
        val j3 = r3.objA3()
        assertEquals(plan, j3.strA3("plan_uuid"))
        assertEquals(outlets.take(5).toSet(), j3.getValue("outlets").jsonArray.map { it.jsonObject.getValue("outlet_id").jsonPrimitive.content.toLong() }.toSet())
        assertEquals(0, env.count("SELECT count(*) FROM app.visit_plan WHERE client_uuid = '$other'"))
        // The same union again changes nothing.
        assertEquals(HttpStatusCode.OK, sendA3(HttpMethod.Post, "/v1/visit-plans", env.tok("tso1001"), body(other, "2026-11-01", "MIR-SR-D", outlets.drop(2).take(3))).status)
        assertEquals(5, env.count("SELECT count(*) FROM app.visit_plan_outlet WHERE plan_client_uuid = '$plan'"))
        // List equals the stored data; the planner is the token's user, never the body.
        val list = sendA3(HttpMethod.Get, "/v1/visit-plans?from=2026-11-01&to=2026-11-01", env.tok("tso1001")).objA3()
        assertEquals(1, list.itemsA3().size)
        val item = list.itemsA3().single()
        assertEquals(env.ids.getValue("tso1001").toString(), item.getValue("planner_user_id").jsonPrimitive.content)
        assertEquals(env.routeIds.getValue("MIR-SR-D").toString(), item.getValue("route_id").jsonPrimitive.content)
        assertEquals("pending", item.getValue("outlets").jsonArray.first().jsonObject.getValue("status").jsonPrimitive.content)
        assertTrue(list.getValue("next_cursor") is JsonNull)
    }

    @Test
    fun anOutletBecomesCompletedWhenItsVisitQueryIsSubmitted() = env.app {
        val outlets = env.outletIds("MIR-SR-3F", 2)
        val plan = uuid()
        sendA3(HttpMethod.Post, "/v1/visit-plans", env.tok("tso1001"), body(plan, "2026-11-02", "MIR-SR-3F", outlets))
        val poUuid = env.scalar("SELECT client_uuid FROM app.visit_plan_outlet WHERE plan_client_uuid = '$plan' AND outlet_id = ${outlets[0]}")
        val cu = uuid()
        env.exec(
            "INSERT INTO app.call_assessment (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, kind, visit_plan_outlet_client_uuid, rubric_id, rubric_version, total_score, max_score, delegate_task) " +
                "VALUES ('$cu', '$cu', date '2026-11-02', ${env.ids.getValue("tso1001")}, now(), 1, 'retailer_questionnaire', '$poUuid', 1, 1, 4, 5, false)",
        )
        val item = sendA3(HttpMethod.Get, "/v1/visit-plans?from=2026-11-02&to=2026-11-02", env.tok("tso1001")).objA3().itemsA3().single()
        val st = item.getValue("outlets").jsonArray.associate { it.jsonObject.getValue("outlet_id").jsonPrimitive.content.toLong() to it.jsonObject.getValue("status").jsonPrimitive.content }
        assertEquals("completed", st.getValue(outlets[0])); assertEquals("pending", st.getValue(outlets[1]))
    }

    @Test
    fun validationScopeAndRoleAreEnforced() = env.app {
        val tso = env.tok("tso1001")
        val good = env.outletIds("MIR-SR-D", 2)
        suspend fun post(b: String, t: String = tso) = sendA3(HttpMethod.Post, "/v1/visit-plans", t, b)
        assertEquals(HttpStatusCode.BadRequest, post(body("not-a-uuid", "2026-11-03", "MIR-SR-D", good)).status)
        assertEquals(HttpStatusCode.BadRequest, post(body(uuid(), "2026-13-45", "MIR-SR-D", good)).status)
        assertEquals(HttpStatusCode.BadRequest, post(body(uuid(), "2026-11-03", "MIR-SR-D", emptyList())).status)
        assertEquals(HttpStatusCode.BadRequest, post("""{"plan_uuid":"${uuid()}","plan_date":"2026-11-03","route_id":1,"outlet_ids":[1],"planner_user_id":5}""").status)
        // Outlets of another route than the one named.
        assertEquals(HttpStatusCode.BadRequest, post(body(uuid(), "2026-11-03", "MIR-SR-3F", good)).status)
        // An outlet of a zone outside the TSO's territory is 403 ERR_OUT_OF_SCOPE (same as an unknown outlet).
        val foreign = env.outletIds("OTH-SR-1", 1)
        val denied = post(body(uuid(), "2026-11-03", "OTH-SR-1", foreign))
        assertEquals(HttpStatusCode.Forbidden, denied.status); assertTrue(denied.bodyAsText().contains("ERR_OUT_OF_SCOPE"))
        assertEquals(HttpStatusCode.Forbidden, post(body(uuid(), "2026-11-03", "MIR-SR-D", listOf(999_999L))).status)
        // Wrong roles: an SR, an AMO and a DMO do not write plans; an SR and an AMO do not read them either.
        for (u in listOf("sr1001", "amo1001", "dmo1001", "admin1001")) assertEquals(HttpStatusCode.Forbidden, post(body(uuid(), "2026-11-03", "MIR-SR-D", good), env.tok(u)).status, u)
        for (u in listOf("sr1001", "amo1001")) assertEquals(HttpStatusCode.Forbidden, sendA3(HttpMethod.Get, "/v1/visit-plans", env.tok(u)).status, u)
        assertEquals(HttpStatusCode.Unauthorized, sendA3(HttpMethod.Get, "/v1/visit-plans", null).status)
        // The same plan_uuid under another TSO, or with another date, is a conflict.
        val plan = uuid()
        assertEquals(HttpStatusCode.Created, post(body(plan, "2026-11-04", "MIR-SR-D", good)).status)
        assertEquals(HttpStatusCode.Conflict, post(body(plan, "2026-11-05", "MIR-SR-D", good)).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Get, "/v1/visit-plans?cursor=abc.def", tso).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Get, "/v1/visit-plans?limit=0", tso).status)
    }

    @Test
    fun randomisedReadsNeverLeakAPlanOutsideTheCallersReach() = env.app {
        val rnd = Random(20)
        val tsos = mapOf("tso1001" to "MIR-SR-D", "tso2001" to "OTH-SR-1", "tso3001" to "FAR-SR-1")
        val planDates = (1..6).map { "2027-02-0$it" }
        val created = mutableMapOf<String, MutableSet<String>>() // plan uuid -> planner username
        val owner = mutableMapOf<String, String>()
        for ((tso, route) in tsos) for (date in planDates.shuffled(rnd).take(4)) {
            val plan = uuid()
            val r = sendA3(HttpMethod.Post, "/v1/visit-plans", env.tok(tso), body(plan, date, route, env.outletIds(route, 1)))
            assertEquals(HttpStatusCode.Created, r.status, "$tso: ${r.bodyAsText()}")
            owner[plan] = tso; created.getOrPut(date) { mutableSetOf() } += plan
        }
        // Who may see whose plans: a TSO only own; a DMO the zones of the division; admin everything.
        val sees = mapOf(
            "tso1001" to setOf("tso1001"), "tso2001" to setOf("tso2001"), "tso3001" to setOf("tso3001"),
            "dmo1001" to setOf("tso1001", "tso2001"), "dmo3001" to setOf("tso3001"), "admin1001" to tsos.keys, "support1001" to tsos.keys,
        )
        var refused = 0
        repeat(120) {
            val caller = sees.keys.random(rnd)
            val from = planDates.random(rnd); val to = planDates.filter { it >= from }.random(rnd)
            val planner = if (rnd.nextInt(3) == 0) tsos.keys.random(rnd) else null
            val limit = rnd.nextInt(1, 6)
            val path = "/v1/visit-plans?from=$from&to=$to&limit=$limit&scope_ids=1,2,3&zone_ids=1,2,3" + (planner?.let { "&planner_user_id=${env.ids.getValue(it)}" } ?: "")
            val expected = owner.filter { (plan, who) -> who in sees.getValue(caller) && (planner == null || who == planner) && env.scalar("SELECT plan_date FROM app.visit_plan WHERE client_uuid = '$plan'")!!.let { it in from..to } }.keys
            val got = mutableSetOf<String>()
            var cursor: String? = null
            var pages = 0
            do {
                val r = sendA3(HttpMethod.Get, path + (cursor?.let { "&cursor=$it" } ?: ""), env.tok(caller))
                if (r.status == HttpStatusCode.Forbidden) {
                    refused++
                    assertTrue(planner != null && planner !in sees.getValue(caller), "$caller refused although $planner is in reach")
                    assertTrue(r.bodyAsText().contains("ERR_OUT_OF_SCOPE"))
                    return@repeat
                }
                assertEquals(HttpStatusCode.OK, r.status)
                assertTrue(planner == null || planner in sees.getValue(caller), "$caller got rows for out-of-reach $planner")
                val b = r.objA3()
                assertTrue(b.itemsA3().size <= limit)
                b.itemsA3().forEach { assertTrue(got.add(it.strA3("plan_uuid")), "duplicate across pages") }
                cursor = b.getValue("next_cursor").let { if (it is JsonNull) null else it.jsonPrimitive.content }
                pages++
            } while (cursor != null && pages < 100)
            assertEquals(emptySet(), got - expected, "$caller leaked")
            assertEquals(emptySet(), expected - got, "$caller missing")
        }
        assertTrue(refused > 0)
    }
}
