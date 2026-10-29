package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.app.TsoWorld.Companion.ack
import com.aktcl.aron.backend.app.TsoWorld.Companion.code
import com.aktcl.aron.backend.app.TsoWorld.Companion.json
import com.aktcl.aron.backend.app.TsoWorld.Companion.status
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * tso-manual B3, SP-03 (D-TSO-MAN-35; s8 row 33 "VisitPlanRecordsTest route_id"): the Set Plan records through the
 * production wiring on the seed (tso1001 of Dhaka North with a TSO phone, the Mirpur routes MIR-SR-D, -3F, -2F):
 * 1. A plan for today with outlets of its route is stored with the route chosen in Set Plan (`visit_plan.route_id` from
 *    the envelope, V0137) and listed with that route; the POST stores the posted route too.
 * 2. A plan date before the day it was set is held `business_date_out_of_window`; today and later are stored.
 * 3. An outlet of another route is held `scope_out_of_reach` (by the envelope route, or by the stored plan's route when
 *    the outlet record carries none); an old app's plan without any route is stored and listed with the outlets'
 *    smallest route (`coalesce(p.route_id, min outlet route)`).
 * 4. An SR's plan record is held `scope_out_of_reach`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VisitPlanRecordsTest {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private lateinit var world: TsoWorld
    private lateinit var tso: TsoPhone
    private lateinit var sr: TsoPhone
    private var daily = 0L
    private var threeF = 0L
    private lateinit var dailyOutlets: List<Long>
    private lateinit var threeFOutlets: List<Long>

    @BeforeAll
    fun setUp() {
        world = TsoWorld(repoRoot)
        tso = world.phone("tso1001", "tso")
        sr = world.phone("sr1001", "sr")
        daily = world.one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
        threeF = world.one("SELECT id FROM app.route WHERE code = 'MIR-SR-3F'")
        dailyOutlets = world.longs("SELECT id FROM app.outlet WHERE route_id = $daily AND status = 'active' ORDER BY id LIMIT 6")
        threeFOutlets = world.longs("SELECT id FROM app.outlet WHERE route_id = $threeF AND status = 'active' ORDER BY id LIMIT 2")
    }

    @AfterAll
    fun tearDown() = world.close()

    private fun uuid() = UUID.randomUUID().toString()

    /** A plan and its outlet records as the TSO app queues them (outlets in the plan's family, rank 1). */
    private fun plan(date: String, route: Long?, outlets: List<Long>, outletRoute: Long? = route, planUuid: String = uuid()): Pair<String, List<JsonObject>> = with(world) {
        planUuid to listOf(record("visit_plan", """{"plan_date":"$date","note":null}""", routeId = route, cu = planUuid)) +
            outlets.map { o -> record("visit_plan_outlet", """{"plan_client_uuid":"$planUuid","outlet_id":$o}""", routeId = outletRoute, family = planUuid, rank = 1) }
    }

    private suspend fun HttpClient.send(p: TsoPhone, records: List<JsonObject>): List<JsonObject> = with(world) {
        val r = sync(p, records)
        records.map { ack(r, it["client_uuid"]!!.jsonPrimitive.content) }
    }

    @Test
    fun aPlanKeepsItsRouteAndOutletsOfTheRouteAreStored() = world.withApi {
        val (pu, recs) = plan("2027-01-03", daily, dailyOutlets.take(3))
        assertEquals(List(4) { "accepted" }, send(tso, recs).map { it.status() })
        assertEquals(daily, world.one("SELECT route_id FROM app.visit_plan WHERE client_uuid = '$pu'"), "V0137: the route chosen in Set Plan")
        val items = with(world) { json(getAs(tso, "/v1/visit-plans?from=2027-01-03&to=2027-01-03").bodyAsText()) }["items"]!!.jsonArray.map { it.jsonObject }
        val mine = items.single { it["plan_uuid"]!!.jsonPrimitive.content == pu }
        assertEquals(daily, mine["route_id"]!!.jsonPrimitive.long)
        assertEquals(dailyOutlets.take(3), mine["outlets"]!!.jsonArray.map { it.jsonObject["outlet_id"]!!.jsonPrimitive.long })
        assertTrue(mine["outlets"]!!.jsonArray.all { it.jsonObject["status"]!!.jsonPrimitive.content == "pending" })
        // The POST path stores the posted route as well.
        val posted = uuid()
        val r = with(world) { postAs(tso, "/v1/visit-plans", """{"plan_uuid":"$posted","plan_date":"2027-01-04","route_id":$threeF,"outlet_ids":${threeFOutlets}}""") }
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        assertEquals(threeF, world.one("SELECT route_id FROM app.visit_plan WHERE client_uuid = '$posted'"))
    }

    @Test
    fun aPlanDateBeforeTheDayItWasSetIsHeldTodayAndLaterAreStored() = world.withApi {
        val (past, pastRecs) = plan("2027-01-02", daily, emptyList())
        val a = send(tso, pastRecs).single()
        assertEquals("quarantined" to "business_date_out_of_window", a.status() to a.code())
        assertEquals(0L, world.one("SELECT count(*) FROM app.visit_plan WHERE client_uuid = '$past'"))
        assertEquals(listOf("accepted"), send(tso, plan("2027-01-03", daily, emptyList()).second).map { it.status() })
        assertEquals(listOf("accepted"), send(tso, plan("2027-02-20", daily, emptyList()).second).map { it.status() })
    }

    @Test
    fun anOutletOfAnotherRouteIsHeldAndAnOldAppsPlanUsesTheOutletsRoute() = world.withApi {
        // By the envelope route.
        val (_, recs) = plan("2027-01-05", daily, listOf(dailyOutlets[3], threeFOutlets[0]))
        val acks = send(tso, recs)
        assertEquals(listOf("accepted", "accepted", "quarantined"), acks.map { it.status() })
        assertEquals("scope_out_of_reach", acks[2].code())
        // By the stored plan's route when the outlet record carries no route.
        val (pu, recs2) = plan("2027-01-06", daily, listOf(dailyOutlets[4], threeFOutlets[1]), outletRoute = null)
        val acks2 = send(tso, recs2)
        assertEquals(listOf("accepted", "accepted", "quarantined"), acks2.map { it.status() }, acks2.toString())
        assertEquals(1L, world.one("SELECT count(*) FROM app.visit_plan_outlet WHERE plan_client_uuid = '$pu'"))
        // An app before V0137: no route anywhere. Stored; listed with the outlets' smallest route.
        val (old, oldRecs) = plan("2027-01-07", null, listOf(dailyOutlets[5]))
        assertEquals(listOf("accepted", "accepted"), send(tso, oldRecs).map { it.status() })
        assertNull(world.scalar("SELECT route_id::text FROM app.visit_plan WHERE client_uuid = '$old'"))
        val items = with(world) { json(getAs(tso, "/v1/visit-plans?from=2027-01-07&to=2027-01-07").bodyAsText()) }["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(daily, items.single { it["plan_uuid"]!!.jsonPrimitive.content == old }["route_id"]!!.jsonPrimitive.long)
    }

    @Test
    fun anSrPlanIsHeld() = world.withApi {
        val a = send(sr, plan("2027-01-03", daily, emptyList()).second).single()
        assertEquals("quarantined" to "scope_out_of_reach", a.status() to a.code())
    }
}
